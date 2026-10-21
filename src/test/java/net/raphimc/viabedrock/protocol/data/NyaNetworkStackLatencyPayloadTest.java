/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NyaNetworkStackLatencyPayloadTest {

    @Test
    void versionTwoEncodesBoundaryFamilyAndReleasePolicy() {
        final long encoded = NyaNetworkStackLatencyPayload.encode(
                NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END,
                0xBEEFL);

        assertEquals(new NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor(
                        NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                        NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END),
                NyaNetworkStackLatencyPayload.decode(encoded));
    }

    @Test
    void versionOneKindOneRemainsLegacyAfterMovement() {
        final long encoded = NyaNetworkStackLatencyPayload.encodeLegacyAfterMovement(123L);

        assertEquals(new NyaNetworkStackLatencyPayload.JavaBoundaryDescriptor(
                        NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                        NyaNetworkStackLatencyPayload.ReleasePolicy.LEGACY_AFTER_MOVEMENT),
                NyaNetworkStackLatencyPayload.decode(encoded));
    }

    @Test
    void unrelatedAndUnknownDescriptorsAreRejected() {
        assertNull(NyaNetworkStackLatencyPayload.decode(1234L));

        final long valid = NyaNetworkStackLatencyPayload.encode(
                NyaNetworkStackLatencyPayload.Family.JAVA_CLIENT_BOUNDARY,
                NyaNetworkStackLatencyPayload.ReleasePolicy.NEXT_CLIENT_TICK_END,
                7L);
        final long unknownPolicy = valid ^ (0xFL << 16);
        final long unknownVersion = valid ^ (0x7L << 24);
        assertTrue(NyaNetworkStackLatencyPayload.isPayload(unknownPolicy));
        assertTrue(NyaNetworkStackLatencyPayload.isPayload(unknownVersion));
        assertNull(NyaNetworkStackLatencyPayload.decode(unknownPolicy));
        assertNull(NyaNetworkStackLatencyPayload.decode(unknownVersion));
    }
    @Test
    void upstreamV2AndOwnedV2KeepReleasePolicySeparateFromOwnership() {
        long prefix = 0x4E59L << 27 | 2L << 24;
        long upstream = prefix | 1L << 20 | 1L << 16 | 123L;
        long ownedDeferred = prefix | 2L << 20 | 1L << 16 | 123L;
        long ownedImmediate = prefix | 2L << 20 | 2L << 16 | 123L;

        assertTrue(NyaNetworkStackLatencyPayload.isJavaClientTickEndBoundary(upstream));
        assertFalse(NyaNetworkStackLatencyPayload.isOwnedBoundary(upstream));
        assertTrue(NyaNetworkStackLatencyPayload.isJavaClientTickEndBoundary(ownedDeferred));
        assertTrue(NyaNetworkStackLatencyPayload.isOwnedBoundary(ownedDeferred));
        assertFalse(NyaNetworkStackLatencyPayload.isJavaClientTickEndBoundary(ownedImmediate));
        assertTrue(NyaNetworkStackLatencyPayload.isOwnedBoundary(ownedImmediate));
    }

    @Test
    void legacyV1RemainsUnownedAndUnknownV2PoliciesDoNotRequestUuid() {
        long legacy = 0x4E59L << 27 | 1L << 24 | 1L << 20 | 123L;
        assertTrue(NyaNetworkStackLatencyPayload.isJavaClientTickEndBoundary(legacy));
        assertFalse(NyaNetworkStackLatencyPayload.isOwnedBoundary(legacy));
        for (int policy : new int[]{0, 3, 15}) {
            long unknown = 0x4E59L << 27 | 2L << 24 | 2L << 20 | (long) policy << 16 | 123L;
            assertFalse(NyaNetworkStackLatencyPayload.isOwnedBoundary(unknown));
            assertFalse(NyaNetworkStackLatencyPayload.isJavaClientTickEndBoundary(unknown));
        }
    }
}
