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
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.data.enums.DyeColor;
import net.raphimc.viabedrock.protocol.rewriter.blockentity.BedBlockEntityRewriter;

import java.util.Locale;
import java.util.Map;

/**
 * 双段方块（门的两半、床的两个部分）在 Java 中的共享属性被基岩拆到两个方块上，
 * 由客户端重新组合。每一半都独立地从自身和它的配对者重新计算：
 * <ul>
 * <li>门：基岩在下半部分存 {@code facing/open}，在上半部分存 {@code hinge}，因此每一
 * 半都从配对者取回缺失的共享属性。</li>
 * <li>床：染料颜色只存在于方块实体 NBT 中（已烘焙进 Java ID），而 {@link BlockNeighborView#getJavaBlockState}
 * 只查调色板，因此这样取到的配对者是不带颜色的 {@code white_bed}。每一半都从自己的方块实体
 * 重新推导颜色（经由 {@link BedBlockEntityRewriter#getColor}），所以配对者绝不会
 * 被降级为白色。</li>
 * </ul>
 */
public final class LinkedBlockRule implements NeighborAwareBlockRule {

    private static final String HALF = "half";
    private static final String LOWER = "lower";
    private static final String UPPER = "upper";
    private static final String FACING = "facing";
    private static final String OPEN = "open";
    private static final String HINGE = "hinge";
    private static final String POWERED = "powered";
    private static final String OCCUPIED = "occupied";
    private static final String PART = "part";
    private static final String BED_SUFFIX = "_bed";

    private final BiMap<BlockState, Integer> javaBlockStates;
    private final Int2ObjectMap<BlockState> javaBlockStatesById = new Int2ObjectOpenHashMap<>();
    private final IntSet doorStates = new IntOpenHashSet();
    private final IntSet bedStates = new IntOpenHashSet();

    public LinkedBlockRule(final BiMap<BlockState, Integer> javaBlockStates) {
        this.javaBlockStates = javaBlockStates;

        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            final int id = entry.getValue();
            this.javaBlockStatesById.put(id, state);
            if (isDoor(state)) {
                this.doorStates.add(id);
            } else if (isBed(state)) {
                this.bedStates.add(id);
            }
        }
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return this.doorStates.contains(javaBlockStateId) || this.bedStates.contains(javaBlockStateId);
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        if (this.doorStates.contains(javaBlockStateId)) {
            return recomputeDoor(view, pos, javaBlockStateId);
        }
        if (this.bedStates.contains(javaBlockStateId)) {
            return recomputeBed(view, pos, javaBlockStateId);
        }
        return javaBlockStateId;
    }

    private int recomputeDoor(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final BlockState self = this.javaBlockStatesById.get(javaBlockStateId);
        if (self == null) return javaBlockStateId;

        final String half = self.properties().get(HALF);
        final BlockPosition otherPos;
        if (LOWER.equals(half)) {
            otherPos = pos.getRelative(BlockFace.TOP);
        } else if (UPPER.equals(half)) {
            otherPos = pos.getRelative(BlockFace.BOTTOM);
        } else {
            return javaBlockStateId;
        }

        final BlockState other = this.javaBlockStatesById.get(view.getJavaBlockState(otherPos));
        final BlockState lowerState = LOWER.equals(half) ? self : other;
        final BlockState upperState = UPPER.equals(half) ? self : other;
        if (!isDoorHalfPair(self, lowerState, upperState)) {
            return javaBlockStateId;
        }

        final String facing = firstNonNull(lowerState.properties().get(FACING), upperState.properties().get(FACING));
        final String open = firstNonNull(lowerState.properties().get(OPEN), upperState.properties().get(OPEN));
        final String hinge = firstNonNull(upperState.properties().get(HINGE), lowerState.properties().get(HINGE));
        final String powered = firstNonNull(lowerState.properties().get(POWERED), upperState.properties().get(POWERED));

        BlockState fixed = self;
        fixed = withProperty(fixed, FACING, facing);
        fixed = withProperty(fixed, OPEN, open);
        fixed = withProperty(fixed, HINGE, hinge);
        fixed = withProperty(fixed, POWERED, powered);
        return this.javaBlockStates.getOrDefault(fixed, javaBlockStateId);
    }

    private int recomputeBed(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final BlockState self = this.javaBlockStatesById.get(javaBlockStateId);
        if (self == null) return javaBlockStateId;

        // 床的染料颜色只存在于基岩方块实体 NBT 中并被烘焙进 Java ID；只查调色板的
        // 邻居查找会得到无色的 white_bed 基础状态。从这一半自己的方块实体
        // 重新推导颜色（与 BedBlockEntityRewriter 相同的来源和 RED 回退），这样配对者会用真实的
        // 染色标识符重建，而不是被降级为白色。
        final BedrockBlockEntity blockEntity = view.getBlockEntity(pos);
        if (blockEntity == null || blockEntity.tag() == null) {
            return javaBlockStateId;
        }

        final DyeColor color = BedBlockEntityRewriter.getColor(blockEntity.tag());
        final BlockState fixed = self.withIdentifier(color.name().toLowerCase(Locale.ROOT) + BED_SUFFIX);
        return this.javaBlockStates.getOrDefault(fixed, javaBlockStateId);
    }

    private static boolean isDoor(final BlockState state) {
        return state.identifier().endsWith("_door")
                && state.properties().containsKey(HALF)
                && state.properties().containsKey(FACING)
                && state.properties().containsKey(OPEN)
                && state.properties().containsKey(HINGE);
    }

    private static boolean isBed(final BlockState state) {
        return state.identifier().endsWith(BED_SUFFIX)
                && state.properties().containsKey(PART)
                && state.properties().containsKey(FACING)
                && state.properties().containsKey(OCCUPIED);
    }

    private static boolean isDoorHalfPair(final BlockState self, final BlockState lowerState, final BlockState upperState) {
        return lowerState != null
                && upperState != null
                && lowerState.namespacedIdentifier().equals(self.namespacedIdentifier())
                && upperState.namespacedIdentifier().equals(self.namespacedIdentifier())
                && LOWER.equals(lowerState.properties().get(HALF))
                && UPPER.equals(upperState.properties().get(HALF));
    }

    private static BlockState withProperty(final BlockState state, final String key, final String value) {
        if (value == null) {
            return state;
        }
        return state.withProperty(key, value);
    }

    private static String firstNonNull(final String first, final String second) {
        return first != null ? first : second;
    }

}
