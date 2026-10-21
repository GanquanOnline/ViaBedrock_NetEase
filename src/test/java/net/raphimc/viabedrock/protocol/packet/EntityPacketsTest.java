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

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.LivingEntity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorEvent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AnimatePacketPayload_Action;
import net.raphimc.viabedrock.protocol.data.enums.java.AnimateAction;
import net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongFunction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class EntityPacketsTest {

    private static final long OWNER_UNIQUE_ID = 1234L;

    @Test
    void translatesTakeItemEntityWithLivingCollector() {
        final Entity itemEntity = entity(EntityTypes1_21_11.ITEM);
        final LivingEntity collectorEntity = livingEntity(EntityTypes1_21_11.PLAYER);

        assertTrue(EntityPackets.canTranslateTakeItemEntity(itemEntity, collectorEntity));
    }

    @Test
    void rejectsTakeItemEntityWithInteractionCollector() {
        final Entity itemEntity = entity(EntityTypes1_21_11.ITEM);
        final Entity collectorEntity = entity(EntityTypes1_21_11.INTERACTION);

        assertFalse(EntityPackets.canTranslateTakeItemEntity(itemEntity, collectorEntity));
    }

    @Test
    void rejectsTakeItemEntityWithMissingOrInvalidEntities() {
        final Entity itemEntity = entity(EntityTypes1_21_11.ITEM);
        final LivingEntity collectorEntity = livingEntity(EntityTypes1_21_11.PLAYER);

        assertAll(
                () -> assertFalse(EntityPackets.canTranslateTakeItemEntity(null, collectorEntity)),
                () -> assertFalse(EntityPackets.canTranslateTakeItemEntity(itemEntity, null)),
                () -> assertFalse(EntityPackets.canTranslateTakeItemEntity(entity(EntityTypes1_21_11.EXPERIENCE_ORB), collectorEntity))
        );
    }

    @Test
    void resolvesRemoteFishingHookOwnerJavaId() {
        final Integer ownerJavaId = EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(OWNER_UNIQUE_ID)}, uniqueId -> {
            assertEquals(OWNER_UNIQUE_ID, uniqueId);
            return 42;
        });

        assertEquals(Integer.valueOf(42), ownerJavaId);
    }

    @Test
    void preservesLocalPlayerJavaIdZero() {
        final Integer ownerJavaId = EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(OWNER_UNIQUE_ID)}, uniqueId -> 0);

        assertEquals(Integer.valueOf(0), ownerJavaId);
    }

    @Test
    void findsOwnerRegardlessOfMetadataOrder() {
        final EntityData variantData = new EntityData(ActorDataIDs.VARIANT.getValue(), EntityDataTypesBedrock.INT, 7);
        final Integer ownerJavaId = EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{variantData, ownerData(OWNER_UNIQUE_ID)}, uniqueId -> 8);

        assertEquals(Integer.valueOf(8), ownerJavaId);
    }

    @Test
    void rejectsMissingOrInvalidFishingHookOwner() {
        final LongFunction<Integer> unexpectedLookup = uniqueId -> fail("Owner lookup should not run for invalid metadata");
        final EntityData wrongType = new EntityData(ActorDataIDs.OWNER.getValue(), EntityDataTypesBedrock.INT, (int) OWNER_UNIQUE_ID);

        assertAll(
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[0], unexpectedLookup)),
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(-1L)}, unexpectedLookup)),
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{wrongType}, unexpectedLookup)),
                () -> assertNull(EntityPackets.getFishingHookOwnerJavaId(new EntityData[]{ownerData(OWNER_UNIQUE_ID)}, uniqueId -> null))
        );
    }

    @Test
    void mapsFallingBlockRuntimeIdToJavaBlockState() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(7)}, bedrockRuntimeId -> {
            assertEquals(7, bedrockRuntimeId);
            return 42;
        });

        assertEquals(Integer.valueOf(42), javaBlockStateId);
    }

    @Test
    void mapsNegativeHashedFallingBlockRuntimeId() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(-7)}, bedrockRuntimeId -> {
            assertEquals(-7, bedrockRuntimeId);
            return 42;
        });

        assertEquals(Integer.valueOf(42), javaBlockStateId);
    }

    @Test
    void findsFallingBlockStateRegardlessOfMetadataOrder() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{ownerData(OWNER_UNIQUE_ID), variantData(7)}, bedrockRuntimeId -> 8);

        assertEquals(Integer.valueOf(8), javaBlockStateId);
    }

    @Test
    void preservesMappedJavaBlockStateZero() {
        final Integer javaBlockStateId = EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(7)}, bedrockRuntimeId -> 0);

        assertEquals(Integer.valueOf(0), javaBlockStateId);
    }

    @Test
    void rejectsMissingInvalidOrUnmappedFallingBlockState() {
        final EntityData wrongType = new EntityData(ActorDataIDs.VARIANT.getValue(), EntityDataTypesBedrock.LONG, 7L);

        assertAll(
                () -> assertNull(EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[0], bedrockRuntimeId -> fail("Block state lookup should not run without variant metadata"))),
                () -> assertNull(EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{wrongType}, bedrockRuntimeId -> fail("Block state lookup should not run for invalid metadata"))),
                () -> assertNull(EntityPackets.getFallingBlockJavaBlockStateId(new EntityData[]{variantData(7)}, bedrockRuntimeId -> -1))
        );
    }

    @Test
    void mapsContextSensitiveActorEvents() {
        assertAll(
                () -> assertEquals(EntityEvent.START_ATTACKING, EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.EVOKER_FANGS)),
                () -> assertEquals(EntityEvent.START_ATTACKING, EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.IRON_GOLEM)),
                () -> assertEquals(EntityEvent.START_RAM, EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.GOAT)),
                () -> assertEquals(EntityEvent.END_RAM, EntityPackets.javaEntityEvent(ActorEvent.STOP_ATTACKING, EntityTypes1_21_11.GOAT)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.START_ATTACKING, EntityTypes1_21_11.VINDICATOR)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.STOP_ATTACKING, EntityTypes1_21_11.VINDICATOR))
        );
    }

    @Test
    void mapsStableVanillaActorEvents() {
        assertAll(
                () -> assertEquals(EntityEvent.TAMING_SUCCEEDED, EntityPackets.javaEntityEvent(ActorEvent.TAMING_SUCCEEDED, EntityTypes1_21_11.WOLF)),
                () -> assertEquals(EntityEvent.JUMP, EntityPackets.javaEntityEvent(ActorEvent.JUMP, EntityTypes1_21_11.RABBIT)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.JUMP, EntityTypes1_21_11.ZOMBIE)),
                () -> assertEquals(EntityEvent.EAT_GRASS, EntityPackets.javaEntityEvent(ActorEvent.EAT_GRASS, EntityTypes1_21_11.SHEEP)),
                () -> assertEquals(EntityEvent.VILLAGER_ANGRY, EntityPackets.javaEntityEvent(ActorEvent.VILLAGER_ANGRY, EntityTypes1_21_11.VILLAGER)),
                () -> assertEquals(EntityEvent.CANCEL_SHAKE_WETNESS, EntityPackets.javaEntityEvent(ActorEvent.SHAKE_WETNESS_STOP, EntityTypes1_21_11.WOLF)),
                () -> assertNull(EntityPackets.javaEntityEvent(ActorEvent.FINISHED_CHARGING_ITEM, EntityTypes1_21_11.PILLAGER)),
                () -> assertEquals(EntityEvent.PROTECTED_FROM_DEATH, EntityPackets.javaEntityEvent(ActorEvent.TALISMAN_ACTIVATE, EntityTypes1_21_11.PLAYER))
        );
    }

    @Test
    void mapsArmSwingOnlyForLivingEntities() {
        final LivingEntity livingEntity = livingEntity(EntityTypes1_21_11.ZOMBIE);
        final Entity boat = entity(EntityTypes1_21_11.OAK_BOAT);
        final Entity customCarrier = entity(EntityTypes1_21_11.INTERACTION);

        assertAll(
                () -> assertEquals(AnimateAction.SWING_MAIN_HAND, resolve(AnimatePacketPayload_Action.Swing, livingEntity).action()),
                () -> assertEquals(AnimateAction.SWING_MAIN_HAND, EntityPackets.javaAnimateAction(ActorEvent.START_ATTACKING, livingEntity)),
                () -> assertNull(resolve(AnimatePacketPayload_Action.Swing, boat)),
                () -> assertNull(EntityPackets.javaAnimateAction(ActorEvent.START_ATTACKING, boat)),
                () -> assertNull(resolve(AnimatePacketPayload_Action.Swing, customCarrier)),
                () -> assertNull(EntityPackets.javaAnimateAction(ActorEvent.START_ATTACKING, customCarrier)),
                () -> assertTrue(EntityPackets.isJavaAnimateActionValid(AnimateAction.SWING_OFF_HAND, livingEntity)),
                () -> assertFalse(EntityPackets.isJavaAnimateActionValid(AnimateAction.SWING_OFF_HAND, boat))
        );
    }

    @Test
    void mapsWakeUpOnlyForPlayers() {
        final PlayerEntity player = playerEntity();

        assertAll(
                () -> assertEquals(AnimateAction.WAKE_UP, resolve(AnimatePacketPayload_Action.WakeUp, player).action()),
                () -> assertNull(resolve(AnimatePacketPayload_Action.WakeUp, livingEntity(EntityTypes1_21_11.ZOMBIE))),
                () -> assertNull(resolve(AnimatePacketPayload_Action.WakeUp, entity(EntityTypes1_21_11.OAK_BOAT)))
        );
    }

    @Test
    void keepsHurtOnTheDedicatedDamageEventPath() {
        assertNull(EntityPackets.javaAnimateAction(ActorEvent.HURT, livingEntity(EntityTypes1_21_11.ZOMBIE)));
    }

    @Test
    void preservesCriticalAnimationsForEveryTrackedEntityType() {
        final Entity boat = entity(EntityTypes1_21_11.OAK_BOAT);
        final Entity customCarrier = entity(EntityTypes1_21_11.INTERACTION);

        assertAll(
                () -> assertEquals(AnimateAction.CRITICAL_HIT, resolve(AnimatePacketPayload_Action.CriticalHit, boat).action()),
                () -> assertEquals(AnimateAction.MAGIC_CRITICAL_HIT, resolve(AnimatePacketPayload_Action.MagicCriticalHit, customCarrier).action()),
                () -> assertFalse(EntityPackets.isJavaAnimateActionValid(AnimateAction.UNUSED, boat)),
                () -> assertNull(resolve(AnimatePacketPayload_Action.NoAction, boat))
        );
    }

    @Test
    void ignoresLateAnimationAfterEntityRemoval() {
        final long runtimeId = 42L;
        final Map<Long, Entity> entities = new HashMap<>();
        entities.put(runtimeId, livingEntity(EntityTypes1_21_11.ZOMBIE, runtimeId));
        entities.remove(runtimeId);

        assertNull(EntityPackets.resolveJavaAnimate(AnimatePacketPayload_Action.Swing, runtimeId, entities::get));
    }

    @Test
    void resolvesRuntimeIdReuseAgainstTheCurrentEntity() {
        final long runtimeId = 42L;
        final Map<Long, Entity> entities = new HashMap<>();
        final LivingEntity original = livingEntity(EntityTypes1_21_11.ZOMBIE, runtimeId);
        final Entity replacementBoat = entity(EntityTypes1_21_11.OAK_BOAT, runtimeId);
        entities.put(runtimeId, original);

        final EntityPackets.JavaAnimate originalSwing = EntityPackets.resolveJavaAnimate(AnimatePacketPayload_Action.Swing, runtimeId, entities::get);
        entities.put(runtimeId, replacementBoat);
        final EntityPackets.JavaAnimate replacementCritical = EntityPackets.resolveJavaAnimate(AnimatePacketPayload_Action.CriticalHit, runtimeId, entities::get);

        assertAll(
                () -> assertSame(original, originalSwing.entity()),
                () -> assertNull(EntityPackets.resolveJavaAnimate(AnimatePacketPayload_Action.Swing, runtimeId, entities::get)),
                () -> assertSame(replacementBoat, replacementCritical.entity()),
                () -> assertEquals(AnimateAction.CRITICAL_HIT, replacementCritical.action())
        );
    }

    private static EntityData ownerData(final long ownerUniqueId) {
        return new EntityData(ActorDataIDs.OWNER.getValue(), EntityDataTypesBedrock.LONG, ownerUniqueId);
    }

    private static EntityData variantData(final int bedrockRuntimeId) {
        return new EntityData(ActorDataIDs.VARIANT.getValue(), EntityDataTypesBedrock.INT, bedrockRuntimeId);
    }

    private static Entity entity(final EntityTypes1_21_11 javaType) {
        return entity(javaType, 2L);
    }

    private static Entity entity(final EntityTypes1_21_11 javaType, final long runtimeId) {
        return new Entity(null, 1L, runtimeId, "test:entity", 3, UUID.randomUUID(), javaType);
    }

    @Test
    void boatEyeOffsetMatchesMotBaseOffset() {
        final Entity boat = new Entity(null, 1L, 2L, "minecraft:boat", 3, UUID.randomUUID(), EntityTypes1_21_11.OAK_BOAT);
        final Entity chestBoat = new Entity(null, 1L, 2L, "minecraft:chest_boat", 3, UUID.randomUUID(), EntityTypes1_21_11.OAK_CHEST_BOAT);
        assertEquals(0.375F, boat.eyeOffset(), 1.0E-6F);
        assertEquals(0.375F, chestBoat.eyeOffset(), 1.0E-6F);
        assertEquals(0F, entity(EntityTypes1_21_11.ITEM).eyeOffset(), 1.0E-6F);
    }

    @Test
    void minecartEyeOffsetMatchesMotBaseOffset() {
        assertAll(
                () -> assertEquals(0.35F, minecart(EntityTypes1_21_11.MINECART).eyeOffset(), 1.0E-6F),
                () -> assertEquals(0.35F, minecart(EntityTypes1_21_11.CHEST_MINECART).eyeOffset(), 1.0E-6F),
                () -> assertEquals(0.35F, minecart(EntityTypes1_21_11.HOPPER_MINECART).eyeOffset(), 1.0E-6F),
                () -> assertEquals(0.35F, minecart(EntityTypes1_21_11.TNT_MINECART).eyeOffset(), 1.0E-6F),
                () -> assertEquals(0.35F, minecart(EntityTypes1_21_11.FURNACE_MINECART).eyeOffset(), 1.0E-6F),
                () -> assertEquals(0.35F, minecart(EntityTypes1_21_11.COMMAND_BLOCK_MINECART).eyeOffset(), 1.0E-6F),
                () -> assertEquals(0.35F, minecart(EntityTypes1_21_11.SPAWNER_MINECART).eyeOffset(), 1.0E-6F)
        );
    }

    private static Entity minecart(final EntityTypes1_21_11 javaType) {
        return new Entity(null, 1L, 2L, "minecraft:minecart", 3, UUID.randomUUID(), javaType);
    }

    private static LivingEntity livingEntity(final EntityTypes1_21_11 javaType) {
        return livingEntity(javaType, 2L);
    }

    private static LivingEntity livingEntity(final EntityTypes1_21_11 javaType, final long runtimeId) {
        return new LivingEntity(null, 1L, runtimeId, "test:living_entity", 3, UUID.randomUUID(), javaType);
    }

    private static PlayerEntity playerEntity() {
        return new PlayerEntity(null, 2L, 3, UUID.randomUUID(), new PlayerAbilities(1L, (byte) 0, (byte) 0));
    }

    private static EntityPackets.JavaAnimate resolve(final AnimatePacketPayload_Action action, final Entity entity) {
        return EntityPackets.resolveJavaAnimate(action, entity.runtimeId(), runtimeId -> entity.runtimeId() == runtimeId ? entity : null);
    }

}
