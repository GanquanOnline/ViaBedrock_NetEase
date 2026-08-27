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
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.RegistryUtil;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingAccess;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
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

            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final String type = Key.namespaced(wrapper.read(BedrockTypes.STRING)); // type
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            final Position3f motion = wrapper.read(BedrockTypes.POSITION_3F); // motion
            final Position3f rotation = wrapper.read(BedrockTypes.POSITION_3F); // rotation
            wrapper.read(BedrockTypes.FLOAT_LE); // body rotation
            final EntityAttribute[] attributes = new EntityAttribute[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // attribute count
            for (int i = 0; i < attributes.length; i++) {
                final String name = wrapper.read(BedrockTypes.STRING); // name
                final float minValue = wrapper.read(BedrockTypes.FLOAT_LE); // min
                final float currentValue = wrapper.read(BedrockTypes.FLOAT_LE); // current
                final float maxValue = wrapper.read(BedrockTypes.FLOAT_LE); // max
                attributes[i] = new EntityAttribute(name, currentValue, minValue, maxValue);
            }
            final EntityData[] entityData = wrapper.read(BedrockTypes.ENTITY_DATA_ARRAY); // entity data
            final EntityProperties entityProperties = EntityPropertyStorage.getOrCreate(wrapper.user())
                    .resolve(type, wrapper.read(BedrockTypes.ENTITY_PROPERTIES)); // entity properties
            final EntityLink[] entityLinks = wrapper.read(BedrockTypes.ENTITY_LINK_ARRAY); // entity links

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

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.UUID, entity.javaUuid()); // uuid
            wrapper.write(Types.VAR_INT, entity.javaTypeId()); // type id
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            // MOT ADD already includes getBaseOffset() for boats (0.375). MATCH MOVE_ENTITY:
            // Java ADD_ENTITY wants the foot. Leaving the network Y here makes the JE hull sit
            // 0.375 high; the first MOVE_VEHICLE then feeds that elevated foot into predicted
            // boat SAI and lifts the boat again (#1-2 takeoff).
            wrapper.write(Types.DOUBLE, (double) javaEntityY(entity, position.y())); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(motion.x(), motion.y(), motion.z())); // velocity
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.x())); // pitch
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.y())); // yaw
            wrapper.write(Types.BYTE, MathUtil.float2Byte(rotation.z())); // head yaw
            wrapper.write(Types.VAR_INT, javaSpawnData); // data
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

            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final BedrockItem item = wrapper.read(itemRewriter.itemType()); // item
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            final Position3f motion = wrapper.read(BedrockTypes.POSITION_3F); // motion
            final EntityData[] entityData = wrapper.read(BedrockTypes.ENTITY_DATA_ARRAY); // entity data
            wrapper.read(Types.BOOLEAN); // from fishing

            final DroppedItemEntity entity = (DroppedItemEntity) entityTracker.addEntity(entityUniqueId, entityRuntimeId, "minecraft:item", EntityTypes1_21_11.ITEM);
            entity.setPosition(position);

            final Item javaItem = itemRewriter.javaItem(item);
            entity.setItem(javaItem);

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.UUID, entity.javaUuid()); // uuid
            wrapper.write(Types.VAR_INT, entity.javaTypeId()); // type id
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y()); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(motion.x(), motion.y(), motion.z())); // velocity
            wrapper.write(Types.BYTE, (byte) 0); // pitch
            wrapper.write(Types.BYTE, (byte) 0); // yaw
            wrapper.write(Types.BYTE, (byte) 0); // head yaw
            wrapper.write(Types.VAR_INT, 0); // data
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();

            final List<EntityData> javaEntityData = new ArrayList<>();
            entity.updateEntityData(entityData, javaEntityData);
            javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ITEM), VersionedTypes.V26_1.entityDataTypes.itemType, javaItem));
            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, wrapper.user());
            setEntityData.write(Types.VAR_INT, entity.javaId()); // entity id
            setEntityData.write(VersionedTypes.V26_1.entityDataList, javaEntityData); // entity data
            setEntityData.send(BedrockProtocol.class);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOVE_ENTITY_ABSOLUTE, ClientboundPackets26_1.ENTITY_POSITION_SYNC, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final short flags = wrapper.read(Types.UNSIGNED_BYTE); // flags
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            final float pitch = MathUtil.byte2Float(wrapper.read(Types.BYTE)); // pitch
            final float yaw = MathUtil.byte2Float(wrapper.read(Types.BYTE)); // yaw
            final float headYaw = MathUtil.byte2Float(wrapper.read(Types.BYTE)); // head yaw
            final boolean onGround = (flags & 1) != 0;
            final boolean teleported = (flags & 2) != 0; // If the position shouldn't be interpolated
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
                entity.setPosition(position);
                ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

                if (teleported) {
                    wrapper.setPacketType(ClientboundPackets26_1.PLAYER_POSITION);
                    entityTracker.getClientPlayer().writePlayerPositionPacketToClient(wrapper, Relative.union(Relative.ROTATION, Relative.VELOCITY), true);
                } else { // force move local entity
                    wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
                    wrapper.write(Types.DOUBLE, (double) entity.position().x()); // x
                    wrapper.write(Types.DOUBLE, (double) javaEntityY(entity, entity.position().y())); // y
                    wrapper.write(Types.DOUBLE, (double) entity.position().z()); // z
                    wrapper.write(Types.DOUBLE, 0D); // velocity x
                    wrapper.write(Types.DOUBLE, 0D); // velocity y
                    wrapper.write(Types.DOUBLE, 0D); // velocity z
                    wrapper.write(Types.FLOAT, entity.rotation().y()); // yaw
                    wrapper.write(Types.FLOAT, entity.rotation().x()); // pitch
                    wrapper.write(Types.BOOLEAN, entity.isOnGround()); // on ground
                }
                return;
            }

            entity.setPosition(position);
            entity.setRotation(new Position3f(pitch, yaw, headYaw));
            entity.setOnGround(onGround);
            ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.DOUBLE, (double) position.x()); // x
            wrapper.write(Types.DOUBLE, (double) javaEntityY(entity, position.y())); // y
            wrapper.write(Types.DOUBLE, (double) position.z()); // z
            wrapper.write(Types.DOUBLE, 0D); // velocity x
            wrapper.write(Types.DOUBLE, 0D); // velocity y
            wrapper.write(Types.DOUBLE, 0D); // velocity z
            wrapper.write(Types.FLOAT, yaw); // yaw
            wrapper.write(Types.FLOAT, pitch); // pitch
            wrapper.write(Types.BOOLEAN, onGround); // on ground

            PacketFactory.sendJavaRotateHead(wrapper.user(), entity);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOVE_ENTITY_DELTA, ClientboundPackets26_1.ENTITY_POSITION_SYNC, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final int flags = wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE); // flags
            final boolean hasX = (flags & 1) != 0;
            final boolean hasY = (flags & 2) != 0;
            final boolean hasZ = (flags & 4) != 0;
            final boolean hasPitch = (flags & 8) != 0;
            final boolean hasYaw = (flags & 16) != 0;
            final boolean hasHeadYaw = (flags & 32) != 0;
            final boolean onGround = (flags & 64) != 0;
            final boolean teleported = (flags & 128) != 0; // If the position shouldn't be interpolated
            final boolean forceMoveLocalEntity = (flags & 256) != 0;

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

                float x = 0F;
                float y = 0F;
                float z = 0F;
                if (hasX) {
                    x = wrapper.read(BedrockTypes.FLOAT_LE);
                }
                if (hasY) {
                    y = wrapper.read(BedrockTypes.FLOAT_LE);
                }
                if (hasZ) {
                    z = wrapper.read(BedrockTypes.FLOAT_LE);
                }
                entity.setPosition(new Position3f(x, y, z));
                ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

                wrapper.clearPacket();
                if (teleported) {
                    wrapper.setPacketType(ClientboundPackets26_1.PLAYER_POSITION);
                    entityTracker.getClientPlayer().writePlayerPositionPacketToClient(wrapper, Relative.union(Relative.ROTATION, Relative.VELOCITY), true);
                } else { // force move local entity
                    wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
                    wrapper.write(Types.DOUBLE, (double) entity.position().x()); // x
                    wrapper.write(Types.DOUBLE, (double) javaEntityY(entity, entity.position().y())); // y
                    wrapper.write(Types.DOUBLE, (double) entity.position().z()); // z
                    wrapper.write(Types.DOUBLE, 0D); // velocity x
                    wrapper.write(Types.DOUBLE, 0D); // velocity y
                    wrapper.write(Types.DOUBLE, 0D); // velocity z
                    wrapper.write(Types.FLOAT, entity.rotation().y()); // yaw
                    wrapper.write(Types.FLOAT, entity.rotation().x()); // pitch
                    wrapper.write(Types.BOOLEAN, entity.isOnGround()); // on ground
                }
                return;
            }

            if (hasX) {
                entity.setPosition(new Position3f(wrapper.read(BedrockTypes.FLOAT_LE), entity.position().y(), entity.position().z()));
            }
            if (hasY) {
                entity.setPosition(new Position3f(entity.position().x(), wrapper.read(BedrockTypes.FLOAT_LE), entity.position().z()));
            }
            if (hasZ) {
                entity.setPosition(new Position3f(entity.position().x(), entity.position().y(), wrapper.read(BedrockTypes.FLOAT_LE)));
            }
            if (hasPitch) {
                entity.setRotation(new Position3f(MathUtil.byte2Float(wrapper.read(Types.BYTE)), entity.rotation().y(), entity.rotation().z()));
            }
            if (hasYaw) {
                entity.setRotation(new Position3f(entity.rotation().x(), MathUtil.byte2Float(wrapper.read(Types.BYTE)), entity.rotation().z()));
            }
            if (hasHeadYaw) {
                entity.setRotation(new Position3f(entity.rotation().x(), entity.rotation().y(), MathUtil.byte2Float(wrapper.read(Types.BYTE))));
                PacketFactory.sendJavaRotateHead(wrapper.user(), entity);
            }
            entity.setOnGround(onGround);
            ExperimentalFeatures.dispatchEntityMoved(wrapper.user(), entity);

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.DOUBLE, (double) entity.position().x()); // x
            wrapper.write(Types.DOUBLE, (double) javaEntityY(entity, entity.position().y())); // y
            wrapper.write(Types.DOUBLE, (double) entity.position().z()); // z
            wrapper.write(Types.DOUBLE, 0D); // velocity x
            wrapper.write(Types.DOUBLE, 0D); // velocity y
            wrapper.write(Types.DOUBLE, 0D); // velocity z
            wrapper.write(Types.FLOAT, entity.rotation().y()); // yaw
            wrapper.write(Types.FLOAT, entity.rotation().x()); // pitch
            wrapper.write(Types.BOOLEAN, entity.isOnGround()); // on ground
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_ENTITY_MOTION, ClientboundPackets26_1.SET_ENTITY_MOTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final Position3f motion = wrapper.read(BedrockTypes.POSITION_3F); // motion
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.LOW_PRECISION_VECTOR, new Vector3d(motion.x(), motion.y(), motion.z())); // velocity
        });
        protocol.registerClientbound(ClientboundBedrockPackets.REMOVE_ENTITY, ClientboundPackets26_1.REMOVE_ENTITIES, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id

            final Entity entity = entityTracker.getEntityByUid(entityUniqueId);
            if (entity == null) {
                wrapper.cancel();
                return;
            }
            entityTracker.removeEntity(entity);

            wrapper.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{entity.javaId()}); // entity ids
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ADD_PAINTING, ClientboundPackets26_1.ADD_ENTITY, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final CompoundTag paintingRegistry = gameSession.getJavaRegistries().getCompoundTag(RegistryKeys.PAINTING_VARIANT);

            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            final Direction direction = Direction.getFromHorizontalId(wrapper.read(BedrockTypes.VAR_INT), Direction.NORTH); // direction
            final String motive = wrapper.read(BedrockTypes.STRING); // motive

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

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.UUID, entity.javaUuid()); // uuid
            wrapper.write(Types.VAR_INT, entity.javaTypeId()); // type id
            wrapper.write(Types.DOUBLE, (double) position.x() + positionOffset.x()); // x
            wrapper.write(Types.DOUBLE, (double) position.y() + positionOffset.y()); // y
            wrapper.write(Types.DOUBLE, (double) position.z() + positionOffset.z()); // z
            wrapper.write(Types.LOW_PRECISION_VECTOR, Vector3d.ZERO); // velocity
            wrapper.write(Types.BYTE, (byte) 0); // pitch
            wrapper.write(Types.BYTE, (byte) 0); // yaw
            wrapper.write(Types.BYTE, (byte) 0); // head yaw
            wrapper.write(Types.VAR_INT, direction.verticalId()); // data
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();

            final PacketWrapper setEntityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, wrapper.user());
            setEntityData.write(Types.VAR_INT, entity.javaId()); // entity id
            setEntityData.write(VersionedTypes.V26_1.entityDataList, Lists.newArrayList(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PAINTING_VARIANT), VersionedTypes.V26_1.entityDataTypes.paintingVariantType, paintingHolder))); // entity data
            setEntityData.send(BedrockProtocol.class);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ENTITY_EVENT, ClientboundPackets26_1.ENTITY_EVENT, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final short rawEvent = wrapper.read(Types.UNSIGNED_BYTE); // event
            final ActorEvent event = ActorEvent.getByValue(rawEvent);
            if (event == null) {
                wrapper.cancel();
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown ActorEvent: " + rawEvent);
                return;
            }
            final int data = wrapper.read(BedrockTypes.VAR_INT); // data
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
                    wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
                    wrapper.write(Types.VAR_INT, RegistryUtil.getRegistryIndex(damageTypeRegistry, damageTypeEntry)); // source type
                    wrapper.write(Types.VAR_INT, 0); // source cause id
                    wrapper.write(Types.VAR_INT, 0); // source direct id
                    wrapper.write(Types.BOOLEAN, false); // has source position
                    if (entity != entityTracker.getClientPlayer()) {
                        entity.playSound(SharedTypes_Legacy_LevelSoundEvent.Hurt);
                    }
                }
                case DEATH -> {
                    wrapper.cancel();
                    if (entity instanceof LivingEntity livingEntity) {
                        livingEntity.setHealth(0F);
                        livingEntity.sendAttribute("minecraft:health");
                    }
                    if (entity == entityTracker.getClientPlayer() && entityTracker.getClientPlayer().isDead() && gameSession.getDeathMessage() != null) {
                        final PacketWrapper playerCombatKill = PacketWrapper.create(ClientboundPackets26_1.PLAYER_COMBAT_KILL, wrapper.user());
                        playerCombatKill.write(Types.VAR_INT, entityTracker.getClientPlayer().javaId()); // entity id
                        playerCombatKill.write(Types.TAG, TextUtil.textComponentToNbt(gameSession.getDeathMessage())); // message
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
                    setEntityData.write(Types.VAR_INT, entity.javaId()); // entity id
                    setEntityData.write(VersionedTypes.V26_1.entityDataList, Lists.newArrayList(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ITEM), VersionedTypes.V26_1.entityDataTypes.itemType, updatedItem))); // entity data
                    setEntityData.send(BedrockProtocol.class);
                }
                default -> {
                    final net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent javaEvent = javaEntityEvent(event, entity.javaType());
                    if (javaEvent != null) {
                        wrapper.write(Types.INT, entity.javaId()); // entity id
                        wrapper.write(Types.BYTE, javaEvent.getValue()); // event
                    } else {
                        final AnimateAction javaAnimateAction = javaAnimateAction(event, entity);
                        if (javaAnimateAction == null) {
                            wrapper.cancel();
                            return;
                        }
                        // Nukkit uses ActorEvent 4 as a generic mob arm swing, while Java reserves entity
                        // status 4 for a few entity types. Other living entities need an Animate packet.
                        wrapper.setPacketType(ClientboundPackets26_1.ANIMATE);
                        wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
                        wrapper.write(Types.UNSIGNED_BYTE, (short) javaAnimateAction.ordinal()); // action
                    }
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_ATTRIBUTES, ClientboundPackets26_1.UPDATE_ATTRIBUTES, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityAttribute[] attributes = new EntityAttribute[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // attribute count
            for (int i = 0; i < attributes.length; i++) {
                final float minValue = wrapper.read(BedrockTypes.FLOAT_LE); // min value
                final float maxValue = wrapper.read(BedrockTypes.FLOAT_LE); // max value
                final float currentValue = wrapper.read(BedrockTypes.FLOAT_LE); // current value
                final float defaultMinValue = wrapper.read(BedrockTypes.FLOAT_LE); // default min value
                final float defaultMaxValue = wrapper.read(BedrockTypes.FLOAT_LE); // default max value
                final float defaultValue = wrapper.read(BedrockTypes.FLOAT_LE); // default value
                final String name = wrapper.read(BedrockTypes.STRING); // name
                final EntityAttribute.Modifier[] modifiers = new EntityAttribute.Modifier[wrapper.read(BedrockTypes.UNSIGNED_VAR_INT)]; // modifier count
                for (int j = 0; j < modifiers.length; j++) {
                    final String id = wrapper.read(BedrockTypes.STRING); // id
                    final String modifierName = wrapper.read(BedrockTypes.STRING); // name
                    final float amount = wrapper.read(BedrockTypes.FLOAT_LE); // amount
                    final AttributeModifierOperation operation = AttributeModifierOperation.getByValue(wrapper.read(BedrockTypes.INT_LE), AttributeModifierOperation.OPERATION_INVALID); // operation
                    final AttributeOperands operand = AttributeOperands.getByValue(wrapper.read(BedrockTypes.INT_LE), AttributeOperands.OPERAND_INVALID); // operand
                    final boolean isSerializable = wrapper.read(Types.BOOLEAN); // is serializable
                    modifiers[j] = new EntityAttribute.Modifier(id, modifierName, amount, operation, operand, isSerializable);
                }
                attributes[i] = new EntityAttribute(name, currentValue, minValue, maxValue, defaultValue, defaultMinValue, defaultMaxValue, modifiers);
            }
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick

            final Entity entity = entityTracker.getEntityByRid(entityRuntimeId);
            if (entity instanceof LivingEntity livingEntity) {
                livingEntity.updateAttributes(attributes, wrapper);
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_ENTITY_DATA, ClientboundPackets26_1.SET_ENTITY_DATA, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);

            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final EntityData[] entityData = wrapper.read(BedrockTypes.ENTITY_DATA_ARRAY); // entity data
            final EntityProperties rawEntityProperties = wrapper.read(BedrockTypes.ENTITY_PROPERTIES); // entity properties
            final long entityDataFrame = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick

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
            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(VersionedTypes.V26_1.entityDataList, javaEntityData); // entity data
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOB_EFFECT, ClientboundPackets26_1.UPDATE_MOB_EFFECT, wrapper -> {
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final MobEffectPacketPayload_Event event = MobEffectPacketPayload_Event.getByValue(wrapper.read(Types.BYTE), MobEffectPacketPayload_Event.Invalid); // event id
            final int effectId = wrapper.read(BedrockTypes.VAR_INT); // effect id
            final int amplifier = wrapper.read(BedrockTypes.VAR_INT); // amplifier
            final boolean showParticles = wrapper.read(Types.BOOLEAN); // show particles
            final int duration = wrapper.read(BedrockTypes.VAR_INT); // duration
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick
            final boolean ambient = EntityPacketLayout.readAmbient(wrapper);

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByRid(entityRuntimeId);
            if (!(entity instanceof LivingEntity livingEntity) || effectId == 0) {
                wrapper.cancel();
                return;
            }

            final String bedrockIdentifier = BedrockProtocol.MAPPINGS.getBedrockEffects().inverse().get(effectId);
            if (bedrockIdentifier == null) { // Bedrock client crashes
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
            final int rawAction = EntityPacketLayout.readAnimateAction(wrapper); // action
            final AnimatePacketPayload_Action action = AnimatePacketPayload_Action.getByValue(rawAction, AnimatePacketPayload_Action.NoAction);
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            wrapper.read(BedrockTypes.FLOAT_LE); // data
            // MOT 860 AnimatePacket.decode(): protocol < 897 && ROW_LEFT/ROW_RIGHT always carry rowingTime.
            EntityPacketLayout.skipRowingTime(wrapper, rawAction);
            EntityPacketLayout.skipSwingSource(wrapper);

            final JavaAnimate javaAnimate = resolveJavaAnimate(action, entityRuntimeId, wrapper.user().get(EntityTracker.class)::getEntityByRid);
            if (javaAnimate == null) {
                wrapper.cancel();
                return;
            }

            final Entity entity = javaAnimate.entity();
            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.UNSIGNED_BYTE, (short) javaAnimate.action().ordinal()); // action
            if (javaAnimate.action() == AnimateAction.WAKE_UP && entity instanceof ClientPlayerEntity clientPlayer) {
                clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.StopSleeping);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.ANIMATE_ENTITY, null, wrapper -> {
            // Java has no MoLang animation system; a server-triggered named animation only makes sense for entities
            // rendered by the ViaBedrockUtility mod: custom entities, and humanoids on the player render path (real
            // players and player-type NPCs such as leaderboard NPCs). Read the packet, then forward
            // "entity uuid + animation name" to the mod so it can play the animation.
            final String animation = wrapper.read(BedrockTypes.STRING); // animation
            wrapper.read(BedrockTypes.STRING); // next state
            wrapper.read(BedrockTypes.STRING); // stop expression
            wrapper.read(Types.INT); // stop expression version (big-endian int)
            wrapper.read(BedrockTypes.STRING); // controller
            wrapper.read(BedrockTypes.FLOAT_LE); // blend out time
            final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // runtime entity id count
            final long[] runtimeIds = new long[count];
            for (int i = 0; i < count; i++) {
                runtimeIds[i] = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // runtime entity id
            }
            wrapper.cancel();

            final ChannelStorage channelStorage = wrapper.user().get(ChannelStorage.class);
            if (channelStorage == null || !channelStorage.hasChannel(ViaBedrockUtilityInterface.CONFIRM_CHANNEL)) {
                return; // No ViaBedrockUtility mod: nothing can render the animation on this path
            }
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            for (final long runtimeId : runtimeIds) {
                final Entity entity = entityTracker.getEntityByRid(runtimeId);
                if (entity instanceof CustomEntity || entity instanceof PlayerEntity) {
                    ViaBedrockUtilityInterface.sendAnimateEntity(wrapper.user(), entity.javaUuid(), animation);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOB_ARMOR_EQUIPMENT, ClientboundPackets26_1.SET_EQUIPMENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final BedrockItem head = wrapper.read(itemRewriter.itemType()); // head
            final BedrockItem chest = wrapper.read(itemRewriter.itemType()); // chest
            final BedrockItem legs = wrapper.read(itemRewriter.itemType()); // legs
            final BedrockItem feet = wrapper.read(itemRewriter.itemType()); // feet
            final BedrockItem body = wrapper.read(itemRewriter.itemType()); // body

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByRid(entityRuntimeId);
            if (entity == null || entity instanceof ClientPlayerEntity) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.FEET.ordinal() | Byte.MIN_VALUE)); // slot
            final Item javaFeet = itemRewriter.javaItem(feet);
            wrapper.write(VersionedTypes.V26_1.item, javaFeet); // item
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.LEGS.ordinal() | Byte.MIN_VALUE)); // slot
            final Item javaLegs = itemRewriter.javaItem(legs);
            wrapper.write(VersionedTypes.V26_1.item, javaLegs); // item
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.CHEST.ordinal() | Byte.MIN_VALUE)); // slot
            final Item javaChest = itemRewriter.javaItem(chest);
            wrapper.write(VersionedTypes.V26_1.item, javaChest); // item
            wrapper.write(Types.BYTE, (byte) (EquipmentSlot.HEAD.ordinal() | Byte.MIN_VALUE)); // slot
            final Item javaHead = itemRewriter.javaItem(head);
            wrapper.write(VersionedTypes.V26_1.item, javaHead); // item
            wrapper.write(Types.BYTE, (byte) EquipmentSlot.BODY.ordinal()); // slot
            final Item javaBody = itemRewriter.javaItem(body);
            wrapper.write(VersionedTypes.V26_1.item, javaBody); // item
        });
        protocol.registerClientbound(ClientboundBedrockPackets.MOB_EQUIPMENT, ClientboundPackets26_1.SET_EQUIPMENT, wrapper -> {
            final ItemRewriter itemRewriter = wrapper.user().get(ItemRewriter.class);
            final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final BedrockItem item = wrapper.read(itemRewriter.newItemType()); // item
            final byte slot = wrapper.read(Types.BYTE); // slot
            final byte selectedSlot = wrapper.read(Types.BYTE); // selected slot
            final byte containerId = wrapper.read(Types.BYTE); // container id

            final Entity entity = wrapper.user().get(EntityTracker.class).getEntityByRid(entityRuntimeId);
            if (entity == null || entity instanceof ClientPlayerEntity) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.VAR_INT, entity.javaId()); // entity id
            if (containerId == ContainerID.CONTAINER_ID_INVENTORY.getValue() && slot >= 0 && slot < 9 && (slot == selectedSlot || selectedSlot < 0)) {
                wrapper.write(Types.BYTE, (byte) EquipmentSlot.MAINHAND.ordinal()); // slot
                wrapper.write(VersionedTypes.V26_1.item, itemRewriter.javaItem(item)); // item
            } else if (containerId == ContainerID.CONTAINER_ID_OFFHAND.getValue()) {
                wrapper.write(Types.BYTE, (byte) EquipmentSlot.OFFHAND.ordinal()); // slot
                wrapper.write(VersionedTypes.V26_1.item, itemRewriter.javaItem(item)); // item
            } else {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.TAKE_ITEM_ENTITY, ClientboundPackets26_1.TAKE_ITEM_ENTITY, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final long itemEntityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // item entity runtime id
            final long collectorEntityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // collector entity runtime id

            final Entity itemEntity = entityTracker.getEntityByRid(itemEntityRuntimeId);
            final Entity collectorEntity = entityTracker.getEntityByRid(collectorEntityRuntimeId);
            if (!canTranslateTakeItemEntity(itemEntity, collectorEntity)) {
                wrapper.cancel();
                return;
            }
            wrapper.write(Types.VAR_INT, itemEntity.javaId()); // item entity id
            wrapper.write(Types.VAR_INT, collectorEntity.javaId()); // collector entity id
            wrapper.write(Types.VAR_INT, 0); // amount
        });
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
            // MOT Entity.java totem uses ActorEvent 65 (TALISMAN_ACTIVATE / CONSUME_TOTEM).
            // Java 1.21 expects PROTECTED_FROM_DEATH(35); without this the pop is cancelled.
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

    /**
     * Convert a Bedrock network Y into the Java entity foot Y.
     * MOT ADD/MOVE already include {@code getBaseOffset()} for boats (0.375);
     * Java ADD_ENTITY / ENTITY_POSITION_SYNC want the foot. Leaving the network Y
     * on spawn makes the JE hull sit high, and the first {@code MOVE_VEHICLE} then
     * feeds that elevated foot into predicted-boat SAI (#1-2 takeoff).
     */
    static float javaEntityY(final Entity entity, final float bedrockNetworkY) {
        return bedrockNetworkY - entity.eyeOffset();
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
