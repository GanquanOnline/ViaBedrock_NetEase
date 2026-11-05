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
import net.raphimc.viabedrock.api.model.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Java 楼梯带有 {@code shape}（straight / inner|outer left|right），基岩则由客户端根据
 * 相邻楼梯计算。该规则会依据邻居重新计算单个楼梯的形状。
 */
public final class StairShapeRule implements NeighborAwareBlockRule {

    private static final int SHAPE_STRAIGHT = 0;
    private static final int SHAPE_INNER_LEFT = 1;
    private static final int SHAPE_INNER_RIGHT = 2;
    private static final int SHAPE_OUTER_LEFT = 3;
    private static final int SHAPE_OUTER_RIGHT = 4;

    private static final String[] SHAPE_NAMES = {"straight", "inner_left", "inner_right", "outer_left", "outer_right"};

    private final Int2ObjectMap<StairProperties> stairProperties = new Int2ObjectOpenHashMap<>();

    public StairShapeRule(final BiMap<BlockState, Integer> javaBlockStates) {
        // 按（命名空间标识符 + facing + half + waterlogged）对楼梯方块状态分组，收集 5 种 shape 变体 ID
        final Map<String, Map<String, Integer>> stairsByGroup = new HashMap<>();

        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            if (!state.identifier().endsWith("_stairs")) continue;

            final Map<String, String> props = state.properties();
            if (!props.containsKey("shape") || !props.containsKey("facing") || !props.containsKey("half")) continue;

            final String groupKey = state.namespacedIdentifier() + "|" + props.get("facing") + "|" + props.get("half") + "|" + props.getOrDefault("waterlogged", "false");
            stairsByGroup.computeIfAbsent(groupKey, k -> new HashMap<>()).put(props.get("shape"), entry.getValue());
        }

        for (Map.Entry<String, Map<String, Integer>> groupEntry : stairsByGroup.entrySet()) {
            final Map<String, Integer> shapeToId = groupEntry.getValue();

            final int[] shapeIds = new int[5];
            boolean complete = true;
            for (int i = 0; i < 5; i++) {
                final Integer id = shapeToId.get(SHAPE_NAMES[i]);
                if (id == null) {
                    complete = false;
                    break;
                }
                shapeIds[i] = id;
            }
            if (!complete) continue;

            final String[] parts = groupEntry.getKey().split("\\|");
            final BlockFace facing = parseFacing(parts[1]);
            final boolean bottom = parts[2].equals("bottom");

            final StairProperties properties = new StairProperties(facing, bottom, shapeIds);
            for (int id : shapeIds) {
                this.stairProperties.put(id, properties);
            }
        }
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return this.stairProperties.containsKey(javaBlockStateId);
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final StairProperties stair = this.stairProperties.get(javaBlockStateId);
        if (stair == null) return javaBlockStateId;

        final int shapeIndex = computeShape(view, pos, stair);
        return stair.shapeIds[shapeIndex];
    }

    private int computeShape(final BlockNeighborView view, final BlockPosition position, final StairProperties stair) {
        final BlockFace facing = stair.facing;

        // 检查前方（facing 方向）的方块
        final StairProperties frontStair = this.stairProperties.get(view.getJavaBlockState(position.getRelative(facing)));
        if (frontStair != null && frontStair.bottom == stair.bottom) {
            final BlockFace frontFacing = frontStair.facing;
            if (facing.axis() != frontFacing.axis()) {
                final StairProperties oppositeStair = this.stairProperties.get(view.getJavaBlockState(position.getRelative(frontFacing.opposite())));
                if (oppositeStair == null || oppositeStair.facing != stair.facing || oppositeStair.bottom != stair.bottom) {
                    return frontFacing == rotateCounterClockwise(facing) ? SHAPE_OUTER_LEFT : SHAPE_OUTER_RIGHT;
                }
            }
        }

        // 检查后方（facing 方向的反向）的方块
        final StairProperties backStair = this.stairProperties.get(view.getJavaBlockState(position.getRelative(facing.opposite())));
        if (backStair != null && backStair.bottom == stair.bottom) {
            final BlockFace backFacing = backStair.facing;
            if (facing.axis() != backFacing.axis()) {
                final StairProperties checkStair = this.stairProperties.get(view.getJavaBlockState(position.getRelative(backFacing)));
                if (checkStair == null || checkStair.facing != stair.facing || checkStair.bottom != stair.bottom) {
                    return backFacing == rotateCounterClockwise(facing) ? SHAPE_INNER_LEFT : SHAPE_INNER_RIGHT;
                }
            }
        }

        return SHAPE_STRAIGHT;
    }

    private static BlockFace rotateCounterClockwise(final BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case SOUTH -> BlockFace.EAST;
            case EAST -> BlockFace.NORTH;
            case WEST -> BlockFace.SOUTH;
            default -> face;
        };
    }

    private static BlockFace parseFacing(final String facing) {
        return switch (facing) {
            case "north" -> BlockFace.NORTH;
            case "south" -> BlockFace.SOUTH;
            case "east" -> BlockFace.EAST;
            case "west" -> BlockFace.WEST;
            default -> throw new IllegalArgumentException("Invalid facing: " + facing);
        };
    }

    private record StairProperties(BlockFace facing, boolean bottom, int[] shapeIds) {
    }

}
