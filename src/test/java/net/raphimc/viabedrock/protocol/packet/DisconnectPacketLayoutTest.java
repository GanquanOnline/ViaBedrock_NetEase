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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisconnectPacketLayoutTest {

    @Test
    void hideScreenIsBooleanBelow974OnBothAxes() {
        assertFalse(DisconnectPacketLayout.usesUnsignedHideScreen(true, 860));
        assertFalse(DisconnectPacketLayout.usesUnsignedHideScreen(false, 860));
        assertFalse(DisconnectPacketLayout.usesUnsignedHideScreen(false, 897));
        assertFalse(DisconnectPacketLayout.usesUnsignedHideScreen(false, 898));
        assertFalse(DisconnectPacketLayout.usesUnsignedHideScreen(false, 924));
        assertFalse(DisconnectPacketLayout.usesUnsignedHideScreen(false, 944));
        assertFalse(DisconnectPacketLayout.usesUnsignedHideScreen(false, 973));
        assertTrue(DisconnectPacketLayout.usesUnsignedHideScreen(true, 974));
        assertTrue(DisconnectPacketLayout.usesUnsignedHideScreen(false, 974));
        assertTrue(DisconnectPacketLayout.usesUnsignedHideScreen(false, 975));
    }

    @Test
    void parsesNetease860HideScreenBoolean() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            DisconnectPacketLayout.writeHideScreen(buffer, false, true, 860);
            assertTrue(DisconnectPacketLayout.readHasMessage(buffer, true, 860));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialDisconnectStillUsesUnsignedHideScreen() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            DisconnectPacketLayout.writeHideScreen(buffer, false, false, 975);
            assertTrue(DisconnectPacketLayout.readHasMessage(buffer, false, 975));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
