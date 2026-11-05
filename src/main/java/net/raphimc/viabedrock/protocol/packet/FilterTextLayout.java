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
 * Bedrock FILTER_TEXT（数据包 163）的线上布局辅助方法。
 * <p>
 * MOT 的 {@code FilterTextPacket} 编码 {@code string text + boolean fromServer}。
 * Java 的铁砧重命名数据包在 Bedrock 侧除了这个回显之外没有对应物：
 * 客户端发送 {@code fromServer=false}，MOT 则以 {@code fromServer=true}
 * 把文本回显回来。真正的自定义名称之后由
 * {@code CraftRecipeOptional} 使用 ITEM_STACK_REQUEST 的 filter-string 尾部来应用。
 */
public final class FilterTextLayout {

    public static final int MOT_MAX_LENGTH = 64;
    public static final int ANVIL_NAME_MAX_LENGTH = 50;

    private FilterTextLayout() {
    }

    public static void write(final ByteBuf buffer, final String text, final boolean fromServer) {
        BedrockTypes.STRING.write(buffer, text != null ? text : "");
        buffer.writeBoolean(fromServer);
    }

    public static Packet read(final ByteBuf buffer) {
        final String text = BedrockTypes.STRING.read(buffer);
        final boolean fromServer = buffer.readBoolean();
        return new Packet(text, fromServer);
    }

    public static Packet read(final PacketWrapper wrapper) {
        final String text = wrapper.read(BedrockTypes.STRING);
        final boolean fromServer = wrapper.read(com.viaversion.viaversion.api.type.Types.BOOLEAN);
        return new Packet(text, fromServer);
    }

    public static void write(final PacketWrapper wrapper, final String text, final boolean fromServer) {
        wrapper.write(BedrockTypes.STRING, text != null ? text : "");
        wrapper.write(com.viaversion.viaversion.api.type.Types.BOOLEAN, fromServer);
    }

    public static String sanitize(final String text) {
        return truncate(text, MOT_MAX_LENGTH);
    }

    public static String sanitizeAnvilName(final String text) {
        return truncate(text, ANVIL_NAME_MAX_LENGTH);
    }

    private static String truncate(final String text, final int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public record Packet(String text, boolean fromServer) {
    }
}
