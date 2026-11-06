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
import net.raphimc.viabedrock.protocol.model.Position3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateClientInputLocksLayoutTest {

    @Test
    void netease860KeepsServerPosition() {
        assertTrue(UpdateClientInputLocksLayout.usesServerPosition(true, 860));
        assertTrue(UpdateClientInputLocksLayout.usesServerPosition(true, 943));
        assertFalse(UpdateClientInputLocksLayout.usesServerPosition(true, 944));
        assertTrue(UpdateClientInputLocksLayout.usesServerPosition(false, 860), "官方 860 仍带服务端位置");
        assertFalse(UpdateClientInputLocksLayout.usesServerPosition(false, 944));
        assertFalse(UpdateClientInputLocksLayout.usesServerPosition(false, 975));
    }

    @Test
    void roundTripsNetease860MovementLock() {
        final Position3f position = new Position3f(1.5F, 64.0F, -8.25F);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            UpdateClientInputLocksLayout.write(buffer, UpdateClientInputLocksLayout.FLAG_MOVEMENT, position, true, 860);
            final UpdateClientInputLocksLayout.DecodedLocks decoded = UpdateClientInputLocksLayout.read(buffer, true, 860);
            assertFalse(buffer.isReadable());
            assertTrue(decoded.movementLocked());
            assertFalse(decoded.cameraLocked());
            assertEquals(position, decoded.serverPosition());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975OmitsServerPosition() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            UpdateClientInputLocksLayout.write(buffer, UpdateClientInputLocksLayout.FLAG_CAMERA, new Position3f(1, 2, 3), false, 975);
            final UpdateClientInputLocksLayout.DecodedLocks decoded = UpdateClientInputLocksLayout.read(buffer, false, 975);
            assertFalse(buffer.isReadable());
            assertTrue(decoded.cameraLocked());
            assertFalse(decoded.movementLocked());
            assertNull(decoded.serverPosition());
        } finally {
            buffer.release();
        }
    }
}
