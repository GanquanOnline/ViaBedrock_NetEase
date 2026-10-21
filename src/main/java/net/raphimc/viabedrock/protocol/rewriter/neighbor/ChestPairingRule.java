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

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.rewriter.blockentity.ChestPairing;

import java.util.Map;

/**
 * Chests (including trapped and copper) store double-chest pairing only in Bedrock NBT ({@code pairx}/{@code pairz}).
 * Java needs that as the {@code type} block-state property, but the static mapping always emits {@code type=single}
 * and {@link net.raphimc.viabedrock.protocol.storage.ChunkTracker#getJavaBlockState} is palette-only. Each chest is
 * recomputed from its own block entity so {@code UPDATE_BLOCK} cannot wipe a previously baked {@code left}/{@code right}.
 */
public final class ChestPairingRule implements NeighborAwareBlockRule {

    private final BiMap<BlockState, Integer> javaBlockStates;
    private final Int2ObjectMap<BlockState> javaBlockStatesById = new Int2ObjectOpenHashMap<>();
    private final IntSet chestStates = new IntOpenHashSet();

    public ChestPairingRule(final BiMap<BlockState, Integer> javaBlockStates) {
        this.javaBlockStates = javaBlockStates;

        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            final int id = entry.getValue();
            this.javaBlockStatesById.put(id, state);
            if (isPairableChest(state)) {
                this.chestStates.add(id);
            }
        }
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return this.chestStates.contains(javaBlockStateId);
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final BlockState self = this.javaBlockStatesById.get(javaBlockStateId);
        if (self == null) {
            return javaBlockStateId;
        }

        final BedrockBlockEntity blockEntity = view.getBlockEntity(pos);
        final String type = ChestPairing.javaType(
                self.properties().get("facing"),
                pos.x(),
                pos.z(),
                blockEntity != null ? blockEntity.tag() : null
        );
        final BlockState fixed = self.replaceProperty("type", type);
        return this.javaBlockStates.getOrDefault(fixed, javaBlockStateId);
    }

    static boolean isPairableChest(final BlockState state) {
        return state.identifier().endsWith("chest") && state.properties().containsKey("type");
    }

}
