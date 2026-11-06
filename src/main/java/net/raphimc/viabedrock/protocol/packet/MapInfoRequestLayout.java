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
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 MAP_INFO_REQUEST（68 号包）的线路布局。
 * <p>
 * MOT {@code MapInfoRequestPacket.decode()} 读取 {@code varlong mapId}，并在
 * 协议 &gt;= 544 时读取一个**小端 int32** 像素数量，随后读取该数量的
 * {@code int + lshort} 像素。官方 Java 客户端从不发送像素，该数量恒为 0。
 * 写成无符号 varint 会让线上少三个字节，MOT 读数量时越过包尾，
 * 整批被判为畸形，累计超过服务端预算即断连。
 */
public final class MapInfoRequestLayout {

    public static final int PIXELS_PROTOCOL = 544;

    private MapInfoRequestLayout() {
    }

    public static void write(final PacketWrapper wrapper, final long mapId) {
        write(wrapper, mapId, true, PIXELS_PROTOCOL);
    }

    public static void write(final PacketWrapper wrapper, final long mapId,
                             final boolean includePixels, final int protocol) {
        wrapper.write(BedrockTypes.VAR_LONG, mapId);
        if (includePixels && protocol >= PIXELS_PROTOCOL) {
            wrapper.write(BedrockTypes.INT_LE, 0);
        }
    }

    public static void write(final ByteBuf buffer, final long mapId,
                             final boolean includePixels, final int protocol) {
        BedrockTypes.VAR_LONG.write(buffer, mapId);
        if (includePixels && protocol >= PIXELS_PROTOCOL) {
            BedrockTypes.INT_LE.write(buffer, 0);
        }
    }
}
