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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerOpenLayoutTest {

    @Test
    void netease860UsesContainerOpenTrailer() {
        assertTrue(ContainerOpenLayout.usesNetEaseTrailer(true, 860));
        assertFalse(ContainerOpenLayout.usesNetEaseTrailer(false, 860));
        assertFalse(ContainerOpenLayout.usesNetEaseTrailer(false, 975));
    }

    @Test
    void parsesNetease860ContainerOpenTrailer() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ContainerOpenLayout.writeTrailer(buffer, true, 860);
            assertEquals(1, buffer.readableBytes());
            ContainerOpenLayout.skipTrailer(buffer, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease898AppendsExtendedTrailer() {
        assertTrue(ContainerOpenLayout.usesNetEaseExtendedTrailer(true, 898));
        assertFalse(ContainerOpenLayout.usesNetEaseExtendedTrailer(true, 860));
        assertFalse(ContainerOpenLayout.usesNetEaseExtendedTrailer(false, 975));
    }

    @Test
    void parsesNetease898ExtendedContainerOpenTrailer() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ContainerOpenLayout.writeTrailer(buffer, true, 898);
            // is-ignore-block 布尔 + 2 个空字符串 + is-closed-drop-item 布尔
            assertEquals(4, buffer.readableBytes());
            ContainerOpenLayout.skipTrailer(buffer, true, 898);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialContainerOpenHasNoTrailer() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ContainerOpenLayout.writeTrailer(buffer, false, 975);
            assertEquals(0, buffer.readableBytes());
            ContainerOpenLayout.skipTrailer(buffer, false, 975);
        } finally {
            buffer.release();
        }
    }
}
