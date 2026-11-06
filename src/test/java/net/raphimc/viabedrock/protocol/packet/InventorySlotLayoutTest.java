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

import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.types.item.BedrockItemType;
import net.raphimc.viabedrock.protocol.types.item.NetworkItemStackDescriptorType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventorySlotLayoutTest {

    private static final Type<BedrockItem> LEGACY_ITEM_TYPE = new BedrockItemType(0, new Int2ObjectOpenHashMap<IntSortedSet>(), false);
    private static final Type<BedrockItem> NETWORK_ITEM_TYPE = new NetworkItemStackDescriptorType(0, new Int2ObjectOpenHashMap<IntSortedSet>(), false);
    private static final FullContainerName EMPTY_CONTAINER = new FullContainerName(ContainerEnumName.AnvilInputContainer, null);

    @Test
    void protocolBelow974UsesRequiredContainerFields() {
        assertTrue(InventorySlotLayout.usesRequiredContainerFields(true, 860));
        assertTrue(InventorySlotLayout.usesRequiredContainerFields(false, 860));
        assertTrue(InventorySlotLayout.usesRequiredContainerFields(false, 973));
        assertFalse(InventorySlotLayout.usesRequiredContainerFields(false, 974));
        assertFalse(InventorySlotLayout.usesRequiredContainerFields(true, 974));
        assertFalse(InventorySlotLayout.usesRequiredContainerFields(true, 975));
    }

    @Test
    void parsesNetease860InventorySlotWithoutLeavingUnreadBytes() {
        final BedrockItem item = new BedrockItem(1, (short) 0, (byte) 1);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventorySlotLayout.writeRequiredContainerFields(buffer, 0, 36, EMPTY_CONTAINER, BedrockItem.empty(), item, LEGACY_ITEM_TYPE);
            final int leftoverIfReadAs974 = leftoverBytesAfterOptionalLayout(buffer);
            assertTrue(leftoverIfReadAs974 > 0, "974 layout should leave the item payload unread");

            final InventorySlotLayout.DecodedInventorySlot decoded = InventorySlotLayout.read(buffer, LEGACY_ITEM_TYPE, NETWORK_ITEM_TYPE, true, 860);
            assertEquals(0, decoded.containerId());
            assertEquals(36, decoded.slot());
            assertEquals(EMPTY_CONTAINER, decoded.containerName());
            assertTrue(decoded.storageItem().isEmpty());
            assertEquals(1, decoded.item().identifier());
            assertEquals(1, decoded.item().amount());
            assertFalse(buffer.isReadable(), "NetEase 860 inventory slot must consume the full packet");
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860InventorySlotAs974LeavesUnreadBytes() {
        final BedrockItem item = new BedrockItem(1, (short) 0, (byte) 1);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventorySlotLayout.writeRequiredContainerFields(buffer, 0, 36, EMPTY_CONTAINER, BedrockItem.empty(), item, LEGACY_ITEM_TYPE);
            // 线上网易仍使用旧版物品编解码器，所以 974 风格的包
            // 读取器会把空的存储物品当成槽位物品消费掉，真正的
            // 负载因此没有被读取。这些残留字节就是 1.21.11 视为多余数据而拒绝的内容。
            InventorySlotLayout.read(buffer, LEGACY_ITEM_TYPE, LEGACY_ITEM_TYPE, false, 975);
            assertTrue(buffer.isReadable(), "974/optional layout must not consume a NetEase 860 inventory slot");
        } finally {
            buffer.release();
        }
    }

    @Test
    void parsesOfficial974InventorySlot() {
        final BedrockItem item = new BedrockItem(5, (short) 0, (byte) 2);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventorySlotLayout.writeOptionalContainerFields(buffer, 12, 3, item, NETWORK_ITEM_TYPE);
            final InventorySlotLayout.DecodedInventorySlot decoded = InventorySlotLayout.read(buffer, LEGACY_ITEM_TYPE, NETWORK_ITEM_TYPE, false, 975);
            assertEquals(12, decoded.containerId());
            assertEquals(3, decoded.slot());
            assertNull(decoded.containerName());
            assertNull(decoded.storageItem());
            assertEquals(5, decoded.item().identifier());
            assertEquals(2, decoded.item().amount());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static int leftoverBytesAfterOptionalLayout(final ByteBuf buffer) {
        final int readerIndex = buffer.readerIndex();
        try {
            InventorySlotLayout.read(buffer, LEGACY_ITEM_TYPE, LEGACY_ITEM_TYPE, false, 975);
            return buffer.readableBytes();
        } finally {
            buffer.readerIndex(readerIndex);
        }
    }
}
