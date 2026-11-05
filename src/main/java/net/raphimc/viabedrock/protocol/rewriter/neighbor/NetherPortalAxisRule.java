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
import net.raphimc.viabedrock.api.model.BlockState;

import java.util.Map;

/**
 * Java 下界传送门的朝向只来自 {@code axis}，而基岩默认的 {@code portal_axis=unknown}
 * （几乎所有基岩地图都用它）不带朝向；于是静态的 {@code unknown -> axis=x} 映射
 * 会让一半沿 Z 轴开启的传送门平面错位。改为根据传送门自身的邻居推导轴向。
 */
public final class NetherPortalAxisRule implements NeighborAwareBlockRule {

    private final int xId;
    private final int zId;

    public NetherPortalAxisRule(final BiMap<BlockState, Integer> javaBlockStates) {
        int xId = -1;
        int zId = -1;
        for (Map.Entry<BlockState, Integer> entry : javaBlockStates.entrySet()) {
            if (!entry.getKey().identifier().equals("nether_portal")) continue;
            final String axis = entry.getKey().properties().get("axis");
            if ("x".equals(axis)) xId = entry.getValue();
            else if ("z".equals(axis)) zId = entry.getValue();
        }
        this.xId = xId;
        this.zId = zId;
    }

    @Override
    public boolean handles(final int javaBlockStateId) {
        return javaBlockStateId == this.xId || javaBlockStateId == this.zId;
    }

    @Override
    public boolean affectsNeighborChunks() {
        // 传送门平面经常横跨区块边界；否则较早区块中的那一列会保留
        // static unknown -> axis=x 的猜测，并渲染成与框架垂直的平面。
        return true;
    }

    @Override
    public int recompute(final BlockNeighborView view, final BlockPosition pos, final int javaBlockStateId) {
        if (this.xId == -1 || this.zId == -1) return javaBlockStateId;
        final boolean alongX = isPortal(view.getJavaBlockState(pos.getRelative(BlockFace.EAST)))
                || isPortal(view.getJavaBlockState(pos.getRelative(BlockFace.WEST)));
        final boolean alongZ = isPortal(view.getJavaBlockState(pos.getRelative(BlockFace.NORTH)))
                || isPortal(view.getJavaBlockState(pos.getRelative(BlockFace.SOUTH)));
        return alongX == alongZ ? javaBlockStateId : (alongX ? this.xId : this.zId);
    }

    private boolean isPortal(final int javaBlockStateId) {
        return javaBlockStateId == this.xId || javaBlockStateId == this.zId;
    }

}
