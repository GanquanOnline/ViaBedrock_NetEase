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

import com.viaversion.viaversion.api.connection.StorableObject;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 把光照计算委托给 ECClientLight 的连接级协商状态。 */
public final class ClientLightStorage implements StorableObject {

    public static final long NEGOTIATION_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long NO_NEGOTIATION_START = Long.MIN_VALUE;

    private volatile Mode mode = Mode.UNKNOWN;
    private volatile boolean frozen;
    private volatile long modeGeneration;
    private boolean probeSent;
    private long negotiationStartedNanos = NO_NEGOTIATION_START;
    private boolean finishRequested;
    private Runnable pendingFinish;
    private ScheduledFuture<?> timeoutTask;
    private boolean closed;
    private boolean clientComputedBypassLogged;

    public synchronized void beginConfigurationCycle() {
        this.cancelTimeout();
        this.probeSent = false;
        this.negotiationStartedNanos = NO_NEGOTIATION_START;
        this.finishRequested = false;
        this.pendingFinish = null;
    }

    public synchronized boolean markProbeSent(final long nowNanos) {
        if (this.closed || this.frozen || this.probeSent) {
            return false;
        }
        this.probeSent = true;
        if (this.negotiationStartedNanos == NO_NEGOTIATION_START) {
            this.negotiationStartedNanos = nowNanos;
        }
        return true;
    }

    /** 在发送确认之前原子地提交客户端计算的光照。 */
    public synchronized boolean tryNegotiateClientComputed() {
        if (this.closed || this.frozen || this.mode != Mode.UNKNOWN) {
            return false;
        }
        this.mode = Mode.CLIENT_COMPUTED;
        this.modeGeneration++;
        return true;
    }

    public synchronized FinishRequest requestFinish(final long nowNanos, final Runnable finish) {
        if (this.closed) {
            return new FinishRequest(FinishDecision.CLOSED, 0L);
        }
        if (this.finishRequested) {
            return new FinishRequest(FinishDecision.DUPLICATE, 0L);
        }
        this.finishRequested = true;

        if (this.frozen) {
            return new FinishRequest(FinishDecision.RUN, 0L);
        }
        if (this.mode == Mode.CLIENT_COMPUTED) {
            this.freezeLocked();
            return new FinishRequest(FinishDecision.RUN, 0L);
        }

        if (this.negotiationStartedNanos == NO_NEGOTIATION_START) {
            // START_GAME 可能抢在 Java CLIENT_INFORMATION 之前。让配置阶段
            // 多开一个有界窗口，好让随后的探测仍有时间完成。
            this.negotiationStartedNanos = nowNanos;
        }
        final long remainingNanos = this.remainingNegotiationNanos(nowNanos);
        if (remainingNanos <= 0L) {
            this.freezeLocked();
            return new FinishRequest(FinishDecision.RUN, 0L);
        }

        this.pendingFinish = finish;
        return new FinishRequest(FinishDecision.WAIT, remainingNanos);
    }

    public synchronized void attachTimeout(final ScheduledFuture<?> timeoutTask) {
        if (this.closed || this.frozen || this.pendingFinish == null) {
            timeoutTask.cancel(false);
            return;
        }
        this.cancelTimeout();
        this.timeoutTask = timeoutTask;
    }

    public synchronized Runnable releasePendingFinishAfterNegotiation() {
        if (this.closed || this.pendingFinish == null || this.mode != Mode.CLIENT_COMPUTED || this.frozen) {
            return null;
        }
        this.freezeLocked();
        return this.takePendingFinish();
    }

    public synchronized Runnable timeoutPendingFinish(final long nowNanos) {
        if (this.closed || this.pendingFinish == null) {
            return null;
        }
        if (this.frozen) {
            return this.takePendingFinish();
        }
        if (this.remainingNegotiationNanos(nowNanos) > 0L) {
            return null;
        }
        this.freezeLocked();
        return this.takePendingFinish();
    }

    public synchronized long remainingNegotiationNanos(final long nowNanos) {
        if (this.negotiationStartedNanos == NO_NEGOTIATION_START) {
            return 0L;
        }
        return NEGOTIATION_WINDOW_NANOS - (nowNanos - this.negotiationStartedNanos);
    }

    public synchronized boolean hasPendingFinish() {
        return this.pendingFinish != null;
    }

    public synchronized void close() {
        this.closed = true;
        this.pendingFinish = null;
        this.cancelTimeout();
    }

    @Override
    public void onRemove() {
        this.close();
    }

    /**
     * 冻结整条连接的决策。未确认的客户端会安全地回退到代理光照。
     *
     * @return 若本次调用冻结了决策则返回 true
     */
    public synchronized boolean freeze() {
        if (this.closed || this.frozen) {
            return false;
        }
        this.freezeLocked();
        return true;
    }

    private void freezeLocked() {
        if (this.mode == Mode.UNKNOWN) {
            this.mode = Mode.SERVER_COMPUTED;
        }
        this.frozen = true;
        this.modeGeneration++;
    }

    private Runnable takePendingFinish() {
        final Runnable finish = this.pendingFinish;
        this.pendingFinish = null;
        this.cancelTimeout();
        return finish;
    }

    private void cancelTimeout() {
        if (this.timeoutTask != null) {
            this.timeoutTask.cancel(false);
            this.timeoutTask = null;
        }
    }

    public Mode mode() {
        return this.mode;
    }

    public boolean isClientComputed() {
        return this.mode == Mode.CLIENT_COMPUTED;
    }

    public boolean allowsProxyComputation() {
        return this.mode != Mode.CLIENT_COMPUTED;
    }

    public long modeGeneration() {
        return this.modeGeneration;
    }

    public synchronized boolean markClientComputedBypassLogged() {
        if (this.clientComputedBypassLogged) {
            return false;
        }
        this.clientComputedBypassLogged = true;
        return true;
    }

    public enum Mode {
        UNKNOWN,
        SERVER_COMPUTED,
        CLIENT_COMPUTED
    }

    public enum FinishDecision {
        RUN,
        WAIT,
        DUPLICATE,
        CLOSED
    }

    public record FinishRequest(FinishDecision decision, long waitNanos) {
    }

}
