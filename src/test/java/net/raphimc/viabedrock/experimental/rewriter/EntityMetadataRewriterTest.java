/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityMetadataRewriterTest {

    @Test
    void convertsMotAreaEffectCloudWaitTimeToJavaWaitingFlag() {
        assertFalse(EntityMetadataRewriter.areaEffectCloudWaiting(0));
        assertTrue(EntityMetadataRewriter.areaEffectCloudWaiting(1));
        assertTrue(EntityMetadataRewriter.areaEffectCloudWaiting(20));
    }

    @Test
    void convertsMotAreaEffectCloudParticlesAndColorToJavaPayloads() {
        assertEquals(new EntityMetadataRewriter.AreaEffectCloudParticle(
                        "minecraft:entity_effect", 0xFF336699, null),
                EntityMetadataRewriter.areaEffectCloudParticle(34, 0x336699));
        assertEquals(new EntityMetadataRewriter.AreaEffectCloudParticle(
                        "minecraft:entity_effect", 0x20336699, null),
                EntityMetadataRewriter.areaEffectCloudParticle(35, 0xFF336699));
        assertEquals(new EntityMetadataRewriter.AreaEffectCloudParticle(
                        "minecraft:instant_effect", 0xFF336699, 1F),
                EntityMetadataRewriter.areaEffectCloudParticle(36, 0x336699));
        assertEquals(null, EntityMetadataRewriter.areaEffectCloudParticle(99, 0x336699));
    }

    @Test
    void mapsSkeletonRangedAttackAndVindicatorAngerToAggressiveMobFlag() {
        assertEquals(0x04, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.SKELETON, EnumSet.of(ActorFlags.FACING_TARGET_TO_RANGE_ATTACK)));
        assertEquals(0x04, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.STRAY, EnumSet.of(ActorFlags.FACING_TARGET_TO_RANGE_ATTACK)));
        assertEquals(0x04, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.VINDICATOR, EnumSet.of(ActorFlags.ANGRY)));
        assertEquals(0, EntityMetadataRewriter.mobFlags(EntityTypes1_21_11.SKELETON, EnumSet.noneOf(ActorFlags.class)));
        assertEquals(0, EntityMetadataRewriter.mobFlags(EntityTypes1_21_11.VINDICATOR, EnumSet.noneOf(ActorFlags.class)));
    }

    @Test
    void preservesNoAiAlongsideAggressiveMobFlag() {
        assertEquals(0x05, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.BOGGED, EnumSet.of(ActorFlags.NOAI, ActorFlags.FACING_TARGET_TO_RANGE_ATTACK)));
        assertEquals(0x01, EntityMetadataRewriter.mobFlags(
                EntityTypes1_21_11.ZOMBIE, EnumSet.of(ActorFlags.NOAI, ActorFlags.ANGRY)));
    }

    @Test
    void resolvesBedrockSpellColorsWithOrWithoutAlpha() {
        assertEquals(1, EntityMetadataRewriter.spellType(true, 0xB3B3CC));
        assertEquals(2, EntityMetadataRewriter.spellType(true, 0xFF664D59));
        assertEquals(3, EntityMetadataRewriter.spellType(true, 0xB38033));
        assertEquals(2, EntityMetadataRewriter.spellType(true, null));
        assertEquals(0, EntityMetadataRewriter.spellType(false, 0xFF664D59));
    }

    @Test
    void mapsLivingAndTamableFlags() {
        assertEquals(0x05, EntityMetadataRewriter.livingFlags(EnumSet.of(
                ActorFlags.USINGITEM, ActorFlags.EMERGING, ActorFlags.DAMAGENEARBYMOBS)));
        assertEquals(0x01, EntityMetadataRewriter.livingFlags(EnumSet.of(ActorFlags.BLOCKING)));
        assertEquals(0, EntityMetadataRewriter.livingFlags(EnumSet.of(ActorFlags.EMERGING)));
        assertEquals(0x07, EntityMetadataRewriter.tamableFlags(EnumSet.of(
                ActorFlags.SITTING, ActorFlags.ANGRY, ActorFlags.TAMED)));
    }

    @Test
    void mapsMotSaddleFlagToJavaSaddleEquipmentTypes() {
        assertTrue(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.HORSE));
        assertTrue(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.DONKEY));
        assertTrue(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.MULE));
        assertTrue(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.SKELETON_HORSE));
        assertTrue(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.ZOMBIE_HORSE));
        assertTrue(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.PIG));
        assertTrue(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.STRIDER));
        assertFalse(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.LLAMA));
        assertFalse(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.TRADER_LLAMA));
        assertFalse(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.CAMEL));
        assertFalse(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.HAPPY_GHAST));
        assertFalse(EntityMetadataRewriter.usesJavaSaddleEquipment(EntityTypes1_21_11.COW));
    }

    @Test
    void sendsJavaSaddleEquipmentOnlyWhenTheMotFlagChanges() {
        assertFalse(EntityMetadataRewriter.shouldSendJavaSaddleEquipment(null, false));
        assertTrue(EntityMetadataRewriter.shouldSendJavaSaddleEquipment(null, true));
        assertTrue(EntityMetadataRewriter.shouldSendJavaSaddleEquipment(false, true));
        assertFalse(EntityMetadataRewriter.shouldSendJavaSaddleEquipment(true, true));
        assertTrue(EntityMetadataRewriter.shouldSendJavaSaddleEquipment(true, false));
        assertFalse(EntityMetadataRewriter.shouldSendJavaSaddleEquipment(false, false));
    }

    @Test
    void buildsEmptyJavaSaddleItemWhenMotFlagIsOff() {
        assertTrue(EntityMetadataRewriter.javaSaddleEquipmentItem(800, false).isEmpty());
        assertTrue(EntityMetadataRewriter.javaSaddleEquipmentItem(null, true).isEmpty());
    }

    @Test
    void localPlayerIgnoresBedrockBlockingWithoutTrackedItemUse() {
        assertEquals(0, EntityMetadataRewriter.localPlayerLivingFlags(
                EnumSet.of(ActorFlags.BLOCKING), false, null));
        assertEquals(0x04, EntityMetadataRewriter.localPlayerLivingFlags(
                EnumSet.of(ActorFlags.BLOCKING, ActorFlags.DAMAGENEARBYMOBS), false, null));
    }

    @Test
    void localPlayerUsesTheTrackedJavaHand() {
        assertEquals(0x01, EntityMetadataRewriter.localPlayerLivingFlags(
                EnumSet.noneOf(ActorFlags.class), true, InteractionHand.MAIN_HAND));
        assertEquals(0x03, EntityMetadataRewriter.localPlayerLivingFlags(
                EnumSet.of(ActorFlags.BLOCKING), true, InteractionHand.OFF_HAND));
    }

    @Test
    void combinesCompositeAnimalFlags() {
        assertEquals(0x2D, EntityMetadataRewriter.foxFlags(EnumSet.of(
                ActorFlags.SITTING, ActorFlags.SNEAKING, ActorFlags.INTERESTED, ActorFlags.SLEEPING)));
        assertEquals(0x1E, EntityMetadataRewriter.pandaFlags(EnumSet.of(
                ActorFlags.SNEEZING, ActorFlags.ROLLING, ActorFlags.SITTING, ActorFlags.LAYING_DOWN)));
        assertEquals(0x1E, Byte.toUnsignedInt(EntityMetadataRewriter.sheepFlags(14, true)));
        assertEquals(0x03, Byte.toUnsignedInt(EntityMetadataRewriter.sheepFlags(3, false)));
    }

    @Test
    void keepsJavaGravityEnabledForDroppedItemsWithoutBedrockGravityFlag() {
        assertFalse(EntityMetadataRewriter.noGravity(
                EntityTypes1_21_11.ITEM, EnumSet.noneOf(ActorFlags.class)));
        assertFalse(EntityMetadataRewriter.noGravity(
                EntityTypes1_21_11.ITEM, EnumSet.of(ActorFlags.HAS_GRAVITY)));
    }

    @Test
    void disablesJavaGravityForImmobileDroppedItems() {
        assertTrue(EntityMetadataRewriter.noGravity(
                EntityTypes1_21_11.ITEM, EnumSet.of(ActorFlags.NOAI)));
        assertTrue(EntityMetadataRewriter.noGravity(
                EntityTypes1_21_11.ITEM, EnumSet.of(ActorFlags.NOAI, ActorFlags.HAS_GRAVITY)));
    }

    @Test
    void hidesHostNametagForMultilineAlwaysShowNames() {
        assertTrue(EntityMetadataRewriter.shouldHideHostNametag("Title\nSubtitle", true));
        assertTrue(EntityMetadataRewriter.shouldHideHostNametag("[开发中]ReBlock\n游玩总人数:0", true));
    }

    @Test
    void keepsHostNametagForSingleLineOrLookAtOnlyNames() {
        assertFalse(EntityMetadataRewriter.shouldHideHostNametag("Title", true));
        assertFalse(EntityMetadataRewriter.shouldHideHostNametag("Title\nSubtitle", false));
        assertFalse(EntityMetadataRewriter.shouldHideHostNametag(null, true));
        assertFalse(EntityMetadataRewriter.shouldHideHostNametag("", true));
    }

    @Test
    void mapsMotSnifferFlagsToJavaStateOrder() {
        assertEquals(Integer.valueOf(6), Integer.valueOf(EntityMetadataRewriter.snifferState(EnumSet.of(ActorFlags.getByValue(111)))));
        assertEquals(Integer.valueOf(5), Integer.valueOf(EntityMetadataRewriter.snifferState(EnumSet.of(ActorFlags.DIGGING))));
        assertEquals(Integer.valueOf(2), Integer.valueOf(EntityMetadataRewriter.snifferState(EnumSet.of(ActorFlags.getByValue(110)))));
        assertEquals(Integer.valueOf(1), Integer.valueOf(EntityMetadataRewriter.snifferState(EnumSet.of(ActorFlags.getByValue(112)))));
        assertEquals(Integer.valueOf(0), Integer.valueOf(EntityMetadataRewriter.snifferState(EnumSet.noneOf(ActorFlags.class))));
    }

    @Test
    void packsTropicalFishFromBedrockVariantFields() {
        assertEquals(Integer.valueOf(0x04030201), Integer.valueOf(EntityMetadataRewriter.packedTropicalFishVariant(1, 2, 3, 4)));
    }

    @Test
    void mapsBedrockCatWolfAndFrogIdsToJavaNames() {
        assertEquals("white", EntityMetadataRewriter.catVariantName(0));
        assertEquals("jellie", EntityMetadataRewriter.catVariantName(10));
        assertEquals("pale", EntityMetadataRewriter.wolfVariantName(0));
        assertEquals("woods", EntityMetadataRewriter.wolfVariantName(8));
        assertEquals("temperate", EntityMetadataRewriter.frogVariantName(0));
        assertEquals("warm", EntityMetadataRewriter.frogVariantName(2));
    }

    @Test
    void preservesBedrockGravitySemanticsForOtherEntities() {
        assertTrue(EntityMetadataRewriter.noGravity(
                EntityTypes1_21_11.ARMOR_STAND, EnumSet.noneOf(ActorFlags.class)));
        assertFalse(EntityMetadataRewriter.noGravity(
                EntityTypes1_21_11.ARMOR_STAND, EnumSet.of(ActorFlags.HAS_GRAVITY)));
    }

    @Test
    void mapsMotPlayerSleepFlagToJavaSleepingPose() {
        final EntityData flags = new EntityData(ActorDataIDs.PLAYER_FLAGS.getValue(), EntityDataTypesBedrock.BYTE, (byte) (1 << 1));
        assertTrue(EntityMetadataRewriter.playerSleeping(flags));
        assertEquals(EntityMetadataRewriter.JAVA_POSE_SLEEPING, EntityMetadataRewriter.javaPlayerPose(true, EnumSet.noneOf(ActorFlags.class)));
        assertEquals(EntityMetadataRewriter.JAVA_POSE_SLEEPING, EntityMetadataRewriter.javaPlayerPose(true, EnumSet.of(ActorFlags.SNEAKING)));
        assertEquals(EntityMetadataRewriter.JAVA_POSE_CROUCHING, EntityMetadataRewriter.javaPlayerPose(false, EnumSet.of(ActorFlags.SNEAKING)));
        assertEquals(EntityMetadataRewriter.JAVA_POSE_STANDING, EntityMetadataRewriter.javaPlayerPose(false, EnumSet.noneOf(ActorFlags.class)));
        assertEquals(EntityMetadataRewriter.JAVA_POSE_SWIMMING, EntityMetadataRewriter.javaPlayerPose(false, EnumSet.of(ActorFlags.SWIMMING)));
        assertEquals(EntityMetadataRewriter.JAVA_POSE_FALL_FLYING, EntityMetadataRewriter.javaPlayerPose(false, EnumSet.of(ActorFlags.GLIDING)));
        assertFalse(EntityMetadataRewriter.playerSleeping(new EntityData(ActorDataIDs.PLAYER_FLAGS.getValue(), EntityDataTypesBedrock.BYTE, (byte) 0)));
        assertFalse(EntityMetadataRewriter.playerSleeping(null));
    }

    @Test
    void mapsMotBedPositionToJavaSleepingPosAndIgnoresUnsetOrigin() {
        final BlockPosition bed = new BlockPosition(12, 64, -8);
        final EntityData bedData = new EntityData(ActorDataIDs.BED_POSITION.getValue(), EntityDataTypesBedrock.BLOCK_POSITION, bed);
        assertEquals(bed, EntityMetadataRewriter.playerBedPosition(bedData));
        assertNull(EntityMetadataRewriter.playerBedPosition(new EntityData(
                ActorDataIDs.BED_POSITION.getValue(), EntityDataTypesBedrock.BLOCK_POSITION, new BlockPosition(0, 0, 0))));
        assertNull(EntityMetadataRewriter.playerBedPosition(null));
    }

    @Test
    void mapsMotFishingHookTargetToJavaHookedEntityPlusOne() {
        assertEquals(0, EntityMetadataRewriter.fishingBobberHookedEntity(0L, uniqueId -> {
            throw new AssertionError("unset TARGET must not look up an entity");
        }));
        assertEquals(0, EntityMetadataRewriter.fishingBobberHookedEntity(-1L, uniqueId -> {
            throw new AssertionError("unset TARGET must not look up an entity");
        }));
        assertEquals(43, EntityMetadataRewriter.fishingBobberHookedEntity(100L, uniqueId -> uniqueId == 100L ? 42 : null));
        assertNull(EntityMetadataRewriter.fishingBobberHookedEntity(100L, uniqueId -> null));
    }
}
