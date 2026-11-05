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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 INTERACT（数据包 0x21）的线上布局辅助工具。
 * <p>
 * 官方基岩 / Nukkit-MOT 协议 897+ 会给可选的 Vector3f 加一个
 * boolean 前缀。网易 860 仍使用 897 之前的布局：mouseover（动作 4）以及自
 * 协议 388 起的 stop-riding（动作 3）总是携带 Vector3f，没有存在标志。
 * 在官方 975 的 INTERACT 中写入缺少该 boolean 的 Vector3f 会留下多余数据；
 * 而在网易 860 上省略它会让 Nukkit 多读下一个数据包。
 */
public final class InteractPacketLayout {

    public static final int OPTIONAL_POSITION_PROTOCOL = 897;

    private InteractPacketLayout() {
    }

    public static boolean usesOptionalPosition() {
        return usesOptionalPosition(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesOptionalPosition(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= OPTIONAL_POSITION_PROTOCOL;
    }

    public static boolean hasPosition(final InteractPacket_Action action) {
        return action == InteractPacket_Action.InteractUpdate || action == InteractPacket_Action.StopRiding;
    }

    public static void writePosition(final PacketWrapper wrapper, final InteractPacket_Action action, final Position3f position) {
        writePosition(wrapper, action, position, emulateNetEase(), netEaseProtocol());
    }

    public static void writePosition(final PacketWrapper wrapper, final InteractPacket_Action action, final Position3f position,
                                     final boolean emulateNetEase, final int protocol) {
        if (usesOptionalPosition(emulateNetEase, protocol)) {
            final boolean present = hasPosition(action);
            wrapper.write(Types.BOOLEAN, present);
            if (present) {
                wrapper.write(BedrockTypes.POSITION_3F, position);
            }
            return;
        }
        if (hasPosition(action)) {
            wrapper.write(BedrockTypes.POSITION_3F, position);
        }
    }

    public static void writePosition(final ByteBuf buffer, final InteractPacket_Action action, final Position3f position,
                                     final boolean emulateNetEase, final int protocol) {
        if (usesOptionalPosition(emulateNetEase, protocol)) {
            final boolean present = hasPosition(action);
            buffer.writeBoolean(present);
            if (present) {
                BedrockTypes.POSITION_3F.write(buffer, position);
            }
            return;
        }
        if (hasPosition(action)) {
            BedrockTypes.POSITION_3F.write(buffer, position);
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
