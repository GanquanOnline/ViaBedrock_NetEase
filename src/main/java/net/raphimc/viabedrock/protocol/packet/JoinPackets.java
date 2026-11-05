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

import com.vdurmont.semver4j.Semver;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntArrayTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.api.minecraft.RegistryEntry;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.ints.IntIntImmutablePair;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.base.v1_7.ClientboundBaseProtocol1_7;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.modinterface.ECClientLightInterface;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions;
import net.raphimc.viabedrock.api.util.BitSets;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.pyrpc.PyRpcDispatcherModule;
import net.raphimc.viabedrock.experimental.resourcepack.ResourcePackModule;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.DataValues;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.GameEventType;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.model.*;
import net.raphimc.viabedrock.protocol.rewriter.BlockItemMappingLayout;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;

public class JoinPackets {

    private static final PacketHandler BIOME_DEFINITION_LIST_HANDLER = wrapper -> {
        if (wrapper.isCancelled()) return;

        wrapper.user().get(GameSessionStorage.class).setBedrockBiomeDefinitions((CompoundTag) wrapper.read(BedrockTypes.NETWORK_TAG)); // 生物群系定义
    };

    private static final PacketHandler REQUIRE_UNINITIALIZED_WORLD_HANDLER = wrapper -> {
        if (!wrapper.user().get(ChunkTracker.class).isEmpty()) {
            wrapper.cancel();
        } else if (!wrapper.user().get(EntityTracker.class).isEmpty()) {
            wrapper.cancel();
        }

        if (wrapper.isCancelled()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Tried to change world properties after the world was already loaded");
        }
    };

    private static final PacketHandler RECONFIGURE_HANDLER = wrapper -> {
        if (wrapper.isCancelled()) return;
        wrapper.cancel();

        wrapper.user().put(new ChunkTracker(wrapper.user(), wrapper.user().get(ChunkTracker.class).getDimension()));
        if (wrapper.user().getProtocolInfo().protocolVersion().newerThanOrEqualTo(ProtocolVersion.v1_20_2)) {
            wrapper.user().get(ClientChannelDiscoveryStorage.class).beginConfigurationCycle();
            wrapper.user().get(ClientLightStorage.class).beginConfigurationCycle();
            wrapper.user().get(BossBarStorage.class).onJavaOverlayCleared();
            final PacketWrapper startConfiguration = PacketWrapper.create(ClientboundPackets26_1.START_CONFIGURATION, wrapper.user());
            startConfiguration.send(BedrockProtocol.class);
            wrapper.user().getProtocolInfo().setServerState(State.CONFIGURATION);

            handleJavaClientGameJoin(wrapper.user());
        } else {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Skipping reconfigure packet as it is not supported by the client. This may cause issues.");
        }
    };

    static String resolveTabListPlaceholders(final String template, final String levelName) {
        return template.replace("%version%", ViaBedrock.VERSION).replace("%level_name%", levelName);
    }

    /**
     * 网易 / Nukkit-MOT 的 START_GAME 仍使用 924 之前的尾部：四个前置 ID（含
     * ownerIdentifier）、rewind + 服务端权威方块破坏（没有 movement-mode varint），且没有
     * 服务器加入信息树。协议 860 还额外有 tickDeathSystemsEnabled
     * （827..896）；898 已将其移除。加入信息树随协议 924 引入。
     */
    public static final int START_GAME_JOIN_INFORMATION_PROTOCOL = 924;
    public static final int START_GAME_TICK_DEATH_SYSTEMS_PROTOCOL = 827;
    public static final int START_GAME_TICK_DEATH_SYSTEMS_END_PROTOCOL = 897;

    static boolean usesNetEaseStartGameTail() {
        return usesNetEaseStartGameTail(ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                ViaBedrock.getConfig().getNetEaseProtocolVersion());
    }

    static boolean usesNetEaseStartGameTail(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol > 0 && protocol < START_GAME_JOIN_INFORMATION_PROTOCOL;
    }

    static boolean usesStartGameTickDeathSystems(final int protocol) {
        return protocol >= START_GAME_TICK_DEATH_SYSTEMS_PROTOCOL
                && protocol < START_GAME_TICK_DEATH_SYSTEMS_END_PROTOCOL;
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientboundTransition(ClientboundBedrockPackets.PLAY_STATUS,
                State.LOGIN, (PacketHandler) wrapper -> {
                    final int rawStatus = wrapper.read(Types.INT); // 状态
                    final PlayStatus status = PlayStatus.getByValue(rawStatus);
                    if (status == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayStatus: " + rawStatus);
                        wrapper.cancel();
                        return;
                    }

                    if (status == PlayStatus.LoginSuccess) {
                        // 在 LOGIN_FINISHED 到达 Java 客户端之前开启初始周期。否则
                        // CLIENT_INFORMATION 可能先启动探测，之后某个基岩 pre-play 数据包
                        // 重置该周期并意外移除协商截止时间。
                        wrapper.user().get(ClientChannelDiscoveryStorage.class).beginConfigurationCycle();
                        wrapper.user().get(ClientLightStorage.class).beginConfigurationCycle();
                        enterInitialConfiguration(wrapper);

                        ClientboundBaseProtocol1_7.onLoginSuccess(wrapper.user());
                        sendClientCacheStatus(wrapper.user());
                    } else {
                        wrapper.setPacketType(ClientboundLoginPackets.LOGIN_DISCONNECT);
                        writePlayStatusKickMessage(wrapper, status);
                    }
                }, State.PLAY, (PacketHandler) wrapper -> {
                    final int rawStatus = wrapper.read(Types.INT); // 状态
                    final PlayStatus status = PlayStatus.getByValue(rawStatus);
                    if (status == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayStatus: " + rawStatus);
                        wrapper.cancel();
                        return;
                    }

                    if (status == PlayStatus.LoginSuccess) {
                        wrapper.cancel();
                        sendClientCacheStatus(wrapper.user());
                    } else if (status == PlayStatus.PlayerSpawn) {
                        wrapper.cancel();
                        final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                        if (clientPlayer.isInitiallySpawned()) {
                            // C1：同一连接上的重生（例如复用该实体的跨服重载）。
                            // 下面朴素的提前返回会跳过加载界面的收尾，使
                            // 客户端卡在加载界面，后端也不知道玩家已就绪。此行为由
                            // movementWatchdog 开关控制：ACTIVE 重发幂等的收尾，OBSERVE 记录日志，
                            // OFF 保持上游行为。
                            final ViaBedrockConfig.MovementWatchdogMode mode = ViaBedrock.getConfig().getMovementWatchdogMode();
                            if (mode == ViaBedrockConfig.MovementWatchdogMode.ACTIVE) {
                                PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, null);
                                final PacketWrapper reInit = PacketWrapper.create(ServerboundBedrockPackets.SET_LOCAL_PLAYER_AS_INITIALIZED, wrapper.user());
                                reInit.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId());
                                reInit.sendToServer(BedrockProtocol.class);
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] C: re-spawn after reload; re-sent EndLoadingScreen + SET_LOCAL_PLAYER_AS_INITIALIZED");
                            } else if (mode == ViaBedrockConfig.MovementWatchdogMode.OBSERVE) {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] C(observe): re-spawn after reload detected (would re-send loading finalization in active mode)");
                            }
                            return;
                        }

                        final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, wrapper.user());
                        interact.write(Types.UNSIGNED_BYTE, (short) InteractPacket_Action.InteractUpdate.getValue()); // 动作
                        interact.write(BedrockTypes.UNSIGNED_VAR_LONG, 0L); // 目标实体运行时 ID
                        InteractPacketLayout.writePosition(interact, InteractPacket_Action.InteractUpdate, Position3f.ZERO);
                        interact.scheduleSendToServer(BedrockProtocol.class);

                        clientPlayer.setRotation(new Position3f(clientPlayer.rotation().x(), clientPlayer.rotation().y(), clientPlayer.rotation().y()));
                        clientPlayer.setInitiallySpawned();

                        PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, null);
                        final PacketWrapper setLocalPlayerAsInitialized = PacketWrapper.create(ServerboundBedrockPackets.SET_LOCAL_PLAYER_AS_INITIALIZED, wrapper.user());
                        setLocalPlayerAsInitialized.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // 实体运行时 ID
                        setLocalPlayerAsInitialized.scheduleSendToServer(BedrockProtocol.class);
                        startNetEaseLatencyHeartbeat(wrapper.user());
                        PyRpcDispatcherModule.scheduleClientLoadAddonsFinished(wrapper.user());
                    } else {
                        wrapper.setPacketType(ClientboundPackets26_1.DISCONNECT);
                        writePlayStatusKickMessage(wrapper, status);
                    }
                }, State.CONFIGURATION, (PacketHandler) wrapper -> {
                    final int rawStatus = wrapper.read(Types.INT); // 状态
                    final PlayStatus status = PlayStatus.getByValue(rawStatus);
                    if (status == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayStatus: " + rawStatus);
                        wrapper.cancel();
                        return;
                    }

                    if (status == PlayStatus.LoginSuccess) {
                        wrapper.cancel();
                        sendClientCacheStatus(wrapper.user());
                    } else if (status == PlayStatus.PlayerSpawn) {
                        // 网易在 Java 客户端完成配置阶段之前就推送了出生通知。
                        // 把初始化握手延迟到重放的数据包在 PLAY 状态被处理之后；
                        // 否则后端永远收不到
                        // SET_LOCAL_PLAYER_AS_INITIALIZED，并会让客户端超时。
                        wrapper.cancel();
                    } else {
                        wrapper.setPacketType(ClientboundConfigurationPackets1_21_9.DISCONNECT);
                        writePlayStatusKickMessage(wrapper, status);
                    }
                }
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.START_GAME,
                State.CONFIGURATION, (PacketHandler) wrapper -> {
                    wrapper.cancel();
                    final UserConnection user = wrapper.user();
                    final ResourcePackLoadStateTracker loadStateTracker =
                            user.get(ResourcePackLoadStateTracker.class);
                    final ResourcePackStorage resourcePackStorage = user.get(ResourcePackStorage.class);
                    final boolean negotiationPending = loadStateTracker != null
                            && loadStateTracker.hasResourcePackStackStarted()
                            && !loadStateTracker.negotiationReadyFuture().isDone();
                    if (resourcePackStorage == null || negotiationPending) {
                        if (loadStateTracker != null && !loadStateTracker.deferStartGame()) {
                            ViaBedrock.getPlatform().getLogger().log(
                                    Level.WARNING, "Ignoring duplicate deferred START_GAME packet");
                            return;
                        }
                        final ByteBuf payload = copyDeferredStartGamePayload(wrapper);
                        final CompletableFuture<ResourcePackStorage> preparation;
                        final boolean sessionOwnedPreparation;
                        try {
                            if (loadStateTracker != null
                                    && loadStateTracker.hasResourcePackStackStarted()) {
                                sessionOwnedPreparation = true;
                                preparation = loadStateTracker.negotiationReadyFuture();
                                ViaBedrock.getPlatform().getLogger().log(
                                        Level.INFO, "Deferring START_GAME until resource pack negotiation is ready");
                            } else {
                                if (loadStateTracker != null
                                        && loadStateTracker.hasAnnouncedResourcePacks()) {
                                    throw new IllegalStateException(
                                            "START_GAME arrived before RESOURCE_PACK_STACK for announced resource packs");
                                }
                                sessionOwnedPreparation = false;
                                ViaBedrock.getPlatform().getLogger().log(
                                        Level.WARNING, "Resource pack negotiation was skipped without announced packs");
                                if (loadStateTracker != null) {
                                    ResourcePackPackets.cancelRemotePackDelivery(loadStateTracker);
                                }
                                final int buildTimeoutSeconds =
                                        ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds();
                                final CompletableFuture<ResourcePackStorage> initialized =
                                        initializePreparedResourcePackStorage(
                                                ResourcePackStorage.createAsync(List.of(), List.of()),
                                                ResourcePackModule::ensureRuntimeData);
                                preparation = ResourcePackPackets.detachedTimeout(
                                        initialized, buildTimeoutSeconds,
                                        TimeUnit.SECONDS, () -> {
                                        }, JoinPackets::cleanupDeferredStorage,
                                        user.getChannel().eventLoop());
                            }
                        } catch (Throwable error) {
                            ReferenceCountUtil.safeRelease(payload);
                            BedrockProtocol.kickForIllegalState(
                                    user, "Failed to prepare resource packs before START_GAME", error);
                            return;
                        }
                        resumeStartGameAfterResourcePackPreparation(
                                user, payload, preparation, sessionOwnedPreparation,
                                (liveUser, deferredPayload) -> {
                                    if (loadStateTracker != null) {
                                        if (liveUser.get(ResourcePackLoadStateTracker.class)
                                                != loadStateTracker) {
                                            throw new IllegalStateException(
                                                    "Resource pack session changed before START_GAME replay");
                                        }
                                        loadStateTracker.markDeferredStartGameReady();
                                    }
                                    PacketWrapper.create(
                                            ClientboundBedrockPackets.START_GAME,
                                            deferredPayload.duplicate(), liveUser)
                                            .send(BedrockProtocol.class, false);
                                },
                                (liveUser, error) -> BedrockProtocol.kickForIllegalState(
                                        liveUser, "Failed to prepare resource packs before START_GAME", error));
                        return;
                    }
                    if (loadStateTracker != null) {
                        if (!loadStateTracker.claimStartGameProcessing()) {
                            ViaBedrock.getPlatform().getLogger().log(
                                    Level.WARNING, "Ignoring duplicate START_GAME packet during resource pack replay");
                            return;
                        }
                        if (loadStateTracker.hasAnnouncedResourcePacks()
                                && loadStateTracker.stackPhase()
                                != ResourcePackLoadStateTracker.StackPhase.PUBLISHED) {
                            BedrockProtocol.kickForIllegalState(user,
                                    "START_GAME observed an unpublished resource pack runtime",
                                    new IllegalStateException(
                                            "Resource pack stack state is " + loadStateTracker.stackPhase()));
                            return;
                        }
                        user.remove(ResourcePackLoadStateTracker.class);
                    }
                    final GameSessionStorage gameSession = user.get(GameSessionStorage.class);

                    final StartGameSharedPrefix sharedPrefix = readStartGameSharedPrefix(wrapper);
                    final long entityUniqueId = sharedPrefix.entityUniqueId();
                    final long entityRuntimeId = sharedPrefix.entityRuntimeId();
                    final GameType playerGameType = sharedPrefix.playerGameType();
                    final Position3f playerPosition = sharedPrefix.playerPosition();
                    final Position2f playerRotation = sharedPrefix.playerRotation();
                    final Dimension dimension = sharedPrefix.dimension();
                    final GeneratorType generatorType = sharedPrefix.generatorType();
                    final GameType levelGameType = sharedPrefix.levelGameType();
                    final boolean hardcore = sharedPrefix.hardcore();
                    final Difficulty difficulty = sharedPrefix.difficulty();
                    final Editor_WorldType editorWorldType = sharedPrefix.editorWorldType();
                    final int currentTime = sharedPrefix.currentTime();
                    final float rainLevel = sharedPrefix.rainLevel();
                    final float lightningLevel = sharedPrefix.lightningLevel();
                    final boolean commandsEnabled = sharedPrefix.commandsEnabled();
                    final GameRule[] gameRules = sharedPrefix.gameRules();
                    final Experiment[] experiments = sharedPrefix.experiments();
                    final int playerPermission = sharedPrefix.playerPermission();
                    final int chunkTickRange = sharedPrefix.chunkTickRange();
                    final String vanillaVersion = sharedPrefix.vanillaVersion();
                    final ChatRestrictionLevel chatRestrictionLevel = sharedPrefix.chatRestrictionLevel();

                    final String gameLevelName;
                    final int rewindHistorySize;
                    final boolean blockBreakingServerAuthoritative;
                    final long levelTime;
                    final BlockProperties[] blockProperties;
                    final boolean inventoryServerAuthoritative;
                    final String serverEngine;
                    final boolean hashedRuntimeBlockIds;
                    final CompoundTag playerPropertyData;
                    if (usesNetEaseStartGameTail()) {
                        final NetEaseLegacyStartGameTail tail = readNetEaseLegacyStartGameTail(wrapper,
                                usesStartGameTickDeathSystems(ViaBedrock.getConfig().getNetEaseProtocolVersion()));
                        if (tail.leftoverBytes() != 0) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                                    "NetEase START_GAME had " + tail.leftoverBytes()
                                            + " leftover bytes after the protocol "
                                            + ViaBedrock.getConfig().getNetEaseProtocolVersion() + " layout");
                        }
                        gameLevelName = tail.gameLevelName();
                        rewindHistorySize = tail.rewindHistorySize();
                        blockBreakingServerAuthoritative = tail.blockBreakingServerAuthoritative();
                        levelTime = tail.levelTime();
                        blockProperties = tail.blockProperties();
                        inventoryServerAuthoritative = tail.inventoryServerAuthoritative();
                        serverEngine = tail.serverEngine();
                        hashedRuntimeBlockIds = tail.hashedRuntimeBlockIds();
                        playerPropertyData = tail.playerPropertyData();
                    } else {
                        wrapper.read(BedrockTypes.STRING); // 世界 id
                        gameLevelName = wrapper.read(BedrockTypes.STRING); // 世界名称
                        wrapper.read(BedrockTypes.STRING); // 付费世界模板 id
                        wrapper.read(Types.BOOLEAN); // 是否为试用版
                        rewindHistorySize = wrapper.read(BedrockTypes.VAR_INT); // 回滚历史大小
                        blockBreakingServerAuthoritative = wrapper.read(Types.BOOLEAN); // 服务端权威方块破坏
                        levelTime = wrapper.read(BedrockTypes.LONG_LE); // 当前世界时间
                        wrapper.read(BedrockTypes.VAR_INT); // 附魔种子
                        blockProperties = wrapper.read(BedrockTypes.BLOCK_PROPERTIES_ARRAY); // 方块属性
                        wrapper.read(BedrockTypes.STRING); // 多人关联 id
                        inventoryServerAuthoritative = wrapper.read(Types.BOOLEAN); // 服务端权威物品栏
                        serverEngine = wrapper.read(BedrockTypes.STRING); // 服务端引擎
                        playerPropertyData = (CompoundTag) wrapper.read(BedrockTypes.NETWORK_TAG); // 玩家属性数据
                        wrapper.read(BedrockTypes.LONG_LE); // 方块注册表校验和
                        wrapper.read(BedrockTypes.UUID); // 世界模板 id
                        wrapper.read(Types.BOOLEAN); // 客户端侧生成
                        hashedRuntimeBlockIds = wrapper.read(Types.BOOLEAN); // 使用哈希方块运行时 ID
                        wrapper.read(Types.BOOLEAN); // 服务端权威音效
                        if (wrapper.read(Types.BOOLEAN)) { // 是否含服务器加入信息
                            if (wrapper.read(Types.BOOLEAN)) { // 是否含 gathering 加入信息
                                wrapper.read(BedrockTypes.UUID); // 体验 id
                                wrapper.read(BedrockTypes.STRING); // 体验名称
                                wrapper.read(BedrockTypes.UUID); // 体验世界 id
                                wrapper.read(BedrockTypes.STRING); // 体验世界名称
                                wrapper.read(BedrockTypes.STRING); // 创建者 id
                                wrapper.read(BedrockTypes.UUID); // 目标 id
                                wrapper.read(BedrockTypes.STRING); // 场景 id
                                wrapper.read(BedrockTypes.STRING); // 服务器 id
                            }
                            if (wrapper.read(Types.BOOLEAN)) { // 是否含商店入口信息
                                wrapper.read(BedrockTypes.STRING); // 商店 id
                                wrapper.read(BedrockTypes.STRING); // 商店名称
                            }
                            if (wrapper.read(Types.BOOLEAN)) { // 是否含 presence 信息
                                wrapper.read(BedrockTypes.STRING); // 体验名称
                                wrapper.read(BedrockTypes.STRING); // 世界名称
                            }
                        }
                        wrapper.read(BedrockTypes.STRING); // 服务器 id
                        wrapper.read(BedrockTypes.STRING); // 场景 id
                        wrapper.read(BedrockTypes.STRING); // 世界 id
                        wrapper.read(BedrockTypes.STRING); // 所有者 id
                    }
                    gameSession.setPlayerEntityProperties(playerPropertyData);

                    if (editorWorldType == Editor_WorldType.EditorProject) {
                        final PacketWrapper disconnect = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.DISCONNECT, wrapper.user());
                        writeEditorProjectDisconnect(disconnect, resourcePackStorage.getTexts().get("disconnectionScreen.editor.mismatchEditorWorld"));
                        disconnect.send(BedrockProtocol.class);
                        return;
                    }

                    ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Server feature version: " + vanillaVersion);
                    Semver version;
                    try {
                        if (vanillaVersion.equals("*")) {
                            version = new Semver("99.99.99");
                        } else {
                            version = new Semver(vanillaVersion, Semver.SemverType.LOOSE);
                        }
                    } catch (Throwable e) {
                        ViaBedrock.getPlatform().getLogger().log(Level.SEVERE, "Invalid vanilla version: " + vanillaVersion);
                        version = new Semver("99.99.99");
                    }

                    final List<String> enabledFeatures = new ArrayList<>();
                    for (Experiment experiment : experiments) {
                        if (experiment.enabled()) {
                            if (BedrockProtocol.MAPPINGS.getBedrockToJavaExperimentalFeatures().containsKey(experiment.name())) {
                                enabledFeatures.add(BedrockProtocol.MAPPINGS.getBedrockToJavaExperimentalFeatures().get(experiment.name()));
                            } else {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "This server uses an unsupported experimental feature: " + experiment.name());
                            }
                        }
                    }

                    if (!inventoryServerAuthoritative) {
                        ViaBedrock.getPlatform().getLogger().log(Level.INFO, "This server uses client authoritative inventories. This is not supported yet.");
                    }

                    gameSession.setBedrockVanillaVersion(version);
                    gameSession.setFlatGenerator(generatorType == GeneratorType.Flat);
                    gameSession.setMovementRewindHistorySize(rewindHistorySize);
                    gameSession.setLevelGameType(levelGameType);
                    gameSession.setLevelTime(levelTime);
                    gameSession.setHardcoreMode(hardcore);
                    gameSession.setChatRestrictionLevel(chatRestrictionLevel);
                    gameSession.setCommandsEnabled(commandsEnabled);
                    gameSession.setInventoryServerAuthoritative(inventoryServerAuthoritative);
                    gameSession.setBlockBreakingServerAuthoritative(blockBreakingServerAuthoritative);
                    gameSession.setStartGameBlockProperties(blockProperties);

                    final PlayerAbilities playerAbilities = new PlayerAbilities(entityUniqueId, (byte) playerPermission, (byte) CommandPermissionLevel.Any.getValue());
                    final ClientPlayerEntity clientPlayer = new ClientPlayerEntity(wrapper.user(), entityRuntimeId, wrapper.user().getProtocolInfo().getUuid(), playerAbilities);
                    clientPlayer.setPosition(new Position3f(playerPosition.x(), playerPosition.y() + clientPlayer.eyeOffset(), playerPosition.z()));
                    clientPlayer.setRotation(new Position3f(playerRotation.x(), playerRotation.y(), 0F));
                    clientPlayer.setOnGround(false);
                    clientPlayer.setGameType(playerGameType);
                    clientPlayer.setName(wrapper.user().getProtocolInfo().getUsername());

                    wrapper.user().get(CustomMappingSyncStorage.class).onStartGame(new PendingStartGame(
                            gameLevelName,
                            difficulty,
                            rainLevel,
                            lightningLevel,
                            currentTime,
                            chunkTickRange,
                            gameRules,
                            blockProperties,
                            hashedRuntimeBlockIds,
                            dimension,
                            clientPlayer,
                            serverEngine,
                            vanillaVersion,
                            enabledFeatures
                    ));
                }, State.PLAY, (PacketHandler) PacketWrapper::cancel // 基岩客户端会忽略多个 start game 数据包
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.BIOME_DEFINITION_LIST,
                // 生物群系在技术上由数据驱动，但客户端似乎忽略其中大部分已定义的数据，转而使用硬编码值。
                State.CONFIGURATION, new PacketHandlers() {
                    @Override
                    protected void register() {
                        handler(PacketWrapper::cancel);
                    }
                }, State.PLAY, new PacketHandlers() {
                    @Override
                    protected void register() {
                        handler(REQUIRE_UNINITIALIZED_WORLD_HANDLER);
                        handler(PacketWrapper::cancel);
                    }
                }
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.DIMENSION_DATA,
                State.CONFIGURATION, (PacketHandler) wrapper -> {
                    wrapper.cancel();
                    final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                    final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 条目数
                    for (int i = 0; i < count; i++) {
                        final String dimensionIdentifier = wrapper.read(BedrockTypes.STRING); // 维度标识符
                        final int maximumHeight = wrapper.read(BedrockTypes.VAR_INT); // 最大高度
                        final int minimumHeight = wrapper.read(BedrockTypes.VAR_INT); // 最小高度
                        wrapper.read(BedrockTypes.VAR_INT); // 生成器类型
                        // 官方 975+ 会追加维度类型。网易 860 每个条目仍使用四个字段。
                        DimensionDataLayout.skipDimensionType(wrapper);
                        if (dimensionIdentifier.equals(Dimension.OVERWORLD.getKey())) { // 基岩客户端目前只支持主世界
                            gameSession.putBedrockDimensionDefinition(dimensionIdentifier, new IntIntImmutablePair(minimumHeight, maximumHeight));
                        }
                    }
                }, State.PLAY, (PacketHandler) PacketWrapper::cancel // 基岩客户端会忽略 start game 之后的维度数据
        );
        protocol.registerClientbound(ClientboundBedrockPackets.ITEM_REGISTRY, null, wrapper -> {
            wrapper.cancel();
            final ItemEntry[] itemEntries = wrapper.read(BedrockTypes.ITEM_ENTRY_ARRAY); // 物品
            final GameSessionStorage itemGameSession = wrapper.user().get(GameSessionStorage.class);
            if (itemGameSession != null) {
                itemGameSession.setItemRegistryEntries(itemEntries);
            }
            // 始终重建。finishCustomMappingStartGame 以前会安装一个空重写器，
            // 而 MOT 的自定义方块物品只在此合并之后才存在。
            final ItemRewriter itemRewriter = new ItemRewriter(wrapper.user(), BlockItemMappingLayout.mergeCustomBlockItems(
                    itemEntries, itemGameSession != null ? itemGameSession.getStartGameBlockProperties() : null));
            wrapper.user().put(itemRewriter);
            final ItemDefinitions itemDefinitions = wrapper.user().get(ResourcePackStorage.class).getItems();

            for (ItemEntry itemEntry : itemEntries) {
                final boolean dataDrivenComponent = itemEntry.version() == ItemVersion.DataDriven && itemRewriter.getComponentItems().contains(itemEntry.identifier());
                final boolean legacyCustomComponent = !itemEntry.identifier().startsWith("minecraft:")
                        && itemEntry.componentData() != null
                        && itemEntry.componentData().get("components") instanceof CompoundTag;
                if (itemEntry.componentData() != null && (dataDrivenComponent || legacyCustomComponent)) {
                    itemDefinitions.addFromNetworkTag(itemEntry.identifier(), itemEntry.componentData());
                }
            }
            wrapper.user().get(PlayerArmorHudTracker.class).markDirty();
            if (!itemRewriter.getItems().isEmpty()) {
                wrapper.user().get(InventoryBootstrapQueue.class).onItemRegistryReady();
            }
        });
        protocol.registerClientboundTransition(ClientboundBedrockPackets.AVAILABLE_ENTITY_IDENTIFIERS,
                State.CONFIGURATION, (PacketHandler) wrapper -> {
                    // MOT 860 在 START_GAME 之后立即发送标识符，此时 Java 仍处于
                    // CONFIGURATION 阶段。在这里取消会让自定义 id 在延迟的
                    // ADD_ENTITY 重放之前未注册。pre-play 保护已经会把这个包入队。
                    wrapper.cancel();
                },
                State.PLAY, (PacketHandler) wrapper -> {
                    wrapper.cancel();
                    final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                    final CompoundTag entityIdentifiers = (CompoundTag) wrapper.read(BedrockTypes.NETWORK_TAG); // 实体标识符
                    for (CompoundTag entityIdentifier : entityIdentifiers.getListTag("idlist", CompoundTag.class)) {
                        final String identifier = entityIdentifier.getString("id");
                        if (identifier != null) {
                            gameSession.addEntityIdentifier(identifier);
                        }
                    }
                }
        );
    }

    static ByteBuf copyDeferredStartGamePayload(final PacketWrapper wrapper) {
        final Channel channel = wrapper.user().getChannel();
        if (channel == null) {
            throw new IllegalStateException("Cannot defer START_GAME without an active channel");
        }

        final ByteBuf framedPacket = channel.alloc().buffer();
        try {
            final int packetId = wrapper.getId();
            wrapper.writeToBuffer(framedPacket);
            if (packetId != -1) {
                final int serializedPacketId = Types.VAR_INT.readPrimitive(framedPacket);
                if (serializedPacketId != packetId) {
                    throw new IllegalStateException("Serialized START_GAME packet id changed from "
                            + packetId + " to " + serializedPacketId);
                }
            }
            return framedPacket.readRetainedSlice(framedPacket.readableBytes());
        } finally {
            framedPacket.release();
        }
    }

    static CompletableFuture<ResourcePackStorage> initializePreparedResourcePackStorage(
            final CompletionStage<ResourcePackStorage> creation,
            final Function<ResourcePackStorage, ? extends CompletionStage<Void>> initializer) {
        Objects.requireNonNull(creation, "creation");
        Objects.requireNonNull(initializer, "initializer");
        final CompletableFuture<ResourcePackStorage> ready = new CompletableFuture<>();
        creation.whenComplete((storage, creationError) -> {
            if (creationError != null) {
                ready.completeExceptionally(unwrapCompletion(creationError));
                return;
            }
            if (storage == null) {
                ready.completeExceptionally(
                        new IllegalStateException("Resource pack creation returned no storage"));
                return;
            }
            if (ready.isDone()) {
                cleanupDeferredStorage(storage);
                return;
            }

            final CompletionStage<Void> initialization;
            try {
                initialization = Objects.requireNonNull(
                        initializer.apply(storage), "resource pack initializer returned null");
            } catch (Throwable error) {
                cleanupDeferredStorage(storage);
                ready.completeExceptionally(error);
                return;
            }
            initialization.whenComplete((ignored, initializationError) -> {
                if (initializationError != null) {
                    cleanupDeferredStorage(storage);
                    ready.completeExceptionally(unwrapCompletion(initializationError));
                } else if (!ready.complete(storage)) {
                    cleanupDeferredStorage(storage);
                }
            });
        });
        return ready;
    }

    static void resumeStartGameAfterResourcePackPreparation(
            final UserConnection user, final ByteBuf payload,
            final CompletableFuture<ResourcePackStorage> preparation,
            final DeferredStartGameReplayer replayer,
            final BiConsumer<UserConnection, Throwable> failureHandler) {
        resumeStartGameAfterResourcePackPreparation(
                user, payload, preparation, false, replayer, failureHandler);
    }

    static void resumeStartGameAfterResourcePackPreparation(
            final UserConnection user, final ByteBuf payload,
            final CompletableFuture<ResourcePackStorage> preparation,
            final boolean sessionOwnedPreparation,
            final DeferredStartGameReplayer replayer,
            final BiConsumer<UserConnection, Throwable> failureHandler) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(replayer, "replayer");
        Objects.requireNonNull(failureHandler, "failureHandler");

        final Channel channel = user.getChannel();
        if (channel == null) {
            ReferenceCountUtil.safeRelease(payload);
            if (!sessionOwnedPreparation) preparation.cancel(false);
            return;
        }

        final AtomicReference<ByteBuf> pendingPayload = new AtomicReference<>(payload);
        channel.closeFuture().addListener(ignored -> {
            ReferenceCountUtil.safeRelease(pendingPayload.getAndSet(null));
            if (!sessionOwnedPreparation) preparation.cancel(false);
        });
        preparation.whenComplete((storage, buildError) -> {
            final ByteBuf ownedPayload = pendingPayload.getAndSet(null);
            if (ownedPayload == null) {
                if (!sessionOwnedPreparation) cleanupDeferredStorage(storage);
                return;
            }

            if (buildError != null || storage == null) {
                ReferenceCountUtil.safeRelease(ownedPayload);
                final Throwable failure = buildError != null ? unwrapCompletion(buildError)
                        : new IllegalStateException("Resource pack preparation returned no storage");
                if (!executeDeferredStartGame(channel, () -> {
                    if (channel.isActive()) failureHandler.accept(user, failure);
                })) {
                    channel.close();
                }
                return;
            }

            if (!executeDeferredStartGame(channel, () -> {
                boolean storageAccountedFor = false;
                try {
                    if (!channel.isActive()) {
                        if (!sessionOwnedPreparation) cleanupDeferredStorage(storage);
                        storageAccountedFor = true;
                        return;
                    }

                    final ResourcePackStorage existing = user.get(ResourcePackStorage.class);
                    if (sessionOwnedPreparation && existing != storage) {
                        throw new IllegalStateException(
                                "Resource pack session completed without publishing its runtime");
                    } else if (existing == null) {
                        user.put(storage);
                    } else if (existing != storage && !sessionOwnedPreparation) {
                        cleanupDeferredStorage(storage);
                    }
                    storageAccountedFor = true;
                    replayer.replay(user, ownedPayload);
                } catch (Throwable error) {
                    if (!storageAccountedFor && !sessionOwnedPreparation) cleanupDeferredStorage(storage);
                    if (channel.isActive()) failureHandler.accept(user, error);
                } finally {
                    ReferenceCountUtil.safeRelease(ownedPayload);
                }
            })) {
                ReferenceCountUtil.safeRelease(ownedPayload);
                if (!sessionOwnedPreparation) cleanupDeferredStorage(storage);
                channel.close();
            }
        });
    }

    private static boolean executeDeferredStartGame(final Channel channel, final Runnable task) {
        try {
            channel.eventLoop().execute(task);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void cleanupDeferredStorage(final ResourcePackStorage storage) {
        if (storage != null) storage.onRemove();
    }

    private static Throwable unwrapCompletion(final Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    interface DeferredStartGameReplayer {

        void replay(UserConnection user, ByteBuf payload) throws Exception;
    }

    public record PendingStartGame(
            String levelName,
            Difficulty difficulty,
            float rainLevel,
            float lightningLevel,
            int currentTime,
            int chunkTickRange,
            GameRule[] gameRules,
            BlockProperties[] blockProperties,
            boolean hashedRuntimeBlockIds,
            Dimension dimension,
            ClientPlayerEntity clientPlayer,
            String serverEngine,
            String vanillaVersion,
            List<String> enabledFeatures
    ) {
    }

    public static void finishCustomMappingStartGame(final UserConnection user, final PendingStartGame pending) {
        final JoinGate joinGate = JoinGate.install(user);
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        gameSession.setServerBrand(formatServerBrand(pending.serverEngine(), pending.vanillaVersion()));
        user.put(new JoinGameStorage(pending.levelName(), pending.difficulty(), pending.rainLevel(), pending.lightningLevel(), pending.currentTime(), pending.chunkTickRange()));
        user.put(new GameRulesStorage(user, pending.gameRules()));
        user.put(new BlockStateRewriter(user, pending.blockProperties(), pending.hashedRuntimeBlockIds()));
        // 针对新的 BlockStateRewriter 重建。不要用空表覆盖后续的 ITEM_REGISTRY
        // 合并；MOT 的自定义方块物品也在这里合成。
        user.put(new ItemRewriter(user, BlockItemMappingLayout.mergeCustomBlockItems(
                gameSession.getItemRegistryEntries(), pending.blockProperties())));
        final EntityTracker entityTracker = new EntityTracker(user);
        entityTracker.addEntity(pending.clientPlayer(), false);
        user.put(entityTracker);
        user.put(new ChunkTracker(user, pending.dimension()));

        sendJavaConfigurationOutputs(user, pending.enabledFeatures(), () -> {
            final PacketWrapper requestChunkRadius = PacketWrapper.create(ServerboundBedrockPackets.REQUEST_CHUNK_RADIUS, user);
            requestChunkRadius.write(BedrockTypes.VAR_INT, user.get(ClientSettingsStorage.class).viewDistance()); // 半径
            requestChunkRadius.write(Types.BYTE, ProtocolConstants.BEDROCK_REQUEST_CHUNK_RADIUS_MAX_RADIUS); // 最大半径
            requestChunkRadius.sendToServer(BedrockProtocol.class);
            PacketFactory.sendBedrockLoadingScreen(user, ServerboundLoadingScreenPacketType.StartLoadingScreen, null);
        });
    }

    static String formatServerBrand(final String serverEngine, final String vanillaVersion) {
        return "Bedrock" + (!serverEngine.isEmpty() ? " @" + serverEngine : "") + " v: " + vanillaVersion;
    }

    /**
     * GanAC 入站 NSL 为 {@code timestamp / 1_000_000}。原始的 {@code currentTimeMillis}
     * （约 1.77e12）缩放后约 1.77e6，正好落在 GanAC 进程级 ping 计数器
     * {@code 1..999_999_999} 内部，{@code releaseThrough} 会提前排空更早的真实 ping。
     * 原版基岩回显 {@code id * 1_000_000}；按那个量级写入毫秒值
     * 会让缩放后的 id 变成 {@code currentTimeMillis}（约 1.77e12），超出 GanAC 的范围。
     * 不要用 {@code 0}（MOT 保活 / GanAC 忽略）。不要复用 GanAC 的小
     * 计数器。HUD 仍使用 {@code JAVA_ONLY_LATENCY_PROBE}，绝不使用这个数据包。
     * 参考：GanAC NetworkLatencyPackets.resolveModernResponseId；NukkitLatencyAdapter。
     */
    static long neteaseHeartbeatTimestamp(final long currentTimeMillis) {
        return Math.multiplyExact(currentTimeMillis, 1_000_000L);
    }

    /** 当 GanAC 会把 {@code wireTimestamp / 1_000_000} 当作待处理 ping id 时返回 true。 */
    static boolean ganacPingIdCollision(final long wireTimestamp) {
        if (wireTimestamp == 0L) {
            return false;
        }
        final long scaledId = wireTimestamp / 1_000_000L;
        return scaledId >= 1L && scaledId <= 999_999_999L;
    }

    private static void startNetEaseLatencyHeartbeat(final UserConnection user) {
        if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) return;
        final Channel channel = user.getChannel();
        if (channel == null || !channel.isActive()) return;
        // 网易会在出生后约 10 秒丢弃会话，除非客户端持续发送
        // NETWORK_STACK_LATENCY；仅回显不够，所以从 PlayerSpawn 起发送主动心跳。
        channel.eventLoop().scheduleAtFixedRate(() -> {
            if (!channel.isActive() || user.getProtocolInfo().getServerState() != State.PLAY) return;
            try {
                final PacketWrapper ping = PacketWrapper.create(ServerboundBedrockPackets.NETWORK_STACK_LATENCY, user);
                ping.write(BedrockTypes.LONG_LE, neteaseHeartbeatTimestamp(System.currentTimeMillis())); // 时间戳
                ping.write(Types.BOOLEAN, false); // 来自服务端
                ping.scheduleSendToServer(BedrockProtocol.class);
                // 额外的 HUD 采样。服务端的 NETWORK_STACK_LATENCY 已经
                // 通过 Java PING/PONG 为 GanAC 完成往返。
                final PacketSyncStorage packetSyncStorage = user.get(PacketSyncStorage.class);
                if (packetSyncStorage != null) {
                    packetSyncStorage.sendJavaLatencyProbe();
                }
            } catch (Throwable t) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to send NetEase NETWORK_STACK_LATENCY heartbeat", t);
            }
        }, 0, 2, TimeUnit.SECONDS);
    }

    static int remainingStartGameBytes(final PacketWrapper wrapper) {
        if (wrapper instanceof PacketWrapperImpl packetWrapper && packetWrapper.getInputBuffer() != null) {
            return packetWrapper.getInputBuffer().readableBytes();
        }
        return 0;
    }

    static StartGameSharedPrefix readStartGameSharedPrefix(final PacketWrapper wrapper) {
        final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 id
        final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
        final GameType playerGameType = GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT)); // 玩家游戏模式
        final Position3f playerPosition = wrapper.read(BedrockTypes.POSITION_3F); // 玩家位置
        final Position2f playerRotation = wrapper.read(BedrockTypes.POSITION_2F); // 玩家朝向
        wrapper.read(BedrockTypes.LONG_LE); // 种子
        wrapper.read(BedrockTypes.SHORT_LE); // 出生生物群系类型
        wrapper.read(BedrockTypes.STRING); // 自定义生物群系名称
        final Dimension dimension = Dimension.values()[wrapper.read(BedrockTypes.VAR_INT)]; // 维度
        final GeneratorType generatorType = GeneratorType.getByValue(wrapper.read(BedrockTypes.VAR_INT), GeneratorType.Undefined); // 生成器 id
        final GameType levelGameType = GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT)); // 世界游戏模式
        final boolean hardcore = wrapper.read(Types.BOOLEAN); // 极限模式
        final Difficulty difficulty = Difficulty.getByValue(wrapper.read(BedrockTypes.VAR_INT), Difficulty.Unknown); // 难度
        wrapper.read(BedrockTypes.BLOCK_POSITION); // 默认出生位置
        wrapper.read(Types.BOOLEAN); // 成就已禁用
        final Editor_WorldType editorWorldType = Editor_WorldType.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // 世界编辑器类型
        wrapper.read(Types.BOOLEAN); // 在世界编辑器中创建
        wrapper.read(Types.BOOLEAN); // 从世界编辑器导出
        final int currentTime = wrapper.read(BedrockTypes.VAR_INT); // 昼夜循环停止时间
        wrapper.read(BedrockTypes.VAR_INT); // 教育版优惠
        wrapper.read(Types.BOOLEAN); // 教育版功能已启用
        wrapper.read(BedrockTypes.STRING); // 教育版产品 id
        final float rainLevel = wrapper.read(BedrockTypes.FLOAT_LE); // 降雨强度
        final float lightningLevel = wrapper.read(BedrockTypes.FLOAT_LE); // 闪电强度
        wrapper.read(Types.BOOLEAN); // 平台锁定内容已确认
        wrapper.read(Types.BOOLEAN); // 多人游戏
        wrapper.read(Types.BOOLEAN); // 是否向局域网广播
        wrapper.read(BedrockTypes.VAR_INT); // Xbox Live 广播模式
        wrapper.read(BedrockTypes.VAR_INT); // 平台广播模式
        final boolean commandsEnabled = wrapper.read(Types.BOOLEAN); // 命令已启用
        wrapper.read(Types.BOOLEAN); // 需要纹理包
        final GameRule[] gameRules = wrapper.read(BedrockTypes.VAR_INT_GAME_RULE_ARRAY); // 游戏规则
        final Experiment[] experiments = wrapper.read(BedrockTypes.EXPERIMENT_ARRAY); // 实验
        wrapper.read(Types.BOOLEAN); // 之前已切换的实验
        wrapper.read(Types.BOOLEAN); // 奖励箱已启用
        wrapper.read(Types.BOOLEAN); // 已启用初始地图
        final int playerPermission = wrapper.read(BedrockTypes.VAR_INT); // 玩家权限
        final int chunkTickRange = wrapper.read(BedrockTypes.INT_LE); // 服务端区块刻范围
        wrapper.read(Types.BOOLEAN); // 行为包已锁定
        wrapper.read(Types.BOOLEAN); // 资源包已锁定
        wrapper.read(Types.BOOLEAN); // 来自锁定的世界模板
        wrapper.read(Types.BOOLEAN); // 仅使用 MSA 玩家代号
        wrapper.read(Types.BOOLEAN); // 来自世界模板
        wrapper.read(Types.BOOLEAN); // 世界模板选项已锁定
        wrapper.read(Types.BOOLEAN); // 只生成 v1 村民
        wrapper.read(Types.BOOLEAN); // 禁用角色形象
        wrapper.read(Types.BOOLEAN); // 禁用自定义皮肤
        wrapper.read(Types.BOOLEAN); // 屏蔽表情聊天
        final String vanillaVersion = wrapper.read(BedrockTypes.STRING); // 原版版本
        wrapper.read(BedrockTypes.INT_LE); // 有限世界宽度
        wrapper.read(BedrockTypes.INT_LE); // 有限世界高度
        wrapper.read(Types.BOOLEAN); // 下界类型
        wrapper.read(BedrockTypes.EDUCATION_URI_RESOURCE); // 教育版共享 URI
        wrapper.read(Types.BOOLEAN); // 启用实验性玩法
        final ChatRestrictionLevel chatRestrictionLevel = ChatRestrictionLevel.getByValue(wrapper.read(Types.BYTE), ChatRestrictionLevel.Disabled); // 聊天限制等级
        wrapper.read(Types.BOOLEAN); // 禁用玩家交互
        return new StartGameSharedPrefix(
                entityUniqueId,
                entityRuntimeId,
                playerGameType,
                playerPosition,
                playerRotation,
                dimension,
                generatorType,
                levelGameType,
                hardcore,
                difficulty,
                editorWorldType,
                currentTime,
                rainLevel,
                lightningLevel,
                commandsEnabled,
                gameRules,
                experiments,
                playerPermission,
                chunkTickRange,
                vanillaVersion,
                chatRestrictionLevel
        );
    }

    static NetEaseLegacyStartGameTail readNetEaseLegacyStartGameTail(final PacketWrapper wrapper, final boolean tickDeathSystems) {
        // Nukkit-MOT / 网易 860 与 898（924 之前）：
        // 前置 ID（含 ownerIdentifier），然后是世界方块，
        // 接着是 rewind + 服务端权威方块破坏。没有 movement-mode varint。
        wrapper.read(BedrockTypes.STRING); // 服务器 id
        wrapper.read(BedrockTypes.STRING); // 世界 id
        wrapper.read(BedrockTypes.STRING); // 场景 id
        wrapper.read(BedrockTypes.STRING); // 所有者标识符
        wrapper.read(BedrockTypes.STRING); // 世界 id
        final String gameLevelName = wrapper.read(BedrockTypes.STRING); // 世界名称
        wrapper.read(BedrockTypes.STRING); // 付费世界模板 id
        wrapper.read(Types.BOOLEAN); // 是否为试用版
        final int rewindHistorySize = wrapper.read(BedrockTypes.VAR_INT); // 回滚历史大小
        final boolean blockBreakingServerAuthoritative = wrapper.read(Types.BOOLEAN); // 服务端权威方块破坏
        final long levelTime = wrapper.read(BedrockTypes.LONG_LE); // 当前刻
        wrapper.read(BedrockTypes.VAR_INT); // 附魔种子
        final BlockProperties[] blockProperties = wrapper.read(BedrockTypes.BLOCK_PROPERTIES_ARRAY); // 前后都没有额外 bool
        wrapper.read(BedrockTypes.STRING); // 多人关联 id
        final boolean inventoryServerAuthoritative = wrapper.read(Types.BOOLEAN); // 服务端权威物品栏
        final String serverEngine = wrapper.read(BedrockTypes.STRING); // 服务端引擎
        final CompoundTag playerPropertyData = (CompoundTag) wrapper.read(BedrockTypes.NETWORK_TAG); // 玩家属性数据
        wrapper.read(BedrockTypes.LONG_LE); // 方块注册表校验和
        wrapper.read(BedrockTypes.UUID); // 世界模板 ID
        wrapper.read(Types.BOOLEAN); // 客户端侧生成
        final boolean hashedRuntimeBlockIds = wrapper.read(Types.BOOLEAN); // 使用哈希方块运行时 ID
        if (tickDeathSystems) {
            wrapper.read(Types.BOOLEAN); // 启用刻死亡系统 (827 <= proto < 897)
        }
        wrapper.read(Types.BOOLEAN); // 服务端权威音效
        return new NetEaseLegacyStartGameTail(
                gameLevelName,
                rewindHistorySize,
                blockBreakingServerAuthoritative,
                levelTime,
                blockProperties,
                inventoryServerAuthoritative,
                serverEngine,
                hashedRuntimeBlockIds,
                playerPropertyData,
                remainingStartGameBytes(wrapper)
        );
    }

    record StartGameSharedPrefix(
            long entityUniqueId,
            long entityRuntimeId,
            GameType playerGameType,
            Position3f playerPosition,
            Position2f playerRotation,
            Dimension dimension,
            GeneratorType generatorType,
            GameType levelGameType,
            boolean hardcore,
            Difficulty difficulty,
            Editor_WorldType editorWorldType,
            int currentTime,
            float rainLevel,
            float lightningLevel,
            boolean commandsEnabled,
            GameRule[] gameRules,
            Experiment[] experiments,
            int playerPermission,
            int chunkTickRange,
            String vanillaVersion,
            ChatRestrictionLevel chatRestrictionLevel
    ) {
    }

    record NetEaseLegacyStartGameTail(
            String gameLevelName,
            int rewindHistorySize,
            boolean blockBreakingServerAuthoritative,
            long levelTime,
            BlockProperties[] blockProperties,
            boolean inventoryServerAuthoritative,
            String serverEngine,
            boolean hashedRuntimeBlockIds,
            CompoundTag playerPropertyData,
            int leftoverBytes
    ) {
    }

    static void enterInitialConfiguration(final PacketWrapper wrapper) {
        final ProtocolInfo protocolInfo = wrapper.user().getProtocolInfo();
        // Java 一处理 LOGIN_FINISHED 就会切换入站解码器。在暴露该数据包之前先推进 ViaBedrock
        // 服务端状态，这样后续的基岩 pre-play 数据包就不会在 Java 客户端已进入 CONFIGURATION
        // 时再生成第二份登录档案。
        protocolInfo.setServerState(State.CONFIGURATION);
        // 网易在 Java 客户端完成配置阶段之前就推送世界数据包。把它们捕获下来，
        // 以便进入 PLAY 状态后重放（见 sendJavaConfigurationOutputs）。
        wrapper.user().put(new PlayStateTransitionQueue(wrapper.user()));
        wrapper.setPacketType(ClientboundLoginPackets.LOGIN_FINISHED);
        wrapper.write(Types.UUID, protocolInfo.getUuid()); // UUID
        wrapper.write(Types.STRING, protocolInfo.getUsername()); // 用户名
        wrapper.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[0]); // 属性
    }

    static void writeEditorProjectDisconnect(final PacketWrapper disconnect, final String reason) {
        PacketFactory.writeJavaDisconnect(disconnect, reason);
    }

    public static void sendConfigurationBrand(final UserConnection user, final String brand) {
        final PacketWrapper brandCustomPayload = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, user);
        brandCustomPayload.write(Types.STRING, "minecraft:brand"); // 通道
        brandCustomPayload.write(Types.STRING, brand); // 内容
        brandCustomPayload.send(BedrockProtocol.class);
        if (user.getChannel() != null) {
            user.getChannel().flush();
        }
    }

    private static void sendClientCacheStatus(final UserConnection user) {
        final PacketWrapper clientCacheStatus = PacketWrapper.create(ServerboundBedrockPackets.CLIENT_CACHE_STATUS, user);
        clientCacheStatus.write(Types.BOOLEAN, !ViaBedrock.getConfig().getBlobCacheMode().equals(ViaBedrockConfig.BlobCacheMode.DISABLED)); // 是否受支持
        clientCacheStatus.sendToServer(BedrockProtocol.class);
    }

    private static void writePlayStatusKickMessage(final PacketWrapper wrapper, final PlayStatus status) {
        final Map<String, String> translations = BedrockProtocol.MAPPINGS.getBedrockResourcePacks().get(DataValues.VANILLA_RESOURCE_PACK_KEY).content().getLang("texts/en_US.lang");

        switch (status) {
            case LoginFailed_ClientOld -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.outdatedClient"));
            case LoginFailed_ServerOld -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.outdatedServer"));
            case LoginFailed_InvalidTenant -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.invalidTenant"));
            case LoginFailed_EditionMismatchEduToVanilla -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.editionMismatchEduToVanilla"));
            case LoginFailed_EditionMismatchVanillaToEdu -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.editionMismatchVanillaToEdu"));
            case LoginFailed_ServerFullSubClient, LoginFailed_EditorMismatchVanillaToEditor ->
                    PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.serverFull") + "\n\n\n\n" + translations.get("disconnectionScreen.serverFull.title"));
            case LoginFailed_EditorMismatchEditorToVanilla -> PacketFactory.writeJavaDisconnect(wrapper, translations.get("disconnectionScreen.editor.mismatchEditorToVanilla"));
            case PlayerSpawn, LoginSuccess -> wrapper.cancel();
            default -> throw new IllegalStateException("Unhandled PlayStatus: " + status);
        }
    }

    private static void handleJavaClientGameJoin(final UserConnection user) {
        sendJavaConfigurationOutputs(user, Collections.emptyList(), () -> sendJavaLoginAndInitialPackets(user));
    }

    public static void sendJavaConfigurationOutputs(final UserConnection user, final List<String> enabledFeatures, final Runnable afterFinish) {
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);

        if (enabledFeatures != null && !enabledFeatures.isEmpty()) {
            final List<String> features = new ArrayList<>(enabledFeatures);
            features.add("minecraft:vanilla");
            final PacketWrapper updateEnabledFeatures = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.UPDATE_ENABLED_FEATURES, user);
            updateEnabledFeatures.write(Types.STRING_ARRAY, features.toArray(new String[0])); // 启用的功能
            updateEnabledFeatures.send(BedrockProtocol.class);
        }

        for (Map.Entry<String, Tag> registry : gameSession.getJavaRegistries().entrySet()) {
            final CompoundTag registryTag = (CompoundTag) registry.getValue();
            final PacketWrapper registryData = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.REGISTRY_DATA, user);
            registryData.write(Types.STRING, registry.getKey()); // 注册表键
            final List<RegistryEntry> entries = new ArrayList<>();
            for (Map.Entry<String, Tag> entry : registryTag.entrySet()) {
                entries.add(new RegistryEntry(entry.getKey(), entry.getValue()));
            }
            registryData.write(Types.REGISTRY_ENTRY_ARRAY, entries.toArray(new RegistryEntry[0])); // 注册表条目
            registryData.send(BedrockProtocol.class);
        }

        final PacketWrapper updateTags = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.UPDATE_TAGS, user);
        updateTags.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaTags().size()); // 注册表数量
        for (Map.Entry<String, Tag> registryEntry : BedrockProtocol.MAPPINGS.getJavaTags().entrySet()) {
            final CompoundTag tag = (CompoundTag) registryEntry.getValue();
            updateTags.write(Types.STRING, registryEntry.getKey()); // 注册表键
            updateTags.write(Types.VAR_INT, tag.size()); // 标签数量
            for (Map.Entry<String, Tag> tagEntry : tag.entrySet()) {
                updateTags.write(Types.STRING, tagEntry.getKey()); // 标签名称
                updateTags.write(Types.VAR_INT_ARRAY_PRIMITIVE, ((IntArrayTag) tagEntry.getValue()).getValue().clone()); // 标签 ID
            }
        }
        updateTags.send(BedrockProtocol.class);

        ECClientLightInterface.finishConfigurationWhenReady(user, sequenceConfigurationCompletion(() -> {
            final PacketWrapper finishConfiguration = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.FINISH_CONFIGURATION, user);
            finishConfiguration.send(BedrockProtocol.class);
            user.getProtocolInfo().setServerState(State.PLAY);
            if (user.getProtocolInfo().protocolVersion().betweenInclusive(ProtocolVersion.v1_20_2, ProtocolVersion.v1_21_2)) { // VB 兼容性
                // 有问题的代码：https://github.com/ViaVersion/ViaBackwards/blob/b90b573f1d6f4d59841a3243e5bd072a43ec78e5/common/src/main/java/com/viaversion/viabackwards/protocol/v1_21_4to1_21_2/rewriter/EntityPacketRewriter1_21_4.java#L109
                user.getProtocolInfo().setClientState(State.PLAY); // 虽然不正确，但 ViaBackwards 依赖它，否则会在 configuration 状态下发送 player loaded 数据包。
            }
            user.get(InventoryBootstrapQueue.class).onPlayReady();
            // 重放网易在 Java 配置阶段发送的世界数据包。这必须在状态切换之后
            // （这样 pre-play 守卫才会放行），并且在上面的 START_GAME 存储
            // 安装完成之后（这样处理器才能使用它们）。
            final PlayStateTransitionQueue transitionQueue = user.get(PlayStateTransitionQueue.class);
            if (transitionQueue != null) {
                transitionQueue.replayPackets();
                user.remove(PlayStateTransitionQueue.class);
            }
        }, afterFinish));
    }

    static Runnable sequenceConfigurationCompletion(final Runnable finishConfiguration, final Runnable afterFinish) {
        return () -> {
            finishConfiguration.run();
            afterFinish.run();
        };
    }

    public static void sendJavaLoginAndInitialPackets(final UserConnection user) {
        final JoinGameStorage joinGameStorage = user.get(JoinGameStorage.class);
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final ClientSettingsStorage clientSettingsStorage = user.get(ClientSettingsStorage.class);
        final GameRulesStorage gameRulesStorage = user.get(GameRulesStorage.class);
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final CommandsStorage commandsStorage = user.get(CommandsStorage.class);
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();

        final PacketWrapper joinGame = PacketWrapper.create(ClientboundPackets26_1.LOGIN, user);
        joinGame.write(Types.INT, clientPlayer.javaId()); // 实体 ID
        joinGame.write(Types.BOOLEAN, gameSession.isHardcoreMode()); // 极限模式
        joinGame.write(Types.STRING_ARRAY, Dimension.getDimensionKeys()); // 维度类型
        joinGame.write(Types.VAR_INT, 100); // 最大玩家数
        joinGame.write(Types.VAR_INT, clientSettingsStorage.viewDistance()); // 视距
        joinGame.write(Types.VAR_INT, joinGameStorage.chunkTickRange()); // 模拟距离
        joinGame.write(Types.BOOLEAN, ViaBedrock.getConfig().shouldTranslateShowCoordinatesGameRule() && !gameRulesStorage.<Boolean>getGameRule("showCoordinates")); // 精简调试信息
        joinGame.write(Types.BOOLEAN, !gameRulesStorage.<Boolean>getGameRule("doImmediateRespawn")); // 显示死亡界面
        joinGame.write(Types.BOOLEAN, gameRulesStorage.getGameRule("doLimitedCrafting")); // 受限合成
        joinGame.write(Types.VAR_INT, chunkTracker.getDimension().ordinal()); // 维度 ID
        joinGame.write(Types.STRING, chunkTracker.getDimension().getKey()); // 维度名称
        joinGame.write(Types.LONG, 0L); // 哈希种子
        joinGame.write(Types.BYTE, (byte) clientPlayer.javaGameMode().ordinal()); // 游戏模式
        joinGame.write(Types.BYTE, (byte) -1); // 上一个游戏模式
        joinGame.write(Types.BOOLEAN, false); // 是否为调试世界
        joinGame.write(Types.BOOLEAN, gameSession.isFlatGenerator()); // 是否为超平坦
        joinGame.write(Types.OPTIONAL_GLOBAL_POSITION, null); // 上次死亡位置
        joinGame.write(Types.VAR_INT, 0); // 传送门冷却
        joinGame.write(Types.VAR_INT, 64); // 海平面
        joinGame.write(Types.BOOLEAN, false); // 强制安全聊天
        joinGame.send(BedrockProtocol.class);

        final String serverBrand = gameSession.getServerBrand();
        if (serverBrand != null) {
            final PacketWrapper brandCustomPayload = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
            brandCustomPayload.write(Types.STRING, "minecraft:brand"); // 通道
            brandCustomPayload.write(Types.STRING, serverBrand); // 内容
            brandCustomPayload.send(BedrockProtocol.class);
        }

        clientPlayer.createTeam();
        clientPlayer.sendInitialEntityData();
        clientPlayer.updateAttributes(clientPlayer.attributes().values().toArray(new EntityAttribute[0]));
        clientPlayer.setAbilities(clientPlayer.abilities());
        clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
        if (commandsStorage != null) {
            commandsStorage.updateCommandTree();
        }
        if (inventoryTracker != null) {
            PacketFactory.sendJavaContainerSetContent(user, inventoryTracker.getInventoryContainer());
        }
        user.get(PlayerArmorHudTracker.class).forceSync();
        chunkTracker.sendCurrentCacheSettingsToJava();

        final PacketWrapper initializeBorder = PacketWrapper.create(ClientboundPackets26_1.INITIALIZE_BORDER, user);
        initializeBorder.write(Types.DOUBLE, 0D); // 中心 x
        initializeBorder.write(Types.DOUBLE, 0D); // 中心 z
        initializeBorder.write(Types.DOUBLE, 0D); // 旧尺寸
        initializeBorder.write(Types.DOUBLE, 60_000_000D); // 新尺寸
        initializeBorder.write(Types.VAR_LONG, 0L); // 插值时间
        initializeBorder.write(Types.VAR_INT, 60_000_000); // 新的绝对最大尺寸
        initializeBorder.write(Types.VAR_INT, 0); // 警告方块距离
        initializeBorder.write(Types.VAR_INT, 0); // 警告时间
        initializeBorder.send(BedrockProtocol.class);

        final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, user);
        updateAttributes.write(Types.VAR_INT, clientPlayer.javaId()); // 实体 ID
        updateAttributes.write(Types.VAR_INT, 1); // 属性数量
        updateAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.ATTACK_SPEED)); // 属性 ID
        updateAttributes.write(Types.DOUBLE, 20D); // 基础值
        updateAttributes.write(Types.VAR_INT, 0); // 修饰符数量
        updateAttributes.send(BedrockProtocol.class);

        final PacketWrapper serverDifficulty = PacketWrapper.create(ClientboundPackets26_1.CHANGE_DIFFICULTY, user);
        serverDifficulty.write(Types.VAR_INT, joinGameStorage.difficulty().getValue()); // 难度
        serverDifficulty.write(Types.BOOLEAN, false); // 已锁定
        serverDifficulty.send(BedrockProtocol.class);

        final ViaBedrockConfig config = ViaBedrock.getConfig();
        if (config.shouldSendTabList()) {
            final PacketWrapper tabList = PacketWrapper.create(ClientboundPackets26_1.TAB_LIST, user);
            final String header = resolveTabListPlaceholders(config.getTabListHeader(), joinGameStorage.levelName());
            final String footer = resolveTabListPlaceholders(config.getTabListFooter(), joinGameStorage.levelName());
            tabList.write(Types.TAG, TextUtil.stringToNbt(header));
            tabList.write(Types.TAG, TextUtil.stringToNbt(footer));
            tabList.send(BedrockProtocol.class);
        }

        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.ADD_PLAYER, PlayerInfoUpdateAction.UPDATE_GAME_MODE, PlayerInfoUpdateAction.UPDATE_LATENCY)); // 动作
        playerInfoUpdate.write(Types.VAR_INT, 1); // 长度
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // UUID
        playerInfoUpdate.write(Types.STRING, StringUtil.encodeUUID(clientPlayer.javaUuid())); // 用户名
        playerInfoUpdate.write(Types.PROFILE_PROPERTY_ARRAY, new GameProfile.Property[0]); // 属性
        playerInfoUpdate.write(Types.VAR_INT, clientPlayer.javaGameMode().ordinal()); // 游戏模式
        final PacketSyncStorage packetSyncStorage = user.get(PacketSyncStorage.class);
        final int latency = packetSyncStorage.latencyMillis();
        playerInfoUpdate.write(Types.VAR_INT, latency); // 延迟
        final PlayerListStorage playerListStorage = user.get(PlayerListStorage.class);
        if (!playerListStorage.containsPlayer(clientPlayer.javaUuid())) {
            playerListStorage.addPlayer(clientPlayer.javaUuid(), clientPlayer.uniqueId(), user.getProtocolInfo().getUsername());
        }
        if (latency != PacketSyncStorage.UNKNOWN_LATENCY) {
            packetSyncStorage.markLatencyPublished(System.nanoTime());
        }
        playerInfoUpdate.send(BedrockProtocol.class);

        if (joinGameStorage.rainLevel() > 0F || joinGameStorage.lightningLevel() > 0F) {
            PacketFactory.sendJavaGameEvent(user, GameEventType.START_RAINING, 0F);
            if (joinGameStorage.rainLevel() > 0F) {
                PacketFactory.sendJavaGameEvent(user, GameEventType.RAIN_LEVEL_CHANGE, joinGameStorage.rainLevel());
            }
            if (joinGameStorage.lightningLevel() > 0F) {
                PacketFactory.sendJavaGameEvent(user, GameEventType.THUNDER_LEVEL_CHANGE, joinGameStorage.lightningLevel());
            }
        }

        final PacketWrapper setTime = PacketWrapper.create(ClientboundBedrockPackets.SET_TIME, user);
        setTime.write(BedrockTypes.VAR_INT, joinGameStorage.currentTime()); // 一天中的时间
        setTime.send(BedrockProtocol.class, false);
    }

}
