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

import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.NyaNetworkStackLatencyPayload;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PacketSyncStorageTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final PacketSyncStorage storage = new PacketSyncStorage(new StubUserConnection(this.channel));

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void consumesNetworkStackLatencyResponseOnce() {
        final int id = this.storage.addNetworkStackLatencyResponse(1234L, 5678L);

        assertEquals(new PacketSyncStorage.NetworkStackLatencyResponse(1234L, 5678L), this.storage.getNetworkStackLatencyResponse(id));
        assertNull(this.storage.getNetworkStackLatencyResponse(id));
    }

    @Test
    void unsupportedNyaBoundaryDoesNotBecomeAnOrdinaryLatencyResponse() {
        final long valid = NyaNetworkStackLatencyPayload.encode(
                NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END,
                7L);
        final int id = this.storage.addNetworkStackLatencyResponse(valid ^ (0x7L << 24), 5678L);

        final PacketSyncStorage.NetworkStackLatencyResponse response =
                this.storage.getNetworkStackLatencyResponse(id);
        assertTrue(response.nyaBoundaryPayload());
        assertNull(response.boundaryDescriptor());
    }

    @Test
    void combinesClientAndServerTransportLatency() {
        assertEquals(60, this.storage.updateLatency(TimeUnit.MILLISECONDS.toNanos(42L), 18));
        assertEquals(60, this.storage.latencyMillis());
    }

    @Test
    void fallsBackToClientLatencyAndClampsInvalidValues() {
        assertEquals(42, this.storage.updateLatency(TimeUnit.MILLISECONDS.toNanos(42L), -1));
        assertEquals(0, this.storage.updateLatency(-1L, -1));
        assertEquals(Integer.MAX_VALUE, this.storage.updateLatency(Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void publishesFirstChangedValueAtMostOncePerSecond() {
        final long firstPublishNanos = 100L;
        assertFalse(this.storage.shouldPublishLatency(firstPublishNanos));

        this.storage.updateLatency(TimeUnit.MILLISECONDS.toNanos(50L), -1);
        assertTrue(this.storage.shouldPublishLatency(firstPublishNanos));
        this.storage.markLatencyPublished(firstPublishNanos);
        assertFalse(this.storage.shouldPublishLatency(firstPublishNanos + PacketSyncStorage.LATENCY_UPDATE_INTERVAL_NANOS));

        this.storage.updateLatency(TimeUnit.MILLISECONDS.toNanos(75L), -1);
        assertFalse(this.storage.shouldPublishLatency(firstPublishNanos + PacketSyncStorage.LATENCY_UPDATE_INTERVAL_NANOS - 1L));
        assertTrue(this.storage.shouldPublishLatency(firstPublishNanos + PacketSyncStorage.LATENCY_UPDATE_INTERVAL_NANOS));
    }

    @Test
    void markingUnknownLatencyDoesNotDelayFirstMeasurement() {
        this.storage.markLatencyPublished(100L);
        this.storage.updateLatency(TimeUnit.MILLISECONDS.toNanos(25L), -1);

        assertTrue(this.storage.shouldPublishLatency(101L));
    }

    @Test
    void legacyBoundaryWaitsForMovementAfterPong() {
        this.storage.deferClientTickEndBoundary(11L, legacyBoundary());

        assertArrayEquals(new long[0], this.storage.consumeClientTickEndBoundaries());

        this.storage.recordJavaMovementFrame();
        assertArrayEquals(new long[]{11L}, this.storage.consumeClientTickEndBoundaries());
        assertArrayEquals(new long[0], this.storage.consumeClientTickEndBoundaries());
    }

    @Test
    void legacyBoundariesKeepTheirOwnMovementThreshold() {
        this.storage.deferClientTickEndBoundary(21L, legacyBoundary());
        this.storage.recordJavaMovementFrame();
        this.storage.deferClientTickEndBoundary(22L, legacyBoundary());

        assertArrayEquals(new long[]{21L}, this.storage.consumeClientTickEndBoundaries());

        this.storage.recordJavaMovementFrame();
        assertArrayEquals(new long[]{22L}, this.storage.consumeClientTickEndBoundaries());
    }

    @Test
    void nextClientTickEndBoundaryDoesNotWaitForMovement() {
        this.storage.deferClientTickEndBoundary(31L, nextTickEndBoundary());

        assertArrayEquals(new long[0], this.storage.consumeClientTickEndBoundaries());
        this.storage.recordJavaClientTickEndFrame();
        assertArrayEquals(new long[]{31L}, this.storage.consumeClientTickEndBoundaries());
    }

    @Test
    void readyTickEndBoundaryIsNotBlockedByLegacyMovementBoundary() {
        this.storage.deferClientTickEndBoundary(41L, legacyBoundary());
        this.storage.deferClientTickEndBoundary(42L, nextTickEndBoundary());

        this.storage.recordJavaClientTickEndFrame();
        assertArrayEquals(new long[]{42L}, this.storage.consumeClientTickEndBoundaries());

        this.storage.recordJavaMovementFrame();
        assertArrayEquals(new long[]{41L}, this.storage.consumeClientTickEndBoundaries());
    }

    @Test
    void multipleTickEndBoundariesKeepInsertionOrder() {
        this.storage.deferClientTickEndBoundary(51L, nextTickEndBoundary());
        this.storage.deferClientTickEndBoundary(52L, nextTickEndBoundary());

        this.storage.recordJavaClientTickEndFrame();
        assertArrayEquals(new long[]{51L, 52L}, this.storage.consumeClientTickEndBoundaries());
    }

    private static NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor legacyBoundary() {
        return new NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor(
                NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                NyaNetworkStackLatencyPayload.ReleasePolicy.LEGACY_AFTER_MOVEMENT);
    }

    private static NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor nextTickEndBoundary() {
        return new NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor(
                NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END);
    }

    @Test
    void worldBoundaryCancelsPendingOrdinaryAndSpecialPongsWithoutReusingTheirIds() {
        final int ordinary = this.storage.addNetworkStackLatencyResponse(31L, 1L);
        final long specialTimestamp = 0x4E59L << 27 | 1L << 24 | 1L << 20 | 1L;
        assertTrue(NyaNetworkStackLatencyPayload.isJavaClientTickEndBoundary(specialTimestamp));
        final int special = this.storage.addNetworkStackLatencyResponse(specialTimestamp, 2L);
        this.storage.invalidateNetworkStackLatencyResponses();
        this.storage.invalidateNetworkStackLatencyResponses();

        final int replacement = this.storage.addNetworkStackLatencyResponse(specialTimestamp, 3L);
        assertTrue(replacement != ordinary && replacement != special);
        final PacketSyncStorage.NetworkStackLatencyResponse ordinaryResponse = this.storage.getNetworkStackLatencyResponse(ordinary);
        assertTrue(ordinaryResponse.cancelled());
        assertEquals(31L, ordinaryResponse.timestamp());
        final PacketSyncStorage.NetworkStackLatencyResponse specialResponse = this.storage.getNetworkStackLatencyResponse(special);
        assertTrue(specialResponse.cancelled());
        assertEquals(specialTimestamp, specialResponse.timestamp());
        assertNull(this.storage.getNetworkStackLatencyResponse(special));
        assertFalse(this.storage.getNetworkStackLatencyResponse(replacement).cancelled());
    }

    @Test
    void worldBoundaryDiscardsAlreadyDeferredAckButNewBoundaryStillWaitsForMovement() {
        this.storage.deferClientTickEndBoundary(41L, legacyBoundary());
        this.storage.recordJavaMovementFrame();
        this.storage.invalidateNetworkStackLatencyResponses();
        assertArrayEquals(new long[0], this.storage.consumeClientTickEndBoundaries());

        this.storage.deferClientTickEndBoundary(42L, legacyBoundary());
        assertArrayEquals(new long[0], this.storage.consumeClientTickEndBoundaries());
        this.storage.recordJavaMovementFrame();
        assertArrayEquals(new long[]{42L}, this.storage.consumeClientTickEndBoundaries());
    }

    @Test
    void pingIdWrapCannotOverwriteOutstandingRequestAndExhaustionIsBounded() {
        final int oldest = this.storage.addNetworkStackLatencyResponse(51L, 1L);
        for (int i = 1; i < Short.MAX_VALUE; i++) {
            assertEquals(i, this.storage.addNetworkStackLatencyResponse(100L + i, i));
        }
        this.storage.invalidateNetworkStackLatencyResponses();
        assertEquals(-1, this.storage.addNetworkStackLatencyResponse(52L, 2L));
        assertTrue(this.storage.getNetworkStackLatencyResponse(1).cancelled());
        assertEquals(1, this.storage.addNetworkStackLatencyResponse(53L, 3L));
        final PacketSyncStorage.NetworkStackLatencyResponse oldestResponse = this.storage.getNetworkStackLatencyResponse(oldest);
        assertEquals(51L, oldestResponse.timestamp());
        assertTrue(oldestResponse.cancelled());
    }

    @Test
    void ownedRequestsKeepUuidAndNeverReuseConsumedPingIds() {
        final UUID originalIdentifier = UUID.randomUUID();
        final int original = this.storage.addNetworkStackLatencyResponse(61L, originalIdentifier);
        assertEquals(Short.MAX_VALUE, original);
        assertEquals(originalIdentifier, this.storage.getNetworkStackLatencyResponse(original).boundaryIdentifier());
        final int replacement = this.storage.addNetworkStackLatencyResponse(61L, UUID.randomUUID());
        assertEquals(original + 1, replacement);
        assertNull(this.storage.getNetworkStackLatencyResponse(original));
        this.storage.invalidateNetworkStackLatencyResponses();
        assertTrue(this.storage.getNetworkStackLatencyResponse(replacement).cancelled());
        assertEquals(replacement + 1, this.storage.addNetworkStackLatencyResponse(61L, UUID.randomUUID()));
        assertEquals(0, this.storage.addNetworkStackLatencyResponse(62L));
    }

    @Test
    void deferredOwnedResponseDoesNotLoseItsIdentifierOrTickEndRequirement() {
        final UUID identifier = UUID.randomUUID();
        final PacketSyncStorage.NetworkStackLatencyResponse response = new PacketSyncStorage.NetworkStackLatencyResponse(71L, 0L, true, nextTickEndBoundary(), false, identifier);
        this.storage.deferClientTickEndBoundary(response);
        assertTrue(this.storage.consumeClientTickEndBoundaryResponses().isEmpty());
        this.storage.recordJavaClientTickEndFrame();
        assertEquals(java.util.List.of(response), this.storage.consumeClientTickEndBoundaryResponses());
        assertTrue(this.storage.consumeClientTickEndBoundaryResponses().isEmpty());
    }

    @Test
    void motKeepaliveTimestampZeroMustNotBecomeAJavaPingId() {
        assertTrue(PacketSyncStorage.isMotKeepaliveTimestamp(0L));
        assertFalse(PacketSyncStorage.isMotKeepaliveTimestamp(1L));
        assertFalse(PacketSyncStorage.isMotKeepaliveTimestamp(42L));
    }

    @Test
    void javaOnlyLatencyProbeIsNotForwardedToBedrock() {
        final int id = this.storage.addNetworkStackLatencyResponse(PacketSyncStorage.JAVA_ONLY_LATENCY_PROBE, 1L);
        final PacketSyncStorage.NetworkStackLatencyResponse response = this.storage.getNetworkStackLatencyResponse(id);

        assertTrue(PacketSyncStorage.isJavaOnlyLatencyProbe(response));
        assertFalse(PacketSyncStorage.isJavaOnlyLatencyProbe(new PacketSyncStorage.NetworkStackLatencyResponse(1234L, 1L)));
        assertFalse(PacketSyncStorage.isJavaOnlyLatencyProbe(null));
    }

    @Test
    void keepAliveRoundTripUpdatesLatencyOnce() {
        this.storage.noteKeepAliveSent(42L);
        assertNull(this.storage.consumeKeepAlive(7L));
        assertNotNull(this.storage.consumeKeepAlive(42L));
        assertNull(this.storage.consumeKeepAlive(42L));
    }

    @Test
    void worldInvalidationPreservesCancelledRequestIdentifierForLatePong() {
        final UUID identifier = UUID.randomUUID();
        final int id = this.storage.addNetworkStackLatencyResponse(81L, identifier);
        this.storage.invalidateNetworkStackLatencyResponses();
        final PacketSyncStorage.NetworkStackLatencyResponse cancelled = this.storage.getNetworkStackLatencyResponse(id);
        assertTrue(cancelled.cancelled());
        assertEquals(identifier, cancelled.boundaryIdentifier());
    }

}
