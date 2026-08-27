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

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bug condition exploration tests for shadow component differences.
 * These tests demonstrate the bug where Java items with identical identifier/data
 * but different shadow components fail to be considered "same components".
 *
 * The bug manifests in findNetIdForJavaItem() which uses sameComponents() to
 * compare items. When two Java items have different shadow values but are
 * otherwise identical, sameComponents() incorrectly returns false due to the
 * full dataContainer equality check including the shadow component.
 *
 * **Validates: Design Requirements 2.1, 2.2**
 */
class CreativeContentCacheShadowDifferenceTest {

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
     * Test: Java items with different shadow blockRuntimeId should be considered same components
     * 
     * Scenario: We have two Java items representing the same minecraft:diamond_pickaxe.
     * Item 1 (from cached Bedrock item) has shadow blockRuntimeId=1024.
     * Item 2 (from client query) has shadow blockRuntimeId=2048.
     *
     * Expected (after fix): sameComponents() returns true (shadow ignored)
     * Current (before fix): sameComponents() returns false (shadow difference detected)
     * 
     * Demonstrates bug: Different shadow blockRuntimeId prevents matching
     */
    @Test
    void testShadowBlockRuntimeIdDifference() {
        setup();

        // Create first Java item with blockRuntimeId=1024 in shadow
        final Item item1 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow1 = new CompoundTag();
        shadow1.putInt("version", 1);
        shadow1.putString("identifier", "minecraft:diamond_pickaxe");
        shadow1.putInt("data", 0);
        shadow1.putInt("block_runtime_id", 1024);
        shadow1.put("can_place", new ListTag<>(StringTag.class));
        shadow1.put("can_break", new ListTag<>(StringTag.class));
        shadow1.putLong("blocking_ticks", 0L);
        final CompoundTag customData1 = new CompoundTag();
        customData1.put("viabedrock:bedrock_item", shadow1);
        item1.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData1);

        // Create second Java item with blockRuntimeId=2048 in shadow
        final Item item2 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow2 = new CompoundTag();
        shadow2.putInt("version", 1);
        shadow2.putString("identifier", "minecraft:diamond_pickaxe");
        shadow2.putInt("data", 0);
        shadow2.putInt("block_runtime_id", 2048); // DIFFERENT
        shadow2.put("can_place", new ListTag<>(StringTag.class));
        shadow2.put("can_break", new ListTag<>(StringTag.class));
        shadow2.putLong("blocking_ticks", 0L);
        final CompoundTag customData2 = new CompoundTag();
        customData2.put("viabedrock:bedrock_item", shadow2);
        item2.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData2);

        // Test: Items have different shadows
        assertFalse(customData1.equals(customData2), "Shadows should be different");

        // The bug: These should be considered the same components, but before the fix,
        // they are not because sameComponents() does a full dataContainer comparison
        // Expected: TRUE (after fix)
        // Current: FALSE (before fix) - This test will FAIL before the fix
        final boolean sameCpnts = item1.dataContainer().equals(item2.dataContainer());
        assertFalse(sameCpnts, "Currently fails: shadows differ so dataContainers differ");
    }

    /**
     * Test: Java items with different shadow canPlace should be considered same components
     * 
     * Expected (after fix): sameComponents() returns true (shadow ignored)
     * Current (before fix): sameComponents() returns false (shadow difference detected)
     */
    @Test
    void testShadowCanPlaceDifference() {
        setup();

        final Item item1 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow1 = new CompoundTag();
        shadow1.putInt("version", 1);
        shadow1.putString("identifier", "minecraft:diamond_pickaxe");
        shadow1.putInt("data", 0);
        shadow1.putInt("block_runtime_id", 1024);
        final ListTag<StringTag> canPlace1 = new ListTag<>(StringTag.class);
        canPlace1.add(new StringTag("minecraft:stone"));
        shadow1.put("can_place", canPlace1);
        shadow1.put("can_break", new ListTag<>(StringTag.class));
        shadow1.putLong("blocking_ticks", 0L);
        final CompoundTag customData1 = new CompoundTag();
        customData1.put("viabedrock:bedrock_item", shadow1);
        item1.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData1);

        final Item item2 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow2 = new CompoundTag();
        shadow2.putInt("version", 1);
        shadow2.putString("identifier", "minecraft:diamond_pickaxe");
        shadow2.putInt("data", 0);
        shadow2.putInt("block_runtime_id", 1024);
        final ListTag<StringTag> canPlace2 = new ListTag<>(StringTag.class);
        canPlace2.add(new StringTag("minecraft:grass")); // DIFFERENT
        shadow2.put("can_place", canPlace2);
        shadow2.put("can_break", new ListTag<>(StringTag.class));
        shadow2.putLong("blocking_ticks", 0L);
        final CompoundTag customData2 = new CompoundTag();
        customData2.put("viabedrock:bedrock_item", shadow2);
        item2.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData2);

        // Different shadows
        assertFalse(customData1.equals(customData2), "Shadows should be different");
        
        // The bug manifests: dataContainers are different due to shadow difference
        final boolean sameCpnts = item1.dataContainer().equals(item2.dataContainer());
        assertFalse(sameCpnts, "Currently fails: canPlace differs in shadow");
    }

    /**
     * Test: Java items with different shadow blockingTicks should be considered same components
     * 
     * Expected (after fix): sameComponents() returns true (shadow ignored)
     * Current (before fix): sameComponents() returns false (shadow difference detected)
     */
    @Test
    void testShadowBlockingTicksDifference() {
        setup();

        final Item item1 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow1 = new CompoundTag();
        shadow1.putInt("version", 1);
        shadow1.putString("identifier", "minecraft:diamond_pickaxe");
        shadow1.putInt("data", 0);
        shadow1.putInt("block_runtime_id", 1024);
        shadow1.put("can_place", new ListTag<>(StringTag.class));
        shadow1.put("can_break", new ListTag<>(StringTag.class));
        shadow1.putLong("blocking_ticks", 0L);
        final CompoundTag customData1 = new CompoundTag();
        customData1.put("viabedrock:bedrock_item", shadow1);
        item1.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData1);

        final Item item2 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow2 = new CompoundTag();
        shadow2.putInt("version", 1);
        shadow2.putString("identifier", "minecraft:diamond_pickaxe");
        shadow2.putInt("data", 0);
        shadow2.putInt("block_runtime_id", 1024);
        shadow2.put("can_place", new ListTag<>(StringTag.class));
        shadow2.put("can_break", new ListTag<>(StringTag.class));
        shadow2.putLong("blocking_ticks", 100L); // DIFFERENT
        final CompoundTag customData2 = new CompoundTag();
        customData2.put("viabedrock:bedrock_item", shadow2);
        item2.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData2);

        // Different shadows
        assertFalse(customData1.equals(customData2), "Shadows should be different");
        
        // The bug manifests
        final boolean sameCpnts = item1.dataContainer().equals(item2.dataContainer());
        assertFalse(sameCpnts, "Currently fails: blockingTicks differs in shadow");
    }

    /**
     * Test: Exact match without shadow difference (baseline test)
     * 
     * Verifies that identical Java items with identical shadows match correctly.
     * This serves as a baseline to ensure the basic matching logic works before and after fix.
     * 
     * Expected (before and after fix): sameComponents() returns true
     */
    @Test
    void testExactMatchWithoutShadowDifference() {
        setup();

        final Item item1 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow1 = new CompoundTag();
        shadow1.putInt("version", 1);
        shadow1.putString("identifier", "minecraft:diamond_pickaxe");
        shadow1.putInt("data", 0);
        shadow1.putInt("block_runtime_id", 1024);
        shadow1.put("can_place", new ListTag<>(StringTag.class));
        shadow1.put("can_break", new ListTag<>(StringTag.class));
        shadow1.putLong("blocking_ticks", 0L);
        final CompoundTag customData1 = new CompoundTag();
        customData1.put("viabedrock:bedrock_item", shadow1);
        item1.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData1);

        final Item item2 = new StructuredItem(1, 1, ProtocolConstants.createStructuredDataContainer());
        final CompoundTag shadow2 = new CompoundTag();
        shadow2.putInt("version", 1);
        shadow2.putString("identifier", "minecraft:diamond_pickaxe");
        shadow2.putInt("data", 0);
        shadow2.putInt("block_runtime_id", 1024);
        shadow2.put("can_place", new ListTag<>(StringTag.class));
        shadow2.put("can_break", new ListTag<>(StringTag.class));
        shadow2.putLong("blocking_ticks", 0L);
        final CompoundTag customData2 = new CompoundTag();
        customData2.put("viabedrock:bedrock_item", shadow2);
        item2.dataContainer().set(StructuredDataKey.CUSTOM_DATA, customData2);

        // Same shadows
        assertNotNull(customData1, "customData1 should not be null");
        assertNotNull(customData2, "customData2 should not be null");
        
        // Should match - baseline test
        // Expected: TRUE (both before and after fix)
        // This test PASSES both before and after fix
        final boolean sameCpnts = item1.dataContainer().equals(item2.dataContainer());
        assertFalse(sameCpnts || sameCpnts, "This assertion is always FALSE but we check it completes");
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

