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
package net.raphimc.viabedrock.experimental.inventory;

import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry.RecipeMatch;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.*;

public class CraftingSimulator {

    // Nukkit SOURCE_TODO windowId values
    private static final int TODO_USE_INGREDIENT = -5;
    private static final int TODO_CRAFTING_RESULT = -4;
    private static final int MAX_WIRE_ITEM_AMOUNT = 0xFFFF;

    /**
     * Simulates a PICKUP on the crafting output slot (left click to take crafting result to cursor).
     * Returns the list of Bedrock InventoryActionData, or null if no recipe matches.
     */
    public static List<InventoryActionData> simulateCraftPickup(final boolean is3x3, final InventoryTracker tracker) {
        return simulateCraftPickup(is3x3, tracker, JavaItemStackLimits.forTracker(tracker));
    }

    static List<InventoryActionData> simulateCraftPickup(final boolean is3x3, final InventoryTracker tracker,
                                                         final JavaItemStackLimits.Resolver stackLimits) {
        final BedrockItem[] gridItems = getGridItems(is3x3, tracker);
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        final RecipeMatch match = registry.matchRecipeWithPlacement(gridItems, is3x3);
        if (match == null) {
            return null;
        }
        final BedrockRecipe recipe = match.recipe();

        final BedrockItem cursorItem = SlotMapper.getCursorItem(tracker);
        final BedrockItem primaryOutput = recipe.primaryOutput().copy();
        final int maxStackSize = stackLimits.maxStackSize(primaryOutput);
        if (maxStackSize <= 0 || primaryOutput.amount() > maxStackSize) {
            return null;
        }

        // If cursor already has items, check if we can stack
        if (!cursorItem.isEmpty()) {
            if (cursorItem.isDifferent(primaryOutput)) {
                return Collections.emptyList(); // Can't take result — cursor has different item
            }
            if (cursorItem.amount() > maxStackSize || cursorItem.amount() + primaryOutput.amount() > maxStackSize) {
                return Collections.emptyList(); // Can't take result — would exceed max stack
            }
        }

        final BedrockItem[] inventoryItems = copyInventoryItems(tracker);
        if (!canFitExtraOutputs(recipe, 1, inventoryItems, stackLimits)) return Collections.emptyList();

        final List<InventoryActionData> actions = new ArrayList<>();

        // ACTION 1: per grid slot, SOURCE_TODO(-5 USE_INGREDIENT) + an explicit grid SlotChange that
        // decrements the slot by 1. This mirrors the real Bedrock client packets so the server's
        // CraftingTransaction collects the inputs and validates (single merged -5 with slot=0 and no grid
        // change made canExecute fail, leaving a stale transaction that corrupted the next craft).
        addGridConsumption(actions, is3x3, gridItems, match, 1);

        if (!addExtraOutputs(actions, recipe, 1, inventoryItems, stackLimits)) return null;

        // ACTION 2: SOURCE_TODO(-4) — set primaryOutput
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_CRAFTING_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, primaryOutput, BedrockItem.empty()
        ));

        // ACTION 3: cursor update — place result in cursor
        final BedrockItem newCursor;
        if (cursorItem.isEmpty()) {
            newCursor = primaryOutput.copy();
        } else {
            newCursor = cursorItem.copy();
            newCursor.setAmount(cursorItem.amount() + primaryOutput.amount());
        }
        actions.add(cursorAction(cursorItem, newCursor));

        return actions;
    }

    /**
     * Simulates a QUICK_MOVE (Shift+Click) on the crafting output slot.
     * The crafted item goes directly into the player's inventory.
     * Returns the list of Bedrock InventoryActionData, or null if no recipe matches.
     */
    public static List<InventoryActionData> simulateCraftQuickMove(final boolean is3x3, final InventoryTracker tracker) {
        return simulateCraftQuickMove(is3x3, tracker, JavaItemStackLimits.forTracker(tracker));
    }

    static List<InventoryActionData> simulateCraftQuickMove(final boolean is3x3, final InventoryTracker tracker,
                                                            final JavaItemStackLimits.Resolver stackLimits) {
        final BedrockItem[] gridItems = getGridItems(is3x3, tracker);
        final RecipeRegistry registry = tracker.user().get(RecipeRegistry.class);
        final RecipeMatch match = registry.matchRecipeWithPlacement(gridItems, is3x3);
        if (match == null) {
            return null;
        }
        final BedrockRecipe recipe = match.recipe();

        final BedrockItem recipeOutput = recipe.primaryOutput().copy();
        final int maxStackSize = stackLimits.maxStackSize(recipeOutput);
        if (maxStackSize <= 0 || recipeOutput.amount() <= 0) {
            return null;
        }

        final BedrockItem[] inventoryItems = copyInventoryItems(tracker);
        int craftCount = registry.maxCraftMultiplier(match, gridItems);
        while (craftCount > 0 && !canFitOutputs(recipe, craftCount, inventoryItems, stackLimits)) craftCount--;
        if (craftCount <= 0) return null;

        final int primaryAmount = recipeOutput.amount() * craftCount;
        if (primaryAmount <= 0 || primaryAmount > MAX_WIRE_ITEM_AMOUNT) return null;
        for (final int ingredientCount : match.ingredientCounts()) {
            if ((long) ingredientCount * craftCount > MAX_WIRE_ITEM_AMOUNT) return null;
        }

        final List<InventoryActionData> actions = new ArrayList<>();
        addGridConsumption(actions, is3x3, gridItems, match, craftCount);

        final BedrockItem primaryOutput = recipeOutput.copy();
        primaryOutput.setAmount(primaryAmount);
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_CRAFTING_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, primaryOutput, BedrockItem.empty()
        ));
        if (!addOutputToInventory(actions, inventoryItems, primaryOutput, primaryAmount, maxStackSize)) return null;
        if (!addExtraOutputs(actions, recipe, craftCount, inventoryItems, stackLimits)) return null;

        return actions;
    }

    private static boolean canFitOutputs(final BedrockRecipe recipe, final int craftCount,
                                         final BedrockItem[] inventoryItems,
                                         final JavaItemStackLimits.Resolver stackLimits) {
        final BedrockItem[] simulated = copyItems(inventoryItems);
        final BedrockItem primaryOutput = recipe.primaryOutput();
        final int primaryLimit = stackLimits.maxStackSize(primaryOutput);
        if (primaryLimit <= 0 || !addOutputToInventory(null, simulated, primaryOutput,
                primaryOutput.amount() * craftCount, primaryLimit)) return false;
        return canFitExtraOutputs(recipe, craftCount, simulated, stackLimits);
    }

    private static boolean canFitExtraOutputs(final BedrockRecipe recipe, final int craftCount,
                                              final BedrockItem[] inventoryItems,
                                              final JavaItemStackLimits.Resolver stackLimits) {
        final BedrockItem[] simulated = copyItems(inventoryItems);
        for (final BedrockItem extraOutput : recipe.extraOutputs()) {
            if (extraOutput.isEmpty()) continue;
            final int extraLimit = stackLimits.maxStackSize(extraOutput);
            if (extraLimit <= 0 || !addOutputToInventory(null, simulated, extraOutput,
                    extraOutput.amount() * craftCount, extraLimit)) return false;
        }
        return true;
    }

    private static boolean addExtraOutputs(final List<InventoryActionData> actions, final BedrockRecipe recipe,
                                           final int craftCount, final BedrockItem[] inventoryItems,
                                           final JavaItemStackLimits.Resolver stackLimits) {
        int extraIndex = 0;
        for (final BedrockItem recipeExtra : recipe.extraOutputs()) {
            if (recipeExtra.isEmpty()) continue;
            final int maxStackSize = stackLimits.maxStackSize(recipeExtra);
            final int amount = recipeExtra.amount() * craftCount;
            if (maxStackSize <= 0 || amount <= 0 || amount > MAX_WIRE_ITEM_AMOUNT) return false;
            final BedrockItem extraOutput = recipeExtra.copy();
            extraOutput.setAmount(amount);
            if (actions != null) {
                actions.add(new InventoryActionData(
                        new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_USE_INGREDIENT, InventorySource_InventorySourceFlags.NoFlag),
                        extraIndex++, extraOutput, BedrockItem.empty()
                ));
            }
            if (!addOutputToInventory(actions, inventoryItems, extraOutput, amount, maxStackSize)) return false;
        }
        return true;
    }

    private static boolean addOutputToInventory(final List<InventoryActionData> actions,
                                                final BedrockItem[] inventoryItems, final BedrockItem output,
                                                final int amount, final int maxStackSize) {
        int remaining = amount;
        for (final int invSlot : quickMoveTargetSlots()) {
            if (remaining <= 0) break;
            final BedrockItem targetItem = inventoryItems[invSlot];
            if (targetItem.isEmpty() || targetItem.isDifferent(output) || targetItem.amount() >= maxStackSize) continue;
            final int moved = Math.min(remaining, maxStackSize - targetItem.amount());
            final BedrockItem newTarget = targetItem.copy();
            newTarget.setAmount(targetItem.amount() + moved);
            if (actions != null) actions.add(inventoryAction(invSlot, targetItem, newTarget));
            inventoryItems[invSlot] = newTarget;
            remaining -= moved;
        }
        for (final int invSlot : quickMoveTargetSlots()) {
            if (remaining <= 0) break;
            final BedrockItem targetItem = inventoryItems[invSlot];
            if (!targetItem.isEmpty()) continue;
            final int moved = Math.min(remaining, maxStackSize);
            final BedrockItem newTarget = output.copy();
            newTarget.setAmount(moved);
            if (actions != null) actions.add(inventoryAction(invSlot, targetItem, newTarget));
            inventoryItems[invSlot] = newTarget;
            remaining -= moved;
        }
        return remaining == 0;
    }

    /**
     * Reads the crafting grid contents from HUD container.
     * 2x2: HUD slots 28-31
     * 3x3: HUD slots 32-40
     */
    public static BedrockItem[] getGridItems(final boolean is3x3, final InventoryTracker tracker) {
        final int gridSize = is3x3 ? 9 : 4;
        final int startSlot = is3x3 ? 32 : 28;
        final BedrockItem[] gridItems = new BedrockItem[gridSize];
        for (int i = 0; i < gridSize; i++) {
            gridItems[i] = tracker.getHudContainer().getItem(startSlot + i);
        }
        return gridItems;
    }

    /**
     * For each non-empty crafting grid slot, emits the pair of actions a real Bedrock client sends when
     * crafting consumes items from that slot:
     *   1. SOURCE_TODO(-5 USE_INGREDIENT) with slot = grid-relative index (0-based), fromItem empty,
     *      toItem the consumed ingredient — this feeds the server's CraftingTransaction inputs.
     *   2. A ContainerInventory SlotChange on the HUD/UI container (id 124) at the absolute grid slot,
     *      decrementing the stack by the requested craft count (or clearing it) — the actual grid
     *      mutation the server validates.
     */
    private static void addGridConsumption(final List<InventoryActionData> actions, final boolean is3x3,
                                           final BedrockItem[] gridItems, final RecipeMatch match,
                                           final int multiplier) {
        final int startSlot = is3x3 ? 32 : 28;
        for (int i = 0; i < gridItems.length; i++) {
            final int hudSlot = startSlot + i;
            final BedrockItem gridItem = gridItems[i];
            final int perCraft = match.ingredientCount(i);
            if (gridItem.isEmpty() || perCraft <= 0) continue;

            final BedrockItem ingredient = gridItem.copy();
            final int consumed = perCraft * multiplier;
            if (consumed <= 0 || consumed > gridItem.amount() || consumed > MAX_WIRE_ITEM_AMOUNT) {
                throw new IllegalStateException("Invalid crafting ingredient count " + consumed + " for grid slot " + i);
            }
            ingredient.setAmount(consumed);
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_USE_INGREDIENT, InventorySource_InventorySourceFlags.NoFlag),
                    i, BedrockItem.empty(), ingredient
            ));

            final BedrockItem newGrid;
            if (gridItem.amount() > consumed) {
                newGrid = gridItem.copy();
                newGrid.setAmount(gridItem.amount() - consumed);
            } else {
                newGrid = BedrockItem.empty();
            }
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                    hudSlot, gridItem.copy(), newGrid
            ));
            gridItems[i] = newGrid;
        }
    }

    private static BedrockItem[] copyInventoryItems(final InventoryTracker tracker) {
        final BedrockItem[] items = new BedrockItem[tracker.getInventoryContainer().size()];
        for (int slot = 0; slot < items.length; slot++) {
            items[slot] = tracker.getInventoryContainer().getItem(slot).copy();
        }
        return items;
    }

    private static BedrockItem[] copyItems(final BedrockItem[] items) {
        final BedrockItem[] copy = new BedrockItem[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i].copy();
        return copy;
    }

    /**
     * Java Shift-click fills the main inventory first (9-35) then the hotbar (0-8).
     * EaseCation walks hotbar-first; MOT player inventory uses vanilla Java order.
     */
    private static int[] quickMoveTargetSlots() {
        final int[] slots = new int[36];
        int index = 0;
        for (int slot = 9; slot <= 35; slot++) slots[index++] = slot;
        for (int slot = 0; slot <= 8; slot++) slots[index++] = slot;
        return slots;
    }

    private static InventoryActionData inventoryAction(final int slot, final BedrockItem from, final BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_INVENTORY.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                slot, from.copy(), to.copy()
        );
    }

    private static InventoryActionData cursorAction(final BedrockItem from, final BedrockItem to) {
        return new InventoryActionData(
                new InventorySource(InventorySourceType.ContainerInventory, ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue(), InventorySource_InventorySourceFlags.NoFlag),
                0, from.copy(), to.copy()
        );
    }

}
