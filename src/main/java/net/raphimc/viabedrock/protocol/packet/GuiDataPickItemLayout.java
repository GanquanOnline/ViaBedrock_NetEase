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
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Bedrock GUI_DATA_PICK_ITEM（数据包 54）的线上布局辅助方法。
 * <p>
 * 官方 Bedrock 写入 {@code string itemName + string itemEffects + little-endian hotbar slot}。
 * Nukkit-MOT 的 {@code GUIDataPickItemPacket.encode()} 在包括 860 在内的所有协议上都只写 {@code putLInt(hotbarSlot)}；
 * 从这四个字节里读两个字符串会抛异常并踢掉 Java 1.21.11。
 */
public final class GuiDataPickItemLayout {

    private GuiDataPickItemLayout() {
    }

    public static boolean usesNameAndEffects(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol <= 0;
    }

    public static void write(final ByteBuf buffer, final Packet packet, final boolean emulateNetEase, final int protocol) {
        if (usesNameAndEffects(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, packet.itemName() != null ? packet.itemName() : "");
            BedrockTypes.STRING.write(buffer, packet.itemEffects() != null ? packet.itemEffects() : "");
        }
        BedrockTypes.INT_LE.write(buffer, packet.hotbarSlot());
    }

    public static Packet read(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesNameAndEffects(emulateNetEase, protocol)) {
            final String itemName = BedrockTypes.STRING.read(buffer);
            final String itemEffects = BedrockTypes.STRING.read(buffer);
            final int hotbarSlot = BedrockTypes.INT_LE.read(buffer);
            return new Packet(itemName, itemEffects, hotbarSlot);
        }
        return new Packet("", "", BedrockTypes.INT_LE.read(buffer));
    }

    public static Packet read(final PacketWrapper wrapper, final boolean emulateNetEase, final int protocol) {
        if (usesNameAndEffects(emulateNetEase, protocol)) {
            final String itemName = wrapper.read(BedrockTypes.STRING);
            final String itemEffects = wrapper.read(BedrockTypes.STRING);
            final int hotbarSlot = wrapper.read(BedrockTypes.INT_LE);
            return new Packet(itemName, itemEffects, hotbarSlot);
        }
        return new Packet("", "", wrapper.read(BedrockTypes.INT_LE));
    }

    public static boolean hasOverlayText(final Packet packet) {
        if (packet == null) {
            return false;
        }
        return !isBlank(packet.itemName()) || !isBlank(packet.itemEffects());
    }

    public static String overlayText(final Packet packet) {
        if (packet == null) {
            return "";
        }
        if (isBlank(packet.itemEffects())) {
            return packet.itemName() != null ? packet.itemName() : "";
        }
        if (isBlank(packet.itemName())) {
            return packet.itemEffects();
        }
        return packet.itemName() + "\n" + packet.itemEffects();
    }

    public static boolean isHotbarSlot(final int slot) {
        return slot >= 0 && slot < 9;
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isEmpty();
    }

    public record Packet(String itemName, String itemEffects, int hotbarSlot) {
    }
}
