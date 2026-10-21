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
package net.raphimc.viabedrock.protocol.rewriter.neighbor;

import com.google.common.collect.HashBiMap;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.IntTag;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.BlockState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestPairingRuleTest {

    private static final BlockState SINGLE = BlockState.fromString("minecraft:chest[facing=north,type=single,waterlogged=false]");
    private static final BlockState LEFT = BlockState.fromString("minecraft:chest[facing=north,type=left,waterlogged=false]");
    private static final BlockState RIGHT = BlockState.fromString("minecraft:chest[facing=north,type=right,waterlogged=false]");
    private static final BlockState WATERLOGGED_SINGLE = BlockState.fromString("minecraft:chest[facing=north,type=single,waterlogged=true]");
    private static final BlockState WATERLOGGED_LEFT = BlockState.fromString("minecraft:chest[facing=north,type=left,waterlogged=true]");
    private static final BlockState COPPER_SINGLE = BlockState.fromString("minecraft:copper_chest[facing=east,type=single,waterlogged=false]");
    private static final BlockState COPPER_LEFT = BlockState.fromString("minecraft:copper_chest[facing=east,type=left,waterlogged=false]");
    private static final BlockState ENDER = BlockState.fromString("minecraft:ender_chest[facing=north,waterlogged=false]");
    private static final BlockState SLAB = BlockState.fromString("minecraft:oak_slab[type=bottom,waterlogged=false]");

    private static final int ID_SINGLE = 1;
    private static final int ID_LEFT = 2;
    private static final int ID_RIGHT = 3;
    private static final int ID_WATERLOGGED_SINGLE = 4;
    private static final int ID_WATERLOGGED_LEFT = 5;
    private static final int ID_COPPER_SINGLE = 6;
    private static final int ID_COPPER_LEFT = 7;
    private static final int ID_ENDER = 8;
    private static final int ID_SLAB = 9;

    @Test
    void handlesPairableChestsOnly() {
        final ChestPairingRule rule = rule();
        assertTrue(rule.handles(ID_SINGLE));
        assertTrue(rule.handles(ID_COPPER_SINGLE));
        assertFalse(rule.handles(ID_ENDER));
        assertFalse(rule.handles(ID_SLAB));
    }

    @Test
    void pairsFromOwnNbt() {
        final BlockPosition pos = new BlockPosition(4, 64, 10);
        final CompoundTag tag = new CompoundTag();
        tag.put("pairx", new IntTag(5));
        tag.put("pairz", new IntTag(10));
        final int remapped = rule().recompute(view(pos, tag, ID_SINGLE), pos, ID_SINGLE);
        assertEquals(ID_LEFT, remapped);
    }

    @Test
    void unpairedNbtIsSingle() {
        final BlockPosition pos = new BlockPosition(4, 64, 10);
        final int remapped = rule().recompute(view(pos, new CompoundTag(), ID_LEFT), pos, ID_LEFT);
        assertEquals(ID_SINGLE, remapped);
    }

    @Test
    void missingBlockEntityIsSingle() {
        final BlockPosition pos = new BlockPosition(4, 64, 10);
        final int remapped = rule().recompute(view(pos, null, ID_LEFT), pos, ID_LEFT);
        assertEquals(ID_SINGLE, remapped);
    }

    @Test
    void preservesWaterlogged() {
        final BlockPosition pos = new BlockPosition(4, 64, 10);
        final CompoundTag tag = new CompoundTag();
        tag.put("pairx", new IntTag(5));
        tag.put("pairz", new IntTag(10));
        final int remapped = rule().recompute(view(pos, tag, ID_WATERLOGGED_SINGLE), pos, ID_WATERLOGGED_SINGLE);
        assertEquals(ID_WATERLOGGED_LEFT, remapped);
    }

    @Test
    void pairsCopperChest() {
        final BlockPosition pos = new BlockPosition(5, 64, 9);
        final CompoundTag tag = new CompoundTag();
        tag.put("pairx", new IntTag(5));
        tag.put("pairz", new IntTag(10));
        final int remapped = rule().recompute(view(pos, tag, ID_COPPER_SINGLE), pos, ID_COPPER_SINGLE);
        assertEquals(ID_COPPER_LEFT, remapped);
    }

    private static ChestPairingRule rule() {
        final HashBiMap<BlockState, Integer> states = HashBiMap.create();
        states.put(SINGLE, ID_SINGLE);
        states.put(LEFT, ID_LEFT);
        states.put(RIGHT, ID_RIGHT);
        states.put(WATERLOGGED_SINGLE, ID_WATERLOGGED_SINGLE);
        states.put(WATERLOGGED_LEFT, ID_WATERLOGGED_LEFT);
        states.put(COPPER_SINGLE, ID_COPPER_SINGLE);
        states.put(COPPER_LEFT, ID_COPPER_LEFT);
        states.put(ENDER, ID_ENDER);
        states.put(SLAB, ID_SLAB);
        return new ChestPairingRule(states);
    }

    private static BlockNeighborView view(final BlockPosition chestPos, final CompoundTag tag, final int javaId) {
        return new BlockNeighborView() {
            @Override
            public int getJavaBlockState(final BlockPosition pos) {
                return pos.equals(chestPos) ? javaId : 0;
            }

            @Override
            public BedrockBlockEntity getBlockEntity(final BlockPosition pos) {
                if (!pos.equals(chestPos) || tag == null) {
                    return null;
                }
                return new BedrockBlockEntity(pos, tag);
            }
        };
    }

}
