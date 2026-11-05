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
 * Bedrock CONTAINER_OPEN（数据包 0x2E）的尾部字段辅助方法。
 * <p>
 * 官方 Bedrock 在 {@code entityUniqueId} 之后就结束。网易 860
 * （{@code V1_21_124_NETEASE}）在该唯一 ID 之后追加一个额外布尔值，网易 898
 * （{@code V1_21_130_NETEASE}）在其后追加 {@code screenName}、{@code customDescription} 和
 * {@code isClosedDropItem}。PacketWrapper 会把 Bedrock 剩余的字节拷到 Java
 * {@code OPEN_SCREEN} 上，所以不读尾部会让 1.21.11 把该 GUI 当作多余数据丢掉。
 */
public final class ContainerOpenLayout {

    private ContainerOpenLayout() {
    }

    public static boolean usesNetEaseTrailer() {
        return usesNetEaseTrailer(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesNetEaseTrailer(final boolean emulateNetEase, final int protocol) {
        return NetEaseWire.applies(emulateNetEase, protocol, NetEaseDeviation.CONTAINER_OPEN_TRAILER);
    }

    public static boolean usesNetEaseExtendedTrailer(final boolean emulateNetEase, final int protocol) {
        return NetEaseWire.applies(emulateNetEase, protocol, NetEaseDeviation.CONTAINER_OPEN_EXTENDED_TRAILER);
    }

    public static void skipTrailer(final PacketWrapper wrapper) {
        skipTrailer(wrapper, emulateNetEase(), netEaseProtocol());
    }

    public static void skipTrailer(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        if (usesNetEaseTrailer(emulateNetEase, protocol)) {
            wrapper.read(Types.BOOLEAN); // 是否为忽略方块
        }
        if (usesNetEaseExtendedTrailer(emulateNetEase, protocol)) {
            wrapper.read(BedrockTypes.STRING); // 界面名称
            wrapper.read(BedrockTypes.STRING); // 自定义描述
            wrapper.read(Types.BOOLEAN); // 是否关闭时丢弃物品
        }
    }

    public static void skipTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesNetEaseTrailer(emulateNetEase, protocol)) {
            buffer.readBoolean();
        }
        if (usesNetEaseExtendedTrailer(emulateNetEase, protocol)) {
            BedrockTypes.STRING.read(buffer);
            BedrockTypes.STRING.read(buffer);
            buffer.readBoolean();
        }
    }

    public static void writeTrailer(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesNetEaseTrailer(emulateNetEase, protocol)) {
            buffer.writeBoolean(false);
        }
        if (usesNetEaseExtendedTrailer(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, "");
            BedrockTypes.STRING.write(buffer, "");
            buffer.writeBoolean(false);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
