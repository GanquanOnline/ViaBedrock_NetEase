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

import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.SpectatorCameraPackets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpectatorCameraTrackerTest {

    @BeforeAll
    static void installViaManager() {
        net.raphimc.viabedrock.test.ViaTestBootstrap.installDefaultManager();
    }

    private static final int OWN_JAVA_ID = 1;
    private static final UUID SESSION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
    private final Map<Long, Entity> entities = new HashMap<>();
    private final List<Integer> cameraPackets = new ArrayList<>();
    private final List<Request> requests = new ArrayList<>();
    private final List<GameMode> gameModes = new ArrayList<>();
    private int abilityResends;
    private SpectatorCameraTracker tracker;

    @BeforeEach
    void setUp() {
        this.user.put(new PlayerListStorage());
        final SpectatorMenuProjection menu = new SpectatorMenuProjection(
                this.user,
                new SpectatorMenuProjection.ProfileSource() {
                    @Override
                    public List<PlayerListStorage.JavaProfile> profiles() {
                        return List.of();
                    }

                    @Override
                    public PlayerListStorage.JavaProfile profile(final UUID uuid) {
                        return null;
                    }

                    @Override
                    public UUID ownUuid() {
                        return null;
                    }
                },
                new SpectatorMenuProjection.PacketSink() {
                    @Override
                    public void remove(final UUID[] uuids) {
                    }

                    @Override
                    public void add(final List<PlayerListStorage.JavaProfile> profiles) {
                    }

                    @Override
                    public void updateGameMode(final UUID uuid, final GameMode gameMode) {
                    }

                    @Override
                    public void addTeam(final SpectatorMenuProjection.ProjectedTeam team) {
                    }

                    @Override
                    public void removeTeam(final String teamId) {
                    }
                }
        );
        this.tracker = new SpectatorCameraTracker(this.user, new SpectatorCameraTracker.EntityLookup() {
            @Override
            public Entity findByRuntimeId(final long runtimeId) {
                return entities.get(runtimeId);
            }

            @Override
            public int ownJavaId() {
                return OWN_JAVA_ID;
            }

            @Override
            public GameMode ownJavaGameMode() {
                return GameMode.CREATIVE;
            }
        }, new SpectatorCameraTracker.PacketSink() {
            @Override
            public void sendCamera(final int javaEntityId) {
                cameraPackets.add(javaEntityId);
            }

            @Override
            public void sendSessionReady(final UUID sessionId, final long generation) {
                requests.add(new Request("ready", sessionId, generation, null));
            }

            @Override
            public void sendDetachRequest(final UUID sessionId, final long generation, final String reason) {
                requests.add(new Request(reason, sessionId, generation, null));
            }

            @Override
            public void sendTargetRequest(final UUID sessionId, final long generation, final UUID targetId) {
                requests.add(new Request("target", sessionId, generation, targetId));
            }

            @Override
            public void sendLegacyDetachRequest(final String reason) {
                requests.add(new Request(reason, null, -1L, null));
            }

            @Override
            public void sendGameMode(final GameMode gameMode) {
                gameModes.add(gameMode);
            }

            @Override
            public void resendAbilities() {
                abilityResends++;
            }
        }, menu);
    }

    @AfterEach
    void tearDown() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void waitsUntilTargetWasSentToJavaClient() {
        final Entity target = this.entity(10L, 40, TARGET_ID, new Position3f(1F, 2F, 3F));
        this.beginSession();

        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());
        assertEquals(SpectatorCameraTracker.State.PENDING_TARGET, this.tracker.state());
        assertTrue(this.cameraPackets.isEmpty());
        assertTrue(this.gameModes.isEmpty());

        this.tracker.onJavaPlayerSpawned(target);
        assertEquals(SpectatorCameraTracker.State.ATTACHED, this.tracker.state());
        assertEquals(List.of(40), this.cameraPackets);
        assertEquals(List.of(GameMode.SPECTATOR), this.gameModes);

        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());
        assertEquals(List.of(40), this.cameraPackets);
    }

    @Test
    void shiftRestoresPresentationButKeepsSessionForReattach() {
        final Entity target = this.entity(10L, 40, TARGET_ID, Position3f.ZERO);
        this.beginSession();
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());

        assertTrue(this.tracker.handleShiftInput(true));
        assertTrue(this.tracker.handleShiftInput(true));
        assertEquals(List.of(new Request("sneak", SESSION_ID, 2L, null)), this.requests);

        this.tracker.detachTarget(SESSION_ID, 2L);
        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of(GameMode.SPECTATOR, GameMode.CREATIVE), this.gameModes);
        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
        assertEquals(1, this.abilityResends);

        assertTrue(this.tracker.handleShiftInput(true));
        assertFalse(this.tracker.handleShiftInput(false));
        assertFalse(this.tracker.handleShiftInput(true));

        this.tracker.attach(SESSION_ID, 3L, target.runtimeId());
        assertEquals(List.of(40, OWN_JAVA_ID, 40), this.cameraPackets);
        assertEquals(List.of(GameMode.SPECTATOR, GameMode.CREATIVE, GameMode.SPECTATOR), this.gameModes);
        assertEquals(GameMode.SPECTATOR, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));

        this.tracker.endSession(SESSION_ID);
        assertEquals(List.of(40, OWN_JAVA_ID, 40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of(GameMode.SPECTATOR, GameMode.CREATIVE, GameMode.SPECTATOR, GameMode.CREATIVE), this.gameModes);
        assertEquals(2, this.abilityResends);
        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
    }

    @Test
    void ignoresStaleGenerationAndUnauthorizedTargets() {
        final Entity target = this.entity(10L, 40, TARGET_ID, Position3f.ZERO);
        this.beginSession();
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());

        this.tracker.detachTarget(SESSION_ID, 1L);
        assertEquals(SpectatorCameraTracker.State.ATTACHED, this.tracker.state());
        assertFalse(this.tracker.requestTarget(UUID.randomUUID()));
        assertTrue(this.tracker.requestTarget(TARGET_ID));
        assertEquals(new Request("target", SESSION_ID, 2L, TARGET_ID), this.requests.getFirst());
    }

    @Test
    void targetRemovalKeepsSessionAndReportsCurrentGeneration() {
        final Entity target = this.entity(10L, 40, TARGET_ID, Position3f.ZERO);
        this.beginSession();
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());
        this.tracker.onEntityRemoved(target);

        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of(new Request("target_removed", SESSION_ID, 2L, null)), this.requests);
        assertEquals(SpectatorCameraTracker.State.DETACHED, this.tracker.state());
        assertEquals(List.of(GameMode.SPECTATOR, GameMode.CREATIVE), this.gameModes);
        assertEquals(1, this.abilityResends);
        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
    }

    @Test
    void dimensionChangeRestoresPresentationAndReportsAttachedTarget() {
        final Entity target = this.entity(10L, 40, TARGET_ID, Position3f.ZERO);
        this.beginSession();
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());

        this.tracker.onDimensionChange();

        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of(new Request("dimension_change", SESSION_ID, 2L, null)), this.requests);
        assertEquals(List.of(GameMode.SPECTATOR, GameMode.CREATIVE), this.gameModes);
        assertEquals(1, this.abilityResends);
        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
    }

    @Test
    void dimensionChangeKeepsDetachedSessionInRealPresentation() {
        this.beginSession();

        this.tracker.onDimensionChange();

        assertTrue(this.cameraPackets.isEmpty());
        assertTrue(this.requests.isEmpty());
        assertTrue(this.gameModes.isEmpty());
        assertEquals(0, this.abilityResends);
        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
    }

    @Test
    void reusedSessionKeepsRealPresentationUntilAttach() {
        this.beginSession();
        this.tracker.onDimensionChange();

        this.beginSession();

        assertTrue(this.gameModes.isEmpty());
        assertEquals(GameMode.ADVENTURE, this.tracker.projectJavaGameMode(GameMode.ADVENTURE));
    }

    @Test
    void clientResetRestoresRealAbilitiesForDetachedSession() {
        this.beginSession();

        this.tracker.restorePresentationAfterClientReset();

        assertTrue(this.gameModes.isEmpty());
        assertEquals(1, this.abilityResends);
    }

    @Test
    void clientResetRestoresRealAbilitiesOutsideSpectatorPresentation() {
        this.tracker.restorePresentationAfterClientReset();

        assertTrue(this.gameModes.isEmpty());
        assertEquals(1, this.abilityResends);
    }

    @Test
    void syntheticSpectatorPresentationRejectsJavaGameModeChanges() {
        assertTrue(this.tracker.acceptsJavaGameModeChange());

        this.beginSession();
        assertTrue(this.tracker.acceptsJavaGameModeChange());

        final Entity target = this.entity(10L, 40, TARGET_ID, Position3f.ZERO);
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());
        assertFalse(this.tracker.acceptsJavaGameModeChange());

        this.tracker.detachTarget(SESSION_ID, 2L);
        assertTrue(this.tracker.acceptsJavaGameModeChange());
    }

    @Test
    void legacyModeRemainsAvailableUntilVersionedSessionBegins() {
        final Entity target = this.entity(10L, 40, TARGET_ID, Position3f.ZERO);
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attachLegacy(target.runtimeId());
        assertEquals(List.of(40), this.cameraPackets);
        assertEquals(List.of(GameMode.SPECTATOR), this.gameModes);

        assertTrue(this.tracker.handleShiftInput(true));
        assertEquals(List.of(new Request("sneak", null, -1L, null)), this.requests);
        this.tracker.detachLegacy();
        assertEquals(List.of(40, OWN_JAVA_ID), this.cameraPackets);
        assertEquals(List.of(GameMode.SPECTATOR, GameMode.CREATIVE), this.gameModes);

        this.beginSession();
        this.tracker.attachLegacy(target.runtimeId());
        assertEquals(SpectatorCameraTracker.State.DETACHED, this.tracker.state());
    }

    @Test
    void usesAttachedTargetAsJavaSoundListener() {
        final Position3f position = new Position3f(4F, 5F, 6F);
        final Entity target = this.entity(10L, 40, TARGET_ID, position);

        assertNull(this.tracker.javaListenerPosition());
        this.beginSession();
        this.tracker.onJavaPlayerSpawned(target);
        this.tracker.attach(SESSION_ID, 2L, target.runtimeId());
        assertEquals(position, this.tracker.javaListenerPosition());

        this.tracker.detachTarget(SESSION_ID, 2L);
        assertNull(this.tracker.javaListenerPosition());
    }

    private void beginSession() {
        this.tracker.beginSession(
                SESSION_ID,
                1L,
                List.of(new SpectatorCameraPackets.Target(TARGET_ID, "Target")),
                List.of()
        );
    }

    private Entity entity(final long runtimeId, final int javaId, final UUID uuid, final Position3f position) {
        final Entity entity = new Entity(this.user, runtimeId, runtimeId, "minecraft:player", javaId,
                uuid, EntityTypes1_21_11.PLAYER);
        entity.setPosition(position);
        this.entities.put(runtimeId, entity);
        return entity;
    }

    private record Request(String action, UUID sessionId, long generation, UUID targetId) {
    }
}
