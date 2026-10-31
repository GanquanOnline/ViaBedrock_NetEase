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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.ChunkPosition;
import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingAccess;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.protocol.storage.ClientLightStorage;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;

import java.util.*;
import java.util.logging.Level;

/**
 * Async light computation engine implementing ChunkLightProvider.
 * Manages all light-related state and computation that was previously in ChunkTracker.
 * <p>
 * Light is computed asynchronously using GlobalLightCache's thread pool,
 * with results delivered back to the Netty event loop for safe packet sending.
 */
public class AsyncLightEngine implements ChunkLightProvider {

    private static final long NO_GENERATION = -1L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final int MAX_LIGHT_UPDATES_PER_TICK = 8;
    private static final int[][] SHOULD_ENQUEUE_OFFSETS = {{-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] SPREAD_DIRS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final int[][] NEIGHBOR_OFFSETS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private final UserConnection user;
    private final ChunkTracker tracker;

    private final Map<Long, ChunkLightState> chunkStates = new HashMap<>();
    private final Set<Long> pendingLightUpdates = new HashSet<>();

    public AsyncLightEngine(final ChunkTracker tracker) {
        this.user = tracker.user();
        this.tracker = tracker;
    }

    @Override
    public boolean processAndSendChunk(final ChunkTracker tracker, final int chunkX, final int chunkZ, final Chunk chunk) {
        if (!this.allowsProxyComputation()) {
            return false;
        }

        final int lightSectionCount = chunk.getSections().length + 2;
        final long chunkKey = ChunkPosition.chunkKey(chunkX, chunkZ);

        // Strip disallowed custom data before taking the immutable light snapshot.
        if (!tracker.stripCustomBlockData(chunk)) {
            return true;
        }

        // Sending the chunk runs it through ViaVersion, which mutates palette ids in-place.
        // Keep an immutable-to-us snapshot with ViaBedrock/source ids for async light calculations.
        final SectionSnapshot sectionSnapshot = copySectionsForLight(chunk.getSections());

        final ChunkLightState state = this.chunkStates.computeIfAbsent(chunkKey, key -> new ChunkLightState());
        state.sections = sectionSnapshot.sections();
        state.sectionsHash = sectionSnapshot.hash();
        state.pendingBlockChanges.clear();
        state.generation++;
        state.lightDependencyGeneration = 0L;
        state.sentToClient = true;
        state.realSkyLight = null;
        state.realBlockLight = null;
        state.realSkyLightHash = 0L;
        state.realBlockLightHash = 0L;
        this.pendingLightUpdates.remove(chunkKey);

        final LightJobInput input = this.createLightJobInput(chunkX, chunkZ, chunkKey, state, state.generation);
        final GlobalLightCache.RealLight cached = GlobalLightCache.getInstance().getRealLight(input.cacheKey());
        if (cached != null) {
            final LightComputation result = new LightComputation(cached.skyLight(), cached.blockLight(), cached.skyLightHash(), cached.blockLightHash());
            this.installRealLight(state, result);
            tracker.sendChunkWithLight(chunk, result.skyLight(), result.blockLight(), lightSectionCount);
            this.markNeighborLightDirty(chunkX, chunkZ);
            return true;
        }

        tracker.sendChunkWithPlaceholderLight(chunk, lightSectionCount);
        this.scheduleLightComputation(input, state);

        return true;
    }

    private void scheduleLightComputation(final int chunkX, final int chunkZ, final long chunkKey, final ChunkLightState state) {
        final long generation = state.generation;
        final LightJobInput input = this.createLightJobInput(chunkX, chunkZ, chunkKey, state, generation);
        this.scheduleLightComputation(input, state);
    }

    private void scheduleLightComputation(final LightJobInput input, final ChunkLightState state) {
        final long generation = input.generation();
        if (!this.isCurrentProxyMode(input.clientLightGeneration())) {
            state.computingGeneration = NO_GENERATION;
            return;
        }
        if (state.computingGeneration == generation) {
            return;
        }
        state.computingGeneration = generation;

        GlobalLightCache.getInstance().submitAsync(() -> {
            if (!this.isCurrentProxyMode(input.clientLightGeneration())) {
                this.user.getChannel().eventLoop().execute(() -> this.cancelLightComputation(input));
                return;
            }
            try {
                final LightComputation result = computeRealLight(input);
                this.user.getChannel().eventLoop().execute(() -> this.completeLightComputation(input, result));
            } catch (final Throwable e) {
                this.user.getChannel().eventLoop().execute(() -> this.failLightComputation(input, e));
            }
        });
    }

    private LightJobInput createLightJobInput(final int chunkX, final int chunkZ, final long chunkKey, final ChunkLightState state, final long generation) {
        final ChunkSection[][] neighborSections = this.collectNeighborSections(chunkX, chunkZ);
        final byte[][][] neighborSkyLight = this.collectNeighborRealSkyLight(chunkX, chunkZ);
        final byte[][][] neighborBlockLight = this.collectNeighborRealBlockLight(chunkX, chunkZ);
        final long[] neighborSectionHashes = this.collectNeighborSectionHashes(chunkX, chunkZ);
        final long[] neighborSkyLightHashes = this.collectNeighborRealSkyLightHashes(chunkX, chunkZ);
        final long[] neighborBlockLightHashes = this.collectNeighborRealBlockLightHashes(chunkX, chunkZ);
        final CustomMappingAccess access = this.user.get(CustomMappingSyncStorage.class).access();
        final long cacheKey = computeLightCacheKey(
                access.lightProfileKey(),
                state.sectionsHash,
                neighborSectionHashes,
                neighborSkyLightHashes,
                neighborBlockLightHashes);

        return new LightJobInput(
                chunkKey,
                chunkX,
                chunkZ,
                generation,
                this.currentClientLightGeneration(),
                state.lightDependencyGeneration,
                cacheKey,
                state.sections,
                neighborSections,
                neighborSkyLight,
                neighborBlockLight,
                access);
    }

    private static LightComputation computeRealLight(final LightJobInput input) {
        final GlobalLightCache.RealLight cached = GlobalLightCache.getInstance().getRealLight(input.cacheKey());
        if (cached != null) {
            return new LightComputation(cached.skyLight(), cached.blockLight(), cached.skyLightHash(), cached.blockLightHash());
        }

        final byte[][] skyLight = computeSkyLight(input.sections(), input.neighborSections(), input.neighborSkyLight(), input.access());
        final byte[][] blockLight = computeBlockLight(input.sections(), input.neighborSections(), input.neighborBlockLight(), input.access());
        final long skyLightHash = hashLightData(FNV_OFFSET, skyLight);
        final long blockLightHash = hashLightData(FNV_OFFSET, blockLight);
        GlobalLightCache.getInstance().putRealLight(input.cacheKey(), skyLight, blockLight, skyLightHash, blockLightHash);
        return new LightComputation(skyLight, blockLight, skyLightHash, blockLightHash);
    }

    private void completeLightComputation(final LightJobInput input, final LightComputation result) {
        if (!this.user.getChannel().isActive()) return;
        if (!this.isCurrentProxyMode(input.clientLightGeneration())) {
            this.cancelLightComputation(input);
            return;
        }

        final ChunkLightState state = this.chunkStates.get(input.chunkKey());
        if (!this.isSameChunkGeneration(state, input)) {
            return;
        }

        state.computingGeneration = NO_GENERATION;
        if (state.lightDependencyGeneration != input.lightDependencyGeneration()) {
            this.scheduleLightComputation(input.chunkX(), input.chunkZ(), input.chunkKey(), state);
            return;
        }

        final boolean changed = hasLightChanged(state.realSkyLight, state.realSkyLightHash, result.skyLight(), result.skyLightHash())
                || hasLightChanged(state.realBlockLight, state.realBlockLightHash, result.blockLight(), result.blockLightHash());
        this.installRealLight(state, result);

        if (changed) {
            this.sendLightUpdate(input.chunkX(), input.chunkZ(), result.skyLight(), result.blockLight());
            this.markNeighborLightDirty(input.chunkX(), input.chunkZ());
        }
    }

    private void failLightComputation(final LightJobInput input, final Throwable e) {
        if (!this.isCurrentProxyMode(input.clientLightGeneration())) {
            this.cancelLightComputation(input);
            return;
        }
        final ChunkLightState state = this.chunkStates.get(input.chunkKey());
        if (this.isSameChunkGeneration(state, input)) {
            state.computingGeneration = NO_GENERATION;
        }
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error computing async chunk light", e);
    }

    private void cancelLightComputation(final LightJobInput input) {
        final ChunkLightState state = this.chunkStates.get(input.chunkKey());
        if (this.isSameChunkGeneration(state, input)) {
            state.computingGeneration = NO_GENERATION;
        }
    }

    private boolean isSameChunkGeneration(final ChunkLightState state, final LightJobInput input) {
        return state != null
                && state.sentToClient
                && state.generation == input.generation()
                && state.sections == input.sections();
    }

    private void installRealLight(final ChunkLightState state, final LightComputation result) {
        state.realSkyLight = result.skyLight();
        state.realBlockLight = result.blockLight();
        state.realSkyLightHash = result.skyLightHash();
        state.realBlockLightHash = result.blockLightHash();
    }

    private static SectionSnapshot copySectionsForLight(final ChunkSection[] sections) {
        final ChunkSection[] copy = new ChunkSection[sections.length];
        long hash = fnv1a(FNV_OFFSET, sections.length);
        for (int i = 0; i < sections.length; i++) {
            final ChunkSection section = sections[i];
            copy[i] = copySectionForLight(section);
            hash = hashSection(hash, section);
        }
        return new SectionSnapshot(copy, hash);
    }

    private static ChunkSection copySectionForLight(final ChunkSection section) {
        if (section == null) return null;
        final ChunkSectionImpl copy = new ChunkSectionImpl(false);
        copy.setNonAirBlocksCount(section.getNonAirBlocksCount());
        copy.removePalette(PaletteType.BLOCKS);

        final DataPalette blocks = section.palette(PaletteType.BLOCKS);
        if (blocks != null) {
            copy.addPalette(PaletteType.BLOCKS, copyPalette(blocks, ChunkSection.SIZE));
        }

        final DataPalette biomes = section.palette(PaletteType.BIOMES);
        if (biomes != null) {
            copy.addPalette(PaletteType.BIOMES, copyPalette(biomes, ChunkSection.BIOME_SIZE));
        }

        return copy;
    }

    private static DataPalette copyPalette(final DataPalette palette, final int valuesLength) {
        final DataPalette copy = new DataPaletteImpl(valuesLength, Math.max(1, palette.size()));
        for (int i = 0; i < palette.size(); i++) {
            copy.addId(palette.idByIndex(i));
        }
        for (int i = 0; i < valuesLength; i++) {
            copy.setPaletteIndexAt(i, palette.paletteIndexAt(i));
        }
        return copy;
    }

    @Override
    public void onBlockChange(final BlockPosition position, final int javaBlockStateId) {
        if (!this.allowsProxyComputation()) return;

        final int chunkX = position.x() >> 4;
        final int chunkZ = position.z() >> 4;
        final long chunkKey = ChunkPosition.chunkKey(chunkX, chunkZ);
        final ChunkLightState state = this.chunkStates.get(chunkKey);
        if (state == null || !state.sentToClient || state.sections == null) return;

        final int sectionIndex = (position.y() - this.tracker.getMinY()) >> 4;
        if (sectionIndex < 0 || sectionIndex >= state.sections.length) return;
        final ChunkSection section = state.sections[sectionIndex];
        if (section == null || section.palette(PaletteType.BLOCKS) == null) return;

        final int previousState = state.pendingBlockChanges.getOrDefault(position,
                section.palette(PaletteType.BLOCKS).idAt(position.x() & 15, position.y() & 15, position.z() & 15));
        if (previousState == javaBlockStateId) return;

        // 合并本 tick 同坐标的变化；立即使旧光照任务失效，但不修改后台线程正在读取的快照。
        state.pendingBlockChanges.put(position, javaBlockStateId);
        state.generation++;
        this.pendingLightUpdates.add(chunkKey);
        this.markNeighborLightDirty(chunkX, chunkZ);
    }

    private void applyPendingBlockChanges(final long chunkKey) {
        final ChunkLightState state = this.chunkStates.get(chunkKey);
        if (state == null || state.pendingBlockChanges.isEmpty()) return;

        final ChunkSection[] sections = state.sections.clone();
        for (Map.Entry<BlockPosition, Integer> entry : state.pendingBlockChanges.entrySet()) {
            final BlockPosition position = entry.getKey();
            final int sectionIndex = (position.y() - this.tracker.getMinY()) >> 4;
            // 每个受影响 section 只复制一次；其余 section 可与旧的不可变快照共用。
            if (sections[sectionIndex] == state.sections[sectionIndex]) {
                sections[sectionIndex] = copySectionForLight(sections[sectionIndex]);
            }
            final DataPalette blocks = sections[sectionIndex].palette(PaletteType.BLOCKS);
            blocks.setIdAt(position.x() & 15, position.y() & 15, position.z() & 15, entry.getValue());
        }
        state.pendingBlockChanges.clear();
        long hash = fnv1a(FNV_OFFSET, sections.length);
        for (ChunkSection section : sections) {
            hash = hashSection(hash, section);
        }
        state.sections = sections;
        state.sectionsHash = hash;
        state.computingGeneration = NO_GENERATION;
        // 保留上次亮度供差异比较；新结果仍通过 LIGHT_UPDATE 下发，不重发区块或清空客户端光照。
        final ChunkPosition position = new ChunkPosition(chunkKey);
        this.markNeighborLightDirty(position.chunkX(), position.chunkZ());
    }

    @Override
    public void onChunkUnload(final long chunkKey) {
        this.chunkStates.remove(chunkKey);
        this.pendingLightUpdates.remove(chunkKey);
    }

    @Override
    public void tick() {
        if (!this.allowsProxyComputation()) {
            this.pendingLightUpdates.clear();
            return;
        }
        if (!this.pendingLightUpdates.isEmpty()) {
            // 先应用同一批世界变化，再采集邻居快照，避免异步任务读到半更新的邻接世界。
            for (long chunkKey : new ArrayList<>(this.pendingLightUpdates)) {
                this.applyPendingBlockChanges(chunkKey);
            }
            int lightBudget = MAX_LIGHT_UPDATES_PER_TICK;
            final Iterator<Long> it = this.pendingLightUpdates.iterator();
            while (it.hasNext() && lightBudget > 0) {
                final long chunkKey = it.next();
                it.remove();
                lightBudget--;
                this.updateSingleNeighborLight(chunkKey);
            }
        }
    }

    // --- Light computation methods ---

    // neighborSections: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z; elements may be null
    // neighborRealSkyLight: completed real sky light from already-sent neighbors; elements may be null
    private static byte[][] computeSkyLight(final ChunkSection[] sections, final ChunkSection[][] neighborSections, final byte[][][] neighborRealSkyLight, final CustomMappingAccess mappings) {
        final int sectionCount = sections.length;
        final int lightSectionCount = sectionCount + 2;

        // lightData[0] = below bottom section, lightData[1..sectionCount] = actual sections, lightData[sectionCount+1] = above top
        final byte[][] lightData = new byte[lightSectionCount][];
        for (int i = 0; i < lightSectionCount; i++) {
            lightData[i] = new byte[ChunkSectionLight.LIGHT_LENGTH];
        }

        // Above-top section: all 15
        Arrays.fill(lightData[sectionCount + 1], (byte) 0xFF);

        // Phase 1: Column-based sky light initialization (top to bottom)
        final int[][] skyLevel = new int[16][16];
        for (int[] row : skyLevel) Arrays.fill(row, 15);

        for (int sIdx = sectionCount - 1; sIdx >= 0; sIdx--) {
            final DataPalette blockPalette = sections[sIdx].palette(PaletteType.BLOCKS);
            final byte[] sectionLight = lightData[sIdx + 1];

            if (blockPalette.size() == 1 && blockPalette.idByIndex(0) == 0) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int level = skyLevel[x][z];
                        if (level > 0) {
                            for (int y = 15; y >= 0; y--) {
                                setNibble(sectionLight, x, y, z, level);
                            }
                        }
                    }
                }
                continue;
            }

            for (int y = 15; y >= 0; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int level = skyLevel[x][z];
                        if (level <= 0) continue;

                        final int blockState = blockPalette.idAt(x, y, z);
                        final int filter = mappings.filterLight(blockState);

                        if (filter > 0) {
                            level = Math.max(0, level - Math.max(1, filter));
                        }

                        skyLevel[x][z] = level;
                        if (level > 0) {
                            setNibble(sectionLight, x, y, z, level);
                        }
                    }
                }
            }
        }

        // Below-bottom section: continue from skyLevel
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final int level = skyLevel[x][z];
                if (level > 0) {
                    for (int y = 15; y >= 0; y--) {
                        setNibble(lightData[0], x, y, z, level);
                    }
                }
            }
        }

        // Phase 2: BFS horizontal propagation
        final IntQueue queue = new IntQueue();

        // Inject neighbor cached sky light at borders
        injectNeighborLight(lightData, sections, neighborRealSkyLight, queue, mappings);

        // Seed the queue from internal positions
        for (int sIdx = 0; sIdx < sectionCount; sIdx++) {
            final byte[] sectionLight = lightData[sIdx + 1];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int level = getNibble(sectionLight, x, y, z);
                        if (level <= 1) continue;

                        if (shouldEnqueue(lightData, sIdx, x, y, z, level)) {
                            queue.enqueue(encodeLightPos(sIdx, x, y, z));
                        }
                    }
                }
            }
        }

        // BFS propagation
        spreadLight(lightData, sections, neighborSections, queue, mappings);

        return lightData;
    }

    private static boolean shouldEnqueue(final byte[][] lightData, final int sIdx, final int x, final int y, final int z, final int level) {
        for (int[] off : SHOULD_ENQUEUE_OFFSETS) {
            final int nx = x + off[0];
            final int nz = z + off[2];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            final int neighborLevel = getNibble(lightData[sIdx + 1], nx, y, nz);
            if (neighborLevel < level - 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inject completed real neighbor light values at chunk borders as BFS seeds.
     * neighborRealLight: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z; elements may be null.
     * Each element is byte[][] with the same lightSectionCount layout as lightData.
     */
    private static void injectNeighborLight(final byte[][] lightData, final ChunkSection[] sections,
            final byte[][][] neighborRealLight, final IntQueue queue, final CustomMappingAccess mappings) {
        if (neighborRealLight == null) return;
        final int sectionCount = sections.length;

        // Direction definitions: [neighborIdx, neighborBorderCoord, targetBorderCoord, isXAxis]
        // -X: neighbor's x=15 → our x=0;  +X: neighbor's x=0 → our x=15
        // -Z: neighbor's z=15 → our z=0;  +Z: neighbor's z=0 → our z=15
        final int[][] directions = {
            {0, 15, 0, 1},   // -X
            {1, 0, 15, 1},   // +X
            {2, 15, 0, 0},   // -Z
            {3, 0, 15, 0},   // +Z
        };

        for (int[] dir : directions) {
            final int neighborIdx = dir[0];
            final int neighborBorder = dir[1];
            final int targetBorder = dir[2];
            final boolean isXAxis = dir[3] == 1;

            if (neighborRealLight[neighborIdx] == null) continue;

            for (int sIdx = 0; sIdx < sectionCount; sIdx++) {
                final byte[] neighborLight = neighborRealLight[neighborIdx][sIdx + 1]; // +1 for below-bottom offset
                if (neighborLight == null) continue;

                final DataPalette currentPalette = sections[sIdx].palette(PaletteType.BLOCKS);

                for (int a = 0; a < 16; a++) {
                    for (int y = 0; y < 16; y++) {
                        final int nBlockX = isXAxis ? neighborBorder : a;
                        final int nBlockZ = isXAxis ? a : neighborBorder;
                        final int tBlockX = isXAxis ? targetBorder : a;
                        final int tBlockZ = isXAxis ? a : targetBorder;

                        final int neighborLightValue = getNibble(neighborLight, nBlockX, y, nBlockZ);
                        if (neighborLightValue <= 1) continue;

                        final int currentBlockState = currentPalette.idAt(tBlockX, y, tBlockZ);
                        final int filter = mappings.filterLight(currentBlockState);
                        final int newLevel = neighborLightValue - Math.max(1, filter);
                        if (newLevel <= 0) continue;

                        if (lightData[sIdx + 1] == null) {
                            lightData[sIdx + 1] = new byte[ChunkSectionLight.LIGHT_LENGTH];
                        }
                        final int current = getNibble(lightData[sIdx + 1], tBlockX, y, tBlockZ);
                        if (newLevel > current) {
                            setNibble(lightData[sIdx + 1], tBlockX, y, tBlockZ, newLevel);
                            queue.enqueue(encodeLightPos(sIdx, tBlockX, y, tBlockZ));
                        }
                    }
                }
            }
        }
    }

    /**
     * BFS light propagation shared by both sky light and block light.
     * Spreads light within the current chunk. When hitting chunk borders,
     * reads neighbor filterLight to correctly attenuate but does NOT write to neighbor light data.
     */
    private static void spreadLight(final byte[][] lightData, final ChunkSection[] sections,
            final ChunkSection[][] neighborSections, final IntQueue queue, final CustomMappingAccess mappings) {
        final int sectionCount = sections.length;

        while (!queue.isEmpty()) {
            final int encoded = queue.dequeue();
            final int sIdx = (encoded >> 12) & 0xFF;
            final int x = encoded & 0xF;
            final int y = (encoded >> 4) & 0xF;
            final int z = (encoded >> 8) & 0xF;
            final int level = getNibble(lightData[sIdx + 1], x, y, z);
            if (level <= 1) continue;

            for (int[] dir : SPREAD_DIRS) {
                int nx = x + dir[0];
                int ny = y + dir[1];
                int nz = z + dir[2];
                int nSIdx = sIdx;

                // Handle vertical section boundary
                if (ny < 0) {
                    nSIdx--;
                    ny = 15;
                } else if (ny > 15) {
                    nSIdx++;
                    ny = 0;
                }

                if (nSIdx < 0 || nSIdx >= sectionCount) continue;

                // Handle horizontal chunk boundary
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    // Light exits the chunk — don't write, but this is fine.
                    // Neighbor updates will be handled by updateNeighborLight().
                    continue;
                }

                final int blockState = sections[nSIdx].palette(PaletteType.BLOCKS).idAt(nx, ny, nz);
                final int filter = mappings.filterLight(blockState);
                final int newLevel = level - Math.max(1, filter);
                if (newLevel <= 0) continue;

                if (lightData[nSIdx + 1] == null) {
                    lightData[nSIdx + 1] = new byte[ChunkSectionLight.LIGHT_LENGTH];
                }

                final int current = getNibble(lightData[nSIdx + 1], nx, ny, nz);
                if (newLevel > current) {
                    setNibble(lightData[nSIdx + 1], nx, ny, nz, newLevel);
                    queue.enqueue(encodeLightPos(nSIdx, nx, ny, nz));
                }
            }
        }
    }

    // neighborSections: [0]=-X, [1]=+X, [2]=-Z, [3]=+Z; elements may be null
    // neighborRealBlockLight: completed real block light from already-sent neighbors; elements may be null
    private static byte[][] computeBlockLight(final ChunkSection[] sections, final ChunkSection[][] neighborSections, final byte[][][] neighborRealBlockLight, final CustomMappingAccess mappings) {
        final int sectionCount = sections.length;
        final int lightSectionCount = sectionCount + 2;

        // null means empty (no block light); only allocate sections that have light sources
        final byte[][] lightData = new byte[lightSectionCount][];

        // Phase 1: Find all light-emitting blocks in this chunk
        final IntQueue queue = new IntQueue();

        for (int sIdx = 0; sIdx < sectionCount; sIdx++) {
            final DataPalette blockPalette = sections[sIdx].palette(PaletteType.BLOCKS);

            boolean hasEmitter = false;
            for (int i = 0; i < blockPalette.size(); i++) {
                if (mappings.emitLight(blockPalette.idByIndex(i)) > 0) {
                    hasEmitter = true;
                    break;
                }
            }
            if (!hasEmitter) continue;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final int blockState = blockPalette.idAt(x, y, z);
                        final int emission = mappings.emitLight(blockState);
                        if (emission > 0) {
                            if (lightData[sIdx + 1] == null) {
                                lightData[sIdx + 1] = new byte[ChunkSectionLight.LIGHT_LENGTH];
                            }
                            setNibble(lightData[sIdx + 1], x, y, z, emission);
                            queue.enqueue(encodeLightPos(sIdx, x, y, z));
                        }
                    }
                }
            }
        }

        // Inject neighbor cached block light at borders
        injectNeighborLight(lightData, sections, neighborRealBlockLight, queue, mappings);

        if (queue.isEmpty()) {
            return lightData;
        }

        // Phase 2: BFS propagation (shared with sky light)
        spreadLight(lightData, sections, neighborSections, queue, mappings);

        return lightData;
    }

    // --- Neighbor light management ---

    private ChunkSection[][] collectNeighborSections(final int chunkX, final int chunkZ) {
        final ChunkSection[][] neighborSections = new ChunkSection[4][];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final long neighborKey = ChunkPosition.chunkKey(nx, nz);
            final ChunkLightState state = this.chunkStates.get(neighborKey);
            neighborSections[i] = state != null ? state.sections : null;
        }
        return neighborSections;
    }

    private byte[][][] collectNeighborRealSkyLight(final int chunkX, final int chunkZ) {
        final byte[][][] result = new byte[4][][];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final ChunkLightState state = this.chunkStates.get(ChunkPosition.chunkKey(nx, nz));
            result[i] = state != null ? state.realSkyLight : null;
        }
        return result;
    }

    private byte[][][] collectNeighborRealBlockLight(final int chunkX, final int chunkZ) {
        final byte[][][] result = new byte[4][][];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final ChunkLightState state = this.chunkStates.get(ChunkPosition.chunkKey(nx, nz));
            result[i] = state != null ? state.realBlockLight : null;
        }
        return result;
    }

    private long[] collectNeighborSectionHashes(final int chunkX, final int chunkZ) {
        final long[] result = new long[4];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final ChunkLightState state = this.chunkStates.get(ChunkPosition.chunkKey(nx, nz));
            result[i] = state != null && state.sections != null ? state.sectionsHash : 0L;
        }
        return result;
    }

    private long[] collectNeighborRealSkyLightHashes(final int chunkX, final int chunkZ) {
        final long[] result = new long[4];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final ChunkLightState state = this.chunkStates.get(ChunkPosition.chunkKey(nx, nz));
            result[i] = state != null && state.realSkyLight != null ? state.realSkyLightHash : 0L;
        }
        return result;
    }

    private long[] collectNeighborRealBlockLightHashes(final int chunkX, final int chunkZ) {
        final long[] result = new long[4];
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final ChunkLightState state = this.chunkStates.get(ChunkPosition.chunkKey(nx, nz));
            result[i] = state != null && state.realBlockLight != null ? state.realBlockLightHash : 0L;
        }
        return result;
    }

    /**
     * Mark already-sent neighbors as needing a light update. The actual re-computation
     * is deferred to tick() where updates are de-duplicated and batched.
     */
    private void markNeighborLightDirty(final int chunkX, final int chunkZ) {
        for (int i = 0; i < 4; i++) {
            final int nx = chunkX + NEIGHBOR_OFFSETS[i][0];
            final int nz = chunkZ + NEIGHBOR_OFFSETS[i][1];
            final long neighborKey = ChunkPosition.chunkKey(nx, nz);
            final ChunkLightState state = this.chunkStates.get(neighborKey);
            if (state != null && state.sentToClient && state.sections != null) {
                state.lightDependencyGeneration++;
                this.pendingLightUpdates.add(neighborKey);
            }
        }
    }

    /**
     * Re-compute light for a single already-sent neighbor chunk and send LIGHT_UPDATE if changed.
     */
    private void updateSingleNeighborLight(final long neighborKey) {
        final ChunkLightState state = this.chunkStates.get(neighborKey);
        if (state == null || !state.sentToClient || state.sections == null) return;

        final ChunkPosition pos = new ChunkPosition(neighborKey);
        this.scheduleLightComputation(pos.chunkX(), pos.chunkZ(), neighborKey, state);
    }

    // --- Packet sending ---

    /**
     * Send a LIGHT_UPDATE packet to update a chunk's light without resending block data.
     */
    private void sendLightUpdate(final int chunkX, final int chunkZ, final byte[][] skyLight, final byte[][] blockLight) {
        if (!this.allowsProxyComputation()) {
            return;
        }
        final int lightSectionCount = skyLight.length;

        final BitSet skyLightMask = new BitSet();
        final BitSet blockLightMask = new BitSet();
        final BitSet emptySkyLightMask = new BitSet();
        final BitSet emptyBlockLightMask = new BitSet();

        final List<byte[]> skyLightArrays = new ArrayList<>();
        final List<byte[]> blockLightArrays = new ArrayList<>();

        for (int i = 0; i < lightSectionCount; i++) {
            skyLightMask.set(i);
            skyLightArrays.add(skyLight[i]);

            if (blockLight[i] != null) {
                blockLightMask.set(i);
                blockLightArrays.add(blockLight[i]);
            } else {
                emptyBlockLightMask.set(i);
            }
        }

        final PacketWrapper lightUpdate = PacketWrapper.create(ClientboundPackets26_1.LIGHT_UPDATE, this.user);
        lightUpdate.write(Types.VAR_INT, chunkX); // chunk x
        lightUpdate.write(Types.VAR_INT, chunkZ); // chunk z
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, skyLightMask.toLongArray()); // sky light mask
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, blockLightMask.toLongArray()); // block light mask
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, emptySkyLightMask.toLongArray()); // empty sky light mask
        lightUpdate.write(Types.LONG_ARRAY_PRIMITIVE, emptyBlockLightMask.toLongArray()); // empty block light mask
        lightUpdate.write(Types.VAR_INT, skyLightArrays.size()); // sky light length
        for (byte[] array : skyLightArrays) {
            lightUpdate.write(Types.BYTE_ARRAY_PRIMITIVE, array); // sky light
        }
        lightUpdate.write(Types.VAR_INT, blockLightArrays.size()); // block light length
        for (byte[] array : blockLightArrays) {
            lightUpdate.write(Types.BYTE_ARRAY_PRIMITIVE, array); // block light
        }
        lightUpdate.send(BedrockProtocol.class);
    }

    private boolean allowsProxyComputation() {
        final ClientLightStorage storage = this.user.get(ClientLightStorage.class);
        return allowsProxyComputation(this.user.get(ChunkTracker.class) == this.tracker, storage);
    }

    private long currentClientLightGeneration() {
        final ClientLightStorage storage = this.user.get(ClientLightStorage.class);
        return storage != null ? storage.modeGeneration() : 0L;
    }

    private boolean isCurrentProxyMode(final long clientLightGeneration) {
        final ClientLightStorage storage = this.user.get(ClientLightStorage.class);
        return isCurrentProxyMode(this.user.get(ChunkTracker.class) == this.tracker, storage, clientLightGeneration);
    }

    static boolean allowsProxyComputation(final boolean currentTracker, final ClientLightStorage storage) {
        return currentTracker && (storage == null || storage.allowsProxyComputation());
    }

    static boolean isCurrentProxyMode(final boolean currentTracker, final ClientLightStorage storage, final long clientLightGeneration) {
        return currentTracker && (storage == null
                || (storage.allowsProxyComputation() && storage.modeGeneration() == clientLightGeneration));
    }

    // --- Utility methods ---

    private static int encodeLightPos(final int sectionIdx, final int x, final int y, final int z) {
        return (sectionIdx << 12) | (z << 8) | (y << 4) | x;
    }

    private static int getNibble(final byte[] lightArray, final int x, final int y, final int z) {
        final int index = (y << 8) | (z << 4) | x;
        final int byteIndex = index >> 1;
        if ((index & 1) == 0) {
            return lightArray[byteIndex] & 0xF;
        } else {
            return (lightArray[byteIndex] >> 4) & 0xF;
        }
    }

    private static void setNibble(final byte[] lightArray, final int x, final int y, final int z, final int value) {
        final int index = (y << 8) | (z << 4) | x;
        final int byteIndex = index >> 1;
        if ((index & 1) == 0) {
            lightArray[byteIndex] = (byte) ((lightArray[byteIndex] & 0xF0) | (value & 0xF));
        } else {
            lightArray[byteIndex] = (byte) ((lightArray[byteIndex] & 0x0F) | ((value & 0xF) << 4));
        }
    }

    private static boolean hasLightChanged(final byte[][] oldLight, final long oldHash, final byte[][] newLight, final long newHash) {
        if (oldLight == null || newLight == null) return oldLight != newLight;
        if (oldHash != newHash) return true;
        if (oldLight.length != newLight.length) return true;
        for (int i = 0; i < oldLight.length; i++) {
            if (oldLight[i] == null && newLight[i] == null) continue;
            if (oldLight[i] == null || newLight[i] == null) return true;
            if (!Arrays.equals(oldLight[i], newLight[i])) return true;
        }
        return false;
    }

    // --- Hash functions for light cache ---

    private static long computeLightCacheKey(
            final long customMappingLightProfileKey,
            final long sectionsHash,
            final long[] neighborSectionHashes,
            final long[] neighborRealSkyLightHashes,
            final long[] neighborRealBlockLightHashes) {
        long hash = FNV_OFFSET;
        hash = fnv1aLong(hash, customMappingLightProfileKey);
        hash = fnv1aLong(hash, sectionsHash);
        hash = hashLongArray(hash, neighborSectionHashes);
        hash = hashLongArray(hash, neighborRealSkyLightHashes);
        hash = hashLongArray(hash, neighborRealBlockLightHashes);
        return hash;
    }

    private static long hashSection(long hash, final ChunkSection section) {
        if (section == null) {
            return fnv1a(hash, 0);
        }

        final DataPalette palette = section.palette(PaletteType.BLOCKS);
        if (palette.size() == 1) {
            return fnv1a(hash, palette.idByIndex(0));
        }

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    hash = fnv1a(hash, palette.idAt(x, y, z));
                }
            }
        }
        return hash;
    }

    private static long hashLongArray(long hash, final long[] values) {
        if (values == null) return fnv1a(hash, 0);
        hash = fnv1a(hash, values.length);
        for (final long value : values) {
            hash = fnv1aLong(hash, value);
        }
        return hash;
    }

    private static long hashLightData(long hash, final byte[][] lightData) {
        if (lightData == null) return fnv1a(hash, 0);
        hash = fnv1a(hash, lightData.length);
        for (final byte[] section : lightData) {
            if (section == null) {
                hash = fnv1a(hash, 0);
            } else {
                for (int i = 0; i < section.length; i += Integer.BYTES) {
                    int value = 0;
                    for (int j = 0; j < Integer.BYTES && i + j < section.length; j++) {
                        value |= (section[i + j] & 0xFF) << (j * Byte.SIZE);
                    }
                    hash = fnv1a(hash, value);
                }
            }
        }
        return hash;
    }

    private static long fnv1aLong(long hash, final long value) {
        hash = fnv1a(hash, (int) value);
        hash = fnv1a(hash, (int) (value >>> 32));
        return hash;
    }

    private static long fnv1a(long hash, final int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private record LightJobInput(
            long chunkKey,
            int chunkX,
            int chunkZ,
            long generation,
            long clientLightGeneration,
            long lightDependencyGeneration,
            long cacheKey,
            ChunkSection[] sections,
            ChunkSection[][] neighborSections,
            byte[][][] neighborSkyLight,
            byte[][][] neighborBlockLight,
            CustomMappingAccess access) {
    }

    private record LightComputation(byte[][] skyLight, byte[][] blockLight, long skyLightHash, long blockLightHash) {
    }

    private record SectionSnapshot(ChunkSection[] sections, long hash) {
    }

    private static final class ChunkLightState {
        private ChunkSection[] sections;
        private final Map<BlockPosition, Integer> pendingBlockChanges = new HashMap<>();
        private long sectionsHash;
        private long generation;
        private long lightDependencyGeneration;
        private boolean sentToClient;
        private byte[][] realSkyLight;
        private byte[][] realBlockLight;
        private long realSkyLightHash;
        private long realBlockLightHash;
        private long computingGeneration = NO_GENERATION;
    }

    // --- IntQueue ---

    private static final class IntQueue {
        private int[] data;
        private int head, tail;

        IntQueue() {
            this.data = new int[1024];
        }

        void enqueue(int value) {
            if (tail == data.length) {
                if (head > data.length / 4) {
                    System.arraycopy(data, head, data, 0, tail - head);
                    tail -= head;
                    head = 0;
                } else {
                    data = Arrays.copyOf(data, data.length * 2);
                }
            }
            data[tail++] = value;
        }

        int dequeue() {
            return data[head++];
        }

        boolean isEmpty() {
            return head >= tail;
        }
    }

}
