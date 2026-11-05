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

import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.NetEaseDeviation;
import net.raphimc.viabedrock.protocol.data.NetEaseWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.TextPacketType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 TEXT（0x09 号包）的线路布局辅助方法。
 * <p>
 * 官方基岩 1.26 / 协议 924+ 以及 Nukkit-MOT 的 897/924 分支编码为：
 * {@code localize, category(0/1/2), type, payload..., xuid, platformId, optional filtered}。
 * <p>
 * 网易 / Nukkit-MOT 协议 860 仍使用 897 之前的布局：
 * {@code type, localize, payload..., xuid, platformId, filtered string}。
 * 把该包当作 924+ 处理会把 UTF-8 长度当作 type id 消费，随后
 * 在尾部的 xuid/platform/filtered 读取上崩溃（加入欢迎语时被踢）。
 */
public final class TextPacketLayout {

    public static final int CATEGORY_LAYOUT_PROTOCOL = 897;
    /**
     * Nukkit-MOT 897..923（“v898 格式”）把占位符名称保留在 category
     * 字节与 type 字节之间；924 又将其移除。
     */
    public static final int CATEGORY_NAME_STRINGS_END_PROTOCOL = 924;

    private TextPacketLayout() {
    }

    public static boolean isLegacyTypeFirstLayout() {
        return isLegacyTypeFirstLayout(emulateNetEase(), netEaseProtocol());
    }

    public static boolean isLegacyTypeFirstLayout(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol > 0 && protocol < CATEGORY_LAYOUT_PROTOCOL;
    }

    public static boolean usesRequiredFilteredString() {
        return usesRequiredFilteredString(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesRequiredFilteredString(final boolean emulateNetEase, final int protocol) {
        return NetEaseWire.applies(emulateNetEase, protocol, NetEaseDeviation.TEXT_REQUIRED_FILTERED_STRING);
    }

    public static boolean usesNetEaseParameterList(final boolean emulateNetEase, final int protocol, final TextPacketType type) {
        if (!NetEaseWire.applies(emulateNetEase, protocol, NetEaseDeviation.TEXT_NETEASE_PARAMETER_LIST)) {
            return false;
        }
        if (type == TextPacketType.chat) {
            return true;
        }
        return protocol >= CATEGORY_LAYOUT_PROTOCOL
                && (type == TextPacketType.whisper || type == TextPacketType.announcement);
    }

    /** 897 之前的网易弹窗在过滤后的消息之后多带一个字符串。 */
    public static boolean usesNetEasePopupTail(final boolean emulateNetEase, final int protocol, final TextPacketType type) {
        return NetEaseWire.applies(emulateNetEase, protocol, NetEaseDeviation.TEXT_NETEASE_POPUP_TAIL)
                && type == TextPacketType.popup;
    }

    public static boolean usesCategoryNameStrings() {
        return usesCategoryNameStrings(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesCategoryNameStrings(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol >= CATEGORY_LAYOUT_PROTOCOL && protocol < CATEGORY_NAME_STRINGS_END_PROTOCOL;
    }

    /**
     * 897..923 把网易聊天参数列表放在 category 分支内部，位于
     * xuid/platform/filtered 尾部之前。897 之前则追加在该尾部之后。
     */
    public static boolean usesInlineNetEaseChatParameters(final boolean emulateNetEase, final int protocol) {
        return usesCategoryNameStrings(emulateNetEase, protocol);
    }

    public static void skipParameterList(final ByteBuf buffer) {
        final long parameterCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        for (long i = 0; i < parameterCount && buffer.isReadable(); i++) {
            BedrockTypes.STRING.read(buffer);
        }
    }

    public static void writeEmptyParameterList(final ByteBuf buffer) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
    }

    public static String[] categoryNameStrings(final int category) {
        return switch (category) {
            case 0 -> new String[]{"raw", "tip", "systemMessage", "textObjectWhisper", "textObjectAnnouncement", "textObject"};
            case 1 -> new String[]{"chat", "whisper", "announcement"};
            case 2 -> new String[]{"translate", "popup", "jukeboxPopup"};
            default -> new String[0];
        };
    }

    public static TextHeader readHeader(final ByteBuf buffer, final boolean legacyTypeFirst, final boolean categoryNameStrings) {
        if (legacyTypeFirst) {
            final short rawType = buffer.readUnsignedByte();
            final boolean localize = buffer.readBoolean();
            return new TextHeader(localize, rawType);
        }
        final boolean localize = buffer.readBoolean();
        final short category = buffer.readUnsignedByte(); // 0=仅消息，1=作者+消息，2=消息+参数
        if (categoryNameStrings) {
            for (int i = 0; i < categoryNameStrings(category).length; i++) {
                BedrockTypes.STRING.read(buffer);
            }
        }
        final short rawType = buffer.readUnsignedByte();
        return new TextHeader(localize, rawType);
    }

    public static void writeHeader(final ByteBuf buffer, final boolean localize, final TextPacketType type,
                                   final boolean legacyTypeFirst, final boolean categoryNameStrings) {
        if (legacyTypeFirst) {
            buffer.writeByte(type.getValue());
            buffer.writeBoolean(localize || type == TextPacketType.translate);
            return;
        }
        buffer.writeBoolean(localize || type == TextPacketType.translate);
        final byte category = categoryOf(type);
        buffer.writeByte(category);
        if (categoryNameStrings) {
            for (final String name : categoryNameStrings(category & 0xFF)) {
                BedrockTypes.STRING.write(buffer, name);
            }
        }
        buffer.writeByte(type.getValue());
    }

    public static byte categoryOf(final TextPacketType type) {
        return switch (type) {
            case chat, whisper, announcement -> (byte) 1;
            case translate, popup, jukeboxPopup -> (byte) 2;
            default -> (byte) 0;
        };
    }

    public static DecodedText readPacket(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        final boolean legacyTypeFirst = isLegacyTypeFirstLayout(emulateNetEase, protocol);
        final TextHeader header = readHeader(buffer, legacyTypeFirst, usesCategoryNameStrings(emulateNetEase, protocol));
        final TextPacketType type = TextPacketType.getByValue(header.rawType());
        if (type == null) {
            throw new IllegalArgumentException("Unknown TextPacketType: " + header.rawType());
        }

        final String sourceName;
        final String message;
        final String[] parameters;
        final boolean inlineParameters = usesInlineNetEaseChatParameters(emulateNetEase, protocol)
                && usesNetEaseParameterList(emulateNetEase, protocol, type);
        switch (type) {
            case chat, whisper, announcement -> {
                sourceName = BedrockTypes.STRING.read(buffer);
                message = BedrockTypes.STRING.read(buffer);
                parameters = new String[0];
                if (inlineParameters) {
                    skipParameterList(buffer);
                }
            }
            case translate, popup, jukeboxPopup -> {
                sourceName = "";
                message = BedrockTypes.STRING.read(buffer);
                parameters = BedrockTypes.STRING_ARRAY.read(buffer);
            }
            default -> {
                sourceName = "";
                message = BedrockTypes.STRING.read(buffer);
                parameters = new String[0];
            }
        }

        final String xuid = BedrockTypes.STRING.read(buffer);
        final String platformOnlineId = BedrockTypes.STRING.read(buffer);
        final String filteredMessage = usesRequiredFilteredString(emulateNetEase, protocol)
                ? BedrockTypes.STRING.read(buffer)
                : BedrockTypes.OPTIONAL_STRING.read(buffer);
        String unknownNe = "";
        if (!inlineParameters && usesNetEaseParameterList(emulateNetEase, protocol, type)) {
            skipParameterList(buffer);
        } else if (usesNetEasePopupTail(emulateNetEase, protocol, type) && buffer.isReadable()) {
            unknownNe = BedrockTypes.STRING.read(buffer);
        }
        if (buffer.isReadable()) {
            buffer.skipBytes(buffer.readableBytes());
        }
        return new DecodedText(header.localize(), type, sourceName, message, parameters, xuid, platformOnlineId, filteredMessage, unknownNe);
    }

    public static void writeTrailer(final ByteBuf buffer, final TextPacketType type, final String xuid,
                                    final boolean emulateNetEase, final int protocol) {
        BedrockTypes.STRING.write(buffer, xuid == null ? "" : xuid);
        BedrockTypes.STRING.write(buffer, "");
        if (usesRequiredFilteredString(emulateNetEase, protocol)) {
            BedrockTypes.STRING.write(buffer, "");
        } else {
            BedrockTypes.OPTIONAL_STRING.write(buffer, null);
        }
        if (usesNetEaseParameterList(emulateNetEase, protocol, type)
                && !usesInlineNetEaseChatParameters(emulateNetEase, protocol)) {
            writeEmptyParameterList(buffer);
        } else if (usesNetEasePopupTail(emulateNetEase, protocol, type)) {
            BedrockTypes.STRING.write(buffer, "");
        }
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }

    public record TextHeader(boolean localize, short rawType) {
    }

    public record DecodedText(boolean localize, TextPacketType type, String sourceName, String message,
                              String[] parameters, String xuid, String platformOnlineId,
                              String filteredMessage, String unknownNe) {
    }
}
