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
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeContentCacheTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void findNetIdFallsBackToIdentifierAndDataWhenNbtDiffers() {
        final CreativeContentCache cache = new CreativeContentCache(this.user);
        final BedrockItem cached = new BedrockItem(35, (short) 0, (byte) 1, null, new String[0], new String[0], 0L, 12, 1);
        cache.replace(List.of(new CreativeContentCache.Entry(7, cached)));

        final BedrockItem requested = new BedrockItem(35, (short) 0, (byte) 64);
        assertEquals(7, cache.findNetId(requested));
    }

    @Test
    void findNetIdDoesNotCrossIdentifiers() {
        final CreativeContentCache cache = new CreativeContentCache(this.user);
        cache.replace(List.of(new CreativeContentCache.Entry(3, new BedrockItem(35, (short) 0, (byte) 1))));
        assertNull(cache.findNetId(new BedrockItem(1, (short) 0, (byte) 1)));
    }

    @Test
    void sameEffectiveComponentsIgnoresBedrockItemShadow() {
        final StructuredItem mapped = new StructuredItem(35, 1, new StructuredDataContainer());
        final CompoundTag shadowCustom = new CompoundTag();
        final CompoundTag shadow = new CompoundTag();
        shadow.putInt("version", 1);
        shadow.putString("identifier", "minecraft:wool");
        shadowCustom.put("viabedrock:bedrock_item", shadow);
        putCustomData(mapped, shadowCustom);

        final StructuredItem clicked = new StructuredItem(35, 1, new StructuredDataContainer());
        assertTrue(CreativeContentCache.sameEffectiveComponents(mapped, clicked));
        assertTrue(CreativeContentCache.sameEffectiveComponents(clicked, mapped));
    }

    @Test
    void sameEffectiveComponentsStillComparesOtherCustomData() {
        final StructuredItem left = new StructuredItem(35, 1, new StructuredDataContainer());
        final CompoundTag leftCustom = new CompoundTag();
        leftCustom.putString("viabedrock:bedrock_identifier", "minecraft:wool");
        putCustomData(left, leftCustom);

        final StructuredItem right = new StructuredItem(35, 1, new StructuredDataContainer());
        final CompoundTag rightCustom = new CompoundTag();
        rightCustom.putString("viabedrock:bedrock_identifier", "minecraft:stone");
        putCustomData(right, rightCustom);

        assertFalse(CreativeContentCache.sameEffectiveComponents(left, right));
    }

    private static void putCustomData(final StructuredItem item, final CompoundTag customData) {
        // Avoid StructuredDataContainer.set(), which needs a protocol id lookup.
        item.dataContainer().data().put(StructuredDataKey.CUSTOM_DATA, StructuredData.of(StructuredDataKey.CUSTOM_DATA, customData, 0));
    }
}
