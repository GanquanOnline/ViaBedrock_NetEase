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
 * 箱子（含陷阱箱和铜箱）只在基岩 NBT（{@code pairx}/{@code pairz}）中保存双箱配对。
 * Java 需要把它作为 {@code type} 方块状态属性，但静态映射总是输出 {@code type=single}，
 * 而 {@link net.raphimc.viabedrock.protocol.storage.ChunkTracker#getJavaBlockState} 只查调色板。每个箱子都
 * 从自己的方块实体重新计算，这样 {@code UPDATE_BLOCK} 就无法抹掉先前烘焙的 {@code left}/{@code right}。
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
