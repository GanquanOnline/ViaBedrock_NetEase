/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2025 RK_01/RaphiMC and contributors
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
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.EulerAngle;
import com.viaversion.viaversion.api.minecraft.Particle;
import com.viaversion.viaversion.api.minecraft.VillagerData;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityDataType;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.CustomEntity;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.experimental.ItemUseSemantics;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.LivingEntity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.EquipmentSlot;
import net.raphimc.viabedrock.experimental.storage.GlowProjectionTracker;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import net.raphimc.viabedrock.protocol.model.EntityPropertyValue;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;

import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.logging.Level;

public class EntityMetadataRewriter {

    private static final int SUMMON_VEX_SPELL_COLOR = 0xB3B3CC;
    private static final int FANGS_SPELL_COLOR = 0x664D59;
    private static final int WOLOLO_SPELL_COLOR = 0xB38033;

    // Called in Entity#translateEntityData if experimental features are enabled
    public static boolean rewrite(final UserConnection user, final Entity entity, final ActorDataIDs id, final EntityData entityData, final List<EntityData> javaEntityData) {
        EntityTracker entityTracker = user.get(EntityTracker.class);

        switch (id) {
            case RESERVED_0, RESERVED_092 -> { // Entity flags mask
                Set<ActorFlags> bedrockFlags = entity.entityFlags();
                byte javaBitMask = sharedFlags(user, entity, isForceInvisible(entity));
                final EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
                if (entity instanceof LivingEntity && scaleData != null && readNumber(scaleData).floatValue() == 0F) {
                    javaBitMask |= (1 << 5);
                }

                upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SHARED_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, javaBitMask));

                // Java players need POSE plus SLEEPING_POS. MOT sleep is PLAYER_FLAGS bit 1
                // (DATA_PLAYER_FLAG_SLEEP) and BED_POSITION, not ActorFlags.SNEAKING. Leaving
                // pose as standing/crouch makes the Java client skip the lie-down animation even
                // when MOT already accepted the bed and skipped the night.
                if (entity.javaType().is(EntityTypes1_21_11.PLAYER)) {
                    applyPlayerSleepPose(entity, javaEntityData, bedrockFlags);
                }

                upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SILENT), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.SILENT)));
                upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.NO_GRAVITY), VersionedTypes.V26_1.entityDataTypes().booleanType, noGravity(entity.javaType(), bedrockFlags)));

                if (entity instanceof LivingEntity) {
                    final byte livingFlags = entity == entityTracker.getClientPlayer()
                            ? localPlayerLivingFlags(bedrockFlags, entityTracker.getClientPlayer(), user.get(ItemRewriter.class))
                            : livingFlags(bedrockFlags);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.LIVING_ENTITY_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, livingFlags));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.MOB)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.MOB_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, mobFlags(entity.javaType(), bedrockFlags)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.ALLAY)) {
                    boolean dancing = bedrockFlags.contains(ActorFlags.DANCING);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.DANCING), VersionedTypes.V26_1.entityDataTypes().booleanType, dancing));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.ABSTRACT_AGEABLE)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));
                }

                if (entity.javaType().is(EntityTypes1_21_11.AXOLOTL)) {
                    boolean playingDead = bedrockFlags.contains(ActorFlags.PLAYING_DEAD);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PLAYING_DEAD), VersionedTypes.V26_1.entityDataTypes().booleanType, playingDead));
                }

                if (entity.javaType().is(EntityTypes1_21_11.BEE)) {
                    final Boolean hasNectar = booleanValue(entity.entityProperties().namedProperty("minecraft:has_nectar"));
                    // The actor flags path must still emit anger when the property snapshot has not arrived.
                    final byte beeBitMask = beeFlags(entity, hasNectar);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, beeBitMask));
                }

                if (entity.javaType().is(EntityTypes1_21_11.OCELOT)) {
                    boolean isTrusting = bedrockFlags.contains(ActorFlags.TRUSTING);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TRUSTING), VersionedTypes.V26_1.entityDataTypes().booleanType, isTrusting));
                }

                if (entity.javaType().is(EntityTypes1_21_11.SHEEP)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WOOL), VersionedTypes.V26_1.entityDataTypes().byteType, sheepFlags(entity)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.SNIFFER)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.STATE), VersionedTypes.V26_1.entityDataTypes().snifferState, snifferState(bedrockFlags)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.TURTLE)) { //TODO: Test
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HAS_EGG), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.IS_PREGNANT)));
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.LAYING_EGG), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.LAYING_EGG)));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.ABSTRACT_CHESTED_HORSE)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CHEST), VersionedTypes.V26_1.entityDataTypes().booleanType, bedrockFlags.contains(ActorFlags.CHESTED)));
                }

                // Java 1.21.5+ draws saddles from SADDLE equipment, not ABSTRACT_HORSE FLAGS 0x04.
                // MOT only sets ActorFlags.SADDLED (EntityHorseBase/Pig/Strider.setSaddled).
                if (usesJavaSaddleEquipment(entity.javaType())) {
                    sendSaddleEquipment(entity, bedrockFlags.contains(ActorFlags.SADDLED));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.TAMABLE_ANIMAL)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, tamableFlags(bedrockFlags)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.CAT)) {
                    final boolean isLying = bedrockFlags.contains(ActorFlags.RESTING) || bedrockFlags.contains(ActorFlags.LAYING_DOWN);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_LYING), VersionedTypes.V26_1.entityDataTypes().booleanType, isLying));
                }

                if (entity.javaType().is(EntityTypes1_21_11.BOGGED)) {
                    boolean isSheared = bedrockFlags.contains(ActorFlags.SHEARED);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SHEARED), VersionedTypes.V26_1.entityDataTypes().booleanType, isSheared));
                }

                if (entity.javaType().is(EntityTypes1_21_11.CREEPER)) {
                    boolean charged = bedrockFlags.contains(ActorFlags.CHARGED);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_POWERED), VersionedTypes.V26_1.entityDataTypes().booleanType, charged));

                    boolean ignited = bedrockFlags.contains(ActorFlags.IGNITED);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_IGNITED), VersionedTypes.V26_1.entityDataTypes().booleanType, ignited));
                }

                if (entity.javaType().is(EntityTypes1_21_11.ZOGLIN)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.ZOMBIE)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));
                }

                if (entity.javaType().is(EntityTypes1_21_11.PIGLIN)) {
                    boolean isBaby = bedrockFlags.contains(ActorFlags.BABY);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BABY), VersionedTypes.V26_1.entityDataTypes().booleanType, isBaby));

                    boolean isDancing = bedrockFlags.contains(ActorFlags.DANCING);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_DANCING), VersionedTypes.V26_1.entityDataTypes().booleanType, isDancing));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.ABSTRACT_RAIDER)) { //TODO: Test
                    boolean isCelebrating = bedrockFlags.contains(ActorFlags.CELEBRATING);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_CELEBRATING), VersionedTypes.V26_1.entityDataTypes().booleanType, isCelebrating));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.SPELLCASTER_ILLAGER)) {
                    applySpellCasting(entity, javaEntityData);
                }

                if (entity.javaType().is(EntityTypes1_21_11.BAT)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType,
                            (byte) (bedrockFlags.contains(ActorFlags.RESTING) ? 0x01 : 0)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.BLAZE)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType,
                            (byte) (bedrockFlags.contains(ActorFlags.ONFIRE) ? 0x01 : 0)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.FOX)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, foxFlags(bedrockFlags)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.PANDA)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, pandaFlags(bedrockFlags)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.POLAR_BEAR)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.STANDING), VersionedTypes.V26_1.entityDataTypes().booleanType,
                            bedrockFlags.contains(ActorFlags.STANDING)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.SNOW_GOLEM)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PUMPKIN), VersionedTypes.V26_1.entityDataTypes().byteType,
                            (byte) (bedrockFlags.contains(ActorFlags.SHEARED) ? 0 : 0x10)));
                }

                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.SPIDER)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType,
                            (byte) (bedrockFlags.contains(ActorFlags.WALLCLIMBING) ? 0x01 : 0)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.WOLF)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.INTERESTED), VersionedTypes.V26_1.entityDataTypes().booleanType,
                            bedrockFlags.contains(ActorFlags.INTERESTED)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.ENDERMAN)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.STARED_AT), VersionedTypes.V26_1.entityDataTypes().booleanType,
                            bedrockFlags.contains(ActorFlags.ANGRY)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.PIGLIN) || entity.javaType().is(EntityTypes1_21_11.PILLAGER)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_CHARGING_CROSSBOW), VersionedTypes.V26_1.entityDataTypes().booleanType,
                            bedrockFlags.contains(ActorFlags.CHARGING)));
                }

                if (entity.javaType().is(EntityTypes1_21_11.ZOMBIE_VILLAGER)) {
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CONVERTING), VersionedTypes.V26_1.entityDataTypes().booleanType,
                            bedrockFlags.contains(ActorFlags.IS_TRANSFORMING)));
                }

            }
            case VARIANT -> {
                int variant = readNumber(entityData).intValue();

                switch (entity.javaType()) {
                    case WOLF -> {
                        final Integer javaVariant = javaRegistryIndex("minecraft:wolf_variant", wolfVariantName(variant));
                        if (javaVariant != null) {
                            javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().wolfVariantType, javaVariant));
                        }
                    }
                    case CAT -> {
                        final Integer javaVariant = javaRegistryIndex("minecraft:cat_variant", catVariantName(variant));
                        if (javaVariant != null) {
                            javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().catVariantType, javaVariant));
                        }
                    }
                    case HORSE -> {
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE_VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, variant));
                    }
                    case FROG -> {
                        final Integer javaVariant = javaRegistryIndex("minecraft:frog_variant", frogVariantName(variant));
                        if (javaVariant != null) {
                            javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().frogVariantType, javaVariant));
                        }
                    }
                    case FOX -> {
                        final int javaVariant = variant == 1 ? 1 : 0; // SNOW : RED
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case TROPICAL_FISH -> applyTropicalFishVariant(entity, javaEntityData);
                    case PUFFERFISH -> {} // For some reason bedrock sends the puffed state here as well as in the PUFFED_STATE Actor ID so we ignore this one
                    case SHULKER -> {
                        byte color = (byte) variant;
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.COLOR), VersionedTypes.V26_1.entityDataTypes().byteType, color));
                    }
                    case AXOLOTL -> {
                        int javaVariant = switch (variant) {
                            case 0 -> 0; // LUCY
                            case 1 -> 3; // CYAN
                            case 2 -> 2; // GOLD
                            case 3 -> 1; // WILD
                            case 4 -> 4; // BLUE
                            default -> {
                                ViaBedrock.getPlatform().getLogger().warning("Unknown axolotl variant " + variant + ", defaulting to LUCY.");
                                yield 0;
                            }
                        };
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case MOOSHROOM -> {
                        int javaVariant = switch (variant) {
                            case 0 -> 0; // RED
                            case 1 -> 1; // BROWN
                            default -> {
                                ViaBedrock.getPlatform().getLogger().warning("Unknown mooshroom variant " + variant + ", defaulting to RED.");
                                yield 0;
                            }
                        };
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case SLIME, MAGMA_CUBE -> {
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SIZE), VersionedTypes.V26_1.entityDataTypes().varIntType, variant));
                    }
                    case RABBIT -> { // TODO: Test when I can
                        int javaVariant = variant;
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case PARROT -> { // TODO: Test when I can
                        int javaVariant = variant;
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, javaVariant));
                    }
                    case VILLAGER, ZOMBIE_VILLAGER -> {
                        // Bedrock encodes the villager profession in VARIANT (region/biome in MARK_VARIANT,
                        // trade level in TRADE_TIER). All three combine into one Java VILLAGER_DATA field.
                        applyVillagerData(entity, javaEntityData);
                    }
                    default -> {
                        if (variant != 0 && !(entity instanceof CustomEntity)) { // Custom entity variants are consumed by the custom renderer.
                            ViaBedrock.getPlatform().getLogger().warning("Received non-zero VARIANT " + variant + " for unsupported entity " + entity.type());
                        }
                    }
                }

            }
            case MARK_VARIANT, TRADE_TIER, COLOR_2_INDEX -> {
                if (entity.javaType().is(EntityTypes1_21_11.TROPICAL_FISH)) {
                    applyTropicalFishVariant(entity, javaEntityData);
                    break;
                }
                // Villager region/biome (MARK_VARIANT) and trade level (TRADE_TIER) also feed the combined
                // Java VILLAGER_DATA field. Other entities using these IDs are not translated yet.
                if (id == ActorDataIDs.COLOR_2_INDEX) {
                    return false;
                }
                if (entity.javaType().is(EntityTypes1_21_11.VILLAGER) || entity.javaType().is(EntityTypes1_21_11.ZOMBIE_VILLAGER)) {
                    applyVillagerData(entity, javaEntityData);
                } else {
                    return false;
                }
            }
            case COLOR_INDEX -> {
                int javaColorIndex = readNumber(entityData).intValue();

                switch (entity.javaType()) {
                    case TROPICAL_FISH -> applyTropicalFishVariant(entity, javaEntityData);
                    case WOLF, CAT -> {
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.COLLAR_COLOR), VersionedTypes.V26_1.entityDataTypes().varIntType, javaColorIndex));
                    }
                    case SHEEP -> {
                        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WOOL), VersionedTypes.V26_1.entityDataTypes().byteType, sheepFlags(entity)));
                    }
                    default -> {
                        if (javaColorIndex != 0 && !(entity instanceof CustomEntity)) { // Custom entity colors are consumed by the custom renderer.
                            ViaBedrock.getPlatform().getLogger().warning("Received non-zero COLOR_INDEX " + javaColorIndex + " for unsupported entity " + entity.type());
                        }
                    }
                }
            }
            case OWNER -> {
                if (entity.javaType().is(EntityTypes1_21_11.FISHING_BOBBER)) {
                    break; // Fishing hook owners are carried by the Java ADD_ENTITY data field.
                }
                long ownerId = readNumber(entityData).longValue();
                if (ownerId == -1 || ownerId == 0) {
                    if (entity.javaType().isOrHasParent(EntityTypes1_21_11.TAMABLE_ANIMAL)) {
                        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.OWNERUUID), VersionedTypes.V26_1.entityDataTypes().optionalUUIDType, null));
                    }
                    break;
                }
                Entity ownerEntity = entityTracker.getEntityByUid(ownerId);
                if (ownerEntity == null) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find owner entity with id " + ownerId + " for entity " + entity.type());
                    break;
                }
                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.TAMABLE_ANIMAL)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.OWNERUUID), VersionedTypes.V26_1.entityDataTypes().optionalUUIDType, ownerEntity.javaUuid()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received OWNER for non-TAMEABLE_ANIMAL entity " + entity.type());
                }
            }
            case CHARGE_AMOUNT -> {
                if (entity.javaType().is(EntityTypes1_21_11.GHAST)) {
                    final boolean charging = readNumber(entityData).intValue() > 0;
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.IS_CHARGING), VersionedTypes.V26_1.entityDataTypes().booleanType, charging));
                } else {
                    return false;
                }
            }
            case DATA_SPELL_CASTING_COLOR -> {
                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.SPELLCASTER_ILLAGER)) {
                    applySpellCasting(entity, javaEntityData);
                } else {
                    return false;
                }
            }
            case FUSE_TIME -> {
                int fuseTime = readNumber(entityData).intValue();
                if (entity.javaType().is(EntityTypes1_21_11.TNT)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FUSE), VersionedTypes.V26_1.entityDataTypes().varIntType, fuseTime));
                } else {
                    //ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received FUSE_TIME for non-TNT entity " + entity.type());
                }
            }
            case AIR_SUPPLY -> { // Air supply is stored as a short in Bedrock, but an int in Java (Bedrock also has a max air supply value we ignore for now)
                int airSupply = readNumber(entityData).intValue();
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.AIR_SUPPLY), VersionedTypes.V26_1.entityDataTypes().varIntType, airSupply));
            }
            case POSE_INDEX -> {
                if (!entity.javaType().is(EntityTypes1_21_11.ARMOR_STAND)) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received POSE_INDEX for non-ARMOR_STAND entity " + entity.type());
                    break;
                }

                byte javaBitMask = 0;
                javaBitMask |= 0x04; // Has arms
                EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
                if (scaleData != null && readNumber(scaleData).floatValue() == 0f) {
                    javaBitMask |= 0x10; // Marker: zero-size bounding box (matches Bedrock scale=0)
                }

                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CLIENT_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, javaBitMask));

                int poseIndex = readNumber(entityData).intValue();

                EulerAngle headPose;
                EulerAngle bodyPose;
                EulerAngle leftArmPose;
                EulerAngle rightArmPose;
                EulerAngle leftLegPose;
                EulerAngle rightLegPose;

                //Poses from https://github.com/lpsmods/armor-stand-poses/blob/1.21/datapack/datapack/data/poses/function/armor_stand/defaults.mcfunction
                switch (poseIndex) {
                    case 0 -> { // DEFAULT
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-10f, 0f, -10f);
                        rightArmPose = new EulerAngle(-15f, 0f, 10f);
                        leftLegPose = new EulerAngle(-1f, 0f, -1f);
                        rightLegPose = new EulerAngle(1f, 0f, 1f);
                    }
                    case 1 -> { // NONE
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(0f, 0f, 0f);
                        rightArmPose = new EulerAngle(0f, 0f, 0f);
                        leftLegPose = new EulerAngle(0f, 0f, 0f);
                        rightLegPose = new EulerAngle(0f, 0f, 0f);
                    }
                    case 2 -> { // SOLEMN
                        headPose = new EulerAngle(15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 2f);
                        leftArmPose = new EulerAngle(-30f, 15f, 15f);
                        rightArmPose = new EulerAngle(-60f, -20f, -10f);
                        leftLegPose = new EulerAngle(-1f, 0f, -1f);
                        rightLegPose = new EulerAngle(1f, 0f, 1f);
                    }
                    case 3 -> { // ATHENA
                        headPose = new EulerAngle(-5f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 2f);
                        leftArmPose = new EulerAngle(10f, 0f, -5f);
                        rightArmPose = new EulerAngle(-60f, 20f, -10f);
                        leftLegPose = new EulerAngle(-3f, -3f, -3f);
                        rightLegPose = new EulerAngle(3f, 3f, 3f);
                    }
                    case 4 -> { // BRANDISH
                        headPose = new EulerAngle(-15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, -2f);
                        leftArmPose = new EulerAngle(20f, 0f, -10f);
                        rightArmPose = new EulerAngle(-110f, 50f, 0f);
                        leftLegPose = new EulerAngle(5f, -3f, -3f);
                        rightLegPose = new EulerAngle(-5f, 3f, 3f);
                    }
                    case 5 -> { // HONOR
                        headPose = new EulerAngle(-15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-110f, 35f, 0f);
                        rightArmPose = new EulerAngle(-110f, -35f, 0f);
                        leftLegPose = new EulerAngle(5f, -3f, -3f);
                        rightLegPose = new EulerAngle(-5f, 3f, 3f);
                    }
                    case 6 -> { // ENTERTAIN
                        headPose = new EulerAngle(-15f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-110f, -35f, 0f);
                        rightArmPose = new EulerAngle(-110f, 35f, 0f);
                        leftLegPose = new EulerAngle(5f, -3f, -3f);
                        rightLegPose = new EulerAngle(-5f, 3f, 3f);
                    }
                    case 7 -> { // SALUTE
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(10f, 0f, -5f);
                        rightArmPose = new EulerAngle(-70f, -40f, 0f);
                        leftLegPose = new EulerAngle(-1f, 0f, -1f);
                        rightLegPose = new EulerAngle(1f, 0f, 1f);
                    }
                    case 8 -> { // RIPOSTE
                        headPose = new EulerAngle(16f, 20f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(4f, 8f, 237f);
                        rightArmPose = new EulerAngle(246f, 0f, 89f);
                        leftLegPose = new EulerAngle(-14f, -18f, -16f);
                        rightLegPose = new EulerAngle(8f, 20f, 4f);
                    }
                    case 9 -> { // ZOMBIE
                        headPose = new EulerAngle(-10f, 0f, -5f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(-105f, 0f, 0f);
                        rightArmPose = new EulerAngle(-100f, 0f, 0f);
                        leftLegPose = new EulerAngle(7f, 0f, 0f);
                        rightLegPose = new EulerAngle(-46f, 0f, 0f);
                    }
                    case 10 -> { // CAN_CAN_A
                        headPose = new EulerAngle(-5f, 18f, 0f);
                        bodyPose = new EulerAngle(0f, 22f, 0f);
                        leftArmPose = new EulerAngle(8f, 0f, -114f);
                        rightArmPose = new EulerAngle(0f, 84f, 111f);
                        leftLegPose = new EulerAngle(-111f, 55f, 0f);
                        rightLegPose = new EulerAngle(0f, 23f, -13f);
                    }
                    case 11 -> { // CAN_CAN_B
                        headPose = new EulerAngle(-10f, -20f, 0f);
                        bodyPose = new EulerAngle(0f, -18f, 0f);
                        leftArmPose = new EulerAngle(0f, 0f, -112f);
                        rightArmPose = new EulerAngle(8f, 90f, 111f);
                        leftLegPose = new EulerAngle(0f, 0f, 13f);
                        rightLegPose = new EulerAngle(-119f, -42f, 0f);
                    }
                    case 12 -> { // HERO
                        headPose = new EulerAngle(-4f, 67f, 0f);
                        bodyPose = new EulerAngle(0f, 8f, 0f);
                        leftArmPose = new EulerAngle(16f, 32f, -8f);
                        rightArmPose = new EulerAngle(-99f, 63f, 0f);
                        leftLegPose = new EulerAngle(0f, -75f, -8f);
                        rightLegPose = new EulerAngle(4f, 63f, 8f);
                    }
                    default -> {
                        // Fallback to none
                        headPose = new EulerAngle(0f, 0f, 0f);
                        bodyPose = new EulerAngle(0f, 0f, 0f);
                        leftArmPose = new EulerAngle(0f, 0f, 0f);
                        rightArmPose = new EulerAngle(0f, 0f, 0f);
                        leftLegPose = new EulerAngle(0f, 0f, 0f);
                        rightLegPose = new EulerAngle(0f, 0f, 0f);
                        ViaBedrock.getPlatform().getLogger().warning("Unknown armor stand pose index " + poseIndex + ", defaulting to NONE.");
                    }
                }

                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HEAD_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, headPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.BODY_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, bodyPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.LEFT_ARM_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, leftArmPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.RIGHT_ARM_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, rightArmPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.LEFT_LEG_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, leftLegPose));
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.RIGHT_LEG_POSE), VersionedTypes.V26_1.entityDataTypes().rotationsType, rightLegPose));
            }

            case PUFFED_STATE -> {
                int javaPuffedState = readNumber(entityData).intValue();
                if (entity.javaType().is(EntityTypes1_21_11.PUFFERFISH)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PUFF_STATE), VersionedTypes.V26_1.entityDataTypes().varIntType, javaPuffedState));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received PUFFED_STATE for non-PUFFERFISH entity " + entity.type());
                }
            }
            case FREEZING_EFFECT_STRENGTH -> {
                float freezingStrength = readNumber(entityData).floatValue();

                // Java freezing strength is from 0-140 whereas Bedrock is from 0.0-1.0
                int javaStrength = Math.round(freezingStrength * 140f);
                javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TICKS_FROZEN), VersionedTypes.V26_1.entityDataTypes().varIntType, javaStrength));
            }
            case GOAT_HORN_COUNT -> {
                if (entity.javaType().is(EntityTypes1_21_11.GOAT)) {
                    // In bedrock the goat always loses its right horn first, whereas in java its random
                    int hornCount = readNumber(entityData).intValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HAS_LEFT_HORN), VersionedTypes.V26_1.entityDataTypes().booleanType, hornCount != 0));
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HAS_RIGHT_HORN), VersionedTypes.V26_1.entityDataTypes().booleanType, hornCount == 2));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received GOAT_HORN_COUNT for non-GOAT entity " + entity.type());
                }
            }
            case EATING_COUNTER -> {
                int eatingCounter = readNumber(entityData).intValue();
                if (entity.javaType().is(EntityTypes1_21_11.PANDA)) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.EAT_COUNTER), VersionedTypes.V26_1.entityDataTypes().varIntType, eatingCounter));
                } else if (eatingCounter != 0) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received EATING_COUNTER for non-PANDA entity " + entity.type() + " with non-zero value " + eatingCounter);
                }
            }
            case ATTACH_FACE -> {
                if (entity.javaType().is(EntityTypes1_21_11.SHULKER)) {
                    int javaAttachFace = readNumber(entityData).intValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ATTACH_FACE), VersionedTypes.V26_1.entityDataTypes().directionType, javaAttachFace));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received ATTACH_FACE for non-SHULKER entity " + entity.type());
                }
            }
            case PEEK_ID -> {
                if (entity.javaType().is(EntityTypes1_21_11.SHULKER)) {
                    byte peek = readNumber(entityData).byteValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PEEK), VersionedTypes.V26_1.entityDataTypes().byteType, peek));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received PEEK_ID for non-SHULKER entity " + entity.type());
                }
            }
            case ATTACHED, ATTACH_POS -> { // Not needed in java
                if (!entity.javaType().is(EntityTypes1_21_11.SHULKER)) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received ATTACH for non-SHULKER entity " + entity.type());
                }
            }
            case RESERVED_053 -> { // BOUNDING_BOX_WIDTH
                if (entity.javaType().is(EntityTypes1_21_11.INTERACTION)) {
                    float width = readNumber(entityData).floatValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WIDTH), VersionedTypes.V26_1.entityDataTypes().floatType, width));
                }
            }
            case RESERVED_054 -> { // BOUNDING_BOX_HEIGHT
                if (entity.javaType().is(EntityTypes1_21_11.INTERACTION)) {
                    float height = readNumber(entityData).floatValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HEIGHT), VersionedTypes.V26_1.entityDataTypes().floatType, height));
                }
            }
            case EFFECT_COLOR -> {
                if (entity.javaType().is(EntityTypes1_21_11.AREA_EFFECT_CLOUD)) {
                    writeAreaEffectCloudParticle(entity, javaEntityData);
                }
            }
            case DATA_RADIUS -> {
                if (entity.javaType().isOrHasParent(EntityTypes1_21_11.AREA_EFFECT_CLOUD)) {
                    float radius = readNumber(entityData).floatValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.RADIUS), VersionedTypes.V26_1.entityDataTypes().floatType, radius));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received DATA_RADIUS for non-AREA_EFFECT_CLOUD entity " + entity.type());
                }
            }
            case DATA_WAITING -> {
                if (entity.javaType().is(EntityTypes1_21_11.AREA_EFFECT_CLOUD)) {
                    final boolean isWaiting = areaEffectCloudWaiting(readNumber(entityData));
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.WAITING), VersionedTypes.V26_1.entityDataTypes().booleanType, isWaiting));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received DATA_WAITING for non-AREA_EFFECT_CLOUD entity " + entity.type());
                }
            }
            case DATA_PARTICLE -> {
                if (entity.javaType().is(EntityTypes1_21_11.AREA_EFFECT_CLOUD)) {
                    writeAreaEffectCloudParticle(entity, javaEntityData);
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received DATA_PARTICLE for non-AREA_EFFECT_CLOUD entity " + entity.type());
                }
            }
            case INV -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    int invulnerabilityTicks = readNumber(entityData).intValue();
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.INV), VersionedTypes.V26_1.entityDataTypes().varIntType, invulnerabilityTicks));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received INV for non-WITHER entity " + entity.type());
                }
            }
            case TARGET_A -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    long targetAId = readNumber(entityData).longValue();
                    if (targetAId == -1 || targetAId == 0) {
                        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_A), VersionedTypes.V26_1.entityDataTypes().varIntType, 0));
                        break;
                    }
                    Entity targetAEntity = entityTracker.getEntityByUid(targetAId);
                    if (targetAEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET_A entity with id " + targetAId + " for entity " + entity.type());
                        break;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_A), VersionedTypes.V26_1.entityDataTypes().varIntType, targetAEntity.javaId()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET_A for non-WITHER entity " + entity.type());
                }
            }
            case TARGET_B -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    long targetBId = readNumber(entityData).longValue();
                    if (targetBId == -1 || targetBId == 0) {
                        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_B), VersionedTypes.V26_1.entityDataTypes().varIntType, 0));
                        break;
                    }
                    Entity targetBEntity = entityTracker.getEntityByUid(targetBId);
                    if (targetBEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET_B entity with id " + targetBId + " for entity " + entity.type());
                        break;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_B), VersionedTypes.V26_1.entityDataTypes().varIntType, targetBEntity.javaId()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET_B for non-WITHER entity " + entity.type());
                }
            }
            case TARGET_C -> {
                if (entity.javaType().is(EntityTypes1_21_11.WITHER)) {
                    long targetCId = readNumber(entityData).longValue();
                    if (targetCId == -1 || targetCId == 0) {
                        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_C), VersionedTypes.V26_1.entityDataTypes().varIntType, 0));
                        break;
                    }
                    Entity targetCEntity = entityTracker.getEntityByUid(targetCId);
                    if (targetCEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET_C entity with id " + targetCId + " for entity " + entity.type());
                        break;
                    }
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TARGET_C), VersionedTypes.V26_1.entityDataTypes().varIntType, targetCEntity.javaId()));
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET_C for non-WITHER entity " + entity.type());
                }
            }
            case TARGET -> {
                long targetId = readNumber(entityData).longValue();
                if (entity.javaType().is(EntityTypes1_21_11.GUARDIAN)) {
                    if (targetId == -1 || targetId == 0) {
                        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ATTACK_TARGET), VersionedTypes.V26_1.entityDataTypes().varIntType, 0));
                        break;
                    }
                    Entity targetEntity = entityTracker.getEntityByUid(targetId);
                    if (targetEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET entity with id " + targetId + " for entity " + entity.type());
                        break;
                    }
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.ATTACK_TARGET), VersionedTypes.V26_1.entityDataTypes().varIntType, targetEntity.javaId()));
                } else if (entity.javaType().is(EntityTypes1_21_11.VEX)) {
                    final byte flags = (byte) (targetId != -1 && targetId != 0 ? 0x01 : 0);
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, flags));
                } else if (entity.javaType().is(EntityTypes1_21_11.FISHING_BOBBER)) {
                    // MOT EntityFishingHook.setTarget writes DATA_TARGET_EID. Java fishing
                    // bobbers store HOOKED_ENTITY as the hooked Java id plus one (0 = none).
                    final Integer hookedEntity = fishingBobberHookedEntity(targetId, uniqueId -> {
                        final Entity hooked = entityTracker.getEntityByUid(uniqueId);
                        return hooked != null ? hooked.javaId() : null;
                    });
                    if (hookedEntity == null) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to find TARGET entity with id " + targetId + " for entity " + entity.type());
                        break;
                    }
                    upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.HOOKED_ENTITY), VersionedTypes.V26_1.entityDataTypes().varIntType, hookedEntity));
                } else if (targetId != 0 && targetId != -1)  {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received TARGET for non-GUARDIAN entity " + entity.type() + " with non-zero value " + targetId);
                }
            }
            case NAME, NAME_RAW_TEXT, NAMETAG_ALWAYS_SHOW -> {
                // Trim blank lines so a single-line CUSTOM_NAME never carries leading/trailing newlines
                // (rendered as missing-glyph boxes). Vanilla Java CUSTOM_NAME also cannot render interior
                // newlines, so only the bottom line stays here. For multiline always-show entities the
                // tracker spawns a TEXT_DISPLAY; the host last-line CUSTOM_NAME must stay hidden or
                // ArmorStandRenderer will draw it on top of the display (an extra overlapping row).
                writeHostNametag(entity, javaEntityData);
            }
            case RESERVED_038 -> { // SCALE (Bedrock entity data ID 38)
                float scale = readNumber(entityData).floatValue();
                if (entity instanceof LivingEntity) {
                    javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SHARED_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, sharedFlags(user, entity, scale == 0f)));
                }
                if (entity.javaType().is(EntityTypes1_21_11.ARMOR_STAND)) {
                    if (scale == 0f) {
                        // Bedrock: scale=0 hides body but keeps nametag visible
                        // Java armor stands also need Marker to remove bounding box height.
                        byte clientFlags = 0x04; // Has arms
                        clientFlags |= 0x10; // Marker: zero-size bounding box
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CLIENT_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, clientFlags));
                    } else {
                        byte clientFlags = 0x04; // Has arms, no marker
                        javaEntityData.add(new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.CLIENT_FLAGS), VersionedTypes.V26_1.entityDataTypes().byteType, clientFlags));
                    }
                }

                // Send minecraft:scale attribute for all LivingEntity types (including ARMOR_STAND when scale != 0)
                if (entity instanceof LivingEntity && scale != 0f) {
                    final double javaScale = Math.max(1.0 / 16.0, Math.min(16.0, (double) scale));

                    final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, user);
                    updateAttributes.write(Types.VAR_INT, entity.javaId()); // entity id
                    updateAttributes.write(Types.VAR_INT, 1); // attribute count
                    updateAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.SCALE)); // attribute id
                    updateAttributes.write(Types.DOUBLE, javaScale); // base value
                    updateAttributes.write(Types.VAR_INT, 0); // modifier count
                    updateAttributes.send(BedrockProtocol.class);
                }
            }
            case AGENT, BALLOON_ANCHOR -> {} // Education edition only, ignore
            case PLAYER_FLAGS, BED_POSITION, ENTER_BED_POSITION -> {
                // MOT sleepOn() writes PLAYER_FLAGS + BED_POSITION together; either field can
                // arrive first. Recompute pose and SLEEPING_POS from the stored pair so Java
                // always sees both or neither.
                if (!entity.javaType().is(EntityTypes1_21_11.PLAYER)) {
                    return false;
                }
                applyPlayerSleepPose(entity, javaEntityData, entity.entityFlags());
            }
            case ROW_TIME_LEFT, ROW_TIME_RIGHT -> {
                if (!entity.javaType().isOrHasParent(EntityTypes1_21_11.ABSTRACT_BOAT)) {
                    return false;
                }
                // MOT EntityBoat DATA 13/14 (ROW_TIME_*) maps to Java ABSTRACT_BOAT PADDLE_LEFT/RIGHT.
                // Java stores a boolean "is paddling"; MOT stores a float animation clock.
                final boolean paddling = readNumber(entityData).floatValue() != 0F;
                final String field = id == ActorDataIDs.ROW_TIME_LEFT ? EntityDataFields.PADDLE_LEFT : EntityDataFields.PADDLE_RIGHT;
                upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(field), VersionedTypes.V26_1.entityDataTypes().booleanType, paddling));
            }
            default -> {
                return false;
            }
        }

        return true;
    }

    /** Translates named MOT properties after the dynamic registry has resolved them. */
    public static boolean rewriteEntityProperties(final Entity entity, final List<EntityData> javaEntityData) {
        final EntityProperties properties = entity.entityProperties();
        boolean translated = false;
        final EntityTypes1_21_11 type = entity.javaType();

        if (type.is(EntityTypes1_21_11.COW)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.VARIANT,
                    VersionedTypes.V26_1.entityDataTypes().cowVariantType,
                    javaRegistryIndex("minecraft:cow_variant",
                            climateVariantName(properties.namedProperty("minecraft:climate_variant"))));
        } else if (type.is(EntityTypes1_21_11.PIG)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.VARIANT,
                    VersionedTypes.V26_1.entityDataTypes().pigVariantType,
                    javaRegistryIndex("minecraft:pig_variant",
                            climateVariantName(properties.namedProperty("minecraft:climate_variant"))));
        } else if (type.is(EntityTypes1_21_11.CHICKEN)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.VARIANT,
                    VersionedTypes.V26_1.entityDataTypes().chickenVariantType,
                    javaRegistryIndex("minecraft:chicken_variant",
                            climateVariantName(properties.namedProperty("minecraft:climate_variant"))));
        }

        if (type.is(EntityTypes1_21_11.CAT)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.SOUND_VARIANT,
                    VersionedTypes.V26_1.entityDataTypes().catSoundVariant,
                    javaRegistryIndex("minecraft:cat_sound_variant",
                            catSoundVariantName(properties.namedProperty("minecraft:sound_variant"))));
        } else if (type.is(EntityTypes1_21_11.WOLF)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.SOUND_VARIANT,
                    VersionedTypes.V26_1.entityDataTypes().wolfSoundVariantType,
                    javaRegistryIndex("minecraft:wolf_sound_variant",
                            wolfSoundVariantName(properties.namedProperty("minecraft:sound_variant"))));
        }

        if (type.is(EntityTypes1_21_11.ARMADILLO)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.ARMADILLO_STATE,
                    VersionedTypes.V26_1.entityDataTypes().armadilloState,
                    javaArmadilloState(properties.namedProperty("minecraft:armadillo_state")));
        }

        if (type.is(EntityTypes1_21_11.CREAKING)) {
            final Boolean canMoveValue = booleanValue(properties.namedProperty("minecraft:can_move"));
            if (canMoveValue != null) {
                translated |= putProperty(entity, javaEntityData, EntityDataFields.CAN_MOVE,
                        VersionedTypes.V26_1.entityDataTypes().booleanType, canMoveValue);
            }

            final EntityPropertyValue state = properties.namedProperty("minecraft:creaking_state");
            if (state != null && state.enumValue() != null) {
                final CreakingStateFlags stateFlags = creakingState(state.enumValue());
                if (stateFlags != null) {
                    translated |= putProperty(entity, javaEntityData, EntityDataFields.IS_ACTIVE,
                            VersionedTypes.V26_1.entityDataTypes().booleanType, stateFlags.active());
                    translated |= putProperty(entity, javaEntityData, EntityDataFields.IS_TEARING_DOWN,
                            VersionedTypes.V26_1.entityDataTypes().booleanType, stateFlags.tearingDown());
                }
            }
            // minecraft:creaking_swaying_ticks has no Java metadata equivalent; it remains in the snapshot.
        }

        if (type.is(EntityTypes1_21_11.HAPPY_GHAST)) {
            final Boolean canMoveValue = booleanValue(properties.namedProperty("minecraft:can_move"));
            if (canMoveValue != null) {
                translated |= putProperty(entity, javaEntityData, EntityDataFields.STAYS_STILL,
                        VersionedTypes.V26_1.entityDataTypes().booleanType, !canMoveValue);
            }
        }

        if (type.is(EntityTypes1_21_11.COPPER_GOLEM)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.WEATHER_STATE,
                    VersionedTypes.V26_1.entityDataTypes().weatheringCopperState,
                    javaWeatheringCopperState(properties.namedProperty("minecraft:oxidation_level")));
            translated |= putProperty(entity, javaEntityData, EntityDataFields.COPPER_GOLEM_STATE,
                    VersionedTypes.V26_1.entityDataTypes().copperGolemState,
                    javaCopperGolemState(properties.namedProperty("minecraft:chest_interaction")));
            // minecraft:has_flower is Java SADDLE equipment containing a poppy, not metadata.
            sendCopperGolemFlowerEquipment(entity, booleanValue(properties.namedProperty("minecraft:has_flower")));
        }

        if (type.is(EntityTypes1_21_11.BEE)) {
            final Boolean hasNectar = booleanValue(properties.namedProperty("minecraft:has_nectar"));
            if (hasNectar != null) {
                translated |= putProperty(entity, javaEntityData, EntityDataFields.FLAGS,
                        VersionedTypes.V26_1.entityDataTypes().byteType, beeFlags(entity, hasNectar));
            }
        }

        if (type.is(EntityTypes1_21_11.ZOMBIE_NAUTILUS)) {
            translated |= putProperty(entity, javaEntityData, EntityDataFields.VARIANT,
                    VersionedTypes.V26_1.entityDataTypes().zombieNautilusVariantType,
                    javaRegistryIndex("minecraft:zombie_nautilus_variant",
                            zombieNautilusVariantName(properties.namedProperty("minecraft:variant"))));
        }
        return translated;
    }

    private static boolean putProperty(final Entity entity, final List<EntityData> javaEntityData,
                                       final String field, final EntityDataType dataType, final Object value) {
        if (value == null) {
            return false;
        }
        final List<String> fields = BedrockProtocol.MAPPINGS.getJavaEntityDataFields().get(entity.javaType());
        if (fields == null) {
            return false;
        }
        final int index = fields.indexOf(field);
        if (index < 0) {
            return false;
        }
        upsert(javaEntityData, new EntityData(index, dataType, value));
        return true;
    }

    static String climateVariantName(final EntityPropertyValue property) {
        if (property == null || property.enumValue() == null) {
            return null;
        }
        return switch (property.enumValue()) {
            case "temperate", "warm", "cold" -> namespaced(property.enumValue());
            default -> null;
        };
    }

    static String catSoundVariantName(final EntityPropertyValue property) {
        if (property == null || property.enumValue() == null) {
            return null;
        }
        return switch (property.enumValue()) {
            case "default" -> "minecraft:classic";
            case "royal" -> "minecraft:royal";
            default -> null;
        };
    }

    static String wolfSoundVariantName(final EntityPropertyValue property) {
        if (property == null || property.enumValue() == null) {
            return null;
        }
        return switch (property.enumValue()) {
            case "default" -> "minecraft:classic";
            case "mad" -> "minecraft:angry";
            case "big", "cute", "grumpy", "puglin", "sad" -> namespaced(property.enumValue());
            default -> null;
        };
    }

    static String zombieNautilusVariantName(final EntityPropertyValue property) {
        if (property == null || property.enumValue() == null) {
            return null;
        }
        return switch (property.enumValue()) {
            case "default" -> "minecraft:temperate";
            case "coral" -> "minecraft:warm";
            default -> null;
        };
    }

    static Integer javaArmadilloState(final EntityPropertyValue property) {
        if (property == null || property.enumValue() == null) {
            return null;
        }
        // Java ArmadilloState has IDLE, ROLLING, SCARED, UNROLLING. Bedrock's
        // peeking and relaxing states both use Java's SCARED state.
        return switch (property.enumValue()) {
            case "unrolled" -> 0;
            case "rolled_up" -> 1;
            case "rolled_up_peeking", "rolled_up_relaxing" -> 2;
            case "rolled_up_unrolling" -> 3;
            default -> null;
        };
    }

    static Integer javaWeatheringCopperState(final EntityPropertyValue property) {
        if (property == null || property.enumValue() == null) {
            return null;
        }
        return switch (property.enumValue()) {
            case "unoxidized" -> 0; // Java UNAFFECTED
            case "exposed" -> 1;
            case "weathered" -> 2;
            case "oxidized" -> 3;
            default -> null;
        };
    }

    static Integer javaCopperGolemState(final EntityPropertyValue property) {
        if (property == null || property.enumValue() == null) {
            return null;
        }
        return switch (property.enumValue()) {
            case "none" -> 0; // Java IDLE
            case "take" -> 1; // Java GETTING_ITEM
            case "take_fail" -> 2; // Java GETTING_NO_ITEM
            case "put" -> 3; // Java DROPPING_ITEM
            case "put_fail" -> 4; // Java DROPPING_NO_ITEM
            default -> null;
        };
    }

    static Integer javaRegistryIndex(final String registryKey, final String value) {
        return javaRegistryIndex(BedrockProtocol.MAPPINGS.getJavaRegistries(), registryKey, value);
    }

    static Integer javaRegistryIndex(final CompoundTag registries, final String registryKey, final String value) {
        if (value == null) {
            return null;
        }
        if (registries != null) {
            final CompoundTag registry = registries.getCompoundTag(registryKey);
            if (registry != null) {
                final String namespacedValue = namespaced(value);
                int index = 0;
                for (final String identifier : registry.keySet()) {
                    if (identifier.equals(namespacedValue)) {
                        return index;
                    }
                    index++;
                }
            }
        }
        return fallbackRegistryIndex(registryKey, value);
    }

    private static Integer fallbackRegistryIndex(final String registryKey, final String value) {
        final String[] fallback = switch (registryKey) {
            case "minecraft:villager_profession" -> new String[]{
                    "none", "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman",
                    "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith"
            };
            case "minecraft:villager_type" -> new String[]{
                    "desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga"
            };
            default -> null;
        };
        if (fallback == null) {
            return null;
        }
        final String namespacedValue = namespaced(value);
        for (int i = 0; i < fallback.length; i++) {
            if (namespaced(fallback[i]).equals(namespacedValue)) {
                return i;
            }
        }
        return null;
    }

    private static String namespaced(final String value) {
        return value.indexOf(':') >= 0 ? value : "minecraft:" + value;
    }

    private static Boolean booleanValue(final EntityPropertyValue property) {
        return property != null ? property.booleanValue() : null;
    }

    private static byte beeFlags(final Entity entity, final Boolean hasNectar) {
        return beeFlags(entity.hasEntityFlag(ActorFlags.ANGRY), hasNectar);
    }

    static byte beeFlags(final boolean angry, final Boolean hasNectar) {
        byte flags = 0;
        if (angry) {
            flags |= 0x02;
        }
        if (Boolean.TRUE.equals(hasNectar)) {
            flags |= 0x08;
        }
        return flags;
    }

    static CreakingStateFlags creakingState(final String state) {
        if (state == null) {
            return null;
        }
        return switch (state) {
            case "neutral" -> new CreakingStateFlags(false, false);
            case "hostile_observed", "hostile_unobserved", "twitching" -> new CreakingStateFlags(true, false);
            case "crumbling" -> new CreakingStateFlags(true, true);
            default -> null;
        };
    }

    record CreakingStateFlags(boolean active, boolean tearingDown) {
    }

    /**
     * MOT {@code DATA_PLAYER_FLAG_SLEEP} is bit 1 of {@code PLAYER_FLAGS} (byte). Java Pose ids
     * match 1.14+: STANDING=0 SLEEPING=2 SWIMMING=3 FALL_FLYING=4 CROUCHING=5.
     */
    static final int JAVA_POSE_STANDING = 0;
    static final int JAVA_POSE_SLEEPING = 2;
    static final int JAVA_POSE_SWIMMING = 3;
    static final int JAVA_POSE_FALL_FLYING = 4;
    static final int JAVA_POSE_CROUCHING = 5;
    static final int PLAYER_FLAG_SLEEP_BIT = 1;

    static boolean playerSleeping(final EntityData playerFlags) {
        if (playerFlags == null) {
            return false;
        }
        return (readNumber(playerFlags).intValue() & (1 << PLAYER_FLAG_SLEEP_BIT)) != 0;
    }

    static BlockPosition playerBedPosition(final EntityData bedPosition) {
        if (bedPosition == null || bedPosition.getValue() == null) {
            return null;
        }
        if (bedPosition.getValue() instanceof BlockPosition position) {
            if (position.x() == 0 && position.y() == 0 && position.z() == 0) {
                return null;
            }
            return position;
        }
        return null;
    }

    static int javaPlayerPose(final boolean sleeping, final Set<ActorFlags> bedrockFlags) {
        if (sleeping) {
            return JAVA_POSE_SLEEPING;
        }
        if (bedrockFlags.contains(ActorFlags.GLIDING)) {
            return JAVA_POSE_FALL_FLYING;
        }
        if (bedrockFlags.contains(ActorFlags.SWIMMING)) {
            return JAVA_POSE_SWIMMING;
        }
        if (bedrockFlags.contains(ActorFlags.SNEAKING)) {
            return JAVA_POSE_CROUCHING;
        }
        return JAVA_POSE_STANDING;
    }

    private static void applyPlayerSleepPose(final Entity entity, final List<EntityData> javaEntityData, final Set<ActorFlags> bedrockFlags) {
        final boolean sleeping = playerSleeping(entity.entityData().get(ActorDataIDs.PLAYER_FLAGS));
        EntityData bedData = entity.entityData().get(ActorDataIDs.BED_POSITION);
        if (bedData == null) {
            bedData = entity.entityData().get(ActorDataIDs.ENTER_BED_POSITION);
        }
        final BlockPosition bedPosition = sleeping ? playerBedPosition(bedData) : null;
        upsert(javaEntityData, new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.POSE),
                VersionedTypes.V26_1.entityDataTypes().poseType,
                javaPlayerPose(sleeping, bedrockFlags)));
        upsert(javaEntityData, new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.SLEEPING_POS),
                VersionedTypes.V26_1.entityDataTypes().optionalBlockPositionType,
                bedPosition));
    }

    static boolean noGravity(final EntityTypes1_21_11 type, final Set<ActorFlags> bedrockFlags) {
        // Bedrock servers can omit HAS_GRAVITY for dropped items while still simulating item gravity.
        return type.is(EntityTypes1_21_11.ITEM)
                ? bedrockFlags.contains(ActorFlags.NOAI)
                : !bedrockFlags.contains(ActorFlags.HAS_GRAVITY);
    }

    static byte livingFlags(final Set<ActorFlags> bedrockFlags) {
        byte flags = 0;
        if (bedrockFlags.contains(ActorFlags.USINGITEM) || bedrockFlags.contains(ActorFlags.BLOCKING)) {
            flags |= 0x01;
        }
        if (bedrockFlags.contains(ActorFlags.DAMAGENEARBYMOBS)) {
            flags |= 0x04;
        }
        return flags;
    }

    public static byte localPlayerLivingFlags(final Set<ActorFlags> bedrockFlags, final ClientPlayerEntity clientPlayer) {
        return localPlayerLivingFlags(bedrockFlags, clientPlayer, null);
    }

    public static byte localPlayerLivingFlags(final Set<ActorFlags> bedrockFlags, final ClientPlayerEntity clientPlayer, final ItemRewriter itemRewriter) {
        final boolean visible = ItemUseSemantics.javaUsingVisible(
                clientPlayer.isUsingItem(),
                isConsumableUseItem(clientPlayer, itemRewriter),
                clientPlayer.usingItemTicks(),
                consumableUseTicks(clientPlayer, itemRewriter)
        );
        return localPlayerLivingFlags(bedrockFlags, visible, visible ? clientPlayer.usingItemHand() : null);
    }

    private static boolean isConsumableUseItem(final ClientPlayerEntity clientPlayer, final ItemRewriter itemRewriter) {
        final ClientPlayerEntity.ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        if (snapshot == null || itemRewriter == null) {
            return false;
        }
        final String identifier = itemRewriter.bedrockIdentifier(snapshot.item());
        final Set<String> itemTags = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier) : null;
        return ItemUseSemantics.isConsumableUseItem(identifier, itemTags, itemRewriter.itemUseDefinition(snapshot.item()));
    }

    private static int consumableUseTicks(final ClientPlayerEntity clientPlayer, final ItemRewriter itemRewriter) {
        final ClientPlayerEntity.ItemUseSnapshot snapshot = clientPlayer.itemUseSnapshot();
        if (snapshot == null || itemRewriter == null) {
            return -1;
        }
        final String identifier = itemRewriter.bedrockIdentifier(snapshot.item());
        final Set<String> itemTags = identifier != null ? BedrockProtocol.MAPPINGS.getBedrockItemTags().get(identifier) : null;
        return ItemUseSemantics.consumableUseTicks(identifier, itemTags, itemRewriter.itemUseDefinition(snapshot.item()));
    }

    public static byte localPlayerLivingFlags(final Set<ActorFlags> bedrockFlags, final boolean usingItem, final InteractionHand hand) {
        byte flags = (byte) (bedrockFlags.contains(ActorFlags.DAMAGENEARBYMOBS) ? 0x04 : 0);
        if (usingItem && hand != null) {
            flags |= 0x01;
            if (hand == InteractionHand.OFF_HAND) {
                flags |= 0x02;
            }
        }
        return flags;
    }

    static byte mobFlags(final EntityTypes1_21_11 type, final Set<ActorFlags> bedrockFlags) {
        byte flags = 0;
        if (bedrockFlags.contains(ActorFlags.NOAI)) {
            flags |= 0x01;
        }
        final boolean aggressive = type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_SKELETON)
                ? bedrockFlags.contains(ActorFlags.FACING_TARGET_TO_RANGE_ATTACK)
                : type.is(EntityTypes1_21_11.VINDICATOR) && bedrockFlags.contains(ActorFlags.ANGRY);
        if (aggressive) {
            flags |= 0x04;
        }
        return flags;
    }

    static byte tamableFlags(final Set<ActorFlags> bedrockFlags) {
        byte flags = 0;
        if (bedrockFlags.contains(ActorFlags.SITTING)) {
            flags |= 0x01;
        }
        if (bedrockFlags.contains(ActorFlags.ANGRY)) {
            flags |= 0x02;
        }
        if (bedrockFlags.contains(ActorFlags.TAMED)) {
            flags |= 0x04;
        }
        return flags;
    }

    static byte foxFlags(final Set<ActorFlags> bedrockFlags) {
        byte flags = 0;
        if (bedrockFlags.contains(ActorFlags.SITTING)) {
            flags |= 0x01;
        }
        if (bedrockFlags.contains(ActorFlags.SNEAKING)) {
            flags |= 0x04;
        }
        if (bedrockFlags.contains(ActorFlags.INTERESTED)) {
            flags |= 0x08;
        }
        if (bedrockFlags.contains(ActorFlags.SLEEPING)) {
            flags |= 0x20;
        }
        return flags;
    }

    static byte pandaFlags(final Set<ActorFlags> bedrockFlags) {
        byte flags = 0;
        if (bedrockFlags.contains(ActorFlags.SNEEZING)) {
            flags |= 0x02;
        }
        if (bedrockFlags.contains(ActorFlags.ROLLING)) {
            flags |= 0x04;
        }
        if (bedrockFlags.contains(ActorFlags.SITTING)) {
            flags |= 0x08;
        }
        if (bedrockFlags.contains(ActorFlags.LAYING_DOWN)) {
            flags |= 0x10;
        }
        return flags;
    }

    static boolean areaEffectCloudWaiting(final Number waitTime) {
        return waitTime != null && waitTime.intValue() > 0;
    }

    static AreaEffectCloudParticle areaEffectCloudParticle(final int particleId, final int effectColor) {
        return switch (particleId) {
            case 34 -> new AreaEffectCloudParticle("minecraft:entity_effect", 0xFF000000 | (effectColor & 0xFFFFFF), null);
            case 35 -> new AreaEffectCloudParticle("minecraft:entity_effect", 0x20000000 | (effectColor & 0xFFFFFF), null);
            case 36 -> new AreaEffectCloudParticle("minecraft:instant_effect", 0xFF000000 | (effectColor & 0xFFFFFF), 1F);
            default -> null;
        };
    }

    private static void writeAreaEffectCloudParticle(final Entity entity, final List<EntityData> javaEntityData) {
        final EntityData particleData = entity.entityData().get(ActorDataIDs.DATA_PARTICLE);
        final EntityData colorData = entity.entityData().get(ActorDataIDs.EFFECT_COLOR);
        if (particleData == null || colorData == null) {
            return;
        }

        final AreaEffectCloudParticle mapping = areaEffectCloudParticle(
                readNumber(particleData).intValue(), readNumber(colorData).intValue());
        if (mapping == null) {
            return;
        }
        final Integer javaParticleId = BedrockProtocol.MAPPINGS.getJavaParticles().get(mapping.identifier());
        if (javaParticleId == null) {
            return;
        }

        final Particle particle = new Particle(javaParticleId);
        particle.add(Types.INT, mapping.color());
        if (mapping.power() != null) {
            particle.add(Types.FLOAT, mapping.power());
        }
        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.PARTICLE),
                VersionedTypes.V26_1.entityDataTypes().particleType, particle));
    }

    record AreaEffectCloudParticle(String identifier, int color, Float power) {
    }

    static byte sheepFlags(final int color, final boolean sheared) {
        return (byte) ((color & 0x0F) | (sheared ? 0x10 : 0));
    }

    static byte spellType(final boolean casting, final Integer color) {
        if (!casting) {
            return 0;
        }
        if (color == null) {
            return 2; // Bedrock can omit the color; fangs is the safest visible fallback for an evoker.
        }
        return switch (color & 0xFFFFFF) {
            case SUMMON_VEX_SPELL_COLOR -> 1;
            case FANGS_SPELL_COLOR -> 2;
            case WOLOLO_SPELL_COLOR -> 3;
            default -> 2;
        };
    }

    private static byte sheepFlags(final Entity entity) {
        final EntityData colorData = entity.entityData().get(ActorDataIDs.COLOR_INDEX);
        final int color = colorData != null ? readNumber(colorData).intValue() : 0;
        return sheepFlags(color, entity.hasEntityFlag(ActorFlags.SHEARED));
    }

    /**
     * Multiline always-show names are owned by {@code MultilineNametagTracker}'s TEXT_DISPLAY,
     * so the host last line is cleared instead of being left for ArmorStandRenderer.
     */
    static boolean shouldHideHostNametag(final String effectiveName, final boolean alwaysShow) {
        return alwaysShow && effectiveName != null && effectiveName.indexOf('\n') >= 0;
    }

    private static void writeHostNametag(final Entity entity, final List<EntityData> javaEntityData) {
        final String effective = effectiveNametag(entity);
        final boolean alwaysShow = nametagAlwaysShown(entity);
        if (shouldHideHostNametag(effective, alwaysShow)) {
            upsert(javaEntityData, new EntityData(
                    entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME),
                    VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                    null));
            upsert(javaEntityData, new EntityData(
                    entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                    VersionedTypes.V26_1.entityDataTypes().booleanType,
                    false));
            return;
        }

        final String name = TextUtil.lastLine(effective);
        final boolean hasVisibleName = name != null && !TextUtil.stripFormatting(name).isEmpty();
        upsert(javaEntityData, new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME),
                VersionedTypes.V26_1.entityDataTypes().optionalComponentType,
                hasVisibleName ? TextUtil.stringToNbt(name) : null));
        upsert(javaEntityData, new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.CUSTOM_NAME_VISIBLE),
                VersionedTypes.V26_1.entityDataTypes().booleanType,
                alwaysShow && hasVisibleName));
    }

    private static String effectiveNametag(final Entity entity) {
        final EntityData nameData = entity.entityData().get(ActorDataIDs.NAME);
        final EntityData nameRawData = entity.entityData().get(ActorDataIDs.NAME_RAW_TEXT);
        final String name = nameData != null ? (String) nameData.getValue() : null;
        final String nameRaw = nameRawData != null ? (String) nameRawData.getValue() : null;
        return TextUtil.trimBlankLines(TextUtil.nametagValue(name, nameRaw));
    }

    private static boolean nametagAlwaysShown(final Entity entity) {
        final EntityData alwaysShowData = entity.entityData().get(ActorDataIDs.NAMETAG_ALWAYS_SHOW);
        if (alwaysShowData != null) {
            return TextUtil.nametagAlwaysShown((Number) alwaysShowData.getValue());
        }
        return true;
    }

    private static void applySpellCasting(final Entity entity, final List<EntityData> javaEntityData) {
        final EntityData colorData = entity.entityData().get(ActorDataIDs.DATA_SPELL_CASTING_COLOR);
        final Integer color = colorData != null ? readNumber(colorData).intValue() : null;
        final byte spell = spellType(entity.hasEntityFlag(ActorFlags.CASTING), color);
        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.SPELL_CASTING), VersionedTypes.V26_1.entityDataTypes().byteType, spell));
    }

    private static void upsert(final List<EntityData> javaEntityData, final EntityData data) {
        javaEntityData.removeIf(existing -> existing.id() == data.id());
        javaEntityData.add(data);
    }

    /**
     * Java fishing bobber {@code HOOKED_ENTITY} is the hooked entity's Java id plus one
     * (vanilla / Geyser convention). {@code 0} means not hooked. MOT writes
     * {@code DATA_TARGET_EID}; {@code null} means the target unique id is not in the tracker yet.
     */
    static Integer fishingBobberHookedEntity(final long targetUniqueId, final LongFunction<Integer> javaIdLookup) {
        if (targetUniqueId == 0L || targetUniqueId == -1L) {
            return 0;
        }
        final Integer javaId = javaIdLookup.apply(targetUniqueId);
        return javaId == null ? null : javaId + 1;
    }

    // Bedrock villager VARIANT (profession) -> Java profession registry id. Inverse of Geyser's
    // VillagerEntity#VILLAGER_PROFESSIONS (Java->Bedrock). Index = Bedrock value, value = Java id.
    private static final int[] BEDROCK_TO_JAVA_PROFESSION = {0, 5, 6, 12, 7, 9, 3, 4, 1, 14, 13, 2, 8, 10, 11};
    // Bedrock villager MARK_VARIANT (region/biome) -> Java villager type registry id. Inverse of Geyser's
    // VillagerEntity#VILLAGER_REGIONS.
    private static final int[] BEDROCK_TO_JAVA_REGION = {2, 0, 1, 3, 4, 5, 6};

    /**
     * Combines the Bedrock villager fields VARIANT (profession), MARK_VARIANT (region/biome) and TRADE_TIER
     * (trade level) into a single Java VILLAGER_DATA entry. Reads sibling fields from the entity's stored
     * data so a change to any one of them rebuilds the complete value (Entity#updateEntityData stores the
     * whole batch before translating, so the latest values are always visible here).
     */
    private static void applyVillagerData(final Entity entity, final List<EntityData> javaEntityData) {
        final EntityData variantData = entity.entityData().get(ActorDataIDs.VARIANT);
        final EntityData markVariantData = entity.entityData().get(ActorDataIDs.MARK_VARIANT);
        final EntityData tradeTierData = entity.entityData().get(ActorDataIDs.TRADE_TIER);

        final int bedrockProfession = variantData != null ? readNumber(variantData).intValue() : 0;
        final int bedrockRegion = markVariantData != null ? readNumber(markVariantData).intValue() : 0;
        final int bedrockTradeTier = tradeTierData != null ? readNumber(tradeTierData).intValue() : 0;

        final Integer profession = javaVillagerProfession(bedrockProfession);
        final Integer type = javaVillagerType(bedrockRegion);
        if (profession == null || type == null) {
            return;
        }
        final int level = Math.max(1, Math.min(5, bedrockTradeTier + 1)); // Java trade levels are 1-based, Bedrock 0-based

        final int index = entity.getJavaEntityDataIndex(EntityDataFields.VILLAGER_DATA);
        // Idempotent: if several villager fields arrive in one batch, keep a single VILLAGER_DATA entry.
        javaEntityData.removeIf(d -> d.id() == index);
        javaEntityData.add(new EntityData(index, VersionedTypes.V26_1.entityDataTypes().villagerDataType, new VillagerData(type, profession, level)));
    }

    private static byte sharedFlags(final UserConnection user, final Entity entity, final boolean forceInvisible) {
        byte sharedFlags = 0;
        if (entity.hasEntityFlag(ActorFlags.ONFIRE)) sharedFlags |= (1 << 0);
        if (entity.hasEntityFlag(ActorFlags.SNEAKING)) sharedFlags |= (1 << 1);
        if (entity.hasEntityFlag(ActorFlags.RIDING)) sharedFlags |= (1 << 2);
        if (entity.hasEntityFlag(ActorFlags.SPRINTING)) sharedFlags |= (1 << 3);
        if (entity.hasEntityFlag(ActorFlags.SWIMMING)) sharedFlags |= (1 << 4);
        if (forceInvisible || entity.hasEntityFlag(ActorFlags.INVISIBLE)) sharedFlags |= (1 << 5);
        final GlowProjectionTracker glow = user.get(GlowProjectionTracker.class);
        if (glow != null && glow.isGlowing(entity.uniqueId())) sharedFlags |= (1 << 6);
        if (entity.hasEntityFlag(ActorFlags.GLIDING)) sharedFlags |= (byte) (1 << 7);
        return sharedFlags;
    }

    static int snifferState(final Set<ActorFlags> flags) {
        // MOT 860 still uses DATA_FLAG_SCENTING/RISING/FEELING_HAPPY = 110/111/112.
        // Generated ActorFlags maps those values to DEPRECATED_1/2/3, so look them up by wire value.
        if (hasFlagValue(flags, 111) || flags.contains(ActorFlags.EMERGING)) {
            return 6; // RISING
        }
        if (flags.contains(ActorFlags.DIGGING)) {
            return 5;
        }
        if (flags.contains(ActorFlags.SEARCHING)) {
            return 4;
        }
        if (flags.contains(ActorFlags.SNIFFING)) {
            return 3;
        }
        if (hasFlagValue(flags, 110)) {
            return 2; // SCENTING
        }
        if (hasFlagValue(flags, 112)) {
            return 1; // FEELING_HAPPY
        }
        return 0; // IDLING / default
    }

    private static boolean hasFlagValue(final Set<ActorFlags> flags, final int value) {
        final ActorFlags flag = ActorFlags.getByValue(value);
        return flag != null && flags.contains(flag);
    }

    static void applyTropicalFishVariant(final Entity entity, final List<EntityData> javaEntityData) {
        final int packed = packedTropicalFishVariant(entity);
        upsert(javaEntityData, new EntityData(entity.getJavaEntityDataIndex(EntityDataFields.TYPE_VARIANT), VersionedTypes.V26_1.entityDataTypes().varIntType, packed));
    }

    static int packedTropicalFishVariant(final Entity entity) {
        return packedTropicalFishVariant(
                actorInt(entity, ActorDataIDs.VARIANT),
                actorInt(entity, ActorDataIDs.MARK_VARIANT),
                actorInt(entity, ActorDataIDs.COLOR_INDEX),
                actorInt(entity, ActorDataIDs.COLOR_2_INDEX));
    }

    static int packedTropicalFishVariant(final int shape, final int pattern, final int baseColor, final int patternColor) {
        return (shape & 0xFF)
                | ((pattern & 0xFF) << 8)
                | ((baseColor & 0xFF) << 16)
                | ((patternColor & 0xFF) << 24);
    }

    private static int actorInt(final Entity entity, final ActorDataIDs id) {
        final EntityData data = entity.entityData().get(id);
        return data != null ? readNumber(data).intValue() : 0;
    }

    static String wolfVariantName(final int bedrockId) {
        return switch (bedrockId) {
            case 0 -> "pale";
            case 1 -> "ashen";
            case 2 -> "black";
            case 3 -> "chestnut";
            case 4 -> "rusty";
            case 5 -> "snowy";
            case 6 -> "spotted";
            case 7 -> "striped";
            case 8 -> "woods";
            default -> null;
        };
    }

    static String catVariantName(final int bedrockId) {
        return switch (bedrockId) {
            case 0 -> "white";
            case 1 -> "black"; // Bedrock tuxedo
            case 2 -> "red";
            case 3 -> "siamese";
            case 4 -> "british_shorthair";
            case 5 -> "calico";
            case 6 -> "persian";
            case 7 -> "ragdoll";
            case 8 -> "tabby";
            case 9 -> "all_black";
            case 10 -> "jellie";
            default -> null;
        };
    }

    static String frogVariantName(final int bedrockId) {
        return switch (bedrockId) {
            case 0 -> "temperate";
            case 1 -> "cold";
            case 2 -> "warm";
            default -> null;
        };
    }

    static Integer javaVillagerProfession(final int bedrockProfession) {
        final String name = switch (bedrockProfession) {
            case 0 -> "none";
            case 1 -> "farmer";
            case 2 -> "fisherman";
            case 3 -> "shepherd";
            case 4 -> "fletcher";
            case 5 -> "librarian";
            case 6 -> "cartographer";
            case 7 -> "cleric";
            case 8 -> "armorer";
            case 9 -> "weaponsmith";
            case 10 -> "toolsmith";
            case 11 -> "butcher";
            case 12 -> "leatherworker";
            case 13 -> "mason";
            case 14 -> "nitwit";
            default -> null;
        };
        return javaRegistryIndex("minecraft:villager_profession", name);
    }

    static Integer javaVillagerType(final int bedrockRegion) {
        final String name = switch (bedrockRegion) {
            case 0 -> "desert";
            case 1 -> "jungle";
            case 2 -> "plains";
            case 3 -> "savanna";
            case 4 -> "snow";
            case 5 -> "swamp";
            case 6 -> "taiga";
            default -> null;
        };
        return javaRegistryIndex("minecraft:villager_type", name);
    }

    static boolean usesJavaSaddleEquipment(final EntityTypes1_21_11 type) {
        if (type.isOrHasParent(EntityTypes1_21_11.LLAMA) || type.isOrHasParent(EntityTypes1_21_11.CAMEL)) {
            return false;
        }
        return type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_HORSE)
                || type.is(EntityTypes1_21_11.PIG)
                || type.is(EntityTypes1_21_11.STRIDER);
    }

    static boolean shouldSendJavaSaddleEquipment(final Boolean lastSent, final boolean saddled) {
        if (lastSent != null && lastSent == saddled) {
            return false;
        }
        return saddled || lastSent != null;
    }

    static Item javaSaddleEquipmentItem(final Integer saddleItemId, final boolean saddled) {
        if (!saddled || saddleItemId == null) {
            return StructuredItem.empty();
        }
        return new StructuredItem(saddleItemId, 1, ProtocolConstants.createStructuredDataContainer());
    }

    static void sendSaddleEquipment(final Entity entity, final boolean saddled) {
        if (!shouldSendJavaSaddleEquipment(entity.javaSaddleEquipped(), saddled)) {
            return;
        }
        final UserConnection user = entity.user();
        if (user == null) {
            return;
        }
        final Integer saddleId = BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:saddle");
        if (saddled && saddleId == null) {
            return;
        }
        final PacketWrapper equipment = PacketWrapper.create(ClientboundPackets26_1.SET_EQUIPMENT, user);
        equipment.write(Types.VAR_INT, entity.javaId());
        equipment.write(Types.BYTE, (byte) EquipmentSlot.SADDLE.ordinal());
        equipment.write(VersionedTypes.V26_1.item, javaSaddleEquipmentItem(saddleId, saddled));
        equipment.send(BedrockProtocol.class);
        entity.setJavaSaddleEquipped(saddled);
    }

    static void sendCopperGolemFlowerEquipment(final Entity entity, final Boolean hasFlower) {
        if (hasFlower == null) {
            return;
        }
        final UserConnection user = entity.user();
        if (user == null) {
            return;
        }
        final Integer poppyId = BedrockProtocol.MAPPINGS.getJavaItems().get("minecraft:poppy");
        final Item flower = hasFlower && poppyId != null
                ? new StructuredItem(poppyId, 1, ProtocolConstants.createStructuredDataContainer())
                : StructuredItem.empty();
        final PacketWrapper equipment = PacketWrapper.create(ClientboundPackets26_1.SET_EQUIPMENT, user);
        equipment.write(Types.VAR_INT, entity.javaId());
        equipment.write(Types.BYTE, (byte) EquipmentSlot.SADDLE.ordinal());
        equipment.write(VersionedTypes.V26_1.item, flower);
        equipment.send(BedrockProtocol.class);
    }

    public static void sendSharedFlags(final UserConnection user, final Entity entity) {
        final List<EntityData> data = List.of(new EntityData(
                entity.getJavaEntityDataIndex(EntityDataFields.SHARED_FLAGS),
                VersionedTypes.V26_1.entityDataTypes().byteType,
                sharedFlags(user, entity, isForceInvisible(entity))
        ));
        final PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, user);
        packet.write(Types.VAR_INT, entity.javaId());
        packet.write(VersionedTypes.V26_1.entityDataList, data);
        packet.send(BedrockProtocol.class);
    }

    private static boolean isForceInvisible(final Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        final EntityData scaleData = entity.entityData().get(ActorDataIDs.RESERVED_038);
        return scaleData != null && readNumber(scaleData).floatValue() == 0F;
    }

    private static Number readNumber(EntityData data) {
        if (data.dataType() == null || data.getValue() == null) {
            throw new IllegalArgumentException("EntityData " + data.id() + " has null data type or value");
        }
        return switch ((EntityDataTypesBedrock) data.dataType()) {
            case BYTE -> (byte) data.getValue();
            case SHORT -> (short) data.getValue();
            case INT -> (int) data.getValue();
            case FLOAT -> (float) data.getValue();
            case LONG -> (long) data.getValue();
            default -> throw new IllegalArgumentException("Unsupported number type: " + data.dataType());
        };
    }
}
