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

import java.util.HashSet;
import java.util.Set;

/** 在区块列首次可见快照之后最多保留一次最终重发义务。 */
final class ProgressiveChunkResendQueue {

    private final Set<Long> changedAfterFirstSend = new HashSet<>();

    boolean onProgress(long chunkKey, boolean firstSnapshotSent, boolean hasOutstanding, boolean changed) {
        if (!firstSnapshotSent) {
            this.changedAfterFirstSend.remove(chunkKey);
            return true;
        }
        if (changed) {
            this.changedAfterFirstSend.add(chunkKey);
        }
        return !hasOutstanding && this.changedAfterFirstSend.remove(chunkKey);
    }

    long[] drainAll() {
        long[] chunkKeys = this.changedAfterFirstSend.stream().mapToLong(Long::longValue).toArray();
        this.changedAfterFirstSend.clear();
        return chunkKeys;
    }

    void onSnapshotSent(long chunkKey) {
        this.changedAfterFirstSend.remove(chunkKey);
    }

    void forget(long chunkKey) {
        this.changedAfterFirstSend.remove(chunkKey);
    }
}
