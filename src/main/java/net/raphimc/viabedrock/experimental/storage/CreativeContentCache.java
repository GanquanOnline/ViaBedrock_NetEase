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
 * 缓存基岩的 CREATIVE_CONTENT，使 Java 创造模式标签页的点击可以转换成
 * Nukkit 860 的 SAI CraftCreative 请求。查找键是由每个基岩创造条目
 * 生成的 Java 物品，因为 ViaBedrock 仍无法把任意的 Java 物品反向转换成基岩物品。
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
            // MOT 的 CraftCreative 按创造模式 netId 查找，而不是按 NBT。Java 的创造模式
            // 点击反向映射后常常匹配不上 blockRuntimeId/tag，所以用 id+data
            // 就足以生成同一个创造条目。
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
        // JE 的创造模式点击从不携带 viabedrock:bedrock_item 影子数据。优先还原
        // 基岩身份，以免药水/元数据变体都塌缩成同一 id 的第一个条目。
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
     * 把 JE 的创造模式点击与已映射的创造条目比较，同时忽略私有的
     * {@code viabedrock:bedrock_item} 影子数据。StructuredDataContainer 没有 equals()，
     * 因此原先的 container.equals() 路径从不匹配，每次生成都落到回退 /
     * 不支持分支。
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
