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
            // MOT 的 ItemOminousBottle.onUse() 要求 ticksUsed >= getUseDuration()-2（32）。
            // 它不在 minecraft:is_food 里；没有这个集合，Java 右键就是空操作。
            "minecraft:ominous_bottle"
    );
    private static final Set<String> RELEASE_ON_RELEASE_ITEMS = Set.of(
            "minecraft:bow",
            "minecraft:crossbow",
            "minecraft:trident",
            // MOT 的 ItemBrush 没有 onClickAir/canRelease。Java 的刷扫是
            // 针对可疑方块的 USE_ITEM_ON；把刷子当作按住使用会吞掉该 CLICK_BLOCK。
            "minecraft:spyglass",
            "minecraft:shield"
    );
    private static final String SPEAR_ITEM_TAG = "minecraft:is_spear";
    private static final String ARMOR_ITEM_TAG = "minecraft:is_armor";
    /**
     * 对这些物品，MOT 的 {@code onActivate} 会转发到 {@code onClickAir}。
     * Java 经常为一次右键先发 USE_ITEM_ON 再发 USE_ITEM；多余的
     * CLICK_AIR 会导致双重投掷 / 双重施法 / 得到两张地图 / 装备两次。
     * 参考：MOT 的 ProjectileItem、ItemFishingRod、ItemEmptyMap、ItemArmor。
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
            // MOT 的 ItemFirework.onActivate 会放在可穿透方块上，随后在滑翔时
            // 由 onClickAir 加速。Java 的 USE_ITEM_ON+USE_ITEM 会消耗两支烟花。
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
     * 官方基岩通过 PlayerAuthInput 的 START_USING_ITEM 加
     * PERFORM_ITEM_INTERACTION 开始进食。网易 860 的 Nukkit-MOT 会解析该 payload 但从不
     * 处理它，因此可消耗物品在那里需要独立的 USE_ITEM 事务。
     * 弓/弩在两条路径上本来就已发送该独立数据包。
     * MOT 860 还会从 CLICK_AIR 开始三叉戟与长矛
     * （{@code ItemTrident.onClickAir}、{@code ItemSpear.onClickAir}）；AuthInput 的
     * START_USING_ITEM 会被解析并忽略，因此网易侧必须发送同样的
     * 独立数据包。盾牌不能这样：MOT 的格挡是潜行，不是使用。
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
     * Nukkit 的 MobEquipmentProcessor 总会调用 {@code setUsingItem(false)}。因此在网易可消耗物品的
     * CLICK_AIR 之前紧接着发送 MOB_EQUIPMENT，会取消第一次点击刚开始的使用状态。
     */
    static boolean skipMobEquipmentBeforeUse(final boolean emulateNetEase, final boolean consumable) {
        return emulateNetEase && consumable;
    }

    /**
     * Java 可能为同一次右键先发 USE_ITEM_ON 再发 USE_ITEM。Nukkit 860 会把已在使用中的
     * 第二次 CLICK_AIR 当作完成尝试，因此在持有时长达到之前必须
     * 丢弃多余的那个数据包。
     */
    static boolean ignoreDuplicateUseStart(final boolean alreadyUsing, final boolean sameItem) {
        return alreadyUsing && sameItem;
    }

    /**
     * 官方 975 通过 AuthInput 的 START_USING_ITEM 加 PERFORM_ITEM_INTERACTION 开始进食。
     * 网易 860 的 Nukkit 会解析该 payload 但从不处理它。已在使用中的第二次 CLICK_AIR
     * 会被当作完成尝试，因此网易侧不能把同一个 ItemUseTransaction 挂到 AuthInput 上，
     * 对任何按住使用的物品都不行
     * （食物、弓、弩、三叉戟、长矛、盾牌、望远镜、刷子）。
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
     * MOT 的 CLICK_AIR 用 {@code equalsFast}（仅 id+data）比较手持物品堆。
     * 之后只改动 NBT/netId/blockRuntimeId 的 INVENTORY_SLOT 重写，
     * 不能取消正在进行的进食/拉弓。附魔金苹果总是带 NBT，
     * 所以即便用官方匹配，一旦开始使用也必须忽略 tag/blockRuntimeId。
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
     * MOT 的 {@code ItemEdible.onClickAir} 相当于 {@code player.canEat(true)}，
     * 但金苹果、紫颂果和蜂蜜瓶例外，它们总是返回 true。
     * 药水/牛奶/不祥之瓶不是 ItemEdible，也同样无视饥饿度直接开始。
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
     * MOT 的 {@code ItemBow.getArrow} 接受任何 id 为 262 的物品堆（普通，或通过
     * damage 指定的药箭）。Java 的光灵箭/药箭仍须算作拉弓弹药，好让客户端
     * 能开始使用；只要该堆在主物品栏里，MOT 就会发射 262，并把未知的光灵箭当作普通箭。
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
     * MOT 的 {@code ItemGoatHorn.onClickAir} 是一次性冷却（140 刻），
     * 不是按住使用。资源包的 {@code use_duration} 不能把该
     * CLICK_AIR 吞进 {@code beginContinuousItemUse}。
     */
    static boolean isGoatHorn(final String identifier) {
        return "minecraft:goat_horn".equals(identifier);
    }

    /**
     * MOT 的 {@code ItemSpear.onClickAir} 会开始使用，且 {@code canRelease()} 为 true。
     * Java 1.21 的长矛同样是按住使用；没有这条映射，右键
     * 会变成瞬时的 CLICK_AIR，永远到不了 {@code onRelease}/突刺。
     * 优先用基岩的 {@code minecraft:is_spear} 标签，其次看标识符后缀。
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
     * MOT 的 {@code ItemShield} 没有 onClickAir。格挡是潜行或骑乘加
     * 任一只手持盾。网易侧不得为盾牌发送使用事务。
     */
    static boolean emulateShieldAsSneak(final boolean emulateNetEase, final boolean shield) {
        return emulateNetEase && shield;
    }

    /**
     * MOT 先处理 START_SPRINTING 再处理 START_SNEAKING，且不会清除
     * 已在疾跑的玩家。Java 的盾牌格挡是站立姿态，因此
     * 盾牌当潜行的开始操作在玩家疾跑时必须发送 StopSprinting。
     */
    static boolean stopSprintingOnShieldSneakStart(final boolean emulateNetEase, final boolean shield, final boolean sprinting) {
        return emulateShieldAsSneak(emulateNetEase, shield) && sprinting;
    }

    /**
     * MOT 的 {@code updateBlockingFlag} 要求潜行或骑乘加盾牌。Java
     * 右键方块是站立姿态，因此 ViaBedrock 仍会发送 StartSneaking。
     * 随后 MOT 的 {@code EntityHuman.getHeight()} 会短潜行到 1.49。PersistSneak
     * 在 MOT 860 的 AuthInput 中未被使用；NukkitMOTJE 把它当作盾牌潜行，
     * 并恢复站立的 AABB。官方 975 不需要这一位。
     * 参考：MOT Player.updateBlockingFlag；EntityHuman.getHeight 的 isShortSneaking。
     */
    public static boolean persistSneakWhileShieldBlocking(final boolean emulateNetEase, final boolean shieldSneakEmulated) {
        return emulateNetEase && shieldSneakEmulated;
    }

    /**
     * MOT 的 AuthInput START/STOP_SPIN_ATTACK 是 SAI 路径。独立的
     * PlayerAction 23 没有对应 case（在 3874 行落到默认的 setUsingItem(false)）；
     * PlayerAction 24 在 SAI 上也会转入该默认分支。保留
     * AuthInput 的那几位；在网易侧跳过额外的 PlayerAction。
     */
    public static boolean sendStandaloneSpinAttackPlayerAction(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * MOT 的 {@code ItemCrossbow.launchArrow} 要求 {@code serverTick - loadTick > 10}。
     * 在装填完成的同一刻开火会开始新的装填而不是射击。
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
     * Java 可能为一次点击先发 USE_ITEM_ON 再发 USE_ITEM。那些 {@code onActivate}
     * 会调用 {@code onClickAir} 的 MOT 物品就会重复生效。
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
     * MOT 的 {@code Player.onSpinAttack}：{@code riptideTicks = 50 + (level << 5)}。
     * 未知等级按 1 处理，这样代理不会让旋转状态永久锁存。
     */
    public static int riptideDurationTicks(final int riptideLevel) {
        final int level = riptideLevel > 0 ? riptideLevel : 1;
        return 50 + (level << 5);
    }

    /**
     * MOT 的 {@code ItemTrident.onRelease} 读取 {@code Enchantment.getEnchantmentLevel(RIPTIDE)}。
     * 基岩把它存为 {@code ench} 中的 short {@code id}/{@code lvl} 对。
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
     * MOT 的 {@code Level.useItemOn} 除非潜行，否则会先激活方块。
     * 按住使用（食物/弓/盾）必须由 Java 的 {@code USE_ITEM} 开始，而不是 {@code USE_ITEM_ON}。
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
     * Java 的 {@code BoatItem} / {@code PlaceOnWaterBlockItem} 射线未命中时表现为 {@code USE_ITEM}。
     * MOT 只从针对水面的 {@code onActivate}（CLICK_BLOCK）放置。
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
     * MOT 的 {@code Item.java} 默认 {@code canRelease()==false} / {@code getUseDuration()==0}。
     * 因此除非 ViaBedrock 发送一次达到时长的第二次 CLICK_AIR，资源包的 {@code ItemUseDefinition}
     * 永远到不了 {@code processAutoCompletion}。原版 MOT 的食物/药水仍会
     * 自动完成，不能给它们那额外的点击。
     */
    static boolean motAutoCompletesConsumable(final String identifier, final Set<String> itemTags) {
        return CONSUME_ON_RELEASE_ITEMS.contains(identifier) || isFood(itemTags);
    }

    /**
     * MOT 的 CLICK_AIR / CLICK_BLOCK 在 {@code equipItem(hotbarSlot)} 之后总会调用
     * {@code inventory.getItemInHand()}。{@code equipItem} 拒绝 {@code < 0}，
     * 因此 {@code itemInHand} 里的副手物品堆仍会消耗主手。
     * 非盾牌的副手使用会先用静默 F 交换提升。
     * 盾牌仍走潜行模拟，不得交换。
     * 参考：MOT Player.java case 1 CLICK_AIR；PlayerInventory.equipItem。
     */
    static boolean rejectNetEaseOffhandUse(final boolean emulateNetEase, final boolean offhand, final boolean shield) {
        return emulateNetEase && offhand && !shield;
    }

    static boolean promoteOffhandUse(final boolean emulateNetEase, final boolean offhand, final boolean shield) {
        return emulateNetEase && offhand && !shield;
    }

    /**
     * MOT 的 Player.java 从不处理 {@code StartItemUseOn} / {@code StopItemUseOn}
     * （{@code ACTION_START_ITEM_USE_ON=28}、{@code ACTION_STOP_ITEM_USE_ON=29}）。
     * 860 的 PlayerAction switch 会落到 {@code setUsingItem(false)}，因此 Java 在咀嚼/拉弓时
     * 的 USE_ITEM_ON 会取消 MOT 的自动完成。
     * 官方 975 仍期望 CLICK_BLOCK 前后有这些动作。
     */
    static boolean sendItemUseOnPlayerActions(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * 原生 MOT 860 的 CLICK_BLOCK 通常带一个空的 {@code actions[]} 列表。MOT 的
     * {@code handleInventoryTransactionPacket} 会在 case 2
     * （{@code TYPE_USE_ITEM}）之前解析每个动作；一个伪造的 SOURCE_CONTAINER 扣减若窗口
     * 或 {@code equalsExact} 快照不匹配，就会立即返回，永远不
     * 执行 {@code useItemOn}。GanAC 的 {@code validateActionSources} 也会把
     * 不匹配的预测扣减当作 SOURCE_ITEM_MISMATCH。官方 975 可以保留
     * 预测的槽位增量。
     * 参考：MOT Player.java 4264-4270 / case 2；NetworkInventoryAction.createInventoryAction。
     */
    static boolean sendPredictedClickBlockSlotDelta(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * MOT 的 USE_ITEM CLICK_BLOCK（{@code actionType=0}）总会在 {@code Level.useItemOn} 之前
     * 调用 {@code setUsingItem(false)}。Java Fabric 在咀嚼/拉弓时会
     * 持续向准星发送 USE_ITEM_ON；一次 CLICK_BLOCK 会让 MOT 的自动完成在 1 刻后被取消。
     * 盾牌格挡是潜行模拟的，不是 MOT 的使用物品状态，因此这些点击仍须到达
     * 箱子/按钮。只要代理已处于使用状态就要这么做，而不只在开启
     * 网易模拟时：生产日志显示先出现 {@code false -> true}，下一刻又静默变成
     * {@code true -> false}，且没有 CLICK_AIR 完成。
     * 参考：MOT Player.java case 2 / actionType 0。
     */
    static boolean skipClickBlockWhileUsing(final boolean emulateNetEase, final boolean usingItem) {
        return skipClickBlockWhileUsing(emulateNetEase, usingItem, false);
    }

    static boolean skipClickBlockWhileUsing(final boolean emulateNetEase, final boolean usingItem,
                                            final boolean shieldSneakEmulated) {
        return usingItem && !shieldSneakEmulated;
    }

    /**
     * MOT 的 Player.java 从不处理 {@code StartItemUseOn}。在网易侧的弓开始操作上
     * 发送它只是无用噪声；只在官方 975 上保留。
     */
    static boolean sendStartItemUseOnForBow(final boolean emulateNetEase) {
        return !emulateNetEase;
    }

    /**
     * 官方 975 仍用第二次 USE_ITEM 加延迟的 ItemRelease 完成进食。
     * Nukkit 860 会把第二次 CLICK_AIR 当作 {@code onUse(ticksUsed)}，并总会先清除
     * 使用状态。若该数据包在 {@code eatingTick}（苹果为 31）之前到达，
     * {@code onUse} 就会失败，{@code processAutoCompletion()} 永远无法消耗物品。
     * <p>
     * Java 侧的咀嚼由 {@code javaUsingVisible()} 单独隐藏。因此网易侧
     * 完全不必发送完成点击，让 Nukkit 在协议侧使用状态保持 true 时
     * 自行自动完成。
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
     * 官方 975 在该第二次 USE_ITEM 之后还会跟一个延迟的 ItemRelease；Nukkit
     * 860 在此处忽略 ItemRelease Consume，因此网易侧不能发送它。
     */
    static boolean delayReleaseAfterConsumableFinish(final boolean emulateNetEase, final boolean consumable) {
        return consumable && !emulateNetEase;
    }

    /**
     * 官方 975 可以在完成包入队后立即丢弃本地使用状态。
     * 网易侧仍必须保持协议侧使用状态，直到完成用的 USE_ITEM
     * 真正发出，否则 START_SPRINTING / MOB_EQUIPMENT 会先把它取消。
     * Java 侧的咀嚼本身由 {@code javaUsingVisible()} 单独驱动。
     */
    static boolean keepLocalUsingAfterConsumableFinish(final boolean emulateNetEase, final boolean consumable, final boolean finishSent) {
        return emulateNetEase && consumable && !finishSent;
    }

    /**
     * 达到时长的可消耗物品释放由 CLIENT_TICK_END 完成，不得
     * 再发送第二次取消事务。任何提前释放都是真正的打断，必须
     * 发往 MOT，好让它的 {@code TYPE_RELEASE_ITEM} 处理器清除使用状态。
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
     * 使用快照不匹配属于真正的取消，第一个刻内也一样。
     * 应把它发送给 MOT，而不是留下一个过期的服务端 using 闩锁。
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
     * Java 的进食动画是本地计算的。原版时长走完后，不要再重写 LIVING_ENTITY_FLAGS，
     * 这样后续的 SET_ENTITY_DATA 就不会在客户端已经松开物品后重新开始咀嚼。
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
            // MOT Food.eatingTick 为 31；网易的 finishReadyTicks 会减去 1，因此苹果在 31 时自动完成。干海带在上面被覆写为 16。
            return DEFAULT_CONSUMABLE_USE_TICKS;
        }
        return -1;
    }

    /**
     * Nukkit 的 START_SPRINTING 总是调用 {@code setUsingItem(false)}。Java 会因移动而开始疾跑，即使进食动画仍在保持，
     * 所以网易端必须丢弃该命令，直到消耗品使用结束。
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
     * CLIENT_TICK_END 在 {@code Entity.age} 前进之前检查 {@code usingItemTicks}。
     * Nukkit 在同一个游戏刻之后统计 {@code serverTick - startAction}，因此时长 {@code N} 在 {@code N - 1} 个 Java 刻时就已就绪。
     */
    private static int finishReadyTicks(final int useDurationTicks, final boolean emulateNetEase) {
        if (!emulateNetEase) {
            return useDurationTicks;
        }
        // CLIENT_TICK_END 在 Entity.age 前进之前检查 usingItemTicks。官方 975 仍在配置的时长处结束；
        // 网易 860 需要提前一个刻发送数据包，这样 Nukkit 的 serverTick - startAction 才能达到 eatingTick（苹果 = 31）。
        return Math.max(1, useDurationTicks - 1);
    }

    private static boolean isFood(final Set<String> itemTags) {
        return itemTags != null && itemTags.contains(FOOD_ITEM_TAG);
    }

    private ItemUseSemantics() {
    }

}
