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
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
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
 * 当 CRAFTING_DATA 中针对该输入恰好只有一条 {@code tag=stonecutter} 配方时，Java 的切石机结果点击会变成 MOT {@code CraftRecipe}。
 * Java 的配方书显示 id 不是基岩的 networkId，因此有歧义的输入保持不映射。
 */
public final class StonecutterSimulator {

    static final int TODO_STONECUTTER_RESULT = -10;
    static final int JAVA_RESULT_SLOT = 1;

    private StonecutterSimulator() {
    }

    public static boolean isStonecutter(final Container container) {
        return container != null && container.type() == ContainerType.STONECUTTER;
    }

    public static List<InventoryActionData> simulateTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isStonecutter(container)) {
            return null;
        }
        final BedrockRecipe recipe = match(tracker, container);
        if (recipe == null) {
            return null;
        }
        final BedrockItem input = container.getItem(0);
        if (input == null || input.isEmpty()) {
            return null;
        }
        final BedrockItem cursor = SlotMapper.getCursorItem(tracker);
        if (cursor != null && !cursor.isEmpty()) {
            return null;
        }
        final int consume = consumeCount(recipe);
        if (consume <= 0 || input.amount() < consume) {
            return null;
        }
        final BedrockItem result = recipe.primaryOutput() != null && !recipe.primaryOutput().isEmpty()
                ? recipe.primaryOutput().copy() : input.copy();
        final List<InventoryActionData> actions = new ArrayList<>();
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_STONECUTTER_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, result, BedrockItem.empty()));
        final BedrockItem leftover = input.copy();
        leftover.setAmount(input.amount() - consume);
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, container.containerId(), InventorySource_InventorySourceFlags.NoFlag),
                0, input.copy(), leftover.amount() <= 0 ? BedrockItem.empty() : leftover));
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, BedrockItem.empty(), result.copy()));
        return actions;
    }

    public static boolean isTakeResult(final List<InventoryActionData> actions) {
        return CartographySimulator.hasTodoMarker(actions, TODO_STONECUTTER_RESULT)
                && CartographySimulator.hasCursorTakeResult(actions);
    }

    static boolean isQuickMoveResult(final List<InventoryActionData> actions) {
        return CartographySimulator.isQuickMoveResult(actions, TODO_STONECUTTER_RESULT);
    }

    static ItemStackRequestEncoder.EncodedRequest encodeQuickMoveResult(
            final InventoryTracker tracker, final List<InventoryActionData> actions,
            final boolean emulateNetEase, final int protocol) {
        final Container container = tracker.getCurrentContainer();
        if (!isStonecutter(container) || !isQuickMoveResult(actions)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockRecipe recipe = match(tracker, container);
        if (recipe == null) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final int takeCount = CartographySimulator.destinationAmount(actions);
        if (takeCount <= 0) return ItemStackRequestEncoder.EncodedRequest.notSupported();
        return ItemStackRequestEncoder.encodeStonecutterApplyToDestinations(
                tracker, recipe.networkId(), consumeCount(recipe), takeCount, actions, emulateNetEase, protocol);
    }

    public static ItemStackRequestEncoder.EncodedRequest encodeTakeResult(final InventoryTracker tracker) {
        final Container container = tracker.getCurrentContainer();
        if (!isStonecutter(container)) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        final BedrockRecipe recipe = match(tracker, container);
        if (recipe == null) {
            return ItemStackRequestEncoder.EncodedRequest.notSupported();
        }
        return ItemStackRequestEncoder.encodeStonecutterApply(
                tracker, recipe.networkId(), consumeCount(recipe), 1);
    }

    private static BedrockRecipe match(final InventoryTracker tracker, final Container container) {
        if (tracker == null || tracker.user() == null) {
            return null;
        }
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        if (registry == null) {
            return null;
        }
        return registry.matchStonecutter(container.getItem(0));
    }

    private static int consumeCount(final BedrockRecipe recipe) {
        if (recipe.ingredients() == null || recipe.ingredients().isEmpty()) {
            return 1;
        }
        return Math.max(1, recipe.ingredients().get(0).count());
    }
}
