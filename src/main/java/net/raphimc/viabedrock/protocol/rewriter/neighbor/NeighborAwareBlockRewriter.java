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
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSection;
import com.viaversion.viaversion.api.minecraft.chunks.DataPalette;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link NeighborAwareBlockRule} 的中央分发器。同一套管线同时服务初始区块转换
 * （{@link #fixChunk}）和运行时方块更新（{@link #resolveUpdate}）；单个位置会按固定顺序运行所有
 * 拥有它的规则，因此被多条规则涉及的方块状态会依次串起来。
 * <p>
 * 邻居传播纯由几何决定：改动之后，周围格子中的每个方块都会被重新修正，
 * 并且只在值真正改变的地方发出更新。这样规则无需知道「我影响谁」，
 * 也能让一次普通方块改动正确地重塑相邻的楼梯/栅栏/门的两半/箱子配对。
 */
public final class NeighborAwareBlockRewriter {

    /**
     * 一次改动可能传播到的格子：四个水平邻居（楼梯形状、栅栏/铁板连接、床
     * 配对者、箱子配对）加上上/下（门的两半）。
     */
    private static final BlockFace[] NEIGHBOR_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.TOP, BlockFace.BOTTOM
    };

    private final List<NeighborAwareBlockRule> rules;

    public NeighborAwareBlockRewriter(final BiMap<BlockState, Integer> javaBlockStates) {
        this.rules = List.of(
                new StairShapeRule(javaBlockStates),
                new BlockConnectionRule(javaBlockStates),
                new NetherPortalAxisRule(javaBlockStates),
                new LinkedBlockRule(javaBlockStates),
                new ChestPairingRule(javaBlockStates),
                new GrowingPlantRule(javaBlockStates),
                new ChorusPlantConnectionRule(javaBlockStates)
        );
    }

    /**
     * 解析一次运行时方块改动：被改动的位置，加上因此自身状态发生变化的每个周围格子。
     * 返回位置到其修正后 Java 方块状态 ID 的映射（被改动的位置排在最前）；只包含真正变化的格子。
     */
    public Map<BlockPosition, Integer> resolveUpdate(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final Map<BlockPosition, Integer> out = new LinkedHashMap<>();
        out.put(pos, fixState(view, pos, javaBlockStateId));

        for (BlockFace face : NEIGHBOR_FACES) {
            final BlockPosition neighbor = pos.getRelative(face);
            final int now = view.getJavaBlockState(neighbor);
            // 规则拥有的邻居的修正值从不会写回跟踪器，因此 `now` 不是可靠的
            // 比对基准：它可能恰好等于新算出的值，而该值
            // 又与客户端实际最后收到的不同（例如门的一半的非权威属性在跟踪器里是
            // 一个固定的默认猜测值，而客户端显示的是早先某个更新修正后的值）。
            // 规则拥有的邻居一律重发；未受影响的邻居可以廉价跳过，因为对它们 fixState
            // 是空操作。
            if (!isHandled(now)) {
                continue;
            }
            out.put(neighbor, fixState(view, neighbor, now));
        }
        return out;
    }

    /**
     * 就地修正刚重映射区块中每个被规则拥有的方块。该过程在初始区块转换时运行，
     * 因此门/床在加载时就同步好，而不是等到之后的方块更新。
     */
    public void fixChunk(final ChunkTracker tracker, final Chunk chunk, final int chunkX, final int chunkZ, final int minY) {
        final ChunkSection[] sections = chunk.getSections();
        final BlockNeighborView view = new ChunkNeighborView(tracker, sections, chunkX, chunkZ, minY);

        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            final ChunkSection section = sections[sIdx];
            if (section == null) continue;

            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette == null) continue;

            // 快速检查：该调色板中是否存在任何由规则拥有的方块？
            boolean relevant = false;
            for (int i = 0; i < palette.size(); i++) {
                if (isHandled(palette.idByIndex(i))) {
                    relevant = true;
                    break;
                }
            }
            if (!relevant) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int raw = palette.idAt(x, y, z);
                        if (!isHandled(raw)) continue;

                        final BlockPosition pos = new BlockPosition(chunkX * 16 + x, minY + sIdx * 16 + y, chunkZ * 16 + z);
                        final int fixed = fixState(view, pos, raw);
                        if (fixed != raw) {
                            palette.setIdAt(x, y, z, fixed);
                        }
                    }
                }
            }
        }
    }

    /**
     * 给定的区块某一侧的边界平面中，是否存在由可能依赖另一侧区块的规则
     * （{@link NeighborAwareBlockRule#affectsNeighborChunks()}）所拥有的方块。为这类方块算出的
     * 修正在该相邻区块尚未到达时是过期的，因此调用方会重发已经发送过的相邻区块。
     *
     * @param side 0 = -X，1 = +X，2 = -Z，3 = +Z
     */
    public boolean hasCrossChunkSensitiveBlockOnBorder(final Chunk chunk, final int side) {
        final ChunkSection[] sections = chunk.getSections();
        for (ChunkSection section : sections) {
            if (section == null) continue;
            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette == null) continue;

            boolean relevant = false;
            for (int i = 0; i < palette.size(); i++) {
                if (this.isCrossChunkSensitive(palette.idByIndex(i))) {
                    relevant = true;
                    break;
                }
            }
            if (!relevant) continue;

            for (int a = 0; a < 16; a++) {
                for (int y = 0; y < 16; y++) {
                    final int x = side <= 1 ? (side == 0 ? 0 : 15) : a;
                    final int z = side >= 2 ? (side == 2 ? 0 : 15) : a;
                    if (this.isCrossChunkSensitive(palette.idAt(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isCrossChunkSensitive(final int javaBlockStateId) {
        for (NeighborAwareBlockRule rule : this.rules) {
            if (rule.isCrossChunkSensitive(javaBlockStateId)) {
                return true;
            }
        }
        return false;
    }

    /** 按顺序运行拥有该位置的每条规则来修正单个位置。 */
    private int fixState(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        int state = javaBlockStateId;
        for (NeighborAwareBlockRule rule : this.rules) {
            if (rule.handles(state)) {
                state = rule.recompute(view, pos, state);
            }
        }
        return state;
    }

    private boolean isHandled(final int javaBlockStateId) {
        for (NeighborAwareBlockRule rule : this.rules) {
            if (rule.handles(javaBlockStateId)) {
                return true;
            }
        }
        return false;
    }

}
