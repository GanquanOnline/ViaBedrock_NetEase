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

import com.viaversion.viaversion.api.minecraft.GameProfile;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.packet.SpectatorCameraPackets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectatorMenuProjectionTest {

    @BeforeAll
    static void installViaManager() {
        net.raphimc.viabedrock.test.ViaTestBootstrap.installDefaultManager();
    }

    private static final UUID OWN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final Map<UUID, PlayerListStorage.JavaProfile> profiles = new LinkedHashMap<>();
    private final List<Operation> operations = new ArrayList<>();
    private final List<TeamOperation> teamOperations = new ArrayList<>();
    private SpectatorMenuProjection projection;

    @BeforeEach
    void setUp() {
        this.profiles.put(OWN_ID, this.profile(OWN_ID, GameMode.ADVENTURE, true));
        this.profiles.put(TARGET_ID, this.profile(TARGET_ID, GameMode.SURVIVAL, false));
        this.profiles.put(OTHER_ID, this.profile(OTHER_ID, GameMode.CREATIVE, true));
        this.projection = new SpectatorMenuProjection(
                this.user,
                new SpectatorMenuProjection.ProfileSource() {
                    @Override
                    public List<PlayerListStorage.JavaProfile> profiles() {
                        return List.copyOf(SpectatorMenuProjectionTest.this.profiles.values());
                    }

                    @Override
                    public PlayerListStorage.JavaProfile profile(final UUID uuid) {
                        return SpectatorMenuProjectionTest.this.profiles.get(uuid);
                    }

                    @Override
                    public UUID ownUuid() {
                        return OWN_ID;
                    }
                },
                new SpectatorMenuProjection.PacketSink() {
                    @Override
                    public void remove(final UUID[] uuids) {
                        operations.add(new Operation("remove", Arrays.asList(uuids), List.of()));
                    }

                    @Override
                    public void add(final List<PlayerListStorage.JavaProfile> profiles) {
                        operations.add(new Operation("add", List.of(), List.copyOf(profiles)));
                    }

                    @Override
                    public void updateGameMode(final UUID uuid, final GameMode gameMode) {
                        operations.add(new Operation("mode", List.of(uuid),
                                List.of(SpectatorMenuProjectionTest.this.profile(uuid, gameMode, false))));
                    }

                    @Override
                    public void addTeam(final SpectatorMenuProjection.ProjectedTeam team) {
                        teamOperations.add(new TeamOperation("add", team));
                    }

                    @Override
                    public void removeTeam(final String teamId) {
                        teamOperations.add(new TeamOperation(
                                "remove",
                                new SpectatorMenuProjection.ProjectedTeam(teamId, "", -1, List.of())
                        ));
                    }
                }
        );
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void cleansAndDeduplicatesNamesDeterministically() {
        assertEquals("Player", SpectatorMenuProjection.cleanName("\u00A7a \n\t"));
        assertEquals("Hello_World", SpectatorMenuProjection.cleanName("\u00A7bHello  World"));
        assertEquals("abcdefghijklmnop", SpectatorMenuProjection.cleanName("abcdefghijklmnopq"));

        final UUID second = UUID.fromString("44444444-4444-4444-4444-444444444444");
        final UUID third = UUID.fromString("55555555-5555-5555-5555-555555555555");
        assertEquals(
                Map.of(
                        TARGET_ID, "DuplicateName123",
                        second, "DuplicateName1_2",
                        third, "duplicatename1_3"
                ),
                SpectatorMenuProjection.resolveNames(List.of(
                        new SpectatorCameraPackets.Target(TARGET_ID, "DuplicateName123"),
                        new SpectatorCameraPackets.Target(second, "DuplicateName123"),
                        new SpectatorCameraPackets.Target(third, "duplicatename123")
                ))
        );
    }

    @Test
    void initialProjectionRemovesAllBaseProfilesAndPublishesOnlyAuthorizedTarget() {
        this.projection.begin(List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible Target")), List.of());

        assertEquals(3, this.operations.size());
        assertEquals(new Operation("remove", List.of(TARGET_ID, OTHER_ID), List.of()), this.operations.get(0));

        final PlayerListStorage.JavaProfile own = this.operations.get(1).profiles().getFirst();
        assertEquals("mode", this.operations.get(1).type());
        assertEquals(OWN_ID, this.operations.get(1).uuids().getFirst());
        assertEquals(GameMode.ADVENTURE, own.gameMode());

        final PlayerListStorage.JavaProfile target = this.operations.get(2).profiles().getFirst();
        assertEquals(TARGET_ID, target.uuid());
        assertEquals("Visible_Target", target.name());
        assertTrue(target.listed());
        assertTrue(this.projection.contains(TARGET_ID));
        assertFalse(this.projection.contains(OTHER_ID));
        assertTrue(this.teamOperations.isEmpty());
    }

    @Test
    void switchesOwnProfileModeOnlyWhileAttached() {
        this.projection.begin(List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")), List.of());
        this.operations.clear();

        this.projection.setOwnSpectatorPresentation(true);
        assertEquals("mode", this.operations.getFirst().type());
        assertEquals(List.of(OWN_ID), this.operations.getFirst().uuids());
        assertEquals(GameMode.SPECTATOR, this.operations.getFirst().profiles().getFirst().gameMode());

        this.operations.clear();
        this.projection.setOwnSpectatorPresentation(false);
        assertEquals("mode", this.operations.getFirst().type());
        assertEquals(GameMode.ADVENTURE, this.operations.getFirst().profiles().getFirst().gameMode());
    }

    @Test
    void keepsOwnProfileSpectatorWhenBaseProfileRefreshesWhileAttached() {
        this.projection.begin(List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")), List.of());
        this.projection.setOwnSpectatorPresentation(true);
        this.operations.clear();

        this.profiles.put(OWN_ID, this.profile(OWN_ID, GameMode.SURVIVAL, true));
        this.projection.refreshProfile(OWN_ID);

        assertEquals(1, this.operations.size());
        assertEquals("mode", this.operations.getFirst().type());
        assertEquals(List.of(OWN_ID), this.operations.getFirst().uuids());
        assertEquals(GameMode.SPECTATOR, this.operations.getFirst().profiles().getFirst().gameMode());
        assertEquals(GameMode.SURVIVAL, this.profiles.get(OWN_ID).gameMode());
    }

    @Test
    void neverRemovesOwnProfileWhileProjectingOrClearing() {
        this.projection.begin(List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")), List.of());
        this.projection.refreshProfile(OWN_ID);
        this.projection.clear();

        for (Operation operation : this.operations) {
            if (operation.type().equals("remove")) {
                assertFalse(operation.uuids().contains(OWN_ID));
            }
        }
    }

    @Test
    void entitySpawnTemporarilyRestoresBaseThenReturnsToMenuProfile() {
        this.projection.begin(List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")), List.of());
        this.operations.clear();

        this.projection.beforeEntitySpawn(TARGET_ID);
        assertEquals(new Operation("remove", List.of(TARGET_ID), List.of()), this.operations.get(0));

        final PlayerListStorage.JavaProfile base = this.profiles.get(TARGET_ID);
        this.operations.add(new Operation("add", List.of(), List.of(base)));
        this.projection.afterEntitySpawn(TARGET_ID);

        assertEquals(new Operation("remove", List.of(TARGET_ID), List.of()), this.operations.get(2));
        assertEquals("Visible", this.operations.get(3).profiles().getFirst().name());
        assertTrue(this.projection.contains(TARGET_ID));
    }

    @Test
    void entitySpawnTemporarilyRemovesAndRestoresProjectedTeams() {
        this.projection.begin(
                List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")),
                List.of(new SpectatorCameraPackets.Team(
                        "bedwars_red", "Red Team", 12, List.of(TARGET_ID)))
        );
        final String teamId = this.teamOperations.getFirst().team().teamId();
        this.teamOperations.clear();

        this.projection.beforeEntitySpawn(TARGET_ID);
        assertEquals("remove", this.teamOperations.getFirst().type());
        assertEquals(teamId, this.teamOperations.getFirst().team().teamId());

        this.projection.afterEntitySpawn(TARGET_ID);
        assertEquals("add", this.teamOperations.getLast().type());
        assertEquals(List.of("Visible"), this.teamOperations.getLast().team().members());
    }

    @Test
    void playerListRemovalMakesAuthorizedTargetUnavailableUntilReadded() {
        this.projection.begin(List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")), List.of());
        this.projection.afterPlayerListRemove(new UUID[]{TARGET_ID});
        assertFalse(this.projection.contains(TARGET_ID));

        this.operations.clear();
        this.projection.afterPlayerListAdd(new UUID[]{TARGET_ID});
        assertTrue(this.projection.contains(TARGET_ID));
        assertEquals(new Operation("remove", List.of(TARGET_ID), List.of()), this.operations.get(0));
        assertEquals("Visible", this.operations.get(1).profiles().getFirst().name());
    }

    @Test
    void keepsDuplicateAssignmentsStableWhenSnapshotOrderChanges() {
        final UUID second = UUID.fromString("44444444-4444-4444-4444-444444444444");
        this.projection.begin(List.of(
                new SpectatorCameraPackets.Target(TARGET_ID, "Duplicate"),
                new SpectatorCameraPackets.Target(second, "Duplicate")
        ), List.of());
        this.operations.clear();

        this.projection.replace(List.of(
                new SpectatorCameraPackets.Target(second, "Duplicate"),
                new SpectatorCameraPackets.Target(TARGET_ID, "Duplicate")
        ), List.of());

        final Map<UUID, String> names = new LinkedHashMap<>();
        for (Operation operation : this.operations) {
            for (PlayerListStorage.JavaProfile profile : operation.profiles()) {
                if (profile.listed()) {
                    names.put(profile.uuid(), profile.name());
                }
            }
        }
        assertEquals("Duplicate", names.get(TARGET_ID));
        assertEquals("Duplicate_2", names.get(second));
    }

    @Test
    void clearRemovesProjectedDirectoryAndRestoresEveryBaseProfile() {
        this.projection.begin(List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")), List.of());
        this.operations.clear();
        this.projection.clear();

        assertEquals(new Operation("remove", List.of(TARGET_ID, OTHER_ID), List.of()), this.operations.get(0));
        assertEquals(List.of(this.profiles.get(TARGET_ID), this.profiles.get(OTHER_ID)),
                this.operations.get(1).profiles());
        assertEquals("mode", this.operations.get(2).type());
        assertEquals(List.of(OWN_ID), this.operations.get(2).uuids());
        assertEquals(GameMode.ADVENTURE, this.operations.get(2).profiles().getFirst().gameMode());
        assertFalse(this.projection.isActive());
    }

    @Test
    void publishesOnlyAuthorizedAvailableTeamMembersWithProjectedNames() {
        this.projection.begin(
                List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible Target")),
                List.of(new SpectatorCameraPackets.Team(
                        "bedwars_red",
                        "Red Team",
                        12,
                        List.of(TARGET_ID, OTHER_ID)
                ))
        );

        assertEquals(1, this.teamOperations.size());
        final SpectatorMenuProjection.ProjectedTeam team = this.teamOperations.getFirst().team();
        assertTrue(team.teamId().startsWith("ecsp_"));
        assertFalse(team.teamId().startsWith("vb_"));
        assertEquals("Red Team", team.displayName());
        assertEquals(12, team.color());
        assertEquals(List.of("Visible_Target"), team.members());
    }

    @Test
    void removesPublishedTeamsWhenTargetsDisappearOrSessionEnds() {
        this.projection.begin(
                List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Visible")),
                List.of(new SpectatorCameraPackets.Team(
                        "bedwars_red", "Red Team", 12, List.of(TARGET_ID)))
        );
        final String teamId = this.teamOperations.getFirst().team().teamId();
        this.teamOperations.clear();

        this.projection.afterPlayerListRemove(new UUID[]{TARGET_ID});
        assertEquals(List.of(new TeamOperation(
                "remove",
                new SpectatorMenuProjection.ProjectedTeam(teamId, "", -1, List.of())
        )), this.teamOperations);

        this.teamOperations.clear();
        this.projection.afterPlayerListAdd(new UUID[]{TARGET_ID});
        assertEquals("add", this.teamOperations.getLast().type());
        assertEquals(List.of("Visible"), this.teamOperations.getLast().team().members());

        this.teamOperations.clear();
        this.projection.clear();
        assertEquals("remove", this.teamOperations.getFirst().type());
    }

    private PlayerListStorage.JavaProfile profile(final UUID uuid, final GameMode gameMode, final boolean listed) {
        return new PlayerListStorage.JavaProfile(
                uuid,
                StringUtil.encodeUUID(uuid),
                new GameProfile.Property[0],
                gameMode,
                listed,
                42,
                null
        );
    }

    private record Operation(
            String type,
            List<UUID> uuids,
            List<PlayerListStorage.JavaProfile> profiles
    ) {
    }

    private record TeamOperation(String type, SpectatorMenuProjection.ProjectedTeam team) {
    }
}
