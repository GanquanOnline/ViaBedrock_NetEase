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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.longs.Long2ObjectOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.longs.LongOpenHashSet;
import com.viaversion.viaversion.libs.fastutil.longs.LongSet;
import com.viaversion.viaversion.libs.mcstructs.text.TextFormatting;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.PlayerEntity;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.pyrpc.GlowModEventCodec;
import net.raphimc.viabedrock.experimental.rewriter.EntityMetadataRewriter;
import net.raphimc.viabedrock.experimental.util.JavaTeamColorUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.java.PlayerTeamMethod;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamCollisionRule;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamVisibility;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;

import java.util.HashSet;
import java.util.Set;

/**
 * 保存单个 Java viewer 的 Glow 投影状态。
 */
public final class GlowProjectionTracker extends StoredObject {
    private static final long LOCAL_PLAYER_KEY = Long.MIN_VALUE + 1L;
    private final Long2ObjectMap<GlowState> states = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<String> nonPlayerTeams = new Long2ObjectOpenHashMap<>();
    private final Set<String> createdTeams = new HashSet<>();
    private long lastSnapshotRevision = -1L;

    public GlowProjectionTracker(final UserConnection user) {
        super(user);
    }

    public boolean isGlowing(final long uniqueId) {
        GlowState state = this.states.get(uniqueId);
        return state != null && state.enabled();
    }

    public int colorOrdinal(final long uniqueId) {
        GlowState state = this.states.get(uniqueId);
        if (state == null || !state.enabled()) {
            return TextFormatting.RESET.getOrdinal();
        }
        return JavaTeamColorUtil.closestOrdinal(state.red(), state.green(), state.blue());
    }

    public void apply(final GlowModEventCodec.Message message) {
        if (message instanceof GlowModEventCodec.Update update) {
            this.applyUpdate(update);
        } else if (message instanceof GlowModEventCodec.Sync sync) {
            this.applySync(sync);
        }
    }

    public void onEntityAdded(final Entity entity) {
        if (entity instanceof ClientPlayerEntity) {
            GlowState pending = this.states.remove(LOCAL_PLAYER_KEY);
            GlowState existing = this.states.get(entity.uniqueId());
            if (pending != null && (existing == null || pending.revision() > existing.revision())) {
                this.states.put(entity.uniqueId(), pending);
            }
        }
        this.refreshEntity(entity);
    }

    public void onEntityRemoved(final Entity entity) {
        long uniqueId = entity.uniqueId();
        String team = this.nonPlayerTeams.remove(uniqueId);
        if (team != null) {
            this.sendTeamPlayers(team, PlayerTeamMethod.LEAVE, new String[]{entity.javaUuid().toString()});
        }
    }

    public void clear() {
        EntityTracker tracker = this.user().get(EntityTracker.class);
        for (Long2ObjectMap.Entry<String> entry : this.nonPlayerTeams.long2ObjectEntrySet()) {
            Entity entity = tracker.getEntityByUid(entry.getLongKey());
            if (entity != null) {
                this.sendTeamPlayers(entry.getValue(), PlayerTeamMethod.LEAVE, new String[]{entity.javaUuid().toString()});
            }
        }
        this.states.clear();
        this.nonPlayerTeams.clear();
        this.createdTeams.clear();
        this.lastSnapshotRevision = -1L;
    }

    private void applyUpdate(final GlowModEventCodec.Update update) {
        long uniqueId = this.resolveUniqueId(update.entityId());
        if (uniqueId == Long.MIN_VALUE) {
            return;
        }
        GlowState previous = this.states.get(uniqueId);
        long previousRevision = previous == null ? -1L : previous.revision();
        if (update.revision() <= Math.max(previousRevision, this.lastSnapshotRevision)) {
            return;
        }
        GlowState state = new GlowState(
                update.enabled(), update.red(), update.green(), update.blue(), update.revision());
        this.states.put(uniqueId, state);
        this.refreshEntityByUid(uniqueId);
    }

    private void applySync(final GlowModEventCodec.Sync sync) {
        if (sync.revision() <= this.lastSnapshotRevision) {
            return;
        }
        LongSet seen = new LongOpenHashSet();
        for (GlowModEventCodec.Update update : sync.entries()) {
            long uniqueId = this.resolveUniqueId(update.entityId());
            if (uniqueId == Long.MIN_VALUE) {
                continue;
            }
            GlowState previous = this.states.get(uniqueId);
            if (previous != null && sync.revision() <= previous.revision()) {
                seen.add(uniqueId);
                continue;
            }
            this.states.put(uniqueId, new GlowState(
                    update.enabled(), update.red(), update.green(), update.blue(), sync.revision()));
            seen.add(uniqueId);
        }
        for (long uniqueId : new LongOpenHashSet(this.states.keySet())) {
            GlowState state = this.states.get(uniqueId);
            if (!seen.contains(uniqueId) && state.revision() < sync.revision()) {
                this.states.put(uniqueId, new GlowState(false, 255, 255, 255, sync.revision()));
            }
        }
        this.lastSnapshotRevision = sync.revision();
        for (long uniqueId : this.states.keySet()) {
            this.refreshEntityByUid(uniqueId);
        }
    }

    private void refreshEntityByUid(final long uniqueId) {
        Entity entity = this.user().get(EntityTracker.class).getEntityByUid(uniqueId);
        if (entity == null) {
            return;
        }
        this.refreshEntity(entity);
    }

    private void refreshEntity(final Entity entity) {
        GlowState state = this.states.get(entity.uniqueId());
        if (state == null && !this.nonPlayerTeams.containsKey(entity.uniqueId())) {
            return;
        }
        if (entity instanceof PlayerEntity playerEntity) {
            playerEntity.refreshGlowTeam();
        } else if (state != null && state.enabled()) {
            this.addNonPlayerToTeam(entity);
        } else {
            this.removeNonPlayerFromTeam(entity);
        }
        EntityMetadataRewriter.sendSharedFlags(this.user(), entity);
    }

    private void addNonPlayerToTeam(final Entity entity) {
        GlowState state = this.states.get(entity.uniqueId());
        if (state == null || !state.enabled()) {
            return;
        }
        String team = this.teamName(state);
        String previous = this.nonPlayerTeams.put(entity.uniqueId(), team);
        String member = entity.javaUuid().toString();
        if (previous != null && !previous.equals(team)) {
            this.sendTeamPlayers(previous, PlayerTeamMethod.LEAVE, new String[]{member});
        }
        this.ensureTeam(team, JavaTeamColorUtil.closestOrdinal(state.red(), state.green(), state.blue()));
        if (!team.equals(previous)) {
            this.sendTeamPlayers(team, PlayerTeamMethod.JOIN, new String[]{member});
        }
    }

    private void removeNonPlayerFromTeam(final Entity entity) {
        String previous = this.nonPlayerTeams.remove(entity.uniqueId());
        if (previous != null) {
            this.sendTeamPlayers(previous, PlayerTeamMethod.LEAVE, new String[]{entity.javaUuid().toString()});
        }
    }

    private void ensureTeam(final String team, final int colorOrdinal) {
        if (!this.createdTeams.add(team)) {
            return;
        }
        PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, this.user());
        packet.write(Types.STRING, team);
        packet.write(Types.BYTE, (byte) PlayerTeamMethod.ADD.ordinal());
        packet.write(Types.TAG, TextUtil.stringToNbt(team));
        packet.write(Types.BYTE, (byte) 0);
        packet.write(Types.VAR_INT, TeamVisibility.ALWAYS.ordinal());
        packet.write(Types.VAR_INT, TeamCollisionRule.NEVER.ordinal());
        packet.write(Types.VAR_INT, colorOrdinal);
        packet.write(Types.TAG, TextUtil.stringToNbt(""));
        packet.write(Types.TAG, TextUtil.stringToNbt(""));
        packet.write(Types.STRING_ARRAY, new String[0]);
        packet.send(BedrockProtocol.class);
    }

    private void sendTeamPlayers(final String team, final PlayerTeamMethod method, final String[] members) {
        PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, this.user());
        packet.write(Types.STRING, team);
        packet.write(Types.BYTE, (byte) method.ordinal());
        packet.write(Types.STRING_ARRAY, members);
        packet.send(BedrockProtocol.class);
    }

    private String teamName(final GlowState state) {
        return String.format("vbg_%02x", JavaTeamColorUtil.closestOrdinal(state.red(), state.green(), state.blue()));
    }

    private long resolveUniqueId(final String value) {
        try {
            if (value == null || value.length() > 32) {
                return Long.MIN_VALUE;
            }
            if ("1".equals(value)) {
                EntityTracker tracker = this.user().get(EntityTracker.class);
                Entity clientPlayer = tracker == null ? null : tracker.getClientPlayer();
                return clientPlayer == null ? LOCAL_PLAYER_KEY : clientPlayer.uniqueId();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private record GlowState(boolean enabled, int red, int green, int blue, long revision) {
    }
}
