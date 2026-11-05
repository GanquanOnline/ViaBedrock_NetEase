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
package net.raphimc.viabedrock.api.model.entity;

final class OffhandPromotionState {

    static final int RESTORE_RESPONSE_TIMEOUT_TICKS = 40;

    private Phase phase = Phase.IDLE;
    private Integer promotionRequestId;
    private Integer restoreRequestId;
    private int restoreRequestAge = -1;

    /**
     * 仅在 MOT 接受静默 F 键交换之后才为 true。PENDING 仍会在物理上占用
     * 追踪器，但此时不得把 Java 的 OFF_HAND 重映射到它上面。
     */
    boolean isPromoted() {
        return this.phase != Phase.IDLE && this.phase != Phase.PROMOTION_PENDING;
    }

    boolean isPromotionPending() {
        return this.phase == Phase.PROMOTION_PENDING;
    }

    boolean shouldRetryRestore() {
        return this.phase == Phase.RESTORE_PENDING;
    }

    boolean isRestoring() {
        return this.phase == Phase.RESTORING;
    }

    Integer promotionRequestId() {
        return this.promotionRequestId;
    }

    Integer restoreRequestId() {
        return this.restoreRequestId;
    }

    boolean canProcessHandSensitiveAction(final boolean offhandAction,
                                          final boolean originalOffhandUseContinuation) {
        return this.canProcessHandSensitiveAction(offhandAction, originalOffhandUseContinuation, false);
    }

    boolean canProcessHandSensitiveAction(final boolean offhandAction,
                                          final boolean originalOffhandUseContinuation,
                                          final boolean allowPromotedOffhandReuse) {
        if (this.phase == Phase.PROMOTION_PENDING
                || this.phase == Phase.RESTORING
                || this.phase == Phase.RESTORE_FLUSH_PENDING) {
            return false;
        }
        if (this.phase == Phase.IDLE) {
            return true;
        }
        return originalOffhandUseContinuation
                || (offhandAction && allowPromotedOffhandReuse);
    }

    boolean scheduleRestore() {
        if (this.phase == Phase.IDLE
                || this.phase == Phase.PROMOTION_PENDING
                || this.phase == Phase.RESTORE_SCHEDULED
                || this.phase == Phase.RESTORING) {
            return false;
        }
        this.phase = Phase.RESTORE_SCHEDULED;
        return true;
    }

    boolean isRestoreScheduled() {
        return this.phase == Phase.RESTORE_SCHEDULED;
    }

    void promotionPending(final Integer requestId) {
        this.phase = Phase.PROMOTION_PENDING;
        this.promotionRequestId = requestId;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
    }

    boolean promotionConfirmed(final Integer requestId) {
        if (this.phase != Phase.PROMOTION_PENDING) {
            return false;
        }
        if (this.promotionRequestId != null && !this.promotionRequestId.equals(requestId)) {
            return false;
        }
        this.phase = Phase.PROMOTED;
        this.promotionRequestId = null;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
        return true;
    }

    boolean promotionRejected(final Integer requestId) {
        if (this.phase != Phase.PROMOTION_PENDING) {
            return false;
        }
        if (this.promotionRequestId != null && !this.promotionRequestId.equals(requestId)) {
            return false;
        }
        this.phase = Phase.IDLE;
        this.promotionRequestId = null;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
        return true;
    }

    void promoted() {
        this.phase = Phase.PROMOTED;
        this.promotionRequestId = null;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
    }

    void restoreSubmissionFailed() {
        if (this.phase == Phase.IDLE || this.phase == Phase.PROMOTION_PENDING) {
            return;
        }
        this.phase = Phase.RESTORE_PENDING;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
    }

    void restoreRequestSent(final int requestId) {
        this.restoreRequestSent(requestId, 0);
    }

    void restoreRequestSent(final int requestId, final int currentAge) {
        this.phase = Phase.RESTORING;
        this.restoreRequestId = requestId;
        this.restoreRequestAge = currentAge;
    }

    boolean restoreTimedOut(final int currentAge) {
        return this.phase == Phase.RESTORING
                && this.restoreRequestAge >= 0
                && currentAge - this.restoreRequestAge >= RESTORE_RESPONSE_TIMEOUT_TICKS;
    }

    boolean restoreConfirmed(final Integer requestId) {
        if (!this.matchesRestoringRequest(requestId)) {
            return false;
        }
        this.phase = Phase.RESTORE_FLUSH_PENDING;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
        return true;
    }

    boolean completeRestoreFlush() {
        if (this.phase != Phase.RESTORE_FLUSH_PENDING) {
            return false;
        }
        this.phase = Phase.IDLE;
        this.promotionRequestId = null;
        return true;
    }

    boolean restoreRejected(final Integer requestId) {
        if (!this.matchesRestoringRequest(requestId)) {
            return false;
        }
        this.phase = Phase.RESTORE_PENDING;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
        return true;
    }

    void abandonRestore() {
        this.phase = Phase.IDLE;
        this.promotionRequestId = null;
        this.restoreRequestId = null;
        this.restoreRequestAge = -1;
    }

    private boolean matchesRestoringRequest(final Integer requestId) {
        return this.phase == Phase.RESTORING && this.restoreRequestId != null
                && this.restoreRequestId.equals(requestId);
    }

    private enum Phase {
        IDLE,
        PROMOTION_PENDING,
        PROMOTED,
        RESTORE_SCHEDULED,
        RESTORING,
        RESTORE_FLUSH_PENDING,
        RESTORE_PENDING
    }

}
