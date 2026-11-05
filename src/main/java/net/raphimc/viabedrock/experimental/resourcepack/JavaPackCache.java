/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.resourcepack;

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArtifactKey;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.cache.PackAlias;
import net.raphimc.viabedrock.api.resourcepack.cache.PackMount;
import net.raphimc.viabedrock.api.resourcepack.cache.RuntimeStackKey;
import net.raphimc.viabedrock.experimental.resourcepack.cache.FailureBackoff;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class JavaPackCache {

    // 每当基岩→Java 转换输出发生变化时递增，使过期的缓存包（由旧版转换器生成）
    // 自然无法命中并被重新生成，而不是被发送出去。
    public static final int CONVERTER_VERSION = 8;
    private static final long MIB = 1024L * 1024L;
    private static final long DEFAULT_DISK_BUDGET_BYTES = 20_480L * MIB;
    private static final long DEFAULT_DISK_IDLE_MILLIS = TimeUnit.DAYS.toMillis(7L);
    private static final long MAINTENANCE_INTERVAL_MILLIS = TimeUnit.HOURS.toMillis(1L);
    private static final long ACCESS_TOUCH_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(10L);
    private static final long TEMP_MAX_AGE_MILLIS = TimeUnit.HOURS.toMillis(1L);
    private static final long BUDGET_EVICTION_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(1L);
    private static final Pattern SHA1_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern CACHE_KEY_PATTERN = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    private static final Pattern CACHE_TEMP_PATTERN = Pattern.compile(
            "((?:[0-9a-f]{40}|[0-9a-f]{64}))-.+\\.(?:zip|sha1)\\.tmp");
    private static final Pattern ARTIFACT_TEMP_PATTERN = Pattern.compile("([0-9a-f]{40})-.+\\.zip\\.tmp");
    private static final Pattern BUILD_WORKSPACE_PATTERN = Pattern.compile(
            "build-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.tmp");
    private static final int ARTIFACT_PUBLICATION_LOCK_STRIPES = 64;

    private final File cacheFolder;
    private final File artifactsFolder;
    private final File buildWorkFolder;
    private final Map<String, ValidatedEntry> validatedEntries = new HashMap<>();
    private final Map<String, CompletableFuture<ArtifactRef>> inflight = new ConcurrentHashMap<>();
    private final FailureBackoff<String> failureBackoff;
    private final ResourcePackWorkScheduler scheduler;
    private final ResourcePackCacheMetrics metrics;
    private final long diskBudgetBytes;
    private final long diskIdleMillis;
    private final LongSupplier currentTimeMillis;
    private final AtomicLong nextMaintenanceMillis = new AtomicLong();
    private final AtomicBoolean maintenanceRunning = new AtomicBoolean();
    private final Map<String, Integer> activeArtifactLeases = new HashMap<>();
    private final Map<String, Integer> publishingArtifacts = new HashMap<>();
    private final Map<String, Integer> validatingCacheEntries = new HashMap<>();
    private final Set<Path> activeBuildWorkspaces = new HashSet<>();
    private final Object[] artifactPublicationLocks = publicationLocks();
    private final Object[] cacheEntryLocks = publicationLocks();
    private final Object cleanupLock = new Object();
    private final ScheduledFuture<?> maintenanceTask;

    private record FileStamp(long size, long modifiedTime) {
    }

    private record ValidatedEntry(String hash, FileStamp zip, FileStamp metadata, FileStamp artifact) {
    }

    private record CacheEntry(String cacheKey, String hash, Path zip, Path metadata, Path artifact,
                              long lastAccessMillis) {
    }

    private record DiskUsage(long bytes, long files) {
    }

    private record KeylessFileStamp(long size, FileTime creationTime, FileTime modifiedTime) {
    }

    private record CacheSnapshot(List<CacheEntry> entries, Map<String, Path> artifacts,
                                 DiskUsage usage, DiskUsage buildWorkspaceUsage) {
    }

    public JavaPackCache(final File cacheFolder) {
        this(cacheFolder, null, null);
    }

    public JavaPackCache(final File cacheFolder, final ResourcePackWorkScheduler scheduler, final ResourcePackCacheMetrics metrics) {
        this(cacheFolder, scheduler, metrics, System::nanoTime);
    }

    public JavaPackCache(final File cacheFolder, final ResourcePackWorkScheduler scheduler,
                         final ResourcePackCacheMetrics metrics, final int diskBudgetMiB,
                         final int diskIdleDays) {
        this(cacheFolder, scheduler, metrics, System::nanoTime, System::currentTimeMillis,
                Math.max(1L, diskBudgetMiB) * MIB,
                TimeUnit.DAYS.toMillis(Math.max(1L, diskIdleDays)));
    }

    JavaPackCache(final File cacheFolder, final ResourcePackWorkScheduler scheduler,
                  final ResourcePackCacheMetrics metrics, final LongSupplier nanoTime) {
        this(cacheFolder, scheduler, metrics, nanoTime, System::currentTimeMillis,
                DEFAULT_DISK_BUDGET_BYTES, DEFAULT_DISK_IDLE_MILLIS);
    }

    JavaPackCache(final File cacheFolder, final ResourcePackWorkScheduler scheduler,
                  final ResourcePackCacheMetrics metrics, final LongSupplier nanoTime,
                  final LongSupplier currentTimeMillis, final long diskBudgetBytes,
                  final long diskIdleMillis) {
        this.cacheFolder = cacheFolder;
        this.scheduler = scheduler;
        this.metrics = metrics;
        this.diskBudgetBytes = Math.max(1L, diskBudgetBytes);
        this.diskIdleMillis = Math.max(1L, diskIdleMillis);
        this.currentTimeMillis = currentTimeMillis;
        this.failureBackoff = new FailureBackoff<>(nanoTime);
        this.cacheFolder.mkdirs();
        this.artifactsFolder = new File(cacheFolder, "artifacts");
        this.artifactsFolder.mkdirs();
        this.buildWorkFolder = new File(cacheFolder, "work");
        this.buildWorkFolder.mkdirs();
        this.runStartupMaintenance();
        this.maintenanceTask = this.scheduler != null
                ? this.scheduler.scheduleIoAtFixedRate(this::runScheduledMaintenance,
                MAINTENANCE_INTERVAL_MILLIS, MAINTENANCE_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                : null;
    }

    public static String computeCacheKey(final Collection<ResourcePack> packs, final boolean supportsFreeRotation) {
        final String raw = packs.stream()
                .map(ResourcePack::key)
                .map(ResourcePack.Key::toString)
                .collect(Collectors.joining(","));
        return sha1Hex(withConverterTag(raw, supportsFreeRotation).getBytes(StandardCharsets.UTF_8));
    }

    public static String computeExactCacheKey(final Collection<ResourcePack> packs, final boolean supportsFreeRotation) {
        return computeExactCacheKey(packs, supportsFreeRotation, ResourcePackRewriter.rewriterFingerprint());
    }

    public static String computeExactCacheKey(final Collection<ResourcePack> packs, final boolean supportsFreeRotation,
                                              final String rewriterFingerprint) {
        final ArrayList<PackMount> mounts = new ArrayList<>(packs.size());
        for (ResourcePack pack : packs) {
            mounts.add(new PackMount(PackAlias.from(pack.key()), ContentDigest.compute(pack.content())));
        }
        return ArtifactKey.compute(RuntimeStackKey.compute(mounts), CONVERTER_VERSION,
                rewriterFingerprint, supportsFreeRotation).hex();
    }

    public static String computeCacheKey(final ResourcePack.Key[] keys, final boolean supportsFreeRotation) {
        final String raw = Arrays.stream(keys)
                .map(ResourcePack.Key::toString)
                .collect(Collectors.joining(","));
        return sha1Hex(withConverterTag(raw, supportsFreeRotation).getBytes(StandardCharsets.UTF_8));
    }

    // 转换输出取决于转换器版本和目标客户端的模型旋转能力，因此两者都必须是
    // 缓存键的一部分。否则为 <1.21.11 客户端转换的包（已剥离旋转）可能被
    // 发给 >=1.21.11 客户端，反之亦然。
    private static String withConverterTag(final String raw, final boolean supportsFreeRotation) {
        return raw + "|cv" + CONVERTER_VERSION + "|fr" + supportsFreeRotation;
    }

    public boolean has(final String cacheKey) {
        try {
            return this.getValidHash(cacheKey) != null;
        } catch (IOException e) {
            return false;
        }
    }

    public String getHash(final String cacheKey) throws IOException {
        final String hash = this.getValidHash(cacheKey);
        if (hash == null) {
            throw new IOException("Invalid or missing Java resource pack cache entry: " + cacheKey);
        }
        return hash;
    }

    @Deprecated(forRemoval = false)
    public byte[] getData(final String cacheKey) throws IOException {
        this.beginCacheEntryValidation(cacheKey);
        try {
            synchronized (this.cacheEntryLock(cacheKey)) {
                if (this.getValidHashLocked(cacheKey) == null) {
                    throw new IOException("Invalid or missing Java resource pack cache entry: " + cacheKey);
                }
                return Files.readAllBytes(getZipFile(cacheKey).toPath());
            }
        } finally {
            this.endCacheEntryValidation(cacheKey);
        }
    }

    public CompletableFuture<ArtifactRef> getOrBuild(final String cacheKey, final ArtifactBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        return this.getOrBuildInternal(cacheKey, target -> {
            builder.build(target);
            return null;
        });
    }

    /**
     * 发布一个由构建器在写入最终字节时顺带算出 SHA-1 的产物。缓存仍会校验
     * 上报的大小和已有的内容寻址目标，但避免仅仅为了计算协议哈希
     * 而把刚转换出的数 GB ZIP 再读一遍。
     */
    public CompletableFuture<ArtifactRef> getOrBuildHashed(final String cacheKey,
                                                            final HashedArtifactBuilder builder) {
        Objects.requireNonNull(builder, "builder");
        return this.getOrBuildInternal(cacheKey, builder::build);
    }

    private CompletableFuture<ArtifactRef> getOrBuildInternal(final String cacheKey,
                                                               final ArtifactBuildOperation builder) {
        this.requestMaintenance();
        try {
            final ArtifactRef cached = this.getArtifact(cacheKey);
            if (cached != null) {
                this.hit();
                return CompletableFuture.completedFuture(cached);
            }
        } catch (IOException e) {
            // 无效条目会走正常的 single-flight 路径重建。
        }

        this.miss();
        final Throwable recentFailure = this.failureBackoff.getIfActive(cacheKey);
        if (recentFailure != null) {
            return CompletableFuture.failedFuture(recentFailure);
        }
        this.afterArtifactCacheMissBeforeFlight(cacheKey);
        final CompletableFuture<ArtifactRef> existing = this.inflight.get(cacheKey);
        if (existing != null) {
            this.waiter();
            return existing.thenApply(value -> value);
        }

        final CompletableFuture<ArtifactRef> created = new CompletableFuture<>();
        final CompletableFuture<ArtifactRef> raced = this.inflight.putIfAbsent(cacheKey, created);
        if (raced != null) {
            this.waiter();
            return raced.thenApply(value -> value);
        }
        final Throwable racedFailure = this.failureBackoff.getIfActive(cacheKey);
        if (racedFailure != null) {
            this.inflight.remove(cacheKey, created);
            this.updateInflight();
            created.completeExceptionally(racedFailure);
            return created.thenApply(value -> value);
        }
        this.updateInflight();

        try {
            final ArtifactRef completedWhileRegistering = this.getArtifact(cacheKey);
            if (completedWhileRegistering != null) {
                this.failureBackoff.clear(cacheKey);
                this.hit();
                this.inflight.remove(cacheKey, created);
                this.updateInflight();
                created.complete(completedWhileRegistering);
                return created.thenApply(value -> value);
            }
        } catch (IOException e) {
            // 无效条目由本次 flight 重建。
        }
        this.build();

        final Runnable buildTask = () -> {
            final long start = System.nanoTime();
            Path temp = null;
            ArtifactRef artifact = null;
            Throwable buildError = null;
            try {
                temp = Files.createTempFile(this.cacheFolder.toPath(), cacheKey + "-", ".zip.tmp");
                final ArtifactBuildResult buildResult = builder.build(temp);
                artifact = buildResult != null
                        ? this.publish(cacheKey, temp, buildResult)
                        : this.publish(cacheKey, temp);
                temp = null;
            } catch (Throwable e) {
                this.failure();
                this.failureBackoff.record(cacheKey, e);
                buildError = e;
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException ignored) {
                    }
                }
                this.inflight.remove(cacheKey, created);
                this.updateInflight();
                if (this.metrics != null) {
                    this.metrics.recordBuildTime(Tier.ARTIFACT,
                            (System.nanoTime() - start) / 1_000_000L);
                }
            }
            if (buildError == null) {
                this.failureBackoff.clear(cacheKey);
                created.complete(artifact);
            } else {
                created.completeExceptionally(buildError);
            }
        };
        if (this.scheduler != null) {
            this.scheduler.runCpu(buildTask).exceptionally(error -> {
                if (!created.isDone()) {
                    this.failure();
                    this.failureBackoff.record(cacheKey, error);
                    this.inflight.remove(cacheKey, created);
                    this.updateInflight();
                    created.completeExceptionally(error);
                }
                return null;
            });
        } else {
            buildTask.run();
        }
        return created.thenApply(value -> value);
    }

    public CompletableFuture<ArtifactRef> getOrBuild(final ArtifactKey artifactKey, final ArtifactBuilder builder) {
        return this.getOrBuild(artifactKey.hex(), builder);
    }

    public CompletableFuture<ArtifactRef> getOrBuildHashed(final ArtifactKey artifactKey,
                                                            final HashedArtifactBuilder builder) {
        return this.getOrBuildHashed(artifactKey.hex(), builder);
    }

    /**
     * 从查找开始到取得租约为止一直固定住缓存条目，堵住维护流程可能在
     * HTTP 路径开始流式发送之前删掉已完成产物的窗口。
     */
    public CompletableFuture<ArtifactLease> getOrBuildLease(final String cacheKey, final ArtifactBuilder builder) {
        this.beginCacheEntryValidation(cacheKey);
        final CompletableFuture<ArtifactLease> dependent = new CompletableFuture<>();
        dependent.whenComplete((value, error) -> this.endCacheEntryValidation(cacheKey));

        final CompletableFuture<ArtifactRef> artifactFuture;
        try {
            artifactFuture = this.getOrBuild(cacheKey, builder);
        } catch (Throwable error) {
            dependent.completeExceptionally(error);
            return dependent;
        }
        this.completeLeaseFromArtifactFuture(dependent, artifactFuture);
        return dependent;
    }

    private void completeLeaseFromArtifactFuture(final CompletableFuture<ArtifactLease> dependent,
                                                  final CompletableFuture<ArtifactRef> artifactFuture) {
        artifactFuture.whenComplete((artifact, error) -> {
            if (dependent.isDone()) {
                return;
            }
            if (error != null) {
                dependent.completeExceptionally(error);
                return;
            }

            ArtifactLease lease = null;
            try {
                lease = this.acquireArtifact(artifact);
                if (lease == null) {
                    throw new IOException("Java resource pack artifact disappeared before lease acquisition: "
                            + artifact.hash());
                }
                if (!dependent.complete(lease)) {
                    lease.close();
                }
            } catch (Throwable acquireError) {
                if (lease != null) lease.close();
                dependent.completeExceptionally(acquireError);
            }
        });
    }

    public CompletableFuture<ArtifactLease> getOrBuildLease(final ArtifactKey artifactKey,
                                                              final ArtifactBuilder builder) {
        return this.getOrBuildLease(artifactKey.hex(), builder);
    }

    public CompletableFuture<ArtifactLease> getOrBuildLeaseHashed(final String cacheKey,
                                                                   final HashedArtifactBuilder builder) {
        this.beginCacheEntryValidation(cacheKey);
        final CompletableFuture<ArtifactLease> dependent = new CompletableFuture<>();
        dependent.whenComplete((value, error) -> this.endCacheEntryValidation(cacheKey));

        final CompletableFuture<ArtifactRef> artifactFuture;
        try {
            artifactFuture = this.getOrBuildHashed(cacheKey, builder);
        } catch (Throwable error) {
            dependent.completeExceptionally(error);
            return dependent;
        }
        this.completeLeaseFromArtifactFuture(dependent, artifactFuture);
        return dependent;
    }

    public CompletableFuture<ArtifactLease> getOrBuildLeaseHashed(final ArtifactKey artifactKey,
                                                                   final HashedArtifactBuilder builder) {
        return this.getOrBuildLeaseHashed(artifactKey.hex(), builder);
    }

    public ArtifactRef getArtifact(final String cacheKey) throws IOException {
        this.requestMaintenance();
        this.beginCacheEntryValidation(cacheKey);
        try {
            synchronized (this.cacheEntryLock(cacheKey)) {
                final String hash = this.getValidHashLocked(cacheKey);
                if (hash == null) {
                    return null;
                }
                final Path path = this.getArtifactFile(hash).toPath();
                return new ArtifactRef(cacheKey, hash, path, Files.size(path));
            }
        } finally {
            this.endCacheEntryValidation(cacheKey);
        }
    }

    public ArtifactLease acquireArtifact(final String hash) throws IOException {
        this.requestMaintenance();
        return this.acquireArtifact("", hash);
    }

    public ArtifactLease acquireArtifact(final ArtifactRef artifact) throws IOException {
        this.requestMaintenance();
        return this.acquireArtifact(artifact.cacheKey(), artifact.hash());
    }

    /** 为单次基于文件的转换创建一个由清理器管理、计入配额的目录。 */
    public BuildWorkspace openBuildWorkspace() throws IOException {
        final Path workspace;
        synchronized (this.cleanupLock) {
            Path candidate;
            do {
                candidate = this.buildWorkFolder.toPath()
                        .resolve("build-" + UUID.randomUUID() + ".tmp")
                        .toAbsolutePath().normalize();
            } while (Files.exists(candidate));
            workspace = candidate;
            Files.createDirectory(workspace);
            synchronized (this) {
                this.activeBuildWorkspaces.add(workspace);
                this.updateBuildWorkspaceMetrics();
            }
        }
        return new BuildWorkspace(this, workspace);
    }

    private ArtifactLease acquireArtifact(final String cacheKey, final String hash) throws IOException {
        if (!SHA1_PATTERN.matcher(hash).matches()) {
            throw new IllegalArgumentException("Invalid SHA-1: " + hash);
        }
        synchronized (this.artifactPublicationLock(hash)) {
            final Path path = this.getArtifactFile(hash).toPath();
            if (!Files.isRegularFile(path)) {
                return null;
            }
            this.touchArtifact(path, hash);
            final long size = this.artifactSize(path);
            synchronized (this) {
                this.activeArtifactLeases.merge(hash, 1, Integer::sum);
                this.updateArtifactLeases();
            }
            return new ArtifactLease(this, new ArtifactRef(cacheKey, hash, path, size));
        }
    }

    private synchronized void releaseArtifact(final String hash) {
        final Integer leases = this.activeArtifactLeases.get(hash);
        if (leases == null) {
            return;
        }
        if (leases == 1) {
            this.activeArtifactLeases.remove(hash);
        } else {
            this.activeArtifactLeases.put(hash, leases - 1);
        }
        this.updateArtifactLeases();
    }

    private void releaseBuildWorkspace(final Path workspace) throws IOException {
        IOException failure = null;
        DiskUsage removed = new DiskUsage(0L, 0L);
        synchronized (this.cleanupLock) {
            try {
                removed = workspaceDiskUsage(workspace);
                deleteRecursively(workspace);
            } catch (IOException e) {
                failure = e;
                this.buildWorkspaceCleanupFailure();
            } finally {
                synchronized (this) {
                    this.activeBuildWorkspaces.remove(workspace);
                    this.updateBuildWorkspaceMetrics();
                }
            }
        }
        this.recordBuildWorkspaceCleanup(removed);
        this.refreshDiskMetrics();
        if (failure != null) {
            throw failure;
        }
    }

    @Deprecated(forRemoval = false)
    public String put(final String cacheKey, final byte[] zipData) throws IOException {
        this.requestMaintenance();
        final Path zipTemp = Files.createTempFile(this.cacheFolder.toPath(), cacheKey + "-", ".zip.tmp");
        try {
            Files.write(zipTemp, zipData);
            return this.publish(cacheKey, zipTemp).hash();
        } finally {
            Files.deleteIfExists(zipTemp);
        }
    }

    private ArtifactRef publish(final String cacheKey, final Path completedTemp) throws IOException {
        final String hash = this.computeArtifactHash(completedTemp);
        return this.publish(cacheKey, completedTemp, hash);
    }

    private ArtifactRef publish(final String cacheKey, final Path completedTemp,
                                final ArtifactBuildResult buildResult) throws IOException {
        if (!Files.isRegularFile(completedTemp)) {
            throw new IOException("Java resource pack builder did not produce a regular file");
        }
        final long actualSize = Files.size(completedTemp);
        if (actualSize != buildResult.size()) {
            throw new IOException("Java resource pack builder reported " + buildResult.size()
                    + " bytes but produced " + actualSize + " bytes");
        }
        return this.publish(cacheKey, completedTemp, buildResult.sha1());
    }

    private ArtifactRef publish(final String cacheKey, final Path completedTemp,
                                final String hash) throws IOException {
        final Object artifactPublicationLock = this.artifactPublicationLock(hash);
        this.beginArtifactPublication(hash);
        try {
            synchronized (artifactPublicationLock) {
                this.publishArtifact(completedTemp, hash);
            }
            final Path hashTemp = Files.createTempFile(this.cacheFolder.toPath(), cacheKey + "-", ".sha1.tmp");
            try {
                Files.writeString(hashTemp, hash, StandardCharsets.UTF_8);
                return this.commitPublishedEntry(cacheKey, completedTemp, hashTemp, hash);
            } finally {
                Files.deleteIfExists(hashTemp);
            }
        } finally {
            this.endArtifactPublication(hash);
            this.refreshDiskMetrics();
        }
    }

    /** 只把简短的缓存键切换串行化；哈希计算和产物落盘都不占用全局锁。 */
    private ArtifactRef commitPublishedEntry(final String cacheKey, final Path completedTemp,
                                             final Path hashTemp, final String hash) throws IOException {
        synchronized (this.cacheEntryLock(cacheKey)) {
            return this.commitPublishedEntryLocked(cacheKey, completedTemp, hashTemp, hash);
        }
    }

    private synchronized ArtifactRef commitPublishedEntryLocked(final String cacheKey, final Path completedTemp,
                                                                final Path hashTemp, final String hash) throws IOException {
        this.validatedEntries.remove(cacheKey);
        final Path zipFile = getZipFile(cacheKey).toPath();
        final Path hashFile = getHashFile(cacheKey).toPath();
        boolean published = false;
        try {
            moveAtomically(completedTemp, zipFile);
            moveAtomically(hashTemp, hashFile);
            final Path artifact = this.getArtifactFile(hash).toPath();
            this.validatedEntries.put(cacheKey, new ValidatedEntry(
                    hash, stamp(zipFile), stamp(hashFile), artifactStamp(artifact)));
            published = true;
            return new ArtifactRef(cacheKey, hash, artifact, Files.size(artifact));
        } finally {
            if (!published) {
                this.validatedEntries.remove(cacheKey);
                try {
                    Files.deleteIfExists(hashFile);
                } finally {
                    Files.deleteIfExists(zipFile);
                }
            }
        }
    }

    private synchronized void beginArtifactPublication(final String hash) {
        this.publishingArtifacts.merge(hash, 1, Integer::sum);
    }

    private synchronized void endArtifactPublication(final String hash) {
        final Integer publications = this.publishingArtifacts.get(hash);
        if (publications == null || publications <= 1) {
            this.publishingArtifacts.remove(hash);
        } else {
            this.publishingArtifacts.put(hash, publications - 1);
        }
    }

    public String getValidHash(final String cacheKey) throws IOException {
        this.beginCacheEntryValidation(cacheKey);
        try {
            synchronized (this.cacheEntryLock(cacheKey)) {
                return this.getValidHashLocked(cacheKey);
            }
        } finally {
            this.endCacheEntryValidation(cacheKey);
        }
    }

    private String getValidHashLocked(final String cacheKey) throws IOException {
        final Path zipFile = getZipFile(cacheKey).toPath();
        final Path hashFile = getHashFile(cacheKey).toPath();
        final String expectedHash;
        final Path artifact;
        final boolean invalidMetadata;
        synchronized (this) {
            if (!Files.isRegularFile(zipFile) || !Files.isRegularFile(hashFile)) {
                this.validatedEntries.remove(cacheKey);
                return null;
            }

            expectedHash = Files.readString(hashFile, StandardCharsets.UTF_8).trim();
            invalidMetadata = !SHA1_PATTERN.matcher(expectedHash).matches();
            if (invalidMetadata) {
                artifact = null;
            } else {
                artifact = this.getArtifactFile(expectedHash).toPath();
                final ValidatedEntry validated = this.validatedEntries.get(cacheKey);
                if (validated != null
                        && validated.hash().equals(expectedHash)
                        && validated.zip().equals(stamp(zipFile))
                        && validated.metadata().equals(stamp(hashFile))
                        && Files.isRegularFile(artifact)
                        && validated.artifact().equals(artifactStamp(artifact))) {
                    this.touchArtifact(artifact, expectedHash);
                    this.validatedEntries.put(cacheKey, new ValidatedEntry(
                            expectedHash, stamp(zipFile), stamp(hashFile), artifactStamp(artifact)));
                    return expectedHash;
                }
            }
        }

        if (invalidMetadata) {
            this.invalidate(cacheKey, zipFile, hashFile);
            return null;
        }

        if (!expectedHash.equals(this.computeCacheEntryHash(zipFile))) {
            this.invalidate(cacheKey, zipFile, hashFile);
            return null;
        }

        this.beginArtifactPublication(expectedHash);
        try {
            synchronized (this.artifactPublicationLock(expectedHash)) {
                this.publishArtifact(zipFile, expectedHash);
            }
        } finally {
            this.endArtifactPublication(expectedHash);
        }
        synchronized (this) {
            this.touchArtifact(artifact, expectedHash);
            this.validatedEntries.put(cacheKey, new ValidatedEntry(
                    expectedHash, stamp(zipFile), stamp(hashFile), artifactStamp(artifact)));
        }
        return expectedHash;
    }

    private void invalidate(final String cacheKey, final Path zipFile, final Path hashFile) throws IOException {
        synchronized (this) {
            this.validatedEntries.remove(cacheKey);
        }
        try {
            try {
                Files.deleteIfExists(hashFile);
            } finally {
                Files.deleteIfExists(zipFile);
            }
        } finally {
            this.refreshDiskMetrics();
        }
    }

    public File getArtifactFile(final String hash) {
        if (!SHA1_PATTERN.matcher(hash).matches()) {
            throw new IllegalArgumentException("Invalid SHA-1: " + hash);
        }
        return new File(this.artifactsFolder, hash + ".zip");
    }

    private void publishArtifact(final Path cacheZip, final String hash) throws IOException {
        final Path artifact = this.getArtifactFile(hash).toPath();
        if (Files.isRegularFile(artifact)) {
            if (Files.isSameFile(cacheZip, artifact) || Files.mismatch(cacheZip, artifact) == -1L) {
                return;
            }
            throw new IOException("Java resource pack SHA-1 collision or conflicting artifact content: " + hash);
        }

        final Path temp = Files.createTempFile(this.artifactsFolder.toPath(), hash + "-", ".zip.tmp");
        try {
            Files.delete(temp);
            try {
                Files.createLink(temp, cacheZip);
            } catch (UnsupportedOperationException | IOException e) {
                Files.copy(cacheZip, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            moveAtomically(temp, artifact);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void moveAtomically(final Path source, final Path target) throws IOException {
        final CopyOption[] atomicOptions = {StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING};
        Files.move(source, target, atomicOptions);
    }

    private static FileStamp stamp(final Path path) throws IOException {
        return new FileStamp(Files.size(path), Files.getLastModifiedTime(path).toMillis());
    }

    private static FileStamp artifactStamp(final Path path) throws IOException {
        return new FileStamp(Files.size(path), 0L);
    }

    String computeArtifactHash(final Path path) throws IOException {
        return sha1Hex(path);
    }

    String computeCacheEntryHash(final Path path) throws IOException {
        return sha1Hex(path);
    }

    void afterArtifactCacheMissBeforeFlight(final String cacheKey) {
    }

    long artifactSize(final Path path) throws IOException {
        return Files.size(path);
    }

    private Object artifactPublicationLock(final String hash) {
        return this.artifactPublicationLocks[Math.floorMod(hash.hashCode(), this.artifactPublicationLocks.length)];
    }

    private Object cacheEntryLock(final String cacheKey) {
        return this.cacheEntryLocks[Math.floorMod(cacheKey.hashCode(), this.cacheEntryLocks.length)];
    }

    private synchronized void beginCacheEntryValidation(final String cacheKey) {
        this.validatingCacheEntries.merge(cacheKey, 1, Integer::sum);
    }

    private synchronized void endCacheEntryValidation(final String cacheKey) {
        final Integer validations = this.validatingCacheEntries.get(cacheKey);
        if (validations == null || validations <= 1) {
            this.validatingCacheEntries.remove(cacheKey);
        } else {
            this.validatingCacheEntries.put(cacheKey, validations - 1);
        }
    }

    private static Object[] publicationLocks() {
        final Object[] locks = new Object[ARTIFACT_PUBLICATION_LOCK_STRIPES];
        Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    private void touchArtifact(final Path path, final String hash) throws IOException {
        final long now = this.currentTimeMillis.getAsLong();
        if (Files.getLastModifiedTime(path).toMillis() <= now - ACCESS_TOUCH_INTERVAL_MILLIS) {
            Files.setLastModifiedTime(path, FileTime.fromMillis(now));
            // 产物和缓存 ZIP 通常是硬链接。刷新该哈希下所有已校验的时间戳，
            // 以免访问时间被触碰后下一次热点查找退化成完整的 SHA-1 计算。
            final Map<String, ValidatedEntry> matching = new HashMap<>();
            synchronized (this) {
                for (Map.Entry<String, ValidatedEntry> entry : this.validatedEntries.entrySet()) {
                    if (entry.getValue().hash().equals(hash)) {
                        matching.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            for (Map.Entry<String, ValidatedEntry> entry : matching.entrySet()) {
                final Path zip = this.getZipFile(entry.getKey()).toPath();
                final Path metadata = this.getHashFile(entry.getKey()).toPath();
                if (Files.isRegularFile(zip) && Files.isRegularFile(metadata) && Files.isRegularFile(path)) {
                    final ValidatedEntry refreshed = new ValidatedEntry(
                            hash, stamp(zip), stamp(metadata), artifactStamp(path));
                    synchronized (this) {
                        if (this.validatedEntries.get(entry.getKey()) == entry.getValue()) {
                            this.validatedEntries.put(entry.getKey(), refreshed);
                        }
                    }
                }
            }
        }
    }

    private void runStartupMaintenance() {
        try {
            synchronized (this.cleanupLock) {
                this.cleanupManagedFiles(true);
            }
        } catch (IOException e) {
            this.cleanupFailure();
        } finally {
            this.nextMaintenanceMillis.set(this.currentTimeMillis.getAsLong() + MAINTENANCE_INTERVAL_MILLIS);
        }
    }

    void cleanupNow() throws IOException {
        try {
            synchronized (this.cleanupLock) {
                this.cleanupManagedFiles(false);
            }
        } catch (IOException e) {
            this.cleanupFailure();
            throw e;
        } finally {
            this.nextMaintenanceMillis.set(this.currentTimeMillis.getAsLong() + MAINTENANCE_INTERVAL_MILLIS);
        }
    }

    boolean hasScheduledMaintenance() {
        return this.maintenanceTask != null && !this.maintenanceTask.isCancelled();
    }

    private void runScheduledMaintenance() {
        if (!this.maintenanceRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            this.cleanupNow();
        } catch (IOException ignored) {
        } finally {
            this.maintenanceRunning.set(false);
        }
    }

    private void requestMaintenance() {
        final long now = this.currentTimeMillis.getAsLong();
        final long next = this.nextMaintenanceMillis.get();
        if (now < next || !this.nextMaintenanceMillis.compareAndSet(next, now + MAINTENANCE_INTERVAL_MILLIS)
                || !this.maintenanceRunning.compareAndSet(false, true)) {
            return;
        }

        final Runnable task = () -> {
            try {
                this.cleanupNow();
            } catch (IOException ignored) {
            } finally {
                this.maintenanceRunning.set(false);
            }
        };
        if (this.scheduler == null) {
            task.run();
            return;
        }
        this.scheduler.runIo(task).whenComplete((ignored, error) -> {
            if (error != null && this.maintenanceRunning.compareAndSet(true, false)) {
                this.cleanupFailure();
                this.nextMaintenanceMillis.set(this.currentTimeMillis.getAsLong());
            }
        });
    }

    private void cleanupManagedFiles(final boolean startup) throws IOException {
        final long now = this.currentTimeMillis.getAsLong();
        this.cleanupManagedTemps(now, startup);
        this.cleanupManagedBuildWorkspaces(now, startup);
        final CacheSnapshot before = this.scanCache();
        final long idleCutoff = now - this.diskIdleMillis;

        final List<CacheEntry> idleEntries = new ArrayList<>(before.entries());
        idleEntries.sort(Comparator.comparingLong(CacheEntry::lastAccessMillis));
        for (CacheEntry entry : idleEntries) {
            if (entry.lastAccessMillis() >= idleCutoff) {
                continue;
            }
            this.deleteEntryIfEligible(entry, idleCutoff);
        }
        this.deleteIdleOrphanArtifacts(this.scanCache(), idleCutoff);

        CacheSnapshot current = this.scanCache();
        final long budgetGraceCutoff = now - BUDGET_EVICTION_GRACE_MILLIS;
        while (current.usage().bytes() > this.diskBudgetBytes) {
            final Path oldestWorkspace = this.oldestBuildWorkspaceEligibleForDeletion(budgetGraceCutoff);
            if (oldestWorkspace != null) {
                this.deleteBuildWorkspaceIfEligible(oldestWorkspace, budgetGraceCutoff);
                current = this.scanCache();
                continue;
            }

            final CacheEntry oldestEntry = current.entries().stream()
                    .filter(entry -> entry.lastAccessMillis() < budgetGraceCutoff)
                    .filter(entry -> !this.isProtected(entry))
                    .min(Comparator.comparingLong(CacheEntry::lastAccessMillis))
                    .orElse(null);
            if (oldestEntry != null) {
                this.deleteEntryIfEligible(oldestEntry, budgetGraceCutoff);
                current = this.scanCache();
                continue;
            }

            final Set<String> referencedHashes = referencedHashes(current.entries());
            final Map.Entry<String, Path> oldestArtifact = current.artifacts().entrySet().stream()
                    .filter(entry -> !referencedHashes.contains(entry.getKey()))
                    .filter(entry -> lastModified(entry.getValue()) < budgetGraceCutoff)
                    .min(Comparator.comparingLong(entry -> lastModified(entry.getValue())))
                    .orElse(null);
            if (oldestArtifact == null) {
                break;
            }
            this.deleteArtifactIfEligible(oldestArtifact.getKey(), oldestArtifact.getValue(), budgetGraceCutoff);
            current = this.scanCache();
        }

        final CacheSnapshot after = this.scanCache();
        this.removeMissingValidatedEntries();
        this.updateDiskMetrics(after);
        if (this.metrics != null) {
            this.metrics.recordArtifactDiskCleanup(
                    Math.max(0L, before.usage().bytes() - after.usage().bytes()),
                    Math.max(0L, before.usage().files() - after.usage().files()));
        }
    }

    private void cleanupManagedBuildWorkspaces(final long now, final boolean startup) throws IOException {
        final List<Path> workspaces = this.listManagedBuildWorkspaces();
        final long cutoff = now - TEMP_MAX_AGE_MILLIS;
        for (Path workspace : workspaces) {
            if (!startup && treeLastModified(workspace) >= cutoff) {
                continue;
            }
            this.deleteBuildWorkspaceIfEligible(workspace, startup ? Long.MAX_VALUE : cutoff);
        }
    }

    private Path oldestBuildWorkspaceEligibleForDeletion(final long accessCutoff) throws IOException {
        Path oldest = null;
        long oldestAccess = Long.MAX_VALUE;
        for (Path workspace : this.listManagedBuildWorkspaces()) {
            if (this.isBuildWorkspaceActive(workspace)) {
                continue;
            }
            final long access = treeLastModified(workspace);
            if (access < accessCutoff && access < oldestAccess) {
                oldest = workspace;
                oldestAccess = access;
            }
        }
        return oldest;
    }

    private List<Path> listManagedBuildWorkspaces() throws IOException {
        final List<Path> paths;
        try (var stream = Files.list(this.buildWorkFolder.toPath())) {
            paths = stream.toList();
        }
        return paths.stream()
                .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> BUILD_WORKSPACE_PATTERN.matcher(path.getFileName().toString()).matches())
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }

    private boolean deleteBuildWorkspaceIfEligible(final Path workspace,
                                                    final long accessCutoff) throws IOException {
        synchronized (this.cleanupLock) {
            if (this.isBuildWorkspaceActive(workspace) || treeLastModified(workspace) >= accessCutoff) {
                return false;
            }
            final DiskUsage removed = workspaceDiskUsage(workspace);
            try {
                deleteRecursively(workspace);
            } catch (IOException e) {
                this.buildWorkspaceCleanupFailure();
                throw e;
            }
            this.recordBuildWorkspaceCleanup(removed);
            if (removed.files() > 0L) {
                this.eviction();
            }
            return true;
        }
    }

    private synchronized boolean isBuildWorkspaceActive(final Path workspace) {
        return this.activeBuildWorkspaces.contains(workspace.toAbsolutePath().normalize());
    }

    private void cleanupManagedTemps(final long now, final boolean startup) throws IOException {
        this.cleanupManagedTemps(this.cacheFolder.toPath(), CACHE_TEMP_PATTERN, now, startup, true);
        this.cleanupManagedTemps(this.artifactsFolder.toPath(), ARTIFACT_TEMP_PATTERN, now, startup, false);
    }

    private void cleanupManagedTemps(final Path directory, final Pattern pattern, final long now,
                                     final boolean startup, final boolean cacheKeyTemp) throws IOException {
        final List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.toList();
        }
        for (Path file : files) {
            if (!isRegularFile(file)) {
                continue;
            }
            final var matcher = pattern.matcher(file.getFileName().toString());
            if (!matcher.matches() || !startup && lastModified(file) >= now - TEMP_MAX_AGE_MILLIS) {
                continue;
            }
            final String identity = matcher.group(1);
            final Object lock = cacheKeyTemp
                    ? this.cacheEntryLock(identity)
                    : this.artifactPublicationLock(identity);
            synchronized (lock) {
                if (cacheKeyTemp ? this.inflight.containsKey(identity)
                        : this.isArtifactProtected(identity)) {
                    continue;
                }
                Files.deleteIfExists(file);
            }
        }
    }

    private CacheSnapshot scanCache() throws IOException {
        this.beforeCacheScan();
        final Map<String, Path> artifacts = new HashMap<>();
        final Set<Path> managedFiles = new HashSet<>();
        final Set<Path> buildWorkspaceFiles = new HashSet<>();
        final List<Path> artifactFiles;
        try (var stream = Files.list(this.artifactsFolder.toPath())) {
            artifactFiles = stream.toList();
        }
        for (Path artifact : artifactFiles) {
            final String name = artifact.getFileName().toString();
            if (name.length() != 44 || !name.endsWith(".zip")
                    || !SHA1_PATTERN.matcher(name.substring(0, 40)).matches() || !isRegularFile(artifact)) {
                continue;
            }
            artifacts.put(name.substring(0, 40), artifact);
            managedFiles.add(artifact);
        }

        final Set<String> cacheKeys = new HashSet<>();
        final List<Path> rootFiles;
        try (var stream = Files.list(this.cacheFolder.toPath())) {
            rootFiles = stream.toList();
        }
        for (Path file : rootFiles) {
            if (!isRegularFile(file)) {
                continue;
            }
            final String name = file.getFileName().toString();
            final String cacheKey;
            if (name.endsWith(".sha1")) {
                cacheKey = name.substring(0, name.length() - 5);
            } else if (name.endsWith(".zip")) {
                cacheKey = name.substring(0, name.length() - 4);
            } else {
                continue;
            }
            if (CACHE_KEY_PATTERN.matcher(cacheKey).matches()) {
                cacheKeys.add(cacheKey);
            }
        }

        final List<CacheEntry> entries = new ArrayList<>(cacheKeys.size());
        for (String cacheKey : cacheKeys) {
            final Path zip = this.getZipFile(cacheKey).toPath();
            final Path metadata = this.getHashFile(cacheKey).toPath();
            if (isRegularFile(zip)) managedFiles.add(zip);
            if (isRegularFile(metadata)) managedFiles.add(metadata);

            String hash = "";
            if (isRegularFile(metadata)) {
                final String candidate = Files.readString(metadata, StandardCharsets.UTF_8).trim();
                if (SHA1_PATTERN.matcher(candidate).matches()) {
                    hash = candidate;
                }
            }
            final Path artifact = artifacts.get(hash);
            long lastAccess = 0L;
            if (isRegularFile(zip)) lastAccess = Math.max(lastAccess, lastModified(zip));
            if (isRegularFile(metadata)) lastAccess = Math.max(lastAccess, lastModified(metadata));
            if (artifact != null) lastAccess = Math.max(lastAccess, lastModified(artifact));
            entries.add(new CacheEntry(cacheKey, hash, zip, metadata, artifact, lastAccess));
        }
        for (Path workspace : this.listManagedBuildWorkspaces()) {
            try (var stream = Files.walk(workspace)) {
                stream.filter(JavaPackCache::isRegularFile).forEach(buildWorkspaceFiles::add);
            } catch (java.nio.file.NoSuchFileException ignored) {
                // 指标扫描进行期间，一次已完成的转换删除了它的工作目录。
            }
        }
        managedFiles.addAll(buildWorkspaceFiles);
        return new CacheSnapshot(entries, artifacts, diskUsage(managedFiles),
                diskUsage(buildWorkspaceFiles));
    }

    void beforeCacheScan() throws IOException {
    }

    private void deleteIdleOrphanArtifacts(final CacheSnapshot snapshot, final long idleCutoff) throws IOException {
        final Set<String> referencedHashes = referencedHashes(snapshot.entries());
        for (Map.Entry<String, Path> artifact : snapshot.artifacts().entrySet()) {
            if (referencedHashes.contains(artifact.getKey())
                    || lastModified(artifact.getValue()) >= idleCutoff) {
                continue;
            }
            this.deleteArtifactIfEligible(artifact.getKey(), artifact.getValue(), idleCutoff);
        }
    }

    private static Set<String> referencedHashes(final List<CacheEntry> entries) {
        final Set<String> hashes = new HashSet<>();
        for (CacheEntry entry : entries) {
            if (!entry.hash().isEmpty() && isRegularFile(entry.zip()) && isRegularFile(entry.metadata())) {
                hashes.add(entry.hash());
            }
        }
        return hashes;
    }

    private synchronized boolean isProtected(final CacheEntry entry) {
        return this.isProtectedLocked(entry);
    }

    private boolean isProtectedLocked(final CacheEntry entry) {
        return this.inflight.containsKey(entry.cacheKey())
                || this.validatingCacheEntries.containsKey(entry.cacheKey())
                || !entry.hash().isEmpty() && (this.activeArtifactLeases.containsKey(entry.hash())
                || this.publishingArtifacts.containsKey(entry.hash()));
    }

    private synchronized boolean isArtifactProtected(final String hash) {
        return this.activeArtifactLeases.containsKey(hash) || this.publishingArtifacts.containsKey(hash);
    }

    private boolean deleteEntryIfEligible(final CacheEntry entry, final long accessCutoff) throws IOException {
        synchronized (this.cacheEntryLock(entry.cacheKey())) {
            if (entry.hash().isEmpty()) {
                return this.deleteEntryIfEligibleLocked(entry, accessCutoff);
            }
            synchronized (this.artifactPublicationLock(entry.hash())) {
                return this.deleteEntryIfEligibleLocked(entry, accessCutoff);
            }
        }
    }

    private boolean deleteEntryIfEligibleLocked(final CacheEntry entry, final long accessCutoff) throws IOException {
        synchronized (this) {
            if (this.isProtectedLocked(entry)) {
                return false;
            }
        }
        if (currentLastAccess(entry) >= accessCutoff) {
            return false;
        }

        synchronized (this) {
            if (this.isProtectedLocked(entry)) {
                return false;
            }
            this.validatedEntries.remove(entry.cacheKey());
        }
        boolean deleted = Files.deleteIfExists(entry.metadata());
        deleted |= Files.deleteIfExists(entry.zip());
        if (deleted) {
            this.eviction();
        }
        return deleted;
    }

    private boolean deleteArtifactIfEligible(final String hash, final Path artifact,
                                             final long accessCutoff) throws IOException {
        synchronized (this.artifactPublicationLock(hash)) {
            if (this.isArtifactProtected(hash) || lastModified(artifact) >= accessCutoff
                    || this.isArtifactReferenced(hash)) {
                return false;
            }
            if (Files.deleteIfExists(artifact)) {
                this.eviction();
                return true;
            }
            return false;
        }
    }

    private boolean isArtifactReferenced(final String hash) throws IOException {
        synchronized (this) {
            if (this.validatedEntries.values().stream().anyMatch(entry -> entry.hash().equals(hash))) {
                return true;
            }
        }
        final List<Path> metadataFiles;
        try (var stream = Files.list(this.cacheFolder.toPath())) {
            metadataFiles = stream
                    .filter(path -> path.getFileName().toString().endsWith(".sha1"))
                    .toList();
        }
        for (Path metadata : metadataFiles) {
            if (!isRegularFile(metadata)) continue;
            try {
                if (hash.equals(Files.readString(metadata, StandardCharsets.UTF_8).trim())) {
                    return true;
                }
            } catch (java.nio.file.NoSuchFileException ignored) {
                // 本次读取与缓存键的发布或淘汰发生了竞态；由该条目的状态锁决定如何保护。
            }
        }
        return false;
    }

    private static long currentLastAccess(final CacheEntry entry) {
        long lastAccess = 0L;
        if (isRegularFile(entry.zip())) lastAccess = Math.max(lastAccess, lastModified(entry.zip()));
        if (isRegularFile(entry.metadata())) lastAccess = Math.max(lastAccess, lastModified(entry.metadata()));
        if (entry.artifact() != null && isRegularFile(entry.artifact())) {
            lastAccess = Math.max(lastAccess, lastModified(entry.artifact()));
        }
        return lastAccess;
    }

    private void removeMissingValidatedEntries() {
        final Map<String, ValidatedEntry> snapshot;
        synchronized (this) {
            snapshot = new HashMap<>(this.validatedEntries);
        }
        for (Map.Entry<String, ValidatedEntry> entry : snapshot.entrySet()) {
            if (isRegularFile(this.getZipFile(entry.getKey()).toPath())
                    && isRegularFile(this.getHashFile(entry.getKey()).toPath())) {
                continue;
            }
            synchronized (this) {
                this.validatedEntries.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private static DiskUsage diskUsage(final Set<Path> managedFiles) throws IOException {
        final Set<Object> physicalFileKeys = new HashSet<>();
        final Map<KeylessFileStamp, List<Path>> keylessFiles = new HashMap<>();
        long bytes = 0L;
        long files = 0L;
        for (Path path : managedFiles) {
            final BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                continue;
            }
            final Object fileKey = attributes.fileKey();
            boolean unique;
            if (fileKey != null) {
                unique = physicalFileKeys.add(fileKey);
            } else {
                unique = true;
                final KeylessFileStamp stamp = new KeylessFileStamp(
                        attributes.size(), attributes.creationTime(), attributes.lastModifiedTime());
                final List<Path> candidates = keylessFiles.computeIfAbsent(stamp, ignored -> new ArrayList<>());
                for (Path existing : candidates) {
                    if (Files.isSameFile(path, existing)) {
                        unique = false;
                        break;
                    }
                }
                if (unique) candidates.add(path);
            }
            if (unique) {
                bytes += attributes.size();
                files++;
            }
        }
        return new DiskUsage(bytes, files);
    }

    private static DiskUsage workspaceDiskUsage(final Path workspace) throws IOException {
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)) {
            return new DiskUsage(0L, 0L);
        }
        final Set<Path> files = new HashSet<>();
        try (var stream = Files.walk(workspace)) {
            stream.filter(JavaPackCache::isRegularFile).forEach(files::add);
        }
        return diskUsage(files);
    }

    private static long treeLastModified(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Long.MIN_VALUE;
        }
        long lastModified = lastModified(directory);
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.toList()) {
                lastModified = Math.max(lastModified, lastModified(path));
            }
        }
        return lastModified;
    }

    private static void deleteRecursively(final Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        IOException failure = null;
        final List<Path> paths;
        try (var stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static boolean isRegularFile(final Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile();
        } catch (IOException e) {
            return false;
        }
    }

    private static long lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private File getZipFile(final String cacheKey) {
        return new File(this.cacheFolder, cacheKey + ".zip");
    }

    private File getHashFile(final String cacheKey) {
        return new File(this.cacheFolder, cacheKey + ".sha1");
    }

    int validatedEntryCount() {
        synchronized (this) {
            return this.validatedEntries.size();
        }
    }

    private static String sha1Hex(final byte[] data) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            final byte[] hash = digest.digest(data);
            final StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha1Hex(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream input = Files.newInputStream(path)) {
                final byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String toHex(final byte[] hash) {
        final StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void hit() {
        if (this.metrics != null) this.metrics.hit(Tier.ARTIFACT);
    }

    private void miss() {
        if (this.metrics != null) this.metrics.miss(Tier.ARTIFACT);
    }

    private void build() {
        if (this.metrics != null) this.metrics.build(Tier.ARTIFACT);
    }

    private void failure() {
        if (this.metrics != null) this.metrics.failure(Tier.ARTIFACT);
    }

    private void waiter() {
        if (this.metrics != null) this.metrics.waiter(Tier.ARTIFACT);
    }

    private void updateInflight() {
        if (this.metrics != null) this.metrics.setInflight(Tier.ARTIFACT, this.inflight.size());
    }

    private void updateArtifactLeases() {
        if (this.metrics != null) {
            this.metrics.setActiveArtifactLeases(
                    this.activeArtifactLeases.values().stream().mapToLong(Integer::longValue).sum());
        }
    }

    private void updateBuildWorkspaceMetrics() {
        if (this.metrics != null) {
            this.metrics.setActiveArtifactBuildWorkspaces(this.activeBuildWorkspaces.size());
        }
    }

    private void recordBuildWorkspaceCleanup(final DiskUsage removed) {
        if (this.metrics != null) {
            this.metrics.recordArtifactBuildWorkspaceCleanup(removed.bytes(), removed.files());
        }
    }

    private void buildWorkspaceCleanupFailure() {
        if (this.metrics != null) {
            this.metrics.artifactBuildWorkspaceCleanupFailure();
        }
    }

    private void updateDiskMetrics(final CacheSnapshot snapshot) {
        if (this.metrics != null) {
            final DiskUsage usage = snapshot.usage();
            this.metrics.setArtifactDiskUsage(usage.bytes(), usage.files());
            this.metrics.setArtifactBuildWorkspaceDiskUsage(
                    snapshot.buildWorkspaceUsage().bytes(), snapshot.buildWorkspaceUsage().files());
            this.metrics.setWeight(Tier.ARTIFACT, usage.bytes(), this.diskBudgetBytes);
        }
    }

    private void refreshDiskMetrics() {
        if (this.metrics == null) return;
        try {
            this.updateDiskMetrics(this.scanCache());
        } catch (IOException ignored) {
            if (this.metrics != null) this.metrics.artifactDiskMetricRefreshFailure();
        }
    }

    private void cleanupFailure() {
        if (this.metrics != null) this.metrics.artifactDiskCleanupFailure();
    }

    private void eviction() {
        if (this.metrics != null) this.metrics.eviction(Tier.ARTIFACT);
    }

    @FunctionalInterface
    public interface ArtifactBuilder {
        void build(Path target) throws Exception;
    }

    @FunctionalInterface
    public interface HashedArtifactBuilder {
        ArtifactBuildResult build(Path target) throws Exception;
    }

    @FunctionalInterface
    private interface ArtifactBuildOperation {
        ArtifactBuildResult build(Path target) throws Exception;
    }

    public record ArtifactBuildResult(String sha1, long size) {

        public ArtifactBuildResult {
            Objects.requireNonNull(sha1, "sha1");
            if (!SHA1_PATTERN.matcher(sha1).matches()) {
                throw new IllegalArgumentException("Invalid SHA-1: " + sha1);
            }
            if (size < 0L) {
                throw new IllegalArgumentException("Artifact size must not be negative");
            }
        }
    }

    public static final class BuildWorkspace implements AutoCloseable {

        private final JavaPackCache owner;
        private final Path path;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BuildWorkspace(final JavaPackCache owner, final Path path) {
            this.owner = owner;
            this.path = path;
        }

        public Path path() {
            return this.path;
        }

        @Override
        public void close() throws IOException {
            if (this.closed.compareAndSet(false, true)) {
                this.owner.releaseBuildWorkspace(this.path);
            }
        }
    }

    public static final class ArtifactLease implements AutoCloseable {

        private final JavaPackCache owner;
        private final ArtifactRef artifact;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ArtifactLease(final JavaPackCache owner, final ArtifactRef artifact) {
            this.owner = owner;
            this.artifact = artifact;
        }

        public ArtifactRef artifact() {
            return this.artifact;
        }

        public synchronized ArtifactLease retain() throws IOException {
            if (this.closed.get()) {
                throw new IOException("Java resource pack artifact lease is already closed");
            }
            final ArtifactLease retained = this.owner.acquireArtifact(this.artifact);
            if (retained == null) {
                throw new IOException("Java resource pack artifact disappeared while leased: "
                        + this.artifact.hash());
            }
            return retained;
        }

        @Override
        public synchronized void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.owner.releaseArtifact(this.artifact.hash());
            }
        }
    }

    public record ArtifactRef(String cacheKey, String hash, Path path, long size) {
    }

}
