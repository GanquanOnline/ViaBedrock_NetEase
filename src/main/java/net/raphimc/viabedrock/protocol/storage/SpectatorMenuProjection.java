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

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.mcstructs.text.TextFormatting;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.util.BitSets;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.java.PlayerTeamMethod;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerInfoUpdateAction;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamCollisionRule;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.TeamVisibility;
import net.raphimc.viabedrock.protocol.packet.SpectatorCameraPackets;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SpectatorMenuProjection extends StoredObject {

    private final ProfileSource profileSource;
    private final PacketSink packetSink;
    private final Map<UUID, String> projectedNames = new LinkedHashMap<>();
    private final Map<UUID, String> projectedBaseNames = new HashMap<>();
    private final Map<String, ProjectedTeam> projectedTeams = new LinkedHashMap<>();
    private final Set<UUID> visibleProjected = new HashSet<>();
    private final Set<UUID> unavailableTargets = new HashSet<>();
    private List<SpectatorCameraPackets.Team> requestedTeams = List.of();
    private boolean active;
    private boolean ownSpectatorPresentation;

    public SpectatorMenuProjection(final UserConnection user) {
        this(user, new ProfileSource() {
            @Override
            public List<PlayerListStorage.JavaProfile> profiles() {
                return user.get(PlayerListStorage.class).javaProfiles();
            }

            @Override
            public PlayerListStorage.JavaProfile profile(final UUID uuid) {
                return user.get(PlayerListStorage.class).javaProfile(uuid);
            }

            @Override
            public UUID ownUuid() {
                final EntityTracker entityTracker = user.get(EntityTracker.class);
                return entityTracker != null && entityTracker.getClientPlayer() != null
                        ? entityTracker.getClientPlayer().javaUuid()
                        : null;
            }
        }, new PacketSink() {
            @Override
            public void remove(final UUID[] uuids) {
                final PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_REMOVE, user);
                packet.write(Types.UUID_ARRAY, uuids);
                packet.send(BedrockProtocol.class);
            }

            @Override
            public void add(final List<PlayerListStorage.JavaProfile> profiles) {
                sendProfiles(user, profiles);
            }

            @Override
            public void updateGameMode(final UUID uuid, final GameMode gameMode) {
                sendGameMode(user, uuid, gameMode);
            }

            @Override
            public void addTeam(final ProjectedTeam team) {
                sendTeam(user, team);
            }

            @Override
            public void removeTeam(final String teamId) {
                final PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, user);
                packet.write(Types.STRING, teamId);
                packet.write(Types.BYTE, (byte) PlayerTeamMethod.REMOVE.ordinal());
                packet.send(BedrockProtocol.class);
            }
        });
    }

    SpectatorMenuProjection(final UserConnection user, final ProfileSource profileSource, final PacketSink packetSink) {
        super(user);
        this.profileSource = profileSource;
        this.packetSink = packetSink;
    }

    public boolean isActive() {
        return this.active;
    }

    public boolean contains(final UUID uuid) {
        return this.active && this.projectedNames.containsKey(uuid) && !this.unavailableTargets.contains(uuid);
    }

    public void begin(
            final List<SpectatorCameraPackets.Target> targets,
            final List<SpectatorCameraPackets.Team> teams
    ) {
        this.active = true;
        this.unavailableTargets.clear();
        this.replaceInternal(targets, teams, true);
    }

    public void replace(
            final List<SpectatorCameraPackets.Target> targets,
            final List<SpectatorCameraPackets.Team> teams
    ) {
        if (!this.active) return;
        this.replaceInternal(targets, teams, false);
    }

    public void clear() {
        if (!this.active) return;
        final List<PlayerListStorage.JavaProfile> profiles = this.profileSource.profiles();
        final List<UUID> uuids = new ArrayList<>(profiles.size());
        for (PlayerListStorage.JavaProfile profile : profiles) {
            uuids.add(profile.uuid());
        }
        uuids.addAll(this.projectedNames.keySet());
        this.removeProjectedTeams();
        this.removeKnownProfiles(uuids);
        this.active = false;
        this.ownSpectatorPresentation = false;
        this.projectedNames.clear();
        this.projectedBaseNames.clear();
        this.requestedTeams = List.of();
        this.visibleProjected.clear();
        this.unavailableTargets.clear();
        final UUID ownUuid = this.profileSource.ownUuid();
        final List<PlayerListStorage.JavaProfile> remoteProfiles = profiles.stream()
                .filter(profile -> !profile.uuid().equals(ownUuid))
                .toList();
        this.packetSink.add(remoteProfiles);
        final PlayerListStorage.JavaProfile ownProfile = this.profileSource.profile(ownUuid);
        if (ownProfile != null) {
            this.packetSink.updateGameMode(ownUuid, ownProfile.gameMode());
        }
    }

    public void afterPlayerListAdd(final UUID[] uuids) {
        if (!this.active) return;
        for (UUID uuid : uuids) {
            this.unavailableTargets.remove(uuid);
        }
        this.removeKnownProfiles(List.of(uuids));
        this.restoreOwnProfileIfPresent(List.of(uuids));
        this.addProjected(List.of(uuids));
        this.refreshProjectedTeams();
    }

    public void afterPlayerListRemove(final UUID[] uuids) {
        if (!this.active) return;
        for (UUID uuid : uuids) {
            this.unavailableTargets.add(uuid);
            this.visibleProjected.remove(uuid);
        }
        this.refreshProjectedTeams();
    }

    public void refreshProfile(final UUID uuid) {
        if (!this.active) return;
        this.removeKnownProfiles(List.of(uuid));
        this.restoreOwnProfileIfPresent(List.of(uuid));
        this.addProjected(List.of(uuid));
    }

    public void setOwnSpectatorPresentation(final boolean spectator) {
        if (!this.active || this.ownSpectatorPresentation == spectator) return;
        this.ownSpectatorPresentation = spectator;
        final UUID ownUuid = this.profileSource.ownUuid();
        if (ownUuid == null) return;
        final PlayerListStorage.JavaProfile base = this.profileSource.profile(ownUuid);
        if (base != null) {
            this.packetSink.updateGameMode(ownUuid, this.effectiveOwnGameMode(ownUuid, base.gameMode()));
        }
    }

    public void beforeEntitySpawn(final UUID uuid) {
        if (!this.active) return;
        this.removeProjectedTeams();
        this.removeKnownProfiles(List.of(uuid));
    }

    public void afterEntitySpawn(final UUID uuid) {
        if (!this.active) return;
        this.unavailableTargets.remove(uuid);
        this.removeKnownProfiles(List.of(uuid));
        this.restoreOwnProfileIfPresent(List.of(uuid));
        this.addProjected(List.of(uuid));
        this.addProjectedTeams();
    }

    public boolean suppressBaseUpdate(final UUID uuid) {
        final UUID ownUuid = this.profileSource.ownUuid();
        return this.active && (ownUuid == null || !ownUuid.equals(uuid));
    }

    private void replaceInternal(
            final List<SpectatorCameraPackets.Target> targets,
            final List<SpectatorCameraPackets.Team> teams,
            final boolean firstProjection
    ) {
        final Map<UUID, String> nextBaseNames = new LinkedHashMap<>();
        for (SpectatorCameraPackets.Target target : targets) {
            nextBaseNames.put(target.uuid(), cleanName(target.name()));
        }
        final Map<UUID, String> nextNames = this.resolveStableNames(nextBaseNames);
        final Set<UUID> affected = new LinkedHashSet<>();
        if (firstProjection) {
            for (PlayerListStorage.JavaProfile profile : this.profileSource.profiles()) {
                affected.add(profile.uuid());
            }
        }
        affected.addAll(this.projectedNames.keySet());
        affected.addAll(nextNames.keySet());

        this.removeProjectedTeams();
        this.removeKnownProfiles(affected);
        this.projectedNames.clear();
        this.projectedNames.putAll(nextNames);
        this.projectedBaseNames.clear();
        this.projectedBaseNames.putAll(nextBaseNames);
        this.visibleProjected.clear();
        this.unavailableTargets.retainAll(nextNames.keySet());
        this.requestedTeams = List.copyOf(teams);
        this.restoreOwnProfileIfPresent(affected);
        this.addProjected(nextNames.keySet());
        this.addProjectedTeams();
    }

    private void refreshProjectedTeams() {
        this.removeProjectedTeams();
        this.addProjectedTeams();
    }

    private void removeProjectedTeams() {
        for (ProjectedTeam team : this.projectedTeams.values()) {
            this.packetSink.removeTeam(team.teamId());
        }
        this.projectedTeams.clear();
    }

    private void addProjectedTeams() {
        for (SpectatorCameraPackets.Team team : this.requestedTeams) {
            final List<String> members = new ArrayList<>(team.members().size());
            for (UUID member : team.members()) {
                final String projectedName = this.projectedNames.get(member);
                if (projectedName != null && !this.unavailableTargets.contains(member)) {
                    members.add(projectedName);
                }
            }
            if (members.isEmpty()) continue;

            final ProjectedTeam projected = new ProjectedTeam(
                    teamId(team.key()),
                    team.displayName(),
                    team.color(),
                    List.copyOf(members)
            );
            this.projectedTeams.put(team.key(), projected);
            this.packetSink.addTeam(projected);
        }
    }

    private static String teamId(final String key) {
        final String hash = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");
        return "ecsp_" + hash.substring(0, 11);
    }

    private Map<UUID, String> resolveStableNames(final Map<UUID, String> baseNames) {
        final Map<UUID, String> names = new LinkedHashMap<>();
        final Set<String> used = new HashSet<>();
        for (Map.Entry<UUID, String> entry : baseNames.entrySet()) {
            final String previousBase = this.projectedBaseNames.get(entry.getKey());
            final String previousName = this.projectedNames.get(entry.getKey());
            if (entry.getValue().equals(previousBase) && previousName != null
                    && used.add(previousName.toLowerCase(Locale.ROOT))) {
                names.put(entry.getKey(), previousName);
            }
        }
        for (Map.Entry<UUID, String> entry : baseNames.entrySet()) {
            if (names.containsKey(entry.getKey())) continue;
            final String base = entry.getValue();
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
                final String suffixText = "_" + suffix++;
                candidate = truncate(base, 16 - suffixText.length()) + suffixText;
            }
            names.put(entry.getKey(), candidate);
        }
        return names;
    }

    private void removeKnownProfiles(final Iterable<UUID> uuids) {
        final Set<UUID> unique = new LinkedHashSet<>();
        final UUID ownUuid = this.profileSource.ownUuid();
        for (UUID uuid : uuids) {
            // LocalPlayer 会缓存它的 PlayerInfo 对象。移除并重新添加
            // 本地档案会让该缓存指向过期的游戏模式。
            if (uuid.equals(ownUuid)) continue;
            unique.add(uuid);
        }
        if (!unique.isEmpty()) {
            this.packetSink.remove(unique.toArray(new UUID[0]));
            this.visibleProjected.removeAll(unique);
        }
    }

    private void restoreOwnProfileIfPresent(final Iterable<UUID> affected) {
        final UUID ownUuid = this.profileSource.ownUuid();
        if (ownUuid == null) return;
        for (UUID uuid : affected) {
            if (!ownUuid.equals(uuid)) continue;
            final PlayerListStorage.JavaProfile base = this.profileSource.profile(uuid);
            if (base != null) {
                this.packetSink.updateGameMode(base.uuid(),
                        this.effectiveOwnGameMode(base.uuid(), base.gameMode()));
            }
            return;
        }
    }

    private GameMode effectiveOwnGameMode(final UUID uuid, final GameMode baseGameMode) {
        final UUID ownUuid = this.profileSource.ownUuid();
        return this.active && this.ownSpectatorPresentation && uuid.equals(ownUuid)
                ? GameMode.SPECTATOR
                : baseGameMode;
    }

    private void addProjected(final Iterable<UUID> uuids) {
        final List<PlayerListStorage.JavaProfile> profiles = new ArrayList<>();
        for (UUID uuid : uuids) {
            final String name = this.projectedNames.get(uuid);
            if (name == null || this.unavailableTargets.contains(uuid)) continue;
            profiles.add(this.projectedProfile(uuid, name));
            this.visibleProjected.add(uuid);
        }
        if (!profiles.isEmpty()) {
            this.packetSink.add(profiles);
        }
    }

    private PlayerListStorage.JavaProfile projectedProfile(final UUID uuid, final String name) {
        final PlayerListStorage.JavaProfile base = this.profileSource.profile(uuid);
        final GameProfile.Property[] properties = base != null
                ? base.properties()
                : new GameProfile.Property[0];
        final GameMode gameMode = base != null && base.gameMode() != GameMode.SPECTATOR
                ? base.gameMode()
                : GameMode.SURVIVAL;
        final PlayerListStorage playerList = this.user().get(PlayerListStorage.class);
        final int latency = base != null ? base.latency() : playerList != null ? playerList.serverLatency(uuid) : 0;
        return new PlayerListStorage.JavaProfile(
                uuid,
                name,
                properties,
                gameMode,
                true,
                latency,
                base != null ? base.displayName() : null
        );
    }

    static Map<UUID, String> resolveNames(final List<SpectatorCameraPackets.Target> targets) {
        final Map<UUID, String> names = new LinkedHashMap<>();
        final Set<String> used = new HashSet<>();
        for (SpectatorCameraPackets.Target target : targets) {
            final String base = cleanName(target.name());
            String candidate = base;
            int suffix = 2;
            while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
                final String suffixText = "_" + suffix++;
                candidate = truncate(base, 16 - suffixText.length()) + suffixText;
            }
            names.put(target.uuid(), candidate);
        }
        return names;
    }

    static String cleanName(final String input) {
        if (input == null) return "Player";
        final StringBuilder builder = new StringBuilder(input.length());
        boolean formatting = false;
        for (int index = 0; index < input.length(); index++) {
            final char character = input.charAt(index);
            if (formatting) {
                formatting = false;
                continue;
            }
            if (character == '\u00A7') {
                formatting = true;
            } else if (!Character.isISOControl(character) && !Character.isWhitespace(character)) {
                builder.append(character);
            } else if (Character.isWhitespace(character) && !builder.isEmpty()
                    && builder.charAt(builder.length() - 1) != '_') {
                builder.append('_');
            }
        }
        String name = builder.toString();
        while (name.endsWith("_")) {
            name = name.substring(0, name.length() - 1);
        }
        return name.isEmpty() ? "Player" : truncate(name, 16);
    }

    private static String truncate(final String value, final int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void sendProfiles(final UserConnection user, final List<PlayerListStorage.JavaProfile> profiles) {
        if (profiles.isEmpty()) return;
        final PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        packet.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(
                8,
                PlayerInfoUpdateAction.ADD_PLAYER,
                PlayerInfoUpdateAction.UPDATE_GAME_MODE,
                PlayerInfoUpdateAction.UPDATE_LISTED,
                PlayerInfoUpdateAction.UPDATE_LATENCY,
                PlayerInfoUpdateAction.UPDATE_DISPLAY_NAME
        ));
        packet.write(Types.VAR_INT, profiles.size());
        for (PlayerListStorage.JavaProfile profile : profiles) {
            packet.write(Types.UUID, profile.uuid());
            packet.write(Types.STRING, profile.name());
            packet.write(Types.PROFILE_PROPERTY_ARRAY, profile.properties());
            packet.write(Types.VAR_INT, profile.gameMode().ordinal());
            packet.write(Types.BOOLEAN, profile.listed());
            packet.write(Types.VAR_INT, profile.latency());
            packet.write(Types.OPTIONAL_TAG, profile.displayName());
        }
        packet.send(BedrockProtocol.class);
    }

    private static void sendGameMode(final UserConnection user, final UUID uuid, final GameMode gameMode) {
        final PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        packet.write(Types.PROFILE_ACTIONS_ENUM1_21_4,
                BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE));
        packet.write(Types.VAR_INT, 1);
        packet.write(Types.UUID, uuid);
        packet.write(Types.VAR_INT, gameMode.ordinal());
        packet.send(BedrockProtocol.class);
    }

    private static void sendTeam(final UserConnection user, final ProjectedTeam team) {
        final TextFormatting color = team.color() >= 0
                ? TextFormatting.getByOrdinal(team.color())
                : TextFormatting.RESET;
        final PacketWrapper packet = PacketWrapper.create(ClientboundPackets26_1.SET_PLAYER_TEAM, user);
        packet.write(Types.STRING, team.teamId());
        packet.write(Types.BYTE, (byte) PlayerTeamMethod.ADD.ordinal());
        packet.write(Types.TAG, TextUtil.stringToNbt(team.displayName()));
        packet.write(Types.BYTE, (byte) 0);
        packet.write(Types.VAR_INT, TeamVisibility.ALWAYS.ordinal());
        packet.write(Types.VAR_INT, TeamCollisionRule.NEVER.ordinal());
        packet.write(Types.VAR_INT, color.getOrdinal());
        packet.write(Types.TAG, TextUtil.stringToNbt(""));
        packet.write(Types.TAG, TextUtil.stringToNbt(""));
        packet.write(Types.STRING_ARRAY, team.members().toArray(new String[0]));
        packet.send(BedrockProtocol.class);
    }

    record ProjectedTeam(String teamId, String displayName, int color, List<String> members) {
    }

    interface ProfileSource {
        List<PlayerListStorage.JavaProfile> profiles();
        PlayerListStorage.JavaProfile profile(UUID uuid);
        UUID ownUuid();
    }

    interface PacketSink {
        void remove(UUID[] uuids);
        void add(List<PlayerListStorage.JavaProfile> profiles);
        void updateGameMode(UUID uuid, GameMode gameMode);
        void addTeam(ProjectedTeam team);
        void removeTeam(String teamId);
    }
}
