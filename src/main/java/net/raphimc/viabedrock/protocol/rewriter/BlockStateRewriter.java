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
package net.raphimc.viabedrock.protocol.rewriter;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.viaversion.nbt.tag.*;
import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.libs.fastutil.ints.*;
import com.viaversion.viaversion.libs.fastutil.objects.Object2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.objects.Object2ObjectOpenHashMap;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.blockstate.BlockStateSanitizer;
import net.raphimc.viabedrock.api.chunk.blockstate.BlockStateUpgrader;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.util.HashedPaletteComparator;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.Netease860BlockRuntimeOverlay;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.custommapping.RuntimeProjection;
import net.raphimc.viabedrock.experimental.custommapping.RuntimeProjectionBuilder;
import net.raphimc.viabedrock.experimental.block.CustomBlockDisplayTracker;
import net.raphimc.viabedrock.protocol.model.BlockProperties;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class BlockStateRewriter implements StorableObject {

    private final Int2IntMap blockStateIdMappings = new Int2IntOpenHashMap(); // 基岩 → Java
    private final Int2IntMap legacyBlockStateIdMappings = new Int2IntOpenHashMap(); // 基岩 → 基岩
    private final BiMap<BlockState, Integer> blockStateMappings = HashBiMap.create(); // 基岩 → 基岩
    private final Object2ObjectMap<String, IntSortedSet> validBlockStates = new Object2ObjectOpenHashMap<>(); // 基岩 → 基岩
    private final Int2ObjectMap<String> blockStateTags = new Int2ObjectOpenHashMap<>(); // 基岩
    private final BlockStateUpgrader blockStateUpgrader;
    private final BlockStateSanitizer blockStateSanitizer;

    public BlockStateRewriter(final UserConnection user, final BlockProperties[] blockProperties, final boolean hashedRuntimeBlockIds) {
        this.blockStateIdMappings.defaultReturnValue(-1);
        this.legacyBlockStateIdMappings.defaultReturnValue(-1);
        this.blockStateUpgrader = BedrockProtocol.MAPPINGS.getBedrockBlockStateUpgrader();

        final List<BedrockBlockState> bedrockBlockStates = new ArrayList<>(BedrockProtocol.MAPPINGS.getBedrockBlockStates());
        final Map<BlockState, Integer> javaBlockStates = BedrockProtocol.MAPPINGS.getJavaBlockStates();
        final Map<BlockState, BlockState> bedrockToJavaBlockStates = BedrockProtocol.MAPPINGS.getBedrockToJavaBlockStates();
        final Map<String, String> blockTags = BedrockProtocol.MAPPINGS.getBedrockCustomBlockTags();
        final Set<String> bedrockBlockIdentifiers = bedrockBlockStates.stream().map(BedrockBlockState::namespacedIdentifier).collect(Collectors.toSet());
        final CustomMappingSyncStorage customMappingSync = user.get(CustomMappingSyncStorage.class);
        RuntimeProjectionBuilder runtimeProjectionBuilder = customMappingSync != null ? customMappingSync.runtimeProjectionBuilder(blockProperties, hashedRuntimeBlockIds) : null;
        Set<BedrockBlockState> customRuntimeStates = Set.of();
        Map<String, CompoundTag> customRuntimeBlockProperties = Map.of();

        // 始终根据 START_GAME 的 blockProperties 重建服务端定义的自定义方块运行时状态，
        // 不依赖 BedrockLoader。基岩服务器会在包含这些自定义方块的完整调色板上
        // 分配运行时 ID（顺序或哈希），因此必须把它们纳入以保持所有方块 ID 对齐。
        // 否则，使用顺序运行时 ID 的服务器会在第一个自定义方块之后让每个方块状态错位。
        // 可选的 BedrockLoader 投影只会在这一重建结果之上叠加真实的 Java 映射。
        try {
            customRuntimeBlockProperties = RuntimeProjectionBuilder.collectEffectiveCustomBlockProperties(blockProperties, bedrockBlockIdentifiers);
            final List<BedrockBlockState> orderedCustomRuntimeStates = RuntimeProjectionBuilder.buildCustomRuntimeStates(blockProperties, bedrockBlockIdentifiers);
            customRuntimeStates = new HashSet<>(orderedCustomRuntimeStates);
            bedrockBlockStates.addAll(orderedCustomRuntimeStates);
        } catch (Throwable e) {
            if (runtimeProjectionBuilder != null) {
                customMappingSync.failProjection("Failed to build custom block runtime projection states", e);
            } else {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to build custom block runtime states from START_GAME block properties", e);
            }
            runtimeProjectionBuilder = null;
            customRuntimeStates = Set.of();
            customRuntimeBlockProperties = Map.of();
        }

        bedrockBlockStates.sort((a, b) -> HashedPaletteComparator.INSTANCE.compare(a.namespacedIdentifier(), b.namespacedIdentifier()));
        final Set<String> reportedMissingCustomRuntimeProperties = new HashSet<>();
        final Map<String, Integer> missingCustomRuntimeCounts = new HashMap<>();
        final Map<String, String> missingCustomRuntimeExamples = new HashMap<>();
        final Map<String, Integer> missingVanillaRuntimeCounts = new HashMap<>();
        final Map<String, String> missingVanillaRuntimeExamples = new HashMap<>();
        final boolean emulateNetEase = ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient();
        final int netEaseProtocol = ViaBedrock.getConfig() != null ? ViaBedrock.getConfig().getNetEaseProtocolVersion() : 0;
        final Netease860BlockRuntimeOverlay netease860Overlay = Netease860BlockRuntimeOverlay.applies(
                emulateNetEase, netEaseProtocol, hashedRuntimeBlockIds)
                ? BedrockProtocol.MAPPINGS.getNetease860BlockRuntimeOverlay()
                : Netease860BlockRuntimeOverlay.empty();
        if (emulateNetEase && netEaseProtocol >= Netease860BlockRuntimeOverlay.SEQUENTIAL_OVERLAY_END_PROTOCOL && !hashedRuntimeBlockIds) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                    "NetEase " + netEaseProtocol + " sent sequential block network ids; "
                            + "MOT anchors that palette on V1_21_111, so block ids fall back to the vanilla order");
        }
        int nextCustomSequentialId = netease860Overlay.nextCustomSequentialId();

        for (int i = 0; i < bedrockBlockStates.size(); i++) {
            final BedrockBlockState bedrockBlockState = bedrockBlockStates.get(i);
            int bedrockId;
            if (hashedRuntimeBlockIds) {
                final var networkId = bedrockBlockState.blockStateTag().getIntTag("network_id");
                if (networkId == null) {
                    throw new IllegalStateException("Missing network_id for hashed block state " + bedrockBlockState.toBlockStateString());
                }
                bedrockId = networkId.asInt();
            } else if (!netease860Overlay.isEmpty()) {
                final var networkId = bedrockBlockState.blockStateTag().getIntTag("network_id");
                if (networkId != null) {
                    final int sequentialId = netease860Overlay.sequentialRuntimeId(networkId.asInt());
                    bedrockId = sequentialId >= 0 ? sequentialId : nextCustomSequentialId++;
                } else {
                    bedrockId = nextCustomSequentialId++;
                }
            } else {
                bedrockId = i;
            }
            if (hashedRuntimeBlockIds && this.blockStateMappings.containsValue(bedrockId)) {
                bedrockId++; // 基岩客户端在发生冲突时会递增哈希值
            } else if (!hashedRuntimeBlockIds && !netease860Overlay.isEmpty() && this.blockStateMappings.containsValue(bedrockId)) {
                while (this.blockStateMappings.containsValue(nextCustomSequentialId)) {
                    nextCustomSequentialId++;
                }
                bedrockId = nextCustomSequentialId++;
            }

            this.blockStateMappings.put(bedrockBlockState, bedrockId);
            this.validBlockStates.computeIfAbsent(bedrockBlockState.namespacedIdentifier(), k -> new IntLinkedOpenHashSet()).add(bedrockId);

            if (blockTags.containsKey(bedrockBlockState.namespacedIdentifier())) {
                this.blockStateTags.put(bedrockId, blockTags.get(bedrockBlockState.namespacedIdentifier()));
            }

            final boolean customRuntimeState = customRuntimeStates.contains(bedrockBlockState);
            boolean projectedCustomRuntimeState = false;
            if (runtimeProjectionBuilder != null && customRuntimeState) {
                try {
                    projectedCustomRuntimeState = runtimeProjectionBuilder.projectRuntime(bedrockId, bedrockBlockState) != -1;
                } catch (Throwable e) {
                    customMappingSync.failProjection("Failed to project custom block runtime state " + bedrockBlockState.toBlockStateString(), e);
                    runtimeProjectionBuilder = null;
                }
            }

            if (projectedCustomRuntimeState) {
                // 在所有运行时 ID 已知后安装；在源 ID 可用之前保持未映射。
            } else if (bedrockToJavaBlockStates.containsKey(bedrockBlockState)) {
                final int javaId = javaBlockStates.get(bedrockToJavaBlockStates.get(bedrockBlockState));
                this.blockStateIdMappings.put(bedrockId, javaId);
            } else {
                if (customRuntimeState) {
                    final String identifier = bedrockBlockState.namespacedIdentifier();
                    missingCustomRuntimeCounts.merge(identifier, 1, Integer::sum);
                    missingCustomRuntimeExamples.putIfAbsent(identifier, bedrockBlockState.toBlockStateString());
                    if (reportedMissingCustomRuntimeProperties.add(identifier)) {
                        final CompoundTag properties = customRuntimeBlockProperties.get(identifier);
                        final String diagnostics = properties != null ? RuntimeProjectionBuilder.describeBlockProperties(properties) : "<missing START_GAME block properties>";
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing custom mapping START_GAME block properties for " + identifier + ": " + diagnostics);
                    }
                    // 叠加层路径使用 glass，而不是 info_update。已存储的黑色立方体会经由
                    // UPDATE_BLOCK / 邻居重写 / ack 泄漏出来，即使区块发送时已换成 glass。
                    final Integer glassId = javaBlockStates.get(CustomBlockDisplayTracker.PLACEHOLDER_JAVA_BLOCK_STATE);
                    final int javaId = glassId != null ? glassId : javaBlockStates.get(bedrockToJavaBlockStates.get(BedrockBlockState.INFO_UPDATE));
                    this.blockStateIdMappings.put(bedrockId, javaId);
                } else {
                    final String identifier = bedrockBlockState.namespacedIdentifier();
                    missingVanillaRuntimeCounts.merge(identifier, 1, Integer::sum);
                    missingVanillaRuntimeExamples.putIfAbsent(identifier, bedrockBlockState.toBlockStateString());
                    final int javaId = javaBlockStates.get(bedrockToJavaBlockStates.get(BedrockBlockState.INFO_UPDATE));
                    this.blockStateIdMappings.put(bedrockId, javaId);
                }
            }
        }
        if (!missingCustomRuntimeCounts.isEmpty()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing custom mapping snapshot summary: " + formatMissingRuntimeSummary(missingCustomRuntimeCounts, missingCustomRuntimeExamples));
        }
        if (!missingVanillaRuntimeCounts.isEmpty()) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Missing bedrock -> java block state mapping summary: " + formatMissingRuntimeSummary(missingVanillaRuntimeCounts, missingVanillaRuntimeExamples));
        }
        if (runtimeProjectionBuilder != null) {
            try {
                final RuntimeProjection projection = runtimeProjectionBuilder.build();
                customMappingSync.installProjection(projection);
                for (RuntimeProjection.ProjectedBlockState state : projection.blockStates()) {
                    this.blockStateIdMappings.put(state.runtimeId(), customMappingSync.packetBlockStateId(state));
                    this.blockStateTags.put(state.runtimeId(), "mod_block:" + state.bedrockIdentifier());
                }
            } catch (Throwable e) {
                customMappingSync.failProjection("Failed to install custom block runtime projection", e);
            }
        }

        for (Int2ObjectMap.Entry<BedrockBlockState> entry : BedrockProtocol.MAPPINGS.getBedrockLegacyBlockStates().int2ObjectEntrySet()) {
            final int legacyId = entry.getIntKey() >> 6;
            final int legacyData = entry.getIntKey() & 63;
            if (legacyData > 15) continue; // Mojang 在 1.12 做的脏 hack。可以安全忽略，因为这些值无法用于区块数据包。

            this.legacyBlockStateIdMappings.put(legacyId << 4 | legacyData & 15, this.blockStateMappings.getOrDefault(entry.getValue(), -1).intValue());
        }

        this.blockStateSanitizer = new BlockStateSanitizer(bedrockBlockStates);
    }

    BlockStateRewriter(final BlockStateUpgrader blockStateUpgrader, final Map<BedrockBlockState, Integer> blockStateMappings) {
        this.blockStateIdMappings.defaultReturnValue(-1);
        this.legacyBlockStateIdMappings.defaultReturnValue(-1);
        this.blockStateUpgrader = Objects.requireNonNull(blockStateUpgrader, "blockStateUpgrader");
        this.blockStateMappings.putAll(blockStateMappings);
        this.blockStateSanitizer = new BlockStateSanitizer(List.copyOf(blockStateMappings.keySet()));
    }

    public int bedrockId(final CompoundTag bedrockBlockStateTag) {
        return this.bedrockId(bedrockBlockStateTag.copy(), bedrockBlockStateTag);
    }

    /** 解析某个独占拥有的方块状态标签，并在升级和清理它时可能就地修改它。 */
    public int bedrockIdOwned(final CompoundTag bedrockBlockStateTag) {
        return this.bedrockId(bedrockBlockStateTag, bedrockBlockStateTag);
    }

    private int bedrockId(final CompoundTag mutableBlockStateTag, final CompoundTag diagnosticBlockStateTag) {
        try {
            this.blockStateUpgrader.upgradeToLatest(mutableBlockStateTag);
            this.blockStateSanitizer.sanitize(mutableBlockStateTag);

            return this.bedrockId(BedrockBlockState.fromNbt(mutableBlockStateTag));
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Error while rewriting block state tag: " + diagnosticBlockStateTag, e);
            return this.bedrockId(BedrockBlockState.AIR);
        }
    }

    public int bedrockId(final BlockState bedrockBlockState) {
        return this.blockStateMappings.getOrDefault(bedrockBlockState, -1);
    }

    public BlockState blockState(final int bedrockBlockStateId) {
        return this.blockStateMappings.inverse().get(bedrockBlockStateId);
    }

    public int bedrockId(final int legacyBlockStateId) {
        return this.legacyBlockStateIdMappings.get(legacyBlockStateId);
    }

    public int javaId(final int bedrockBlockStateId) {
        return this.blockStateIdMappings.get(bedrockBlockStateId);
    }

    public int waterlog(final int javaBlockStateId) {
        if (BedrockProtocol.MAPPINGS.getJavaPreWaterloggedBlockStates().contains(javaBlockStateId)) {
            return javaBlockStateId;
        }

        final BlockState blockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        if (blockState == null) {
            return -1;
        }
        final BlockState waterlogged = blockState.withProperty("waterlogged", "true");
        return BedrockProtocol.MAPPINGS.getJavaBlockStates().getOrDefault(waterlogged, -1);
    }

    public IntSortedSet validBlockStates(final String bedrockBlockIdentifier) {
        return this.validBlockStates.get(bedrockBlockIdentifier);
    }

    public String tag(final int bedrockBlockStateId) {
        return this.blockStateTags.get(bedrockBlockStateId);
    }

    private static String formatMissingRuntimeSummary(final Map<String, Integer> counts, final Map<String, String> examples) {
        final int missingStateCount = counts.values().stream().mapToInt(Integer::intValue).sum();
        final String topMissing = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(20)
                .map(entry -> entry.getKey() + "=" + entry.getValue() + " example=" + examples.get(entry.getKey()))
                .collect(Collectors.joining("; "));
        return "identifiers=" + counts.size() + ", states=" + missingStateCount + ", top=" + topMissing;
    }

}
