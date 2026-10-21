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
 * Central dispatcher for {@link NeighborAwareBlockRule}s. One pipeline serves both the initial chunk translation
 * ({@link #fixChunk}) and runtime block updates ({@link #resolveUpdate}); a single position is corrected by running
 * every rule that owns it, in a fixed order, so a state touched by more than one rule chains through all of them.
 * <p>
 * Neighbor propagation is purely geometric: after a change, every block in the surrounding cells is re-corrected and
 * an update is emitted only where the value actually changed. This keeps the rules free of any "who do I affect"
 * knowledge and makes a plain block change correctly re-shape adjacent stairs/fences/door halves/chest pairs.
 */
public final class NeighborAwareBlockRewriter {

    /**
     * The cells a change can propagate to: the four horizontal neighbors (stair shape, fence/pane connections, bed
     * partner, chest pair) plus up/down (door halves).
     */
    private static final BlockFace[] NEIGHBOR_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.TOP, BlockFace.BOTTOM
    };

    private final List<NeighborAwareBlockRule> rules;

    public NeighborAwareBlockRewriter(final BiMap<BlockState, Integer> javaBlockStates) {
        this.rules = List.of(
                new StairShapeRule(javaBlockStates),
                new BlockConnectionRule(javaBlockStates),
                new LinkedBlockRule(javaBlockStates),
                new ChestPairingRule(javaBlockStates)
        );
    }

    /**
     * Resolves a runtime block change: the changed position plus every surrounding cell whose own state changed as a
     * result. Returns a map (changed position first) of positions to their corrected Java block state id; only cells
     * that actually changed are included.
     */
    public Map<BlockPosition, Integer> resolveUpdate(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final Map<BlockPosition, Integer> out = new LinkedHashMap<>();
        out.put(pos, fixState(view, pos, javaBlockStateId));

        for (BlockFace face : NEIGHBOR_FACES) {
            final BlockPosition neighbor = pos.getRelative(face);
            final int now = view.getJavaBlockState(neighbor);
            // A rule-owned neighbor's fixed value is never written back into the tracker, so `now` is not a reliable
            // baseline to diff against: it can coincidentally match a freshly recomputed value that nonetheless
            // differs from what the client was actually last sent (e.g. a door half's non-authoritative property is a
            // constant default guess in the tracker, while the client is showing a value corrected by an earlier
            // update). Always resend rule-owned neighbors; untouched neighbors are cheap to skip since fixState is a
            // no-op for them.
            if (!isHandled(now)) {
                continue;
            }
            out.put(neighbor, fixState(view, neighbor, now));
        }
        return out;
    }

    /**
     * Corrects every owned block in a freshly remapped chunk in place. Runs during initial chunk translation, so
     * doors/beds are synchronized on load instead of only on a later block update.
     */
    public void fixChunk(final ChunkTracker tracker, final Chunk chunk, final int chunkX, final int chunkZ, final int minY) {
        final ChunkSection[] sections = chunk.getSections();
        final BlockNeighborView view = new ChunkNeighborView(tracker, sections, chunkX, chunkZ, minY);

        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            final ChunkSection section = sections[sIdx];
            if (section == null) continue;

            final DataPalette palette = section.palette(PaletteType.BLOCKS);
            if (palette == null) continue;

            // Quick check: does this palette contain any block owned by a rule?
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
     * Corrects a single position by running every rule that owns it, chained in order.
     */
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
