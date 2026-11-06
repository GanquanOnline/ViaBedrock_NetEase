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
import net.raphimc.viabedrock.protocol.data.BedrockDeviation;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
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

    /** 子区块高度图长度：256 个方块（每个 4 位）。 */
    private static final int HEIGHT_MAP_LENGTH = 256;

    /** 2192 起高度图每段长度，与 MOT 的 {@code putHeightMapData} 一致。 */
    private static final int HEIGHT_MAP_SEGMENT_LENGTH = 16;

    private static PacketHandler updateBlockHandler(final boolean synced) {
        return wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockPosition position = wrapper.get(Types.BLOCK_POSITION1_14, 0);
            final int blockState = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 方块状态
            wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 标志位
            final int layer = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 层
            if (layer < 0 || layer > 1) {
                wrapper.cancel();
                return;
            }

            final IntObjectPair<BlockEntity> remappedBlock = chunkTracker.handleBlockChange(position, layer, blockState);
            if (remappedBlock == null) {
                wrapper.cancel();
                return;
            }

            // 针对本次改动及其影响的每个相邻方块，重新计算依赖邻居的方块（楼梯形状、栅栏/铁板连接、门/床的两半）。
            final CustomBlockDisplayTracker displayTracker = wrapper.user().get(CustomBlockDisplayTracker.class);
            final int displayState = displayTracker != null
                    ? displayTracker.overlayJavaBlockState(blockState, remappedBlock.keyInt())
                    : remappedBlock.keyInt();
            final BlockNeighborView view = new TrackerNeighborView(chunkTracker);
            final Map<BlockPosition, Integer> updates = BedrockProtocol.MAPPINGS.getNeighborRewriter().resolveUpdate(view, position, displayState);
            wrapper.write(Types.VAR_INT, updates.getOrDefault(position, displayState)); // 方块状态

            // UPDATE_BLOCK_SYNCED 在公共字段之后追加实体运行时 ID 和同步类型。
            // 必须在丢弃剩余字节之前读走它们，否则 send() 会把这些 varlong 复制到 Java 的 BLOCK_UPDATE 上。
            if (synced) {
                wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
                wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 方块同步类型
            }

            // 显式发送 BLOCK_UPDATE，确保它先于任何延迟的 BlockChangedAck 到达
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();

            if (displayTracker != null && layer == 0) {
                displayTracker.sync(position, blockState);
            }

            for (Map.Entry<BlockPosition, Integer> entry : updates.entrySet()) {
                // 邻接改写的结果必须同步回 ChunkTracker，否则下一次邻居判定仍用旧状态。
                chunkTracker.onJavaBlockChange(entry.getKey(), entry.getValue());
                if (entry.getKey().equals(position)) {
                    continue;
                }
                PacketFactory.sendJavaBlockUpdate(wrapper.user(), entry.getKey(), entry.getValue());
            }

            if (remappedBlock.value() != null) {
                PacketFactory.sendJavaBlockEntityData(wrapper.user(), position, remappedBlock.value());
            }

            // Java 客户端必须在结束这条破坏预测之前收到权威的主层状态。
            if (layer == 0) {
                final BlockBreakingProgressTracker breakTracker = wrapper.user().get(BlockBreakingProgressTracker.class);
                if (breakTracker != null) {
                    final Integer seq = breakTracker.consumeAck(position);
                    if (seq != null) {
                        PacketFactory.sendJavaBlockChangedAck(wrapper.user(), seq);
                    }
                }
            }

            // 为方块放置发送延迟的 BlockChangedAck（实验性功能）。
            // 该 ack 必须在 BLOCK_UPDATE 之后到达，这样 Java 客户端的预测
            // 只会在服务端已知状态更新之后才被清除，避免放置闪烁。
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
            final int rawType = wrapper.read(BedrockTypes.VAR_INT); // 类型
            final SpawnPositionType type = SpawnPositionType.getByValue(rawType);
            if (type == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown SpawnPositionType: " + rawType);
                wrapper.cancel();
                return;
            }
            final BlockPosition compassPosition = wrapper.read(BedrockTypes.BLOCK_POSITION); // 指南针坐标
            final Dimension dimension = Dimension.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // 维度
            if (dimension == null) {
                wrapper.cancel();
                return;
            }
            wrapper.read(BedrockTypes.BLOCK_POSITION); // 生成坐标

            switch (type) {
                case WorldSpawn -> {
                    wrapper.write(Types.GLOBAL_POSITION, new GlobalBlockPosition(dimension.getKey(), compassPosition.x(), compassPosition.y(), compassPosition.z()));
                    wrapper.write(Types.FLOAT, 0F); // 偏航角
                    wrapper.write(Types.FLOAT, 0F); // 俯仰角
                }
                case PlayerRespawn -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled SpawnPositionType: " + type);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CHANGE_DIMENSION, ClientboundPackets26_1.RESPAWN, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);

            final int dimensionId = wrapper.read(BedrockTypes.VAR_INT); // 维度
            final Dimension dimension = Dimension.getByValue(dimensionId);
            if (dimension == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received CHANGE_DIMENSION with invalid dimension id: " + dimensionId);
                wrapper.cancel();
                return;
            }
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 坐标
            wrapper.read(Types.BOOLEAN); // 重生
            final Long loadingScreenId;
            if (wrapper.read(Types.BOOLEAN)) { // 是否带加载界面 ID
                loadingScreenId = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // 加载界面 ID
            } else {
                loadingScreenId = null;
            }
            PacketLeftoverLayout.discardUnreadInput(wrapper);

            // 热切后端保留同一连接，旧世界的确认不能被转发给新后端。
            wrapper.user().get(PacketSyncStorage.class).invalidateNetworkStackLatencyResponses();
            final ChunkTracker oldChunkTracker = wrapper.user().get(ChunkTracker.class);
            final String resolvedKey = ExperimentalFeatures.dispatchResolveDimensionKey(dimension, oldChunkTracker);
            final String dimensionKey = resolvedKey != null ? resolvedKey : dimension.getKey();

            final ChunkTracker chunkTracker = new ChunkTracker(wrapper.user(), dimension, dimensionKey);
            wrapper.user().put(chunkTracker);
            final EntityTracker oldEntityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = oldEntityTracker.getClientPlayer();
            wrapper.user().get(JavaPlayerStateStorage.class).reset();
            clientPlayer.resetInputLocks();
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

            wrapper.write(Types.VAR_INT, dimension.ordinal()); // 维度类型 ID
            wrapper.write(Types.STRING, dimensionKey); // 维度名
            wrapper.write(Types.LONG, 0L); // 哈希种子
            final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
            wrapper.write(Types.BYTE, (byte) spectatorCamera.projectJavaGameMode(clientPlayer.javaGameMode()).ordinal()); // 游戏模式
            wrapper.write(Types.BYTE, (byte) -1); // 上一游戏模式
            wrapper.write(Types.BOOLEAN, false); // 是否为调试模式
            wrapper.write(Types.BOOLEAN, gameSession.isFlatGenerator()); // 是否为超平坦
            wrapper.write(Types.OPTIONAL_GLOBAL_POSITION, null); // 上次死亡坐标
            wrapper.write(Types.VAR_INT, 0); // 传送门冷却
            wrapper.write(Types.VAR_INT, 64); // 海平面
            wrapper.write(Types.BYTE, (byte) (RespawnKeepFlag.ATTRIBUTE_MODIFIERS.getBit() | RespawnKeepFlag.ENTITY_DATA.getBit())); // 保留数据掩码
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            wrapper.send(BedrockProtocol.class);
            wrapper.cancel();
            chunkTracker.resetJavaChunkLoading();
            clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
            // Waterdog 1.19.50+ 会先注入 CHANGE_DIMENSION，再注入一个由服务端发出的
            // DIMENSION_CHANGE_SUCCESS。Java 客户端会跳过假维度技巧
            // 并等待这个 ACK。立即发送它，这样剩余字节或数据包
            // 顺序都不会卡住握手。
            clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.ChangeDimensionAck);
            PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, loadingScreenId);
            clientPlayer.setDimensionChangeInfo(null);
            clientPlayer.sendAttribute("minecraft:health"); // Java 客户端重生时总会重置生命值，而基岩客户端切换维度时会保留生命值
            wrapper.user().get(PlayerArmorHudTracker.class).forceSync();
            clientPlayer.sendEffects(); // Java 客户端重生时总会重置状态效果。重新发送它们

            final PacketWrapper initializeBorder = PacketWrapper.create(ClientboundPackets26_1.INITIALIZE_BORDER, wrapper.user());
            initializeBorder.write(Types.DOUBLE, 0D); // 中心 x
            initializeBorder.write(Types.DOUBLE, 0D); // 中心 z
            initializeBorder.write(Types.DOUBLE, 0D); // 旧大小
            initializeBorder.write(Types.DOUBLE, 60_000_000D); // 新大小
            initializeBorder.write(Types.VAR_LONG, 0L); // 插值时间
            initializeBorder.write(Types.VAR_INT, 60_000_000); // 新的绝对最大大小
            initializeBorder.write(Types.VAR_INT, 0); // 警告距离（方块）
            initializeBorder.write(Types.VAR_INT, 0); // 警告时间
            initializeBorder.send(BedrockProtocol.class);

            PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer()); // Java 客户端重生时总会重置物品栏。重新发送它
            inventoryTracker.getInventoryContainer().sendSelectedHotbarSlotToClient(); // Java 客户端重生时总会重置选中的快捷栏槽位。重新发送它
            spectatorCamera.restorePresentationAfterClientReset();
        });
        protocol.registerClientbound(ClientboundBedrockPackets.LEVEL_CHUNK, null, wrapper -> {
            wrapper.cancel();
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final int chunkX = wrapper.read(BedrockTypes.VAR_INT); // 区块 x
            final int chunkZ = wrapper.read(BedrockTypes.VAR_INT); // 区块 z
            final Dimension dimension = Dimension.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // 维度
            if (dimension != chunkTracker.getDimension()) {
                return;
            }
            final int sectionCount = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 子区块数量
            final boolean chunkV2168 = BedrockWire.applies(BedrockDeviation.LEVEL_CHUNK_PACKET_V2168);
            if (!chunkV2168 && sectionCount < -2) { // 基岩客户端会忽略这个数据包
                return;
            }

            final int startY = chunkTracker.getMinY() >> 4;
            final int endY = chunkTracker.getMaxY() >> 4;
            int requestSectionCount = 0;
            if (chunkV2168) {
                // 2168 去掉了 -1/-2 哨兵值，改成显式的“请求子区块 + 上限”
                if (wrapper.read(Types.BOOLEAN)) {
                    requestSectionCount = wrapper.read(BedrockTypes.VAR_INT) + 1; // 上限
                }
            } else if (sectionCount == -2) {
                requestSectionCount = wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE) + 1; // 数量
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
                            sections[i].mergeWith(chunkTracker.handleBlockPalette(BedrockTypes.CHUNK_SECTION.read(dataBuf))); // 区块段
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
                                BedrockDataPalette biomePalette = BedrockTypes.RUNTIME_DATA_PALETTE.read(dataBuf); // 生物群系调色板
                                if (biomePalette == null) {
                                    if (i == 0) {
                                        throw new RuntimeException("First biome palette can not point to previous biome palette");
                                    }
                                    biomePalette = ((BedrockDataPalette) sections[i - 1].palette(PaletteType.BIOMES)).clone();
                                }
                                sections[i].addPalette(PaletteType.BIOMES, biomePalette);
                            }
                        }

                        dataBuf.skipBytes(1); // 边界方块
                        while (dataBuf.isReadable()) {
                            final Tag tag = BedrockTypes.NETWORK_TAG.read(dataBuf); // 方块实体标签
                            if (tag instanceof CompoundTag) { // 忽略非 compound 标签
                                blockEntities.add(new BedrockBlockEntity((CompoundTag) tag));
                            }
                        }
                    } catch (IndexOutOfBoundsException ignored) {
                        // 基岩客户端会在读到的位置停止，并加载所有已成功读取的内容
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

            final boolean cachingEnabled = wrapper.read(Types.BOOLEAN); // 是否启用缓存
            if (chunkV2168) {
                // 2168 起 blob 列表始终带计数，缓存开关只决定数据要不要和缓存拼接
                final Long[] blobs = wrapper.read(BedrockTypes.LONG_ARRAY); // blob ID 列表
                final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // 数据
                if (!cachingEnabled) {
                    readChunkTail(wrapper);
                    dataConsumer.accept(data);
                    return;
                }
                final int expectedLength = sectionCount + 1;
                if (blobs.length != expectedLength) { // 基岩客户端会把随机的内存内容写入请求，极可能导致崩溃
                    throw new IllegalStateException("Invalid blob count: " + blobs.length + " (expected " + expectedLength + ")");
                }
                wrapper.user().get(BlobCache.class).getBlob(blobs).thenAccept(blob -> {
                    final byte[] combinedData = new byte[data.length + blob.length];
                    System.arraycopy(blob, 0, combinedData, 0, blob.length);
                    System.arraycopy(data, 0, combinedData, blob.length, data.length);
                    dataConsumer.accept(combinedData);
                });
            } else if (cachingEnabled) {
                final Long[] blobs = wrapper.read(BedrockTypes.LONG_ARRAY); // blob ID 列表
                final int expectedLength = sectionCount < 0 ? 1 : sectionCount + 1;
                if (blobs.length != expectedLength) { // 基岩客户端会把随机的内存内容写入请求，极可能导致崩溃
                    throw new IllegalStateException("Invalid blob count: " + blobs.length + " (expected " + expectedLength + ")");
                }
                final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // 数据
                wrapper.user().get(BlobCache.class).getBlob(blobs).thenAccept(blob -> {
                    final byte[] combinedData = new byte[data.length + blob.length];
                    System.arraycopy(blob, 0, combinedData, 0, blob.length);
                    System.arraycopy(data, 0, combinedData, blob.length, data.length);
                    dataConsumer.accept(combinedData);
                });
            } else {
                dataConsumer.accept(wrapper.read(BedrockTypes.BYTE_ARRAY)); // 数据
            }
            readChunkTail(wrapper);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SUB_CHUNK, null, wrapper -> {
            wrapper.cancel();
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);

            final boolean cachingEnabled = wrapper.read(Types.BOOLEAN); // 是否启用缓存
            final boolean subChunkV2168 = BedrockWire.applies(BedrockDeviation.SUB_CHUNK_PACKET_V2168);
            final boolean segmentedHeightMap = BedrockWire.applies(BedrockDeviation.SUB_CHUNK_HEIGHTMAP_SEGMENTS);
            final Dimension dimension = Dimension.getByValue(wrapper.read(BedrockTypes.VAR_INT)); // 维度
            if (dimension != chunkTracker.getDimension()) {
                return;
            }
            final BlockPosition center;
            final long count;
            if (subChunkV2168) {
                // 2168 起基准原点是三个小端 int32，计数是无符号 varint
                center = new BlockPosition(wrapper.read(BedrockTypes.INT_LE), wrapper.read(BedrockTypes.INT_LE), wrapper.read(BedrockTypes.INT_LE)); // 中心坐标
                count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT); // 数量
            } else {
                center = wrapper.read(BedrockTypes.SIGNED_BLOCK_POSITION); // 中心坐标（有符号）
                count = wrapper.read(BedrockTypes.UNSIGNED_INT_LE); // 数量
            }

            for (long i = 0; i < count; i++) {
                final BlockPosition offset = wrapper.read(BedrockTypes.SUB_CHUNK_OFFSET); // 偏移
                final SubChunkPacket_SubChunkRequestResult result = SubChunkPacket_SubChunkRequestResult.getByValue(wrapper.read(Types.BYTE), SubChunkPacket_SubChunkRequestResult.Undefined); // 结果
                final boolean hasData = subChunkV2168
                        ? wrapper.read(Types.BOOLEAN)
                        : result != SubChunkPacket_SubChunkRequestResult.SuccessAllAir || !cachingEnabled;
                final byte[] data = hasData ? wrapper.read(BedrockTypes.BYTE_ARRAY) : new byte[0]; // 数据
                final SubChunkPacket_HeightMapDataType heightmapResult = SubChunkPacket_HeightMapDataType.getByValue(wrapper.read(Types.BYTE), SubChunkPacket_HeightMapDataType.NoData); // 高度图结果
                final boolean hasHeightmap = subChunkV2168 ? wrapper.read(Types.BOOLEAN) : heightmapResult == SubChunkPacket_HeightMapDataType.HasData;
                if (hasHeightmap) {
                    readHeightMap(wrapper, segmentedHeightMap); // 高度图数据
                }
                final SubChunkPacket_HeightMapDataType renderHeightmapResult = SubChunkPacket_HeightMapDataType.getByValue(wrapper.read(Types.BYTE), SubChunkPacket_HeightMapDataType.NoData); // 渲染高度图结果
                final boolean hasRenderHeightmap = subChunkV2168 ? wrapper.read(Types.BOOLEAN) : renderHeightmapResult == SubChunkPacket_HeightMapDataType.HasData;
                if (hasRenderHeightmap) {
                    readHeightMap(wrapper, segmentedHeightMap); // 渲染高度图数据
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
                                section = BedrockTypes.CHUNK_SECTION.read(dataBuf); // 区块段
                                while (dataBuf.isReadable()) {
                                    final Tag tag = BedrockTypes.NETWORK_TAG.read(dataBuf); // 方块实体标签
                                    if (tag instanceof CompoundTag) { // 忽略非 compound 标签
                                        blockEntities.add(new BedrockBlockEntity((CompoundTag) tag));
                                    }
                                }
                            } catch (IndexOutOfBoundsException ignored) {
                                // 基岩客户端会在读到的位置停止，并加载所有已成功读取的内容
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

                final boolean hasBlobId = subChunkV2168 ? wrapper.read(Types.BOOLEAN) : cachingEnabled;
                if (hasBlobId) {
                    final long hash = wrapper.read(BedrockTypes.LONG_LE); // blob 标识
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
                            // 全空气响应和显式失败响应都不依赖可选的缓存副作用。
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
                map(BedrockTypes.BLOCK_POSITION, Types.BLOCK_POSITION1_14); // 坐标
                handler(updateBlockHandler(false));
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_BLOCK_SYNCED, ClientboundPackets26_1.BLOCK_UPDATE, new PacketHandlers() {
            @Override
            protected void register() {
                map(BedrockTypes.BLOCK_POSITION, Types.BLOCK_POSITION1_14); // 坐标
                handler(updateBlockHandler(true));
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_SUB_CHUNK_BLOCKS, null, wrapper -> {
            wrapper.cancel(); // 需要多个数据包，因为偏移可能跨过区块边界
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            wrapper.read(BedrockTypes.BLOCK_POSITION); // 坐标 | 基岩客户端似乎未使用
            final BlockChangeEntry[][] blockUpdatesArray = new BlockChangeEntry[2][];
            blockUpdatesArray[0] = wrapper.read(BedrockTypes.BLOCK_CHANGE_ENTRY_ARRAY); // 标准方块
            blockUpdatesArray[1] = wrapper.read(BedrockTypes.BLOCK_CHANGE_ENTRY_ARRAY); // 额外方块

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

            // 在本批次的所有改动都应用到跟踪器之后，重新计算依赖邻居的修正（楼梯形状、连接、门/床的两半），以便跨方块查询能看到整个批次的最终状态。
            final BlockNeighborView view = new TrackerNeighborView(chunkTracker);
            final NeighborAwareBlockRewriter neighborRewriter = BedrockProtocol.MAPPINGS.getNeighborRewriter();
            final Map<BlockPosition, Integer> finalBlockStates = new LinkedHashMap<>();
            for (Map.Entry<BlockPosition, Integer> changed : remappedBlockStates.entrySet()) {
                finalBlockStates.putAll(neighborRewriter.resolveUpdate(view, changed.getKey(), changed.getValue()));
            }

            for (Map.Entry<BlockPosition, Integer> entry : finalBlockStates.entrySet()) {
                final BlockPosition position = entry.getKey();
                chunkTracker.onJavaBlockChange(position, entry.getValue());
                final BlockPosition chunkPosition = new BlockPosition(position.x() >> 4, position.y() >> 4, position.z() >> 4);
                final BlockPosition relative = new BlockPosition(position.x() & 0xF, position.y() & 0xF, position.z() & 0xF);
                blockChanges.computeIfAbsent(chunkPosition, k -> new ArrayList<>()).add(new BlockChangeRecord1_16_2(relative.x(), relative.y(), relative.z(), entry.getValue()));
            }

            for (Map.Entry<BlockPosition, List<BlockChangeRecord>> entry : blockChanges.entrySet()) {
                final BlockPosition chunkPosition = entry.getKey();
                final List<BlockChangeRecord> changes = entry.getValue();
                final long chunkKey = packJavaSectionPosition(chunkPosition.x(), chunkPosition.y(), chunkPosition.z());

                final PacketWrapper multiBlockChange = wrapper.create(ClientboundPackets26_1.SECTION_BLOCKS_UPDATE);
                multiBlockChange.write(Types.LONG, chunkKey); // 区块坐标
                multiBlockChange.write(Types.VAR_LONG_BLOCK_CHANGE_ARRAY, changes.toArray(new BlockChangeRecord[0])); // 方块改动记录
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
                map(BedrockTypes.BLOCK_POSITION, Types.BLOCK_POSITION1_14); // 坐标
                handler(wrapper -> {
                    final Tag tag = wrapper.read(BedrockTypes.NETWORK_TAG); // 方块实体标签
                    if (!(tag instanceof CompoundTag)) {
                        wrapper.cancel();
                        return;
                    }

                    final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
                    final BedrockBlockEntity bedrockBlockEntity = new BedrockBlockEntity(wrapper.get(Types.BLOCK_POSITION1_14, 0), (CompoundTag) tag);
                    chunkTracker.addBlockEntity(bedrockBlockEntity);

                    // 物品展示框会被转换为 Java 实体而非方块实体。更新展示框显示出的物品/朝向。
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
                        wrapper.write(Types.VAR_INT, javaBlockEntity.typeId()); // 类型
                        wrapper.write(Types.COMPOUND_TAG, javaBlockEntity.tag()); // 方块实体标签
                    } else {
                        wrapper.cancel();
                    }
                });
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.NETWORK_CHUNK_PUBLISHER_UPDATE, ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS, wrapper -> {
            final BlockPosition position = wrapper.read(BedrockTypes.SIGNED_BLOCK_POSITION); // 中心坐标（有符号）
            final int radius = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT) >> 4; // 半径
            final int publisherChunkX = position.x() >> 4;
            final int publisherChunkZ = position.z() >> 4;
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final int previousRadius = chunkTracker.radius();
            // 当 START_GAME 生成点为 Lobby [13,13] 时，MOT 的 orderChunks() 可能发布 (0,0)
            // 或一个过期的最后排序区块。Java 1.21.11 会卸载 SET_CHUNK_CACHE_CENTER
            // ± 半径之外的所有内容，因此要把玩家所在区块保留在窗口内，
            // 且半径不得小于 viewDistance。
            // 参考：MOT Player.orderChunks；ChunkTracker.resolveJavaCacheCenter。
            final boolean centerChanged = chunkTracker.applyPublisher(publisherChunkX, publisherChunkZ, radius);
            wrapper.write(Types.VAR_INT, chunkTracker.radius()); // 半径

            final int count = wrapper.read(BedrockTypes.INT_LE); // 服务端已构建区块数量
            for (int i = 0; i < count; i++) {
                wrapper.read(BedrockTypes.VAR_INT); // 区块 x
                wrapper.read(BedrockTypes.VAR_INT); // 区块 z
            }

            if (centerChanged) {
                final PacketWrapper updateViewPosition = wrapper.create(ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER);
                updateViewPosition.write(Types.VAR_INT, chunkTracker.centerX()); // 区块 x
                updateViewPosition.write(Types.VAR_INT, chunkTracker.centerZ()); // 区块 z
                updateViewPosition.send(BedrockProtocol.class);
            }
            if (previousRadius == chunkTracker.radius()) {
                wrapper.cancel();
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CHUNK_RADIUS_UPDATED, ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS, new PacketHandlers() {
            @Override
            public void register() {
                map(BedrockTypes.VAR_INT, Types.VAR_INT); // 半径
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
            final long bedrockTime = wrapper.read(BedrockTypes.VAR_INT); // 时间
            wrapper.write(Types.LONG, wrapper.user().get(GameSessionStorage.class).getLevelTime()); // 游戏时间
            wrapper.write(Types.VAR_INT, 1); // 时钟更新计数
            wrapper.write(Types.VAR_INT, 0); // 注册表 ID（主世界时钟）
            wrapper.write(Types.VAR_LONG, bedrockTime >= 0L ? bedrockTime % 24000L : 24000L + (bedrockTime % 24000L)); // 总刻数
            wrapper.write(Types.FLOAT, 0F); // 部分刻
            wrapper.write(Types.FLOAT, wrapper.user().get(GameRulesStorage.class).getGameRule("doDayLightCycle") ? 1F : 0F); // 速率
        });

        protocol.registerServerbound(ServerboundPackets26_1.SIGN_UPDATE, ServerboundBedrockPackets.BLOCK_ENTITY_DATA, wrapper -> {
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final BlockStateRewriter blockStateRewriter = wrapper.user().get(BlockStateRewriter.class);
            final BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // 坐标
            final boolean front = wrapper.read(Types.BOOLEAN); // 正面
            final List<String> lines = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                lines.add(wrapper.read(Types.STRING)); // 线
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

            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) { // 去掉末尾空行
                lines.remove(lines.size() - 1);
            }
            final String text = lines.stream().reduce((a, b) -> a + '\n' + b).orElse("");
            signTag.getCompoundTag(front ? "FrontText" : "BackText").putString("Text", text);

            wrapper.write(BedrockTypes.BLOCK_POSITION, position); // 坐标
            wrapper.write(BedrockTypes.NETWORK_TAG, signTag.copy()); // 方块实体标签
        });
    }

    /**
     * 读 LEVEL_CHUNK 在区块数据之后的尾部。2225 起追加一个“仅生物群系更新”标志，
     * 无存在性 bool、无长度字段；本仓库不用该语义，读完即丢。
     */
    static void readChunkTail(final PacketWrapper wrapper) {
        if (BedrockWire.applies(BedrockDeviation.LEVEL_CHUNK_CLIENT_BIOME_UPDATE)) {
            wrapper.read(Types.BOOLEAN);
        }
    }

    /**
     * 读子区块高度图。2192 起改成分段：{@code HEIGHT_MAP_LENGTH / 16} 段，
     * 每段前置无符号 varint 段长（恒为 16）；此前是一整块定长数据。
     * 高度图内容在本仓库不用，读完即丢。
     */
    static void readHeightMap(final PacketWrapper wrapper, final boolean segmented) {
        if (!segmented) {
            wrapper.read(new ByteArrayType(HEIGHT_MAP_LENGTH));
            return;
        }
        for (int offset = 0; offset < HEIGHT_MAP_LENGTH; offset += HEIGHT_MAP_SEGMENT_LENGTH) {
            final long length = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
            wrapper.read(new ByteArrayType(Math.max(0, (int) length)));
        }
    }

    static boolean shouldRetryFailedSubChunk(final SubChunkPacket_SubChunkRequestResult result) {
        // 显式的坐标/维度失败是稳定的；只有模糊的会话失败才会进行有限次重试。
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
