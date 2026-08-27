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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.libs.fastutil.ints.IntArrayList;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.longs.LongArrayList;
import com.viaversion.viaversion.libs.fastutil.longs.LongList;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.experimental.riding.RidingAnchorHelper;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.data.enums.java.InputFlag;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.api.model.container.HorseContainer;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public class RidingTracker extends StoredObject {

    private static final byte LINK_REMOVE = 0;
    private static final byte LINK_RIDE = 1;
    private static final byte LINK_PASSENGER = 2;
    private static final ActorDataIDs SEAT_OFFSET_DATA = ActorDataIDs.RESERVED_056; // Synapse SEAT_OFFSET = 56
    private static final float JAVA_PLAYER_VEHICLE_ATTACHMENT_Y = 0.6F; // PlayerEntity.VEHICLE_ATTACHMENT_POS
    private static final int PENDING_DISMOUNT_TICKS = 10;
    private static final Position3f BOAT_PLAYER_SEAT_OFFSET = new Position3f(0F, 1.02001F, 0F);

    private final Long2ObjectMap<LongList> vehiclePassengers = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<AnchorState> anchorsByPassenger = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Position3f> seatOffsets = new Long2ObjectOpenHashMap<>();
    private Long localVehicleUniqueId;
    private boolean ridingShiftDown;
    private Set<InputFlag> lastInputFlags = EnumSet.noneOf(InputFlag.class);
    private MoveVehicleInput lastMoveVehicleInput;
    private boolean lastMoveVehicleInputFresh;
    private Long pendingDismountVehicleUniqueId;
    private int pendingDismountTicks;
    private Position3f lastSafeDismountPosition;

    public RidingTracker(final UserConnection user) {
        super(user);
    }

    public void resetForDimensionChange() {
        for (final long passengerUniqueId : this.anchorsByPassenger.keySet().toLongArray()) {
            this.removeAnchor(passengerUniqueId);
        }
        this.vehiclePassengers.clear();
        this.anchorsByPassenger.clear();
        this.seatOffsets.clear();
        this.clearLocalRiding();
    }

    boolean hasTrackedRidingState() {
        return !this.vehiclePassengers.isEmpty() || !this.anchorsByPassenger.isEmpty()
                || !this.seatOffsets.isEmpty() || this.localVehicleUniqueId != null
                || this.pendingDismountVehicleUniqueId != null;
    }

    public Entity localVehicle() {
        if (this.localVehicleUniqueId == null) {
            return null;
        }

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return null;
        }

        final Entity vehicle = entityTracker.getEntityByUid(this.localVehicleUniqueId);
        if (vehicle == null) {
            this.clearLocalRiding();
        }
        return vehicle;
    }

    public boolean updateRidingShift(final boolean shiftDown) {
        final boolean pressed = shiftDown && !this.ridingShiftDown;
        this.ridingShiftDown = shiftDown;
        return pressed;
    }

    public boolean isLocalRiding() {
        return this.localVehicle() != null;
    }

    public void setLastInputFlags(final Set<InputFlag> inputFlags) {
        this.lastInputFlags = inputFlags.isEmpty() ? EnumSet.noneOf(InputFlag.class) : EnumSet.copyOf(inputFlags);
    }

    public void setLastMoveVehicleInput(final double x, final double y, final double z, final float yaw, final float pitch, final boolean onGround) {
        this.lastMoveVehicleInput = new MoveVehicleInput(new Position3f((float) x, (float) y, (float) z), yaw, pitch, onGround);
        this.lastMoveVehicleInputFresh = true;
    }

    public void requestLocalDismount(final Entity vehicle) {
        if (this.localVehicleUniqueId == null || vehicle.uniqueId() != this.localVehicleUniqueId) {
            return;
        }

        this.pendingDismountVehicleUniqueId = vehicle.uniqueId();
        this.pendingDismountTicks = PENDING_DISMOUNT_TICKS;
    }

    public void applyAuthInput(final ClientPlayerEntity clientPlayer, final PlayerAuthInputContext context) {
        final Entity vehicle = this.localVehicle();
        if (vehicle == null) {
            return;
        }

        final LocalRidingMode mode = this.localRidingMode(vehicle, clientPlayer);
        this.removeRidingInputData(clientPlayer);

        final Position3f authInputPosition = this.authInputPosition(vehicle, clientPlayer, mode);
        final Position3f safeDismountPosition = this.safeDismountPosition(vehicle, clientPlayer, mode, authInputPosition);
        if (this.isPendingDismount(vehicle)) {
            context.setPosition(this.lastSafeDismountPosition != null ? this.lastSafeDismountPosition : safeDismountPosition);
            context.setDelta(Position3f.ZERO);
            this.tickPendingDismount();
            this.lastMoveVehicleInputFresh = false;
            return;
        }

        this.lastSafeDismountPosition = safeDismountPosition;
        context.setPosition(authInputPosition);
        context.setDelta(Position3f.ZERO);

        switch (mode) {
            case BOAT_PREDICTED -> {
                this.addMovementInputData(clientPlayer);
                this.addBoatPaddleInputData(clientPlayer);

                final MoveVehicleInput vehicleInput = this.lastMoveVehicleInputFresh ? this.lastMoveVehicleInput : null;
                final float vehiclePitch = vehicleInput != null ? vehicleInput.pitch() : vehicle.rotation().x();
                final float vehicleYaw = vehicleInput != null ? vehicleInput.yaw() : vehicle.rotation().y();
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.IsInClientPredictedVehicle);
                context.setPredictedVehicle(vehicle.uniqueId(), vehiclePitch, vehicleYaw);
            }
            case VIRTUAL_INPUT_ONLY -> this.addMovementInputData(clientPlayer);
        }

        this.lastMoveVehicleInputFresh = false;
    }

    public void handleLink(final EntityLink link) {
        final long vehicleUniqueId = link.fromEntityUniqueId();
        final long passengerUniqueId = link.toEntityUniqueId();
        final byte type = link.type();

        if (type == LINK_REMOVE) {
            this.removePassenger(vehicleUniqueId, passengerUniqueId);
            this.updateLocalVehicle(passengerUniqueId, null);
            return;
        }

        if (type != LINK_RIDE && type != LINK_PASSENGER) {
            return;
        }

        this.removePassengerFromOtherVehicles(passengerUniqueId, vehicleUniqueId);
        final LongList passengers = this.vehiclePassengers.computeIfAbsent(vehicleUniqueId, k -> new LongArrayList());
        updatePassengerOrder(passengers, passengerUniqueId, type);
        this.updateLocalVehicle(passengerUniqueId, vehicleUniqueId);
        this.refreshVehicle(vehicleUniqueId);
    }

    static void updatePassengerOrder(final LongList passengers, final long passengerUniqueId, final byte linkType) {
        if (linkType == LINK_RIDE) {
            passengers.rem(passengerUniqueId);
            passengers.add(0, passengerUniqueId);
        } else if (linkType == LINK_PASSENGER && !contains(passengers, passengerUniqueId)) {
            passengers.add(passengerUniqueId);
        }
    }

    public void onEntityAdded(final Entity entity) {
        this.updateSeatOffset(entity);
    }

    public void onEntityDataChanged(final Entity entity) {
        this.updateSeatOffset(entity);
        this.refreshVehicle(entity.uniqueId());
        for (final Long2ObjectMap.Entry<LongList> entry : this.vehiclePassengers.long2ObjectEntrySet()) {
            if (contains(entry.getValue(), entity.uniqueId())) {
                this.refreshVehicle(entry.getLongKey());
            }
        }
        final AnchorState anchor = this.anchorsByPassenger.get(entity.uniqueId());
        if (anchor != null) {
            this.refreshVehicle(anchor.vehicleUniqueId);
        }
    }

    public void onEntityMoved(final Entity entity) {
        this.refreshVehicle(entity.uniqueId());
    }

    public void onEntityRemoved(final Entity entity) {
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        final LongList passengers = this.vehiclePassengers.remove(entity.uniqueId());
        if (passengers != null) {
            for (int i = 0; i < passengers.size(); i++) {
                this.removeAnchor(passengers.getLong(i));
            }
            if (passengerTracker != null) {
                passengerTracker.clearVehicle(entity.javaId());
            }
        }

        this.removeAnchor(entity.uniqueId());
        this.seatOffsets.remove(entity.uniqueId());

        final LongList changedVehicles = new LongArrayList();
        for (final Long2ObjectMap.Entry<LongList> entry : this.vehiclePassengers.long2ObjectEntrySet()) {
            if (entry.getValue().rem(entity.uniqueId())) {
                changedVehicles.add(entry.getLongKey());
            }
        }
        for (final long vehicleUniqueId : changedVehicles) {
            final LongList vehiclePassengers = this.vehiclePassengers.get(vehicleUniqueId);
            if (vehiclePassengers != null && vehiclePassengers.isEmpty()) {
                this.vehiclePassengers.remove(vehicleUniqueId);
            }
            this.refreshVehicle(vehicleUniqueId);
        }

        if (this.localVehicleUniqueId != null && (this.localVehicleUniqueId == entity.uniqueId() || isLocalPlayer(entity))) {
            this.clearLocalRiding();
        }
    }

    private void removePassenger(final long vehicleUniqueId, final long passengerUniqueId) {
        final LongList passengers = this.vehiclePassengers.get(vehicleUniqueId);
        if (passengers != null) {
            passengers.rem(passengerUniqueId);
            if (passengers.isEmpty()) {
                this.vehiclePassengers.remove(vehicleUniqueId);
            }
        }
        this.removeAnchor(passengerUniqueId);
        this.refreshVehicle(vehicleUniqueId);
    }

    private void refreshVehicle(final long vehicleUniqueId) {
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (passengerTracker == null || entityTracker == null) {
            return;
        }

        final Entity vehicle = entityTracker.getEntityByUid(vehicleUniqueId);
        if (vehicle == null) {
            return;
        }

        final LongList passengerUids = this.vehiclePassengers.get(vehicleUniqueId);
        if (passengerUids == null || passengerUids.isEmpty()) {
            passengerTracker.setBedrockPassengers(vehicle.javaId());
            return;
        }

        final boolean usesVanillaRiding = usesVanillaRiding(vehicle.javaType());
        final IntArrayList directPassengerJavaIds = new IntArrayList(passengerUids.size());
        for (int i = 0; i < passengerUids.size(); i++) {
            final long passengerUniqueId = passengerUids.getLong(i);
            final Entity passenger = entityTracker.getEntityByUid(passengerUniqueId);
            if (passenger != null && (usesVanillaRiding || this.canRideDirectly(vehicle, passenger))) {
                this.removeAnchor(passengerUniqueId);
                directPassengerJavaIds.add(passenger.javaId());
            }
        }
        passengerTracker.setBedrockPassengers(vehicle.javaId(), directPassengerJavaIds.toIntArray());
        if (usesVanillaRiding) {
            return;
        }

        for (int i = 0; i < passengerUids.size(); i++) {
            final Entity passenger = entityTracker.getEntityByUid(passengerUids.getLong(i));
            if (passenger != null && !this.canRideDirectly(vehicle, passenger)) {
                this.ensureAnchor(vehicle, passenger);
            }
        }
    }

    private void ensureAnchor(final Entity vehicle, final Entity passenger) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (entityTracker == null || passengerTracker == null) {
            return;
        }

        AnchorState anchor = this.anchorsByPassenger.get(passenger.uniqueId());
        if (anchor == null) {
            anchor = new AnchorState(
                    entityTracker.getNextJavaEntityId(),
                    UUID.nameUUIDFromBytes(("viabedrock:riding-anchor:" + passenger.uniqueId()).getBytes(StandardCharsets.UTF_8)),
                    vehicle.uniqueId());
            this.anchorsByPassenger.put(passenger.uniqueId(), anchor);
        }
        anchor.vehicleUniqueId = vehicle.uniqueId();

        final Position3f rawOffset = this.rawSeatOffset(passenger);
        final float anchorYOffset = this.passengerAnchorYOffset(passenger);
        final Position3f position = vehicle.position().add(this.seatOffset(vehicle, passenger, rawOffset, anchorYOffset));
        if (!anchor.spawned) {
            RidingAnchorHelper.spawn(this.user(), anchor.javaId, anchor.uuid, position);
            anchor.spawned = true;
        }
        passengerTracker.setBedrockPassengers(anchor.javaId, passenger.javaId());
        // Force the client to recalculate the passenger position after the relation and zero-height anchor data arrive.
        RidingAnchorHelper.move(this.user(), anchor.javaId, position, vehicle.rotation(), vehicle.isOnGround());
    }

    private void removeAnchor(final long passengerUniqueId) {
        final AnchorState anchor = this.anchorsByPassenger.remove(passengerUniqueId);
        if (anchor == null || !anchor.spawned) {
            return;
        }

        final JavaPassengerTracker passengerTracker = this.user().get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.clearVehicle(anchor.javaId);
        }
        RidingAnchorHelper.remove(this.user(), anchor.javaId);
    }

    private void removePassengerFromOtherVehicles(final long passengerUniqueId, final long newVehicleUniqueId) {
        final LongList changedVehicles = new LongArrayList();
        for (final Long2ObjectMap.Entry<LongList> entry : this.vehiclePassengers.long2ObjectEntrySet()) {
            if (entry.getLongKey() != newVehicleUniqueId && entry.getValue().rem(passengerUniqueId)) {
                changedVehicles.add(entry.getLongKey());
            }
        }
        for (final long vehicleUniqueId : changedVehicles) {
            final LongList passengers = this.vehiclePassengers.get(vehicleUniqueId);
            if (passengers != null && passengers.isEmpty()) {
                this.vehiclePassengers.remove(vehicleUniqueId);
            }
            this.refreshVehicle(vehicleUniqueId);
        }
    }

    private void updateSeatOffset(final Entity entity) {
        final EntityData data = entity.entityData().get(SEAT_OFFSET_DATA);
        if (data != null && data.getValue() instanceof Position3f offset) {
            this.seatOffsets.put(entity.uniqueId(), offset);
        }
    }

    private Position3f rawSeatOffset(final Entity passenger) {
        final Position3f offset = this.seatOffsets.get(passenger.uniqueId());
        if (offset == null) {
            return Position3f.ZERO;
        }
        return offset;
    }

    private boolean canRideDirectly(final Entity vehicle, final Entity passenger) {
        if (!this.isLocalPlayer(vehicle)) {
            return false;
        }

        final Position3f offset = this.rawSeatOffset(passenger);
        return offset.x() == 0F && offset.y() == 0F && offset.z() == 0F;
    }

    private Position3f seatOffset(final Entity vehicle, final Entity passenger, final Position3f offset, final float anchorYOffset) {
        // Bedrock seat offsets are local to the vehicle; anchors need absolute Java coordinates.
        final double yaw = Math.toRadians(vehicle.rotation().y());
        final float sin = (float) Math.sin(yaw);
        final float cos = (float) Math.cos(yaw);
        final float y = offset.y() + anchorYOffset;
        return new Position3f(
                offset.x() * cos - offset.z() * sin,
                y,
                offset.x() * sin + offset.z() * cos);
    }

    private float passengerAnchorYOffset(final Entity passenger) {
        if (passenger instanceof PlayerEntity) {
            // Bedrock player positions are network/base-offset coordinates (Nukkit EntityHuman#getBaseOffset = 1.62).
            // Java then subtracts the player's vehicle attachment from the anchor when applying SET_PASSENGERS.
            return JAVA_PLAYER_VEHICLE_ATTACHMENT_Y - passenger.eyeOffset();
        }
        return 0F;
    }

    private Position3f authInputPosition(final Entity vehicle, final ClientPlayerEntity clientPlayer, final LocalRidingMode mode) {
        if (mode == LocalRidingMode.BOAT_PREDICTED) {
            // MOT 860 still uses IN_CLIENT_PREDICTED_IN_VEHICLE + EntityBoat.onInput(x,y,z,yaw).
            // JE MOVE_VEHICLE Y follows client boat buoyancy and climbs each tick; feeding that Y
            // back through SAI makes MOT rewrite the hull higher and higher (#1-2 continuous lift).
            // Keep XZ/yaw from MOVE_VEHICLE for steering, but pin Y to the MOT boat network height.
            final Position3f vehicleNetworkPosition = vehicle.position();
            if (this.lastMoveVehicleInputFresh && this.lastMoveVehicleInput != null) {
                return predictedBoatAuthInputPosition(
                        this.lastMoveVehicleInput.position(),
                        vehicleNetworkPosition,
                        vehicle.eyeOffset());
            }
            return predictedBoatAuthInputFromVehicle(vehicleNetworkPosition, vehicle.eyeOffset());
        }

        final Position3f vehiclePosition = vehicle.position();
        if (mode == LocalRidingMode.VIRTUAL_INPUT_ONLY || mode == LocalRidingMode.PASSENGER_ONLY) {
            // MOT SAI riding subtracts riding.getMountedOffset().y (horse 1.2), not player 1.62.
            // Writing vehicle.y + eyeOffset drops the seat and lands ~0.78 below the passenger
            // foot, which trips GanAC AntiVehicle.FlyCheck (0.5). Match VIRTUAL_INPUT_ONLY /
            // safeDismountPosition: vehicle + seat + player eye.
            // Ref: MOT Player.java clientPosition; Entity.getMountedOffset; EntityHorse height 1.6.
            return passengerAuthInputPosition(
                    vehiclePosition,
                    this.seatOffset(vehicle, clientPlayer, this.rawSeatOffset(clientPlayer), 0F),
                    clientPlayer.eyeOffset());
        }

        return new Position3f(vehiclePosition.x(), vehiclePosition.y() + clientPlayer.eyeOffset(), vehiclePosition.z());
    }

    /**
     * PASSENGER_ONLY / VIRTUAL_INPUT_ONLY SAI Y is the passenger network position:
     * vehicle foot + seat offset + player {@code getBaseOffset()} (1.62). MOT then
     * subtracts {@code riding.getMountedOffset().y}.
     */
    static Position3f passengerAuthInputPosition(final Position3f vehiclePosition, final Position3f seatOffset, final float playerEyeOffset) {
        return new Position3f(
                vehiclePosition.x() + seatOffset.x(),
                vehiclePosition.y() + seatOffset.y() + playerEyeOffset,
                vehiclePosition.z() + seatOffset.z());
    }

    /**
     * Java {@code MOVE_VEHICLE} carries JE client XZ plus a buoyancy-affected Y. MOT predicted-boat
     * SAI must be the boat network Y ({@code EntityBoat.getBaseOffset()} = 0.375) because
     * {@code onInput} subtracts that offset. Adding the player eye (1.62), or feeding JE buoyancy
     * Y back through SAI, lifts the boat every tick and trips GanAC AntiVehicle.FlyCheck (0.5).
     * Ref: MOT Player.java IN_CLIENT_PREDICTED_IN_VEHICLE; EntityBoat.onInput.
     */
    static Position3f predictedBoatAuthInputPosition(final Position3f javaVehiclePosition, final float vehicleEyeOffset) {
        return new Position3f(
                javaVehiclePosition.x(),
                javaVehiclePosition.y() + vehicleEyeOffset,
                javaVehiclePosition.z());
    }

    static Position3f predictedBoatAuthInputPosition(
            final Position3f javaVehiclePosition,
            final Position3f vehicleNetworkPosition,
            final float vehicleEyeOffset) {
        // vehicleEyeOffset kept for API symmetry with the foot-space helper; Y is intentionally
        // taken from the MOT network height so JE buoyancy cannot accumulate through onInput.
        return new Position3f(
                javaVehiclePosition.x(),
                predictedBoatNetworkY(vehicleNetworkPosition),
                javaVehiclePosition.z());
    }

    /**
     * MOT ADD/MOVE already stores the boat network Y (foot + {@code getBaseOffset()}). Convert
     * that tracker position back to the Java boat foot so helpers that still speak in JE foot
     * space stay consistent with spawn / MOVE sync.
     */
    static Position3f predictedBoatJavaFoot(final Position3f vehicleNetworkPosition, final float vehicleEyeOffset) {
        return new Position3f(
                vehicleNetworkPosition.x(),
                vehicleNetworkPosition.y() - vehicleEyeOffset,
                vehicleNetworkPosition.z());
    }

    static float predictedBoatNetworkY(final Position3f vehicleNetworkPosition) {
        // Tracker stores MOT ADD/MOVE network Y. Keep that value as SAI Y so onInput restores the
        // same server foot instead of accumulating JE buoyancy deltas.
        return vehicleNetworkPosition.y();
    }

    static Position3f predictedBoatAuthInputFromVehicle(final Position3f vehicleNetworkPosition, final float vehicleEyeOffset) {
        return predictedBoatAuthInputPosition(predictedBoatJavaFoot(vehicleNetworkPosition, vehicleEyeOffset), vehicleEyeOffset);
    }

    private Position3f safeDismountPosition(final Entity vehicle, final ClientPlayerEntity clientPlayer, final LocalRidingMode mode, final Position3f authInputPosition) {
        if (mode == LocalRidingMode.BOAT_PREDICTED) {
            return authInputPosition.add(this.seatOffset(vehicle, clientPlayer, this.boatMountedOffset(clientPlayer), 0F));
        }

        final Position3f vehiclePosition = vehicle.position();
        final Position3f seatPosition = vehiclePosition.add(this.seatOffset(vehicle, clientPlayer, this.rawSeatOffset(clientPlayer), 0F));
        return new Position3f(seatPosition.x(), seatPosition.y() + clientPlayer.eyeOffset(), seatPosition.z());
    }

    private Position3f boatMountedOffset(final ClientPlayerEntity clientPlayer) {
        final Position3f offset = this.rawSeatOffset(clientPlayer);
        if (offset == Position3f.ZERO || offset.x() == 0F && offset.y() == 0F && offset.z() == 0F) {
            return BOAT_PLAYER_SEAT_OFFSET;
        }
        return offset;
    }

    private LocalRidingMode localRidingMode(final Entity vehicle, final ClientPlayerEntity clientPlayer) {
        return localRidingMode(vehicle.javaType(), this.isControllingPassenger(vehicle.uniqueId(), clientPlayer.uniqueId()));
    }

    static LocalRidingMode localRidingMode(final EntityTypes1_21_11 type, final boolean controllingPassenger) {
        if (!controllingPassenger) {
            return LocalRidingMode.PASSENGER_ONLY;
        }
        if (usesBoatRiding(type)) {
            return LocalRidingMode.BOAT_PREDICTED;
        }
        if (type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_MINECART)
                || type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_HORSE)
                || type == EntityTypes1_21_11.PIG
                || type == EntityTypes1_21_11.STRIDER
                || !usesVanillaRiding(type)) {
            return LocalRidingMode.VIRTUAL_INPUT_ONLY;
        }
        return LocalRidingMode.PASSENGER_ONLY;
    }

    private boolean isControllingPassenger(final long vehicleUniqueId, final long passengerUniqueId) {
        final LongList passengers = this.vehiclePassengers.get(vehicleUniqueId);
        return passengers != null && !passengers.isEmpty() && passengers.getLong(0) == passengerUniqueId;
    }

    private void addMovementInputData(final ClientPlayerEntity clientPlayer) {
        if (this.lastInputFlags.contains(InputFlag.FORWARD)) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Up);
        }
        if (this.lastInputFlags.contains(InputFlag.BACKWARD)) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Down);
        }
        if (this.lastInputFlags.contains(InputFlag.LEFT)) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Left);
        }
        if (this.lastInputFlags.contains(InputFlag.RIGHT)) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Right);
        }
        if (this.lastInputFlags.contains(InputFlag.JUMP)) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.JumpDown, PlayerAuthInputPacket_InputData.Jumping, PlayerAuthInputPacket_InputData.WantUp, PlayerAuthInputPacket_InputData.JumpCurrentRaw);
        }
        if (this.lastInputFlags.contains(InputFlag.SHIFT)) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakDown, PlayerAuthInputPacket_InputData.Sneaking, PlayerAuthInputPacket_InputData.WantDown, PlayerAuthInputPacket_InputData.SneakCurrentRaw);
        }
    }

    private void addBoatPaddleInputData(final ClientPlayerEntity clientPlayer) {
        final boolean left = this.lastInputFlags.contains(InputFlag.LEFT);
        final boolean right = this.lastInputFlags.contains(InputFlag.RIGHT);
        if (!left && !right && (this.lastInputFlags.contains(InputFlag.FORWARD) || this.lastInputFlags.contains(InputFlag.BACKWARD))) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.PaddlingLeft, PlayerAuthInputPacket_InputData.PaddlingRight);
            return;
        }

        // Bedrock paddle flags describe the oar being used: row right to turn left, row left to turn right.
        if (left) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.PaddlingRight);
        }
        if (right) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.PaddlingLeft);
        }
    }

    private void removeRidingInputData(final ClientPlayerEntity clientPlayer) {
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.Up);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.Down);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.Left);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.Right);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.JumpDown);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.Jumping);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.WantUp);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.JumpCurrentRaw);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.SneakDown);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.Sneaking);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.WantDown);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.SneakCurrentRaw);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.PaddlingLeft);
        clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.PaddlingRight);
    }

    private void updateLocalVehicle(final long passengerUniqueId, final Long vehicleUniqueId) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return;
        }

        final Entity clientPlayer = entityTracker.getClientPlayer();
        if (clientPlayer != null && clientPlayer.uniqueId() == passengerUniqueId) {
            if (vehicleUniqueId == null) {
                this.clearLocalRiding();
                return;
            }

            this.localVehicleUniqueId = vehicleUniqueId;
            this.clearPendingDismount();
            this.lastMoveVehicleInput = null;
            this.lastMoveVehicleInputFresh = false;
        }
    }

    private void clearLocalRiding() {
        this.closeLocalHorseContainer();
        this.localVehicleUniqueId = null;
        this.ridingShiftDown = false;
        this.lastInputFlags = EnumSet.noneOf(InputFlag.class);
        this.lastMoveVehicleInput = null;
        this.lastMoveVehicleInputFresh = false;
        this.lastSafeDismountPosition = null;
        this.clearPendingDismount();
    }

    /**
     * MOT HorseInventory never sends CONTAINER_CLOSE on dismount; it only
     * broadcasts SET_ENTITY_LINK type 0. Keep the JE mount screen until unlink.
     * Ref: MOT Entity.dismountEntity / HorseInventory.onClose.
     */
    private void closeLocalHorseContainer() {
        final InventoryTracker inventoryTracker = this.user().get(InventoryTracker.class);
        if (inventoryTracker == null) {
            return;
        }
        if (!(inventoryTracker.getCurrentContainer() instanceof HorseContainer horse)) {
            return;
        }
        if (this.localVehicleUniqueId != null && horse.entityUniqueId() != this.localVehicleUniqueId) {
            return;
        }
        inventoryTracker.forceCloseCurrentContainer();
    }

    private boolean isPendingDismount(final Entity vehicle) {
        return this.pendingDismountVehicleUniqueId != null && this.pendingDismountVehicleUniqueId == vehicle.uniqueId();
    }

    private void tickPendingDismount() {
        if (this.pendingDismountVehicleUniqueId != null && --this.pendingDismountTicks <= 0) {
            this.clearPendingDismount();
        }
    }

    private void clearPendingDismount() {
        this.pendingDismountVehicleUniqueId = null;
        this.pendingDismountTicks = 0;
    }

    private boolean isLocalPlayer(final Entity entity) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return false;
        }

        final Entity clientPlayer = entityTracker.getClientPlayer();
        return clientPlayer != null && clientPlayer == entity;
    }

    private static boolean usesVanillaRiding(final EntityTypes1_21_11 type) {
        return usesBoatRiding(type)
                || type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_HORSE)
                || type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_MINECART)
                || type == EntityTypes1_21_11.PIG
                || type == EntityTypes1_21_11.STRIDER;
    }

    private static boolean usesBoatRiding(final EntityTypes1_21_11 type) {
        return type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_BOAT);
    }

    private static boolean contains(final LongList list, final long value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.getLong(i) == value) {
                return true;
            }
        }
        return false;
    }

    private static final class AnchorState {
        final int javaId;
        final UUID uuid;
        long vehicleUniqueId;
        boolean spawned;

        AnchorState(final int javaId, final UUID uuid, final long vehicleUniqueId) {
            this.javaId = javaId;
            this.uuid = uuid;
            this.vehicleUniqueId = vehicleUniqueId;
        }
    }

    private record MoveVehicleInput(Position3f position, float yaw, float pitch, boolean onGround) {
    }

    enum LocalRidingMode {
        BOAT_PREDICTED,
        VIRTUAL_INPUT_ONLY,
        PASSENGER_ONLY
    }

}
