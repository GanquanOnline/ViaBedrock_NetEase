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

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPositionLayoutTest {

    @Test
    void netease860UsesUnsignedY() {
        assertTrue(BlockPositionLayout.usesUnsignedY(true, 860));
        assertTrue(BlockPositionLayout.usesUnsignedY(true, 943));
        assertFalse(BlockPositionLayout.usesUnsignedY(true, 944));
        assertTrue(BlockPositionLayout.usesUnsignedY(false, 860), "官方 860 仍在 944 之前");
        assertTrue(BlockPositionLayout.usesUnsignedY(false, 943));
        assertFalse(BlockPositionLayout.usesUnsignedY(false, 944));
        assertFalse(BlockPositionLayout.usesUnsignedY(false, 975));
    }

    @Test
    void netease860Y64IsUnsignedNotZigZag() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeBlockVector3(buffer, 10, 64, -20, true);
            assertEquals(10, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(64, (int) BedrockTypes.UNSIGNED_VAR_INT.read(buffer));
            assertEquals(-20, (int) BedrockTypes.VAR_INT.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860Y64AsOfficialReads32() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeBlockVector3(buffer, 10, 64, -20, true);
            BedrockTypes.VAR_INT.read(buffer);
            assertEquals(32, (int) BedrockTypes.VAR_INT.read(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975Y64IsZigZag() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeBlockVector3(buffer, 10, 64, -20, false);
            assertEquals(10, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(64, (int) BedrockTypes.VAR_INT.read(buffer));
            assertEquals(-20, (int) BedrockTypes.VAR_INT.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void signedPositionAlwaysUsesZigZagY() {
        final BlockPosition position = new BlockPosition(1, -64, 8);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.SIGNED_BLOCK_POSITION.write(buffer, position);
            assertEquals(position, BedrockTypes.SIGNED_BLOCK_POSITION.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static void writeBlockVector3(final ByteBuf buffer, final int x, final int y, final int z, final boolean unsignedY) {
        BedrockTypes.VAR_INT.write(buffer, x);
        if (unsignedY) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, y);
        } else {
            BedrockTypes.VAR_INT.write(buffer, y);
        }
        BedrockTypes.VAR_INT.write(buffer, z);
    }
}

