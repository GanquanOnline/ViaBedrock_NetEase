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

/** Proxy-side light engine contract used only while a connection is in server-computed mode. */
public interface ChunkLightProvider {

    /**
     * Process light data and send the chunk to the Java client.
     * A false result means the provider became stale or the negotiated mode changed before it
     * could take ownership; the caller must revalidate the connection state instead of sending a
     * fallback packet through this path.
     *
     * @param tracker the chunk tracker for the current connection
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @param chunk   the remapped Java chunk ready for sending
     * @return true if this provider handled sending the chunk, false if it no longer owns the mode
     */
    boolean processAndSendChunk(ChunkTracker tracker, int chunkX, int chunkZ, Chunk chunk);

    /** 方块更新使用 ViaBedrock 源版本的最终 JE 状态，不能传入已被后续协议改写的 ID。 */
    void onBlockChange(BlockPosition position, int javaBlockStateId);

    /**
     * Called when a chunk is unloaded. Implementations should clean up any cached state.
     *
     * @param chunkKey the packed chunk key (ChunkTracker.chunkKey(x, z))
     */
    void onChunkUnload(long chunkKey);

    /**
     * Called once per tick to process pending async light updates.
     */
    void tick();

}
