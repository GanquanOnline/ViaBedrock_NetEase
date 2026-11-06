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
package net.raphimc.viabedrock.experimental.types.inventory;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.packet.InventoryTransactionLayout;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public class InventoryActionDataType extends Type<InventoryActionData> {

    private final UserConnection user;

    public InventoryActionDataType(final UserConnection user) {
        super(InventoryActionData.class);

        this.user = user;
    }

    @Override
    public InventoryActionData read(ByteBuf buffer) {
        final int protocol = BedrockWire.current();
        return read(buffer, itemType(protocol), InventoryTransactionLayout.SourceShape.of(protocol));
    }

    /**
     * 动作读取按形状而非协议号分支：独立 INVENTORY_TRANSACTION 包用 1001 形状，
     * PLAYER_AUTH_INPUT 内嵌载荷到 2168 才换，所以两个开关由调用方决定。
     */
    public InventoryActionData read(ByteBuf buffer, final Type<BedrockItem> itemType,
                                    final InventoryTransactionLayout.SourceShape sourceShape) {
        final InventorySource source = InventorySourcePacketType.read(buffer, sourceShape);
        final int slot = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        final BedrockItem fromItem = itemType.read(buffer);
        final BedrockItem toItem = itemType.read(buffer);

        return new InventoryActionData(source, slot, fromItem, toItem);
    }

    @Override
    public void write(ByteBuf buffer, InventoryActionData value) {
        final int protocol = BedrockWire.current();
        write(buffer, value, itemType(protocol), InventoryTransactionLayout.SourceShape.of(protocol));
    }

    public void write(ByteBuf buffer, final InventoryActionData value, final Type<BedrockItem> itemType,
                      final InventoryTransactionLayout.SourceShape sourceShape) {
        InventorySourcePacketType.write(buffer, value.source(), sourceShape);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, value.slot());
        itemType.write(buffer, value.fromItem());
        itemType.write(buffer, value.toItem());
    }

    private Type<BedrockItem> itemType(final int protocol) {
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        if (itemRewriter == null) {
            throw new IllegalStateException("ItemRewriter not found for user " + user);
        }
        return itemRewriter.itemType(protocol);
    }
}
