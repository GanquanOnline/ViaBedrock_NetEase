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

import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipFileContent;
import net.raphimc.viabedrock.api.resourcepack.cache.ArchiveDigest;
import net.raphimc.viabedrock.api.resourcepack.cache.ContentDigest;
import net.raphimc.viabedrock.api.resourcepack.cache.PackAlias;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackArchiveStoreTest {

    private static final ResourcePack.Key PACK_KEY = new ResourcePack.Key(
            UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f000"), "1.0.0");
    private static final ResourcePack.Key WRONG_KEY = new ResourcePack.Key(
            UUID.fromString("f0e0d0c0-b0a0-9080-7060-504030201000"), "1.0.0");

    private final List<ResourcePackWorkScheduler> schedulers = new ArrayList<>();

    @AfterEach
    void shutdownSchedulers() {
        this.schedulers.forEach(ResourcePackWorkScheduler::shutdown);
    }

    @Test
    void concurrentSourceRequestsDownloadAndExpandOnlyOnce(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "shared");
        final AtomicInteger loaderCalls = new AtomicInteger();
        final CountDownLatch loaderStarted = new CountDownLatch(1);
        final CountDownLatch releaseLoader = new CountDownLatch(1);
        final ResourcePackArchiveStore.ArchiveLoader loader = () -> {
            loaderCalls.incrementAndGet();
            loaderStarted.countDown();
            if (!releaseLoader.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Timed out waiting to release test loader");
            }
            return archive;
        };

        final List<CompletableFuture<ResourcePack>> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(fixture.store().loadFromSource(
                    "https://packs.example/shared.mcpack", PACK_KEY, new byte[0], "", loader));
        }

        try {
            assertTrue(loaderStarted.await(10, TimeUnit.SECONDS));
            assertEquals(1, loaderCalls.get());
        } finally {
            releaseLoader.countDown();
        }

        final List<ResourcePack> packs = new ArrayList<>();
        for (CompletableFuture<ResourcePack> request : requests) {
            packs.add(request.get(10, TimeUnit.SECONDS));
        }
        for (ResourcePack pack : packs) {
            assertSame(packs.getFirst(), pack);
        }
        assertInstanceOf(ZipFileContent.class, packs.getFirst().content());
        assertEquals(1L, fixture.metrics().getArchiveMisses());
        assertEquals(1L, fixture.metrics().getArchiveBuilds());
        assertEquals(1L, fixture.metrics().getArchiveBuildTimeSamples());
        assertEquals(19L, fixture.metrics().getArchiveWaiters());
        assertArchiveInflightEventually(fixture.metrics(), 0L);
        assertEquals(1L, fixture.metrics().getContentMisses());
        assertEquals(1L, fixture.metrics().getContentBuilds());
        assertEquals(1L, fixture.metrics().getContentBuildTimeSamples());
        assertEquals(0L, fixture.metrics().getContentWaiters(),
                "The source flight collapses callers before the content expansion flight");
        assertEquals(0L, fixture.metrics().getContentInflight());
        assertEquals(0L, fixture.metrics().getBlobBuilds());
        assertEquals(0L, fixture.metrics().getBlobWaiters());
        assertEquals(0, fixture.metrics().getLayerBuilds());
    }

    @Test
    void completedSourceFlightIsRetiredBeforeCompletionCallbacks(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "completion-boundary");
        final AtomicInteger loaderCalls = new AtomicInteger();
        final CountDownLatch loaderStarted = new CountDownLatch(1);
        final CountDownLatch releaseLoader = new CountDownLatch(1);
        final ResourcePackArchiveStore.ArchiveLoader loader = () -> {
            if (loaderCalls.incrementAndGet() == 1) {
                loaderStarted.countDown();
                if (!releaseLoader.await(10L, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release completion-boundary loader");
                }
            }
            return archive;
        };
        final String source = "https://packs.example/completion-boundary.mcpack";
        final CompletableFuture<ResourcePack> first = fixture.store().loadFromSource(
                source, PACK_KEY, new byte[0], "", loader);
        assertTrue(loaderStarted.await(10L, TimeUnit.SECONDS));

        final CompletableFuture<ResourcePack> reentrant = first.thenCompose(firstPack ->
                fixture.store().loadFromSource(source, PACK_KEY, new byte[0], "", loader)
                        .thenApply(secondPack -> {
                            assertSame(firstPack, secondPack);
                            return secondPack;
                        }));
        releaseLoader.countDown();

        reentrant.get(10L, TimeUnit.SECONDS);
        assertEquals(2, loaderCalls.get(),
                "A completion callback must not join a source flight that has already completed");
        assertArchiveInflightEventually(fixture.metrics(), 0L);
    }

    @Test
    void twentyRawClaimsShareOnePublication(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 1);
        final byte[] archive = plainArchive(PACK_KEY, "shared-raw-claim");
        final byte[] digest = sha256(archive);
        final List<ResourcePackArchiveStore.Claim> claims = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            claims.add(fixture.store().claim(digest));
        }

        assertTrue(claims.getFirst().leader());
        assertTrue(claims.subList(1, claims.size()).stream().noneMatch(ResourcePackArchiveStore.Claim::leader));
        assertTrue(claims.stream().noneMatch(claim -> claim.path().isDone()));
        assertEquals(1L, fixture.metrics().getArchiveMisses());
        assertEquals(19L, fixture.metrics().getArchiveWaiters());
        assertEquals(1L, fixture.metrics().getArchiveInflight());
        assertEquals(0L, fixture.metrics().getArchiveBuilds());

        final Path published = fixture.store().publish(claims.getFirst(), archive);
        for (ResourcePackArchiveStore.Claim claim : claims) {
            assertEquals(published, claim.path().get(10L, TimeUnit.SECONDS));
        }
        assertEquals(20L, fixture.store().activeRawLeaseCount());
        assertEquals(1L, fixture.metrics().getArchiveBuilds());
        assertEquals(1L, fixture.metrics().getArchiveBuildTimeSamples());
        assertArchiveInflightEventually(fixture.metrics(), 0L);
        fixture.clock().advanceDays(2L);
        Files.setLastModifiedTime(published, FileTime.fromMillis(
                fixture.clock().read() - TimeUnit.DAYS.toMillis(2L)));
        fixture.store().cleanupNow();
        assertTrue(Files.isRegularFile(published),
                "Publication bridge leases must replace rawInflight protection before it is removed");

        final List<CompletableFuture<ResourcePack>> effectiveLoads = claims.stream()
                .map(claim -> fixture.store().loadEffective(claim, PACK_KEY, new byte[0], ""))
                .toList();
        for (CompletableFuture<ResourcePack> effectiveLoad : effectiveLoads) {
            effectiveLoad.get(10L, TimeUnit.SECONDS);
        }
        assertEquals(1L, fixture.metrics().getContentBuilds());
        assertEquals(0L, fixture.store().activeRawLeaseCount());

        try (ResourcePackArchiveStore.Claim hot = fixture.store().claim(digest)) {
            assertFalse(hot.leader());
            assertEquals(published, hot.path().get(10L, TimeUnit.SECONDS));
            assertEquals(1L, fixture.store().activeRawLeaseCount());
            fixture.store().loadEffective(hot, PACK_KEY, new byte[0], "")
                    .get(10L, TimeUnit.SECONDS);
        }
        assertEquals(0L, fixture.store().activeRawLeaseCount());
        assertEquals(1L, fixture.metrics().getArchiveHits());
        assertEquals(1L, fixture.metrics().getArchiveBuilds());
        assertEquals(1L, fixture.metrics().getArchiveBuildTimeSamples());
    }

    @Test
    void archiveInflightMetricIncludesRawAndSourceFlights(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] rawArchive = plainArchive(PACK_KEY, "raw-inflight");
        final ResourcePackArchiveStore.Claim rawClaim = fixture.store().claim(sha256(rawArchive));
        final byte[] sourceArchive = plainArchive(PACK_KEY, "source-inflight");
        final CountDownLatch sourceStarted = new CountDownLatch(1);
        final CountDownLatch releaseSource = new CountDownLatch(1);

        final CompletableFuture<ResourcePack> source = fixture.store().loadFromSource(
                "https://packs.example/inflight.mcpack", PACK_KEY, new byte[0], "", () -> {
                    sourceStarted.countDown();
                    if (!releaseSource.await(10L, TimeUnit.SECONDS)) {
                        throw new IOException("Timed out waiting to release source flight");
                    }
                    return sourceArchive;
                });
        assertTrue(sourceStarted.await(10L, TimeUnit.SECONDS));
        assertEquals(2L, fixture.metrics().getArchiveInflight(),
                "The JMX gauge must include one raw flight and one source flight");

        releaseSource.countDown();
        source.get(10L, TimeUnit.SECONDS);
        assertArchiveInflightEventually(fixture.metrics(), 1L);
        fixture.store().publish(rawClaim, rawArchive);
        rawClaim.close();
        assertArchiveInflightEventually(fixture.metrics(), 0L);
    }

    @Test
    void concurrentEffectiveLoadsJoinOneContentExpansion(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final PublishedArchive published = publish(
                fixture.store(), plainArchive(PACK_KEY, "shared-effective-content"));
        final CountDownLatch workersStarted = new CountDownLatch(2);
        final CountDownLatch releaseWorkers = new CountDownLatch(1);
        for (int i = 0; i < 2; i++) {
            fixture.scheduler().runCpu(() -> {
                workersStarted.countDown();
                await(releaseWorkers);
            });
        }
        assertTrue(workersStarted.await(10L, TimeUnit.SECONDS));

        final List<CompletableFuture<ResourcePack>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                requests.add(fixture.store().loadEffective(
                        published.path(), published.digest(), PACK_KEY, new byte[0], ""));
            }
            assertEquals(1L, fixture.metrics().getContentMisses());
            assertEquals(19L, fixture.metrics().getContentWaiters());
            assertEquals(1L, fixture.metrics().getContentInflight());
        } finally {
            releaseWorkers.countDown();
        }

        final List<ResourcePack> packs = new ArrayList<>();
        for (CompletableFuture<ResourcePack> request : requests) {
            packs.add(request.get(10L, TimeUnit.SECONDS));
        }
        packs.forEach(pack -> assertSame(packs.getFirst(), pack));
        assertEquals(1L, fixture.metrics().getContentBuilds());
        assertEquals(1L, fixture.metrics().getContentBuildTimeSamples());
        assertEquals(0L, fixture.metrics().getContentInflight());
    }

    @Test
    void pathAndInputStreamPublishConsumeOwnedTemps(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] pathArchive = plainArchive(PACK_KEY, "path-publish");
        final ResourcePackArchiveStore.Claim pathClaim = fixture.store().claim(sha256(pathArchive));
        final Path completedTemp = fixture.store().createRawTemp(pathClaim);
        Files.write(completedTemp, pathArchive);

        final Path published = fixture.store().publish(pathClaim, completedTemp);

        assertFalse(Files.exists(completedTemp));
        assertArrayEquals(pathArchive, Files.readAllBytes(published));
        pathClaim.close();

        final byte[] streamArchive = plainArchive(PACK_KEY, "stream-publish");
        final ResourcePackArchiveStore.Claim streamClaim = fixture.store().claim(sha256(streamArchive));
        final Path streamPublished = fixture.store().publish(
                streamClaim, new ByteArrayInputStream(streamArchive));
        assertArrayEquals(streamArchive, Files.readAllBytes(streamPublished));
        streamClaim.close();
    }

    @Test
    void byteArrayAndInputStreamPublishEnforceArchiveLimitBeforeAndDuringWrite(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 1, 4, 1, 100, 200);
        final byte[] oversizedBytes = new byte[1024 * 1024 + 1];
        oversizedBytes[oversizedBytes.length - 1] = 1;
        final ResourcePackArchiveStore.Claim byteClaim = fixture.store().claim(sha256(oversizedBytes));

        final IOException byteFailure = assertThrows(
                IOException.class, () -> fixture.store().publish(byteClaim, oversizedBytes));
        assertTrue(byteFailure.getMessage().contains("configured size limit"));
        assertFutureFailure(byteClaim.path(), IOException.class);

        final byte[] oversizedStreamBytes = new byte[2 * 1024 * 1024];
        oversizedStreamBytes[0] = 2;
        final ByteArrayInputStream oversizedStream = new ByteArrayInputStream(oversizedStreamBytes);
        final ResourcePackArchiveStore.Claim streamClaim = fixture.store().claim(sha256(oversizedStreamBytes));

        final IOException streamFailure = assertThrows(
                IOException.class, () -> fixture.store().publish(streamClaim, oversizedStream));
        assertTrue(streamFailure.getMessage().contains("configured size limit"));
        assertTrue(oversizedStream.available() > 0,
                "The bounded publisher must stop consuming the stream once the limit is crossed");
        assertFutureFailure(streamClaim.path(), IOException.class);
        assertEquals(2L, fixture.metrics().getArchiveFailures());
        assertArchiveInflightEventually(fixture.metrics(), 0L);
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/raw/sha256")));
        assertNoRawTemps(tempDir);
    }

    @Test
    void matchingLegacyArchiveIsCopiedAndAtomicallyImported(@TempDir final Path tempDir) throws Exception {
        final byte[] archive = plainArchive(PACK_KEY, "legacy-exact");
        final Path legacy = tempDir.resolve(PACK_KEY + ".mcpack");
        Files.write(legacy, archive);
        final Fixture fixture = this.fixture(tempDir);
        final ResourcePackArchiveStore.Claim claim = fixture.store().claim(sha256(archive));

        assertTrue(fixture.store().tryImportLegacy(claim, PACK_KEY).get(10, TimeUnit.SECONDS));
        final Path imported = claim.path().get(10, TimeUnit.SECONDS);

        assertTrue(Files.isRegularFile(legacy));
        assertArrayEquals(archive, Files.readAllBytes(legacy));
        assertArrayEquals(archive, Files.readAllBytes(imported));
        assertTrue(imported.startsWith(tempDir.resolve("v2/raw/sha256")));
        claim.close();
    }

    @Test
    void mismatchingLegacyArchiveFallsBackWithoutFailureBackoff(@TempDir final Path tempDir) throws Exception {
        final byte[] expected = plainArchive(PACK_KEY, "expected");
        final byte[] stale = plainArchive(PACK_KEY, "stale");
        final Path legacy = tempDir.resolve(PACK_KEY + ".mcpack");
        Files.write(legacy, stale);
        final Fixture fixture = this.fixture(tempDir);
        final ResourcePackArchiveStore.Claim claim = fixture.store().claim(sha256(expected));

        assertFalse(fixture.store().tryImportLegacy(claim, PACK_KEY).get(10, TimeUnit.SECONDS));
        assertFalse(claim.path().isDone());
        assertEquals(0L, fixture.metrics().getArchiveFailures());

        final Path downloaded = fixture.store().publish(claim, expected);
        assertArrayEquals(expected, Files.readAllBytes(downloaded));
        assertArrayEquals(stale, Files.readAllBytes(legacy));
        claim.close();
    }

    @Test
    void abandonedLegacyLeaderDoesNotPoisonRetryBackoff(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "abandoned");
        final byte[] hash = sha256(archive);
        final ResourcePackArchiveStore.Claim abandoned = fixture.store().claim(hash);

        fixture.store().abandon(abandoned, new IOException("connection closed"));

        assertFutureFailure(abandoned.path(), IOException.class);
        final ResourcePackArchiveStore.Claim retry = fixture.store().claim(hash);
        assertTrue(retry.leader());
        fixture.store().publish(retry, archive);
        retry.close();
        assertEquals(0L, fixture.metrics().getArchiveFailures());
    }

    @Test
    void abandonedLeaderPromotesOldestFollowerWithoutFailingSharedFlight(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "promoted-follower");
        final byte[] hash = sha256(archive);
        final ResourcePackArchiveStore.Claim leader = fixture.store().claim(hash);
        final ResourcePackArchiveStore.Claim promoted = fixture.store().claim(hash);
        final ResourcePackArchiveStore.Claim waiter = fixture.store().claim(hash);
        final Path staleLeaderTemp = fixture.store().createRawTemp(leader);
        Files.write(staleLeaderTemp, archive);

        fixture.store().abandon(leader, new IOException("leader connection closed"));

        assertFutureFailure(leader.path(), IOException.class);
        assertTrue(promoted.leadership().get(10, TimeUnit.SECONDS));
        assertTrue(promoted.leader());
        assertFalse(waiter.leadership().isDone());
        assertThrows(IllegalArgumentException.class, () -> fixture.store().publish(leader, staleLeaderTemp));
        assertFalse(Files.exists(staleLeaderTemp));
        fixture.store().fail(leader, new IOException("late stale leader failure"));
        assertFalse(waiter.path().isDone());

        final Path published = fixture.store().publish(promoted, archive);

        assertEquals(published, promoted.path().get(10, TimeUnit.SECONDS));
        assertEquals(published, waiter.path().get(10, TimeUnit.SECONDS));
        assertFalse(waiter.leadership().get(10, TimeUnit.SECONDS));
        assertEquals(0L, fixture.metrics().getArchiveFailures());
        assertEquals(1L, fixture.metrics().getArchiveBuilds());
        promoted.close();
        waiter.close();
    }

    @Test
    void disconnectedFollowerIsWithdrawnBeforeLeadershipHandoff(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "surviving-follower");
        final byte[] hash = sha256(archive);
        final ResourcePackArchiveStore.Claim leader = fixture.store().claim(hash);
        final ResourcePackArchiveStore.Claim disconnected = fixture.store().claim(hash);
        final ResourcePackArchiveStore.Claim survivor = fixture.store().claim(hash);

        fixture.store().abandon(disconnected, new IOException("follower connection closed"));
        fixture.store().abandon(leader, new IOException("leader connection closed"));

        assertFutureFailure(disconnected.path(), IOException.class);
        assertFalse(disconnected.leadership().get(10, TimeUnit.SECONDS));
        assertTrue(survivor.leadership().get(10, TimeUnit.SECONDS));
        assertTrue(survivor.leader());
        fixture.store().publish(survivor, archive);
        survivor.close();
        assertEquals(0L, fixture.metrics().getArchiveFailures());
    }

    @Test
    void pathHashMismatchDeletesTempAndBacksOffRetry(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] expectedArchive = plainArchive(PACK_KEY, "path-expected");
        final byte[] wrongArchive = plainArchive(PACK_KEY, "path-wrong");
        final byte[] expectedHash = sha256(expectedArchive);
        final ResourcePackArchiveStore.Claim claim = fixture.store().claim(expectedHash);
        final Path completedTemp = fixture.store().createRawTemp(claim);
        Files.write(completedTemp, wrongArchive);

        assertThrows(IllegalStateException.class, () -> fixture.store().publish(claim, completedTemp));
        assertFalse(Files.exists(completedTemp));

        final ResourcePackArchiveStore.Claim backedOff = fixture.store().claim(expectedHash);
        assertFalse(backedOff.leader());
        assertFutureFailure(backedOff.path(), IllegalStateException.class);
        assertEquals(1L, fixture.metrics().getArchiveBuildTimeSamples());

        fixture.advanceBackoff();
        final ResourcePackArchiveStore.Claim retry = fixture.store().claim(expectedHash);
        assertTrue(retry.leader());
        fixture.store().publish(retry, expectedArchive);
        retry.close();
        assertEquals(2L, fixture.metrics().getArchiveBuildTimeSamples());
    }

    @Test
    void asyncPublishDeletesTempAndFailsClaimWhenIoSchedulerRejects(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "async-rejected");
        final ResourcePackArchiveStore.Claim claim = fixture.store().claim(sha256(archive));
        final Path completedTemp = fixture.store().createRawTemp(claim);
        Files.write(completedTemp, archive);
        this.schedulers.getLast().shutdown();

        assertFutureFailure(
                fixture.store().publishAsync(claim, completedTemp), RejectedExecutionException.class);

        assertFalse(Files.exists(completedTemp));
        assertFutureFailure(claim.path(), RejectedExecutionException.class);
    }

    @Test
    void pathSourceLoaderStreamsIntoCasAndCleansTemp(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "path-source");
        final AtomicReference<Path> sourceTemp = new AtomicReference<>();

        final ResourcePack pack = fixture.store().loadFromSource(
                "https://packs.example/path.mcpack", PACK_KEY, archive.length, new byte[0], "", target -> {
                    sourceTemp.set(target);
                    try (var output = Files.newOutputStream(target)) {
                        output.write(archive, 0, archive.length / 2);
                        output.write(archive, archive.length / 2, archive.length - archive.length / 2);
                    }
                }).get(10, TimeUnit.SECONDS);

        assertEquals(PACK_KEY, pack.key());
        assertInstanceOf(ZipFileContent.class, pack.content());
        assertNotNull(((ZipFileContent) pack.content()).contentDigest());
        assertFalse(Files.exists(sourceTemp.get()));
    }

    @Test
    void streamSourceHashesExactTransferredBytesWhileWritingCas(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "stream-source");
        final ArchiveDigest digest = ArchiveDigest.compute(archive);
        final PackAlias alias = PackAlias.from("", PACK_KEY, archive.length, "", new byte[0]);
        final AtomicInteger loaderCalls = new AtomicInteger();

        final ResourcePack pack = fixture.store().loadFromStreamSource(
                "https://packs.example/stream.mcpack", alias, "", false, new byte[0], output -> {
                    loaderCalls.incrementAndGet();
                    output.write(archive, 0, archive.length / 2);
                    output.write(archive, archive.length / 2, archive.length - archive.length / 2);
                }).get(10, TimeUnit.SECONDS);

        final Path raw = casFile(tempDir, digest.hex(), ".mcpack");
        assertEquals(1, loaderCalls.get());
        assertArrayEquals(archive, Files.readAllBytes(raw));
        assertEquals(digest, ArchiveDigest.compute(Files.readAllBytes(raw)));
        assertEquals(PACK_KEY, pack.key());
        assertEquals(1L, fixture.metrics().getArchiveBuilds());
        assertEquals(1L, fixture.metrics().getContentBuilds());
    }

    @Test
    void streamSourceHandsClaimToEffectiveLoadOnIoWorker(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "io-handoff");
        final PackAlias alias = PackAlias.from("", PACK_KEY, archive.length, "", new byte[0]);
        final AtomicReference<String> handoffThread = new AtomicReference<>();
        fixture.store().sourceHandoffHook(() -> handoffThread.set(Thread.currentThread().getName()));

        fixture.store().loadFromStreamSource(
                "https://packs.example/io-handoff.mcpack", alias, "", false, new byte[0],
                output -> output.write(archive)).get(10L, TimeUnit.SECONDS);

        assertNotNull(handoffThread.get());
        assertTrue(handoffThread.get().startsWith("ViaBedrock Pack IO #"));
    }

    @Test
    void synchronousSourceHandoffFailureReleasesPublishedRawLease(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "handoff-failure");
        final PackAlias alias = PackAlias.from("", PACK_KEY, archive.length, "", new byte[0]);
        fixture.store().sourceHandoffHook(() -> {
            throw new IllegalStateException("test handoff failure");
        });

        final IllegalStateException failure = assertFutureFailure(fixture.store().loadFromStreamSource(
                "https://packs.example/handoff-failure.mcpack", alias, "", false, new byte[0],
                output -> output.write(archive)), IllegalStateException.class);

        assertTrue(failure.getMessage().contains("test handoff failure"));
        assertEquals(1L, fixture.metrics().getArchiveBuilds());
        assertEquals(0L, fixture.metrics().getArchiveFailures());
        assertArchiveInflightEventually(fixture.metrics(), 0L);
        assertEquals(0L, fixture.store().activeRawLeaseCount());
        assertEquals(0L, fixture.metrics().getContentBuilds());
    }

    @Test
    void synchronousFollowerHandoffFailureImmediatelyWithdrawsRawWaiter(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "follower-handoff-failure");
        final byte[] hash = sha256(archive);
        final ResourcePackArchiveStore.Claim leader = fixture.store().claim(hash);
        final PackAlias alias = PackAlias.from("", PACK_KEY, archive.length, "", new byte[0]);
        fixture.store().sourceHandoffHook(() -> {
            throw new IllegalStateException("test follower handoff failure");
        });

        assertFutureFailure(fixture.store().loadFromStreamSource(
                "https://packs.example/follower-handoff-failure.mcpack", alias, "", false, new byte[0],
                output -> output.write(archive)), IllegalStateException.class);

        assertEquals(0, fixture.store().rawWaiterCount());
        assertArchiveInflightEventually(fixture.metrics(), 1L);
        assertEquals(0L, fixture.metrics().getArchiveFailures());
        fixture.store().publish(leader, archive);
        leader.close();
        assertArchiveInflightEventually(fixture.metrics(), 0L);
    }

    @Test
    void sourceLoadersValidateAnnouncedAndMaximumSizeBeforeRawPublication(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 1, 4, 1, 100, 200);
        final byte[] archive = plainArchive(PACK_KEY, "source-size");

        final IOException byteLoaderFailure = assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/wrong-byte-size.mcpack", PACK_KEY, archive.length + 1L,
                new byte[0], "", () -> archive), IOException.class);
        final IOException pathLoaderFailure = assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/wrong-path-size.mcpack", PACK_KEY, archive.length + 1L,
                new byte[0], "", target -> Files.write(target, archive)), IOException.class);
        final IOException oversizedFailure = assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/oversized.mcpack", PACK_KEY, new byte[0], "",
                () -> new byte[1024 * 1024 + 1]), IOException.class);

        assertTrue(byteLoaderFailure.getMessage().contains("does not match announced size"));
        assertTrue(pathLoaderFailure.getMessage().contains("does not match announced size"));
        assertTrue(oversizedFailure.getMessage().contains("configured size limit"));
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/raw/sha256")));
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/content/sha256")));
        assertEquals(0L, fixture.store().aliasHistorySize());
        assertEquals(0L, fixture.store().trustedAliasHistorySize());
        assertEquals(3L, fixture.metrics().getArchiveFailures());
        assertEquals(0L, fixture.metrics().getContentFailures());
        assertEquals(0L, fixture.metrics().getBlobFailures());
    }

    @Test
    void streamSourceStopsAtStoreArchiveLimitWhileWriting(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 1, 4, 1, 100, 200);
        final byte[] oversized = new byte[1024 * 1024 + 1];
        final PackAlias alias = PackAlias.from("", PACK_KEY, -1L, "", new byte[0]);
        final AtomicBoolean writeReturned = new AtomicBoolean();

        final IOException failure = assertFutureFailure(fixture.store().loadFromStreamSource(
                "https://packs.example/stream-oversized.mcpack", alias, "", false, new byte[0], output -> {
                    output.write(oversized);
                    writeReturned.set(true);
                }), IOException.class);

        assertTrue(failure.getMessage().contains("configured size limit"));
        assertFalse(writeReturned.get(), "The bounded store stream must reject the write immediately");
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/raw/sha256")));
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/content/sha256")));
        assertEquals(0L, fixture.store().aliasHistorySize());
        assertEquals(0L, fixture.store().trustedAliasHistorySize());
        assertEquals(1L, fixture.metrics().getArchiveFailures());
        assertNoRawTemps(tempDir);
    }

    @Test
    void pathSourcePreflightsKnownSizeAndDeletesUnknownOversizedOutput(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 1, 4, 1, 100, 200);
        final AtomicInteger knownSizeLoaderCalls = new AtomicInteger();
        final IOException knownSizeFailure = assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/path-known-oversized.mcpack", PACK_KEY, 1024L * 1024L + 1L,
                new byte[0], "", target -> {
                    knownSizeLoaderCalls.incrementAndGet();
                    Files.write(target, new byte[0]);
                }), IOException.class);

        assertTrue(knownSizeFailure.getMessage().contains("configured size limit"));
        assertEquals(0, knownSizeLoaderCalls.get(),
                "An oversized announced path source must be rejected before invoking its loader");

        final AtomicReference<Path> unknownSizeTarget = new AtomicReference<>();
        final IOException unknownSizeFailure = assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/path-unknown-oversized.mcpack", PACK_KEY, new byte[0], "", target -> {
                    unknownSizeTarget.set(target);
                    Files.write(target, new byte[1024 * 1024 + 1]);
                }), IOException.class);

        assertTrue(unknownSizeFailure.getMessage().contains("configured size limit"));
        assertNotNull(unknownSizeTarget.get());
        assertFalse(Files.exists(unknownSizeTarget.get()),
                "Compatibility path output must be deleted immediately after post-write validation fails");
        assertEquals(2L, fixture.metrics().getArchiveFailures());
        assertArchiveInflightEventually(fixture.metrics(), 0L);
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/raw/sha256")));
        assertNoRawTemps(tempDir);
    }

    @Test
    void failedPathSourceLoaderDeletesTempAndCanRetry(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "path-retry");
        final AtomicReference<Path> failedTemp = new AtomicReference<>();
        final AtomicInteger attempts = new AtomicInteger();
        final ResourcePackArchiveStore.ArchivePathLoader loader = target -> {
            if (attempts.incrementAndGet() == 1) {
                failedTemp.set(target);
                Files.write(target, bytes("partial"));
                throw new IOException("temporary path source failure");
            }
            Files.write(target, archive);
        };

        assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/path-retry.mcpack", PACK_KEY, new byte[0], "", loader), IOException.class);
        assertFalse(Files.exists(failedTemp.get()));
        assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/path-retry.mcpack", PACK_KEY, new byte[0], "", loader), IOException.class);
        assertEquals(1, attempts.get());

        fixture.advanceBackoff();
        final ResourcePack pack = fixture.store().loadFromSource(
                "https://packs.example/path-retry.mcpack", PACK_KEY, new byte[0], "", loader)
                .get(10, TimeUnit.SECONDS);
        assertEquals(PACK_KEY, pack.key());
        assertEquals(2, attempts.get());
    }

    @Test
    void failedLeaderPublicationRecordsOneFailureAndReleasesClaim(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "publication-failure");
        final ArchiveDigest digest = ArchiveDigest.compute(archive);
        final Path target = casFile(tempDir, digest.hex(), ".mcpack");
        Files.createDirectories(target);
        Files.write(target.resolve("blocker"), bytes("blocker"));
        final PackAlias alias = PackAlias.from("", PACK_KEY, archive.length, "", new byte[0]);

        assertFutureFailure(fixture.store().loadFromStreamSource(
                "https://packs.example/publication-failure.mcpack", alias, "", false, new byte[0],
                output -> output.write(archive)), IOException.class);

        assertEquals(1L, fixture.metrics().getArchiveFailures());
        assertArchiveInflightEventually(fixture.metrics(), 0L);
        assertEquals(0L, fixture.store().activeRawLeaseCount());
        assertEquals(0L, fixture.store().aliasHistorySize());
        assertEquals(0L, fixture.store().trustedAliasHistorySize());
        assertNoRawTemps(tempDir);
    }

    @Test
    void conflictingSourceSizeContentIdAndKeyDoNotShareSourceFlight(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final CountDownLatch allLoadersStarted = new CountDownLatch(5);
        final CountDownLatch releaseLoaders = new CountDownLatch(1);
        final AtomicInteger loaderCalls = new AtomicInteger();
        final byte[] keyA = bytes("0123456789abcdef0123456789abcdef");
        final byte[] keyB = bytes("fedcba9876543210fedcba9876543210");
        final byte[] archive = plainArchive(PACK_KEY, "scoped-source");
        final byte[] differentSizeArchive = Arrays.copyOf(archive, archive.length + 1);

        final List<SourceRequest> variants = List.of(
                new SourceRequest("https://backend-a.example/pack", archive.length, "content-a", keyA, archive),
                new SourceRequest("https://backend-b.example/pack", archive.length, "content-a", keyA, archive),
                new SourceRequest("https://backend-a.example/pack", differentSizeArchive.length,
                        "content-a", keyA, differentSizeArchive),
                new SourceRequest("https://backend-a.example/pack", archive.length, "content-b", keyA, archive),
                new SourceRequest("https://backend-a.example/pack", archive.length, "content-a", keyB, archive));
        final List<CompletableFuture<ResourcePack>> requests = new ArrayList<>();
        for (SourceRequest variant : variants) {
            requests.add(fixture.store().loadFromSource(
                    variant.source(), PACK_KEY, variant.announcedSize(), variant.contentKey(), variant.contentId(), () -> {
                        loaderCalls.incrementAndGet();
                        allLoadersStarted.countDown();
                        if (!releaseLoaders.await(10, TimeUnit.SECONDS)) {
                            throw new IOException("Timed out waiting to release scoped source loaders");
                        }
                        return variant.archive();
                    }));
        }

        try {
            assertTrue(allLoadersStarted.await(10, TimeUnit.SECONDS));
            assertEquals(5, loaderCalls.get());
        } finally {
            releaseLoaders.countDown();
        }
        for (CompletableFuture<ResourcePack> request : requests) {
            final IllegalStateException failure = assertFutureFailure(request, IllegalStateException.class);
            assertTrue(failure.getMessage().contains("Missing contents.json"));
        }
    }

    @Test
    void exactTrustedAliasReusesVerifiedCanonicalContent(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "trusted");
        final byte[] contentKey = new byte[0];
        final PackAlias alias = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, archive.length, "content-a", contentKey);
        final String sequence = "a".repeat(64);
        final AtomicInteger loaderCalls = new AtomicInteger();

        fixture.store().loadFromSource("https://packs.example/trusted.mcpack", alias, sequence, true,
                contentKey, () -> {
                    loaderCalls.incrementAndGet();
                    return archive;
                }).get(10, TimeUnit.SECONDS);
        final ResourcePack reused = fixture.store().loadFromSource(
                "https://packs.example/trusted.mcpack", alias, sequence, true, contentKey, () -> {
                    loaderCalls.incrementAndGet();
                    throw new IOException("trusted hit must not reacquire the source");
                }).get(10, TimeUnit.SECONDS);

        assertEquals(1, loaderCalls.get());
        assertArrayEquals(bytes("trusted"), reused.content().get("value.txt"));
        assertEquals(1L, fixture.metrics().getArchiveBuilds());
        assertEquals(1L, fixture.metrics().getContentBuilds());
        assertEquals(1L, fixture.metrics().getContentHits());
        assertEquals(0L, fixture.metrics().getBlobBuilds());
        assertFalse(Files.exists(fixture.store().trustedAliasConflictPath(alias, sequence)));
    }

    @Test
    void overlappingTrustedLookupsReferenceCountCanonicalProtection(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "trusted-overlap");
        final byte[] contentKey = new byte[0];
        final PackAlias alias = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, archive.length, "content-a", contentKey);
        final String sequence = "f".repeat(64);
        final ResourcePack seeded = fixture.store().loadFromSource(
                "https://packs.example/overlap-seed.mcpack", alias, sequence, false, contentKey,
                () -> archive).get(10L, TimeUnit.SECONDS);
        final Path canonical = ((ZipFileContent) seeded.content()).path();
        final CountDownLatch bothLookupsActive = new CountDownLatch(2);
        final CountDownLatch allowOneLookup = new CountDownLatch(1);
        final CountDownLatch allowRemainingLookup = new CountDownLatch(1);
        final AtomicBoolean oneLookupPassed = new AtomicBoolean();
        final AtomicInteger fallbackCalls = new AtomicInteger();
        fixture.store().trustedLookupBeforeCommitHook(() -> {
            bothLookupsActive.countDown();
            await(allowOneLookup);
            if (!oneLookupPassed.compareAndSet(false, true)) {
                await(allowRemainingLookup);
            }
        });
        final ResourcePackArchiveStore.ArchiveLoader fallback = () -> {
            fallbackCalls.incrementAndGet();
            return archive;
        };
        final CompletableFuture<ResourcePack> first = fixture.store().loadFromSource(
                "https://packs.example/overlap-first.mcpack", alias, sequence, true, contentKey, fallback);
        final CompletableFuture<ResourcePack> second = fixture.store().loadFromSource(
                "https://packs.example/overlap-second.mcpack", alias, sequence, true, contentKey, fallback);

        try {
            assertTrue(bothLookupsActive.await(10L, TimeUnit.SECONDS));
            assertEquals(2, fixture.store().activePathReferenceCount(canonical));
            allowOneLookup.countDown();
            CompletableFuture.anyOf(first, second).get(10L, TimeUnit.SECONDS);
            assertEquals(1, fixture.store().activePathReferenceCount(canonical));
        } finally {
            allowOneLookup.countDown();
            allowRemainingLookup.countDown();
        }
        CompletableFuture.allOf(first, second).get(10L, TimeUnit.SECONDS);

        assertEquals(0, fixture.store().activePathReferenceCount(canonical));
        assertEquals(0, fallbackCalls.get());
    }

    @Test
    void inflightTrustedLookupRejectsHistoryThatBecomesConflictingBeforeReturn(
            @TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] firstArchive = plainArchive(PACK_KEY, "first");
        final byte[] secondArchive = plainArchive(PACK_KEY, "other");
        final byte[] fallbackArchive = plainArchive(PACK_KEY, "third");
        assertEquals(firstArchive.length, secondArchive.length);
        assertEquals(firstArchive.length, fallbackArchive.length);
        final byte[] contentKey = new byte[0];
        final PackAlias alias = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, firstArchive.length, "content-a", contentKey);
        final String sequence = "9".repeat(64);
        fixture.store().loadFromSource("https://packs.example/history-first.mcpack",
                alias, sequence, false, contentKey, () -> firstArchive).get(10L, TimeUnit.SECONDS);

        final CountDownLatch lookupValidated = new CountDownLatch(1);
        final CountDownLatch releaseLookup = new CountDownLatch(1);
        final CountDownLatch conflictObserved = new CountDownLatch(1);
        final CountDownLatch releaseConflictPersist = new CountDownLatch(1);
        final CountDownLatch fallbackLoaderCalled = new CountDownLatch(1);
        final AtomicBoolean firstPersist = new AtomicBoolean();
        fixture.store().trustedLookupBeforeCommitHook(() -> {
            lookupValidated.countDown();
            await(releaseLookup);
        });
        fixture.store().trustedConflictBeforePersistHook(() -> {
            if (firstPersist.compareAndSet(false, true)) {
                conflictObserved.countDown();
                await(releaseConflictPersist);
            }
        });

        final CompletableFuture<ResourcePack> trustedLookup = fixture.store().loadFromSource(
                "https://packs.example/history-trusted.mcpack",
                alias, sequence, true, contentKey, () -> {
                    fallbackLoaderCalled.countDown();
                    return fallbackArchive;
                });
        assertTrue(lookupValidated.await(10L, TimeUnit.SECONDS));
        final CompletableFuture<ResourcePack> conflict = fixture.store().loadFromSource(
                "https://packs.example/history-conflict.mcpack",
                alias, sequence, false, contentKey, () -> secondArchive);
        assertTrue(conflictObserved.await(10L, TimeUnit.SECONDS));

        try {
            releaseLookup.countDown();
            assertTrue(fallbackLoaderCalled.await(10L, TimeUnit.SECONDS),
                    "The stale trusted candidate must fall back before its tombstone is durable");
        } finally {
            releaseConflictPersist.countDown();
        }

        assertArrayEquals(bytes("other"), conflict.get(10L, TimeUnit.SECONDS).content().get("value.txt"));
        assertArrayEquals(bytes("third"), trustedLookup.get(10L, TimeUnit.SECONDS).content().get("value.txt"));
    }

    @Test
    void strictModeAlwaysAcquiresSourceEvenWithAnExactObservation(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "strict");
        final byte[] contentKey = new byte[0];
        final PackAlias alias = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, archive.length, "content-a", contentKey);
        final String sequence = "b".repeat(64);
        final AtomicInteger loaderCalls = new AtomicInteger();

        fixture.store().loadFromSource("https://packs.example/strict.mcpack", alias, sequence, true,
                contentKey, () -> archive).get(10, TimeUnit.SECONDS);
        fixture.store().loadFromSource("https://packs.example/strict.mcpack", alias, sequence, false,
                contentKey, () -> {
                    loaderCalls.incrementAndGet();
                    return archive;
                }).get(10, TimeUnit.SECONDS);

        assertEquals(1, loaderCalls.get());
    }

    @Test
    void trustedAliasRequiresExactBackendDeclarationAndAnnouncementSequence(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "variants");
        final byte[] keyA = new byte[0];
        final byte[] keyB = bytes("0123456789abcdef0123456789abcdef");
        final String baselineSequence = "c".repeat(64);
        final PackAlias baseline = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, archive.length, "content-a", keyA);
        fixture.store().loadFromSource("https://packs.example/variants.mcpack", baseline, baselineSequence,
                true, keyA, () -> archive).get(10, TimeUnit.SECONDS);

        final List<TrustedVariant> variants = List.of(
                new TrustedVariant(PackAlias.from(
                        "inet:10.0.0.2:19132", PACK_KEY, archive.length,
                        "content-a", keyA), baselineSequence, keyA),
                new TrustedVariant(PackAlias.from(
                        "inet:10.0.0.1:19132", PACK_KEY, archive.length + 1L,
                        "content-a", keyA), baselineSequence, keyA),
                new TrustedVariant(PackAlias.from(
                        "inet:10.0.0.1:19132", PACK_KEY, archive.length,
                        "content-b", keyA), baselineSequence, keyA),
                new TrustedVariant(PackAlias.from(
                        "inet:10.0.0.1:19132", PACK_KEY, archive.length,
                        "content-a", keyB), baselineSequence, keyB),
                new TrustedVariant(baseline, "d".repeat(64), keyA));
        final AtomicInteger loaderCalls = new AtomicInteger();
        for (TrustedVariant variant : variants) {
            final CompletableFuture<ResourcePack> result = fixture.store().loadFromSource(
                    "https://packs.example/variants.mcpack", variant.alias(), variant.sequence(), true,
                    variant.contentKey(), () -> {
                        loaderCalls.incrementAndGet();
                        return archive;
                    });
            if (variant.alias().announcedSize() != archive.length) {
                assertFutureFailure(result, IOException.class);
            } else if (variant.contentKey().length > 0) {
                assertFutureFailure(result, IllegalStateException.class);
            } else {
                assertEquals(PACK_KEY, result.get(10, TimeUnit.SECONDS).key());
            }
        }

        assertEquals(variants.size(), loaderCalls.get());
    }

    @Test
    void conflictingExactAliasRetainsHistoryAndDisablesTrustedHit(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] firstArchive = plainArchive(PACK_KEY, "first");
        final byte[] secondArchive = plainArchive(PACK_KEY, "other");
        final byte[] thirdArchive = plainArchive(PACK_KEY, "third");
        assertEquals(firstArchive.length, secondArchive.length);
        assertEquals(firstArchive.length, thirdArchive.length);
        final byte[] contentKey = new byte[0];
        final PackAlias alias = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, firstArchive.length, "content-a", contentKey);
        final String sequence = "e".repeat(64);

        fixture.store().loadFromSource("https://packs.example/conflict.mcpack", alias, sequence, false,
                contentKey, () -> firstArchive).get(10, TimeUnit.SECONDS);
        fixture.store().loadFromSource("https://packs.example/conflict.mcpack", alias, sequence, false,
                contentKey, () -> secondArchive).get(10, TimeUnit.SECONDS);
        assertEquals(1, fixture.metrics().getAliasConflicts());

        final AtomicInteger loaderCalls = new AtomicInteger();
        final ResourcePack resolved = fixture.store().loadFromSource(
                "https://packs.example/conflict.mcpack", alias, sequence, true, contentKey, () -> {
                    loaderCalls.incrementAndGet();
                    return thirdArchive;
                }).get(10, TimeUnit.SECONDS);

        assertEquals(1, loaderCalls.get());
        assertArrayEquals(bytes("third"), resolved.content().get("value.txt"));
    }

    @Test
    void trustedAliasConflictCreatesStablePermanentTombstone(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 7);
        final byte[] firstArchive = plainArchive(PACK_KEY, "first");
        final byte[] secondArchive = plainArchive(PACK_KEY, "other");
        assertEquals(firstArchive.length, secondArchive.length);
        final byte[] contentKey = new byte[0];
        final PackAlias alias = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, firstArchive.length, "content-a", contentKey);
        final String sequence = "1".repeat(64);

        fixture.store().loadFromSource("https://packs.example/tombstone-first.mcpack",
                alias, sequence, false, contentKey, () -> firstArchive).get(10, TimeUnit.SECONDS);
        fixture.store().loadFromSource("https://packs.example/tombstone-second.mcpack",
                alias, sequence, false, contentKey, () -> secondArchive).get(10, TimeUnit.SECONDS);

        final Path tombstone = fixture.store().trustedAliasConflictPath(alias, sequence);
        final String fileName = tombstone.getFileName().toString();
        assertTrue(fileName.matches("[0-9a-f]{64}\\.conflict"));
        final String conflictDigest = fileName.substring(0, 64);
        assertEquals("ViaBedrock-TrustedAliasConflict-v1\n" + conflictDigest + '\n',
                Files.readString(tombstone, StandardCharsets.US_ASCII));
        assertEquals(1L, countRegularFiles(tempDir.resolve("v2/trusted-alias-conflicts/sha256")));

        final MaintenanceFixture restarted = this.maintenanceFixture(tempDir, 100, 7);
        assertEquals(tombstone, restarted.store().trustedAliasConflictPath(alias, sequence));
        assertNotEquals(tombstone, restarted.store().trustedAliasConflictPath(alias, "2".repeat(64)));
        final PackAlias differentAlias = PackAlias.from(
                "inet:10.0.0.2:19132", PACK_KEY, firstArchive.length, "content-a", contentKey);
        assertNotEquals(tombstone, restarted.store().trustedAliasConflictPath(differentAlias, sequence));

        final Path staleTemp = Files.createTempFile(
                tombstone.getParent(), conflictDigest + "-", ".conflict.tmp");
        Files.setLastModifiedTime(staleTemp, FileTime.fromMillis(
                restarted.clock().read() - TimeUnit.HOURS.toMillis(2L)));
        final MaintenanceFixture recovered = this.maintenanceFixture(
                tempDir, 100, 7, 100_000L, restarted.clock().read());

        assertFalse(Files.exists(staleTemp));
        assertTrue(Files.isRegularFile(tombstone));
        assertTrue(Files.isRegularFile(recovered.store().trustedAliasGlobalQuarantinePath()));
        assertTrue(recovered.metrics().getTrustedAliasGlobalQuarantine());
        assertEquals(1L, countRegularFiles(tempDir.resolve("v2/trusted-alias-conflicts/sha256")));
    }

    @Test
    void trustedAliasConflictSurvivesHistoryEvictionAndRestart(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 7, 1L);
        final byte[] firstArchive = plainArchive(PACK_KEY, "first");
        final byte[] secondArchive = plainArchive(PACK_KEY, "other");
        final byte[] fallbackArchive = plainArchive(PACK_KEY, "third");
        assertEquals(firstArchive.length, secondArchive.length);
        assertEquals(firstArchive.length, fallbackArchive.length);
        final byte[] contentKey = new byte[0];
        final PackAlias alias = PackAlias.from(
                "inet:10.0.0.1:19132", PACK_KEY, firstArchive.length, "content-a", contentKey);
        final String sequence = "3".repeat(64);

        fixture.store().loadFromSource("https://packs.example/evicted-first.mcpack",
                alias, sequence, false, contentKey, () -> firstArchive).get(10, TimeUnit.SECONDS);
        fixture.store().loadFromSource("https://packs.example/evicted-second.mcpack",
                alias, sequence, false, contentKey, () -> secondArchive).get(10, TimeUnit.SECONDS);
        final Path tombstone = fixture.store().trustedAliasConflictPath(alias, sequence);
        assertTrue(Files.isRegularFile(tombstone));

        final PackAlias evictionAlias = PackAlias.from(
                "inet:10.0.0.2:19132", PACK_KEY, firstArchive.length, "content-b", contentKey);
        fixture.store().loadFromSource("https://packs.example/eviction-trigger.mcpack",
                evictionAlias, "4".repeat(64), false, contentKey, () -> firstArchive).get(10, TimeUnit.SECONDS);
        assertEquals(1L, fixture.store().trustedAliasHistorySize());

        final AtomicInteger inProcessLoaderCalls = new AtomicInteger();
        final ResourcePack inProcess = fixture.store().loadFromSource(
                "https://packs.example/quarantined-in-process.mcpack",
                alias, sequence, true, contentKey, () -> {
                    inProcessLoaderCalls.incrementAndGet();
                    return fallbackArchive;
                }).get(10, TimeUnit.SECONDS);
        assertEquals(1, inProcessLoaderCalls.get());
        assertArrayEquals(bytes("third"), inProcess.content().get("value.txt"));

        final AtomicInteger strictLoaderCalls = new AtomicInteger();
        final ResourcePack strict = fixture.store().loadFromSource(
                "https://packs.example/quarantined-strict.mcpack",
                alias, sequence, false, contentKey, () -> {
                    strictLoaderCalls.incrementAndGet();
                    return firstArchive;
                }).get(10, TimeUnit.SECONDS);
        assertEquals(1, strictLoaderCalls.get());
        assertArrayEquals(bytes("first"), strict.content().get("value.txt"));

        final MaintenanceFixture restarted = this.maintenanceFixture(tempDir, 100, 7, 1L);
        final AtomicInteger restartedLoaderCalls = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            final ResourcePack resolved = restarted.store().loadFromSource(
                    "https://packs.example/quarantined-after-restart.mcpack",
                    alias, sequence, true, contentKey, () -> {
                        restartedLoaderCalls.incrementAndGet();
                        return firstArchive;
                    }).get(10, TimeUnit.SECONDS);
            assertArrayEquals(bytes("first"), resolved.content().get("value.txt"));
        }
        assertEquals(2, restartedLoaderCalls.get(),
                "The durable tombstone must prevent relearning a trusted alias after restart");
        assertTrue(Files.isRegularFile(tombstone));
    }

    @Test
    void trustedAliasTombstoneCapEnablesDurableGlobalFailClosedMode(
            @TempDir final Path tempDir) throws Exception {
        final long now = System.currentTimeMillis();
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 7, 100L, now, 2);
        final byte[] firstArchive = plainArchive(PACK_KEY, "first");
        final byte[] secondArchive = plainArchive(PACK_KEY, "other");
        assertEquals(firstArchive.length, secondArchive.length);
        final byte[] contentKey = new byte[0];
        final PackAlias reusableAlias = PackAlias.from(
                "inet:10.0.1.1:19132", PACK_KEY, firstArchive.length, "reusable", contentKey);
        final String reusableSequence = "7".repeat(64);
        fixture.store().loadFromSource("https://packs.example/global-reusable-first.mcpack",
                reusableAlias, reusableSequence, false, contentKey, () -> firstArchive)
                .get(10L, TimeUnit.SECONDS);

        for (int i = 0; i < 2; i++) {
            final PackAlias alias = PackAlias.from(
                    "inet:10.0.2." + i + ":19132", PACK_KEY,
                    firstArchive.length, "conflict-" + i, contentKey);
            final String sequence = String.valueOf(i + 1).repeat(64);
            fixture.store().loadFromSource("https://packs.example/global-first-" + i + ".mcpack",
                    alias, sequence, false, contentKey, () -> firstArchive).get(10L, TimeUnit.SECONDS);
            fixture.store().loadFromSource("https://packs.example/global-second-" + i + ".mcpack",
                    alias, sequence, false, contentKey, () -> secondArchive).get(10L, TimeUnit.SECONDS);
        }

        final Path tombstoneRoot = tempDir.resolve("v2/trusted-alias-conflicts");
        assertTrue(Files.isRegularFile(fixture.store().trustedAliasGlobalQuarantinePath()));
        assertEquals(2L, fixture.metrics().getTrustedAliasConflictTombstones());
        assertEquals(2L, fixture.metrics().getTrustedAliasConflictTombstoneCapacity());
        assertTrue(fixture.metrics().getTrustedAliasGlobalQuarantine());
        assertEquals(3L, countRegularFiles(tombstoneRoot));
        assertEquals(regularFileBytes(tombstoneRoot),
                fixture.metrics().getTrustedAliasConflictTombstoneBytes());
        assertEquals(fixture.metrics().getCasDiskBytes(),
                fixture.metrics().getArchiveWeightBytes()
                        + fixture.metrics().getContentWeightBytes()
                        + fixture.metrics().getTrustedAliasConflictTombstoneBytes());

        final AtomicInteger globallyRejectedLoaderCalls = new AtomicInteger();
        fixture.store().loadFromSource("https://packs.example/global-reusable-hit.mcpack",
                reusableAlias, reusableSequence, true, contentKey, () -> {
                    globallyRejectedLoaderCalls.incrementAndGet();
                    return firstArchive;
                }).get(10L, TimeUnit.SECONDS);
        assertEquals(1, globallyRejectedLoaderCalls.get());

        final PackAlias extraAlias = PackAlias.from(
                "inet:10.0.2.9:19132", PACK_KEY, firstArchive.length, "extra", contentKey);
        fixture.store().loadFromSource("https://packs.example/global-extra-first.mcpack",
                extraAlias, "8".repeat(64), false, contentKey, () -> firstArchive)
                .get(10L, TimeUnit.SECONDS);
        fixture.store().loadFromSource("https://packs.example/global-extra-second.mcpack",
                extraAlias, "8".repeat(64), false, contentKey, () -> secondArchive)
                .get(10L, TimeUnit.SECONDS);
        assertEquals(3L, countRegularFiles(tombstoneRoot),
                "Global quarantine must prevent creation of additional tombstone inodes");

        final MaintenanceFixture restarted = this.maintenanceFixture(tempDir, 100, 7, 100L, now, 2);
        assertTrue(restarted.metrics().getTrustedAliasGlobalQuarantine());
        assertEquals(2L, restarted.metrics().getTrustedAliasConflictTombstones());
        assertEquals(3L, countRegularFiles(tombstoneRoot));
        final AtomicInteger restartedLoaderCalls = new AtomicInteger();
        restarted.store().loadFromSource("https://packs.example/global-restarted-hit.mcpack",
                reusableAlias, reusableSequence, true, contentKey, () -> {
                    restartedLoaderCalls.incrementAndGet();
                    return firstArchive;
                }).get(10L, TimeUnit.SECONDS);
        assertEquals(1, restartedLoaderCalls.get());
    }

    @Test
    void rawHashMismatchFailsWaitersAndAllowsRetry(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] expectedArchive = plainArchive(PACK_KEY, "expected");
        final byte[] wrongArchive = plainArchive(PACK_KEY, "wrong");
        final byte[] expectedHash = sha256(expectedArchive);
        final ResourcePackArchiveStore.Claim leader = fixture.store().claim(expectedHash);
        final ResourcePackArchiveStore.Claim waiter = fixture.store().claim(expectedHash);

        assertTrue(leader.leader());
        assertThrows(IllegalStateException.class, () -> fixture.store().publish(leader, wrongArchive));
        assertFutureFailure(waiter.path(), IllegalStateException.class);

        final ResourcePackArchiveStore.Claim backedOff = fixture.store().claim(expectedHash);
        assertFalse(backedOff.leader());
        assertFutureFailure(backedOff.path(), IllegalStateException.class);

        fixture.advanceBackoff();
        final ResourcePackArchiveStore.Claim retry = fixture.store().claim(expectedHash);
        assertTrue(retry.leader());
        final Path published = fixture.store().publish(retry, expectedArchive);
        assertArrayEquals(expectedArchive, Files.readAllBytes(published));
        retry.close();
    }

    @Test
    void failedRawLeaderCanBeReclaimedAfterBackoff(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "retry");
        final byte[] hash = sha256(archive);
        final ResourcePackArchiveStore.Claim leader = fixture.store().claim(hash);
        final ResourcePackArchiveStore.Claim waiter = fixture.store().claim(hash);

        fixture.store().fail(leader, new IOException("leader disconnected"));
        assertFutureFailure(waiter.path(), IOException.class);

        final ResourcePackArchiveStore.Claim backedOff = fixture.store().claim(hash);
        assertFalse(backedOff.leader());
        assertFutureFailure(backedOff.path(), IOException.class);

        fixture.advanceBackoff();
        final ResourcePackArchiveStore.Claim retry = fixture.store().claim(hash);
        assertTrue(retry.leader());
        fixture.store().publish(retry, archive);
        retry.close();
    }

    @Test
    void corruptedStoredArchiveIsRejectedAndCanBeReclaimed(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "stored");
        final PublishedArchive published = publish(fixture.store(), archive);
        Files.write(published.path(), plainArchive(PACK_KEY, "corrupted"));

        assertFutureFailure(fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], ""), IllegalStateException.class);
        assertEquals(1L, fixture.metrics().getArchiveFailures());
        assertEquals(0L, fixture.metrics().getContentFailures());
        final ResourcePackArchiveStore.Claim retry = fixture.store().claim(sha256(archive));

        assertTrue(retry.leader());
        fixture.store().publish(retry, archive);
        retry.close();
    }

    @Test
    void corruptedCanonicalArchiveIsDeletedAndRebuilt(@TempDir final Path tempDir) throws Exception {
        final Fixture firstFixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "canonical-original");
        final PublishedArchive published = publish(firstFixture.store(), archive);
        final ResourcePack first = firstFixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);
        final Path canonical = ((ZipFileContent) first.content()).path();
        final ContentDigest expectedDigest = ((ZipFileContent) first.content()).contentDigest();
        Files.write(canonical, plainArchive(PACK_KEY, "canonical-tampered"));

        final Fixture secondFixture = this.fixture(tempDir);
        final ResourcePack repaired = secondFixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);

        assertArrayEquals(bytes("canonical-original"), repaired.content().get("value.txt"));
        assertEquals(expectedDigest, ((ZipFileContent) repaired.content()).contentDigest());
        assertEquals(expectedDigest, ContentDigest.compute(new ZipFileContent(canonical)));
    }

    @Test
    void canonicalTempMismatchNeverReplacesConcurrentValidTarget(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final InMemoryContent expectedContent = new InMemoryContent();
        expectedContent.putString("manifest.json", manifest(PACK_KEY));
        expectedContent.putString("value.txt", "expected");
        final ContentDigest digest = ContentDigest.compute(expectedContent);
        final byte[] canonicalArchive = expectedContent.toZip();
        final byte[] mismatchingArchive = plainArchive(PACK_KEY, "mismatch");
        final CountDownLatch mismatchingTempWritten = new CountDownLatch(1);
        final CountDownLatch releaseValidation = new CountDownLatch(1);
        final InMemoryContent racingContent = new InMemoryContent() {
            @Override
            public void writeZip(final Path target) throws IOException {
                Files.write(target, mismatchingArchive);
                mismatchingTempWritten.countDown();
                await(releaseValidation);
            }
        };
        racingContent.putString("manifest.json", manifest(PACK_KEY));
        racingContent.putString("value.txt", "expected");

        final CompletableFuture<Void> publication = CompletableFuture.runAsync(() -> {
            try {
                fixture.store().ensureCanonical(new ResourcePack(racingContent), digest);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        final Path target = contentCasFile(tempDir, digest.hex());
        try {
            assertTrue(mismatchingTempWritten.await(10L, TimeUnit.SECONDS));
            Files.write(target, canonicalArchive);
        } finally {
            releaseValidation.countDown();
        }

        final Throwable failure = assertFutureFailure(publication, IllegalStateException.class);
        assertTrue(failure.getMessage().contains("digest mismatch"));
        assertEquals(digest, ContentDigest.compute(new ZipFileContent(target)));
        assertArrayEquals(bytes("expected"), new ZipFileContent(target).get("value.txt"));
        assertNoCanonicalTemps(tempDir);
    }

    @Test
    void initializationDeletesFreshManagedTempsAndExpansionDirectories(@TempDir final Path tempDir)
            throws Exception {
        this.fixture(tempDir);
        final Path rawRoot = tempDir.resolve("v2/raw/sha256");
        final Path contentRoot = tempDir.resolve("v2/content/sha256");
        final Path rawTemp = Files.createTempFile(rawRoot, "fresh-", ".mcpack.tmp");
        final Path contentTemp = Files.createTempFile(contentRoot, "fresh-", ".zip.tmp");
        final Path expansionTemp = Files.createDirectories(contentRoot.resolve("expand-fresh"));
        Files.write(expansionTemp.resolve("partial.bin"), bytes("partial"));
        final Path rawArchive = casFile(tempDir, "0".repeat(64), ".mcpack");
        final Path canonicalArchive = contentCasFile(tempDir, "1".repeat(64));
        Files.write(rawArchive, bytes("durable-raw"));
        Files.write(canonicalArchive, bytes("durable-content"));

        this.fixture(tempDir);

        assertFalse(Files.exists(rawTemp));
        assertFalse(Files.exists(contentTemp));
        assertFalse(Files.exists(expansionTemp));
        assertTrue(Files.isRegularFile(rawArchive));
        assertTrue(Files.isRegularFile(canonicalArchive));
    }

    @Test
    void initializationDeletesStaleExpansionDirectory(@TempDir final Path tempDir) throws Exception {
        this.fixture(tempDir);
        final Path stale = tempDir.resolve("v2/content/sha256/expand-stale");
        Files.createDirectories(stale.resolve("archive"));
        Files.write(stale.resolve("archive/partial.bin"), bytes("partial"));
        Files.setLastModifiedTime(stale, FileTime.fromMillis(
                System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)));

        this.fixture(tempDir);

        assertFalse(Files.exists(stale));
    }

    @Test
    void cleanupProtectsLiveCanonicalAndInflightRawFiles(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 7);
        final byte[] archive = plainArchive(PACK_KEY, "live");
        final PublishedArchive published = publish(fixture.store(), archive);
        final ResourcePack livePack = fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);
        final Path canonical = ((ZipFileContent) livePack.content()).path();

        final byte[] inflightData = bytes("inflight");
        final ArchiveDigest inflightDigest = ArchiveDigest.compute(inflightData);
        final ResourcePackArchiveStore.Claim inflight = fixture.store().claim(sha256(inflightData));
        final Path inflightTarget = tempDir.resolve("v2/raw/sha256")
                .resolve(inflightDigest.hex().substring(0, 2))
                .resolve(inflightDigest.hex() + ".mcpack");
        Files.createDirectories(inflightTarget.getParent());
        Files.write(inflightTarget, inflightData);

        fixture.clock().advanceDays(8L);
        fixture.store().cleanupNow();

        assertFalse(Files.exists(published.path()));
        assertTrue(Files.isRegularFile(canonical));
        assertTrue(Files.isRegularFile(inflightTarget));
        assertEquals(PACK_KEY, livePack.key());

        fixture.store().abandon(inflight, new IOException("test release"));
        fixture.store().cleanupNow();
        assertFalse(Files.exists(inflightTarget));
        assertTrue(fixture.metrics().getCasDiskDeletedFiles() >= 2L);
    }

    @Test
    void hotRawClaimPinsArchiveUntilEffectiveLoadAcquiresItsBuildLease(
            @TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 1);
        final byte[] archive = plainArchive(PACK_KEY, "raw-claim-lease");
        final PublishedArchive published = publish(fixture.store(), archive);
        final ResourcePackArchiveStore.Claim hotClaim = fixture.store().claim(sha256(archive));
        final CountDownLatch readyToLoad = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        final CompletableFuture<ResourcePack> load = CompletableFuture.supplyAsync(() -> {
            readyToLoad.countDown();
            await(releaseLoad);
            return fixture.store().loadEffective(hotClaim, PACK_KEY, new byte[0], "");
        }).thenCompose(stage -> stage);

        try {
            assertTrue(readyToLoad.await(10L, TimeUnit.SECONDS));
            assertEquals(1L, fixture.store().activeRawLeaseCount());
            fixture.clock().advanceDays(2L);
            Files.setLastModifiedTime(published.path(), FileTime.fromMillis(
                    fixture.clock().read() - TimeUnit.DAYS.toMillis(2L)));

            fixture.store().cleanupNow();

            assertTrue(Files.isRegularFile(published.path()),
                    "The claim-to-load bridge must protect a hot raw CAS hit");
        } finally {
            releaseLoad.countDown();
        }

        final ResourcePack pack = load.get(10L, TimeUnit.SECONDS);
        assertEquals(PACK_KEY, pack.key());
        assertEquals(0L, fixture.store().activeRawLeaseCount());

        fixture.clock().advanceDays(2L);
        Files.setLastModifiedTime(published.path(), FileTime.fromMillis(
                fixture.clock().read() - TimeUnit.DAYS.toMillis(2L)));
        fixture.store().cleanupNow();

        assertFalse(Files.exists(published.path()),
                "The raw archive must be evictable after expansion releases its short lease");
    }

    @Test
    void pathCompatibilityLoadPinsRawBeforeLeavingItsPublicEntry(
            @TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 1);
        final byte[] archive = plainArchive(PACK_KEY, "path-entry-lease");
        final PublishedArchive published = publish(fixture.store(), archive);
        final CountDownLatch leaseAcquired = new CountDownLatch(1);
        final CountDownLatch releaseEntry = new CountDownLatch(1);
        fixture.store().pathLoadLeaseAcquiredHook(() -> {
            leaseAcquired.countDown();
            await(releaseEntry);
        });
        final CompletableFuture<ResourcePack> load = CompletableFuture.supplyAsync(() ->
                fixture.store().loadEffective(
                        published.path(), published.digest(), PACK_KEY, new byte[0], ""))
                .thenCompose(stage -> stage);

        try {
            assertTrue(leaseAcquired.await(10L, TimeUnit.SECONDS));
            assertEquals(1L, fixture.store().activeRawLeaseCount());
            fixture.clock().advanceDays(2L);
            Files.setLastModifiedTime(published.path(), FileTime.fromMillis(
                    fixture.clock().read() - TimeUnit.DAYS.toMillis(2L)));
            fixture.store().cleanupNow();
            assertTrue(Files.isRegularFile(published.path()));
        } finally {
            releaseEntry.countDown();
        }

        assertEquals(PACK_KEY, load.get(10L, TimeUnit.SECONDS).key());
        assertEquals(0L, fixture.store().activeRawLeaseCount());
    }

    @Test
    void closingHotRawClaimBeforeLoadReleasesBridgeExactlyOnce(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 1);
        final byte[] archive = plainArchive(PACK_KEY, "closed-raw-claim");
        final PublishedArchive published = publish(fixture.store(), archive);
        final ResourcePackArchiveStore.Claim hotClaim = fixture.store().claim(sha256(archive));

        assertEquals(1L, fixture.store().activeRawLeaseCount());
        hotClaim.close();
        hotClaim.close();

        assertEquals(0L, fixture.store().activeRawLeaseCount());
        assertFutureFailure(fixture.store().loadEffective(
                hotClaim, PACK_KEY, new byte[0], ""), CancellationException.class);
        fixture.clock().advanceDays(2L);
        Files.setLastModifiedTime(published.path(), FileTime.fromMillis(
                fixture.clock().read() - TimeUnit.DAYS.toMillis(2L)));
        fixture.store().cleanupNow();
        assertFalse(Files.exists(published.path()));
    }

    @Test
    void canonicalLeaseIsReferenceCountedAndOnlyProtectsActiveReaders(
            @TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 1);
        final ResourcePack source = new ResourcePack(new ZipContent(plainArchive(PACK_KEY, "leased")));
        final ContentDigest digest = ContentDigest.compute(source.content());
        fixture.store().ensureCanonical(source, digest);
        fixture.store().ensureCanonical(source, digest);
        assertEquals(1L, fixture.metrics().getContentBuilds());
        assertEquals(1L, fixture.metrics().getContentBuildTimeSamples());
        final Path canonical = contentCasFile(tempDir, digest.hex());
        final ResourcePackArchiveStore.CanonicalContentLease first = fixture.store().leaseCanonical(digest);
        final ResourcePackArchiveStore.CanonicalContentLease second = fixture.store().leaseCanonical(digest);

        fixture.clock().advanceDays(2L);
        Files.setLastModifiedTime(canonical, FileTime.fromMillis(
                fixture.clock().read() - TimeUnit.DAYS.toMillis(2L)));
        fixture.store().cleanupNow();
        assertTrue(Files.isRegularFile(canonical));
        assertArrayEquals(bytes("leased"), first.content().get("value.txt"));

        first.close();
        fixture.store().cleanupNow();
        assertTrue(Files.isRegularFile(canonical), "The second reader must keep the CAS entry pinned");

        second.close();
        fixture.store().cleanupNow();
        assertFalse(Files.exists(canonical), "Idle canonical content must be evictable after the last lease");
    }

    @Test
    void cleanupEnforcesCasQuotaAndPublishesIndependentDiskMetrics(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 1, 7);
        final Path first = casFile(tempDir, "1".repeat(64), ".mcpack");
        final Path second = casFile(tempDir, "2".repeat(64), ".mcpack");
        final byte[] data = new byte[700 * 1024];
        Files.write(first, data);
        Files.write(second, data);
        final long now = fixture.clock().read();
        Files.setLastModifiedTime(first, FileTime.fromMillis(now - TimeUnit.MINUTES.toMillis(12L)));
        Files.setLastModifiedTime(second, FileTime.fromMillis(now - TimeUnit.MINUTES.toMillis(11L)));

        fixture.store().cleanupNow();

        assertFalse(Files.exists(first));
        assertTrue(Files.isRegularFile(second));
        assertEquals(data.length, fixture.metrics().getCasDiskBytes());
        assertEquals(1L, fixture.metrics().getCasDiskFiles());
        assertEquals(data.length, fixture.metrics().getArchiveWeightBytes());
        assertEquals(0L, fixture.metrics().getContentWeightBytes());
        assertEquals(1024L * 1024L, fixture.metrics().getArchiveMaxWeightBytes());
        assertEquals(1024L * 1024L, fixture.metrics().getContentMaxWeightBytes());
        assertTrue(fixture.metrics().getCasDiskDeletedBytes() >= data.length);
        assertEquals(0L, fixture.metrics().getArtifactDiskBytes());
    }

    @Test
    void rawAndCanonicalPublicationUpdateDiskWeightsImmediately(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 1);
        final byte[] archive = plainArchive(PACK_KEY, "immediate-disk-weight");
        final PublishedArchive published = publish(fixture.store(), archive);

        assertEquals(Files.size(published.path()), fixture.metrics().getArchiveWeightBytes());
        assertEquals(1L, fixture.metrics().getCasDiskFiles());
        assertEquals(fixture.metrics().getArchiveWeightBytes(), fixture.metrics().getCasDiskBytes());

        final ResourcePack pack = fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10L, TimeUnit.SECONDS);
        final Path canonical = ((ZipFileContent) pack.content()).path();
        assertEquals(Files.size(canonical), fixture.metrics().getContentWeightBytes());
        assertEquals(2L, fixture.metrics().getCasDiskFiles());
        assertEquals(fixture.metrics().getCasDiskBytes(),
                fixture.metrics().getArchiveWeightBytes() + fixture.metrics().getContentWeightBytes());

        fixture.clock().advanceDays(2L);
        Files.setLastModifiedTime(published.path(), FileTime.fromMillis(
                fixture.clock().read() - TimeUnit.DAYS.toMillis(2L)));
        fixture.store().cleanupNow();
        assertEquals(0L, fixture.metrics().getArchiveWeightBytes());
        assertEquals(fixture.metrics().getContentWeightBytes(), fixture.metrics().getCasDiskBytes());
    }

    @Test
    void cleanupAttributesManagedEvictionsToArchiveAndContentTiers(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 1);
        final Path archive = casFile(tempDir, "a".repeat(64), ".mcpack");
        final Path content = contentCasFile(tempDir, "b".repeat(64));
        Files.write(archive, bytes("raw"));
        Files.write(content, bytes("canonical"));
        final FileTime stale = FileTime.fromMillis(
                fixture.clock().read() - TimeUnit.DAYS.toMillis(2L));
        Files.setLastModifiedTime(archive, stale);
        Files.setLastModifiedTime(content, stale);

        fixture.store().cleanupNow();

        assertFalse(Files.exists(archive));
        assertFalse(Files.exists(content));
        assertEquals(1L, fixture.metrics().getArchiveEvictions());
        assertEquals(1L, fixture.metrics().getContentEvictions());
        assertEquals(0L, fixture.metrics().getBlobEvictions());
    }

    @Test
    void throttledAccessTouchSurvivesRestartIdleCleanup(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture first = this.maintenanceFixture(tempDir, 100, 1);
        final byte[] archive = plainArchive(PACK_KEY, "touched");
        final PublishedArchive published = publish(first.store(), archive);
        final ResourcePack pack = first.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);
        final Path canonical = ((ZipFileContent) pack.content()).path();
        first.scheduler().submitIo(() -> null).get(10, TimeUnit.SECONDS);

        first.clock().advanceDays(2L);
        final long stale = first.clock().read() - TimeUnit.DAYS.toMillis(2L);
        Files.setLastModifiedTime(published.path(), FileTime.fromMillis(stale));
        Files.setLastModifiedTime(canonical, FileTime.fromMillis(stale));
        try (ResourcePackArchiveStore.Claim ignored = first.store().claim(sha256(archive))) {
            // 认领精确的原始摘要会刷新其持久化的访问时间。
        }
        first.store().loadEffective(published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);
        first.scheduler().submitIo(() -> null).get(10, TimeUnit.SECONDS);

        assertTrue(Files.getLastModifiedTime(published.path()).toMillis() >= first.clock().read());
        assertTrue(Files.getLastModifiedTime(canonical).toMillis() >= first.clock().read());

        final MaintenanceFixture restarted = this.maintenanceFixture(
                tempDir, 100, 1, 100_000L, first.clock().read());
        assertTrue(Files.isRegularFile(published.path()));
        assertTrue(Files.isRegularFile(canonical));
        assertEquals(2L, restarted.metrics().getCasDiskFiles());
        assertEquals(Files.size(published.path()), restarted.metrics().getArchiveWeightBytes());
        assertEquals(Files.size(canonical), restarted.metrics().getContentWeightBytes());
        assertEquals(restarted.metrics().getCasDiskBytes(),
                restarted.metrics().getArchiveWeightBytes() + restarted.metrics().getContentWeightBytes());
        assertEquals(100L * 1024L * 1024L, restarted.metrics().getArchiveMaxWeightBytes());
        assertEquals(100L * 1024L * 1024L, restarted.metrics().getContentMaxWeightBytes());
    }

    @Test
    void aliasObservationCachesStayBounded(@TempDir final Path tempDir) throws Exception {
        final MaintenanceFixture fixture = this.maintenanceFixture(tempDir, 100, 7, 4L);
        final byte[] archive = plainArchive(PACK_KEY, "bounded-aliases");
        final PublishedArchive published = publish(fixture.store(), archive);
        for (int i = 0; i < 10; i++) {
            final PackAlias alias = PackAlias.from(
                    "inet:10.0.0." + i + ":19132", PACK_KEY, archive.length, "content", new byte[0]);
            fixture.store().loadEffective(published.path(), published.digest(), alias,
                    String.format("%064x", i), new byte[0]).get(10, TimeUnit.SECONDS);
        }

        assertTrue(fixture.store().aliasHistorySize() <= 4L);
        assertTrue(fixture.store().trustedAliasHistorySize() <= 4L);
    }

    @Test
    void maintenancePrunesClearedExpandedWeakValues() {
        final ConcurrentMap<String, WeakReference<Object>> values = new ConcurrentHashMap<>();
        final WeakReference<Object> cleared = new WeakReference<>(new Object());
        final Object live = new Object();
        cleared.clear();
        values.put("cleared", cleared);
        values.put("live", new WeakReference<>(live));

        ResourcePackArchiveStore.pruneClearedWeakValues(values);

        assertFalse(values.containsKey("cleared"));
        assertTrue(values.containsKey("live"));
        assertSame(live, values.get("live").get());
    }

    @Test
    void failedSourceLoaderCanBeRetried(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "retry-source");
        final AtomicInteger attempts = new AtomicInteger();
        final ResourcePackArchiveStore.ArchiveLoader loader = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IOException("temporary source failure");
            }
            return archive;
        };

        assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/retry.mcpack", PACK_KEY, new byte[0], "", loader), IOException.class);
        assertFutureFailure(fixture.store().loadFromSource(
                "https://packs.example/retry.mcpack", PACK_KEY, new byte[0], "", loader), IOException.class);
        assertEquals(1, attempts.get());

        fixture.advanceBackoff();
        final ResourcePack pack = fixture.store().loadFromSource(
                "https://packs.example/retry.mcpack", PACK_KEY, new byte[0], "", loader)
                .get(10, TimeUnit.SECONDS);

        assertEquals(PACK_KEY, pack.key());
        assertEquals(2, attempts.get());
    }

    @Test
    void validatesPlainManifestKeyEvenAfterExpansionWasCached(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = plainArchive(PACK_KEY, "plain");
        final PublishedArchive published = publish(fixture.store(), archive);

        final ResourcePack pack = fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);
        assertEquals(PACK_KEY, pack.key());

        final CompletableFuture<ResourcePack> wrongAlias = fixture.store().loadEffective(
                published.path(), published.digest(), WRONG_KEY, new byte[0], "");
        assertManifestMismatch(wrongAlias);
    }

    @Test
    void validatesEncryptedManifestKeyWithoutCrossAliasReuse(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final EncryptedArchive encrypted = encryptedArchive(PACK_KEY);
        final PublishedArchive published = publish(fixture.store(), encrypted.archive());

        final ResourcePack pack = fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, encrypted.contentKey(), encrypted.contentId())
                .get(10, TimeUnit.SECONDS);
        assertEquals(PACK_KEY, pack.key());
        assertArrayEquals(bytes("decrypted-value"), pack.content().get("secret.txt"));

        final CompletableFuture<ResourcePack> wrongAlias = fixture.store().loadEffective(
                published.path(), published.digest(), WRONG_KEY, encrypted.contentKey(), encrypted.contentId());
        assertManifestMismatch(wrongAlias);
        assertNoExpansionTemps(tempDir);
    }

    @Test
    void rejectsEncryptedPackWithoutContentKey(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final EncryptedArchive encrypted = encryptedArchive(PACK_KEY);
        final PublishedArchive published = publish(fixture.store(), encrypted.archive());

        final IllegalStateException failure = assertFutureFailure(fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], encrypted.contentId()),
                IllegalStateException.class);
        final IllegalStateException backedOff = assertFutureFailure(fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], encrypted.contentId()),
                IllegalStateException.class);

        assertTrue(failure.getMessage().contains("missing its content key"));
        assertSame(failure, backedOff);
        assertEquals(1L, fixture.metrics().getContentBuildTimeSamples());
        fixture.advanceBackoff();
        final IllegalStateException retried = assertFutureFailure(fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], encrypted.contentId()),
                IllegalStateException.class);
        assertFalse(failure == retried);
        assertEquals(2L, fixture.metrics().getContentBuildTimeSamples());
        assertNoExpansionTemps(tempDir);
    }

    @Test
    void nonEmptyContentKeyRejectsMissingTruncatedAndUnknownEncryptionHeaders(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] contentKey = bytes("0123456789abcdef0123456789abcdef");
        final byte[] unknownHeader = new byte[256];
        final List<MalformedEncryptedArchive> malformed = List.of(
                new MalformedEncryptedArchive("missing", plainArchive(PACK_KEY, "missing"),
                        "Missing contents.json"),
                new MalformedEncryptedArchive("truncated", zip(List.of(
                        new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                        new Entry("contents.json", new byte[4]))), "shorter than its encryption header"),
                new MalformedEncryptedArchive("unknown", zip(List.of(
                        new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                        new Entry("contents.json", unknownHeader))), "contents.json magic mismatch"));

        for (MalformedEncryptedArchive candidate : malformed) {
            final PackAlias alias = PackAlias.from(
                    "inet:10.0.0.1:19132", PACK_KEY, candidate.archive().length,
                    "test-content", contentKey);
            final IllegalStateException failure = assertFutureFailure(fixture.store().loadFromSource(
                    "https://packs.example/" + candidate.name() + ".mcpack", alias,
                    "0".repeat(64), false, contentKey,
                    () -> candidate.archive()), IllegalStateException.class);
            assertTrue(failure.getMessage().contains(candidate.expectedMessage()));
        }

        assertEquals(malformed.size(), countRegularFiles(tempDir.resolve("v2/raw/sha256")));
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/content/sha256")));
        assertEquals(0L, fixture.store().aliasHistorySize());
        assertEquals(0L, fixture.store().trustedAliasHistorySize());
        assertEquals(0L, fixture.metrics().getArchiveFailures());
        assertEquals(malformed.size(), fixture.metrics().getContentFailures());
        assertEquals(0L, fixture.metrics().getBlobFailures());
        assertNoExpansionTemps(tempDir);
    }

    @Test
    void canonicalizesSingleRootDirectoryThroughDiskStaging(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = zip(List.of(
                new Entry("root/manifest.json", bytes(manifest(PACK_KEY))),
                new Entry("root/value.txt", bytes("root-value"))));
        final PublishedArchive published = publish(fixture.store(), archive);

        final ResourcePack pack = fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);

        assertArrayEquals(bytes("root-value"), pack.content().get("value.txt"));
        assertFalse(pack.content().contains("root/value.txt"));
        assertNoExpansionTemps(tempDir);
    }

    @Test
    void canonicalizesNestedZipThroughSingleFileDiskStaging(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] nestedArchive = plainArchive(PACK_KEY, "nested-value");
        final byte[] outerArchive = zip(List.of(new Entry("pack.zip", nestedArchive)));
        final PublishedArchive published = publish(fixture.store(), outerArchive);

        final ResourcePack pack = fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], "")
                .get(10, TimeUnit.SECONDS);

        assertArrayEquals(bytes("nested-value"), pack.content().get("value.txt"));
        assertNoExpansionTemps(tempDir);
    }

    @Test
    void nestedZipSharesExpandedByteBudgetAcrossLayers(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 2, 1, 1, 100, 200);
        final byte[] payload = new byte[700 * 1024];
        new Random(12345L).nextBytes(payload);
        final byte[] nestedArchive = zip(List.of(
                new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                new Entry("payload.bin", payload)));
        final byte[] outerArchive = zip(List.of(new Entry("pack.zip", nestedArchive)));
        final PublishedArchive published = publish(fixture.store(), outerArchive);

        final Throwable failure = assertFutureFailure(fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], ""), IOException.class);

        assertTrue(failure.getMessage().contains("expanded size limit"));
        assertNoExpansionTemps(tempDir);
    }

    @Test
    void rejectsRootArchiveBeforeEntryCountCanExceedItsBound(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 2, 4, 1, 2, 200);
        final byte[] archive = zip(List.of(
                new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                new Entry("first.txt", bytes("first")),
                new Entry("second.txt", bytes("second"))));
        final PublishedArchive published = publish(fixture.store(), archive);

        final Throwable failure = assertFutureFailure(fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], ""), IOException.class);

        assertTrue(failure.getMessage().contains("too many entries"));
        assertNoExpansionTemps(tempDir);
    }

    @Test
    void rejectsZipSlipWithoutPublishingEffectiveContent(@TempDir final Path tempDir) throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] archive = zip(List.of(
                new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                new Entry("../escape.txt", bytes("escape"))));

        final Throwable failure = assertRejectedEffectiveArchive(fixture, tempDir, archive);

        assertTrue(failure.getMessage().contains("non-canonical path"));
        try (var paths = Files.walk(tempDir)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().equals("escape.txt")));
        }
    }

    @Test
    void rejectsDuplicateNormalizedPathWithoutPublishingEffectiveContent(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixture(tempDir);
        final byte[] duplicateArchive = zip(List.of(
                new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                new Entry("same/", new byte[0]),
                new Entry("same", bytes("second"))));

        final Throwable failure = assertRejectedEffectiveArchive(fixture, tempDir, duplicateArchive);

        assertTrue(failure.getMessage().contains("duplicate path"));
    }

    @Test
    void rejectsEntryLargerThanConfiguredLimitWithoutPublishingEffectiveContent(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 4, 4, 1, 100, 200);
        final byte[] payload = new byte[1024 * 1024 + 1];
        new Random(54321L).nextBytes(payload);
        final byte[] archive = zip(List.of(
                new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                new Entry("oversized.bin", payload)));

        final Throwable failure = assertRejectedEffectiveArchive(fixture, tempDir, archive);

        assertTrue(failure.getMessage().contains("entry exceeds the configured size limit"));
    }

    @Test
    void rejectsArchiveAboveCompressionRatioWithoutPublishingEffectiveContent(@TempDir final Path tempDir)
            throws Exception {
        final Fixture fixture = this.fixtureWithArchiveLimits(tempDir, 4, 4, 1, 100, 2);
        final byte[] archive = zip(List.of(
                new Entry("manifest.json", bytes(manifest(PACK_KEY))),
                new Entry("compressed.bin", new byte[256 * 1024])));

        final Throwable failure = assertRejectedEffectiveArchive(fixture, tempDir, archive);

        assertTrue(failure.getMessage().contains("compression ratio"));
    }

    private Fixture fixture(final Path tempDir) {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ViaBedrockConfig config = (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers" -> 2;
                    case "getResourcePackCacheIoWorkers" -> 8;
                    case "getResourcePackCacheQueueCapacity" -> 64;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        this.schedulers.add(scheduler);
        final MutableNanoClock clock = new MutableNanoClock();
        return new Fixture(
                new ResourcePackArchiveStore(tempDir, scheduler, metrics, clock::read), metrics, clock, scheduler);
    }

    private Fixture fixtureWithArchiveLimits(final Path tempDir, final int maxArchiveMiB,
                                             final int maxExpandedMiB, final int maxEntryMiB,
                                             final int maxEntries, final int maxCompressionRatio) {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ViaBedrockConfig config = (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers" -> 2;
                    case "getResourcePackCacheIoWorkers" -> 8;
                    case "getResourcePackCacheQueueCapacity" -> 64;
                    case "getResourcePackCacheDiskBudgetMiB" -> 100;
                    case "getResourcePackCacheDiskIdleDays" -> 7;
                    case "getResourcePackMaxArchiveMiB" -> maxArchiveMiB;
                    case "getResourcePackMaxExpandedMiB" -> maxExpandedMiB;
                    case "getResourcePackMaxEntryMiB" -> maxEntryMiB;
                    case "getResourcePackMaxEntries" -> maxEntries;
                    case "getResourcePackMaxCompressionRatio" -> maxCompressionRatio;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        this.schedulers.add(scheduler);
        final MutableNanoClock clock = new MutableNanoClock();
        return new Fixture(
                new ResourcePackArchiveStore(tempDir, scheduler, metrics, config, clock::read),
                metrics, clock, scheduler);
    }

    private MaintenanceFixture maintenanceFixture(final Path tempDir, final int diskBudgetMiB,
                                                  final int diskIdleDays) {
        return this.maintenanceFixture(
                tempDir, diskBudgetMiB, diskIdleDays, 100_000L, System.currentTimeMillis());
    }

    private MaintenanceFixture maintenanceFixture(final Path tempDir, final int diskBudgetMiB,
                                                   final int diskIdleDays, final long aliasHistoryMaxEntries) {
        return this.maintenanceFixture(
                tempDir, diskBudgetMiB, diskIdleDays, aliasHistoryMaxEntries, System.currentTimeMillis());
    }

    private MaintenanceFixture maintenanceFixture(final Path tempDir, final int diskBudgetMiB,
                                                   final int diskIdleDays, final long aliasHistoryMaxEntries,
                                                   final long initialMillis) {
        return this.maintenanceFixture(
                tempDir, diskBudgetMiB, diskIdleDays, aliasHistoryMaxEntries, initialMillis, 4_096);
    }

    private MaintenanceFixture maintenanceFixture(final Path tempDir, final int diskBudgetMiB,
                                                   final int diskIdleDays, final long aliasHistoryMaxEntries,
                                                   final long initialMillis,
                                                   final int trustedAliasConflictMaxTombstones) {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ViaBedrockConfig config = (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers" -> 2;
                    case "getResourcePackCacheIoWorkers" -> 1;
                    case "getResourcePackCacheQueueCapacity" -> 64;
                    case "getResourcePackCacheDiskBudgetMiB" -> diskBudgetMiB;
                    case "getResourcePackCacheDiskIdleDays" -> diskIdleDays;
                    case "getResourcePackMaxArchiveMiB" -> 2_048;
                    case "getResourcePackMaxExpandedMiB" -> 4_096;
                    case "getResourcePackMaxEntryMiB" -> 512;
                    case "getResourcePackMaxEntries" -> 100_000;
                    case "getResourcePackMaxCompressionRatio" -> 200;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        this.schedulers.add(scheduler);
        final MutableNanoClock nanoClock = new MutableNanoClock();
        final MutableMillisClock millisClock = new MutableMillisClock(initialMillis);
        return new MaintenanceFixture(new ResourcePackArchiveStore(
                tempDir, scheduler, metrics, config, nanoClock::read, millisClock::read,
                aliasHistoryMaxEntries, trustedAliasConflictMaxTombstones), metrics, millisClock, scheduler);
    }

    private static Path casFile(final Path root, final String digest, final String extension) throws IOException {
        final Path path = root.resolve("v2/raw/sha256").resolve(digest.substring(0, 2))
                .resolve(digest + extension);
        Files.createDirectories(path.getParent());
        return path;
    }

    private static Path contentCasFile(final Path root, final String digest) throws IOException {
        final Path path = root.resolve("v2/content/sha256").resolve(digest.substring(0, 2))
                .resolve(digest + ".zip");
        Files.createDirectories(path.getParent());
        return path;
    }

    private static PublishedArchive publish(final ResourcePackArchiveStore store, final byte[] archive) throws Exception {
        final ArchiveDigest digest = ArchiveDigest.compute(archive);
        try (ResourcePackArchiveStore.Claim claim = store.claim(sha256(archive))) {
            assertTrue(claim.leader());
            return new PublishedArchive(digest, store.publish(claim, archive));
        }
    }

    private static byte[] plainArchive(final ResourcePack.Key key, final String value) throws Exception {
        return zip(List.of(
                new Entry("manifest.json", bytes(manifest(key))),
                new Entry("value.txt", bytes(value))));
    }

    private static EncryptedArchive encryptedArchive(final ResourcePack.Key key) throws Exception {
        final String contentId = "test-content";
        final byte[] contentKey = bytes("0123456789abcdef0123456789abcdef");
        final byte[] fileKey = bytes("fedcba9876543210fedcba9876543210");
        final String contentsJson = "{\"content\":[{\"path\":\"secret.txt\",\"key\":\""
                + new String(fileKey, StandardCharsets.ISO_8859_1) + "\"}]}";

        final byte[] header = new byte[256];
        header[4] = (byte) 0xFC;
        header[5] = (byte) 0xB9;
        header[6] = (byte) 0xCF;
        header[7] = (byte) 0x9B;
        final byte[] contentIdBytes = bytes(contentId);
        header[16] = (byte) contentIdBytes.length;
        System.arraycopy(contentIdBytes, 0, header, 17, contentIdBytes.length);

        final ByteArrayOutputStream encryptedContents = new ByteArrayOutputStream();
        encryptedContents.write(header);
        encryptedContents.write(encrypt(contentKey, bytes(contentsJson)));
        final byte[] archive = zip(List.of(
                new Entry("manifest.json", bytes(manifest(key))),
                new Entry("contents.json", encryptedContents.toByteArray()),
                new Entry("secret.txt", encrypt(fileKey, bytes("decrypted-value")))));
        return new EncryptedArchive(archive, contentKey, contentId);
    }

    private static byte[] encrypt(final byte[] key, final byte[] data) throws Exception {
        final Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
        return cipher.doFinal(data);
    }

    private static String manifest(final ResourcePack.Key key) {
        return "{\"format_version\":2,\"header\":{\"name\":\"test\",\"description\":\"test\","
                + "\"uuid\":\"" + key.id() + "\",\"version\":[1,0,0]}}";
    }

    private static byte[] zip(final List<Entry> entries) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.path()));
                zip.write(entry.data());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] sha256(final byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static void assertNoExpansionTemps(final Path tempDir) throws IOException {
        final Path contentRoot = tempDir.resolve("v2/content/sha256");
        try (var paths = Files.list(contentRoot)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("expand-")));
        }
    }

    private static void assertNoCanonicalTemps(final Path tempDir) throws IOException {
        final Path contentRoot = tempDir.resolve("v2/content/sha256");
        try (var paths = Files.walk(contentRoot)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".zip.tmp")));
        }
    }

    private static void assertNoRawTemps(final Path tempDir) throws IOException {
        final Path rawRoot = tempDir.resolve("v2/raw/sha256");
        try (var paths = Files.walk(rawRoot)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".mcpack.tmp")));
        }
    }

    private static Throwable assertRejectedEffectiveArchive(final Fixture fixture, final Path tempDir,
                                                            final byte[] archive) throws Exception {
        final PublishedArchive published = publish(fixture.store(), archive);

        final Throwable failure = assertFutureFailure(fixture.store().loadEffective(
                published.path(), published.digest(), PACK_KEY, new byte[0], ""), IOException.class);

        assertTrue(Files.isRegularFile(published.path()));
        assertEquals(1L, countRegularFiles(tempDir.resolve("v2/raw/sha256")));
        assertEquals(0L, countRegularFiles(tempDir.resolve("v2/content/sha256")));
        assertEquals(0L, fixture.store().aliasHistorySize());
        assertEquals(0L, fixture.store().trustedAliasHistorySize());
        assertEquals(0L, fixture.metrics().getArchiveFailures());
        assertEquals(1L, fixture.metrics().getContentFailures());
        assertNoExpansionTemps(tempDir);
        return failure;
    }

    private static long countRegularFiles(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return 0L;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static long regularFileBytes(final Path root) throws IOException {
        if (!Files.exists(root)) return 0L;
        try (var paths = Files.walk(root)) {
            long bytes = 0L;
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                bytes += Files.size(path);
            }
            return bytes;
        }
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertManifestMismatch(final CompletableFuture<ResourcePack> future) throws Exception {
        final Throwable failure = assertFutureFailure(future, IllegalStateException.class);
        assertTrue(failure.getMessage().contains("does not match manifest key"));
    }

    private static <T extends Throwable> T assertFutureFailure(
            final CompletableFuture<?> future, final Class<T> expectedType) throws Exception {
        final ExecutionException thrown = assertThrows(
                ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS));
        Throwable cause = thrown;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertNotNull(cause);
        return assertInstanceOf(expectedType, cause);
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(10L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", e);
        }
    }

    private static void assertArchiveInflightEventually(final ResourcePackCacheMetrics metrics,
                                                        final long expected) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (metrics.getArchiveInflight() != expected && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        assertEquals(expected, metrics.getArchiveInflight());
    }

    private record Fixture(ResourcePackArchiveStore store, ResourcePackCacheMetrics metrics,
                           MutableNanoClock clock, ResourcePackWorkScheduler scheduler) {

        private void advanceBackoff() {
            this.clock.advanceBackoff();
        }
    }

    private record MaintenanceFixture(ResourcePackArchiveStore store, ResourcePackCacheMetrics metrics,
                                      MutableMillisClock clock, ResourcePackWorkScheduler scheduler) {
    }

    private static final class MutableNanoClock {
        private final AtomicLong nanoTime = new AtomicLong();

        private long read() {
            return this.nanoTime.get();
        }

        private void advanceBackoff() {
            this.nanoTime.addAndGet(FailureBackoff.RETRY_DELAY_NANOS);
        }
    }

    private static final class MutableMillisClock {
        private final AtomicLong millis;

        private MutableMillisClock(final long initialMillis) {
            this.millis = new AtomicLong(initialMillis);
        }

        private long read() {
            return this.millis.get();
        }

        private void advanceDays(final long days) {
            this.millis.addAndGet(TimeUnit.DAYS.toMillis(days));
        }
    }

    private record PublishedArchive(ArchiveDigest digest, Path path) {
    }

    private record EncryptedArchive(byte[] archive, byte[] contentKey, String contentId) {
    }

    private record MalformedEncryptedArchive(String name, byte[] archive, String expectedMessage) {
    }

    private record SourceRequest(String source, long announcedSize, String contentId, byte[] contentKey,
                                 byte[] archive) {
    }

    private record TrustedVariant(PackAlias alias, String sequence, byte[] contentKey) {
    }

    private record Entry(String path, byte[] data) {
    }

}
