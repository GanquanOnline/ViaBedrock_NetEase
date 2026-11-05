/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.resourcepack.cache;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 短暂保留构建失败，避免反复请求的坏键造成重试风暴。 */
public final class FailureBackoff<K> {

    public static final long RETRY_DELAY_NANOS = TimeUnit.SECONDS.toNanos(5L);

    private final Cache<K, Throwable> failures;

    public FailureBackoff() {
        this(System::nanoTime);
    }

    public FailureBackoff(final LongSupplier nanoTime) {
        Objects.requireNonNull(nanoTime, "nanoTime");
        this.failures = CacheBuilder.newBuilder()
                .expireAfterWrite(RETRY_DELAY_NANOS, TimeUnit.NANOSECONDS)
                .ticker(new Ticker() {
                    @Override
                    public long read() {
                        return nanoTime.getAsLong();
                    }
                })
                .build();
    }

    public Throwable getIfActive(final K key) {
        return this.failures.getIfPresent(key);
    }

    public void record(final K key, final Throwable failure) {
        this.failures.put(key, Objects.requireNonNull(failure, "failure"));
    }

    public void clear(final K key) {
        this.failures.invalidate(key);
    }

}
