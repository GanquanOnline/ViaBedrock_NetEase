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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.BOAT_PREDICTED;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.PASSENGER_ONLY;
import static net.raphimc.viabedrock.experimental.storage.RidingTracker.LocalRidingMode.VIRTUAL_INPUT_ONLY;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RidingTrackerTest {

    @BeforeAll
    static void initializeProtocolTypes() throws Exception {
        // Initialize component keys before VersionedTypes, as in the standalone metadata tests.
        Class.forName("com.viaversion.viaversion.api.minecraft.data.StructuredDataKey");
        // Via 5.12 UserConnectionImpl requires a platform before the fixture can install its own manager.
        final Field managerField = Via.class.getDeclaredField("manager");
        managerField.setAccessible(true);
        if (managerField.get(null) != null) {
            return;
        }
        final ProtocolManager protocolManager = (ProtocolManager) Proxy.newProxyInstance(
                ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("createPacketWrapper")) {
                        return new PacketWrapperImpl((PacketType) args[0], null, (UserConnection) args[2]);
                    }
                    if (method.getName().equals("registeredProtocolCount") || method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    return null;
                });
        final ViaManager manager = (ViaManager) Proxy.newProxyInstance(
                ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getProtocolManager")) {
                        return protocolManager;
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    return null;
                });
        managerField.set(null, manager);
    }

    private static final long PLAYER_UNIQUE_ID = 7L;
    private static final long BOAT_UNIQUE_ID = 42L;
    private static final byte LINK_REMOVE = 0;
    private static final byte LINK_RIDE = 1;
    private static final Position3f BOAT_POSITION = new Position3f(12.5F, 64F, -4.5F);
    private static final Position3f SEAT_OFFSET = new Position3f(0F, 1.02001F, 0F);
    private static final Position3f EXPECTED_DISMOUNT_POSITION = new Position3f(
            BOAT_POSITION.x(), BOAT_POSITION.y() + SEAT_OFFSET.y() + 1.62F, BOAT_POSITION.z());

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
    void doesNotForwardInputFromNonControllingPassengers() {
        assertEquals(PASSENGER_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.MINECART, false));
    }

    @Test
    void preservesExistingVanillaPassengerModes() {
        // This fork steers MOT horses, pigs and striders with virtual input so SAI keeps the seat offset.
        assertAll(
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.HORSE, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.PIG, true)),
                () -> assertEquals(VIRTUAL_INPUT_ONLY, RidingTracker.localRidingMode(EntityTypes1_21_11.STRIDER, true))
        );
    }

    @Test
    void serverLinkRemovalSynchronizesTheLocalPlayerAboveTheBoat() {
        final RidingFixture fixture = ridingFixture();
        try {
            fixture.tickInput();

            fixture.tracker().handleLink(new EntityLink(
                    BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, LINK_REMOVE, false, false, 0F));

            assertEquals(EXPECTED_DISMOUNT_POSITION, fixture.clientPlayer().position());
            assertEquals(Relative.ROTATION, fixture.clientPlayer().positionSyncRelatives());
            assertNull(fixture.tracker().localVehicle());
            assertEquals(List.of("passengers:[]", "position"), fixture.clientPlayer().events);
            final PacketWrapper position = fixture.clientPlayer().lastPositionPacket;
            assertEquals(EXPECTED_DISMOUNT_POSITION.x(), position.get(Types.DOUBLE, 0), 0.00001);
            assertEquals(BOAT_POSITION.y() + SEAT_OFFSET.y(), position.get(Types.DOUBLE, 1), 0.00001);
            assertTrue(fixture.clientPlayer().hasPendingPositionSync());
            fixture.clientPlayer().confirmTeleport(position.get(Types.VAR_INT, 0));
            assertFalse(fixture.clientPlayer().hasPendingPositionSync());
            fixture.tracker().onEntityRemoved(fixture.boat());
            assertEquals(2, fixture.clientPlayer().events.size());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    @Test
    void vehicleRemovalBeforeTheFirstRidingTickUsesTheCurrentBoatPosition() {
        final RidingFixture fixture = ridingFixture();
        try {
            fixture.tracker().onEntityRemoved(fixture.boat());

            assertEquals(EXPECTED_DISMOUNT_POSITION, fixture.clientPlayer().position());
            assertEquals(Relative.ROTATION, fixture.clientPlayer().positionSyncRelatives());
            assertNull(fixture.tracker().localVehicle());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    @Test
    void authoritativeServerPositionWinsAfterTheDismountSync() {
        final RidingFixture fixture = ridingFixture();
        try {
            fixture.tickInput();
            fixture.tracker().handleLink(new EntityLink(
                    BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, LINK_REMOVE, false, false, 0F));
            final Position3f serverPosition = new Position3f(100F, 81.62F, -30F);
            fixture.clientPlayer().setPositionFromServer(serverPosition);
            final PlayerAuthInputContext context = new PlayerAuthInputContext(serverPosition, Position3f.ZERO);

            fixture.tracker().applyAuthInput(fixture.clientPlayer(), context);

            assertEquals(serverPosition, context.position());
            assertNull(fixture.tracker().localVehicle());
        } finally {
            fixture.channel().finishAndReleaseAll();
        }
    }

    @Test
    void serverTeleportBeforeUnlinkWinsOverQueuedJavaBoatMovement() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.tickInput();
            final Position3f target = new Position3f(100F, 81.62F, -30F);
            fixture.clientPlayer().setPositionFromServer(target);
            fixture.tracker().handleMoveVehicle(-5D, 40D, -5D, 0F, 0F, false);
            fixture.unlink();
            assertEquals(target, fixture.clientPlayer().position());
        }
    }

    @Test
    void removedVehicleUsesServerTeleportTarget() {
        try (RidingFixture fixture = ridingFixture()) {
            final Position3f target = new Position3f(100F, 81.62F, -30F);
            fixture.clientPlayer().setPositionFromServer(target);
            fixture.tracker().onEntityRemoved(fixture.boat());
            assertEquals(target, fixture.clientPlayer().position());
        }
    }

    @Test
    void serverVehicleMovementReplacesThePreviousTickAndJavaPrediction() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.tickInput();
            fixture.tracker().handleMoveVehicle(-5D, 40D, -5D, 0F, 0F, false);
            fixture.boat().setPosition(new Position3f(48F, 70F, -12F));
            fixture.tracker().onEntityMoved(fixture.boat());
            fixture.unlink();
            assertEquals(new Position3f(48F, 70F + SEAT_OFFSET.y() + 1.62F, -12F), fixture.clientPlayer().position());
        }
    }

    @Test
    void consumedJavaVehicleMovementRemainsTheCurrentPositionUntilServerUpdates() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.tracker().handleMoveVehicle(48D, 70D, -12D, 90F, 10F, false);
            fixture.tickInput();
            fixture.tickInput();
            fixture.unlink();
            assertEquals(new Position3f(48F, 70F + SEAT_OFFSET.y() + 1.62F, -12F), fixture.clientPlayer().position());
        }
    }

    @Test
    void dimensionResetDiscardsOldRideWithoutSendingPositionPackets() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.tickInput();
            fixture.clientPlayer().setPositionFromServer(new Position3f(30F, 70F, 30F));
            fixture.entities().prepareForRespawn();
            final Entity newBoat = new Entity(fixture.user(), 84L, 840L, "minecraft:boat", 3,
                    UUID.randomUUID(), EntityTypes1_21_11.OAK_BOAT);
            newBoat.setPosition(new Position3f(50F, 80F, 50F));
            fixture.user().getStoredObjects().put(EntityTracker.class,
                    new FixtureEntityTracker(fixture.user(), fixture.clientPlayer(), newBoat));
            final Position3f target = new Position3f(100F, 81.62F, -30F);
            fixture.clientPlayer().setPosition(target);
            assertNull(fixture.tracker().localVehicle());
            assertEquals(target, fixture.clientPlayer().position());
            assertEquals(List.of(), fixture.clientPlayer().events);
            fixture.tracker().handleLink(new EntityLink(84L, PLAYER_UNIQUE_ID, LINK_RIDE, false, false, 0F));
            fixture.tracker().handleLink(new EntityLink(84L, PLAYER_UNIQUE_ID, LINK_REMOVE, false, false, 0F));
            assertEquals(new Position3f(50F, 81.62F, 50F), fixture.clientPlayer().position(),
                    "The new world must not inherit the old seat offset or server teleport");
        }
    }

    @Test
    void missingVehicleOnlyClearsState() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.tickInput();
            fixture.entities().vehicles.clear();
            final Position3f target = new Position3f(100F, 81.62F, -30F);
            fixture.clientPlayer().setPosition(target);
            assertNull(fixture.tracker().localVehicle());
            assertEquals(target, fixture.clientPlayer().position());
            assertEquals(List.of(), fixture.clientPlayer().events);
        }
    }

    @Test
    void continuedServerRideClearsTheEarlierPlayerTeleport() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.clientPlayer().setPositionFromServer(new Position3f(100F, 81.62F, -30F));
            fixture.boat().setPosition(new Position3f(100F, 80F, -30F));
            fixture.tracker().onEntityMoved(fixture.boat());
            fixture.tracker().handleMoveVehicle(101D, 80D, -30D, 0F, 0F, false);
            final PlayerAuthInputContext input = new PlayerAuthInputContext(fixture.clientPlayer().position(), Position3f.ZERO);
            fixture.tracker().applyAuthInput(fixture.clientPlayer(), input);
            assertTrue(input.hasPredictedVehicle());
            assertEquals(new Position3f(101F, 81.62F, -30F), input.position());
            fixture.unlink();
            assertEquals(new Position3f(101F, 80F + SEAT_OFFSET.y() + 1.62F, -30F), fixture.clientPlayer().position());
        }
    }

    @Test
    void changingVehiclesDropsOldTeleportAndIgnoresOldUnlink() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.clientPlayer().setPositionFromServer(new Position3f(100F, 81.62F, -30F));
            final Entity minecart = new Entity(fixture.user(), 84L, 840L, "minecraft:minecart", 3,
                    UUID.randomUUID(), EntityTypes1_21_11.MINECART);
            minecart.setPosition(new Position3f(50F, 80F, 50F));
            fixture.entities().vehicles.put(minecart.uniqueId(), minecart);
            fixture.setSeatOffset(new Position3f(0F, 0.5F, 0F));
            fixture.tracker().handleLink(new EntityLink(84L, PLAYER_UNIQUE_ID, LINK_RIDE, false, false, 0F));
            fixture.unlink();
            fixture.tracker().onEntityRemoved(fixture.boat());
            assertSame(minecart, fixture.tracker().localVehicle());
            assertNull(fixture.clientPlayer().lastPositionPacket);
            fixture.tracker().handleMoveVehicle(-5D, -5D, -5D, 0F, 0F, false);
            fixture.tracker().handleLink(new EntityLink(84L, PLAYER_UNIQUE_ID, LINK_REMOVE, false, false, 0F));
            assertEquals(new Position3f(50F, 80.5F + 1.62F, 50F), fixture.clientPlayer().position());
        }
    }

    @Test
    void passengerRemovalDoesNotDismountOrAcceptVehicleInputFromLocalSecondPassenger() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.tracker().handleLink(new EntityLink(BOAT_UNIQUE_ID, 9L, LINK_RIDE, false, false, 0F));
            fixture.tracker().handleLink(new EntityLink(BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, (byte) 2, false, false, 0F));
            fixture.setSeatOffset(new Position3f(-0.6F, 1.02001F, 0F));
            fixture.tracker().handleMoveVehicle(48D, 70D, -12D, 0F, 0F, false);
            assertEquals(BOAT_POSITION, fixture.boat().position());
            fixture.tracker().handleLink(new EntityLink(BOAT_UNIQUE_ID, 9L, LINK_REMOVE, false, false, 0F));
            assertSame(fixture.boat(), fixture.tracker().localVehicle());
            assertNull(fixture.clientPlayer().lastPositionPacket);
            fixture.unlink();
            assertEquals(BOAT_POSITION.x() - 0.6F, fixture.clientPlayer().position().x());
        }
    }

    @Test
    void pendingShiftDismountUsesUpdatedVehicleAndSeatPosition() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.tracker().requestLocalDismount(fixture.boat());
            fixture.tickInput();
            fixture.boat().setPosition(new Position3f(48F, 70F, -12F));
            fixture.tracker().onEntityMoved(fixture.boat());
            fixture.setSeatOffset(new Position3f(0F, 1.32F, 0F));
            final PlayerAuthInputContext input = new PlayerAuthInputContext(fixture.clientPlayer().position(), Position3f.ZERO);
            fixture.tracker().applyAuthInput(fixture.clientPlayer(), input);
            assertFalse(input.hasPredictedVehicle());
            fixture.unlink();
            assertEquals(new Position3f(48F, 70F + 1.32F + 1.62F, -12F), fixture.clientPlayer().position());
        }
    }

    @Test
    void oldServerTeleportAckCannotReleaseNewDismountSync() {
        try (RidingFixture fixture = ridingFixture()) {
            fixture.clientPlayer().setInitiallySpawned();
            final Position3f target = new Position3f(100F, 81.62F, -30F);
            fixture.clientPlayer().setPositionFromServer(target);
            final PacketWrapper oldTeleport = new PacketWrapperImpl(ClientboundPackets26_1.PLAYER_POSITION, null, fixture.user());
            fixture.clientPlayer().writePlayerPositionPacketToClient(oldTeleport, Relative.NONE, false);
            fixture.unlink();
            fixture.clientPlayer().confirmTeleport(oldTeleport.get(Types.VAR_INT, 0));
            fixture.clientPlayer().updatePlayerPosition(-5D, 40D, -5D, (short) 0);
            assertEquals(target, fixture.clientPlayer().position());
            assertTrue(fixture.clientPlayer().hasPendingPositionSync());
            fixture.clientPlayer().confirmTeleport(fixture.clientPlayer().lastPositionPacket.get(Types.VAR_INT, 0));
            assertFalse(fixture.clientPlayer().hasPendingPositionSync());
        }
    }

    @Test
    void localPlayerCarriesAnAnchoredDisplayAboveTheirFeetAndInitializesInterpolationBeforeMounting() throws Exception {
        try (AnchorFixture fixture = new AnchorFixture()) {
            fixture.clientPlayer.setPosition(new Position3f(12.5F, 65.62F, -4.5F));
            final Entity egg = fixture.addDisplay(84L, 3, new Position3f(0F, 0.63F, 0F));
            fixture.link(fixture.clientPlayer, egg);

            final PacketWrapper spawn = fixture.last(ClientboundPackets26_1.ADD_ENTITY);
            assertPacketPosition(spawn, 12.5D, 66.25D, -4.5D);
            final int anchorId = spawn.get(Types.VAR_INT, 0);
            final PacketWrapper data = fixture.last(ClientboundPackets26_1.SET_ENTITY_DATA);
            assertEquals(anchorId, data.get(Types.VAR_INT, 0));
            final List<EntityData> metadata = data.get(VersionedTypes.V26_1.entityDataList, 0);
            assertEquals(1, metadata.size());
            assertEquals(3, metadata.getFirst().getValue());
            final PacketWrapper relation = fixture.last(ClientboundPackets26_1.SET_PASSENGERS);
            assertEquals(anchorId, relation.get(Types.VAR_INT, 0));
            assertArrayEquals(new int[]{egg.javaId()}, relation.get(Types.VAR_INT_ARRAY_PRIMITIVE, 0));
            assertTrue(fixture.packets.indexOf(data) < fixture.packets.indexOf(relation));
            assertPacketPosition(fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC), 12.5D, 66.25D, -4.5D);
        }
    }

    @Test
    void remotePlayerTurningAndWalkingMovesTheSameLocalSeatAnchorAroundTheirFeet() throws Exception {
        try (AnchorFixture fixture = new AnchorFixture()) {
            final PlayerEntity carrier = new PlayerEntity(fixture.user, 420L, 2, UUID.randomUUID(),
                    new PlayerAbilities(BOAT_UNIQUE_ID, (byte) 0, (byte) 0));
            carrier.setPosition(new Position3f(12.5F, 65.62F, -4.5F));
            fixture.entities.vehicles.put(carrier.uniqueId(), carrier);
            final Entity egg = fixture.addDisplay(84L, 3, new Position3f(0.5F, 0.63F, 1.25F));
            fixture.link(carrier, egg);
            final int anchorId = fixture.last(ClientboundPackets26_1.ADD_ENTITY).get(Types.VAR_INT, 0);
            assertPacketPosition(fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC), 13D, 66.25D, -3.25D);
            fixture.packets.clear();

            carrier.setRotation(new Position3f(0F, 90F, 90F));
            fixture.tracker.onEntityMoved(carrier);
            assertPacketPosition(fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC), 11.25D, 66.25D, -4D);

            carrier.setPosition(new Position3f(20.5F, 71.62F, 8.5F));
            carrier.setRotation(new Position3f(0F, 180F, 180F));
            fixture.tracker.onEntityMoved(carrier);
            final PacketWrapper move = fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC);
            assertEquals(anchorId, move.get(Types.VAR_INT, 0));
            assertPacketPosition(move, 20D, 72.25D, 7.25D);
            assertEquals(180F, move.get(Types.FLOAT, 0));
            assertFalse(fixture.packets.stream().anyMatch(packet -> packet.getPacketType() == ClientboundPackets26_1.ADD_ENTITY));
        }
    }

    @Test
    void nonPlayerCarrierKeepsItsRootHeightAndRotatesTheLocalSeat() throws Exception {
        try (AnchorFixture fixture = new AnchorFixture()) {
            final Entity carrier = new Entity(fixture.user, BOAT_UNIQUE_ID, 420L, "ec:guard", 2,
                    UUID.randomUUID(), EntityTypes1_21_11.INTERACTION);
            carrier.setPosition(new Position3f(12.5F, 64F, -4.5F));
            carrier.setRotation(new Position3f(0F, 90F, 90F));
            fixture.entities.vehicles.put(carrier.uniqueId(), carrier);
            final Entity egg = fixture.addDisplay(84L, 3, new Position3f(0.5F, 2.25F, 1.25F));
            fixture.link(carrier, egg);
            assertPacketPosition(fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC), 11.25D, 66.25D, -4D);
        }
    }

    @Test
    void eggSeatFromNativeCaptureStaysAtTheSameHeightForTheJavaCarrier() throws Exception {
        try (AnchorFixture fixture = new AnchorFixture()) {
            // Native acceptance: feet Y=65, eye/network Y=66.62, seat Y=0.48, egg bottom Y=67.10.
            fixture.clientPlayer.setPosition(new Position3f(12.5F, 66.62F, -4.5F));
            final Entity egg = fixture.addDisplay(84L, 3, new Position3f(0F, 0.48F, 0F));
            fixture.link(fixture.clientPlayer, egg);
            assertPacketPosition(fixture.last(ClientboundPackets26_1.ADD_ENTITY), 12.5D, 67.10D, -4.5D);
            fixture.clientPlayer.setPosition(new Position3f(13.5F, 66.62F, -4.5F));
            fixture.tracker.onEntityMoved(fixture.clientPlayer);
            assertPacketPosition(fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC), 13.5D, 67.10D, -4.5D);
        }
    }

    @Test
    void fullyLockedDisplayFollowsBodyYawWithSeatRotationWithoutReceivingIndependentPositionPackets() throws Exception {
        try (AnchorFixture fixture = new AnchorFixture()) {
            final Entity carrier = new Entity(fixture.user, BOAT_UNIQUE_ID, 420L, "ec:guard", 2,
                    UUID.randomUUID(), EntityTypes1_21_11.INTERACTION);
            carrier.setPosition(new Position3f(12.5F, 64F, -4.5F));
            carrier.setRotation(new Position3f(0F, 90F, 180F));
            fixture.entities.vehicles.put(carrier.uniqueId(), carrier);
            final Entity egg = fixture.addDisplay(84L, 3, new Position3f(0F, 2.25F, 1.25F));
            egg.setRotation(new Position3f(15F, 0F, 0F));
            fixture.setRotationLock(egg, (byte) 1, 0F, (byte) 1, 30F);
            fixture.link(carrier, egg);
            final int anchorId = fixture.last(ClientboundPackets26_1.ADD_ENTITY).get(Types.VAR_INT, 0);
            final PacketWrapper rotate = fixture.last(ClientboundPackets26_1.MOVE_ENTITY_ROT);
            assertEquals(egg.javaId(), rotate.get(Types.VAR_INT, 0));
            assertEquals((byte) 85, rotate.get(Types.BYTE, 0)); // 120 degrees in Java's byte-angle format.
            assertEquals((byte) 10, rotate.get(Types.BYTE, 1)); // The display keeps its 15-degree pitch.
            assertEquals(new Position3f(15F, 120F, 120F), egg.rotation());
            assertPacketPosition(fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC), 11.25D, 66.25D, -4.5D);
            fixture.packets.clear();

            carrier.setRotation(new Position3f(0F, 180F, 270F));
            fixture.tracker.onEntityMoved(carrier);
            assertEquals((byte) -107, fixture.last(ClientboundPackets26_1.MOVE_ENTITY_ROT).get(Types.BYTE, 0)); // 210 degrees.
            final PacketWrapper move = fixture.last(ClientboundPackets26_1.ENTITY_POSITION_SYNC);
            assertEquals(anchorId, move.get(Types.VAR_INT, 0));
            assertPacketPosition(move, 12.5D, 66.25D, -5.75D);
            assertTrue(fixture.packets.stream().filter(packet -> packet.getPacketType() == ClientboundPackets26_1.ENTITY_POSITION_SYNC)
                    .allMatch(packet -> packet.get(Types.VAR_INT, 0) == anchorId));
            fixture.packets.clear();
            fixture.tracker.onEntityMoved(carrier);
            assertFalse(fixture.packets.stream().anyMatch(packet -> packet.getPacketType() == ClientboundPackets26_1.MOVE_ENTITY_ROT));
        }
    }

    @Test
    void unlockedAndPartiallyLockedDisplaysKeepTheirOwnYawWhileDisabledSeatRotationIsIgnored() throws Exception {
        try (AnchorFixture fixture = new AnchorFixture()) {
            fixture.clientPlayer.setRotation(new Position3f(0F, 90F, 180F));
            final Entity egg = fixture.addDisplay(84L, 3, new Position3f(0F, 2.25F, 0F));
            fixture.setRotationLock(egg, (byte) 0, 0F, (byte) 1, 30F);
            fixture.link(fixture.clientPlayer, egg);
            fixture.setRotationLock(egg, (byte) 1, 90F, (byte) 1, 30F);
            fixture.tracker.onEntityDataChanged(egg);
            assertFalse(fixture.packets.stream().anyMatch(packet -> packet.getPacketType() == ClientboundPackets26_1.MOVE_ENTITY_ROT));
            assertEquals(Position3f.ZERO, egg.rotation());

            fixture.setRotationLock(egg, (byte) 1, 0F, (byte) 0, 30F);
            fixture.tracker.onEntityDataChanged(egg);
            assertEquals((byte) 64, fixture.last(ClientboundPackets26_1.MOVE_ENTITY_ROT).get(Types.BYTE, 0));
            assertEquals(new Position3f(0F, 90F, 90F), egg.rotation());
        }
    }

    private static void assertPacketPosition(final PacketWrapper packet, final double x, final double y, final double z) {
        assertEquals(x, packet.get(Types.DOUBLE, 0), 0.00001);
        assertEquals(y, packet.get(Types.DOUBLE, 1), 0.00001);
        assertEquals(z, packet.get(Types.DOUBLE, 2), 0.00001);
    }

    private static final class AnchorFixture implements AutoCloseable {

        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
        private final FixtureClientPlayerEntity clientPlayer = new FixtureClientPlayerEntity(this.user, PLAYER_UNIQUE_ID,
                UUID.randomUUID(), new PlayerAbilities(PLAYER_UNIQUE_ID, (byte) 0, (byte) 0));
        private final FixtureEntityTracker entities = new FixtureEntityTracker(this.user, this.clientPlayer, this.clientPlayer);
        private final RidingTracker tracker = new RidingTracker(this.user);
        private final List<PacketWrapper> packets = new ArrayList<>();
        private final Field managerField = Via.class.getDeclaredField("manager");
        private final Field fieldsField = BedrockMappingData.class.getDeclaredField("javaEntityDataFields");
        private final Field displayIdField = EntityTypes1_21_11.class.getDeclaredField("id");
        private final int previousDisplayId;
        private final Object previousManager;
        private final Object previousFields;

        AnchorFixture() throws Exception {
            this.managerField.setAccessible(true);
            this.fieldsField.setAccessible(true);
            this.displayIdField.setAccessible(true);
            this.previousDisplayId = this.displayIdField.getInt(EntityTypes1_21_11.TEXT_DISPLAY);
            this.previousManager = this.managerField.get(null);
            this.previousFields = this.fieldsField.get(BedrockProtocol.MAPPINGS);
            // Only the packet transport and metadata registry are fixtures; the riding and packet writers run normally.
            final ProtocolManager protocolManager = (ProtocolManager) Proxy.newProxyInstance(
                    ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("registeredProtocolCount") || method.getReturnType() == int.class) {
                            return 0;
                        }
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        if (method.getName().equals("createPacketWrapper")) {
                            return new PacketWrapperImpl((PacketType) args[0], null, (UserConnection) args[2]) {
                                @Override
                                public void send(final Class<? extends Protocol> protocol, final boolean skipCurrentPipeline) {
                                    packets.add(this);
                                }
                            };
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            final ViaManager manager = (ViaManager) Proxy.newProxyInstance(
                    ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getProtocolManager")) {
                            return protocolManager;
                        }
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        if (method.getReturnType() == int.class) {
                            return 0;
                        }
                        return null;
                    });
            this.managerField.set(null, manager);
            this.displayIdField.setInt(EntityTypes1_21_11.TEXT_DISPLAY, 1);
            this.fieldsField.set(BedrockProtocol.MAPPINGS,
                    Map.of(EntityTypes1_21_11.TEXT_DISPLAY, List.of(EntityDataFields.POS_ROT_INTERPOLATION_DURATION)));
            this.clientPlayer.setPosition(new Position3f(0F, 65.62F, 0F));
            this.user.getStoredObjects().put(EntityTracker.class, this.entities);
            this.user.put(new JavaPassengerTracker(this.user));
            this.user.put(this.tracker);
        }

        Entity addDisplay(final long uniqueId, final int javaId, final Position3f localSeat) {
            final Entity display = new Entity(this.user, uniqueId, uniqueId * 10, "ec:egg", javaId,
                    UUID.randomUUID(), EntityTypes1_21_11.INTERACTION);
            this.entities.vehicles.put(uniqueId, display);
            display.entityData().put(ActorDataIDs.RESERVED_056,
                    new EntityData(56, EntityDataTypesBedrock.POSITION_3F, localSeat));
            this.tracker.onEntityAdded(display);
            return display;
        }

        void setRotationLock(final Entity display, final byte lock, final float degrees, final byte offset, final float offsetDegrees) {
            display.entityData().put(ActorDataIDs.SEAT_LOCK_PASSENGER_ROTATION,
                    new EntityData(57, EntityDataTypesBedrock.BYTE, lock));
            display.entityData().put(ActorDataIDs.SEAT_LOCK_PASSENGER_ROTATION_DEGREES,
                    new EntityData(58, EntityDataTypesBedrock.FLOAT, degrees));
            display.entityData().put(ActorDataIDs.SEAT_ROTATION_OFFSET,
                    new EntityData(59, EntityDataTypesBedrock.BYTE, offset));
            display.entityData().put(ActorDataIDs.SEAT_ROTATION_OFFSET_DEGREES,
                    new EntityData(60, EntityDataTypesBedrock.FLOAT, offsetDegrees));
        }

        void link(final Entity carrier, final Entity passenger) {
            this.tracker.handleLink(new EntityLink(carrier.uniqueId(), passenger.uniqueId(), LINK_RIDE, false, false, 0F));
        }

        PacketWrapper last(final ClientboundPackets26_1 type) {
            return this.packets.stream().filter(packet -> packet.getPacketType() == type).reduce((first, second) -> second).orElseThrow();
        }

        @Override
        public void close() throws Exception {
            this.managerField.set(null, this.previousManager);
            this.displayIdField.setInt(EntityTypes1_21_11.TEXT_DISPLAY, this.previousDisplayId);
            this.fieldsField.set(BedrockProtocol.MAPPINGS, this.previousFields);
            this.channel.finishAndReleaseAll();
        }
    }

    private static RidingFixture ridingFixture() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final FixtureClientPlayerEntity clientPlayer = new FixtureClientPlayerEntity(
                user,
                PLAYER_UNIQUE_ID,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new PlayerAbilities(PLAYER_UNIQUE_ID, (byte) 0, (byte) 0));
        clientPlayer.setPosition(new Position3f(0F, 65.62F, 0F));
        final Entity boat = new Entity(
                user,
                BOAT_UNIQUE_ID,
                420L,
                "minecraft:boat",
                2,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                EntityTypes1_21_11.OAK_BOAT);
        boat.setPosition(BOAT_POSITION);

        final FixtureEntityTracker entities = new FixtureEntityTracker(user, clientPlayer, boat);
        user.getStoredObjects().put(EntityTracker.class, entities);
        user.getStoredObjects().put(JavaPassengerTracker.class, new JavaPassengerTracker(user) {
            @Override
            public void setBedrockPassengers(final int vehicleId, final int... passengerIds) {
                clientPlayer.events.add("passengers:" + java.util.Arrays.toString(passengerIds));
            }

            @Override
            public void clearVehicle(final int vehicleId) {
                this.setBedrockPassengers(vehicleId);
            }
        });
        final RidingTracker tracker = new RidingTracker(user);
        user.put(tracker);
        clientPlayer.entityData().put(ActorDataIDs.RESERVED_056,
                new EntityData(56, EntityDataTypesBedrock.POSITION_3F, SEAT_OFFSET));
        tracker.onEntityAdded(clientPlayer);
        tracker.handleLink(new EntityLink(
                BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, LINK_RIDE, false, false, 0F));
        clientPlayer.events.clear();
        return new RidingFixture(channel, tracker, clientPlayer, boat, user, entities);
    }

    private record RidingFixture(
            EmbeddedChannel channel,
            RidingTracker tracker,
            FixtureClientPlayerEntity clientPlayer,
            Entity boat,
            UserConnectionImpl user,
            FixtureEntityTracker entities
    ) implements AutoCloseable {

        void unlink() {
            this.tracker.handleLink(new EntityLink(BOAT_UNIQUE_ID, PLAYER_UNIQUE_ID, LINK_REMOVE, false, false, 0F));
        }

        void setSeatOffset(final Position3f offset) {
            this.clientPlayer.entityData().put(ActorDataIDs.RESERVED_056,
                    new EntityData(56, EntityDataTypesBedrock.POSITION_3F, offset));
            this.tracker.onEntityDataChanged(this.clientPlayer);
        }

        @Override
        public void close() {
            this.channel.finishAndReleaseAll();
        }

        void tickInput() {
            this.tracker.applyAuthInput(
                    this.clientPlayer,
                    new PlayerAuthInputContext(this.clientPlayer.position(), Position3f.ZERO));
        }
    }

    private static final class FixtureClientPlayerEntity extends ClientPlayerEntity {

        private Set<Relative> positionSyncRelatives;
        private PacketWrapper lastPositionPacket;
        private final List<String> events = new ArrayList<>();

        FixtureClientPlayerEntity(
                final UserConnection user,
                final long runtimeId,
                final UUID javaUuid,
                final PlayerAbilities abilities
        ) {
            super(user, runtimeId, javaUuid, abilities);
        }

        @Override
        public void sendPlayerPositionPacketToClient(final Set<Relative> relatives) {
            this.positionSyncRelatives = relatives;
            this.lastPositionPacket = new PacketWrapperImpl(ClientboundPackets26_1.PLAYER_POSITION, null, this.user);
            this.writePlayerPositionPacketToClient(this.lastPositionPacket, relatives, true);
            this.events.add("position");
        }

        Set<Relative> positionSyncRelatives() {
            return this.positionSyncRelatives;
        }
    }

    private static final class FixtureEntityTracker extends EntityTracker {

        private final ClientPlayerEntity clientPlayer;
        private final Map<Long, Entity> vehicles = new HashMap<>();

        FixtureEntityTracker(final UserConnection user, final ClientPlayerEntity clientPlayer, final Entity boat) {
            super(user);
            this.clientPlayer = clientPlayer;
            this.vehicles.put(boat.uniqueId(), boat);
        }

        @Override
        public ClientPlayerEntity getClientPlayer() {
            return this.clientPlayer;
        }

        @Override
        public Entity getEntityByUid(final long uniqueId) {
            if (uniqueId == this.clientPlayer.uniqueId()) {
                return this.clientPlayer;
            }
            return this.vehicles.get(uniqueId);
        }
    }

}
