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
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * {@link BlockNeighborView} for runtime block updates: all neighbor lookups go through the {@link ChunkTracker}.
 */
public final class TrackerNeighborView implements BlockNeighborView {

    private final ChunkTracker tracker;

    public TrackerNeighborView(final ChunkTracker tracker) {
        this.tracker = tracker;
    }

    @Override public net.easecation.blockstates.SharedBlockStates.State getBedrockState(final BlockPosition pos) {
        return this.tracker.getKnownSharedBlockState(pos);
    }

    @Override
    public int getJavaBlockState(final BlockPosition pos) {
        final BedrockChunkSection section = this.tracker.getChunkSection(pos);
        if (section == null || section.palettesCount(PaletteType.BLOCKS) == 0) {
            return 0;
        }
        return this.tracker.getJavaBlockState(pos);
    }

    @Override
    public BedrockBlockEntity getBlockEntity(final BlockPosition pos) {
        return this.tracker.getBlockEntity(pos);
    }

}
