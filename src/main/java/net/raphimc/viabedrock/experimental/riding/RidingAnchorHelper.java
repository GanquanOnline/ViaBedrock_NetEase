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
package net.raphimc.viabedrock.experimental.riding;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.model.Position3f;

import java.util.UUID;

public final class RidingAnchorHelper {

    private RidingAnchorHelper() {
    }

    public static void spawn(final UserConnection user, final int javaId, final UUID uuid, final Position3f position) {
        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, user);
        addEntity.write(Types.VAR_INT, javaId); // entity id
        addEntity.write(Types.UUID, uuid); // uuid
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.TEXT_DISPLAY.getId()); // type id
        addEntity.write(Types.DOUBLE, (double) position.x()); // x
        addEntity.write(Types.DOUBLE, (double) position.y()); // y
        addEntity.write(Types.DOUBLE, (double) position.z()); // z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // velocity
        addEntity.write(Types.BYTE, (byte) 0); // pitch
        addEntity.write(Types.BYTE, (byte) 0); // yaw
        addEntity.write(Types.BYTE, (byte) 0); // head yaw
        addEntity.write(Types.VAR_INT, 0); // data
        addEntity.send(BedrockProtocol.class);
    }

    public static void move(final UserConnection user, final int javaId, final Position3f position, final Position3f rotation, final boolean onGround) {
        final PacketWrapper move = PacketWrapper.create(ClientboundPackets26_1.ENTITY_POSITION_SYNC, user);
        move.write(Types.VAR_INT, javaId); // entity id
        move.write(Types.DOUBLE, (double) position.x()); // x
        move.write(Types.DOUBLE, (double) position.y()); // y
        move.write(Types.DOUBLE, (double) position.z()); // z
        move.write(Types.DOUBLE, 0D); // velocity x
        move.write(Types.DOUBLE, 0D); // velocity y
        move.write(Types.DOUBLE, 0D); // velocity z
        move.write(Types.FLOAT, rotation.y()); // yaw
        move.write(Types.FLOAT, rotation.x()); // pitch
        move.write(Types.BOOLEAN, onGround); // on ground
        move.send(BedrockProtocol.class);
    }

    /**
     * Snap the locally controlled JE vehicle (boat/horse/etc.) back to an authoritative pose.
     * Clientbound {@code MOVE_VEHICLE} updates the ridden vehicle itself; plain
     * {@code ENTITY_POSITION_SYNC} is ignored/overwritten by JE local boat buoyancy.
     */
    public static void moveVehicle(final UserConnection user, final Position3f position, final float yaw, final float pitch) {
        final PacketWrapper move = PacketWrapper.create(ClientboundPackets26_1.MOVE_VEHICLE, user);
        move.write(Types.DOUBLE, (double) position.x()); // x
        move.write(Types.DOUBLE, (double) position.y()); // y
        move.write(Types.DOUBLE, (double) position.z()); // z
        move.write(Types.FLOAT, yaw); // yaw
        move.write(Types.FLOAT, pitch); // pitch
        move.send(BedrockProtocol.class);
    }

    public static void remove(final UserConnection user, final int javaId) {
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, user);
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{javaId});
        removeEntities.send(BedrockProtocol.class);
    }

}
