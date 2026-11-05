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
package net.raphimc.viabedrock.experimental.model.inventory;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ItemUseInventoryTransaction_TriggerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemReleaseInventoryTransaction_ActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemUseInventoryTransaction_ActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemUseInventoryTransaction_PredictedResult;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemUseOnActorInventoryTransaction_ActionType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.Position3f;

public interface InventoryTransactionData {

    // NormalTransactionData 表示普通物品栏事务（例如合成）的事务数据对象，不含任何内容。
    record NormalTransactionData() implements InventoryTransactionData {}

    // MismatchTransactionData 表示不匹配的物品栏事务的数据对象。
    record MismatchTransactionData() implements InventoryTransactionData {}


    // UseItemTransactionData 表示客户端对方块使用物品时发送的物品栏事务数据对象。
    record UseItemTransactionData(
            ItemUseInventoryTransaction_ActionType actionType,
            ItemUseInventoryTransaction_TriggerType triggerType,
            BlockPosition blockPosition,
            int face,
            int hotbarSlot,
            BedrockItem itemInHand,
            Position3f playerPosition,
            Position3f clickPosition,
            int blockRuntimeId,
            ItemUseInventoryTransaction_PredictedResult predictedResult,
            byte clientCooldownState
    ) implements InventoryTransactionData {}

    // UseItemOnEntityTransactionData 表示客户端对实体使用物品时发送的物品栏事务数据对象。
    record UseItemOnEntityTransactionData(
            long entityRuntimeId,
            ItemUseOnActorInventoryTransaction_ActionType actionType,
            int hotbarSlot,
            BedrockItem itemInHand,
            Position3f playerPosition,
            Position3f clickPosition
    ) implements InventoryTransactionData {}

    // ReleaseItemTransactionData 表示客户端松开正在使用的物品时发送的物品栏事务数据对象，
    // 例如进食中途停止或停止拉弓。
    record ReleaseItemTransactionData(
            ItemReleaseInventoryTransaction_ActionType actionType,
            int hotbarSlot,
            BedrockItem itemInHand,
            Position3f headPosition
    ) implements InventoryTransactionData {}

}
