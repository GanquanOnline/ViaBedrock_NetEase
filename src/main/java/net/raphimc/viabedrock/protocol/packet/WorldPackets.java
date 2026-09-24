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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.minecraft.*;
import com.viaversion.viaversion.api.minecraft.blockentity.BlockEntity;
import com.viaversion.viaversion.api.minecraft.chunks.ChunkSection;
import com.viaversion.viaversion.api.minecraft.chunks.PaletteType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.ints.IntObjectPair;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.util.MathUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockBlockEntity;
import net.raphimc.viabedrock.api.chunk.BedrockChunk;
import net.raphimc.viabedrock.api.chunk.BlockEntityWithBlockState;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockBiomeArray;
import net.raphimc.viabedrock.api.chunk.datapalette.BedrockDataPalette;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSection;
import net.raphimc.viabedrock.api.chunk.section.BedrockChunkSectionImpl;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.block.CustomBlockDisplayTracker;
import net.raphimc.viabedrock.experimental.storage.BlockBreakingProgressTracker;
import net.raphimc.viabedrock.experimental.storage.BlockPlacementAckTracker;
import net.raphimc.viabedrock.protocol.data.enums.Dimension;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ServerboundLoadingScreenPacketType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.SpawnPositionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.SubChunkPacket_HeightMapDataType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.SubChunkPacket_SubChunkRequestResult;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.data.enums.java.RespawnKeepFlag;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.model.BlockChangeEntry;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockEntityRewriter;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.blockentity.SignBlockEntityRewriter;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.BlockNeighborView;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.NeighborAwareBlockRewriter;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.TrackerNeighborView;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.array.ByteArrayType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

public class WorldPackets {

    private static PacketHandler updateBlockHandler(final boolean synced) {
        return wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockPosition position = wrapper.get(Types.BLOCK_POSITION1_14, 0);
            final int blockState = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // block state
            wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // flags
            final int layer = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // layer
            if (layer < 0 || layer > 1) {
                wrapper.cancel();
                return;
            }

            final IntObjectPair<BlockEntity> remappedBlock = chunkTracker.handleBlockChange(position, layer, blockState);
            if (remappedBlock == null) {
                wrapper.cancel();
                return;
            }

            // Recompute neighbor-aware blocks (stair shapes, fence/pane connections, door/bed halves) for this change
            // and every neighbor it affects.
            final CustomBlockDisplayTracker displayTracker = wrapper.user().get(CustomBlockDisplayTracker.class);
            final int displayState = displayTracker != null
                    ? displayTracker.overlayJavaBlockState(blockState, remappedBlock.keyInt())
                    : remappedBlock.keyInt();
            final BlockNeighborView view = new TrackerNeighborView(chunkTracker);
            final Map<BlockPosition, Integer> updates = BedrockProtocol.MAPPINGS.getNeighborRewriter().resolveUpdate(view, position, displayState);
            wrapper.write(Types.VAR_INT, updates.getOrDefault(position, displayState)); // block state

            // UPDATE_BLOCK_SYNCED appends entity runtime id + sync type after the common fields.
            // Consume them before leftover discard so send() cannot copy those varlongs onto Java BLOCK_UPDATE.
            if (synced) {
                wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
                wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // block sync type
            }

            // Send the BLOCK_UPDATE explicitly to ensure it arrives before any deferred BlockChangedAck
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();

            if (displayTracker != null && layer == 0) {
                displayTracker.sync(position, blockState);
            }

            for (Map.Entry<BlockPosition, Integer> entry : updates.entrySet()) {
                if (entry.getKey().equals(position)) {
                    continue;
                }
                PacketFactory.sendJavaBlockUpdate(wrapper.user(), entry.getKey(), entry.getValue());
            }

            if (remappedBlock.value() != null) {
                PacketFactory.sendJavaBlockEntityData(wrapper.user(), position, remappedBlock.value());
            }

            // Java client must receive the authoritative main-layer state before ending this break prediction.
            if (layer == 0) {
                final BlockBreakingProgressTracker breakTracker = wrapper.user().get(BlockBreakingProgressTracker.class);
                if (breakTracker != null) {
                    final Integer seq = breakTracker.consumeAck(position);
                    if (seq != null) {
                        PacketFactory.sendJavaBlockChangedAck(wrapper.user(), seq);
                    }
                }
            }

            // Send deferred BlockChangedAck for block placement (experimental feature).
            // The ack must arrive AFTER the BLOCK_UPDATE so the Java client's prediction is cleared
            // only after the server-known state has been updated, preventing placement flicker.
            final BlockPlacementAckTracker tracker = wrapper.user().get(BlockPlacementAckTracker.class);
            if (tracker != null) {
                final Integer seq = tracker.consumeAck(position);
                if (seq != null) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), seq);
                }
                for (final int expiredSeq : tracker.flushExpired()) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), expiredSeq);
                }
            }
        };
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.SET_SPAWN_POSITION, ClientboundPackets26_1.SET_DEFAULT_SPAWN_POSITION, wrapper -> {
            final int rawType = wrapper.read(BedrockTypes.VAR_INT); // type
            final SpawnPositionType type = SpawnPositionType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SpawnPositionType: " + rawType);
                wrapper.cancel();
                return;
            }
            final BlockPosition compassPosition = wrapper.read(BedrockTypes.BLOCK_POSITION); // compass position
            final Dimension dimension = Dimension.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // dimension
            if (dimension == null) {
                wrapper.cancel();
                return;
            }
            wrapper.read(BedrockTypes.BLOCK_POSITION); // spawn position

            switch (type) {
                case WorldSpawn -> {
                    wrapper.write(Types.GLOBAL_POSITION, new GlobalBlockPosition(dimension.getKey(), compassPosition.x(), compassPosition.y(), compassPosition.z()));
                    wrapper.write(Types.FLOAT, 0F); // yaw
                    wrapper.write(Types.FLOAT, 0F); // pitch
                }
                case PlayerRespawn -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled SpawnPositionType: " + type);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CHANGE_DIMENSION, ClientboundPackets26_1.RESPAWN, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            final int dimensionId = wrapper.read(BedrockTypes.VAR_INT); // dimension
            final Dimension dimension = Dimension.getByValue(dimensionId);
            if (dimension == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received CHANGE_DIMENSION with invalid dimension id: " + dimensionId);
                wrapper.cancel();
                return;
            }
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            wrapper.read(Types.BOOLEAN); // respawn
            final Long loadingScreenId;
            if (wrapper.read(Types.BOOLEAN)) { // has loading screen id
                loadingScreenId = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // loading screen id
            } else {
                loadingScreenId = null;
            }
            PacketLeftoverLayout.discardUnreadInput(wrapper);

            final ChunkTracker oldChunkTracker = wrapper.user().get(ChunkTracker.class);
            final String resolvedKey = ExperimentalFeatures.dispatchResolveDimensionKey(dimension, oldChunkTracker);
            final String dimensionKey = resolvedKey != null ? resolvedKey : dimension.getKey();

            final ChunkTracker chunkTracker = new ChunkTracker(wrapper.user(), dimension, dimensionKey);
            wrapper.user().put(chunkTracker);
            final EntityTracker oldEntityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = oldEntityTracker.getClientPlayer();
            wrapper.user().get(JavaPlayerStateStorage.class).reset();
            wrapper.user().get(SpectatorCameraTracker.class).onDimensionChange();
            ExperimentalFeatures.dispatchDimensionChange(wrapper.user());
            oldEntityTracker.prepareForRespawn();
            final EntityTracker newEntityTracker = new EntityTracker(wrapper.user());
            newEntityTracker.addEntity(clientPlayer);
            wrapper.user().put(newEntityTracker);

            PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.StartLoadingScreen, loadingScreenId);
            clientPlayer.setPosition(new Position3f(position.x(), position.y() + clientPlayer.eyeOffset(), position.z()));
            clientPlayer.setDimensionChangeInfo(new ClientPlayerEntity.DimensionChangeInfo(loadingScreenId));
            inventoryTracker.closeForDimensionChange();
            if (inventoryTracker.getCurrentForm() != null) {
                inventoryTracker.closeCurrentForm();
            }

            wrapper.write(Types.VAR_INT, dimension.ordinal()); // dimension type id
            wrapper.write(Types.STRING, dimensionKey); // dimension name
            wrapper.write(Types.LONG, 0L); // hashed seed
            final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
            wrapper.write(Types.BYTE, (byte) spectatorCamera.projectJavaGameMode(clientPlayer.javaGameMode()).ordinal()); // game mode
            wrapper.write(Types.BYTE, (byte) -1); // previous game mode
            wrapper.write(Types.BOOLEAN, false); // is debug
            wrapper.write(Types.BOOLEAN, gameSession.isFlatGenerator()); // is flat
            wrapper.write(Types.OPTIONAL_GLOBAL_POSITION, null); // last death position
            wrapper.write(Types.VAR_INT, 0); // portal cooldown
            wrapper.write(Types.VAR_INT, 64); // sea level
            wrapper.write(Types.BYTE, (byte) (RespawnKeepFlag.ATTRIBUTE_MODIFIERS.getBit() | RespawnKeepFlag.ENTITY_DATA.getBit())); // keep data mask
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();
            chunkTracker.resetJavaChunkLoading();
            clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
            // Waterdog 1.19.50+ injects CHANGE_DIMENSION then a server-originated
            // DIMENSION_CHANGE_SUCCESS. Java same-dimension transfers skip the fake
            // dimension trick and wait for this client ACK. Send it immediately so
            // leftover bytes or packet order cannot stall the handshake.
            clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.ChangeDimensionAck);
            PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, loadingScreenId);
            clientPlayer.setDimensionChangeInfo(null);
            clientPlayer.sendAttribute("minecraft:health"); // Java client always resets health on respawn, but Bedrock client keeps health when switching dimensions
            wrapper.user().get(PlayerArmorHudTracker.class).forceSync();
            clientPlayer.sendEffects(); // Java client always resets effects on respawn. Resend them

            final PacketWrapper initializeBorder = PacketWrapper.create(ClientboundPackets26_1.INITIALIZE_BORDER, wrapper.user());
            initializeBorder.write(Types.DOUBLE, 0D); // center x
            initializeBorder.write(Types.DOUBLE, 0D); // center z
            initializeBorder.write(Types.DOUBLE, 0D); // old size
            initializeBorder.write(Types.DOUBLE, 60_000_000D); // new size
            initializeBorder.write(Types.VAR_LONG, 0L); // lerp time
            initializeBorder.write(Types.VAR_INT, 60_000_000); // new absolute max size
            initializeBorder.write(Types.VAR_INT, 0); // warning blocks
            initializeBorder.write(Types.VAR_INT, 0); // warning time
            initializeBorder.send(BedrockProtocol.class);

            PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer()); // Java client always resets inventory on respawn. Resend it
            inventoryTracker.getInventoryContainer().sendSelectedHotbarSlotToClient(); // Java client always resets selected hotbar slot on respawn. Resend it
            spectatorCamera.restorePresentationAfterClientReset();
        });
        protocol.registerClientbound(ClientboundBedrockPackets.LEVEL_CHUNK, null, wrapper -> {
            wrapper.cancel();
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final int chunkX = wrapper.read(BedrockTypes.VAR_INT); // chunk x
            final int chunkZ = wrapper.read(BedrockTypes.VAR_INT); // chunk z
            final Dimension dimension = Dimension.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // dimension
            if (dimension != chunkTracker.getDimension()) {
                return;
            }
            final int sectionCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // sub chunk count
            if (sectionCount < -2) { // Bedrock client ignores this packet
                return;
            }

            final int startY = chunkTracker.getMinY() >> 4;
            final int endY = chunkTracker.getMaxY() >> 4;
            int requestSectionCount = 0;
            if (sectionCount == -2) {
                requestSectionCount = wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE) + 1; // count
            } else if (sectionCount == -1) {
                requestSectionCount = endY - startY;
            }

            final BedrockChunk previousChunk = chunkTracker.getChunk(chunkX, chunkZ);
            if (previousChunk != null) {
                chunkTracker.unloadChunk(new ChunkPosition(chunkX, chunkZ));
                if (previousChunk.isRequestSubChunks()) {
                    requestSectionCount = endY - startY;
                }
            }

            final BedrockChunk chunk = chunkTracker.createChunk(chunkX, chunkZ, requestSectionCount > 0 ? requestSectionCount : sectionCount);
            if (chunk == null) {
                return;
            }
            chunk.setRequestSubChunks(requestSectionCount > 0);

            final int fRequestSectionCount = requestSectionCount;
            final Consumer<byte[]> dataConsumer = combinedData -> {
                try {
                    if (chunkTracker.getChunk(chunkX, chunkZ) != chunk) {
                        return;
                    }
                    final ByteBuf dataBuf = Unpooled.wrappedBuffer(combinedData);

                    final BedrockChunkSection[] sections = chunk.getSections();
                    final List<BlockEntity> blockEntities = chunk.blockEntities();
                    try {
                        for (int i = 0; i < sectionCount; i++) {
                            sections[i].mergeWith(chunkTracker.handleBlockPalette(BedrockTypes.CHUNK_SECTION.read(dataBuf))); // chunk section
                            sections[i].applyPendingBlockUpdates(chunkTracker.bedrockAirId());
                        }
                        if (gameSession.getBedrockVanillaVersion().isLowerThan("1.18.0")) {
                            final byte[] biomeData = new byte[256];
                            dataBuf.readBytes(biomeData);
                            for (ChunkSection section : sections) {
                                section.addPalette(PaletteType.BIOMES, new BedrockBiomeArray(biomeData));
                            }
                        } else {
                            for (int i = 0; i < sections.length; i++) {
                                BedrockDataPalette biomePalette = BedrockTypes.RUNTIME_DATA_PALETTE.read(dataBuf); // biome palette
                                if (biomePalette == null) {
                                    if (i == 0) {
                                        throw new RuntimeException("First biome palette can not point to previous biome palette");
                                    }
                                    biomePalette = ((BedrockDataPalette) sections[i - 1].palette(PaletteType.BIOMES)).clone();
                                }
                                sections[i].addPalette(PaletteType.BIOMES, biomePalette);
                            }
                        }

                        dataBuf.skipBytes(1); // border blocks
                        while (dataBuf.isReadable()) {
                            final Tag tag = BedrockTypes.NETWORK_TAG.read(dataBuf); // block entity tag
                            if (tag instanceof CompoundTag) { // Ignore non-compound tags
                                blockEntities.add(new BedrockBlockEntity((CompoundTag) tag));
                            }
                        }
                    } catch (IndexOutOfBoundsException ignored) {
                        // Bedrock client stops reading at whatever point and loads whatever it has read successfully
                    } catch (Throwable e) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error reading chunk data", e);
                    }

                    if (chunkTracker.getChunk(chunkX, chunkZ) != chunk) {
                        return;
                    }
                    if (fRequestSectionCount > 0) {
                        chunkTracker.requestSubChunks(chunkX, chunkZ, startY, MathUtil.clamp(startY + fRequestSectionCount, startY + 1, endY));
                    }
                    if (!chunk.isRequestSubChunks()) {
                        chunkTracker.sendChunk(chunkX, chunkZ);
                    }
                } catch (Throwable e) {
                    throw new RuntimeException("Error handling chunk data", e);
                }
            };

            if (wrapper.read(Types.BOOLEAN)) { // caching enabled
                final Long[] blobs = wrapper.read(BedrockTypes.LONG_ARRAY); // blob ids
                final int expectedLength = sectionCount < 0 ? 1 : sectionCount + 1;
                if (blobs.length != expectedLength) { // Bedrock client writes random memory contents into the request and most likely crashes
                    throw new IllegalStateException("Invalid blob count: " + blobs.length + " (expected " + expectedLength + ")");
                }
                final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // data
                wrapper.user().get(BlobCache.class).getBlob(blobs).thenAccept(blob -> {
                    final byte[] combinedData = new byte[data.length + blob.length];
                    System.arraycopy(blob, 0, combinedData, 0, blob.length);
                    System.arraycopy(data, 0, combinedData, blob.length, data.length);
                    dataConsumer.accept(combinedData);
                });
            } else {
                dataConsumer.accept(wrapper.read(BedrockTypes.BYTE_ARRAY)); // data
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SUB_CHUNK, null, wrapper -> {
            wrapper.cancel();
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);

            final boolean cachingEnabled = wrapper.read(Types.BOOLEAN); // caching enabled
            final Dimension dimension = Dimension.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // dimension
            if (dimension != chunkTracker.getDimension()) {
                return;
            }
            final BlockPosition center = wrapper.read(BedrockTypes.SIGNED_BLOCK_POSITION); // signed center position
            final long count = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // count

            for (long i = 0; i < count; i++) {
                final BlockPosition offset = wrapper.read(BedrockTypes.SUB_CHUNK_OFFSET); // offset
                final SubChunkPacket_SubChunkRequestResult result = SubChunkPacket_SubChunkRequestResult.getByValue(wrapper.read(Types.BYTE), SubChunkPacket_SubChunkRequestResult.Undefined); // result
                final byte[] data = result != SubChunkPacket_SubChunkRequestResult.SuccessAllAir || !cachingEnabled ? wrapper.read(BedrockTypes.BYTE_ARRAY) : new byte[0]; // data
                final SubChunkPacket_HeightMapDataType heightmapResult = SubChunkPacket_HeightMapDataType.getByValue(wrapper.read(Types.BYTE), SubChunkPacket_HeightMapDataType.NoData); // heightmap result
                if (heightmapResult == SubChunkPacket_HeightMapDataType.HasData) {
                    wrapper.read(new ByteArrayType(256)); // heightmap data
                }
                final SubChunkPacket_HeightMapDataType renderHeightmapResult = SubChunkPacket_HeightMapDataType.getByValue(wrapper.read(Types.BYTE), SubChunkPacket_HeightMapDataType.NoData); // render heightmap result
                if (renderHeightmapResult == SubChunkPacket_HeightMapDataType.HasData) {
                    wrapper.read(new ByteArrayType(256)); // render heightmap data
                }

                final BlockPosition absolute = new BlockPosition(center.x() + offset.x(), center.y() + offset.y(), center.z() + offset.z());
                final boolean waitingForBlob = cachingEnabled && result == SubChunkPacket_SubChunkRequestResult.Success;
                final ChunkTracker.SubChunkResponseToken responseToken = chunkTracker.captureSubChunkResponse(
                        absolute.x(), absolute.y(), absolute.z(), waitingForBlob);
                final Consumer<byte[]> dataConsumer = combinedData -> {
                    try {
                        if (result == SubChunkPacket_SubChunkRequestResult.SuccessAllAir) {
                            chunkTracker.mergeSubChunk(absolute.x(), absolute.y(), absolute.z(), responseToken, new BedrockChunkSectionImpl(), new ArrayList<>());
                        } else if (result == SubChunkPacket_SubChunkRequestResult.Success) {
                            final ByteBuf dataBuf = Unpooled.wrappedBuffer(combinedData);

                            BedrockChunkSection section = new BedrockChunkSectionImpl();
                            final List<BedrockBlockEntity> blockEntities = new ArrayList<>();
                            try {
                                section = BedrockTypes.CHUNK_SECTION.read(dataBuf); // chunk section
                                while (dataBuf.isReadable()) {
                                    final Tag tag = BedrockTypes.NETWORK_TAG.read(dataBuf); // block entity tag
                                    if (tag instanceof CompoundTag) { // Ignore non-compound tags
                                        blockEntities.add(new BedrockBlockEntity((CompoundTag) tag));
                                    }
                                }
                            } catch (IndexOutOfBoundsException ignored) {
                                // Bedrock client stops reading at whatever point and loads whatever it has read successfully
                            } catch (Throwable e) {
                                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error reading sub chunk data", e);
                            }
                            chunkTracker.mergeSubChunk(absolute.x(), absolute.y(), absolute.z(), responseToken, section, blockEntities);
                        } else if (shouldRetryFailedSubChunk(result)) {
                            final ChunkTracker.SubChunkRetryResult retryResult = chunkTracker.retryPendingSubChunk(
                                    absolute.x(), absolute.y(), absolute.z(), responseToken
                            );
                            if (retryResult == ChunkTracker.SubChunkRetryResult.EXHAUSTED) {
                                ViaBedrock.getPlatform().getLogger().log(
                                        Level.WARNING,
                                        "Stopped retrying sub chunk at " + absolute + " after result " + result
                                );
                            } else if (retryResult == ChunkTracker.SubChunkRetryResult.REQUEUED) {
                                ViaBedrock.getPlatform().getLogger().fine(
                                        "Retrying sub chunk at " + absolute + " after result " + result
                                );
                            }
                        } else {
                            ViaBedrock.getPlatform().getLogger().log(
                                    Level.WARNING,
                                    "Received non-retryable sub chunk result " + result + " at " + absolute
                            );
                            chunkTracker.completePendingSubChunk(absolute.x(), absolute.y(), absolute.z(), responseToken);
                        }
                    } catch (Throwable e) {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error handling sub chunk data at " + absolute + " result=" + result + " bytes=" + combinedData.length, e);
                        throw new RuntimeException("Error handling sub chunk data", e);
                    }
                };

                if (cachingEnabled) {
                    final long hash = wrapper.read(BedrockTypes.LONG_LE); // blob id
                    if (waitingForBlob) {
                        final CompletableFuture<byte[]> blobFuture;
                        try {
                            blobFuture = wrapper.user().get(BlobCache.class).getBlob(hash);
                        } catch (Throwable throwable) {
                            handleSubChunkBlobFailure(chunkTracker, absolute, responseToken, throwable);
                            continue;
                        }
                        if (responseToken == null) {
                            continue;
                        }
                        blobFuture.whenComplete((blob, throwable) -> {
                            if (throwable != null || blob == null) {
                                handleSubChunkBlobFailure(
                                        chunkTracker,
                                        absolute,
                                        responseToken,
                                        throwable != null ? throwable : new IllegalStateException("Sub chunk blob completed without data")
                                );
                            } else if (data.length == 0) {
                                dataConsumer.accept(blob);
                            } else if (blob.length == 0) {
                                dataConsumer.accept(data);
                            } else {
                                final byte[] combinedData = new byte[data.length + blob.length];
                                System.arraycopy(blob, 0, combinedData, 0, blob.length);
                                System.arraycopy(data, 0, combinedData, blob.length, data.length);
                                dataConsumer.accept(combinedData);
                            }
                        });
                    } else {
                        try {
                            wrapper.user().get(BlobCache.class).getBlob(hash);
                        } catch (Throwable throwable) {
                            // All-air and explicit failure responses do not depend on the optional cache side effect.
                            ViaBedrock.getPlatform().getLogger().fine(
                                    "Failed to acknowledge optional sub chunk blob at " + absolute + ": " + throwable
                            );
                        }
                        dataConsumer.accept(data);
                    }
                } else {
                    dataConsumer.accept(data);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_BLOCK, ClientboundPackets26_1.BLOCK_UPDATE, new PacketHandlers() {
            @Override
            protected void register() {
                map(BedrockTypes.BLOCK_POSITION, Types.BLOCK_POSITION1_14); // position
                handler(updateBlockHandler(false));
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_BLOCK_SYNCED, ClientboundPackets26_1.BLOCK_UPDATE, new PacketHandlers() {
            @Override
            protected void register() {
                map(BedrockTypes.BLOCK_POSITION, Types.BLOCK_POSITION1_14); // position
                handler(updateBlockHandler(true));
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_SUB_CHUNK_BLOCKS, null, wrapper -> {
            wrapper.cancel(); // Need multiple packets because offsets can go over chunk boundaries
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            wrapper.read(BedrockTypes.BLOCK_POSITION); // position | Seems to be unused by the Bedrock client
            final BlockChangeEntry[][] blockUpdatesArray = new BlockChangeEntry[2][];
            blockUpdatesArray[0] = wrapper.read(BedrockTypes.BLOCK_CHANGE_ENTRY_ARRAY); // standard blocks
            blockUpdatesArray[1] = wrapper.read(BedrockTypes.BLOCK_CHANGE_ENTRY_ARRAY); // extra blocks

            final Map<BlockPosition, List<BlockChangeRecord>> blockChanges = new HashMap<>();
            final Map<BlockPosition, BlockEntity> blockEntities = new HashMap<>();
            final Map<BlockPosition, Integer> remappedBlockStates = new LinkedHashMap<>();
            final List<BlockPosition> updatedStandardBlocks = new ArrayList<>();
            for (int layer = 0; layer < blockUpdatesArray.length; layer++) {
                for (BlockChangeEntry entry : blockUpdatesArray[layer]) {
                    final IntObjectPair<BlockEntity> remappedBlock = chunkTracker.handleBlockChange(entry.position(), layer, entry.blockState());
                    if (remappedBlock == null) {
                        continue;
                    }
                    if (layer == 0) {
                        updatedStandardBlocks.add(entry.position());
                    }
                    if (remappedBlock.value() != null) {
                        blockEntities.put(entry.position(), remappedBlock.value());
                    }
                    final CustomBlockDisplayTracker displayTracker = wrapper.user().get(CustomBlockDisplayTracker.class);
                    remappedBlockStates.put(entry.position(), displayTracker != null
                            ? displayTracker.overlayJavaBlockState(entry.blockState(), remappedBlock.keyInt())
                            : remappedBlock.keyInt());
                    if (displayTracker != null && layer == 0) {
                        displayTracker.sync(entry.position(), entry.blockState());
                    }
                }
            }

            // Recompute neighbor-aware fixes (stair shapes, connections, door/bed halves) after every change in this
            // batch has been applied to the tracker, so cross-block lookups see the final state of the whole batch.
            final BlockNeighborView view = new TrackerNeighborView(chunkTracker);
            final NeighborAwareBlockRewriter neighborRewriter = BedrockProtocol.MAPPINGS.getNeighborRewriter();
            final Map<BlockPosition, Integer> finalBlockStates = new LinkedHashMap<>();
            for (Map.Entry<BlockPosition, Integer> changed : remappedBlockStates.entrySet()) {
                finalBlockStates.putAll(neighborRewriter.resolveUpdate(view, changed.getKey(), changed.getValue()));
            }

            for (Map.Entry<BlockPosition, Integer> entry : finalBlockStates.entrySet()) {
                final BlockPosition position = entry.getKey();
                final BlockPosition chunkPosition = new BlockPosition(position.x() >> 4, position.y() >> 4, position.z() >> 4);
                final BlockPosition relative = new BlockPosition(position.x() & 0xF, position.y() & 0xF, position.z() & 0xF);
                blockChanges.computeIfAbsent(chunkPosition, k -> new ArrayList<>()).add(new BlockChangeRecord1_16_2(relative.x(), relative.y(), relative.z(), entry.getValue()));
            }

            for (Map.Entry<BlockPosition, List<BlockChangeRecord>> entry : blockChanges.entrySet()) {
                final BlockPosition chunkPosition = entry.getKey();
                final List<BlockChangeRecord> changes = entry.getValue();
                final long chunkKey = packJavaSectionPosition(chunkPosition.x(), chunkPosition.y(), chunkPosition.z());

                final PacketWrapper multiBlockChange = wrapper.create(ClientboundPackets26_1.SECTION_BLOCKS_UPDATE);
                multiBlockChange.write(Types.LONG, chunkKey); // chunk position
                multiBlockChange.write(Types.VAR_LONG_BLOCK_CHANGE_ARRAY, changes.toArray(new BlockChangeRecord[0])); // block change records
                multiBlockChange.send(BedrockProtocol.class);
            }
            for (Map.Entry<BlockPosition, BlockEntity> entry : blockEntities.entrySet()) {
                PacketFactory.sendJavaBlockEntityData(wrapper.user(), entry.getKey(), entry.getValue());
            }

            // 批量主层更新全部发出后，才能确认其中对应的破坏/放置 sequence。
            final BlockBreakingProgressTracker breakTracker = wrapper.user().get(BlockBreakingProgressTracker.class);
            final BlockPlacementAckTracker placementTracker = wrapper.user().get(BlockPlacementAckTracker.class);
            for (BlockPosition position : updatedStandardBlocks) {
                if (breakTracker != null) {
                    final Integer breakSeq = breakTracker.consumeAck(position);
                    if (breakSeq != null) {
                        PacketFactory.sendJavaBlockChangedAck(wrapper.user(), breakSeq);
                    }
                }
                if (placementTracker != null) {
                    final Integer placeSeq = placementTracker.consumeAck(position);
                    if (placeSeq != null) {
                        PacketFactory.sendJavaBlockChangedAck(wrapper.user(), placeSeq);
                    }
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.BLOCK_ENTITY_DATA, ClientboundPackets26_1.BLOCK_ENTITY_DATA, new PacketHandlers() {
            @Override
            protected void register() {
                map(BedrockTypes.BLOCK_POSITION, Types.BLOCK_POSITION1_14); // position
                handler(wrapper -> {
                    final Tag tag = wrapper.read(BedrockTypes.NETWORK_TAG); // block entity tag
                    if (!(tag instanceof CompoundTag)) {
                        wrapper.cancel();
                        return;
                    }

                    final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
                    final BedrockBlockEntity bedrockBlockEntity = new BedrockBlockEntity(wrapper.get(Types.BLOCK_POSITION1_14, 0), (CompoundTag) tag);
                    chunkTracker.addBlockEntity(bedrockBlockEntity);

                    // Item frames are translated to Java entities, not block entities. Update the frame's displayed item/rotation.
                    final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
                    if (entityTracker.getItemFrameId(bedrockBlockEntity.position()) != -1) {
                        entityTracker.updateItemFrameContents(bedrockBlockEntity.position(), bedrockBlockEntity.tag());
                        wrapper.cancel();
                        return;
                    }

                    final BlockEntity javaBlockEntity = BlockEntityRewriter.toJava(wrapper.user(), chunkTracker.getBlockState(bedrockBlockEntity.position()), bedrockBlockEntity);
                    if (javaBlockEntity instanceof BlockEntityWithBlockState blockEntityWithBlockState) {
                        PacketFactory.sendJavaBlockUpdate(wrapper.user(), bedrockBlockEntity.position(), blockEntityWithBlockState.blockState());
                    }

                    if (javaBlockEntity != null && javaBlockEntity.tag() != null) {
                        wrapper.write(Types.VAR_INT, javaBlockEntity.typeId()); // type
                        wrapper.write(Types.COMPOUND_TAG, javaBlockEntity.tag()); // block entity tag
                    } else {
                        wrapper.cancel();
                    }
                });
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.NETWORK_CHUNK_PUBLISHER_UPDATE, ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS, wrapper -> {
            final BlockPosition position = wrapper.read(BedrockTypes.SIGNED_BLOCK_POSITION); // signed center position
            final int radius = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT) >> 4; // radius
            final int publisherChunkX = position.x() >> 4;
            final int publisherChunkZ = position.z() >> 4;
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final int previousRadius = chunkTracker.radius();
            // MOT orderChunks() can publish (0,0) or a stale last-order column while
            // START_GAME spawn is Lobby [13,13]. Java 1.21.11 unloads everything
            // outside SET_CHUNK_CACHE_CENTER +/- radius, so keep the player column
            // inside the window and never shrink below viewDistance.
            // Ref: MOT Player.orderChunks; ChunkTracker.resolveJavaCacheCenter.
            final boolean centerChanged = chunkTracker.applyPublisher(publisherChunkX, publisherChunkZ, radius);
            wrapper.write(Types.VAR_INT, chunkTracker.radius()); // radius

            final int count = wrapper.read(BedrockTypes.INT_LE); // server built chunks count
            for (int i = 0; i < count; i++) {
                wrapper.read(BedrockTypes.VAR_INT); // chunk x
                wrapper.read(BedrockTypes.VAR_INT); // chunk z
            }

            if (centerChanged) {
                final PacketWrapper updateViewPosition = wrapper.create(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER);
                updateViewPosition.write(Types.VAR_INT, chunkTracker.centerX()); // chunk x
                updateViewPosition.write(Types.VAR_INT, chunkTracker.centerZ()); // chunk z
                updateViewPosition.send(BedrockProtocol.class);
            }
            if (previousRadius == chunkTracker.radius()) {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CHUNK_RADIUS_UPDATED, ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS, new PacketHandlers() {
            @Override
            public void register() {
                map(BedrockTypes.VAR_INT, Types.VAR_INT); // radius
                handler(wrapper -> {
                    final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
                    final int radius = wrapper.get(Types.VAR_INT, 0);
                    final int previousRadius = chunkTracker.radius();
                    chunkTracker.setRadius(radius);
                    wrapper.set(Types.VAR_INT, 0, chunkTracker.radius());
                    if (previousRadius == chunkTracker.radius()) {
                        wrapper.cancel();
                    }
                });
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_TIME, ClientboundPackets26_1.SET_TIME, wrapper -> {
            final long bedrockTime = wrapper.read(BedrockTypes.VAR_INT); // time
            wrapper.write(Types.LONG, wrapper.user().get(GameSessionStorage.class).getLevelTime()); // game time
            wrapper.write(Types.VAR_INT, 1); // clock update count
            wrapper.write(Types.VAR_INT, 0); // registry id (overworld clock)
            wrapper.write(Types.VAR_LONG, bedrockTime >= 0L ? bedrockTime % 24000L : 24000L + (bedrockTime % 24000L)); // total ticks
            wrapper.write(Types.FLOAT, 0F); // partial tick
            wrapper.write(Types.FLOAT, wrapper.user().get(GameRulesStorage.class).getGameRule("doDayLightCycle") ? 1F : 0F); // rate
        });

        protocol.registerServerbound(ServerboundPackets26_1.SIGN_UPDATE, ServerboundBedrockPackets.BLOCK_ENTITY_DATA, wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = wrapper.user().get(BlockStateRewriter.class);
            final BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // position
            final boolean front = wrapper.read(Types.BOOLEAN); // front
            final List<String> lines = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                lines.add(wrapper.read(Types.STRING)); // line
            }

            final String tag = blockStateRewriter.tag(chunkTracker.getBlockState(position));
            final BedrockBlockEntity signBlockEntity = (CustomBlockTags.HANGING_SIGN.equals(tag) || CustomBlockTags.SIGN.equals(tag)) ? chunkTracker.getBlockEntity(position) : null;
            final CompoundTag signTag = signBlockEntity != null ? signBlockEntity.tag() : new CompoundTag();
            SignBlockEntityRewriter.upgradeData(signTag);
            SignBlockEntityRewriter.sanitizeData(signTag);
            if (CustomBlockTags.SIGN.equals(tag)) {
                signTag.putString("id", "Sign");
            } else if (CustomBlockTags.HANGING_SIGN.equals(tag)) {
                signTag.putString("id", "HangingSign");
            }
            signTag.putInt("x", position.x());
            signTag.putInt("y", position.y());
            signTag.putInt("z", position.z());

            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) { // Remove trailing empty lines
                lines.remove(lines.size() - 1);
            }
            final String text = lines.stream().reduce((a, b) -> a + '\n' + b).orElse("");
            signTag.getCompoundTag(front ? "FrontText" : "BackText").putString("Text", text);

            wrapper.write(BedrockTypes.BLOCK_POSITION, position); // position
            wrapper.write(BedrockTypes.NETWORK_TAG, signTag.copy()); // block entity tag
        });
    }

    static boolean shouldRetryFailedSubChunk(final SubChunkPacket_SubChunkRequestResult result) {
        // Explicit coordinate/dimension failures are stable; only ambiguous session failures get bounded retries.
        return result == SubChunkPacket_SubChunkRequestResult.Undefined
                || result == SubChunkPacket_SubChunkRequestResult.PlayerDoesntExist;
    }

    static long packJavaSectionPosition(final int x, final int y, final int z) {
        return (x & 0x3FFFFFL) << 42 | (z & 0x3FFFFFL) << 20 | (y & 0xFFFFFL);
    }

    private static void handleSubChunkBlobFailure(final ChunkTracker chunkTracker, final BlockPosition position,
                                                  final ChunkTracker.SubChunkResponseToken token, final Throwable throwable) {
        final ChunkTracker.SubChunkRetryResult result = chunkTracker.retryPendingSubChunk(
                position.x(), position.y(), position.z(), token
        );
        if (result == ChunkTracker.SubChunkRetryResult.STALE) {
            return;
        }
        if (result == ChunkTracker.SubChunkRetryResult.REQUEUED) {
            ViaBedrock.getPlatform().getLogger().fine(
                    "Retrying sub chunk after blob failure at " + position + ": " + throwable
            );
        } else {
            ViaBedrock.getPlatform().getLogger().log(
                    Level.WARNING,
                    "Stopped retrying sub chunk after blob failure at " + position,
                    throwable
            );
        }
    }

}
