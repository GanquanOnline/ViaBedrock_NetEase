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
package net.raphimc.viabedrock.protocol;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.providers.ViaProviders;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.packet.mapping.PacketMappings;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.protocol.StatelessTransitionProtocol;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayStatus;
import net.raphimc.viabedrock.protocol.packet.*;
import net.raphimc.viabedrock.protocol.provider.BlobCacheProvider;
import net.raphimc.viabedrock.protocol.provider.ClientAddressProvider;
import net.raphimc.viabedrock.protocol.provider.JavaClientEncryptionKeyProvider;
import net.raphimc.viabedrock.protocol.provider.NettyPipelineProvider;
import net.raphimc.viabedrock.protocol.provider.ResourcePackProvider;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.task.*;

import java.util.EnumSet;
import java.util.logging.Level;

public class BedrockProtocol extends StatelessTransitionProtocol<ClientboundBedrockPackets, ClientboundPackets26_1, ServerboundBedrockPackets, ServerboundPackets26_1> {

    public static final BedrockMappingData MAPPINGS = new BedrockMappingData();

    private static final EnumSet<ClientboundBedrockPackets> LOGIN_STATE_WHITELIST = EnumSet.of(
            ClientboundBedrockPackets.NETWORK_SETTINGS,
            ClientboundBedrockPackets.SERVER_TO_CLIENT_HANDSHAKE,
            ClientboundBedrockPackets.PLAY_STATUS,
            ClientboundBedrockPackets.DISCONNECT,
            ClientboundBedrockPackets.PACKET_VIOLATION_WARNING,
            ClientboundBedrockPackets.NETWORK_STACK_LATENCY
    );

    private static final EnumSet<ClientboundBedrockPackets> BEFORE_PLAY_STATE_WHITELIST = EnumSet.of(
            ClientboundBedrockPackets.RESOURCE_PACKS_INFO,
            ClientboundBedrockPackets.RESOURCE_PACK_DATA_INFO,
            ClientboundBedrockPackets.RESOURCE_PACK_CHUNK_DATA,
            ClientboundBedrockPackets.RESOURCE_PACK_STACK,
            ClientboundBedrockPackets.BIOME_DEFINITION_LIST,
            ClientboundBedrockPackets.DIMENSION_DATA,
            ClientboundBedrockPackets.AVAILABLE_COMMANDS,
            ClientboundBedrockPackets.START_GAME
    );

    static {
        BEFORE_PLAY_STATE_WHITELIST.addAll(LOGIN_STATE_WHITELIST);
    }

    static boolean shouldSynthesizeLoginSuccess(final State serverState, final ClientboundBedrockPackets packet) {
        return serverState == State.LOGIN
                && !LOGIN_STATE_WHITELIST.contains(packet)
                && BEFORE_PLAY_STATE_WHITELIST.contains(packet);
    }

    public BedrockProtocol() {
        super(ClientboundBedrockPackets.class, ClientboundPackets26_1.class, ServerboundBedrockPackets.class, ServerboundPackets26_1.class);
    }

    @Override
    protected void registerPackets() {
        LoginPackets.register(this);
        ConfigurationPackets.register(this);
        PlayPackets.register(this);
        MultiStatePackets.register(this);
        ResourcePackPackets.register(this);
        JoinPackets.register(this);
        ChatPackets.register(this);
        ClientPlayerPackets.register(this);
        OtherPlayerPackets.register(this);
        NeteasePackets.register(this);
        WorldPackets.register(this);
        EntityPackets.register(this);
        HudPackets.register(this);
        InventoryPackets.register(this);
        WorldEffectPackets.register(this);
        ScriptMessagePackets.register(this);
        UnhandledPackets.register(this);

        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            ExperimentalFeatures.registerModules();
            ExperimentalFeatures.registerPacketTranslators(this);
        }

        // 未处理数据包的回退（临时）
        for (ClientboundBedrockPackets packet : this.unmappedClientboundPacketType.getEnumConstants()) {
            if (!this.hasRegisteredClientbound(packet)) {
                this.cancelClientbound(packet);
            }
        }
        for (ServerboundPackets26_1 packet : this.unmappedServerboundPacketType.getEnumConstants()) {
            if (!this.hasRegisteredServerbound(packet)) {
                this.cancelServerbound(packet);
            }
        }
    }

    @Override
    public void register(final ViaProviders providers) {
        providers.require(NettyPipelineProvider.class);
        providers.register(ResourcePackProvider.class, ViaBedrock.getConfig().getPackCacheMode().createProvider());
        providers.register(BlobCacheProvider.class, ViaBedrock.getConfig().getBlobCacheMode().createProvider());
        providers.register(ClientAddressProvider.class, new ClientAddressProvider());
        providers.register(JavaClientEncryptionKeyProvider.class, new JavaClientEncryptionKeyProvider());
        providers.register(SkinProvider.class, new SkinProvider());

        if (!ViaBedrock.getConfig().getBlobCacheMode().equals(ViaBedrockConfig.BlobCacheMode.DISABLED) && !providers.get(BlobCacheProvider.class).hasBlob(0L)) {
            providers.get(BlobCacheProvider.class).addBlob(0L, new byte[0]);
        }

        Via.getPlatform().runRepeatingSync(new KeepAliveTask(), 20L);
        Via.getPlatform().runRepeatingSync(new ChunkTrackerTickTask(), 2L);
        Via.getPlatform().runRepeatingSync(new BlobCacheTickTask(), 2L);
        Via.getPlatform().runRepeatingSync(new EntityTrackerTickTask(), 1L);
        Via.getPlatform().runRepeatingSync(new InventoryTrackerTickTask(), 1L);

        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            ExperimentalFeatures.registerTasks();
        }
    }

    @Override
    public void init(UserConnection user) {
        final String locale = ViaBedrock.getConfig().getLanguage().isEmpty() ? "en_us" : ViaBedrock.getConfig().getLanguage();
        user.put(new ClientSettingsStorage(locale, 12, 0, true, (short) 127, 1, false, true, 0));
        user.put(new GameSessionStorage(user));
        user.put(new ResourcePackDownloadTracker());
        user.put(new BlobCache(user));
        user.put(new PacketSyncStorage(user));
        user.put(new ChannelStorage());
        user.put(new ClientChannelDiscoveryStorage());
        user.put(new JavaCustomPayloadRateLimitStorage());
        user.put(new JavaPlayerStateStorage());
        user.put(new ClientLightStorage());
        user.put(new CustomMappingSyncStorage(user));
        user.put(new EntityPropertyStorage(user));
        user.put(new PlayerListStorage());
        user.put(new ScoreboardTracker());
        user.put(new BossBarStorage(user));
        user.put(new InventoryBootstrapQueue(user));
        user.put(new InventoryTracker(user));
        user.put(new EnchantingSessionStorage(user));
        user.put(new AnvilSessionStorage(user));
        user.put(new TradeSessionStorage(user));
        user.put(new PlayerArmorHudTracker(user));
        user.put(new SpectatorMenuProjection(user));
        user.put(new SpectatorCameraTracker(user));

        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            ExperimentalFeatures.registerStorages(user);
        }
    }

    @Override
    public BedrockMappingData getMappingData() {
        if (ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
            ExperimentalFeatures.registerModules();
        }
        return MAPPINGS;
    }

    @Override
    public void transform(Direction direction, State state, PacketWrapper wrapper) throws InformativeException, CancelException {
        if (state == State.STATUS) { // 基岩协议中不存在 Status，它由传输层处理
            super.transform(direction, state, wrapper);
            return;
        }

        if (direction == Direction.CLIENTBOUND) {
            State serverState = wrapper.user().getProtocolInfo().getServerState();
            final ClientboundBedrockPackets packet = ClientboundBedrockPackets.getPacket(wrapper.getId());
            if (packet == null) {
                final ByteBuf content = ((PacketWrapperImpl) wrapper).getInputBuffer();
                ViaBedrock.getPlatform().getLogger().warning("Received unknown packet " + wrapper.getId() + " in state " + serverState + " with content: " + ByteBufUtil.hexDump(content));
                throw CancelException.generate();
            }
            final InventoryBootstrapQueue inventoryBootstrapQueue = wrapper.user().get(InventoryBootstrapQueue.class);
            if (inventoryBootstrapQueue != null && inventoryBootstrapQueue.deferIfNeeded(packet, wrapper, serverState)) {
                throw CancelException.generate();
            }
            if (shouldSynthesizeLoginSuccess(serverState, packet)) { // 基岩客户端可以跳过登录状态
                ViaBedrock.getPlatform().getLogger().warning("Skipping LOGIN state");
                final PacketWrapper playStatus = PacketWrapper.create(ClientboundBedrockPackets.PLAY_STATUS, wrapper.user());
                playStatus.write(Types.INT, PlayStatus.LoginSuccess.getValue()); // 状态
                playStatus.send(BedrockProtocol.class, false);
                wrapper.user().getProtocolInfo().setServerState(State.CONFIGURATION);
                serverState = State.CONFIGURATION;
            }
            // PLAY_STATUS 位于登录白名单中，因此下面的白名单检查会让
            // 网易提前发送的 PlayerSpawn 通过，进入 CONFIGURATION 处理器，而该处理器会取消它
            // 却不初始化玩家 -> 客户端永远不发送 PLAYER_AUTH_INPUT，后端将其超时。
            // 因此在这里于白名单之前拦截它，让它在 PLAY 阶段重放。
            if (serverState == State.CONFIGURATION && packet == ClientboundBedrockPackets.PLAY_STATUS
                    && PlayStateTransitionQueue.deferIfNeeded(wrapper.user(), packet, wrapper)) {
                throw CancelException.generate();
            }
            if (serverState != State.PLAY && !BEFORE_PLAY_STATE_WHITELIST.contains(packet)) { // 基岩客户端在收到 START_GAME 数据包之前会忽略大多数数据包
                if (serverState == State.CONFIGURATION && PlayStateTransitionQueue.deferIfNeeded(wrapper.user(), packet, wrapper)) {
                    throw CancelException.generate();
                }
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received packet " + packet + " outside PLAY state. Ignoring it.");
                throw CancelException.generate();
            }
        }

        /*if (direction == Direction.CLIENTBOUND) {
            System.out.println("PRE: direction = " + direction + ", state = " + state + ", packet=" + ClientboundBedrockPackets.getPacket(wrapper.getId()) + ", wrapper = " + wrapper);
        } else {
            System.out.println("PRE: direction = " + direction + ", state = " + state + ", packet=" + ServerboundPackets26_1.values()[wrapper.getId()] + ", wrapper = " + wrapper);
        }*/
        if (direction == Direction.SERVERBOUND && state == State.PLAY) {
            final JoinGate joinGate = wrapper.user().get(JoinGate.class);
            if (joinGate != null) {
                final ServerboundPackets26_1 packet = this.packetTypeById(ServerboundPackets26_1.class, wrapper.getId(), wrapper.getPacketType());
                if (packet == null ? joinGate.interceptUnknownServerboundBeforeOpen(wrapper) : joinGate.interceptServerboundBeforeOpen(packet, wrapper)) {
                    throw CancelException.generate();
                }
            }
        }
        try {
            super.transform(direction, state, wrapper);
            // PacketWrapper 会把未读取的基岩字节复制到重写后的 Java 数据包上。
            // 在按布局重映射之后丢弃任何残余尾部，以免 1.21.11 因多余数据被
            // 踢出。没有尾部的官方数据包是空操作。
            if (direction == Direction.CLIENTBOUND && !wrapper.isCancelled()) {
                PacketLeftoverLayout.discardUnreadInput(wrapper);
            }
            if (direction == Direction.CLIENTBOUND && state == State.PLAY) {
                final JoinGate joinGate = wrapper.user().get(JoinGate.class);
                if (joinGate != null && !wrapper.isCancelled()) {
                    final ClientboundPackets26_1 packet = this.packetTypeById(ClientboundPackets26_1.class, wrapper.getId(), wrapper.getPacketType());
                    if (packet == null ? joinGate.interceptUnknownClientbound(wrapper) : joinGate.interceptClientbound(packet, wrapper)) {
                        throw CancelException.generate();
                    }
                }
            }
        } catch (CancelException e) {
            throw e;
        }
        /*if (direction == Direction.CLIENTBOUND) {
            System.out.println("POST: direction = " + direction + ", state = " + state + ", packet=" + ClientboundPackets26_1.values()[wrapper.getId()] + ", wrapper = " + wrapper);
        } else {
            System.out.println("POST: direction = " + direction + ", state = " + state + ", packet=" + ServerboundBedrockPackets.getPacket(wrapper.getId()) + ", wrapper = " + wrapper);
        }*/
    }

    private <T extends Enum<T> & PacketType> T packetTypeById(final Class<T> packetClass, final int id, final PacketType packetType) {
        if (packetClass.isInstance(packetType)) {
            return packetClass.cast(packetType);
        }
        for (T packet : packetClass.getEnumConstants()) {
            if (packet.getId() == id) {
                return packet;
            }
        }
        return null;
    }

    public static void kickForIllegalState(final UserConnection user, final String reason) {
        kickForIllegalState(user, reason, null);
    }

    public static void kickForIllegalState(final UserConnection user, final String reason, final Throwable e) {
        ViaBedrock.getPlatform().getLogger().log(Level.SEVERE, "Illegal state: " + reason, e);

        final PacketType disconnectPacketType = switch (user.getProtocolInfo().getServerState()) {
            case LOGIN -> ClientboundLoginPackets.LOGIN_DISCONNECT;
            case CONFIGURATION -> ClientboundConfigurationPackets1_21_9.DISCONNECT;
            case PLAY -> ClientboundPackets26_1.DISCONNECT;
            default -> throw new IllegalStateException("Unexpected state: " + user.getProtocolInfo().getServerState());
        };
        try {
            final PacketWrapper disconnect = PacketWrapper.create(disconnectPacketType, user);
            PacketFactory.writeJavaDisconnect(disconnect, "§4ViaBedrock encountered an error:\n§c" + reason + "\n\n§rPlease report this issue on the ViaBedrock GitHub page.");
            disconnect.send(BedrockProtocol.class);
        } catch (Throwable ignored) {
        }

        if (user.getChannel() != null) {
            user.getChannel().flush();
            user.getChannel().close();
        }
    }

    // 仅用于实验性功能
    public PacketMappings getClientboundMappings() {
        return this.clientboundMappings;
    }

    // 仅用于实验性功能
    public PacketMappings getServerboundMappings() {
        return this.serverboundMappings;
    }

}
