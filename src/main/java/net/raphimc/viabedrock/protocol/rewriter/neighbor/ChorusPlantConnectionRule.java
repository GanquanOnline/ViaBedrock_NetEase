/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.rewriter.neighbor;

import com.google.common.collect.BiMap;
import com.viaversion.viaversion.api.minecraft.BlockFace;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import net.raphimc.viabedrock.api.model.BlockState;

import java.util.Arrays;
import java.util.Map;

/**
 * 基岩版紫颂植株不携带连接属性，按原版规则补齐 Java 六个方向的枝干。
 */
public final class ChorusPlantConnectionRule implements NeighborAwareBlockRule {

    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.TOP, BlockFace.BOTTOM
    };
    private static final String[] PROPERTIES = {"north", "east", "south", "west", "up", "down"};

    private final IntSet plants = new IntOpenHashSet();
    private final IntSet flowers = new IntOpenHashSet();
    private final IntSet endStone = new IntOpenHashSet();
    private final int[] connectionIds = new int[64];

    public ChorusPlantConnectionRule(final BiMap<BlockState, Integer> javaBlockStates) {
        Arrays.fill(this.connectionIds, -1);
        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            if (!state.namespace().equals("minecraft")) continue;

            final int id = entry.getValue();
            switch (state.identifier()) {
                case "chorus_plant" -> {
                    this.plants.add(id);
                    int connections = 0;
                    for (int i = 0; i < PROPERTIES.length; i++) {
                        if (state.hasProperty(PROPERTIES[i], "true")) connections |= 1 << i;
                    }
                    this.connectionIds[connections] = id;
                }
                case "chorus_flower" -> this.flowers.add(id);
                case "end_stone" -> this.endStone.add(id);
            }
        }
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return this.plants.contains(javaBlockStateId);
    }

    @Override
    public boolean affectsNeighborChunks() {
        return true;
    }

    @Override
    public boolean isCrossChunkSensitive(final int javaBlockStateId) {
        return this.plants.contains(javaBlockStateId) || this.flowers.contains(javaBlockStateId);
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        if (!this.handles(javaBlockStateId)) return javaBlockStateId;

        int connections = 0;
        for (int i = 0; i < FACES.length; i++) {
            final int neighbor = view.getJavaBlockState(pos.getRelative(FACES[i]));
            if (this.plants.contains(neighbor) || this.flowers.contains(neighbor)
                    || FACES[i] == BlockFace.BOTTOM && this.endStone.contains(neighbor)) {
                connections |= 1 << i;
            }
        }
        final int result = this.connectionIds[connections];
        return result == -1 ? javaBlockStateId : result;
    }

}
