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
package net.raphimc.viabedrock.api.util;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.libs.mcstructs.text.TextComponent;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.model.container.HorseContainer;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ServerboundLoadingScreenPacketType;
import net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent;
import net.raphimc.viabedrock.protocol.data.enums.java.GameEventType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.CustomChatCompletionsAction;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.InteractPacketLayout;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.Map;
import java.util.UUID;

public class PacketFactory {

    public static PacketWrapper createJavaPlayerLatencyUpdate(final UserConnection user, final UUID uuid, final int latencyMillis) {
        return createJavaPlayerLatencyUpdate(user, Map.of(uuid, latencyMillis));
    }

    public static PacketWrapper createJavaPlayerLatencyUpdate(final UserConnection user, final Map<UUID, Integer> latencies) {
        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        writeJavaPlayerLatencyUpdate(playerInfoUpdate, latencies);
        return playerInfoUpdate;
    }

    static void writeJavaPlayerLatencyUpdate(final PacketWrapper playerInfoUpdate, final UUID uuid, final int latencyMillis) {
        writeJavaPlayerLatencyUpdate(playerInfoUpdate, Map.of(uuid, latencyMillis));
    }

    static void writeJavaPlayerLatencyUpdate(final PacketWrapper playerInfoUpdate, final Map<UUID, Integer> latencies) {
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_LATENCY)); // 动作
        playerInfoUpdate.write(Types.VAR_INT, latencies.size()); // 长度
        for (Map.Entry<UUID, Integer> entry : latencies.entrySet()) {
            playerInfoUpdate.write(Types.UUID, entry.getKey()); // UUID
            playerInfoUpdate.write(Types.VAR_INT, entry.getValue()); // 延迟
        }
    }

    public static void sendJavaSystemChat(final UserConnection user, final Tag message) {
        final PacketWrapper systemChat = PacketWrapper.create(ClientboundPackets26_1.SYSTEM_CHAT, user);
        systemChat.write(Types.TAG, message); // 消息
        systemChat.write(Types.BOOLEAN, false); // 叠加层
        systemChat.send(BedrockProtocol.class);
    }

    public static void sendJavaBlockEntityData(final UserConnection user, final BlockPosition position, final BlockEntity blockEntity) {
        final PacketWrapper blockEntityData = PacketWrapper.create(ClientboundPackets26_1.BLOCK_ENTITY_DATA, user);
        blockEntityData.write(Types.BLOCK_POSITION1_14, position); // 位置
        blockEntityData.write(Types.VAR_INT, blockEntity.typeId()); // 类型
        blockEntityData.write(Types.COMPOUND_TAG, blockEntity.tag()); // 方块实体数据
        blockEntityData.send(BedrockProtocol.class);
    }

    public static void sendJavaCustomChatCompletions(final UserConnection user, final CustomChatCompletionsAction action, final String[] entries) {
        final PacketWrapper customChatCompletions = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_CHAT_COMPLETIONS, user);
        customChatCompletions.write(Types.VAR_INT, action.ordinal()); // 动作
        customChatCompletions.write(Types.STRING_ARRAY, entries); // 条目
        customChatCompletions.send(BedrockProtocol.class);
    }

    public static void sendJavaContainerSetContent(final UserConnection user, final Container container) {
        final PacketWrapper containerSetContent = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_SET_CONTENT, user);
        writeJavaContainerSetContent(containerSetContent, container);
        containerSetContent.send(BedrockProtocol.class);
    }

    public static void sendJavaGameEvent(final UserConnection user, final GameEventType event, final float value) {
        final PacketWrapper gameEvent = PacketWrapper.create(ClientboundPackets26_1.GAME_EVENT, user);
        gameEvent.write(Types.UNSIGNED_BYTE, (short) event.ordinal()); // 事件 ID
        gameEvent.write(Types.FLOAT, value); // 值
        gameEvent.send(BedrockProtocol.class);
    }

    public static void sendJavaEntityEvent(final UserConnection user, final Entity entity, final EntityEvent event) {
        final PacketWrapper entityEvent = PacketWrapper.create(ClientboundPackets26_1.ENTITY_EVENT, user);
        entityEvent.write(Types.INT, entity.javaId()); // 实体 ID
        entityEvent.write(Types.BYTE, event.getValue()); // 事件
        entityEvent.send(BedrockProtocol.class);
    }

    public static void sendJavaContainerClose(final UserConnection user, final int containerId) {
        final PacketWrapper containerClose = PacketWrapper.create(ClientboundPackets26_1.CONTAINER_CLOSE, user);
        containerClose.write(Types.VAR_INT, containerId); // 容器 ID
        containerClose.send(BedrockProtocol.class);
    }

    public static void sendJavaRotateHead(final UserConnection user, final Entity entity) {
        final PacketWrapper rotateHead = PacketWrapper.create(ClientboundPackets26_1.ROTATE_HEAD, user);
        rotateHead.write(Types.VAR_INT, entity.javaId()); // 实体 ID
        rotateHead.write(Types.BYTE, MathUtil.float2Byte(entity.rotation().z())); // 头部偏航角
        rotateHead.send(BedrockProtocol.class);
    }

    public static void sendJavaBlockChangedAck(final UserConnection user, final int sequence) {
        final PacketWrapper blockChangedAck = PacketWrapper.create(ClientboundPackets26_1.BLOCK_CHANGED_ACK, user);
        blockChangedAck.write(Types.VAR_INT, sequence); // 序列号
        blockChangedAck.send(BedrockProtocol.class);
    }

    public static void sendJavaBlockUpdate(final UserConnection user, final BlockPosition position, final int blockState) {
        final PacketWrapper blockUpdate = PacketWrapper.create(ClientboundPackets26_1.BLOCK_UPDATE, user);
        blockUpdate.write(Types.BLOCK_POSITION1_14, position); // 位置
        blockUpdate.write(Types.VAR_INT, blockState); // 方块状态
        blockUpdate.send(BedrockProtocol.class);
    }

    public static void sendJavaLevelParticles(final UserConnection user, final Position3f position, final BedrockMappingData.JavaParticle particle) {
        final PacketWrapper levelParticles = PacketWrapper.create(ClientboundPackets26_1.LEVEL_PARTICLES, user);
        writeJavaLevelParticles(levelParticles, position, particle);
        levelParticles.send(BedrockProtocol.class);
    }

    public static void sendBedrockContainerClose(final UserConnection user, final byte containerId, final ContainerType containerType) {
        final PacketWrapper containerClose = PacketWrapper.create(ServerboundBedrockPackets.CONTAINER_CLOSE, user);
        containerClose.write(Types.BYTE, containerId); // 容器 ID
        containerClose.write(Types.BYTE, (byte) containerType.getValue()); // 类型
        containerClose.write(Types.BOOLEAN, false); // 服务端发起
        containerClose.sendToServer(BedrockProtocol.class);
    }

    public static void sendBedrockOpenInventory(final UserConnection user) {
        final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, user);
        interact.write(Types.UNSIGNED_BYTE, (short) InteractPacket_Action.OpenInventory.getValue()); // 动作
        interact.write(BedrockTypes.UNSIGNED_VAR_LONG, user.get(EntityTracker.class).getClientPlayer().runtimeId()); // 目标实体运行时 ID
        InteractPacketLayout.writePosition(interact, InteractPacket_Action.OpenInventory, Position3f.ZERO);
        interact.sendToServer(BedrockProtocol.class);
    }

    public static void sendBedrockLoadingScreen(final UserConnection user, final ServerboundLoadingScreenPacketType type, final Long loadingScreenId) {
        final PacketWrapper loadingScreen = PacketWrapper.create(ServerboundBedrockPackets.LOADING_SCREEN, user);
        loadingScreen.write(BedrockTypes.VAR_INT, type.getValue()); // 类型
        loadingScreen.write(Types.BOOLEAN, loadingScreenId != null); // 是否有加载界面 ID
        if (loadingScreenId != null) {
            loadingScreen.write(BedrockTypes.UNSIGNED_INT_LE, loadingScreenId); // 加载界面 ID
        }
        // scheduleSendToServer 会推迟到事件循环末尾：在服务端→客户端转换中同步调用 sendToServer，
        // 会在同一线程重入客户端→服务端管道并被静默取消（sendToServer0 吞掉了 CancelException）。
        loadingScreen.scheduleSendToServer(BedrockProtocol.class);
    }

    public static void writeJavaDisconnect(final PacketWrapper wrapper, final String reason) {
        writeJavaDisconnectComponent(wrapper, reason != null ? TextUtil.stringToTextComponent(reason) : TextComponent.empty());
    }

    public static void writeJavaDisconnectComponent(final PacketWrapper wrapper, final TextComponent reason) {
        final TextComponent safeReason = reason != null ? reason : TextComponent.empty();
        switch (wrapper.getPacketType().state()) {
            case LOGIN -> wrapper.write(Types.COMPONENT, TextUtil.textComponentToGson(safeReason));
            case CONFIGURATION, PLAY -> wrapper.write(Types.TAG, TextUtil.textComponentToNbt(safeReason));
            default -> throw new IllegalStateException("Unexpected state: " + wrapper.getPacketType().state());
        }
    }

    public static void writeJavaContainerSetContent(final PacketWrapper wrapper, final Container container) {
        wrapper.write(Types.VAR_INT, container.javaContainerId()); // 容器 ID
        wrapper.write(Types.VAR_INT, 0); // 修订号
        wrapper.write(VersionedTypes.V26_1.itemArray, container.getJavaItems()); // 物品
        wrapper.write(VersionedTypes.V26_1.item, wrapper.user().get(InventoryTracker.class).getHudContainer().getJavaItem(0)); // 光标物品
    }

    /**
     * Java 1.21.11 的坐骑物品栏大小固定为 MOUNT_SCREEN_OPEN 列。
     * 当 MOT 的 HorseInventory 在 CONTAINER_OPEN 之后增长时需要重新打开。
     */
    public static void sendJavaMountScreenOpen(final UserConnection user, final HorseContainer horse) {
        final PacketWrapper open = PacketWrapper.create(ClientboundPackets26_1.MOUNT_SCREEN_OPEN, user);
        open.write(Types.VAR_INT, horse.javaContainerId());
        open.write(Types.VAR_INT, horse.javaColumns());
        open.write(Types.INT, horse.javaEntityId());
        open.send(BedrockProtocol.class);
    }

    public static void writeJavaLevelParticles(final PacketWrapper wrapper, final Position3f position, final BedrockMappingData.JavaParticle particle) {
        wrapper.write(Types.BOOLEAN, false); // 覆写限流器
        wrapper.write(Types.BOOLEAN, false); // 始终显示
        wrapper.write(Types.DOUBLE, (double) position.x()); // x
        wrapper.write(Types.DOUBLE, (double) position.y()); // y
        wrapper.write(Types.DOUBLE, (double) position.z()); // z
        wrapper.write(Types.FLOAT, particle.offsetX()); // 偏移 x
        wrapper.write(Types.FLOAT, particle.offsetY()); // 偏移 y
        wrapper.write(Types.FLOAT, particle.offsetZ()); // 偏移 z
        wrapper.write(Types.FLOAT, particle.speed()); // 速度
        wrapper.write(Types.INT, particle.count()); // 数量
        wrapper.write(VersionedTypes.V26_1.particle, particle.particle().copy()); // 粒子数据
    }

}
