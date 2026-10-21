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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.inventory;

import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.test.StubUserConnection;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.storage.RecipeRegistry;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingSimulatorStackLimitsTest {

    private static final int OUTPUT_ID = 2;
    private static final int INGREDIENT_ID = 3;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private final InventoryTracker tracker = new InventoryTracker(this.user);
    private final RecipeRegistry recipes = new RecipeRegistry(this.user);

    CraftingSimulatorStackLimitsTest() {
        this.user.put(this.recipes);
    }

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void accumulatedUnsignedShortAmountIsNotTreatedAsEmpty() {
        final BedrockItem item = new BedrockItem(OUTPUT_ID, (short) 0, 256, null);

        assertEquals(256, item.amount());
        assertFalse(item.isEmpty());
    }

    @Test
    void fullArmorCursorDoesNotConsumeAnotherCraft() {
        this.prepareRecipe(1);
        this.tracker.getHudContainer().setItemSilent(0, item(OUTPUT_ID, 1));

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftPickup(
                false, this.tracker, ignored -> 1);

        assertNotNull(actions);
        assertTrue(actions.isEmpty());
        assertEquals(1, this.tracker.getHudContainer().getItem(28).amount());
        assertEquals(1, this.tracker.getHudContainer().getItem(0).amount());
    }

    @Test
    void quickMoveSplitsCraftOutputIntoLegalArmorStacks() {
        this.prepareRecipe(2);

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 1);

        assertNotNull(actions);
        final List<InventoryActionData> inventoryActions = inventoryActions(actions);
        assertEquals(2, inventoryActions.size());
        assertEquals(List.of(9, 10), inventoryActions.stream().map(InventoryActionData::slot).toList());
        assertTrue(inventoryActions.stream().allMatch(action -> action.toItem().amount() == 1));
    }

    @Test
    void quickMoveFailsAtomicallyWhenNoLegalTargetExists() {
        this.prepareRecipe(1);
        for (int slot = 0; slot < this.tracker.getInventoryContainer().size(); slot++) {
            this.tracker.getInventoryContainer().setItemSilent(slot, item(99, 1));
        }

        assertNull(CraftingSimulator.simulateCraftQuickMove(false, this.tracker, ignored -> 1));
        assertEquals(1, this.tracker.getHudContainer().getItem(28).amount());
    }

    @Test
    void quickMoveReducesCraftCountToWholeOutputsThatFit() {
        this.prepareRecipe(4, 16);
        for (int slot = 0; slot < this.tracker.getInventoryContainer().size(); slot++) {
            this.tracker.getInventoryContainer().setItemSilent(slot, item(99, 64));
        }
        this.tracker.getInventoryContainer().setItemSilent(9, item(OUTPUT_ID, 60));

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 64);

        assertNotNull(actions);
        assertEquals(1, ingredientAmount(actions));
        assertEquals(4, primaryOutputAmount(actions));
        assertEquals(64, inventoryActions(actions).getFirst().toItem().amount());
    }

    @Test
    void quickMoveAccumulatesAllCraftsAvailableInTheGrid() {
        this.prepareRecipe(4, 16);

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 64);

        assertNotNull(actions);
        assertEquals(0, actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.ContainerInventory)
                .filter(action -> action.source().containerId() == ContainerID.CONTAINER_ID_PLAYER_ONLY_UI.getValue())
                .filter(action -> action.slot() == 28)
                .findFirst().orElseThrow().toItem().amount());
        final List<InventoryActionData> inventoryActions = inventoryActions(actions);
        assertEquals(1, inventoryActions.size());
        assertEquals(64, inventoryActions.get(0).toItem().amount());
        assertEquals(16, actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.NonImplementedFeatureTODO)
                .filter(action -> action.source().containerId() == -5)
                .findFirst().orElseThrow().toItem().amount());
    }

    @Test
    void quickMoveCarriesMoreThan255OutputInOneUnsignedShortTransaction() {
        this.prepareRecipe(4, 64);

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 64);

        assertNotNull(actions);
        assertEquals(256, primaryOutputAmount(actions));
        assertEquals(64, ingredientAmount(actions));
        assertEquals(List.of(9, 10, 11, 12), actions.stream()
                .filter(CraftingSimulatorStackLimitsTest::isInventoryAction)
                .map(InventoryActionData::slot)
                .toList());
        assertEquals(64, actions.stream()
                .filter(CraftingSimulatorStackLimitsTest::isInventoryAction)
                .findFirst().orElseThrow().toItem().amount());
        assertConservesItems(actions);
    }

    @Test
    void shapedIngredientCountControlsCraftMultiplierAndConsumption() {
        this.recipes.clear();
        this.tracker.getHudContainer().clearItems();
        this.tracker.getInventoryContainer().clearItems();
        this.tracker.getHudContainer().setItemSilent(28, item(INGREDIENT_ID, 6));
        this.recipes.addRecipe(new BedrockRecipe(
                "test:counted",
                BedrockRecipe.RecipeType.SHAPED,
                1,
                1,
                List.of(new BedrockRecipe.RecipeIngredient(
                        INGREDIENT_ID,
                        BedrockRecipe.RecipeIngredient.ANY_DAMAGE,
                        2
                )),
                item(OUTPUT_ID, 1),
                List.of(),
                "crafting_table",
                0,
                2,
                false
        ));

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 64);

        assertNotNull(actions);
        assertEquals(6, ingredientAmount(actions));
        assertEquals(3, primaryOutputAmount(actions));
    }

    @Test
    void shapelessIngredientCountUsesOneGridSlotAndConsumesItsDeclaredAmount() {
        this.recipes.clear();
        this.tracker.getHudContainer().clearItems();
        this.tracker.getInventoryContainer().clearItems();
        this.tracker.getHudContainer().setItemSilent(28, item(INGREDIENT_ID, 6));
        this.recipes.addRecipe(new BedrockRecipe(
                "test:counted_shapeless",
                BedrockRecipe.RecipeType.SHAPELESS,
                0,
                0,
                List.of(new BedrockRecipe.RecipeIngredient(
                        INGREDIENT_ID,
                        BedrockRecipe.RecipeIngredient.ANY_DAMAGE,
                        2
                )),
                item(OUTPUT_ID, 1),
                List.of(),
                "crafting_table",
                0,
                4,
                false
        ));

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 64);

        assertNotNull(actions);
        assertEquals(6, ingredientAmount(actions));
        assertEquals(3, primaryOutputAmount(actions));
    }

    @Test
    void extraOutputsAreDeclaredAndPlacedWithoutBreakingConservation() {
        this.recipes.clear();
        this.tracker.getHudContainer().clearItems();
        this.tracker.getInventoryContainer().clearItems();
        this.tracker.getHudContainer().setItemSilent(28, item(INGREDIENT_ID, 2));
        this.recipes.addRecipe(new BedrockRecipe(
                "test:remainder",
                BedrockRecipe.RecipeType.SHAPELESS,
                0,
                0,
                List.of(new BedrockRecipe.RecipeIngredient(
                        INGREDIENT_ID,
                        BedrockRecipe.RecipeIngredient.ANY_DAMAGE,
                        1
                )),
                item(OUTPUT_ID, 1),
                List.of(item(4, 1)),
                "crafting_table",
                0,
                3,
                false
        ));

        final List<InventoryActionData> actions = CraftingSimulator.simulateCraftQuickMove(
                false, this.tracker, ignored -> 64);

        assertNotNull(actions);
        assertEquals(2, primaryOutputAmount(actions));
        assertEquals(2, actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.NonImplementedFeatureTODO)
                .filter(action -> action.source().containerId() == -5)
                .filter(action -> action.fromItem().identifier() == 4)
                .findFirst().orElseThrow().fromItem().amount());
        assertTrue(actions.stream()
                .filter(CraftingSimulatorStackLimitsTest::isInventoryAction)
                .anyMatch(action -> action.toItem().identifier() == 4 && action.toItem().amount() == 2));
        assertConservesItems(actions);
    }

    @Test
    void unresolvedCraftOutputLimitUsesAuthoritativeRollback() {
        this.prepareRecipe(1);

        assertNull(CraftingSimulator.simulateCraftPickup(
                false, this.tracker, ignored -> JavaItemStackLimits.UNSUPPORTED));
        assertEquals(1, this.tracker.getHudContainer().getItem(28).amount());
    }

    private void prepareRecipe(final int outputAmount) {
        prepareRecipe(outputAmount, 1);
    }

    private void prepareRecipe(final int outputAmount, final int ingredientAmount) {
        this.recipes.clear();
        this.tracker.getHudContainer().clearItems();
        this.tracker.getInventoryContainer().clearItems();
        this.tracker.getHudContainer().setItemSilent(28, item(INGREDIENT_ID, ingredientAmount));
        this.recipes.addRecipe(new BedrockRecipe(
                "test:armor",
                BedrockRecipe.RecipeType.SHAPELESS,
                0,
                0,
                List.of(new BedrockRecipe.RecipeIngredient(
                        INGREDIENT_ID,
                        BedrockRecipe.RecipeIngredient.ANY_DAMAGE,
                        1
                )),
                item(OUTPUT_ID, outputAmount),
                List.of(),
                "crafting_table",
                0,
                1,
                false
        ));
    }

    private static List<InventoryActionData> inventoryActions(final List<InventoryActionData> actions) {
        return actions.stream()
                .filter(CraftingSimulatorStackLimitsTest::isInventoryAction)
                .toList();
    }

    private static boolean isInventoryAction(final InventoryActionData action) {
        return action.source().type() == InventorySourceType.ContainerInventory
                && action.source().containerId() == ContainerID.CONTAINER_ID_INVENTORY.getValue();
    }

    private static int primaryOutputAmount(final List<InventoryActionData> actions) {
        return actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.NonImplementedFeatureTODO)
                .filter(action -> action.source().containerId() == -4)
                .findFirst().orElseThrow().fromItem().amount();
    }

    private static int ingredientAmount(final List<InventoryActionData> actions) {
        return actions.stream()
                .filter(action -> action.source().type() == InventorySourceType.NonImplementedFeatureTODO)
                .filter(action -> action.source().containerId() == -5)
                .filter(action -> action.fromItem().isEmpty())
                .findFirst().orElseThrow().toItem().amount();
    }

    private static void assertConservesItems(final List<InventoryActionData> actions) {
        final Map<Integer, Integer> fromAmounts = new HashMap<>();
        final Map<Integer, Integer> toAmounts = new HashMap<>();
        for (final InventoryActionData action : actions) {
            if (!action.fromItem().isEmpty()) {
                fromAmounts.merge(action.fromItem().identifier(), action.fromItem().amount(), Integer::sum);
            }
            if (!action.toItem().isEmpty()) {
                toAmounts.merge(action.toItem().identifier(), action.toItem().amount(), Integer::sum);
            }
        }
        assertEquals(fromAmounts, toAmounts);
    }

    private static BedrockItem item(final int id, final int amount) {
        return new BedrockItem(id, (short) 0, (byte) amount);
    }

}
