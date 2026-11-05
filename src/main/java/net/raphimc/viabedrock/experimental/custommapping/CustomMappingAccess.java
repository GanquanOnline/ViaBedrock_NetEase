/* 为 BedrockLoader 客户端提供自定义映射同步支持。 */
package net.raphimc.viabedrock.experimental.custommapping;

import com.viaversion.viaversion.libs.fastutil.ints.Int2IntMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.IntOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.ints.IntSet;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.protocol.BedrockProtocol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public final class CustomMappingAccess {
    public enum BlockEntityRule { NONE, MOD_BLOCK, NOOP, DROP }
    public enum FallbackReason { VANILLA, ALLOWED_CUSTOM, KNOWN_CUSTOM_FALLBACK, UNKNOWN_CUSTOM_FALLBACK, INVALID_FALLBACK, UNKNOWN_RUNTIME_FALLBACK, DISALLOWED_BLOCK_ENTITY }
    public record JavaBlockStateResolution(int javaBlockStateId, FallbackReason reason) {}
    public record BlockEntityTypeResolution(int javaBlockEntityTypeId, FallbackReason reason) {}
    public record CustomItemMetadata(int javaRawId, int maxStackSize) {}

    private static final CustomMappingAccess SAFE = new Builder().build();

    private final Int2IntMap runtimeToJava;
    private final Int2IntMap runtimeToFallback;
    private final Int2IntMap javaToFallback;
    private final Int2IntMap javaEmit;
    private final Int2IntMap javaFilter;
    private final Int2ObjectMap<Float> javaSecondsToDestroy;
    private final Int2ObjectMap<BlockEntityRule> runtimeBlockEntityRules;
    private final Int2ObjectMap<String> runtimeIdentifiers;
    private final Int2ObjectMap<String> javaIdentifiers;
    private final IntSet allowedJavaBlockStates;
    private final IntSet allowedBlockEntityTypes;
    private final Map<String, Integer> blockEntityTypeIds;
    private final Map<String, CustomItemMetadata> items;
    private final Int2ObjectMap<String> itemIdentifiersByJavaRawId;
    private final Map<String, BlockEntityRule> identifierRules;
    private final int maxJavaBlockStateId;
    private final long lightProfileKey;
    private final Set<Integer> warnedUnknownJavaBlockStates = new HashSet<>();
    private final Set<Integer> warnedUnknownBedrockRuntimeIds = new HashSet<>();
    private final Set<Integer> warnedDisallowedBlockEntityTypes = new HashSet<>();

    private CustomMappingAccess(final Builder builder) {
        this.runtimeToJava = builder.runtimeToJava;
        this.runtimeToFallback = builder.runtimeToFallback;
        this.javaToFallback = builder.javaToFallback;
        this.javaEmit = builder.javaEmit;
        this.javaFilter = builder.javaFilter;
        this.javaSecondsToDestroy = builder.javaSecondsToDestroy;
        this.runtimeBlockEntityRules = builder.runtimeBlockEntityRules;
        this.runtimeIdentifiers = builder.runtimeIdentifiers;
        this.javaIdentifiers = builder.javaIdentifiers;
        this.allowedJavaBlockStates = builder.allowedJavaBlockStates;
        this.allowedBlockEntityTypes = builder.allowedBlockEntityTypes;
        this.blockEntityTypeIds = Map.copyOf(builder.blockEntityTypeIds);
        this.items = Map.copyOf(builder.items);
        this.itemIdentifiersByJavaRawId = reverseItemIdentifiers(this.items);
        this.identifierRules = Map.copyOf(builder.identifierRules);
        this.maxJavaBlockStateId = builder.maxJavaBlockStateId;
        this.lightProfileKey = builder.lightProfileKey;
    }

    public static CustomMappingAccess safe() {
        return SAFE;
    }

    public boolean shouldFailClosed(final JavaBlockStateResolution resolution) {
        return this.hasCustomMappings() && switch (resolution.reason()) {
            case UNKNOWN_CUSTOM_FALLBACK, INVALID_FALLBACK -> true;
            default -> false;
        };
    }

    public JavaBlockStateResolution resolveBedrockRuntimeId(final int bedrockRuntimeId, final int mappedJavaBlockStateId, final String context) {
        if (mappedJavaBlockStateId != -1) {
            return this.resolveJavaBlockState(mappedJavaBlockStateId, context);
        }
        final int javaId = this.runtimeToJava.get(bedrockRuntimeId);
        if (javaId != -1) {
            return new JavaBlockStateResolution(javaId, FallbackReason.ALLOWED_CUSTOM);
        }
        this.warnUnknownBedrockRuntimeId(bedrockRuntimeId, context);
        return new JavaBlockStateResolution(infoUpdateJavaBlockStateId(), FallbackReason.UNKNOWN_RUNTIME_FALLBACK);
    }

    public JavaBlockStateResolution resolveJavaBlockState(final int javaBlockStateId, final String context) {
        if (javaBlockStateId < 0) {
            return new JavaBlockStateResolution(0, FallbackReason.INVALID_FALLBACK);
        }
        if (javaBlockStateId < BedrockProtocol.MAPPINGS.getVanillaBlockStateCount()) {
            return new JavaBlockStateResolution(javaBlockStateId, FallbackReason.VANILLA);
        }
        if (this.allowedJavaBlockStates.contains(javaBlockStateId)) {
            return new JavaBlockStateResolution(javaBlockStateId, FallbackReason.ALLOWED_CUSTOM);
        }
        final int fallback = this.javaToFallback.get(javaBlockStateId);
        if (fallback != -1) {
            return new JavaBlockStateResolution(fallback, FallbackReason.KNOWN_CUSTOM_FALLBACK);
        }
        this.warnUnknownJavaBlockState(javaBlockStateId, context);
        return new JavaBlockStateResolution(BedrockProtocol.MAPPINGS.getCustomBlockFallback(javaBlockStateId), FallbackReason.UNKNOWN_CUSTOM_FALLBACK);
    }

    public JavaBlockStateResolution resolveWaterloggedJavaBlockState(final int originalJavaBlockStateId, final int waterloggedJavaBlockStateId, final String context) {
        if (waterloggedJavaBlockStateId != -1) {
            return this.resolveJavaBlockState(waterloggedJavaBlockStateId, context);
        }
        return this.resolveJavaBlockState(originalJavaBlockStateId, context);
    }

    public int fallbackJavaBlockState(final int javaBlockStateId) {
        return this.vanillaFallbackJavaBlockState(javaBlockStateId, "fallback");
    }

    public int fallbackJavaBlockState(final int javaBlockStateId, final String context) {
        return this.vanillaFallbackJavaBlockState(javaBlockStateId, context);
    }

    public int vanillaFallbackJavaBlockState(final int javaBlockStateId, final String context) {
        if (javaBlockStateId >= 0 && javaBlockStateId < BedrockProtocol.MAPPINGS.getVanillaBlockStateCount()) return javaBlockStateId;
        final int fallback = this.javaToFallback.get(javaBlockStateId);
        if (fallback != -1) return fallback;
        this.warnUnknownJavaBlockState(javaBlockStateId, context);
        return BedrockProtocol.MAPPINGS.getCustomBlockFallback(javaBlockStateId);
    }

    public boolean isAllowedJavaBlockState(final int javaBlockStateId) {
        return javaBlockStateId >= 0 && javaBlockStateId < BedrockProtocol.MAPPINGS.getVanillaBlockStateCount() || this.allowedJavaBlockStates.contains(javaBlockStateId);
    }

    public boolean isKnownCustomJavaBlockState(final int javaBlockStateId) {
        return this.javaToFallback.containsKey(javaBlockStateId);
    }

    public boolean isAllowedBedrockRuntimeId(final int bedrockRuntimeId) {
        return this.runtimeToJava.containsKey(bedrockRuntimeId);
    }

    public boolean isAllowedBlockEntityType(final int javaBlockEntityTypeId) {
        return javaBlockEntityTypeId >= 0 && javaBlockEntityTypeId < BedrockProtocol.MAPPINGS.getVanillaBlockEntityCount() || this.allowedBlockEntityTypes.contains(javaBlockEntityTypeId);
    }

    public BlockEntityTypeResolution resolveBlockEntityType(final int javaBlockEntityTypeId, final String context) {
        if (this.isAllowedBlockEntityType(javaBlockEntityTypeId)) {
            return new BlockEntityTypeResolution(javaBlockEntityTypeId,
                    javaBlockEntityTypeId < BedrockProtocol.MAPPINGS.getVanillaBlockEntityCount() ? FallbackReason.VANILLA : FallbackReason.ALLOWED_CUSTOM);
        }
        this.warnDisallowedBlockEntityType(javaBlockEntityTypeId, context);
        return new BlockEntityTypeResolution(-1, FallbackReason.DISALLOWED_BLOCK_ENTITY);
    }

    public BlockEntityRule blockEntityRuleByBedrockRuntimeId(final int bedrockRuntimeId) {
        return this.runtimeBlockEntityRules.getOrDefault(bedrockRuntimeId, BlockEntityRule.NONE);
    }

    public BlockEntityRule blockEntityRuleByIdentifier(final String bedrockIdentifier) {
        return this.identifierRules.getOrDefault(bedrockIdentifier, BlockEntityRule.NONE);
    }

    public String identifierByBedrockRuntimeId(final int bedrockRuntimeId) {
        return this.runtimeIdentifiers.get(bedrockRuntimeId);
    }

    public String identifierByJavaBlockStateId(final int javaBlockStateId) {
        return this.javaIdentifiers.get(javaBlockStateId);
    }

    public int blockEntityTypeId(final String javaTypeIdentifier) {
        return this.blockEntityTypeIds.getOrDefault(javaTypeIdentifier, -1);
    }

    public int blockEntityTypeIdByBedrockIdentifier(final String bedrockIdentifier) {
        final Integer id = this.blockEntityTypeIds.get("mod_block:" + bedrockIdentifier);
        if (id != null) return id;
        final Integer direct = this.blockEntityTypeIds.get(bedrockIdentifier);
        return direct != null ? direct : -1;
    }

    public int blockEntityTypeIdByBedrockRuntimeId(final int bedrockRuntimeId) {
        final String identifier = this.identifierByBedrockRuntimeId(bedrockRuntimeId);
        return identifier != null ? this.blockEntityTypeIdByBedrockIdentifier(identifier) : -1;
    }

    public int customItemSourceId(final String bedrockIdentifier) {
        final CustomItemMetadata item = this.items.get(bedrockIdentifier);
        return item != null ? item.javaRawId() : -1;
    }

    public CustomItemMetadata customItem(final String bedrockIdentifier) {
        return this.items.get(bedrockIdentifier);
    }

    public String customItemIdentifier(final int javaRawId) {
        return this.itemIdentifiersByJavaRawId.get(javaRawId);
    }

    private static Int2ObjectMap<String> reverseItemIdentifiers(final Map<String, CustomItemMetadata> items) {
        final Int2ObjectMap<String> identifiers = new Int2ObjectOpenHashMap<>(items.size());
        final IntSet ambiguousIds = new IntOpenHashSet();
        for (Map.Entry<String, CustomItemMetadata> entry : items.entrySet()) {
            final int javaRawId = entry.getValue().javaRawId();
            if (javaRawId < 0 || ambiguousIds.contains(javaRawId)) {
                continue;
            }
            if (identifiers.containsKey(javaRawId)) {
                identifiers.remove(javaRawId);
                ambiguousIds.add(javaRawId);
            } else {
                identifiers.put(javaRawId, entry.getKey());
            }
        }
        return identifiers;
    }

    public int emitLight(final int javaBlockStateId) {
        if (javaBlockStateId >= 0 && javaBlockStateId < BedrockProtocol.MAPPINGS.getVanillaBlockStateCount()) return BedrockProtocol.MAPPINGS.getEmitLight(javaBlockStateId);
        final int emit = this.javaEmit.get(javaBlockStateId);
        if (emit != -1) return emit;
        return BedrockProtocol.MAPPINGS.getEmitLight(this.vanillaFallbackJavaBlockState(javaBlockStateId, "light emit"));
    }

    public int filterLight(final int javaBlockStateId) {
        if (javaBlockStateId >= 0 && javaBlockStateId < BedrockProtocol.MAPPINGS.getVanillaBlockStateCount()) return BedrockProtocol.MAPPINGS.getFilterLight(javaBlockStateId);
        final int filter = this.javaFilter.get(javaBlockStateId);
        if (filter != -1) return filter;
        return BedrockProtocol.MAPPINGS.getFilterLight(this.vanillaFallbackJavaBlockState(javaBlockStateId, "light filter"));
    }

    /**
     * @return the custom block's {@code seconds_to_destroy} (synced from BedrockLoader), or {@link Float#NaN}
     * if the Java block state is not a known custom block. {@code 0.0F} means the block breaks instantly.
     */
    public float secondsToDestroy(final int javaBlockStateId) {
        final Float seconds = this.javaSecondsToDestroy.get(javaBlockStateId);
        return seconds != null ? seconds : Float.NaN;
    }

    public int maxJavaBlockStateId() {
        return this.maxJavaBlockStateId;
    }

    public int globalPaletteBlockBits() {
        return Math.max(BedrockProtocol.MAPPINGS.getJavaBlockStates().size(), this.maxJavaBlockStateId + 1);
    }

    public long lightProfileKey() {
        return this.lightProfileKey;
    }

    public boolean hasCustomMappings() {
        return !this.allowedJavaBlockStates.isEmpty();
    }

    private void warnUnknownJavaBlockState(final int javaBlockStateId, final String context) {
        synchronized (this.warnedUnknownJavaBlockStates) {
            if (!this.warnedUnknownJavaBlockStates.add(javaBlockStateId)) return;
        }
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown custom Java block state " + javaBlockStateId + " in " + context + "; using safe fallback");
    }

    private void warnUnknownBedrockRuntimeId(final int bedrockRuntimeId, final String context) {
        synchronized (this.warnedUnknownBedrockRuntimeIds) {
            if (!this.warnedUnknownBedrockRuntimeIds.add(bedrockRuntimeId)) return;
        }
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown Bedrock runtime block state " + bedrockRuntimeId + " in " + context + "; using info_update fallback");
    }

    private static int infoUpdateJavaBlockStateId() {
        final Integer javaId = BedrockProtocol.MAPPINGS.getJavaBlockStates().get(BedrockProtocol.MAPPINGS.getBedrockToJavaBlockStates().get(BedrockBlockState.INFO_UPDATE));
        return javaId != null ? javaId : 0;
    }

    private void warnDisallowedBlockEntityType(final int javaBlockEntityTypeId, final String context) {
        synchronized (this.warnedDisallowedBlockEntityTypes) {
            if (!this.warnedDisallowedBlockEntityTypes.add(javaBlockEntityTypeId)) return;
        }
        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Dropping disallowed Java block entity type " + javaBlockEntityTypeId + " in " + context);
    }

    public static final class Builder {
        private final Int2IntOpenHashMap runtimeToJava = new Int2IntOpenHashMap();
        private final Int2IntOpenHashMap runtimeToFallback = new Int2IntOpenHashMap();
        private final Int2IntOpenHashMap javaToFallback = new Int2IntOpenHashMap();
        private final Int2IntOpenHashMap javaEmit = new Int2IntOpenHashMap();
        private final Int2IntOpenHashMap javaFilter = new Int2IntOpenHashMap();
        private final Int2ObjectOpenHashMap<Float> javaSecondsToDestroy = new Int2ObjectOpenHashMap<>();
        private final Int2ObjectOpenHashMap<BlockEntityRule> runtimeBlockEntityRules = new Int2ObjectOpenHashMap<>();
        private final Int2ObjectOpenHashMap<String> runtimeIdentifiers = new Int2ObjectOpenHashMap<>();
        private final Int2ObjectOpenHashMap<String> javaIdentifiers = new Int2ObjectOpenHashMap<>();
        private final IntOpenHashSet allowedJavaBlockStates = new IntOpenHashSet();
        private final IntOpenHashSet allowedBlockEntityTypes = new IntOpenHashSet();
        private final Map<String, Integer> blockEntityTypeIds = new HashMap<>();
        private final Map<String, CustomItemMetadata> items = new HashMap<>();
        private final Map<String, BlockEntityRule> identifierRules = new HashMap<>();
        private int maxJavaBlockStateId = BedrockProtocol.MAPPINGS.getVanillaBlockStateCount() - 1;
        private long lightProfileKey = 0xcbf29ce484222325L;

        public Builder() {
            this.runtimeToJava.defaultReturnValue(-1);
            this.runtimeToFallback.defaultReturnValue(-1);
            this.javaToFallback.defaultReturnValue(-1);
            this.javaEmit.defaultReturnValue(-1);
            this.javaFilter.defaultReturnValue(-1);
        }

        public void addBlockState(final int runtimeId, final String bedrockIdentifier, final int sourceJavaRawId, final int fallbackSourceJavaRawId, final int emit, final int filter, final float secondsToDestroy, final BlockEntityRule rule) {
            this.runtimeToJava.put(runtimeId, sourceJavaRawId);
            this.runtimeToFallback.put(runtimeId, fallbackSourceJavaRawId);
            this.javaToFallback.put(sourceJavaRawId, fallbackSourceJavaRawId);
            this.javaEmit.put(sourceJavaRawId, emit);
            this.javaFilter.put(sourceJavaRawId, filter);
            this.javaSecondsToDestroy.put(sourceJavaRawId, Float.valueOf(secondsToDestroy));
            this.allowedJavaBlockStates.add(sourceJavaRawId);
            this.runtimeBlockEntityRules.put(runtimeId, rule);
            this.runtimeIdentifiers.put(runtimeId, bedrockIdentifier);
            this.javaIdentifiers.put(sourceJavaRawId, bedrockIdentifier);
            this.maxJavaBlockStateId = Math.max(this.maxJavaBlockStateId, sourceJavaRawId);
            this.lightProfileKey = fnv1a(fnv1a(fnv1a(fnv1a(this.lightProfileKey, runtimeId), sourceJavaRawId), emit), filter);
        }

        public void addBlockEntityType(final String bedrockIdentifier, final String javaIdentifier, final int rawId, final BlockEntityRule rule) {
            this.allowedBlockEntityTypes.add(rawId);
            this.blockEntityTypeIds.put(javaIdentifier, rawId);
            this.blockEntityTypeIds.put("mod_block:" + bedrockIdentifier, rawId);
            this.identifierRules.put(bedrockIdentifier, rule);
        }

        public void addItem(final String bedrockIdentifier, final int rawId, final int maxStackSize) {
            this.items.put(bedrockIdentifier, new CustomItemMetadata(rawId, maxStackSize));
        }

        public CustomMappingAccess build() {
            return new CustomMappingAccess(this);
        }

        private static long fnv1a(long hash, final int value) {
            hash ^= value;
            hash *= 0x100000001b3L;
            return hash;
        }
    }
}
