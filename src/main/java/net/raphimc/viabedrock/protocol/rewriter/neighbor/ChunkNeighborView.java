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
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSection;
import com.viaversion.viaversion.api.minecraft.chunks.DataPalette;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * 用于初始区块转换的 {@link BlockNeighborView}：区块内查找读取刚重映射的 Java
 * 区块段（因此同一轮中更早做的邻居修正可见）；其余一切（跨区块方块状态
 * 和所有方块实体）都委托给 {@link TrackerNeighborView}。
 */
public final class ChunkNeighborView implements BlockNeighborView {

    private final TrackerNeighborView trackerView;
    private final ChunkSection[] sections;
    private final int chunkX;
    private final int chunkZ;
    private final int minY;

    public ChunkNeighborView(final ChunkTracker tracker, final ChunkSection[] sections, final int chunkX, final int chunkZ, final int minY) {
        this.trackerView = new TrackerNeighborView(tracker);
        this.sections = sections;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
    }

    @Override
    public int getJavaBlockState(final BlockPosition pos) {
        if ((pos.x() >> 4) != this.chunkX || (pos.z() >> 4) != this.chunkZ) {
            return this.trackerView.getJavaBlockState(pos);
        }

        final int sIdx = (pos.y() - this.minY) >> 4;
        if (sIdx < 0 || sIdx >= this.sections.length) return 0;
        final ChunkSection section = this.sections[sIdx];
        if (section == null) return 0;
        final DataPalette palette = section.palette(PaletteType.BLOCKS);
        if (palette == null) return 0;
        return palette.idAt(pos.x() & 15, pos.y() & 15, pos.z() & 15);
    }

    @Override public net.easecation.blockstates.SharedBlockStates.State getBedrockState(final BlockPosition pos) {
        // tracker 保留原始 BE sections；不能读本轮已重写的 JE palette。
        return this.trackerView.getBedrockState(pos);
    }

    @Override
    public BedrockBlockEntity getBlockEntity(final BlockPosition pos) {
        return this.trackerView.getBlockEntity(pos);
    }

}
