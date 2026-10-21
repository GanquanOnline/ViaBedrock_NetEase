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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockRecipe.RecipeIngredient;
import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.ArrayList;
import java.util.List;

public class RecipeRegistry extends StoredObject {

    private final List<BedrockRecipe> craftingRecipes = new ArrayList<>();
    private final List<SmithingRecipe> smithingRecipes = new ArrayList<>();

    public RecipeRegistry(final UserConnection user) {
        super(user);
    }

    public void clear() {
        this.craftingRecipes.clear();
        this.smithingRecipes.clear();
    }

    public void addRecipe(final BedrockRecipe recipe) {
        this.craftingRecipes.add(recipe);
    }

    public void addSmithingRecipe(final SmithingRecipe recipe) {
        this.smithingRecipes.add(recipe);
    }

    public int recipeCount() {
        return this.craftingRecipes.size();
    }

    public BedrockRecipe matchRecipe(final BedrockItem[] gridItems, final boolean is3x3) {
        final RecipeMatch match = this.matchRecipeWithPlacement(gridItems, is3x3);
        return match != null ? match.recipe() : null;
    }

    public RecipeMatch matchRecipeWithPlacement(final BedrockItem[] gridItems, final boolean is3x3) {
        final int gridWidth = is3x3 ? 3 : 2;
        final int gridHeight = is3x3 ? 3 : 2;

        RecipeMatch bestMatch = null;
        int bestPriority = Integer.MAX_VALUE;

        for (final BedrockRecipe recipe : this.craftingRecipes) {
            final int[] ingredientCounts;
            if (recipe.type() == BedrockRecipe.RecipeType.SHAPED) {
                ingredientCounts = matchShaped(recipe, gridItems, gridWidth, gridHeight);
            } else if (recipe.type() == BedrockRecipe.RecipeType.SHAPELESS) {
                if ("stonecutter".equals(recipe.tag()) || "smithing_table".equals(recipe.tag())) {
                    continue;
                }
                ingredientCounts = matchShapeless(recipe, gridItems);
            } else {
                continue;
            }
            if (ingredientCounts != null && recipe.priority() < bestPriority) {
                bestMatch = new RecipeMatch(recipe, ingredientCounts);
                bestPriority = recipe.priority();
            }
        }

        return bestMatch;
    }

    public int maxCraftMultiplier(final RecipeMatch match, final BedrockItem[] gridItems) {
        if (match == null) return 0;
        int multiplier = Integer.MAX_VALUE;
        for (int slot = 0; slot < gridItems.length; slot++) {
            final int perCraft = match.ingredientCount(slot);
            if (perCraft > 0) multiplier = Math.min(multiplier, gridItems[slot].amount() / perCraft);
        }
        return multiplier == Integer.MAX_VALUE ? 0 : multiplier;
    }

    public BedrockRecipe matchStonecutter(final BedrockItem input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        BedrockRecipe unique = null;
        for (final BedrockRecipe recipe : this.craftingRecipes) {
            if (recipe.type() != BedrockRecipe.RecipeType.SHAPELESS) {
                continue;
            }
            if (!"stonecutter".equals(recipe.tag())) {
                continue;
            }
            if (recipe.ingredients().size() != 1 || !recipe.ingredients().get(0).matches(input)) {
                continue;
            }
            if (unique != null) {
                return null;
            }
            unique = recipe;
        }
        return unique;
    }

    public SmithingRecipe matchSmithingTransform(final BedrockItem equipment, final BedrockItem ingredient, final BedrockItem template) {
        SmithingRecipe unique = null;
        for (final SmithingRecipe recipe : this.smithingRecipes) {
            if (recipe.trim()) {
                continue;
            }
            if (!recipe.matches(equipment, ingredient, template)) {
                continue;
            }
            if (unique != null) {
                return null;
            }
            unique = recipe;
        }
        return unique;
    }

    public record SmithingRecipe(int networkId, boolean trim, RecipeIngredient equipment, RecipeIngredient ingredient,
                                 RecipeIngredient template) {
        public boolean matches(final BedrockItem equipmentItem, final BedrockItem ingredientItem, final BedrockItem templateItem) {
            return matchesIngredient(this.equipment, equipmentItem)
                    && matchesIngredient(this.ingredient, ingredientItem)
                    && matchesIngredient(this.template, templateItem);
        }

        private static boolean matchesIngredient(final RecipeIngredient ingredient, final BedrockItem item) {
            if (ingredient == null || ingredient.runtimeId() == 0) {
                return item == null || item.isEmpty();
            }
            return item != null && ingredient.matches(item);
        }
    }

    private static int[] matchShapeless(final BedrockRecipe recipe, final BedrockItem[] gridItems) {
        final List<RecipeIngredient> ingredients = recipe.ingredients();
        int nonEmptyGridCount = 0;
        for (final BedrockItem item : gridItems) {
            if (!item.isEmpty()) nonEmptyGridCount++;
        }
        if (ingredients.size() != nonEmptyGridCount) return null;

        final boolean[] used = new boolean[gridItems.length];
        final int[] ingredientCounts = new int[gridItems.length];
        return assignShapelessIngredients(ingredients, gridItems, 0, used, ingredientCounts)
                ? ingredientCounts : null;
    }

    private static boolean assignShapelessIngredients(final List<RecipeIngredient> ingredients,
                                                      final BedrockItem[] gridItems, final int ingredientIndex,
                                                      final boolean[] used, final int[] ingredientCounts) {
        if (ingredientIndex == ingredients.size()) return true;
        final RecipeIngredient ingredient = ingredients.get(ingredientIndex);
        if (ingredient.runtimeId() == 0 || ingredient.count() <= 0) return false;
        for (int slot = 0; slot < gridItems.length; slot++) {
            final BedrockItem item = gridItems[slot];
            if (used[slot] || item.isEmpty() || !ingredient.matches(item)
                    || item.amount() < ingredient.count()) continue;
            used[slot] = true;
            ingredientCounts[slot] = ingredient.count();
            if (assignShapelessIngredients(ingredients, gridItems, ingredientIndex + 1, used, ingredientCounts)) return true;
            ingredientCounts[slot] = 0;
            used[slot] = false;
        }
        return false;
    }

    private static int[] matchShaped(final BedrockRecipe recipe, final BedrockItem[] gridItems,
                                     final int gridWidth, final int gridHeight) {
        if (recipe.width() > gridWidth || recipe.height() > gridHeight) return null;
        final int maxMirror = recipe.assumeSymmetry() ? 1 : 0;
        for (int mirror = 0; mirror <= maxMirror; mirror++) {
            for (int offX = 0; offX <= gridWidth - recipe.width(); offX++) {
                for (int offY = 0; offY <= gridHeight - recipe.height(); offY++) {
                    final int[] ingredientCounts = matchShapedAt(
                            recipe, gridItems, gridWidth, gridHeight, offX, offY, mirror == 1);
                    if (ingredientCounts != null) return ingredientCounts;
                }
            }
        }
        return null;
    }

    private static int[] matchShapedAt(final BedrockRecipe recipe, final BedrockItem[] gridItems,
                                       final int gridWidth, final int gridHeight, final int offX,
                                       final int offY, final boolean mirror) {
        final int[] ingredientCounts = new int[gridItems.length];
        for (int y = 0; y < gridHeight; y++) {
            for (int x = 0; x < gridWidth; x++) {
                final int gridIndex = y * gridWidth + x;
                final BedrockItem gridItem = gridItems[gridIndex];
                final int recipeX = mirror ? (recipe.width() - 1 - (x - offX)) : (x - offX);
                final int recipeY = y - offY;
                if (recipeX >= 0 && recipeX < recipe.width() && recipeY >= 0 && recipeY < recipe.height()) {
                    final RecipeIngredient ingredient = recipe.ingredients().get(recipeY * recipe.width() + recipeX);
                    if (ingredient.runtimeId() == 0) {
                        if (!gridItem.isEmpty()) return null;
                    } else if (ingredient.count() <= 0 || !ingredient.matches(gridItem)
                            || gridItem.amount() < ingredient.count()) {
                        return null;
                    } else {
                        ingredientCounts[gridIndex] = ingredient.count();
                    }
                } else if (!gridItem.isEmpty()) {
                    return null;
                }
            }
        }
        return ingredientCounts;
    }

    public record RecipeMatch(BedrockRecipe recipe, int[] ingredientCounts) {
        public RecipeMatch {
            ingredientCounts = ingredientCounts.clone();
        }

        @Override
        public int[] ingredientCounts() {
            return this.ingredientCounts.clone();
        }

        public int ingredientCount(final int slot) {
            return this.ingredientCounts[slot];
        }
    }

}
