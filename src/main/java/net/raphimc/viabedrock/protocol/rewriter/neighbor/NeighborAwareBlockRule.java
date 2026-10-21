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

import com.viaversion.viaversion.api.minecraft.BlockPosition;

/**
 * A single family of "neighbor-aware" blocks whose correct Java block state cannot be produced by the static
 * Bedrock->Java state mapping alone, because Java stores information that Bedrock computes on the client from
 * adjacent blocks (stair corner shape, fence/pane connections, the shared halves of a door/bed, ...).
 * <p>
 * A rule only needs to recompute its own state from its neighbors; propagation to neighbors is handled uniformly by
 * the {@link NeighborAwareBlockRewriter}, which re-runs the whole rule set over the surrounding cells after a change.
 */
public interface NeighborAwareBlockRule {

    /**
     * @return whether this rule owns the given Java block state id. Must be O(1) (backed by an int set/map).
     */
    boolean handles(final int javaBlockStateId);

    /**
     * Recomputes the correct Java block state for a single position by reading the neighbors it depends on.
     *
     * @param javaBlockStateId the position's current (pre-fix) Java block state id
     * @return the corrected Java block state id, or the input unchanged when nothing applies
     */
    int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId);

    /**
     * @return whether a stale value of this rule can be visually broken when the neighbor it depends on lives in a
     * chunk that had not arrived yet (and therefore was not re-checked). Only such rules make it worth re-running the
     * fix on an already sent neighbor chunk; they must be rare, as that costs a chunk resend.
     */
    default boolean affectsNeighborChunks() {
        return false;
    }

    /**
     * 区块边缘是否包含会影响另一侧形态的方块。依赖方块本身不一定需要修正，例如紫颂花。
     */
    default boolean isCrossChunkSensitive(final int javaBlockStateId) {
        return this.affectsNeighborChunks() && this.handles(javaBlockStateId);
    }

}
