/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack;

import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPackCacheTest {

    private static final String CACHE_KEY_A = "a".repeat(64);
    private static final String CACHE_KEY_B = "b".repeat(64);

    private ResourcePackWorkScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (this.scheduler != null) {
            this.scheduler.shutdown();
        }
    }

    @Test
    void publishesAndReloadsContentAddressedArtifact(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final byte[] data = "converted-pack".getBytes(StandardCharsets.UTF_8);

        final String hash = cache.put("cache-key", data);

        assertEquals(hash, cache.getValidHash("cache-key"));
        assertArrayEquals(data, cache.getData("cache-key"));
        assertTrue(cache.getArtifactFile(hash).isFile());
        assertArrayEquals(data, Files.readAllBytes(cache.getArtifactFile(hash).toPath()));

        final JavaPackCache reloaded = new JavaPackCache(tempDir.toFile());
        assertEquals(hash, reloaded.getValidHash("cache-key"));
    }

    @Test
    void rejectsTruncatedCacheEntry(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics);
        final byte[] complete = "complete".getBytes(StandardCharsets.UTF_8);
        final String hash = cache.put(CACHE_KEY_A, complete);
        final Path cacheZip = tempDir.resolve(CACHE_KEY_A + ".zip");

        Files.delete(cacheZip); // 先断开与不可变产物之间可能的硬链接。
        Files.writeString(cacheZip, "truncated", StandardCharsets.UTF_8);

        assertNull(cache.getValidHash(CACHE_KEY_A));
        assertFalse(Files.exists(cacheZip));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".sha1")));
        assertEquals(1L, metrics.getArtifactDiskFiles());
        assertEquals(complete.length, metrics.getArtifactDiskBytes());
        assertEquals(metrics.getArtifactDiskBytes(), metrics.getArtifactWeightBytes());
        assertNotNull(hash);
    }

    @Test
    void repairsMissingArtifactFromValidCache(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final byte[] data = "converted-pack".getBytes(StandardCharsets.UTF_8);
        final String hash = cache.put("cache-key", data);
        final Path artifact = cache.getArtifactFile(hash).toPath();
        Files.delete(artifact);

        assertEquals(hash, cache.getValidHash("cache-key"));
        assertArrayEquals(data, Files.readAllBytes(artifact));
    }

    @Test
    void missingDiskEntryAndMaintenanceDropValidatedStrongKeys(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        cache.put(CACHE_KEY_A, "first".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, cache.validatedEntryCount());
        Files.delete(tempDir.resolve(CACHE_KEY_A + ".zip"));

        assertNull(cache.getValidHash(CACHE_KEY_A));
        assertEquals(0, cache.validatedEntryCount());

        cache.put(CACHE_KEY_B, "second".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, cache.validatedEntryCount());
        Files.delete(tempDir.resolve(CACHE_KEY_B + ".sha1"));

        cache.cleanupNow();

        assertEquals(0, cache.validatedEntryCount());
    }

    @Test
    void concurrentWaitersBuildOnceAndReceiveTheSameArtifact(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), this.scheduler(metrics), metrics);
        final byte[] data = "single-flight-artifact".getBytes(StandardCharsets.UTF_8);
        final AtomicInteger builds = new AtomicInteger();
        final CountDownLatch buildStarted = new CountDownLatch(1);
        final CountDownLatch releaseBuild = new CountDownLatch(1);
        final JavaPackCache.ArtifactBuilder builder = target -> {
            builds.incrementAndGet();
            buildStarted.countDown();
            if (!releaseBuild.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release artifact builder");
            }
            Files.write(target, data);
        };

        final List<CompletableFuture<JavaPackCache.ArtifactRef>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(cache.getOrBuild("shared-key", builder));
        }

        try {
            assertTrue(buildStarted.await(10, TimeUnit.SECONDS));
            assertEquals(1, builds.get());
        } finally {
            releaseBuild.countDown();
        }

        final List<JavaPackCache.ArtifactRef> artifacts = new ArrayList<>();
        for (CompletableFuture<JavaPackCache.ArtifactRef> request : requests) {
            artifacts.add(request.get(10, TimeUnit.SECONDS));
        }
        for (JavaPackCache.ArtifactRef artifact : artifacts) {
            assertEquals(artifacts.getFirst(), artifact);
        }
        assertArrayEquals(data, Files.readAllBytes(artifacts.getFirst().path()));
        assertEquals(1, metrics.getArtifactBuilds());
        assertEquals(1L, metrics.getArtifactBuildTimeSamples());
        assertEquals(19, metrics.getArtifactWaiters());
        assertTrue(metrics.getArtifactWeightBytes() > 0L);
        assertEquals(metrics.getArtifactDiskBytes(), metrics.getArtifactWeightBytes());
        assertTrue(metrics.getArtifactMaxWeightBytes() > 0L);
    }

    @Test
    void completedArtifactClosesMissBeforeFlightRegistrationRace(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final CountDownLatch firstMiss = new CountDownLatch(1);
        final CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        final AtomicInteger missHooks = new AtomicInteger();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), this.scheduler(metrics), metrics) {
            @Override
            void afterArtifactCacheMissBeforeFlight(final String cacheKey) {
                if (missHooks.incrementAndGet() != 1) {
                    return;
                }
                firstMiss.countDown();
                try {
                    if (!releaseFirstRequest.await(10L, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to resume the first cache request");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting to resume the first cache request", e);
                }
            }
        };
        final AtomicInteger builds = new AtomicInteger();
        final JavaPackCache.ArtifactBuilder builder = target -> {
            builds.incrementAndGet();
            Files.writeString(target, "single-flight", StandardCharsets.UTF_8);
        };
        final CompletableFuture<CompletableFuture<JavaPackCache.ArtifactRef>> firstCall =
                CompletableFuture.supplyAsync(() -> cache.getOrBuild(CACHE_KEY_A, builder));

        final JavaPackCache.ArtifactRef secondArtifact;
        try {
            assertTrue(firstMiss.await(10L, TimeUnit.SECONDS));
            secondArtifact = cache.getOrBuild(CACHE_KEY_A, builder).get(10L, TimeUnit.SECONDS);
        } finally {
            releaseFirstRequest.countDown();
        }
        final JavaPackCache.ArtifactRef firstArtifact = firstCall.thenCompose(future -> future)
                .get(10L, TimeUnit.SECONDS);

        assertEquals(secondArtifact, firstArtifact);
        assertEquals(1, builds.get());
        assertEquals(1L, metrics.getArtifactBuilds());
        assertEquals(1L, metrics.getArtifactHits());
    }

    @Test
    void failedBuildIsBackedOffForFiveSecondsBeforeRetry(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final AtomicLong nanoTime = new AtomicLong();
        final JavaPackCache cache = new JavaPackCache(
                tempDir.toFile(), this.scheduler(metrics), metrics, nanoTime::get);
        final AtomicInteger attempts = new AtomicInteger();
        final JavaPackCache.ArtifactBuilder builder = target -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("temporary conversion failure");
            }
            Files.writeString(target, "recovered", StandardCharsets.UTF_8);
        };

        final CompletableFuture<JavaPackCache.ArtifactRef> failed = cache.getOrBuild("retry-key", builder);
        final ExecutionException firstFailure = assertThrows(
                ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));
        final ExecutionException backedOff = assertThrows(ExecutionException.class,
                () -> cache.getOrBuild("retry-key", builder).get(10, TimeUnit.SECONDS));
        assertSame(firstFailure.getCause(), backedOff.getCause());
        assertEquals(1, attempts.get());
        assertEquals(1, metrics.getArtifactBuilds());

        nanoTime.addAndGet(TimeUnit.SECONDS.toNanos(5L));
        final JavaPackCache.ArtifactRef recovered = cache.getOrBuild("retry-key", builder)
                .get(10, TimeUnit.SECONDS);

        assertEquals(2, attempts.get());
        assertEquals("recovered", Files.readString(recovered.path(), StandardCharsets.UTF_8));
        assertEquals(2, metrics.getArtifactBuilds());
        assertEquals(1, metrics.getArtifactFailures());
    }

    @Test
    void cancellingAWaiterDoesNotCancelTheSharedBuild(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), this.scheduler(metrics), metrics);
        final CountDownLatch buildStarted = new CountDownLatch(1);
        final CountDownLatch releaseBuild = new CountDownLatch(1);
        final AtomicInteger builds = new AtomicInteger();
        final JavaPackCache.ArtifactBuilder builder = target -> {
            builds.incrementAndGet();
            buildStarted.countDown();
            if (!releaseBuild.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release artifact builder");
            }
            Files.writeString(target, "shared", StandardCharsets.UTF_8);
        };

        final CompletableFuture<JavaPackCache.ArtifactRef> leader = cache.getOrBuild("cancel-key", builder);
        final CompletableFuture<JavaPackCache.ArtifactRef> waiter = cache.getOrBuild("cancel-key", builder);
        assertTrue(buildStarted.await(10, TimeUnit.SECONDS));
        assertTrue(waiter.cancel(false));
        releaseBuild.countDown();

        assertEquals("shared", Files.readString(leader.get(10, TimeUnit.SECONDS).path()));
        assertEquals(1, builds.get());
        assertEquals(1, metrics.getArtifactBuilds());
    }

    @Test
    void startupDeletesOnlyOwnedTemporaryFiles(@TempDir final Path tempDir) throws Exception {
        final Path artifacts = Files.createDirectories(tempDir.resolve("artifacts"));
        final Path cacheTemp = Files.writeString(
                tempDir.resolve(CACHE_KEY_A + "-123.zip.tmp"), "partial", StandardCharsets.UTF_8);
        final Path metadataTemp = Files.writeString(
                tempDir.resolve(CACHE_KEY_A + "-123.sha1.tmp"), "partial", StandardCharsets.UTF_8);
        final Path artifactTemp = Files.writeString(
                artifacts.resolve("1".repeat(40) + "-123.zip.tmp"), "partial", StandardCharsets.UTF_8);
        final Path unknown = Files.writeString(tempDir.resolve("operator-notes.zip.tmp"), "keep");
        final Path unknownArtifact = Files.writeString(artifacts.resolve("README.tmp"), "keep");

        new JavaPackCache(tempDir.toFile());

        assertFalse(Files.exists(cacheTemp));
        assertFalse(Files.exists(metadataTemp));
        assertFalse(Files.exists(artifactTemp));
        assertTrue(Files.exists(unknown));
        assertTrue(Files.exists(unknownArtifact));
    }

    @Test
    void schedulerRegistersFixedRateMaintenance(@TempDir final Path tempDir) {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache scheduled = new JavaPackCache(
                tempDir.resolve("scheduled").toFile(), this.scheduler(metrics), metrics);
        final JavaPackCache opportunisticOnly = new JavaPackCache(tempDir.resolve("local").toFile());

        assertTrue(scheduled.hasScheduledMaintenance());
        assertFalse(opportunisticOnly.hasScheduledMaintenance());
    }

    @Test
    void idleCleanupDeletesKeyPairAndUnleasedContentArtifact(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = lifecycleCache(
                tempDir, metrics, now, 16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(1L));
        final String hash = cache.put(CACHE_KEY_A, "idle-artifact".getBytes(StandardCharsets.UTF_8));
        ageEntry(tempDir, cache, CACHE_KEY_A, hash, now.get() - TimeUnit.DAYS.toMillis(2L));

        cache.cleanupNow();

        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".sha1")));
        assertFalse(cache.getArtifactFile(hash).isFile());
        assertTrue(metrics.getArtifactDiskDeletedBytes() > 0L);
        assertTrue(metrics.getArtifactDiskDeletedFiles() > 0L);
        assertEquals(0L, metrics.getArtifactDiskBytes());
        assertEquals(0L, metrics.getArtifactDiskFiles());
        assertEquals(0L, metrics.getArtifactWeightBytes());
        assertEquals(16L * 1024L * 1024L, metrics.getArtifactMaxWeightBytes());
    }

    @Test
    void activeArtifactLeasePinsIdleFilesUntilReleased(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = lifecycleCache(
                tempDir, metrics, now, 16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(1L));
        final String hash = cache.put(CACHE_KEY_A, "leased-artifact".getBytes(StandardCharsets.UTF_8));
        assertTrue(metrics.getArtifactWeightBytes() > 0L);
        assertEquals(metrics.getArtifactDiskBytes(), metrics.getArtifactWeightBytes());
        final long old = now.get() - TimeUnit.DAYS.toMillis(2L);
        ageEntry(tempDir, cache, CACHE_KEY_A, hash, old);
        final JavaPackCache.ArtifactLease lease = cache.acquireArtifact(hash);
        assertNotNull(lease);
        ageEntry(tempDir, cache, CACHE_KEY_A, hash, old);

        cache.cleanupNow();

        assertTrue(Files.isRegularFile(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertTrue(cache.getArtifactFile(hash).isFile());
        assertEquals(1L, metrics.getActiveArtifactLeases());

        lease.close();
        cache.cleanupNow();

        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(cache.getArtifactFile(hash).isFile());
        assertEquals(0L, metrics.getActiveArtifactLeases());
    }

    @Test
    void getOrBuildLeaseHotHitPinsValidationUntilPublicationLeaseAcquired(
            @TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final CountDownLatch acquisitionStarted = new CountDownLatch(1);
        final CountDownLatch releaseAcquisition = new CountDownLatch(1);
        final AtomicBoolean blockAcquisition = new AtomicBoolean();
        final JavaPackCache cache = new JavaPackCache(
                tempDir.toFile(), null, metrics, System::nanoTime, now::get,
                16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(1L)) {
            @Override
            public ArtifactLease acquireArtifact(final ArtifactRef artifact) throws IOException {
                if (blockAcquisition.get()) {
                    acquisitionStarted.countDown();
                    try {
                        if (!releaseAcquisition.await(10L, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to acquire the publication lease");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Publication lease acquisition was interrupted", e);
                    }
                }
                return super.acquireArtifact(artifact);
            }
        };
        final String hash = cache.put(CACHE_KEY_A, "hot-publication".getBytes(StandardCharsets.UTF_8));
        final AtomicInteger unexpectedBuilds = new AtomicInteger();
        blockAcquisition.set(true);
        final CompletableFuture<CompletableFuture<JavaPackCache.ArtifactLease>> invocation =
                CompletableFuture.supplyAsync(() -> cache.getOrBuildLease(CACHE_KEY_A, target -> {
                    unexpectedBuilds.incrementAndGet();
                    Files.writeString(target, "unexpected rebuild", StandardCharsets.UTF_8);
                }));

        try {
            assertTrue(acquisitionStarted.await(10L, TimeUnit.SECONDS));
            ageEntry(tempDir, cache, CACHE_KEY_A, hash,
                    now.get() - TimeUnit.DAYS.toMillis(2L));

            cache.cleanupNow();

            assertTrue(Files.isRegularFile(tempDir.resolve(CACHE_KEY_A + ".zip")));
            assertTrue(Files.isRegularFile(tempDir.resolve(CACHE_KEY_A + ".sha1")));
            assertTrue(cache.getArtifactFile(hash).isFile());
            assertEquals(0, unexpectedBuilds.get());
            assertEquals(0L, metrics.getActiveArtifactLeases());
        } finally {
            releaseAcquisition.countDown();
        }

        final JavaPackCache.ArtifactLease lease = invocation.thenCompose(future -> future)
                .get(10L, TimeUnit.SECONDS);
        try {
            assertNotNull(lease);
            assertEquals(hash, lease.artifact().hash());
            assertEquals(1L, metrics.getActiveArtifactLeases());
        } finally {
            lease.close();
        }

        ageEntry(tempDir, cache, CACHE_KEY_A, hash,
                now.get() - TimeUnit.DAYS.toMillis(2L));
        cache.cleanupNow();

        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".sha1")));
        assertFalse(cache.getArtifactFile(hash).isFile());
        assertEquals(0L, metrics.getActiveArtifactLeases());
    }

    @Test
    void artifactHashingDoesNotBlockConcurrentLeaseAcquisition(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final CountDownLatch hashStarted = new CountDownLatch(1);
        final CountDownLatch releaseHash = new CountDownLatch(1);
        final AtomicBoolean blockHashing = new AtomicBoolean();
        final JavaPackCache cache = new JavaPackCache(
                tempDir.toFile(), this.scheduler(metrics), metrics) {
            @Override
            String computeArtifactHash(final Path path) throws IOException {
                if (blockHashing.get()) {
                    hashStarted.countDown();
                    try {
                        if (!releaseHash.await(10L, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release artifact hashing");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Artifact hashing was interrupted", e);
                    }
                }
                return super.computeArtifactHash(path);
            }
        };
        final String existingHash = cache.put(CACHE_KEY_A, "existing".getBytes(StandardCharsets.UTF_8));
        blockHashing.set(true);
        final CompletableFuture<JavaPackCache.ArtifactRef> build = cache.getOrBuild(
                CACHE_KEY_B, target -> Files.writeString(target, "new artifact", StandardCharsets.UTF_8));
        assertTrue(hashStarted.await(10L, TimeUnit.SECONDS));

        final CompletableFuture<JavaPackCache.ArtifactLease> acquisition = CompletableFuture.supplyAsync(() -> {
            try {
                return cache.acquireArtifact(existingHash);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        JavaPackCache.ArtifactLease lease = null;
        try {
            lease = acquisition.get(2L, TimeUnit.SECONDS);
            assertNotNull(lease);
        } finally {
            if (lease != null) lease.close();
            releaseHash.countDown();
            build.get(10L, TimeUnit.SECONDS);
        }
        assertEquals(0L, metrics.getActiveArtifactLeases());
    }

    @Test
    void coldCacheValidationDoesNotBlockConcurrentLeaseAcquisition(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache writer = new JavaPackCache(tempDir.toFile());
        final String existingHash = writer.put(CACHE_KEY_A, "existing".getBytes(StandardCharsets.UTF_8));
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final CountDownLatch hashStarted = new CountDownLatch(1);
        final CountDownLatch releaseHash = new CountDownLatch(1);
        final JavaPackCache reader = new JavaPackCache(
                tempDir.toFile(), this.scheduler(metrics), metrics) {
            @Override
            String computeCacheEntryHash(final Path path) throws IOException {
                hashStarted.countDown();
                try {
                    if (!releaseHash.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release cache validation hashing");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Cache validation hashing was interrupted", e);
                }
                return super.computeCacheEntryHash(path);
            }
        };
        final CompletableFuture<JavaPackCache.ArtifactRef> validation = CompletableFuture.supplyAsync(() -> {
            try {
                return reader.getArtifact(CACHE_KEY_A);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        assertTrue(hashStarted.await(10L, TimeUnit.SECONDS));

        final CompletableFuture<JavaPackCache.ArtifactLease> acquisition = CompletableFuture.supplyAsync(() -> {
            try {
                return reader.acquireArtifact(existingHash);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        JavaPackCache.ArtifactLease lease = null;
        try {
            lease = acquisition.get(2L, TimeUnit.SECONDS);
            assertNotNull(lease);
        } finally {
            if (lease != null) lease.close();
            releaseHash.countDown();
            assertNotNull(validation.get(10L, TimeUnit.SECONDS));
        }
        assertEquals(0L, metrics.getActiveArtifactLeases());
    }

    @Test
    void blockedCleanerScanDoesNotBlockArtifactLeaseAcquisition(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final AtomicBoolean blockScans = new AtomicBoolean();
        final CountDownLatch scanStarted = new CountDownLatch(1);
        final CountDownLatch releaseScan = new CountDownLatch(1);
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics) {
            @Override
            void beforeCacheScan() throws IOException {
                if (!blockScans.get()) return;
                scanStarted.countDown();
                try {
                    if (!releaseScan.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release cache scan");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Cache scan was interrupted", e);
                }
            }
        };
        final String hash = cache.put(CACHE_KEY_A, "existing".getBytes(StandardCharsets.UTF_8));
        blockScans.set(true);
        final CompletableFuture<Void> cleanup = CompletableFuture.runAsync(() -> {
            try {
                cache.cleanupNow();
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        assertTrue(scanStarted.await(10L, TimeUnit.SECONDS));

        final CompletableFuture<JavaPackCache.ArtifactLease> acquisition = CompletableFuture.supplyAsync(() -> {
            try {
                return cache.acquireArtifact(hash);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        JavaPackCache.ArtifactLease lease = null;
        try {
            lease = acquisition.get(2L, TimeUnit.SECONDS);
            assertNotNull(lease);
        } finally {
            if (lease != null) lease.close();
            releaseScan.countDown();
            cleanup.get(10L, TimeUnit.SECONDS);
        }
        assertEquals(0L, metrics.getActiveArtifactLeases());
    }

    @Test
    void blockedInvalidationMetricsScanDoesNotBlockArtifactLeaseAcquisition(
            @TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final AtomicBoolean blockScans = new AtomicBoolean();
        final CountDownLatch scanStarted = new CountDownLatch(1);
        final CountDownLatch releaseScan = new CountDownLatch(1);
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics) {
            @Override
            void beforeCacheScan() throws IOException {
                if (!blockScans.get()) return;
                scanStarted.countDown();
                try {
                    if (!releaseScan.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release invalidation metrics scan");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Invalidation metrics scan was interrupted", e);
                }
            }
        };
        final byte[] complete = "existing".getBytes(StandardCharsets.UTF_8);
        final String hash = cache.put(CACHE_KEY_A, complete);
        final Path cacheZip = tempDir.resolve(CACHE_KEY_A + ".zip");
        Files.delete(cacheZip);
        Files.writeString(cacheZip, "truncated", StandardCharsets.UTF_8);
        blockScans.set(true);

        final CompletableFuture<String> invalidation = CompletableFuture.supplyAsync(() -> {
            try {
                return cache.getValidHash(CACHE_KEY_A);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        assertTrue(scanStarted.await(10L, TimeUnit.SECONDS));

        final CompletableFuture<JavaPackCache.ArtifactLease> acquisition = CompletableFuture.supplyAsync(() -> {
            try {
                return cache.acquireArtifact(hash);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        JavaPackCache.ArtifactLease lease = null;
        try {
            lease = acquisition.get(2L, TimeUnit.SECONDS);
            assertNotNull(lease);
        } finally {
            if (lease != null) lease.close();
            releaseScan.countDown();
            assertNull(invalidation.get(10L, TimeUnit.SECONDS));
        }
        assertEquals(0L, metrics.getActiveArtifactLeases());
        assertEquals(1L, metrics.getArtifactDiskFiles());
        assertEquals(complete.length, metrics.getArtifactDiskBytes());
    }

    @Test
    void failedDiskMetricRefreshHasItsOwnMetric(@TempDir final Path tempDir) throws Exception {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final AtomicBoolean failScans = new AtomicBoolean();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile(), null, metrics) {
            @Override
            void beforeCacheScan() throws IOException {
                if (failScans.get()) throw new IOException("expected metrics scan failure");
            }
        };
        final String hash = cache.put(CACHE_KEY_A, "complete".getBytes(StandardCharsets.UTF_8));
        Files.writeString(tempDir.resolve(CACHE_KEY_A + ".sha1"), "invalid", StandardCharsets.UTF_8);
        failScans.set(true);

        assertNull(cache.getValidHash(CACHE_KEY_A));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".sha1")));
        assertTrue(cache.getArtifactFile(hash).isFile());
        assertEquals(1L, metrics.getArtifactDiskMetricRefreshFailures());
        assertEquals(0L, metrics.getArtifactDiskCleanupFailures());
    }

    @Test
    void artifactAccessTouchKeepsHardLinkedCacheEntryHot(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final AtomicInteger cacheValidations = new AtomicInteger();
        final JavaPackCache cache = new JavaPackCache(
                tempDir.toFile(), null, new ResourcePackCacheMetrics(), System::nanoTime, now::get,
                16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(30L)) {
            @Override
            String computeCacheEntryHash(final Path path) throws IOException {
                cacheValidations.incrementAndGet();
                return super.computeCacheEntryHash(path);
            }
        };
        final String hash = cache.put(CACHE_KEY_A, "hot-artifact".getBytes(StandardCharsets.UTF_8));
        now.addAndGet(TimeUnit.MINUTES.toMillis(11L));

        try (JavaPackCache.ArtifactLease ignored = cache.acquireArtifact(hash)) {
            assertNotNull(ignored);
        }
        assertNotNull(cache.getArtifact(CACHE_KEY_A));

        assertEquals(0, cacheValidations.get());
    }

    @Test
    void failedArtifactSizeReadDoesNotLeakLease(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = new JavaPackCache(
                tempDir.toFile(), null, metrics, System::nanoTime, now::get,
                16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(1L)) {
            @Override
            long artifactSize(final Path path) throws IOException {
                throw new IOException("simulated artifact stat failure");
            }
        };
        final String hash = cache.put(CACHE_KEY_A, "leased-artifact".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> cache.acquireArtifact(hash));
        assertEquals(0L, metrics.getActiveArtifactLeases());

        ageEntry(tempDir, cache, CACHE_KEY_A, hash, now.get() - TimeUnit.DAYS.toMillis(2L));
        cache.cleanupNow();
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(cache.getArtifactFile(hash).isFile());
    }

    @Test
    void inflightBuildPinsItsAgedTemporaryFile(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = new JavaPackCache(
                tempDir.toFile(), this.scheduler(metrics), metrics, System::nanoTime, now::get,
                16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(1L));
        final CountDownLatch buildStarted = new CountDownLatch(1);
        final CountDownLatch releaseBuild = new CountDownLatch(1);
        final AtomicReference<Path> buildTemp = new AtomicReference<>();
        final CompletableFuture<JavaPackCache.ArtifactRef> build = cache.getOrBuild(CACHE_KEY_A, target -> {
            buildTemp.set(target);
            Files.setLastModifiedTime(target, FileTime.fromMillis(now.get() - TimeUnit.DAYS.toMillis(2L)));
            buildStarted.countDown();
            if (!releaseBuild.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release artifact build");
            }
            Files.writeString(target, "completed", StandardCharsets.UTF_8);
        });
        assertTrue(buildStarted.await(10, TimeUnit.SECONDS));

        cache.cleanupNow();

        assertTrue(Files.isRegularFile(buildTemp.get()));
        releaseBuild.countDown();
        assertEquals("completed", Files.readString(build.get(10, TimeUnit.SECONDS).path()));
    }

    @Test
    void diskUsageCountsHardLinkedArtifactOnlyOnce(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = lifecycleCache(
                tempDir, metrics, now, 16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(30L));
        final byte[] data = "hard-linked-artifact".getBytes(StandardCharsets.UTF_8);
        final String hash = cache.put(CACHE_KEY_A, data);

        cache.cleanupNow();

        final boolean hardLinked = Files.isSameFile(
                tempDir.resolve(CACHE_KEY_A + ".zip"), cache.getArtifactFile(hash).toPath());
        assertEquals(hardLinked ? 2L : 3L, metrics.getArtifactDiskFiles());
        assertEquals(hardLinked ? data.length + 40L : data.length * 2L + 40L, metrics.getArtifactDiskBytes());
        assertEquals(metrics.getArtifactDiskBytes(), metrics.getArtifactWeightBytes());
        assertEquals(16L * 1024L * 1024L, metrics.getArtifactMaxWeightBytes());
    }

    @Test
    void diskBudgetEvictsOldestCompleteArtifactSet(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = lifecycleCache(
                tempDir, metrics, now, 1L, TimeUnit.DAYS.toMillis(30L));
        final String hash = cache.put(CACHE_KEY_A, "over-budget".getBytes(StandardCharsets.UTF_8));
        ageEntry(tempDir, cache, CACHE_KEY_A, hash, now.get() - TimeUnit.MINUTES.toMillis(2L));

        cache.cleanupNow();

        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".sha1")));
        assertFalse(cache.getArtifactFile(hash).isFile());
        assertEquals(0L, metrics.getArtifactDiskBytes());
    }

    @Test
    void configuredConstructorAppliesDiskBudgetMiBAndIdleDays(@TempDir final Path tempDir) throws Exception {
        final Path budgetDirectory = Files.createDirectory(tempDir.resolve("budget"));
        final JavaPackCache budgetCache = new JavaPackCache(
                budgetDirectory.toFile(), null, new ResourcePackCacheMetrics(), 1, 30);
        final byte[] oversized = new byte[(int) (1024L * 1024L + 128L)];
        final String budgetHash = budgetCache.put(CACHE_KEY_A, oversized);
        ageEntry(budgetDirectory, budgetCache, CACHE_KEY_A, budgetHash,
                System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(2L));

        budgetCache.cleanupNow();

        assertFalse(Files.exists(budgetDirectory.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(budgetCache.getArtifactFile(budgetHash).isFile());

        final Path idleDirectory = Files.createDirectory(tempDir.resolve("idle"));
        final JavaPackCache idleCache = new JavaPackCache(
                idleDirectory.toFile(), null, new ResourcePackCacheMetrics(), 16, 1);
        final String idleHash = idleCache.put(CACHE_KEY_B, "idle".getBytes(StandardCharsets.UTF_8));
        ageEntry(idleDirectory, idleCache, CACHE_KEY_B, idleHash,
                System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2L));

        idleCache.cleanupNow();

        assertFalse(Files.exists(idleDirectory.resolve(CACHE_KEY_B + ".zip")));
        assertFalse(idleCache.getArtifactFile(idleHash).isFile());
    }

    @Test
    void conflictingSha1ArtifactIsNeverOverwritten(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final byte[] expected = "new-converted-pack".getBytes(StandardCharsets.UTF_8);
        final byte[] conflicting = "different-existing-content".getBytes(StandardCharsets.UTF_8);
        final String hash = sha1(expected);
        final Path artifact = cache.getArtifactFile(hash).toPath();
        Files.write(artifact, conflicting);

        final IOException collision = assertThrows(IOException.class, () -> cache.put(CACHE_KEY_B, expected));

        assertTrue(collision.getMessage().contains("collision"));
        assertArrayEquals(conflicting, Files.readAllBytes(artifact));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_B + ".zip")));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_B + ".sha1")));
    }

    @Test
    void hashedBuilderAvoidsSecondArtifactReadAndStillPublishesAtomically(
            @TempDir final Path tempDir) throws Exception {
        final AtomicInteger postBuildHashScans = new AtomicInteger();
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile()) {
            @Override
            String computeArtifactHash(final Path path) throws IOException {
                postBuildHashScans.incrementAndGet();
                return super.computeArtifactHash(path);
            }
        };
        final byte[] data = "streamed-final-zip".getBytes(StandardCharsets.UTF_8);
        final String hash = sha1(data);

        final JavaPackCache.ArtifactRef artifact = cache.getOrBuildHashed(CACHE_KEY_A, target -> {
            Files.write(target, data);
            return new JavaPackCache.ArtifactBuildResult(hash, data.length);
        }).get(10L, TimeUnit.SECONDS);

        assertEquals(0, postBuildHashScans.get());
        assertEquals(hash, artifact.hash());
        assertArrayEquals(data, Files.readAllBytes(artifact.path()));
        assertEquals(hash, cache.getValidHash(CACHE_KEY_A));
    }

    @Test
    void hashedBuilderRejectsSizeMismatchBeforePublication(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final byte[] data = "incomplete".getBytes(StandardCharsets.UTF_8);

        final ExecutionException failure = assertThrows(ExecutionException.class, () ->
                cache.getOrBuildHashed(CACHE_KEY_A, target -> {
                    Files.write(target, data);
                    return new JavaPackCache.ArtifactBuildResult(sha1(data), data.length + 1L);
                }).get(10L, TimeUnit.SECONDS));

        assertTrue(failure.getCause().getMessage().contains("reported"));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".sha1")));
    }

    @Test
    void hashedBuilderPreservesConflictingArtifactValidation(@TempDir final Path tempDir) throws Exception {
        final JavaPackCache cache = new JavaPackCache(tempDir.toFile());
        final byte[] expected = "hashed-converted-pack".getBytes(StandardCharsets.UTF_8);
        final byte[] conflicting = "existing-conflict".getBytes(StandardCharsets.UTF_8);
        final String hash = sha1(expected);
        Files.write(cache.getArtifactFile(hash).toPath(), conflicting);

        final ExecutionException collision = assertThrows(ExecutionException.class, () ->
                cache.getOrBuildHashed(CACHE_KEY_A, target -> {
                    Files.write(target, expected);
                    return new JavaPackCache.ArtifactBuildResult(hash, expected.length);
                }).get(10L, TimeUnit.SECONDS));

        assertTrue(collision.getCause().getMessage().contains("collision"));
        assertArrayEquals(conflicting, Files.readAllBytes(cache.getArtifactFile(hash).toPath()));
        assertFalse(Files.exists(tempDir.resolve(CACHE_KEY_A + ".zip")));
    }

    @Test
    void buildWorkspaceIsProtectedWhileActiveAndRemovedOnClose(@TempDir final Path tempDir) throws Exception {
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final JavaPackCache cache = lifecycleCache(
                tempDir, metrics, now, 16L * 1024L * 1024L, TimeUnit.DAYS.toMillis(30L));
        final JavaPackCache.BuildWorkspace workspace = cache.openBuildWorkspace();
        final Path payload = Files.writeString(workspace.path().resolve("converted.bin"), "workspace");
        Files.setLastModifiedTime(payload, FileTime.fromMillis(now.get() - TimeUnit.DAYS.toMillis(2L)));
        Files.setLastModifiedTime(workspace.path(), FileTime.fromMillis(now.get() - TimeUnit.DAYS.toMillis(2L)));

        cache.cleanupNow();

        assertTrue(Files.isRegularFile(payload));
        assertEquals(1L, metrics.getActiveArtifactBuildWorkspaces());
        assertEquals("workspace".length(), metrics.getArtifactBuildWorkspaceBytes());
        assertEquals(1L, metrics.getArtifactBuildWorkspaceFiles());
        assertEquals(metrics.getArtifactDiskBytes(), metrics.getArtifactWeightBytes());
        workspace.close();
        assertFalse(Files.exists(workspace.path()));
        assertEquals(0L, metrics.getActiveArtifactBuildWorkspaces());
        assertEquals(0L, metrics.getArtifactBuildWorkspaceBytes());
        assertTrue(metrics.getArtifactBuildWorkspaceDeletedBytes() >= "workspace".length());
        assertTrue(metrics.getArtifactBuildWorkspacePeakBytes() >= "workspace".length());
    }

    @Test
    void startupAndBudgetMaintenanceRemoveOnlyManagedAbandonedWorkspaces(
            @TempDir final Path tempDir) throws Exception {
        final Path work = Files.createDirectories(tempDir.resolve("work"));
        final Path startupStale = Files.createDirectories(
                work.resolve("build-00000000-0000-0000-0000-000000000001.tmp"));
        Files.write(startupStale.resolve("stale.bin"), new byte[128]);
        final Path unrelated = Files.createDirectories(work.resolve("operator-owned"));
        Files.writeString(unrelated.resolve("keep.txt"), "keep");
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final AtomicLong now = new AtomicLong(System.currentTimeMillis());

        final JavaPackCache cache = lifecycleCache(
                tempDir, metrics, now, 1L, TimeUnit.DAYS.toMillis(30L));

        assertFalse(Files.exists(startupStale));
        assertTrue(Files.isRegularFile(unrelated.resolve("keep.txt")));

        final Path overBudget = Files.createDirectories(
                work.resolve("build-00000000-0000-0000-0000-000000000002.tmp"));
        final Path payload = Files.write(overBudget.resolve("large.bin"), new byte[256]);
        final FileTime old = FileTime.fromMillis(now.get() - TimeUnit.MINUTES.toMillis(2L));
        Files.setLastModifiedTime(payload, old);
        Files.setLastModifiedTime(overBudget, old);
        cache.cleanupNow();

        assertFalse(Files.exists(overBudget));
        assertTrue(Files.isRegularFile(unrelated.resolve("keep.txt")));
        assertTrue(metrics.getArtifactBuildWorkspaceDeletedFiles() >= 2L);
    }

    private ResourcePackWorkScheduler scheduler(final ResourcePackCacheMetrics metrics) {
        final ViaBedrockConfig config = (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers", "getResourcePackCacheIoWorkers" -> 2;
                    case "getResourcePackCacheQueueCapacity" -> 64;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        return this.scheduler = new ResourcePackWorkScheduler(config, metrics);
    }

    private static JavaPackCache lifecycleCache(final Path directory, final ResourcePackCacheMetrics metrics,
                                                final AtomicLong now, final long diskBudgetBytes,
                                                final long diskIdleMillis) {
        return new JavaPackCache(directory.toFile(), null, metrics, System::nanoTime, now::get,
                diskBudgetBytes, diskIdleMillis);
    }

    private static void ageEntry(final Path directory, final JavaPackCache cache, final String cacheKey,
                                 final String hash, final long modifiedTime) throws IOException {
        final FileTime old = FileTime.fromMillis(modifiedTime);
        Files.setLastModifiedTime(directory.resolve(cacheKey + ".zip"), old);
        Files.setLastModifiedTime(directory.resolve(cacheKey + ".sha1"), old);
        Files.setLastModifiedTime(cache.getArtifactFile(hash).toPath(), old);
    }

    private static String sha1(final byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
    }

}
