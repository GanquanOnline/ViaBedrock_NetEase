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
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.rewriter.BedrockArmorProtectionRegistry;
import net.raphimc.viabedrock.protocol.rewriter.BedrockArmorValueResolver;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.logging.Level;

public final class PlayerArmorHudTracker extends StoredObject {

    private final IntSupplier armorValueSupplier;
    private final BooleanSupplier readySupplier;
    private final IntConsumer armorValueSender;
    private int currentValue;
    private int lastSentValue = -1;
    private boolean dirty = true;

    public PlayerArmorHudTracker(final UserConnection user) {
        super(user);
        this.armorValueSupplier = this::resolveArmorValue;
        this.readySupplier = this::isReady;
        this.armorValueSender = this::sendArmorValue;
    }

    PlayerArmorHudTracker(final IntSupplier armorValueSupplier, final BooleanSupplier readySupplier, final IntConsumer armorValueSender) {
        super(null);
        this.armorValueSupplier = armorValueSupplier;
        this.readySupplier = readySupplier;
        this.armorValueSender = armorValueSender;
    }

    public boolean markDirty() {
        this.dirty = true;
        return this.syncIfReady();
    }

    public boolean syncIfReady() {
        return this.sync(false);
    }

    public boolean forceSync() {
        this.dirty = true;
        return this.sync(true);
    }

    public void reset() {
        this.currentValue = 0;
        this.lastSentValue = -1;
        this.dirty = true;
    }

    int currentValue() {
        return this.currentValue;
    }

    int lastSentValue() {
        return this.lastSentValue;
    }

    boolean dirty() {
        return this.dirty;
    }

    private boolean sync(final boolean force) {
        if (!this.dirty || !this.readySupplier.getAsBoolean()) {
            return false;
        }

        final int resolvedValue;
        try {
            resolvedValue = this.armorValueSupplier.getAsInt();
        } catch (RuntimeException e) {
            this.logSyncFailure("resolve", e);
            return false;
        }

        this.currentValue = Math.max(0, Math.min(BedrockArmorValueResolver.MAX_ARMOR_VALUE, resolvedValue));
        if (!force && this.currentValue == this.lastSentValue) {
            this.dirty = false;
            return false;
        }

        try {
            this.armorValueSender.accept(this.currentValue);
        } catch (RuntimeException e) {
            this.logSyncFailure("send", e);
            return false;
        }
        this.lastSentValue = this.currentValue;
        this.dirty = false;
        return true;
    }

    private void logSyncFailure(final String operation, final RuntimeException cause) {
        if (this.user() != null) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to " + operation + " local player armor HUD value", cause);
        }
    }

    private int resolveArmorValue() {
        final UserConnection user = this.user();
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        final ResourcePackStorage resourcePackStorage = user.get(ResourcePackStorage.class);
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        if (itemRewriter == null || resourcePackStorage == null || inventoryTracker == null) {
            return 0;
        }

        final BedrockArmorProtectionRegistry protectionRegistry = new BedrockArmorProtectionRegistry(resourcePackStorage.getItems(), BedrockProtocol.MAPPINGS.getBedrockArmorProtection());
        final BedrockArmorValueResolver resolver = new BedrockArmorValueResolver(itemRewriter::bedrockIdentifier, protectionRegistry::protection);
        return resolver.resolve(inventoryTracker.getArmorContainer().getItems());
    }

    private boolean isReady() {
        final UserConnection user = this.user();
        return user.getProtocolInfo().getServerState() == State.PLAY
                && user.get(EntityTracker.class) != null
                && user.get(EntityTracker.class).getClientPlayer() != null;
    }

    private void sendArmorValue(final int armorValue) {
        final UserConnection user = this.user();
        final PacketWrapper updateAttributes = PacketWrapper.create(ClientboundPackets26_1.UPDATE_ATTRIBUTES, user);
        updateAttributes.write(Types.VAR_INT, user.get(EntityTracker.class).getClientPlayer().javaId()); // 实体 id
        updateAttributes.write(Types.VAR_INT, 1); // 属性数量
        updateAttributes.write(Types.VAR_INT, BedrockProtocol.MAPPINGS.getJavaEntityAttributes().get(Attributes.ARMOR)); // 属性 id
        updateAttributes.write(Types.DOUBLE, (double) armorValue); // 基础值
        updateAttributes.write(Types.VAR_INT, 0); // 修饰符数量
        updateAttributes.send(BedrockProtocol.class);
    }

}
