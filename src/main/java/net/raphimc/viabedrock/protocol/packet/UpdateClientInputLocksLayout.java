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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 UPDATE_CLIENT_INPUT_LOCKS（196 号包）的线路布局辅助方法。
 * <p>
 * MOT {@code UpdateClientInputLocksPacket} 写入无符号 varint 位集，
 * 然后仅在 {@code protocol < 944} 时写入 {@code serverPosition}。官方 944+
 * 已将其移除，官方 860..943 与网易 860 一样仍带该向量。
 * <p>
 * MOT 用位 2 表示相机，位 4 表示移动（{@code InputLockType}）。
 */
public final class UpdateClientInputLocksLayout {

    public static final int SERVER_POSITION_REMOVED_PROTOCOL = 944;
    public static final int FLAG_CAMERA = 2;
    public static final int FLAG_MOVEMENT = 4;

    private UpdateClientInputLocksLayout() {
    }

    public static boolean usesServerPosition() {
        return usesServerPosition(BedrockWire.effectiveProtocol());
    }

    public static boolean usesServerPosition(final int protocol) {
        return protocol > 0 && protocol < SERVER_POSITION_REMOVED_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean usesServerPosition(final boolean emulateNetEase, final int protocol) {
        return usesServerPosition(protocol);
    }

    public static DecodedLocks read(final PacketWrapper wrapper) {
        return read(wrapper, BedrockWire.effectiveProtocol());
    }

    public static DecodedLocks read(final PacketWrapper wrapper, final int protocol) {
        return read(wrapper, false, protocol);
    }

    public static DecodedLocks read(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        final int lockComponentData = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        Position3f serverPosition = null;
        if (usesServerPosition(emulateNetEase, protocol)) {
            serverPosition = wrapper.read(BedrockTypes.POSITION_3F);
        }
        return new DecodedLocks(lockComponentData, serverPosition);
    }

    public static DecodedLocks read(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final int lockComponentData = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        Position3f serverPosition = null;
        if (usesServerPosition(emulateNetEase, protocol)) {
            serverPosition = BedrockTypes.POSITION_3F.read(buffer);
        }
        return new DecodedLocks(lockComponentData, serverPosition);
    }

    public static void write(final ByteBuf buffer, final int lockComponentData, final Position3f serverPosition,
                             final boolean emulateNetEase, final int protocol) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, lockComponentData);
        if (usesServerPosition(emulateNetEase, protocol)) {
            BedrockTypes.POSITION_3F.write(buffer, serverPosition != null ? serverPosition : Position3f.ZERO);
        }
    }

    public static boolean locksCamera(final int lockComponentData) {
        return (lockComponentData & FLAG_CAMERA) != 0;
    }

    public static boolean locksMovement(final int lockComponentData) {
        return (lockComponentData & FLAG_MOVEMENT) != 0;
    }

    public record DecodedLocks(int lockComponentData, Position3f serverPosition) {
        public boolean cameraLocked() {
            return locksCamera(lockComponentData);
        }

        public boolean movementLocked() {
            return locksMovement(lockComponentData);
        }
    }
}
