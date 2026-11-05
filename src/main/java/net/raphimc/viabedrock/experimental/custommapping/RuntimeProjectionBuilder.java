/* 根据校验后的快照配置与具体的 START_GAME 运行时 ID 构建 RuntimeProjection。 */
package net.raphimc.viabedrock.experimental.custommapping;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.util.Key;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.util.BlockStateHasher;
import net.raphimc.viabedrock.api.util.CombinationUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.model.BlockProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class RuntimeProjectionBuilder {
    private final SnapshotProfile profile;
    private final RuntimeProjectionCache.Key cacheKey;
    private final RuntimeProjection cachedProjection;
    private final Map<Integer, RuntimeProjection.ProjectedBlockState> cachedBlockStatesByRuntimeId;
    private final List<RuntimeProjection.ProjectedBlockState> blockStates = new ArrayList<>();
    private final int blockStateSourceBase;
    private final int blockEntitySourceBase;

    public RuntimeProjectionBuilder(final SnapshotProfile profile, final RuntimeProjectionCache.Key cacheKey, final int blockStateSourceBase, final int blockEntitySourceBase) {
        this.profile = profile;
        this.cacheKey = cacheKey;
        this.blockStateSourceBase = blockStateSourceBase;
        this.blockEntitySourceBase = blockEntitySourceBase;
        this.cachedProjection = cacheKey != null ? RuntimeProjectionCache.getInstance().get(cacheKey) : null;
        if (this.cachedProjection != null) {
            this.cachedBlockStatesByRuntimeId = this.cachedProjection.blockStates().stream()
                    .collect(Collectors.toMap(RuntimeProjection.ProjectedBlockState::runtimeId, state -> state));
        } else {
            this.cachedBlockStatesByRuntimeId = Map.of();
        }
    }

    public int projectRuntime(final int runtimeId, final BlockState bedrockBlockState) {
        if (this.cachedProjection != null) {
            final RuntimeProjection.ProjectedBlockState mapping = this.cachedBlockStatesByRuntimeId.get(runtimeId);
            return mapping != null ? mapping.sourceJavaRawId() : -1;
        }
        final SnapshotProfile.BlockStateMapping mapping = this.profile.blockState(bedrockBlockState);
        if (mapping == null) {
            return -1;
        }

        final int sourceJavaRawId = this.blockStateSourceBase + this.blockStates.size();
        this.blockStates.add(new RuntimeProjection.ProjectedBlockState(
                runtimeId,
                bedrockBlockState.namespacedIdentifier(),
                sourceJavaRawId,
                mapping.targetJavaRawId(),
                mapping.fallbackSourceJavaRawId(),
                mapping.emit(),
                mapping.filter(),
                mapping.secondsToDestroy(),
                mapping.rule()));
        return sourceJavaRawId;
    }

    public RuntimeProjection build() {
        if (this.cachedProjection != null) {
            return this.cachedProjection;
        }
        final List<SnapshotProfile.BlockEntityTypeMapping> blockEntityTypes = new ArrayList<>(this.profile.blockEntityTypes().size());
        int nextBlockEntitySourceId = this.blockEntitySourceBase;
        for (SnapshotProfile.BlockEntityTypeMapping type : this.profile.blockEntityTypes()) {
            blockEntityTypes.add(new SnapshotProfile.BlockEntityTypeMapping(type.bedrockIdentifier(), type.javaIdentifier(), nextBlockEntitySourceId++, type.targetJavaRawId(), type.rule()));
        }
        final RuntimeProjection projection = new RuntimeProjection(this.blockStates, blockEntityTypes, this.profile.items());
        if (this.cacheKey != null) {
            RuntimeProjectionCache.getInstance().put(this.cacheKey, projection);
        }
        return projection;
    }

    public static List<BedrockBlockState> buildCustomRuntimeStates(final BlockProperties[] blockProperties, final Set<String> vanillaBedrockIdentifiers) {
        final List<BedrockBlockState> customBlockStates = new ArrayList<>();
        final Map<String, CompoundTag> effectiveBlockProperties = collectEffectiveCustomBlockProperties(blockProperties, vanillaBedrockIdentifiers);

        for (Map.Entry<String, CompoundTag> blockProperty : effectiveBlockProperties.entrySet()) {
            if (!(blockProperty.getValue().get("vanilla_block_data") instanceof CompoundTag)) {
                continue; // 基岩客户端会忽略没有此标签的方块
            }
            if (!(blockProperty.getValue().get("menu_category") instanceof CompoundTag)) {
                throw new IllegalStateException("Missing menu_category tag for " + blockProperty.getKey());
            }

            final Map<String, Set<Tag>> propertiesMap = collectRuntimeProperties(blockProperty.getValue(), blockProperty.getKey());
            final List<CompoundTag> combinations = CombinationUtil.generateCombinations(propertiesMap).stream()
                    .map(stringTagMap -> {
                        final CompoundTag combination = new CompoundTag();
                        for (Map.Entry<String, Tag> entry : stringTagMap.entrySet()) {
                            combination.put(entry.getKey(), entry.getValue().copy());
                        }
                        return combination;
                    }).collect(Collectors.toList());
            if (combinations.isEmpty()) {
                combinations.add(new CompoundTag());
            }

            for (CompoundTag combination : combinations) {
                final CompoundTag blockStateTag = new CompoundTag();
                blockStateTag.putString("name", blockProperty.getKey());
                blockStateTag.put("states", combination);
                blockStateTag.putInt("network_id", BlockStateHasher.hash(blockStateTag));
                customBlockStates.add(BedrockBlockState.fromNbt(blockStateTag));
            }
        }
        return customBlockStates;
    }

    public static Map<String, CompoundTag> collectEffectiveCustomBlockProperties(final BlockProperties[] blockProperties, final Set<String> vanillaBedrockIdentifiers) {
        final Map<String, CompoundTag> effectiveBlockProperties = new LinkedHashMap<>();
        for (BlockProperties blockProperty : blockProperties) {
            final String identifier = blockProperty.name().toLowerCase(Locale.ROOT);
            if (vanillaBedrockIdentifiers.contains(identifier)) {
                continue; // 基岩客户端不允许覆写原版方块状态
            }
            effectiveBlockProperties.putIfAbsent(identifier, blockProperty.properties());
        }
        return effectiveBlockProperties;
    }

    public static String describeBlockProperties(final CompoundTag blockProperties) {
        final StringBuilder builder = new StringBuilder();
        builder.append("hasVanillaBlockData=").append(blockProperties.get("vanilla_block_data") instanceof CompoundTag);
        builder.append(", hasMenuCategory=").append(blockProperties.get("menu_category") instanceof CompoundTag);
        if (blockProperties.get("vanilla_block_data") instanceof CompoundTag vanillaBlockData) {
            builder.append(", vanillaBlockData=").append(vanillaBlockData);
        }
        if (blockProperties.get("menu_category") instanceof CompoundTag menuCategory) {
            builder.append(", menuCategory=").append(menuCategory);
        }
        builder.append(", properties=").append(describeProperties(blockProperties));
        builder.append(", traits=").append(describeTraits(blockProperties));
        return builder.toString();
    }

    private static String describeProperties(final CompoundTag blockProperties) {
        final ListTag<CompoundTag> properties = blockProperties.getListTag("properties", CompoundTag.class);
        if (properties == null) return "[]";
        final List<String> descriptions = new ArrayList<>();
        for (CompoundTag property : properties) {
            final String name = property.get("name") instanceof StringTag nameTag ? nameTag.getValue() : "<missing>";
            descriptions.add(name + "=" + property.get("enum"));
        }
        return descriptions.toString();
    }

    private static String describeTraits(final CompoundTag blockProperties) {
        final ListTag<CompoundTag> traits = blockProperties.getListTag("traits", CompoundTag.class);
        if (traits == null) return "[]";
        final List<String> descriptions = new ArrayList<>();
        for (CompoundTag trait : traits) {
            final String name = trait.get("name") instanceof StringTag nameTag ? Key.namespaced(nameTag.getValue()) : "<missing>";
            descriptions.add(name + "{enabled_states=" + trait.get("enabled_states") + "}");
        }
        return descriptions.toString();
    }

    private static Map<String, Set<Tag>> collectRuntimeProperties(final CompoundTag blockProperties, final String identifier) {
        final Map<String, Set<Tag>> propertiesMap = new LinkedHashMap<>();
        final ListTag<CompoundTag> properties = blockProperties.getListTag("properties", CompoundTag.class);
        if (properties != null) {
            for (CompoundTag property : properties) {
                if (property.get("name") instanceof StringTag nameTag) {
                    final String name = nameTag.getValue();
                    if (property.get("enum") instanceof ListTag<?> enumTag) {
                        final Set<Tag> values = new LinkedHashSet<>();
                        for (Tag tag : enumTag) {
                            values.add(tag);
                        }
                        propertiesMap.put(name, values);
                    }
                }
            }
        }

        final ListTag<CompoundTag> traits = blockProperties.getListTag("traits", CompoundTag.class);
        if (traits != null) {
            for (CompoundTag trait : traits) {
                if (trait.get("name") instanceof StringTag nameTag) {
                    final String name = Key.namespaced(nameTag.getValue());
                    final Map<String, Map<String, Set<String>>> traitStateProperties = BedrockProtocol.MAPPINGS.getBedrockBlockTraits().get(name);
                    if (traitStateProperties == null) {
                        throw new RuntimeException("Missing block trait state properties for " + name);
                    }

                    if (trait.get("enabled_states") instanceof CompoundTag enabledStatesTag) {
                        for (Map.Entry<String, Tag> tag : enabledStatesTag) {
                            final String key = Key.namespaced(tag.getKey());
                            final boolean enabled = tag.getValue() instanceof com.viaversion.nbt.tag.ByteTag byteTag && byteTag.asByte() != 0;
                            if (!enabled) continue;
                            if (!traitStateProperties.containsKey(key)) {
                                throw new RuntimeException("Missing block trait state properties for trait " + name + " and enabled state " + key + " on " + identifier);
                            }
                            for (Map.Entry<String, Set<String>> propertiesEntry : traitStateProperties.get(key).entrySet()) {
                                final Set<Tag> values = new LinkedHashSet<>();
                                for (String value : propertiesEntry.getValue()) {
                                    values.add(new StringTag(value));
                                }
                                propertiesMap.put(propertiesEntry.getKey(), values);
                            }
                        }
                    } else {
                        throw new RuntimeException("Missing enabled_states tag for trait " + name + " on " + identifier);
                    }
                }
            }
        }
        return propertiesMap;
    }
}
