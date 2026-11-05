package net.raphimc.viabedrock.experimental.custommapping;

import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

public final class CustomMappingProfileCache {
    public static final long EMPTY_KEY = 0x9ae16a3b2f90404fL;
    public static final long UNKNOWN_KEY = 0L;
    public static final String SYNC_MAPPING_VERSION = "viabedrock-custom-mapping-v3";
    private static final int KEY_SCHEMA_VERSION = 3;
    private static final String PROFILE_ALGORITHM_VERSION = "snapshot-profile-v1";
    private static final String LIGHT_SEMANTIC_ALGORITHM_VERSION = "block-light-semantics-v1";
    private static final int MAX_ENTRIES = 128;
    private static final long TTL_MILLIS = 30 * 60 * 1000L;
    private static final CustomMappingProfileCache INSTANCE = new CustomMappingProfileCache();

    public static CustomMappingProfileCache getInstance() {
        return INSTANCE;
    }

    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);

    private CustomMappingProfileCache() {
    }

    public synchronized SnapshotProfile getOrBuild(final byte[] snapshotBody, final Supplier<SnapshotProfile> builder) {
        final long key = cacheKey(snapshotBody);
        final long now = System.currentTimeMillis();
        pruneExpired(now);
        final Entry cached = this.entries.get(key);
        if (cached != null) {
            cached.lastAccessMillis = now;
            return cached.profile;
        }
        final SnapshotProfile profile = builder.get();
        this.entries.put(key, new Entry(profile, now));
        evictOverflow();
        return profile;
    }

    public static long cacheKey(final byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        hash = fnv1a(hash, KEY_SCHEMA_VERSION);
        hash = fnv1a(hash, SYNC_MAPPING_VERSION);
        hash = fnv1a(hash, PROFILE_ALGORITHM_VERSION);
        hash = fnv1a(hash, LIGHT_SEMANTIC_ALGORITHM_VERSION);
        hash = fnv1a(hash, net.raphimc.viabedrock.ViaBedrock.getConfig() != null && net.raphimc.viabedrock.ViaBedrock.getConfig().shouldEmulateNetEaseClient()
                ? net.raphimc.viabedrock.ViaBedrock.getConfig().getNetEaseProtocolVersion()
                : BedrockWire.current());
        hash = fnv1a(hash, ProtocolConstants.BEDROCK_VERSION_NAME);
        hash = fnv1a(hash, bytes.length);
        for (final byte b : bytes) {
            hash ^= b & 0xFFL;
            hash *= 0x100000001b3L;
        }
        return hash == UNKNOWN_KEY ? EMPTY_KEY : hash;
    }

    private void pruneExpired(final long now) {
        final Iterator<Map.Entry<Long, Entry>> iterator = this.entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().lastAccessMillis > TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private void evictOverflow() {
        while (this.entries.size() > MAX_ENTRIES) {
            final Iterator<Long> iterator = this.entries.keySet().iterator();
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

    private static long fnv1a(long hash, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        hash = fnv1a(hash, bytes.length);
        for (final byte b : bytes) {
            hash ^= b & 0xFFL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static final class Entry {
        private final SnapshotProfile profile;
        private long lastAccessMillis;

        private Entry(final SnapshotProfile profile, final long lastAccessMillis) {
            this.profile = profile;
            this.lastAccessMillis = lastAccessMillis;
        }
    }
}
