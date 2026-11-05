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
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.Position3f;

import java.util.List;
import java.util.UUID;

public final class RidingAnchorHelper {

    private RidingAnchorHelper() {
    }

    public static void spawn(final UserConnection user, final int javaId, final UUID uuid, final Position3f position) {
        final PacketWrapper addEntity = PacketWrapper.create(ClientboundPackets26_1.ADD_ENTITY, user);
        addEntity.write(Types.VAR_INT, javaId); // 实体 ID
        addEntity.write(Types.UUID, uuid); // UUID
        addEntity.write(Types.VAR_INT, EntityTypes1_21_11.TEXT_DISPLAY.getId()); // 类型 ID
        addEntity.write(Types.DOUBLE, (double) position.x()); // x
        addEntity.write(Types.DOUBLE, (double) position.y()); // y
        addEntity.write(Types.DOUBLE, (double) position.z()); // z
        addEntity.write(Types.MOVEMENT_VECTOR, Vector3d.ZERO); // 速度
        addEntity.write(Types.BYTE, (byte) 0); // 俯仰角
        addEntity.write(Types.BYTE, (byte) 0); // 偏航角
        addEntity.write(Types.BYTE, (byte) 0); // 头部偏航角
        addEntity.write(Types.VAR_INT, 0); // 数据
        addEntity.send(BedrockProtocol.class);

        // 挂点使用原版三 tick 位置插值，乘客随挂点平滑移动。
        final int interpolationIndex = BedrockProtocol.MAPPINGS.getJavaEntityDataFields()
                .get(EntityTypes1_21_11.TEXT_DISPLAY).indexOf(EntityDataFields.POS_ROT_INTERPOLATION_DURATION);
        if (interpolationIndex == -1) {
            throw new IllegalStateException("Unknown TEXT_DISPLAY entity data field: " + EntityDataFields.POS_ROT_INTERPOLATION_DURATION);
        }
        final PacketWrapper entityData = PacketWrapper.create(ClientboundPackets26_1.SET_ENTITY_DATA, user);
        entityData.write(Types.VAR_INT, javaId);
        entityData.write(VersionedTypes.V26_1.entityDataList, List.of(
                new EntityData(interpolationIndex, VersionedTypes.V26_1.entityDataTypes.varIntType, 3)));
        entityData.send(BedrockProtocol.class);
    }

    public static void move(final UserConnection user, final int javaId, final Position3f position, final Position3f rotation, final boolean onGround) {
        final PacketWrapper move = PacketWrapper.create(ClientboundPackets26_1.ENTITY_POSITION_SYNC, user);
        move.write(Types.VAR_INT, javaId); // 实体 ID
        move.write(Types.DOUBLE, (double) position.x()); // x
        move.write(Types.DOUBLE, (double) position.y()); // y
        move.write(Types.DOUBLE, (double) position.z()); // z
        move.write(Types.DOUBLE, 0D); // 速度 x
        move.write(Types.DOUBLE, 0D); // 速度 y
        move.write(Types.DOUBLE, 0D); // 速度 z
        move.write(Types.FLOAT, rotation.y()); // 偏航角
        move.write(Types.FLOAT, rotation.x()); // 俯仰角
        move.write(Types.BOOLEAN, onGround); // 在地面上
        move.send(BedrockProtocol.class);
    }

    /**
     * 把本地控制的 JE 载具（船/马等）拉回权威姿态。
     * 服务端→客户端的 {@code MOVE_VEHICLE} 更新的正是被骑乘的载具本身；单纯的
     * {@code ENTITY_POSITION_SYNC} 会被 JE 本地的船浮力逻辑忽略或覆盖。
     */
    public static void moveVehicle(final UserConnection user, final Position3f position, final float yaw, final float pitch) {
        final PacketWrapper move = PacketWrapper.create(ClientboundPackets26_1.MOVE_VEHICLE, user);
        move.write(Types.DOUBLE, (double) position.x()); // x
        move.write(Types.DOUBLE, (double) position.y()); // y
        move.write(Types.DOUBLE, (double) position.z()); // z
        move.write(Types.FLOAT, yaw); // 偏航角
        move.write(Types.FLOAT, pitch); // 俯仰角
        move.send(BedrockProtocol.class);
    }

    public static void rotatePassenger(final UserConnection user, final int javaId, final Position3f rotation, final boolean onGround) {
        final PacketWrapper rotate = PacketWrapper.create(ClientboundPackets26_1.MOVE_ENTITY_ROT, user);
        rotate.write(Types.VAR_INT, javaId);
        rotate.write(Types.BYTE, MathUtil.float2Byte(rotation.y()));
        rotate.write(Types.BYTE, MathUtil.float2Byte(rotation.x()));
        rotate.write(Types.BOOLEAN, onGround);
        rotate.send(BedrockProtocol.class);
    }

    public static void remove(final UserConnection user, final int javaId) {
        final PacketWrapper removeEntities = PacketWrapper.create(ClientboundPackets26_1.REMOVE_ENTITIES, user);
        removeEntities.write(Types.VAR_INT_ARRAY_PRIMITIVE, new int[]{javaId});
        removeEntities.send(BedrockProtocol.class);
    }

}
