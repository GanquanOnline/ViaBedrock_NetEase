/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.nbt.tag.Tag;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Connection-scoped reconciliation state for Bedrock and Java boss bars. */
public final class BossBarStorage extends StoredObject {

    private final Map<UUID, BossBar> activeBars = new HashMap<>();
    private final Map<Long, UUID> javaUuidsByBedrockId = new HashMap<>();
    private final Set<UUID> clientBars = new HashSet<>();
    private final Set<UUID> pendingClientBars = new HashSet<>();

    public BossBarStorage(final UserConnection user) {
        super(user);
        if (user.getChannel() != null) {
            user.getChannel().closeFuture().addListener(ignored -> clearConnection());
        }
    }

    public BossBar add(final UUID uuid, final Tag name, final float progress, final int color) {
        final BossBar bar = new BossBar(name, progress, color);
        this.activeBars.put(uuid, bar);
        return bar;
    }

    public UUID resolveOrCreateJavaUuid(final long bedrockId, final UUID entityUuid) {
        // Bedrock normally identifies a boss by actor ID; synthetic server bars may intentionally have no actor.
        return this.javaUuidsByBedrockId.computeIfAbsent(bedrockId, ignored -> entityUuid != null ? entityUuid : UUID.randomUUID());
    }

    public UUID getJavaUuid(final long bedrockId) {
        return this.javaUuidsByBedrockId.get(bedrockId);
    }

    public BossBar get(final UUID uuid) {
        return this.activeBars.get(uuid);
    }

    public boolean markClientVisible(final UUID uuid) {
        this.pendingClientBars.remove(uuid);
        return this.clientBars.add(uuid);
    }

    public boolean markAddSent(final UUID uuid) {
        final JoinGate joinGate = this.user().get(JoinGate.class);
        if (joinGate != null && !joinGate.isOpen()) {
            this.pendingClientBars.add(uuid);
            return true;
        }
        return this.markClientVisible(uuid);
    }

    public UpdateAction reconcileUpdate(final UUID uuid) {
        if (!this.activeBars.containsKey(uuid)) return UpdateAction.DROP;
        if (this.pendingClientBars.contains(uuid)) {
            final JoinGate joinGate = this.user().get(JoinGate.class);
            if (joinGate != null && !joinGate.isOpen()) return UpdateAction.UPDATE;
            this.pendingClientBars.remove(uuid);
        }
        return this.clientBars.add(uuid) ? UpdateAction.ADD : UpdateAction.UPDATE;
    }

    public boolean remove(final UUID uuid) {
        this.activeBars.remove(uuid);
        this.javaUuidsByBedrockId.values().removeIf(uuid::equals);
        this.pendingClientBars.remove(uuid);
        return this.clientBars.remove(uuid);
    }

    /** START_CONFIGURATION makes Minecraft clear BossHealthOverlay locally. */
    public void onJavaOverlayCleared() {
        this.clientBars.clear();
        this.pendingClientBars.clear();
    }

    public void clearConnection() {
        this.activeBars.clear();
        this.javaUuidsByBedrockId.clear();
        this.clientBars.clear();
        this.pendingClientBars.clear();
    }

    public enum UpdateAction {
        ADD,
        UPDATE,
        DROP
    }

    public static final class BossBar {
        private Tag name;
        private float progress;
        private int color;

        private BossBar(final Tag name, final float progress, final int color) {
            this.name = name;
            this.progress = progress;
            this.color = color;
        }

        public Tag name() { return this.name; }
        public void setName(final Tag name) { this.name = name; }
        public float progress() { return this.progress; }
        public void setProgress(final float progress) { this.progress = progress; }
        public int color() { return this.color; }
        public void setColor(final int color) { this.color = color; }
    }

}
