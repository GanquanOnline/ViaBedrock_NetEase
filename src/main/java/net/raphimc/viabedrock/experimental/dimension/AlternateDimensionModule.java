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
package net.raphimc.viabedrock.experimental.dimension;

import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/**
 * 处理同维度传送：在正常与备用维度键之间切换，
 * 当服务器把 CHANGE_DIMENSION 发往玩家当前所在的维度时，强制 Java 客户端重新加载区块。
 */
public class AlternateDimensionModule implements FeatureModule {

    @Override
    public String resolveDimensionKey(final Dimension dimension, final ChunkTracker oldChunkTracker) {
        if (oldChunkTracker != null && dimension == oldChunkTracker.getDimension()) {
            // 同维度：在正常键与备用键之间切换，以触发 Java 客户端重新加载区块
            return oldChunkTracker.getDimensionKey().equals(dimension.getKey())
                    ? dimension.getAltKey()
                    : dimension.getKey();
        }
        return null; // 不同维度：使用默认键
    }

}
