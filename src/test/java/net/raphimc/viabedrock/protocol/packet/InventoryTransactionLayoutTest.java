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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTransactionLayoutTest {

    @Test
    void netease860OmitsClientCooldownState() {
        assertFalse(InventoryTransactionLayout.usesClientCooldownState(true, 860));
        assertFalse(InventoryTransactionLayout.usesClientCooldownState(true, 943));
        assertTrue(InventoryTransactionLayout.usesClientCooldownState(true, 944));
        assertFalse(InventoryTransactionLayout.usesClientCooldownState(false, 860), "官方 860 没有冷却字节");
        assertTrue(InventoryTransactionLayout.usesClientCooldownState(false, 944));
        assertTrue(InventoryTransactionLayout.usesClientCooldownState(false, 975));
    }

    @Test
    void netease860UseItemTrailerHasNoCooldownByte() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventoryTransactionLayout.writeClientCooldownState(buffer, (byte) 1, 860);
            assertEquals(0, buffer.readableBytes());
            assertEquals(0, InventoryTransactionLayout.readClientCooldownState(buffer, 860));
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860UseItemAs944ReadsPastThePacket() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventoryTransactionLayout.writeClientCooldownState(buffer, (byte) 1, 860);
            assertThrows(IndexOutOfBoundsException.class,
                    () -> InventoryTransactionLayout.readClientCooldownState(buffer, 944));
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialUseItemStillWritesCooldownByte() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            InventoryTransactionLayout.writeClientCooldownState(buffer, (byte) 3, 975);
            assertEquals(1, buffer.readableBytes());
            assertEquals(3, InventoryTransactionLayout.readClientCooldownState(buffer, 975));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
