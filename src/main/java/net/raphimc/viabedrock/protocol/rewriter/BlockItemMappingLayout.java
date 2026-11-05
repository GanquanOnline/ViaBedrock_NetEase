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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.viaversion.libs.fastutil.ints.IntSortedSet;
import net.raphimc.viabedrock.api.model.BedrockBlockState;
import net.raphimc.viabedrock.experimental.custommapping.RuntimeProjectionBuilder;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemVersion;
import net.raphimc.viabedrock.protocol.model.BlockProperties;
import net.raphimc.viabedrock.protocol.model.ItemEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用于基岩方块物品 → Java 物品映射的安全查找辅助方法。
 * <p>
 * Nukkit-MOT 860 的 START_GAME 调色板（以及 {@code askyblockwar:*} /
 * {@code minecraft:cinnabar_*} 墙体等网易附加项）可能在
 * {@code bedrockToJavaBlockItems} 中列出某个标识符，而会话的
 * {@code validBlockStates} 表却没有对应的运行时 ID。官方 ViaBedrock
 * 随后会对 null 调用 {@code IntSortedSet.firstInt()}，把 Java 1.21.11
 * 从 {@code ADD_PLAYER} / {@code MOB_EQUIPMENT} 踢出。缺失的调色板必须
 * 回退到 paper/自定义物品路径，而不是抛异常。
 */
public final class BlockItemMappingLayout {

    private BlockItemMappingLayout() {
    }

    /**
     * 运行时 ID 为 0 的方块物品的默认方块运行时 ID。
     * {@code 0} 表示「未知」，调用方不得把它当作已映射的方块状态。
     */
    public static int fallbackBlockRuntimeId(final IntSortedSet validBlockStates) {
        if (validBlockStates == null || validBlockStates.isEmpty()) {
            return 0;
        }
        return validBlockStates.firstInt();
    }

    /**
     * 会话调色板缺失或为空时，保留 MOT 860 的线上运行时 ID。
     * 官方 ViaBedrock 在物品解码时对该空集合调用 {@code firstInt()}
     * 并把 Java 1.21.11 踢出。调色板非空时，未知状态仍会被
     * 重映射到第一个已知的运行时 ID。
     */
    public static int sanitizeBlockRuntimeId(final IntSortedSet validBlockStates, final int blockRuntimeId) {
        if (validBlockStates == null || validBlockStates.isEmpty() || validBlockStates.contains(blockRuntimeId)) {
            return blockRuntimeId;
        }
        return validBlockStates.firstInt();
    }

    /**
     * MOT 的自定义方块物品使用 {@code itemId = 255 - nukkitId}，且从不出现
     * 在 ITEM_REGISTRY 中。物品栏槽位仍携带这个负数 varint。
     */
    public static int customBlockItemId(final int nukkitBlockId) {
        return 255 - nukkitBlockId;
    }

    public static Integer customBlockItemId(final CompoundTag blockProperties) {
        if (!(blockProperties != null && blockProperties.get("vanilla_block_data") instanceof CompoundTag vanillaBlockData)) {
            return null;
        }
        if (!(vanillaBlockData.get("block_id") instanceof NumberTag blockIdTag)) {
            return null;
        }
        return customBlockItemId(blockIdTag.asInt());
    }

    public static ItemEntry[] mergeCustomBlockItems(final ItemEntry[] itemEntries, final BlockProperties[] blockProperties) {
        if (blockProperties == null || blockProperties.length == 0) {
            return itemEntries != null ? itemEntries : new ItemEntry[0];
        }
        final Set<String> vanillaIdentifiers = BedrockProtocol.MAPPINGS.getBedrockBlockStates().stream()
                .map(BedrockBlockState::namespacedIdentifier)
                .collect(Collectors.toSet());
        return mergeCustomBlockItems(itemEntries, RuntimeProjectionBuilder.collectEffectiveCustomBlockProperties(blockProperties, vanillaIdentifiers));
    }

    public static ItemEntry[] mergeCustomBlockItems(final ItemEntry[] itemEntries, final Map<String, CompoundTag> customBlockProperties) {
        if (customBlockProperties == null || customBlockProperties.isEmpty()) {
            return itemEntries != null ? itemEntries : new ItemEntry[0];
        }

        final List<ItemEntry> merged = new ArrayList<>();
        if (itemEntries != null) {
            merged.addAll(Arrays.asList(itemEntries));
        }

        final Set<String> knownIdentifiers = merged.stream()
                .map(entry -> entry.identifier().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final Set<Integer> knownIds = merged.stream()
                .map(ItemEntry::id)
                .collect(Collectors.toCollection(HashSet::new));

        for (Map.Entry<String, CompoundTag> customBlock : customBlockProperties.entrySet()) {
            final String identifier = customBlock.getKey();
            if (knownIdentifiers.contains(identifier)) {
                continue;
            }
            final Integer itemId = customBlockItemId(customBlock.getValue());
            if (itemId == null || knownIds.contains(itemId)) {
                continue;
            }
            knownIdentifiers.add(identifier);
            knownIds.add(itemId);
            merged.add(new ItemEntry(identifier, itemId, false, ItemVersion.None, null));
        }
        return merged.toArray(ItemEntry[]::new);
    }

}
