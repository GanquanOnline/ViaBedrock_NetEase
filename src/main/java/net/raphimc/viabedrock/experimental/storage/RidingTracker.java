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
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.container.HorseContainer;
import net.raphimc.viabedrock.experimental.ItemUseAirClickTarget;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.experimental.riding.RidingAnchorHelper;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.data.enums.java.InputFlag;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
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
    // MOT EntityMinecartAbstract: getMountedOffset = height * 0.75 = 0.525. Off-rail adds +0.35 via seat metadata.
    // Visual sitting stays on JE's 0.1875 passenger attachment; this offset is SAI / dismount only.
    private static final Position3f MINECART_PLAYER_SEAT_OFFSET = new Position3f(0F, 0.525F, 0F);
    // MOT EntityBoat dimensions. Occupied boats disable wave sim, so ViaBedrock must derive a
    // resting network Y from the water surface instead of freezing the last tracker snapshot.
    private static final float BOAT_WIDTH = 1.4F;
    // Absolute no-op epsilon for tiny float noise when comparing visual vs sync target.
    private static final float BOAT_CLIENT_SYNC_EPSILON = 0.02F;
    // Soft vertical approach for SAI/tracker and for rare JE corrections.
    private static final float BOAT_VERTICAL_APPROACH_STEP = 0.045F;
    // Dead zone around the FINAL resting foot Y. Inside this band we do not send MOVE_VEHICLE,
    // otherwise JE buoyancy (~0.045/tick) fights our sync every few ticks and jitters.
    private static final float BOAT_JAVA_SYNC_TAKEOFF_MARGIN = 0.20F;
    private static final float BOAT_JAVA_SYNC_HOVER_MARGIN = 0.35F;
    // Reject continuous JE buoyancy climbs while still allowing waterfall / slope drops.
    private static final float BOAT_MAX_JAVA_CLIMB = 0.75F;

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
                // Soft-follow the water-aware SAI target. Snap JE only when the visual hull
                // drifted beyond the larger epsilon so MOVE_VEHICLE does not jitter every tick.
                // Keep the ViaBedrock tracker Y aligned with the approached network Y.
                this.syncPredictedBoatToJavaClient(vehicle, vehicleInput);
                vehicle.setPosition(new Position3f(authInputPosition.x(), authInputPosition.y(), authInputPosition.z()));
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

        final Position3f rawOffset = this.rawSeatOffset(vehicle, passenger);
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

    private Position3f rawSeatOffset(final Entity vehicle, final Entity passenger) {
        return defaultSeatOffset(vehicle.javaType(), this.rawSeatOffset(passenger));
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
            // Raw JE buoyancy Y climbs forever if echoed. Hard-pinning to the frozen MOT tracker Y
            // stopped the fly-up but left the hull hovering/jittering because occupied boats disable
            // MOT wave sim and never refresh network Y on slopes/waterfalls. Keep XZ/yaw from
            // MOVE_VEHICLE and choose a water-surface (or grounded) network Y instead.
            final Position3f vehicleNetworkPosition = vehicle.position();
            final Position3f javaVehiclePosition = this.lastMoveVehicleInputFresh && this.lastMoveVehicleInput != null
                    ? this.lastMoveVehicleInput.position()
                    : null;
            return predictedBoatAuthInputPosition(
                    javaVehiclePosition,
                    vehicleNetworkPosition,
                    vehicle.eyeOffset(),
                    this.boatWorldView());
        }

        final Position3f vehiclePosition = vehicle.position();
        if (mode == LocalRidingMode.VIRTUAL_INPUT_ONLY || mode == LocalRidingMode.PASSENGER_ONLY) {
            // MOT SAI riding subtracts riding.getMountedOffset().y (horse 1.2), not player 1.62.
            // Writing vehicle.y + eyeOffset drops the seat and lands ~0.78 below the passenger
            // foot, which trips GanAC AntiVehicle.FlyCheck (0.5). Match VIRTUAL_INPUT_ONLY /
            // safeDismountPosition: vehicle + seat + player eye.
            // Ref: MOT Player.java clientPosition; Entity.getMountedOffset; EntityHorse height 1.6.
            // Minecart network Y includes getBaseOffset (0.35); Bedrock player SAI is
            // internal foot + seat + player 1.62.
            final Position3f authVehiclePosition = usesMinecartRiding(vehicle.javaType())
                    ? new Position3f(vehiclePosition.x(), vehiclePosition.y() - vehicle.eyeOffset(), vehiclePosition.z())
                    : vehiclePosition;
            return passengerAuthInputPosition(
                    authVehiclePosition,
                    this.seatOffset(vehicle, clientPlayer, this.rawSeatOffset(vehicle, clientPlayer), 0F),
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
     * {@code onInput} subtracts that offset. Adding the player eye (1.62), or feeding raw JE
     * buoyancy Y back through SAI, lifts the boat every tick and trips GanAC AntiVehicle.FlyCheck.
     * Ref: MOT Player.java IN_CLIENT_PREDICTED_IN_VEHICLE; EntityBoat.onInput / getWaterLevel.
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
        return predictedBoatAuthInputPosition(javaVehiclePosition, vehicleNetworkPosition, vehicleEyeOffset, null);
    }

    static Position3f predictedBoatAuthInputPosition(
            final Position3f javaVehiclePosition,
            final Position3f vehicleNetworkPosition,
            final float vehicleEyeOffset,
            final ItemUseAirClickTarget.WorldView world) {
        final float networkY = predictedBoatNetworkY(javaVehiclePosition, vehicleNetworkPosition, vehicleEyeOffset, world);
        if (javaVehiclePosition == null) {
            return new Position3f(vehicleNetworkPosition.x(), networkY, vehicleNetworkPosition.z());
        }
        return new Position3f(javaVehiclePosition.x(), networkY, javaVehiclePosition.z());
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
        return vehicleNetworkPosition.y();
    }

    /**
     * Choose the SAI network Y for a predicted boat.
     * <p>
     * Priority:
     * <ol>
     *   <li>Chunk water surface under the steered XZ (occupied MOT boats disable wave sim, so the
     *       tracker Y never follows slopes / waterfalls by itself).</li>
     *   <li>Small, physically plausible JE MOVE_VEHICLE corrections around that surface.</li>
     *   <li>Last MOT tracker network Y when chunks are unloaded.</li>
     * </ol>
     * Large JE buoyancy climbs are rejected so #1-2 fly-up cannot accumulate through onInput.
     */
    static float predictedBoatNetworkY(
            final Position3f javaVehiclePosition,
            final Position3f vehicleNetworkPosition,
            final float vehicleEyeOffset,
            final ItemUseAirClickTarget.WorldView world) {
        return approachBoatNetworkY(
                vehicleNetworkPosition.y(),
                boatTargetNetworkY(javaVehiclePosition, vehicleNetworkPosition, vehicleEyeOffset, world));
    }

    /**
     * Final resting network Y (water surface / grounded), without the per-tick approach.
     * JE MOVE_VEHICLE must aim at this value; syncing to the intermediate approached Y was
     * fighting JE buoyancy every tick and caused the remaining water jitter.
     */
    static float boatTargetNetworkY(
            final Position3f javaVehiclePosition,
            final Position3f vehicleNetworkPosition,
            final float vehicleEyeOffset,
            final ItemUseAirClickTarget.WorldView world) {
        final float trackerNetworkY = vehicleNetworkPosition.y();
        final float sampleX = javaVehiclePosition != null ? javaVehiclePosition.x() : vehicleNetworkPosition.x();
        final float sampleZ = javaVehiclePosition != null ? javaVehiclePosition.z() : vehicleNetworkPosition.z();
        final float javaOrTrackerNetworkY = javaVehiclePosition != null ? javaVehiclePosition.y() + vehicleEyeOffset : trackerNetworkY;
        final Float waterNetworkY = resolveBoatWaterNetworkY(world, sampleX, trackerNetworkY, javaOrTrackerNetworkY, sampleZ);

        if (waterNetworkY != null) {
            return waterNetworkY;
        }

        final Float groundNetworkY = resolveBoatGroundNetworkY(world, sampleX, trackerNetworkY, javaOrTrackerNetworkY, sampleZ, vehicleEyeOffset);
        if (groundNetworkY != null) {
            // Same soft-follow path as water: land cliffs / slopes must not freeze at the old
            // mount snapshot or the hull hangs in the air.
            return groundNetworkY;
        }

        if (javaVehiclePosition != null) {
            final float javaNetworkY = javaOrTrackerNetworkY;
            final float delta = javaNetworkY - trackerNetworkY;
            // Chunks unloaded / no solid sample: always allow downward JE motion (cliffs), and
            // only small upward corrections. Large upward climbs are still the #1-2 fly-up.
            if (delta <= 0.12F) {
                return javaNetworkY;
            }
        }
        return trackerNetworkY;
    }

    static float approachBoatNetworkY(final float currentNetworkY, final float targetNetworkY) {
        final float delta = targetNetworkY - currentNetworkY;
        if (Math.abs(delta) <= BOAT_VERTICAL_APPROACH_STEP) {
            return targetNetworkY;
        }
        return currentNetworkY + Math.copySign(BOAT_VERTICAL_APPROACH_STEP, delta);
    }

    static boolean shouldSyncPredictedBoatJavaY(final float visualFootY, final float targetFootY) {
        final float dy = visualFootY - targetFootY;
        return dy > BOAT_JAVA_SYNC_TAKEOFF_MARGIN || dy < -BOAT_JAVA_SYNC_HOVER_MARGIN;
    }

    static float approachBoatJavaSyncFootY(final float visualFootY, final float targetFootY) {
        return approachBoatNetworkY(visualFootY, targetFootY);
    }

    static Position3f predictedBoatAuthInputFromVehicle(final Position3f vehicleNetworkPosition, final float vehicleEyeOffset) {
        return predictedBoatAuthInputPosition(predictedBoatJavaFoot(vehicleNetworkPosition, vehicleEyeOffset), vehicleEyeOffset);
    }

    /**
     * JE foot used to snap the local predicted boat. Keep MOVE_VEHICLE XZ/yaw for steering, and
     * use the same water-aware network Y that SAI will send so the visual hull and MOT onInput
     * stay locked together without fighting every tick.
     */
    static Position3f predictedBoatJavaSyncPosition(
            final Position3f javaMoveVehiclePosition,
            final Position3f vehicleNetworkPosition,
            final float vehicleEyeOffset) {
        return predictedBoatJavaSyncPosition(javaMoveVehiclePosition, vehicleNetworkPosition, vehicleEyeOffset, null);
    }

    static Position3f predictedBoatJavaSyncPosition(
            final Position3f javaMoveVehiclePosition,
            final Position3f vehicleNetworkPosition,
            final float vehicleEyeOffset,
            final ItemUseAirClickTarget.WorldView world) {
        final float targetNetworkY = boatTargetNetworkY(javaMoveVehiclePosition, vehicleNetworkPosition, vehicleEyeOffset, world);
        final float targetFootY = targetNetworkY - vehicleEyeOffset;
        if (javaMoveVehiclePosition == null) {
            // No fresh JE sample: keep XZ from the tracker and soft-follow the final foot target.
            final float footY = approachBoatJavaSyncFootY(vehicleNetworkPosition.y() - vehicleEyeOffset, targetFootY);
            return new Position3f(vehicleNetworkPosition.x(), footY, vehicleNetworkPosition.z());
        }
        final float footY = approachBoatJavaSyncFootY(javaMoveVehiclePosition.y(), targetFootY);
        return new Position3f(javaMoveVehiclePosition.x(), footY, javaMoveVehiclePosition.z());
    }

    /**
     * Sample water under the boat BB the same way MOT {@code EntityBoat.getWaterLevel()} does.
     * Resting network Y equals the highest water {@code maxY} under the hull
     * ({@code waterDiff = (footY + baseOffset) - waterMaxY ~= 0}).
     */
    /**
     * Sample solid ground under the boat BB. Resting network Y is {@code groundTop + eyeOffset}
     * (MOT boat foot sits on the block top; network Y adds {@code EntityBoat.getBaseOffset()}).
     */
    static Float resolveBoatGroundNetworkY(
            final ItemUseAirClickTarget.WorldView world,
            final float x,
            final float primarySeedNetworkY,
            final float secondarySeedNetworkY,
            final float z,
            final float vehicleEyeOffset) {
        if (world == null) {
            return null;
        }
        final float half = BOAT_WIDTH * 0.5F;
        final int minX = (int) Math.floor(x - half);
        final int maxX = (int) Math.floor(x + half);
        final int minZ = (int) Math.floor(z - half);
        final int maxZ = (int) Math.floor(z + half);
        final int minY = (int) Math.floor(Math.min(primarySeedNetworkY, secondarySeedNetworkY)) - 32;
        final int maxY = (int) Math.floor(Math.max(primarySeedNetworkY, secondarySeedNetworkY)) + 2;
        float bestTop = Float.NEGATIVE_INFINITY;
        boolean found = false;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = maxY; by >= minY; by--) {
                    if (isBoatPassableSupportBlock(world, bx, by, bz)) {
                        continue;
                    }
                    found = true;
                    final float top = by + 1F;
                    if (top > bestTop) {
                        bestTop = top;
                    }
                    break;
                }
            }
        }
        return found ? bestTop + vehicleEyeOffset : null;
    }

    static boolean isBoatPassableSupportBlock(final ItemUseAirClickTarget.WorldView world, final int x, final int y, final int z) {
        final BlockPosition pos = new BlockPosition(x, y, z);
        final int id = world.blockStateId(0, pos);
        if (id == world.airId()) {
            return true;
        }
        final BlockState state = world.blockState(id);
        if (state == null) {
            return false;
        }
        final String identifier = state.identifier();
        return "air".equals(identifier)
                || "water".equals(identifier)
                || "flowing_water".equals(identifier)
                || "lava".equals(identifier)
                || "flowing_lava".equals(identifier)
                || "short_grass".equals(identifier)
                || "tall_grass".equals(identifier)
                || "tallgrass".equals(identifier)
                || "double_plant".equals(identifier)
                || "snow_layer".equals(identifier)
                || "carpet".equals(identifier)
                || "reeds".equals(identifier)
                || "waterlily".equals(identifier)
                || "lily_pad".equals(identifier);
    }

    static Float resolveBoatWaterNetworkY(
            final ItemUseAirClickTarget.WorldView world,
            final float x,
            final float seedNetworkY,
            final float z) {
        return resolveBoatWaterNetworkY(world, x, seedNetworkY, seedNetworkY, z);
    }

    static Float resolveBoatWaterNetworkY(
            final ItemUseAirClickTarget.WorldView world,
            final float x,
            final float primarySeedNetworkY,
            final float secondarySeedNetworkY,
            final float z) {
        if (world == null) {
            return null;
        }
        final float half = BOAT_WIDTH * 0.5F;
        final int minX = (int) Math.floor(x - half);
        final int maxX = (int) Math.floor(x + half);
        final int minZ = (int) Math.floor(z - half);
        final int maxZ = (int) Math.floor(z + half);
        final int minY = (int) Math.floor(Math.min(primarySeedNetworkY, secondarySeedNetworkY)) - 8;
        final int maxY = (int) Math.floor(Math.max(primarySeedNetworkY, secondarySeedNetworkY)) + 8;
        float bestSurface = Float.NEGATIVE_INFINITY;
        boolean found = false;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                for (int by = maxY; by >= minY; by--) {
                    final Float surface = waterSurfaceMaxY(world, bx, by, bz);
                    if (surface == null) {
                        continue;
                    }
                    found = true;
                    if (surface > bestSurface) {
                        bestSurface = surface;
                    }
                    break;
                }
            }
        }
        return found ? bestSurface : null;
    }

    static Float waterSurfaceMaxY(final ItemUseAirClickTarget.WorldView world, final int x, final int y, final int z) {
        final BlockPosition pos = new BlockPosition(x, y, z);
        Float best = null;
        for (int layer = 0; layer <= 1; layer++) {
            final BlockState state = world.blockState(world.blockStateId(layer, pos));
            final Float surface = waterSurfaceMaxY(state, y);
            if (surface != null && (best == null || surface > best)) {
                best = surface;
            }
        }
        return best;
    }

    static Float waterSurfaceMaxY(final BlockState state, final int blockY) {
        if (state == null) {
            return null;
        }
        final String identifier = state.identifier();
        if (!"water".equals(identifier) && !"flowing_water".equals(identifier)) {
            return null;
        }
        int depth = 0;
        final String liquidDepth = state.properties().get("liquid_depth");
        if (liquidDepth != null) {
            try {
                depth = Integer.parseInt(liquidDepth);
            } catch (final NumberFormatException ignored) {
                depth = 0;
            }
        }
        // MOT BlockLiquid: damage >= 8 is falling water treated as full source for height percent.
        if (depth >= 8) {
            depth = 0;
        }
        final float heightPercent = (depth + 1) / 9F;
        return blockY + 1F - heightPercent;
    }

    private ItemUseAirClickTarget.WorldView boatWorldView() {
        final ChunkTracker chunkTracker = this.user().get(ChunkTracker.class);
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        if (chunkTracker == null || blockStateRewriter == null) {
            return null;
        }
        return new ItemUseAirClickTarget.WorldView() {
            @Override
            public int blockStateId(final int layer, final BlockPosition position) {
                return chunkTracker.getBlockState(layer, position);
            }

            @Override
            public BlockState blockState(final int bedrockBlockStateId) {
                return blockStateRewriter.blockState(bedrockBlockStateId);
            }

            @Override
            public int airId() {
                return chunkTracker.bedrockAirId();
            }
        };
    }

    private void syncPredictedBoatToJavaClient(final Entity vehicle, final MoveVehicleInput vehicleInput) {
        final ItemUseAirClickTarget.WorldView world = this.boatWorldView();
        final Position3f visual = vehicleInput != null ? vehicleInput.position() : null;
        final float targetNetworkY = boatTargetNetworkY(visual, vehicle.position(), vehicle.eyeOffset(), world);
        final float targetFootY = targetNetworkY - vehicle.eyeOffset();
        // Near the final resting height, leave JE alone. Syncing to the intermediate approached
        // tracker Y (or to the exact water foot every few buoyancy ticks) was the remaining jitter.
        if (visual != null && !shouldSyncPredictedBoatJavaY(visual.y(), targetFootY)) {
            return;
        }
        final Position3f javaPosition = predictedBoatJavaSyncPosition(visual, vehicle.position(), vehicle.eyeOffset(), world);
        final float yaw = vehicleInput != null ? vehicleInput.yaw() : vehicle.rotation().y();
        final float pitch = vehicleInput != null ? vehicleInput.pitch() : vehicle.rotation().x();
        if (visual != null
                && Math.abs(visual.x() - javaPosition.x()) < BOAT_CLIENT_SYNC_EPSILON
                && Math.abs(visual.y() - javaPosition.y()) < BOAT_CLIENT_SYNC_EPSILON
                && Math.abs(visual.z() - javaPosition.z()) < BOAT_CLIENT_SYNC_EPSILON) {
            return;
        }
        RidingAnchorHelper.moveVehicle(this.user(), javaPosition, yaw, pitch);
    }

    private Position3f safeDismountPosition(final Entity vehicle, final ClientPlayerEntity clientPlayer, final LocalRidingMode mode, final Position3f authInputPosition) {
        if (mode == LocalRidingMode.BOAT_PREDICTED) {
            return authInputPosition.add(this.seatOffset(vehicle, clientPlayer, this.boatMountedOffset(clientPlayer), 0F));
        }

        final Position3f vehiclePosition = usesMinecartRiding(vehicle.javaType())
                ? new Position3f(vehicle.position().x(), vehicle.position().y() - vehicle.eyeOffset(), vehicle.position().z())
                : vehicle.position();
        final Position3f seatPosition = vehiclePosition.add(this.seatOffset(vehicle, clientPlayer, this.rawSeatOffset(vehicle, clientPlayer), 0F));
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
        if (usesMinecartRiding(type)
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

    static boolean usesVanillaRiding(final EntityTypes1_21_11 type) {
        return usesBoatRiding(type)
                || type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_HORSE)
                || usesMinecartRiding(type)
                || type == EntityTypes1_21_11.PIG
                || type == EntityTypes1_21_11.STRIDER;
    }

    private static boolean usesBoatRiding(final EntityTypes1_21_11 type) {
        return type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_BOAT);
    }

    static boolean usesMinecartRiding(final EntityTypes1_21_11 type) {
        return type != null && type.isOrHasParent(EntityTypes1_21_11.ABSTRACT_MINECART);
    }

    static Position3f defaultSeatOffset(final EntityTypes1_21_11 type, final Position3f metadataOffset) {
        if (metadataOffset != null && (metadataOffset.x() != 0F || metadataOffset.y() != 0F || metadataOffset.z() != 0F)) {
            return metadataOffset;
        }
        if (usesMinecartRiding(type)) {
            return MINECART_PLAYER_SEAT_OFFSET;
        }
        return metadataOffset != null ? metadataOffset : Position3f.ZERO;
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
