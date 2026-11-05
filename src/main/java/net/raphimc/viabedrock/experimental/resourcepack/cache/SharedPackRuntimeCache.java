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

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import net.easecation.bedrockmotion.pack.PackManager;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.cache.PackAlias;
import net.raphimc.viabedrock.api.resourcepack.cache.PackMount;
import net.raphimc.viabedrock.api.resourcepack.cache.RuntimeStackKey;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.SelectedSubpackContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.api.resourcepack.definition.ParsedPackLayer;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics.Tier;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.DataValues;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.rewriter.resourcepack.CustomSoundResourceRewriter;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class SharedPackRuntimeCache {

    private static final long MIB = 1024L * 1024L;
    private static final int RUNTIME_SCHEMA_VERSION = 1;
    private static final long MAINTENANCE_INTERVAL_MINUTES = 1L;

    private final Cache<ContentDigest, FrozenPackBlob> blobs;
    private final ConcurrentMap<ContentDigest, CompletableFuture<FrozenPackBlob>> blobInflight = new ConcurrentHashMap<>();
    private final ParsedPackLayerCache parsedLayers;
    private final Cache<RuntimeStackKey, SharedPackRuntime> idleRuntimes;
    private final ConcurrentMap<RuntimeStackKey, CompletableFuture<SharedPackRuntime>> inflight = new ConcurrentHashMap<>();
    private final ConcurrentMap<RuntimeStackKey, ActiveRuntime> activeRuntimes = new ConcurrentHashMap<>();
    private final FailureBackoff<RuntimeStackKey> failureBackoff;
    private final FailureBackoff<ContentDigest> blobFailureBackoff;
    private final ConcurrentMap<ContentDigest, WeakReference<FrozenPackBlob>> liveBlobs = new ConcurrentHashMap<>();
    // 内置包随协议映射初始化一次，之后不再暴露以供修改。
    private final WeakIdentityMap<ResourcePack, ContentDigest> trustedBuiltInDigests = new WeakIdentityMap<>();
    private final Object admissionLock = new Object();
    private final ResourcePackCacheMetrics metrics;
    private final ResourcePackWorkScheduler scheduler;
    private final ResourcePackArchiveStore archiveStore;
    private final long blobMaxWeight;
    private final long layerMaxWeight;
    private final long runtimeMaxWeight;
    private final long hardLimit;
    private final boolean serverAnimationEnabled;
    private final AtomicLong blobWeight = new AtomicLong();
    private final AtomicLong runtimeWeight = new AtomicLong();
    private final AtomicLong motionInflight = new AtomicLong();
    private long reservedBuildWeight;
    private final ScheduledFuture<?> maintenanceTask;

    /** 面向没有磁盘 CAS 的测试和嵌入方的兼容构造函数。生产环境必须提供 store。 */
    @Deprecated(forRemoval = false)
    public SharedPackRuntimeCache(final ViaBedrockConfig config, final ResourcePackCacheMetrics metrics,
                                  final ResourcePackWorkScheduler scheduler) {
        this(config, metrics, scheduler, null, System::nanoTime);
    }

    public SharedPackRuntimeCache(final ViaBedrockConfig config, final ResourcePackCacheMetrics metrics,
                                  final ResourcePackWorkScheduler scheduler,
                                  final ResourcePackArchiveStore archiveStore) {
        this(config, metrics, scheduler, archiveStore, System::nanoTime);
    }

    SharedPackRuntimeCache(final ViaBedrockConfig config, final ResourcePackCacheMetrics metrics,
                           final ResourcePackWorkScheduler scheduler, final LongSupplier nanoTime) {
        this(config, metrics, scheduler, null, nanoTime);
    }

    SharedPackRuntimeCache(final ViaBedrockConfig config, final ResourcePackCacheMetrics metrics,
                           final ResourcePackWorkScheduler scheduler,
                           final ResourcePackArchiveStore archiveStore, final LongSupplier nanoTime) {
        this.metrics = metrics;
        this.scheduler = scheduler;
        this.archiveStore = archiveStore;
        this.failureBackoff = new FailureBackoff<>(nanoTime);
        this.blobFailureBackoff = new FailureBackoff<>(nanoTime);
        final long configuredBudget = config.getResourcePackCacheMemoryBudgetMiB() * MIB;
        final long automaticBudget = Math.min(3_072L * MIB,
                Math.max(512L * MIB, Runtime.getRuntime().maxMemory() / 4L));
        final long budget = configuredBudget > 0L ? configuredBudget : automaticBudget;
        final long configuredHardLimit = config.getResourcePackCacheMemoryHardLimitMiB() * MIB;
        this.hardLimit = configuredHardLimit > 0L
                ? configuredHardLimit
                : Math.min(Runtime.getRuntime().maxMemory() / 2L, budget * 2L);
        this.blobMaxWeight = Math.max(1L, budget * 20L / 100L);
        this.layerMaxWeight = Math.max(1L, budget * 35L / 100L);
        this.runtimeMaxWeight = Math.max(1L, budget * 45L / 100L);
        this.serverAnimationEnabled = config.shouldEnableServerEntityAnimation();
        final Ticker cacheTicker = new Ticker() {
            @Override
            public long read() {
                return nanoTime.getAsLong();
            }
        };
        this.blobs = CacheBuilder.newBuilder()
                .maximumWeight(this.blobMaxWeight)
                .weigher((ContentDigest key, FrozenPackBlob value) -> clampWeight(value.estimatedHeapWeightBytes()))
                .expireAfterAccess(config.getResourcePackCacheIdleExpireMinutes(), TimeUnit.MINUTES)
                .ticker(cacheTicker)
                .removalListener(notification -> {
                    if (notification.getValue() instanceof FrozenPackBlob blob) {
                        this.blobWeight.addAndGet(-blob.estimatedHeapWeightBytes());
                        if (notification.getCause() != RemovalCause.EXPLICIT
                                && notification.getCause() != RemovalCause.REPLACED) {
                            this.metrics.eviction(Tier.BLOB);
                        }
                        this.updateWeights();
                    }
                })
                .build();
        this.parsedLayers = new ParsedPackLayerCache(
                this.layerMaxWeight, config.getResourcePackCacheIdleExpireMinutes(), this.metrics, nanoTime,
                this::publishLayer);
        this.idleRuntimes = CacheBuilder.newBuilder()
                .maximumWeight(this.runtimeMaxWeight)
                .weigher((RuntimeStackKey key, SharedPackRuntime value) -> clampWeight(value.estimatedWeightBytes()))
                .expireAfterAccess(config.getResourcePackCacheIdleExpireMinutes(), TimeUnit.MINUTES)
                .ticker(cacheTicker)
                .removalListener(notification -> {
                    if (notification.getValue() instanceof SharedPackRuntime runtime) {
                        this.runtimeWeight.addAndGet(-runtime.estimatedWeightBytes());
                        if (notification.getCause() != RemovalCause.EXPLICIT
                                && notification.getCause() != RemovalCause.REPLACED) {
                            this.metrics.eviction(Tier.RUNTIME);
                        }
                        this.updateWeights();
                    }
                })
                .build();
        this.updateWeights();
        this.maintenanceTask = this.scheduler.scheduleIoAtFixedRate(
                this::cleanUp, MAINTENANCE_INTERVAL_MINUTES,
                MAINTENANCE_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    public RuntimeLease acquire(final List<ResourcePack> resourcePacksTopToBottom) {
        return this.acquire(resourcePacksTopToBottom,
                java.util.Collections.nCopies(resourcePacksTopToBottom.size(), ""),
                ResourcePackRewriter.rewriterFingerprint());
    }

    public CompletableFuture<RuntimeLease> acquireAsync(final List<ResourcePack> resourcePacksTopToBottom,
                                                        final List<String> subpacksTopToBottom) {
        return this.acquireAsync(resourcePacksTopToBottom, subpacksTopToBottom,
                ResourcePackRewriter.rewriterFingerprint());
    }

    public CompletableFuture<RuntimeLease> acquireAsync(
            final List<ResourcePack> resourcePacksTopToBottom,
            final List<String> subpacksTopToBottom,
            final String runtimeDataFingerprint) {
        final CompletableFuture<RuntimeLease> worker = this.scheduler.submitCpu(
                () -> this.acquire(resourcePacksTopToBottom, subpacksTopToBottom, runtimeDataFingerprint));
        final CompletableFuture<RuntimeLease> dependent = new CompletableFuture<>();
        worker.whenComplete((lease, error) -> {
            if (error != null) {
                dependent.completeExceptionally(error);
            } else if (!dependent.complete(lease)) {
                lease.close();
            }
        });
        return dependent;
    }

    public RuntimeLease acquire(final List<ResourcePack> resourcePacksTopToBottom, final List<String> subpacksTopToBottom) {
        return this.acquire(resourcePacksTopToBottom, subpacksTopToBottom,
                ResourcePackRewriter.rewriterFingerprint());
    }

    public RuntimeLease acquire(final List<ResourcePack> resourcePacksTopToBottom,
                                final List<String> subpacksTopToBottom,
                                final String runtimeDataFingerprint) {
        if (resourcePacksTopToBottom.size() != subpacksTopToBottom.size()) {
            throw new IllegalArgumentException("Resource pack and subpack stack sizes differ");
        }
        Objects.requireNonNull(runtimeDataFingerprint, "runtimeDataFingerprint");
        final List<ContentDigest> sharedDigests = new ArrayList<>(resourcePacksTopToBottom.size());
        final List<PackMount> mounts = new ArrayList<>(resourcePacksTopToBottom.size());
        for (int i = 0; i < resourcePacksTopToBottom.size(); i++) {
            final ResourcePack pack = resourcePacksTopToBottom.get(i);
            final ContentDigest digest = this.digest(pack);
            sharedDigests.add(digest);
            mounts.add(new PackMount(PackAlias.from(pack.key()), digest, subpacksTopToBottom.get(i)));
        }
        final List<ResourcePack> vanillaPacks = BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks() == null
                ? List.of() : List.copyOf(BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks());
        final List<ContentDigest> vanillaDigests = new ArrayList<>(vanillaPacks.size());
        if (!vanillaPacks.isEmpty()) {
            for (int i = vanillaPacks.size() - 1; i >= 0; i--) {
                final ResourcePack vanillaPack = vanillaPacks.get(i);
                final ContentDigest digest = this.digestBuiltIn(vanillaPack);
                mounts.add(new PackMount(PackAlias.from(vanillaPack.key()), digest));
            }
            for (ResourcePack vanillaPack : vanillaPacks) {
                vanillaDigests.add(this.digestBuiltIn(vanillaPack));
            }
        }
        final ResourcePack skinPack = this.skinPack();
        final ContentDigest skinDigest = skinPack != null ? this.digestBuiltIn(skinPack) : null;
        final RuntimeStackKey key = RuntimeStackKey.compute(mounts, RUNTIME_SCHEMA_VERSION, List.of(
                this.serverAnimationEnabled ? "motion:" + PackManager.serverAnimationFingerprint() : "motion:disabled",
                skinDigest != null ? "skin:" + skinDigest.hex() : "skin:none",
                "runtime-data:" + runtimeDataFingerprint));

        final RuntimeLease existingLease = this.acquireExisting(key);
        if (existingLease != null) {
            return existingLease;
        }
        final ActiveRuntime rejectedActive = this.activeRuntimes.get(key);
        if (rejectedActive != null && rejectedActive.runtime.isRejected()) {
            final Throwable rejection = this.failureBackoff.getIfActive(key);
            throw propagate(rejection != null ? rejection : new IllegalStateException(
                    "Shared resource pack runtime is still releasing after failed initialization"));
        }

        this.metrics.miss(Tier.RUNTIME);
        final Throwable recentFailure = this.failureBackoff.getIfActive(key);
        if (recentFailure != null) {
            throw propagate(recentFailure);
        }
        final CompletableFuture<SharedPackRuntime> candidate = new CompletableFuture<>();
        final CompletableFuture<SharedPackRuntime> build = this.inflight.putIfAbsent(key, candidate);
        if (build == null) {
            final Throwable racedFailure = this.failureBackoff.getIfActive(key);
            if (racedFailure != null) {
                this.inflight.remove(key, candidate);
                this.metrics.setInflight(Tier.RUNTIME, this.inflight.size());
                candidate.completeExceptionally(racedFailure);
                throw propagate(racedFailure);
            }
            final RuntimeLease justPublished = this.acquireExisting(key);
            if (justPublished != null) {
                this.inflight.remove(key, candidate);
                this.metrics.setInflight(Tier.RUNTIME, this.inflight.size());
                candidate.complete(justPublished.runtime());
                return justPublished;
            }
            this.metrics.build(Tier.RUNTIME);
            this.metrics.setInflight(Tier.RUNTIME, this.inflight.size());
            final long start = System.nanoTime();
            final long inflightEstimate = this.estimateBuildWeight(
                    resourcePacksTopToBottom, sharedDigests, subpacksTopToBottom,
                    vanillaPacks, vanillaDigests, skinPack, skinDigest);
            boolean buildWeightReserved = false;
            try {
                this.reserveBuildWeight(inflightEstimate);
                buildWeightReserved = true;
                final List<FrozenPackBlob> sharedBlobs = new ArrayList<>(resourcePacksTopToBottom.size());
                for (int i = 0; i < resourcePacksTopToBottom.size(); i++) {
                    final ContentDigest digest = sharedDigests.get(i);
                    final FrozenPackBlob blob = this.internBlob(resourcePacksTopToBottom.get(i));
                    if (!digest.equals(blob.contentDigest())) {
                        throw new IllegalStateException("Resource pack changed after runtime identity was computed");
                    }
                    sharedBlobs.add(blob);
                }
                final SharedPackRuntime runtime = this.buildRuntime(key, runtimeDataFingerprint, sharedBlobs,
                        subpacksTopToBottom, vanillaPacks, vanillaDigests, skinPack, skinDigest);
                final ActiveRuntime activated;
                final AtomicBoolean activatedNewRuntime = new AtomicBoolean();
                synchronized (this.admissionLock) {
                    final long otherReservations = Math.max(0L,
                            this.reservedBuildWeight - inflightEstimate);
                    if (saturatingAdd(this.estimatedRetainedWeight(runtime), otherReservations) > this.hardLimit) {
                        throw new IllegalStateException("Shared resource pack cache hard memory limit exceeded");
                    }
                    activated = this.activeRuntimes.compute(key, (ignored, existing) -> {
                        if (existing != null) {
                            if (existing.runtime.isRejected()) {
                                throw new IllegalStateException("Shared resource pack runtime was rejected after initialization");
                            }
                            existing.leases.incrementAndGet();
                            return existing;
                        }
                        activatedNewRuntime.set(true);
                        return new ActiveRuntime(runtime);
                    });
                    this.failureBackoff.clear(key);
                }
                this.inflight.remove(key, candidate);
                this.metrics.setInflight(Tier.RUNTIME, this.inflight.size());
                candidate.complete(runtime);
                this.updateActiveState(activatedNewRuntime.get());
                return new RuntimeLease(this, key, activated.runtime);
            } catch (Throwable e) {
                this.metrics.failure(Tier.RUNTIME);
                synchronized (this.admissionLock) {
                    this.failureBackoff.record(key, e);
                }
                this.inflight.remove(key, candidate);
                this.metrics.setInflight(Tier.RUNTIME, this.inflight.size());
                candidate.completeExceptionally(e);
                throw propagate(e);
            } finally {
                if (buildWeightReserved) {
                    this.releaseBuildWeight(inflightEstimate);
                }
                this.metrics.recordBuildTime(Tier.RUNTIME,
                        (System.nanoTime() - start) / 1_000_000L);
            }
        }

        this.metrics.waiter(Tier.RUNTIME);
        final SharedPackRuntime runtime = build.join();
        if (runtime.bedrockMotionPackManager() != null) this.metrics.waiter(Tier.MOTION);
        final AtomicBoolean activatedNewRuntime = new AtomicBoolean();
        final ActiveRuntime activated;
        synchronized (this.admissionLock) {
            activated = this.activeRuntimes.compute(key, (ignored, existing) -> {
                if (runtime.isRejected()) {
                    throw new IllegalStateException("Shared resource pack runtime was rejected after initialization");
                }
                if (existing != null) {
                    if (existing.runtime != runtime || existing.runtime.isRejected()) {
                        throw new IllegalStateException("Runtime key was rebound to a different shared runtime");
                    }
                    existing.leases.incrementAndGet();
                    return existing;
                }
                activatedNewRuntime.set(true);
                return new ActiveRuntime(runtime);
            });
            this.idleRuntimes.invalidate(key);
        }
        this.updateActiveState(activatedNewRuntime.get());
        return new RuntimeLease(this, key, activated.runtime);
    }

    private RuntimeLease acquireExisting(final RuntimeStackKey key) {
        synchronized (this.admissionLock) {
            final AtomicBoolean acquiredLease = new AtomicBoolean();
            final ActiveRuntime active = this.activeRuntimes.computeIfPresent(key, (ignored, value) -> {
                if (value.runtime.isRejected()) return value;
                value.leases.incrementAndGet();
                acquiredLease.set(true);
                return value;
            });
            if (active != null && acquiredLease.get()) {
                this.failureBackoff.clear(key);
                this.metrics.hit(Tier.RUNTIME);
                if (this.serverAnimationEnabled) this.metrics.hit(Tier.MOTION);
                this.updateActiveState(false);
                return new RuntimeLease(this, key, active.runtime);
            }

            final SharedPackRuntime idle = this.idleRuntimes.getIfPresent(key);
            if (idle == null) return null;
            if (idle.isRejected()) {
                this.idleRuntimes.invalidate(key);
                return null;
            }
            this.failureBackoff.clear(key);
            this.metrics.hit(Tier.RUNTIME);
            if (this.serverAnimationEnabled) this.metrics.hit(Tier.MOTION);
            final AtomicBoolean activatedNewRuntime = new AtomicBoolean();
            final ActiveRuntime activated = this.activeRuntimes.compute(key, (ignored, existing) -> {
                if (existing != null) {
                    if (existing.runtime.isRejected()) {
                        throw new IllegalStateException("Shared resource pack runtime was rejected after initialization");
                    }
                    existing.leases.incrementAndGet();
                    return existing;
                }
                activatedNewRuntime.set(true);
                return new ActiveRuntime(idle);
            });
            this.idleRuntimes.invalidate(key);
            this.updateActiveState(activatedNewRuntime.get());
            return new RuntimeLease(this, key, activated.runtime);
        }
    }

    private SharedPackRuntime buildRuntime(final RuntimeStackKey key,
                                           final String runtimeDataFingerprint,
                                           final List<FrozenPackBlob> customBlobsTopToBottom,
                                           final List<String> subpacksTopToBottom,
                                           final List<ResourcePack> vanillaPacksBottomToTop,
                                           final List<ContentDigest> vanillaDigestsBottomToTop,
                                           final ResourcePack skinPack, final ContentDigest skinDigest) {
        final List<ResourcePack> packsBottomToTop = new ArrayList<>(
                vanillaPacksBottomToTop.size() + customBlobsTopToBottom.size());
        final List<SharedPackRuntime.PackSource> packSourcesBottomToTop = new ArrayList<>(
                vanillaPacksBottomToTop.size() + customBlobsTopToBottom.size());
        final List<ParsedPackLayerCache.RetainedLayer> retainedLayersBottomToTop = new ArrayList<>(
                vanillaPacksBottomToTop.size() + customBlobsTopToBottom.size());
        for (int i = 0; i < vanillaPacksBottomToTop.size(); i++) {
            final ResourcePack pack = vanillaPacksBottomToTop.get(i);
            final ContentDigest digest = vanillaDigestsBottomToTop.get(i);
            packsBottomToTop.add(pack);
            packSourcesBottomToTop.add(new SharedPackRuntime.PackSource(
                    new PackMount(PackAlias.from(pack.key()), digest),
                    SharedPackRuntime.SourceKind.BUILT_IN));
            retainedLayersBottomToTop.add(this.parsedLayers.getOrParseRetained(
                    pack, digest, ""));
        }
        for (int i = customBlobsTopToBottom.size() - 1; i >= 0; i--) {
            final FrozenPackBlob blob = customBlobsTopToBottom.get(i);
            final ResourcePack pack = blob.resourcePack();
            final ContentDigest digest = blob.contentDigest();
            final String subpack = subpacksTopToBottom.get(i);
            packsBottomToTop.add(this.mount(pack, subpack));
            packSourcesBottomToTop.add(new SharedPackRuntime.PackSource(
                    new PackMount(PackAlias.from(pack.key()), digest, subpack),
                    SharedPackRuntime.SourceKind.CANONICAL));
            retainedLayersBottomToTop.add(this.parsedLayers.getOrParseRetained(blob, subpack));
        }

        final List<ParsedPackLayerCache.RetainedLayer> retainedModelBaseLayers = skinPack != null
                ? List.of(this.parsedLayers.getOrParseRetained(skinPack, skinDigest, "")) : List.of();
        final List<ParsedPackLayer> layersBottomToTop = retainedLayersBottomToTop.stream()
                .map(ParsedPackLayerCache.RetainedLayer::layer)
                .toList();
        final List<ParsedPackLayer> modelBaseLayers = retainedModelBaseLayers.stream()
                .map(ParsedPackLayerCache.RetainedLayer::layer)
                .toList();
        final ParsedPackLayer.FoldedDefinitions definitions =
                ParsedPackLayer.foldBottomToTop(layersBottomToTop, modelBaseLayers);
        final List<ParsedPackLayerCache.RetainedLayer> pinnedLayers = new ArrayList<>(
                retainedModelBaseLayers.size() + retainedLayersBottomToTop.size());
        pinnedLayers.addAll(retainedModelBaseLayers);
        pinnedLayers.addAll(retainedLayersBottomToTop);

        final PackManager motion;
        if (this.serverAnimationEnabled) {
            this.metrics.miss(Tier.MOTION);
            this.metrics.build(Tier.MOTION);
            this.metrics.setInflight(Tier.MOTION, this.motionInflight.incrementAndGet());
            final long start = System.nanoTime();
            try {
                motion = PackManager.fromServerAnimationLayers(
                        pinnedLayers.stream().map(ParsedPackLayerCache.RetainedLayer::layer)
                                .map(ParsedPackLayer::serverAnimation).toList());
            } catch (RuntimeException | Error failure) {
                this.metrics.failure(Tier.MOTION);
                throw failure;
            } finally {
                this.metrics.recordBuildTime(Tier.MOTION,
                        (System.nanoTime() - start) / 1_000_000L);
                this.metrics.setInflight(Tier.MOTION, this.motionInflight.decrementAndGet());
            }
        } else {
            motion = null;
        }
        final List<ResourcePack> packsTopToBottom = new ArrayList<>(packsBottomToTop);
        Collections.reverse(packsTopToBottom);
        final Set<String> customSoundNames = CustomSoundResourceRewriter.findCustomSoundNames(
                definitions.sounds(), packsTopToBottom);
        return new SharedPackRuntime(key, runtimeDataFingerprint, packSourcesBottomToTop, this,
                definitions, pinnedLayers, motion, customSoundNames);
    }

    private ResourcePack mount(final ResourcePack pack, final String selectedSubpack) {
        if (selectedSubpack == null || selectedSubpack.isEmpty()) return pack;
        final ResourcePack mounted = new ResourcePack(
                new SelectedSubpackContent(pack.content(), selectedSubpack));
        if (!pack.key().equals(mounted.key())) {
            throw new IllegalArgumentException("Selected subpack changed the resource pack manifest identity");
        }
        return mounted;
    }

    private PackStackLease openPackStack(final SharedPackRuntime runtime) throws IOException {
        final List<ResourcePackArchiveStore.CanonicalContentLease> contentLeases = new ArrayList<>();
        final Map<ContentDigest, ResourcePackArchiveStore.CanonicalContentLease> leasesByDigest = new HashMap<>();
        try {
            final List<ResourcePack> bottomToTop = new ArrayList<>(runtime.packSourcesBottomToTop().size());
            for (SharedPackRuntime.PackSource source : runtime.packSourcesBottomToTop()) {
                bottomToTop.add(this.materialize(source, leasesByDigest, contentLeases));
            }
            final List<ResourcePack> topToBottom = new ArrayList<>(bottomToTop);
            Collections.reverse(topToBottom);
            return new PackStackLease(bottomToTop, topToBottom, contentLeases);
        } catch (IOException | RuntimeException | Error failure) {
            closeContentLeases(contentLeases);
            throw failure;
        }
    }

    private ResourcePack materialize(
            final SharedPackRuntime.PackSource source,
            final Map<ContentDigest, ResourcePackArchiveStore.CanonicalContentLease> leasesByDigest,
            final List<ResourcePackArchiveStore.CanonicalContentLease> contentLeases) throws IOException {
        final ResourcePack pack;
        if (source.sourceKind() == SharedPackRuntime.SourceKind.BUILT_IN) {
            pack = this.resolveBuiltIn(source.mount());
        } else if (this.archiveStore != null) {
            ResourcePackArchiveStore.CanonicalContentLease contentLease =
                    leasesByDigest.get(source.mount().contentDigest());
            if (contentLease == null) {
                contentLease = this.archiveStore.leaseCanonical(source.mount().contentDigest());
                leasesByDigest.put(source.mount().contentDigest(), contentLease);
                contentLeases.add(contentLease);
            }
            pack = new ResourcePack(contentLease.content());
            validateSourceKey(source.mount(), pack);
        } else {
            pack = this.resolveCachedBlob(source.mount());
        }
        return this.mount(pack, source.mount().subpack());
    }

    ResourcePack materializeView(final SharedPackRuntime.PackSource source) {
        final ResourcePack pack;
        if (source.sourceKind() == SharedPackRuntime.SourceKind.BUILT_IN) {
            pack = this.resolveBuiltIn(source.mount());
        } else if (this.archiveStore != null) {
            final var content = this.archiveStore.canonicalView(source.mount().contentDigest());
            pack = content.withReadSession(() -> new ResourcePack(content));
            validateSourceKey(source.mount(), pack);
        } else {
            final Content content = new CachedBlobContentView(this, source.mount().contentDigest());
            pack = content.withReadSession(() -> new ResourcePack(content));
            validateSourceKey(source.mount(), pack);
        }
        if (source.mount().subpack().isEmpty()) return pack;
        final var selected = new SelectedSubpackContent(pack.content(), source.mount().subpack());
        return pack.content().withReadSession(() -> new ResourcePack(selected));
    }

    private ResourcePack resolveBuiltIn(final PackMount mount) {
        final List<ResourcePack> vanillaPacks = BedrockProtocol.MAPPINGS.getBedrockVanillaResourcePacks();
        if (vanillaPacks != null) {
            for (ResourcePack candidate : vanillaPacks) {
                if (candidate.key().equals(mount.alias().toResourcePackKey())
                        && this.digestBuiltIn(candidate).equals(mount.contentDigest())) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Bundled resource pack content is unavailable: " + mount.alias());
    }

    private ResourcePack resolveCachedBlob(final PackMount mount) {
        final ResourcePack pack = this.resolveCachedBlobByDigest(mount.contentDigest()).resourcePack();
        validateSourceKey(mount, pack);
        return pack;
    }

    private FrozenPackBlob resolveCachedBlobByDigest(final ContentDigest digest) {
        final WeakReference<FrozenPackBlob> liveReference = this.liveBlobs.get(digest);
        final FrozenPackBlob live = liveReference != null ? liveReference.get() : null;
        final FrozenPackBlob blob = live != null ? live : this.blobs.getIfPresent(digest);
        if (blob == null) {
            throw new IllegalStateException("Canonical resource pack store is unavailable and the blob was evicted: "
                    + digest);
        }
        return blob;
    }

    private static void validateSourceKey(final PackMount mount, final ResourcePack pack) {
        if (!pack.key().equals(mount.alias().toResourcePackKey())) {
            throw new IllegalStateException("Canonical resource pack manifest identity changed for " + mount.alias());
        }
    }

    private static void closeContentLeases(
            final List<ResourcePackArchiveStore.CanonicalContentLease> contentLeases) {
        for (int i = contentLeases.size() - 1; i >= 0; i--) {
            contentLeases.get(i).close();
        }
    }

    private ContentDigest digest(final ResourcePack pack) {
        final ContentDigest storeVerified = this.archiveStore != null
                ? this.archiveStore.verifiedDigest(pack) : null;
        if (storeVerified != null) return storeVerified;
        final ContentDigest computed = ContentDigest.compute(pack.content());
        if (pack.content() instanceof ZipFileContent zipFileContent
                && zipFileContent.contentDigest() != null
                && !zipFileContent.contentDigest().equals(computed)) {
            throw new IllegalArgumentException("Resource pack content does not match its claimed digest");
        }
        return computed;
    }

    private ContentDigest digestBuiltIn(final ResourcePack pack) {
        final ContentDigest known = this.trustedBuiltInDigests.get(pack);
        if (known != null) return known;
        final ContentDigest computed = this.digest(pack);
        final ContentDigest raced = this.trustedBuiltInDigests.putIfAbsent(pack, computed);
        return raced != null ? raced : computed;
    }

    private ResourcePack skinPack() {
        if (BedrockProtocol.MAPPINGS.getBedrockSkinPacks() == null) return null;
        return BedrockProtocol.MAPPINGS.getBedrockSkinPacks().get(DataValues.VANILLA_SKIN_PACK_KEY);
    }

    FrozenPackBlob internBlob(final ResourcePack source) {
        final ContentDigest digest = this.digest(source);
        final FrozenPackBlob cached = this.blobs.getIfPresent(digest);
        if (cached != null) {
            if (isBlobBackingAvailable(cached)) {
                this.blobFailureBackoff.clear(digest);
                this.metrics.hit(Tier.BLOB);
                this.liveBlobs.put(digest, new WeakReference<>(cached));
                return validateBlobSource(source, cached);
            }
            this.blobs.invalidate(digest);
            this.liveBlobs.remove(digest);
            this.blobs.cleanUp();
        }

        final WeakReference<FrozenPackBlob> liveReference = this.liveBlobs.get(digest);
        final FrozenPackBlob live = liveReference != null ? liveReference.get() : null;
        if (live != null && isBlobBackingAvailable(live)) {
            this.blobFailureBackoff.clear(digest);
            this.metrics.hit(Tier.BLOB);
            return validateBlobSource(source, live);
        } else if (liveReference != null) {
            this.liveBlobs.remove(digest, liveReference);
        }

        this.metrics.miss(Tier.BLOB);
        final Throwable recentFailure = this.blobFailureBackoff.getIfActive(digest);
        if (recentFailure != null) throw propagate(recentFailure);
        final CompletableFuture<FrozenPackBlob> candidate = new CompletableFuture<>();
        final CompletableFuture<FrozenPackBlob> raced = this.blobInflight.putIfAbsent(digest, candidate);
        if (raced != null) {
            this.metrics.waiter(Tier.BLOB);
            return validateBlobSource(source, joinBlob(raced));
        }
        final Throwable racedFailure = this.blobFailureBackoff.getIfActive(digest);
        if (racedFailure != null) {
            this.blobInflight.remove(digest, candidate);
            candidate.completeExceptionally(racedFailure);
            throw propagate(racedFailure);
        }

        this.metrics.setInflight(Tier.BLOB, this.blobInflight.size());
        long buildStartNanos = 0L;
        long reservation = 0L;
        FrozenPackBlob result = null;
        Throwable failure = null;
        boolean reserved = false;
        boolean buildStarted = false;
        try {
            reservation = this.estimateBlobBuildWeight(source.content());
            this.reserveBuildWeight(reservation,
                    "Shared resource pack cache hard memory limit exceeded before blob construction");
            reserved = true;
            buildStartNanos = System.nanoTime();
            buildStarted = true;
            this.metrics.build(Tier.BLOB);
            final FrozenPackBlob built = validateBlobSource(
                    source, this.buildBlob(source, digest));
            synchronized (this.admissionLock) {
                final long otherReservations = Math.max(0L, this.reservedBuildWeight - reservation);
                if (saturatingAdd(this.estimatedRetainedWeight(
                        built, built.estimatedHeapWeightBytes()), otherReservations) > this.hardLimit) {
                    throw new IllegalStateException(
                            "Shared resource pack cache hard memory limit exceeded after blob construction");
                }
                this.blobWeight.addAndGet(built.estimatedHeapWeightBytes());
                this.blobs.put(digest, built);
                this.liveBlobs.put(digest, new WeakReference<>(built));
                this.updateWeights();
            }
            this.blobFailureBackoff.clear(digest);
            result = built;
        } catch (Throwable throwable) {
            failure = throwable;
            this.blobFailureBackoff.record(digest, throwable);
            if (buildStarted) this.metrics.failure(Tier.BLOB);
        } finally {
            if (reserved) this.releaseBuildWeight(reservation);
            if (buildStarted) {
                this.metrics.recordBuildTime(Tier.BLOB,
                        Math.max(0L, System.nanoTime() - buildStartNanos) / 1_000_000L);
            }
            this.blobInflight.remove(digest, candidate);
            this.metrics.setInflight(Tier.BLOB, this.blobInflight.size());
        }
        if (failure == null) {
            candidate.complete(result);
            return validateBlobSource(source, result);
        }
        candidate.completeExceptionally(failure);
        throw propagate(failure);
    }

    private FrozenPackBlob buildBlob(final ResourcePack source, final ContentDigest digest) {
        validateCurrentManifestIdentity(source);
        if (this.archiveStore == null) {
            return FrozenPackBlob.compatibility(digest, source);
        }
        try {
            this.archiveStore.ensureCanonical(source, digest);
            return this.archiveStore.openFrozenBlob(digest);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build canonical resource pack blob", e);
        }
    }

    private static void validateCurrentManifestIdentity(final ResourcePack source) {
        final ResourcePack reparsed = source.content().withReadSession(
                () -> new ResourcePack(source.content()));
        if (!source.key().equals(reparsed.key())) {
            throw new IllegalArgumentException(
                    "Resource pack manifest identity changed after it was declared");
        }
    }

    private static FrozenPackBlob validateBlobSource(final ResourcePack source, final FrozenPackBlob blob) {
        if (!source.key().equals(blob.manifestKey())) {
            throw new IllegalArgumentException("Resource pack manifest identity does not match its shared blob");
        }
        return blob;
    }

    private static boolean isBlobBackingAvailable(final FrozenPackBlob blob) {
        return blob.canonicalPath().map(Files::isRegularFile).orElse(true);
    }

    private long estimateBlobBuildWeight(final Content content) {
        if (this.archiveStore == null) {
            return SharedPackRuntime.estimateContentWeight(content);
        }
        long weight = 128L;
        for (String path : content.getFilesDeep("", "")) {
            weight = saturatingAdd(weight, 64L + (long) path.length() * Character.BYTES);
        }
        return Math.max(1L, weight);
    }

    private static FrozenPackBlob joinBlob(final CompletableFuture<FrozenPackBlob> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            throw propagate(failure.getCause() != null ? failure.getCause() : failure);
        }
    }

    private void release(final RuntimeStackKey key, final SharedPackRuntime runtime) {
        final AtomicBoolean deactivatedRuntime = new AtomicBoolean();
        synchronized (this.admissionLock) {
            this.activeRuntimes.computeIfPresent(key, (ignored, active) -> {
                if (active.runtime != runtime || active.leases.decrementAndGet() > 0L) {
                    return active;
                }
                if (runtime.isRejected()) {
                    this.idleRuntimes.invalidate(key);
                } else {
                    this.runtimeWeight.addAndGet(runtime.estimatedWeightBytes());
                    this.idleRuntimes.put(key, runtime);
                    this.updateWeights();
                }
                deactivatedRuntime.set(true);
                return null;
            });
        }
        this.updateActiveState(deactivatedRuntime.get());
    }

    private RuntimeLease retain(final RuntimeStackKey key, final SharedPackRuntime runtime) {
        final AtomicBoolean activatedNewRuntime = new AtomicBoolean();
        final ActiveRuntime activated;
        synchronized (this.admissionLock) {
            if (runtime.isRejected()) {
                throw new IllegalStateException("Shared resource pack runtime was rejected after initialization");
            }
            activated = this.activeRuntimes.compute(key, (ignored, active) -> {
                if (runtime.isRejected()) {
                    throw new IllegalStateException("Shared resource pack runtime was rejected after initialization");
                }
                if (active == null) {
                    activatedNewRuntime.set(true);
                    return new ActiveRuntime(runtime);
                }
                if (active.runtime != runtime || active.runtime.isRejected()) {
                    throw new IllegalStateException("Runtime key was rebound to a different shared runtime");
                }
                active.leases.incrementAndGet();
                return active;
            });
            this.idleRuntimes.invalidate(key);
        }
        this.updateActiveState(activatedNewRuntime.get());
        return new RuntimeLease(this, key, activated.runtime);
    }

    private void refreshRuntimeWeight(final RuntimeStackKey key, final SharedPackRuntime runtime) {
        synchronized (this.admissionLock) {
            runtime.refreshConverterDataWeight();
            if (saturatingAdd(this.retainedWeightSnapshot().totalBytes(), this.reservedBuildWeight)
                    > this.hardLimit) {
                final IllegalStateException failure = new IllegalStateException(
                        "Shared resource pack cache hard memory limit exceeded after runtime initialization");
                this.rejectRuntime(key, runtime, failure);
                throw failure;
            }
            this.failureBackoff.clear(key);
            this.updateActiveState(true);
        }
    }

    private void initializeRuntimeData(final RuntimeStackKey key, final SharedPackRuntime runtime,
                                       final Runnable initializer) {
        final long estimate = runtime.initializationEstimateBytes();
        boolean reserved = false;
        boolean initializationStarted = false;
        try {
            this.reserveBuildWeight(estimate,
                    "Shared resource pack cache hard memory limit exceeded before runtime initialization");
            reserved = true;
            runtime.beginConverterDataInitialization();
            initializationStarted = true;
            initializer.run();
            runtime.sealConverterData();
            initializationStarted = false;
            synchronized (this.admissionLock) {
                runtime.refreshConverterDataWeight();
                final long otherReservations = Math.max(0L, this.reservedBuildWeight - estimate);
                if (saturatingAdd(this.retainedWeightSnapshot().totalBytes(), otherReservations)
                        > this.hardLimit) {
                    throw new IllegalStateException(
                            "Shared resource pack cache hard memory limit exceeded after runtime initialization");
                }
                this.failureBackoff.clear(key);
                this.updateActiveState(true);
            }
        } catch (Throwable failure) {
            if (initializationStarted) runtime.abortConverterDataInitialization();
            this.rejectRuntime(key, runtime, failure);
            throw propagate(failure);
        } finally {
            if (reserved) this.releaseBuildWeight(estimate);
        }
    }

    private void rejectRuntime(final RuntimeStackKey key, final SharedPackRuntime runtime,
                               final Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        synchronized (this.admissionLock) {
            if (!runtime.reject()) return;
            this.failureBackoff.record(key, failure);
            this.metrics.failure(Tier.RUNTIME);
            this.idleRuntimes.invalidate(key);
            this.updateActiveState(true);
        }
    }

    private synchronized void updateActiveState(final boolean retainedSetChanged) {
        long leases = 0L;
        for (ActiveRuntime active : this.activeRuntimes.values()) {
            leases += active.leases.get();
        }
        this.metrics.setActiveRuntimeLeases(leases);
        if (retainedSetChanged) {
            final SharedPackRuntime.RetainedWeight retainedWeight = new SharedPackRuntime.RetainedWeight();
            for (ActiveRuntime active : this.activeRuntimes.values()) {
                active.runtime.collectRetained(retainedWeight);
            }
            this.metrics.setActiveRuntimeWeightBytes(retainedWeight.totalBytes());
            this.updateMotionWeight();
        }
    }

    private void updateWeights() {
        this.metrics.setWeight(Tier.BLOB, Math.max(0L, this.blobWeight.get()), this.blobMaxWeight);
        this.metrics.setCompletedEntries(Tier.BLOB, this.blobs.asMap().size());
        this.metrics.setWeight(Tier.RUNTIME, Math.max(0L, this.runtimeWeight.get()), this.runtimeMaxWeight);
        this.updateMotionWeight();
    }

    private void updateMotionWeight() {
        if (!this.serverAnimationEnabled) {
            this.metrics.setWeight(Tier.MOTION, 0L, 0L);
            return;
        }

        // 动画定义由运行时图持有，并受共享硬上限约束。
        // 这里只是保留子集的估计值，不是要加到运行时/层权重上的数量。
        final Set<SharedPackRuntime> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        long weight = 0L;
        for (ActiveRuntime active : this.activeRuntimes.values()) {
            if (retained.add(active.runtime)) {
                weight = saturatingAdd(weight, motionWeight(active.runtime));
            }
        }
        for (SharedPackRuntime runtime : this.idleRuntimes.asMap().values()) {
            if (retained.add(runtime)) {
                weight = saturatingAdd(weight, motionWeight(runtime));
            }
        }
        this.metrics.setWeight(Tier.MOTION, weight, this.hardLimit);
    }

    private static long motionWeight(final SharedPackRuntime runtime) {
        final PackManager motion = runtime.bedrockMotionPackManager();
        if (motion == null) return 0L;
        final long definitions = (long) motion.getAnimationDefinitions().getAnimations().size()
                + motion.getAnimationControllerDefinitions().getControllers().size()
                + motion.getRenderControllerDefinitions().getRenderControllers().size();
        return saturatingAdd(256L, definitions * 512L);
    }

    private long estimatedRetainedWeight(final SharedPackRuntime candidate) {
        final SharedPackRuntime.RetainedWeight retainedWeight = this.retainedWeightSnapshot();
        candidate.collectRetained(retainedWeight);
        return retainedWeight.totalBytes();
    }

    private long estimatedRetainedWeight(final Object identity, final long weightBytes) {
        final SharedPackRuntime.RetainedWeight retainedWeight = this.retainedWeightSnapshot();
        retainedWeight.add(identity, weightBytes);
        return retainedWeight.totalBytes();
    }

    private void publishLayer(final ParsedPackLayerCache.RetainedLayer layer,
                              final Runnable publication) {
        synchronized (this.admissionLock) {
            final long projected = saturatingAdd(
                    this.estimatedRetainedWeight(layer.layer(), layer.estimatedWeightBytes()),
                    this.reservedBuildWeight);
            if (projected > this.hardLimit) {
                throw new IllegalStateException(
                        "Shared resource pack cache hard memory limit exceeded after layer parsing");
            }
            publication.run();
        }
    }

    private SharedPackRuntime.RetainedWeight retainedWeightSnapshot() {
        final SharedPackRuntime.RetainedWeight retainedWeight = new SharedPackRuntime.RetainedWeight();
        for (FrozenPackBlob blob : this.blobs.asMap().values()) {
            retainedWeight.add(blob, blob.estimatedHeapWeightBytes());
        }
        this.parsedLayers.collectRetained(retainedWeight);
        for (SharedPackRuntime runtime : this.idleRuntimes.asMap().values()) {
            runtime.collectRetained(retainedWeight);
        }
        for (ActiveRuntime active : this.activeRuntimes.values()) {
            active.runtime.collectRetained(retainedWeight);
        }
        return retainedWeight;
    }

    private void reserveBuildWeight(final long estimateBytes) {
        this.reserveBuildWeight(estimateBytes,
                "Shared resource pack cache hard memory limit exceeded before layer parsing");
    }

    private void reserveBuildWeight(final long estimateBytes, final String failureMessage) {
        synchronized (this.admissionLock) {
            final long projected = saturatingAdd(
                    this.retainedWeightSnapshot().totalBytes(),
                    saturatingAdd(this.reservedBuildWeight, estimateBytes));
            if (projected > this.hardLimit) {
                throw new IllegalStateException(failureMessage);
            }
            this.reservedBuildWeight = saturatingAdd(this.reservedBuildWeight, estimateBytes);
            this.metrics.addInflightEstimatedWeightBytes(estimateBytes);
        }
    }

    private void releaseBuildWeight(final long estimateBytes) {
        synchronized (this.admissionLock) {
            this.reservedBuildWeight = Math.max(0L, this.reservedBuildWeight - estimateBytes);
            this.metrics.addInflightEstimatedWeightBytes(-estimateBytes);
        }
    }

    public BuildReservation reserveArtifactBuild(final long estimateBytes) {
        final long reserved = Math.max(1L, estimateBytes);
        this.reserveBuildWeight(reserved,
                "Shared resource pack cache hard memory limit exceeded before artifact conversion");
        return new BuildReservation(this, reserved);
    }

    long estimatedRetainedWeightBytes() {
        return this.retainedWeightSnapshot().totalBytes();
    }

    void cleanUp() {
        this.blobs.cleanUp();
        this.parsedLayers.cleanUp();
        this.idleRuntimes.cleanUp();
        this.trustedBuiltInDigests.cleanUp();
        this.liveBlobs.entrySet().removeIf(entry -> entry.getValue().get() == null);
        this.updateWeights();
        this.updateActiveState(false);
    }

    public int completedBlobCount() {
        return this.blobs.asMap().size();
    }

    public FrozenPackBlob findCompletedBlob(final ContentDigest digest) {
        return this.blobs.getIfPresent(Objects.requireNonNull(digest, "digest"));
    }

    void invalidateCompletedBlob(final ContentDigest digest) {
        this.blobs.invalidate(Objects.requireNonNull(digest, "digest"));
        this.blobs.cleanUp();
    }

    boolean hasScheduledMaintenance() {
        return !this.maintenanceTask.isCancelled();
    }

    private static int clampWeight(final long weight) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, weight));
    }

    private long estimateBuildWeight(final List<ResourcePack> customPacks,
                                     final List<ContentDigest> customDigests,
                                     final List<String> customSubpacks,
                                     final List<ResourcePack> vanillaPacks,
                                     final List<ContentDigest> vanillaDigests,
                                     final ResourcePack skinPack, final ContentDigest skinDigest) {
        long weight = 64L * 1024L;
        for (int i = 0; i < customPacks.size(); i++) {
            weight = addLayerBuildEstimate(weight, this.parsedLayers.estimateBuild(
                    customPacks.get(i), customDigests.get(i), customSubpacks.get(i)));
        }
        for (int i = 0; i < vanillaPacks.size(); i++) {
            weight = addLayerBuildEstimate(weight, this.parsedLayers.estimateBuild(
                    vanillaPacks.get(i), vanillaDigests.get(i), ""));
        }
        if (skinPack != null) {
            weight = addLayerBuildEstimate(weight,
                    this.parsedLayers.estimateBuild(skinPack, skinDigest, ""));
        }
        return weight;
    }

    private static long addLayerBuildEstimate(
            final long current, final ParsedPackLayerCache.LayerBuildEstimate layer) {
        return saturatingAdd(current, saturatingAdd(
                layer.parseReservationBytes(), layer.retainedWeightBytes() / 4L));
    }

    private static long saturatingAdd(final long left, final long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static RuntimeException propagate(final Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) return runtimeException;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Failed to build shared resource pack runtime", failure);
    }

    /** 按身份做记忆化，同时不把每个历史连接包都变成 GC 根。 */
    static final class WeakIdentityMap<K, V> {
        private final ReferenceQueue<K> referenceQueue = new ReferenceQueue<>();
        private final Map<IdentityWeakReference<K>, V> entries = new HashMap<>();

        synchronized V get(final K key) {
            Objects.requireNonNull(key, "key");
            this.cleanUp();
            return this.entries.get(new IdentityWeakReference<>(key));
        }

        synchronized V putIfAbsent(final K key, final V value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            this.cleanUp();
            final IdentityWeakReference<K> lookup = new IdentityWeakReference<>(key);
            final V existing = this.entries.get(lookup);
            if (existing != null) return existing;
            this.entries.put(new IdentityWeakReference<>(key, this.referenceQueue), value);
            return null;
        }

        synchronized void cleanUp() {
            IdentityWeakReference<?> reference;
            while ((reference = (IdentityWeakReference<?>) this.referenceQueue.poll()) != null) {
                this.entries.remove(reference);
            }
        }

        synchronized int size() {
            this.cleanUp();
            return this.entries.size();
        }

        private static final class IdentityWeakReference<T> extends WeakReference<T> {
            private final int identityHashCode;

            private IdentityWeakReference(final T referent) {
                super(referent);
                this.identityHashCode = System.identityHashCode(referent);
            }

            private IdentityWeakReference(final T referent, final ReferenceQueue<T> referenceQueue) {
                super(referent, referenceQueue);
                this.identityHashCode = System.identityHashCode(referent);
            }

            @Override
            public int hashCode() {
                return this.identityHashCode;
            }

            @Override
            public boolean equals(final Object object) {
                if (this == object) return true;
                if (!(object instanceof IdentityWeakReference<?> that)) return false;
                final Object referent = this.get();
                return referent != null && referent == that.get();
            }
        }
    }

    /** 面向在没有磁盘 CAS 情况下构造缓存的测试/嵌入方的轻量兼容视图。 */
    private static final class CachedBlobContentView extends Content {

        private final WeakReference<SharedPackRuntimeCache> owner;
        private final ContentDigest digest;
        private final ThreadLocal<Content> readSession = new ThreadLocal<>();

        private CachedBlobContentView(final SharedPackRuntimeCache owner, final ContentDigest digest) {
            this.owner = new WeakReference<>(owner);
            this.digest = digest;
        }

        @Override
        public List<String> getFilesShallow(final String path, final String extension) {
            return this.withContent(content -> content.getFilesShallow(path, extension));
        }

        @Override
        public List<String> getFilesDeep(final String path, final String extension) {
            return this.withContent(content -> content.getFilesDeep(path, extension));
        }

        @Override
        public boolean contains(final String path) {
            return this.withContent(content -> content.contains(path));
        }

        @Override
        public byte[] get(final String path) {
            return this.withContent(content -> content.get(path));
        }

        @Override
        public InputStream open(final String path) throws IOException {
            return this.content().open(path);
        }

        @Override
        public long size(final String path) throws IOException {
            return this.content().size(path);
        }

        @Override
        public void visitFiles(final List<String> paths, final FileVisitor visitor) throws IOException {
            this.content().visitFiles(paths, visitor);
        }

        @Override
        public <T> T withReadSession(final Supplier<T> action) {
            if (this.readSession.get() != null) return action.get();
            final Content content = this.content();
            this.readSession.set(content);
            try {
                return content.withReadSession(action);
            } finally {
                this.readSession.remove();
            }
        }

        @Override
        public boolean put(final String path, final byte[] data) {
            throw new UnsupportedOperationException("Shared resource pack compatibility view cannot be modified");
        }

        @Override
        public void writeZip(final Path target) throws IOException {
            this.content().writeZip(target);
        }

        private Content content() {
            final Content active = this.readSession.get();
            if (active != null) return active;
            final SharedPackRuntimeCache owner = this.owner.get();
            if (owner == null) {
                throw new IllegalStateException("Shared resource pack cache is unavailable");
            }
            return owner.resolveCachedBlobByDigest(this.digest).resourcePack().content();
        }

        private <T> T withContent(final java.util.function.Function<Content, T> action) {
            return action.apply(this.content());
        }
    }

    private static final class ActiveRuntime {
        private final SharedPackRuntime runtime;
        private final AtomicLong leases = new AtomicLong(1L);

        private ActiveRuntime(final SharedPackRuntime runtime) {
            this.runtime = runtime;
        }
    }

    public static final class PackStackLease implements AutoCloseable {

        private final List<ResourcePack> bottomToTop;
        private final List<ResourcePack> topToBottom;
        private final List<ResourcePackArchiveStore.CanonicalContentLease> contentLeases;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PackStackLease(final List<ResourcePack> bottomToTop,
                               final List<ResourcePack> topToBottom,
                               final List<ResourcePackArchiveStore.CanonicalContentLease> contentLeases) {
            this.bottomToTop = List.copyOf(bottomToTop);
            this.topToBottom = List.copyOf(topToBottom);
            this.contentLeases = List.copyOf(contentLeases);
        }

        public List<ResourcePack> bottomToTop() {
            if (this.closed.get()) throw new IllegalStateException("Resource pack stack lease is closed");
            return this.bottomToTop;
        }

        public List<ResourcePack> topToBottom() {
            if (this.closed.get()) throw new IllegalStateException("Resource pack stack lease is closed");
            return this.topToBottom;
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                closeContentLeases(this.contentLeases);
            }
        }
    }

    public static final class BuildReservation implements AutoCloseable {
        private final SharedPackRuntimeCache owner;
        private final long estimateBytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BuildReservation(final SharedPackRuntimeCache owner, final long estimateBytes) {
            this.owner = owner;
            this.estimateBytes = estimateBytes;
        }

        @Override
        public void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.owner.releaseBuildWeight(this.estimateBytes);
            }
        }
    }

    public static final class RuntimeLease implements AutoCloseable {
        private final SharedPackRuntimeCache owner;
        private final RuntimeStackKey key;
        private final SharedPackRuntime runtime;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RuntimeLease(final SharedPackRuntimeCache owner, final RuntimeStackKey key, final SharedPackRuntime runtime) {
            this.owner = owner;
            this.key = key;
            this.runtime = runtime;
        }

        public SharedPackRuntime runtime() {
            return this.runtime;
        }

        public PackStackLease openPackStack() throws IOException {
            return this.owner.openPackStack(this.runtime);
        }

        public synchronized RuntimeLease retain() {
            if (this.closed.get()) {
                throw new IllegalStateException("Shared resource pack runtime lease is already closed");
            }
            return this.owner.retain(this.key, this.runtime);
        }

        public synchronized void refreshRetainedWeight() {
            if (this.closed.get()) {
                throw new IllegalStateException("Shared resource pack runtime lease is already closed");
            }
            this.owner.refreshRuntimeWeight(this.key, this.runtime);
        }

        public synchronized void initializeRuntimeData(final Runnable initializer) {
            if (this.closed.get()) {
                throw new IllegalStateException("Shared resource pack runtime lease is already closed");
            }
            this.owner.initializeRuntimeData(this.key, this.runtime, initializer);
        }

        public void rejectRuntime(final Throwable failure) {
            this.owner.rejectRuntime(this.key, this.runtime, failure);
        }

        @Override
        public synchronized void close() {
            if (this.closed.compareAndSet(false, true)) {
                this.owner.release(this.key, this.runtime);
            }
        }
    }

}
