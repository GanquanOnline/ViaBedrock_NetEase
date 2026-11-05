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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientLightStorageTest {

    @Test
    void registerBeforeFinishRunsImmediatelyInClientMode() {
        final ClientLightStorage storage = new ClientLightStorage();
        final AtomicInteger finishes = new AtomicInteger();
        final Runnable finish = finishes::incrementAndGet;

        assertTrue(storage.markProbeSent(100L));
        assertTrue(storage.tryNegotiateClientComputed());
        final ClientLightStorage.FinishRequest request = storage.requestFinish(200L, finish);

        assertEquals(ClientLightStorage.FinishDecision.RUN, request.decision());
        assertEquals(ClientLightStorage.Mode.CLIENT_COMPUTED, storage.mode());
        assertFalse(storage.freeze());
        assertFalse(storage.allowsProxyComputation());
        finish.run(); // RUN 告知调用方执行其后续逻辑。
        assertEquals(1, finishes.get());
    }

    @Test
    void finishWaitsForRegisterAndIsReleasedOnceAfterNegotiation() {
        final ClientLightStorage storage = new ClientLightStorage();
        final AtomicInteger finishes = new AtomicInteger();
        final Runnable finish = finishes::incrementAndGet;

        assertTrue(storage.markProbeSent(1_000L));
        final ClientLightStorage.FinishRequest request = storage.requestFinish(2_000L, finish);
        assertEquals(ClientLightStorage.FinishDecision.WAIT, request.decision());
        assertEquals(ClientLightStorage.FinishDecision.DUPLICATE, storage.requestFinish(2_001L, finish).decision());
        assertTrue(storage.hasPendingFinish());
        final TrackingScheduledFuture timeout = new TrackingScheduledFuture();
        storage.attachTimeout(timeout);

        assertTrue(storage.tryNegotiateClientComputed());
        final Runnable released = storage.releasePendingFinishAfterNegotiation();
        assertSame(finish, released);
        released.run();

        assertEquals(1, finishes.get());
        assertEquals(ClientLightStorage.Mode.CLIENT_COMPUTED, storage.mode());
        assertFalse(storage.freeze());
        assertFalse(storage.hasPendingFinish());
        assertTrue(timeout.isCancelled());
        assertNull(storage.releasePendingFinishAfterNegotiation());
    }

    @Test
    void timeoutFallsBackToServerComputedFromOriginalProbeDeadline() {
        final ClientLightStorage storage = new ClientLightStorage();
        final AtomicInteger finishes = new AtomicInteger();
        final long probeStart = 10_000L;

        assertTrue(storage.markProbeSent(probeStart));
        final long almostExpired = probeStart + ClientLightStorage.NEGOTIATION_WINDOW_NANOS - 25L;
        final ClientLightStorage.FinishRequest request = storage.requestFinish(almostExpired, finishes::incrementAndGet);
        assertEquals(ClientLightStorage.FinishDecision.WAIT, request.decision());
        assertEquals(25L, request.waitNanos());
        final TrackingScheduledFuture timeout = new TrackingScheduledFuture();
        storage.attachTimeout(timeout);
        assertNull(storage.timeoutPendingFinish(almostExpired + 24L));

        final Runnable released = storage.timeoutPendingFinish(almostExpired + 25L);
        assertNotNull(released);
        released.run();

        assertEquals(1, finishes.get());
        assertEquals(ClientLightStorage.Mode.SERVER_COMPUTED, storage.mode());
        assertFalse(storage.freeze());
        assertTrue(storage.allowsProxyComputation());
        assertTrue(timeout.isCancelled());
        assertNull(storage.timeoutPendingFinish(Long.MAX_VALUE));
    }

    @Test
    void finishBeforeProbeStartsBoundedWindowAndProbeDoesNotExtendIt() {
        final ClientLightStorage storage = new ClientLightStorage();
        final AtomicInteger finishes = new AtomicInteger();
        final long finishRequestNanos = 10_000L;

        final ClientLightStorage.FinishRequest request = storage.requestFinish(finishRequestNanos, finishes::incrementAndGet);
        assertEquals(ClientLightStorage.FinishDecision.WAIT, request.decision());
        assertEquals(ClientLightStorage.NEGOTIATION_WINDOW_NANOS, request.waitNanos());

        final long probeNanos = finishRequestNanos + TimeUnit.MILLISECONDS.toNanos(750L);
        assertTrue(storage.markProbeSent(probeNanos));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(250L), storage.remainingNegotiationNanos(probeNanos));
        assertNull(storage.timeoutPendingFinish(finishRequestNanos + ClientLightStorage.NEGOTIATION_WINDOW_NANOS - 1L));

        final Runnable released = storage.timeoutPendingFinish(finishRequestNanos + ClientLightStorage.NEGOTIATION_WINDOW_NANOS);
        assertNotNull(released);
        released.run();
        assertEquals(1, finishes.get());
        assertEquals(ClientLightStorage.Mode.SERVER_COMPUTED, storage.mode());
        assertFalse(storage.freeze());
    }

    @Test
    void finishBeforeProbeCanStillNegotiateClientComputedMode() {
        final ClientLightStorage storage = new ClientLightStorage();
        final AtomicInteger finishes = new AtomicInteger();

        assertEquals(ClientLightStorage.FinishDecision.WAIT, storage.requestFinish(1_000L, finishes::incrementAndGet).decision());
        assertTrue(storage.markProbeSent(2_000L));
        assertTrue(storage.tryNegotiateClientComputed());

        final Runnable released = storage.releasePendingFinishAfterNegotiation();
        assertNotNull(released);
        released.run();
        assertEquals(1, finishes.get());
        assertEquals(ClientLightStorage.Mode.CLIENT_COMPUTED, storage.mode());
        assertFalse(storage.freeze());
    }

    @Test
    void unrelatedRegistrationKeepsBarrierWaitingUntilDeadline() {
        final ClientLightStorage storage = new ClientLightStorage();
        final AtomicInteger finishes = new AtomicInteger();

        assertTrue(storage.markProbeSent(0L));
        assertEquals(ClientLightStorage.FinishDecision.WAIT, storage.requestFinish(1L, finishes::incrementAndGet).decision());
        assertTrue(storage.hasPendingFinish()); // 无关的增量注册不会触碰 EC 状态。
        assertEquals(ClientLightStorage.Mode.UNKNOWN, storage.mode());

        final Runnable released = storage.timeoutPendingFinish(ClientLightStorage.NEGOTIATION_WINDOW_NANOS);
        assertNotNull(released);
        released.run();
        assertEquals(1, finishes.get());
        assertEquals(ClientLightStorage.Mode.SERVER_COMPUTED, storage.mode());
        assertFalse(storage.tryNegotiateClientComputed());
    }

    @Test
    void lateRegisterIsRejectedAfterFallbackFreeze() {
        final ClientLightStorage storage = new ClientLightStorage();

        assertTrue(storage.freeze());
        assertFalse(storage.tryNegotiateClientComputed());
        assertEquals(ClientLightStorage.Mode.SERVER_COMPUTED, storage.mode());
        assertEquals(1L, storage.modeGeneration());
    }

    @Test
    void chunkRoutingOnlyBypassesProxyLightForNegotiatedClients() {
        final ClientLightStorage unknown = new ClientLightStorage();
        assertFalse(unknown.isClientComputed());
        assertTrue(unknown.allowsProxyComputation());

        final ClientLightStorage fallback = new ClientLightStorage();
        assertTrue(fallback.freeze());
        assertEquals(ClientLightStorage.Mode.SERVER_COMPUTED, fallback.mode());
        assertFalse(fallback.isClientComputed());
        assertTrue(fallback.allowsProxyComputation());

        final ClientLightStorage negotiated = new ClientLightStorage();
        assertTrue(negotiated.tryNegotiateClientComputed());
        assertTrue(negotiated.isClientComputed());
        assertFalse(negotiated.allowsProxyComputation());
    }

    @Test
    void finishAndRegisterRaceIsIdempotent() throws Exception {
        final ClientLightStorage storage = new ClientLightStorage();
        final AtomicInteger finishes = new AtomicInteger();
        final Runnable finish = finishes::incrementAndGet;
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        assertTrue(storage.markProbeSent(0L));
        try {
            final Future<ClientLightStorage.FinishRequest> finishRequest = executor.submit(() -> {
                start.await();
                return storage.requestFinish(1L, finish);
            });
            final Future<Boolean> negotiation = executor.submit(() -> {
                start.await();
                return storage.tryNegotiateClientComputed();
            });
            start.countDown();

            final ClientLightStorage.FinishRequest request = finishRequest.get(5L, TimeUnit.SECONDS);
            assertTrue(negotiation.get(5L, TimeUnit.SECONDS));
            if (request.decision() == ClientLightStorage.FinishDecision.RUN) {
                finish.run();
            } else {
                assertEquals(ClientLightStorage.FinishDecision.WAIT, request.decision());
                final Runnable released = storage.releasePendingFinishAfterNegotiation();
                assertNotNull(released);
                released.run();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, finishes.get());
        assertEquals(ClientLightStorage.Mode.CLIENT_COMPUTED, storage.mode());
        assertFalse(storage.freeze());
        assertFalse(storage.tryNegotiateClientComputed());
        assertNull(storage.releasePendingFinishAfterNegotiation());
    }

    @Test
    void eachConfigurationCycleAllowsExactlyOneFinishRequest() {
        final ClientLightStorage storage = new ClientLightStorage();

        storage.beginConfigurationCycle();
        assertTrue(storage.tryNegotiateClientComputed());
        assertEquals(ClientLightStorage.FinishDecision.RUN, storage.requestFinish(0L, () -> { }).decision());
        assertEquals(ClientLightStorage.FinishDecision.DUPLICATE, storage.requestFinish(5L, () -> { }).decision());

        storage.beginConfigurationCycle();
        assertFalse(storage.markProbeSent(10L));
        assertEquals(ClientLightStorage.FinishDecision.RUN, storage.requestFinish(10L, () -> { }).decision());
        assertEquals(ClientLightStorage.FinishDecision.DUPLICATE, storage.requestFinish(11L, () -> { }).decision());
        assertEquals(ClientLightStorage.Mode.CLIENT_COMPUTED, storage.mode());
        assertEquals(2L, storage.modeGeneration());
    }

    @Test
    void logsOnlyTheFirstClientComputedChunkBypass() {
        final ClientLightStorage storage = new ClientLightStorage();

        assertTrue(storage.markClientComputedBypassLogged());
        assertFalse(storage.markClientComputedBypassLogged());
    }

    @Test
    void removalDropsPendingFinish() {
        final ClientLightStorage storage = new ClientLightStorage();

        assertTrue(storage.markProbeSent(0L));
        assertEquals(ClientLightStorage.FinishDecision.WAIT, storage.requestFinish(1L, () -> { }).decision());
        final TrackingScheduledFuture timeout = new TrackingScheduledFuture();
        storage.attachTimeout(timeout);
        storage.onRemove();

        assertFalse(storage.hasPendingFinish());
        assertTrue(timeout.isCancelled());
        assertNull(storage.timeoutPendingFinish(Long.MAX_VALUE));
        assertEquals(ClientLightStorage.FinishDecision.CLOSED, storage.requestFinish(2L, () -> { }).decision());
        assertFalse(storage.tryNegotiateClientComputed());
    }

    private static final class TrackingScheduledFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        @Override
        public long getDelay(final TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(final Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            this.cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return this.cancelled;
        }

        @Override
        public boolean isDone() {
            return this.cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(final long timeout, final TimeUnit unit) {
            return null;
        }
    }

}
