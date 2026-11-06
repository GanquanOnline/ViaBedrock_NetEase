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
import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.api.util.BitSets;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.GameType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerPositionModeComponent_PositionMode;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.EquipmentSlot;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import net.raphimc.viabedrock.protocol.model.*;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.EntityPropertyStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import net.raphimc.viabedrock.protocol.storage.SpectatorCameraTracker;
import net.raphimc.viabedrock.protocol.storage.SpectatorMenuProjection;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.UUID;
import java.util.logging.Level;

public class OtherPlayerPackets {

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.ADD_PLAYER, ClientboundPackets26_1.ADD_ENTITY, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final PlayerListStorage playerListStorage = wrapper.user().get(PlayerListStorage.class);

            final UUID uuid = wrapper.read(BedrockTypes.UUID); // UUID
            final String username = wrapper.read(BedrockTypes.STRING); // 用户名
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final String platformOnlineId = wrapper.read(BedrockTypes.STRING); // 平台在线 ID
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            final Position3f motion = wrapper.read(BedrockTypes.POSITION_3F); // 运动
            final Position3f rotation = wrapper.read(BedrockTypes.POSITION_3F); // 旋转
            final BedrockItem item = wrapper.read(itemRewriter.itemType()); // 手持物品
            final GameType gameType = GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT)); // 游戏类型
            final EntityData[] entityData = wrapper.read(BedrockTypes.ENTITY_DATA_ARRAY); // 实体数据
            final EntityProperties entityProperties = EntityPropertyStorage.getOrCreate(wrapper.user())
                    .resolve("minecraft:player", wrapper.read(BedrockTypes.ENTITY_PROPERTIES)); // 实体属性
            final PlayerAbilities abilities = wrapper.read(BedrockTypes.PLAYER_ABILITIES); // 能力
            final EntityLink[] entityLinks = wrapper.read(BedrockTypes.ENTITY_LINK_ARRAY); // 实体链接

            final PlayerEntity entity = entityTracker.addEntity(new PlayerEntity(wrapper.user(), entityRuntimeId, entityTracker.getNextJavaEntityId(), uuid, abilities));
            entity.setPosition(position.add(0F, entity.eyeOffset(), 0F));
            entity.setRotation(rotation);
            entity.setEntityProperties(entityProperties);
            entity.updateName(username);

            final SpectatorMenuProjection spectatorMenu = wrapper.user().get(SpectatorMenuProjection.class);
            spectatorMenu.beforeEntitySpawn(uuid);

            final GameProfile.Property[] profileProperties = new GameProfile.Property[]{
                    new GameProfile.Property("platform_online_id", platformOnlineId),
                    new GameProfile.Property("device_id", wrapper.read(BedrockTypes.STRING)), // 设备 ID
                    new GameProfile.Property("device_os", wrapper.read(BedrockTypes.INT_LE).toString()) // 设备操作系统
            };
            final net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode javaGameMode =
                    GameTypeRewriter.getEffectiveGameMode(gameType, gameSession.getLevelGameType());
            playerListStorage.putJavaProfileIfAbsent(new PlayerListStorage.JavaProfile(
                    uuid,
                    StringUtil.encodeUUID(uuid),
                    profileProperties,
                    javaGameMode,
                    false,
                    playerListStorage.serverLatency(uuid),
                    null
            ));
            playerListStorage.updateJavaGameMode(uuid, javaGameMode);

            final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, wrapper.user());
            playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.ADD_PLAYER, PlayerInfoUpdateAction.UPDATE_GAME_MODE, PlayerInfoUpdateAction.UPDATE_LATENCY)); // 动作
            playerInfoUpdate.write(Types.VAR_INT, 1); // 长度
            playerInfoUpdate.write(Types.UUID, uuid); // UUID
            playerInfoUpdate.write(Types.STRING, StringUtil.encodeUUID(uuid)); // 用户名
            playerInfoUpdate.write(Types.PROFILE_PROPERTY_ARRAY, profileProperties); // 属性
            playerInfoUpdate.write(Types.VAR_INT, javaGameMode.ordinal()); // 游戏模式
            playerInfoUpdate.write(Types.VAR_INT, playerListStorage.serverLatency(uuid)); // 延迟
            playerInfoUpdate.send(BedrockProtocol.class);

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.UUID, uuid); // UUID
            wrapper.write(Types.VAR_INT, EntityTypes1_21_11.PLAYER.getId()); // 类型 ID
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y()); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(motion.x(), motion.y(), motion.z())); // 速度
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.x())); // 俯仰角
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.y())); // 偏航角
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.z())); // 头部偏航角
            wrapper.write(Types.VAR_INT, 0); // 数据
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();
            wrapper.user().get(SpectatorCameraTracker.class).onJavaPlayerSpawned(entity);
            spectatorMenu.afterEntitySpawn(uuid);

            final PacketWrapper setEquipment = PacketWrapper.create(ClientboundPackets26_1.SET_EQUIPMENT, wrapper.user());
            setEquipment.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            setEquipment.write(Types.BYTE, (byte) EquipmentSlot.MAINHAND.ordinal()); // 槽位
            setEquipment.write(VersionedTypes.V26_1.item, itemRewriter.javaItem(item)); // 物品
            setEquipment.send(BedrockProtocol.class);

            entity.sendInitialEntityData();
            entity.updateEntityData(entityData);
            entity.updateEntityProperties(entityProperties);
            ExperimentalFeatures.dispatchEntityLinks(wrapper.user(), entityLinks);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOVE_PLAYER, ClientboundPackets26_1.ENTITY_POSITION_SYNC, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            final Position3f rotation = wrapper.read(BedrockTypes.POSITION_3F); // 旋转
            final PlayerPositionModeComponent_PositionMode mode = PlayerPositionModeComponent_PositionMode.getByValue(wrapper.read(Types.BYTE), PlayerPositionModeComponent_PositionMode.OnlyHeadRot); // 模式
            final boolean onGround = wrapper.read(Types.BOOLEAN); // 是否在地面
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 所骑乘实体的运行时 ID
            if (BedrockWire.applies(BedrockDeviation.MOVE_PLAYER_TELEPORT_FLAG)) {
                // 2168 起传送原因/物品由外层 bool 决定，不再只看模式
                if (wrapper.read(Types.BOOLEAN)) {
                    wrapper.read(BedrockTypes.INT_LE); // 传送原因
                    wrapper.read(BedrockTypes.INT_LE); // 实体类型
                }
            } else if (mode == PlayerPositionModeComponent_PositionMode.Teleport) {
                wrapper.read(BedrockTypes.INT_LE); // 传送原因
                wrapper.read(BedrockTypes.INT_LE); // 实体类型
            }
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 刻

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }
            if (!entity.javaType().isOrHasParent(EntityTypes1_21_11.PLAYER)) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received move player packet for non-player entity: " + entity.javaType());
                wrapper.cancel();
                return;
            }
            if (mode == PlayerPositionModeComponent_PositionMode.OnlyHeadRot) {
                entity.setRotation(new Position3f(rotation.x(), entity.rotation().y(), entity.rotation().z()));
                wrapper.setPacketType(ClientboundPackets26_1.MOVE_ENTITY_ROT);
                wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
                wrapper.write(Types.BYTE, MathUtil.float2Byte(entity.rotation().y())); // 偏航角
                wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.x())); // 俯仰角
                wrapper.write(Types.BOOLEAN, entity.isOnGround()); // 是否在地面
                return;
            }

            final Position3f previousPosition = entity.position();
            final Position3f previousRotation = entity.rotation();
            final boolean previousOnGround = entity.isOnGround();
            final boolean duplicatePosition = previousPosition.equals(position) && previousRotation.equals(rotation) && previousOnGround == onGround;

            // MOT sendPosition(..., targets==null) 把 MODE_NORMAL=0 当作本地
            // 橡皮筋回拉。Java 1.21.11 没有发往自身的 ENTITY_POSITION_SYNC，所以每个
            // 本地 MOVE_PLAYER 都必须转成 PLAYER_POSITION。只有 Respawn/RESET
            // 使用伪造的传送 ID，以免 AuthInput 发出 HandledTeleport。
            // 参考：MOT Player.sendPosition；OtherPlayerPacketsMovePlayerTest。
            if (entity instanceof ClientPlayerEntity clientPlayer && shouldRewriteLocalMovePlayerToJavaPosition(mode)) {
                if (shouldCancelDuplicateLocalTeleport(mode, duplicatePosition)) {
                    wrapper.cancel();
                    return;
                }
                clientPlayer.setPositionFromServer(position);
                entity.setRotation(rotation);
                entity.setOnGround(onGround);
                ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);
                wrapper.setPacketType(ClientboundPackets26_1.PLAYER_POSITION);
                clientPlayer.writePlayerPositionPacketToClient(wrapper, Relative.NONE, isFakeJavaTeleportForMovePlayer(mode));
                return;
            }

            entity.setPosition(position);
            entity.setRotation(rotation);
            entity.setOnGround(onGround);
            ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y() - entity.eyeOffset()); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.DOUBLE, 0D); // 速度 x
            wrapper.write(Types.DOUBLE, 0D); // 速度 y
            wrapper.write(Types.DOUBLE, 0D); // 速度 z
            wrapper.write(Types.FLOAT, rotation.y()); // 偏航角
            wrapper.write(Types.FLOAT, rotation.x()); // 俯仰角
            wrapper.write(Types.BOOLEAN, onGround); // 是否在地面

            PacketFactory.sendJavaRotateHead(wrapper.user(), entity);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_SKIN, null, wrapper -> {
            wrapper.cancel();
            final UUID uuid = wrapper.read(BedrockTypes.UUID); // UUID
            final SkinData skin = wrapper.read(BedrockTypes.SKIN); // 皮肤
            wrapper.read(BedrockTypes.STRING); // 新皮肤名称
            wrapper.read(BedrockTypes.STRING); // 旧皮肤名称
            wrapper.read(Types.BOOLEAN); // 可信皮肤

            // 与 PLAYER_LIST 的重映射保持一致：本地玩家的基岩 UUID 与其 javaUuid 不同，因此
            // 以基岩 UUID 寻址的运行时皮肤更新必须重映射到 javaUuid。否则
            // mod 会按 javaUuid 索引皮肤，本地玩家自己的皮肤更换将永远不会生效。
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final UUID targetUuid = (clientPlayer != null && uuid.equals(clientPlayer.bedrockUuid()))
                    ? clientPlayer.javaUuid() : uuid;

            Via.getManager().getProviders().get(SkinProvider.class).setSkin(wrapper.user(), targetUuid, skin);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_ABILITIES, ClientboundPackets26_1.PLAYER_ABILITIES, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final PlayerAbilities abilities = wrapper.read(BedrockTypes.PLAYER_ABILITIES); // 能力
            final Entity entity = entityTracker.getEntityByUid(abilities.entityUniqueId());
            if (entity instanceof ClientPlayerEntity clientPlayer) {
                clientPlayer.setAbilities(abilities, wrapper);
            } else if (entity instanceof PlayerEntity player) {
                player.setAbilities(abilities);
                wrapper.cancel();
            } else {
                wrapper.cancel();
            }
        });
    }

    static boolean shouldRewriteLocalMovePlayerToJavaPosition(final PlayerPositionModeComponent_PositionMode mode) {
        return mode == PlayerPositionModeComponent_PositionMode.Normal
                || mode == PlayerPositionModeComponent_PositionMode.Respawn
                || mode == PlayerPositionModeComponent_PositionMode.Teleport;
    }

    static boolean shouldCancelDuplicateLocalTeleport(final PlayerPositionModeComponent_PositionMode mode, final boolean duplicatePosition) {
        return mode == PlayerPositionModeComponent_PositionMode.Teleport && duplicatePosition;
    }

    static boolean isFakeJavaTeleportForMovePlayer(final PlayerPositionModeComponent_PositionMode mode) {
        return mode == PlayerPositionModeComponent_PositionMode.Respawn;
    }

}
