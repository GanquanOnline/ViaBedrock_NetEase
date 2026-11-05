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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryContentLayoutTest {

    private static final Type<BedrockItem> LEGACY_ITEM_TYPE = new BedrockItemType(0, new Int2ObjectOpenHashMap<IntSortedSet>(), false);
    private static final FullContainerName EMPTY_CONTAINER = new FullContainerName(ContainerEnumName.AnvilInputContainer, null);

    @Test
    void officialAndNeteaseKeepRequiredContainerFields() {
        assertTrue(InventoryContentLayout.usesRequiredContainerFields(true, 860));
        assertTrue(InventoryContentLayout.usesRequiredContainerFields(false, 975));
        assertTrue(InventoryContentLayout.usesRequiredContainerFields(false, 860));
    }

    @Test
    void parsesNetease860InventoryContentWithoutLeavingUnreadBytes() {
        final BedrockItem[] items = {new BedrockItem(1, (short) 0, (byte) 1)};
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventoryContentLayout.writeRequiredContainerFields(buffer, 0, items, EMPTY_CONTAINER, BedrockItem.empty(), LEGACY_ITEM_TYPE);
            assertTrue(leftoverBytesAfterOptionalLayout(buffer) > 0,
                    "974 optional layout should leave the NetEase storage item unread");

            final InventoryContentLayout.DecodedInventoryContent decoded = InventoryContentLayout.read(buffer, LEGACY_ITEM_TYPE);
            assertEquals(0, decoded.containerId());
            assertEquals(1, decoded.items().length);
            assertEquals(1, decoded.items()[0].identifier());
            assertEquals(EMPTY_CONTAINER, decoded.containerName());
            assertTrue(decoded.storageItem().isEmpty());
            assertFalse(buffer.isReadable(), "NetEase 860 inventory content must consume the full packet");
        } finally {
            buffer.release();
        }
    }

    @Test
    void parsesOfficial975RequiredContainerFields() {
        final BedrockItem[] items = {new BedrockItem(5, (short) 0, (byte) 2)};
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventoryContentLayout.writeRequiredContainerFields(buffer, 12, items, EMPTY_CONTAINER, BedrockItem.empty(), LEGACY_ITEM_TYPE);
            final InventoryContentLayout.DecodedInventoryContent decoded = InventoryContentLayout.read(buffer, LEGACY_ITEM_TYPE);
            assertEquals(12, decoded.containerId());
            assertEquals(1, decoded.items().length);
            assertEquals(5, decoded.items()[0].identifier());
            assertEquals(EMPTY_CONTAINER, decoded.containerName());
            assertTrue(decoded.storageItem().isEmpty());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static int leftoverBytesAfterOptionalLayout(final ByteBuf buffer) {
        final int readerIndex = buffer.readerIndex();
        try {
            net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // 容器 id
            final int itemCount = net.raphimc.viabedrock.protocol.types.BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            for (int i = 0; i < itemCount; i++) {
                LEGACY_ITEM_TYPE.read(buffer);
            }
            buffer.readBoolean(); // 可选完整容器名
            buffer.readBoolean(); // 可选存储物品
            return buffer.readableBytes();
        } finally {
            buffer.readerIndex(readerIndex);
        }
    }
}
