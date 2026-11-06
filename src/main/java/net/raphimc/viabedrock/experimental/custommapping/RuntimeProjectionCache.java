package net.raphimc.viabedrock.experimental.custommapping;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.data.MappingData;
import com.viaversion.viaversion.api.data.Mappings;
import com.viaversion.viaversion.api.protocol.Protocol;
import net.raphimc.viabedrock.protocol.model.BlockProperties;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeProjectionCache {
    private static final int KEY_SCHEMA_VERSION = 3;
    private static final String PROJECTION_ALGORITHM_VERSION = "runtime-projection-v2-source-synthetic";
    private static final String SOURCE_ALLOCATION_ALGORITHM_VERSION = "source-allocation-v2-stage-target-bounded";
    private static final String LIGHT_SEMANTIC_ALGORITHM_VERSION = "block-light-semantics-v1";
    private static final int MAX_ENTRIES = 256;
    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private static final RuntimeProjectionCache INSTANCE = new RuntimeProjectionCache();

    public static RuntimeProjectionCache getInstance() {
        return INSTANCE;
    }

    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);

    private RuntimeProjectionCache() {
    }

    public synchronized RuntimeProjection get(final Key key) {
        final long now = System.currentTimeMillis();
        pruneExpired(now);
        final Entry entry = this.entries.get(key);
        if (entry == null) return null;
        entry.lastAccessMillis = now;
        return entry.projection;
    }

    public synchronized void put(final Key key, final RuntimeProjection projection) {
        this.entries.put(key, new Entry(projection, System.currentTimeMillis()));
        evictOverflow();
    }

    public static Key key(final SnapshotProfile profile, final BlockProperties[] blockProperties, final boolean hashedRuntimeBlockIds, final int blockStateSourceBase, final int blockEntitySourceBase, final long pipelineRegistryKey) {
        return new Key(profile.cacheKey(), runtimeKey(blockProperties, hashedRuntimeBlockIds, blockStateSourceBase, blockEntitySourceBase, pipelineRegistryKey));
    }

    public static long pipelineRegistryKey(final Iterable<? extends Protocol> pipeline) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv1a(hash, "pipeline-stage-registry-v1");
        int stageCount = 0;
        for (final Protocol protocol : pipeline) {
            stageCount++;
            hash = fnv1a(hash, protocol.getClass().getName());
            final MappingData mappingData = protocol.getMappingData();
            hash = fnv1a(hash, mappingData != null ? 1 : 0);
            if (mappingData == null) continue;
            hash = mappingSignature(hash, "block_state", mappingData.getBlockStateMappings());
            hash = mappingSignature(hash, "block_entity_type", mappingData.getBlockEntityMappings());
        }
        return fnv1a(hash, stageCount);
    }

    private static long runtimeKey(final BlockProperties[] blockProperties, final boolean hashedRuntimeBlockIds, final int blockStateSourceBase, final int blockEntitySourceBase, final long pipelineRegistryKey) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv1a(hash, KEY_SCHEMA_VERSION);
        hash = fnv1a(hash, CustomMappingProfileCache.SYNC_MAPPING_VERSION);
        hash = fnv1a(hash, PROJECTION_ALGORITHM_VERSION);
        hash = fnv1a(hash, SOURCE_ALLOCATION_ALGORITHM_VERSION);
        hash = fnv1a(hash, blockStateSourceBase);
        hash = fnv1a(hash, blockEntitySourceBase);
        hash = fnv1a(hash, pipelineRegistryKey);
        hash = fnv1a(hash, LIGHT_SEMANTIC_ALGORITHM_VERSION);
        hash = fnv1a(hash, net.raphimc.viabedrock.ViaBedrock.getConfig() != null && net.raphimc.viabedrock.ViaBedrock.getConfig().shouldEmulateNetEaseClient()
                ? net.raphimc.viabedrock.ViaBedrock.getConfig().getNetEaseProtocolVersion()
                : BedrockWire.current());
        hash = fnv1a(hash, ProtocolConstants.BEDROCK_VERSION_NAME);
        hash = fnv1a(hash, hashedRuntimeBlockIds ? 1 : 0);
        hash = fnv1a(hash, blockProperties.length);
        for (final BlockProperties blockProperty : blockProperties) {
            hash = fnv1a(hash, blockProperty.name().hashCode());
            final CompoundTag properties = blockProperty.properties();
            hash = fnv1a(hash, properties != null ? properties.toString().hashCode() : 0);
        }
        return hash;
    }

    private static long mappingSignature(long hash, final String registry, final Mappings mappings) {
        hash = fnv1a(hash, registry);
        hash = fnv1a(hash, mappings != null ? 1 : 0);
        if (mappings == null) return hash;
        hash = fnv1a(hash, mappings.getClass().getName());
        hash = fnv1a(hash, mappings.size());
        hash = fnv1a(hash, mappings.mappedSize());
        for (int id = 0; id < mappings.size(); id++) {
            final int mappedId = mappings.getNewId(id);
            if (mappedId == -1) continue;
            hash = fnv1a(hash, id);
            hash = fnv1a(hash, mappedId);
        }
        return hash;
    }

    private void pruneExpired(final long now) {
        final Iterator<Map.Entry<Key, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().lastAccessMillis > TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private void evictOverflow() {
        while (this.entries.size() > MAX_ENTRIES) {
            final Iterator<Key> iterator = this.entries.keySet().iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private static long fnv1a(long hash, final int value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private static long fnv1a(long hash, final long value) {
        long remaining = value;
        for (int i = 0; i < Long.BYTES; i++) {
            hash ^= remaining & 0xFFL;
            hash *= 0x100000001b3L;
            remaining >>>= Byte.SIZE;
        }
        return hash;
    }

    private static long fnv1a(long hash, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash = fnv1a(hash, bytes.length);
        for (final byte b : bytes) {
            hash ^= b & 0xFFL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public record Key(long profileKey, long runtimeKey) {
    }

    private static final class Entry {
        private final RuntimeProjection projection;
        private long lastAccessMillis;

        private Entry(final RuntimeProjection projection, final long lastAccessMillis) {
            this.projection = projection;
            this.lastAccessMillis = lastAccessMillis;
        }
    }
}
