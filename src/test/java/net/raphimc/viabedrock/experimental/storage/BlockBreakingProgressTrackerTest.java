/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockBreakingProgressTrackerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final BlockBreakingProgressTracker tracker = new BlockBreakingProgressTracker(new UserConnectionImpl(this.channel));

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void forwardsOnlyStandaloneSwings() {
        final BlockPosition position = new BlockPosition(1, 64, 2);

        assertTrue(this.tracker.shouldForwardStandaloneSwing());

        this.tracker.startMining(position, Direction.NORTH);
        assertFalse(this.tracker.shouldForwardStandaloneSwing());

        this.tracker.suspendMining(position);
        assertFalse(this.tracker.shouldForwardStandaloneSwing());

        this.tracker.finishMining(position, 0, 1234);
        assertFalse(this.tracker.shouldForwardStandaloneSwing());
        for (int i = 0; i < 5; i++) {
            this.tracker.tick();
        }
        assertTrue(this.tracker.shouldForwardStandaloneSwing());
    }

    @Test
    void preservesOriginalStateForTimedOutPredictionRollback() {
        final BlockPosition position = new BlockPosition(1, 64, 2);
        this.tracker.expectJavaAckAfterBlockUpdate(position, 7, 1234);

        assertTrue(this.tracker.collectTimedOutBreakAcks(System.currentTimeMillis()).isEmpty());

        final List<BlockBreakingProgressTracker.TimedOutBreakAck> timedOut =
                this.tracker.collectTimedOutBreakAcks(System.currentTimeMillis() + 1_001L);
        assertEquals(List.of(new BlockBreakingProgressTracker.TimedOutBreakAck(position, 7, 1234, null)), timedOut);
        assertTrue(this.tracker.collectTimedOutBreakAcks(Long.MAX_VALUE).isEmpty());
    }

}
