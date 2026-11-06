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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.BookEditAction;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * 基岩 BOOK_EDIT（数据包 0x61 / 97）的线格式布局辅助。
 * <p>
 * MOT {@code BookEditPacket} 在协议 924 开始分叉（只看协议号，没有网易条件）：
 * <ul>
 * <li>860 和 924 之前：{@code byte action, byte inventorySlot}，
 * 然后页码以 byte 形式给出。</li>
 * <li>924 起（含将来的网易 924+）：{@code varint inventorySlot, unsigned-varint action}，
 * 然后页码以 varint 形式给出。</li>
 * </ul>
 * Java 1.21.11 的 {@code EDIT_BOOK} 会发送整个页面列表外加可选的
 * 标题。MOT 只接受逐页的 REPLACE/ADD/DELETE/SWAP 加 SIGN，因此
 * Java 载荷必须展开成这些 C2S 包。
 */
public final class BookEditLayout {

    public static final int VARINT_LAYOUT_PROTOCOL = 924;
    public static final int MAX_PAGES = 50;
    public static final int MAX_PAGE_TEXT = 256;
    public static final int MAX_TITLE = 64;
    public static final int MAX_AUTHOR = 64;
    public static final int MAX_XUID = 64;
    public static final String WRITABLE_BOOK = "minecraft:writable_book";

    private BookEditLayout() {
    }

    public static boolean usesVarIntLayout() {
        return usesVarIntLayout(BedrockWire.effectiveProtocol());
    }

    public static boolean usesVarIntLayout(final int protocol) {
        return protocol <= 0 || protocol >= VARINT_LAYOUT_PROTOCOL;
    }

    /** 旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean usesVarIntLayout(final boolean emulateNetEase, final int protocol) {
        return usesVarIntLayout(protocol);
    }

    public static void writeHeader(final ByteBuf buffer, final BookEditAction action, final int inventorySlot,
                                  final boolean emulateNetEase, final int protocol) {
        if (usesVarIntLayout(emulateNetEase, protocol)) {
            BedrockTypes.VAR_INT.write(buffer, inventorySlot);
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, action.getValue());
        } else {
            buffer.writeByte(action.getValue());
            buffer.writeByte(inventorySlot);
        }
    }

    public static void writePageNumber(final ByteBuf buffer, final int pageNumber,
                                       final boolean emulateNetEase, final int protocol) {
        if (usesVarIntLayout(emulateNetEase, protocol)) {
            BedrockTypes.VAR_INT.write(buffer, pageNumber);
        } else {
            buffer.writeByte(pageNumber);
        }
    }

    public static Header readHeader(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesVarIntLayout(emulateNetEase, protocol)) {
            final int inventorySlot = BedrockTypes.VAR_INT.read(buffer);
            final int actionId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            return new Header(BookEditAction.getByValue(actionId), inventorySlot);
        }
        final int actionId = buffer.readUnsignedByte();
        final int inventorySlot = buffer.readUnsignedByte();
        return new Header(BookEditAction.getByValue(actionId), inventorySlot);
    }

    public static int readPageNumber(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        if (usesVarIntLayout(emulateNetEase, protocol)) {
            return BedrockTypes.VAR_INT.read(buffer);
        }
        return buffer.readUnsignedByte();
    }

    public static byte[] encodeReplaceOrAddPage(final BookEditAction action, final int inventorySlot,
                                                final int pageNumber, final String text,
                                                final boolean emulateNetEase, final int protocol) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeHeader(buffer, action, inventorySlot, emulateNetEase, protocol);
            writePageNumber(buffer, pageNumber, emulateNetEase, protocol);
            BedrockTypes.STRING.write(buffer, clamp(text, MAX_PAGE_TEXT));
            BedrockTypes.STRING.write(buffer, "");
            return readAll(buffer);
        } finally {
            buffer.release();
        }
    }

    public static byte[] encodeDeletePage(final int inventorySlot, final int pageNumber,
                                          final boolean emulateNetEase, final int protocol) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeHeader(buffer, BookEditAction.DeletePage, inventorySlot, emulateNetEase, protocol);
            writePageNumber(buffer, pageNumber, emulateNetEase, protocol);
            return readAll(buffer);
        } finally {
            buffer.release();
        }
    }

    public static byte[] encodeSwapPages(final int inventorySlot, final int pageNumber, final int secondaryPageNumber,
                                         final boolean emulateNetEase, final int protocol) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeHeader(buffer, BookEditAction.SwapPages, inventorySlot, emulateNetEase, protocol);
            writePageNumber(buffer, pageNumber, emulateNetEase, protocol);
            writePageNumber(buffer, secondaryPageNumber, emulateNetEase, protocol);
            return readAll(buffer);
        } finally {
            buffer.release();
        }
    }

    public static byte[] encodeSignBook(final int inventorySlot, final String title, final String author, final String xuid,
                                        final boolean emulateNetEase, final int protocol) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeHeader(buffer, BookEditAction.Finalize, inventorySlot, emulateNetEase, protocol);
            BedrockTypes.STRING.write(buffer, clamp(title, MAX_TITLE));
            BedrockTypes.STRING.write(buffer, clamp(author, MAX_AUTHOR));
            BedrockTypes.STRING.write(buffer, clamp(xuid, MAX_XUID));
            return readAll(buffer);
        } finally {
            buffer.release();
        }
    }

    public static List<String> pagesFromItem(final BedrockItem item) {
        if (item == null || item.isEmpty() || item.tag() == null) {
            return List.of();
        }
        final ListTag<CompoundTag> pages = item.tag().getListTag("pages", CompoundTag.class);
        if (pages == null) {
            return List.of();
        }
        final List<String> texts = new ArrayList<>(pages.size());
        for (CompoundTag page : pages) {
            texts.add(page != null ? page.getString("text", "") : "");
        }
        return texts;
    }

    public static List<String> clampJavaPages(final List<String> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        final int size = Math.min(pages.size(), MAX_PAGES);
        final List<String> clamped = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            clamped.add(clamp(pages.get(i), MAX_PAGE_TEXT));
        }
        return clamped;
    }

    public static List<byte[]> diffPages(final int inventorySlot, final List<String> oldPages, final List<String> newPages,
                                         final boolean emulateNetEase, final int protocol) {
        final List<String> from = oldPages != null ? oldPages : List.of();
        final List<String> to = clampJavaPages(newPages);
        final List<byte[]> packets = new ArrayList<>();
        final int shared = Math.min(from.size(), to.size());
        for (int i = 0; i < shared; i++) {
            if (!from.get(i).equals(to.get(i))) {
                packets.add(encodeReplaceOrAddPage(BookEditAction.ReplacePage, inventorySlot, i, to.get(i), emulateNetEase, protocol));
            }
        }
        if (to.size() > from.size()) {
            for (int i = from.size(); i < to.size(); i++) {
                packets.add(encodeReplaceOrAddPage(BookEditAction.AddPage, inventorySlot, i, to.get(i), emulateNetEase, protocol));
            }
        } else if (from.size() > to.size()) {
            for (int i = from.size() - 1; i >= to.size(); i--) {
                packets.add(encodeDeletePage(inventorySlot, i, emulateNetEase, protocol));
            }
        }
        return packets;
    }

    public static String clamp(final String value, final int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static byte[] readAll(final ByteBuf buffer) {
        final byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }

    public record Header(BookEditAction action, int inventorySlot) {
    }
}
