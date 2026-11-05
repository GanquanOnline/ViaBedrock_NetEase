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
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry.SmithingRecipe;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * Java 的锻造台结果点击会变成 MOT {@code CraftRecipe}。纹饰的 networkId 硬编码为 {@code 1}；下界合金升级使用 CRAFTING_DATA 中匹配的配方。
 * 依次 Consume 装备 / 材料 / 模板，然后 Take HUD 的 created-output 50。
 */
public final class SmithingSimulator {

    static final int TODO_SMITHING_RESULT = -11;
    static final int JAVA_RESULT_SLOT = 3;
    static final int TRIM_NETWORK_ID = 1;

    private SmithingSimulator() {
    }

    public static boolean isSmithing(final Container container) {
        return container != null && container.type() == ContainerType.SMITHING_TABLE;
    }

    public static List<InventoryActionData> simulateTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isSmithing(container)) {
            return null;
        }
        final int recipeNetworkId = recipeNetworkId(tracker, container);
        if (recipeNetworkId <= 0) {
            return null;
        }
        final BedrockItem equipment = container.getItem(0);
        if (equipment == null || equipment.isEmpty()) {
            return null;
        }
        final BedrockItem cursor = SlotMapper.getCursorItem(tracker);
        if (cursor != null && !cursor.isEmpty()) {
            return null;
        }
        final BedrockItem result = equipment.copy();
        final List<InventoryActionData> actions = new ArrayList<>();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_SMITHING_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, result, BedrockItem.empty()));
        actions.add(consumeOne(container, 0, equipment));
        addConsumeIfPresent(actions, container, 1);
        addConsumeIfPresent(actions, container, 2);
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), result.copy()));
        return actions;
    }

    public static boolean isTakeResult(final List<InventoryActionData> actions) {
        return CartographySimulator.hasTodoMarker(actions, TODO_SMITHING_RESULT)
                && CartographySimulator.hasCursorTakeResult(actions);
    }

    static boolean isQuickMoveResult(final List<InventoryActionData> actions) {
        return CartographySimulator.isQuickMoveResult(actions, TODO_SMITHING_RESULT);
    }

    static ItemStackRequestEncoder.EncodedRequest encodeQuickMoveResult(
            final InventoryTracker tracker, final List<InventoryActionData> actions,
            final boolean emulateNetEase, final int protocol) {
        final Container container = tracker.getCurrentContainer();
        if (!isSmithing(container) || !isQuickMoveResult(actions)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final int recipeNetworkId = recipeNetworkId(tracker, container);
        final int takeCount = CartographySimulator.destinationAmount(actions);
        if (recipeNetworkId <= 0 || takeCount <= 0) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        return ItemStackRequestEncoder.encodeSmithingApplyToDestinations(tracker, recipeNetworkId, takeCount, actions, emulateNetEase, protocol);
    }

    public static ItemStackRequestEncoder.EncodedRequest encodeTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isSmithing(container)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final int recipeNetworkId = recipeNetworkId(tracker, container);
        if (recipeNetworkId <= 0) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        return ItemStackRequestEncoder.encodeSmithingApply(tracker, recipeNetworkId);
    }

    private static int recipeNetworkId(final InventoryTracker tracker, final Container container) {
        if (tracker == null || tracker.user() == null) {
            return -1;
        }
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        if (registry == null) {
            return -1;
        }
        final SmithingRecipe transform = registry.matchSmithingTransform(
                container.getItem(0), container.getItem(1), container.getItem(2));
        if (transform != null) {
            return transform.networkId();
        }
        final BedrockItem template = container.getItem(2);
        final BedrockItem equipment = container.getItem(0);
        final BedrockItem ingredient = container.getItem(1);
        if (template != null && !template.isEmpty() && equipment != null && !equipment.isEmpty()
                && ingredient != null && !ingredient.isEmpty()) {
            return TRIM_NETWORK_ID;
        }
        return -1;
    }

    private static void addConsumeIfPresent(final List<InventoryActionData> actions, final Container container, final int slot) {
        final BedrockItem item = container.getItem(slot);
        if (item != null && !item.isEmpty()) {
            actions.add(consumeOne(container, slot, item));
        }
    }

    private static InventoryActionData consumeOne(final Container container, final int slot, final BedrockItem item) {
        final BedrockItem leftover = item.copy();
        leftover.setAmount(item.amount() - 1);
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                slot, item.copy(), leftover.amount() <= 0 ? BedrockItem.empty() : leftover);
    }
}
