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
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryTransactionData;
import net.raphimc.viabedrock.experimental.model.inventory.LegacySetItemSlotData;
import net.raphimc.viabedrock.experimental.types.ExperimentalBedrockTypes;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ItemUseInventoryTransaction_TriggerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.InventoryTransactionLayout;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.List;

public class InventoryTransactionPacketType extends Type<BedrockInventoryTransaction> {

    private final UserConnection user;
    private final InventoryActionDataType inventoryActionDataType;

    public InventoryTransactionPacketType(final UserConnection user, final InventoryActionDataType inventoryActionDataType) {
        super(BedrockInventoryTransaction.class);
        this.user = user;
        this.inventoryActionDataType = inventoryActionDataType;
    }

    @Override
    public BedrockInventoryTransaction read(ByteBuf buffer) {
        final ItemRewriter itemRewriter = this.itemRewriter();
        final int protocol = BedrockWire.current();
        final Type<BedrockItem> itemType = itemRewriter.itemType(protocol);

        final int legacyRequestId = BedrockTypes.VAR_INT.read(buffer);
        LegacySetItemSlotData[] legacySlots = new LegacySetItemSlotData[0];
        if (InventoryTransactionLayout.usesLegacySlotFlag(protocol)) {
            if (buffer.readBoolean()) { // legacy 槽位是否存在
                legacySlots = ExperimentalBedrockTypes.LEGACY_SET_ITEM_SLOT_DATA.read(buffer);
            }
        } else if (legacyRequestId != 0) {
            legacySlots = ExperimentalBedrockTypes.LEGACY_SET_ITEM_SLOT_DATA.read(buffer);
        }
        if (InventoryTransactionLayout.usesConstantBooleans(protocol)) {
            buffer.readBoolean(); // 恒 true，MOT 读侧为 false 会直接抛异常
        }
        ComplexInventoryTransaction_Type type = ComplexInventoryTransaction_Type.getByValue(BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
        if (InventoryTransactionLayout.usesConstantBooleans(protocol)) {
            buffer.readBoolean(); // 恒 true，位于动作数之前
        }
        InventoryActionData[] actions = this.readActions(buffer, itemType, InventoryTransactionLayout.SourceShape.of(protocol));
        InventoryTransactionData transactionData = switch (type) {
            case NormalTransaction ->  new InventoryTransactionData.NormalTransactionData();
            case InventoryMismatch -> new InventoryTransactionData.MismatchTransactionData();
            case ItemUseTransaction -> {
                final ItemUseInventoryTransaction_ActionType actionType = ItemUseInventoryTransaction_ActionType.getByValue(InventoryTransactionLayout.readActionType(buffer, protocol));
                final ItemUseInventoryTransaction_TriggerType triggerType = ItemUseInventoryTransaction_TriggerType.getByValue(InventoryTransactionLayout.readUnsignedEnum(buffer, protocol));
                final BlockPosition blockPosition = BedrockTypes.BLOCK_POSITION.read(buffer);
                final int face = InventoryTransactionLayout.readFace(buffer, protocol);
                final int hotbarSlot = BedrockTypes.VAR_INT.read(buffer);
                final int hand = InventoryTransactionLayout.usesUseItemHand(protocol) ? buffer.readUnsignedByte() : 0;
                yield new InventoryTransactionData.UseItemTransactionData(
                        actionType,
                        triggerType,
                        blockPosition,
                        face,
                        hotbarSlot,
                        itemType.read(buffer),
                        BedrockTypes.POSITION_3F.read(buffer),
                        BedrockTypes.POSITION_3F.read(buffer),
                        BedrockTypes.UNSIGNED_VAR_INT.read(buffer),
                        ItemUseInventoryTransaction_PredictedResult.getByValue(InventoryTransactionLayout.readUnsignedEnum(buffer, protocol)),
                        InventoryTransactionLayout.readClientCooldownState(buffer),
                        hand
                );
            }
            case ItemUseOnEntityTransaction -> {
                final long entityRuntimeId = readEntityRuntimeId(buffer);
                final ItemUseOnActorInventoryTransaction_ActionType actionType = ItemUseOnActorInventoryTransaction_ActionType.getByValue(InventoryTransactionLayout.readActionType(buffer, protocol));
                final int hotbarSlot = BedrockTypes.VAR_INT.read(buffer);
                final int hand = InventoryTransactionLayout.usesUseItemOnEntityHand(protocol) ? buffer.readUnsignedByte() : 0;
                yield new InventoryTransactionData.UseItemOnEntityTransactionData(
                        entityRuntimeId,
                        actionType,
                        hotbarSlot,
                        itemType.read(buffer),
                        BedrockTypes.POSITION_3F.read(buffer),
                        BedrockTypes.POSITION_3F.read(buffer),
                        hand
                );
            }
            case ItemReleaseTransaction -> {
                final ItemReleaseInventoryTransaction_ActionType actionType = ItemReleaseInventoryTransaction_ActionType.getByValue(InventoryTransactionLayout.readActionType(buffer, protocol));
                final int hotbarSlot = BedrockTypes.VAR_INT.read(buffer);
                final BedrockItem itemInHand = itemType.read(buffer);
                final Position3f headPosition = BedrockTypes.POSITION_3F.read(buffer);
                yield new InventoryTransactionData.ReleaseItemTransactionData(
                        actionType,
                        hotbarSlot,
                        itemInHand,
                        headPosition,
                        InventoryTransactionLayout.usesReleaseItemHand(protocol) ? buffer.readUnsignedByte() : 0
                );
            }
        };

        return new BedrockInventoryTransaction(legacyRequestId, List.of(legacySlots), List.of(actions), type, transactionData);
    }

    static long readEntityRuntimeId(final ByteBuf buffer) {
        return BedrockTypes.UNSIGNED_VAR_LONG.read(buffer);
    }

    static void writeEntityRuntimeId(final ByteBuf buffer, final long runtimeId) {
        BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, runtimeId);
    }

    @Override
    public void write(ByteBuf buffer, BedrockInventoryTransaction bedrockInventoryTransaction) {
        final ItemRewriter itemRewriter = this.itemRewriter();
        final int protocol = BedrockWire.current();
        final Type<BedrockItem> itemType = itemRewriter.itemType(protocol);

        BedrockTypes.VAR_INT.write(buffer, bedrockInventoryTransaction.legacyRequestId());
        if (InventoryTransactionLayout.usesLegacySlotFlag(protocol)) {
            final boolean hasLegacySlots = bedrockInventoryTransaction.legacyRequestId() != 0;
            buffer.writeBoolean(hasLegacySlots);
            if (hasLegacySlots) {
                ExperimentalBedrockTypes.LEGACY_SET_ITEM_SLOT_DATA.write(buffer, bedrockInventoryTransaction.legacySlots().toArray(new LegacySetItemSlotData[0]));
            }
        } else if (bedrockInventoryTransaction.legacyRequestId() != 0) {
            ExperimentalBedrockTypes.LEGACY_SET_ITEM_SLOT_DATA.write(buffer, bedrockInventoryTransaction.legacySlots().toArray(new LegacySetItemSlotData[0]));
        }
        if (InventoryTransactionLayout.usesConstantBooleans(protocol)) {
            buffer.writeBoolean(true);
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, bedrockInventoryTransaction.transactionType().getValue());
        if (InventoryTransactionLayout.usesConstantBooleans(protocol)) {
            buffer.writeBoolean(true);
        }
        this.writeActions(buffer, bedrockInventoryTransaction.actions(), itemType, InventoryTransactionLayout.SourceShape.of(protocol));
        switch (bedrockInventoryTransaction.transactionType()) {
            case NormalTransaction, InventoryMismatch -> {
                // 没有额外数据要写入
            }
            case ItemUseTransaction -> {
                InventoryTransactionData.UseItemTransactionData data = (InventoryTransactionData.UseItemTransactionData) bedrockInventoryTransaction.transactionData();
                this.writeUseItemTransactionData(buffer, data, itemType, protocol);
            }
            case ItemUseOnEntityTransaction -> {
                InventoryTransactionData.UseItemOnEntityTransactionData data = (InventoryTransactionData.UseItemOnEntityTransactionData) bedrockInventoryTransaction.transactionData();
                writeEntityRuntimeId(buffer, data.entityRuntimeId());
                InventoryTransactionLayout.writeActionType(buffer, data.actionType().getValue(), protocol);
                BedrockTypes.VAR_INT.write(buffer, data.hotbarSlot());
                if (InventoryTransactionLayout.usesUseItemOnEntityHand(protocol)) {
                    buffer.writeByte(data.hand());
                }
                itemType.write(buffer, data.itemInHand());
                BedrockTypes.POSITION_3F.write(buffer, data.playerPosition());
                BedrockTypes.POSITION_3F.write(buffer, data.clickPosition());
            }
            case ItemReleaseTransaction -> {
                InventoryTransactionData.ReleaseItemTransactionData data = (InventoryTransactionData.ReleaseItemTransactionData) bedrockInventoryTransaction.transactionData();
                InventoryTransactionLayout.writeActionType(buffer, data.actionType().getValue(), protocol);
                BedrockTypes.VAR_INT.write(buffer, data.hotbarSlot());
                itemType.write(buffer, data.itemInHand());
                BedrockTypes.POSITION_3F.write(buffer, data.headPosition());
                if (InventoryTransactionLayout.usesReleaseItemHand(protocol)) {
                    buffer.writeByte(data.hand());
                }
            }
        }
    }

    /**
     * 写入内嵌在 PLAYER_AUTH_INPUT 里的物品交互载荷。MOT 的这部分解码要到 2168 才换成
     * 1001 形状，所以 1001 上这里仍是旧形状：没有 legacy 槽位存在性 bool、没有恒 true bool、
     * 枚举是 varint、物品是旧槽位编码。
     */
    public void writeItemInteractionData(ByteBuf buffer, BedrockInventoryTransaction bedrockInventoryTransaction) {
        final ItemRewriter itemRewriter = this.itemRewriter();
        final int protocol = BedrockWire.current();
        final boolean newShape = InventoryTransactionLayout.usesEmbeddedInteractionShape(protocol);
        final Type<BedrockItem> itemType = newShape ? itemRewriter.itemType(protocol) : itemRewriter.itemType();

        BedrockTypes.VAR_INT.write(buffer, bedrockInventoryTransaction.legacyRequestId());
        if (bedrockInventoryTransaction.legacyRequestId() != 0) {
            ExperimentalBedrockTypes.LEGACY_SET_ITEM_SLOT_DATA.write(buffer, bedrockInventoryTransaction.legacySlots().toArray(new LegacySetItemSlotData[0]));
        }
        this.writeActions(buffer, bedrockInventoryTransaction.actions(), itemType,
                InventoryTransactionLayout.SourceShape.of(newShape ? protocol : 0));
        this.writeUseItemTransactionData(buffer, (InventoryTransactionData.UseItemTransactionData) bedrockInventoryTransaction.transactionData(),
                itemType, newShape ? protocol : 0);
    }

    public void writeUseItemTransactionData(ByteBuf buffer, InventoryTransactionData.UseItemTransactionData data,
                                            final Type<BedrockItem> itemType, final int protocol) {
        InventoryTransactionLayout.writeActionType(buffer, data.actionType().getValue(), protocol);
        InventoryTransactionLayout.writeUnsignedEnum(buffer, data.triggerType().getValue(), protocol);
        BedrockTypes.BLOCK_POSITION.write(buffer, data.blockPosition());
        InventoryTransactionLayout.writeFace(buffer, data.face(), protocol);
        BedrockTypes.VAR_INT.write(buffer, data.hotbarSlot());
        if (InventoryTransactionLayout.usesUseItemHand(protocol)) {
            buffer.writeByte(data.hand());
        }
        itemType.write(buffer, data.itemInHand());
        BedrockTypes.POSITION_3F.write(buffer, data.playerPosition());
        BedrockTypes.POSITION_3F.write(buffer, data.clickPosition());
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, data.blockRuntimeId());
        InventoryTransactionLayout.writeUnsignedEnum(buffer, data.predictedResult().getValue(), protocol);
        InventoryTransactionLayout.writeClientCooldownState(buffer, data.clientCooldownState());
    }

    private InventoryActionData[] readActions(final ByteBuf buffer, final Type<BedrockItem> itemType,
                                              final InventoryTransactionLayout.SourceShape sourceShape) {
        final InventoryActionData[] actions = new InventoryActionData[BedrockTypes.UNSIGNED_VAR_INT.read(buffer)];
        for (int i = 0; i < actions.length; i++) {
            actions[i] = this.inventoryActionDataType.read(buffer, itemType, sourceShape);
        }
        return actions;
    }

    private void writeActions(final ByteBuf buffer, final List<InventoryActionData> actions,
                              final Type<BedrockItem> itemType,
                              final InventoryTransactionLayout.SourceShape sourceShape) {
        final InventoryActionData[] actionArray = actions != null ? actions.toArray(new InventoryActionData[0]) : new InventoryActionData[0];
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, actionArray.length);
        for (final InventoryActionData action : actionArray) {
            this.inventoryActionDataType.write(buffer, action, itemType, sourceShape);
        }
    }

    private ItemRewriter itemRewriter() {
        final ItemRewriter itemRewriter = this.user.get(ItemRewriter.class);
        if (itemRewriter == null) {
            throw new IllegalStateException("ItemRewriter not found for user " + this.user);
        }
        return itemRewriter;
    }
}
