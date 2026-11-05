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
package net.raphimc.viabedrock.experimental.light;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.chunks.Chunk;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

/** 代理侧光照引擎契约，仅在连接处于服务端计算模式时使用。 */
public interface ChunkLightProvider {

    /**
     * 处理光照数据并把区块发送给 Java 客户端。
     * 返回 false 表示提供器已失效，或协商模式在它能接管之前发生了变化；
     * 调用方必须重新校验连接状态，而不是通过这条路径发送回退数据包。
     *
     * @param tracker 当前连接的区块跟踪器
     *
     * @param chunkX  区块 X 坐标
     *
     * @param chunkZ  区块 Z 坐标
     *
     * @param chunk   已重映射、可直接发送的 Java 区块
     *
     * @return 该提供器完成了区块发送时返回 true，已不再拥有该模式时返回 false
     */
    boolean processAndSendChunk(ChunkTracker tracker, int chunkX, int chunkZ, Chunk chunk);

    /** 方块更新使用 ViaBedrock 源版本的最终 JE 状态，不能传入已被后续协议改写的 ID。 */
    void onBlockChange(BlockPosition position, int javaBlockStateId);

    /**
     * 区块卸载时调用。实现应清理所有缓存状态。
     *
     * @param chunkKey 打包后的区块键 (ChunkTracker.chunkKey(x, z))
     */
    void onChunkUnload(long chunkKey);

    /** 每刻调用一次，处理待处理的异步光照更新。 */
    void tick();

}
