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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ItemReleaseInventoryTransaction_ActionType;

import java.util.Map;
import java.util.Set;

public final class ItemUseSemantics {

    private static final int DEFAULT_CONSUMABLE_USE_TICKS = 32;
    private static final int MOT_DRIED_KELP_EATING_TICKS = 16;
    private static final String FOOD_ITEM_TAG = "minecraft:is_food";
    private static final String BOAT_ITEM_TAG = "minecraft:boat";
    private static final Map<String, Integer> MOT_CONSUMABLE_USE_TICKS = Map.of(
            "minecraft:potion", DEFAULT_CONSUMABLE_USE_TICKS,
            "minecraft:milk_bucket", DEFAULT_CONSUMABLE_USE_TICKS,
            "minecraft:ominous_bottle", DEFAULT_CONSUMABLE_USE_TICKS,
            "minecraft:dried_kelp", MOT_DRIED_KELP_EATING_TICKS
    );
    private static final Set<String> CONSUME_ON_RELEASE_ITEMS = Set.of(
            "minecraft:potion",
            "minecraft:milk_bucket",
            // MOT ItemOminousBottle.onUse() requires ticksUsed >= getUseDuration()-2 (32).
            // It is not in minecraft:is_food; without this set Java right-click is a no-op.
            "minecraft:ominous_bottle"
    );
    private static final Set<String> RELEASE_ON_RELEASE_ITEMS = Set.of(
            "minecraft:bow",
            "minecraft:crossbow",
            "minecraft:trident",
            // MOT ItemBrush has no onClickAir/canRelease. Java brushing is
            // USE_ITEM_ON against the suspicious block; treating brush as
            // hold-to-use swallowed that CLICK_BLOCK.
            "minecraft:spyglass",
            "minecraft:shield"
    );
    private static final String SPEAR_ITEM_TAG = "minecraft:is_spear";
    private static final String ARMOR_ITEM_TAG = "minecraft:is_armor";
    /**
     * MOT {@code onActivate} delegates to {@code onClickAir} for these items.
     * Java often emits USE_ITEM_ON then USE_ITEM for one right-click; the extra
     * CLICK_AIR would double-throw / double-cast / give two maps / equip twice.
     * Ref: MOT ProjectileItem, ItemFishingRod, ItemEmptyMap, ItemArmor.
     */
    private static final Set<String> DUPLICATE_CLICK_AIR_AFTER_USE_ON = Set.of(
            "minecraft:ender_pearl",
            "minecraft:ender_eye",
            "minecraft:snowball",
            "minecraft:egg",
            "minecraft:splash_potion",
            "minecraft:lingering_potion",
            "minecraft:experience_bottle",
            "minecraft:wind_charge",
            "minecraft:fishing_rod",
            "minecraft:empty_map",
            // MOT ItemFirework.onActivate places on pass-through blocks, then onClickAir
            // boosts while gliding. Java USE_ITEM_ON+USE_ITEM would consume two rockets.
            "minecraft:firework_rocket"
    );

    static boolean isContinuousUseItem(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final boolean chargedCrossbow) {
        if (identifier == null || ("minecraft:crossbow".equals(identifier) && chargedCrossbow) || isGoatHorn(identifier)) {
            return false;
        }
        return RELEASE_ON_RELEASE_ITEMS.contains(identifier)
                || isSpear(identifier, itemTags)
                || CONSUME_ON_RELEASE_ITEMS.contains(identifier)
                || isFood(itemTags)
                || itemUse != null;
    }

    public static boolean isConsumableUseItem(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse) {
        return consumableUseTicks(identifier, itemTags, itemUse) > 0;
    }

    /**
     * Official Bedrock starts food from PlayerAuthInput START_USING_ITEM +
     * PERFORM_ITEM_INTERACTION. NetEase 860 Nukkit-MOT parses that payload but never
     * handles it, so consumables need a standalone USE_ITEM transaction there.
     * Bows/crossbows already send that standalone packet on both paths.
     * MOT 860 also starts tridents and spears from CLICK_AIR
     * ({@code ItemTrident.onClickAir}, {@code ItemSpear.onClickAir}); AuthInput
     * START_USING_ITEM is parsed and ignored, so NetEase must send the same
     * standalone packet. Shields must not: MOT blocking is sneak, not use.
     */
    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow, final boolean trident,
                                                 final boolean spear) {
        return needsStandaloneUseTransaction(emulateNetEase, consumable, bow, crossbow, trident, spear, false);
    }

    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow, final boolean trident,
                                                 final boolean spear, final boolean spyglass) {
        return bow || crossbow || (emulateNetEase && (trident || spear || consumable || spyglass));
    }

    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow, final boolean trident) {
        return needsStandaloneUseTransaction(emulateNetEase, consumable, bow, crossbow, trident, false);
    }

    static boolean needsStandaloneUseTransaction(final boolean emulateNetEase, final boolean consumable,
                                                 final boolean bow, final boolean crossbow) {
        return needsStandaloneUseTransaction(emulateNetEase, consumable, bow, crossbow, false, false);
    }

    /**
     * Nukkit's MobEquipmentProcessor always calls {@code setUsingItem(false)}. Sending
     * MOB_EQUIPMENT immediately before a NetEase consumable CLICK_AIR therefore cancels
     * the using-state that the first click just started.
     */
    static boolean skipMobEquipmentBeforeUse(final boolean emulateNetEase, final boolean consumable) {
        return emulateNetEase && consumable;
    }

    /**
     * Java may emit USE_ITEM_ON then USE_ITEM for the same right-click. Nukkit 860 treats a
     * second CLICK_AIR while already using as a finish attempt, so the extra packet must be
     * dropped until the held duration is ready.
     */
    static boolean ignoreDuplicateUseStart(final boolean alreadyUsing, final boolean sameItem) {
        return alreadyUsing && sameItem;
    }

    /**
     * Official 975 starts food from AuthInput START_USING_ITEM + PERFORM_ITEM_INTERACTION.
     * NetEase 860 Nukkit parses that payload but never handles it. A second CLICK_AIR
     * while already using is treated as a finish attempt, so NetEase must not attach
     * the same ItemUseTransaction to AuthInput for any hold-to-use item
     * (food, bow, crossbow, trident, spear, shield, spyglass, brush).
     */
    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable,
                                                  final boolean bow, final boolean holdToUseWeapon) {
        if (!emulateNetEase) {
            return true;
        }
        return !consumable && !bow && !holdToUseWeapon;
    }

    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable,
                                                  final boolean bow) {
        return attachAuthInputItemInteraction(emulateNetEase, consumable, bow, false);
    }

    static boolean attachAuthInputItemInteraction(final boolean emulateNetEase, final boolean consumable) {
        return attachAuthInputItemInteraction(emulateNetEase, consumable, false, false);
    }

    /**
     * MOT CLICK_AIR compares the held stack with {@code equalsFast} (id+data only).
     * A later INVENTORY_SLOT rewrite that only changes NBT/netId/blockRuntimeId must
     * not cancel an in-progress eat/draw. Enchanted golden apples always carry NBT,
     * so even official matching has to ignore tag/blockRuntimeId once using started.
     */
    public static boolean matchesUseItem(final boolean emulateNetEase, final int snapshotId, final short snapshotData,
                                  final int snapshotBlockRuntimeId, final Object snapshotTag,
                                  final Integer currentId, final Short currentData, final Integer currentBlockRuntimeId,
                                  final Object currentTag) {
        if (currentId == null || currentData == null) {
            return false;
        }
        return snapshotId == currentId && snapshotData == currentData;
    }

    /**
     * MOT {@code ItemEdible.onClickAir} is {@code player.canEat(true)} except for
     * golden apples, chorus fruit and honey bottles, which always return true.
     * Potions/milk/ominous bottles are not ItemEdible and also start regardless of hunger.
     */
    static boolean alwaysEatsWhenFull(final String identifier) {
        return "minecraft:golden_apple".equals(identifier)
                || "minecraft:enchanted_golden_apple".equals(identifier)
                || "minecraft:chorus_fruit".equals(identifier)
                || "minecraft:honey_bottle".equals(identifier)
                || CONSUME_ON_RELEASE_ITEMS.contains(identifier);
    }

    static boolean canStartConsumable(final boolean emulateNetEase, final String identifier, final boolean consumable,
                                      final boolean creative, final boolean hungry) {
        if (!emulateNetEase || !consumable) {
            return true;
        }
        return creative || hungry || alwaysEatsWhenFull(identifier);
    }

    /**
     * MOT {@code ItemBow.getArrow} accepts any id 262 stack (plain or tipped via
     * damage). Java spectral/tipped still have to count as draw-ammo so the client
     * can start using; MOT fires 262 and treats unknown spectral as a normal arrow
     * once the stack is in the main inventory.
     */
    static boolean canStartBow(final boolean emulateNetEase, final boolean bow, final boolean creative,
                               final boolean hasBowAmmo) {
        if (!emulateNetEase || !bow) {
            return true;
        }
        return creative || hasBowAmmo;
    }

    static boolean isRegularArrow(final String identifier) {
        return isBowAmmo(identifier);
    }

    static boolean isBowAmmo(final String identifier) {
        return "minecraft:arrow".equals(identifier)
                || "minecraft:tipped_arrow".equals(identifier)
                || "minecraft:spectral_arrow".equals(identifier);
    }

    static boolean isShield(final String identifier) {
        return "minecraft:shield".equals(identifier);
    }

    static boolean isSpyglass(final String identifier) {
        return "minecraft:spyglass".equals(identifier);
    }

    /**
     * MOT {@code ItemGoatHorn.onClickAir} is a one-shot cooldown (140 ticks),
     * not hold-to-use. Resource-pack {@code use_duration} must not swallow that
     * CLICK_AIR into {@code beginContinuousItemUse}.
     */
    static boolean isGoatHorn(final String identifier) {
        return "minecraft:goat_horn".equals(identifier);
    }

    /**
     * MOT {@code ItemSpear.onClickAir} starts using and {@code canRelease()} is true.
     * Java 1.21 spears are also hold-to-use; without this mapping a right-click
     * becomes an instant CLICK_AIR and never reaches {@code onRelease}/stab.
     * Prefer the Bedrock {@code minecraft:is_spear} tag, then identifier suffix.
     */
    static boolean isSpear(final String identifier, final Set<String> itemTags) {
        if (itemTags != null && itemTags.contains(SPEAR_ITEM_TAG)) {
            return true;
        }
        return identifier != null && identifier.endsWith("_spear");
    }

    static boolean isSpear(final String identifier) {
        return isSpear(identifier, null);
    }

    /**
     * MOT {@code ItemShield} has no onClickAir. Blocking is sneak-or-riding + shield
     * in either hand. NetEase must not send a use transaction for a shield.
     */
    static boolean emulateShieldAsSneak(final boolean emulateNetEase, final boolean shield) {
        return emulateNetEase && shield;
    }

    /**
     * MOT processes START_SPRINTING before START_SNEAKING and does not clear
     * an already-sprinting player. Java shield-block is standing, so the
     * shield-as-sneak start must emit StopSprinting when the player is sprinting.
     */
    static boolean stopSprintingOnShieldSneakStart(final boolean emulateNetEase, final boolean shield, final boolean sprinting) {
        return emulateShieldAsSneak(emulateNetEase, shield) && sprinting;
    }

    /**
     * MOT {@code updateBlockingFlag} requires sneak-or-riding + shield. Java
     * right-click block is standing, so ViaBedrock still sends StartSneaking.
     * MOT {@code EntityHuman.getHeight()} then short-sneaks to 1.49. PersistSneak
     * is unused on MOT 860 AuthInput; NukkitMOTJE treats it as "shield sneak" and
     * restores the standing AABB. Official 975 does not need this bit.
     * Ref: MOT Player.updateBlockingFlag; EntityHuman.getHeight isShortSneaking.
     */
    public static boolean persistSneakWhileShieldBlocking(final boolean emulateNetEase, final boolean shieldSneakEmulated) {
        return emulateNetEase && shieldSneakEmulated;
    }

    /**
     * MOT AuthInput START/STOP_SPIN_ATTACK is the SAI path. Standalone
     * PlayerAction 23 has no case (default setUsingItem(false) at 3874);
     * PlayerAction 24 on SAI ≥748 also breaks into that default. Keep the
     * AuthInput bits; skip the extra PlayerAction on NetEase.
     */
    public static boolean sendStandaloneSpinAttackPlayerAction(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * MOT {@code ItemCrossbow.launchArrow} requires {@code serverTick - loadTick > 10}.
     * Firing on the same tick charge completes starts a new charge instead of shooting.
     */
    static boolean crossbowFireReady(final boolean emulateNetEase, final boolean charged, final int ticksSinceCharge) {
        if (!emulateNetEase || !charged) {
            return true;
        }
        return ticksSinceCharge > 10;
    }

    static boolean chargedCrossbowUsesMotTag(final boolean emulateNetEase, final boolean hasChargedItem,
                                             final boolean hasJavaChargedProjectiles) {
        return emulateNetEase ? hasChargedItem : (hasChargedItem || hasJavaChargedProjectiles);
    }

    /**
     * Java may emit USE_ITEM_ON then USE_ITEM for one click. MOT items whose
     * {@code onActivate} calls {@code onClickAir} would then double-apply.
     */
    static boolean dropDuplicateAirClickAfterUseOn(final boolean emulateNetEase, final String identifier,
                                                   final boolean sameTickUseOn) {
        return dropDuplicateAirClickAfterUseOn(emulateNetEase, identifier, null, sameTickUseOn);
    }

    static boolean dropDuplicateAirClickAfterUseOn(final boolean emulateNetEase, final String identifier,
                                                   final Set<String> itemTags, final boolean sameTickUseOn) {
        if (!emulateNetEase || !sameTickUseOn || identifier == null) {
            return false;
        }
        if (DUPLICATE_CLICK_AIR_AFTER_USE_ON.contains(identifier)) {
            return true;
        }
        if (isAirClickBlockPlaceItem(identifier, itemTags) || isEmptyPickupBucket(identifier) || isGlassBottle(identifier)) {
            return true;
        }
        return itemTags != null && itemTags.contains(ARMOR_ITEM_TAG);
    }

    /**
     * MOT {@code Player.onSpinAttack}: {@code riptideTicks = 50 + (level << 5)}.
     * Unknown level uses 1 so the proxy never leaves spin latched forever.
     */
    public static int riptideDurationTicks(final int riptideLevel) {
        final int level = riptideLevel > 0 ? riptideLevel : 1;
        return 50 + (level << 5);
    }

    /**
     * MOT {@code ItemTrident.onRelease} reads {@code Enchantment.getEnchantmentLevel(RIPTIDE)}.
     * Bedrock stores that as a short {@code id}/{@code lvl} pair in {@code ench}.
     */
    public static int riptideLevel(final com.viaversion.nbt.tag.CompoundTag tag) {
        if (tag == null) {
            return 1;
        }
        if (!(tag.get("ench") instanceof com.viaversion.nbt.tag.ListTag<?> enchantments)) {
            return 1;
        }
        int level = 0;
        for (final com.viaversion.nbt.tag.Tag enchantment : enchantments) {
            if (!(enchantment instanceof com.viaversion.nbt.tag.CompoundTag compoundTag)) {
                continue;
            }
            if (!(compoundTag.get("id") instanceof com.viaversion.nbt.tag.NumberTag idTag)
                    || !(compoundTag.get("lvl") instanceof com.viaversion.nbt.tag.NumberTag lvlTag)) {
                continue;
            }
            if (idTag.asInt() == net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.Enchant_Type.Riptide.getValue()) {
                level = Math.max(level, lvlTag.asInt());
            }
        }
        return level > 0 ? level : 1;
    }

    /**
     * MOT {@code Level.useItemOn} activates the block first unless sneaking.
     * Hold-to-use (food/bow/shield) must start from Java {@code USE_ITEM}, not {@code USE_ITEM_ON}.
     */
    public static boolean shouldStartContinuousUseFromUseItemOn(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    static boolean isFilledPlaceBucket(final String identifier) {
        return "minecraft:water_bucket".equals(identifier)
                || "minecraft:lava_bucket".equals(identifier)
                || "minecraft:powder_snow_bucket".equals(identifier)
                || "minecraft:cod_bucket".equals(identifier)
                || "minecraft:salmon_bucket".equals(identifier)
                || "minecraft:tropical_fish_bucket".equals(identifier)
                || "minecraft:pufferfish_bucket".equals(identifier)
                || "minecraft:axolotl_bucket".equals(identifier)
                || "minecraft:tadpole_bucket".equals(identifier);
    }

    static boolean isEmptyPickupBucket(final String identifier) {
        return "minecraft:bucket".equals(identifier);
    }

    static boolean isGlassBottle(final String identifier) {
        return "minecraft:glass_bottle".equals(identifier);
    }

    /**
     * Java {@code BoatItem} / {@code PlaceOnWaterBlockItem} ray-miss as {@code USE_ITEM}.
     * MOT only places from {@code onActivate} (CLICK_BLOCK) against the water surface.
     */
    static boolean isWaterSurfacePlaceItem(final String identifier, final Set<String> itemTags) {
        if (identifier == null) {
            return false;
        }
        if ("minecraft:waterlily".equals(identifier)
                || "minecraft:lily_pad".equals(identifier)
                || "minecraft:frog_spawn".equals(identifier)
                || "minecraft:frogspawn".equals(identifier)) {
            return true;
        }
        return itemTags != null && itemTags.contains(BOAT_ITEM_TAG);
    }

    static boolean isAirClickBlockPlaceItem(final String identifier, final Set<String> itemTags) {
        return isFilledPlaceBucket(identifier) || isWaterSurfacePlaceItem(identifier, itemTags);
    }

    /**
     * MOT {@code Item.java} defaults {@code canRelease()==false} / {@code getUseDuration()==0}.
     * Resource-pack {@code ItemUseDefinition} therefore never reaches {@code processAutoCompletion}
     * unless ViaBedrock sends a duration-ready second CLICK_AIR. Vanilla MOT foods/potions still
     * auto-complete and must not get that extra click.
     */
    static boolean motAutoCompletesConsumable(final String identifier, final Set<String> itemTags) {
        return CONSUME_ON_RELEASE_ITEMS.contains(identifier) || isFood(itemTags);
    }

    /**
     * MOT CLICK_AIR / CLICK_BLOCK always call {@code inventory.getItemInHand()}
     * after {@code equipItem(hotbarSlot)}. {@code equipItem} rejects {@code < 0},
     * so an offhand stack in {@code itemInHand} would still consume the main hand.
     * Non-shield offhand use is promoted with a silent F-swap first.
     * Shield stays sneak-emulation and must not swap.
     * Ref: MOT Player.java case 1 CLICK_AIR; PlayerInventory.equipItem.
     */
    static boolean rejectNetEaseOffhandUse(final boolean emulateNetEase, final boolean offhand, final boolean shield) {
        return emulateNetEase && offhand && !shield;
    }

    static boolean promoteOffhandUse(final boolean emulateNetEase, final boolean offhand, final boolean shield) {
        return emulateNetEase && offhand && !shield;
    }

    /**
     * MOT Player.java never handles {@code StartItemUseOn} / {@code StopItemUseOn}
     * ({@code ACTION_START_ITEM_USE_ON=28}, {@code ACTION_STOP_ITEM_USE_ON=29}).
     * The 860 PlayerAction switch falls through to {@code setUsingItem(false)}, so a
     * Java USE_ITEM_ON while chewing/drawing would cancel MOT auto-complete.
     * Official 975 still expects those actions around CLICK_BLOCK.
     */
    static boolean sendItemUseOnPlayerActions(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * Native MOT 860 CLICK_BLOCK typically has an empty {@code actions[]} list. MOT
     * {@code handleInventoryTransactionPacket} parses every action before case 2
     * ({@code TYPE_USE_ITEM}); a fabricated SOURCE_CONTAINER decrement whose window
     * or {@code equalsExact} snapshot does not match returns immediately and never
     * runs {@code useItemOn}. GanAC {@code validateActionSources} also treats a
     * mismatched predicted decrement as SOURCE_ITEM_MISMATCH. Official 975 can keep
     * the predicted slot delta.
     * Ref: MOT Player.java 4264-4270 / case 2; NetworkInventoryAction.createInventoryAction.
     */
    static boolean sendPredictedClickBlockSlotDelta(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * MOT USE_ITEM CLICK_BLOCK ({@code actionType=0}) always calls
     * {@code setUsingItem(false)} before {@code Level.useItemOn}. Java Fabric
     * keeps sending USE_ITEM_ON at the crosshair while chewing/drawing; a
     * CLICK_BLOCK would cancel MOT auto-complete after 1 tick. Shield-block is
     * sneak-emulated, not MOT using-item, so those clicks must still reach
     * chests/buttons. Do this whenever the proxy is already using, not only when
     * NetEase emulation is on: production logs showed {@code false -> true} then
     * a silent {@code true -> false} on the next tick with no CLICK_AIR finish.
     * Ref: MOT Player.java case 2 / actionType 0.
     */
    static boolean skipClickBlockWhileUsing(final boolean emulateNetEase, final boolean usingItem) {
        return skipClickBlockWhileUsing(emulateNetEase, usingItem, false);
    }

    static boolean skipClickBlockWhileUsing(final boolean emulateNetEase, final boolean usingItem,
                                            final boolean shieldSneakEmulated) {
        return usingItem && !shieldSneakEmulated;
    }

    /**
     * MOT Player.java never handles {@code StartItemUseOn}. Sending it on NetEase bow
     * start is unused noise; keep it on official 975 only.
     */
    static boolean sendStartItemUseOnForBow(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * Official 975 still finishes food with a second USE_ITEM plus delayed ItemRelease.
     * Nukkit 860 treats a second CLICK_AIR as {@code onUse(ticksUsed)} and always clears
     * using-state first. If that packet arrives before {@code eatingTick} (apple = 31),
     * {@code onUse} fails and {@code processAutoCompletion()} can never consume.
     * <p>
     * Java chewing is hidden separately by {@code javaUsingVisible()}. NetEase therefore
     * must not send a finish click at all and let Nukkit auto-complete while protocol
     * using-state stays true.
     */
    static boolean sendConsumableFinishTransaction(final boolean emulateNetEase, final boolean consumable) {
        return sendConsumableFinishTransaction(emulateNetEase, consumable, true);
    }

    static boolean sendConsumableFinishTransaction(final boolean emulateNetEase, final boolean consumable,
                                                   final boolean motAutoCompletes) {
        if (!consumable) {
            return false;
        }
        return !emulateNetEase || !motAutoCompletes;
    }

    /**
     * Official 975 still follows that second USE_ITEM with a delayed ItemRelease; Nukkit
     * 860 ignores ItemRelease Consume here, so NetEase must not send it.
     */
    static boolean delayReleaseAfterConsumableFinish(final boolean emulateNetEase, final boolean consumable) {
        return consumable && !emulateNetEase;
    }

    /**
     * Official 975 can drop the local using-state as soon as the finish packet is queued.
     * NetEase still has to keep protocol-side using-state until the finish USE_ITEM is
     * actually sent, otherwise START_SPRINTING / MOB_EQUIPMENT cancel Nukkit first.
     * Java chewing itself is driven separately by {@code javaUsingVisible()}.
     */
    static boolean keepLocalUsingAfterConsumableFinish(final boolean emulateNetEase, final boolean consumable, final boolean finishSent) {
        return emulateNetEase && consumable && !finishSent;
    }

    /**
     * A duration-ready consumable release is completed by CLIENT_TICK_END and must not
     * send a second cancel transaction. Any early release is a real interrupt and must
     * reach MOT so its {@code TYPE_RELEASE_ITEM} handler can clear using-state.
     */
    static boolean ignoreJavaConsumableRelease(final boolean emulateNetEase, final boolean consumable) {
        return ignoreJavaConsumableRelease(emulateNetEase, consumable, false);
    }

    static boolean ignoreJavaConsumableRelease(final boolean emulateNetEase, final boolean consumable,
                                               final boolean durationReady) {
        return emulateNetEase && consumable && durationReady;
    }

    static boolean ignoreJavaConsumableRelease(final boolean emulateNetEase, final boolean consumable,
                                               final boolean durationReady, final int usingTicks) {
        return ignoreJavaConsumableRelease(emulateNetEase, consumable, durationReady);
    }

    /**
     * A mismatched use snapshot is a real cancellation, including during the first tick.
     * Send it to MOT instead of leaving a stale server-side using latch.
     */
    static boolean sendCancelRelease(final boolean emulateNetEase, final boolean consumable, final int usingTicks) {
        return true;
    }

    static boolean consumableConsumedByServer(final int startedId, final int startedAmount,
                                             final boolean currentEmpty, final int currentId, final int currentAmount) {
        if (startedId == 0 || startedAmount <= 0) {
            return false;
        }
        if (currentEmpty || currentId == 0) {
            return true;
        }
        return currentId == startedId && currentAmount < startedAmount;
    }

    static boolean localUsingTimedOut(final boolean emulateNetEase, final boolean consumable, final int usingTicks) {
        return localUsingTimedOut(emulateNetEase, consumable, usingTicks, DEFAULT_CONSUMABLE_USE_TICKS);
    }

    static boolean localUsingTimedOut(final boolean emulateNetEase, final boolean consumable, final int usingTicks,
                                      final int useDurationTicks) {
        final int duration = useDurationTicks > 0 ? useDurationTicks : DEFAULT_CONSUMABLE_USE_TICKS;
        return emulateNetEase && consumable && usingTicks >= duration + 16;
    }

    /**
     * Java's eat animation is local. Once the vanilla duration has elapsed, stop rewriting
     * LIVING_ENTITY_FLAGS so a later SET_ENTITY_DATA cannot restart chewing after the
     * client already released the item.
     */
    public static boolean javaUsingVisible(final boolean usingItem, final boolean consumable, final int usingTicks) {
        return javaUsingVisible(usingItem, consumable, usingTicks, DEFAULT_CONSUMABLE_USE_TICKS);
    }

    public static boolean javaUsingVisible(final boolean usingItem, final boolean consumable, final int usingTicks,
                                           final int useDurationTicks) {
        final int duration = useDurationTicks > 0 ? useDurationTicks : DEFAULT_CONSUMABLE_USE_TICKS;
        return usingItem && (!consumable || usingTicks < duration);
    }

    public static int consumableUseTicks(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse) {
        if (identifier != null && MOT_CONSUMABLE_USE_TICKS.containsKey(identifier)) {
            return MOT_CONSUMABLE_USE_TICKS.get(identifier);
        }
        if (itemUse != null) {
            return itemUse.useDurationTicks();
        }
        if (isFood(itemTags) || CONSUME_ON_RELEASE_ITEMS.contains(identifier)) {
            // MOT Food.eatingTick is 31; NetEase finishReadyTicks subtracts 1 so apple
            // auto-completes at 31. Dried kelp is overridden above to 16.
            return DEFAULT_CONSUMABLE_USE_TICKS;
        }
        return -1;
    }

    /**
     * Nukkit START_SPRINTING always calls {@code setUsingItem(false)}. Java starts sprinting
     * from movement even while the eating animation is held, so NetEase must drop that
     * command until the consumable finishes.
     */
    public static boolean suppressStartSprintingWhileUsingItem(final boolean emulateNetEase, final boolean usingItem) {
        return emulateNetEase && usingItem;
    }

    static ItemReleaseInventoryTransaction_ActionType releaseAction(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final int usingTicks) {
        return releaseAction(identifier, itemTags, itemUse, usingTicks, false);
    }

    static ItemReleaseInventoryTransaction_ActionType releaseAction(final String identifier, final Set<String> itemTags, final ItemUseDefinition itemUse, final int usingTicks, final boolean emulateNetEase) {
        if (identifier == null || RELEASE_ON_RELEASE_ITEMS.contains(identifier) || isSpear(identifier, itemTags)) {
            return ItemReleaseInventoryTransaction_ActionType.Release;
        }

        final int useDurationTicks = consumableUseTicks(identifier, itemTags, itemUse);
        return useDurationTicks > 0 && usingTicks >= finishReadyTicks(useDurationTicks, emulateNetEase)
                ? ItemReleaseInventoryTransaction_ActionType.Use
                : ItemReleaseInventoryTransaction_ActionType.Release;
    }

    /**
     * CLIENT_TICK_END inspects {@code usingItemTicks} before {@code Entity.age} advances.
     * Nukkit counts {@code serverTick - startAction} after the same game tick, so duration
     * {@code N} is ready at {@code N - 1} Java ticks.
     */
    private static int finishReadyTicks(final int useDurationTicks, final boolean emulateNetEase) {
        if (!emulateNetEase) {
            return useDurationTicks;
        }
        // CLIENT_TICK_END inspects usingItemTicks before Entity.age advances. Official 975
        // still finishes at the configured duration; NetEase 860 needs the packet one tick
        // earlier so Nukkit's serverTick - startAction reaches eatingTick (apple = 31).
        return Math.max(1, useDurationTicks - 1);
    }

    private static boolean isFood(final Set<String> itemTags) {
        return itemTags != null && itemTags.contains(FOOD_ITEM_TAG);
    }

    private ItemUseSemantics() {
    }

}
