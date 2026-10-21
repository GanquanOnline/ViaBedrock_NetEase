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
package net.raphimc.viabedrock.protocol.rewriter.blockentity;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChestPairingTest {

    @Test
    void unpairedNbtIsSingle() {
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("north", 0, 0, new CompoundTag()));
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("north", 0, 0, null));
    }

    @Test
    void northFacingPairOffsets() {
        assertEquals(ChestPairing.TYPE_LEFT, ChestPairing.javaType("north", 1, 0));
        assertEquals(ChestPairing.TYPE_RIGHT, ChestPairing.javaType("north", -1, 0));
    }

    @Test
    void southFacingPairOffsets() {
        assertEquals(ChestPairing.TYPE_LEFT, ChestPairing.javaType("south", -1, 0));
        assertEquals(ChestPairing.TYPE_RIGHT, ChestPairing.javaType("south", 1, 0));
    }

    @Test
    void eastFacingPairOffsets() {
        assertEquals(ChestPairing.TYPE_LEFT, ChestPairing.javaType("east", 0, 1));
        assertEquals(ChestPairing.TYPE_RIGHT, ChestPairing.javaType("east", 0, -1));
    }

    @Test
    void westFacingPairOffsets() {
        assertEquals(ChestPairing.TYPE_LEFT, ChestPairing.javaType("west", 0, -1));
        assertEquals(ChestPairing.TYPE_RIGHT, ChestPairing.javaType("west", 0, 1));
    }

    @Test
    void nbtPairxPairzUsesBlockPosition() {
        final CompoundTag tag = new CompoundTag();
        tag.put("pairx", new IntTag(5));
        tag.put("pairz", new IntTag(10));
        assertEquals(ChestPairing.TYPE_LEFT, ChestPairing.javaType("north", 4, 10, tag));
        assertEquals(ChestPairing.TYPE_RIGHT, ChestPairing.javaType("north", 6, 10, tag));
        assertEquals(ChestPairing.TYPE_LEFT, ChestPairing.javaType("east", 5, 9, tag));
    }

    @Test
    void diagonalOrDistantPairIsSingle() {
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("north", 1, 1));
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("north", 2, 0));
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("north", 0, 0));
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("up", 1, 0));
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("north", 0, 1));
        assertEquals(ChestPairing.TYPE_SINGLE, ChestPairing.javaType("east", 1, 0));
    }

}
