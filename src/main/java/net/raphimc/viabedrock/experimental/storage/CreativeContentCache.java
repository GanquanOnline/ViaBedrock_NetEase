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
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        // JE creative clicks never carry viabedrock:bedrock_item shadow. Prefer restoring the
        // Bedrock identity first so potion/meta variants do not collapse to the first same id.
        final BedrockItem restored = itemRewriter.bedrockItem(javaItem);
        if (restored != null && !restored.isEmpty()) {
            final Integer exact = this.findExactNetId(restored);
            if (exact != null) {
                return exact;
            }
            final Integer identity = this.findNetId(restored);
            if (identity != null) {
                return identity;
            }
        }
        Integer fallback = null;
        for (final Entry entry : this.entries) {
            final Item mapped = itemRewriter.javaItem(entry.item().copy());
            if (mapped == null || mapped.isEmpty() || mapped.identifier() != javaItem.identifier()) {
                continue;
            }
            if (sameEffectiveComponents(mapped, javaItem)) {
                return entry.netId();
            }
            if (fallback == null) {
                fallback = entry.netId();
            }
        }
        return fallback;
    }

    /**
     * Compare JE creative clicks against mapped creative entries while ignoring the private
     * {@code viabedrock:bedrock_item} shadow. StructuredDataContainer has no equals(), so the
     * previous container.equals() path never matched and every spawn fell through to fallback /
     * unsupported.
     */
    static boolean sameEffectiveComponents(final Item left, final Item right) {
        final StructuredDataContainer leftData = safeDataContainer(left);
        final StructuredDataContainer rightData = safeDataContainer(right);
        if (leftData == null || rightData == null) {
            return leftData == rightData;
        }
        return sameEffectiveData(leftData, rightData);
    }

    private static StructuredDataContainer safeDataContainer(final Item item) {
        if (item == null) {
            return null;
        }
        try {
            return item.dataContainer();
        } catch (final UnsupportedOperationException ignored) {
            return null;
        }
    }

    private static boolean sameEffectiveData(final StructuredDataContainer left, final StructuredDataContainer right) {
        final Map<StructuredDataKey<?>, StructuredData<?>> leftMap = left.data();
        final Map<StructuredDataKey<?>, StructuredData<?>> rightMap = right.data();
        final boolean leftHasCustom = leftMap.containsKey(StructuredDataKey.CUSTOM_DATA);
        final boolean rightHasCustom = rightMap.containsKey(StructuredDataKey.CUSTOM_DATA);
        final int leftSize = leftMap.size() - (leftHasCustom ? 1 : 0);
        final int rightSize = rightMap.size() - (rightHasCustom ? 1 : 0);
        if (leftSize != rightSize) {
            return false;
        }
        for (final Map.Entry<StructuredDataKey<?>, StructuredData<?>> entry : leftMap.entrySet()) {
            final StructuredDataKey<?> key = entry.getKey();
            if (key == StructuredDataKey.CUSTOM_DATA) {
                continue;
            }
            final StructuredData<?> other = rightMap.get(key);
            if (!Objects.equals(entry.getValue(), other)) {
                return false;
            }
        }
        return sameEffectiveCustomData(left.get(StructuredDataKey.CUSTOM_DATA), right.get(StructuredDataKey.CUSTOM_DATA));
    }

    private static boolean sameEffectiveCustomData(final CompoundTag left, final CompoundTag right) {
        final CompoundTag leftEffective = withoutBedrockItemShadow(left);
        final CompoundTag rightEffective = withoutBedrockItemShadow(right);
        if (leftEffective == null || rightEffective == null) {
            return leftEffective == rightEffective;
        }
        return leftEffective.equals(rightEffective);
    }

    private static CompoundTag withoutBedrockItemShadow(final CompoundTag customData) {
        if (customData == null) {
            return null;
        }
        if (!customData.contains("viabedrock:bedrock_item")) {
            return customData.isEmpty() ? null : customData;
        }
        if (customData.size() == 1) {
            return null;
        }
        final CompoundTag copy = customData.copy();
        copy.remove("viabedrock:bedrock_item");
        return copy.isEmpty() ? null : copy;
    }

    public record Entry(int netId, BedrockItem item) {
    }

}
