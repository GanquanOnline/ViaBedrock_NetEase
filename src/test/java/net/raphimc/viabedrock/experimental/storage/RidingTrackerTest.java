/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.libs.fastutil.longs.LongArrayList;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.experimental.ItemUseAirClickTarget;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.test.StubUserConnection;
import net.raphimc.viabedrock.protocol.packet.EntityPacketLayout;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.BOAT_PREDICTED;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.PASSENGER_ONLY;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.VIRTUAL_INPUT_ONLY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RidingTrackerTest {

    @Test
    void preservesMotControllerAcrossDuplicateAndOutOfOrderLinks() {
        final LongArrayList passengers = new LongArrayList();

        RidingTracker.updatePassengerOrder(passengers, 22L, (byte) 2);
        RidingTracker.updatePassengerOrder(passengers, 11L, (byte) 1);
        assertArrayEquals(new long[]{11L, 22L}, passengers.toLongArray());

        RidingTracker.updatePassengerOrder(passengers, 22L, (byte) 2);
        RidingTracker.updatePassengerOrder(passengers, 11L, (byte) 1);
        assertArrayEquals(new long[]{11L, 22L}, passengers.toLongArray());

        RidingTracker.updatePassengerOrder(passengers, 22L, (byte) 1);
        assertArrayEquals(new long[]{22L, 11L}, passengers.toLongArray());
    }

    @Test
    void dimensionResetClearsTrackedRidingRelations() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final StubUserConnection user = new StubUserConnection(channel);
            final RidingTracker tracker = new RidingTracker(user);
            user.put(tracker);

            tracker.handleLink(new EntityLink(100L, 200L, (byte) 1, true, false, 0F));
            assertTrue(tracker.hasTrackedRidingState());

            tracker.resetForDimensionChange();
            assertFalse(tracker.hasTrackedRidingState());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void forwardsDirectionalInputForControllableMinecarts() {
        assertAll(
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.MINECART, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.CHEST_MINECART, true))
        );
    }

    @Test
    void keepsBoatPredictionSeparateFromMinecartInput() {
        assertEquals(BOAT_PREDICTED, RidingTracker.localRidingMode(EntityTypes1_21_11.OAK_BOAT, true));
    }

    @Test
    void predictedBoatAuthInputUsesBoatNetworkOffsetNotPlayerEye() {
        final Position3f javaBoat = new Position3f(10F, 64F, -3F);
        final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(javaBoat, 0.375F);
        assertEquals(10F, auth.x());
        assertEquals(64.375F, auth.y(), 1.0e-6F);
        assertEquals(-3F, auth.z());
        assertTrue(auth.y() - javaBoat.y() < 1.0F, "player eye 1.62 would lift the boat into GanAC FlyCheck");
    }

    @Test
    void predictedBoatWithoutMoveVehicleKeepsMotNetworkY() {
        final Position3f motNetworkBoat = new Position3f(10F, 64.375F, -3F);
        final Position3f auth = RidingTracker.predictedBoatAuthInputFromVehicle(motNetworkBoat, 0.375F);
        assertEquals(10F, auth.x());
        assertEquals(64.375F, auth.y(), 1.0e-6F);
        assertEquals(-3F, auth.z());
        assertEquals(motNetworkBoat.y(), auth.y(), 1.0e-6F);
        assertTrue(Math.abs(auth.y() - (motNetworkBoat.y() + 1.62F)) > 1.0F,
                "player eye fallback would lift the boat ~1.245 on the first mount tick");
    }

    @Test
    void predictedBoatFromSpawnedJavaFootMatchesMotNetworkY() {
        // After ADD_ENTITY strips MOT getBaseOffset, JE MOVE_VEHICLE reports the foot.
        // That foot + boat eyeOffset must equal the MOT network Y used without MOVE_VEHICLE.
        final Position3f motNetworkBoat = new Position3f(10F, 64.375F, -3F);
        final Position3f javaFoot = RidingTracker.predictedBoatJavaFoot(motNetworkBoat, 0.375F);
        final Position3f fromMoveVehicle = RidingTracker.predictedBoatAuthInputPosition(javaFoot, 0.375F);
        final Position3f fromTracker = RidingTracker.predictedBoatAuthInputFromVehicle(motNetworkBoat, 0.375F);

        assertEquals(64.0F, javaFoot.y(), 1.0e-6F);
        assertEquals(fromTracker.y(), fromMoveVehicle.y(), 1.0e-6F);
        assertEquals(motNetworkBoat.y(), fromMoveVehicle.y(), 1.0e-6F);
        assertTrue(Math.abs(fromMoveVehicle.y() - (motNetworkBoat.y() + 0.375F)) > 0.3F,
                "feeding the MOT network Y as a Java foot would lift SAI by another 0.375");
    }

    @Test
    void predictedBoatIgnoresJavaBuoyancyYWhenMoveVehicleIsPresent() {
        // JE client boat buoyancy can raise MOVE_VEHICLE Y every tick. MOT 860 onInput writes
        // that Y into the hull; feeding it back would make the boat climb forever (#1-2).
        final Position3f motNetworkBoat = new Position3f(10F, 64.375F, -3F);
        final Position3f buoyantJavaFoot = new Position3f(11F, 65.0F, -2F);
        final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(buoyantJavaFoot, motNetworkBoat, 0.375F);

        assertEquals(11F, auth.x(), 1.0e-6F);
        assertEquals(64.375F, auth.y(), 1.0e-6F);
        assertEquals(-2F, auth.z(), 1.0e-6F);
        assertEquals(motNetworkBoat.y(), auth.y(), 1.0e-6F);
        assertTrue(Math.abs(auth.y() - (buoyantJavaFoot.y() + 0.375F)) > 0.5F,
                "JE buoyancy Y must not become the next MOT hull height");
    }

    @Test
    void predictedBoatJavaSyncSoftFollowsTargetFootWhilePreservingSteerXZ() {
        // Large JE climb corrections still start from the visual foot and approach the resting
        // target one step at a time (no teleport), while preserving steer XZ.
        final Position3f motNetworkBoat = new Position3f(10F, 64.375F, -3F);
        final Position3f buoyantJavaFoot = new Position3f(11F, 65.0F, -2F);
        final Position3f sync = RidingTracker.predictedBoatJavaSyncPosition(buoyantJavaFoot, motNetworkBoat, 0.375F);

        assertEquals(11F, sync.x(), 1.0e-6F);
        assertEquals(65.0F - 0.045F, sync.y(), 1.0e-6F);
        assertEquals(-2F, sync.z(), 1.0e-6F);
        assertTrue(sync.y() < buoyantJavaFoot.y(), "must start pulling the JE foot down");
        assertTrue(sync.y() > 64.0F, "must not teleport to the resting foot in one packet");
    }

    @Test
    void predictedBoatApproachesWaterSurfaceInsteadOfTeleporting() {
        // Occupied MOT boats disable wave sim, so tracker Y stays at the mount snapshot. Hard
        // snapping to the water surface teleports the hull; approach one step per auth tick.
        final Position3f frozenTracker = new Position3f(10F, 70.375F, -3F);
        final Position3f steeredJavaFoot = new Position3f(10.2F, 69.9F, -3.1F);
        final ItemUseAirClickTarget.WorldView world = waterWorld(10, 64, -3, 0);

        final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(steeredJavaFoot, frozenTracker, 0.375F, world);
        final Position3f sync = RidingTracker.predictedBoatJavaSyncPosition(steeredJavaFoot, frozenTracker, 0.375F, world);

        final float waterNetworkY = 64F + (8F / 9F);
        final float expectedNetworkY = 70.375F - 0.045F;
        final float expectedSyncFoot = 69.9F - 0.045F;
        assertEquals(10.2F, auth.x(), 1.0e-6F);
        assertEquals(expectedNetworkY, auth.y(), 1.0e-5F);
        assertEquals(-3.1F, auth.z(), 1.0e-6F);
        assertEquals(expectedSyncFoot, sync.y(), 1.0e-5F);
        assertTrue(auth.y() > waterNetworkY + 1F, "must not teleport all the way to the water in one tick");
        assertTrue(auth.y() < frozenTracker.y(), "must start descending toward the water");
    }

    @Test
    void predictedBoatEventuallyReachesLowerWaterAcrossAuthTicks() {
        Position3f tracker = new Position3f(10F, 65.0F, -3F);
        final Position3f laggingJavaFoot = new Position3f(10F, 64.7F, -3F);
        final ItemUseAirClickTarget.WorldView world = waterWorld(10, 63, -3, 0);
        final float expectedNetworkY = 63F + (8F / 9F);

        float authY = tracker.y();
        for (int i = 0; i < 64; i++) {
            final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(laggingJavaFoot, tracker, 0.375F, world);
            authY = auth.y();
            tracker = new Position3f(tracker.x(), authY, tracker.z());
            if (Math.abs(authY - expectedNetworkY) <= 1.0e-5F) {
                break;
            }
        }
        assertEquals(expectedNetworkY, authY, 1.0e-5F);
    }

    @Test
    void approachBoatNetworkYMovesAtMostOneStep() {
        assertEquals(10.045F, RidingTracker.approachBoatNetworkY(10F, 12F), 1.0e-6F);
        assertEquals(9.955F, RidingTracker.approachBoatNetworkY(10F, 8F), 1.0e-6F);
        assertEquals(10.02F, RidingTracker.approachBoatNetworkY(10F, 10.02F), 1.0e-6F);
    }

    @Test
    void shouldSyncPredictedBoatJavaYUsesTakeoffAndHoverMargins() {
        final float targetFoot = 64.0F;
        assertFalse(RidingTracker.shouldSyncPredictedBoatJavaY(64.10F, targetFoot));
        assertFalse(RidingTracker.shouldSyncPredictedBoatJavaY(63.80F, targetFoot));
        assertTrue(RidingTracker.shouldSyncPredictedBoatJavaY(64.21F, targetFoot));
        assertTrue(RidingTracker.shouldSyncPredictedBoatJavaY(63.64F, targetFoot));
    }

    @Test
    void predictedBoatAllowsSmallGroundedJavaCorrectionWithoutWater() {
        final Position3f tracker = new Position3f(10F, 64.375F, -3F);
        final Position3f groundedJavaFoot = new Position3f(10F, 64.01F, -3F);
        final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(groundedJavaFoot, tracker, 0.375F, null);
        assertEquals(64.01F + 0.375F, auth.y(), 1.0e-5F);
    }

    @Test
    void predictedBoatApproachesLandGroundInsteadOfHoveringOnCliff() {
        // No water under the hull: previous code rejected large downward JE deltas and froze at
        // the mount snapshot, so land cliffs left the boat floating. Soft-follow solid ground.
        final Position3f frozenTracker = new Position3f(10F, 70.375F, -3F);
        final Position3f fallingJavaFoot = new Position3f(10.1F, 68.5F, -3.1F);
        final ItemUseAirClickTarget.WorldView world = solidWorld(10, 64, -3);

        final float groundNetworkY = 64F + 1F + 0.375F;
        assertEquals(groundNetworkY, RidingTracker.boatTargetNetworkY(fallingJavaFoot, frozenTracker, 0.375F, world), 1.0e-5F);

        final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(fallingJavaFoot, frozenTracker, 0.375F, world);
        assertEquals(70.375F - 0.045F, auth.y(), 1.0e-5F);
        assertTrue(auth.y() > groundNetworkY + 1F, "must not teleport to the cliff bottom in one tick");
    }

    @Test
    void predictedBoatAllowsLargeLandDescentWithoutChunkSample() {
        final Position3f tracker = new Position3f(10F, 70.375F, -3F);
        final Position3f fallingJavaFoot = new Position3f(10F, 68.0F, -3F);
        final Position3f auth = RidingTracker.predictedBoatAuthInputPosition(fallingJavaFoot, tracker, 0.375F, null);
        assertEquals(70.375F - 0.045F, auth.y(), 1.0e-5F);
    }

    @Test
    void waterSurfaceMaxYMatchesMotLiquidHeightPercent() {
        assertEquals(64F + (8F / 9F), RidingTracker.waterSurfaceMaxY(waterState(0), 64), 1.0e-6F);
        assertEquals(64F + (7F / 9F), RidingTracker.waterSurfaceMaxY(waterState(1), 64), 1.0e-6F);
        assertEquals(64F + (8F / 9F), RidingTracker.waterSurfaceMaxY(waterState(8), 64), 1.0e-6F);
        assertEquals(null, RidingTracker.waterSurfaceMaxY(new BlockState("stone", Collections.emptyMap()), 64));
    }

    private static ItemUseAirClickTarget.WorldView solidWorld(final int x, final int y, final int z) {
        final int solidId = 9;
        final BlockPosition solid = new BlockPosition(x, y, z);
        return new ItemUseAirClickTarget.WorldView() {
            @Override
            public int blockStateId(final int layer, final BlockPosition position) {
                return layer == 0 && position.equals(solid) ? solidId : 0;
            }

            @Override
            public BlockState blockState(final int bedrockBlockStateId) {
                if (bedrockBlockStateId == solidId) {
                    return new BlockState("stone", Collections.emptyMap());
                }
                return new BlockState("air", Collections.emptyMap());
            }

            @Override
            public int airId() {
                return 0;
            }
        };
    }

    private static ItemUseAirClickTarget.WorldView waterWorld(final int x, final int y, final int z, final int depth) {
        final BlockState water = waterState(depth);
        return new ItemUseAirClickTarget.WorldView() {
            @Override
            public int blockStateId(final int layer, final BlockPosition position) {
                if (layer == 0 && position.x() == x && position.y() == y && position.z() == z) {
                    return 1;
                }
                return 0;
            }

            @Override
            public BlockState blockState(final int bedrockBlockStateId) {
                return bedrockBlockStateId == 1 ? water : new BlockState("air", Collections.emptyMap());
            }

            @Override
            public int airId() {
                return 0;
            }
        };
    }

    private static BlockState waterState(final int depth) {
        final Map<String, String> properties = new HashMap<>();
        properties.put("liquid_depth", Integer.toString(depth));
        return new BlockState("water", properties);
    }

    @Test
    void doesNotForwardInputFromNonControllingPassengers() {
        assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.MINECART, false));
    }

    @Test
    void mapsJavaPaddleBooleansToMotRowActions() {
        assertAll(
                () -> assertTrue(EntityPacketLayout.isRowAction(EntityPacketLayout.ROW_LEFT_ACTION)),
                () -> assertTrue(EntityPacketLayout.isRowAction(EntityPacketLayout.ROW_RIGHT_ACTION)),
                () -> assertFalse(EntityPacketLayout.isRowAction(1)),
                () -> assertEquals(129, EntityPacketLayout.ROW_LEFT_ACTION),
                () -> assertEquals(128, EntityPacketLayout.ROW_RIGHT_ACTION)
        );
    }

    @Test
    void forwardsDirectionalInputForMot860Rideables() {
        assertAll(
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.HORSE, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.DONKEY, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.MULE, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.SKELETON_HORSE, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.ZOMBIE_HORSE, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.PIG, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.STRIDER, true)),
                () -> assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.HORSE, false))
        );
    }

    @Test
    void minecartsUseJavaPassengerAttachmentAndMotSeatForAuthInput() {
        assertAll(
                () -> assertTrue(RidingTracker.usesVanillaRiding(EntityTypes1_21_11.MINECART)),
                () -> assertTrue(RidingTracker.usesVanillaRiding(EntityTypes1_21_11.CHEST_MINECART)),
                () -> assertTrue(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.MINECART)),
                () -> assertTrue(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.CHEST_MINECART)),
                () -> assertTrue(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.HOPPER_MINECART)),
                () -> assertTrue(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.TNT_MINECART)),
                () -> assertTrue(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.FURNACE_MINECART)),
                () -> assertTrue(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.COMMAND_BLOCK_MINECART)),
                () -> assertTrue(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.SPAWNER_MINECART)),
                () -> assertFalse(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.OAK_BOAT)),
                () -> assertFalse(RidingTracker.usesMinecartRiding(EntityTypes1_21_11.HORSE))
        );
    }

    @Test
    void minecartDefaultSeatMatchesMotMountedOffset() {
        assertEquals(0.525F, RidingTracker.defaultSeatOffset(EntityTypes1_21_11.MINECART, Position3f.ZERO).y(), 1.0e-6F);
        final Position3f offRail = new Position3f(0F, 0.875F, 0F);
        assertEquals(offRail, RidingTracker.defaultSeatOffset(EntityTypes1_21_11.MINECART, offRail));
        assertEquals(Position3f.ZERO, RidingTracker.defaultSeatOffset(EntityTypes1_21_11.HORSE, Position3f.ZERO));
    }

    @Test
    void minecartAuthInputUsesInternalFootPlusSeatPlusPlayerEye() {
        final Position3f internal = new Position3f(10F, 64F, -3F);
        final Position3f seat = new Position3f(0F, 0.525F, 0F);
        final Position3f auth = RidingTracker.passengerAuthInputPosition(internal, seat, 1.62F);

        assertEquals(10F, auth.x());
        assertEquals(66.145F, auth.y(), 1.0e-5F);
        assertEquals(-3F, auth.z());
        assertEquals(64F + 0.525F + 1.62F, auth.y(), 1.0e-5F);
    }

    @Test
    void passengerAuthInputKeepsHorseSeatPlusPlayerEye() {
        final Position3f vehicle = new Position3f(10F, 64F, -3F);
        final Position3f horseSeat = new Position3f(0F, 1.2F, 0F);
        final Position3f auth = RidingTracker.passengerAuthInputPosition(vehicle, horseSeat, 1.62F);
        assertEquals(10F, auth.x());
        assertEquals(66.82F, auth.y(), 1.0e-5F);
        assertEquals(-3F, auth.z());
        final float motFeetY = auth.y() - horseSeat.y();
        final float passengerFootY = vehicle.y() + horseSeat.y();
        assertEquals(65.62F, motFeetY, 1.0e-5F);
        assertEquals(65.2F, passengerFootY, 1.0e-5F);
        assertTrue(Math.abs(motFeetY - passengerFootY) < 0.5F,
                "MOT subtracts the 1.2 seat; dropping the seat would land 0.78 below the passenger foot");
    }

}
