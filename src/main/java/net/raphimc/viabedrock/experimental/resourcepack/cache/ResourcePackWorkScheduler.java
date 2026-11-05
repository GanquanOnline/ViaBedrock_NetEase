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

import net.raphimc.viabedrock.platform.ViaBedrockConfig;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ResourcePackWorkScheduler {

    static final int MAX_WORKERS = 64;
    static final int MAX_QUEUE_CAPACITY = 4_096;

    private final ThreadPoolExecutor cpuExecutor;
    private final ThreadPoolExecutor ioExecutor;
    private final ScheduledThreadPoolExecutor maintenanceExecutor;
    private final ResourcePackCacheMetrics metrics;

    public ResourcePackWorkScheduler(final ViaBedrockConfig config, final ResourcePackCacheMetrics metrics) {
        this.metrics = metrics;
        final int availableProcessors = Runtime.getRuntime().availableProcessors();
        final int configuredCpuWorkers = config.getResourcePackCacheCpuWorkers();
        final int cpuWorkers = configuredCpuWorkers > 0
                ? clamp(configuredCpuWorkers, 1, MAX_WORKERS)
                : Math.max(2, Math.min(4, availableProcessors / 2));
        final int ioWorkers = clamp(config.getResourcePackCacheIoWorkers(), 1, MAX_WORKERS);
        final int queueCapacity = clamp(config.getResourcePackCacheQueueCapacity(), 1, MAX_QUEUE_CAPACITY);
        this.cpuExecutor = createExecutor(cpuWorkers, queueCapacity, "ViaBedrock Pack CPU");
        this.ioExecutor = createExecutor(ioWorkers, queueCapacity, "ViaBedrock Pack IO");
        this.maintenanceExecutor = new ScheduledThreadPoolExecutor(
                1, new NamedThreadFactory("ViaBedrock Pack Maintenance"));
        this.maintenanceExecutor.setRemoveOnCancelPolicy(true);
        this.metrics.executorCapacity(cpuWorkers, queueCapacity, ioWorkers, queueCapacity);
        this.refreshMetrics();
    }

    public Executor cpuExecutor() {
        return this.cpuExecutor;
    }

    public Executor ioExecutor() {
        return this.ioExecutor;
    }

    public <T> CompletableFuture<T> submitCpu(final Callable<T> task) {
        return this.submit(this.cpuExecutor, task);
    }

    public <T> CompletableFuture<T> submitIo(final Callable<T> task) {
        return this.submit(this.ioExecutor, task);
    }

    public CompletableFuture<Void> runCpu(final Runnable task) {
        return this.submitCpu(() -> {
            task.run();
            return null;
        });
    }

    public CompletableFuture<Void> runIo(final Runnable task) {
        return this.submitIo(() -> {
            task.run();
            return null;
        });
    }

    /** 守护定时器只负责入队；维护工作本身始终在有界 IO 执行器上运行。 */
    public ScheduledFuture<?> scheduleIoAtFixedRate(final Runnable task, final long initialDelay,
                                                    final long period, final TimeUnit unit) {
        return this.maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                this.runIo(task);
            } catch (Throwable ignored) {
                // 保持固定频率触发器存活；提交失败会通过执行器指标暴露。
            }
        }, initialDelay, period, unit);
    }

    public void shutdown() {
        for (Runnable task : this.maintenanceExecutor.shutdownNow()) {
            if (task instanceof Future<?> future) {
                future.cancel(false);
            }
        }
        this.failQueuedTasks(this.cpuExecutor.shutdownNow(), "CPU");
        this.failQueuedTasks(this.ioExecutor.shutdownNow(), "IO");
        this.refreshMetrics();
    }

    private <T> CompletableFuture<T> submit(final ThreadPoolExecutor executor, final Callable<T> task) {
        final SubmittedTask<T> submittedTask = new SubmittedTask<>(task);
        try {
            executor.execute(submittedTask);
        } catch (RejectedExecutionException e) {
            this.recordRejection(executor);
            submittedTask.fail(e);
        }
        this.refreshMetrics();
        return submittedTask.result();
    }

    private void failQueuedTasks(final Iterable<Runnable> queuedTasks, final String executorName) {
        for (Runnable queuedTask : queuedTasks) {
            if (queuedTask instanceof ResourcePackWorkScheduler.SubmittedTask<?> submittedTask) {
                submittedTask.fail(new RejectedExecutionException(
                        "Resource pack " + executorName + " executor shut down before task execution"));
            }
        }
    }

    private void recordRejection(final ThreadPoolExecutor executor) {
        if (executor == this.cpuExecutor) {
            this.metrics.cpuExecutorRejected();
        } else {
            this.metrics.ioExecutorRejected();
        }
    }

    private void refreshMetrics() {
        this.metrics.executorSnapshot(
                this.cpuExecutor.getActiveCount(), this.cpuExecutor.getQueue().size(),
                this.ioExecutor.getActiveCount(), this.ioExecutor.getQueue().size());
    }

    private static ThreadPoolExecutor createExecutor(final int threads, final int queueCapacity, final String name) {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new NamedThreadFactory(name),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private static int clamp(final int value, final int minimum, final int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private final class SubmittedTask<T> implements Runnable {
        private final Callable<T> task;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private SubmittedTask(final Callable<T> task) {
            this.task = task;
        }

        @Override
        public void run() {
            ResourcePackWorkScheduler.this.refreshMetrics();
            try {
                if (!this.result.isDone()) {
                    this.result.complete(this.task.call());
                }
            } catch (Throwable e) {
                this.result.completeExceptionally(e);
            } finally {
                ResourcePackWorkScheduler.this.refreshMetrics();
            }
        }

        private CompletableFuture<T> result() {
            return this.result;
        }

        private void fail(final Throwable error) {
            this.result.completeExceptionally(error);
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        private final String name;

        private NamedThreadFactory(final String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(final Runnable runnable) {
            final Thread thread = new Thread(runnable, this.name + " #" + this.counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

}
