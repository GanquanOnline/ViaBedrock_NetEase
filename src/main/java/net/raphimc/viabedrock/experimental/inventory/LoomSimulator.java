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
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java 的织布机结果点击会变成 MOT {@code CraftLoom}。图案短代码来自槽位 2 的图案物品（{@code creeper} → {@code cre}）。
 * Java 通过配方书显示 id 选择的仅条纹图案保持不映射。
 */
public final class LoomSimulator {

    static final int TODO_LOOM_RESULT = -9;
    static final int JAVA_RESULT_SLOT = 3;

    private static final Map<String, String> PATTERN_ITEM_TO_MOT = Map.of(
            "minecraft:creeper_banner_pattern", "cre",
            "minecraft:skull_banner_pattern", "sku",
            "minecraft:flower_banner_pattern", "flo",
            "minecraft:mojang_banner_pattern", "moj",
            "minecraft:flow_banner_pattern", "flw",
            "minecraft:guster_banner_pattern", "gus",
            "minecraft:field_masoned_banner_pattern", "bri",
            "minecraft:bordure_indented_banner_pattern", "cbo"
    );

    private LoomSimulator() {
    }

    public static boolean isLoom(final Container container) {
        return container != null && container.type() == ContainerType.LOOM;
    }

    public static List<InventoryActionData> simulateTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isLoom(container)) {
            return null;
        }
        final BedrockItem banner = container.getItem(0);
        final BedrockItem dye = container.getItem(1);
        if (banner == null || banner.isEmpty() || dye == null || dye.isEmpty()) {
            return null;
        }
        final BedrockItem cursor = SlotMapper.getCursorItem(tracker);
        if (cursor != null && !cursor.isEmpty()) {
            return null;
        }
        final BedrockItem result = banner.copy();
        final List<InventoryActionData> actions = new ArrayList<>();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_LOOM_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, result, BedrockItem.empty()));
        actions.add(consumeOne(container, 0, banner));
        actions.add(consumeOne(container, 1, dye));
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), result.copy()));
        return actions;
    }

    public static boolean isTakeResult(final List<InventoryActionData> actions) {
        return CartographySimulator.hasTodoMarker(actions, TODO_LOOM_RESULT)
                && CartographySimulator.hasCursorTakeResult(actions);
    }

    static boolean isQuickMoveResult(final List<InventoryActionData> actions) {
        return CartographySimulator.isQuickMoveResult(actions, TODO_LOOM_RESULT);
    }

    static ItemStackRequestEncoder.EncodedRequest encodeQuickMoveResult(
            final UserConnection user, final InventoryTracker tracker, final List<InventoryActionData> actions,
            final boolean emulateNetEase, final int protocol) {
        final Container container = tracker.getCurrentContainer();
        if (!isLoom(container) || !isQuickMoveResult(actions)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockItem banner = container.getItem(0);
        final BedrockItem dye = container.getItem(1);
        if (banner == null || banner.isEmpty() || dye == null || dye.isEmpty()) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        final String patternId = patternId(container.getItem(2), itemRewriter);
        final int takeCount = CartographySimulator.destinationAmount(actions);
        if (patternId == null || takeCount <= 0) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        return ItemStackRequestEncoder.encodeLoomApplyToDestinations(tracker, 1, 1, takeCount, patternId, actions, emulateNetEase, protocol);
    }

    public static ItemStackRequestEncoder.EncodedRequest encodeTakeResult(final UserConnection user, final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isLoom(container)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockItem banner = container.getItem(0);
        final BedrockItem dye = container.getItem(1);
        if (banner == null || banner.isEmpty() || dye == null || dye.isEmpty()) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        final String patternId = patternId(container.getItem(2), itemRewriter);
        if (patternId == null) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        return ItemStackRequestEncoder.encodeLoomApply(tracker, 1, 1, 1, patternId);
    }

    static String patternId(final BedrockItem patternItem, final ItemRewriter itemRewriter) {
        if (patternItem == null || patternItem.isEmpty()) {
            return "";
        }
        if (itemRewriter == null) {
            return null;
        }
        final String identifier = itemRewriter.bedrockIdentifier(patternItem);
        if (identifier == null) {
            return null;
        }
        return PATTERN_ITEM_TO_MOT.get(identifier);
    }

    private static InventoryActionData consumeOne(final Container container, final int slot, final BedrockItem item) {
        final BedrockItem leftover = item.copy();
        leftover.setAmount(item.amount() - 1);
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                slot, item.copy(), leftover.amount() <= 0 ? BedrockItem.empty() : leftover);
    }
}
