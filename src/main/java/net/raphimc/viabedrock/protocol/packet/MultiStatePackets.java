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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ServerboundConfigurationPackets1_21_9;
import com.viaversion.viaversion.util.Key;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTranslator;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.modinterface.ECClientLightInterface;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.DataValues;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.NyaNetworkStackLatencyPayload;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.Connection_DisconnectFailReason;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.MinecraftPacketIds;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PacketViolationSeverity;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PacketViolationType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.provider.NettyPipelineProvider;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.ClientChannelDiscoveryStorage;
import net.raphimc.viabedrock.protocol.storage.ClientSettingsStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.PacketSyncStorage;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import net.raphimc.viabedrock.protocol.storage.SpectatorCameraTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;

public class MultiStatePackets {

    private static final PacketHandler DISCONNECT_HANDLER = wrapper -> {
        final Connection_DisconnectFailReason disconnectReason = Connection_DisconnectFailReason.getByValue(wrapper.read(BedrockTypes.VAR_INT), Connection_DisconnectFailReason.Unknown); // 原因
        final boolean hasMessage = DisconnectPacketLayout.readHasMessage(wrapper); // 是否有消息
        if (hasMessage) {
            final Map<String, String> translations = BedrockProtocol.MAPPINGS.getBedrockResourcePacks().get(DataValues.VANILLA_RESOURCE_PACK_KEY).content().getLang("texts/en_US.lang");
            final Function<String, String> translator = k -> translations.getOrDefault(k, k);
            final String rawMessage = wrapper.read(BedrockTypes.STRING); // 消息
            wrapper.read(BedrockTypes.STRING); // 过滤后的消息
            wrapper.clearInputBuffer();
            final String translatedMessage = BedrockTranslator.translate(rawMessage, translator, new Object[0]);
            PacketFactory.writeJavaDisconnect(wrapper, translatedMessage + " §r(Reason: " + disconnectReason + ")");
        } else {
            wrapper.clearInputBuffer();
            PacketFactory.writeJavaDisconnect(wrapper, null);
        }
    };

    private static final PacketHandler PACKET_VIOLATION_WARNING_HANDLER = wrapper -> {
        final PacketViolationType type = PacketViolationType.getByValue(wrapper.read(BedrockTypes.VAR_INT), PacketViolationType.Unknown); // 类型
        final PacketViolationSeverity severity = PacketViolationSeverity.getByValue(wrapper.read(BedrockTypes.VAR_INT), PacketViolationSeverity.Unknown); // 严重程度
        final int packetIdCause = wrapper.read(BedrockTypes.VAR_INT); // 引发问题的数据包 ID
        final String context = wrapper.read(BedrockTypes.STRING); // 上下文

        final MinecraftPacketIds packet = MinecraftPacketIds.getByValue(packetIdCause);

        final String reason = "§4Packet violation warning: §c"
                + type.name()
                + " (" + severity.name() + ")\n"
                + "Violating Packet: " + (packet != null ? packet.name() : packetIdCause) + "\n"
                + (context.isEmpty() ? "No context provided" : (" Context: '" + context + "'"))
                + "\n\nPlease report this issue on the ViaBedrock GitHub page!";
        PacketFactory.writeJavaDisconnect(wrapper, reason);
    };

    private static final PacketHandler NETWORK_STACK_LATENCY_HANDLER = wrapper -> {
        final long timestamp = wrapper.read(BedrockTypes.LONG_LE); // 时间戳
        final boolean fromServer = wrapper.read(Types.BOOLEAN); // 来自服务端
        if (ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
            // MOT 860 的保活是 new NetworkStackLatencyPacket()（timestamp=0、
            // fromServer=false）。把它映射到 Java PING 会让 GanAC 排队 id 0，
            // 并被 releaseThrough(0) 排空所有在途的 GanAC ping。
            // 真实的 GanAC id 是 1..999_999_999，必须仍然往返 Java PING，
            // 这样补偿才不是 0ms。本地回显 MOT 0；只有 timestamp != 0 时才
            // 等待 Java PONG。PONG_HANDLER 写入 timestamp * 1_000_000。
            if (PacketSyncStorage.isMotKeepaliveTimestamp(timestamp)) {
                wrapper.cancel();
                final PacketWrapper echo = PacketWrapper.create(ServerboundBedrockPackets.NETWORK_STACK_LATENCY, wrapper.user());
                echo.write(BedrockTypes.LONG_LE, 0L);
                echo.write(Types.BOOLEAN, true);
                echo.sendToServer(BedrockProtocol.class);
                return;
            }
            final int id = wrapper.user().get(PacketSyncStorage.class).addNetworkStackLatencyResponse(timestamp);
            wrapper.write(Types.INT, id); // 参数
            return;
        }
        if (!fromServer) {
            wrapper.cancel();
            return;
        }
        final int id;
        if (NyaNetworkStackLatencyPayload.isOwnedBoundary(timestamp)) {
            final UUID identifier = new UUID(wrapper.read(BedrockTypes.LONG_LE), wrapper.read(BedrockTypes.LONG_LE));
            if (!Via.getManager().getProviders().get(NettyPipelineProvider.class).supportsFullJavaPingIds(wrapper.user())) {
                wrapper.cancel();
                return;
            }
            id = wrapper.user().get(PacketSyncStorage.class).addNetworkStackLatencyResponse(timestamp, identifier);
        } else {
            id = wrapper.user().get(PacketSyncStorage.class).addNetworkStackLatencyResponse(timestamp);
        }
        if (id < 0) {
            wrapper.cancel();
            return;
        }
        wrapper.write(Types.INT, id); // 参数
    };

    private static final PacketHandler PONG_HANDLER = wrapper -> {
        final PacketSyncStorage packetSyncStorage = wrapper.user().get(PacketSyncStorage.class);
        final int id = wrapper.read(Types.INT); // 参数
        final PacketSyncStorage.NetworkStackLatencyResponse response = packetSyncStorage.getNetworkStackLatencyResponse(id);
        if (response != null) {
            if (response.cancelled()) {
                wrapper.cancel();
                return;
            }
            if (response.nyaBoundaryPayload() && (response.boundaryDescriptor() == null
                    || response.boundaryDescriptor().releasePolicy()
                    != NyaNetworkStackLatencyPayload.ReleasePolicy.AFTER_CLIENT_PONG)) {
                // 未知专属格式不能降级即时确认；新版在下一次实际生成的 AuthInput 前释放。
                wrapper.cancel();
                packetSyncStorage.deferClientTickEndBoundary(response);
                return;
            }
            if (!response.nyaBoundaryPayload() && wrapper.user().getProtocolInfo().getServerState() != State.LOGIN) {
                final long nowNanos = System.nanoTime();
                final int serverTransportLatencyMillis = Via.getManager().getProviders().get(NettyPipelineProvider.class).getServerTransportLatencyMillis(wrapper.user());
                packetSyncStorage.updateLatency(nowNanos - response.requestNanos(), serverTransportLatencyMillis);
                publishJavaPlayerLatency(wrapper.user(), packetSyncStorage, nowNanos);
            }

            if (PacketSyncStorage.isJavaOnlyLatencyProbe(response)) {
                // HUD / 玩家列表探测。绝不可写回为基岩 NSL。
                wrapper.cancel();
                return;
            }

            writeNetworkStackLatencyResponse(wrapper, response);
        } else {
            wrapper.cancel();
            if (!packetSyncStorage.handleSyncTask(id)) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received unexpected pong with id " + id);
            }
        }
    };

    static void writeNetworkStackLatencyResponse(final PacketWrapper wrapper,
                                                final PacketSyncStorage.NetworkStackLatencyResponse response) {
        wrapper.write(BedrockTypes.LONG_LE, response.timestamp() * 1_000_000L); // 时间戳
        wrapper.write(Types.BOOLEAN, true); // 来自服务端
        if (response.boundaryIdentifier() != null) {
            wrapper.write(BedrockTypes.LONG_LE, response.boundaryIdentifier().getMostSignificantBits());
            wrapper.write(BedrockTypes.LONG_LE, response.boundaryIdentifier().getLeastSignificantBits());
        }
    }

    private static void publishJavaPlayerLatency(final UserConnection user, final PacketSyncStorage packetSyncStorage, final long nowNanos) {
        if (user.getProtocolInfo().getServerState() != State.PLAY || !packetSyncStorage.shouldPublishLatency(nowNanos)) return;

        final EntityTracker entityTracker = user.get(EntityTracker.class);
        if (entityTracker.getClientPlayer() == null) return;

        final UUID javaUuid = entityTracker.getClientPlayer().javaUuid();
        if (!user.get(PlayerListStorage.class).containsPlayer(javaUuid)) return;

        final PacketWrapper playerInfoUpdate = PacketFactory.createJavaPlayerLatencyUpdate(user, javaUuid, packetSyncStorage.latencyMillis());
        playerInfoUpdate.send(BedrockProtocol.class);
        packetSyncStorage.markLatencyPublished(nowNanos);
        ExperimentalFeatures.dispatchPlayerLatenciesUpdated(user, Map.of(javaUuid, packetSyncStorage.latencyMillis()));
    }

    public static final PacketHandler CLIENT_SETTINGS_HANDLER = wrapper -> {
        final String locale = wrapper.read(Types.STRING); // 语言环境
        final byte viewDistance = wrapper.read(Types.BYTE); // 视距
        final int chatVisibility = wrapper.read(Types.VAR_INT); // 聊天可见性
        final boolean chatColors = wrapper.read(Types.BOOLEAN); // 聊天颜色
        final short skinParts = wrapper.read(Types.UNSIGNED_BYTE); // 皮肤部件
        final int mainHand = wrapper.read(Types.VAR_INT); // 主手
        final boolean textFiltering = wrapper.read(Types.BOOLEAN); // 文本过滤
        final boolean allowsListing = wrapper.read(Types.BOOLEAN); // 允许列出
        final int particleStatus = wrapper.read(Types.VAR_INT); // 粒子状态
        wrapper.user().put(new ClientSettingsStorage(locale, viewDistance, chatVisibility, chatColors, skinParts, mainHand, textFiltering, allowsListing, particleStatus));

        wrapper.write(BedrockTypes.VAR_INT, (int) viewDistance); // 半径
        wrapper.write(Types.BYTE, ProtocolConstants.BEDROCK_REQUEST_CHUNK_RADIUS_MAX_RADIUS); // 最大半径
    };

    public static final PacketHandler CUSTOM_PAYLOAD_HANDLER = wrapper -> {
        wrapper.cancel();
        final String channel = Key.namespaced(wrapper.read(Types.STRING)); // 通道
        if (channel.equals(ViaBedrockUtilityInterface.PLAYER_STATE_CHANNEL)) {
            ViaBedrockUtilityInterface.handlePlayerState(
                    wrapper.user(), wrapper.read(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA)
            );
            return;
        }
        JavaCustomPayloadBridge.bridgeServerbound(channel, wrapper);
        if (channel.equals("minecraft:register")) {
            final List<String> channels = Arrays.asList(new String(wrapper.read(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA), StandardCharsets.UTF_8).split("\0"));
            wrapper.user().get(ClientChannelDiscoveryStorage.class).markRegistrationReceived();
            wrapper.user().get(ChannelStorage.class).addChannels(channels);

            if (channels.contains(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
                // 同时提前加入数据通道，使 setSkin() 在客户端 PLAY 阶段注册之前就能工作
                wrapper.user().get(ChannelStorage.class).addChannels(List.of(ViaBedrockUtilityInterface.CHANNEL));
                ViaBedrockUtilityInterface.confirmPresence(wrapper.user());
            }
            if (channels.contains(ViaBedrockUtilityInterface.SPECTATOR_NOCLIP_CAPABILITY)) {
                final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
                final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
                if (clientPlayer != null) {
                    final GameMode previous = clientPlayer.javaGameMode();
                    clientPlayer.updateJavaGameMode();
                    if (clientPlayer.javaGameMode() != previous
                            && wrapper.user().getProtocolInfo().getClientState() == State.PLAY) {
                        final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
                        final GameMode presented = spectatorCamera != null
                                ? spectatorCamera.projectJavaGameMode(clientPlayer.javaGameMode())
                                : clientPlayer.javaGameMode();
                        ClientPlayerPackets.sendJavaGameMode(wrapper.user(), presented);
                    }
                }
            }
            ViaBedrockUtilityInterface.confirmCustomEntityMotion(wrapper.user());
            ViaBedrockUtilityInterface.confirmWitherSkullMotion(wrapper.user());
            if (wrapper.user().getProtocolInfo().getClientState() == State.PLAY
                    && wrapper.user().get(EntityTracker.class) != null
                    && wrapper.user().get(EntityTracker.class).getClientPlayer() != null) {
                wrapper.user().get(EntityTracker.class).getClientPlayer().resendInputLocks();
            }
            ExperimentalFeatures.dispatchChannelRegistered(wrapper.user(), new java.util.HashSet<>(channels));
            if (wrapper.user().getProtocolInfo().getClientState() == State.CONFIGURATION
                    && channels.contains(ECClientLightInterface.CONFIRM_CHANNEL)) {
                ECClientLightInterface.confirmPresence(wrapper.user());
            }
        } else {
            ExperimentalFeatures.dispatchCustomPayload(channel, wrapper);
        }
    };

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientboundTransition(ClientboundBedrockPackets.DISCONNECT,
                ClientboundPackets26_1.DISCONNECT, DISCONNECT_HANDLER,
                ClientboundLoginPackets.LOGIN_DISCONNECT, DISCONNECT_HANDLER,
                ClientboundConfigurationPackets1_21_9.DISCONNECT, DISCONNECT_HANDLER
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.PACKET_VIOLATION_WARNING,
                ClientboundPackets26_1.DISCONNECT, PACKET_VIOLATION_WARNING_HANDLER,
                ClientboundLoginPackets.LOGIN_DISCONNECT, PACKET_VIOLATION_WARNING_HANDLER,
                ClientboundConfigurationPackets1_21_9.DISCONNECT, PACKET_VIOLATION_WARNING_HANDLER
        );
        protocol.registerClientboundTransition(ClientboundBedrockPackets.NETWORK_STACK_LATENCY,
                ClientboundPackets26_1.PING, NETWORK_STACK_LATENCY_HANDLER,
                State.LOGIN, (PacketHandler) wrapper -> {
                    NETWORK_STACK_LATENCY_HANDLER.handle(wrapper);
                    if (!wrapper.isCancelled()) {
                        wrapper.resetReader();
                        PONG_HANDLER.handle(wrapper);
                        if (!wrapper.isCancelled()) {
                            wrapper.setPacketType(ServerboundBedrockPackets.NETWORK_STACK_LATENCY);
                            wrapper.sendToServer(BedrockProtocol.class);
                            wrapper.cancel();
                        }
                    }
                },
                ClientboundConfigurationPackets1_21_9.PING, NETWORK_STACK_LATENCY_HANDLER
        );

        protocol.registerServerbound(ServerboundPackets26_1.PONG, ServerboundBedrockPackets.NETWORK_STACK_LATENCY, PONG_HANDLER);
        protocol.registerServerboundTransition(ServerboundConfigurationPackets1_21_9.PONG, ServerboundBedrockPackets.NETWORK_STACK_LATENCY, PONG_HANDLER);
    }

}
