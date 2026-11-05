/* 由自定义映射快照派生、经过校验并在本地降级的不可变配置。 */
package net.raphimc.viabedrock.experimental.custommapping;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.util.*;
import java.util.logging.Level;

public final class SnapshotProfile {

    private final Map<CustomMappingSnapshot.TypedBedrockState, BlockStateMapping> blockStatesByState;
    private final List<BlockEntityTypeMapping> blockEntityTypes;
    private final Map<String, ItemMapping> itemsByBedrockIdentifier;
    private final List<ItemMapping> items;
    private final List<String> diagnostics;
    private final long cacheKey;

    private SnapshotProfile(
            final Map<CustomMappingSnapshot.TypedBedrockState, BlockStateMapping> blockStatesByState,
            final List<BlockEntityTypeMapping> blockEntityTypes,
            final Map<String, ItemMapping> itemsByBedrockIdentifier,
            final List<ItemMapping> items,
            final List<String> diagnostics,
            final long cacheKey) {
        this.blockStatesByState = Map.copyOf(blockStatesByState);
        this.blockEntityTypes = List.copyOf(blockEntityTypes);
        this.itemsByBedrockIdentifier = Map.copyOf(itemsByBedrockIdentifier);
        this.items = List.copyOf(items);
        this.diagnostics = List.copyOf(diagnostics);
        this.cacheKey = cacheKey;
    }

    public static SnapshotProfile empty() {
        return new SnapshotProfile(Map.of(), List.of(), Map.of(), List.of(), List.of(), CustomMappingProfileCache.EMPTY_KEY);
    }

    public static SnapshotProfile fromSnapshot(final CustomMappingSnapshot snapshot) {
        return fromSnapshot(snapshot, CustomMappingProfileCache.UNKNOWN_KEY);
    }

    public static SnapshotProfile fromSnapshot(final CustomMappingSnapshot snapshot, final long cacheKey) {
        return fromSnapshot(snapshot, cacheKey, BedrockProtocol.MAPPINGS.getJavaItems().size());
    }

    static SnapshotProfile fromSnapshot(final CustomMappingSnapshot snapshot, final long cacheKey, final int itemSourceBase) {
        if (itemSourceBase < 0) {
            throw new IllegalArgumentException("Item source base must be non-negative");
        }
        final List<String> diagnostics = new ArrayList<>();
        final List<BlockEntityTypeMapping> entityTypes = new ArrayList<>();
        final Map<String, BlockEntityTypeMapping> entityTypesByBedrockIdentifier = new HashMap<>();
        int nextBlockEntitySourceId = BedrockProtocol.MAPPINGS.getVanillaBlockEntityCount();
        for (CustomMappingSnapshot.BlockEntityTypeEntry type : snapshot.blockEntityTypes()) {
            final CustomMappingAccess.BlockEntityRule rule = type.rule() == CustomMappingAccess.BlockEntityRule.NONE
                    ? CustomMappingAccess.BlockEntityRule.DROP : type.rule();
            final BlockEntityTypeMapping mapping = new BlockEntityTypeMapping(type.bedrockIdentifier(), type.javaIdentifier(), nextBlockEntitySourceId++, type.targetJavaRawId(), rule);
            entityTypes.add(mapping);
            entityTypesByBedrockIdentifier.put(type.bedrockIdentifier(), mapping);
        }

        final List<ItemMapping> items = new ArrayList<>();
        final Map<String, ItemMapping> itemsByBedrockIdentifier = new HashMap<>();
        int nextItemSourceId = itemSourceBase;
        for (CustomMappingSnapshot.ItemEntry item : snapshot.items()) {
            final ItemMapping mapping = new ItemMapping(item.bedrockIdentifier(), nextItemSourceId++, item.targetJavaRawId(), item.maxStackSize());
            items.add(mapping);
            itemsByBedrockIdentifier.put(item.bedrockIdentifier(), mapping);
        }

        final Map<CustomMappingSnapshot.TypedBedrockState, BlockStateMapping> states = new HashMap<>();
        for (CustomMappingSnapshot.BlockStateEntry state : snapshot.blockStates()) {
            final int fallbackSourceJavaRawId = rawIdFor(state.fallbackJavaState());
            if (fallbackSourceJavaRawId < 0 || fallbackSourceJavaRawId >= BedrockProtocol.MAPPINGS.getVanillaBlockStateCount()) {
                diagnostics.add("Skipped " + state.bedrockState().toUntypedBlockStateString() + " because fallbackJavaState is not a source vanilla block state: " + state.fallbackJavaState());
                continue;
            }

            int emit = state.emit();
            int filter = state.filter();
            if (emit < 0 || emit > 15) {
                diagnostics.add("Degraded invalid emit light for " + state.bedrockState().toUntypedBlockStateString() + ": " + emit);
                emit = 0;
            }
            if (filter < 0 || filter > 15) {
                diagnostics.add("Degraded invalid filter light for " + state.bedrockState().toUntypedBlockStateString() + ": " + filter);
                filter = BedrockProtocol.MAPPINGS.getFilterLight(fallbackSourceJavaRawId);
            }

            CustomMappingAccess.BlockEntityRule rule = state.blockEntityRule();
            if (rule == CustomMappingAccess.BlockEntityRule.MOD_BLOCK && !entityTypesByBedrockIdentifier.containsKey(state.bedrockState().identifier())) {
                diagnostics.add("Degraded missing mod_block block entity type for " + state.bedrockState().toUntypedBlockStateString());
                rule = CustomMappingAccess.BlockEntityRule.DROP;
            }

            states.put(state.bedrockState(), new BlockStateMapping(state.targetJavaRawId(), fallbackSourceJavaRawId, emit, filter, state.secondsToDestroy(), rule));
        }

        if (!diagnostics.isEmpty()) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Custom mapping snapshot was locally degraded: " + diagnostics.size() + " issue(s)");
        }
        return new SnapshotProfile(states, entityTypes, itemsByBedrockIdentifier, items, diagnostics, cacheKey);
    }

    public BlockStateMapping blockState(final BlockState runtimeState) {
        return this.blockStatesByState.get(CustomMappingSnapshot.TypedBedrockState.fromRuntimeState(runtimeState));
    }

    public List<BlockEntityTypeMapping> blockEntityTypes() {
        return this.blockEntityTypes;
    }

    public ItemMapping item(final String bedrockIdentifier) {
        return this.itemsByBedrockIdentifier.get(bedrockIdentifier);
    }

    public List<ItemMapping> items() {
        return this.items;
    }

    public int blockStateCount() {
        return this.blockStatesByState.size();
    }

    public int blockEntityTypeCount() {
        return this.blockEntityTypes.size();
    }

    public int itemCount() {
        return this.items.size();
    }

    public int maxTargetJavaRawId() {
        int max = -1;
        for (BlockStateMapping mapping : this.blockStatesByState.values()) {
            max = Math.max(max, mapping.targetJavaRawId());
        }
        return max;
    }

    public int maxTargetBlockEntityRawId() {
        int max = -1;
        for (BlockEntityTypeMapping mapping : this.blockEntityTypes) {
            max = Math.max(max, mapping.targetJavaRawId());
        }
        return max;
    }

    public int maxTargetJavaItemRawId() {
        int max = -1;
        for (ItemMapping mapping : this.items) {
            max = Math.max(max, mapping.targetJavaRawId());
        }
        return max;
    }

    public List<String> diagnostics() {
        return this.diagnostics;
    }

    public long cacheKey() {
        return this.cacheKey;
    }

    public record BlockStateMapping(int targetJavaRawId, int fallbackSourceJavaRawId, int emit, int filter, float secondsToDestroy, CustomMappingAccess.BlockEntityRule rule) {
    }

    public record BlockEntityTypeMapping(String bedrockIdentifier, String javaIdentifier, int sourceJavaRawId, int targetJavaRawId, CustomMappingAccess.BlockEntityRule rule) {
    }

    public record ItemMapping(String bedrockIdentifier, int sourceJavaRawId, int targetJavaRawId, int maxStackSize) {
    }

    private static int rawIdFor(final String blockStateString) {
        try {
            final Integer id = BedrockProtocol.MAPPINGS.getJavaBlockStates().get(BlockState.fromString(blockStateString));
            return id != null ? id : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
