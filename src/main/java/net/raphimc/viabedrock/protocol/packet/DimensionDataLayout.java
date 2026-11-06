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
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Bedrock DIMENSION_DATA（数据包 0xB4 / -76）的线上布局辅助方法。
 * <p>
 * 官方 Bedrock / Nukkit-MOT 协议 975+ 在 {@code generator type}
 * 之后追加 {@code dimension type}。网易 860 仍然为每个条目
 * 编码四个字段：identifier、最大高度、最小高度、generator type。
 * 在 860 上读这个额外的 varint 会吃掉下一条目的字符串长度，
 * 并中止剩余定义，因此主世界高度可能被丢弃。
 */
public final class DimensionDataLayout {

    public static final int DIMENSION_TYPE_PROTOCOL = 975;

    private DimensionDataLayout() {
    }

    public static boolean usesDimensionType() {
        return usesDimensionType(BedrockWire.effectiveProtocol());
    }

    public static boolean usesDimensionType(final int protocol) {
        return protocol >= DIMENSION_TYPE_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean usesDimensionType(final boolean emulateNetEase, final int protocol) {
        return usesDimensionType(protocol);
    }

    public static void skipDimensionType(final PacketWrapper wrapper) {
        if (usesDimensionType()) {
            wrapper.read(BedrockTypes.VAR_INT);
        }
    }

    public static Integer readDimensionType(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (!usesDimensionType(emulateNetEase, protocol)) {
            return null;
        }
        return BedrockTypes.VAR_INT.read(buffer);
    }

    public static void skipDimensionType(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        readDimensionType(buffer, emulateNetEase, protocol);
    }

    public static void writeDimensionType(final ByteBuf buffer, final int dimensionType,
                                          final boolean emulateNetEase, final int protocol) {
        if (usesDimensionType(emulateNetEase, protocol)) {
            BedrockTypes.VAR_INT.write(buffer, dimensionType);
        }
    }
}
