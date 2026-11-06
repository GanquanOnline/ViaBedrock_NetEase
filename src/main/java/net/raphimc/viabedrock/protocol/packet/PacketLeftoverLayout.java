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
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import io.netty.buffer.ByteBuf;

/**
 * PacketWrapper 会把所有未读的基岩字节复制到重写后的 Java 数据包上。
 * 网易 860 的尾部（以及任何读取不足的官方载荷）随后会以 extra data 形式
 * 踢掉 1.21.11，例如在已映射的 S2C 包 OPEN_SCREEN / CONTAINER_SET_CONTENT 上。
 * <p>
 * 只跳过剩余输入字节。{@link PacketWrapper#clearInputBuffer()}
 * 还会清空已映射的 {@code readableObjects}，这会破坏那些
 * 在丢弃剩余字节之前用过 {@code map()} 的 PacketHandler。
 */
public final class PacketLeftoverLayout {

    private PacketLeftoverLayout() {
    }

    public static void discardUnreadInput(final PacketWrapper wrapper) {
        if (wrapper instanceof PacketWrapperImpl packetWrapper) {
            final ByteBuf input = packetWrapper.getInputBuffer();
            if (input != null && input.isReadable()) {
                NetEaseLayoutReport.record(wrapper, input.readableBytes());
            }
            discardUnreadInput(input);
        }
    }

    public static void discardUnreadInput(final ByteBuf input) {
        if (input != null && input.isReadable()) {
            input.skipBytes(input.readableBytes());
        }
    }
}
