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
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 INVENTORY_CONTENT（数据包 0x31）的线上布局辅助工具。
 * <p>
 * INVENTORY_SLOT 在 974 增加了可选 boolean 分支，但 Nukkit-MOT 从未把
 * 该分支加到内容数据包上。官方 975 和网易 860 仍然都写入：
 * {@code containerId, items, required full container name, required storage item}。
 * 物品在协议 1001 之前使用旧的槽位编解码器，1001 起改用网络物品堆描述符，
 * 所以编码器必须由协议号决定（{@link ItemRewriter#itemType(int)}）。
 * 把任一种包按可选容器字段来读取，会漏掉真正的物品载荷，
 * 使背包取出看起来是空的。
 */
public final class InventoryContentLayout {

    private InventoryContentLayout() {
    }

    public static boolean usesRequiredContainerFields() {
        return true;
    }

    public static boolean usesRequiredContainerFields(final boolean emulateNetEase, final int protocol) {
        return true;
    }

    public static DecodedInventoryContent read(final PacketWrapper wrapper, final ItemRewriter itemRewriter) {
        return read(wrapper, itemRewriter, BedrockWire.current());
    }

    public static DecodedInventoryContent read(final PacketWrapper wrapper, final ItemRewriter itemRewriter,
                                                final int protocol) {
        final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final BedrockItem[] items = wrapper.read(itemRewriter.itemArrayType(protocol));
        final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME);
        final BedrockItem storageItem = wrapper.read(itemRewriter.itemType(protocol));
        return new DecodedInventoryContent(containerId, items, containerName, storageItem);
    }

    public static DecodedInventoryContent read(final ByteBuf buffer, final Type<BedrockItem> itemType) {
        final int containerId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final int itemCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final BedrockItem[] items = new BedrockItem[itemCount];
        for (int i = 0; i < itemCount; i++) {
            items[i] = itemType.read(buffer);
        }
        final FullContainerName containerName = BedrockTypes.FULL_CONTAINER_NAME.read(buffer);
        final BedrockItem storageItem = itemType.read(buffer);
        return new DecodedInventoryContent(containerId, items, containerName, storageItem);
    }

    public static void writeRequiredContainerFields(final ByteBuf buffer, final int containerId,
                                                    final BedrockItem[] items, final FullContainerName containerName,
                                                    final BedrockItem storageItem, final Type<BedrockItem> itemType) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, containerId);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, items.length);
        for (final BedrockItem item : items) {
            itemType.write(buffer, item);
        }
        BedrockTypes.FULL_CONTAINER_NAME.write(buffer, containerName);
        itemType.write(buffer, storageItem);
    }

    public record DecodedInventoryContent(int containerId, BedrockItem[] items, FullContainerName containerName,
                                          BedrockItem storageItem) {
    }
}
