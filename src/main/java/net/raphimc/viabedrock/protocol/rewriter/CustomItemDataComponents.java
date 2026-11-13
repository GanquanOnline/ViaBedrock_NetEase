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
package net.raphimc.viabedrock.protocol.rewriter;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.minecraft.SoundEvent;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.data.Consumable1_21_2;
import com.viaversion.viaversion.api.minecraft.item.data.Equippable;
import com.viaversion.viaversion.api.minecraft.item.data.FoodProperties1_21_2;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.ItemUseDefinition;
import net.raphimc.viabedrock.protocol.model.BedrockItem;

import java.util.Locale;

final class CustomItemDataComponents {

    static final String BEDROCK_IDENTIFIER_KEY = "viabedrock:bedrock_identifier";
    static final String BEDROCK_ITEM_SHADOW_KEY = "viabedrock:bedrock_item";
    static final int BEDROCK_ITEM_SHADOW_VERSION = 1;

    static void applyPaperFallbackIdentity(final Item item, final String bedrockIdentifier) {
        final StructuredDataContainer data = item.dataContainer();
        final CompoundTag existingCustomData = data.get(StructuredDataKey.CUSTOM_DATA);
        data.set(StructuredDataKey.CUSTOM_DATA, createPaperFallbackIdentity(existingCustomData, bedrockIdentifier));
    }

    static void applyBedrockItemShadow(final Item item, final String bedrockIdentifier, final BedrockItem bedrockItem) {
        final StructuredDataContainer data = item.dataContainer();
        final CompoundTag existingCustomData = data.get(StructuredDataKey.CUSTOM_DATA);
        final CompoundTag customData = existingCustomData != null ? existingCustomData.copy() : new CompoundTag();
        final CompoundTag shadow = new CompoundTag();
        shadow.putInt("version", BEDROCK_ITEM_SHADOW_VERSION);
        shadow.putString("identifier", bedrockIdentifier);
        shadow.putInt("data", bedrockItem.data());
        shadow.putInt("block_runtime_id", bedrockItem.blockRuntimeId());
        if (bedrockItem.tag() != null) {
            shadow.put("tag", bedrockItem.tag().copy());
        }
        shadow.put("can_place", stringList(bedrockItem.canPlace()));
        shadow.put("can_break", stringList(bedrockItem.canBreak()));
        shadow.putLong("blocking_ticks", bedrockItem.blockingTicks());
        customData.put(BEDROCK_ITEM_SHADOW_KEY, shadow);
        data.set(StructuredDataKey.CUSTOM_DATA, customData);
    }

    private static ListTag<StringTag> stringList(final String[] values) {
        final ListTag<StringTag> list = new ListTag<>(StringTag.class);
        for (String value : values) {
            if (value != null) {
                list.add(new StringTag(value));
            }
        }
        return list;
    }

    static void applyConsumable(final Item item, final ItemUseDefinition itemUse, final boolean experimentalFeaturesEnabled) {
        final ConsumableComponents components = createConsumableComponents(itemUse, experimentalFeaturesEnabled);
        if (components != null) {
            item.dataContainer().set(StructuredDataKey.CONSUMABLE1_21_2, components.consumable());
            item.dataContainer().set(StructuredDataKey.FOOD1_21_2, components.food());
        }
    }

    static void applyMaxStackSize(final Item item, final Integer maxStackSize, final boolean fallbackOnly) {
        if (maxStackSize == null) {
            return;
        }
        final StructuredDataContainer data = item.dataContainer();
        if (fallbackOnly && (data.get(StructuredDataKey.MAX_STACK_SIZE) != null || data.hasEmpty(StructuredDataKey.MAX_STACK_SIZE))) {
            return;
        }
        data.set(StructuredDataKey.MAX_STACK_SIZE, maxStackSize);
    }

    static CompoundTag createPaperFallbackIdentity(final String bedrockIdentifier) {
        return createPaperFallbackIdentity(null, bedrockIdentifier);
    }

    static CompoundTag createPaperFallbackIdentity(final CompoundTag existingCustomData, final String bedrockIdentifier) {
        final CompoundTag customData = existingCustomData != null ? existingCustomData.copy() : new CompoundTag();
        customData.putString(BEDROCK_IDENTIFIER_KEY, bedrockIdentifier);
        return customData;
    }

    static ConsumableComponents createConsumableComponents(final ItemUseDefinition itemUse, final boolean experimentalFeaturesEnabled) {
        if (!experimentalFeaturesEnabled || itemUse == null) {
            return null;
        }
        return new ConsumableComponents(createConsumable(itemUse), createFoodProperties());
    }

    static Consumable1_21_2 createConsumable(final ItemUseDefinition itemUse) {
        if (itemUse == null) {
            return null;
        }
        return new Consumable1_21_2(
                itemUse.useDurationTicks() / 20F,
                itemUse.animation().javaId(),
                Holder.of(new SoundEvent(itemUse.animation().soundIdentifier(), null)),
                itemUse.animation().consumeParticles(),
                new Consumable1_21_2.ConsumeEffect[0]
        );
    }

    static FoodProperties1_21_2 createFoodProperties() {
        return new FoodProperties1_21_2(0, 0F, true);
    }

    record ConsumableComponents(Consumable1_21_2 consumable, FoodProperties1_21_2 food) {
    }

    /**
     * 网易自定义防具没有映射到原版护甲时没有 equippable 组件，客户端会把装备槽当成空的，
     * 穿在身上什么都不画。这里补一个指向原版护甲贴图的 equippable，让护甲能显示出来。
     */
    static void applyWearableEquippable(final Item item, final String bedrockIdentifier, final String wearableSlot, final boolean javaItemIsArmor) {
        if (javaItemIsArmor || wearableSlot == null) {
            return;
        }
        final int slot = armorSlotId(wearableSlot);
        if (slot < 0) {
            return;
        }
        final StructuredDataContainer data = item.dataContainer();
        if (data.get(StructuredDataKey.EQUIPPABLE1_21_6) != null || data.hasEmpty(StructuredDataKey.EQUIPPABLE1_21_6)) {
            return;
        }
        data.set(StructuredDataKey.EQUIPPABLE1_21_6, new Equippable(
                slot,
                Holder.of(new SoundEvent("minecraft:item.armor.equip_generic", null)),
                equipmentAsset(bedrockIdentifier),
                null,
                null,
                true,
                true,
                true
        ));
    }

    private static int armorSlotId(final String wearableSlot) {
        return switch (wearableSlot) {
            case "slot.armor.feet" -> 1;
            case "slot.armor.legs" -> 2;
            case "slot.armor.chest" -> 3;
            case "slot.armor.head" -> 4;
            default -> -1;
        };
    }

    private static String equipmentAsset(final String bedrockIdentifier) {
        final String identifier = bedrockIdentifier.toLowerCase(Locale.ROOT);
        if (identifier.contains("netherite")) {
            return "minecraft:netherite";
        }
        if (identifier.contains("diamond")) {
            return "minecraft:diamond";
        }
        if (identifier.contains("gold")) {
            return "minecraft:gold";
        }
        if (identifier.contains("chain")) {
            return "minecraft:chainmail";
        }
        if (identifier.contains("leather")) {
            return "minecraft:leather";
        }
        if (identifier.contains("turtle")) {
            return "minecraft:turtle_scute";
        }
        if (identifier.contains("armadillo")) {
            return "minecraft:armadillo_scute";
        }
        return "minecraft:iron";
    }

    private CustomItemDataComponents() {
    }

}
