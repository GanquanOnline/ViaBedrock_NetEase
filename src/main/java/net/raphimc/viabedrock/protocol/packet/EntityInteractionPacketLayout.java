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
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ComplexInventoryTransaction_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemUseOnActorInventoryTransaction_ActionType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public final class EntityInteractionPacketLayout {

    private EntityInteractionPacketLayout() {
    }

    /**
     * 写一个独立的 INVENTORY_TRANSACTION 包（类型 3）的载荷。
     * <p>
     * 1001 起实体交互事务与其它事务用同一个外壳：legacy 槽位存在性 bool、动作数前的恒 true
     * bool、有符号 varint 的 actionType、网络物品堆描述符。2225 起 hotbarSlot 之后多一个手持字节。
     * 旧形状（{@code protocol < 1001}）下这些分支全不生效，输出跟之前一致。
     */
    public static byte[] encode(final Type<BedrockItem> itemType, final int protocol, final long entityRuntimeId,
                                final int actionType, final int hand, final int hotbarSlot,
                                final BedrockItem itemInHand, final Position3f playerPosition,
                                final Position3f clickPosition) {
        final int interact = ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue();
        final int attack = ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue();
        if (actionType != interact && actionType != attack) {
            throw new IllegalArgumentException("Unsupported MOT entity action type: " + actionType);
        }

        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.VAR_INT.write(buffer, 0); // 旧版请求 ID
            if (InventoryTransactionLayout.usesLegacySlotFlag(protocol)) {
                buffer.writeBoolean(false); // legacy 槽位不存在
            }
            if (InventoryTransactionLayout.usesConstantBooleans(protocol)) {
                buffer.writeBoolean(true);
            }
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, ComplexInventoryTransaction_Type.ItemUseOnEntityTransaction.getValue());
            if (InventoryTransactionLayout.usesConstantBooleans(protocol)) {
                buffer.writeBoolean(true);
            }
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 动作数量
            BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, entityRuntimeId);
            InventoryTransactionLayout.writeActionType(buffer, actionType, protocol);
            BedrockTypes.VAR_INT.write(buffer, hotbarSlot);
            if (InventoryTransactionLayout.usesUseItemOnEntityHand(protocol)) {
                buffer.writeByte(hand);
            }
            itemType.write(buffer, itemInHand);
            BedrockTypes.POSITION_3F.write(buffer, playerPosition);
            BedrockTypes.POSITION_3F.write(buffer, clickPosition);

            final byte[] payload = new byte[buffer.readableBytes()];
            buffer.readBytes(payload);
            return payload;
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to encode MOT entity interaction transaction", e);
        } finally {
            buffer.release();
        }
    }

}
