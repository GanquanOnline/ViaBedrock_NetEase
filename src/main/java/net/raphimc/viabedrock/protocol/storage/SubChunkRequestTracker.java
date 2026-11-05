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

import com.viaversion.viaversion.api.minecraft.ChunkPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 跟踪每个渐进式子区块请求，从入队到恰好一个终态结果。 */
final class SubChunkRequestTracker<O> {

    private final int maxRetries;
    private final int responseTimeoutTicks;
    private final int blobTimeoutTicks;
    private final Set<Position> queued = new HashSet<>();
    private final Map<Position, Pending<O>> pending = new HashMap<>();
    private final Map<Position, Pending<O>> claimed = new HashMap<>();
    private final Map<Position, Integer> retryCounts = new HashMap<>();
    private final Map<Long, Integer> outstandingCounts = new HashMap<>();
    private long nextAttempt;

    SubChunkRequestTracker(final int maxRetries, final int responseTimeoutTicks, final int blobTimeoutTicks) {
        if (maxRetries < 0 || responseTimeoutTicks <= 0 || blobTimeoutTicks <= 0) {
            throw new IllegalArgumentException("Invalid sub-chunk retry or timeout configuration");
        }
        this.maxRetries = maxRetries;
        this.responseTimeoutTicks = responseTimeoutTicks;
        this.blobTimeoutTicks = blobTimeoutTicks;
    }

    boolean contains(final Position position) {
        return this.queued.contains(position) || this.pending.containsKey(position) || this.claimed.containsKey(position);
    }

    boolean enqueue(final Position position) {
        if (this.contains(position) || !this.queued.add(position)) {
            return false;
        }
        this.outstandingCounts.merge(position.chunkKey(), 1, Integer::sum);
        return true;
    }

    Set<Position> queuedPositions() {
        return Set.copyOf(this.queued);
    }

    boolean hasQueued() {
        return !this.queued.isEmpty();
    }

    DispatchResult dispatch(final Position position, final O owner, final long tick) {
        if (!this.queued.remove(position)) {
            return DispatchResult.NOT_QUEUED;
        }
        if (owner == null) {
            this.retryCounts.remove(position);
            this.decrementOutstanding(position);
            return DispatchResult.DISCARDED;
        }
        this.pending.put(position, new Pending<>(owner, ++this.nextAttempt, tick, false));
        return DispatchResult.DISPATCHED;
    }

    Token<O> captureResponse(final Position position, final O currentOwner, final long tick, final boolean waitingForBlob) {
        final Pending<O> request = this.pending.get(position);
        if (request == null || request.owner() != currentOwner) {
            return null;
        }
        if (waitingForBlob && !request.waitingForBlob()) {
            this.pending.put(position, new Pending<>(request.owner(), request.attempt(), tick, true));
        }
        return new Token<>(request.owner(), request.attempt());
    }

    Claim<O> claim(final Position position, final Token<O> token, final O currentOwner) {
        if (token == null) {
            return null;
        }
        final Pending<O> request = this.pending.get(position);
        if (
                request == null
                        || request.owner() != token.owner()
                        || request.attempt() != token.attempt()
                        || request.owner() != currentOwner
        ) {
            return null;
        }
        this.pending.remove(position);
        this.claimed.put(position, request);
        return new Claim<>(position, request.owner(), request.attempt());
    }

    boolean complete(final Claim<O> claim) {
        if (!this.consumeClaim(claim)) {
            return false;
        }
        this.retryCounts.remove(claim.position());
        this.decrementOutstanding(claim.position());
        return true;
    }

    RetryResult retry(final Claim<O> claim, final boolean ownerCanRetry) {
        if (!this.consumeClaim(claim)) {
            return RetryResult.STALE;
        }
        final int retries = this.retryCounts.merge(claim.position(), 1, Integer::sum);
        if (retries <= this.maxRetries && ownerCanRetry) {
            this.queued.add(claim.position());
            return RetryResult.REQUEUED;
        }

        this.retryCounts.remove(claim.position());
        this.decrementOutstanding(claim.position());
        return RetryResult.EXHAUSTED;
    }

    List<Expired<O>> expire(final long tick) {
        return this.expire(tick, Integer.MAX_VALUE);
    }

    List<Expired<O>> expire(final long tick, final int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("Expiration limit must be positive");
        }
        final List<Expired<O>> expired = new ArrayList<>();
        final Iterator<Map.Entry<Position, Pending<O>>> iterator = this.pending.entrySet().iterator();
        while (iterator.hasNext() && expired.size() < limit) {
            final Map.Entry<Position, Pending<O>> entry = iterator.next();
            final Pending<O> request = entry.getValue();
            final int timeoutTicks = request.waitingForBlob() ? this.blobTimeoutTicks : this.responseTimeoutTicks;
            if (tick - request.phaseStartedTick() < timeoutTicks) {
                continue;
            }

            iterator.remove();
            this.claimed.put(entry.getKey(), request);
            expired.add(new Expired<>(
                    new Claim<>(entry.getKey(), request.owner(), request.attempt()),
                    request.waitingForBlob()
            ));
        }
        return expired;
    }

    Set<Long> cancelIf(final Predicate<Position> predicate) {
        final Set<Position> canceled = new HashSet<>();
        this.queued.removeIf(position -> {
            if (!predicate.test(position)) {
                return false;
            }
            canceled.add(position);
            return true;
        });
        this.pending.keySet().removeIf(position -> {
            if (!predicate.test(position)) {
                return false;
            }
            canceled.add(position);
            return true;
        });
        this.claimed.keySet().removeIf(position -> {
            if (!predicate.test(position)) {
                return false;
            }
            canceled.add(position);
            return true;
        });
        this.retryCounts.keySet().removeIf(predicate);

        final Set<Long> affectedColumns = new HashSet<>();
        for (Position position : canceled) {
            affectedColumns.add(position.chunkKey());
            this.decrementOutstanding(position);
        }
        return affectedColumns;
    }

    void cancelColumn(final long chunkKey) {
        this.cancelIf(position -> position.chunkKey() == chunkKey);
        this.outstandingCounts.remove(chunkKey);
    }

    boolean hasOutstanding(final long chunkKey) {
        return this.outstandingCounts.getOrDefault(chunkKey, 0) > 0;
    }

    int outstandingCount(final long chunkKey) {
        return this.outstandingCounts.getOrDefault(chunkKey, 0);
    }

    boolean isEmpty() {
        return this.queued.isEmpty()
                && this.pending.isEmpty()
                && this.claimed.isEmpty()
                && this.retryCounts.isEmpty()
                && this.outstandingCounts.isEmpty();
    }

    private boolean consumeClaim(final Claim<O> claim) {
        if (claim == null) {
            return false;
        }
        final Pending<O> request = this.claimed.get(claim.position());
        if (request == null || request.owner() != claim.owner() || request.attempt() != claim.attempt()) {
            return false;
        }
        this.claimed.remove(claim.position());
        return true;
    }

    private void decrementOutstanding(final Position position) {
        final long chunkKey = position.chunkKey();
        final int outstanding = this.outstandingCounts.getOrDefault(chunkKey, 0);
        if (outstanding <= 1) {
            this.outstandingCounts.remove(chunkKey);
        } else {
            this.outstandingCounts.put(chunkKey, outstanding - 1);
        }
    }

    record Position(int chunkX, int subChunkY, int chunkZ) {

        long chunkKey() {
            return ChunkPosition.chunkKey(this.chunkX, this.chunkZ);
        }
    }

    record Token<O>(O owner, long attempt) {
    }

    record Claim<O>(Position position, O owner, long attempt) {
    }

    record Expired<O>(Claim<O> claim, boolean waitingForBlob) {
    }

    private record Pending<O>(O owner, long attempt, long phaseStartedTick, boolean waitingForBlob) {
    }

    enum DispatchResult {
        DISPATCHED,
        DISCARDED,
        NOT_QUEUED
    }

    enum RetryResult {
        REQUEUED,
        EXHAUSTED,
        STALE
    }
}
