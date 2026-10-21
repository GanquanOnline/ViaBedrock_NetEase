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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.ChunkPosition;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import com.viaversion.viaversion.api.minecraft.chunks.*;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.chunk.ChunkType26_1;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectImmutablePair;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.longs.LongOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.longs.LongSet;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.util.CompactArrayUtil;
import com.viaversion.viaversion.util.MathUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockChunk;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockBlockArray;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockDataPalette;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSectionImpl;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.java.GameEventType;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.HeightmapType;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.data.generated.java.RegistryKeys;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockEntityRewriter;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.NeighborAwareBlockRewriter;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.*;
import java.util.logging.Level;

import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.block.CustomBlockDisplayTracker;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingAccess;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.light.AsyncLightEngine;
import net.raphimc.viabedrock.experimental.light.ChunkLightProvider;

// TODO: Feature: Block connections
public class ChunkTracker extends StoredObject {

    private static final int MAX_SUB_CHUNK_RETRIES = 2;
    private static final int SUB_CHUNK_RESPONSE_TIMEOUT_TICKS = 40;
    private static final int SUB_CHUNK_BLOB_TIMEOUT_TICKS = 100;
    private static final int MAX_SUB_CHUNK_OFFSETS_PER_TICK = 64;
    private static final int MAX_SUB_CHUNK_TIMEOUTS_PER_TICK = MAX_SUB_CHUNK_OFFSETS_PER_TICK;

    private final Dimension dimension;
    private final String dimensionKey;
    private final int minY;
    private final int worldHeight;
    private final int biomePaletteBits;

    private final Long2ObjectMap<BedrockChunk> chunks = new Long2ObjectOpenHashMap<>();
    private final LongSet dirtyChunks = new LongOpenHashSet();
    private final LongSet javaSentChunks = new LongOpenHashSet();
    private final ProgressiveChunkResendQueue progressiveChunkResends = new ProgressiveChunkResendQueue();

    private final ChunkLightProvider lightProvider;

    private final SubChunkRequestTracker<BedrockChunk> subChunkRequests = new SubChunkRequestTracker<>(
            MAX_SUB_CHUNK_RETRIES,
            SUB_CHUNK_RESPONSE_TIMEOUT_TICKS,
            SUB_CHUNK_BLOB_TIMEOUT_TICKS
    );

    private int centerX = 0;
    private int centerZ = 0;
    private int radius;
    private boolean levelChunksLoadStartSent;
    private long tickSequence;
    private int remainingSubChunkOffsetsThisTick;

    public ChunkTracker(final UserConnection user, final Dimension dimension) {
        this(user, dimension, dimension.getKey());
    }

    public ChunkTracker(final UserConnection user, final Dimension dimension, final String dimensionKey) {
        super(user);
        this.dimension = dimension;
        this.dimensionKey = dimensionKey;

        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final CompoundTag registries = gameSession.getJavaRegistries();
        final CompoundTag dimensionRegistry = registries.getCompoundTag(RegistryKeys.DIMENSION_TYPE);
        final CompoundTag biomeRegistry = registries.getCompoundTag(RegistryKeys.WORLDGEN_BIOME);
        final CompoundTag dimensionTag = dimensionRegistry.getCompoundTag(this.dimension.getKey());
        this.minY = dimensionTag.getNumberTag("min_y").asInt();
        this.worldHeight = dimensionTag.getNumberTag("height").asInt();
        this.biomePaletteBits = MathUtil.ceilLog2(biomeRegistry.size());

        final ClientSettingsStorage settings = user.get(ClientSettingsStorage.class);
        final int viewDistance = settings != null ? Math.max(1, settings.viewDistance()) : 8;
        final ChunkTracker oldChunkTracker = user.get(ChunkTracker.class);
        this.radius = oldChunkTracker != null ? Math.max(viewDistance, oldChunkTracker.radius) : viewDistance;
        if (!this.seedCenterFromClientPlayer() && oldChunkTracker != null) {
            this.centerX = oldChunkTracker.centerX;
            this.centerZ = oldChunkTracker.centerZ;
        }

        this.lightProvider = new AsyncLightEngine(this);
    }

    /**
     * MOT 860 spawn is often far from (0, 0) (Lobby [13,13] in the live log).
     * Java 1.21.11 rejects LEVEL_CHUNK_WITH_LIGHT outside SET_CHUNK_CACHE_CENTER,
     * so the tracker must start on the START_GAME spawn column instead of (0, 0).
     *
     * @return true when the client player position was available
     */
    private boolean seedCenterFromClientPlayer() {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null || entityTracker.getClientPlayer() == null) {
            return false;
        }
        final Position3f playerPosition = entityTracker.getClientPlayer().position();
        this.centerX = javaChunkCoord(playerPosition.x());
        this.centerZ = javaChunkCoord(playerPosition.z());
        return true;
    }

    public static int javaChunkCoord(final double blockCoordinate) {
        return (int) Math.floor(blockCoordinate) >> 4;
    }

    /**
     * Java unloads every column outside SET_CHUNK_CACHE_CENTER +/- radius.
     * A MOT publisher at (0,0) after Lobby spawn, or a copied previous-dimension
     * center, would drop the player's column when view distance is 8-12.
     * Keep the player column inside the Chebyshev window; never shrink below
     * viewDistance or apply radius 0.
     * Ref: MOT Player.orderChunks NETWORK_CHUNK_PUBLISHER_UPDATE; Java ClientChunkCache.
     */
    static int chebyshevChunkDistance(final int chunkX1, final int chunkZ1, final int chunkX2, final int chunkZ2) {
        return Math.max(Math.abs(chunkX1 - chunkX2), Math.abs(chunkZ1 - chunkZ2));
    }

    static boolean isPlayerColumnInsideJavaCache(final int centerX, final int centerZ,
                                                 final int playerChunkX, final int playerChunkZ,
                                                 final int radius) {
        return chebyshevChunkDistance(centerX, centerZ, playerChunkX, playerChunkZ) <= Math.max(0, radius);
    }

    public static int[] resolveJavaCacheCenter(final int publisherChunkX, final int publisherChunkZ,
                                        final int playerChunkX, final int playerChunkZ, final int radius) {
        if (isPlayerColumnInsideJavaCache(publisherChunkX, publisherChunkZ, playerChunkX, playerChunkZ, radius)) {
            return new int[]{publisherChunkX, publisherChunkZ};
        }
        return new int[]{playerChunkX, playerChunkZ};
    }

    public static int resolveJavaCacheRadius(final int publisherRadius, final int currentRadius, final int viewDistance) {
        final int floor = Math.max(1, viewDistance);
        final int incoming = publisherRadius > 0 ? publisherRadius : currentRadius;
        return Math.max(floor, incoming);
    }

    public static boolean shouldSnapJavaCacheCenterToPlayerColumn(final int centerX, final int centerZ,
                                                           final int playerChunkX, final int playerChunkZ,
                                                           final int radius) {
        return !isPlayerColumnInsideJavaCache(centerX, centerZ, playerChunkX, playerChunkZ, radius);
    }

    public void alignCenterToClientPlayer() {
        final int previousCenterX = this.centerX;
        final int previousCenterZ = this.centerZ;
        if (!this.seedCenterFromClientPlayer()) {
            return;
        }
        if (previousCenterX == this.centerX && previousCenterZ == this.centerZ) {
            return;
        }
        this.removeOutOfLoadDistanceChunks();
        this.markLoadedUnsentChunksDirty();
    }

    /**
     * Apply MOT NETWORK_CHUNK_PUBLISHER_UPDATE without yanking Java off the
     * player column. Returns whether the Java center packet must be sent.
     */
    public boolean applyPublisher(final int publisherChunkX, final int publisherChunkZ, final int publisherRadius) {
        final int viewDistance = this.viewDistanceOrDefault();
        final ChunkPosition playerChunk = this.playerChunk();
        final int newRadius = resolveJavaCacheRadius(publisherRadius, this.radius, viewDistance);
        final int[] newCenter = resolveJavaCacheCenter(publisherChunkX, publisherChunkZ, playerChunk.chunkX(), playerChunk.chunkZ(), newRadius);
        final boolean centerChanged = this.centerX != newCenter[0] || this.centerZ != newCenter[1];
        this.radius = newRadius;
        this.centerX = newCenter[0];
        this.centerZ = newCenter[1];
        this.removeOutOfLoadDistanceChunks();
        this.markLoadedUnsentChunksDirty();
        return centerChanged;
    }

    public void setCenter(final int x, final int z) {
        final ChunkPosition playerChunk = this.playerChunk();
        final int[] newCenter = resolveJavaCacheCenter(x, z, playerChunk.chunkX(), playerChunk.chunkZ(), this.radius);
        this.centerX = newCenter[0];
        this.centerZ = newCenter[1];
        this.removeOutOfLoadDistanceChunks();
        this.markLoadedUnsentChunksDirty();
    }

    public void setRadius(final int radius) {
        this.radius = resolveJavaCacheRadius(radius, this.radius, this.viewDistanceOrDefault());
        this.removeOutOfLoadDistanceChunks();
    }

    private int viewDistanceOrDefault() {
        final ClientSettingsStorage settings = this.user().get(ClientSettingsStorage.class);
        return settings != null ? Math.max(1, settings.viewDistance()) : Math.max(1, this.radius);
    }

    public int centerX() {
        return this.centerX;
    }

    public int centerZ() {
        return this.centerZ;
    }

    public int radius() {
        return this.radius;
    }

    private JoinGate joinGate() {
        return this.user().get(JoinGate.class);
    }

    private boolean suppressJavaRuntimePacketBeforeLogin(final ClientboundPackets26_1 packet) {
        final JoinGate joinGate = this.joinGate();
        if (joinGate == null || joinGate.atLeastJoinPhase(JoinGate.JoinPhase.LOGIN_SENT)) {
            return false;
        }

        return true;
    }

    private boolean delayNonPlayerChunkBeforeJavaLogin(final int chunkX, final int chunkZ, final boolean playerChunkForGate) {
        final JoinGate joinGate = this.joinGate();
        if (joinGate == null || joinGate.atLeastJoinPhase(JoinGate.JoinPhase.LOGIN_SENT) || playerChunkForGate) {
            return false;
        }

        this.dirtyChunks.add(ChunkPosition.chunkKey(chunkX, chunkZ));
        return true;
    }

    private boolean ensureJavaLoginForPlayerChunk(final int chunkX, final int chunkZ, final boolean playerChunkForGate) {
        final JoinGate joinGate = this.joinGate();
        if (joinGate == null || joinGate.atLeastJoinPhase(JoinGate.JoinPhase.LOGIN_SENT) || !playerChunkForGate) {
            return true;
        }

        joinGate.onPlayerChunkReady(chunkX, chunkZ);
        return joinGate.trySendJavaLogin();
    }

    private void markPlayerChunkSentForJoinGate(final boolean playerChunk) {
        if (!playerChunk) {
            return;
        }

        final JoinGate joinGate = this.joinGate();
        if (joinGate != null) {
            joinGate.onPlayerChunkSent();
        }
    }

    public void sendCurrentCacheSettingsToJava() {
        final PacketWrapper setChunkCacheCenter = PacketWrapper.create(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER, this.user());
        setChunkCacheCenter.write(Types.VAR_INT, this.centerX); // chunk x
        setChunkCacheCenter.write(Types.VAR_INT, this.centerZ); // chunk z
        setChunkCacheCenter.send(BedrockProtocol.class);

        final PacketWrapper setChunkCacheRadius = PacketWrapper.create(ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS, this.user());
        setChunkCacheRadius.write(Types.VAR_INT, this.radius); // radius
        setChunkCacheRadius.send(BedrockProtocol.class);
    }

    public BedrockChunk createChunk(final int chunkX, final int chunkZ, final int nonNullSectionCount) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) {
            // MOT can publish spawn columns before NETWORK_CHUNK_PUBLISHER_UPDATE.
            // Recenter onto the START_GAME player so Lobby [13,13] is not dropped
            // while Java cache center is still (0, 0).
            this.alignCenterToClientPlayer();
            if (!this.isInLoadDistance(chunkX, chunkZ)) {
                return null;
            }
        }
        if (this.isPlayerChunk(chunkX, chunkZ)
                && shouldSnapJavaCacheCenterToPlayerColumn(this.centerX, this.centerZ, chunkX, chunkZ, this.radius)) {
            this.alignCenterToClientPlayer();
            if (!this.suppressJavaRuntimePacketBeforeLogin(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER)) {
                this.sendCurrentCacheSettingsToJava();
            }
        } else if (!this.isInRenderDistance(chunkX, chunkZ)) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received chunk outside of render distance, but within load distance: " + chunkX + ", " + chunkZ);
            this.alignCenterToClientPlayer();
            if (!this.suppressJavaRuntimePacketBeforeLogin(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER)) {
                this.sendCurrentCacheSettingsToJava();
            }
        }

        final BedrockChunk chunk = new BedrockChunk(chunkX, chunkZ, new BedrockChunkSection[this.worldHeight >> 4]);
        for (int i = 0; i < nonNullSectionCount && i < chunk.getSections().length; i++) {
            chunk.getSections()[i] = new BedrockChunkSectionImpl();
        }
        for (int i = 0; i < chunk.getSections().length; i++) {
            if (chunk.getSections()[i] == null) {
                chunk.getSections()[i] = new BedrockChunkSectionImpl(true);
            }
        }
        this.chunks.put(ChunkPosition.chunkKey(chunk.getX(), chunk.getZ()), chunk);
        return chunk;
    }

    public void unloadChunk(final ChunkPosition chunkPos) {
        final long key = chunkPos.chunkKey();
        this.chunks.remove(key);
        this.dirtyChunks.remove(key);
        this.javaSentChunks.remove(key);
        this.progressiveChunkResends.forget(key);
        this.subChunkRequests.cancelColumn(key);
        this.lightProvider.onChunkUnload(key);
        this.user().get(EntityTracker.class).removeItemFrame(chunkPos);
        final CustomBlockDisplayTracker displayTracker = this.user().get(CustomBlockDisplayTracker.class);
        if (displayTracker != null) {
            displayTracker.removeChunk(chunkPos);
        }

        if (this.suppressJavaRuntimePacketBeforeLogin(ClientboundPackets26_1.FORGET_LEVEL_CHUNK)) {
            return;
        }

        final PacketWrapper unloadChunk = PacketWrapper.create(ClientboundPackets26_1.FORGET_LEVEL_CHUNK, this.user());
        unloadChunk.write(Types.CHUNK_POSITION, chunkPos); // chunk position
        unloadChunk.send(BedrockProtocol.class);
    }

    public BedrockChunk getChunk(final int chunkX, final int chunkZ) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return null;
        return this.chunks.get(ChunkPosition.chunkKey(chunkX, chunkZ));
    }

    public BedrockChunkSection getChunkSection(final int chunkX, final int subChunkY, final int chunkZ) {
        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) return null;

        final int sectionIndex = subChunkY + Math.abs(this.minY >> 4);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return null;

        return chunk.getSections()[sectionIndex];
    }

    public BedrockChunkSection getChunkSection(final BlockPosition blockPosition) {
        return this.getChunkSection(blockPosition.x() >> 4, blockPosition.y() >> 4, blockPosition.z() >> 4);
    }

    public int getBlockState(final BlockPosition blockPosition) {
        return this.getBlockState(0, blockPosition);
    }

    public int getBlockState(final int layer, final BlockPosition blockPosition) {
        final BedrockChunkSection chunkSection = this.getChunkSection(blockPosition);
        if (chunkSection == null) return this.bedrockAirId();
        if (chunkSection.palettesCount(PaletteType.BLOCKS) <= layer) return this.bedrockAirId();
        return chunkSection.palettes(PaletteType.BLOCKS).get(layer).idAt(blockPosition.x() & 15, blockPosition.y() & 15, blockPosition.z() & 15);
    }

    public int getJavaBlockState(final BlockPosition blockPosition) {
        final BedrockChunkSection chunkSection = this.getChunkSection(blockPosition);
        if (chunkSection == null) return ProtocolConstants.JAVA_AIR_ID;

        final int sectionX = blockPosition.x() & 15;
        final int sectionY = blockPosition.y() & 15;
        final int sectionZ = blockPosition.z() & 15;

        return this.getJavaBlockState(chunkSection, sectionX, sectionY, sectionZ);
    }

    public int getJavaBlockState(final BedrockChunkSection section, final int sectionX, final int sectionY, final int sectionZ) {
        return this.getJavaBlockStateResolution(section, sectionX, sectionY, sectionZ, "chunk block lookup").javaBlockStateId();
    }

    public CustomMappingAccess.JavaBlockStateResolution getJavaBlockStateResolution(final BedrockChunkSection section, final int sectionX, final int sectionY, final int sectionZ, final String context) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final CustomMappingAccess customAccess = this.user().get(CustomMappingSyncStorage.class).access();
        final List<DataPalette> blockPalettes = section.palettes(PaletteType.BLOCKS);

        final int blockState0 = blockPalettes.get(0).idAt(sectionX, sectionY, sectionZ);
        final int mappedJavaBlockState = blockStateRewriter.javaId(blockState0);
        if (mappedJavaBlockState == -1) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + blockState0);
        }
        CustomMappingAccess.JavaBlockStateResolution layer0Resolution = customAccess.resolveBedrockRuntimeId(blockState0, mappedJavaBlockState, context);
        int remappedBlockState = layer0Resolution.javaBlockStateId();
        if (customAccess.shouldFailClosed(layer0Resolution)) {
            return layer0Resolution;
        }
        final CustomBlockDisplayTracker displayTracker = this.user().get(CustomBlockDisplayTracker.class);
        if (displayTracker != null) {
            final int overlayState = displayTracker.overlayJavaBlockState(blockState0, remappedBlockState);
            if (overlayState != remappedBlockState) {
                remappedBlockState = overlayState;
                layer0Resolution = new CustomMappingAccess.JavaBlockStateResolution(overlayState, layer0Resolution.reason());
            }
        }

        if (blockState0 != this.bedrockAirId() && blockPalettes.size() > 1) {
            final int blockState1 = blockPalettes.get(1).idAt(sectionX, sectionY, sectionZ);
            if (blockState1 != this.bedrockAirId()) {
                if (CustomBlockTags.WATER.equals(blockStateRewriter.tag(blockState1))) { // Waterlogging
                    final int waterloggedBlockState = blockStateRewriter.waterlog(remappedBlockState);
                    if (waterloggedBlockState == -1) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing waterlogged block state: " + blockState0);
                    }
                    final CustomMappingAccess.JavaBlockStateResolution waterloggedResolution = customAccess.resolveWaterloggedJavaBlockState(remappedBlockState, waterloggedBlockState, context + " waterlogged");
                    if (customAccess.shouldFailClosed(waterloggedResolution)) {
                        return waterloggedResolution;
                    }
                    layer0Resolution = waterloggedResolution;
                    remappedBlockState = waterloggedResolution.javaBlockStateId();
                } else {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Invalid layer 2 block state. L1: " + blockState0 + ", L2: " + blockState1);
                }
            }
        }

        return new CustomMappingAccess.JavaBlockStateResolution(remappedBlockState, layer0Resolution.reason());
    }

    public BedrockBlockEntity getBlockEntity(final BlockPosition blockPosition) {
        final BedrockChunk chunk = this.getChunk(blockPosition.x() >> 4, blockPosition.z() >> 4);
        if (chunk == null) return null;
        return chunk.getBlockEntityAt(blockPosition);
    }

    public void addBlockEntity(final BedrockBlockEntity bedrockBlockEntity) {
        final BedrockChunk chunk = this.getChunk(bedrockBlockEntity.position().x() >> 4, bedrockBlockEntity.position().z() >> 4);
        if (chunk == null) return;

        chunk.removeBlockEntityAt(bedrockBlockEntity.position());
        chunk.blockEntities().add(bedrockBlockEntity);
    }

    public boolean isChunkLoaded(final ChunkPosition chunkPos) {
        if (!this.isInLoadDistance(chunkPos.chunkX(), chunkPos.chunkZ())) return false;
        return this.chunks.containsKey(chunkPos.chunkKey());
    }

    public boolean isInUnloadedChunkSection(final Position3f playerPosition) {
        return isInUnloadedChunkSection(playerPosition, 1.62F);
    }

    /**
     * MOT AuthInput Y is eye-space. The freeze gate must use feet Y so swim/crawl
     * near a subchunk border does not freeze while the feet section is loaded.
     */
    public boolean isInUnloadedChunkSection(final Position3f playerPosition, final float eyeOffset) {
        final float feetY = playerPosition.y() - eyeOffset;
        final BlockPosition chunkSectionPosition = new BlockPosition((int) Math.floor(playerPosition.x()) >> 4, (int) Math.floor(feetY) >> 4, (int) Math.floor(playerPosition.z()) >> 4);
        final ChunkPosition chunkPos = new ChunkPosition(chunkSectionPosition.x(), chunkSectionPosition.z());
        if (!this.isChunkLoaded(chunkPos)) {
            return true;
        }
        final BedrockChunkSection chunkSection = this.getChunkSection(chunkSectionPosition.x(), chunkSectionPosition.y(), chunkSectionPosition.z());
        if (chunkSection == null) {
            return false;
        }
        if (chunkSection.hasPendingBlockUpdates()) {
            return true;
        }
        return this.dirtyChunks.contains(chunkPos.chunkKey());
    }

    public boolean isInLoadDistance(final int chunkX, final int chunkZ) {
        if (!this.isInRenderDistance(chunkX, chunkZ)) { // Bedrock accepts chunks outside the chunk render range and uses the player position as a center to determine if a chunk is allowed to be loaded
            final EntityTracker entityTracker = this.user().get(EntityTracker.class);
            if (entityTracker == null) return false;
            final int centerX = (int) Math.floor(entityTracker.getClientPlayer().position().x()) >> 4;
            final int centerZ = (int) Math.floor(entityTracker.getClientPlayer().position().z()) >> 4;
            return Math.abs(chunkX - centerX) <= this.radius && Math.abs(chunkZ - centerZ) <= this.radius;
        }

        return true;
    }

    public boolean isInRenderDistance(final int chunkX, final int chunkZ) {
        return Math.abs(chunkX - this.centerX) <= this.radius && Math.abs(chunkZ - this.centerZ) <= this.radius;
    }

    public void removeOutOfLoadDistanceChunks() {
        final Set<ChunkPosition> chunksToRemove = new HashSet<>();
        for (long chunkKey : this.chunks.keySet()) {
            final ChunkPosition chunkPos = new ChunkPosition(chunkKey);
            if (this.isInLoadDistance(chunkPos.chunkX(), chunkPos.chunkZ())) continue;

            chunksToRemove.add(chunkPos);
        }
        for (ChunkPosition chunkPos : chunksToRemove) {
            this.unloadChunk(chunkPos);
        }
    }

    public void requestSubChunks(final int chunkX, final int chunkZ, final int from, final int to) {
        for (int i = from; i < to; i++) {
            this.requestSubChunk(chunkX, i, chunkZ);
        }
        final long chunkKey = ChunkPosition.chunkKey(chunkX, chunkZ);
        if (!this.hasOutstandingSubChunks(chunkKey)) {
            final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
            if (chunk != null) {
                chunk.setRequestSubChunks(false);
            }
        }
    }

    public void requestSubChunk(final int chunkX, final int subChunkY, final int chunkZ) {
        if (!this.isInLoadDistance(chunkX, chunkZ)) return;

        final SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(chunkX, subChunkY, chunkZ);
        if (this.subChunkRequests.contains(position)) {
            return;
        }

        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return;
        }
        final BedrockChunkSection section = this.getChunkSection(chunkX, subChunkY, chunkZ);
        if (section != null && !section.hasPendingBlockUpdates()) {
            return;
        }
        if (this.subChunkRequests.enqueue(position)) {
            chunk.setRequestSubChunks(true);
        }
    }

    public SubChunkResponseToken captureSubChunkResponse(final int chunkX, final int subChunkY, final int chunkZ, final boolean waitingForBlob) {
        final SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(chunkX, subChunkY, chunkZ);
        // Bedrock sub-chunk responses use the transport's reliable ordering and carry no request id.
        // The local attempt token protects the asynchronous blob callback after this point.
        final SubChunkRequestTracker.Token<BedrockChunk> token = this.subChunkRequests.captureResponse(
                position,
                this.getChunk(chunkX, chunkZ),
                this.tickSequence,
                waitingForBlob
        );
        return token != null ? new SubChunkResponseToken(token.owner(), token.attempt()) : null;
    }

    public boolean mergeSubChunk(final int chunkX, final int subChunkY, final int chunkZ, final SubChunkResponseToken token, final BedrockChunkSection other, final List<BedrockBlockEntity> blockEntities) {
        final SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(chunkX, subChunkY, chunkZ);
        final SubChunkRequestTracker.Claim<BedrockChunk> claim = this.claimPendingSubChunk(position, token);
        if (claim == null) {
            return false;
        }
        final BedrockChunk chunk = claim.owner();
        boolean changed = false;
        try {
            if (!this.isInLoadDistance(chunkX, chunkZ)) {
                return false;
            }

            final int sectionIndex = subChunkY + Math.abs(this.minY >> 4);
            if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                return false;
            }

            final BedrockChunkSection section = chunk.getSections()[sectionIndex];
            if (!section.hasPendingBlockUpdates()) {
                return false;
            }

            final BedrockChunkSection remappedSection = this.handleBlockPalette(other);
            // From this point onward a failure may leave a partially-mutated column that must be resent.
            changed = true;
            section.mergeWith(remappedSection);
            section.applyPendingBlockUpdates(this.bedrockAirId());
            blockEntities.forEach(blockEntity -> chunk.removeBlockEntityAt(blockEntity.position()));
            chunk.blockEntities().addAll(blockEntities);
            return true;
        } finally {
            if (this.subChunkRequests.complete(claim)) {
                this.finishSubChunk(position, changed);
            }
        }
    }

    public SubChunkRetryResult retryPendingSubChunk(final int chunkX, final int subChunkY, final int chunkZ, final SubChunkResponseToken token) {
        final SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(chunkX, subChunkY, chunkZ);
        final SubChunkRequestTracker.Claim<BedrockChunk> claim = this.claimPendingSubChunk(position, token);
        if (claim == null) {
            return SubChunkRetryResult.STALE;
        }
        return this.retryOrCompleteSubChunk(claim);
    }

    public boolean completePendingSubChunk(final int chunkX, final int subChunkY, final int chunkZ, final SubChunkResponseToken token) {
        final SubChunkRequestTracker.Position position = new SubChunkRequestTracker.Position(chunkX, subChunkY, chunkZ);
        final SubChunkRequestTracker.Claim<BedrockChunk> claim = this.claimPendingSubChunk(position, token);
        if (claim == null || !this.subChunkRequests.complete(claim)) {
            return false;
        }
        this.finishSubChunk(position, false);
        return true;
    }

    private SubChunkRetryResult retryOrCompleteSubChunk(final SubChunkRequestTracker.Claim<BedrockChunk> claim) {
        final SubChunkRequestTracker.Position position = claim.position();
        final boolean ownerCanRetry = this.isInLoadDistance(position.chunkX(), position.chunkZ())
                && this.getChunk(position.chunkX(), position.chunkZ()) == claim.owner();
        final SubChunkRequestTracker.RetryResult result = this.subChunkRequests.retry(claim, ownerCanRetry);
        if (result == SubChunkRequestTracker.RetryResult.EXHAUSTED) {
            this.finishSubChunk(position, false);
        }
        return switch (result) {
            case REQUEUED -> SubChunkRetryResult.REQUEUED;
            case EXHAUSTED -> SubChunkRetryResult.EXHAUSTED;
            case STALE -> SubChunkRetryResult.STALE;
        };
    }

    private SubChunkRequestTracker.Claim<BedrockChunk> claimPendingSubChunk(final SubChunkRequestTracker.Position position, final SubChunkResponseToken token) {
        if (token == null) {
            return null;
        }
        return this.subChunkRequests.claim(
                position,
                new SubChunkRequestTracker.Token<>(token.owner(), token.attempt()),
                this.getChunk(position.chunkX(), position.chunkZ())
        );
    }

    private void finishSubChunk(final SubChunkRequestTracker.Position position, final boolean changed) {
        final long chunkKey = position.chunkKey();
        this.clearSubChunkRequestFlagIfSettled(chunkKey);
        this.queueProgressiveChunkSnapshot(position.chunkX(), position.chunkZ(), changed);
    }

    private void clearSubChunkRequestFlagIfSettled(final long chunkKey) {
        if (this.hasOutstandingSubChunks(chunkKey)) {
            return;
        }
        final BedrockChunk chunk = this.chunks.get(chunkKey);
        if (chunk != null) {
            chunk.setRequestSubChunks(false);
        }
    }

    public IntObjectPair<BlockEntity> handleBlockChange(final BlockPosition blockPosition, final int layer, final int blockState) {
        final BedrockChunkSection section = this.getChunkSection(blockPosition);
        if (section == null) {
            return null;
        }

        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final CustomMappingAccess customAccess = this.user().get(CustomMappingSyncStorage.class).access();
        final boolean hasCustomMapping = customAccess.hasCustomMappings();
        final int sectionX = blockPosition.x() & 15;
        final int sectionY = blockPosition.y() & 15;
        final int sectionZ = blockPosition.z() & 15;

        if (section.hasPendingBlockUpdates()) {
            section.addPendingBlockUpdate(sectionX, sectionY, sectionZ, layer, blockState);
            return null;
        }

        while (section.palettesCount(PaletteType.BLOCKS) <= layer) {
            final BedrockDataPalette palette = new BedrockDataPalette();
            palette.addId(this.bedrockAirId());
            section.addPalette(PaletteType.BLOCKS, palette);
        }
        final DataPalette palette = section.palettes(PaletteType.BLOCKS).get(layer);
        final int prevBlockState = palette.idAt(sectionX, sectionY, sectionZ);
        final String prevTag = blockStateRewriter.tag(prevBlockState);
        palette.setIdAt(sectionX, sectionY, sectionZ, blockState);
        final String tag = blockStateRewriter.tag(blockState);

        final CustomMappingAccess.JavaBlockStateResolution remappedResolution = this.getJavaBlockStateResolution(section, sectionX, sectionY, sectionZ, "single block update");
        if (customAccess.shouldFailClosed(remappedResolution)) {
            return null;
        }
        int remappedBlockState = remappedResolution.javaBlockStateId();

        if (!Objects.equals(prevTag, tag)) {
            this.getChunk(blockPosition.x() >> 4, blockPosition.z() >> 4).removeBlockEntityAt(blockPosition);
            entityTracker.removeItemFrame(blockPosition);
        }

        if (prevBlockState != blockState) {
            if (BlockEntityRewriter.isJavaBlockEntity(this.user(), blockState)) {
                final BedrockBlockEntity bedrockBlockEntity = this.getBlockEntity(blockPosition);
                final BlockEntity javaBlockEntity = BlockEntityRewriter.toJavaOrCreate(this.user(), blockState, bedrockBlockEntity, BlockEntity.pack(sectionX, sectionZ), (short) blockPosition.y());
                if (javaBlockEntity instanceof BlockEntityWithBlockState blockEntityWithBlockState) {
                    remappedBlockState = blockEntityWithBlockState.blockState();
                }

                if (javaBlockEntity != null && javaBlockEntity.tag() != null) {
                    if (!hasCustomMapping) {
                        remappedBlockState = customAccess.resolveJavaBlockState(remappedBlockState, "single block entity update").javaBlockStateId();
                        if (customAccess.resolveBlockEntityType(javaBlockEntity.typeId(), "single block entity update").javaBlockEntityTypeId() == -1) {
                            return new IntObjectImmutablePair<>(remappedBlockState, null);
                        }
                    }
                    return new IntObjectImmutablePair<>(remappedBlockState, javaBlockEntity);
                }
            } else if (CustomBlockTags.ITEM_FRAME.equals(tag)) {
                final BedrockBlockEntity frameBlockEntity = this.getBlockEntity(blockPosition);
                entityTracker.spawnItemFrame(blockPosition, blockStateRewriter.blockState(blockState), frameBlockEntity != null ? frameBlockEntity.tag() : null);
            }
        }

        if (!hasCustomMapping && remappedBlockState >= BedrockProtocol.MAPPINGS.getVanillaBlockStateCount()) {
            remappedBlockState = customAccess.fallbackJavaBlockState(remappedBlockState, "single block update without custom mappings");
        }

        return new IntObjectImmutablePair<>(remappedBlockState, null);
    }

    public BedrockChunkSection handleBlockPalette(final BedrockChunkSection section) {
        this.replaceLegacyBlocks(section);
        this.resolvePersistentIds(section);
        return section;
    }

    private void queueProgressiveChunkSnapshot(final int chunkX, final int chunkZ, final boolean changed) {
        final long chunkKey = ChunkPosition.chunkKey(chunkX, chunkZ);
        final ClientLightStorage storage = this.user().get(ClientLightStorage.class);
        if (storage == null || !storage.isClientComputed()) {
            if (changed || (!this.javaSentChunks.contains(chunkKey) && !this.hasOutstandingSubChunks(chunkKey))) {
                this.dirtyChunks.add(chunkKey);
            }
            return;
        }
        if (
                this.progressiveChunkResends.onProgress(
                        chunkKey,
                        this.javaSentChunks.contains(chunkKey),
                        this.hasOutstandingSubChunks(chunkKey),
                        changed)
        ) {
            this.dirtyChunks.add(chunkKey);
        }
    }

    private boolean hasOutstandingSubChunks(final long chunkKey) {
        return this.subChunkRequests.hasOutstanding(chunkKey);
    }

    public void sendChunk(final int chunkX, final int chunkZ) {
        if (!this.trySendChunk(chunkX, chunkZ)) {
            this.dirtyChunks.add(ChunkPosition.chunkKey(chunkX, chunkZ));
        }
    }

    boolean trySendChunk(final int chunkX, final int chunkZ) {
        final BedrockChunk chunk = this.getChunk(chunkX, chunkZ);
        if (chunk == null) {
            return true;
        }

        final boolean playerChunkForGate = this.isPlayerChunk(chunkX, chunkZ);
        if (this.delayNonPlayerChunkBeforeJavaLogin(chunkX, chunkZ, playerChunkForGate)) {
            return false;
        }

        final Chunk translatableChunk = this.remapChunk(chunk);
        if (translatableChunk == null) {
            return false;
        }

        if (!this.ensureJavaLoginForPlayerChunk(chunkX, chunkZ, playerChunkForGate)) {
            return false;
        }

        final ClientLightStorage clientLightStorage = this.user().get(ClientLightStorage.class);
        if (clientLightStorage != null) {
            if (clientLightStorage.freeze()) {
                ViaBedrock.getPlatform().getLogger().fine("Froze ECClientLight negotiation at first chunk: " + clientLightStorage.mode());
            }
            if (clientLightStorage.isClientComputed()) {
                if (!this.stripCustomBlockData(translatableChunk)) {
                    return false;
                }
                final int lightSectionCount = translatableChunk.getSections().length + 2;
                this.sendChunkWithPlaceholderLight(translatableChunk, lightSectionCount);
                if (clientLightStorage.markClientComputedBypassLogged()) {
                    ViaBedrock.getPlatform().getLogger().fine("Sent first chunk without proxy light computation for ECClientLight");
                }
                return true;
            }
        }

        // SERVER_COMPUTED always has one provider; a refusal means the tracker/mode invariant broke.
        if (this.lightProvider.processAndSendChunk(this, chunkX, chunkZ, translatableChunk)) {
            return true;
        }
        if (this.user().get(ChunkTracker.class) != this) {
            return false;
        }
        throw new IllegalStateException("Current ChunkTracker has no active proxy light provider in server-computed mode");
    }

    /** Re-applies custom-block display overlays after the Java resource pack becomes available. */
    public void rescanCustomBlockDisplays() {
        final CustomBlockDisplayTracker displayTracker = this.user().get(CustomBlockDisplayTracker.class);
        if (displayTracker == null) {
            return;
        }
        for (BedrockChunk chunk : this.chunks.values()) {
            if (!this.javaSentChunks.contains(ChunkPosition.chunkKey(chunk.getX(), chunk.getZ()))) {
                continue;
            }
            displayTracker.syncChunk(chunk, this.minY);
        }
    }

    private boolean isPlayerChunk(final int chunkX, final int chunkZ) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return false;
        }

        final Position3f playerPosition = entityTracker.getClientPlayer().position();
        final int playerChunkX = (int) Math.floor(playerPosition.x()) >> 4;
        final int playerChunkZ = (int) Math.floor(playerPosition.z()) >> 4;
        return chunkX == playerChunkX && chunkZ == playerChunkZ;
    }

    public void sendChunkWithLight(final Chunk remappedChunk, final byte[][] skyLight, final byte[][] blockLight, final int lightSectionCount) {
        final ChunkLightPayload lightPayload = ChunkLightPayload.create(skyLight, blockLight, lightSectionCount);
        this.sendChunkWithLight(remappedChunk, lightPayload);
    }

    public void sendChunkWithPlaceholderLight(final Chunk remappedChunk, final int lightSectionCount) {
        this.sendChunkWithLight(remappedChunk, ChunkLightPayload.placeholder(lightSectionCount));
    }

    private void sendChunkWithLight(final Chunk remappedChunk, final ChunkLightPayload lightPayload) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        boolean playerChunk = false;
        if (entityTracker != null) {
            final Position3f playerPosition = entityTracker.getClientPlayer().position();
            final int playerChunkX = (int) Math.floor(playerPosition.x()) >> 4;
            final int playerChunkZ = (int) Math.floor(playerPosition.z()) >> 4;
            playerChunk = remappedChunk.getX() == playerChunkX && remappedChunk.getZ() == playerChunkZ;
            if (playerChunk && shouldSnapJavaCacheCenterToPlayerColumn(this.centerX, this.centerZ, playerChunkX, playerChunkZ, this.radius)) {
                this.alignCenterToClientPlayer();
                if (!this.suppressJavaRuntimePacketBeforeLogin(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER)) {
                    this.sendCurrentCacheSettingsToJava();
                }
            }
            if (playerChunk && !this.levelChunksLoadStartSent
                    && !this.suppressJavaRuntimePacketBeforeLogin(ClientboundPackets26_1.GAME_EVENT)) {
                this.levelChunksLoadStartSent = true;
                PacketFactory.sendJavaGameEvent(this.user(), GameEventType.LEVEL_CHUNKS_LOAD_START, 0F);
            }
        }
        if (this.suppressJavaRuntimePacketBeforeLogin(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT)) {
            this.dirtyChunks.add(ChunkPosition.chunkKey(remappedChunk.getX(), remappedChunk.getZ()));
            return;
        }
        this.sendChunkBatchStart(playerChunk, remappedChunk.getX(), remappedChunk.getZ());

        final PacketWrapper levelChunkWithLight = PacketWrapper.create(ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT, this.user());
        levelChunkWithLight.write(this.currentChunkType(), remappedChunk); // chunk
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, lightPayload.skyLightMask().toLongArray()); // sky light mask
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, lightPayload.blockLightMask().toLongArray()); // block light mask
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, lightPayload.emptySkyLightMask().toLongArray()); // empty sky light mask
        levelChunkWithLight.write(Types.LONG_ARRAY_PRIMITIVE, lightPayload.emptyBlockLightMask().toLongArray()); // empty block light mask
        levelChunkWithLight.write(Types.VAR_INT, lightPayload.skyLightArrays().size()); // sky light length
        for (byte[] array : lightPayload.skyLightArrays()) {
            levelChunkWithLight.write(Types.BYTE_ARRAY_PRIMITIVE, array); // sky light
        }
        levelChunkWithLight.write(Types.VAR_INT, lightPayload.blockLightArrays().size()); // block light length
        for (byte[] array : lightPayload.blockLightArrays()) {
            levelChunkWithLight.write(Types.BYTE_ARRAY_PRIMITIVE, array); // block light
        }
        levelChunkWithLight.send(BedrockProtocol.class);
        final CustomBlockDisplayTracker displayTracker = this.user().get(CustomBlockDisplayTracker.class);
        if (displayTracker != null) {
            displayTracker.syncChunk(this.getChunk(remappedChunk.getX(), remappedChunk.getZ()), this.minY);
        }
        final long chunkKey = ChunkPosition.chunkKey(remappedChunk.getX(), remappedChunk.getZ());
        this.javaSentChunks.add(chunkKey);
        this.progressiveChunkResends.onSnapshotSent(chunkKey);
        this.markPlayerChunkSentForJoinGate(playerChunk);
        this.sendChunkBatchFinished(1);
    }

    private void sendChunkBatchStart(final boolean playerChunk, final int chunkX, final int chunkZ) {
        PacketWrapper.create(ClientboundPackets26_1.CHUNK_BATCH_START, this.user()).send(BedrockProtocol.class);
    }

    private void sendChunkBatchFinished(final int batchSize) {
        final PacketWrapper chunkBatchFinished = PacketWrapper.create(ClientboundPackets26_1.CHUNK_BATCH_FINISHED, this.user());
        chunkBatchFinished.write(Types.VAR_INT, batchSize);
        chunkBatchFinished.send(BedrockProtocol.class);
    }

    private Type<Chunk> currentChunkType() {
        final int blockPaletteBits = MathUtil.ceilLog2(this.user().get(CustomMappingSyncStorage.class).access().globalPaletteBlockBits());
        return new ChunkType26_1(this.worldHeight >> 4, blockPaletteBits, this.biomePaletteBits);
    }

    public boolean stripCustomBlockData(final Chunk chunk) {
        final CustomMappingAccess access = this.user().get(CustomMappingSyncStorage.class).access();

        // Replace block state IDs that are not allowed for this connection with their fallback blocks.
        for (final ChunkSection section : chunk.getSections()) {
            if (section == null) continue;
            final DataPalette blockPalette = section.palette(PaletteType.BLOCKS);
            if (blockPalette == null) continue;
            for (int i = 0; i < blockPalette.size(); i++) {
                final int stateId = blockPalette.idByIndex(i);
                final CustomMappingAccess.JavaBlockStateResolution resolution = access.resolveJavaBlockState(stateId, "chunk palette strip");
                blockPalette.setIdByIndex(i, resolution.javaBlockStateId());
            }
        }

        chunk.blockEntities().removeIf(be -> access.resolveBlockEntityType(be.typeId(), "chunk block entity strip").javaBlockEntityTypeId() == -1);
        return true;
    }

    public Dimension getDimension() {
        return this.dimension;
    }

    public String getDimensionKey() {
        return this.dimensionKey;
    }

    public int getMinY() {
        return this.minY;
    }

    public int getMaxY() {
        return this.worldHeight - Math.abs(this.minY);
    }

    public int getWorldHeight() {
        return this.worldHeight;
    }

    public int bedrockAirId() {
        return this.user().get(BlockStateRewriter.class).bedrockId(BedrockBlockState.AIR);
    }

    public boolean isEmpty() {
        boolean empty = true;
        empty &= this.chunks.isEmpty();
        empty &= this.subChunkRequests.isEmpty();
        return empty;
    }

    private static final int MAX_CHUNKS_PER_TICK = 4;
    private static final int MAX_SUB_CHUNK_OFFSETS_PER_REQUEST = 256;

    public void tick() {
        this.tickSequence++;
        this.remainingSubChunkOffsetsThisTick = MAX_SUB_CHUNK_OFFSETS_PER_TICK;
        this.expireTimedOutSubChunks();
        final JoinGate joinGate = this.joinGate();
        if (joinGate != null) {
            joinGate.tick();
        }

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final boolean initiallySpawned = entityTracker != null && entityTracker.getClientPlayer().isInitiallySpawned();
        if (initiallySpawned && !this.playerCompileNeighborhoodSent()) {
            this.sendSubChunkRequests();
        }

        if (!this.dirtyChunks.isEmpty()) {
            int count = 0;
            while (!this.dirtyChunks.isEmpty() && count < MAX_CHUNKS_PER_TICK) {
                Long dirtyChunk = this.levelChunksLoadStartSent ? this.pollPlayerNeighborhoodDirtyChunk() : this.pollPlayerDirtyChunk();
                if (dirtyChunk == null && this.levelChunksLoadStartSent && shouldFallThroughNeighborhoodGate(
                        this.playerCompileNeighborhoodSent(), this.hasPlayerNeighborhoodDirtyChunk(), this.javaSentChunks.contains(this.playerChunkKey()), !this.dirtyChunks.isEmpty())) {
                    dirtyChunk = this.pollNextDirtyChunk();
                }
                if (dirtyChunk == null) {
                    break;
                }
                final ChunkPosition chunkPos = new ChunkPosition(dirtyChunk);
                this.sendChunk(chunkPos.chunkX(), chunkPos.chunkZ());
                count++;
            }
        }

        this.lightProvider.tick();

        if (!initiallySpawned) {
            return;
        }

        if (this.levelChunksLoadStartSent && this.playerCompileNeighborhoodSent()) {
            this.sendSubChunkRequests();
        }
    }

    private void sendSubChunkRequests() {
        final Set<Long> canceledColumns = this.subChunkRequests.cancelIf(
                position -> !this.isInLoadDistance(position.chunkX(), position.chunkZ())
        );
        for (long chunkKey : canceledColumns) {
            this.clearSubChunkRequestFlagIfSettled(chunkKey);
        }
        final BlockPosition basePosition = new BlockPosition(this.centerX, 0, this.centerZ);
        while (this.subChunkRequests.hasQueued() && this.remainingSubChunkOffsetsThisTick > 0) {
            final Set<SubChunkRequestTracker.Position> group = this.pollNextSubChunkRequestGroup(
                    Math.min(MAX_SUB_CHUNK_OFFSETS_PER_REQUEST, this.remainingSubChunkOffsetsThisTick)
            );
            if (group.isEmpty()) {
                break;
            }
            final Iterator<SubChunkRequestTracker.Position> iterator = group.iterator();
            while (iterator.hasNext()) {
                final SubChunkRequestTracker.Position position = iterator.next();
                final SubChunkRequestTracker.DispatchResult result = this.subChunkRequests.dispatch(
                        position,
                        this.getChunk(position.chunkX(), position.chunkZ()),
                        this.tickSequence
                );
                if (result != SubChunkRequestTracker.DispatchResult.DISPATCHED) {
                    iterator.remove();
                    if (result == SubChunkRequestTracker.DispatchResult.DISCARDED) {
                        this.clearSubChunkRequestFlagIfSettled(position.chunkKey());
                    }
                }
            }
            if (group.isEmpty()) {
                continue;
            }

            final PacketWrapper subChunkRequest = PacketWrapper.create(ServerboundBedrockPackets.SUB_CHUNK_REQUEST, this.user());
            subChunkRequest.write(BedrockTypes.VAR_INT, this.dimension.ordinal()); // dimension id
            subChunkRequest.write(BedrockTypes.SIGNED_BLOCK_POSITION, basePosition); // signed base position
            subChunkRequest.write(BedrockTypes.INT_LE, group.size()); // sub chunk offset count
            for (SubChunkRequestTracker.Position subChunkPosition : group) {
                final BlockPosition offset = new BlockPosition(subChunkPosition.chunkX() - basePosition.x(), subChunkPosition.subChunkY(), subChunkPosition.chunkZ() - basePosition.z());
                subChunkRequest.write(BedrockTypes.SUB_CHUNK_OFFSET, offset); // offset
            }
            subChunkRequest.sendToServer(BedrockProtocol.class);
            this.remainingSubChunkOffsetsThisTick -= group.size();

            if (!this.playerCompileNeighborhoodSent()) {
                break;
            }
        }
    }

    private void expireTimedOutSubChunks() {
        int retriedBlobTimeouts = 0;
        int exhaustedBlobTimeouts = 0;
        int exhaustedResponseTimeouts = 0;
        final Set<Long> blobTimeoutColumns = new HashSet<>();
        final Set<Long> exhaustedResponseColumns = new HashSet<>();
        SubChunkRequestTracker.Position firstBlobTimeout = null;
        SubChunkRequestTracker.Position firstExhaustedResponse = null;
        for (SubChunkRequestTracker.Expired<BedrockChunk> expired : this.subChunkRequests.expire(
                this.tickSequence,
                MAX_SUB_CHUNK_TIMEOUTS_PER_TICK
        )) {
            final SubChunkRequestTracker.Claim<BedrockChunk> claim = expired.claim();
            if (expired.waitingForBlob()) {
                final SubChunkRetryResult result = this.retryOrCompleteSubChunk(claim);
                if (result == SubChunkRetryResult.REQUEUED) {
                    retriedBlobTimeouts++;
                } else if (result == SubChunkRetryResult.EXHAUSTED) {
                    exhaustedBlobTimeouts++;
                }
                blobTimeoutColumns.add(claim.position().chunkKey());
                if (firstBlobTimeout == null) {
                    firstBlobTimeout = claim.position();
                }
                continue;
            }
            if (this.retryOrCompleteSubChunk(claim) == SubChunkRetryResult.EXHAUSTED) {
                exhaustedResponseTimeouts++;
                exhaustedResponseColumns.add(claim.position().chunkKey());
                if (firstExhaustedResponse == null) {
                    firstExhaustedResponse = claim.position();
                }
            }
        }
        final int blobTimeouts = retriedBlobTimeouts + exhaustedBlobTimeouts;
        if (blobTimeouts > 0) {
            ViaBedrock.getPlatform().getLogger().log(
                    exhaustedBlobTimeouts > 0 ? Level.WARNING : Level.FINE,
                    "Timed out waiting for " + blobTimeouts + " sub chunk blobs across "
                            + blobTimeoutColumns.size() + " columns; requeued=" + retriedBlobTimeouts
                            + ", exhausted=" + exhaustedBlobTimeouts + ", first at " + firstBlobTimeout
            );
        }
        if (exhaustedResponseTimeouts > 0) {
            ViaBedrock.getPlatform().getLogger().log(
                    Level.WARNING,
                    "Stopped retrying " + exhaustedResponseTimeouts + " timed-out sub chunks across "
                            + exhaustedResponseColumns.size() + " columns; first at " + firstExhaustedResponse
            );
        }
    }

    public void resetJavaChunkLoading() {
        final ChunkPosition playerChunk = this.playerChunk();
        this.levelChunksLoadStartSent = false;
        this.javaSentChunks.clear();
        for (long chunkKey : this.progressiveChunkResends.drainAll()) {
            if (this.chunks.containsKey(chunkKey)) {
                this.dirtyChunks.add(chunkKey);
            }
        }
        this.setCenter(playerChunk.chunkX(), playerChunk.chunkZ());
        this.sendCurrentCacheSettingsToJava();
        this.markPlayerNeighborhoodDirty(playerChunk);
    }

    private Long pollNextDirtyChunk() {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final int originX;
        final int originZ;
        if (entityTracker != null) {
            final Position3f playerPosition = entityTracker.getClientPlayer().position();
            originX = (int) Math.floor(playerPosition.x()) >> 4;
            originZ = (int) Math.floor(playerPosition.z()) >> 4;
        } else {
            originX = this.centerX;
            originZ = this.centerZ;
        }

        Long bestKey = null;
        int bestDistance = Integer.MAX_VALUE;
        final Iterator<Long> iterator = this.dirtyChunks.iterator();
        while (iterator.hasNext()) {
            final long key = iterator.next();
            final ChunkPosition chunkPos = new ChunkPosition(key);
            final int distance = Math.abs(chunkPos.chunkX() - originX) + Math.abs(chunkPos.chunkZ() - originZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestKey = key;
                if (distance == 0) {
                    break;
                }
            }
        }
        if (bestKey != null) {
            this.dirtyChunks.remove((long) bestKey);
        }
        return bestKey;
    }

    private Long pollPlayerDirtyChunk() {
        final Long playerChunk = this.playerChunkKey();
        if (playerChunk != null && this.dirtyChunks.remove((long) playerChunk)) {
            return playerChunk;
        }
        return null;
    }

    static boolean shouldFallThroughNeighborhoodGate(final boolean neighborhoodSent, final boolean hasNeighborhoodDirty,
                                                      final boolean playerColumnSent, final boolean hasAnyDirty) {
        return !neighborhoodSent && !hasNeighborhoodDirty && playerColumnSent && hasAnyDirty;
    }

    private Long pollPlayerNeighborhoodDirtyChunk() {
        if (this.playerCompileNeighborhoodSent()) {
            return this.pollNextDirtyChunk();
        }

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return null;
        }
        final Position3f playerPosition = entityTracker.getClientPlayer().position();
        final int playerChunkX = (int) Math.floor(playerPosition.x()) >> 4;
        final int playerChunkZ = (int) Math.floor(playerPosition.z()) >> 4;

        Long bestKey = null;
        int bestDistance = Integer.MAX_VALUE;
        final Iterator<Long> iterator = this.dirtyChunks.iterator();
        while (iterator.hasNext()) {
            final long key = iterator.next();
            final ChunkPosition chunkPos = new ChunkPosition(key);
            if (Math.abs(chunkPos.chunkX() - playerChunkX) > 1 || Math.abs(chunkPos.chunkZ() - playerChunkZ) > 1) {
                continue;
            }
            final int distance = Math.abs(chunkPos.chunkX() - playerChunkX) + Math.abs(chunkPos.chunkZ() - playerChunkZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestKey = key;
                if (distance == 0) {
                    break;
                }
            }
        }
        if (bestKey != null) {
            this.dirtyChunks.remove((long) bestKey);
        }
        return bestKey;
    }

    private boolean hasPlayerNeighborhoodDirtyChunk() {
        if (this.playerCompileNeighborhoodSent()) {
            return false;
        }
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return false;
        }
        final Position3f playerPosition = entityTracker.getClientPlayer().position();
        final int playerChunkX = (int) Math.floor(playerPosition.x()) >> 4;
        final int playerChunkZ = (int) Math.floor(playerPosition.z()) >> 4;
        for (final long key : this.dirtyChunks) {
            final ChunkPosition chunkPos = new ChunkPosition(key);
            if (Math.abs(chunkPos.chunkX() - playerChunkX) <= 1 && Math.abs(chunkPos.chunkZ() - playerChunkZ) <= 1) {
                return true;
            }
        }
        return false;
    }

    private Set<SubChunkRequestTracker.Position> pollNextSubChunkRequestGroup(final int limit) {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final int originX;
        final int originZ;
        if (entityTracker != null) {
            final Position3f playerPosition = entityTracker.getClientPlayer().position();
            originX = (int) Math.floor(playerPosition.x()) >> 4;
            originZ = (int) Math.floor(playerPosition.z()) >> 4;
        } else {
            originX = this.centerX;
            originZ = this.centerZ;
        }

        final List<SubChunkRequestTracker.Position> sorted = new ArrayList<>(this.subChunkRequests.queuedPositions());
        sorted.sort(Comparator.comparingInt(position -> Math.abs(position.chunkX() - originX) + Math.abs(position.chunkZ() - originZ)));
        final Set<SubChunkRequestTracker.Position> group = new LinkedHashSet<>();
        for (SubChunkRequestTracker.Position position : sorted) {
            if (!this.levelChunksLoadStartSent && (position.chunkX() != originX || position.chunkZ() != originZ)) {
                continue;
            }
            if (this.levelChunksLoadStartSent && !this.playerCompileNeighborhoodSent() && (Math.abs(position.chunkX() - originX) > 1 || Math.abs(position.chunkZ() - originZ) > 1)) {
                continue;
            }
            group.add(position);
            if (group.size() >= limit) {
                break;
            }
        }
        return group;
    }

    private Long playerChunkKey() {
        return this.playerChunk().chunkKey();
    }

    private ChunkPosition playerChunk() {
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return new ChunkPosition(this.centerX, this.centerZ);
        }
        final Position3f playerPosition = entityTracker.getClientPlayer().position();
        final int playerChunkX = (int) Math.floor(playerPosition.x()) >> 4;
        final int playerChunkZ = (int) Math.floor(playerPosition.z()) >> 4;
        return new ChunkPosition(playerChunkX, playerChunkZ);
    }

    private void markLoadedUnsentChunksDirty() {
        for (final long chunkKey : this.chunks.keySet()) {
            if (!this.javaSentChunks.contains(chunkKey) && this.isInLoadDistance(new ChunkPosition(chunkKey).chunkX(), new ChunkPosition(chunkKey).chunkZ())) {
                this.dirtyChunks.add(chunkKey);
            }
        }
    }

    private void markPlayerNeighborhoodDirty(final ChunkPosition playerChunk) {
        for (int chunkZ = playerChunk.chunkZ() - 1; chunkZ <= playerChunk.chunkZ() + 1; chunkZ++) {
            for (int chunkX = playerChunk.chunkX() - 1; chunkX <= playerChunk.chunkX() + 1; chunkX++) {
                final long key = ChunkPosition.chunkKey(chunkX, chunkZ);
                if (this.chunks.containsKey(key)) {
                    this.dirtyChunks.add(key);
                }
            }
        }
    }

    private boolean playerCompileNeighborhoodSent() {
        if (!this.levelChunksLoadStartSent) {
            return false;
        }
        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        if (entityTracker == null) {
            return true;
        }
        final Position3f playerPosition = entityTracker.getClientPlayer().position();
        final int playerChunkX = (int) Math.floor(playerPosition.x()) >> 4;
        final int playerChunkZ = (int) Math.floor(playerPosition.z()) >> 4;
        for (int chunkZ = playerChunkZ - 1; chunkZ <= playerChunkZ + 1; chunkZ++) {
            for (int chunkX = playerChunkX - 1; chunkX <= playerChunkX + 1; chunkX++) {
                if (!this.javaSentChunks.contains(ChunkPosition.chunkKey(chunkX, chunkZ))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Marks already sent neighbor chunks dirty when this chunk's border contains a block whose fix could depend on a
     * chunk that had not arrived yet, so that neighbor is re-translated (and re-fixed) with the now complete
     * neighborhood. A neighbor that has not been sent yet will see this chunk directly and needs no resend.
     */
    private void refixSentNeighborChunks(final Chunk chunk, final int chunkX, final int chunkZ) {
        final NeighborAwareBlockRewriter rewriter = BedrockProtocol.MAPPINGS.getNeighborRewriter();
        for (int side = 0; side < 4; side++) {
            final int neighborX = chunkX + (side == 0 ? -1 : side == 1 ? 1 : 0);
            final int neighborZ = chunkZ + (side == 2 ? -1 : side == 3 ? 1 : 0);
            final long neighborKey = ChunkPosition.chunkKey(neighborX, neighborZ);
            if (!this.javaSentChunks.contains(neighborKey)) continue;
            if (rewriter.hasCrossChunkSensitiveBlockOnBorder(chunk, side)) {
                this.dirtyChunks.add(neighborKey);
            }
        }
    }

    private Chunk remapChunk(final BedrockChunk chunk) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final CustomMappingAccess customAccess = this.user().get(CustomMappingSyncStorage.class).access();
        final CustomBlockDisplayTracker displayTracker = this.user().get(CustomBlockDisplayTracker.class);
        final int airId = this.bedrockAirId();

        final Chunk remappedChunk = new Chunk1_21_5(chunk.getX(), chunk.getZ(), new ChunkSection[chunk.getSections().length], new Heightmap[2], new ArrayList<>());

        final BedrockChunkSection[] bedrockSections = chunk.getSections();
        final ChunkSection[] remappedSections = remappedChunk.getSections();
        for (int idx = 0; idx < bedrockSections.length; idx++) {
            final BedrockChunkSection bedrockSection = bedrockSections[idx];
            final List<DataPalette> blockPalettes = bedrockSection.palettes(PaletteType.BLOCKS);
            final ChunkSection remappedSection = remappedSections[idx] = new ChunkSectionImpl(false);
            final DataPalette remappedBlockPalette = remappedSection.palette(PaletteType.BLOCKS);

            if (!blockPalettes.isEmpty()) {
                final DataPalette layer0 = blockPalettes.get(0);
                if (layer0.size() == 1) {
                    remappedBlockPalette.addId(layer0.idByIndex(0));
                } else {
                    this.transferPaletteData(layer0, remappedBlockPalette);
                }

                final String[] paletteIndexBlockStateTags = new String[remappedBlockPalette.size()];
                for (int i = 0; i < remappedBlockPalette.size(); i++) {
                    final int bedrockBlockState = remappedBlockPalette.idByIndex(i);
                    final CustomMappingAccess.JavaBlockStateResolution resolution = customAccess.resolveBedrockRuntimeId(bedrockBlockState, blockStateRewriter.javaId(bedrockBlockState), "chunk palette remap");
                    remappedBlockPalette.setIdByIndex(i, resolution.javaBlockStateId());
                    paletteIndexBlockStateTags[i] = blockStateRewriter.tag(bedrockBlockState);
                }

                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            final int paletteIndex = remappedBlockPalette.paletteIndexAt(remappedBlockPalette.index(x, y, z));
                            final String tag = paletteIndexBlockStateTags[paletteIndex];
                            final int bedrockRuntimeId = layer0.idAt(x, y, z);
                            if (displayTracker != null) {
                                final int overlayState = displayTracker.overlayJavaBlockState(bedrockRuntimeId, remappedBlockPalette.idAt(x, y, z));
                                if (overlayState != remappedBlockPalette.idAt(x, y, z)) {
                                    remappedBlockPalette.setIdAt(x, y, z, overlayState);
                                }
                            }
                            if (tag != null) {
                                final int absY = this.minY + (idx << 4) + y;
                                final BlockPosition position = new BlockPosition((chunk.getX() << 4) + x, absY, (chunk.getZ() << 4) + z);
                                if (BlockEntityRewriter.isJavaBlockEntity(this.user(), bedrockRuntimeId)) {
                                    final BlockEntity javaBlockEntity = BlockEntityRewriter.toJavaOrCreate(this.user(), bedrockRuntimeId, chunk.getBlockEntityAt(position), BlockEntity.pack(x, z), (short) absY);
                                    if (javaBlockEntity instanceof BlockEntityWithBlockState blockEntityWithBlockState) {
                                        remappedBlockPalette.setIdAt(x, y, z, blockEntityWithBlockState.blockState());
                                    }
                                    if (javaBlockEntity != null && javaBlockEntity.tag() != null) {
                                        remappedChunk.blockEntities().add(javaBlockEntity);
                                    }
                                } else if (tag.equals(CustomBlockTags.ITEM_FRAME)) {
                                    final BedrockBlockEntity frameBlockEntity = chunk.getBlockEntityAt(position);
                                    this.user().get(EntityTracker.class).spawnItemFrame(position, blockStateRewriter.blockState(layer0.idAt(x, y, z)), frameBlockEntity != null ? frameBlockEntity.tag() : null);
                                }
                            }
                        }
                    }
                }

                if (blockPalettes.size() > 1) {
                    final DataPalette layer1 = blockPalettes.get(1);
                    if (layer1.size() != 1 || layer1.idByIndex(0) != airId) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                for (int y = 0; y < 16; y++) {
                                    final int blockState0 = layer0.idAt(x, y, z);
                                    if (blockState0 == airId) continue;
                                    final int blockState1 = layer1.idAt(x, y, z);
                                    if (blockState1 == airId) continue;
                                    final int javaBlockState = remappedBlockPalette.idAt(x, y, z);

                                    if (CustomBlockTags.WATER.equals(blockStateRewriter.tag(blockState1))) { // Waterlogging
                                        final int waterloggedBlockState = blockStateRewriter.waterlog(javaBlockState);
                                        final CustomMappingAccess.JavaBlockStateResolution waterloggedResolution = customAccess.resolveWaterloggedJavaBlockState(javaBlockState, waterloggedBlockState, "chunk palette waterlog");
                                        if (waterloggedBlockState == -1 && waterloggedResolution.reason() == CustomMappingAccess.FallbackReason.VANILLA) {
                                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing waterlogged block state: " + blockState0);
                                        } else {
                                            remappedBlockPalette.setIdAt(x, y, z, waterloggedResolution.javaBlockStateId());
                                        }
                                    } else {
                                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Invalid layer 2 block state. L1: " + blockState0 + ", L2: " + blockState1);
                                    }
                                }
                            }
                        }
                    }
                }

                int nonAirBlockCount = 0;
                int fluidCount = 0;
                for (int i = 0; i < ChunkSection.SIZE; i++) {
                    final int javaBlockState = remappedBlockPalette.idAt(i);
                    if (javaBlockState != ProtocolConstants.JAVA_AIR_ID) {
                        nonAirBlockCount++;
                    }
                    if (BedrockProtocol.MAPPINGS.getJavaFluidBlockStates().contains(javaBlockState)) {
                        fluidCount++;
                    }
                }
                remappedSection.setNonAirBlocksCount(nonAirBlockCount);
                remappedSection.setFluidCount(fluidCount);
            } else {
                remappedBlockPalette.addId(ProtocolConstants.JAVA_AIR_ID);
            }

            final DataPalette biomePalette = bedrockSection.palette(PaletteType.BIOMES);
            final DataPalette remappedBiomePalette = new DataPaletteImpl(ChunkSection.BIOME_SIZE);
            remappedSection.addPalette(PaletteType.BIOMES, remappedBiomePalette);

            if (biomePalette != null) {
                if (biomePalette.size() == 1) {
                    remappedBiomePalette.addId(biomePalette.idByIndex(0));
                } else {
                    for (int x = 0; x < 4; x++) {
                        for (int z = 0; z < 4; z++) {
                            for (int y = 0; y < 4; y++) {
                                final BiomeAggregator subBiomes = new BiomeAggregator(4);
                                for (int subX = 0; subX < 4; subX++) {
                                    for (int subZ = 0; subZ < 4; subZ++) {
                                        for (int subY = 0; subY < 4; subY++) {
                                            subBiomes.record(biomePalette.idAt((x << 2) + subX, (y << 2) + subY, (z << 2) + subZ));
                                        }
                                    }
                                }
                                remappedBiomePalette.setIdAt(x, y, z, subBiomes.getMaxBiome());
                            }
                        }
                    }
                }

                remappedBiomePalette.replaceIds(bedrockBiome -> {
                    final String bedrockBiomeName = BedrockProtocol.MAPPINGS.getBedrockBiomes().inverse().get(bedrockBiome);
                    if (bedrockBiomeName != null) {
                        return BedrockProtocol.MAPPINGS.getJavaBiomes().get(bedrockBiomeName);
                    } else {
                        ViaBedrock.getPlatform().getLogger().log(Level.FINE, "Missing biome: " + bedrockBiome);
                        return BedrockProtocol.MAPPINGS.getJavaBiomes().get("the_void");
                    }
                });
            } else {
                remappedBiomePalette.addId(BedrockProtocol.MAPPINGS.getJavaBiomes().get("the_void"));
            }
        }

        // Fix neighbor-aware blocks (stair shapes, fence/pane connections, door/bed halves) based on neighboring blocks
        BedrockProtocol.MAPPINGS.getNeighborRewriter().fixChunk(this, remappedChunk, chunk.getX(), chunk.getZ(), this.minY);
        // A fix computed now is wrong when it depended on a neighbor chunk that had not arrived yet (e.g. a nether
        // portal plane straddling the border). Re-run the fix on already sent neighbors now that this chunk exists.
        if (!this.javaSentChunks.contains(ChunkPosition.chunkKey(chunk.getX(), chunk.getZ()))) {
            this.refixSentNeighborChunks(remappedChunk, chunk.getX(), chunk.getZ());
        }

        final IntSet motionBlockingBlockStates = BedrockProtocol.MAPPINGS.getJavaHeightMapBlockStates().get("motion_blocking");
        final int[] worldSurface = new int[16 * 16];
        final int[] motionBlocking = new int[16 * 16];
        Arrays.fill(worldSurface, Integer.MIN_VALUE);
        Arrays.fill(motionBlocking, Integer.MIN_VALUE);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int index = (z << 4) + x;
                FIND_Y:
                for (int idx = remappedSections.length - 1; idx >= 0; idx--) {
                    final DataPalette blockPalette = remappedSections[idx].palette(PaletteType.BLOCKS);
                    if (blockPalette.size() == 1 && blockPalette.idByIndex(0) == ProtocolConstants.JAVA_AIR_ID) {
                        continue;
                    }

                    for (int y = 15; y >= 0; y--) {
                        final int blockState = blockPalette.idAt(x, y, z);
                        if (blockState != ProtocolConstants.JAVA_AIR_ID) {
                            final int value = (idx << 4) + y + 1;

                            if (worldSurface[index] == Integer.MIN_VALUE) {
                                worldSurface[index] = value;
                            }
                            if (motionBlocking[index] == Integer.MIN_VALUE && motionBlockingBlockStates.contains(blockState)) {
                                motionBlocking[index] = value;
                                break FIND_Y;
                            }
                        }
                    }
                }

                if (worldSurface[index] == Integer.MIN_VALUE) {
                    worldSurface[index] = this.minY;
                }
                if (motionBlocking[index] == Integer.MIN_VALUE) {
                    motionBlocking[index] = this.minY;
                }
            }
        }

        final int bitsPerEntry = MathUtil.ceilLog2(this.worldHeight + 1);
        remappedChunk.heightmaps()[0] = new Heightmap(HeightmapType.WORLD_SURFACE.ordinal(), CompactArrayUtil.createCompactArrayWithPadding(bitsPerEntry, worldSurface.length, i -> worldSurface[i]));
        remappedChunk.heightmaps()[1] = new Heightmap(HeightmapType.MOTION_BLOCKING.ordinal(), CompactArrayUtil.createCompactArrayWithPadding(bitsPerEntry, motionBlocking.length, i -> motionBlocking[i]));

        return remappedChunk;
    }

    private void resolvePersistentIds(final BedrockChunkSection bedrockSection) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);
        final CustomMappingAccess customAccess = this.user().get(CustomMappingSyncStorage.class).access();

        final List<DataPalette> palettes = bedrockSection.palettes(PaletteType.BLOCKS);
        for (DataPalette palette : palettes) {
            if (palette instanceof BedrockDataPalette bedrockPalette) {
                if (bedrockPalette.usesPersistentIds()) {
                    bedrockPalette.resolvePersistentIds(bedrockBlockStateTag -> {
                        final int bedrockBlockState = blockStateRewriter.bedrockIdOwned((CompoundTag) bedrockBlockStateTag);
                        if (bedrockBlockState != -1) {
                            return bedrockBlockState;
                        } else {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing block state: " + bedrockBlockStateTag);
                            if (customAccess.hasCustomMappings()) {
                                return -1;
                            }
                            return blockStateRewriter.bedrockId(BedrockBlockState.INFO_UPDATE);
                        }
                    });
                    bedrockPalette.addId(this.bedrockAirId());
                }
            }
        }
    }

    private void replaceLegacyBlocks(final BedrockChunkSection bedrockSection) {
        final BlockStateRewriter blockStateRewriter = this.user().get(BlockStateRewriter.class);

        final List<DataPalette> palettes = bedrockSection.palettes(PaletteType.BLOCKS);
        for (DataPalette palette : palettes) {
            if (palette instanceof BedrockBlockArray) {
                final BedrockDataPalette newPalette = new BedrockDataPalette();
                this.transferPaletteData(palette, newPalette);
                newPalette.replaceIds(legacyBlockState -> {
                    final int bedrockBlockState = blockStateRewriter.bedrockId(legacyBlockState);
                    if (bedrockBlockState != -1) {
                        return bedrockBlockState;
                    } else {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing legacy block state: " + legacyBlockState);
                        return this.bedrockAirId();
                    }
                });
                palettes.set(palettes.indexOf(palette), newPalette);
            }
        }
    }

    /**
     * Transfers the palette data between two different palette types.
     *
     * @param source The source palette
     * @param target The target palette
     */
    private void transferPaletteData(final DataPalette source, final DataPalette target) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    target.setIdAt(x, y, z, source.idAt(x, y, z));
                }
            }
        }
    }

    public record SubChunkResponseToken(BedrockChunk owner, long attempt) {
    }

    public enum SubChunkRetryResult {
        REQUEUED,
        EXHAUSTED,
        STALE
    }

    private static class BiomeAggregator {

        private int[] biome;
        private int[] count;
        private int size;

        private BiomeAggregator(final int capacity) {
            this.biome = new int[capacity];
            this.count = new int[capacity];
        }

        private void record(final int biome) {
            for (int i = 0; i < this.size; i++) {
                if (this.biome[i] == biome) {
                    this.count[i]++;
                    return;
                }
            }
            this.init(biome);
        }

        private int getMaxBiome() {
            int maxBiome = Integer.MIN_VALUE;
            int maxCount = Integer.MIN_VALUE;
            for (int i = 0; i < this.size; i++) {
                if (this.count[i] > maxCount) {
                    maxCount = this.count[i];
                    maxBiome = this.biome[i];
                }
            }
            return maxBiome;
        }

        private void init(final int biome) {
            if (this.size == this.biome.length) {
                final int[] newBiome = new int[this.size == 0 ? 2 : this.size * 2];
                final int[] newCount = new int[this.size == 0 ? 2 : this.size * 2];
                System.arraycopy(this.biome, 0, newBiome, 0, this.size);
                System.arraycopy(this.count, 0, newCount, 0, this.size);
                this.biome = newBiome;
                this.count = newCount;
            }
            this.biome[this.size] = biome;
            this.count[this.size] = 1;
            this.size++;
        }

    }

}
