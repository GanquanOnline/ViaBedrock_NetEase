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
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingDataLayoutTest {

    @Test
    void netease860UsesLegacyFurnaceLayout() {
        assertFalse(CraftingDataLayout.usesShapelessFurnaceLayout(true, 860));
        assertTrue(CraftingDataLayout.isLegacyFurnaceType(2));
        assertTrue(CraftingDataLayout.isLegacyFurnaceType(3));
        assertFalse(CraftingDataLayout.isLegacyFurnaceType(0));
    }

    @Test
    void shapelessFurnaceLayoutStartsAt974() {
        assertFalse(CraftingDataLayout.usesShapelessFurnaceLayout(false, 860));
        assertFalse(CraftingDataLayout.usesShapelessFurnaceLayout(false, 973));
        assertTrue(CraftingDataLayout.usesShapelessFurnaceLayout(false, 974));
        assertTrue(CraftingDataLayout.usesShapelessFurnaceLayout(false, 975));
        assertTrue(CraftingDataLayout.usesShapelessFurnaceLayout(true, 974));
        assertFalse(CraftingDataLayout.usesShapelessFurnaceLayout(true, 973));
    }

    @Test
    void official975KeepsByteUnlockingLayout() {
        assertTrue(CraftingDataLayout.usesUnlockingRequirement(false, 975));
        assertFalse(CraftingDataLayout.usesVarIntUnlockingRequirement(false, 975));
        assertFalse(CraftingDataLayout.usesVarIntUnlockingRequirement(true, 860));
        assertTrue(CraftingDataLayout.usesVarIntUnlockingRequirement(false, 2168));
    }

    @Test
    void netease860AlwaysUnlockedConsumesOnlyTheContextByte() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CraftingDataLayout.writeUnlockingRequirement(buffer, true, 860, 1);
            assertEquals(1, CraftingDataLayout.skipUnlockingRequirement(buffer, true, 860));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void official975AlwaysUnlockedConsumesOnlyTheContextByte() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CraftingDataLayout.writeUnlockingRequirement(buffer, false, 975, 1);
            assertEquals(1, CraftingDataLayout.skipUnlockingRequirement(buffer, false, 975));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860NoneUnlockingConsumesExtraIngredients() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CraftingDataLayout.writeUnlockingRequirement(
                    buffer, true, 860, CraftingDataLayout.UNLOCKING_CONTEXT_NONE,
                    new RecipeIngredient(5, 0, 1));
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 42);
            assertEquals(0, CraftingDataLayout.skipUnlockingRequirement(buffer, true, 860));
            assertEquals(42, BedrockTypes.UNSIGNED_VAR_INT.read(buffer).intValue());
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNoneUnlockingAsASingleByteLeavesTheIngredientArray() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CraftingDataLayout.writeUnlockingRequirement(
                    buffer, true, 860, CraftingDataLayout.UNLOCKING_CONTEXT_NONE,
                    new RecipeIngredient(5, 0, 1));
            buffer.readUnsignedByte();
            assertTrue(buffer.isReadable(), "NONE extra ingredients must remain if only the context byte is read");
        } finally {
            buffer.release();
        }
    }

    @Test
    void protocol2168UnlockingUsesVarIntAndBoolean() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CraftingDataLayout.writeUnlockingRequirement(
                    buffer, false, 2168, CraftingDataLayout.UNLOCKING_CONTEXT_NONE,
                    new RecipeIngredient(7, 0, 2));
            assertEquals(0, CraftingDataLayout.skipUnlockingRequirement(buffer, false, 2168));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }
}
