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
 * 一类「依赖邻居」的方块，其正确的 Java 方块状态无法仅由静态
 * 基岩→Java 状态映射产生，因为 Java 保存的信息在基岩中是由客户端根据
 * 相邻方块计算的（楼梯拐角形状、栅栏/铁板连接、门/床共享的两半等）。
 * <p>
 * 规则只需从邻居重新计算自身状态；向邻居的传播由
 * {@link NeighborAwareBlockRewriter} 统一处理，它在一次改动后会对周围格子重新运行整套规则。
 */
public interface NeighborAwareBlockRule {

    /** @return 该规则是否拥有给定的 Java 方块状态 ID。必须是 O(1)（由 int 集合/映射支撑）。 */
    boolean handles(final int javaBlockStateId);

    /**
     * 通过读取所依赖的邻居，为单个位置重新计算正确的 Java 方块状态。
     *
     * @param javaBlockStateId 该位置当前（修正前）的 Java 方块状态 ID
     *
     * @return 修正后的 Java 方块状态 ID；无需处理时原样返回输入
     */
    int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId);

    /**
     * @return 当该规则依赖的邻居位于尚未到达（因而未被重新检查）的区块中时，
     * 该规则的过期值是否会出现可见的错乱。只有这类规则才值得在已发送的邻居区块上重跑
     * 修正；它们必须很少，因为那要付出一次区块重发。
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
