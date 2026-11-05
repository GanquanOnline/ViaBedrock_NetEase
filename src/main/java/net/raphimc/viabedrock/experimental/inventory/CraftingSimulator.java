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

    // Nukkit SOURCE_TODO 的 windowId 取值
    private static final int TODO_USE_INGREDIENT = -5;
    private static final int TODO_CRAFTING_RESULT = -4;
    private static final int MAX_WIRE_ITEM_AMOUNT = 0xFFFF;

    /**
     * 在合成输出槽上模拟一次 PICKUP（左键把合成结果拿到光标）。
     * 返回基岩 InventoryActionData 列表；若无配方的匹配则返回 null。
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

        // 如果光标上已有物品，检查能否堆叠
        if (!cursorItem.isEmpty()) {
            if (cursorItem.isDifferent(primaryOutput)) {
                return Collections.emptyList(); // 无法取走结果 — 光标上是不同的物品
            }
            if (cursorItem.amount() > maxStackSize || cursorItem.amount() + primaryOutput.amount() > maxStackSize) {
                return Collections.emptyList(); // 无法取走结果 — 会超过最大堆叠
            }
        }

        final BedrockItem[] inventoryItems = copyInventoryItems(tracker);
        if (!canFitExtraOutputs(recipe, 1, inventoryItems, stackLimits)) return Collections.emptyList();

        final List<InventoryActionData> actions = new ArrayList<>();

        // 操作 1：对每个合成网格槽位，发送 SOURCE_TODO（-5 USE_INGREDIENT）加一个显式的网格 SlotChange，使该槽位减 1。
        // 这与真实基岩客户端的数据包一致，服务器端的 CraftingTransaction 才能收集这些输入并通过校验
        // （把 -5 合并成单条、slot=0 且没有网格变化，会让 canExecute 失败，留下一个过期事务并污染下一次合成）。
        addGridConsumption(actions, is3x3, gridItems, match, 1);

        if (!addExtraOutputs(actions, recipe, 1, inventoryItems, stackLimits)) return null;

        // 操作 2：SOURCE_TODO(-4) → 设置 primaryOutput
        actions.add(new InventoryActionData(
                new InventorySource(InventorySourceType.NonImplementedFeatureTODO, TODO_CRAFTING_RESULT, InventorySource_InventorySourceFlags.NoFlag),
                0, primaryOutput, BedrockItem.empty()
        ));

        // 操作 3：更新光标 → 把结果放入光标
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
     * 在合成输出槽上模拟一次 QUICK_MOVE（Shift+点击）。
     * 合成出的物品直接进入玩家物品栏。
     * 返回基岩 InventoryActionData 列表；若无配方的匹配则返回 null。
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
     * 从 HUD 容器读取合成网格内容。
     * 2x2：HUD 槽位 28-31
     * 3x3：HUD 槽位 32-40
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
     * 对每个非空的合成网格槽位，发出真实基岩客户端在合成消耗该槽位物品时会发送的两个动作：
     * 1. SOURCE_TODO（-5 USE_INGREDIENT），slot = 网格内的相对索引（从 0 开始），fromItem 为空，toItem 为被消耗的材料 — 这会喂给服务器的 CraftingTransaction 输入。
     * 2. 在 HUD/UI 容器（id 124）上对绝对网格槽位发一个 ContainerInventory SlotChange，按请求的合成数量扣减该堆叠（或清空它）— 这是服务器实际校验的网格变更。
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
     * Java 的 Shift+点击先填主物品栏（9-35），再填快捷栏（0-8）。
     * EaseCation 先走快捷栏；MOT 玩家物品栏使用原版 Java 顺序。
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
