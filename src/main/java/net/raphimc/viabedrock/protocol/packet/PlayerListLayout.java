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
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.NetEaseDeviation;
import net.raphimc.viabedrock.protocol.data.NetEaseWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 PLAYER_LIST 添加（0x3F 号包）的尾部辅助方法。
 * <p>
 * 官方基岩 860–2167 在可信皮肤布尔数组之后结束。
 * MOT 网易在 {@code protocol >= 649} 时按条目追加：
 * {@code bool, bool, byte[], lint, bool}（bloom / persona 尾部）。
 * Java 从不消费这些字段，但必须把它们读掉，这样
 * PLAYER_INFO_UPDATE 不带 extra data 就不只靠丢弃剩余字节来实现。
 */
public final class PlayerListLayout {

    private PlayerListLayout() {
    }

    public static boolean usesNetEaseAddTrailer() {
        return usesNetEaseAddTrailer(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesNetEaseAddTrailer(final boolean emulateNetEase, final int protocol) {
        return NetEaseWire.applies(emulateNetEase, protocol, NetEaseDeviation.PLAYER_LIST_TRAILER);
    }

    public static void skipNetEaseAddTrailer(final PacketWrapper wrapper, final int length) {
        if (!usesNetEaseAddTrailer() || length <= 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            wrapper.read(Types.BOOLEAN);
        }
        for (int i = 0; i < length; i++) {
            wrapper.read(Types.BOOLEAN);
        }
        for (int i = 0; i < length; i++) {
            wrapper.read(BedrockTypes.BYTE_ARRAY);
        }
        for (int i = 0; i < length; i++) {
            wrapper.read(BedrockTypes.INT_LE);
        }
        for (int i = 0; i < length; i++) {
            wrapper.read(Types.BOOLEAN);
        }
    }

    public static void writeNetEaseAddTrailer(final ByteBuf buffer, final int length,
                                              final boolean emulateNetEase, final int protocol) {
        if (!usesNetEaseAddTrailer(emulateNetEase, protocol) || length <= 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            buffer.writeBoolean(false);
        }
        for (int i = 0; i < length; i++) {
            buffer.writeBoolean(false);
        }
        for (int i = 0; i < length; i++) {
            BedrockTypes.BYTE_ARRAY.write(buffer, new byte[0]);
        }
        for (int i = 0; i < length; i++) {
            BedrockTypes.INT_LE.write(buffer, 0);
        }
        for (int i = 0; i < length; i++) {
            buffer.writeBoolean(false);
        }
    }

    public static void skipNetEaseAddTrailer(final ByteBuf buffer, final int length,
                                             final boolean emulateNetEase, final int protocol) {
        if (!usesNetEaseAddTrailer(emulateNetEase, protocol) || length <= 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            buffer.readBoolean();
        }
        for (int i = 0; i < length; i++) {
            buffer.readBoolean();
        }
        for (int i = 0; i < length; i++) {
            BedrockTypes.BYTE_ARRAY.read(buffer);
        }
        for (int i = 0; i < length; i++) {
            BedrockTypes.INT_LE.read(buffer);
        }
        for (int i = 0; i < length; i++) {
            buffer.readBoolean();
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
