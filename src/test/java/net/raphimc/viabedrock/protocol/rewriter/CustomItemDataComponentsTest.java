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
import com.viaversion.viaversion.api.data.FullMappings;
import com.viaversion.viaversion.api.minecraft.data.StructuredData;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.minecraft.item.data.Consumable1_21_2;
import com.viaversion.viaversion.api.minecraft.item.data.FoodProperties1_21_2;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.ItemUseDefinition;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.UseAnimation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomItemDataComponentsTest {

    @Test
    void paperFallbackIdentitySeparatesDifferentBedrockItems() {
        final CompoundTag healData = CustomItemDataComponents.createPaperFallbackIdentity("easecation:stackable_potion_heal");
        final CompoundTag speedData = CustomItemDataComponents.createPaperFallbackIdentity("easecation:stackable_potion_move_speed");
        assertEquals("easecation:stackable_potion_heal", healData.getString(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));
        assertEquals("easecation:stackable_potion_move_speed", speedData.getString(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));
        assertNotEquals(healData, speedData);
        assertEquals(healData, CustomItemDataComponents.createPaperFallbackIdentity("easecation:stackable_potion_heal"));
    }

    @Test
    void paperFallbackIdentityPreservesExistingCustomData() {
        final CompoundTag existing = new CompoundTag();
        existing.putString("test:owner", "inventory");

        final CompoundTag merged = CustomItemDataComponents.createPaperFallbackIdentity(existing, "easecation:stackable_potion_heal");

        assertEquals("inventory", merged.getString("test:owner"));
        assertEquals("easecation:stackable_potion_heal", merged.getString(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));
        assertNull(existing.getString(CustomItemDataComponents.BEDROCK_IDENTIFIER_KEY));
    }

    @Test
    void drinkConsumableUsesBedrockDurationWithoutLocalEffects() {
        final Consumable1_21_2 consumable = CustomItemDataComponents.createConsumable(new ItemUseDefinition(32, UseAnimation.DRINK));
        assertEquals(1.6F, consumable.consumeSeconds());
        assertEquals(2, consumable.animationType());
        assertEquals("minecraft:entity.generic.drink", consumable.sound().value().identifier());
        assertFalse(consumable.hasConsumeParticles());
        assertEquals(0, consumable.consumeEffects().length);
    }

    @Test
    void missingItemUseDefinitionDoesNotAddConsumable() {
        assertNull(CustomItemDataComponents.createConsumable(null));
    }

    @Test
    void consumableComponentsRequireExperimentalFeatures() {
        final ItemUseDefinition itemUse = new ItemUseDefinition(32, UseAnimation.DRINK);

        assertNull(CustomItemDataComponents.createConsumableComponents(itemUse, false));
        assertNull(CustomItemDataComponents.createConsumableComponents(null, true));

        final CustomItemDataComponents.ConsumableComponents components = CustomItemDataComponents.createConsumableComponents(itemUse, true);
        assertNotNull(components);
        assertEquals(1.6F, components.consumable().consumeSeconds());
        assertEquals(0, components.food().nutrition());
        assertTrue(components.food().canAlwaysEat());
    }

    @Test
    void compatibilityFoodPropertiesDoNotApplyGameplayValues() {
        final FoodProperties1_21_2 food = CustomItemDataComponents.createFoodProperties();
        assertEquals(0, food.nutrition());
        assertEquals(0F, food.saturationModifier());
        assertTrue(food.canAlwaysEat());
    }

    @Test
    void synchronizedStackSizeIsApplied() throws ReflectiveOperationException {
        final StructuredItem item = emptyItemWithLookup();

        CustomItemDataComponents.applyMaxStackSize(item, 16, false);

        assertEquals(16, item.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE));
    }

    @Test
    void resourceStackSizeDoesNotReplaceSynchronizedValue() {
        final StructuredItem item = itemWith(StructuredData.of(StructuredDataKey.MAX_STACK_SIZE, 64, 1));

        CustomItemDataComponents.applyMaxStackSize(item, 16, true);

        assertEquals(64, item.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE));
    }

    @Test
    void unknownStackSizeDoesNotAddComponent() {
        final StructuredItem item = itemWith();

        CustomItemDataComponents.applyMaxStackSize(item, null, false);

        assertNull(item.dataContainer().get(StructuredDataKey.MAX_STACK_SIZE));
    }

    private static StructuredItem itemWith(final StructuredData<?>... data) {
        return new StructuredItem(10_000, 1, new StructuredDataContainer(data));
    }

    private static StructuredItem emptyItemWithLookup() throws ReflectiveOperationException {
        final StructuredDataContainer container = new StructuredDataContainer();
        final FullMappings lookup = (FullMappings) Proxy.newProxyInstance(
                FullMappings.class.getClassLoader(),
                new Class<?>[]{FullMappings.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("id") || method.getName().equals("mappedId")) {
                        return 1;
                    }
                    throw new UnsupportedOperationException(method.toString());
                }
        );

        // 生产环境中，这个序列化器查找来自已初始化的 ViaVersion 协议。
        final Field lookupField = StructuredDataContainer.class.getDeclaredField("lookup");
        lookupField.setAccessible(true);
        lookupField.set(container, lookup);
        return new StructuredItem(10_000, 1, container);
    }

}
