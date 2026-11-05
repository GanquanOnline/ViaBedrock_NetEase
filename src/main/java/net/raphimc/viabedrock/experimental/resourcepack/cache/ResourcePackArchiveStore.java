/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack.cache;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArchiveDigest;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.cache.PackAlias;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.DirectoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 精确的原始归档 CAS，加上 single-flight 展开/解密。 */
public final class ResourcePackArchiveStore {

    private static final long MIB = 1024L * 1024L;
    private static final long MAINTENANCE_INTERVAL_MINUTES = 15L;
    private static final long STALE_TEMP_MILLIS = TimeUnit.HOURS.toMillis(1L);
    private static final long ACCESS_TOUCH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10L);
    private static final long BUDGET_EVICTION_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(10L);
    private static final long ALIAS_HISTORY_MAX_ENTRIES = 100_000L;
    private static final int ALIAS_HISTORY_MAX_SAMPLES = 32;
    private static final int TRUSTED_ALIAS_CONFLICT_MAX_TOMBSTONES = 4_096;
    private static final int CANONICAL_PUBLICATION_LOCK_STRIPES = 64;
    private static final byte[] TRUSTED_ALIAS_CONFLICT_DOMAIN =
            "ViaBedrock-TrustedAliasConflict-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final String TRUSTED_ALIAS_CONFLICT_HEADER = "ViaBedrock-TrustedAliasConflict-v1\n";
    private static final String TRUSTED_ALIAS_GLOBAL_QUARANTINE_HEADER =
            "ViaBedrock-TrustedAliasGlobalQuarantine-v1\n";

    private final Path serverPacksRoot;
    private final Path rawRoot;
    private final Path contentRoot;
    private final Path trustedAliasConflictRoot;
    private final Path trustedAliasGlobalQuarantinePath;
    private final ResourcePackWorkScheduler scheduler;
    private final ResourcePackCacheMetrics metrics;
    private final ArchiveLimits archiveLimits;
    private final ConcurrentMap<String, RawFlight> rawInflight = new ConcurrentHashMap<>();
    private final Object rawFlightLock = new Object();
    private final ConcurrentMap<ExpansionKey, CompletableFuture<ResourcePack>> expansionInflight = new ConcurrentHashMap<>();
    private final ConcurrentMap<SourceKey, CompletableFuture<ResourcePack>> sourceInflight = new ConcurrentHashMap<>();
    private final Object archiveInflightMetricsLock = new Object();
    private final FailureBackoff<String> rawFailures;
    private final FailureBackoff<ExpansionKey> expansionFailures;
    private final FailureBackoff<SourceKey> sourceFailures;
    private final ConcurrentMap<ExpansionKey, WeakReference<ResourcePack>> expanded = new ConcurrentHashMap<>();
    private final Cache<PackAlias, AliasContentHistory> aliases;
    private final Cache<TrustedAliasKey, TrustedObservationHistory> trustedAliases;
    private final Set<String> trustedAliasConflictQuarantine = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean trustedAliasGlobalQuarantine = new AtomicBoolean();
    private final Map<ResourcePack, ContentDigest> verifiedPacks = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<FrozenPackBlob, Path> liveCanonicalBlobs = Collections.synchronizedMap(new WeakHashMap<>());
    private final ConcurrentMap<Path, AtomicInteger> activePaths = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, AtomicInteger> activeRawLeases = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, AtomicInteger> activeContentLeases = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, Long> accessTimes = new ConcurrentHashMap<>();
    private final ConcurrentMap<Path, Long> persistedTouches = new ConcurrentHashMap<>();
    private final AtomicBoolean cleanupRunning = new AtomicBoolean();
    private final Object diskMetricsLock = new Object();
    private final Object[] canonicalPublicationLocks = publicationLocks();
    private volatile Runnable pathLoadLeaseAcquiredHook = () -> {
    };
    private volatile Runnable trustedLookupBeforeCommitHook = () -> {
    };
    private volatile Runnable trustedConflictBeforePersistHook = () -> {
    };
    private volatile Runnable sourceHandoffHook = () -> {
    };
    private final long diskBudgetBytes;
    private final long diskIdleMillis;
    private final int trustedAliasConflictMaxTombstones;
    private volatile int trustedAliasConflictTombstoneCount;
    private long archiveDiskBytes;
    private long archiveDiskFiles;
    private long contentDiskBytes;
    private long contentDiskFiles;
    private long tombstoneDiskBytes;
    private long tombstoneDiskFiles;
    private final LongSupplier nanoTime;
    private final LongSupplier currentTimeMillis;

    public ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                                    final ResourcePackCacheMetrics metrics) {
        this(serverPacksFolder, scheduler, metrics, 20_480, 7,
                new ArchiveLimits(2_048L * MIB, 4_096L * MIB, 512L * MIB, 100_000, 200),
                System::nanoTime, System::currentTimeMillis, true, ALIAS_HISTORY_MAX_ENTRIES,
                TRUSTED_ALIAS_CONFLICT_MAX_TOMBSTONES);
    }

    ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                             final ResourcePackCacheMetrics metrics, final LongSupplier nanoTime) {
        this(serverPacksFolder, scheduler, metrics, 20_480, 7,
                new ArchiveLimits(2_048L * MIB, 4_096L * MIB, 512L * MIB, 100_000, 200),
                nanoTime, System::currentTimeMillis, false, ALIAS_HISTORY_MAX_ENTRIES,
                TRUSTED_ALIAS_CONFLICT_MAX_TOMBSTONES);
    }

    public ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                                    final ResourcePackCacheMetrics metrics, final ViaBedrockConfig config) {
        this(serverPacksFolder, scheduler, metrics, config, config.getResourcePackCacheDiskBudgetMiB());
    }

    public ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                                    final ResourcePackCacheMetrics metrics, final ViaBedrockConfig config,
                                    final int diskBudgetMiB) {
        this(serverPacksFolder, scheduler, metrics,
                diskBudgetMiB, config.getResourcePackCacheDiskIdleDays(),
                new ArchiveLimits(
                        (long) config.getResourcePackMaxArchiveMiB() * MIB,
                        (long) config.getResourcePackMaxExpandedMiB() * MIB,
                        (long) config.getResourcePackMaxEntryMiB() * MIB,
                        config.getResourcePackMaxEntries(),
                        config.getResourcePackMaxCompressionRatio()), System::nanoTime,
                System::currentTimeMillis, true, ALIAS_HISTORY_MAX_ENTRIES,
                TRUSTED_ALIAS_CONFLICT_MAX_TOMBSTONES);
    }

    ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                             final ResourcePackCacheMetrics metrics, final ViaBedrockConfig config,
                             final LongSupplier nanoTime) {
        this(serverPacksFolder, scheduler, metrics,
                config.getResourcePackCacheDiskBudgetMiB(), config.getResourcePackCacheDiskIdleDays(),
                new ArchiveLimits(
                        (long) config.getResourcePackMaxArchiveMiB() * MIB,
                        (long) config.getResourcePackMaxExpandedMiB() * MIB,
                        (long) config.getResourcePackMaxEntryMiB() * MIB,
                        config.getResourcePackMaxEntries(),
                        config.getResourcePackMaxCompressionRatio()), nanoTime,
                System::currentTimeMillis, false, ALIAS_HISTORY_MAX_ENTRIES,
                TRUSTED_ALIAS_CONFLICT_MAX_TOMBSTONES);
    }

    ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                             final ResourcePackCacheMetrics metrics, final ViaBedrockConfig config,
                             final LongSupplier nanoTime, final LongSupplier currentTimeMillis) {
        this(serverPacksFolder, scheduler, metrics,
                config.getResourcePackCacheDiskBudgetMiB(), config.getResourcePackCacheDiskIdleDays(),
                new ArchiveLimits(
                        (long) config.getResourcePackMaxArchiveMiB() * MIB,
                        (long) config.getResourcePackMaxExpandedMiB() * MIB,
                        (long) config.getResourcePackMaxEntryMiB() * MIB,
                        config.getResourcePackMaxEntries(),
                        config.getResourcePackMaxCompressionRatio()), nanoTime, currentTimeMillis, false,
                ALIAS_HISTORY_MAX_ENTRIES, TRUSTED_ALIAS_CONFLICT_MAX_TOMBSTONES);
    }

    ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                             final ResourcePackCacheMetrics metrics, final ViaBedrockConfig config,
                             final LongSupplier nanoTime, final LongSupplier currentTimeMillis,
                             final long aliasHistoryMaxEntries) {
        this(serverPacksFolder, scheduler, metrics,
                config.getResourcePackCacheDiskBudgetMiB(), config.getResourcePackCacheDiskIdleDays(),
                new ArchiveLimits(
                        (long) config.getResourcePackMaxArchiveMiB() * MIB,
                        (long) config.getResourcePackMaxExpandedMiB() * MIB,
                        (long) config.getResourcePackMaxEntryMiB() * MIB,
                        config.getResourcePackMaxEntries(),
                        config.getResourcePackMaxCompressionRatio()), nanoTime, currentTimeMillis, false,
                aliasHistoryMaxEntries, TRUSTED_ALIAS_CONFLICT_MAX_TOMBSTONES);
    }

    ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                             final ResourcePackCacheMetrics metrics, final ViaBedrockConfig config,
                             final LongSupplier nanoTime, final LongSupplier currentTimeMillis,
                             final long aliasHistoryMaxEntries, final int trustedAliasConflictMaxTombstones) {
        this(serverPacksFolder, scheduler, metrics,
                config.getResourcePackCacheDiskBudgetMiB(), config.getResourcePackCacheDiskIdleDays(),
                new ArchiveLimits(
                        (long) config.getResourcePackMaxArchiveMiB() * MIB,
                        (long) config.getResourcePackMaxExpandedMiB() * MIB,
                        (long) config.getResourcePackMaxEntryMiB() * MIB,
                        config.getResourcePackMaxEntries(),
                        config.getResourcePackMaxCompressionRatio()), nanoTime, currentTimeMillis, false,
                aliasHistoryMaxEntries, trustedAliasConflictMaxTombstones);
    }

    private ResourcePackArchiveStore(final Path serverPacksFolder, final ResourcePackWorkScheduler scheduler,
                                     final ResourcePackCacheMetrics metrics, final int diskBudgetMiB,
                                     final int diskIdleDays, final ArchiveLimits archiveLimits,
                                     final LongSupplier nanoTime, final LongSupplier currentTimeMillis,
                                     final boolean periodicCleanup, final long aliasHistoryMaxEntries,
                                     final int trustedAliasConflictMaxTombstones) {
        this.serverPacksRoot = serverPacksFolder.toAbsolutePath().normalize();
        this.rawRoot = this.serverPacksRoot.resolve("v2/raw/sha256");
        this.contentRoot = this.serverPacksRoot.resolve("v2/content/sha256");
        this.trustedAliasConflictRoot = this.serverPacksRoot.resolve("v2/trusted-alias-conflicts/sha256");
        this.trustedAliasGlobalQuarantinePath =
                this.serverPacksRoot.resolve("v2/trusted-alias-conflicts/GLOBAL_QUARANTINE");
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.archiveLimits = archiveLimits;
        this.diskBudgetBytes = Math.max(1L, diskBudgetMiB) * MIB;
        this.diskIdleMillis = TimeUnit.DAYS.toMillis(Math.max(1, diskIdleDays));
        this.trustedAliasConflictMaxTombstones = Math.max(1, trustedAliasConflictMaxTombstones);
        this.nanoTime = nanoTime;
        this.currentTimeMillis = currentTimeMillis;
        this.rawFailures = new FailureBackoff<>(nanoTime);
        this.expansionFailures = new FailureBackoff<>(nanoTime);
        this.sourceFailures = new FailureBackoff<>(nanoTime);
        this.aliases = CacheBuilder.newBuilder()
                .maximumSize(Math.max(1L, aliasHistoryMaxEntries))
                .expireAfterAccess(30L, TimeUnit.DAYS)
                .build();
        this.trustedAliases = CacheBuilder.newBuilder()
                .maximumSize(Math.max(1L, aliasHistoryMaxEntries))
                .expireAfterAccess(30L, TimeUnit.DAYS)
                .build();
        try {
            Files.createDirectories(this.rawRoot);
            Files.createDirectories(this.contentRoot);
            Files.createDirectories(this.trustedAliasConflictRoot);
            this.recoverTrustedAliasConflictState();
            this.cleanupManagedFiles(true);
        } catch (IOException e) {
            this.metrics.casDiskCleanupFailure();
            throw new IllegalStateException("Failed to initialize resource pack CAS", e);
        }
        if (periodicCleanup) {
            this.schedulePeriodicCleanup();
        }
    }

    private void cleanupStaleExpansionDirectories(final long now, final boolean startup) throws IOException {
        final long cutoff = now - STALE_TEMP_MILLIS;
        final List<Path> stale;
        try (var paths = Files.list(this.contentRoot)) {
            stale = paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("expand-"))
                    .filter(path -> {
                        try {
                            return (startup || Files.getLastModifiedTime(path).toMillis() < cutoff)
                                    && !this.isActivePath(path);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }
        for (Path path : stale) {
            final DiskUsage deleted = diskUsage(path);
            deleteRecursively(path);
            this.metrics.recordCasDiskCleanup(deleted.bytes(), deleted.files());
        }
    }

    private void schedulePeriodicCleanup() {
        this.scheduler.scheduleIoAtFixedRate(this::runScheduledCleanup,
                MAINTENANCE_INTERVAL_MINUTES, MAINTENANCE_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    private void runScheduledCleanup() {
        if (!this.cleanupRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            this.cleanupNow();
        } catch (IOException ignored) {
        } finally {
            this.cleanupRunning.set(false);
        }
    }

    synchronized void cleanupNow() throws IOException {
        try {
            this.cleanupManagedFiles(false);
        } catch (IOException e) {
            this.metrics.casDiskCleanupFailure();
            throw e;
        }
    }

    private synchronized void cleanupManagedFiles(final boolean startup) throws IOException {
        final long now = this.currentTimeMillis.getAsLong();
        this.aliases.cleanUp();
        this.trustedAliases.cleanUp();
        this.cleanupStaleExpansionDirectories(now, startup);
        this.cleanupStaleTemps(now, startup);

        final Set<Path> liveContentPaths = this.liveContentPaths();
        final long idleCutoff = now - this.diskIdleMillis;
        final List<CacheEntry> entries = this.scanCacheEntries();
        entries.sort(Comparator.comparingLong(CacheEntry::lastAccessMillis));
        long bytes = entries.stream().mapToLong(CacheEntry::size).sum();

        for (CacheEntry entry : entries) {
            if (entry.lastAccessMillis() >= idleCutoff || this.isProtected(entry.path(), liveContentPaths)) {
                continue;
            }
            if (this.deleteCacheEntry(entry)) {
                bytes -= entry.size();
            }
        }
        if (bytes > this.diskBudgetBytes) {
            for (CacheEntry entry : entries) {
                if (bytes <= this.diskBudgetBytes) break;
                if (entry.lastAccessMillis() > now - BUDGET_EVICTION_GRACE_MILLIS
                        || !Files.isRegularFile(entry.path())
                        || this.isProtected(entry.path(), liveContentPaths)) {
                    continue;
                }
                if (this.deleteCacheEntry(entry)) {
                    bytes -= entry.size();
                }
            }
        }
        this.refreshDiskMetrics();
    }

    private void refreshDiskMetrics() throws IOException {
        synchronized (this.diskMetricsLock) {
            long archiveBytes = 0L;
            long archiveFiles = 0L;
            long contentBytes = 0L;
            long contentFiles = 0L;
            for (CacheEntry entry : this.scanCacheEntries()) {
                if (entry.tier() == Tier.ARCHIVE) {
                    archiveBytes += entry.size();
                    archiveFiles++;
                } else if (entry.tier() == Tier.CONTENT) {
                    contentBytes += entry.size();
                    contentFiles++;
                }
            }
            final TombstoneUsage tombstones = this.scanTrustedAliasConflictUsage();
            this.archiveDiskBytes = archiveBytes;
            this.archiveDiskFiles = archiveFiles;
            this.contentDiskBytes = contentBytes;
            this.contentDiskFiles = contentFiles;
            this.tombstoneDiskBytes = tombstones.bytes();
            this.tombstoneDiskFiles = tombstones.files();
            this.trustedAliasConflictTombstoneCount = tombstones.individualFiles();
            if (tombstones.globalQuarantine()) this.trustedAliasGlobalQuarantine.set(true);
            this.publishDiskMetricsLocked();
        }
    }

    private void publishDiskMetricsLocked() {
        this.metrics.setCasDiskUsage(
                this.archiveDiskBytes + this.contentDiskBytes + this.tombstoneDiskBytes,
                this.archiveDiskFiles + this.contentDiskFiles + this.tombstoneDiskFiles);
        // 原始内容和规范内容共用一个磁盘配额；这些上限是同一个约束值，
        // 而不是两个可以相加的独立额度。
        this.metrics.setWeight(Tier.ARCHIVE, this.archiveDiskBytes, this.diskBudgetBytes);
        this.metrics.setWeight(Tier.CONTENT, this.contentDiskBytes, this.diskBudgetBytes);
        this.metrics.setTrustedAliasConflictState(
                this.trustedAliasConflictTombstoneCount, this.tombstoneDiskBytes,
                this.trustedAliasConflictMaxTombstones, this.trustedAliasGlobalQuarantine.get());
    }

    private TombstoneUsage scanTrustedAliasConflictUsage() throws IOException {
        long bytes = 0L;
        long files = 0L;
        int individualFiles = 0;
        final Path root = this.trustedAliasConflictRoot.getParent();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (path.getFileName().toString().endsWith(".tmp")) continue;
                bytes += Files.size(path);
                files++;
                if (path.startsWith(this.trustedAliasConflictRoot)
                        && path.getFileName().toString().endsWith(".conflict")) {
                    individualFiles++;
                }
            }
        }
        return new TombstoneUsage(bytes, files, individualFiles,
                Files.isRegularFile(this.trustedAliasGlobalQuarantinePath));
    }

    private void adjustManagedDiskUsageLocked(final Tier tier, final long byteDelta, final long fileDelta) {
        if (tier == Tier.ARCHIVE) {
            this.archiveDiskBytes = Math.max(0L, this.archiveDiskBytes + byteDelta);
            this.archiveDiskFiles = Math.max(0L, this.archiveDiskFiles + fileDelta);
        } else if (tier == Tier.CONTENT) {
            this.contentDiskBytes = Math.max(0L, this.contentDiskBytes + byteDelta);
            this.contentDiskFiles = Math.max(0L, this.contentDiskFiles + fileDelta);
        }
        this.publishDiskMetricsLocked();
    }

    private void cleanupStaleTemps(final long now, final boolean startup) throws IOException {
        final Path root = this.rawRoot.getParent().getParent();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                final boolean durableTrustedAliasFallback =
                        path.startsWith(this.trustedAliasConflictRoot.getParent())
                                && Files.isRegularFile(this.trustedAliasGlobalQuarantinePath);
                if (!path.getFileName().toString().endsWith(".tmp")
                        || (!startup && !durableTrustedAliasFallback
                        && Files.getLastModifiedTime(path).toMillis() >= now - STALE_TEMP_MILLIS)
                        || this.isProtectedTemp(path)) {
                    continue;
                }
                final long size = Files.size(path);
                if (Files.deleteIfExists(path)) {
                    this.metrics.recordCasDiskCleanup(size, 1L);
                }
            }
        }
    }

    private List<CacheEntry> scanCacheEntries() throws IOException {
        final Path root = this.rawRoot.getParent().getParent();
        final List<CacheEntry> entries = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                final String name = path.getFileName().toString();
                final Tier tier;
                if (path.startsWith(this.rawRoot) && name.endsWith(".mcpack")) {
                    tier = Tier.ARCHIVE;
                } else if (path.startsWith(this.contentRoot) && name.endsWith(".zip")) {
                    tier = Tier.CONTENT;
                } else {
                    continue;
                }
                final long modified = Files.getLastModifiedTime(path).toMillis();
                final long accessed = Math.max(modified, this.accessTimes.getOrDefault(path, Long.MIN_VALUE));
                entries.add(new CacheEntry(path, Files.size(path), accessed, tier));
            }
        }
        return entries;
    }

    private Set<Path> liveContentPaths() {
        final Set<Path> paths = new HashSet<>();
        synchronized (this.verifiedPacks) {
            for (ResourcePack pack : this.verifiedPacks.keySet()) {
                if (pack.content() instanceof ZipFileContent zipFileContent) {
                    paths.add(zipFileContent.path());
                }
            }
        }
        synchronized (this.liveCanonicalBlobs) {
            paths.addAll(this.liveCanonicalBlobs.values());
        }
        pruneClearedWeakValues(this.expanded);
        for (var entry : this.expanded.entrySet()) {
            final ResourcePack pack = entry.getValue().get();
            if (pack != null && pack.content() instanceof ZipFileContent zipFileContent) {
                paths.add(zipFileContent.path());
            }
        }
        return paths;
    }

    static <K, V> void pruneClearedWeakValues(final ConcurrentMap<K, WeakReference<V>> values) {
        for (var entry : values.entrySet()) {
            if (entry.getValue().get() == null) values.remove(entry.getKey(), entry.getValue());
        }
    }

    private boolean isProtected(final Path path, final Set<Path> liveContentPaths) {
        if (this.isActivePath(path) || this.activeRawLeases.containsKey(path)
                || this.activeContentLeases.containsKey(path)
                || liveContentPaths.contains(path)) {
            return true;
        }
        final String name = path.getFileName().toString();
        if (!path.startsWith(this.rawRoot) || !name.endsWith(".mcpack")) {
            return false;
        }
        final String digest = name.substring(0, name.length() - ".mcpack".length());
        if (this.rawInflight.containsKey(digest)) {
            return true;
        }
        for (ExpansionKey key : this.expansionInflight.keySet()) {
            if (key.archiveDigest().hex().equals(digest)) return true;
        }
        return false;
    }

    private boolean isProtectedTemp(final Path path) {
        if (this.isActivePath(path) || !this.sourceInflight.isEmpty()) {
            return true;
        }
        if (path.startsWith(this.trustedAliasConflictRoot.getParent())
                && !Files.isRegularFile(this.trustedAliasGlobalQuarantinePath)) {
            return true;
        }
        final String name = path.getFileName().toString();
        if (name.length() >= 64) {
            final String prefix = name.substring(0, 64);
            return isSha256Hex(prefix) && this.rawInflight.containsKey(prefix);
        }
        return false;
    }

    private boolean isActivePath(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        for (Path activePath : this.activePaths.keySet()) {
            if (normalized.equals(activePath) || normalized.startsWith(activePath)
                    || activePath.startsWith(normalized)) {
                return true;
            }
        }
        return false;
    }

    // 清理器在保护检查和删除操作期间一直持有该监视器。
    private synchronized void acquireActivePath(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        this.activePaths.compute(normalized, (ignored, current) -> {
            if (current == null) return new AtomicInteger(1);
            current.incrementAndGet();
            return current;
        });
    }

    private void releaseActivePath(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        this.activePaths.computeIfPresent(normalized, (ignored, current) ->
                current.decrementAndGet() == 0 ? null : current);
    }

    int activePathReferenceCount(final Path path) {
        final AtomicInteger references = this.activePaths.get(path.toAbsolutePath().normalize());
        return references == null ? 0 : references.get();
    }

    private boolean deleteCacheEntry(final CacheEntry entry) throws IOException {
        final long deletedSize = this.deleteManagedFile(entry.path(), entry.tier());
        if (deletedSize < 0L) {
            return false;
        }
        this.accessTimes.remove(entry.path());
        this.persistedTouches.remove(entry.path());
        this.metrics.recordCasDiskCleanup(deletedSize, 1L);
        this.metrics.eviction(entry.tier());
        return true;
    }

    private long deleteManagedFile(final Path path, final Tier tier) throws IOException {
        synchronized (this.diskMetricsLock) {
            if (!Files.isRegularFile(path)) return -1L;
            final long size = Files.size(path);
            if (!Files.deleteIfExists(path)) return -1L;
            this.adjustManagedDiskUsageLocked(tier, -size, -1L);
            return size;
        }
    }

    private void markAccess(final Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        final long now = this.currentTimeMillis.getAsLong();
        this.accessTimes.put(normalized, now);
        final AtomicBoolean shouldTouch = new AtomicBoolean();
        this.persistedTouches.compute(normalized, (ignored, previous) -> {
            if (previous == null || previous <= now - ACCESS_TOUCH_INTERVAL_MILLIS) {
                shouldTouch.set(true);
                return now;
            }
            return previous;
        });
        if (!shouldTouch.get()) return;
        this.scheduler.submitIo(() -> {
            if (Files.isRegularFile(normalized)) {
                Files.setLastModifiedTime(normalized, FileTime.fromMillis(now));
            }
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) this.persistedTouches.remove(normalized, now);
        });
    }

    private void registerLivePack(final ResourcePack pack) {
        if (pack.content() instanceof ZipFileContent zipFileContent) {
            if (zipFileContent.contentDigest() == null) {
                throw new IllegalArgumentException("Store-managed resource pack is missing its verified digest");
            }
            this.verifiedPacks.put(pack, zipFileContent.contentDigest());
            this.markAccess(zipFileContent.path());
        }
    }

    ContentDigest verifiedDigest(final ResourcePack pack) {
        return this.verifiedPacks.get(Objects.requireNonNull(pack, "pack"));
    }

    /** 确保规范 CAS 中存在完全一致的有效包，同时不保留源对象。 */
    public synchronized void ensureCanonical(final ResourcePack pack, final ContentDigest digest) throws IOException {
        final ContentDigest actual = pack.content() instanceof ZipFileContent zipFileContent
                && zipFileContent.contentDigest() != null
                ? zipFileContent.contentDigest() : ContentDigest.compute(pack.content());
        if (!digest.equals(actual)) {
            throw new IllegalArgumentException("Resource pack content does not match its declared digest");
        }

        final Path expected = this.contentPath(digest);
        if (pack.content() instanceof ZipFileContent zipFileContent
                && zipFileContent.path().equals(expected) && Files.isRegularFile(expected)) {
            this.markAccess(expected);
            return;
        }
        if (Files.isRegularFile(expected) && canonicalMatches(expected, digest)) {
            this.markAccess(expected);
            return;
        }

        final long start = this.nanoTime.getAsLong();
        boolean sample = false;
        try {
            final CanonicalPublication publication = this.publishCanonical(digest, pack.content());
            if (publication.published()) {
                this.metrics.build(Tier.CONTENT);
                sample = true;
            }
        } catch (IOException | RuntimeException | Error failure) {
            this.metrics.failure(Tier.CONTENT);
            sample = true;
            throw failure;
        } finally {
            if (sample) {
                this.metrics.recordBuildTime(Tier.CONTENT,
                        Math.max(0L, this.nanoTime.getAsLong() - start) / 1_000_000L);
            }
        }
    }

    /** 打开一个经过强校验、落盘存储的 blob，构造完成后不保留规范租约。 */
    public FrozenPackBlob openFrozenBlob(final ContentDigest digest) throws IOException {
        Objects.requireNonNull(digest, "digest");
        final Path canonical = this.contentPath(digest);
        this.acquireActivePath(canonical);
        try {
            if (!Files.isRegularFile(canonical)) {
                throw new IOException("Canonical resource pack content is unavailable: " + digest);
            }
            final ZipFileContent index = new ZipFileContent(canonical, digest);
            final ContentDigest actual;
            try {
                actual = ContentDigest.compute(index);
            } catch (RuntimeException failure) {
                throw new IOException("Failed to verify canonical resource pack content", failure);
            }
            if (!digest.equals(actual)) {
                throw new IOException("Canonical resource pack content digest mismatch: "
                        + actual + " != " + digest);
            }

            final Content content = new CanonicalContentView(this, digest, index);
            final ResourcePack pack;
            try {
                pack = content.withReadSession(() -> new ResourcePack(content));
            } catch (RuntimeException failure) {
                throw new IOException("Failed to parse canonical resource pack manifest", failure);
            }
            final FrozenPackBlob blob = FrozenPackBlob.canonical(
                    digest, pack, canonical, index.weightBytes());
            this.verifiedPacks.put(pack, digest);
            this.liveCanonicalBlobs.put(blob, canonical);
            this.markAccess(canonical);
            return blob;
        } finally {
            this.releaseActivePath(canonical);
        }
    }

    /** 打开一个引用计数的租约，防止规范文件被清理器删除。 */
    public synchronized CanonicalContentLease leaseCanonical(final ContentDigest digest) throws IOException {
        final Path canonical = this.contentPath(digest);
        this.activeContentLeases.compute(canonical, (ignored, current) -> {
            if (current == null) return new AtomicInteger(1);
            current.incrementAndGet();
            return current;
        });
        try {
            if (!Files.isRegularFile(canonical)) {
                throw new IOException("Canonical resource pack content is unavailable: " + digest);
            }
            final ZipFileContent content = new ZipFileContent(canonical, digest);
            this.markAccess(canonical);
            return new CanonicalContentLease(this, canonical, content);
        } catch (IOException | RuntimeException | Error failure) {
            this.releaseCanonical(canonical);
            throw failure;
        }
    }

    /** 返回轻量的不可变视图，仅在读取期间获取规范租约。 */
    public Content canonicalView(final ContentDigest digest) {
        return new CanonicalContentView(this, digest);
    }

    private void releaseCanonical(final Path canonical) {
        this.activeContentLeases.computeIfPresent(canonical, (ignored, current) ->
                current.decrementAndGet() == 0 ? null : current);
    }

    private synchronized RawArchiveLease tryLeaseRaw(final ArchiveDigest digest) {
        final Path archive = this.rawPath(digest);
        if (!Files.isRegularFile(archive)) {
            return null;
        }
        return this.leaseExistingRaw(archive, digest);
    }

    private synchronized RawArchiveLease leaseRaw(final Path archive, final ArchiveDigest digest) throws IOException {
        final Path normalized = archive.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Raw resource pack archive is unavailable: " + digest);
        }
        return this.leaseExistingRaw(normalized, digest);
    }

    private List<RawArchiveLease> leaseRaw(final Path archive, final ArchiveDigest digest,
                                           final int count) throws IOException {
        synchronized (this) {
            final Path normalized = archive.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized)) {
                throw new IOException("Raw resource pack archive is unavailable: " + digest);
            }
            this.activeRawLeases.compute(normalized, (ignored, current) -> {
                if (current == null) return new AtomicInteger(count);
                current.addAndGet(count);
                return current;
            });
            this.markAccess(normalized);
            final List<RawArchiveLease> leases = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                leases.add(new RawArchiveLease(this, normalized, digest));
            }
            return leases;
        }
    }

    private RawArchiveLease leaseExistingRaw(final Path archive, final ArchiveDigest digest) {
        this.activeRawLeases.compute(archive, (ignored, current) -> {
            if (current == null) return new AtomicInteger(1);
            current.incrementAndGet();
            return current;
        });
        this.markAccess(archive);
        return new RawArchiveLease(this, archive, digest);
    }

    private void releaseRaw(final Path archive) {
        this.activeRawLeases.computeIfPresent(archive, (ignored, current) ->
                current.decrementAndGet() == 0 ? null : current);
    }

    long activeRawLeaseCount() {
        return this.activeRawLeases.values().stream().mapToLong(AtomicInteger::get).sum();
    }

    int rawWaiterCount() {
        synchronized (this.rawFlightLock) {
            return this.rawInflight.values().stream().mapToInt(flight -> flight.waiters.size()).sum();
        }
    }

    void pathLoadLeaseAcquiredHook(final Runnable hook) {
        this.pathLoadLeaseAcquiredHook = Objects.requireNonNull(hook, "hook");
    }

    void trustedLookupBeforeCommitHook(final Runnable hook) {
        this.trustedLookupBeforeCommitHook = Objects.requireNonNull(hook, "hook");
    }

    void trustedConflictBeforePersistHook(final Runnable hook) {
        this.trustedConflictBeforePersistHook = Objects.requireNonNull(hook, "hook");
    }

    void sourceHandoffHook(final Runnable hook) {
        this.sourceHandoffHook = Objects.requireNonNull(hook, "hook");
    }

    private static DiskUsage diskUsage(final Path root) throws IOException {
        long bytes = 0L;
        long files = 0L;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(path);
                files++;
            }
        }
        return new DiskUsage(bytes, files);
    }

    public Claim claim(final byte[] expectedHash) {
        final ArchiveDigest digest = digest(expectedHash);
        final RawArchiveLease existingLease = this.tryLeaseRaw(digest);
        if (existingLease != null) {
            this.rawFailures.clear(digest.hex());
            this.metrics.hit(Tier.ARCHIVE);
            return Claim.completed(digest, existingLease);
        }

        final Throwable recentFailure = this.rawFailures.getIfActive(digest.hex());
        if (recentFailure != null) {
            return Claim.failed(digest, recentFailure);
        }

        final Claim claim;
        boolean leader = false;
        synchronized (this.rawFlightLock) {
            // 发布可能在上面的无锁快速路径与加入 flight 之间完成。
            final RawArchiveLease racedLease = this.tryLeaseRaw(digest);
            if (racedLease != null) {
                this.rawFailures.clear(digest.hex());
                this.metrics.hit(Tier.ARCHIVE);
                return Claim.completed(digest, racedLease);
            }
            final Throwable racedFailure = this.rawFailures.getIfActive(digest.hex());
            if (racedFailure != null) {
                return Claim.failed(digest, racedFailure);
            }

            RawFlight flight = this.rawInflight.get(digest.hex());
            if (flight == null) {
                flight = new RawFlight();
                claim = new Claim(digest, flight);
                claim.leader = true;
                claim.startBuild(this.nanoTime.getAsLong());
                claim.leadership.complete(true);
                flight.leader = claim;
                this.rawInflight.put(digest.hex(), flight);
                leader = true;
            } else {
                claim = new Claim(digest, flight);
                flight.waiters.addLast(claim);
            }
        }
        if (leader) {
            this.metrics.miss(Tier.ARCHIVE);
        } else {
            this.metrics.waiter(Tier.ARCHIVE);
        }
        this.updateArchiveInflightMetric();
        return claim;
    }

    /** 在原始 CAS 文件系统上创建一个由调用方持有的下载文件。调用方必须发布它或删除它。 */
    public Path createRawTemp(final Claim claim) throws IOException {
        requireLeader(claim);
        return this.createRawTemp(claim.digest().hex() + "-");
    }

    public Path publish(final Claim claim, final byte[] archive) throws IOException {
        try {
            requireLeader(claim);
            this.validateArchiveSize(archive.length);
        } catch (IOException | RuntimeException | Error e) {
            this.fail(claim, e);
            throw e;
        }
        final Path temp = this.createRawTemp(claim);
        try {
            Files.write(temp, archive, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            return this.publish(claim, temp);
        } catch (IOException | RuntimeException | Error e) {
            deleteOwnedTemp(temp, e);
            if (!claim.path().isDone()) {
                this.fail(claim, e);
            }
            throw e;
        }
    }

    /**
     * 在流式校验 SHA-256 之后，发布一个已完成且由调用方持有的临时文件。
     * 无论结果如何，传入的文件都会被消费并删除。
     */
    public Path publish(final Claim claim, final Path completedTemp) throws IOException {
        final Path ownedTemp = completedTemp.toAbsolutePath().normalize();
        final ArchiveDigest actual;
        try {
            requireLeader(claim);
            this.validateRawSize(ownedTemp);
            forceFile(ownedTemp);
            actual = computeArchiveDigest(ownedTemp);
        } catch (IOException | RuntimeException | Error e) {
            deleteOwnedTemp(ownedTemp, e);
            this.fail(claim, e);
            throw e;
        }
        return this.publishVerified(claim, ownedTemp, actual);
    }

    private Path publishVerified(final Claim claim, final Path ownedTemp,
                                 final ArchiveDigest actual) throws IOException {
        try {
            requireLeader(claim);
            if (!actual.equals(claim.digest())) {
                throw new IllegalStateException("Resource pack archive hash mismatch");
            }

            this.beginPublish(claim);

            final Path target = this.rawPath(actual);
            if (ownedTemp.equals(target.toAbsolutePath().normalize())) {
                throw new IllegalArgumentException("Completed raw archive temp is already the CAS target");
            }
            Files.createDirectories(target.getParent());
            this.acquireActivePath(target);
            try {
                synchronized (this.diskMetricsLock) {
                    final boolean existed = Files.isRegularFile(target);
                    final long previousSize = existed ? Files.size(target) : 0L;
                    moveAtomically(ownedTemp, target);
                    final long publishedSize = Files.size(target);
                    this.adjustManagedDiskUsageLocked(
                            Tier.ARCHIVE, publishedSize - previousSize, existed ? 0L : 1L);
                }
            } finally {
                this.releaseActivePath(target);
            }
            this.markAccess(target);
            this.completePublishedFlight(claim, target);
            return target;
        } catch (IOException | RuntimeException | Error e) {
            deleteOwnedTemp(ownedTemp, e);
            this.fail(claim, e);
            throw e;
        }
    }

    /** 立即转移所有权，并在 Netty 事件循环之外完成文件哈希/发布。 */
    public CompletableFuture<Path> publishAsync(final Claim claim, final Path completedTemp) {
        final Path ownedTemp = completedTemp.toAbsolutePath().normalize();
        try {
            requireLeader(claim);
            return this.scheduler.submitIo(() -> this.publish(claim, ownedTemp)).whenComplete((path, error) -> {
                if (error != null) {
                    deleteOwnedTemp(ownedTemp, error);
                    this.fail(claim, error);
                }
            });
        } catch (RuntimeException | Error e) {
            deleteOwnedTemp(ownedTemp, e);
            this.fail(claim, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public Path publish(final Claim claim, final InputStream archive) throws IOException {
        final Path temp = this.createRawTemp(claim);
        try (OutputStream output = new SizeLimitedOutputStream(Files.newOutputStream(
                temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                this.archiveLimits.maxArchiveBytes())) {
            archive.transferTo(output);
        } catch (IOException | RuntimeException | Error e) {
            deleteOwnedTemp(temp, e);
            this.fail(claim, e);
            throw e;
        }
        return this.publish(claim, temp);
    }

    public void fail(final Claim claim, final Throwable error) {
        final List<Claim> failedClaims;
        synchronized (this.rawFlightLock) {
            final RawFlight flight = this.rawInflight.get(claim.digest().hex());
            if (flight != claim.flight || flight.leader != claim || !claim.active) {
                return;
            }
            this.rawInflight.remove(claim.digest().hex(), flight);
            this.rawFailures.record(claim.digest().hex(), error);
            failedClaims = flight.terminate();
        }
        this.metrics.failure(Tier.ARCHIVE);
        this.recordRawBuildTime(claim);
        this.updateArchiveInflightMetric();
        for (Claim failedClaim : failedClaims) {
            failedClaim.close();
            failedClaim.finishLeadership(false);
            failedClaim.path.completeExceptionally(error);
        }
    }

    /**
     * 仅在原始 SHA-256 完全匹配之后才惰性导入旧式 UUID/版本归档。未命中时
     * 保持 leader 认领处于打开状态，以便调用方继续走正常的协议分块。
     */
    public CompletableFuture<Boolean> tryImportLegacy(final Claim claim, final ResourcePack.Key declaredKey) {
        requireLeader(claim);
        return this.scheduler.submitIo(() -> {
            final Path legacy = this.legacyPath(declaredKey);
            if (legacy == null || !Files.isRegularFile(legacy)) {
                return false;
            }

            Path temp = null;
            final ArchiveDigest actual;
            try {
                temp = this.createRawTemp(claim);
                Files.copy(legacy, temp, StandardCopyOption.REPLACE_EXISTING);
                this.validateRawSize(temp);
                forceFile(temp);
                actual = computeArchiveDigest(temp);
            } catch (IOException | RuntimeException e) {
                if (temp != null) deleteOwnedTemp(temp, e);
                return false;
            }
            if (!actual.equals(claim.digest())) {
                Files.deleteIfExists(temp);
                return false;
            }
            this.publishVerified(claim, temp, actual);
            return true;
        });
    }

    /** 释放已断开的申请者且不记录失败退避。等待中的申请者会被提升。 */
    public void abandon(final Claim claim, final Throwable reason) {
        claim.close();
        Claim promoted = null;
        boolean abandoned = false;
        synchronized (this.rawFlightLock) {
            final RawFlight flight = this.rawInflight.get(claim.digest().hex());
            if (flight != claim.flight || !claim.active) {
                return;
            }
            if (flight.leader == claim) {
                // 原子发布一旦开始，该工作就与连接解绑，应当执行到底。
                if (flight.publishing) {
                    return;
                }
                claim.active = false;
                claim.leader = false;
                flight.leader = null;
                promoted = flight.promoteNext();
                if (promoted == null) {
                    this.rawInflight.remove(claim.digest().hex(), flight);
                    flight.terminal = true;
                }
                abandoned = true;
            } else if (flight.waiters.remove(claim)) {
                claim.active = false;
                abandoned = true;
            }
        }
        if (abandoned) {
            claim.finishLeadership(false);
            claim.path.completeExceptionally(reason);
            this.updateArchiveInflightMetric();
        }
        if (promoted != null) {
            promoted.startBuild(this.nanoTime.getAsLong());
            promoted.finishLeadership(true);
        }
    }

    private void beginPublish(final Claim claim) {
        synchronized (this.rawFlightLock) {
            final RawFlight flight = this.rawInflight.get(claim.digest().hex());
            if (flight != claim.flight || flight.leader != claim || !claim.active || flight.terminal) {
                throw new IllegalStateException("Raw archive claim no longer owns the active flight");
            }
            flight.publishing = true;
        }
    }

    private void completePublishedFlight(final Claim claim, final Path target) throws IOException {
        final List<Claim> completedClaims;
        synchronized (this.rawFlightLock) {
            final RawFlight flight = this.rawInflight.get(claim.digest().hex());
            if (flight != claim.flight || flight.leader != claim || !flight.publishing || flight.terminal) {
                throw new IllegalStateException("Raw archive publication lost ownership of its flight");
            }
            completedClaims = flight.claims();
            final List<RawArchiveLease> leases = this.leaseRaw(target, claim.digest(), completedClaims.size());
            for (int i = 0; i < completedClaims.size(); i++) {
                completedClaims.get(i).attachRawLease(leases.get(i));
            }
            this.rawInflight.remove(claim.digest().hex(), flight);
            this.rawFailures.clear(claim.digest().hex());
            flight.terminate();
        }
        this.metrics.build(Tier.ARCHIVE);
        this.recordRawBuildTime(claim);
        this.updateArchiveInflightMetric();
        for (Claim completedClaim : completedClaims) {
            completedClaim.finishLeadership(false);
            if (!completedClaim.path.complete(target)) {
                completedClaim.close();
            }
        }
    }

    private void recordRawBuildTime(final Claim claim) {
        final long elapsedNanos = claim.finishBuild(this.nanoTime.getAsLong());
        if (elapsedNanos >= 0L) {
            this.metrics.recordBuildTime(Tier.ARCHIVE, elapsedNanos / 1_000_000L);
        }
    }

    private Path legacyPath(final ResourcePack.Key declaredKey) {
        final Path candidate = this.serverPacksRoot.resolve(declaredKey + ".mcpack").normalize();
        return candidate.getParent() != null && candidate.getParent().equals(this.serverPacksRoot)
                ? candidate : null;
    }

    public CompletableFuture<ResourcePack> loadEffective(final Path archive, final ArchiveDigest archiveDigest,
                                                         final ResourcePack.Key declaredKey, final byte[] contentKey,
                                                         final String contentId) {
        return this.loadEffectiveFromPath(archive, archiveDigest,
                PackAlias.from("", declaredKey, -1L, contentId, contentKey), "", contentKey);
    }

    public CompletableFuture<ResourcePack> loadEffective(final Path archive, final ArchiveDigest archiveDigest,
                                                         final PackAlias alias,
                                                         final String announcementSequenceFingerprint,
                                                         final byte[] contentKey) {
        return this.loadEffectiveFromPath(
                archive, archiveDigest, alias, announcementSequenceFingerprint, contentKey);
    }

    private CompletableFuture<ResourcePack> loadEffectiveFromPath(
            final Path archive, final ArchiveDigest archiveDigest, final PackAlias alias,
            final String announcementSequenceFingerprint, final byte[] contentKey) {
        RawArchiveLease rawLease = null;
        try {
            rawLease = this.leaseRaw(archive, archiveDigest);
            this.pathLoadLeaseAcquiredHook.run();
            return this.loadEffective(
                    archive, archiveDigest, alias, announcementSequenceFingerprint, contentKey, rawLease);
        } catch (Throwable failure) {
            closeRawLease(rawLease);
            return CompletableFuture.failedFuture(failure);
        }
    }

    public CompletableFuture<ResourcePack> loadEffective(final Claim claim,
                                                         final ResourcePack.Key declaredKey,
                                                         final byte[] contentKey, final String contentId) {
        return this.loadEffective(claim,
                PackAlias.from("", declaredKey, -1L, contentId, contentKey), "", contentKey);
    }

    public CompletableFuture<ResourcePack> loadEffective(final Claim claim, final PackAlias alias,
                                                         final String announcementSequenceFingerprint,
                                                         final byte[] contentKey) {
        final CompletableFuture<ResourcePack> result = claim.path().handle((archive, pathError) -> {
            final RawArchiveLease bridgeLease = claim.takeRawLease();
            if (pathError != null) {
                closeRawLease(bridgeLease);
                return CompletableFuture.<ResourcePack>failedFuture(pathError);
            }
            if (claim.isClosed()) {
                closeRawLease(bridgeLease);
                return CompletableFuture.<ResourcePack>failedFuture(
                        new CancellationException("Raw resource pack archive claim is closed"));
            }
            try {
                return this.loadEffective(archive, claim.digest(), alias,
                        announcementSequenceFingerprint, contentKey, bridgeLease);
            } catch (Throwable failure) {
                closeRawLease(bridgeLease);
                return CompletableFuture.<ResourcePack>failedFuture(failure);
            }
        }).thenCompose(stage -> stage);
        result.whenComplete((pack, error) -> claim.close());
        return result;
    }

    private CompletableFuture<ResourcePack> loadEffective(final Path archive, final ArchiveDigest archiveDigest,
                                                          final PackAlias alias,
                                                          final String announcementSequenceFingerprint,
                                                          final byte[] contentKey,
                                                          RawArchiveLease rawLease) {
        if (rawLease != null && !rawLease.matches(archive, archiveDigest)) {
            rawLease.close();
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Raw resource pack lease does not match the requested archive"));
        }
        this.markAccess(archive);
        if (!alias.contentKeyFingerprint().equals(PackAlias.fingerprintContentKey(contentKey))) {
            closeRawLease(rawLease);
            this.metrics.failure(Tier.CONTENT);
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Resource pack content key does not match its alias fingerprint"));
        }
        final ResourcePack.Key declaredKey = alias.toResourcePackKey();
        final ExpansionKey key = new ExpansionKey(
                archiveDigest, PackAlias.from(declaredKey), alias.contentKeyFingerprint(), alias.contentId());
        final WeakReference<ResourcePack> weak = this.expanded.get(key);
        final ResourcePack cached = weak != null ? weak.get() : null;
        if (cached != null) {
            closeRawLease(rawLease);
            this.expansionFailures.clear(key);
            this.metrics.hit(Tier.CONTENT);
            this.observe(alias, announcementSequenceFingerprint, archiveDigest, cached);
            return CompletableFuture.completedFuture(cached);
        }

        final Throwable recentFailure = this.expansionFailures.getIfActive(key);
        if (recentFailure != null) {
            closeRawLease(rawLease);
            return CompletableFuture.failedFuture(recentFailure);
        }

        if (rawLease == null) {
            try {
                rawLease = this.leaseRaw(archive, archiveDigest);
            } catch (IOException | RuntimeException | Error failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        final CompletableFuture<ResourcePack> candidate = new CompletableFuture<>();
        final CompletableFuture<ResourcePack> raced = this.expansionInflight.putIfAbsent(key, candidate);
        if (raced != null) {
            closeRawLease(rawLease);
            this.metrics.waiter(Tier.CONTENT);
            return raced.thenApply(pack -> {
                this.observe(alias, announcementSequenceFingerprint, archiveDigest, pack);
                return pack;
            });
        }
        final Throwable racedFailure = this.expansionFailures.getIfActive(key);
        if (racedFailure != null) {
            closeRawLease(rawLease);
            this.expansionInflight.remove(key, candidate);
            candidate.completeExceptionally(racedFailure);
            return candidate.thenApply(pack -> pack);
        }
        this.metrics.miss(Tier.CONTENT);
        this.metrics.setInflight(Tier.CONTENT, this.expansionInflight.size());
        final AtomicBoolean rawValidationFailed = new AtomicBoolean();
        final RawArchiveLease buildLease = rawLease;
        final long buildStart = this.nanoTime.getAsLong();
        final CompletableFuture<ResourcePack> expansion;
        try {
            expansion = this.scheduler.submitCpu(() -> {
                try {
                    this.validateRawSize(archive);
                    final ArchiveDigest actualArchiveDigest = computeArchiveDigest(archive);
                    if (!actualArchiveDigest.equals(archiveDigest)) {
                        if (archive.toAbsolutePath().normalize().startsWith(this.rawRoot.toAbsolutePath().normalize())) {
                            this.deleteManagedFile(archive, Tier.ARCHIVE);
                        }
                        throw new IllegalStateException("Stored resource pack archive hash mismatch");
                    }
                } catch (IOException | RuntimeException | Error failure) {
                    rawValidationFailed.set(true);
                    throw failure;
                }

                final Path workRoot = Files.createTempDirectory(
                        this.contentRoot, "expand-" + archiveDigest.hex() + "-");
                this.acquireActivePath(workRoot);
                try {
                    final Path extractedRoot = workRoot.resolve("archive");
                    final ExtractionBudget extractionBudget = this.newExtractionBudget(archive);
                    this.extractArchive(archive, extractedRoot, extractionBudget);
                    final Path packRoot = this.findPackRoot(workRoot, extractedRoot, extractionBudget);
                    final ResourcePack mutable = new ResourcePack(
                            new DirectoryContent(packRoot.toAbsolutePath().normalize()));
                    validateManifestKey(mutable, declaredKey);
                    if (contentKey.length > 0) {
                        mutable.decryptContent(contentKey, alias.contentId());
                    } else if (mutable.isContentEncrypted()) {
                        throw new IllegalStateException("Encrypted resource pack is missing its content key");
                    }
                    return this.canonicalize(mutable);
                } finally {
                    try {
                        deleteRecursively(workRoot);
                    } finally {
                        this.releaseActivePath(workRoot);
                    }
                }
            });
        } catch (Throwable failure) {
            this.expansionFailures.record(key, failure);
            this.metrics.failure(Tier.CONTENT);
            this.metrics.recordBuildTime(Tier.CONTENT,
                    Math.max(0L, this.nanoTime.getAsLong() - buildStart) / 1_000_000L);
            this.expansionInflight.remove(key, candidate);
            this.metrics.setInflight(Tier.CONTENT, this.expansionInflight.size());
            buildLease.close();
            candidate.completeExceptionally(failure);
            return candidate.thenApply(pack -> pack);
        }
        expansion.whenComplete((pack, error) -> {
            try {
                if (error == null) {
                    this.expanded.put(key, new WeakReference<>(pack));
                    this.expansionFailures.clear(key);
                    this.metrics.build(Tier.CONTENT);
                } else {
                    this.expansionFailures.record(key, error);
                    this.metrics.failure(rawValidationFailed.get() ? Tier.ARCHIVE : Tier.CONTENT);
                }
                this.expansionInflight.remove(key, candidate);
                this.metrics.setInflight(Tier.CONTENT, this.expansionInflight.size());
            } finally {
                this.metrics.recordBuildTime(Tier.CONTENT,
                        Math.max(0L, this.nanoTime.getAsLong() - buildStart) / 1_000_000L);
                buildLease.close();
            }
            if (error == null) {
                candidate.complete(pack);
            } else {
                candidate.completeExceptionally(error);
            }
        });
        return candidate.thenApply(pack -> {
            this.observe(alias, announcementSequenceFingerprint, archiveDigest, pack);
            return pack;
        });
    }

    private static void closeRawLease(final RawArchiveLease lease) {
        if (lease != null) lease.close();
    }

    private ResourcePack canonicalize(final ResourcePack pack) throws IOException {
        final ContentDigest contentDigest = ContentDigest.compute(pack.content());
        final CanonicalPublication canonical = this.publishCanonical(contentDigest, pack.content());
        return new ResourcePack(new ZipFileContent(canonical.path(), contentDigest));
    }

    public CompletableFuture<ResourcePack> loadFromSource(final String source, final ResourcePack.Key declaredKey,
                                                          final byte[] contentKey, final String contentId,
                                                          final ArchiveLoader loader) {
        return this.loadFromSource(source, declaredKey, -1L, contentKey, contentId, loader);
    }

    public CompletableFuture<ResourcePack> loadFromSource(final String source, final ResourcePack.Key declaredKey,
                                                          final long announcedSize, final byte[] contentKey,
                                                          final String contentId, final ArchiveLoader loader) {
        return this.loadFromStreamSource(source,
                PackAlias.from("", declaredKey, announcedSize, contentId, contentKey), "", false, contentKey,
                output -> output.write(loader.load()));
    }

    public CompletableFuture<ResourcePack> loadFromSource(final String source, final ResourcePack.Key declaredKey,
                                                          final byte[] contentKey, final String contentId,
                                                          final ArchivePathLoader loader) {
        return this.loadFromSource(source, declaredKey, -1L, contentKey, contentId, loader);
    }

    public CompletableFuture<ResourcePack> loadFromSource(final String source, final ResourcePack.Key declaredKey,
                                                          final long announcedSize, final byte[] contentKey,
                                                          final String contentId, final ArchivePathLoader loader) {
        return this.loadFromSource(source,
                PackAlias.from("", declaredKey, announcedSize, contentId, contentKey), "", false, contentKey,
                loader);
    }

    public CompletableFuture<ResourcePack> loadFromSource(final String source, final PackAlias alias,
                                                          final String announcementSequenceFingerprint,
                                                          final boolean trustDeclaredAlias, final byte[] contentKey,
                                                          final ArchiveLoader loader) {
        return this.loadFromStreamSource(
                source, alias, announcementSequenceFingerprint, trustDeclaredAlias, contentKey,
                output -> output.write(loader.load()));
    }

    public CompletableFuture<ResourcePack> loadFromSource(final String source, final PackAlias alias,
                                                          final String announcementSequenceFingerprint,
                                                          final boolean trustDeclaredAlias, final byte[] contentKey,
                                                          final ArchivePathLoader loader) {
        return this.loadFromSourceInternal(
                source, alias, announcementSequenceFingerprint, trustDeclaredAlias, contentKey, target -> {
                    loader.load(target);
                    return null;
                });
    }

    public CompletableFuture<ResourcePack> loadFromStreamSource(
            final String source, final PackAlias alias, final String announcementSequenceFingerprint,
            final boolean trustDeclaredAlias, final byte[] contentKey, final ArchiveStreamLoader loader) {
        return this.loadFromSourceInternal(
                source, alias, announcementSequenceFingerprint, trustDeclaredAlias, contentKey,
                target -> writeSourceStream(target, loader));
    }

    private CompletableFuture<ResourcePack> loadFromSourceInternal(
            final String source, final PackAlias alias, final String announcementSequenceFingerprint,
            final boolean trustDeclaredAlias, final byte[] contentKey, final SourceArchiveLoader loader) {
        if (!alias.contentKeyFingerprint().equals(PackAlias.fingerprintContentKey(contentKey))) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Resource pack content key does not match its alias fingerprint"));
        }
        final boolean trustedLookup = trustDeclaredAlias && alias.isComplete()
                && isSha256Hex(announcementSequenceFingerprint);
        final SourceKey sourceKey = new SourceKey(
                source, alias, announcementSequenceFingerprint, trustedLookup);
        final Throwable recentFailure = this.sourceFailures.getIfActive(sourceKey);
        if (recentFailure != null) {
            return CompletableFuture.failedFuture(recentFailure);
        }
        final CompletableFuture<ResourcePack> candidate = new CompletableFuture<>();
        final CompletableFuture<ResourcePack> raced = this.sourceInflight.putIfAbsent(sourceKey, candidate);
        if (raced != null) {
            this.metrics.waiter(Tier.ARCHIVE);
            return raced.thenApply(pack -> pack);
        }
        this.updateArchiveInflightMetric();
        final Throwable racedFailure = this.sourceFailures.getIfActive(sourceKey);
        if (racedFailure != null) {
            this.sourceInflight.remove(sourceKey, candidate);
            this.updateArchiveInflightMetric();
            candidate.completeExceptionally(racedFailure);
            return candidate.thenApply(pack -> pack);
        }

        try {
            this.validateAnnouncedSourceSize(alias.announcedSize());
        } catch (IOException e) {
            this.sourceFailures.record(sourceKey, e);
            this.metrics.failure(Tier.ARCHIVE);
            this.sourceInflight.remove(sourceKey, candidate);
            this.updateArchiveInflightMetric();
            candidate.completeExceptionally(e);
            return candidate.thenApply(pack -> pack);
        }

        final CompletableFuture<ResourcePack> trustedCandidate = trustedLookup
                ? this.lookupTrustedAlias(alias, announcementSequenceFingerprint)
                : CompletableFuture.completedFuture(null);
        trustedCandidate.thenCompose(pack -> pack != null
                ? CompletableFuture.completedFuture(pack)
                : this.loadSource(alias, announcementSequenceFingerprint, contentKey, loader))
                .whenComplete((pack, error) -> {
            if (error == null) {
                this.sourceFailures.clear(sourceKey);
            } else {
                this.sourceFailures.record(sourceKey, error);
            }
            this.sourceInflight.remove(sourceKey, candidate);
            this.updateArchiveInflightMetric();
            if (error == null) {
                candidate.complete(pack);
            } else {
                candidate.completeExceptionally(error);
            }
        });
        return candidate.thenApply(pack -> pack);
    }

    private CompletableFuture<ResourcePack> loadSource(final PackAlias alias,
                                                       final String announcementSequenceFingerprint,
                                                       final byte[] contentKey,
                                                       final SourceArchiveLoader loader) {
        return this.scheduler.submitIo(() -> {
            final Path temp = this.createRawTemp("source-");
            Claim claim = null;
            boolean publicationAttempted = false;
            try {
                ArchiveDigest digest = loader.load(temp);
                this.validateSourceSize(temp, alias.announcedSize());
                forceFile(temp);
                if (digest == null) digest = computeArchiveDigest(temp);
                claim = this.claim(digestBytes(digest));
                if (claim.leader()) {
                    publicationAttempted = true;
                    this.publishVerified(claim, temp, digest);
                } else {
                    Files.deleteIfExists(temp);
                }
            } catch (Exception e) {
                deleteOwnedTemp(temp, e);
                if (!publicationAttempted) this.metrics.failure(Tier.ARCHIVE);
                if (claim != null) this.abandon(claim, e);
                throw e;
            } catch (Error e) {
                deleteOwnedTemp(temp, e);
                if (!publicationAttempted) this.metrics.failure(Tier.ARCHIVE);
                if (claim != null) this.abandon(claim, e);
                throw e;
            }
            try {
                this.sourceHandoffHook.run();
                return this.loadEffective(claim, alias, announcementSequenceFingerprint, contentKey);
            } catch (RuntimeException | Error e) {
                this.abandon(claim, e);
                throw e;
            }
        }).thenCompose(stage -> stage);
    }

    private ArchiveDigest writeSourceStream(
            final Path target, final ArchiveStreamLoader loader) throws Exception {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (OutputStream output = new SizeLimitedOutputStream(
                new DigestOutputStream(Files.newOutputStream(target,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING), digest),
                this.archiveLimits.maxArchiveBytes())) {
            loader.load(output);
        }
        return new ArchiveDigest(java.util.HexFormat.of().formatHex(digest.digest()));
    }

    private CompletableFuture<ResourcePack> lookupTrustedAlias(final PackAlias alias,
                                                               final String announcementSequenceFingerprint) {
        final TrustedAliasKey trustedAliasKey = new TrustedAliasKey(alias, announcementSequenceFingerprint);
        final String conflictDigest = trustedAliasConflictDigest(trustedAliasKey);
        if (this.trustedAliasGlobalQuarantine.get()
                || this.trustedAliasConflictQuarantine.contains(conflictDigest)) {
            return CompletableFuture.completedFuture(null);
        }
        return this.scheduler.submitIo(() -> {
            if (this.isTrustedAliasQuarantined(conflictDigest)) {
                return null;
            }
            final TrustedObservationHistory observations = this.trustedAliases.getIfPresent(trustedAliasKey);
            if (observations == null) return null;
            final ContentDigest candidate = observations.uniqueContent();
            if (candidate == null || this.isTrustedAliasQuarantined(conflictDigest)) return null;

            final Path canonical = this.contentPath(candidate);
            this.acquireActivePath(canonical);
            try {
                if (!Files.isRegularFile(canonical) || !canonicalMatches(canonical, candidate)) {
                    return null;
                }
                final ResourcePack pack = new ResourcePack(new ZipFileContent(canonical, candidate));
                validateManifestKey(pack, alias.toResourcePackKey());
                this.registerLivePack(pack);
                this.trustedLookupBeforeCommitHook.run();
                if (!candidate.equals(observations.uniqueContent())
                        || this.isTrustedAliasQuarantined(conflictDigest)) {
                    return null;
                }
                this.metrics.hit(Tier.CONTENT);
                return pack;
            } finally {
                this.releaseActivePath(canonical);
            }
        }).exceptionally(error -> null);
    }

    private void observe(final PackAlias alias, final String announcementSequenceFingerprint,
                         final ArchiveDigest archiveDigest, final ResourcePack pack) {
        this.registerLivePack(pack);
        final ContentDigest contentDigest = pack.content() instanceof ZipFileContent zipFileContent
                && zipFileContent.contentDigest() != null
                ? zipFileContent.contentDigest() : ContentDigest.compute(pack.content());
        final AliasContentHistory observed = this.aliases.asMap().computeIfAbsent(
                alias, ignored -> new AliasContentHistory());
        if (observed.observe(contentDigest)) {
            this.metrics.aliasConflict();
            if (ViaBedrock.getPlatform() != null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "Resource pack alias resolved to conflicting content; trusted reuse is disabled when "
                                + "the exact announcement has multiple candidates: " + alias);
            }
        }
        if (alias.isComplete() && isSha256Hex(announcementSequenceFingerprint)) {
            final TrustedAliasKey trustedAliasKey = new TrustedAliasKey(alias, announcementSequenceFingerprint);
            final boolean conflicting = this.trustedAliases.asMap().computeIfAbsent(
                            trustedAliasKey, ignored -> new TrustedObservationHistory())
                    .observe(new AliasObservation(archiveDigest, contentDigest));
            if (conflicting) {
                try {
                    this.persistTrustedAliasConflict(trustedAliasKey);
                } catch (IOException e) {
                    try {
                        this.persistTrustedAliasGlobalQuarantine();
                    } catch (IOException globalFailure) {
                        e.addSuppressed(globalFailure);
                        throw new IllegalStateException(
                                "Failed to durably quarantine conflicting resource pack aliases", e);
                    }
                    if (ViaBedrock.getPlatform() != null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.SEVERE,
                                "Failed to persist an individual resource pack alias quarantine; "
                                        + "trusted alias reuse is now globally disabled", e);
                    }
                }
            }
        }
    }

    private boolean isTrustedAliasQuarantined(final String conflictDigest) {
        if (this.trustedAliasGlobalQuarantine.get()
                || this.durableQuarantineExists(this.trustedAliasGlobalQuarantinePath)) {
            this.activateTrustedAliasGlobalQuarantine();
            return true;
        }
        if (this.trustedAliasConflictQuarantine.contains(conflictDigest)) return true;
        if (!this.durableQuarantineExists(this.trustedAliasConflictPath(conflictDigest))) return false;
        this.trustedAliasConflictQuarantine.add(conflictDigest);
        return true;
    }

    private boolean durableQuarantineExists(final Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            this.activateTrustedAliasGlobalQuarantine();
            try {
                this.persistTrustedAliasGlobalQuarantine();
            } catch (IOException ignored) {
                this.metrics.casDiskCleanupFailure();
            }
            return true;
        }
    }

    private void activateTrustedAliasGlobalQuarantine() {
        this.trustedAliasGlobalQuarantine.set(true);
        synchronized (this.diskMetricsLock) {
            this.publishDiskMetricsLocked();
        }
    }

    private synchronized void persistTrustedAliasConflict(final TrustedAliasKey key) throws IOException {
        this.trustedConflictBeforePersistHook.run();
        if (this.trustedAliasGlobalQuarantine.get()) return;
        final String conflictDigest = trustedAliasConflictDigest(key);
        this.trustedAliasConflictQuarantine.add(conflictDigest);
        final Path target = this.trustedAliasConflictPath(conflictDigest);
        if (Files.exists(target)) return;
        if (this.trustedAliasConflictTombstoneCount >= this.trustedAliasConflictMaxTombstones) {
            this.persistTrustedAliasGlobalQuarantine();
            return;
        }

        Files.createDirectories(target.getParent());
        final Path temp = Files.createTempFile(
                target.getParent(), conflictDigest + "-", ".conflict.tmp");
        boolean published = false;
        this.acquireActivePath(temp);
        this.acquireActivePath(target);
        try {
            Files.writeString(temp, TRUSTED_ALIAS_CONFLICT_HEADER + conflictDigest + '\n',
                    StandardCharsets.US_ASCII, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            forceFile(temp);
            synchronized (this.diskMetricsLock) {
                if (!Files.isRegularFile(target)) {
                    moveAtomically(temp, target);
                    final long size = Files.size(target);
                    this.tombstoneDiskBytes += size;
                    this.tombstoneDiskFiles++;
                    this.trustedAliasConflictTombstoneCount++;
                    this.publishDiskMetricsLocked();
                    published = true;
                } else {
                    published = true;
                }
            }
        } finally {
            this.releaseActivePath(target);
            this.releaseActivePath(temp);
            if (published) Files.deleteIfExists(temp);
        }
        if (this.trustedAliasConflictTombstoneCount >= this.trustedAliasConflictMaxTombstones) {
            this.persistTrustedAliasGlobalQuarantine();
        }
    }

    private synchronized void recoverTrustedAliasConflictState() throws IOException {
        this.refreshDiskMetrics();
        if (this.durableQuarantineExists(this.trustedAliasGlobalQuarantinePath)) {
            this.activateTrustedAliasGlobalQuarantine();
        }
        final boolean interruptedPublication;
        try (var paths = Files.walk(this.trustedAliasConflictRoot.getParent())) {
            interruptedPublication = paths.filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
        if ((interruptedPublication
                || this.trustedAliasConflictTombstoneCount >= this.trustedAliasConflictMaxTombstones)
                && !this.trustedAliasGlobalQuarantine.get()) {
            this.persistTrustedAliasGlobalQuarantine();
        }
    }

    private synchronized void persistTrustedAliasGlobalQuarantine() throws IOException {
        this.trustedAliasGlobalQuarantine.set(true);
        synchronized (this.diskMetricsLock) {
            if (Files.isRegularFile(this.trustedAliasGlobalQuarantinePath)) {
                this.publishDiskMetricsLocked();
                return;
            }
            Files.createDirectories(this.trustedAliasGlobalQuarantinePath.getParent());
            final Path temp = Files.createTempFile(
                    this.trustedAliasGlobalQuarantinePath.getParent(), "global-", ".quarantine.tmp");
            boolean published = false;
            this.acquireActivePath(temp);
            this.acquireActivePath(this.trustedAliasGlobalQuarantinePath);
            try {
                Files.writeString(temp, TRUSTED_ALIAS_GLOBAL_QUARANTINE_HEADER,
                        StandardCharsets.US_ASCII, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                forceFile(temp);
                moveAtomically(temp, this.trustedAliasGlobalQuarantinePath);
                this.tombstoneDiskBytes += Files.size(this.trustedAliasGlobalQuarantinePath);
                this.tombstoneDiskFiles++;
                this.publishDiskMetricsLocked();
                published = true;
            } finally {
                this.releaseActivePath(this.trustedAliasGlobalQuarantinePath);
                this.releaseActivePath(temp);
                if (published) Files.deleteIfExists(temp);
                if (!published) this.publishDiskMetricsLocked();
            }
        }
    }

    Path trustedAliasConflictPath(final PackAlias alias, final String announcementSequenceFingerprint) {
        if (!isSha256Hex(announcementSequenceFingerprint)) {
            throw new IllegalArgumentException("Announcement sequence fingerprint must be SHA-256");
        }
        return this.trustedAliasConflictPath(
                trustedAliasConflictDigest(new TrustedAliasKey(alias, announcementSequenceFingerprint)));
    }

    private Path trustedAliasConflictPath(final String conflictDigest) {
        return this.trustedAliasConflictRoot.resolve(conflictDigest.substring(0, 2))
                .resolve(conflictDigest + ".conflict");
    }

    Path trustedAliasGlobalQuarantinePath() {
        return this.trustedAliasGlobalQuarantinePath;
    }

    long aliasHistorySize() {
        this.aliases.cleanUp();
        return this.aliases.size();
    }

    long trustedAliasHistorySize() {
        this.trustedAliases.cleanUp();
        return this.trustedAliases.size();
    }

    private CanonicalPublication publishCanonical(final ContentDigest digest, final Content content) throws IOException {
        final Path target = this.contentPath(digest);
        this.acquireActivePath(target);
        try {
            if (Files.isRegularFile(target)) {
                if (canonicalMatches(target, digest)) {
                    this.markAccess(target);
                    return new CanonicalPublication(target, false);
                }
            }
            Files.createDirectories(target.getParent());
            final Path temp = Files.createTempFile(target.getParent(), digest.hex() + "-", ".zip.tmp");
            this.acquireActivePath(temp);
            try {
                content.writeZip(temp);
                forceFile(temp);
                if (!canonicalMatches(temp, digest)) {
                    throw new IllegalStateException("Canonical resource pack content digest mismatch");
                }

                boolean published = false;
                synchronized (this.canonicalPublicationLock(digest)) {
                    if (!Files.isRegularFile(target) || !canonicalMatches(target, digest)) {
                        synchronized (this.diskMetricsLock) {
                            final boolean existed = Files.isRegularFile(target);
                            final long previousSize = existed ? Files.size(target) : 0L;
                            moveAtomically(temp, target);
                            final long publishedSize = Files.size(target);
                            this.adjustManagedDiskUsageLocked(
                                    Tier.CONTENT, publishedSize - previousSize, existed ? 0L : 1L);
                        }
                        published = true;
                    }
                }
                this.markAccess(target);
                return new CanonicalPublication(target, published);
            } finally {
                this.releaseActivePath(temp);
                Files.deleteIfExists(temp);
            }
        } finally {
            this.releaseActivePath(target);
        }
    }

    private Object canonicalPublicationLock(final ContentDigest digest) {
        return this.canonicalPublicationLocks[(digest.hashCode() & Integer.MAX_VALUE)
                % this.canonicalPublicationLocks.length];
    }

    private static Object[] publicationLocks() {
        final Object[] locks = new Object[CANONICAL_PUBLICATION_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
        return locks;
    }

    private static boolean canonicalMatches(final Path archive, final ContentDigest expectedDigest) {
        try {
            return ContentDigest.compute(new ZipFileContent(archive)).equals(expectedDigest);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private Path createRawTemp(final String prefix) throws IOException {
        Files.createDirectories(this.rawRoot);
        return Files.createTempFile(this.rawRoot, prefix, ".mcpack.tmp").toAbsolutePath().normalize();
    }

    private long validateRawSize(final Path archive) throws IOException {
        final long size = Files.size(archive);
        this.validateArchiveSize(size);
        return size;
    }

    private void validateArchiveSize(final long size) throws IOException {
        if (size < 0L || size > this.archiveLimits.maxArchiveBytes()) {
            throw new IOException("Resource pack archive exceeds the configured size limit");
        }
    }

    private void validateAnnouncedSourceSize(final long announcedSize) throws IOException {
        if (announcedSize > this.archiveLimits.maxArchiveBytes()) {
            throw new IOException("Announced resource pack archive size exceeds the configured size limit");
        }
    }

    private void validateSourceSize(final Path archive, final long announcedSize) throws IOException {
        final long actualSize = this.validateRawSize(archive);
        if (announcedSize >= 0L && actualSize != announcedSize) {
            throw new IOException("Resource pack archive size " + actualSize
                    + " does not match announced size " + announcedSize);
        }
    }

    private void extractArchive(final Path archive, final Path targetRoot,
                                final ExtractionBudget extractionBudget) throws IOException {
        Files.createDirectories(targetRoot);
        this.scanArchive(archive, targetRoot.toAbsolutePath().normalize(), extractionBudget);
    }

    private void scanArchive(final Path archive, final Path targetRoot,
                             final ExtractionBudget extractionBudget) throws IOException {
        final long archiveBytes = Files.size(archive);
        if (archiveBytes > this.archiveLimits.maxArchiveBytes()) {
            throw new IOException("Resource pack archive exceeds the configured size limit");
        }
        final Set<String> paths = new HashSet<>();
        final byte[] buffer = new byte[64 * 1024];
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(
                Files.newInputStream(archive), 64 * 1024))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++extractionBudget.entries > this.archiveLimits.maxEntries()) {
                    throw new IOException("Resource pack contains too many entries");
                }
                final String path = validateZipPath(entry);
                if (!paths.add(path)) {
                    throw new IOException("Resource pack contains duplicate path: " + path);
                }

                final Path target;
                if (targetRoot == null) {
                    target = null;
                } else {
                    target = targetRoot.resolve(path).normalize();
                    if (!target.startsWith(targetRoot)) {
                        throw new IOException("Resource pack path escapes the extraction directory: " + path);
                    }
                }

                if (entry.isDirectory()) {
                    if (target != null) {
                        Files.createDirectories(target);
                    }
                    if (zip.read() != -1) {
                        throw new IOException("Resource pack directory entry contains data: " + entry.getName());
                    }
                    continue;
                }

                if (target != null) {
                    Files.createDirectories(target.getParent());
                }
                long entryBytes = 0L;
                try (OutputStream output = target == null
                             ? OutputStream.nullOutputStream()
                             : Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int read;
                    while ((read = zip.read(buffer)) != -1) {
                        if (entryBytes > this.archiveLimits.maxEntryBytes() - read) {
                            throw new IOException("Resource pack entry exceeds the configured size limit: " + path);
                        }
                        if (extractionBudget.expandedBytes > this.archiveLimits.maxExpandedBytes() - read) {
                            throw new IOException("Resource pack exceeds the configured expanded size limit");
                        }
                        if (extractionBudget.expandedBytes > extractionBudget.ratioLimit - read) {
                            throw new IOException("Resource pack exceeds the configured compression ratio");
                        }
                        entryBytes += read;
                        extractionBudget.expandedBytes += read;
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private Path findPackRoot(final Path workRoot, Path currentRoot,
                              final ExtractionBudget extractionBudget) throws IOException {
        for (int depth = 0; depth < 8; depth++) {
            if (hasRootManifest(currentRoot)) {
                return currentRoot;
            }

            final List<Path> files;
            try (var paths = Files.walk(currentRoot)) {
                files = paths.filter(Files::isRegularFile).toList();
            }
            if (files.size() == 1 && files.getFirst().getFileName().toString().endsWith(".zip")) {
                final Path nestedRoot = workRoot.resolve("nested-" + depth);
                this.extractArchive(files.getFirst(), nestedRoot, extractionBudget);
                currentRoot = nestedRoot;
                continue;
            }
            if (!files.isEmpty()) {
                String commonRoot = null;
                boolean sharedRoot = true;
                for (Path file : files) {
                    final Path relative = currentRoot.relativize(file);
                    if (relative.getNameCount() < 2) {
                        sharedRoot = false;
                        break;
                    }
                    final String first = relative.getName(0).toString();
                    if (commonRoot == null) {
                        commonRoot = first;
                    } else if (!commonRoot.equals(first)) {
                        sharedRoot = false;
                        break;
                    }
                }
                if (sharedRoot && commonRoot != null && Files.isDirectory(currentRoot.resolve(commonRoot))) {
                    currentRoot = currentRoot.resolve(commonRoot);
                    continue;
                }
            }
            throw new IllegalStateException("Missing manifest.json");
        }
        throw new IOException("Resource pack nesting exceeds the configured safety limit");
    }

    private ExtractionBudget newExtractionBudget(final Path archive) throws IOException {
        return new ExtractionBudget(saturatingMultiply(
                Files.size(archive), this.archiveLimits.maxCompressionRatio()));
    }

    private static boolean hasRootManifest(final Path root) {
        return Files.isRegularFile(root.resolve("manifest.json"))
                || Files.isRegularFile(root.resolve("pack_manifest.json"));
    }

    private static void validateManifestKey(final ResourcePack pack, final ResourcePack.Key declaredKey) {
        if (!pack.key().equals(declaredKey)) {
            throw new IllegalStateException("Declared resource pack key " + declaredKey
                    + " does not match manifest key " + pack.key());
        }
    }

    private static String validateZipPath(final ZipEntry entry) throws IOException {
        final String rawPath = entry.getName();
        final String path = entry.isDirectory() && rawPath.endsWith("/")
                ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
            throw new IOException("Resource pack contains a non-canonical path: " + rawPath);
        }
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            throw new IOException("Resource pack path contains a drive prefix: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Resource pack contains a non-canonical path: " + path);
            }
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                throw new IOException("Resource pack path contains a control character");
            }
        }
        return path;
    }

    private static ArchiveDigest computeArchiveDigest(final Path archive) throws IOException {
        try (InputStream input = Files.newInputStream(archive)) {
            return ArchiveDigest.compute(input);
        }
    }

    private static void forceFile(final Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static long saturatingMultiply(final long left, final int right) {
        if (left <= 0L || right <= 0) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private void requireLeader(final Claim claim) {
        synchronized (this.rawFlightLock) {
            final RawFlight flight = this.rawInflight.get(claim.digest().hex());
            if (flight != claim.flight || flight.leader != claim || !claim.active || flight.terminal) {
                throw new IllegalArgumentException("Only the active raw archive claim leader may publish");
            }
        }
    }

    private void updateArchiveInflightMetric() {
        synchronized (this.archiveInflightMetricsLock) {
            this.metrics.setInflight(
                    Tier.ARCHIVE, (long) this.rawInflight.size() + this.sourceInflight.size());
        }
    }

    private static void deleteOwnedTemp(final Path temp, final Throwable failure) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException cleanupError) {
            failure.addSuppressed(cleanupError);
        }
    }

    private static void deleteRecursively(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            final List<Path> toDelete = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : toDelete) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path rawPath(final ArchiveDigest digest) {
        return this.rawRoot.resolve(digest.hex().substring(0, 2)).resolve(digest.hex() + ".mcpack");
    }

    private Path contentPath(final ContentDigest digest) {
        return this.contentRoot.resolve(digest.hex().substring(0, 2)).resolve(digest.hex() + ".zip");
    }

    private static ArchiveDigest digest(final byte[] expectedHash) {
        if (expectedHash.length != 32) {
            throw new IllegalArgumentException("Resource pack SHA-256 must contain 32 bytes");
        }
        final StringBuilder hex = new StringBuilder(64);
        for (byte value : expectedHash) {
            hex.append(Character.forDigit(Byte.toUnsignedInt(value) >>> 4, 16));
            hex.append(Character.forDigit(Byte.toUnsignedInt(value) & 0x0F, 16));
        }
        return new ArchiveDigest(hex.toString());
    }

    private static byte[] digestBytes(final ArchiveDigest digest) {
        final byte[] bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(digest.hex().substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private static boolean isSha256Hex(final String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private static String trustedAliasConflictDigest(final TrustedAliasKey key) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        final PackAlias alias = key.alias();
        digest.update(TRUSTED_ALIAS_CONFLICT_DOMAIN);
        updateDigestString(digest, alias.backendScope());
        updateDigestLong(digest, alias.id().getMostSignificantBits());
        updateDigestLong(digest, alias.id().getLeastSignificantBits());
        updateDigestString(digest, alias.version());
        updateDigestLong(digest, alias.announcedSize());
        updateDigestString(digest, alias.contentId());
        updateDigestString(digest, alias.contentKeyFingerprint());
        updateDigestString(digest, key.announcementSequenceFingerprint());
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void updateDigestString(final MessageDigest digest, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateDigestInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateDigestInt(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateDigestLong(final MessageDigest digest, final long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void moveAtomically(final Path source, final Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static final class RawFlight {
        private final ArrayDeque<Claim> waiters = new ArrayDeque<>();
        private Claim leader;
        private boolean publishing;
        private boolean terminal;

        private Claim promoteNext() {
            while (!this.waiters.isEmpty()) {
                final Claim candidate = this.waiters.removeFirst();
                if (!candidate.active || candidate.path.isDone() || candidate.isClosed()) {
                    candidate.active = false;
                    candidate.finishLeadership(false);
                    candidate.path.completeExceptionally(
                            new CancellationException("Raw resource pack archive claim was closed"));
                    continue;
                }
                candidate.leader = true;
                this.leader = candidate;
                return candidate;
            }
            return null;
        }

        private List<Claim> claims() {
            final List<Claim> claims = new ArrayList<>(this.waiters.size() + (this.leader != null ? 1 : 0));
            if (this.leader != null) claims.add(this.leader);
            claims.addAll(this.waiters);
            return claims;
        }

        private List<Claim> terminate() {
            final List<Claim> claims = this.claims();
            this.waiters.clear();
            this.leader = null;
            this.publishing = false;
            this.terminal = true;
            for (Claim claim : claims) {
                claim.active = false;
                claim.leader = false;
            }
            return claims;
        }
    }

    public static final class Claim implements AutoCloseable {
        private final ArchiveDigest digest;
        private final RawFlight flight;
        private final CompletableFuture<Path> path = new CompletableFuture<>();
        private final CompletableFuture<Boolean> leadership = new CompletableFuture<>();
        private final AtomicReference<RawArchiveLease> rawLease = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean leader;
        private boolean active = true;
        private long buildStartNanos = Long.MIN_VALUE;

        private Claim(final ArchiveDigest digest, final RawFlight flight) {
            this.digest = digest;
            this.flight = flight;
        }

        private static Claim completed(final ArchiveDigest digest, final RawArchiveLease rawLease) {
            final Claim claim = new Claim(digest, null);
            claim.active = false;
            claim.leadership.complete(false);
            claim.attachRawLease(rawLease);
            claim.path.complete(rawLease.archive());
            return claim;
        }

        private static Claim failed(final ArchiveDigest digest, final Throwable error) {
            final Claim claim = new Claim(digest, null);
            claim.active = false;
            claim.leadership.complete(false);
            claim.path.completeExceptionally(error);
            return claim;
        }

        private void finishLeadership(final boolean promoted) {
            this.leadership.complete(promoted);
        }

        private synchronized void startBuild(final long startNanos) {
            this.buildStartNanos = startNanos;
        }

        private synchronized long finishBuild(final long endNanos) {
            if (this.buildStartNanos == Long.MIN_VALUE) return -1L;
            final long elapsed = Math.max(0L, endNanos - this.buildStartNanos);
            this.buildStartNanos = Long.MIN_VALUE;
            return elapsed;
        }

        private void attachRawLease(final RawArchiveLease lease) {
            if (this.closed.get() || !this.rawLease.compareAndSet(null, lease)) {
                lease.close();
                return;
            }
            if (this.closed.get()) {
                closeRawLease(this.rawLease.getAndSet(null));
            }
        }

        private RawArchiveLease takeRawLease() {
            return this.rawLease.getAndSet(null);
        }

        private boolean isClosed() {
            return this.closed.get();
        }

        public ArchiveDigest digest() {
            return this.digest;
        }

        public boolean leader() {
            return this.leader;
        }

        public CompletableFuture<Path> path() {
            return this.path;
        }

        /** 当本次认领拥有该 flight 时以 true 完成；若 flight 在其被提升之前结束则为 false。 */
        public CompletableFuture<Boolean> leadership() {
            return this.leadership.thenApply(promoted -> promoted);
        }

        /** 释放未被消费的、从认领到展开的原始 CAS 租约。可安全重复调用。 */
        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                closeRawLease(this.rawLease.getAndSet(null));
            }
        }
    }

    private static final class RawArchiveLease implements AutoCloseable {

        private final ResourcePackArchiveStore owner;
        private final Path archive;
        private final ArchiveDigest digest;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RawArchiveLease(final ResourcePackArchiveStore owner, final Path archive,
                                final ArchiveDigest digest) {
            this.owner = owner;
            this.archive = archive;
            this.digest = digest;
        }

        private Path archive() {
            return this.archive;
        }

        private boolean matches(final Path archive, final ArchiveDigest digest) {
            return this.archive.equals(archive.toAbsolutePath().normalize()) && this.digest.equals(digest);
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.owner.releaseRaw(this.archive);
            }
        }
    }

    public static final class CanonicalContentLease implements AutoCloseable {

        private final ResourcePackArchiveStore owner;
        private final Path canonical;
        private final ZipFileContent content;
        private final AtomicBoolean closed = new AtomicBoolean();

        private CanonicalContentLease(final ResourcePackArchiveStore owner, final Path canonical,
                                      final ZipFileContent content) {
            this.owner = owner;
            this.canonical = canonical;
            this.content = content;
        }

        public ZipFileContent content() {
            if (this.closed.get()) {
                throw new IllegalStateException("Canonical resource pack lease is closed");
            }
            return this.content;
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.owner.releaseCanonical(this.canonical);
            }
        }
    }

    private static final class CanonicalContentView extends Content {

        private final ResourcePackArchiveStore owner;
        private final ContentDigest digest;
        private final ZipFileContent index;
        private final ThreadLocal<CanonicalContentLease> readSession = new ThreadLocal<>();

        private CanonicalContentView(final ResourcePackArchiveStore owner, final ContentDigest digest) {
            this(owner, digest, null);
        }

        private CanonicalContentView(final ResourcePackArchiveStore owner, final ContentDigest digest,
                                     final ZipFileContent index) {
            this.owner = owner;
            this.digest = digest;
            this.index = index;
        }

        @Override
        public List<String> getFilesShallow(final String path, final String extension) {
            if (this.index != null) return this.index.getFilesShallow(path, extension);
            return this.withContent(content -> content.getFilesShallow(path, extension));
        }

        @Override
        public List<String> getFilesDeep(final String path, final String extension) {
            if (this.index != null) return this.index.getFilesDeep(path, extension);
            return this.withContent(content -> content.getFilesDeep(path, extension));
        }

        @Override
        public boolean contains(final String path) {
            if (this.index != null) return this.index.contains(path);
            return this.withContent(content -> content.contains(path));
        }

        @Override
        public byte[] get(final String path) {
            return this.withContent(content -> content.get(path));
        }

        @Override
        public InputStream open(final String path) throws IOException {
            final CanonicalContentLease session = this.readSession.get();
            if (session != null) {
                return session.content().open(path);
            }

            final CanonicalContentLease lease = this.owner.leaseCanonical(this.digest);
            final InputStream input;
            try {
                input = lease.content().open(path);
            } catch (IOException | RuntimeException | Error failure) {
                lease.close();
                throw failure;
            }
            if (input == null) {
                lease.close();
                return null;
            }
            return new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    IOException failure = null;
                    try {
                        super.close();
                    } catch (IOException e) {
                        failure = e;
                    } finally {
                        lease.close();
                    }
                    if (failure != null) throw failure;
                }
            };
        }

        @Override
        public long size(final String path) throws IOException {
            if (this.index != null) return this.index.size(path);
            final CanonicalContentLease session = this.readSession.get();
            if (session != null) return session.content().size(path);
            try (CanonicalContentLease lease = this.owner.leaseCanonical(this.digest)) {
                return lease.content().size(path);
            }
        }

        @Override
        public void visitFiles(final List<String> paths, final FileVisitor visitor) throws IOException {
            final CanonicalContentLease session = this.readSession.get();
            if (session != null) {
                session.content().visitFiles(paths, visitor);
                return;
            }
            try (CanonicalContentLease lease = this.owner.leaseCanonical(this.digest)) {
                lease.content().visitFiles(paths, visitor);
            }
        }

        @Override
        public <T> T withReadSession(final Supplier<T> action) {
            if (this.readSession.get() != null) return action.get();
            try (CanonicalContentLease lease = this.owner.leaseCanonical(this.digest)) {
                this.readSession.set(lease);
                return lease.content().withReadSession(action);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                this.readSession.remove();
            }
        }

        @Override
        public boolean put(final String path, final byte[] data) {
            throw new UnsupportedOperationException("Canonical resource pack view cannot be modified");
        }

        @Override
        public void writeZip(final Path target) throws IOException {
            final CanonicalContentLease session = this.readSession.get();
            if (session != null) {
                session.content().writeZip(target);
                return;
            }
            try (CanonicalContentLease lease = this.owner.leaseCanonical(this.digest)) {
                lease.content().writeZip(target);
            }
        }

        private <T> T withContent(final java.util.function.Function<ZipFileContent, T> action) {
            final CanonicalContentLease session = this.readSession.get();
            if (session != null) return action.apply(session.content());
            try (CanonicalContentLease lease = this.owner.leaseCanonical(this.digest)) {
                return action.apply(lease.content());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @FunctionalInterface
    public interface ArchiveLoader {
        byte[] load() throws Exception;
    }

    @FunctionalInterface
    public interface ArchivePathLoader {
        /**
         * 面向需要 {@link Path} 的加载器的兼容入口。加载器运行期间无法限制任意路径写入，
         * 因此 store 会在本方法返回后立即校验并删除超限的输出。新的加载器应使用
         * {@link ArchiveStreamLoader} 以获得强制写入上限。
         */
        void load(Path target) throws Exception;
    }

    @FunctionalInterface
    public interface ArchiveStreamLoader {
        void load(OutputStream output) throws Exception;
    }

    @FunctionalInterface
    private interface SourceArchiveLoader {
        /** 若摘要是流式处理时算出的则返回该摘要；路径兼容加载器返回 null。 */
        ArchiveDigest load(Path target) throws Exception;
    }

    private record ExpansionKey(ArchiveDigest archiveDigest, PackAlias alias, String keyFingerprint, String contentId) {
    }

    private record SourceKey(String source, PackAlias alias, String announcementSequenceFingerprint,
                             boolean trustedLookup) {
    }

    private record TrustedAliasKey(PackAlias alias, String announcementSequenceFingerprint) {
    }

    private record AliasObservation(ArchiveDigest archiveDigest, ContentDigest contentDigest) {
    }

    private static final class AliasContentHistory {
        private final Set<ContentDigest> samples = new LinkedHashSet<>();
        private boolean conflicting;

        private synchronized boolean observe(final ContentDigest digest) {
            if (this.samples.contains(digest)) return false;
            final boolean firstConflict = !this.samples.isEmpty() && !this.conflicting;
            if (!this.samples.isEmpty()) this.conflicting = true;
            if (this.samples.size() < ALIAS_HISTORY_MAX_SAMPLES) this.samples.add(digest);
            return firstConflict;
        }
    }

    private static final class TrustedObservationHistory {
        private final Set<AliasObservation> samples = new LinkedHashSet<>();
        private ContentDigest contentDigest;
        private boolean conflicting;

        private synchronized boolean observe(final AliasObservation observation) {
            if (this.contentDigest == null) {
                this.contentDigest = observation.contentDigest();
            } else if (!this.contentDigest.equals(observation.contentDigest())) {
                this.conflicting = true;
            }
            if (this.samples.size() < ALIAS_HISTORY_MAX_SAMPLES) this.samples.add(observation);
            return this.conflicting;
        }

        private synchronized ContentDigest uniqueContent() {
            return this.conflicting ? null : this.contentDigest;
        }
    }

    private record CacheEntry(Path path, long size, long lastAccessMillis, Tier tier) {
    }

    private record CanonicalPublication(Path path, boolean published) {
    }

    private record DiskUsage(long bytes, long files) {
    }

    private record TombstoneUsage(long bytes, long files, int individualFiles, boolean globalQuarantine) {
    }

    private record ArchiveLimits(long maxArchiveBytes, long maxExpandedBytes, long maxEntryBytes, int maxEntries,
                                 int maxCompressionRatio) {
    }

    private static final class SizeLimitedOutputStream extends FilterOutputStream {
        private final long maxBytes;
        private long written;

        private SizeLimitedOutputStream(final OutputStream output, final long maxBytes) {
            super(output);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(final int value) throws IOException {
            this.reserve(1L);
            this.out.write(value);
        }

        @Override
        public void write(final byte[] data, final int offset, final int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, data.length);
            this.reserve(length);
            this.out.write(data, offset, length);
        }

        private void reserve(final long bytes) throws IOException {
            if (bytes > this.maxBytes - this.written) {
                throw new IOException("Resource pack archive exceeds the configured size limit");
            }
            this.written += bytes;
        }
    }

    private static final class ExtractionBudget {
        private final long ratioLimit;
        private long expandedBytes;
        private int entries;

        private ExtractionBudget(final long ratioLimit) {
            this.ratioLimit = ratioLimit;
        }
    }

}
