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
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.storage.CreativeContentCache;
import net.raphimc.viabedrock.experimental.types.inventory.InstanceItemType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Bedrock CREATIVE_CONTENT（数据包 0x91 / 145）的线上布局辅助方法。
 * <p>
 * 官方 975 与网易 860 都从协议 776 起写入分组，然后为每个条目
 * 写入从 1 开始的创造模式网络 ID 外加一个实例物品。Nukkit 860
 * 仍然使用旧的物品编解码器，而不是 NetworkItemStackDescriptor。
 */
public final class CreativeContentLayout {

    public static final int GROUPS_PROTOCOL = 776;

    private CreativeContentLayout() {
    }

    public static boolean usesGroups(final boolean emulateNetEase, final int protocol) {
        return protocol >= GROUPS_PROTOCOL;
    }

    /**
     * Nukkit 用 putSlot(..., true) / 实例物品布局写入 CREATIVE_CONTENT 条目：
     * 没有 stack-network-id 布尔值。用 ItemRewriter.itemType() 去读，
     * 会把下一个字段当成那个布尔值消费掉，导致数据包其余部分失步。
     */
    public static Type<BedrockItem> itemType() {
        return InstanceItemType.INSTANCE;
    }

    public static List<CreativeContentCache.Entry> read(final PacketWrapper wrapper, final Type<BedrockItem> itemType,
                                                        final boolean emulateNetEase, final int protocol) {
        if (usesGroups(emulateNetEase, protocol)) {
            final int groupCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            for (int i = 0; i < groupCount; i++) {
                skipGroup(wrapper, itemType, protocol);
            }
        }
        final int itemCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        final List<CreativeContentCache.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            final int netId = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            final BedrockItem item = wrapper.read(itemType);
            if (usesGroups(emulateNetEase, protocol)) {
                wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            }
            if (item != null && !item.isEmpty()) {
                entries.add(new CreativeContentCache.Entry(netId, item.copy()));
            }
        }
        return entries;
    }

    public static List<CreativeContentCache.Entry> read(final ByteBuf buffer, final Type<BedrockItem> itemType,
                                                        final boolean emulateNetEase, final int protocol) {
        if (usesGroups(emulateNetEase, protocol)) {
            final int groupCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            for (int i = 0; i < groupCount; i++) {
                skipGroup(buffer, itemType, protocol);
            }
        }
        final int itemCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final List<CreativeContentCache.Entry> entries = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            final int netId = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            final BedrockItem item = itemType.read(buffer);
            if (usesGroups(emulateNetEase, protocol)) {
                BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            }
            if (item != null && !item.isEmpty()) {
                entries.add(new CreativeContentCache.Entry(netId, item.copy()));
            }
        }
        return entries;
    }

    public static void write(final ByteBuf buffer, final List<CreativeContentCache.Entry> entries,
                             final Type<BedrockItem> itemType, final boolean emulateNetEase, final int protocol) {
        if (usesGroups(emulateNetEase, protocol)) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, entries.size());
        for (final CreativeContentCache.Entry entry : entries) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, entry.netId());
            itemType.write(buffer, entry.item());
            if (usesGroups(emulateNetEase, protocol)) {
                BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0);
            }
        }
    }

    private static void skipGroup(final PacketWrapper wrapper, final Type<BedrockItem> itemType, final int protocol) {
        if (protocol >= ItemStackRequestLayout.UNSIGNED_ACTION_TYPE_PROTOCOL) {
            wrapper.read(Types.BYTE);
        } else {
            wrapper.read(BedrockTypes.INT_LE);
        }
        wrapper.read(BedrockTypes.STRING);
        wrapper.read(itemType);
    }

    private static void skipGroup(final ByteBuf buffer, final Type<BedrockItem> itemType, final int protocol) {
        if (protocol >= ItemStackRequestLayout.UNSIGNED_ACTION_TYPE_PROTOCOL) {
            buffer.readByte();
        } else {
            BedrockTypes.INT_LE.read(buffer);
        }
        BedrockTypes.STRING.read(buffer);
        itemType.read(buffer);
    }
}
