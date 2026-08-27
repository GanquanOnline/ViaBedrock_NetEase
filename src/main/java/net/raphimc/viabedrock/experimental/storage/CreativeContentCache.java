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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Caches Bedrock CREATIVE_CONTENT so Java creative-tab clicks can be turned into
 * Nukkit 860 SAI CraftCreative requests. Lookup is by the Java item produced from
 * each Bedrock creative entry, because ViaBedrock still cannot translate arbitrary
 * Java items back to Bedrock.
 */
public class CreativeContentCache extends StoredObject {

    private final List<Entry> entries = new ArrayList<>();

    public CreativeContentCache(final UserConnection user) {
        super(user);
    }

    public void replace(final List<Entry> next) {
        this.entries.clear();
        if (next != null) {
            this.entries.addAll(next);
        }
    }

    public void clear() {
        this.entries.clear();
    }

    public int size() {
        return this.entries.size();
    }

    public Integer findNetId(final BedrockItem item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        Integer fallback = null;
        for (final Entry entry : this.entries) {
            if (!entry.item().isDifferent(item)) {
                return entry.netId();
            }
            // MOT CraftCreative looks up by creative netId, not NBT. Java creative
            // clicks often reverse-map without matching blockRuntimeId/tag, so id+data
            // is enough to spawn the same creative entry.
            if (fallback == null && sameCreativeIdentity(entry.item(), item)) {
                fallback = entry.netId();
            }
        }
        return fallback;
    }

    public Integer findExactNetId(final BedrockItem item) {
        if (item == null || item.isEmpty()) return null;
        for (final Entry entry : this.entries) {
            if (!entry.item().isDifferent(item)) return entry.netId();
        }
        return null;
    }

    private static boolean sameCreativeIdentity(final BedrockItem cached, final BedrockItem requested) {
        return cached.identifier() == requested.identifier() && cached.data() == requested.data();
    }

    public BedrockItem itemByNetId(final int netId) {
        for (final Entry entry : this.entries) {
            if (entry.netId() == netId) {
                return entry.item().copy();
            }
        }
        return null;
    }

    public Integer findNetIdForJavaItem(final ItemRewriter itemRewriter, final Item javaItem) {
        if (itemRewriter == null || javaItem == null || javaItem.isEmpty()) {
            return null;
        }
        Integer fallback = null;
        for (final Entry entry : this.entries) {
            final Item mapped = itemRewriter.javaItem(entry.item().copy());
            if (mapped == null || mapped.isEmpty() || mapped.identifier() != javaItem.identifier()) {
                continue;
            }
            if (sameComponents(mapped, javaItem)) {
                return entry.netId();
            }
            if (fallback == null) {
                fallback = entry.netId();
            }
        }
        return fallback;
    }

    /**
     * Removes the viabedrock:bedrock_item shadow component from an item's CustomData.
     * This is used when comparing items to ignore shadow differences that don't affect
     * the item's core functionality.
     *
     * @param item the item to filter
     * @return the item with the shadow component removed, or the original item if no changes were made
     */
    private static Item removeBedrockItemShadow(final Item item) {
        // Handle null cases
        if (item == null || item.dataContainer() == null) {
            return item;
        }

        final StructuredDataContainer data = item.dataContainer();
        final CompoundTag customData = data.get(StructuredDataKey.CUSTOM_DATA);

        // If no CustomData or no shadow, return original item
        if (customData == null || !customData.contains("viabedrock:bedrock_item")) {
            return item;
        }

        // Create a copy of the item with the shadow removed
        final Item itemCopy = item.copy();
        final StructuredDataContainer dataCopy = itemCopy.dataContainer();
        final CompoundTag customDataCopy = customData.copy();
        customDataCopy.remove("viabedrock:bedrock_item");

        // If CustomData is now empty, remove it entirely
        if (customDataCopy.isEmpty()) {
            dataCopy.remove(StructuredDataKey.CUSTOM_DATA);
        } else {
            dataCopy.set(StructuredDataKey.CUSTOM_DATA, customDataCopy);
        }

        return itemCopy;
    }

    private static boolean sameComponents(final Item left, final Item right) {
        // Filter out shadow components before comparison
        final Item leftFiltered = removeBedrockItemShadow(left);
        final Item rightFiltered = removeBedrockItemShadow(right);
        
        if (leftFiltered.dataContainer() == null || rightFiltered.dataContainer() == null) {
            return leftFiltered.dataContainer() == rightFiltered.dataContainer();
        }
        return leftFiltered.dataContainer().equals(rightFiltered.dataContainer());
    }

    public record Entry(int netId, BedrockItem item) {
    }

}
