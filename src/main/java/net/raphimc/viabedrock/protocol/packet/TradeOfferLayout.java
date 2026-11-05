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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.nbt.tag.ListTag;
import com.viaversion.nbt.tag.NumberTag;
import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.minecraft.item.Item;
import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.ArrayList;
import java.util.List;

/**
 * MOT {@code UpdateTradePacket.offers} 是网络小端复合标签，其
 * {@code Recipes} 列表由 {@code RecipeBuildUtils} 构建。Java 1.21.11 的
 * {@code MERCHANT_OFFERS} 还需要 ItemCost / 结果以及 1.14.3 尾部。
 */
public final class TradeOfferLayout {

    public static final int TRADE_RECIPE_ID = 0x20000000;

    private TradeOfferLayout() {
    }

    public static List<Offer> parseOffers(final Tag offersTag) {
        final List<Offer> offers = new ArrayList<>();
        if (!(offersTag instanceof CompoundTag root)) {
            return offers;
        }
        final List<CompoundTag> recipes = recipeList(root);
        if (recipes.isEmpty()) {
            return offers;
        }
        for (final CompoundTag recipe : recipes) {
            if (recipe == null) {
                continue;
            }
            final int netId = intValue(recipe, "netId", 0);
            if (netId < TRADE_RECIPE_ID) {
                continue;
            }
            final CompoundTag buyA = recipe.getCompoundTag("buyA");
            final CompoundTag buyB = recipe.getCompoundTag("buyB");
            final CompoundTag sell = recipe.getCompoundTag("sell");
            if (sell == null) {
                continue;
            }
            final int uses = intValue(recipe, "uses", 0);
            final int maxUses = intValue(recipe, "maxUses", Integer.MAX_VALUE);
            final int rewardExp = intValue(recipe, "rewardExp", 0);
            final int demand = intValue(recipe, "demand", 0);
            final float multiplier = floatValue(recipe, "priceMultiplierA", 0F);
            offers.add(new Offer(netId, buyA, buyB, sell, uses, maxUses, rewardExp, 0, multiplier, demand));
        }
        return offers;
    }

    public static void writeJavaMerchantOffers(final PacketWrapper wrapper, final int javaWindowId,
                                               final List<Offer> offers, final ItemRewriter itemRewriter,
                                               final int tradeTier, final int villagerExperience,
                                               final boolean regularVillager, final boolean canRestock) {
        wrapper.write(Types.VAR_INT, javaWindowId);
        wrapper.write(Types.VAR_INT, offers.size());
        for (final Offer offer : offers) {
            wrapper.write(VersionedTypes.V26_1.itemCost, javaItem(itemRewriter, offer.buyA()));
            wrapper.write(VersionedTypes.V26_1.item, javaItem(itemRewriter, offer.sell()));
            final Item second = offer.buyB() != null ? javaItem(itemRewriter, offer.buyB()) : null;
            wrapper.write(VersionedTypes.V26_1.optionalItemCost, isPresent(second) ? second : null);
            wrapper.write(Types.BOOLEAN, offer.uses() >= offer.maxUses() && offer.maxUses() != Integer.MAX_VALUE);
            wrapper.write(Types.INT, offer.uses());
            wrapper.write(Types.INT, offer.maxUses());
            wrapper.write(Types.INT, offer.rewardExp());
            wrapper.write(Types.INT, offer.specialPrice());
            wrapper.write(Types.FLOAT, offer.priceMultiplier());
            wrapper.write(Types.INT, offer.demand());
        }
        wrapper.write(Types.VAR_INT, Math.max(1, tradeTier + 1));
        wrapper.write(Types.VAR_INT, Math.max(0, villagerExperience));
        wrapper.write(Types.BOOLEAN, regularVillager);
        wrapper.write(Types.BOOLEAN, canRestock);
    }

    public static int consumeCount(final CompoundTag itemTag) {
        if (itemTag == null) {
            return 0;
        }
        return Math.max(1, intValue(itemTag, "Count", 1));
    }

    static Item javaItem(final ItemRewriter itemRewriter, final CompoundTag tag) {
        if (itemRewriter == null || tag == null) {
            return StructuredItem.empty();
        }
        return itemRewriter.javaItemFromNbt(tag);
    }

    private static boolean isPresent(final Item item) {
        return item != null && !item.isEmpty();
    }

    private static List<CompoundTag> recipeList(final CompoundTag root) {
        final ListTag<CompoundTag> typed = root.getListTag("Recipes", CompoundTag.class);
        if (typed != null) {
            return typed.getValue();
        }
        final Tag raw = root.get("Recipes");
        if (!(raw instanceof ListTag<?> untyped)) {
            return List.of();
        }
        final List<CompoundTag> recipes = new ArrayList<>();
        for (final Tag entry : untyped) {
            if (entry instanceof CompoundTag recipe) {
                recipes.add(recipe);
            }
        }
        return recipes;
    }

    static int intValue(final CompoundTag tag, final String key, final int fallback) {
        if (tag.get(key) instanceof NumberTag number) {
            return number.asInt();
        }
        return fallback;
    }

    private static float floatValue(final CompoundTag tag, final String key, final float fallback) {
        if (tag.get(key) instanceof NumberTag number) {
            return number.asFloat();
        }
        return fallback;
    }

    public record Offer(int netId, CompoundTag buyA, CompoundTag buyB, CompoundTag sell,
                        int uses, int maxUses, int rewardExp, int specialPrice,
                        float priceMultiplier, int demand) {
        public int buyACount() {
            return consumeCount(this.buyA);
        }

        public int buyBCount() {
            return this.buyB == null ? 0 : consumeCount(this.buyB);
        }
    }
}
