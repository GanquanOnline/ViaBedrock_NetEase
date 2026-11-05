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

import com.viaversion.viaversion.api.connection.StorableObject;

import java.util.concurrent.TimeUnit;

/**
 * Java 自定义负载桥的连接级固定窗口限流器。
 * 该存储挂在 ViaVersion 连接上，而不是可伪造的玩家 id 上。
 */
public final class JavaCustomPayloadRateLimitStorage implements StorableObject {

    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1L);

    private long windowStartedNanos = Long.MIN_VALUE;
    private int messages;
    private int bytes;

    public synchronized boolean tryAcquire(final long nowNanos, final int payloadBytes,
                                           final int maxMessages, final int maxBytes) {
        if (payloadBytes < 0 || maxMessages < 1 || maxBytes < 1 || payloadBytes > maxBytes) {
            return false;
        }
        if (this.windowStartedNanos == Long.MIN_VALUE || nowNanos - this.windowStartedNanos >= WINDOW_NANOS) {
            this.windowStartedNanos = nowNanos;
            this.messages = 0;
            this.bytes = 0;
        }
        if (this.messages >= maxMessages || payloadBytes > maxBytes - this.bytes) {
            return false;
        }

        this.messages++;
        this.bytes += payloadBytes;
        return true;
    }
}
