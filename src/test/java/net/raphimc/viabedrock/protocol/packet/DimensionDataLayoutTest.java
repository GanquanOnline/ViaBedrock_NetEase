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
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DimensionDataLayoutTest {

    @Test
    void dimensionTypeStartsAt975() {
        assertFalse(DimensionDataLayout.usesDimensionType(true, 860));
        assertFalse(DimensionDataLayout.usesDimensionType(true, 974));
        assertTrue(DimensionDataLayout.usesDimensionType(true, 975));
        assertFalse(DimensionDataLayout.usesDimensionType(false, 860));
        assertFalse(DimensionDataLayout.usesDimensionType(false, 974));
        assertTrue(DimensionDataLayout.usesDimensionType(false, 975));
    }

    @Test
    void parsesNetease860EntriesWithoutLeavingUnreadBytes() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeEntry(buffer, "minecraft:overworld", 320, -64, 1, null, true, 860);
            writeEntry(buffer, "minecraft:nether", 128, 0, 2, null, true, 860);

            assertEquals("minecraft:overworld", BedrockTypes.STRING.read(buffer));
            assertEquals(320, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(-64, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(1, (int) BedrockTypes.VAR_INT.read(buffer));
            assertNull(DimensionDataLayout.readDimensionType(buffer, true, 860));

            assertEquals("minecraft:nether", BedrockTypes.STRING.read(buffer));
            assertEquals(128, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(0, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(2, (int) BedrockTypes.VAR_INT.read(buffer));
            assertNull(DimensionDataLayout.readDimensionType(buffer, true, 860));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860EntriesAs975OverReadsTheNextIdentifier() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeEntry(buffer, "minecraft:overworld", 320, -64, 1, null, true, 860);
            writeEntry(buffer, "minecraft:nether", 128, 0, 2, null, true, 860);

            BedrockTypes.STRING.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            DimensionDataLayout.skipDimensionType(buffer, false, 975);

            assertTrue(buffer.isReadable());
            assertThrows(Exception.class, () -> {
                assertEquals("minecraft:nether", BedrockTypes.STRING.read(buffer));
            });
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingASingleNetease860EntryAs975ReadsPastThePacket() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeEntry(buffer, "minecraft:overworld", 320, -64, 1, null, true, 860);
            BedrockTypes.STRING.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            BedrockTypes.VAR_INT.read(buffer);
            assertFalse(buffer.isReadable());
            assertThrows(IndexOutOfBoundsException.class, () -> DimensionDataLayout.skipDimensionType(buffer, false, 975));
        } finally {
            buffer.release();
        }
    }

    @Test
    void parsesOfficial975EntriesIncludingDimensionType() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeEntry(buffer, "minecraft:overworld", 384, -64, 1, 0, false, 975);
            assertEquals("minecraft:overworld", BedrockTypes.STRING.read(buffer));
            assertEquals(384, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(-64, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(1, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(0, DimensionDataLayout.readDimensionType(buffer, false, 975));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static void writeEntry(final ByteBuf buffer, final String identifier, final int maximumHeight,
                                   final int minimumHeight, final int generatorType, final Integer dimensionType,
                                   final boolean emulateNetEase, final int protocol) {
        BedrockTypes.STRING.write(buffer, identifier);
        BedrockTypes.VAR_INT.write(buffer, maximumHeight);
        BedrockTypes.VAR_INT.write(buffer, minimumHeight);
        BedrockTypes.VAR_INT.write(buffer, generatorType);
        if (dimensionType != null) {
            DimensionDataLayout.writeDimensionType(buffer, dimensionType, emulateNetEase, protocol);
        }
    }
}
