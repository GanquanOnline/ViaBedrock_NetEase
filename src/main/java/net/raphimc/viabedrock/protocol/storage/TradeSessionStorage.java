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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import net.raphimc.viabedrock.protocol.packet.TradeOfferLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 当前打开商人的最近一次 {@code UPDATE_TRADE} 报价列表以及 Java 选中的槽位。
 * {@code SELECT_TRADE} 只存储 MOT 的 {@code netId}；真正的交易是之后的结果槽点击，
 * 编码为 {@code CraftRecipe} + TRADE2 Consume + created-output Take。
 */
public final class TradeSessionStorage extends StoredObject {

    private List<TradeOfferLayout.Offer> offers = List.of();
    private int selectedSlot = -1;
    private int tradeTier;
    private int villagerExperience;
    private boolean usingEconomyTrade = true;

    public TradeSessionStorage(final UserConnection user) {
        super(user);
    }

    public void setOffers(final List<TradeOfferLayout.Offer> offers, final int tradeTier,
                          final int villagerExperience, final boolean usingEconomyTrade) {
        this.offers = offers == null || offers.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(offers));
        this.tradeTier = tradeTier;
        this.villagerExperience = villagerExperience;
        this.usingEconomyTrade = usingEconomyTrade;
        this.selectedSlot = -1;
    }

    public void setSelectedSlot(final int selectedSlot) {
        this.selectedSlot = selectedSlot;
    }

    public void clear() {
        this.offers = List.of();
        this.selectedSlot = -1;
        this.tradeTier = 0;
        this.villagerExperience = 0;
        this.usingEconomyTrade = true;
    }

    public List<TradeOfferLayout.Offer> offers() {
        return this.offers;
    }

    public TradeOfferLayout.Offer selectedOffer() {
        if (this.selectedSlot < 0 || this.selectedSlot >= this.offers.size()) {
            return null;
        }
        return this.offers.get(this.selectedSlot);
    }

    public int selectedSlot() {
        return this.selectedSlot;
    }

    public int tradeTier() {
        return this.tradeTier;
    }

    public int villagerExperience() {
        return this.villagerExperience;
    }

    public boolean usingEconomyTrade() {
        return this.usingEconomyTrade;
    }
}
