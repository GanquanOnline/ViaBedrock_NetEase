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

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.api.resourcepack.definition.ParsedPackLayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class SharedPackRuntimeCacheTest {

    @Test
    void fixedMaintenancePurgesDeadWeakValueKeys(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            try (var ignored = cache.acquire(List.of(pack(UUID.randomUUID(), "1.0.0", "weak")))) {
                // 填充两个弱值身份索引。
            }
            final ConcurrentMap<?, WeakReference<?>> liveBlobs = weakMap(cache, "liveBlobs");
            final Object parsedLayers = field(cache, "parsedLayers");
            final ConcurrentMap<?, WeakReference<?>> liveLayers = weakMap(parsedLayers, "live");
            assertFalse(liveBlobs.isEmpty());
            assertFalse(liveLayers.isEmpty());
            liveBlobs.values().forEach(WeakReference::clear);
            liveLayers.values().forEach(WeakReference::clear);

            cache.cleanUp();

            assertTrue(liveBlobs.isEmpty());
            assertTrue(liveLayers.isEmpty());
            assertTrue(cache.hasScheduledMaintenance());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void weakLiveHitsRefreshCompletedIdleExpiry(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = configWithIdleMinutes(tempDir, 1);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final AtomicLong nanoTime = new AtomicLong();
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, nanoTime::get);
            final ParsedPackLayerCache layers = new ParsedPackLayerCache(
                    16L * 1024L * 1024L, 1, metrics, nanoTime::get);
            final ResourcePack source = pack(UUID.randomUUID(), "1.0.0", "idle-refresh");
            final ContentDigest digest = ContentDigest.compute(source.content());
            final FrozenPackBlob blob = cache.internBlob(source);
            final ParsedPackLayerCache.RetainedLayer layer =
                    layers.getOrParseRetained(source, digest, "");

            nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(59L));
            assertSame(blob, cache.internBlob(source));
            assertSame(layer, layers.getOrParseRetained(source, digest, ""));

            nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(2L));
            cache.cleanUp();
            layers.cleanUp();

            assertSame(blob, cache.findCompletedBlob(digest));
            assertEquals(1, layers.completedCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void concurrentIdenticalStacksBuildOneRuntime(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "shared");
            final List<CompletableFuture<SharedPackRuntimeCache.RuntimeLease>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(cache.acquireAsync(List.of(pack), List.of("")));
            }
            final List<SharedPackRuntimeCache.RuntimeLease> leases = futures.stream().map(CompletableFuture::join).toList();
            final SharedPackRuntime runtime = leases.getFirst().runtime();
            assertTrue(leases.stream().allMatch(lease -> lease.runtime() == runtime));
            assertEquals(1L, metrics.getBlobBuilds());
            assertEquals(1L, metrics.getBlobBuildTimeSamples());
            assertEquals(1L, metrics.getRuntimeBuilds());
            assertEquals(1L, metrics.getRuntimeBuildTimeSamples());
            assertEquals(1L, metrics.getMotionMisses());
            assertEquals(1L, metrics.getMotionBuilds());
            assertEquals(1L, metrics.getMotionBuildTimeSamples());
            assertEquals(0L, metrics.getMotionFailures());
            assertEquals(0L, metrics.getMotionInflight());
            assertTrue(metrics.getMotionWeightBytes() > 0L);
            assertTrue(metrics.getMotionMaxWeightBytes() > 0L);
            assertEquals(20L, metrics.getActiveRuntimeLeases());
            final long layerBuilds = metrics.getLayerBuilds();
            try (var hotLease = cache.acquire(List.of(pack), List.of(""))) {
                assertSame(runtime, hotLease.runtime());
                assertEquals(layerBuilds, metrics.getLayerBuilds());
                assertEquals(1L, metrics.getMotionBuilds());
            }
            leases.forEach(SharedPackRuntimeCache.RuntimeLease::close);
            assertEquals(0L, metrics.getActiveRuntimeLeases());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void twentyConcurrentBlobRequestsPublishOneCanonicalIdentity(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final ResourcePack source = pack(UUID.randomUUID(), "1.0.0", "blob-single-flight",
                    "payload.bin", "x".repeat(1024 * 1024));
            final ContentDigest digest = ContentDigest.compute(source.content());
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<FrozenPackBlob>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                requests.add(executor.submit(() -> {
                    awaitLatch(start);
                    return cache.internBlob(source);
                }));
            }
            start.countDown();

            final List<FrozenPackBlob> blobs = new ArrayList<>();
            for (Future<FrozenPackBlob> request : requests) {
                blobs.add(request.get(20L, TimeUnit.SECONDS));
            }

            blobs.forEach(blob -> assertSame(blobs.getFirst(), blob));
            assertTrue(blobs.getFirst().isProductionCanonical());
            assertEquals(digest, blobs.getFirst().contentDigest());
            assertSame(blobs.getFirst(), cache.findCompletedBlob(digest));
            assertEquals(1, cache.completedBlobCount());
            assertEquals(1L, metrics.getBlobCompletedEntries());
            assertEquals(1L, metrics.getBlobBuilds());
            assertEquals(1L, metrics.getBlobBuildTimeSamples());
            assertEquals(0L, metrics.getBlobInflight());
        } finally {
            executor.shutdownNow();
            scheduler.shutdown();
        }
    }

    @Test
    void noStoreConstructorUsesExplicitCompatibilityBlobBacking(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final FrozenPackBlob blob = cache.internBlob(
                    pack(UUID.randomUUID(), "1.0.0", "compatibility"));

            assertEquals(FrozenPackBlob.BackingKind.COMPATIBILITY, blob.backingKind());
            assertFalse(blob.isProductionCanonical());
            assertTrue(blob.canonicalPath().isEmpty());
            assertEquals(1, cache.completedBlobCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void existingBlobDoesNotTrustForgedZipFileContentDigest(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final UUID id = UUID.randomUUID();
            final ResourcePack original = pack(id, "1.0.0", "original");
            final ContentDigest originalDigest = ContentDigest.compute(original.content());
            final FrozenPackBlob originalBlob = cache.internBlob(original);
            final ResourcePack different = pack(id, "1.0.0", "different");
            final Path forgedArchive = tempDir.resolve("forged.zip");
            different.content().writeZip(forgedArchive);
            final ResourcePack forged = new ResourcePack(new ZipFileContent(forgedArchive, originalDigest));

            final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> cache.internBlob(forged));

            assertTrue(failure.getMessage().contains("claimed digest"));
            assertSame(originalBlob, cache.findCompletedBlob(originalDigest));
            assertEquals(1, cache.completedBlobCount());
            assertEquals(1L, metrics.getBlobBuilds());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void changedZipFileCannotReuseItsPreviouslyClaimedDigest(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final UUID id = UUID.randomUUID();
            final Path archive = tempDir.resolve("mutable.zip");
            final ResourcePack original = pack(id, "1.0.0", "before");
            original.content().writeZip(archive);
            final ContentDigest claimed = ContentDigest.compute(new ZipFileContent(archive));
            final ResourcePack wrapper = new ResourcePack(new ZipFileContent(archive, claimed));
            cache.internBlob(wrapper);

            pack(id, "1.0.0", "after").content().writeZip(archive);

            final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> cache.internBlob(wrapper));
            assertTrue(failure.getMessage().contains("claimed digest"));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void distinctStoreVerifiedWrappersUseIdentityDigestWithoutRehashingOnBlobHit(
            @TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final ResourcePack source = pack(UUID.randomUUID(), "1.0.0", "store-verified");
            final ContentDigest digest = ContentDigest.compute(source.content());
            archiveStore.ensureCanonical(source, digest);
            final FrozenPackBlob firstWrapper = archiveStore.openFrozenBlob(digest);
            final FrozenPackBlob secondWrapper = archiveStore.openFrozenBlob(digest);
            assertEquals(digest, archiveStore.verifiedDigest(firstWrapper.resourcePack()));
            assertEquals(digest, archiveStore.verifiedDigest(secondWrapper.resourcePack()));
            final FrozenPackBlob cached = cache.internBlob(firstWrapper.resourcePack());
            final Path canonical = cached.canonicalPath().orElseThrow();

            final FrozenPackBlob hit = cache.internBlob(secondWrapper.resourcePack());

            assertSame(cached, hit);
            assertTrue(Files.isRegularFile(canonical));
            assertEquals(1L, metrics.getBlobBuilds());
            assertTrue(metrics.getBlobHits() >= 1L);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void missingCanonicalBlobBackingIsInvalidatedAndRebuilt(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final ResourcePack source = pack(UUID.randomUUID(), "1.0.0", "rebuild-missing-canonical");
            final FrozenPackBlob first = cache.internBlob(source);
            final Path canonical = first.canonicalPath().orElseThrow();
            Files.delete(canonical);

            final FrozenPackBlob rebuilt = cache.internBlob(source);

            assertNotSame(first, rebuilt);
            assertEquals(first.contentDigest(), rebuilt.contentDigest());
            assertTrue(Files.isRegularFile(rebuilt.canonicalPath().orElseThrow()));
            assertEquals(2L, metrics.getBlobBuilds());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void mutableCompatibilitySourceIsReidentifiedAndFrozen(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack source = pack(UUID.randomUUID(), "1.0.0", "before");
            final FrozenPackBlob before = cache.internBlob(source);

            source.content().put("marker.txt", "after".getBytes(StandardCharsets.UTF_8));
            final FrozenPackBlob after = cache.internBlob(source);

            assertNotSame(before, after);
            assertNotEquals(before.contentDigest(), after.contentDigest());
            assertEquals("before", before.content().getString("marker.txt"));
            assertEquals("after", after.content().getString("marker.txt"));
            assertEquals(2, cache.completedBlobCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void manifestIdentityMismatchDoesNotPublishCompletedBlob(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    tempDir.resolve("server-packs"), scheduler, metrics, config);
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final ResourcePack source = pack(UUID.randomUUID(), "1.0.0", "declared");
            final byte[] differentManifest = pack(UUID.randomUUID(), "1.0.0", "different")
                    .content().get("manifest.json");
            source.content().put("manifest.json", differentManifest);
            final ContentDigest digest = ContentDigest.compute(source.content());

            final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> cache.internBlob(source));

            assertTrue(failure.getMessage().contains("manifest identity"));
            assertNull(cache.findCompletedBlob(digest));
            assertEquals(0, cache.completedBlobCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void concurrentLayerRequestsParseOnce() throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final CountDownLatch parsed = new CountDownLatch(1);
        final CountDownLatch releasePublication = new CountDownLatch(1);
        final ParsedPackLayerCache layers = new ParsedPackLayerCache(
                64L * 1024L * 1024L, 30, metrics, System::nanoTime,
                (layer, publication) -> {
                    parsed.countDown();
                    awaitLatch(releasePublication);
                    publication.run();
                });
        final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "concurrent-layer",
                "items/shared.json", itemJson("test:shared", "shared"));
        final ContentDigest digest = ContentDigest.compute(pack.content());
        final ExecutorService executor = Executors.newFixedThreadPool(20);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<ParsedPackLayerCache.RetainedLayer>> requests = new ArrayList<>();
        try {
            try {
                for (int i = 0; i < 20; i++) {
                    requests.add(executor.submit(() -> {
                        awaitLatch(start);
                        return layers.getOrParseRetained(pack, digest, "");
                    }));
                }
                start.countDown();
                assertTrue(parsed.await(10L, TimeUnit.SECONDS));
                awaitMetric(() -> metrics.getLayerWaiters() == 19L);
            } finally {
                releasePublication.countDown();
            }

            final List<ParsedPackLayerCache.RetainedLayer> results = new ArrayList<>();
            for (Future<ParsedPackLayerCache.RetainedLayer> request : requests) {
                results.add(request.get(10L, TimeUnit.SECONDS));
            }
            results.forEach(result -> assertSame(results.getFirst(), result));
            assertEquals(1L, metrics.getLayerBuilds());
            assertEquals(1L, metrics.getLayerBuildTimeSamples());
            assertEquals(19L, metrics.getLayerWaiters());
            assertEquals(0L, metrics.getLayerInflight());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void contentAndSubpackChangeRuntimeIdentity(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final UUID id = UUID.randomUUID();
            try (var base = cache.acquire(List.of(pack(id, "1.0.0", "base")), List.of(""));
                 var changed = cache.acquire(List.of(pack(id, "1.0.0", "changed")), List.of(""));
                 var subpack = cache.acquire(List.of(pack(id, "1.0.0", "base")), List.of("hd"))) {
                assertNotEquals(base.runtime().key(), changed.runtime().key());
                assertNotEquals(base.runtime().key(), subpack.runtime().key());
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void runtimeDataFingerprintChangesRuntimeIdentity(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "runtime-fingerprint");
            try (var first = cache.acquire(List.of(pack), List.of(""), "rewriter-a");
                 var same = cache.acquire(List.of(pack), List.of(""), "rewriter-a");
                 var changed = cache.acquire(List.of(pack), List.of(""), "rewriter-b")) {
                assertSame(first.runtime(), same.runtime());
                assertNotSame(first.runtime(), changed.runtime());
                assertNotEquals(first.runtime().key(), changed.runtime().key());
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void branchStacksReuseCommonParsedLayerAndMotionDefinitions(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack common = pack(UUID.randomUUID(), "1.0.0", "common",
                    "items/common.json", itemJson("test:common", "common"),
                    "animations/common.json", animationJson("animation.test.common"));
            final ResourcePack branchA = pack(UUID.randomUUID(), "1.0.0", "a",
                    "items/branch.json", itemJson("test:branch", "a"));
            final ResourcePack branchB = pack(UUID.randomUUID(), "1.0.0", "b",
                    "items/branch.json", itemJson("test:branch", "b"));

            try (var commonLease = cache.acquire(List.of(common))) {
                final CacheBuildCounts commonCounts = CacheBuildCounts.capture(metrics);
                try (var branchALease = cache.acquire(List.of(branchA, common))) {
                    assertEquals(commonCounts.blobs() + 1L, metrics.getBlobBuilds());
                    assertEquals(commonCounts.layers() + 1L, metrics.getLayerBuilds());
                    assertEquals(commonCounts.runtimes() + 1L, metrics.getRuntimeBuilds());
                    assertEquals(commonCounts.motions() + 1L, metrics.getMotionBuilds());
                    try (var branchBLease = cache.acquire(List.of(branchB, common))) {
                        assertEquals(commonCounts.blobs() + 2L, metrics.getBlobBuilds());
                        assertEquals(commonCounts.layers() + 2L, metrics.getLayerBuilds());
                        assertEquals(commonCounts.runtimes() + 2L, metrics.getRuntimeBuilds());
                        assertEquals(commonCounts.motions() + 2L, metrics.getMotionBuilds());

                        final SharedPackRuntime.PackSource commonOnlySource =
                                findPackSource(commonLease.runtime(), common.key());
                        final ParsedPackLayer commonOnlyLayer = findLayer(commonLease.runtime(), common.key());
                        final ParsedPackLayer commonInA = findLayer(branchALease.runtime(), common.key());
                        final ParsedPackLayer commonInB = findLayer(branchBLease.runtime(), common.key());
                        assertEquals(commonOnlySource, findPackSource(branchALease.runtime(), common.key()));
                        assertEquals(commonOnlySource, findPackSource(branchBLease.runtime(), common.key()));
                        assertSame(commonOnlyLayer, commonInA);
                        assertSame(commonOnlyLayer, commonInB);

                        final Object commonAnimations = commonLease.runtime().bedrockMotionPackManager()
                                .getAnimationDefinitions().getAnimations();
                        final Object commonAnimation = commonLease.runtime().bedrockMotionPackManager()
                                .getAnimationDefinitions().getAnimations().get("animation.test.common");
                        assertSame(commonAnimation, branchALease.runtime().bedrockMotionPackManager()
                                .getAnimationDefinitions().getAnimations().get("animation.test.common"));
                        assertSame(commonAnimation, branchBLease.runtime().bedrockMotionPackManager()
                                .getAnimationDefinitions().getAnimations().get("animation.test.common"));
                        assertNotSame(commonAnimations, branchALease.runtime().bedrockMotionPackManager()
                                .getAnimationDefinitions().getAnimations());
                        assertNotSame(commonAnimations, branchBLease.runtime().bedrockMotionPackManager()
                                .getAnimationDefinitions().getAnimations());
                        assertEquals("a", branchALease.runtime().items().get("test:branch").iconComponent());
                        assertEquals("b", branchBLease.runtime().items().get("test:branch").iconComponent());
                    }
                }
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void selectedSubpackChangesEffectiveDefinitions(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "subpack",
                    "items/shared.json", itemJson("test:shared", "base"),
                    "subpacks/hd/items/shared.json", itemJson("test:shared", "hd"));
            try (var base = cache.acquire(List.of(pack), List.of(""));
                 var hd = cache.acquire(List.of(pack), List.of("hd"))) {
                assertEquals("base", base.runtime().items().get("test:shared").iconComponent());
                assertEquals("hd", hd.runtime().items().get("test:shared").iconComponent());
                assertNotEquals(base.runtime().key(), hd.runtime().key());
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void runtimePrecomputesCustomSoundNamesBeforeDroppingPackContent(
            @TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "sounds",
                    "sounds/sound_definitions.json",
                    "{\"test.custom\":{\"category\":\"neutral\",\"sounds\":[\"sounds/custom\"]}}",
                    "sounds/custom.ogg", "ogg");

            try (var lease = cache.acquire(List.of(pack))) {
                assertEquals(Set.of("test.custom"), lease.runtime().customSoundNames());
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void disabledServerAnimationDoesNotRecordMotionWork(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 64, 128, false);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "motion-disabled",
                    "animations/disabled.json", animationJson("animation.test.disabled"));
            try (var first = cache.acquire(List.of(pack));
                 var hot = cache.acquire(List.of(pack))) {
                assertSame(first.runtime(), hot.runtime());
                assertNull(first.runtime().bedrockMotionPackManager());
            }
            assertEquals(0L, metrics.getMotionHits());
            assertEquals(0L, metrics.getMotionMisses());
            assertEquals(0L, metrics.getMotionBuilds());
            assertEquals(0L, metrics.getMotionFailures());
            assertEquals(0L, metrics.getMotionWaiters());
            assertEquals(0L, metrics.getMotionInflight());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void runtimeFailuresBackOffBeforeRetry(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 4, 8);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final AtomicLong nanoTime = new AtomicLong();
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, nanoTime::get);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "blob-backoff");
            final SharedPackRuntimeCache.BuildReservation pressure =
                    cache.reserveArtifactBuild(8L * 1024L * 1024L - 1L);

            final RuntimeException firstFailure = assertThrows(
                    RuntimeException.class, () -> cache.acquire(List.of(pack)));
            pressure.close();
            final long runtimeBuilds = metrics.getRuntimeBuilds();

            final RuntimeException backedOff = assertThrows(
                    RuntimeException.class, () -> cache.acquire(List.of(pack)));
            assertSame(firstFailure, backedOff);
            assertEquals(runtimeBuilds, metrics.getRuntimeBuilds());

            nanoTime.addAndGet(FailureBackoff.RETRY_DELAY_NANOS);
            try (var lease = cache.acquire(List.of(pack))) {
                assertEquals(pack.key(), lease.runtime().parsedLayersBottomToTop().getLast().sourceKey());
            }
            assertEquals(runtimeBuilds + 1L, metrics.getRuntimeBuilds());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void layerFailuresBackOffBeforeRetry() {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final AtomicLong nanoTime = new AtomicLong();
        final AtomicBoolean failPublication = new AtomicBoolean(true);
        final ParsedPackLayerCache layers = new ParsedPackLayerCache(
                16L * 1024L * 1024L, 30, metrics, nanoTime::get,
                (ignored, publication) -> {
                    if (failPublication.getAndSet(false)) {
                        throw new IllegalStateException("transient layer publication failure");
                    }
                    publication.run();
                });
        final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "layer-backoff");
        final ContentDigest digest = ContentDigest.compute(pack.content());

        final RuntimeException firstFailure = assertThrows(
                RuntimeException.class, () -> layers.getOrParseRetained(pack, digest, ""));
        final long builds = metrics.getLayerBuilds();
        final RuntimeException backedOff = assertThrows(
                RuntimeException.class, () -> layers.getOrParseRetained(pack, digest, ""));
        assertSame(firstFailure, backedOff);
        assertEquals(builds, metrics.getLayerBuilds());

        nanoTime.addAndGet(FailureBackoff.RETRY_DELAY_NANOS);
        assertNotNull(layers.getOrParseRetained(pack, digest, ""));
        assertEquals(builds + 1L, metrics.getLayerBuilds());
    }

    @Test
    void cancelledAsyncAcquireClosesLateLeaseWithoutCancellingWorker(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 1);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final CompletableFuture<Void> blocker = scheduler.submitCpu(() -> {
                workerStarted.countDown();
                if (!releaseWorker.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release test worker");
                }
                return null;
            });
            assertTrue(workerStarted.await(10, TimeUnit.SECONDS));

            final CompletableFuture<SharedPackRuntimeCache.RuntimeLease> cancelled =
                    cache.acquireAsync(List.of(pack(UUID.randomUUID(), "1.0.0", "cancelled")), List.of(""));
            assertTrue(cancelled.cancel(false));
            final CompletableFuture<Void> barrier = scheduler.submitCpu(() -> null);
            releaseWorker.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            barrier.get(10, TimeUnit.SECONDS);

            assertTrue(cancelled.isCancelled());
            assertEquals(1L, metrics.getRuntimeBuilds());
            assertEquals(0L, metrics.getActiveRuntimeLeases());
        } finally {
            releaseWorker.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void multipleLeasesDoNotMultiplyActiveRetainedWeight(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final List<SharedPackRuntimeCache.RuntimeLease> leases = new ArrayList<>();
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = packWithPayload(UUID.randomUUID(), 64 * 1024, (byte) 1);
            leases.add(cache.acquire(List.of(pack)));
            final long singleLeaseWeight = metrics.getActiveRuntimeWeightBytes();
            assertTrue(singleLeaseWeight > 64 * 1024L);

            for (int i = 1; i < 20; i++) {
                leases.add(cache.acquire(List.of(pack)));
            }
            assertEquals(20L, metrics.getActiveRuntimeLeases());
            assertEquals(singleLeaseWeight, metrics.getActiveRuntimeWeightBytes());

            while (leases.size() > 1) {
                leases.removeLast().close();
            }
            assertEquals(singleLeaseWeight, metrics.getActiveRuntimeWeightBytes());
            leases.removeLast().close();
            assertEquals(0L, metrics.getActiveRuntimeWeightBytes());
            assertEquals(0L, metrics.getActiveRuntimeLeases());
            assertTrue(metrics.getRuntimeWeightBytes() > 0L);
        } finally {
            leases.forEach(SharedPackRuntimeCache.RuntimeLease::close);
            scheduler.shutdown();
        }
    }

    @Test
    void runtimeDataRefreshUpdatesActiveAndIdleWeights(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(List.of(
                    pack(UUID.randomUUID(), "1.0.0", "runtime-data")));
            final long before = metrics.getActiveRuntimeWeightBytes();
            lease.initializeRuntimeData(() -> lease.runtime().putConverterDataDuringInitialization(
                    "large-runtime-value", "x".repeat(600 * 1024)));

            assertTrue(metrics.getActiveRuntimeWeightBytes() >= before + 1024 * 1024L);
            assertThrows(UnsupportedOperationException.class,
                    () -> lease.runtime().converterData().put("late", "mutation"));
            final long refreshed = lease.runtime().estimatedWeightBytes();
            lease.close();
            assertTrue(metrics.getRuntimeWeightBytes() >= refreshed);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void postInitializationHardLimitRejectsAndDoesNotCacheRuntime(@TempDir final Path tempDir) throws Exception {
        final int hardLimitMiB = 8;
        final ViaBedrockConfig config = config(tempDir, 0, 4, hardLimitMiB);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "post-admission");
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(List.of(pack));
            final long remaining = hardLimitMiB * 1024L * 1024L - cache.estimatedRetainedWeightBytes();
            assertTrue(remaining > 0L);
            final String oversized = "x".repeat(Math.toIntExact(remaining / 2L + 64L * 1024L));

            final IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> lease.initializeRuntimeData(
                            () -> lease.runtime().putConverterDataDuringInitialization(
                                    "oversized-runtime-value", oversized)));
            assertTrue(failure.getMessage().contains("after runtime initialization"));
            lease.close();
            cache.cleanUp();

            assertEquals(0L, metrics.getActiveRuntimeLeases());
            assertEquals(0L, metrics.getRuntimeWeightBytes());
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> cache.acquire(List.of(pack))));
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void runtimeDataSealDetachesNestedMutableValues(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(List.of(
                    pack(UUID.randomUUID(), "1.0.0", "runtime-data-freeze")));
            final List<String> mutable = new ArrayList<>(List.of("before"));

            lease.initializeRuntimeData(() -> lease.runtime().putConverterDataDuringInitialization(
                    "nested", Map.of("values", mutable)));
            mutable.add("after");

            @SuppressWarnings("unchecked")
            final Map<String, List<String>> frozen = (Map<String, List<String>>)
                    lease.runtime().converterData().get("nested");
            assertEquals(List.of("before"), frozen.get("values"));
            assertThrows(UnsupportedOperationException.class,
                    () -> frozen.get("values").add("mutation"));
            lease.close();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void parseReservationRejectsLargeDefinitionBeforeLayerConstruction(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 4, 8);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final String largeAnimation = "{\"format_version\":\"1.8.0\",\"animations\":{}}"
                    + " ".repeat(1024 * 1024);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "large-parse",
                    "animations/large.json", largeAnimation);

            final IllegalStateException failure = assertThrows(
                    IllegalStateException.class, () -> cache.acquire(List.of(pack)));

            assertTrue(failure.getMessage().contains("before layer parsing"));
            assertEquals(0L, metrics.getLayerBuilds());
            assertEquals(0L, metrics.getInflightEstimatedWeightBytes());
            assertTrue(cache.estimatedRetainedWeightBytes() < 8L * 1024L * 1024L);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void completedLayerIsNotPublishedWhenSecondAdmissionFails(@TempDir final Path tempDir) throws Exception {
        final int hardLimitMiB = 8;
        final ViaBedrockConfig config = config(tempDir, 0, 4, hardLimitMiB);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ParsedPackLayerCache layers = (ParsedPackLayerCache) field(cache, "parsedLayers");
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "post-parse-admission");
            final ContentDigest digest = ContentDigest.compute(pack.content());

            final ParsedPackLayerCache sizing = new ParsedPackLayerCache(
                    16L * 1024L * 1024L, 30, new ResourcePackCacheMetrics());
            final long completedLayerWeight = sizing.getOrParseRetained(pack, digest, "").estimatedWeightBytes();
            final long hardLimitBytes = hardLimitMiB * 1024L * 1024L;
            final long reservationBytes = hardLimitBytes - completedLayerWeight + 1L;
            assertTrue(reservationBytes > 0L);

            try (var ignored = cache.reserveArtifactBuild(reservationBytes)) {
                final IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> layers.getOrParseRetained(pack, digest, ""));

                assertTrue(failure.getMessage().contains("after layer parsing"));
                assertEquals(0L, metrics.getLayerWeightBytes());
                assertEquals(0, layers.completedCount());
                assertEquals(reservationBytes, metrics.getInflightEstimatedWeightBytes());
            }
            assertEquals(0L, metrics.getInflightEstimatedWeightBytes());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void blobAdmissionIncludesConcurrentBuildReservations(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 4, 8);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            try (var ignored = cache.reserveArtifactBuild(6L * 1024L * 1024L)) {
                final IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> cache.acquire(List.of(
                                packWithPayload(UUID.randomUUID(), 3 * 1024 * 1024, (byte) 7))));

                assertTrue(failure.getMessage().contains("hard memory limit"));
                assertEquals(0L, metrics.getBlobBuilds());
                assertEquals(0L, metrics.getBlobWeightBytes());
                assertEquals(6L * 1024L * 1024L, metrics.getInflightEstimatedWeightBytes());
            }
            assertEquals(0L, metrics.getInflightEstimatedWeightBytes());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void artifactBuildReservationUsesHardLimitAndJmxWeight(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 4, 8);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final SharedPackRuntimeCache.BuildReservation reservation =
                    cache.reserveArtifactBuild(5L * 1024L * 1024L);
            assertEquals(5L * 1024L * 1024L, metrics.getInflightEstimatedWeightBytes());

            final IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> cache.reserveArtifactBuild(4L * 1024L * 1024L));
            assertTrue(failure.getMessage().contains("before artifact conversion"));
            assertEquals(5L * 1024L * 1024L, metrics.getInflightEstimatedWeightBytes());

            reservation.close();
            reservation.close();
            assertEquals(0L, metrics.getInflightEstimatedWeightBytes());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void sharedRuntimeInitializationReservesAgainstConcurrentBuilds(@TempDir final Path tempDir) throws Exception {
        final int hardLimitMiB = 8;
        final ViaBedrockConfig config = config(tempDir, 0, 4, hardLimitMiB);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(List.of(
                    pack(UUID.randomUUID(), "1.0.0", "runtime-init-reservation")));
            final long remaining = hardLimitMiB * 1024L * 1024L - cache.estimatedRetainedWeightBytes();
            final long initializationEstimate = lease.runtime().initializationEstimateBytes();
            final long concurrentReservation = remaining - initializationEstimate + 1L;
            assertTrue(concurrentReservation > 0L);
            final AtomicBoolean initialized = new AtomicBoolean();

            try (var ignored = cache.reserveArtifactBuild(concurrentReservation)) {
                final IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> lease.initializeRuntimeData(() -> initialized.set(true)));

                assertTrue(failure.getMessage().contains("before runtime initialization"));
                assertFalse(initialized.get());
                assertEquals(concurrentReservation, metrics.getInflightEstimatedWeightBytes());
            }
            lease.close();
            assertEquals(0L, metrics.getInflightEstimatedWeightBytes());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void closedRuntimeLeaseCannotReactivateRuntime(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(List.of(
                    pack(UUID.randomUUID(), "1.0.0", "closed-lease")));
            lease.close();

            assertThrows(IllegalStateException.class, lease::retain);
            assertThrows(IllegalStateException.class, lease::refreshRetainedWeight);
            assertThrows(IllegalStateException.class,
                    () -> lease.initializeRuntimeData(() -> fail("closed lease initialized runtime")));
            assertEquals(0L, metrics.getActiveRuntimeLeases());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void failedPostInitializationRuntimeIsRejectedAndBackedOff(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final AtomicLong nanoTime = new AtomicLong();
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, nanoTime::get);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "failed-runtime-data");
            final SharedPackRuntimeCache.RuntimeLease lease = cache.acquire(List.of(pack));
            final SharedPackRuntime rejected = lease.runtime();
            final IllegalStateException failure = new IllegalStateException("runtime data failed");

            lease.rejectRuntime(failure);
            lease.close();
            cache.cleanUp();

            assertEquals(0L, metrics.getActiveRuntimeLeases());
            assertEquals(0L, metrics.getRuntimeWeightBytes());
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> cache.acquire(List.of(pack))));

            nanoTime.addAndGet(FailureBackoff.RETRY_DELAY_NANOS);
            try (var retried = cache.acquire(List.of(pack))) {
                assertNotSame(rejected, retried.runtime());
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void activeRuntimeDoesNotRetainBlobContentAfterCompletedTierEviction(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 1, 8);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final ResourcePack source = packWithPayload(UUID.randomUUID(), 512 * 1024, (byte) 2);
            final ContentDigest digest = ContentDigest.compute(source.content());
            try (var lease = cache.acquire(List.of(source))) {
                cache.cleanUp();
                assertEquals(0L, metrics.getBlobWeightBytes());
                assertTrue(metrics.getBlobEvictions() > 0L);
                assertEquals(digest, lease.runtime().packSourcesTopToBottom().getFirst().mount().contentDigest());
                assertEquals(1, lease.runtime().packStackTopToBottom().size(),
                        "Compatibility size checks must not materialize canonical content");
                assertTrue(metrics.getActiveRuntimeWeightBytes() < 512 * 1024L,
                        "The runtime must not account the evicted binary payload as retained content");
                assertTrue(cache.estimatedRetainedWeightBytes() >= metrics.getActiveRuntimeWeightBytes());
            }
            cache.cleanUp();
            assertEquals(0L, metrics.getActiveRuntimeWeightBytes());
            assertTrue(metrics.getRuntimeEvictions() > 0L,
                    "The full retained closure is larger than the tiny idle runtime budget");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void activeAndIdleRuntimeDoNotKeepSourcePackOrContentAlive(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 8, 16);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final RetentionProbe probe = createRetentionProbe(cache);
            cache.cleanUp();
            awaitCollected(probe.pack(), probe.content());
            assertEquals(1, probe.lease().runtime().packStackTopToBottom().size());

            probe.lease().close();
            cache.cleanUp();
            assertTrue(metrics.getRuntimeWeightBytes() > 0L,
                    "The descriptor-only runtime should remain reusable in the idle cache");
            assertNull(probe.pack().get());
            assertNull(probe.content().get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void activeRuntimeDoesNotPreventCanonicalCleanupAfterCompletedBlobEviction(
            @TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final AtomicLong currentTimeMillis = new AtomicLong(System.currentTimeMillis());
        try {
            final Path serverPacks = tempDir.resolve("server-packs");
            final ResourcePackArchiveStore archiveStore = new ResourcePackArchiveStore(
                    serverPacks, scheduler, metrics, config, System::nanoTime, currentTimeMillis::get);
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(
                    config, metrics, scheduler, archiveStore);
            final ResourcePack source = pack(UUID.randomUUID(), "1.0.0", "cleanup",
                    "items/retained.json", itemJson("test:retained", "retained"));
            final ContentDigest digest = ContentDigest.compute(source.content());
            try (var lease = cache.acquire(List.of(source))) {
                final Path canonical = serverPacks.resolve("v2/content/sha256")
                        .resolve(digest.hex().substring(0, 2)).resolve(digest.hex() + ".zip");
                assertTrue(Files.isRegularFile(canonical));

                currentTimeMillis.addAndGet(TimeUnit.DAYS.toMillis(8L));
                archiveStore.cleanupNow();

                assertTrue(Files.isRegularFile(canonical),
                        "The completed disk blob must protect its backing canonical ZIP");
                final WeakReference<FrozenPackBlob> blob = evictCompletedBlob(cache, digest);
                awaitCollected(blob);
                archiveStore.cleanupNow();

                assertFalse(Files.exists(canonical),
                        "An online descriptor-only runtime must not pin an evicted canonical blob");
                assertNotNull(lease.runtime().items().get("test:retained"));
                assertEquals(1, lease.runtime().packStackTopToBottom().size());
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void activeRuntimeWeightSurvivesParsedLayerEviction(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 1, 8);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final String largeJson = itemJson("test:large", "large") + " ".repeat(160 * 1024);
            final ResourcePack pack = pack(UUID.randomUUID(), "1.0.0", "large-layer",
                    "items/large.json", largeJson);
            try (var ignored = cache.acquire(List.of(pack))) {
                cache.cleanUp();
                assertEquals(0L, metrics.getLayerWeightBytes());
                assertTrue(metrics.getLayerEvictions() > 0L);
                assertTrue(metrics.getActiveRuntimeWeightBytes() >= 700 * 1024L);
                assertTrue(cache.estimatedRetainedWeightBytes() >= metrics.getActiveRuntimeWeightBytes());
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void idleRuntimeCacheEvictsByFullRetainedClosure(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir, 0, 1, 8);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            for (int i = 0; i < 4; i++) {
                try (var ignored = cache.acquire(List.of(
                        packWithPayload(UUID.randomUUID(), 32 * 1024, (byte) i)))) {
                    // 关闭会把唯一的运行时移入带权重的空闲缓存。
                }
            }
            cache.cleanUp();
            assertTrue(metrics.getRuntimeEvictions() > 0L);
            assertTrue(metrics.getRuntimeWeightBytes() <= metrics.getRuntimeMaxWeightBytes());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void hardLimitRejectsNewContentButReusesExistingRuntime(@TempDir final Path tempDir) throws Exception {
        final int hardLimitMiB = 32;
        final ViaBedrockConfig config = config(tempDir, 0, 1, hardLimitMiB);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        try {
            final SharedPackRuntimeCache cache = new SharedPackRuntimeCache(config, metrics, scheduler);
            final UUID id = UUID.randomUUID();
            final ResourcePack firstPack = packWithPayload(id, 256 * 1024, (byte) 3);
            try (var first = cache.acquire(List.of(firstPack));
                 var reused = cache.acquire(List.of(packWithPayload(id, 256 * 1024, (byte) 3)))) {
                final long retainedBefore = metrics.getActiveRuntimeWeightBytes();
                assertSame(first.runtime(), reused.runtime());
                assertEquals(retainedBefore, metrics.getActiveRuntimeWeightBytes());

                final long remaining = hardLimitMiB * 1024L * 1024L - cache.estimatedRetainedWeightBytes();
                assertTrue(remaining > 0L);
                final int rejectedPayloadBytes = Math.toIntExact(remaining + 64L * 1024L);
                final IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> cache.acquire(List.of(
                                packWithPayload(UUID.randomUUID(), rejectedPayloadBytes, (byte) 4))));
                assertTrue(failure.getMessage().contains("hard memory limit"));
                assertEquals(2L, metrics.getActiveRuntimeLeases());
                try (var hot = cache.acquire(List.of(firstPack))) {
                    assertSame(first.runtime(), hot.runtime());
                }
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void digestMemoizationUsesWeakIdentityKeys() {
        final SharedPackRuntimeCache.WeakIdentityMap<EqualKey, String> map =
                new SharedPackRuntimeCache.WeakIdentityMap<>();
        final EqualKey first = new EqualKey(1);
        final EqualKey second = new EqualKey(1);
        assertEquals(first, second);
        assertNull(map.putIfAbsent(first, "first"));
        assertNull(map.putIfAbsent(second, "second"));
        assertEquals("first", map.get(first));
        assertEquals("second", map.get(second));
        assertEquals(2, map.size());

        final Class<?> weakKey = Arrays.stream(SharedPackRuntimeCache.WeakIdentityMap.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("IdentityWeakReference"))
                .findFirst()
                .orElseThrow();
        assertTrue(WeakReference.class.isAssignableFrom(weakKey));
    }

    private static ViaBedrockConfig config(final Path tempDir) throws Exception {
        return config(tempDir, 0);
    }

    private static ViaBedrockConfig configWithIdleMinutes(
            final Path tempDir, final int idleMinutes) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock.yml");
        Files.writeString(configPath, "resource-pack-cache:\n"
                + "  memory-budget-mib: 64\n"
                + "  memory-hard-limit-mib: 128\n"
                + "  idle-expire-minutes: " + idleMinutes + "\n");
        final ViaBedrockConfig config = new ViaBedrockConfig(
                configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        return config;
    }

    private static ViaBedrockConfig config(final Path tempDir, final int cpuWorkers) throws Exception {
        return config(tempDir, cpuWorkers, 64, 128);
    }

    private static ViaBedrockConfig config(final Path tempDir, final int cpuWorkers,
                                            final int memoryBudgetMiB, final int hardLimitMiB) throws Exception {
        return config(tempDir, cpuWorkers, memoryBudgetMiB, hardLimitMiB, true);
    }

    private static ViaBedrockConfig config(final Path tempDir, final int cpuWorkers,
                                            final int memoryBudgetMiB, final int hardLimitMiB,
                                            final boolean serverAnimationEnabled) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock.yml");
        Files.writeString(configPath, "enable-server-entity-animation: " + serverAnimationEnabled + "\n"
                + "resource-pack-cache:\n  memory-budget-mib: " + memoryBudgetMiB + "\n"
                + "  memory-hard-limit-mib: " + hardLimitMiB + "\n  cpu-workers: " + cpuWorkers + "\n");
        final ViaBedrockConfig config = new ViaBedrockConfig(configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        return config;
    }

    private static void awaitLatch(final CountDownLatch latch) {
        try {
            if (!latch.await(10L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", e);
        }
    }

    private static void awaitMetric(final java.util.function.BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for cache metric");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
    }

    private static RetentionProbe createRetentionProbe(final SharedPackRuntimeCache cache) {
        final ResourcePack pack = packWithPayload(UUID.randomUUID(), 2 * 1024 * 1024, (byte) 9);
        final WeakReference<ResourcePack> packReference = new WeakReference<>(pack);
        final WeakReference<Content> contentReference = new WeakReference<>(pack.content());
        return new RetentionProbe(cache.acquire(List.of(pack)), packReference, contentReference);
    }

    private static void awaitCollected(final WeakReference<?>... references) {
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean collected = true;
            for (WeakReference<?> reference : references) {
                collected &= reference.get() == null;
            }
            if (collected) return;
            System.gc();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
        }
        fail("Descriptor-only runtime retained its source ResourcePack or Content");
    }

    private static ResourcePack pack(final UUID id, final String version, final String marker) {
        return pack(id, version, marker, new String[0]);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<?, WeakReference<?>> weakMap(final Object owner, final String name)
            throws ReflectiveOperationException {
        return (ConcurrentMap<?, WeakReference<?>>) field(owner, name);
    }

    private static Object field(final Object owner, final String name) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static WeakReference<FrozenPackBlob> evictCompletedBlob(
            final SharedPackRuntimeCache cache, final ContentDigest digest) {
        final FrozenPackBlob blob = cache.findCompletedBlob(digest);
        assertNotNull(blob);
        cache.invalidateCompletedBlob(digest);
        return new WeakReference<>(blob);
    }

    private static ResourcePack pack(final UUID id, final String version, final String marker,
                                     final String... pathAndContents) {
        if ((pathAndContents.length & 1) != 0) {
            throw new IllegalArgumentException("Path/content arguments must be paired");
        }
        final JsonObject header = new JsonObject();
        header.addProperty("uuid", id.toString());
        final JsonArray versionArray = new JsonArray();
        for (String component : version.split("\\.")) {
            versionArray.add(Integer.parseInt(component));
        }
        header.add("version", versionArray);
        header.addProperty("name", "test");
        final JsonObject manifest = new JsonObject();
        manifest.addProperty("format_version", 2);
        manifest.add("header", header);
        final InMemoryContent content = new InMemoryContent();
        content.putJson("manifest.json", manifest);
        content.putString("marker.txt", marker);
        for (int i = 0; i < pathAndContents.length; i += 2) {
            content.putString(pathAndContents[i], pathAndContents[i + 1]);
        }
        return new ResourcePack(content);
    }

    private static ResourcePack packWithPayload(final UUID id, final int payloadBytes, final byte fill) {
        final ResourcePack pack = pack(id, "1.0.0", "payload");
        final byte[] payload = new byte[payloadBytes];
        Arrays.fill(payload, fill);
        pack.content().put("payload.bin", payload);
        return pack;
    }

    private static ParsedPackLayer findLayer(final SharedPackRuntime runtime, final ResourcePack.Key key) {
        return runtime.parsedLayersBottomToTop().stream()
                .filter(layer -> layer.sourceKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static SharedPackRuntime.PackSource findPackSource(
            final SharedPackRuntime runtime, final ResourcePack.Key key) {
        return runtime.packSourcesBottomToTop().stream()
                .filter(source -> source.mount().alias().toResourcePackKey().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static String itemJson(final String identifier, final String icon) {
        return """
                {"minecraft:item":{"description":{"identifier":"%s"},"components":{"minecraft:icon":"%s"}}}
                """.formatted(identifier, icon);
    }

    private static String animationJson(final String identifier) {
        return """
                {"format_version":"1.8.0","animations":{"%s":{"loop":true}}}
                """.formatted(identifier);
    }

    private record EqualKey(int value) {
    }

    private record RetentionProbe(SharedPackRuntimeCache.RuntimeLease lease,
                                  WeakReference<ResourcePack> pack,
                                  WeakReference<Content> content) {
    }

    private record CacheBuildCounts(long blobs, long layers, long runtimes, long motions) {

        private static CacheBuildCounts capture(final ResourcePackCacheMetrics metrics) {
            return new CacheBuildCounts(
                    metrics.getBlobBuilds(), metrics.getLayerBuilds(), metrics.getRuntimeBuilds(),
                    metrics.getMotionBuilds());
        }
    }

}
