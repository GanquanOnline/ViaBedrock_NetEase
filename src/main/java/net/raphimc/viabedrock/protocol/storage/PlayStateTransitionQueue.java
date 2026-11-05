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
 * along with <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Queue;
import java.util.logging.Level;

/**
 * 衔接网易的提前世界流与 ViaBedrock 的 Java 配置握手。
 *
 * <p>原版基岩服务器只在客户端确认出生（SET_LOCAL_PLAYER_AS_INITIALIZED）之后才发送世界数据包，
 * 而 ViaBedrock 是在 Java 客户端进入 PLAY 状态之后才发送该确认。网易 860 后端在
 * START_GAME 之后立刻推送区块、实体和物品栏数据包，此时 Java 客户端仍在完成配置阶段。
 * 协议的预游玩守卫丢弃了这些数据包，导致 PLAY_STATUS(PlayerSpawn) 从未初始化玩家，
 * 后端最终把沉默的客户端超时掉。</p>
 *
 * <p>此队列捕获早期的原始数据包，并在进入 Java PLAY 状态、START_GAME 的存储安装完成后，
 * 通过正常的服务端→客户端管线重放它们。</p>
 */
public class PlayStateTransitionQueue extends StoredObject {

    /**
     * 与提前加入流相关的数据包。MOT 860 的 {@code Player.processLogin()}
     * 在 START_GAME 之后立即发送 abilities、创造模式内容、手持物品装备和 trim 数据，
     * 而此时 Java 客户端仍处于 CONFIGURATION。这些数据包的 PLAY 处理器需要
     * START_GAME 的存储，因此在此处复制，并在 {@code finishCustomMappingStartGame} 之后
     * 重放。HUD / 教育相关的残留仍保持取消状态。
     *
     * <p>参考：MOT {@code Player.processLogin()} 中 {@code forceDataPacket(startGamePacket)} 之后
     * （{@code adventureSettings.update()}、{@code inventory.sendCreativeContents()}、
     * {@code inventory.sendHeldItemIfNotAir()}、{@code TrimDataPacket.getCachedPacket()}）。</p>
     */
    private static final EnumSet<ClientboundBedrockPackets> DEFERRED_PACKETS = EnumSet.of(
            ClientboundBedrockPackets.PLAY_STATUS,
            ClientboundBedrockPackets.SET_ENTITY_DATA,
            ClientboundBedrockPackets.ADD_ENTITY,
            ClientboundBedrockPackets.REMOVE_ENTITY,
            ClientboundBedrockPackets.ADD_ITEM_ENTITY,
            ClientboundBedrockPackets.ADD_PLAYER,
            ClientboundBedrockPackets.ADD_PAINTING,
            ClientboundBedrockPackets.SET_ENTITY_LINK,
            ClientboundBedrockPackets.SET_ENTITY_MOTION,
            ClientboundBedrockPackets.MOVE_ENTITY_ABSOLUTE,
            ClientboundBedrockPackets.MOVE_ENTITY_DELTA,
            ClientboundBedrockPackets.MOVE_PLAYER,
            ClientboundBedrockPackets.AVAILABLE_ENTITY_IDENTIFIERS,
            ClientboundBedrockPackets.NETWORK_CHUNK_PUBLISHER_UPDATE,
            ClientboundBedrockPackets.UPDATE_ATTRIBUTES,
            ClientboundBedrockPackets.MOB_EFFECT,
            ClientboundBedrockPackets.SET_DISPLAY_OBJECTIVE,
            ClientboundBedrockPackets.SET_SCORE,
            ClientboundBedrockPackets.UPDATE_BLOCK,
            ClientboundBedrockPackets.BLOCK_ENTITY_DATA,
            ClientboundBedrockPackets.CRAFTING_DATA,
            ClientboundBedrockPackets.LEVEL_CHUNK,
            ClientboundBedrockPackets.SUB_CHUNK,
            ClientboundBedrockPackets.BIOME_DEFINITION_LIST,
            ClientboundBedrockPackets.PLAYER_LIST,
            ClientboundBedrockPackets.SET_TIME,
            ClientboundBedrockPackets.SET_DIFFICULTY,
            ClientboundBedrockPackets.SET_SPAWN_POSITION,
            ClientboundBedrockPackets.GAME_RULES_CHANGED,
            ClientboundBedrockPackets.SET_COMMANDS_ENABLED,
            ClientboundBedrockPackets.CONFIRM_SKIN,
            ClientboundBedrockPackets.SYNC_SKIN,
            ClientboundBedrockPackets.NETEASE_JSON,
            // MOT 860 的 processLogin() 在 START_GAME 之后。丢弃这些包会让 Java
            // 缺少飞行/创造标志、CreativeContentCache 为空，并且没有
            // 手持物品的 MOB_EQUIPMENT。在 PLAY 之后重放，好让既有的
            // 处理器能用上 START_GAME 的存储。
            ClientboundBedrockPackets.UPDATE_ABILITIES,
            ClientboundBedrockPackets.UPDATE_ADVENTURE_SETTINGS,
            // CREATIVE_CONTENT / MOB_EQUIPMENT 在 InventoryBootstrapQueue 中等待
            // ITEM_REGISTRY，以便第一次重写不是空的。
            ClientboundBedrockPackets.TRIM_DATA,
            ClientboundBedrockPackets.CAMERA_PRESETS,
            ClientboundBedrockPackets.SYNC_ENTITY_PROPERTY,
            // MOT 的 processLogin() 以 addOnlinePlayer() 结束；插件可以在该回调中
            // 在 Java 仍处于 CONFIGURATION 时发出 PY_RPC / TEXT / 标题 / toast / boss。
            // 在 PLAY 之后重放，好让 PyRpcDispatcher 与 HudPackets 仍能合成
            // C2S 握手。
            // 参考：MOT Player.processLogin() this.server.addOnlinePlayer(this);
            ClientboundBedrockPackets.PY_RPC,
            ClientboundBedrockPackets.TEXT,
            ClientboundBedrockPackets.SET_TITLE,
            ClientboundBedrockPackets.TOAST_REQUEST,
            ClientboundBedrockPackets.BOSS_EVENT
    );

    private static final int MAX_QUEUED_PACKETS = 4096;
    private static final int MAX_QUEUED_BYTES = 32 * 1024 * 1024;

    private record DeferredPacket(ClientboundBedrockPackets packet, ByteBuf payload) {
    }

    private final Queue<DeferredPacket> queue = new ArrayDeque<>();
    private int queuedBytes;
    private boolean replaying;
    private boolean failed;

    public PlayStateTransitionQueue(final UserConnection user) {
        super(user);
    }

    public static boolean shouldDefer(final ClientboundBedrockPackets packet) {
        return DEFERRED_PACKETS.contains(packet);
    }

    public static boolean deferIfNeeded(final UserConnection user, final ClientboundBedrockPackets packet, final PacketWrapper wrapper) {
        final PlayStateTransitionQueue transitionQueue = user.get(PlayStateTransitionQueue.class);
        if (transitionQueue == null || !shouldDefer(packet)) {
            return false;
        }
        return transitionQueue.defer(packet, wrapper);
    }

    private boolean defer(final ClientboundBedrockPackets packet, final PacketWrapper wrapper) {
        if (this.replaying || this.failed) {
            return false;
        }

        final ByteBuf payload;
        try {
            payload = copyRawPayload(wrapper);
        } catch (final RuntimeException e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to copy early " + packet + " packet for replay", e);
            return false;
        }
        if (payload == null) {
            return false;
        }

        if (this.queue.size() >= MAX_QUEUED_PACKETS || this.queuedBytes + payload.readableBytes() > MAX_QUEUED_BYTES) {
            payload.release();
            this.fail();
            return true;
        }

        this.queue.add(new DeferredPacket(packet, payload));
        this.queuedBytes += payload.readableBytes();
        return true;
    }

    public void replayPackets() {
        if (this.replaying || this.failed || this.queue.isEmpty()) {
            this.clear();
            return;
        }

        this.replaying = true;
        try {
            DeferredPacket deferredPacket;
            while ((deferredPacket = this.queue.poll()) != null) {
                this.queuedBytes -= deferredPacket.payload().readableBytes();
                final PacketWrapper replay = PacketWrapper.create(deferredPacket.packet(), deferredPacket.payload(), this.user());
                try {
                    replay.send(BedrockProtocol.class, false);
                } catch (final Throwable t) {
                    // 单个格式错误的早期数据包不得中断后续加入流；在这个队列出现之前，
                    // 正常的管线本来也会丢弃它。
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to replay early " + deferredPacket.packet() + " packet", t);
                }
            }
        } finally {
            this.clear();
            this.replaying = false;
        }
    }

    private void fail() {
        this.failed = true;
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Early world packet queue exceeded its safety limit; dropping remaining early packets");
        this.clear();
    }

    private void clear() {
        DeferredPacket deferredPacket;
        while ((deferredPacket = this.queue.poll()) != null) {
            this.queuedBytes -= deferredPacket.payload().readableBytes();
            deferredPacket.payload().release();
        }
    }

    @Override
    public void onRemove() {
        this.clear();
    }

    private static ByteBuf copyRawPayload(final PacketWrapper wrapper) {
        final ByteBuf framedPacket = wrapper.user().getChannel().alloc().buffer();
        try {
            final int packetId = wrapper.getId();
            wrapper.writeToBuffer(framedPacket);
            if (packetId != -1) {
                final int serializedPacketId = Types.VAR_INT.readPrimitive(framedPacket);
                if (serializedPacketId != packetId) {
                    throw new IllegalStateException("Serialized packet id changed from " + packetId + " to " + serializedPacketId);
                }
            }
            return framedPacket.readRetainedSlice(framedPacket.readableBytes());
        } finally {
            framedPacket.release();
        }
    }
}
