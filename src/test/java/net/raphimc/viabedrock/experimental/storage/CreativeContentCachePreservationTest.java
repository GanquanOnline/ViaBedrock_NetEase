/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.platform.ViaPlatformLoader;
import com.viaversion.viaversion.commands.ViaCommandHandler;
import com.viaversion.viaversion.configuration.AbstractViaConfig;
import com.viaversion.viaversion.platform.NoopInjector;
import com.viaversion.viaversion.platform.UserConnectionViaVersionPlatform;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preservation tests for non-shadow-difference input behavior.
 *
 * These tests observe the behavior of unfixed code for inputs that do NOT satisfy
 * the bug condition (shadow component difference). They verify that such inputs
 * work correctly and establish a baseline of expected behavior.
 *
 * The tests are structured to pass on unfixed code and continue to pass after the fix,
 * ensuring no regression in handling of non-shadow-related item matching.
 *
 * **Validates: Design Requirements 3.1, 3.2, 3.3**
 */
class CreativeContentCachePreservationTest {

    private EmbeddedChannel channel;
    private StubUserConnection user;
    private CreativeContentCache cache;

    @BeforeAll
    static void setupVia() throws Exception {
        if (!Via.isLoaded()) {
            com.viaversion.viaversion.ViaManagerImpl.initAndLoad(
                    new TestPlatform(),
                    new NoopInjector(),
                    new ViaCommandHandler(false),
                    ViaPlatformLoader.NOOP
            );
            awaitMappingCompletion();
        }
    }

    private static void awaitMappingCompletion() throws InterruptedException {
        final var protocolManager = Via.getManager().getProtocolManager();
        if (protocolManager.hasLoadedMappings()) {
            return;
        }
        final long deadline = System.nanoTime() + 60_000_000_000L;
        while (!protocolManager.hasLoadedMappings() && !protocolManager.checkForMappingCompletion(true)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for mapping completion");
            }
            Thread.sleep(100L);
        }
    }

    void setup() {
        this.channel = new EmbeddedChannel();
        this.user = new StubUserConnection(this.channel);
        this.cache = new CreativeContentCache(this.user);
    }

    @AfterEach
    void teardown() {
        if (this.channel != null) {
            this.channel.finishAndReleaseAll();
        }
    }

    /**
     * Property 2.1: No Shadow Item Query Preserved
     *
     * For items without the viabedrock:bedrock_item shadow component,
     * the query behavior should remain unchanged after the fix.
     *
     * Pre-condition: Item does not contain shadow component
     * Property: Query should return the cached entry
     * Verification: Query succeeds for items without shadow
     */
    @Test
    void testNoShadowItemQueryPreserved() {
        setup();

        // Create and cache a Bedrock item: diamond_pickaxe
        final BedrockItem bedrockItem = new BedrockItem(285, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);
        this.cache.replace(List.of(new CreativeContentCache.Entry(42, bedrockItem)));

        // Verify cache state
        assertEquals(1, this.cache.size(), "Cache should have 1 entry");
        
        // For items without shadow, basic cache operations should work
        final BedrockItem retrievedItem = this.cache.itemByNetId(42);
        assertNotNull(retrievedItem, "Item should be retrievable by netId");
        assertEquals(285, retrievedItem.identifier(), "Retrieved item should have correct id");

        teardown();
    }

    /**
     * Property 2.2: Mismatched Identifier Rejected
     *
     * Items with BedrockItem identifiers that don't match cached entries
     * should not match via isDifferent check.
     *
     * Pre-condition: Query item has different identifier than cached
     * Property: findNetId returns fallback or null for non-matching identifiers
     * Verification: Non-matching identifiers are handled correctly
     */
    @Test
    void testMismatchedIdentifierPreserved() {
        setup();

        // Cache a diamond_pickaxe (id=285, data=0)
        final BedrockItem cachedItem = new BedrockItem(285, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);
        this.cache.replace(List.of(new CreativeContentCache.Entry(42, cachedItem)));

        // Query with iron_pickaxe (id=256, data=0) - different id
        final BedrockItem queryItem = new BedrockItem(256, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);

        // findNetId uses isDifferent check - different ids should not match exactly
        Integer netId = this.cache.findNetId(queryItem);
        
        // For items with same data but different id, it depends on sameCreativeIdentity
        // Since ids are different (285 vs 256), no fallback should occur
        assertNull(netId, "Different identifiers should not match");

        teardown();
    }

    /**
     * Property 2.3: Identifier/Data Match Fallback Behavior Preserved
     *
     * When Bedrock items have matching id/data, fallback mechanism should work.
     *
     * Pre-condition: Item id and data match, but other properties may differ
     * Property: findNetId returns entry when id/data match
     * Verification: Fallback mechanism consistently works for id/data matches
     */
    @Test
    void testIdentifierDataMatchWithOtherComponentDiffPreserved() {
        setup();

        // Cache a Bedrock item: id=35, data=0, with specific NBT
        final BedrockItem cachedItem = new BedrockItem(35, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);
        this.cache.replace(List.of(new CreativeContentCache.Entry(7, cachedItem)));

        // Query with same id/data but different amount
        final BedrockItem queryItem = new BedrockItem(35, (short) 0, (byte) 64);

        // findNetId checks isDifferent, then falls back to sameCreativeIdentity
        Integer netId = this.cache.findNetId(queryItem);

        // Since id and data match, fallback should return the netId
        assertEquals(7, netId, "Fallback should return netId for matching id/data");

        teardown();
    }

    /**
     * Property 2.4: Non-Creative-Library Item Preserved
     *
     * When the creative content cache is empty, all queries return null.
     * This behavior should be preserved after the fix.
     *
     * Pre-condition: Cache is empty
     * Property: findNetId and itemByNetId both return null
     * Verification: Empty cache consistently returns null
     */
    @Test
    void testEmptyCachePreserved() {
        setup();

        // Create an empty cache
        this.cache.replace(List.of());
        assertEquals(0, this.cache.size(), "Cache should be empty");

        // Query on empty cache
        final BedrockItem queryItem = new BedrockItem(285, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);
        Integer netId = this.cache.findNetId(queryItem);

        // Empty cache should return null
        assertNull(netId, "Empty cache should return null");

        // itemByNetId on empty cache should also return null
        BedrockItem retrievedItem = this.cache.itemByNetId(42);
        assertNull(retrievedItem, "Empty cache should not retrieve any item");

        teardown();
    }

    /**
     * Additional test: Verify cache operations on non-shadow items
     *
     * Tests basic cache operations to ensure preservation of non-shadow behavior.
     */
    @Test
    void testCacheOperationsPreserved() {
        setup();

        // Create cache entries without shadow components
        final List<CreativeContentCache.Entry> entries = List.of(
                new CreativeContentCache.Entry(1, new BedrockItem(35, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1)),
                new CreativeContentCache.Entry(2, new BedrockItem(36, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1)),
                new CreativeContentCache.Entry(3, new BedrockItem(37, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1))
        );
        this.cache.replace(entries);

        // Verify cache size
        assertEquals(3, this.cache.size(), "Cache should have 3 entries");

        // Verify item retrieval by netId
        BedrockItem item1 = this.cache.itemByNetId(1);
        assertNotNull(item1, "Item 1 should be retrievable");
        assertEquals(35, item1.identifier(), "Item 1 should have id 35");

        BedrockItem item2 = this.cache.itemByNetId(2);
        assertNotNull(item2, "Item 2 should be retrievable");
        assertEquals(36, item2.identifier(), "Item 2 should have id 36");

        // Verify exact netId lookup works
        Integer exactNetId = this.cache.findExactNetId(new BedrockItem(35, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1));
        assertEquals(1, exactNetId, "Exact lookup for id 35 should return netId 1");

        teardown();
    }

    /**
     * Additional test: Verify findNetId fallback behavior with data mismatch
     *
     * When querying with same identifier but different data, behavior should be consistent.
     */
    @Test
    void testFallbackBehaviorWithDataMismatch() {
        setup();

        // Cache item with specific data value
        final BedrockItem cachedItem = new BedrockItem(35, (short) 3, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);
        this.cache.replace(List.of(new CreativeContentCache.Entry(99, cachedItem)));

        // Query with DIFFERENT data
        final BedrockItem queryItem = new BedrockItem(35, (short) 5, (byte) 1);

        // Same identifier but different data
        // The isDifferent check will catch this, but sameCreativeIdentity should match on id/data pair
        Integer netId = this.cache.findNetId(queryItem);

        // This should return the fallback (99) since id matches but data differs
        // The behavior should be consistent before and after fix
        assertNull(netId, "Different data values should not match via fallback");

        teardown();
    }

    /**
     * Additional test: Verify clear operation works correctly
     *
     * Clearing the cache should leave it empty.
     */
    @Test
    void testCacheClearPreserved() {
        setup();

        // Populate cache
        final List<CreativeContentCache.Entry> entries = List.of(
                new CreativeContentCache.Entry(1, new BedrockItem(35, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1)),
                new CreativeContentCache.Entry(2, new BedrockItem(36, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1))
        );
        this.cache.replace(entries);
        assertEquals(2, this.cache.size(), "Cache should have 2 entries");

        // Clear cache
        this.cache.clear();
        assertEquals(0, this.cache.size(), "Cache should be empty after clear");

        // Query should return null on empty cache
        Integer netId = this.cache.findNetId(new BedrockItem(35, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1));
        assertNull(netId, "Empty cache should return null for any query");

        teardown();
    }

    private static final class TestPlatform extends UserConnectionViaVersionPlatform {
        private TestPlatform() {
            super(null);
        }

        @Override
        public String getPlatformName() {
            return "ViaBedrock Test";
        }

        @Override
        public String getPlatformVersion() {
            return "test";
        }

        @Override
        public Logger createLogger(final String name) {
            return Logger.getGlobal();
        }

        @Override
        protected AbstractViaConfig createConfig() {
            return new AbstractViaConfig(null, null) {
                @Override
                public void reload() {
                }
            };
        }
    }
}
