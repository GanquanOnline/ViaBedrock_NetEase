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
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.FullContainerName;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 INVENTORY_SLOT（数据包 0x32）的线上布局辅助工具。
 * <p>
 * 官方基岩 1.21.90+ / 协议 974+ 以及 Nukkit-MOT 的对应分支编码为：
 * {@code containerId, slot, optional full container name, optional storage item, item}。
 * <p>
 * 网易 / Nukkit-MOT 协议 860 仍使用 974 之前的布局：
 * {@code containerId, slot, required full container name, required storage item, item}。
 * 把该包按 974+ 处理会把容器名字节当作 boolean 读掉，
 * 从而漏掉真正的物品载荷。PacketWrapper 会把这些残留的基岩
 * 字节追加到 Java {@code container_set_slot} 上，1.21.11 会以多余数据拒绝它。
 */
public final class InventorySlotLayout {

    public static final int OPTIONAL_CONTAINER_FIELDS_PROTOCOL = 974;

    private InventorySlotLayout() {
    }

    public static boolean usesRequiredContainerFields() {
        return usesRequiredContainerFields(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesRequiredContainerFields(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol > 0 && protocol < OPTIONAL_CONTAINER_FIELDS_PROTOCOL;
    }

    public static DecodedInventorySlot read(final PacketWrapper wrapper, final ItemRewriter itemRewriter) {
        final int containerId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final int slot = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        if (usesRequiredContainerFields()) {
            final FullContainerName containerName = wrapper.read(BedrockTypes.FULL_CONTAINER_NAME);
            final BedrockItem storageItem = wrapper.read(itemRewriter.itemType());
            final BedrockItem item = wrapper.read(itemRewriter.itemType());
            return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
        }
        final FullContainerName containerName = wrapper.read(BedrockTypes.OPTIONAL_FULL_CONTAINER_NAME);
        final BedrockItem storageItem = wrapper.read(itemRewriter.optionalNewItemType());
        final BedrockItem item = wrapper.read(itemRewriter.newItemType());
        return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
    }

    public static DecodedInventorySlot read(final ByteBuf buffer, final Type<BedrockItem> itemType, final Type<BedrockItem> newItemType,
                                           final boolean emulateNetEase, final int protocol) {
        final int containerId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final int slot = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        if (usesRequiredContainerFields(emulateNetEase, protocol)) {
            final FullContainerName containerName = BedrockTypes.FULL_CONTAINER_NAME.read(buffer);
            final BedrockItem storageItem = itemType.read(buffer);
            final BedrockItem item = itemType.read(buffer);
            return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
        }
        final FullContainerName containerName = BedrockTypes.OPTIONAL_FULL_CONTAINER_NAME.read(buffer);
        final BedrockItem storageItem = buffer.readBoolean() ? newItemType.read(buffer) : null;
        final BedrockItem item = newItemType.read(buffer);
        return new DecodedInventorySlot(containerId, slot, containerName, storageItem, item);
    }

    public static void writeRequiredContainerFields(final ByteBuf buffer, final int containerId, final int slot,
                                                    final FullContainerName containerName, final BedrockItem storageItem,
                                                    final BedrockItem item, final Type<BedrockItem> itemType) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, containerId);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, slot);
        BedrockTypes.FULL_CONTAINER_NAME.write(buffer, containerName);
        itemType.write(buffer, storageItem);
        itemType.write(buffer, item);
    }

    public static void writeOptionalContainerFields(final ByteBuf buffer, final int containerId, final int slot,
                                                    final BedrockItem item, final Type<BedrockItem> newItemType) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, containerId);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, slot);
        buffer.writeBoolean(false);
        buffer.writeBoolean(false);
        newItemType.write(buffer, item);
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }

    public record DecodedInventorySlot(int containerId, int slot, FullContainerName containerName,
                                       BedrockItem storageItem, BedrockItem item) {
    }
}

