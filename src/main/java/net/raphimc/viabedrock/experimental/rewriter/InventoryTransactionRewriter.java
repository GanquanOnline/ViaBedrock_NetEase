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
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.types.inventory.InventoryActionDataType;
import net.raphimc.viabedrock.experimental.types.inventory.InventoryTransactionPacketType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.array.ArrayType;

public class InventoryTransactionRewriter extends StoredObject {

    private final Type<BedrockInventoryTransaction> inventoryTransactionType;
    private final Type<BedrockInventoryTransaction> itemInteractionDataType;
    private final Type<InventoryActionData[]> inventoryActionDataType;

    public InventoryTransactionRewriter(final UserConnection user) {
        super(user);

        final InventoryActionDataType actionDataType = new InventoryActionDataType(user);
        this.inventoryActionDataType = new ArrayType<>(actionDataType, BedrockTypes.UNSIGNED_VAR_INT);
        final InventoryTransactionPacketType transactionPacketType = new InventoryTransactionPacketType(user, actionDataType);
        this.inventoryTransactionType = transactionPacketType;
        this.itemInteractionDataType = new Type<>(BedrockInventoryTransaction.class) {
            @Override
            public BedrockInventoryTransaction read(final ByteBuf buffer) {
                throw new UnsupportedOperationException("Item interaction data is only written by ViaBedrock");
            }

            @Override
            public void write(final ByteBuf buffer, final BedrockInventoryTransaction value) {
                transactionPacketType.writeItemInteractionData(buffer, value);
            }
        };
    }

    public Type<BedrockInventoryTransaction> getInventoryTransactionType() {
        return inventoryTransactionType;
    }

    public Type<InventoryActionData[]> getInventoryActionDataType() {
        return inventoryActionDataType;
    }

    public Type<BedrockInventoryTransaction> getItemInteractionDataType() {
        return itemInteractionDataType;
    }

}
