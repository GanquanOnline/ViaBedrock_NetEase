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
package net.raphimc.viabedrock.experimental.storage;

import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecipeRegistryCraftingMatchTest {

    private static final int INGREDIENT_ID = 3;
    private static final int OUTPUT_ID = 2;

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private final RecipeRegistry recipes = new RecipeRegistry(this.user);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void stonecutterRecipesAreNotMatchedOnTheCraftingGrid() {
        this.recipes.addRecipe(stonecutterRecipe());

        assertNull(this.recipes.matchRecipe(new BedrockItem[]{item(INGREDIENT_ID, 1), BedrockItem.empty(), BedrockItem.empty(), BedrockItem.empty()}, false));
        assertNotNull(this.recipes.matchStonecutter(item(INGREDIENT_ID, 1)));
    }

    @Test
    void smithingTableRecipesAreNotMatchedOnTheCraftingGrid() {
        this.recipes.addRecipe(new BedrockRecipe(
                "test:smithing",
                BedrockRecipe.RecipeType.SHAPELESS,
                0,
                0,
                List.of(new BedrockRecipe.RecipeIngredient(INGREDIENT_ID, BedrockRecipe.RecipeIngredient.ANY_DAMAGE, 1)),
                item(OUTPUT_ID, 1),
                List.of(),
                "smithing_table",
                0,
                2,
                false
        ));

        assertNull(this.recipes.matchRecipe(new BedrockItem[]{item(INGREDIENT_ID, 1), BedrockItem.empty(), BedrockItem.empty(), BedrockItem.empty()}, false));
    }

    @Test
    void shapelessIngredientCountUsesDeclaredAmountForMultiplier() {
        this.recipes.addRecipe(new BedrockRecipe(
                "test:counted",
                BedrockRecipe.RecipeType.SHAPELESS,
                0,
                0,
                List.of(new BedrockRecipe.RecipeIngredient(INGREDIENT_ID, BedrockRecipe.RecipeIngredient.ANY_DAMAGE, 2)),
                item(OUTPUT_ID, 1),
                List.of(),
                "crafting_table",
                0,
                3,
                false
        ));
        final BedrockItem[] grid = new BedrockItem[]{item(INGREDIENT_ID, 6), BedrockItem.empty(), BedrockItem.empty(), BedrockItem.empty()};

        final RecipeRegistry.RecipeMatch match = this.recipes.matchRecipeWithPlacement(grid, false);

        assertNotNull(match);
        assertEquals(3, this.recipes.maxCraftMultiplier(match, grid));
        assertEquals(2, match.ingredientCount(0));
    }

    private BedrockRecipe stonecutterRecipe() {
        return new BedrockRecipe(
                "test:cut",
                BedrockRecipe.RecipeType.SHAPELESS,
                0,
                0,
                List.of(new BedrockRecipe.RecipeIngredient(INGREDIENT_ID, BedrockRecipe.RecipeIngredient.ANY_DAMAGE, 1)),
                item(OUTPUT_ID, 1),
                List.of(),
                "stonecutter",
                0,
                1,
                false
        );
    }

    private static BedrockItem item(final int id, final int amount) {
        return new BedrockItem(id, (short) 0, (byte) amount);
    }

}
