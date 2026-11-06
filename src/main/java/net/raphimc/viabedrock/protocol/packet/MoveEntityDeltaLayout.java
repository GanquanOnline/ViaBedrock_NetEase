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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 {@code MOVE_ENTITY_DELTA}（0x6F / 111 号包）的读取布局。
 * <p>
 * 2168 之前用 16 位标志掩码决定哪些轴带载荷；2168 起改成逐轴存在性 bool 与载荷交替出现，
 * 尾部换成四个独立 bool（{@code onGround} / {@code forceMove} / {@code forceMoveLocalEntity} /
 * {@code forceCompletion}），旧掩码的 {@code FLAG_TELEPORTING} 位不再存在。
 * <p>
 * 布局取自 Nukkit-MOT {@code MoveEntityDeltaPacket.decode()} 与 {@code encode()} 的
 * {@code protocol >= v1_26_40} 分支，两侧字段顺序一致。
 * <p>
 * 2192 在尾部再追加一个 unsigned var long（ticks）；本仓没有 2192 档案，这里不读。
 */
public final class MoveEntityDeltaLayout {

    public static final int FLAG_HAS_X = 1;
    public static final int FLAG_HAS_Y = 2;
    public static final int FLAG_HAS_Z = 4;
    public static final int FLAG_HAS_PITCH = 8;
    public static final int FLAG_HAS_YAW = 16;
    public static final int FLAG_HAS_HEAD_YAW = 32;
    public static final int FLAG_ON_GROUND = 64;
    public static final int FLAG_TELEPORTING = 128;
    public static final int FLAG_FORCE_MOVE_LOCAL_ENTITY = 256;

    private MoveEntityDeltaLayout() {
    }

    /**
     * 读取结果。{@code flags} 在 2168 也照旧填充，调用方可以继续按旧掩码查询。
     */
    public record Delta(long entityRuntimeId, int flags, float x, float y, float z,
                        float pitch, float yaw, float headYaw, boolean onGround,
                        boolean forceMove, boolean forceMoveLocalEntity, boolean forceCompletion) {

        public boolean has(final int flag) {
            return (this.flags & flag) != 0;
        }
    }

    /** @return true 表示坐标用逐轴存在性 bool，而不是标志位掩码 */
    public static boolean usesAxisBooleans(final int protocol) {
        return BedrockWire.applies(protocol, BedrockDeviation.MOVE_ENTITY_DELTA_AXIS_BOOLS);
    }

    public static Delta read(final PacketWrapper wrapper, final int protocol) {
        final long entityRuntimeId = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
        if (usesAxisBooleans(protocol)) {
            int flags = 0;
            if (wrapper.read(Types.BOOLEAN)) {
                flags |= FLAG_HAS_X;
            }
            final float x = (flags & FLAG_HAS_X) != 0 ? wrapper.read(BedrockTypes.FLOAT_LE) : 0F;
            if (wrapper.read(Types.BOOLEAN)) {
                flags |= FLAG_HAS_Y;
            }
            final float y = (flags & FLAG_HAS_Y) != 0 ? wrapper.read(BedrockTypes.FLOAT_LE) : 0F;
            if (wrapper.read(Types.BOOLEAN)) {
                flags |= FLAG_HAS_Z;
            }
            final float z = (flags & FLAG_HAS_Z) != 0 ? wrapper.read(BedrockTypes.FLOAT_LE) : 0F;
            if (wrapper.read(Types.BOOLEAN)) {
                flags |= FLAG_HAS_PITCH;
            }
            final float pitch = (flags & FLAG_HAS_PITCH) != 0 ? MathUtil.byte2Float(wrapper.read(Types.BYTE)) : 0F;
            if (wrapper.read(Types.BOOLEAN)) {
                flags |= FLAG_HAS_YAW;
            }
            final float yaw = (flags & FLAG_HAS_YAW) != 0 ? MathUtil.byte2Float(wrapper.read(Types.BYTE)) : 0F;
            if (wrapper.read(Types.BOOLEAN)) {
                flags |= FLAG_HAS_HEAD_YAW;
            }
            final float headYaw = (flags & FLAG_HAS_HEAD_YAW) != 0 ? MathUtil.byte2Float(wrapper.read(Types.BYTE)) : 0F;
            final boolean onGround = wrapper.read(Types.BOOLEAN);
            if (onGround) {
                flags |= FLAG_ON_GROUND;
            }
            final boolean forceMove = wrapper.read(Types.BOOLEAN); // 服务端强制移动，本转译层不区分
            final boolean forceMoveLocalEntity = wrapper.read(Types.BOOLEAN);
            if (forceMoveLocalEntity) {
                flags |= FLAG_FORCE_MOVE_LOCAL_ENTITY;
            }
            final boolean forceCompletion = wrapper.read(Types.BOOLEAN);
            return new Delta(entityRuntimeId, flags, x, y, z, pitch, yaw, headYaw, onGround,
                    forceMove, forceMoveLocalEntity, forceCompletion);
        }

        final int flags = wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE); // 标志位
        final float x = (flags & FLAG_HAS_X) != 0 ? wrapper.read(BedrockTypes.FLOAT_LE) : 0F;
        final float y = (flags & FLAG_HAS_Y) != 0 ? wrapper.read(BedrockTypes.FLOAT_LE) : 0F;
        final float z = (flags & FLAG_HAS_Z) != 0 ? wrapper.read(BedrockTypes.FLOAT_LE) : 0F;
        final float pitch = (flags & FLAG_HAS_PITCH) != 0 ? MathUtil.byte2Float(wrapper.read(Types.BYTE)) : 0F;
        final float yaw = (flags & FLAG_HAS_YAW) != 0 ? MathUtil.byte2Float(wrapper.read(Types.BYTE)) : 0F;
        final float headYaw = (flags & FLAG_HAS_HEAD_YAW) != 0 ? MathUtil.byte2Float(wrapper.read(Types.BYTE)) : 0F;
        return new Delta(entityRuntimeId, flags, x, y, z, pitch, yaw, headYaw, (flags & FLAG_ON_GROUND) != 0,
                false, (flags & FLAG_FORCE_MOVE_LOCAL_ENTITY) != 0, false);
    }

}
