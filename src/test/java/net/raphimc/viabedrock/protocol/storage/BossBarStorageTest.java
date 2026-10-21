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

import com.viaversion.nbt.tag.StringTag;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.connection.StorableObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BossBarStorageTest {

    private static final UUID BAR = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void addUpdateRemove() {
        final BossBarStorage storage = storage();
        final BossBarStorage.BossBar bar = storage.add(BAR, new StringTag("first"), 0.5F, 1);
        assertTrue(storage.markClientVisible(BAR));
        bar.setName(new StringTag("updated"));
        assertEquals(BossBarStorage.UpdateAction.UPDATE, storage.reconcileUpdate(BAR), "an ordinary update must not add a duplicate Java bar");
        assertTrue(storage.remove(BAR));
        assertNull(storage.get(BAR));
    }

    @Test
    void createsStableDistinctJavaUuidsForEntitylessBossBars() {
        final BossBarStorage storage = storage();

        final UUID first = storage.resolveOrCreateJavaUuid(41L, null);
        assertEquals(first, storage.resolveOrCreateJavaUuid(41L, null));
        assertNotEquals(first, storage.resolveOrCreateJavaUuid(42L, null));
    }

    @Test
    void prefersEntityUuidAndDropsMappingWithBossBar() {
        final BossBarStorage storage = storage();
        final UUID entityUuid = UUID.fromString("00000000-0000-0000-0000-000000000043");

        assertEquals(entityUuid, storage.resolveOrCreateJavaUuid(43L, entityUuid));
        storage.add(entityUuid, new StringTag("entity"), 1F, 0);
        storage.markClientVisible(entityUuid);
        assertTrue(storage.remove(entityUuid));
        assertNull(storage.getJavaUuid(43L));
    }

    @Test
    void entitylessMappingIsRemovedAndNotReusedAfterRemoval() {
        final BossBarStorage storage = storage();
        final UUID first = storage.resolveOrCreateJavaUuid(44L, null);
        storage.add(first, new StringTag("first"), 1F, 0);
        storage.markClientVisible(first);

        assertTrue(storage.remove(first));
        assertNull(storage.getJavaUuid(44L));
        assertEquals(BossBarStorage.UpdateAction.DROP, storage.reconcileUpdate(first));
        assertNotEquals(first, storage.resolveOrCreateJavaUuid(44L, null));
    }

    @Test
    void configurationClearRetainsEntitylessMapping() {
        final BossBarStorage storage = storage();
        final UUID uuid = storage.resolveOrCreateJavaUuid(45L, null);
        storage.add(uuid, new StringTag("persistent"), 1F, 0);
        storage.markClientVisible(uuid);

        storage.onJavaOverlayCleared();
        assertEquals(uuid, storage.getJavaUuid(45L));
        assertEquals(BossBarStorage.UpdateAction.ADD, storage.reconcileUpdate(uuid));
    }

    @Test
    void removeThenUpdateHasNoBedrockStateToRestore() {
        final BossBarStorage storage = storage();
        storage.add(BAR, new StringTag("active"), 1F, 0);
        storage.markClientVisible(BAR);
        storage.remove(BAR);
        assertNull(storage.get(BAR), "a stale update after remove must be dropped");
        assertEquals(BossBarStorage.UpdateAction.DROP, storage.reconcileUpdate(BAR));
    }

    @Test
    void dimensionRemovalThenUpdateHasNoStateToRestore() {
        final BossBarStorage storage = storage();
        storage.add(BAR, new StringTag("old dimension"), 1F, 0);
        storage.markClientVisible(BAR);
        assertTrue(storage.remove(BAR)); // Entity.remove during CHANGE_DIMENSION
        assertNull(storage.get(BAR));
        assertEquals(BossBarStorage.UpdateAction.DROP, storage.reconcileUpdate(BAR));
    }

    @Test
    void clientClearThenServerUpdateRestoresExactlyOnceFromLatestSnapshot() {
        final BossBarStorage storage = storage();
        final BossBarStorage.BossBar bar = storage.add(BAR, new StringTag("before"), 0.25F, 2);
        storage.markClientVisible(BAR);
        storage.onJavaOverlayCleared();

        bar.setName(new StringTag("after"));
        assertEquals(BossBarStorage.UpdateAction.ADD, storage.reconcileUpdate(BAR), "first update after clear must rebuild the Java bar");
        assertEquals("after", ((StringTag) storage.get(BAR).name()).getValue());
        assertEquals(BossBarStorage.UpdateAction.UPDATE, storage.reconcileUpdate(BAR), "later updates must not rebuild it again");
    }

    @Test
    void sameConnectionCanReenterMultipleConfigurationPlayCycles() {
        final BossBarStorage storage = storage();
        storage.add(BAR, new StringTag("persistent"), 0.75F, 3);
        assertTrue(storage.markClientVisible(BAR));

        storage.onJavaOverlayCleared();
        assertEquals(BossBarStorage.UpdateAction.ADD, storage.reconcileUpdate(BAR));
        storage.onJavaOverlayCleared();
        assertEquals(BossBarStorage.UpdateAction.ADD, storage.reconcileUpdate(BAR));
        assertEquals(BossBarStorage.UpdateAction.UPDATE, storage.reconcileUpdate(BAR));
    }

    @Test
    void connectionCloseDropsServerAndClientState() {
        final BossBarStorage storage = storage();
        storage.add(BAR, new StringTag("closed"), 1F, 0);
        storage.markClientVisible(BAR);
        storage.clearConnection();
        assertNull(storage.get(BAR));
        assertEquals(BossBarStorage.UpdateAction.DROP, storage.reconcileUpdate(BAR));
    }

    @Test
    void addLostBeforeJoinGateOpenIsRebuiltByFirstUpdate() {
        final UserConnection user = storedObjectUser();
        final BossBarStorage storage = new BossBarStorage(user);
        final JoinGate joinGate = new JoinGate(user);
        user.put(storage);
        user.put(joinGate);
        storage.add(BAR, new StringTag("queued"), 1F, 0);

        assertTrue(storage.markAddSent(BAR));
        assertEquals(BossBarStorage.UpdateAction.UPDATE, storage.reconcileUpdate(BAR));
        joinGate.onPlayerChunkReady(0, 0);
        joinGate.onPlayerChunkSent();
        joinGate.onPlayerLoaded();
        assertTrue(joinGate.isOpen());
        assertEquals(BossBarStorage.UpdateAction.ADD, storage.reconcileUpdate(BAR));
        assertEquals(BossBarStorage.UpdateAction.UPDATE, storage.reconcileUpdate(BAR));
    }

    @Test
    void deliveredQueuedAddDoesNotProduceDuplicateAddAfterGateOpens() {
        final UserConnection user = storedObjectUser();
        final BossBarStorage storage = new BossBarStorage(user);
        final JoinGate joinGate = new JoinGate(user);
        user.put(storage);
        user.put(joinGate);
        storage.add(BAR, new StringTag("queued"), 1F, 0);
        storage.markAddSent(BAR);
        storage.markClientVisible(BAR); // JoinGate flush delivery callback

        joinGate.onPlayerChunkReady(0, 0);
        joinGate.onPlayerChunkSent();
        joinGate.onPlayerLoaded();
        assertEquals(BossBarStorage.UpdateAction.UPDATE, storage.reconcileUpdate(BAR));
    }

    private static BossBarStorage storage() {
        final UserConnection user = (UserConnection) Proxy.newProxyInstance(
                UserConnection.class.getClassLoader(),
                new Class<?>[]{UserConnection.class},
                (proxy, method, args) -> null
        );
        return new BossBarStorage(user);
    }

    private static UserConnection storedObjectUser() {
        final Map<Class<?>, StorableObject> storedObjects = new HashMap<>();
        return (UserConnection) Proxy.newProxyInstance(
                UserConnection.class.getClassLoader(),
                new Class<?>[]{UserConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "put" -> {
                        final StorableObject object = (StorableObject) args[0];
                        storedObjects.put(object.getClass(), object);
                        yield null;
                    }
                    case "get" -> storedObjects.get((Class<?>) args[0]);
                    case "getChannel" -> null;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "BossBarStorageTestUser";
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

}
