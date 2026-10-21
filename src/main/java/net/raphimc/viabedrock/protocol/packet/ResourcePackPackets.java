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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ServerboundConfigurationPackets1_21_9;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.ArchiveDigest;
import net.raphimc.viabedrock.api.resourcepack.http.RemotePackServiceClient;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.experimental.resourcepack.cache.ResourcePackArchiveStore;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PackType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ResourcePackResponse;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ResourcePackAction;
import net.raphimc.viabedrock.protocol.model.Experiment;
import net.raphimc.viabedrock.platform.ResourcePackDeliveryMode;
import net.raphimc.viabedrock.protocol.storage.ResourcePackDownloadTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackLoadStateTracker;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;

public class ResourcePackPackets {

    /** One Java connection owns one translated Bedrock stack; a later push replaces the prior stack. */
    static final UUID JAVA_TRANSLATED_PACK_ID = UUID.fromString("f777c5a3-8ae6-4a58-a3ea-1bca4f0aa847");
    private static final int RESOURCE_PACK_CHUNK_REQUEST_WINDOW = 4;
    private static final Type<byte[]> RESOURCE_PACK_CONTENT_KEY = new BoundedByteArrayType(0, 4 * 1024);
    private static final Type<byte[]> RESOURCE_PACK_HASH = new BoundedByteArrayType(32, 32);
    private static final Type<byte[]> RESOURCE_PACK_CHUNK = new BoundedByteArrayType(
            1, ResourcePackDownloadTracker.MAX_CHUNK_BYTES);

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientboundTransition(ClientboundBedrockPackets.RESOURCE_PACKS_INFO,
                ClientboundConfigurationPackets1_21_9.RESOURCE_PACK_PUSH, (PacketHandler) wrapper -> {
                    final boolean resourcePackRequired = wrapper.read(Types.BOOLEAN);
                    final boolean hasAddonPacks = wrapper.read(Types.BOOLEAN);
                    final boolean hasScripts = wrapper.read(Types.BOOLEAN);
                    final boolean forceDisableVibrantVisuals = wrapper.read(Types.BOOLEAN);
                    final UUID worldTemplateId = wrapper.read(BedrockTypes.UUID);
                    final String worldTemplateVersion = wrapper.read(BedrockTypes.STRING);
                    final ResourcePackLoadStateTracker.Info[] infos = new ResourcePackLoadStateTracker.Info[wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE)]; // resource packs size
                    for (int i = 0; i < infos.length; i++) {
                        final UUID id = wrapper.read(BedrockTypes.UUID); // pack id
                        final String version = wrapper.read(BedrockTypes.STRING); // pack version
                        final long packSize = wrapper.read(BedrockTypes.UNSIGNED_LONG_LE); // pack size
                        final byte[] contentKey = wrapper.read(RESOURCE_PACK_CONTENT_KEY); // content key
                        final String subpackNames = wrapper.read(BedrockTypes.STRING); // subpack names
                        final String contentId = wrapper.read(BedrockTypes.STRING); // content identity
                        final boolean packHasScripts = wrapper.read(Types.BOOLEAN);
                        final boolean addonPack = wrapper.read(Types.BOOLEAN);
                        final boolean rayTracingCapable = wrapper.read(Types.BOOLEAN);
                        final String cdnUrlString = wrapper.read(BedrockTypes.STRING);
                        URL cdnUrl = null;
                        try {
                            if (!cdnUrlString.isEmpty()) {
                                cdnUrl = new URL(cdnUrlString);
                            }
                        } catch (MalformedURLException ignored) {
                        }
                        infos[i] = new ResourcePackLoadStateTracker.Info(
                                new ResourcePack.Key(id, version), packSize, contentKey, contentId, subpackNames,
                                cdnUrl, cdnUrlString, packHasScripts, addonPack, rayTracingCapable);
                    }
                    final ResourcePackLoadStateTracker.AnnouncementHeader announcementHeader =
                            new ResourcePackLoadStateTracker.AnnouncementHeader(
                                    resourcePackRequired, hasAddonPacks, hasScripts, forceDisableVibrantVisuals,
                                    worldTemplateId, worldTemplateVersion);
                    final ResourcePackLoadStateTracker existingTracker =
                            wrapper.user().get(ResourcePackLoadStateTracker.class);
                    if (existingTracker != null) {
                        wrapper.cancel();
                        if (!existingTracker.hasSameAnnouncementSequence(announcementHeader, infos)) {
                            BedrockProtocol.kickForIllegalState(wrapper.user(),
                                    "Conflicting RESOURCE_PACKS_INFO received during resource pack negotiation",
                                    new IllegalStateException("Resource pack announcement sequence changed"));
                        }
                        return;
                    }
                    if (wrapper.user().has(ResourcePackStorage.class)) {
                        wrapper.cancel();
                        BedrockProtocol.kickForIllegalState(wrapper.user(),
                                "Received RESOURCE_PACKS_INFO after resource pack negotiation finished",
                                new IllegalStateException("Resource pack runtime was already published"));
                        return;
                    }
                    final ResourcePackLoadStateTracker loadStateTracker;
                    try {
                        loadStateTracker = new ResourcePackLoadStateTracker(
                                wrapper.user(), infos, announcementHeader);
                    } catch (Throwable error) {
                        wrapper.cancel();
                        BedrockProtocol.kickForIllegalState(
                                wrapper.user(), "Invalid RESOURCE_PACKS_INFO announcement", error);
                        return;
                    }
                    wrapper.user().put(loadStateTracker);

                    if (ViaBedrock.getConfig().shouldTranslateResourcePacks() && wrapper.user().getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_21_7)) {
                        if (ViaBedrock.getConfig().getResourcePackDeliveryMode()
                                == ResourcePackDeliveryMode.REMOTE) {
                            wrapper.cancel();
                            announceRemotePack(wrapper.user(), loadStateTracker);
                        } else {
                            loadStateTracker.markRemoteDeliveryNotApplicable();
                            final UUID httpToken = UUID.randomUUID();
                            loadStateTracker.setHttpToken(httpToken);
                            ViaBedrock.getResourcePackServer().addConnection(
                                    httpToken, wrapper.user().getChannel().closeFuture());
                            final String resourcePackUrl = ViaBedrock.getResourcePackServer().getUrl()
                                    + "?token=" + httpToken;
                            writeJavaPackAnnouncement(wrapper, JAVA_TRANSLATED_PACK_ID,
                                    resourcePackUrl, "");
                        }
                    } else {
                        wrapper.cancel();
                        loadStateTracker.markRemoteDeliveryNotApplicable();
                        loadStateTracker.markJavaClientDeclined();
                        finishBedrockPackDownloadsWhenReady(wrapper.user(), loadStateTracker);
                    }
                }, State.PLAY, (PacketHandler) PacketWrapper::cancel // Bedrock client ignores resource packs after the initial info packet
        );
        protocol.registerClientbound(ClientboundBedrockPackets.RESOURCE_PACK_STACK, null, wrapper -> {
            wrapper.cancel();
            final UserConnection user = wrapper.user();
            final ResourcePackLoadStateTracker loadStateTracker =
                    user.get(ResourcePackLoadStateTracker.class);
            if (loadStateTracker == null) {
                BedrockProtocol.kickForIllegalState(user,
                        "RESOURCE_PACK_STACK has no active resource pack session",
                        new IllegalStateException("RESOURCE_PACKS_INFO was not received"));
                return;
            }

            final boolean resourcePackRequired = wrapper.read(Types.BOOLEAN);
            if (ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                // NetEase / protocol 860 still serializes the behaviour-pack stack first.
                skipPackStackEntries(wrapper);
            }
            final ResourcePack.Key[] keys = new ResourcePack.Key[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // resource packs size
            final String[] subpacks = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                final UUID id = UUID.fromString(wrapper.read(BedrockTypes.STRING)); // id
                final String version = wrapper.read(BedrockTypes.STRING); // version
                subpacks[i] = wrapper.read(BedrockTypes.STRING); // subpack name
                keys[i] = new ResourcePack.Key(id, version);
            }
            final String baseGameVersion = wrapper.read(BedrockTypes.STRING);
            final Experiment[] experiments = wrapper.read(BedrockTypes.EXPERIMENT_ARRAY); // experiments
            final boolean experimentsPreviouslyToggled = wrapper.read(Types.BOOLEAN);
            final boolean includeEditorPacks = wrapper.read(Types.BOOLEAN);
            for (Experiment experiment : experiments) {
                if (experiment.enabled()) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "This server uses an experimental resource pack: " + experiment.name());
                }
            }

            try {
                if (loadStateTracker.beginResourcePackStack(
                        keys, subpacks, resourcePackRequired, baseGameVersion, experiments,
                        experimentsPreviouslyToggled, includeEditorPacks)
                        == ResourcePackLoadStateTracker.StackStart.DUPLICATE) {
                    return;
                }
            } catch (Throwable error) {
                BedrockProtocol.kickForIllegalState(
                        user, "Invalid RESOURCE_PACK_STACK transition", error);
                return;
            }
            armResourcePackNegotiationCompletion(user, loadStateTracker);

            final AtomicReference<ResourcePackStorage> acquiredStorage = new AtomicReference<>();
            final AtomicReference<Throwable> buildAbortReason = new AtomicReference<>();
            final CompletableFuture<Void> connectionClosed = new CompletableFuture<>();
            user.getChannel().closeFuture().addListener(ignored -> {
                final CancellationException cancellation = new CancellationException(
                        "Resource pack connection closed during runtime build");
                buildAbortReason.compareAndSet(null, cancellation);
                connectionClosed.completeExceptionally(cancellation);
            });
            final CompletableFuture<ResourcePackStorage> build = loadStateTracker
                    .loadRequestedResourcePacks()
                    .thenCombine(loadStateTracker.requireRemoteDeliveryDecision(),
                            (ignored, remoteOutcome) -> null)
                    .thenCompose(ignored -> loadStateTracker.prepareResourcePackStackAsync(keys, subpacks))
                    .thenCompose(preparedStack -> ResourcePackStorage.createAsync(
                            preparedStack.resourcePacksTopToBottom(),
                            preparedStack.selectedSubpacksTopToBottom()))
                    .thenCompose(resourcePackStorage -> {
                        acquiredStorage.set(resourcePackStorage);
                        final Throwable abortReason = buildAbortReason.get();
                        if (abortReason != null) {
                            cleanupAcquiredStorage(acquiredStorage, resourcePackStorage);
                            return CompletableFuture.failedFuture(abortReason);
                        }
                        return ResourcePackModule.ensureRuntimeData(resourcePackStorage)
                                .thenApply(ignored -> resourcePackStorage);
                    }).whenComplete((resourcePackStorage, error) -> {
                        if (error != null) {
                            cleanupAcquiredStorage(acquiredStorage, acquiredStorage.get());
                        }
                    });
            final CompletableFuture<ResourcePackStorage> connectedBuild = detachedCancellation(
                    build, connectionClosed,
                    storage -> cleanupAcquiredStorage(acquiredStorage, storage));
            detachedTimeout(connectedBuild,
                    ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds(), TimeUnit.SECONDS,
                    () -> buildAbortReason.compareAndSet(null,
                            new TimeoutException("Resource pack runtime build timed out")),
                    storage -> cleanupAcquiredStorage(acquiredStorage, storage),
                    user.getChannel().eventLoop())
                    .whenComplete((resourcePackStorage, error) -> {
                        if (error == null) acquiredStorage.compareAndSet(resourcePackStorage, null);
                        finishResourcePackStackBuild(
                                user, loadStateTracker, resourcePackStorage, error);
                    });
        });
        protocol.registerClientbound(ClientboundBedrockPackets.RESOURCE_PACK_DATA_INFO, null, wrapper -> {
            wrapper.cancel();
            final String key = wrapper.read(BedrockTypes.STRING); // resource name
            final long chunkSize = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // chunk size
            wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // announced number of chunks
            final long size = wrapper.read(BedrockTypes.UNSIGNED_LONG_LE); // file size
            final byte[] hash = wrapper.read(RESOURCE_PACK_HASH); // file hash
            final boolean premium = wrapper.read(Types.BOOLEAN); // is premium pack
            final PackType type = PackType.getByValue(wrapper.read(Types.UNSIGNED_BYTE), PackType.Invalid); // pack type

            final ResourcePackLoadStateTracker loadStateTracker = wrapper.user().get(ResourcePackLoadStateTracker.class);
            final ResourcePackDownloadTracker downloadTracker =
                    wrapper.user().get(ResourcePackDownloadTracker.class);
            final boolean sharedCacheEnabled = ViaBedrock.isSharedResourcePackCacheEnabled();
            final ResourcePack.Key packKey;
            final ResourcePackLoadStateTracker.Info info;
            try {
                ResourcePackDownloadTracker.validateMetadata(size, chunkSize, hash);
                if (sharedCacheEnabled && isCountedTransferPack(type)) {
                    if (loadStateTracker == null) {
                        throw new IllegalStateException("Resource pack transfer has no active announcement tracker");
                    }
                    final ResourcePackLoadStateTracker.ResolvedRequest resolved =
                            loadStateTracker.resolveTransferRequest(key);
                    packKey = resolved.key();
                    info = resolved.info();
                } else {
                    packKey = null;
                    info = null;
                }
                if (info != null && info.announcedSize() >= 0L && info.announcedSize() != size) {
                    throw new IllegalStateException("Resource pack size changed during negotiation: "
                            + size + " != " + info.announcedSize());
                }
                if (downloadTracker == null) {
                    throw new IllegalStateException("Resource pack transfer has no download tracker");
                }
                if (downloadTracker.registerTransfer(
                        key, packKey, size, chunkSize, hash, premium, type)
                        == ResourcePackDownloadTracker.TransferRegistration.DUPLICATE) {
                    return;
                }
            } catch (Throwable e) {
                BedrockProtocol.kickForIllegalState(wrapper.user(), "Invalid server resource pack metadata", e);
                return;
            }
            final ResourcePackArchiveStore.Claim archiveClaim = shouldClaimRawArchive(
                    sharedCacheEnabled, type, info)
                    ? ViaBedrock.getResourcePackArchiveStore().claim(hash) : null;
            if (archiveClaim != null && archiveClaim.leader()) {
                tryLegacyPackBeforeChunks(wrapper.user(), loadStateTracker, key, packKey, info,
                        size, chunkSize, hash, premium, type, archiveClaim);
                return;
            }
            if (archiveClaim != null && archiveClaim.path().isDone()
                    && !archiveClaim.path().isCompletedExceptionally()) {
                // Raw CAS hit: attach the shared archive immediately, but still run this
                // connection's protocol transfer. The Downloading response listed every
                // announced pack, and sequential senders (e.g. WaterdogPE) only advance to
                // the next DATA_INFO after the current pack's chunk requests complete.
                loadClaimedPack(wrapper.user(), loadStateTracker, packKey, info, archiveClaim);
                startResourcePackDownload(wrapper.user(), key, packKey,
                        size, chunkSize, hash, premium, type, null);
                return;
            }
            if (archiveClaim != null) {
                loadClaimedPackOrTakeOver(wrapper.user(), loadStateTracker, key, packKey, info,
                        size, chunkSize, hash, premium, type, archiveClaim);
                return;
            }
            startResourcePackDownload(wrapper.user(), key, packKey,
                    size, chunkSize, hash, premium, type, null);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.RESOURCE_PACK_CHUNK_DATA, null, wrapper -> {
            wrapper.cancel();
            final String key = wrapper.read(BedrockTypes.STRING); // resource name
            final long chunk = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // chunk id
            final long byteOffset = wrapper.read(BedrockTypes.UNSIGNED_LONG_LE); // byte offset
            final byte[] data = wrapper.read(RESOURCE_PACK_CHUNK); // chunk data

            final UserConnection user = wrapper.user();
            final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
            final ResourcePackDownloadTracker.Download download = downloadTracker.get(key);
            if (download != null) {
                download.processDataChunkAsync(
                                ViaBedrock.getResourcePackWorkScheduler(), chunk, byteOffset, data)
                        .whenComplete((chunkResult, error) -> user.getChannel().eventLoop().execute(
                                () -> handleResourcePackChunkWrite(
                                        user, key, download, chunkResult, error)));
            } else {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received RESOURCE_PACK_CHUNK_DATA for unknown pack: " + key);
            }
        });

        protocol.registerServerboundTransition(ServerboundConfigurationPackets1_21_9.RESOURCE_PACK, ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, wrapper -> {
            wrapper.read(Types.UUID); // id
            final ResourcePackAction action = ResourcePackAction.values()[wrapper.read(Types.VAR_INT)]; // action
            final ResourcePackLoadStateTracker loadStateTracker =
                    wrapper.user().get(ResourcePackLoadStateTracker.class);
            if (loadStateTracker == null) {
                wrapper.cancel();
                final ResourcePackStorage completedStorage =
                        wrapper.user().get(ResourcePackStorage.class);
                if (completedStorage != null) {
                    if (action == ResourcePackAction.SUCCESSFULLY_LOADED) {
                        completedStorage.setLoadedOnJavaClient();
                        ExperimentalFeatures.dispatchJavaResourcePackLoaded(wrapper.user());
                    }
                    ViaBedrock.getPlatform().getLogger().fine(
                            "Ignoring late Java resource pack action after session completion: " + action);
                    return;
                }
                BedrockProtocol.kickForIllegalState(wrapper.user(),
                        "Java resource pack response has no active resource pack session",
                        new IllegalStateException("Unexpected Java resource pack action " + action));
                return;
            }
            switch (action) {
                case SUCCESSFULLY_LOADED -> {
                    wrapper.cancel();
                    try {
                        loadStateTracker.markJavaClientLoaded();
                    } catch (Throwable error) {
                        BedrockProtocol.kickForIllegalState(
                                wrapper.user(), "Invalid Java resource pack state transition", error);
                        return;
                    }
                    final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
                    if (resourcePackStorage != null) {
                        resourcePackStorage.setLoadedOnJavaClient();
                    }
                    ExperimentalFeatures.dispatchJavaResourcePackLoaded(wrapper.user());
                }
                case FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED -> {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Client resource pack download/load failed");
                    wrapper.cancel();
                    try {
                        loadStateTracker.markJavaClientFailed();
                    } catch (Throwable error) {
                        BedrockProtocol.kickForIllegalState(
                                wrapper.user(), "Invalid Java resource pack state transition", error);
                        return;
                    }
                    cancelRemotePackDelivery(loadStateTracker);
                    finishBedrockPackDownloadsWhenReady(wrapper.user(), loadStateTracker);
                }
                case DECLINED, INVALID_URL -> {
                    wrapper.cancel();
                    try {
                        loadStateTracker.markJavaClientDeclined();
                    } catch (Throwable error) {
                        BedrockProtocol.kickForIllegalState(
                                wrapper.user(), "Invalid Java resource pack state transition", error);
                        return;
                    }
                    cancelRemotePackDelivery(loadStateTracker);
                    finishBedrockPackDownloadsWhenReady(wrapper.user(), loadStateTracker);
                }
                case ACCEPTED -> {
                    wrapper.cancel();
                    try {
                        loadStateTracker.markJavaClientAccepted();
                    } catch (Throwable error) {
                        BedrockProtocol.kickForIllegalState(
                                wrapper.user(), "Invalid Java resource pack state transition", error);
                        return;
                    }
                    finishBedrockPackDownloadsWhenReady(wrapper.user(), loadStateTracker);
                }
                case DOWNLOADED -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled ResourcePackAction: " + action);
            }
        });
    }

    private static void finishResourcePackStackBuild(final UserConnection user,
                                                     final ResourcePackLoadStateTracker loadStateTracker,
                                                     final ResourcePackStorage resourcePackStorage,
                                                     final Throwable buildError) {
        final AtomicBoolean storageAccountedFor = new AtomicBoolean();
        if (resourcePackStorage != null) {
            user.getChannel().closeFuture().addListener(ignored -> {
                if (storageAccountedFor.compareAndSet(false, true)) {
                    resourcePackStorage.onRemove();
                }
            });
        }

        final Runnable publish = () -> {
            if (buildError != null) {
                if (resourcePackStorage != null
                        && storageAccountedFor.compareAndSet(false, true)) {
                    resourcePackStorage.onRemove();
                }
                loadStateTracker.failResourcePackStack(buildError);
                failPackDelivery(loadStateTracker, buildError);
                if (user.getChannel().isActive()) {
                    BedrockProtocol.kickForIllegalState(
                            user, "Failed to build the shared resource pack runtime", buildError);
                }
                return;
            }
            if (!user.getChannel().isActive()
                    || user.get(ResourcePackLoadStateTracker.class) != loadStateTracker) {
                if (storageAccountedFor.compareAndSet(false, true)) {
                    resourcePackStorage.onRemove();
                }
                final CancellationException cancellation = new CancellationException(
                        "Resource pack session ended before runtime publication");
                loadStateTracker.failResourcePackStack(cancellation);
                failPackDelivery(loadStateTracker, cancellation);
                return;
            }

            try {
                resourcePackStorage.setSupportsFreeRotation(user.getProtocolInfo().protocolVersion()
                        .newerThanOrEqualTo(ProtocolVersion.v1_21_11));
                user.put(resourcePackStorage);
                storageAccountedFor.set(true);
                if (loadStateTracker.javaPackPhase()
                        == ResourcePackLoadStateTracker.JavaPackPhase.LOADED) {
                    resourcePackStorage.setLoadedOnJavaClient();
                    ExperimentalFeatures.dispatchJavaResourcePackLoaded(user);
                }
                if (ViaBedrock.getResourcePackServer() != null && loadStateTracker.httpToken() != null) {
                    ViaBedrock.getResourcePackServer().completeConnection(
                            loadStateTracker.httpToken(), resourcePackStorage);
                } else if (ViaBedrock.getRemotePackServiceClient() != null
                        && loadStateTracker.shouldPublishRemotePack()) {
                    final RemotePackServiceClient remotePackServiceClient =
                            ViaBedrock.getRemotePackServiceClient();
                    remotePackServiceClient
                            .publish(loadStateTracker.remotePackLookup(), resourcePackStorage)
                            .whenComplete((ignored, uploadError) -> {
                                if (uploadError != null) {
                                    cancelRemotePackDelivery(loadStateTracker);
                                    if (user.getChannel().isActive()) {
                                        BedrockProtocol.kickForIllegalState(
                                                user, "Failed to publish the Java resource pack", uploadError);
                                    }
                                }
                            });
                }
                ExperimentalFeatures.dispatchResourcePackStackSet(user);
                loadStateTracker.completeResourcePackStack(resourcePackStorage);
            } catch (Throwable publishError) {
                if (user.get(ResourcePackStorage.class) == resourcePackStorage) {
                    user.remove(ResourcePackStorage.class);
                } else if (storageAccountedFor.compareAndSet(false, true)) {
                    resourcePackStorage.onRemove();
                }
                loadStateTracker.failResourcePackStack(publishError);
                failPackDelivery(loadStateTracker, publishError);
                BedrockProtocol.kickForIllegalState(
                        user, "Failed to publish the shared resource pack runtime", publishError);
            }
        };
        final RejectedExecutionException rejection = executeOnEventLoop(
                user.getChannel().eventLoop(), publish,
                () -> {
                    if (resourcePackStorage != null
                            && storageAccountedFor.compareAndSet(false, true)) {
                        resourcePackStorage.onRemove();
                    }
                });
        if (rejection != null) {
            loadStateTracker.failResourcePackStack(rejection);
            failPackDelivery(loadStateTracker, rejection);
            BedrockProtocol.kickForIllegalState(
                    user, "Failed to publish the shared resource pack runtime", rejection);
        }
    }

    static RejectedExecutionException executeOnEventLoop(final Executor eventLoop, final Runnable task,
                                                         final Runnable rejectedCleanup) {
        try {
            eventLoop.execute(task);
            return null;
        } catch (RejectedExecutionException e) {
            try {
                rejectedCleanup.run();
            } catch (Throwable cleanupError) {
                e.addSuppressed(cleanupError);
            }
            return e;
        }
    }

    static <T> CompletableFuture<T> detachedTimeout(final CompletionStage<T> source, final long timeout,
                                                    final TimeUnit unit, final Runnable timeoutCleanup,
                                                    final Consumer<T> lateValueCleanup,
                                                    final ScheduledExecutorService timeoutScheduler) {
        final CompletableFuture<T> bounded = new CompletableFuture<>();
        final AtomicBoolean decided = new AtomicBoolean();
        final AtomicReference<ScheduledFuture<?>> timeoutTask = new AtomicReference<>();
        source.whenComplete((value, error) -> {
            if (error != null) {
                if (decided.compareAndSet(false, true)) {
                    bounded.completeExceptionally(error);
                }
            } else if (decided.compareAndSet(false, true)) {
                bounded.complete(value);
            } else {
                lateValueCleanup.accept(value);
            }
            final ScheduledFuture<?> scheduled = timeoutTask.get();
            if (scheduled != null) scheduled.cancel(false);
        });
        try {
            final ScheduledFuture<?> scheduled = timeoutScheduler.schedule(() -> {
                if (decided.compareAndSet(false, true)) {
                    final TimeoutException failure = new TimeoutException(
                            "Resource pack build exceeded " + timeout + ' ' + unit.name().toLowerCase());
                    try {
                        timeoutCleanup.run();
                    } catch (Throwable cleanupError) {
                        failure.addSuppressed(cleanupError);
                    }
                    bounded.completeExceptionally(failure);
                }
            }, timeout, unit);
            timeoutTask.set(scheduled);
            if (bounded.isDone()) scheduled.cancel(false);
        } catch (RejectedExecutionException e) {
            if (decided.compareAndSet(false, true)) {
                try {
                    timeoutCleanup.run();
                } catch (Throwable cleanupError) {
                    e.addSuppressed(cleanupError);
                }
                bounded.completeExceptionally(e);
            }
        }
        return bounded;
    }

    static boolean shouldClaimRawArchive(final boolean sharedCacheEnabled, final PackType type,
                                         final ResourcePackLoadStateTracker.Info info) {
        return sharedCacheEnabled && isCountedTransferPack(type) && info != null;
    }

    static boolean isCountedTransferPack(final PackType type) {
        return type == PackType.Resources || type == PackType.Behavior;
    }

    private static void skipPackStackEntries(final PacketWrapper wrapper) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < count; i++) {
            wrapper.read(BedrockTypes.STRING); // id
            wrapper.read(BedrockTypes.STRING); // version
            wrapper.read(BedrockTypes.STRING); // subpack name
        }
        if (count > 0) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO,
                    "Skipped " + count + " NetEase behaviour pack stack entries");
        }
    }

    static <T> CompletableFuture<T> detachedCancellation(final CompletionStage<T> source,
                                                         final CompletionStage<?> cancellation,
                                                         final Consumer<T> lateValueCleanup) {
        final CompletableFuture<T> dependent = new CompletableFuture<>();
        cancellation.whenComplete((ignored, error) -> dependent.completeExceptionally(
                error != null ? error : new CancellationException("Resource pack build waiter cancelled")));
        source.whenComplete((value, error) -> {
            if (error != null) {
                dependent.completeExceptionally(error);
            } else if (!dependent.complete(value)) {
                lateValueCleanup.accept(value);
            }
        });
        return dependent;
    }

    private static void cleanupStorage(final ResourcePackStorage storage) {
        if (storage != null) storage.onRemove();
    }

    private static void cleanupAcquiredStorage(final AtomicReference<ResourcePackStorage> acquiredStorage,
                                               final ResourcePackStorage storage) {
        if (storage != null && acquiredStorage.compareAndSet(storage, null)) {
            cleanupStorage(storage);
        }
    }

    private static void failPackDelivery(final ResourcePackLoadStateTracker loadStateTracker,
                                         final Throwable error) {
        if (ViaBedrock.getResourcePackServer() != null && loadStateTracker.httpToken() != null) {
            ViaBedrock.getResourcePackServer().failConnection(loadStateTracker.httpToken(), error);
        } else if (ViaBedrock.getRemotePackServiceClient() != null) {
            cancelRemotePackDelivery(loadStateTracker);
        }
    }

    static void cancelRemotePackDelivery(final ResourcePackLoadStateTracker loadStateTracker) {
        if (loadStateTracker == null || ViaBedrock.getRemotePackServiceClient() == null) return;
        final CompletableFuture<RemotePackServiceClient.Lookup> lookupFuture =
                loadStateTracker.claimRemotePackCancellationFuture();
        if (lookupFuture == null) return;
        lookupFuture.whenComplete((lookup, error) -> {
            if (error == null && lookup != null) {
                ViaBedrock.getRemotePackServiceClient().cancel(lookup);
            }
        });
    }

    private static void announceRemotePack(final UserConnection user,
                                           final ResourcePackLoadStateTracker loadStateTracker) {
        final RemotePackServiceClient client = ViaBedrock.getRemotePackServiceClient();
        if (client == null) {
            final IllegalStateException error =
                    new IllegalStateException("Remote resource pack client is not initialized");
            loadStateTracker.failRemoteDelivery(error);
            BedrockProtocol.kickForIllegalState(
                    user, "Remote resource pack service is unavailable", error);
            return;
        }
        final boolean supportsFreeRotation = user.getProtocolInfo().protocolVersion()
                .newerThanOrEqualTo(ProtocolVersion.v1_21_11);
        final String lookupKey = RemotePackServiceClient.computeLookupKey(
                loadStateTracker.announcementSequenceFingerprint(), supportsFreeRotation);
        final CompletableFuture<RemotePackServiceClient.Lookup> lookupFuture =
                loadStateTracker.startRemotePackLookup(() -> client.lookup(lookupKey));
        user.getChannel().closeFuture().addListener(ignored ->
                cancelRemotePackDelivery(loadStateTracker));
        lookupFuture.whenComplete((lookup, error) -> {
            final Runnable announce = () -> {
                if (!user.getChannel().isActive()
                        || user.get(ResourcePackLoadStateTracker.class) != loadStateTracker) {
                    cancelRemotePackDelivery(loadStateTracker);
                    return;
                }
                if (error != null) {
                    BedrockProtocol.kickForIllegalState(
                            user, "Failed to query the Java resource pack service", error);
                    return;
                }
                try {
                    final PacketWrapper resourcePack = PacketWrapper.create(
                            ClientboundConfigurationPackets1_21_9.RESOURCE_PACK_PUSH, user);
                    writeJavaPackAnnouncement(resourcePack, JAVA_TRANSLATED_PACK_ID, lookup.publicUrl(),
                            lookup.ready() ? lookup.sha1() : "");
                    resourcePack.send(BedrockProtocol.class);
                } catch (Throwable sendError) {
                    cancelRemotePackDelivery(loadStateTracker);
                    BedrockProtocol.kickForIllegalState(
                            user, "Failed to announce the Java resource pack", sendError);
                }
            };
            final RejectedExecutionException rejection = executeOnEventLoop(
                    user.getChannel().eventLoop(), announce,
                    () -> cancelRemotePackDelivery(loadStateTracker));
            if (rejection != null && user.getChannel().isActive()) {
                BedrockProtocol.kickForIllegalState(
                        user, "Failed to schedule the Java resource pack announcement", rejection);
            }
        });
    }

    private static void writeJavaPackAnnouncement(final PacketWrapper wrapper, final UUID id,
                                                  final String url, final String hash) {
        wrapper.write(Types.UUID, id);
        wrapper.write(Types.STRING, url);
        wrapper.write(Types.STRING, hash);
        wrapper.write(Types.BOOLEAN, true);
        wrapper.write(Types.OPTIONAL_TAG, TextUtil.stringToNbt(
                "\n§aThe resource packs will be downloaded and converted to the Java Edition format. "
                        + "This may take a while, depending on your internet connection and the size of the packs. "
                        + "This pack is required to join the server."));
    }

    private static void finishBedrockPackDownloadsWhenReady(
            final UserConnection user, final ResourcePackLoadStateTracker loadStateTracker) {
        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        loadStateTracker.loadRequestedResourcePacks().whenComplete((ignored, error) -> {
            final UserConnection liveUser = userReference.get();
            if (!isConnectionActive(liveUser)
                    || liveUser.get(ResourcePackLoadStateTracker.class) != loadStateTracker) {
                return;
            }
            final Runnable finish = () -> {
                if (!isConnectionActive(liveUser)
                        || liveUser.get(ResourcePackLoadStateTracker.class) != loadStateTracker) {
                    return;
                }
                if (error != null) {
                    BedrockProtocol.kickForIllegalState(liveUser,
                            "One of the server resource packs failed to load. Try again later or decline the resource packs.",
                            error);
                    return;
                }
                if (!loadStateTracker.claimBedrockDownloadsFinished()) return;
                try {
                    final PacketWrapper resourcePackClientResponse = PacketWrapper.create(
                            ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, liveUser);
                    resourcePackClientResponse.write(Types.BYTE,
                            (byte) ResourcePackResponse.DownloadingFinished.getValue()); // status
                    resourcePackClientResponse.write(
                            BedrockTypes.SHORT_LE_STRING_ARRAY, new String[0]); // downloading packs
                    resourcePackClientResponse.sendToServer(BedrockProtocol.class);
                } catch (Throwable sendError) {
                    BedrockProtocol.kickForIllegalState(liveUser,
                            "Failed to finish server resource pack downloads", sendError);
                }
            };
            final RejectedExecutionException rejection = executeOnEventLoop(
                    liveUser.getChannel().eventLoop(), finish, () -> {
                    });
            if (rejection != null && liveUser.getChannel().isActive()) {
                BedrockProtocol.kickForIllegalState(
                        liveUser, "Failed to schedule server resource pack completion", rejection);
            }
        });
    }

    private static void armResourcePackNegotiationCompletion(
            final UserConnection user, final ResourcePackLoadStateTracker loadStateTracker) {
        loadStateTracker.negotiationReadyFuture().whenComplete((resourcePackStorage, error) -> {
            if (error != null) return;
            final Runnable finish = () -> {
                if (!isConnectionActive(user)
                        || user.get(ResourcePackLoadStateTracker.class) != loadStateTracker
                        || user.get(ResourcePackStorage.class) != resourcePackStorage) {
                    return;
                }
                user.get(CustomMappingSyncStorage.class).delayResourcePackStackFinishedIfNeeded(() -> {
                    if (isConnectionActive(user)
                            && user.get(ResourcePackLoadStateTracker.class) == loadStateTracker
                            && loadStateTracker.claimResourcePackStackFinished()) {
                        sendResourcePackStackFinished(user);
                    }
                });
            };
            final RejectedExecutionException rejection = executeOnEventLoop(
                    user.getChannel().eventLoop(), finish, () -> {
                    });
            if (rejection != null && user.getChannel().isActive()) {
                BedrockProtocol.kickForIllegalState(
                        user, "Failed to finish resource pack negotiation", rejection);
            }
        });
    }

    private static void requestResourcePackChunks(final UserConnection user, final String key,
                                                  final ResourcePackDownloadTracker.Download download,
                                                  final int maximumRequests) {
        for (int i = 0; i < maximumRequests; i++) {
            final long chunk = download.claimNextChunkRequest();
            if (chunk < 0L) {
                return;
            }
            final PacketWrapper request = PacketWrapper.create(
                    ServerboundBedrockPackets.RESOURCE_PACK_CHUNK_REQUEST, user);
            request.write(BedrockTypes.STRING, key); // resource name
            request.write(BedrockTypes.UNSIGNED_INT_LE, chunk); // chunk
            try {
                request.sendToServer(BedrockProtocol.class);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to send resource pack chunk request", e);
            }
        }
    }

    private static void tryLegacyPackBeforeChunks(final UserConnection user,
                                                  final ResourcePackLoadStateTracker loadStateTracker,
                                                  final String key, final ResourcePack.Key packKey,
                                                  final ResourcePackLoadStateTracker.Info info,
                                                  final long size, final long chunkSize, final byte[] hash,
                                                  final boolean premium, final PackType type,
                                                  final ResourcePackArchiveStore.Claim claim) {
        final ResourcePackArchiveStore store = ViaBedrock.getResourcePackArchiveStore();
        final CompletableFuture<Boolean> importFuture = store.tryImportLegacy(claim, packKey);
        final CompletableFuture<Void> connectionStage = new CompletableFuture<>();
        connectionStage.whenComplete((ignored, error) -> {
            if (connectionStage.isCancelled()) {
                abandonClaimIfOpen(store, claim,
                        new CancellationException("Resource pack connection closed during legacy CAS import"));
            }
        });
        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                new WeakReference<>(loadStateTracker);
        importFuture.whenComplete((imported, importError) -> {
            if (connectionStage.isCancelled()) {
                return;
            }
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveLoadTracker = loadTrackerReference.get();
            if (!isConnectionActive(liveUser) || liveLoadTracker == null) {
                abandonClaimIfOpen(store, claim,
                        new CancellationException("Resource pack connection closed before legacy CAS import completed"));
                connectionStage.complete(null);
                return;
            }
            final Runnable continuation = () -> {
                if (!isConnectionActive(liveUser)
                        || liveUser.get(ResourcePackLoadStateTracker.class) != liveLoadTracker) {
                    abandonClaimIfOpen(store, claim,
                            new CancellationException("Resource pack connection closed before legacy CAS import completed"));
                    connectionStage.complete(null);
                    return;
                }
                if (Boolean.TRUE.equals(imported)) {
                    loadClaimedPack(liveUser, liveLoadTracker, packKey, info, claim);
                } else if (claim.path().isCompletedExceptionally()) {
                    final Throwable failure = importError != null ? importError
                            : new IllegalStateException("Legacy resource pack CAS import failed");
                    BedrockProtocol.kickForIllegalState(
                            liveUser, "Failed to import a legacy server resource pack", failure);
                } else {
                    startResourcePackDownload(
                            liveUser, key, packKey, size, chunkSize, hash, premium, type, claim);
                }
                connectionStage.complete(null);
            };
            final RejectedExecutionException rejection = executeOnEventLoop(
                    liveUser.getChannel().eventLoop(), continuation,
                    () -> abandonClaimIfOpen(store, claim,
                            new CancellationException("Resource pack event loop stopped during legacy CAS import")));
            if (rejection != null) {
                connectionStage.completeExceptionally(rejection);
            }
        });
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker != null) {
            downloadTracker.trackConnectionStage(connectionStage);
        } else {
            connectionStage.cancel(false);
        }
    }

    private static void startResourcePackDownload(final UserConnection user, final String key,
                                                  final ResourcePack.Key packKey,
                                                  final long size, final long chunkSize, final byte[] hash,
                                                  final boolean premium, final PackType type,
                                                  final ResourcePackArchiveStore.Claim archiveClaim) {
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker == null || !isConnectionActive(user)) {
            if (archiveClaim != null) {
                abandonClaimIfOpen(ViaBedrock.getResourcePackArchiveStore(), archiveClaim,
                        new CancellationException("Resource pack connection closed before download started"));
            }
            return;
        }
        final ResourcePackDownloadTracker.Download download;
        try {
            download = downloadTracker.add(
                    key, packKey, size, chunkSize, hash, premium, type, archiveClaim);
        } catch (Throwable e) {
            if (archiveClaim != null && archiveClaim.leader()) {
                if (e instanceof CancellationException || !isConnectionActive(user)) {
                    ViaBedrock.getResourcePackArchiveStore().abandon(archiveClaim, e);
                } else {
                    ViaBedrock.getResourcePackArchiveStore().fail(archiveClaim, e);
                }
            }
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, "Failed to start a server resource pack download", e);
            }
            return;
        }
        try {
            requestResourcePackChunks(user, key, download, RESOURCE_PACK_CHUNK_REQUEST_WINDOW);
        } catch (Throwable e) {
            downloadTracker.cancel(key, e);
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, "Failed to request a server resource pack", e);
            }
        }
    }

    private static void abandonClaimIfOpen(final ResourcePackArchiveStore store,
                                           final ResourcePackArchiveStore.Claim claim,
                                           final Throwable reason) {
        if (!claim.path().isDone()) {
            store.abandon(claim, reason);
        } else {
            claim.close();
        }
    }

    private static void loadClaimedPack(final UserConnection user, final ResourcePackLoadStateTracker loadStateTracker,
                                        final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
                                        final ResourcePackArchiveStore.Claim claim) {
        attachClaimedPack(user, loadStateTracker, packKey, info, claim, claim.path(),
                "Failed to load a server resource pack");
    }

    private static void loadClaimedPackOrTakeOver(
            final UserConnection user, final ResourcePackLoadStateTracker loadStateTracker,
            final String key, final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
            final long size, final long chunkSize, final byte[] hash, final boolean premium,
            final PackType type, final ResourcePackArchiveStore.Claim claim) {
        loadClaimedPack(user, loadStateTracker, packKey, info, claim);
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker == null) {
            abandonClaimIfOpen(ViaBedrock.getResourcePackArchiveStore(), claim,
                    new CancellationException("Resource pack connection has no download tracker"));
            return;
        }

        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                new WeakReference<>(loadStateTracker);
        final WeakReference<ResourcePackDownloadTracker> downloadTrackerReference =
                new WeakReference<>(downloadTracker);
        downloadTracker.trackArchiveClaim(claim).whenComplete((promoted, error) -> {
            if (error != null || !Boolean.TRUE.equals(promoted)) {
                return;
            }
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveLoadTracker = loadTrackerReference.get();
            final ResourcePackDownloadTracker liveDownloadTracker = downloadTrackerReference.get();
            final ResourcePackArchiveStore store = ViaBedrock.getResourcePackArchiveStore();
            if (!isConnectionActive(liveUser) || liveLoadTracker == null || liveDownloadTracker == null) {
                abandonClaimIfOpen(store, claim,
                        new CancellationException("Promoted resource pack claimant disconnected"));
                return;
            }

            final Runnable takeOver = () -> {
                if (!isConnectionActive(liveUser)
                        || liveUser.get(ResourcePackLoadStateTracker.class) != liveLoadTracker
                        || liveUser.get(ResourcePackDownloadTracker.class) != liveDownloadTracker
                        || !claim.leader()) {
                    abandonClaimIfOpen(store, claim,
                            new CancellationException("Promoted resource pack claimant is no longer active"));
                    return;
                }
                tryLegacyPackBeforeChunks(liveUser, liveLoadTracker, key, packKey, info,
                        size, chunkSize, hash, premium, type, claim);
            };
            executeOnEventLoop(liveUser.getChannel().eventLoop(), takeOver,
                    () -> abandonClaimIfOpen(store, claim,
                            new CancellationException("Promoted resource pack claimant event loop stopped")));
        });
    }

    private static void publishClaimedPack(final UserConnection user,
                                           final ResourcePackLoadStateTracker loadStateTracker,
                                           final ResourcePack.Key packKey,
                                           final ResourcePackLoadStateTracker.Info info,
                                           final ResourcePackArchiveStore.Claim claim, final Path archive) {
        attachClaimedPack(user, loadStateTracker, packKey, info, claim,
                ViaBedrock.getResourcePackArchiveStore().publishAsync(claim, archive),
                "Failed to store a server resource pack");
    }

    private static void attachClaimedPack(final UserConnection user,
                                          final ResourcePackLoadStateTracker loadStateTracker,
                                          final ResourcePack.Key packKey,
                                          final ResourcePackLoadStateTracker.Info info,
                                          final ResourcePackArchiveStore.Claim claim,
                                          final CompletableFuture<Path> archiveFuture,
                                          final String failureMessage) {
        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                new WeakReference<>(loadStateTracker);
        final CompletableFuture<Void> stage = attachClaimedPackWaiter(
                userReference, loadTrackerReference, packKey, info, claim, archiveFuture, failureMessage);
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker != null) {
            downloadTracker.trackConnectionStage(stage);
        } else {
            stage.cancel(false);
        }
    }

    static CompletableFuture<Void> attachClaimedPackWaiter(
            final WeakReference<UserConnection> userReference,
            final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference,
            final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
            final ResourcePackArchiveStore.Claim claim,
            final CompletableFuture<Path> archiveFuture, final String failureMessage) {
        final CompletableFuture<Void> stage = archiveFuture.thenCompose(path -> {
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveTracker = loadTrackerReference.get();
            if (!isConnectionActive(liveUser) || liveTracker == null
                    || liveUser.get(ResourcePackLoadStateTracker.class) != liveTracker) {
                claim.close();
                return CompletableFuture.completedFuture(null);
            }
            return ViaBedrock.getResourcePackArchiveStore().loadEffective(
                            claim, liveTracker.getAlias(packKey),
                            liveTracker.announcementSequenceFingerprint(), info.contentKey())
                    .thenAccept(pack -> {
                        final UserConnection activeUser = userReference.get();
                        final ResourcePackLoadStateTracker activeTracker = loadTrackerReference.get();
                        if (isConnectionActive(activeUser) && activeTracker != null
                                && activeUser.get(ResourcePackLoadStateTracker.class) == activeTracker) {
                            activeTracker.addLocalResourcePack(pack);
                        }
                    });
        }).exceptionally(error -> {
            final UserConnection liveUser = userReference.get();
            if (isConnectionActive(liveUser)) {
                BedrockProtocol.kickForIllegalState(liveUser, failureMessage, error);
            }
            return null;
        });
        stage.whenComplete((ignored, error) -> claim.close());
        return stage;
    }

    static CompletableFuture<Path> publishDetachedArchive(
            final ResourcePackArchiveStore archiveStore,
            final ResourcePackArchiveStore.Claim claim, final Path archive) {
        final CompletableFuture<Path> publication = archiveStore.publishAsync(claim, archive);
        publication.whenComplete((ignored, error) -> claim.close());
        return publication;
    }

    static CompletableFuture<Void> attachClaimedPackWaiter(
            final WeakReference<UserConnection> userReference,
            final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference,
            final ResourcePack.Key packKey, final ResourcePackLoadStateTracker.Info info,
            final ArchiveDigest archiveDigest,
            final CompletableFuture<Path> archiveFuture, final String failureMessage) {
        return archiveFuture.thenCompose(path -> {
            final UserConnection liveUser = userReference.get();
            final ResourcePackLoadStateTracker liveTracker = loadTrackerReference.get();
            if (!isConnectionActive(liveUser) || liveTracker == null
                    || liveUser.get(ResourcePackLoadStateTracker.class) != liveTracker) {
                return CompletableFuture.completedFuture(null);
            }
            return ViaBedrock.getResourcePackArchiveStore().loadEffective(
                            path, archiveDigest, liveTracker.getAlias(packKey),
                            liveTracker.announcementSequenceFingerprint(), info.contentKey())
                    .thenAccept(pack -> {
                        final UserConnection activeUser = userReference.get();
                        final ResourcePackLoadStateTracker activeTracker = loadTrackerReference.get();
                        if (isConnectionActive(activeUser) && activeTracker != null
                                && activeUser.get(ResourcePackLoadStateTracker.class) == activeTracker) {
                            activeTracker.addLocalResourcePack(pack);
                        }
                    });
        }).exceptionally(error -> {
            final UserConnection liveUser = userReference.get();
            if (isConnectionActive(liveUser)) {
                BedrockProtocol.kickForIllegalState(liveUser, failureMessage, error);
            }
            return null;
        });
    }

    private static void handleResourcePackChunkWrite(final UserConnection user, final String key,
                                                     final ResourcePackDownloadTracker.Download download,
                                                     final ResourcePackDownloadTracker.Download.ChunkResult chunkResult,
                                                     final Throwable error) {
        final ResourcePackDownloadTracker downloadTracker = user.get(ResourcePackDownloadTracker.class);
        if (downloadTracker == null || downloadTracker.get(key) != download) {
            return;
        }
        if (error != null) {
            downloadTracker.fail(key, error);
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, "Failed to write a server resource pack chunk", error);
            }
            return;
        }
        if (!isConnectionActive(user)) {
            downloadTracker.cancel(key, new CancellationException("Resource pack connection closed"));
            return;
        }
        if (!chunkResult.acceptedNewChunk()) return;
        final Path completedFile = chunkResult.completedFile();
        if (completedFile == null) {
            try {
                requestResourcePackChunks(user, key, download, 1);
            } catch (Throwable requestError) {
                downloadTracker.cancel(key, requestError);
                if (isConnectionActive(user)) {
                    BedrockProtocol.kickForIllegalState(user,
                            "Failed to request a server resource pack chunk", requestError);
                }
            }
            return;
        }

        if (download.archiveClaim() != null) {
            final ResourcePack.Key packKey = download.declaredKey();
            final ResourcePackLoadStateTracker loadStateTracker = user.get(ResourcePackLoadStateTracker.class);
            if (loadStateTracker == null) {
                try {
                    final Path archive = downloadTracker.takeCompleted(key);
                    publishDetachedArchive(
                            ViaBedrock.getResourcePackArchiveStore(), download.archiveClaim(), archive)
                            .exceptionally(publishError -> {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                                        "Failed to retain a completed server resource pack after negotiation ended",
                                        publishError);
                                return null;
                            });
                } catch (Throwable publishError) {
                    downloadTracker.fail(key, publishError);
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                            "Failed to retain a completed server resource pack after negotiation ended",
                            publishError);
                }
                return;
            }
            final ResourcePackLoadStateTracker.Info info =
                    packKey != null ? loadStateTracker.getRequest(packKey) : null;
            if (packKey == null || info == null) {
                final IllegalStateException failure = new IllegalStateException(
                        "Shared resource pack download lost its announced identity: " + key);
                downloadTracker.fail(key, failure);
                BedrockProtocol.kickForIllegalState(
                        user, "Failed to resolve a downloaded server resource pack", failure);
                return;
            }
            if (download.archiveClaim().path().isDone()
                    && !download.archiveClaim().path().isCompletedExceptionally()) {
                // CAS hit: the pack was already attached from the shared archive. Drop the redundant
                // protocol transfer without republishing, so the already-attached pack stays intact.
                downloadTracker.remove(key);
                return;
            }
            try {
                final Path archive = downloadTracker.takeCompleted(key);
                publishClaimedPack(user, loadStateTracker, packKey, info,
                        download.archiveClaim(), archive);
            } catch (Throwable publishError) {
                BedrockProtocol.kickForIllegalState(user, "Failed to store a server resource pack", publishError);
            }
            return;
        }

        final WeakReference<UserConnection> userReference = new WeakReference<>(user);
        final WeakReference<ResourcePackDownloadTracker> downloadTrackerReference =
                new WeakReference<>(downloadTracker);
        if (isCountedTransferPack(download.type())) {
            final WeakReference<ResourcePackLoadStateTracker> loadTrackerReference =
                    new WeakReference<>(user.get(ResourcePackLoadStateTracker.class));
            download.loadCompletedLegacyPackAsync(ViaBedrock.getResourcePackWorkScheduler())
                    .whenComplete((pack, loadError) -> {
                        final UserConnection liveUser = userReference.get();
                        final ResourcePackDownloadTracker liveDownloadTracker = downloadTrackerReference.get();
                        if (liveDownloadTracker == null || liveDownloadTracker.get(key) != download) {
                            return;
                        }
                        if (loadError != null) {
                            liveDownloadTracker.fail(key, loadError);
                            kickOnEventLoop(liveUser,
                                    "Failed to load a legacy server resource pack", loadError);
                            return;
                        }
                        final ResourcePackLoadStateTracker liveLoadTracker = loadTrackerReference.get();
                        if (!isConnectionActive(liveUser) || liveLoadTracker == null
                                || liveUser.get(ResourcePackLoadStateTracker.class) != liveLoadTracker) {
                            liveDownloadTracker.cancel(
                                    key, new CancellationException("Resource pack connection closed"));
                            return;
                        }
                        liveLoadTracker.addRemoteResourcePack(pack);
                        liveDownloadTracker.remove(key);
                    });
            return;
        }

        download.verifyCompletedHashAsync(ViaBedrock.getResourcePackWorkScheduler())
                .whenComplete((ignored, verifyError) -> {
                    final UserConnection liveUser = userReference.get();
                    final ResourcePackDownloadTracker liveDownloadTracker = downloadTrackerReference.get();
                    if (liveDownloadTracker == null || liveDownloadTracker.get(key) != download) {
                        return;
                    }
                    if (verifyError != null) {
                        liveDownloadTracker.fail(key, verifyError);
                        kickOnEventLoop(liveUser,
                                "Failed to verify a server resource pack", verifyError);
                    } else {
                        liveDownloadTracker.remove(key);
                    }
                });
    }

    private static void kickOnEventLoop(final UserConnection user, final String message, final Throwable error) {
        if (!isConnectionActive(user)) {
            return;
        }
        executeOnEventLoop(user.getChannel().eventLoop(), () -> {
            if (isConnectionActive(user)) {
                BedrockProtocol.kickForIllegalState(user, message, error);
            }
        }, () -> {
        });
    }

    private static boolean isConnectionActive(final UserConnection user) {
        return user != null && user.getChannel().isActive();
    }

    private static void sendResourcePackStackFinished(final UserConnection user) {
        try {
            final PacketWrapper resourcePackClientResponse = PacketWrapper.create(ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, user);
            resourcePackClientResponse.write(Types.BYTE, (byte) ResourcePackResponse.ResourcePackStackFinished.getValue()); // status
            resourcePackClientResponse.write(BedrockTypes.SHORT_LE_STRING_ARRAY, new String[0]); // downloading packs
            resourcePackClientResponse.sendToServer(BedrockProtocol.class);
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to send ResourcePackStackFinished to Bedrock server", e);
        }
    }

    static final class BoundedByteArrayType extends Type<byte[]> {

        private final int minimumLength;
        private final int maximumLength;

        BoundedByteArrayType(final int minimumLength, final int maximumLength) {
            super(byte[].class);
            if (minimumLength < 0 || maximumLength < minimumLength) {
                throw new IllegalArgumentException("Invalid byte array bounds");
            }
            this.minimumLength = minimumLength;
            this.maximumLength = maximumLength;
        }

        @Override
        public byte[] read(final ByteBuf buffer) {
            final int length = BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer);
            if (length < this.minimumLength || length > this.maximumLength) {
                throw new IllegalArgumentException("Byte array length is outside the allowed range: " + length);
            }
            if (!buffer.isReadable(length)) {
                throw new IllegalArgumentException("Byte array length exceeds readable bytes: " + length
                        + " > " + buffer.readableBytes());
            }
            final byte[] value = new byte[length];
            buffer.readBytes(value);
            return value;
        }

        @Override
        public void write(final ByteBuf buffer, final byte[] value) {
            if (value.length < this.minimumLength || value.length > this.maximumLength) {
                throw new IllegalArgumentException("Byte array length is outside the allowed range: " + value.length);
            }
            BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, value.length);
            buffer.writeBytes(value);
        }

    }

}
