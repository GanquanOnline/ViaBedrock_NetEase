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

import com.google.common.collect.Lists;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.PaintingVariant;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.CustomEntity;
import net.raphimc.viabedrock.api.model.entity.DroppedItemEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.LivingEntity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.api.resourcepack.definition.EntityDefinitions;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.storage.RidingTracker;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.RegistryUtil;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingAccess;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.AnimateAction;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.EquipmentSlot;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.data.generated.java.RegistryKeys;
import net.raphimc.viabedrock.protocol.model.*;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.EntityPropertyStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.function.LongFunction;
import java.util.logging.Level;

public class EntityPackets {

    private static final float PAINTING_POS_OFFSET = -0.46875F;
    private static final String FALLING_BLOCK_IDENTIFIER = "minecraft:falling_block";
    private static final String FISHING_HOOK_IDENTIFIER = "minecraft:fishing_hook";

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.ADD_ENTITY, ClientboundPackets26_1.ADD_ENTITY, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final String type = Key.namespaced(wrapper.read(BedrockTypes.STRING)); // 类型
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            final Position3f motion = wrapper.read(BedrockTypes.POSITION_3F); // 运动向量
            final Position3f rotation = wrapper.read(BedrockTypes.POSITION_3F); // 旋转
            wrapper.read(BedrockTypes.FLOAT_LE); // 身体旋转
            final EntityAttribute[] attributes = new EntityAttribute[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // 属性数量
            for (int i = 0; i < attributes.length; i++) {
                final String name = wrapper.read(BedrockTypes.STRING); // 名称
                final float minValue = wrapper.read(BedrockTypes.FLOAT_LE); // 最小值
                final float currentValue = wrapper.read(BedrockTypes.FLOAT_LE); // 当前值
                final float maxValue = wrapper.read(BedrockTypes.FLOAT_LE); // 最大值
                attributes[i] = new EntityAttribute(name, currentValue, minValue, maxValue);
            }
            final EntityData[] entityData = wrapper.read(BedrockTypes.ENTITY_DATA_ARRAY); // 实体数据
            final EntityProperties entityProperties = EntityPropertyStorage.getOrCreate(wrapper.user())
                    .resolve(type, wrapper.read(BedrockTypes.ENTITY_PROPERTIES)); // 实体属性
            final EntityLink[] entityLinks = wrapper.read(BedrockTypes.ENTITY_LINK_ARRAY); // 实体链接
            EntityPacketLayout.skipPassengerOfBlockArguments(wrapper);

            final int javaSpawnData;
            if (type.equals(FISHING_HOOK_IDENTIFIER)) {
                final Integer ownerJavaId = getFishingHookOwnerJavaId(entityData, ownerUniqueId -> {
                    final Entity owner = entityTracker.getEntityByUid(ownerUniqueId);
                    return owner instanceof PlayerEntity ? owner.javaId() : null;
                });
                if (ownerJavaId == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Not spawning fishing hook with unique ID " + entityUniqueId + " and runtime ID " + entityRuntimeId + " because its owner is missing or invalid");
                    wrapper.cancel();
                    return;
                }
                javaSpawnData = ownerJavaId;
            } else if (type.equals(FALLING_BLOCK_IDENTIFIER)) {
                final BlockStateRewriter blockStateRewriter = wrapper.user().get(BlockStateRewriter.class);
                final CustomMappingAccess customMappingAccess = wrapper.user().get(CustomMappingSyncStorage.class).access();
                final Integer javaBlockStateId = getFallingBlockJavaBlockStateId(entityData, bedrockRuntimeId ->
                        customMappingAccess.resolveBedrockRuntimeId(bedrockRuntimeId, blockStateRewriter.javaId(bedrockRuntimeId), "falling block spawn data").javaBlockStateId());
                if (javaBlockStateId == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Not spawning falling block with unique ID " + entityUniqueId + " and runtime ID " + entityRuntimeId + " because its block state metadata is missing or invalid");
                    wrapper.cancel();
                    return;
                }
                javaSpawnData = javaBlockStateId;
            } else {
                javaSpawnData = 0;
            }

            final Entity entity;
            final Entity resolvedEntity = ExperimentalFeatures.dispatchResolveEntity(wrapper.user(), entityUniqueId, entityRuntimeId, type);

            if (resolvedEntity != null) {
                entity = resolvedEntity;
            } else {
                final EntityTypes1_21_11 javaEntityType = BedrockProtocol.MAPPINGS.getBedrockToJavaEntities().get(type);
                if (javaEntityType != null) {
                    entity = entityTracker.addEntity(entityUniqueId, entityRuntimeId, type, javaEntityType);
                } else if (gameSession.getAvailableEntityIdentifiers().contains(type)) {
                    final ResourcePackStorage resourcePackStorage = wrapper.user().get(ResourcePackStorage.class);
                    final EntityDefinitions.EntityDefinition entityDefinition = resourcePackStorage.getEntities().get(type);
                    if (entityDefinition != null) {
                        if (resourcePackStorage.isLoadedOnJavaClient()) {
                            entity = new CustomEntity(wrapper.user(), entityUniqueId, entityRuntimeId, type, entityTracker.getNextJavaEntityId(), entityDefinition);
                            entityTracker.addEntity(entity);
                        } else {
                            entity = entityTracker.addEntity(entityUniqueId, entityRuntimeId, type, EntityTypes1_21_11.PIG);
                        }
                    } else {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing bedrock entity type: " + type);
                        wrapper.cancel();
                        return;
                    }
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown bedrock entity type: " + type);
                    wrapper.cancel();
                    return;
                }
            }
            entity.setPosition(position);
            entity.setRotation(rotation);
            entity.setEntityProperties(entityProperties);

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.UUID, entity.javaUuid()); // uuid
            wrapper.write(Types.VAR_INT, entity.javaTypeId()); // 类型 ID
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y()); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(motion.x(), motion.y(), motion.z())); // 速度
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.x())); // 俯仰角
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.y())); // 偏航角
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.z())); // 头部偏航角
            wrapper.write(Types.VAR_INT, javaSpawnData); // 数据
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();

            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.updateAttributes(attributes);
            }
            entity.updateEntityData(entityData);
            ExperimentalFeatures.dispatchEntityLinks(wrapper.user(), entityLinks);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ADD_ITEM_ENTITY, ClientboundPackets26_1.ADD_ENTITY, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);

            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final BedrockItem item = wrapper.read(itemRewriter.itemType()); // 物品
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            final Position3f motion = wrapper.read(BedrockTypes.POSITION_3F); // 运动向量
            final EntityData[] entityData = wrapper.read(BedrockTypes.ENTITY_DATA_ARRAY); // 实体数据
            wrapper.read(Types.BOOLEAN); // 来自钓鱼

            final DroppedItemEntity entity = (DroppedItemEntity) entityTracker.addEntity(entityUniqueId, entityRuntimeId, "minecraft:item", EntityTypes1_21_11.ITEM);
            entity.setPosition(position);

            final Item javaItem = itemRewriter.javaItem(item);
            entity.setItem(javaItem);

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.UUID, entity.javaUuid()); // uuid
            wrapper.write(Types.VAR_INT, entity.javaTypeId()); // 类型 ID
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y()); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(motion.x(), motion.y(), motion.z())); // 速度
            wrapper.write(Types.BYTE, (byte) 0); // 俯仰角
            wrapper.write(Types.BYTE, (byte) 0); // 偏航角
            wrapper.write(Types.BYTE, (byte) 0); // 头部偏航角
            wrapper.write(Types.VAR_INT, 0); // 数据
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();

            final List<EntityData> javaEntityData = new ArrayList<>();
            entity.updateEntityData(entityData, javaEntityData);
            javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ITEM), VersionedTypes.V26_1.entityDataTypes.itemType, javaItem));
            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, wrapper.user());
            setEntityData.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData); // 实体数据
            setEntityData.send(BedrockProtocol.class);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOVE_ENTITY_ABSOLUTE, ClientboundPackets26_1.ENTITY_POSITION_SYNC, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final short flags = wrapper.read(Types.UNSIGNED_BYTE); // 标志位
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            final float pitch = MathUtil.byte2Float(wrapper.read(Types.BYTE)); // 俯仰角
            final float yaw = MathUtil.byte2Float(wrapper.read(Types.BYTE)); // 偏航角
            final float headYaw = MathUtil.byte2Float(wrapper.read(Types.BYTE)); // 头部偏航角
            final boolean onGround = (flags & 1) != 0;
            final boolean teleported = (flags & 2) != 0; // 位置是否不应插值
            final boolean forceMoveLocalEntity = (flags & 4) != 0;

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            if (entity == entityTracker.getClientPlayer()) {
                if (!teleported && !forceMoveLocalEntity) {
                    wrapper.cancel();
                    return;
                }
                entityTracker.getClientPlayer().setPositionFromServer(position);
                ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

                if (teleported) {
                    wrapper.setPacketType(ClientboundPackets26_1.PLAYER_POSITION);
                    entityTracker.getClientPlayer().writePlayerPositionPacketToClient(wrapper, Relative.union(Relative.ROTATION, Relative.VELOCITY), true);
                } else { // 强制移动本地实体
                    wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
                    wrapper.write(Types.DOUBLE, (double) entity.position().x()); // x
                    wrapper.write(Types.DOUBLE, (double) entity.position().y() - entity.eyeOffset()); // y
                    wrapper.write(Types.DOUBLE, (double) entity.position().z()); // z
                    wrapper.write(Types.DOUBLE, 0D); // x 方向速度
                    wrapper.write(Types.DOUBLE, 0D); // y 方向速度
                    wrapper.write(Types.DOUBLE, 0D); // z 方向速度
                    wrapper.write(Types.FLOAT, entity.rotation().y()); // 偏航角
                    wrapper.write(Types.FLOAT, entity.rotation().x()); // 俯仰角
                    wrapper.write(Types.BOOLEAN, entity.isOnGround()); // 是否在地面
                }
                return;
            }

            entity.setPosition(position);
            entity.setRotation(new Position3f(pitch, yaw, headYaw));
            entity.setOnGround(onGround);
            ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

            if (writeAnchoredPassengerRotation(wrapper, entity)
                    || ViaBedrockUtilityInterface.writeCustomEntityTeleport(wrapper, entity, teleported)) {
                PacketFactory.sendJavaRotateHead(wrapper.user(), entity);
                return;
            }
            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y() - entity.eyeOffset()); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.DOUBLE, 0D); // x 方向速度
            wrapper.write(Types.DOUBLE, 0D); // y 方向速度
            wrapper.write(Types.DOUBLE, 0D); // z 方向速度
            wrapper.write(Types.FLOAT, yaw); // 偏航角
            wrapper.write(Types.FLOAT, pitch); // 俯仰角
            wrapper.write(Types.BOOLEAN, onGround); // 是否在地面

            PacketFactory.sendJavaRotateHead(wrapper.user(), entity);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOVE_ENTITY_DELTA, ClientboundPackets26_1.ENTITY_POSITION_SYNC, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final MoveEntityDeltaLayout.Delta delta = MoveEntityDeltaLayout.read(wrapper, BedrockWire.effectiveProtocol());
            final long entityRuntimeId = delta.entityRuntimeId();
            final boolean hasX = delta.has(MoveEntityDeltaLayout.FLAG_HAS_X);
            final boolean hasY = delta.has(MoveEntityDeltaLayout.FLAG_HAS_Y);
            final boolean hasZ = delta.has(MoveEntityDeltaLayout.FLAG_HAS_Z);
            final boolean hasPitch = delta.has(MoveEntityDeltaLayout.FLAG_HAS_PITCH);
            final boolean hasYaw = delta.has(MoveEntityDeltaLayout.FLAG_HAS_YAW);
            final boolean hasHeadYaw = delta.has(MoveEntityDeltaLayout.FLAG_HAS_HEAD_YAW);
            final boolean onGround = delta.onGround();
            // 2168 取消了 TELEPORTING 位，本地玩家只能靠 forceMoveLocalEntity 进强制移动分支
            final boolean teleported = delta.has(MoveEntityDeltaLayout.FLAG_TELEPORTING); // 位置是否不应插值
            final boolean forceMoveLocalEntity = delta.forceMoveLocalEntity();
            final float x = delta.x();
            final float y = delta.y();
            final float z = delta.z();
            final float pitch = delta.pitch();
            final float yaw = delta.yaw();
            final float headYaw = delta.headYaw();

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            if (entity == entityTracker.getClientPlayer()) {
                if (!teleported && !forceMoveLocalEntity) {
                    wrapper.cancel();
                    return;
                }

                entityTracker.getClientPlayer().setPositionFromServer(new Position3f(x, y, z));
                ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

                wrapper.clearPacket();
                if (teleported) {
                    wrapper.setPacketType(ClientboundPackets26_1.PLAYER_POSITION);
                    entityTracker.getClientPlayer().writePlayerPositionPacketToClient(wrapper, Relative.union(Relative.ROTATION, Relative.VELOCITY), true);
                } else { // 强制移动本地实体
                    wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
                    wrapper.write(Types.DOUBLE, (double) entity.position().x()); // x
                    wrapper.write(Types.DOUBLE, (double) entity.position().y() - entity.eyeOffset()); // y
                    wrapper.write(Types.DOUBLE, (double) entity.position().z()); // z
                    wrapper.write(Types.DOUBLE, 0D); // x 方向速度
                    wrapper.write(Types.DOUBLE, 0D); // y 方向速度
                    wrapper.write(Types.DOUBLE, 0D); // z 方向速度
                    wrapper.write(Types.FLOAT, entity.rotation().y()); // 偏航角
                    wrapper.write(Types.FLOAT, entity.rotation().x()); // 俯仰角
                    wrapper.write(Types.BOOLEAN, entity.isOnGround()); // 是否在地面
                }
                return;
            }

            if (hasX) {
                entity.setPosition(new Position3f(x, entity.position().y(), entity.position().z()));
            }
            if (hasY) {
                entity.setPosition(new Position3f(entity.position().x(), y, entity.position().z()));
            }
            if (hasZ) {
                entity.setPosition(new Position3f(entity.position().x(), entity.position().y(), z));
            }
            if (hasPitch) {
                entity.setRotation(new Position3f(pitch, entity.rotation().y(), entity.rotation().z()));
            }
            if (hasYaw) {
                entity.setRotation(new Position3f(entity.rotation().x(), yaw, entity.rotation().z()));
            }
            if (hasHeadYaw) {
                entity.setRotation(new Position3f(entity.rotation().x(), entity.rotation().y(), headYaw));
                PacketFactory.sendJavaRotateHead(wrapper.user(), entity);
            }
            entity.setOnGround(onGround);
            ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

            if (writeAnchoredPassengerRotation(wrapper, entity)
                    || ViaBedrockUtilityInterface.writeCustomEntityTeleport(wrapper, entity, teleported)) {
                return;
            }
            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.DOUBLE, (double) entity.position().x()); // x
            wrapper.write(Types.DOUBLE, (double) entity.position().y() - entity.eyeOffset()); // y
            wrapper.write(Types.DOUBLE, (double) entity.position().z()); // z
            wrapper.write(Types.DOUBLE, 0D); // x 方向速度
            wrapper.write(Types.DOUBLE, 0D); // y 方向速度
            wrapper.write(Types.DOUBLE, 0D); // z 方向速度
            wrapper.write(Types.FLOAT, entity.rotation().y()); // 偏航角
            wrapper.write(Types.FLOAT, entity.rotation().x()); // 俯仰角
            wrapper.write(Types.BOOLEAN, entity.isOnGround()); // 是否在地面
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_ENTITY_MOTION, ClientboundPackets26_1.SET_ENTITY_MOTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final Position3f motion = wrapper.read(BedrockTypes.POSITION_3F); // 运动向量
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 刻

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(motion.x(), motion.y(), motion.z())); // 速度
        });
        protocol.registerClientbound(ClientboundBedrockPackets.REMOVE_ENTITY, ClientboundPackets26_1.REMOVE_ENTITIES, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID

            final Entity entity = entityTracker.getEntityByUid(entityUniqueId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }
            entityTracker.removeEntity(entity);

            wrapper.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{entity.javaId()}); // 实体 ID 列表
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ADD_PAINTING, ClientboundPackets26_1.ADD_ENTITY, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final CompoundTag paintingRegistry = gameSession.getJavaRegistries().getCompoundTag(RegistryKeys.PAINTING_VARIANT);

            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            final Direction direction = Direction.getFromHorizontalId(wrapper.read(BedrockTypes.VAR_INT), Direction.NORTH); // 方向
            final String motive = wrapper.read(BedrockTypes.STRING); // 图案

            String javaIdentifier = BedrockProtocol.MAPPINGS.getBedrockToJavaPaintings().get(motive);
            if (javaIdentifier == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown bedrock painting motive: " + motive);
                javaIdentifier = "minecraft:kebab";
            }
            final CompoundTag paintingEntry = paintingRegistry.getCompoundTag(javaIdentifier);
            final Holder<PaintingVariant> paintingHolder = Holder.of(RegistryUtil.getRegistryIndex(paintingRegistry, paintingEntry));
            final int width = paintingEntry.getInt("width");
            final int height = paintingEntry.getInt("height");
            final float widthOffset = width % 2 == 0 ? 0.5F : 0;
            final float heightOffset = height % 2 == 0 ? 0.5F : 0;
            Position3f positionOffset = new Position3f(-0.5F, -0.5F, -0.5F);
            positionOffset = switch (direction) {
                case NORTH -> positionOffset.subtract(-widthOffset, heightOffset, -PAINTING_POS_OFFSET);
                case EAST -> positionOffset.subtract(PAINTING_POS_OFFSET, heightOffset, -widthOffset);
                case SOUTH -> positionOffset.subtract(widthOffset, heightOffset, PAINTING_POS_OFFSET);
                case WEST -> positionOffset.subtract(-PAINTING_POS_OFFSET, heightOffset, widthOffset);
                default -> positionOffset;
            };

            final Entity entity = entityTracker.addEntity(entityUniqueId, entityRuntimeId, "minecraft:painting", EntityTypes1_21_11.PAINTING);
            entity.setPosition(position);

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.UUID, entity.javaUuid()); // uuid
            wrapper.write(Types.VAR_INT, entity.javaTypeId()); // 类型 ID
            wrapper.write(Types.DOUBLE, (double) position.x() + positionOffset.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y() + positionOffset.y()); // y
            wrapper.write(Types.DOUBLE, (double) position.z() + positionOffset.z()); // z
            wrapper.write(Types.LOW_PRECISION_VECTOR, Vector3d.ZERO); // 速度
            wrapper.write(Types.BYTE, (byte) 0); // 俯仰角
            wrapper.write(Types.BYTE, (byte) 0); // 偏航角
            wrapper.write(Types.BYTE, (byte) 0); // 头部偏航角
            wrapper.write(Types.VAR_INT, direction.verticalId()); // 数据
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();

            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, wrapper.user());
            setEntityData.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            setEntityData.write(VersionedTypes.V26_1.entityDataList, Lists.newArrayList(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PAINTING_VARIANT), VersionedTypes.V26_1.entityDataTypes.paintingVariantType, paintingHolder))); // 实体数据
            setEntityData.send(BedrockProtocol.class);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ENTITY_EVENT, ClientboundPackets26_1.ENTITY_EVENT, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final short rawEvent = wrapper.read(Types.UNSIGNED_BYTE); // 事件
            final ActorEvent event = ActorEvent.getByValue(rawEvent);
            if (event == null) {
                wrapper.cancel();
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ActorEvent: " + rawEvent);
                return;
            }
            final int data = wrapper.read(BedrockTypes.VAR_INT); // 数据
            EntityPacketLayout.skipEntityEventFireAtPosition(wrapper);

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }
            switch (event) {
                case HURT -> {
                    final CompoundTag damageTypeRegistry = gameSession.getJavaRegistries().getCompoundTag(RegistryKeys.DAMAGE_TYPE);
                    final SharedTypes_Legacy_ActorDamageCause damageCause = SharedTypes_Legacy_ActorDamageCause.getByValue(data, SharedTypes_Legacy_ActorDamageCause.Override);
                    final CompoundTag damageTypeEntry = damageTypeRegistry.getCompoundTag(BedrockProtocol.MAPPINGS.getBedrockToJavaDamageCauses().get(damageCause));

                    wrapper.setPacketType(ClientboundPackets26_1.DAMAGE_EVENT);
                    wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
                    wrapper.write(Types.VAR_INT, RegistryUtil.getRegistryIndex(damageTypeRegistry, damageTypeEntry)); // 来源类型
                    wrapper.write(Types.VAR_INT, 0); // 来源原因 ID
                    wrapper.write(Types.VAR_INT, 0); // 来源直接 ID
                    wrapper.write(Types.BOOLEAN, false); // 是否包含来源位置
                    if (entity != entityTracker.getClientPlayer()) {
                        entity.playSound(SharedTypes_Legacy_LevelSoundEvent.Hurt);
                    }
                }
                case DEATH -> {
                    if (entity instanceof CustomEntity) {
                        // 自定义实体使用 Interaction 载体，需独立转发死亡状态供客户端渲染。
                        wrapper.write(Types.INT, entity.javaId()); // 实体 ID
                        wrapper.write(Types.BYTE, net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.DEATH.getValue()); // 事件
                    } else {
                        wrapper.cancel();
                    }
                    if (entity instanceof LivingEntity livingEntity) {
                        livingEntity.setHealth(0F);
                        livingEntity.sendAttribute("minecraft:health");
                    }
                    if (entity == entityTracker.getClientPlayer() && entityTracker.getClientPlayer().isDead() && gameSession.getDeathMessage() != null) {
                        final PacketWrapper playerCombatKill = PacketWrapper.create(ClientboundPackets26_1.PLAYER_COMBAT_KILL, wrapper.user());
                        playerCombatKill.write(Types.VAR_INT, entityTracker.getClientPlayer().javaId()); // 实体 ID
                        playerCombatKill.write(Types.TAG, TextUtil.textComponentToNbt(gameSession.getDeathMessage())); // 消息
                        playerCombatKill.send(BedrockProtocol.class);
                    }
                    if (entity != entityTracker.getClientPlayer()) {
                        entity.playSound(SharedTypes_Legacy_LevelSoundEvent.Death);
                    }
                }
                case UPDATE_STACK_SIZE -> {
                    wrapper.cancel();
                    if (!(entity instanceof DroppedItemEntity droppedItemEntity) || data <= 0) {
                        return;
                    }

                    final Item updatedItem = droppedItemEntity.updateItemAmount(data);
                    final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, wrapper.user());
                    setEntityData.write(Types.VAR_INT, entity.javaId()); // 实体 ID
                    setEntityData.write(VersionedTypes.V26_1.entityDataList, Lists.newArrayList(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ITEM), VersionedTypes.V26_1.entityDataTypes.itemType, updatedItem))); // 实体数据
                    setEntityData.send(BedrockProtocol.class);
                }
                default -> {
                    final net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent javaEvent = javaEntityEvent(event, entity.javaType());
                    if (javaEvent != null) {
                        wrapper.write(Types.INT, entity.javaId()); // 实体 ID
                        wrapper.write(Types.BYTE, javaEvent.getValue()); // 事件
                    } else {
                        final AnimateAction javaAnimateAction = javaAnimateAction(event, entity);
                        if (javaAnimateAction == null) {
                            wrapper.cancel();
                            return;
                        }
                        // Nukkit 把 ActorEvent 4 当作通用的生物挥臂，而 Java 把实体
                        // 状态 4 保留给少数实体类型。其他生物需要用 Animate 数据包。
                        wrapper.setPacketType(ClientboundPackets26_1.ANIMATE);
                        wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
                        wrapper.write(Types.UNSIGNED_BYTE, (short) javaAnimateAction.ordinal()); // 动作
                    }
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_ATTRIBUTES, ClientboundPackets26_1.UPDATE_ATTRIBUTES, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final EntityAttribute[] attributes = new EntityAttribute[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // 属性数量
            for (int i = 0; i < attributes.length; i++) {
                final float minValue = wrapper.read(BedrockTypes.FLOAT_LE); // 最小值
                final float maxValue = wrapper.read(BedrockTypes.FLOAT_LE); // 最大值
                final float currentValue = wrapper.read(BedrockTypes.FLOAT_LE); // 当前值
                final float defaultMinValue = wrapper.read(BedrockTypes.FLOAT_LE); // 默认最小值
                final float defaultMaxValue = wrapper.read(BedrockTypes.FLOAT_LE); // 默认最大值
                final float defaultValue = wrapper.read(BedrockTypes.FLOAT_LE); // 默认值
                final String name = wrapper.read(BedrockTypes.STRING); // 名称
                final EntityAttribute.Modifier[] modifiers = new EntityAttribute.Modifier[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // 修饰符数量
                for (int j = 0; j < modifiers.length; j++) {
                    final String id = wrapper.read(BedrockTypes.STRING); // ID
                    final String modifierName = wrapper.read(BedrockTypes.STRING); // 名称
                    final float amount = wrapper.read(BedrockTypes.FLOAT_LE); // 数量
                    final AttributeModifierOperation operation = AttributeModifierOperation.getByValue(wrapper.read(BedrockTypes.INT_LE), AttributeModifierOperation.OPERATION_INVALID); // 运算
                    final AttributeOperands operand = AttributeOperands.getByValue(wrapper.read(BedrockTypes.INT_LE), AttributeOperands.OPERAND_INVALID); // 操作数
                    final boolean isSerializable = wrapper.read(Types.BOOLEAN); // 是否可序列化
                    modifiers[j] = new EntityAttribute.Modifier(id, modifierName, amount, operation, operand, isSerializable);
                }
                attributes[i] = new EntityAttribute(name, currentValue, minValue, maxValue, defaultValue, defaultMinValue, defaultMaxValue, modifiers);
            }
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 刻

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.updateAttributes(attributes, wrapper);
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_ENTITY_DATA, ClientboundPackets26_1.SET_ENTITY_DATA, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final EntityData[] entityData = wrapper.read(BedrockTypes.ENTITY_DATA_ARRAY); // 实体数据
            final EntityProperties rawEntityProperties = wrapper.read(BedrockTypes.ENTITY_PROPERTIES); // 实体属性
            final long entityDataFrame = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 刻

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            final EntityProperties entityProperties = EntityPropertyStorage.getOrCreate(wrapper.user())
                    .resolve(entity.type(), rawEntityProperties);
            entity.setEntityProperties(entityProperties);
            entity.setEntityDataFrame(entityDataFrame);
            final List<EntityData> javaEntityData = new ArrayList<>();
            entity.updateEntityData(entityData, javaEntityData);
            if (entity instanceof PlayerEntity playerEntity) {
                playerEntity.updateEntityProperties(entityProperties);
            }
            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(VersionedTypes.V26_1.entityDataList, javaEntityData); // 实体数据
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOB_EFFECT, ClientboundPackets26_1.UPDATE_MOB_EFFECT, wrapper -> {
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final MobEffectPacketPayload_Event event = MobEffectPacketPayload_Event.getByValue(wrapper.read(Types.BYTE), MobEffectPacketPayload_Event.Invalid); // 事件 ID
            final int effectId = wrapper.read(BedrockTypes.VAR_INT); // 效果 ID
            final int amplifier = wrapper.read(BedrockTypes.VAR_INT); // 效果强度
            final boolean showParticles = wrapper.read(Types.BOOLEAN); // 是否显示粒子
            final int duration = wrapper.read(BedrockTypes.VAR_INT); // 持续时间
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 刻
            final boolean ambient = EntityPacketLayout.readAmbient(wrapper);

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByRid(entityRuntimeId);
            if (!(entity instanceof LivingEntity livingEntity) || effectId == 0) {
                wrapper.cancel();
                return;
            }

            final String bedrockIdentifier = BedrockProtocol.MAPPINGS.getBedrockEffects().inverse().get(effectId);
            if (bedrockIdentifier == null) { // 基岩客户端会崩溃
                throw new IllegalStateException("Unknown bedrock effect: " + effectId);
            }
            final EntityEffect effect = new EntityEffect(bedrockIdentifier, amplifier, duration, showParticles, ambient);
            switch (event) {
                case Invalid -> wrapper.cancel();
                case Add, Update -> livingEntity.updateEffect(effect, wrapper);
                case Remove -> {
                    wrapper.setPacketType(ClientboundPackets26_1.REMOVE_MOB_EFFECT);
                    livingEntity.removeEffect(bedrockIdentifier, wrapper);
                }
                default -> throw new IllegalStateException("Unhandled MobEffectPacketPayload_Event: " + event);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ANIMATE, ClientboundPackets26_1.ANIMATE, wrapper -> {
            final int rawAction = EntityPacketLayout.readAnimateAction(wrapper); // 动作
            final AnimatePacketPayload_Action action = AnimatePacketPayload_Action.getByValue(rawAction, AnimatePacketPayload_Action.NoAction);
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            wrapper.read(BedrockTypes.FLOAT_LE); // 数据
            // MOT 860 的 AnimatePacket.decode()：protocol < 897 && ROW_LEFT/ROW_RIGHT 总会携带 rowingTime。
            EntityPacketLayout.skipRowingTime(wrapper, rawAction);
            EntityPacketLayout.skipSwingSource(wrapper);
            EntityPacketLayout.skipAnimateHand(wrapper);
            // MOT 898 的 AnimatePacket.decode()：网易会追加攻击 ID 的 varlong。
            EntityPacketLayout.skipNetEaseAttackId(wrapper);

            final JavaAnimate javaAnimate = resolveJavaAnimate(action, entityRuntimeId, wrapper.user().get(EntityTracker.class)::getEntityByRid);
            if (javaAnimate == null) {
                wrapper.cancel();
                return;
            }

            final Entity entity = javaAnimate.entity();
            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.UNSIGNED_BYTE, (short) javaAnimate.action().ordinal()); // 动作
            if (javaAnimate.action() == AnimateAction.WAKE_UP && entity instanceof ClientPlayerEntity clientPlayer) {
                clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.StopSleeping);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ANIMATE_ENTITY, null, wrapper -> {
            // Java 没有 MoLang 动画系统；服务端触发的具名动画只对
            // 由 ViaBedrockUtility mod 渲染的实体有意义：自定义实体，以及走玩家渲染路径的人形
            // （真实玩家和玩家类型 NPC，例如排行榜 NPC）。具备该能力的玩家渲染器会收到
            // 完整的运行时控制器请求；自定义实体和旧客户端仍保留旧版载荷。
            final String animation = wrapper.read(BedrockTypes.STRING); // 动画
            final String nextState = wrapper.read(BedrockTypes.STRING); // 下一状态
            final String stopExpression = wrapper.read(BedrockTypes.STRING); // 停止表达式
            final int stopExpressionVersion = wrapper.read(Types.INT); // 停止表达式版本（大端 int）
            final String controller = wrapper.read(BedrockTypes.STRING); // 控制器
            final float blendOutTime = wrapper.read(BedrockTypes.FLOAT_LE); // 淡出时间
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 运行时实体 ID 数量
            final long[] runtimeIds = new long[count];
            for (int i = 0; i < count; i++) {
                runtimeIds[i] = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 运行时实体 ID
            }
            wrapper.cancel();

            final ChannelStorage channelStorage = wrapper.user().get(ChannelStorage.class);
            if (channelStorage == null || !channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
                return; // 没有 ViaBedrockUtility mod：这条路径上无法渲染该动画
            }
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            for (final long runtimeId : runtimeIds) {
                final Entity entity = entityTracker.getEntityByRid(runtimeId);
                if (entity instanceof PlayerEntity) {
                    ViaBedrockUtilityInterface.sendPlayerAnimation(wrapper.user(), entity.javaUuid(), animation,
                            nextState, stopExpression, stopExpressionVersion, controller, blendOutTime);
                } else if (entity instanceof CustomEntity) {
                    ViaBedrockUtilityInterface.sendAnimateEntity(wrapper.user(), entity.javaUuid(), animation);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOB_ARMOR_EQUIPMENT, ClientboundPackets26_1.SET_EQUIPMENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final Type<BedrockItem> armorItemType = itemRewriter.itemType(BedrockWire.current());
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final BedrockItem head = wrapper.read(armorItemType); // 头部
            final BedrockItem chest = wrapper.read(armorItemType); // 胸部
            final BedrockItem legs = wrapper.read(armorItemType); // 腿部
            final BedrockItem feet = wrapper.read(armorItemType); // 脚部
            final BedrockItem body = wrapper.read(armorItemType); // 身体

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByRid(entityRuntimeId);
            if (entity == null || entity instanceof ClientPlayerEntity) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.FEET.ordinal() | Byte.MIN_VALUE)); // 槽位
            final Item javaFeet = itemRewriter.javaItem(feet);
            wrapper.write(VersionedTypes.V26_1.item, javaFeet); // 物品
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.LEGS.ordinal() | Byte.MIN_VALUE)); // 槽位
            final Item javaLegs = itemRewriter.javaItem(legs);
            wrapper.write(VersionedTypes.V26_1.item, javaLegs); // 物品
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.CHEST.ordinal() | Byte.MIN_VALUE)); // 槽位
            final Item javaChest = itemRewriter.javaItem(chest);
            wrapper.write(VersionedTypes.V26_1.item, javaChest); // 物品
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.HEAD.ordinal() | Byte.MIN_VALUE)); // 槽位
            final Item javaHead = itemRewriter.javaItem(head);
            wrapper.write(VersionedTypes.V26_1.item, javaHead); // 物品
            wrapper.write(Types.BYTE, (byte) EquipmentSlot.BODY.ordinal()); // 槽位
            final Item javaBody = itemRewriter.javaItem(body);
            wrapper.write(VersionedTypes.V26_1.item, javaBody); // 物品
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOB_EQUIPMENT, ClientboundPackets26_1.SET_EQUIPMENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final BedrockItem item = wrapper.read(itemRewriter.newItemType()); // 物品
            final byte slot = wrapper.read(Types.BYTE); // 槽位
            final byte selectedSlot = wrapper.read(Types.BYTE); // 选中槽位
            final byte containerId = wrapper.read(Types.BYTE); // 容器 ID

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByRid(entityRuntimeId);
            if (entity == null || entity instanceof ClientPlayerEntity) {
                wrapper.cancel();
                return;
            }

            final EquipmentSlot equipmentSlot = getMobEquipmentSlot(entity, slot, selectedSlot, containerId);
            if (equipmentSlot == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, entity.javaId()); // 实体 ID
            wrapper.write(Types.BYTE, (byte) equipmentSlot.ordinal()); // 槽位
            wrapper.write(VersionedTypes.V26_1.item, itemRewriter.javaItem(item)); // 物品
        });
        protocol.registerClientbound(ClientboundBedrockPackets.TAKE_ITEM_ENTITY, ClientboundPackets26_1.TAKE_ITEM_ENTITY, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final long itemEntityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 物品实体运行时 ID
            final long collectorEntityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 收集者实体运行时 ID

            final Entity itemEntity = entityTracker.getEntityByRid(itemEntityRuntimeId);
            final Entity collectorEntity = entityTracker.getEntityByRid(collectorEntityRuntimeId);
            if (!canTranslateTakeItemEntity(itemEntity, collectorEntity)) {
                wrapper.cancel();
                return;
            }
            wrapper.write(Types.VAR_INT, itemEntity.javaId()); // 物品实体 ID
            wrapper.write(Types.VAR_INT, collectorEntity.javaId()); // 收集者实体 ID
            wrapper.write(Types.VAR_INT, 0); // 数量
        });
    }

    static EquipmentSlot getMobEquipmentSlot(final Entity entity, final int slot, final int selectedSlot, final int containerId) {
        if (containerId == ContainerID.CONTAINER_ID_OFFHAND.getValue()) {
            return EquipmentSlot.OFFHAND;
        }
        if (containerId != ContainerID.CONTAINER_ID_INVENTORY.getValue() || slot < 0 || slot >= 9 || (slot != selectedSlot && selectedSlot >= 0)) {
            return null;
        }
        if (entity.javaType() == EntityTypes1_21_11.ARMOR_STAND) {
            // 基岩版盔甲架使用六个装备槽位，不能将护甲按快捷栏物品写入主手。
            return switch (slot) {
                case 0 -> EquipmentSlot.MAINHAND;
                case 1 -> EquipmentSlot.OFFHAND;
                case 2 -> EquipmentSlot.HEAD;
                case 3 -> EquipmentSlot.CHEST;
                case 4 -> EquipmentSlot.LEGS;
                case 5 -> EquipmentSlot.FEET;
                default -> null;
            };
        }
        return EquipmentSlot.MAINHAND;
    }

    private static boolean writeAnchoredPassengerRotation(final PacketWrapper wrapper, final Entity entity) {
        final RidingTracker riding = wrapper.user().get(RidingTracker.class);
        if (riding == null || !riding.isAnchoredPassenger(entity.uniqueId())) {
            return false;
        }

        // 保留服务端坐标缓存与朝向，但挂载期间只由挂点推进客户端位置，避免两条同步路径互相拉扯。
        wrapper.setPacketType(ClientboundPackets26_1.MOVE_ENTITY_ROT);
        wrapper.write(Types.VAR_INT, entity.javaId());
        wrapper.write(Types.BYTE, MathUtil.float2Byte(entity.rotation().y()));
        wrapper.write(Types.BYTE, MathUtil.float2Byte(entity.rotation().x()));
        wrapper.write(Types.BOOLEAN, entity.isOnGround());
        return true;
    }

    static boolean canTranslateTakeItemEntity(final Entity itemEntity, final Entity collectorEntity) {
        return itemEntity != null
                && itemEntity.javaType() == EntityTypes1_21_11.ITEM
                && collectorEntity instanceof LivingEntity;
    }

    static Integer getFishingHookOwnerJavaId(final EntityData[] entityData, final LongFunction<Integer> ownerJavaIdLookup) {
        for (EntityData data : entityData) {
            if (data.id() != ActorDataIDs.OWNER.getValue()) {
                continue;
            }
            if (data.dataType() != EntityDataTypesBedrock.LONG || !(data.getValue() instanceof Long ownerUniqueId) || ownerUniqueId == -1L) {
                return null;
            }

            return ownerJavaIdLookup.apply(ownerUniqueId);
        }
        return null;
    }

    static Integer getFallingBlockJavaBlockStateId(final EntityData[] entityData, final IntUnaryOperator javaBlockStateLookup) {
        for (EntityData data : entityData) {
            if (data.id() != ActorDataIDs.VARIANT.getValue()) {
                continue;
            }
            if (data.dataType() != EntityDataTypesBedrock.INT || !(data.getValue() instanceof Integer bedrockRuntimeId)) {
                return null;
            }

            final int javaBlockStateId = javaBlockStateLookup.applyAsInt(bedrockRuntimeId);
            return javaBlockStateId >= 0 ? javaBlockStateId : null;
        }
        return null;
    }

    static net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent javaEntityEvent(final ActorEvent event, final EntityTypes1_21_11 type) {
        return switch (event) {
            case JUMP -> type.is(EntityTypes1_21_11.RABBIT)
                    ? net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.JUMP : null;
            case START_ATTACKING -> {
                if (type.is(EntityTypes1_21_11.GOAT)) {
                    yield net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.START_RAM;
                }
                if (type.is(EntityTypes1_21_11.EVOKER_FANGS)
                        || type.is(EntityTypes1_21_11.IRON_GOLEM)
                        || type.is(EntityTypes1_21_11.WARDEN)) {
                    yield net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.START_ATTACKING;
                }
                yield null;
            }
            case STOP_ATTACKING -> type.is(EntityTypes1_21_11.GOAT)
                    ? net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.END_RAM : null;
            case TAMING_FAILED -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.TAMING_FAILED;
            case TAMING_SUCCEEDED -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.TAMING_SUCCEEDED;
            case SHAKE_WETNESS -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.SHAKE_WETNESS;
            case EAT_GRASS -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.EAT_GRASS;
            case ZOMBIE_CONVERTING -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.ZOMBIE_CONVERTING;
            case LOVE_HEARTS -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.LOVE_HEARTS;
            case VILLAGER_ANGRY -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.VILLAGER_ANGRY;
            case VILLAGER_HAPPY -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.VILLAGER_HAPPY;
            // MOT 的 Entity.java 中不死图腾使用 ActorEvent 65（TALISMAN_ACTIVATE / CONSUME_TOTEM）。
            // Java 1.21 期望 PROTECTED_FROM_DEATH(35)；缺少这一步会导致图腾触发被取消。
            case TALISMAN_ACTIVATE -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.PROTECTED_FROM_DEATH;
            case WITCH_HAT_MAGIC -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.WITCH_HAT_MAGIC;
            case FIREWORKS_EXPLODE -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.FIREWORKS_EXPLODE;
            case IN_LOVE_HEARTS -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.IN_LOVE_HEARTS;
            case SILVERFISH_MERGE_ANIM -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.SILVERFISH_MERGE_ANIM;
            case GUARDIAN_ATTACK_SOUND -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.GUARDIAN_ATTACK_SOUND;
            case SHAKE_WETNESS_STOP -> net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent.CANCEL_SHAKE_WETNESS;
            default -> null;
        };
    }

    static JavaAnimate resolveJavaAnimate(final AnimatePacketPayload_Action bedrockAction, final long entityRuntimeId,
                                          final LongFunction<Entity> entityLookup) {
        final Entity entity = entityLookup.apply(entityRuntimeId);
        final AnimateAction javaAction = switch (bedrockAction) {
            case NoAction -> null;
            case Swing -> AnimateAction.SWING_MAIN_HAND;
            case WakeUp -> AnimateAction.WAKE_UP;
            case CriticalHit -> AnimateAction.CRITICAL_HIT;
            case MagicCriticalHit -> AnimateAction.MAGIC_CRITICAL_HIT;
        };
        return isJavaAnimateActionValid(javaAction, entity) ? new JavaAnimate(entity, javaAction) : null;
    }

    static AnimateAction javaAnimateAction(final ActorEvent bedrockEvent, final Entity entity) {
        final AnimateAction javaAction = bedrockEvent == ActorEvent.START_ATTACKING ? AnimateAction.SWING_MAIN_HAND : null;
        return isJavaAnimateActionValid(javaAction, entity) ? javaAction : null;
    }

    static boolean isJavaAnimateActionValid(final AnimateAction action, final Entity entity) {
        if (action == null || entity == null) {
            return false;
        }
        return switch (action) {
            case SWING_MAIN_HAND, SWING_OFF_HAND -> entity instanceof LivingEntity;
            case WAKE_UP -> entity instanceof PlayerEntity;
            case CRITICAL_HIT, MAGIC_CRITICAL_HIT -> true;
            case UNUSED -> false;
        };
    }

    record JavaAnimate(Entity entity, AnimateAction action) {
    }

}
