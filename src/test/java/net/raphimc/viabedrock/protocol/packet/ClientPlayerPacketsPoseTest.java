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
package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayerPacketsPoseTest {

    @Test
    void sprintInWaterRetriesStartUntilMotConfirms() {
        assertTrue(ClientPlayerPackets.wantsJavaSwim(false, false, false, true, false, true));
        assertEquals(PlayerAuthInputPacket_InputData.StartSwimming,
                ClientPlayerPackets.swimTransitionFlag(true, false));
        assertEquals(PlayerAuthInputPacket_InputData.StartSwimming,
                ClientPlayerPackets.swimTransitionFlag(true, false),
                "MOT may cancel the first Start against the previous feet block");
        assertNull(ClientPlayerPackets.swimTransitionFlag(true, true));
        assertFalse(ClientPlayerPackets.localSwimmingAfterTransition(true, false));
        assertTrue(ClientPlayerPackets.localSwimmingAfterTransition(true, true));
    }

    @Test
    void leavingWaterOrSprintStopsSwimming() {
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, false, false, false, false, true));
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, false, false, true, false, false));
        assertEquals(PlayerAuthInputPacket_InputData.StopSwimming,
                ClientPlayerPackets.swimTransitionFlag(false, true));
        assertNull(ClientPlayerPackets.swimTransitionFlag(false, false));
        assertFalse(ClientPlayerPackets.localSwimmingAfterTransition(false, true));
        assertFalse(ClientPlayerPackets.localSwimmingAfterTransition(false, false));
    }

    @Test
    void flyingGlidingOrRidingDoesNotStartSwimming() {
        assertFalse(ClientPlayerPackets.wantsJavaSwim(true, false, false, true, true, true));
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, true, false, true, true, true));
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, false, true, true, true, true));
    }

    @Test
    void authInputYawWrapsSouthEndpointAndAccumulatedLook() {
        assertEquals(-180.0f, MathUtil.wrapDegrees(180.0f), 0.0f);
        assertEquals(-180.0f, MathUtil.wrapDegrees(-180.0f), 0.0f);
        assertEquals(0.0f, MathUtil.wrapDegrees(360.0f), 0.0f);
        assertEquals(-179.0f, MathUtil.wrapDegrees(181.0f), 0.0f);
    }

    @Test
    void landingWhileGlidingEmitsStopGliding() {
        assertTrue(ClientPlayerPackets.shouldStopGliding(true, true));
        assertFalse(ClientPlayerPackets.shouldStopGliding(true, false));
        assertFalse(ClientPlayerPackets.shouldStopGliding(false, true));
    }

    @Test
    void waterVehicleOrMissingElytraStopsGliding() {
        assertTrue(ClientPlayerPackets.shouldStopGliding(true, false, true, false, true));
        assertTrue(ClientPlayerPackets.shouldStopGliding(true, false, false, true, true));
        assertTrue(ClientPlayerPackets.shouldStopGliding(true, false, false, false, false));
        assertFalse(ClientPlayerPackets.shouldStopGliding(true, false, false, false, true));
        assertFalse(ClientPlayerPackets.shouldStopGliding(false, false, true, true, false));
    }

    @Test
    void jumpHeldInAirDoesNotStopGliding() {
        // Firework boost keeps JUMP down. Do not treat that as Java air-cancel.
        assertFalse(ClientPlayerPackets.shouldStopGliding(true, false, false, false, true));
    }

    @Test
    void startGlidingWaitsUntilAirborne() {
        assertFalse(ClientPlayerPackets.shouldEmitStartGliding(true, true));
        assertTrue(ClientPlayerPackets.shouldEmitStartGliding(true, false));
        assertFalse(ClientPlayerPackets.shouldEmitStartGliding(false, false));
        assertFalse(ClientPlayerPackets.shouldCancelPendingStartGliding(false, false, true));
        assertTrue(ClientPlayerPackets.shouldCancelPendingStartGliding(true, false, true));
        assertTrue(ClientPlayerPackets.shouldCancelPendingStartGliding(false, true, true));
        assertTrue(ClientPlayerPackets.shouldCancelPendingStartGliding(false, false, false));
    }

    @Test
    void waterIdentifiersMatchMotFeetCheck() {
        assertTrue(ClientPlayerPackets.isWaterIdentifier("water"));
        assertTrue(ClientPlayerPackets.isWaterIdentifier("flowing_water"));
        assertFalse(ClientPlayerPackets.isWaterIdentifier("lava"));
        assertFalse(ClientPlayerPackets.isWaterIdentifier(null));
    }

    @Test
    void unknownWaterKeepsLastInsideSample() {
        assertTrue(ClientPlayerPackets.keepLastInsideOfWater(null, true));
        assertFalse(ClientPlayerPackets.keepLastInsideOfWater(null, false));
        assertTrue(ClientPlayerPackets.keepLastInsideOfWater(Boolean.TRUE, false));
        assertFalse(ClientPlayerPackets.keepLastInsideOfWater(Boolean.FALSE, true));
    }

    @Test
    void unknownWaterDoesNotStopWhileStillSprinting() {
        final boolean inWater = ClientPlayerPackets.keepLastInsideOfWater(null, true);
        assertTrue(ClientPlayerPackets.wantsJavaSwim(false, false, false, true, false, inWater));
        assertNull(ClientPlayerPackets.swimTransitionFlag(true, true));
        assertTrue(ClientPlayerPackets.localSwimmingAfterTransition(true, true));
    }

    @Test
    void unknownWaterKeepsRetryingStartFromLastInsideSample() {
        final boolean inWater = ClientPlayerPackets.keepLastInsideOfWater(null, true);
        assertTrue(ClientPlayerPackets.wantsJavaSwim(false, false, false, true, false, inWater));
        assertEquals(PlayerAuthInputPacket_InputData.StartSwimming,
                ClientPlayerPackets.swimTransitionFlag(true, false));
        assertFalse(ClientPlayerPackets.localSwimmingAfterTransition(true, false),
                "do not latch swimming before MOT ActorFlags.SWIMMING");
    }

    @Test
    void lastInsideSampleFallsBackToMotSwimmingFlag() {
        assertTrue(ClientPlayerPackets.lastInsideOfWaterSample(true, false));
        assertTrue(ClientPlayerPackets.lastInsideOfWaterSample(false, true));
        assertFalse(ClientPlayerPackets.lastInsideOfWaterSample(false, false));
        assertTrue(ClientPlayerPackets.lastInsideOfWaterSample(true, true));
    }

    @Test
    void unknownWaterDoesNotStartUntilFeetChunkLoads() {
        final boolean inWater = ClientPlayerPackets.keepLastInsideOfWater(null, false);
        assertFalse(ClientPlayerPackets.wantsJavaSwim(false, false, false, true, false, inWater));
        assertNull(ClientPlayerPackets.swimTransitionFlag(false, false));
    }

    @Test
    void pendingInWorldSectionKeepsLastInsideSample() {
        assertNull(ClientPlayerPackets.waterSampleFromChunkState(false, false, false, true));
        assertNull(ClientPlayerPackets.waterSampleFromChunkState(true, true, false, true));
        assertEquals(Boolean.FALSE, ClientPlayerPackets.waterSampleFromChunkState(true, false, false, true));
        assertEquals(Boolean.TRUE, ClientPlayerPackets.waterSampleFromChunkState(true, true, true, true));
        assertEquals(Boolean.FALSE, ClientPlayerPackets.waterSampleFromChunkState(true, true, true, false));
    }

    @Test
    void solidCeilingIsCrawlEvidenceAndAirIsNot() {
        assertTrue(ClientPlayerPackets.isSolidCrawlCeiling(null, 0) == false);
        assertFalse(ClientPlayerPackets.isSolidCrawlCeiling(null, 1));
    }
}
