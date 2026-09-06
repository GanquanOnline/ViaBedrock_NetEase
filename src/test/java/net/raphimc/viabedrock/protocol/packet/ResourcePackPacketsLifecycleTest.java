/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArchiveDigest;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.storage.ResourcePackLoadStateTracker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackPacketsLifecycleTest {

    @Test
    void translatedJavaPackUsesStableReplacementIdentity() {
        assertEquals(UUID.fromString("f777c5a3-8ae6-4a58-a3ea-1bca4f0aa847"),
                ResourcePackPackets.JAVA_TRANSLATED_PACK_ID);
    }

    @Test
    void eventLoopRejectionRunsLeaseCleanup() {
        final AtomicInteger taskCalls = new AtomicInteger();
        final AtomicInteger cleanupCalls = new AtomicInteger();

        final RejectedExecutionException rejection = ResourcePackPackets.executeOnEventLoop(
                task -> {
                    throw new RejectedExecutionException("event loop stopped");
                }, taskCalls::incrementAndGet, cleanupCalls::incrementAndGet);

        assertNotNull(rejection);
        assertEquals(0, taskCalls.get());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    void acceptedEventLoopTaskDoesNotRunRejectionCleanup() {
        final AtomicInteger taskCalls = new AtomicInteger();
        final AtomicInteger cleanupCalls = new AtomicInteger();

        final RejectedExecutionException rejection = ResourcePackPackets.executeOnEventLoop(
                Runnable::run, taskCalls::incrementAndGet, cleanupCalls::incrementAndGet);

        assertNull(rejection);
        assertEquals(1, taskCalls.get());
        assertEquals(0, cleanupCalls.get());
    }

    @Test
    void detachedTimeoutDoesNotCancelSharedSourceAndCleansLateValue() throws Exception {
        final ScheduledThreadPoolExecutor timeoutScheduler = timeoutScheduler();
        try {
            final CompletableFuture<String> source = new CompletableFuture<>();
            final AtomicInteger timeoutCleanup = new AtomicInteger();
            final AtomicInteger lateCleanup = new AtomicInteger();
            final CompletableFuture<String> bounded = ResourcePackPackets.detachedTimeout(
                    source, 10L, TimeUnit.MILLISECONDS, timeoutCleanup::incrementAndGet,
                    ignored -> lateCleanup.incrementAndGet(), timeoutScheduler);

            final ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> bounded.get(5, TimeUnit.SECONDS));
            assertEquals(TimeoutException.class, failure.getCause().getClass());
            assertEquals(1, timeoutCleanup.get());
            assertFalse(source.isDone());
            assertFalse(source.isCancelled());

            source.complete("late");
            assertEquals(1, lateCleanup.get());
        } finally {
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void detachedTimeoutTransfersOnTimeValueWithoutCleanup() throws Exception {
        final ScheduledThreadPoolExecutor timeoutScheduler = timeoutScheduler();
        try {
            final AtomicInteger timeoutCleanup = new AtomicInteger();
            final AtomicInteger lateCleanup = new AtomicInteger();
            final CompletableFuture<String> bounded = ResourcePackPackets.detachedTimeout(
                    CompletableFuture.completedFuture("ready"), 10L, TimeUnit.MILLISECONDS,
                    timeoutCleanup::incrementAndGet, ignored -> lateCleanup.incrementAndGet(),
                    timeoutScheduler);

            assertEquals("ready", bounded.get(5, TimeUnit.SECONDS));
            Thread.sleep(30L);
            assertEquals(0, timeoutCleanup.get());
            assertEquals(0, lateCleanup.get());
            assertTrue(timeoutScheduler.getQueue().isEmpty());
        } finally {
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void detachedTimeoutCancelsLongTimerAfterSourceCompletes() throws Exception {
        final ScheduledThreadPoolExecutor timeoutScheduler = timeoutScheduler();
        try {
            final CompletableFuture<String> source = new CompletableFuture<>();
            final CompletableFuture<String> bounded = ResourcePackPackets.detachedTimeout(
                    source, 1L, TimeUnit.DAYS, () -> {
                    }, ignored -> {
                    }, timeoutScheduler);

            source.complete("ready");

            assertEquals("ready", bounded.get(5, TimeUnit.SECONDS));
            assertTrue(timeoutScheduler.getQueue().isEmpty());
        } finally {
            timeoutScheduler.shutdownNow();
        }
    }

    @Test
    void disconnectedWaiterDoesNotCancelSharedBuildAndCleansLateValue() {
        final CompletableFuture<String> source = new CompletableFuture<>();
        final CompletableFuture<Void> disconnected = new CompletableFuture<>();
        final AtomicInteger lateCleanup = new AtomicInteger();
        final CompletableFuture<String> dependent = ResourcePackPackets.detachedCancellation(
                source, disconnected, ignored -> lateCleanup.incrementAndGet());

        disconnected.completeExceptionally(new java.util.concurrent.CancellationException("disconnected"));
        assertThrows(java.util.concurrent.CancellationException.class, dependent::join);
        assertFalse(source.isDone());
        assertFalse(source.isCancelled());

        source.complete("late");
        assertEquals(1, lateCleanup.get());
    }

    @Test
    void preclosedWaiterWinsOverAlreadyAvailableLateValue() {
        final CompletableFuture<String> source = CompletableFuture.completedFuture("late");
        final CompletableFuture<Void> disconnected = CompletableFuture.failedFuture(
                new java.util.concurrent.CancellationException("disconnected"));
        final AtomicInteger lateCleanup = new AtomicInteger();

        final CompletableFuture<String> dependent = ResourcePackPackets.detachedCancellation(
                source, disconnected, ignored -> lateCleanup.incrementAndGet());

        assertThrows(java.util.concurrent.CancellationException.class, dependent::join);
        assertEquals(1, lateCleanup.get());
    }

    @Test
    void clearedClaimWaiterDetachesFromSharedArchiveFuture() {
        final ResourcePack.Key packKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
        final ResourcePackLoadStateTracker.Info info = new ResourcePackLoadStateTracker.Info(
                packKey, new byte[0], "", null);
        final CompletableFuture<Path> sharedArchive = new CompletableFuture<>();
        final CompletableFuture<Void> waiter = ResourcePackPackets.attachClaimedPackWaiter(
                new WeakReference<UserConnection>(null),
                new WeakReference<ResourcePackLoadStateTracker>(null),
                packKey, info, ArchiveDigest.compute(new byte[0]), sharedArchive, "unused");

        sharedArchive.complete(Path.of("not-opened"));

        assertDoesNotThrow(waiter::join);
    }

    @Test
    void clearedLoadTrackerStillPublishesArchiveForSharedWaiters(@TempDir final Path tempDir) throws Exception {
        final byte[] archive = "completed-after-stack".getBytes(StandardCharsets.UTF_8);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(archive);
        final ResourcePackWorkScheduler scheduler = resourcePackScheduler();
        try {
            final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
            final ResourcePackArchiveStore store = new ResourcePackArchiveStore(tempDir, scheduler, metrics);
            final ResourcePackArchiveStore.Claim leader = store.claim(digest);
            final ResourcePackArchiveStore.Claim waiter = store.claim(digest);
            final Path completed = store.createRawTemp(leader);
            Files.write(completed, archive);

            final Path published = ResourcePackPackets.publishDetachedArchive(store, leader, completed)
                    .get(10L, TimeUnit.SECONDS);

            assertEquals(published, waiter.path().get(10L, TimeUnit.SECONDS));
            assertEquals(0L, metrics.getArchiveFailures());
            waiter.close();
        } finally {
            scheduler.shutdown();
        }
    }

    private static ScheduledThreadPoolExecutor timeoutScheduler() {
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static ResourcePackWorkScheduler resourcePackScheduler() {
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ViaBedrockConfig config = (ViaBedrockConfig) Proxy.newProxyInstance(
                ViaBedrockConfig.class.getClassLoader(), new Class<?>[]{ViaBedrockConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getResourcePackCacheCpuWorkers" -> 1;
                    case "getResourcePackCacheIoWorkers" -> 1;
                    case "getResourcePackCacheQueueCapacity" -> 8;
                    default -> throw new UnsupportedOperationException(method.toString());
                });
        return new ResourcePackWorkScheduler(config, metrics);
    }

}
