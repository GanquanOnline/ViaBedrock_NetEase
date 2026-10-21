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
package net.raphimc.viabedrock.experimental;

import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.ItemUseDefinition;
import net.raphimc.viabedrock.api.resourcepack.definition.ItemDefinitions.UseAnimation;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemReleaseInventoryTransaction_ActionType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseSemanticsTest {

    @Test
    void customConsumableCompletesAtConfiguredDuration() {
        final ItemUseDefinition itemUse = new ItemUseDefinition(32, UseAnimation.DRINK);

        assertTrue(ItemUseSemantics.isContinuousUseItem("easecation:stackable_potion_heal", null, itemUse, false));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("easecation:stackable_potion_heal", null, itemUse, 31));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("easecation:stackable_potion_heal", null, itemUse, 32));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("easecation:stackable_potion_heal", null, itemUse, 31, true));
    }

    @Test
    void customConsumableUsesNonDefaultDuration() {
        final ItemUseDefinition itemUse = new ItemUseDefinition(12, UseAnimation.EAT);

        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 11));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 12));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("test:quick_food", null, itemUse, 11, true));
    }

    @Test
    void vanillaContinuousUseBehaviorRemainsAvailable() {
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:potion", null, null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:apple", Set.of("minecraft:is_food"), null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:bow", null, null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:shield", null, null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:wooden_spear", Set.of("minecraft:is_spear"), null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:netherite_spear", null, null, false));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:crossbow", null, null, true));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:paper", null, null, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:spyglass", null, null, false));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:brush", null, null, false));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:goat_horn", null, null, false));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:snowball", null, null, false));
    }

    @Test
    void goatHornStaysClickAirEvenWhenResourcePackDefinesUseDuration() {
        final ItemUseDefinition itemUse = new ItemUseDefinition(32, UseAnimation.EAT);
        assertFalse(ItemUseSemantics.isGoatHorn("minecraft:bow"));
        assertTrue(ItemUseSemantics.isGoatHorn("minecraft:goat_horn"));
        assertFalse(ItemUseSemantics.isContinuousUseItem("minecraft:goat_horn", null, itemUse, false));
        assertTrue(ItemUseSemantics.isContinuousUseItem("easecation:stackable_potion_heal", null, itemUse, false));
    }

    @Test
    void vanillaConsumablesKeepOfficialDurationAndFinishEarlyOnNetEase() {
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("minecraft:potion", null, null, 31));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:potion", null, null, 32));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:apple", Set.of("minecraft:is_food"), null, 32));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("minecraft:apple", Set.of("minecraft:is_food"), null, 30, true));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:apple", Set.of("minecraft:is_food"), null, 31, true));
        assertEquals(16, ItemUseSemantics.consumableUseTicks("minecraft:dried_kelp", Set.of("minecraft:is_food"), null));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("minecraft:dried_kelp", Set.of("minecraft:is_food"), null, 14, true));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Use,
                ItemUseSemantics.releaseAction("minecraft:dried_kelp", Set.of("minecraft:is_food"), null, 15, true));
        assertEquals(32, ItemUseSemantics.consumableUseTicks("minecraft:honey_bottle", Set.of("minecraft:is_food"), null));
        assertTrue(ItemUseSemantics.javaUsingVisible(true, true, 15, 16));
        assertFalse(ItemUseSemantics.javaUsingVisible(true, true, 16, 16));
    }

    @Test
    void consumableDetectionCoversFoodPotionAndCustomUseItems() {
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:apple", Set.of("minecraft:is_food"), null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:potion", null, null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:milk_bucket", null, null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("test:quick_food", null, new ItemUseDefinition(12, UseAnimation.EAT)));
        assertFalse(ItemUseSemantics.isConsumableUseItem("minecraft:bow", null, null));
        assertFalse(ItemUseSemantics.isConsumableUseItem("minecraft:shield", null, null));
        assertFalse(ItemUseSemantics.isConsumableUseItem("minecraft:paper", null, null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:ominous_bottle", null, null));
        assertTrue(ItemUseSemantics.isConsumableUseItem("minecraft:glow_berries", Set.of("minecraft:is_food"), null));
        assertTrue(ItemUseSemantics.isContinuousUseItem("minecraft:ominous_bottle", null, null, false));
    }

    @Test
    void neteaseConsumablesNeedStandaloneUseItemWhileOfficialKeepsAuthInputOnly() {
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, true, false, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, false, true, false));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(false, true, false, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(false, false, true, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false));
    }

    @Test
    void neteaseConsumablesSkipMobEquipmentAndFinishWithoutRelease() {
        assertTrue(ItemUseSemantics.skipMobEquipmentBeforeUse(true, true));
        assertFalse(ItemUseSemantics.skipMobEquipmentBeforeUse(false, true));
        assertFalse(ItemUseSemantics.skipMobEquipmentBeforeUse(true, false));
        assertFalse(ItemUseSemantics.sendConsumableFinishTransaction(true, true));
        assertTrue(ItemUseSemantics.sendConsumableFinishTransaction(false, true));
        assertFalse(ItemUseSemantics.sendConsumableFinishTransaction(true, false));
        assertTrue(ItemUseSemantics.motAutoCompletesConsumable("minecraft:apple", Set.of("minecraft:is_food")));
        assertTrue(ItemUseSemantics.motAutoCompletesConsumable("minecraft:potion", null));
        assertFalse(ItemUseSemantics.motAutoCompletesConsumable("easecation:stackable_potion_heal", null));
        assertFalse(ItemUseSemantics.sendConsumableFinishTransaction(true, true, true));
        assertTrue(ItemUseSemantics.sendConsumableFinishTransaction(true, true, false));
        assertFalse(ItemUseSemantics.delayReleaseAfterConsumableFinish(true, true));
        assertTrue(ItemUseSemantics.delayReleaseAfterConsumableFinish(false, true));
        assertFalse(ItemUseSemantics.delayReleaseAfterConsumableFinish(true, false));
        assertTrue(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(true, true, false));
        assertFalse(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(true, true, true));
        assertFalse(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(false, true, false));
        assertFalse(ItemUseSemantics.keepLocalUsingAfterConsumableFinish(true, false, false));
        assertTrue(ItemUseSemantics.javaUsingVisible(true, true, 31));
        assertFalse(ItemUseSemantics.javaUsingVisible(true, true, 32));
        assertTrue(ItemUseSemantics.javaUsingVisible(true, false, 40));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(true, true),
                "two-arg helper must not swallow an early Java RELEASE_USE_ITEM");
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(false, true));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(true, false));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(true, true, false, 0),
                "an early Java RELEASE_USE_ITEM must reach MOT to clear using-state");
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(false, true, false, 1));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(true, true, false, 2),
                "a later empty-hand release is still an interrupt");
        assertTrue(ItemUseSemantics.ignoreJavaConsumableRelease(true, true, true));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(true, false, true));
        assertFalse(ItemUseSemantics.ignoreJavaConsumableRelease(false, true, true));
        assertTrue(ItemUseSemantics.sendCancelRelease(true, true, 0));
        assertTrue(ItemUseSemantics.sendCancelRelease(false, true, 1));
        assertTrue(ItemUseSemantics.sendCancelRelease(true, true, 2));
        assertTrue(ItemUseSemantics.sendCancelRelease(true, false, 0));
        assertTrue(ItemUseSemantics.suppressStartSprintingWhileUsingItem(true, true));
        assertFalse(ItemUseSemantics.suppressStartSprintingWhileUsingItem(false, true));
        assertFalse(ItemUseSemantics.suppressStartSprintingWhileUsingItem(true, false));
        assertTrue(ItemUseSemantics.consumableConsumedByServer(322, 8, false, 322, 7));
        assertTrue(ItemUseSemantics.consumableConsumedByServer(322, 1, true, 0, 0));
        assertFalse(ItemUseSemantics.consumableConsumedByServer(322, 8, false, 322, 8));
        assertFalse(ItemUseSemantics.localUsingTimedOut(true, true, 47));
        assertTrue(ItemUseSemantics.localUsingTimedOut(true, true, 48));
        assertFalse(ItemUseSemantics.localUsingTimedOut(false, true, 40));
    }

    @Test
    void neteaseConsumablesDropDuplicateStartAndAuthInputInteraction() {
        assertTrue(ItemUseSemantics.ignoreDuplicateUseStart(true, true));
        assertFalse(ItemUseSemantics.ignoreDuplicateUseStart(false, true));
        assertFalse(ItemUseSemantics.ignoreDuplicateUseStart(true, false));
        assertFalse(ItemUseSemantics.attachAuthInputItemInteraction(true, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(false, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(true, false));
        assertFalse(ItemUseSemantics.attachAuthInputItemInteraction(true, false, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(false, false, true));
        assertFalse(ItemUseSemantics.attachAuthInputItemInteraction(true, false, false, true));
        assertTrue(ItemUseSemantics.attachAuthInputItemInteraction(false, false, false, true));
        assertFalse(ItemUseSemantics.sendStartItemUseOnForBow(true));
        assertTrue(ItemUseSemantics.sendStartItemUseOnForBow(false));
        assertFalse(ItemUseSemantics.sendItemUseOnPlayerActions(true));
        assertTrue(ItemUseSemantics.sendItemUseOnPlayerActions(false));
        assertFalse(ItemUseSemantics.sendPredictedClickBlockSlotDelta(true));
        assertTrue(ItemUseSemantics.sendPredictedClickBlockSlotDelta(false));
        assertTrue(ItemUseSemantics.skipClickBlockWhileUsing(true, true));
        assertFalse(ItemUseSemantics.skipClickBlockWhileUsing(true, false));
        assertTrue(ItemUseSemantics.skipClickBlockWhileUsing(false, true),
                "Java USE_ITEM_ON while chewing must not become MOT CLICK_BLOCK even without NetEase emulation");
        assertFalse(ItemUseSemantics.skipClickBlockWhileUsing(true, true, true));
        assertTrue(ItemUseSemantics.skipClickBlockWhileUsing(true, true, false));
        assertTrue(ItemUseSemantics.skipClickBlockWhileUsing(false, true, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(false, false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false, false));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(false, false, false, false, false, true));
        assertTrue(ItemUseSemantics.needsStandaloneUseTransaction(true, false, false, false, false, false, true));
        assertFalse(ItemUseSemantics.needsStandaloneUseTransaction(false, false, false, false, false, false, true));
        assertTrue(ItemUseSemantics.isSpear("minecraft:iron_spear", Set.of("minecraft:is_spear")));
        assertTrue(ItemUseSemantics.isSpear("minecraft:copper_spear"));
        assertFalse(ItemUseSemantics.isSpear("minecraft:trident"));
        assertEquals(ItemReleaseInventoryTransaction_ActionType.Release,
                ItemUseSemantics.releaseAction("minecraft:wooden_spear", Set.of("minecraft:is_spear"), null, 40, true));
        assertTrue(ItemUseSemantics.emulateShieldAsSneak(true, true));
        assertFalse(ItemUseSemantics.emulateShieldAsSneak(false, true));
        assertTrue(ItemUseSemantics.stopSprintingOnShieldSneakStart(true, true, true));
        assertFalse(ItemUseSemantics.stopSprintingOnShieldSneakStart(true, true, false));
        assertFalse(ItemUseSemantics.stopSprintingOnShieldSneakStart(false, true, true));
        assertTrue(ItemUseSemantics.persistSneakWhileShieldBlocking(true, true));
        assertFalse(ItemUseSemantics.persistSneakWhileShieldBlocking(true, false));
        assertFalse(ItemUseSemantics.persistSneakWhileShieldBlocking(false, true));
        assertFalse(ItemUseSemantics.sendStandaloneSpinAttackPlayerAction(true));
        assertTrue(ItemUseSemantics.sendStandaloneSpinAttackPlayerAction(false));
        assertTrue(ItemUseSemantics.rejectNetEaseOffhandUse(true, true, false));
        assertFalse(ItemUseSemantics.rejectNetEaseOffhandUse(true, true, true));
        assertTrue(ItemUseSemantics.promoteOffhandUse(true, true, false));
        assertFalse(ItemUseSemantics.promoteOffhandUse(true, true, true));
        assertFalse(ItemUseSemantics.promoteOffhandUse(false, true, false));
        assertFalse(ItemUseSemantics.promoteOffhandUse(true, false, false));
        assertTrue(ItemUseSemantics.isBowAmmo("minecraft:spectral_arrow"));
        assertTrue(ItemUseSemantics.isBowAmmo("minecraft:tipped_arrow"));
        assertTrue(ItemUseSemantics.isRegularArrow("minecraft:spectral_arrow"));
        assertFalse(ItemUseSemantics.canStartBow(true, true, false, false));
        assertTrue(ItemUseSemantics.canStartBow(true, true, false, true));
        assertTrue(ItemUseSemantics.canStartBow(true, true, true, false));
        assertFalse(ItemUseSemantics.canStartConsumable(true, "minecraft:apple", true, false, false));
        assertTrue(ItemUseSemantics.canStartConsumable(true, "minecraft:golden_apple", true, false, false));
        assertFalse(ItemUseSemantics.crossbowFireReady(true, true, 10));
        assertTrue(ItemUseSemantics.crossbowFireReady(true, true, 11));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:ender_pearl", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:snowball", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:egg", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:ender_eye", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:splash_potion", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:lingering_potion", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:experience_bottle", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:wind_charge", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:fishing_rod", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:empty_map", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:iron_helmet", Set.of("minecraft:is_armor"), true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:elytra", Set.of("minecraft:is_armor"), true));
        assertFalse(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:bow", true));
        assertFalse(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:snowball", false));
        assertFalse(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(false, "minecraft:snowball", true));
        assertTrue(ItemUseSemantics.isSpyglass("minecraft:spyglass"));
        assertFalse(ItemUseSemantics.isSpyglass("minecraft:brush"));
        assertTrue(ItemUseSemantics.chargedCrossbowUsesMotTag(true, true, false));
        assertFalse(ItemUseSemantics.chargedCrossbowUsesMotTag(true, false, true));
        assertTrue(ItemUseSemantics.chargedCrossbowUsesMotTag(false, false, true));
        assertTrue(ItemUseSemantics.matchesUseItem(true, 1, (short) 0, 9, "a", 1, (short) 0, 8, "b"));
        assertTrue(ItemUseSemantics.matchesUseItem(false, 1, (short) 0, 9, "a", 1, (short) 0, 8, "b"),
                "enchanted golden apple NBT/blockRuntimeId must not abort eating");
        assertFalse(ItemUseSemantics.matchesUseItem(true, 1, (short) 0, 9, "a", 2, (short) 0, 9, "a"));
        assertEquals(82, ItemUseSemantics.riptideDurationTicks(1));
        assertEquals(114, ItemUseSemantics.riptideDurationTicks(2));
        assertEquals(146, ItemUseSemantics.riptideDurationTicks(3));
        assertEquals(82, ItemUseSemantics.riptideDurationTicks(0));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:firework_rocket", true));
        assertFalse(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:goat_horn", true));
        assertTrue(ItemUseSemantics.isFilledPlaceBucket("minecraft:water_bucket"));
        assertTrue(ItemUseSemantics.isFilledPlaceBucket("minecraft:powder_snow_bucket"));
        assertTrue(ItemUseSemantics.isEmptyPickupBucket("minecraft:bucket"));
        assertTrue(ItemUseSemantics.isWaterSurfacePlaceItem("minecraft:oak_boat", Set.of("minecraft:boat")));
        assertTrue(ItemUseSemantics.isWaterSurfacePlaceItem("minecraft:waterlily", null));
        assertTrue(ItemUseSemantics.isWaterSurfacePlaceItem("minecraft:frog_spawn", null));
        assertTrue(ItemUseSemantics.isAirClickBlockPlaceItem("minecraft:lava_bucket", null));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:water_bucket", true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:oak_boat", Set.of("minecraft:boat"), true));
        assertTrue(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:bucket", true));
        assertFalse(ItemUseSemantics.dropDuplicateAirClickAfterUseOn(true, "minecraft:oak_boat", Set.of("minecraft:boat"), false));
        assertFalse(ItemUseSemantics.shouldStartContinuousUseFromUseItemOn(true));
        assertTrue(ItemUseSemantics.shouldStartContinuousUseFromUseItemOn(false));
        final com.viaversion.nbt.tag.CompoundTag ench = new com.viaversion.nbt.tag.CompoundTag();
        final com.viaversion.nbt.tag.ListTag<com.viaversion.nbt.tag.CompoundTag> list = new com.viaversion.nbt.tag.ListTag<>(com.viaversion.nbt.tag.CompoundTag.class);
        final com.viaversion.nbt.tag.CompoundTag riptide = new com.viaversion.nbt.tag.CompoundTag();
        riptide.putShort("id", (short) 30);
        riptide.putShort("lvl", (short) 3);
        list.add(riptide);
        ench.put("ench", list);
        assertEquals(3, ItemUseSemantics.riptideLevel(ench));
        assertEquals(1, ItemUseSemantics.riptideLevel(null));
    }

}
