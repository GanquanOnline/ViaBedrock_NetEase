/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackCacheMetrics;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackWorkScheduler;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackStorageAsyncTest {

    @Test
    void coldEmptySharedRuntimeIsConstructedOnPackWorker(@TempDir final Path tempDir) throws Exception {
        final ViaBedrockConfig config = config(tempDir);
        final ResourcePackCacheMetrics metrics = new ResourcePackCacheMetrics();
        final ResourcePackWorkScheduler scheduler = new ResourcePackWorkScheduler(config, metrics);
        final SharedPackRuntimeCache runtimeCache = new SharedPackRuntimeCache(config, metrics, scheduler);
        final CountDownLatch workerOccupied = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final CompletableFuture<Void> blocker = scheduler.runCpu(() -> {
            workerOccupied.countDown();
            await(releaseWorker);
        });
        try {
            assertTrue(workerOccupied.await(5L, TimeUnit.SECONDS));
            final Thread caller = Thread.currentThread();
            final AtomicReference<Thread> completionThread = new AtomicReference<>();

            final CompletableFuture<ResourcePackStorage> preparation = ResourcePackStorage.createAsync(
                    List.of(), List.of(), runtimeCache, scheduler);
            final CompletableFuture<Void> observed = preparation.thenAccept(
                    ignored -> completionThread.set(Thread.currentThread()));

            assertFalse(preparation.isDone());
            assertEquals(0L, metrics.getRuntimeBuilds());

            releaseWorker.countDown();
            blocker.get(5L, TimeUnit.SECONDS);
            // 先等待观察任务，避免 preparation.get() 帮助执行其尚未结束的 thenAccept 回调。
            observed.get(5L, TimeUnit.SECONDS);
            final ResourcePackStorage storage = preparation.get(10L, TimeUnit.SECONDS);
            try {
                assertNotSame(caller, completionThread.get());
                assertTrue(completionThread.get().getName().startsWith("ViaBedrock Pack CPU #"));
                assertEquals(1L, metrics.getRuntimeBuilds());
            } finally {
                storage.onRemove();
            }
        } finally {
            releaseWorker.countDown();
            scheduler.shutdown();
        }
    }

    private static ViaBedrockConfig config(final Path tempDir) throws Exception {
        final Path configPath = tempDir.resolve("viabedrock.yml");
        Files.writeString(configPath, """
                enable-server-entity-animation: false
                resource-pack-cache:
                  memory-budget-mib: 64
                  memory-hard-limit-mib: 128
                  cpu-workers: 1
                  io-workers: 1
                  queue-capacity: 8
                """);
        final ViaBedrockConfig config = new ViaBedrockConfig(
                configPath.toFile(), Logger.getAnonymousLogger());
        config.reload();
        return config;
    }

    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(5L, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", e);
        }
    }
}
