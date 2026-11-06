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
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Bedrock DISCONNECT（数据包 0x05）的线上布局辅助方法。
 * <p>
 * 官方 Bedrock 974+ 把 hide-disconnection-screen 编码为 unsigned varint；
 * 897..973 与网易 860 仍然使用布尔值（0x00/0x01），恰好就是一个 1 字节 unsigned varint，
 * 所以两种读法在 897..973 上字节等价，这里只是把取值方式统一成协议号判断。
 */
public final class DisconnectPacketLayout {

    public static final int UNSIGNED_HIDE_SCREEN_PROTOCOL = 974;

    private DisconnectPacketLayout() {
    }

    public static boolean usesUnsignedHideScreen() {
        return usesUnsignedHideScreen(BedrockWire.effectiveProtocol());
    }

    public static boolean usesUnsignedHideScreen(final int protocol) {
        return protocol >= UNSIGNED_HIDE_SCREEN_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定（官方 897..973 也在阈值之下）。 */
    public static boolean usesUnsignedHideScreen(final boolean emulateNetEase, final int protocol) {
        return usesUnsignedHideScreen(protocol);
    }

    /** @return 当 hide-screen 标志之后跟着踢出消息／过滤后消息时返回 true */
    public static boolean readHasMessage(final PacketWrapper wrapper) {
        if (usesUnsignedHideScreen()) {
            return wrapper.read(BedrockTypes.UNSIGNED_VAR_INT) == 0;
        }
        return !wrapper.read(Types.BOOLEAN);
    }

    public static boolean readHasMessage(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesUnsignedHideScreen(emulateNetEase, protocol)) {
            return BedrockTypes.UNSIGNED_VAR_INT.read(buffer) == 0;
        }
        return !buffer.readBoolean();
    }

    public static void writeHideScreen(final ByteBuf buffer, final boolean hideScreen,
                                       final boolean emulateNetEase, final int protocol) {
        if (usesUnsignedHideScreen(emulateNetEase, protocol)) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, hideScreen ? 1 : 0);
        } else {
            buffer.writeBoolean(hideScreen);
        }
    }
}
