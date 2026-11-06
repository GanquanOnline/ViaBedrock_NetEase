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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookEditLayoutTest {

    @Test
    void byteActionAndSlotIsDecidedByProtocolOnly() {
        assertFalse(BookEditLayout.usesVarIntLayout(true, 860));
        assertFalse(BookEditLayout.usesVarIntLayout(true, 923));
        assertTrue(BookEditLayout.usesVarIntLayout(true, 924));
        assertFalse(BookEditLayout.usesVarIntLayout(false, 860), "官方 860 也是 byte 布局");
        assertFalse(BookEditLayout.usesVarIntLayout(false, 923));
        assertTrue(BookEditLayout.usesVarIntLayout(false, 924));
        assertTrue(BookEditLayout.usesVarIntLayout(false, 975));
    }

    @Test
    void defaultWireProtocolWritesTheVarIntLayout() {
        assertEquals(975, BedrockWire.effectiveProtocol(), "无配置时不能退成网易协议号");
        assertTrue(BookEditLayout.usesVarIntLayout(), "975 必须写 924 起的 varint 布局");
    }

    @Test
    void roundTripsNetease860ReplacePage() {
        final byte[] payload = BookEditLayout.encodeReplaceOrAddPage(BookEditAction.ReplacePage, 7, 2, "hello", true, 860);
        final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            final BookEditLayout.Header header = BookEditLayout.readHeader(buffer, true, 860);
            assertEquals(BookEditAction.ReplacePage, header.action());
            assertEquals(7, header.inventorySlot());
            assertEquals(2, BookEditLayout.readPageNumber(buffer, true, 860));
            assertEquals("hello", BedrockTypes.STRING.read(buffer));
            assertEquals("", BedrockTypes.STRING.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official924UsesVarIntSlotThenAction() {
        final byte[] payload = BookEditLayout.encodeReplaceOrAddPage(BookEditAction.AddPage, 12, 3, "page", false, 975);
        final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            final BookEditLayout.Header header = BookEditLayout.readHeader(buffer, false, 975);
            assertEquals(BookEditAction.AddPage, header.action());
            assertEquals(12, header.inventorySlot());
            assertEquals(3, BookEditLayout.readPageNumber(buffer, false, 975));
            assertEquals("page", BedrockTypes.STRING.read(buffer));
            assertEquals("", BedrockTypes.STRING.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void diffsJavaPagesIntoReplaceAddAndDelete() {
        final List<byte[]> packets = BookEditLayout.diffPages(1, List.of("a", "b", "c"), List.of("a", "B"), true, 860);
        assertEquals(2, packets.size());

        final ByteBuf replace = Unpooled.wrappedBuffer(packets.get(0));
        try {
            assertEquals(BookEditAction.ReplacePage, BookEditLayout.readHeader(replace, true, 860).action());
            assertEquals(1, BookEditLayout.readPageNumber(replace, true, 860));
            assertEquals("B", BedrockTypes.STRING.read(replace));
        } finally {
            replace.release();
        }

        final ByteBuf delete = Unpooled.wrappedBuffer(packets.get(1));
        try {
            assertEquals(BookEditAction.DeletePage, BookEditLayout.readHeader(delete, true, 860).action());
            assertEquals(2, BookEditLayout.readPageNumber(delete, true, 860));
            assertFalse(delete.isReadable());
        } finally {
            delete.release();
        }
    }

    @Test
    void readsExistingBedrockPagesFromItemTag() {
        final CompoundTag page = new CompoundTag();
        page.putString("text", "old");
        page.putString("photoname", "");
        final ListTag<CompoundTag> pages = new ListTag<>(CompoundTag.class);
        pages.add(page);
        final CompoundTag tag = new CompoundTag();
        tag.put("pages", pages);
        final BedrockItem item = new BedrockItem(386, (short) 0, (byte) 1, tag);
        assertEquals(List.of("old"), BookEditLayout.pagesFromItem(item));
    }
}
