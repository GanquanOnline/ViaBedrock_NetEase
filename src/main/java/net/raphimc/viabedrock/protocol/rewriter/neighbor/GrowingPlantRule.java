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
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import net.raphimc.viabedrock.api.model.BlockState;

import java.util.Map;

/**
 * 基岩版的海带、下界藤蔓及无浆果洞穴藤蔓依赖邻居区分尖端和中段，Java 则使用两个方块 ID。
 * 按生长方向上的同类邻居选择形态，保留尖端年龄和当前方块的浆果状态。
 */
public final class GrowingPlantRule implements NeighborAwareBlockRule {

    private enum Plant {
        WEEPING_VINES("weeping_vines", BlockFace.BOTTOM),
        TWISTING_VINES("twisting_vines", BlockFace.TOP),
        KELP("kelp", BlockFace.TOP),
        CAVE_VINES("cave_vines", BlockFace.BOTTOM);

        private final String head;
        private final String body;
        private final BlockFace growthDirection;

        Plant(final String head, final BlockFace growthDirection) {
            this.head = head;
            this.body = head + "_plant";
            this.growthDirection = growthDirection;
        }
    }

    private final Int2ObjectMap<PlantState> states = new Int2ObjectOpenHashMap<>();

    public GrowingPlantRule(final BiMap<BlockState, Integer> javaBlockStates) {
        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            final BlockState state = entry.getKey();
            if (!state.namespace().equals("minecraft")) continue;

            for (Plant plant : Plant.values()) {
                if (!state.identifier().equals(plant.head) && !state.identifier().equals(plant.body)) continue;

                final int id = entry.getValue();
                final BlockState head = state.withIdentifier(plant.head)
                        .withProperty("age", state.properties().getOrDefault("age", "0"));
                final BlockState body = state.withIdentifier(plant.body).withoutProperties("age");
                this.states.put(id, new PlantState(plant,
                        javaBlockStates.getOrDefault(head, id), javaBlockStates.getOrDefault(body, id)));
                break;
            }
        }
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return this.states.containsKey(javaBlockStateId);
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        final PlantState self = this.states.get(javaBlockStateId);
        if (self == null) return javaBlockStateId;

        final PlantState next = this.states.get(view.getJavaBlockState(pos.getRelative(self.plant.growthDirection)));
        return next != null && next.plant == self.plant ? self.bodyId : self.headId;
    }

    private record PlantState(Plant plant, int headId, int bodyId) {
    }

}
