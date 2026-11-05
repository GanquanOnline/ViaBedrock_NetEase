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

import com.viaversion.nbt.tag.NumberTag;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * MOT {@code CraftRecipeOptionalProcessor.updateAnvilResult} 的消耗数量。
 * 修理材料循环对每个物品消耗 {@code min(damage, maxDurability/4)} 个单位；合并 / 附魔书消耗 1 个。
 * 当 MOT 会拒绝该请求时返回 {@code -1}（存在修理材料但 {@code repair <= 0}）。
 */
public final class AnvilRepairCost {

    private static final int LEGACY_PLANKS = 5;
    private static final int LEGACY_COBBLESTONE = 4;
    private static final int LEGACY_IRON_INGOT = 265;
    private static final int LEGACY_GOLD_INGOT = 266;
    private static final int LEGACY_DIAMOND = 264;
    private static final int LEGACY_LEATHER = 334;
    private static final int LEGACY_NETHERITE_INGOT = 742;
    private static final int LEGACY_PHANTOM_MEMBRANE = 470;
    private static final int LEGACY_ENCHANTED_BOOK = 403;

    private static final Map<String, String> REPAIR_MATERIAL_BY_IDENTIFIER = new HashMap<>();
    private static final Map<Integer, Integer> REPAIR_MATERIAL_BY_LEGACY_ID = new HashMap<>();
    private static final Map<String, Integer> MAX_DURABILITY_BY_IDENTIFIER = new HashMap<>();
    private static final Map<Integer, Integer> MAX_DURABILITY_BY_LEGACY_ID = new HashMap<>();

    static {
        putRepair("minecraft:wooden_sword", "minecraft:planks", 268, LEGACY_PLANKS, 60);
        putRepair("minecraft:wooden_shovel", "minecraft:planks", 269, LEGACY_PLANKS, 60);
        putRepair("minecraft:wooden_pickaxe", "minecraft:planks", 270, LEGACY_PLANKS, 60);
        putRepair("minecraft:wooden_axe", "minecraft:planks", 271, LEGACY_PLANKS, 60);
        putRepair("minecraft:wooden_hoe", "minecraft:planks", 290, LEGACY_PLANKS, 60);
        putRepair("minecraft:stone_sword", "minecraft:cobblestone", 272, LEGACY_COBBLESTONE, 132);
        putRepair("minecraft:stone_shovel", "minecraft:cobblestone", 273, LEGACY_COBBLESTONE, 132);
        putRepair("minecraft:stone_pickaxe", "minecraft:cobblestone", 274, LEGACY_COBBLESTONE, 132);
        putRepair("minecraft:stone_axe", "minecraft:cobblestone", 275, LEGACY_COBBLESTONE, 132);
        putRepair("minecraft:stone_hoe", "minecraft:cobblestone", 291, LEGACY_COBBLESTONE, 132);
        putRepair("minecraft:iron_shovel", "minecraft:iron_ingot", 256, LEGACY_IRON_INGOT, 251);
        putRepair("minecraft:iron_pickaxe", "minecraft:iron_ingot", 257, LEGACY_IRON_INGOT, 251);
        putRepair("minecraft:iron_axe", "minecraft:iron_ingot", 258, LEGACY_IRON_INGOT, 251);
        putRepair("minecraft:iron_sword", "minecraft:iron_ingot", 267, LEGACY_IRON_INGOT, 251);
        putRepair("minecraft:iron_hoe", "minecraft:iron_ingot", 292, LEGACY_IRON_INGOT, 251);
        putRepair("minecraft:chainmail_helmet", "minecraft:iron_ingot", 302, LEGACY_IRON_INGOT, 166);
        putRepair("minecraft:chainmail_chestplate", "minecraft:iron_ingot", 303, LEGACY_IRON_INGOT, 241);
        putRepair("minecraft:chainmail_leggings", "minecraft:iron_ingot", 304, LEGACY_IRON_INGOT, 226);
        putRepair("minecraft:chainmail_boots", "minecraft:iron_ingot", 305, LEGACY_IRON_INGOT, 196);
        putRepair("minecraft:iron_helmet", "minecraft:iron_ingot", 306, LEGACY_IRON_INGOT, 166);
        putRepair("minecraft:iron_chestplate", "minecraft:iron_ingot", 307, LEGACY_IRON_INGOT, 241);
        putRepair("minecraft:iron_leggings", "minecraft:iron_ingot", 308, LEGACY_IRON_INGOT, 226);
        putRepair("minecraft:iron_boots", "minecraft:iron_ingot", 309, LEGACY_IRON_INGOT, 196);
        putRepair("minecraft:golden_sword", "minecraft:gold_ingot", 283, LEGACY_GOLD_INGOT, 33);
        putRepair("minecraft:golden_shovel", "minecraft:gold_ingot", 284, LEGACY_GOLD_INGOT, 33);
        putRepair("minecraft:golden_pickaxe", "minecraft:gold_ingot", 285, LEGACY_GOLD_INGOT, 33);
        putRepair("minecraft:golden_axe", "minecraft:gold_ingot", 286, LEGACY_GOLD_INGOT, 33);
        putRepair("minecraft:golden_hoe", "minecraft:gold_ingot", 294, LEGACY_GOLD_INGOT, 33);
        putRepair("minecraft:golden_helmet", "minecraft:gold_ingot", 314, LEGACY_GOLD_INGOT, 78);
        putRepair("minecraft:golden_chestplate", "minecraft:gold_ingot", 315, LEGACY_GOLD_INGOT, 113);
        putRepair("minecraft:golden_leggings", "minecraft:gold_ingot", 316, LEGACY_GOLD_INGOT, 106);
        putRepair("minecraft:golden_boots", "minecraft:gold_ingot", 317, LEGACY_GOLD_INGOT, 92);
        putRepair("minecraft:diamond_sword", "minecraft:diamond", 276, LEGACY_DIAMOND, 1562);
        putRepair("minecraft:diamond_shovel", "minecraft:diamond", 277, LEGACY_DIAMOND, 1562);
        putRepair("minecraft:diamond_pickaxe", "minecraft:diamond", 278, LEGACY_DIAMOND, 1562);
        putRepair("minecraft:diamond_axe", "minecraft:diamond", 279, LEGACY_DIAMOND, 1562);
        putRepair("minecraft:diamond_hoe", "minecraft:diamond", 293, LEGACY_DIAMOND, 1562);
        putRepair("minecraft:diamond_helmet", "minecraft:diamond", 310, LEGACY_DIAMOND, 364);
        putRepair("minecraft:diamond_chestplate", "minecraft:diamond", 311, LEGACY_DIAMOND, 529);
        putRepair("minecraft:diamond_leggings", "minecraft:diamond", 312, LEGACY_DIAMOND, 496);
        putRepair("minecraft:diamond_boots", "minecraft:diamond", 313, LEGACY_DIAMOND, 430);
        putRepair("minecraft:leather_helmet", "minecraft:leather", 298, LEGACY_LEATHER, 56);
        putRepair("minecraft:leather_chestplate", "minecraft:leather", 299, LEGACY_LEATHER, 81);
        putRepair("minecraft:leather_leggings", "minecraft:leather", 300, LEGACY_LEATHER, 76);
        putRepair("minecraft:leather_boots", "minecraft:leather", 301, LEGACY_LEATHER, 66);
        putRepair("minecraft:netherite_sword", "minecraft:netherite_ingot", 743, LEGACY_NETHERITE_INGOT, 2032);
        putRepair("minecraft:netherite_shovel", "minecraft:netherite_ingot", 744, LEGACY_NETHERITE_INGOT, 2032);
        putRepair("minecraft:netherite_pickaxe", "minecraft:netherite_ingot", 745, LEGACY_NETHERITE_INGOT, 2032);
        putRepair("minecraft:netherite_axe", "minecraft:netherite_ingot", 746, LEGACY_NETHERITE_INGOT, 2032);
        putRepair("minecraft:netherite_hoe", "minecraft:netherite_ingot", 747, LEGACY_NETHERITE_INGOT, 2032);
        putRepair("minecraft:netherite_helmet", "minecraft:netherite_ingot", 748, LEGACY_NETHERITE_INGOT, 407);
        putRepair("minecraft:netherite_chestplate", "minecraft:netherite_ingot", 749, LEGACY_NETHERITE_INGOT, 592);
        putRepair("minecraft:netherite_leggings", "minecraft:netherite_ingot", 750, LEGACY_NETHERITE_INGOT, 555);
        putRepair("minecraft:netherite_boots", "minecraft:netherite_ingot", 751, LEGACY_NETHERITE_INGOT, 481);
        putRepair("minecraft:elytra", "minecraft:phantom_membrane", 444, LEGACY_PHANTOM_MEMBRANE, 432);
    }

    private static void putRepair(final String identifier, final String material, final int legacyId,
                                  final int legacyMaterial, final int maxDurability) {
        REPAIR_MATERIAL_BY_IDENTIFIER.put(identifier, material);
        REPAIR_MATERIAL_BY_LEGACY_ID.put(legacyId, legacyMaterial);
        MAX_DURABILITY_BY_IDENTIFIER.put(identifier, maxDurability);
        MAX_DURABILITY_BY_LEGACY_ID.put(legacyId, maxDurability);
    }

    private static final Set<String> PLANKS_IDENTIFIERS = Set.of(
            "minecraft:planks", "minecraft:oak_planks", "minecraft:spruce_planks",
            "minecraft:birch_planks", "minecraft:jungle_planks", "minecraft:acacia_planks",
            "minecraft:dark_oak_planks", "minecraft:mangrove_planks", "minecraft:cherry_planks",
            "minecraft:bamboo_planks", "minecraft:crimson_planks", "minecraft:warped_planks",
            "minecraft:pale_oak_planks"
    );

    private AnvilRepairCost() {
    }

    public static int materialCount(final BedrockItem input, final BedrockItem material, final ItemRewriter itemRewriter) {
        if (material == null || material.isEmpty()) {
            return 0;
        }
        if (input == null || input.isEmpty()) {
            return -1;
        }
        final String inputId = identifier(input, itemRewriter);
        final String materialId = identifier(material, itemRewriter);
        final int maxDurability = maxDurability(input, inputId);
        if (isRepairMaterial(input, inputId, material, materialId) && maxDurability > 0) {
            return repairMaterialUnits(damage(input), maxDurability, material.amount());
        }
        if (isEnchantedBook(material, materialId) || isSameItemCombine(input, inputId, material, materialId, maxDurability)) {
            return Math.min(1, material.amount());
        }
        return -1;
    }

    static int repairMaterialUnits(final int damage, final int maxDurability, final int available) {
        int repair = Math.min(damage, maxDurability / 4);
        if (repair <= 0) {
            return -1;
        }
        int remainingDamage = damage;
        int used = 0;
        while (repair > 0 && used < available) {
            remainingDamage -= repair;
            used++;
            repair = Math.min(remainingDamage, maxDurability / 4);
        }
        return used;
    }

    private static boolean isRepairMaterial(final BedrockItem input, final String inputId,
                                            final BedrockItem material, final String materialId) {
        if (materialId != null && inputId != null) {
            final String expected = REPAIR_MATERIAL_BY_IDENTIFIER.get(inputId);
            if (expected == null) {
                return false;
            }
            if ("minecraft:planks".equals(expected)) {
                return PLANKS_IDENTIFIERS.contains(materialId);
            }
            return expected.equals(materialId);
        }
        final Integer expectedLegacy = REPAIR_MATERIAL_BY_LEGACY_ID.get(input.identifier());
        return expectedLegacy != null && expectedLegacy == material.identifier();
    }

    private static boolean isSameItemCombine(final BedrockItem input, final String inputId,
                                             final BedrockItem material, final String materialId,
                                             final int maxDurability) {
        if (maxDurability <= 0) {
            return false;
        }
        if (inputId != null && materialId != null) {
            return inputId.equals(materialId);
        }
        return input.identifier() == material.identifier();
    }

    private static boolean isEnchantedBook(final BedrockItem material, final String materialId) {
        if ("minecraft:enchanted_book".equals(materialId)) {
            return true;
        }
        return material.identifier() == LEGACY_ENCHANTED_BOOK;
    }

    private static int maxDurability(final BedrockItem input, final String inputId) {
        if (inputId != null) {
            final Integer value = MAX_DURABILITY_BY_IDENTIFIER.get(inputId);
            if (value != null) {
                return value;
            }
        }
        return MAX_DURABILITY_BY_LEGACY_ID.getOrDefault(input.identifier(), -1);
    }

    static int damage(final BedrockItem item) {
        if (item.tag() != null && item.tag().get("Damage") instanceof NumberTag damageTag) {
            return Math.max(0, damageTag.asInt());
        }
        return Math.max(0, item.data());
    }

    private static String identifier(final BedrockItem item, final ItemRewriter itemRewriter) {
        if (itemRewriter == null || item == null || item.isEmpty()) {
            return null;
        }
        return itemRewriter.bedrockIdentifier(item);
    }

}
