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
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;

/**
 * Abstracts "read a neighbor block" so a {@link NeighborAwareBlockRule} can be evaluated identically during initial
 * chunk translation (reading the freshly remapped chunk sections, so fixes already applied earlier in the same pass
 * are visible) and during runtime block updates (reading the
 * {@link net.raphimc.viabedrock.protocol.storage.ChunkTracker}).
 */
public interface BlockNeighborView {

    /**
     * @return the Java block state id at the position, or {@code 0} (air) when there is no data there.
     */
    int getJavaBlockState(final BlockPosition pos);

    /** 原始 BE 已知排列；未知区块/自定义未建模状态返回 null，不能当作空气。 */
    default net.easecation.blockstates.SharedBlockStates.State getBedrockState(final BlockPosition pos) { return null; }

    /**
     * @return the Bedrock block entity at the position (e.g. a bed carrying its dye colour in NBT), or {@code null}.
     * Needed because {@link #getJavaBlockState} is palette-only and never injects block entity data.
     */
    BedrockBlockEntity getBlockEntity(final BlockPosition pos);

}
