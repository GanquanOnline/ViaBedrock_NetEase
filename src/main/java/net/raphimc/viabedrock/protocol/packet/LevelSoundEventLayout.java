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
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 {@code LEVEL_SOUND_EVENT}（0x7B / 123 号包）的尾部辅助方法。
 * <p>
 * 官方基岩 1.21.90+ / 协议 974+ 以及 Nukkit-MOT 的对应分支会在小端实体唯一 ID 之后
 * 追加一个可选的 {@code fireAtPosition} 向量。网易 / Nukkit-MOT 协议 860 仍然止于
 * {@code entityUniqueId}（{@code protocol >= 785}）。把该包当作 974+ 处理会试图在
 * 42 字节载荷之后多读一个可选布尔值，从而以 {@code SOUND}/{@code OptionalType}
 * extra-data 中止 Java 连接。
 */
public final class LevelSoundEventLayout {

    public static final int ENTITY_UNIQUE_ID_PROTOCOL = 785;
    public static final int FIRE_AT_POSITION_PROTOCOL = 974;

    private LevelSoundEventLayout() {
    }

    public static boolean usesEntityUniqueId() {
        return usesEntityUniqueId(BedrockWire.effectiveProtocol());
    }

    public static boolean usesEntityUniqueId(final int protocol) {
        return protocol >= ENTITY_UNIQUE_ID_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean usesEntityUniqueId(final boolean emulateNetEase, final int protocol) {
        return usesEntityUniqueId(protocol);
    }

    public static boolean usesFireAtPosition() {
        return usesFireAtPosition(BedrockWire.effectiveProtocol());
    }

    public static boolean usesFireAtPosition(final int protocol) {
        return protocol >= FIRE_AT_POSITION_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean usesFireAtPosition(final boolean emulateNetEase, final int protocol) {
        return usesFireAtPosition(protocol);
    }

    public static void skipTrailer(final PacketWrapper wrapper) {
        if (usesEntityUniqueId()) {
            wrapper.read(BedrockTypes.LONG_LE);
        }
        if (usesFireAtPosition()) {
            wrapper.read(BedrockTypes.OPTIONAL_POSITION_3F);
        }
    }

    public static void writeTrailer(final PacketWrapper wrapper, final long entityUniqueId) {
        if (usesEntityUniqueId()) {
            wrapper.write(BedrockTypes.LONG_LE, entityUniqueId);
        }
        if (usesFireAtPosition()) {
            wrapper.write(BedrockTypes.OPTIONAL_POSITION_3F, null);
        }
    }

    public static void skipTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesEntityUniqueId(emulateNetEase, protocol)) {
            BedrockTypes.LONG_LE.read(buffer);
        }
        if (usesFireAtPosition(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_POSITION_3F.read(buffer);
        }
    }

    public static void writeTrailer(final ByteBuf buffer, final long entityUniqueId,
                                    final Position3f fireAtPosition, final boolean emulateNetEase, final int protocol) {
        if (usesEntityUniqueId(emulateNetEase, protocol)) {
            BedrockTypes.LONG_LE.write(buffer, entityUniqueId);
        }
        if (usesFireAtPosition(emulateNetEase, protocol)) {
            BedrockTypes.OPTIONAL_POSITION_3F.write(buffer, fireAtPosition);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
