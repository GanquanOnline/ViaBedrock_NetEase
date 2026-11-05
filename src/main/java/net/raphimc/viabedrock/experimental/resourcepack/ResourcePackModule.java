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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.resourcepack.cache.SharedPackRuntimeCache.RuntimeLease;
import net.raphimc.viabedrock.protocol.rewriter.ResourcePackRewriter;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** 注册本分支特有的资源包重写器，并处理包栈构建之后的初始化。 */
public class ResourcePackModule implements FeatureModule {

    private static final String SHARED_RUNTIME_DATA_FUTURE = "resource_pack_shared_runtime_data_future:";
    private static final String SESSION_RUNTIME_DATA_FUTURE = "resource_pack_session_runtime_data_future:";
    private static final ThreadLocal<Map<ResourcePackStorage, Set<String>>> ACTIVE_INITIALIZATIONS =
            ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<Set<CompletableFuture<Void>>> ACTIVE_INITIALIZATION_FUTURES =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    public ResourcePackModule() {
        ResourcePackRewriter.registerRewriter(new UITextureResourceRewriter());
    }

    @Override
    public void onResourcePackStackSet(final UserConnection user) {
        final ResourcePackStorage resourcePackStorage = user.get(ResourcePackStorage.class);
        if (resourcePackStorage != null) {
            ensureRuntimeData(resourcePackStorage);
        }
    }

    @Override
    public void onJavaResourcePackLoaded(final UserConnection user) {
        final ResourcePackStorage resourcePackStorage = user.get(ResourcePackStorage.class);
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        if (resourcePackStorage == null || chunkTracker == null) {
            return;
        }
        // 叠加层生成需要客户端已加载转换后的 Java 模型。运行时数据可能在此之前
        // 就已就绪，因此要等两者都完成，并切回连接线程后再触碰区块/实体状态。
        ensureRuntimeData(resourcePackStorage).whenComplete((ignored, error) -> {
            if (error != null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                        "Failed to initialize custom block overlay runtime data", error);
                return;
            }
            final io.netty.channel.Channel channel = user.getChannel();
            if (channel == null || !channel.isActive()) {
                return;
            }
            channel.eventLoop().execute(chunkTracker::rescanCustomBlockDisplays);
        });
    }

    /** 仅当所有由包栈派生的运行时数据完成初始化并发布后才完成。 */
    public static CompletableFuture<Void> ensureRuntimeData(final ResourcePackStorage resourcePackStorage) {
        final String fingerprint = resourcePackStorage.getRewriterFingerprint();
        return ensureSharedRuntimeData(resourcePackStorage, fingerprint)
                .thenCompose(ignored -> ensureSessionRuntimeData(resourcePackStorage, fingerprint));
    }

    private static CompletableFuture<Void> ensureSharedRuntimeData(
            final ResourcePackStorage resourcePackStorage, final String fingerprint) {
        final String marker = SHARED_RUNTIME_DATA_FUTURE + fingerprint;
        return initializeOnce(resourcePackStorage, marker, true, lease -> {
            if (lease != null) {
                lease.initializeRuntimeData(() -> ResourcePackRewriter.initSharedRuntimeData(resourcePackStorage));
            } else {
                ResourcePackRewriter.initSharedRuntimeData(resourcePackStorage);
            }
        });
    }

    private static CompletableFuture<Void> ensureSessionRuntimeData(
            final ResourcePackStorage resourcePackStorage, final String fingerprint) {
        final String marker = SESSION_RUNTIME_DATA_FUTURE + fingerprint;
        return initializeOnce(resourcePackStorage, marker, false,
                ignored -> ResourcePackRewriter.initSessionRuntimeData(resourcePackStorage));
    }

    private static CompletableFuture<Void> initializeOnce(
            final ResourcePackStorage resourcePackStorage, final String marker, final boolean shared,
            final java.util.function.Consumer<RuntimeLease> initializer) {
        if (isInitializationActive(resourcePackStorage, marker)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Resource pack runtime initialization cannot wait on itself"));
        }
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        final CompletableFuture<Void> existing = resourcePackStorage
                .putRuntimeInitializationIfAbsent(marker, completion, shared);
        if (existing != null) {
            if (ACTIVE_INITIALIZATION_FUTURES.get().contains(existing)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Resource pack runtime initialization cannot wait on itself"));
            }
            return existing.thenApply(ignored -> null);
        }

        final RuntimeLease retained;
        try {
            retained = resourcePackStorage.retainRuntimeLease();
        } catch (Throwable error) {
            resourcePackStorage.removeRuntimeInitialization(marker, completion, shared);
            completion.completeExceptionally(error);
            return completion.thenApply(ignored -> null);
        }
        final Runnable initialize = () -> {
            final long start = System.nanoTime();
            beginInitialization(resourcePackStorage, marker, completion);
            try {
                try {
                    resourcePackStorage.withLazyPackStack(() -> {
                        initializer.accept(retained);
                        return null;
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to materialize shared resource pack content", e);
                }
                ViaBedrock.getPlatform().getLogger().info(
                        "Initialized " + (shared ? "shared" : "session") + " resource pack runtime data in "
                                + ((System.nanoTime() - start) / 1_000_000L) + "ms (async)");
            } catch (Throwable e) {
                if (shared) {
                    resourcePackStorage.rejectSharedRuntime(e);
                }
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to initialize entity runtime data", e);
                throw e;
            } finally {
                endInitialization(resourcePackStorage, marker, completion);
            }
        };
        final CompletableFuture<Void> task;
        try {
            task = ViaBedrock.getResourcePackWorkScheduler() != null
                    ? ViaBedrock.getResourcePackWorkScheduler().runCpu(initialize)
                    : runOnPlatformWorker(initialize);
        } catch (Throwable error) {
            if (retained != null) retained.close();
            resourcePackStorage.removeRuntimeInitialization(marker, completion, shared);
            completion.completeExceptionally(error);
            return completion.thenApply(ignored -> null);
        }
        task.whenComplete((ignored, error) -> {
            if (retained != null) retained.close();
            if (error == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(error);
                resourcePackStorage.removeRuntimeInitialization(marker, completion, shared);
            }
        });
        return completion.thenApply(ignored -> null);
    }

    private static boolean isInitializationActive(
            final ResourcePackStorage storage, final String marker) {
        final Set<String> active = ACTIVE_INITIALIZATIONS.get().get(storage);
        return active != null && active.contains(marker);
    }

    private static void beginInitialization(
            final ResourcePackStorage storage, final String marker,
            final CompletableFuture<Void> completion) {
        final Set<CompletableFuture<Void>> activeFutures = ACTIVE_INITIALIZATION_FUTURES.get();
        if (!activeFutures.add(completion)) {
            throw new IllegalStateException("Resource pack runtime initialization future is already active");
        }
        final Map<ResourcePackStorage, Set<String>> activeByStorage = ACTIVE_INITIALIZATIONS.get();
        final Set<String> active = activeByStorage.computeIfAbsent(storage, ignored -> new HashSet<>());
        if (!active.add(marker)) {
            activeFutures.remove(completion);
            if (activeFutures.isEmpty()) ACTIVE_INITIALIZATION_FUTURES.remove();
            throw new IllegalStateException("Resource pack runtime initialization is already active");
        }
    }

    private static void endInitialization(
            final ResourcePackStorage storage, final String marker,
            final CompletableFuture<Void> completion) {
        final Map<ResourcePackStorage, Set<String>> activeByStorage = ACTIVE_INITIALIZATIONS.get();
        final Set<String> active = activeByStorage.get(storage);
        if (active != null && active.remove(marker) && active.isEmpty()) {
            activeByStorage.remove(storage);
        }
        if (activeByStorage.isEmpty()) ACTIVE_INITIALIZATIONS.remove();
        final Set<CompletableFuture<Void>> activeFutures = ACTIVE_INITIALIZATION_FUTURES.get();
        activeFutures.remove(completion);
        if (activeFutures.isEmpty()) ACTIVE_INITIALIZATION_FUTURES.remove();
    }

    private static CompletableFuture<Void> runOnPlatformWorker(final Runnable task) {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            Via.getPlatform().runAsync(() -> {
                try {
                    task.run();
                    future.complete(null);
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

}
