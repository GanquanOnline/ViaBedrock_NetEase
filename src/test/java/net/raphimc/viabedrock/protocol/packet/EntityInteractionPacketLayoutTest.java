/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
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
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EntityInteractionPacketLayoutTest {

    private static final Type<BedrockItem> TEST_ITEM_TYPE = new Type<>(BedrockItem.class) {
        @Override
        public BedrockItem read(final ByteBuf buffer) {
            return new BedrockItem(BedrockTypes.VAR_INT.read(buffer), (short) 0, buffer.readByte());
        }

        @Override
        public void write(final ByteBuf buffer, final BedrockItem value) {
            BedrockTypes.VAR_INT.write(buffer, value.identifier());
            buffer.writeByte(value.amount());
        }
    };

    @Test
    void encodesKnownMOTTypeThreeInteractVector() throws Exception {
        assertEquals(0, ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue());
        final long runtimeId = 4_294_967_295L;
        final Position3f playerPosition = new Position3f(1.25F, 65.5F, -3.75F);
        final Position3f clickPosition = new Position3f(10F, 70F, 11.5F);
        final byte[] payload = EntityInteractionPacketLayout.encode(
                TEST_ITEM_TYPE,
                0,
                runtimeId,
                ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue(),
                0,
                7,
                new BedrockItem(355, (short) 0, (byte) 2),
                playerPosition,
                clickPosition
        );

        assertArrayEquals(HexFormat.of().parseHex(
                "000300ffffffff0f000ec605020000a03f00008342000070c00000204100008c4200003841"), payload);

        final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            assertEquals(0, BedrockTypes.VAR_INT.read(buffer));
            assertEquals(ComplexInventoryTransaction_Type.ItemUseOnEntityTransaction.getValue(),
                    BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(0, BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(0xFF, buffer.getUnsignedByte(buffer.readerIndex()));
            assertEquals(0xFF, buffer.getUnsignedByte(buffer.readerIndex() + 1));
            assertEquals(0xFF, buffer.getUnsignedByte(buffer.readerIndex() + 2));
            assertEquals(0xFF, buffer.getUnsignedByte(buffer.readerIndex() + 3));
            assertEquals(0x0F, buffer.getUnsignedByte(buffer.readerIndex() + 4));
            assertEquals(runtimeId, BedrockTypes.UNSIGNED_VAR_LONG.read(buffer));
            assertEquals(ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue(),
                    BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(7, BedrockTypes.VAR_INT.read(buffer));
            final BedrockItem itemInHand = TEST_ITEM_TYPE.read(buffer);
            assertEquals(355, itemInHand.identifier());
            assertEquals(2, itemInHand.amount());
            assertEquals(playerPosition, BedrockTypes.POSITION_3F.read(buffer));
            assertEquals(clickPosition, BedrockTypes.POSITION_3F.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void encodesAttackOneAndRejectsUnhandledActionTwo() throws Exception {
        assertEquals(1, ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue());
        final byte[] payload = EntityInteractionPacketLayout.encode(
                TEST_ITEM_TYPE,
                0,
                -1L,
                ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue(),
                0,
                0,
                BedrockItem.empty(),
                Position3f.ZERO,
                new Position3f(0F, 1F, 0F)
        );
        final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            BedrockTypes.VAR_INT.read(buffer);
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            assertEquals(-1L, BedrockTypes.UNSIGNED_VAR_LONG.read(buffer));
            assertEquals(ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue(),
                    BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(0, BedrockTypes.VAR_INT.read(buffer));
            final BedrockItem itemInHand = TEST_ITEM_TYPE.read(buffer);
            assertEquals(0, itemInHand.identifier());
            assertEquals(0, itemInHand.amount());
            assertEquals(Position3f.ZERO, BedrockTypes.POSITION_3F.read(buffer));
            assertEquals(new Position3f(0F, 1F, 0F), BedrockTypes.POSITION_3F.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }

        assertThrows(IllegalArgumentException.class, () -> EntityInteractionPacketLayout.encode(
                TEST_ITEM_TYPE, 0, 1L, 2, 0, 0, BedrockItem.empty(), Position3f.ZERO, Position3f.ZERO));
    }
    @Test
    void writesUseItemOnEntityHandAt2225() {
        final byte[] payload = EntityInteractionPacketLayout.encode(
                TEST_ITEM_TYPE,
                2225,
                1L,
                ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue(),
                1,
                3,
                new BedrockItem(355, (short) 0, (byte) 2),
                Position3f.ZERO,
                Position3f.ZERO
        );
        final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            assertEquals(0, BedrockTypes.VAR_INT.read(buffer));
            assertFalse(buffer.readBoolean());
            assertEquals(ComplexInventoryTransaction_Type.ItemUseOnEntityTransaction.getValue(),
                    BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(0, BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(1L, BedrockTypes.UNSIGNED_VAR_LONG.read(buffer));
            assertEquals(ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue(),
                    BedrockTypes.VAR_INT.read(buffer));
            assertEquals(3, BedrockTypes.VAR_INT.read(buffer));
            assertEquals(1, buffer.readUnsignedByte());
            assertEquals(355, TEST_ITEM_TYPE.read(buffer).identifier());
            assertEquals(Position3f.ZERO, BedrockTypes.POSITION_3F.read(buffer));
            assertEquals(Position3f.ZERO, BedrockTypes.POSITION_3F.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void omitsUseItemOnEntityHandBelow2225() {
        final byte[] payload = EntityInteractionPacketLayout.encode(
                TEST_ITEM_TYPE,
                2192,
                1L,
                ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue(),
                1,
                3,
                new BedrockItem(355, (short) 0, (byte) 2),
                Position3f.ZERO,
                Position3f.ZERO
        );
        final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            BedrockTypes.VAR_INT.read(buffer);
            buffer.readBoolean();
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            BedrockTypes.UNSIGNED_VAR_LONG.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(3, BedrockTypes.VAR_INT.read(buffer));
            assertEquals(355, TEST_ITEM_TYPE.read(buffer).identifier());
            assertEquals(Position3f.ZERO, BedrockTypes.POSITION_3F.read(buffer));
            assertEquals(Position3f.ZERO, BedrockTypes.POSITION_3F.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

}
