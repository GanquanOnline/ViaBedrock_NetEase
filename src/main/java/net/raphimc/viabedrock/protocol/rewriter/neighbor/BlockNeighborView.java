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
 * 抽象出「读取相邻方块」，使 {@link NeighborAwareBlockRule} 在初始
 * 区块转换时（读取刚重映射过的区块段，因此同一轮中更早应用的修正
 * 可见）与运行时方块更新时（读取
 * {@link net.raphimc.viabedrock.protocol.storage.ChunkTracker}）能以相同方式求值。
 */
public interface BlockNeighborView {

    /** @return 该位置的 Java 方块状态 ID，无数据时为 {@code 0}（空气）。 */
    int getJavaBlockState(final BlockPosition pos);

    /** 原始 BE 已知排列；未知区块/自定义未建模状态返回 null，不能当作空气。 */
    default net.easecation.blockstates.SharedBlockStates.State getBedrockState(final BlockPosition pos) { return null; }

    /**
     * @return 该位置的基岩方块实体（例如在 NBT 中携带染料颜色的床），没有时为 {@code null}。
     * 之所以需要它，是因为 {@link #getJavaBlockState} 只查调色板，从不注入方块实体数据。
     */
    BedrockBlockEntity getBlockEntity(final BlockPosition pos);

}
