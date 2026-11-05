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

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class ResourcePackCacheMetrics implements ResourcePackCacheMXBean {

    public static final String OBJECT_NAME = "net.raphimc.viabedrock:type=ResourcePackCache";

    private static final Object REGISTRATION_LOCK = new Object();

    private final String registrationId = UUID.randomUUID().toString();
    private final Map<Tier, Counters> tiers = new EnumMap<>(Tier.class);
    private final AtomicLong activeRuntimeLeases = new AtomicLong();
    private final AtomicLong activeArtifactLeases = new AtomicLong();
    private final AtomicLong activeRuntimeWeightBytes = new AtomicLong();
    private final AtomicLong inflightEstimatedWeightBytes = new AtomicLong();
    private final AtomicLong diskBytes = new AtomicLong();
    private final AtomicLong diskFiles = new AtomicLong();
    private final LongAdder diskDeletedBytes = new LongAdder();
    private final LongAdder diskDeletedFiles = new LongAdder();
    private final LongAdder diskCleanupFailures = new LongAdder();
    private final LongAdder diskMetricRefreshFailures = new LongAdder();
    private final AtomicLong activeArtifactBuildWorkspaces = new AtomicLong();
    private final AtomicLong artifactBuildWorkspaceBytes = new AtomicLong();
    private final AtomicLong artifactBuildWorkspaceFiles = new AtomicLong();
    private final AtomicLong artifactBuildWorkspacePeakBytes = new AtomicLong();
    private final LongAdder artifactBuildWorkspaceDeletedBytes = new LongAdder();
    private final LongAdder artifactBuildWorkspaceDeletedFiles = new LongAdder();
    private final LongAdder artifactBuildWorkspaceCleanupFailures = new LongAdder();
    private final AtomicLong casDiskBytes = new AtomicLong();
    private final AtomicLong casDiskFiles = new AtomicLong();
    private final LongAdder casDiskDeletedBytes = new LongAdder();
    private final LongAdder casDiskDeletedFiles = new LongAdder();
    private final LongAdder casDiskCleanupFailures = new LongAdder();
    private final LongAdder aliasConflicts = new LongAdder();
    private final AtomicLong trustedAliasConflictTombstones = new AtomicLong();
    private final AtomicLong trustedAliasConflictTombstoneBytes = new AtomicLong();
    private final AtomicLong trustedAliasConflictTombstoneCapacity = new AtomicLong();
    private final AtomicBoolean trustedAliasGlobalQuarantine = new AtomicBoolean();
    private final LongAdder buildTimeMillis = new LongAdder();
    private final LongAdder buildTimeSamples = new LongAdder();
    private final AtomicLong maxBuildTimeMillis = new AtomicLong();
    private final LongAdder executorRejections = new LongAdder();
    private final LongAdder cpuExecutorRejections = new LongAdder();
    private final LongAdder ioExecutorRejections = new LongAdder();
    private final AtomicInteger cpuExecutorWorkerCount = new AtomicInteger();
    private final AtomicInteger cpuExecutorQueueCapacity = new AtomicInteger();
    private final AtomicInteger cpuExecutorActiveThreads = new AtomicInteger();
    private final AtomicInteger cpuExecutorQueueSize = new AtomicInteger();
    private final AtomicInteger ioExecutorWorkerCount = new AtomicInteger();
    private final AtomicInteger ioExecutorQueueCapacity = new AtomicInteger();
    private final AtomicInteger ioExecutorActiveThreads = new AtomicInteger();
    private final AtomicInteger ioExecutorQueueSize = new AtomicInteger();
    private final AtomicLong pendingHttpRequests = new AtomicLong();
    private final AtomicLong maxPendingHttpRequestsPerToken = new AtomicLong();
    private final AtomicLong maxPendingHttpRequestsGlobal = new AtomicLong();
    private final LongAdder httpTokenRequestRejections = new LongAdder();
    private final LongAdder httpGlobalRequestRejections = new LongAdder();

    public ResourcePackCacheMetrics() {
        for (Tier tier : Tier.values()) {
            this.tiers.put(tier, new Counters());
        }
    }

    public void register() {
        try {
            synchronized (REGISTRATION_LOCK) {
                final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                final ObjectName name = new ObjectName(OBJECT_NAME);
                if (server.isRegistered(name)) {
                    server.unregisterMBean(name);
                }
                server.registerMBean(new StandardMBean(this, ResourcePackCacheMXBean.class, true), name);
            }
        } catch (Throwable ignored) {
            // 指标采集绝不能阻止 ViaBedrock 启动。
        }
    }

    public void unregister() {
        try {
            synchronized (REGISTRATION_LOCK) {
                final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
                final ObjectName name = new ObjectName(OBJECT_NAME);
                if (server.isRegistered(name)
                        && this.registrationId.equals(server.getAttribute(name, "RegistrationId"))) {
                    server.unregisterMBean(name);
                }
            }
        } catch (Throwable ignored) {
            // 指标拆除绝不能阻止 ViaBedrock 停止。
        }
    }

    public void hit(final Tier tier) {
        this.tier(tier).hits.increment();
    }

    public void miss(final Tier tier) {
        this.tier(tier).misses.increment();
    }

    public void build(final Tier tier) {
        this.tier(tier).builds.increment();
    }

    public void failure(final Tier tier) {
        this.tier(tier).failures.increment();
    }

    public void waiter(final Tier tier) {
        this.tier(tier).waiters.increment();
    }

    public void setInflight(final Tier tier, final long value) {
        this.tier(tier).inflight.set(value);
    }

    public void setWeight(final Tier tier, final long weightBytes, final long maxWeightBytes) {
        this.tier(tier).weightBytes.set(weightBytes);
        this.tier(tier).maxWeightBytes.set(maxWeightBytes);
    }

    public void eviction(final Tier tier) {
        this.tier(tier).evictions.increment();
    }

    public void setActiveRuntimeLeases(final long value) {
        this.activeRuntimeLeases.set(value);
    }

    public void setActiveArtifactLeases(final long value) {
        this.activeArtifactLeases.set(Math.max(0L, value));
    }

    public void setActiveRuntimeWeightBytes(final long value) {
        this.activeRuntimeWeightBytes.set(Math.max(0L, value));
    }

    public void addInflightEstimatedWeightBytes(final long delta) {
        this.inflightEstimatedWeightBytes.updateAndGet(current -> Math.max(0L, current + delta));
    }

    public void setArtifactDiskUsage(final long bytes, final long files) {
        this.diskBytes.set(Math.max(0L, bytes));
        this.diskFiles.set(Math.max(0L, files));
    }

    public void recordArtifactDiskCleanup(final long deletedBytes, final long deletedFiles) {
        this.diskDeletedBytes.add(Math.max(0L, deletedBytes));
        this.diskDeletedFiles.add(Math.max(0L, deletedFiles));
    }

    public void artifactDiskCleanupFailure() {
        this.diskCleanupFailures.increment();
    }

    public void artifactDiskMetricRefreshFailure() {
        this.diskMetricRefreshFailures.increment();
    }

    public void setActiveArtifactBuildWorkspaces(final long value) {
        this.activeArtifactBuildWorkspaces.set(Math.max(0L, value));
    }

    public void setArtifactBuildWorkspaceDiskUsage(final long bytes, final long files) {
        final long safeBytes = Math.max(0L, bytes);
        this.artifactBuildWorkspaceBytes.set(safeBytes);
        this.artifactBuildWorkspaceFiles.set(Math.max(0L, files));
        this.artifactBuildWorkspacePeakBytes.accumulateAndGet(safeBytes, Math::max);
    }

    public void recordArtifactBuildWorkspaceCleanup(final long deletedBytes, final long deletedFiles) {
        final long safeBytes = Math.max(0L, deletedBytes);
        this.artifactBuildWorkspaceDeletedBytes.add(safeBytes);
        this.artifactBuildWorkspaceDeletedFiles.add(Math.max(0L, deletedFiles));
        this.artifactBuildWorkspacePeakBytes.accumulateAndGet(safeBytes, Math::max);
    }

    public void artifactBuildWorkspaceCleanupFailure() {
        this.artifactBuildWorkspaceCleanupFailures.increment();
    }

    public void setCasDiskUsage(final long bytes, final long files) {
        this.casDiskBytes.set(Math.max(0L, bytes));
        this.casDiskFiles.set(Math.max(0L, files));
    }

    public void recordCasDiskCleanup(final long deletedBytes, final long deletedFiles) {
        this.casDiskDeletedBytes.add(Math.max(0L, deletedBytes));
        this.casDiskDeletedFiles.add(Math.max(0L, deletedFiles));
    }

    public void casDiskCleanupFailure() {
        this.casDiskCleanupFailures.increment();
    }

    public void aliasConflict() {
        this.aliasConflicts.increment();
    }

    public void setTrustedAliasConflictState(final long tombstones, final long bytes,
                                             final long capacity, final boolean globalQuarantine) {
        this.trustedAliasConflictTombstones.set(Math.max(0L, tombstones));
        this.trustedAliasConflictTombstoneBytes.set(Math.max(0L, bytes));
        this.trustedAliasConflictTombstoneCapacity.set(Math.max(0L, capacity));
        this.trustedAliasGlobalQuarantine.set(globalQuarantine);
    }

    public void recordBuildTime(final long millis) {
        this.buildTimeMillis.add(millis);
        this.buildTimeSamples.increment();
        this.maxBuildTimeMillis.accumulateAndGet(millis, Math::max);
    }

    public void recordBuildTime(final Tier tier, final long millis) {
        final Counters counters = this.tier(tier);
        counters.buildTimeMillis.add(millis);
        counters.buildTimeSamples.increment();
        counters.maxBuildTimeMillis.accumulateAndGet(millis, Math::max);
        this.recordBuildTime(millis);
    }

    public void setCompletedEntries(final Tier tier, final long entries) {
        this.tier(tier).completedEntries.set(Math.max(0L, entries));
    }

    public void cpuExecutorRejected() {
        this.executorRejections.increment();
        this.cpuExecutorRejections.increment();
    }

    public void ioExecutorRejected() {
        this.executorRejections.increment();
        this.ioExecutorRejections.increment();
    }

    public void executorCapacity(final int cpuWorkers, final int cpuQueueCapacity,
                                 final int ioWorkers, final int ioQueueCapacity) {
        this.cpuExecutorWorkerCount.set(cpuWorkers);
        this.cpuExecutorQueueCapacity.set(cpuQueueCapacity);
        this.ioExecutorWorkerCount.set(ioWorkers);
        this.ioExecutorQueueCapacity.set(ioQueueCapacity);
    }

    public void executorSnapshot(final int cpuActive, final int cpuQueued, final int ioActive, final int ioQueued) {
        this.cpuExecutorActiveThreads.set(cpuActive);
        this.cpuExecutorQueueSize.set(cpuQueued);
        this.ioExecutorActiveThreads.set(ioActive);
        this.ioExecutorQueueSize.set(ioQueued);
    }

    public void httpRequestCapacity(final long perToken, final long global) {
        this.maxPendingHttpRequestsPerToken.set(Math.max(0L, perToken));
        this.maxPendingHttpRequestsGlobal.set(Math.max(0L, global));
    }

    public void setPendingHttpRequests(final long value) {
        this.pendingHttpRequests.set(Math.max(0L, value));
    }

    public void httpTokenRequestRejected() {
        this.httpTokenRequestRejections.increment();
    }

    public void httpGlobalRequestRejected() {
        this.httpGlobalRequestRejections.increment();
    }

    private Counters tier(final Tier tier) {
        return this.tiers.get(tier);
    }

    @Override public long getArchiveHits() { return this.tier(Tier.ARCHIVE).hits.sum(); }
    @Override public long getArchiveMisses() { return this.tier(Tier.ARCHIVE).misses.sum(); }
    @Override public long getArchiveBuilds() { return this.tier(Tier.ARCHIVE).builds.sum(); }
    @Override public long getArchiveFailures() { return this.tier(Tier.ARCHIVE).failures.sum(); }
    @Override public long getArchiveWaiters() { return this.tier(Tier.ARCHIVE).waiters.sum(); }
    @Override public long getArchiveInflight() { return this.tier(Tier.ARCHIVE).inflight.get(); }
    @Override public long getArchiveWeightBytes() { return this.tier(Tier.ARCHIVE).weightBytes.get(); }
    @Override public long getArchiveMaxWeightBytes() { return this.tier(Tier.ARCHIVE).maxWeightBytes.get(); }
    @Override public long getArchiveEvictions() { return this.tier(Tier.ARCHIVE).evictions.sum(); }
    @Override public long getArchiveBuildTimeMillis() { return this.tier(Tier.ARCHIVE).buildTimeMillis.sum(); }
    @Override public long getArchiveBuildTimeSamples() { return this.tier(Tier.ARCHIVE).buildTimeSamples.sum(); }
    @Override public double getArchiveAverageBuildTimeMillis() { return this.averageBuildTime(Tier.ARCHIVE); }
    @Override public long getArchiveMaxBuildTimeMillis() { return this.tier(Tier.ARCHIVE).maxBuildTimeMillis.get(); }
    @Override public long getContentHits() { return this.tier(Tier.CONTENT).hits.sum(); }
    @Override public long getContentMisses() { return this.tier(Tier.CONTENT).misses.sum(); }
    @Override public long getContentBuilds() { return this.tier(Tier.CONTENT).builds.sum(); }
    @Override public long getContentFailures() { return this.tier(Tier.CONTENT).failures.sum(); }
    @Override public long getContentWaiters() { return this.tier(Tier.CONTENT).waiters.sum(); }
    @Override public long getContentInflight() { return this.tier(Tier.CONTENT).inflight.get(); }
    @Override public long getContentWeightBytes() { return this.tier(Tier.CONTENT).weightBytes.get(); }
    @Override public long getContentMaxWeightBytes() { return this.tier(Tier.CONTENT).maxWeightBytes.get(); }
    @Override public long getContentEvictions() { return this.tier(Tier.CONTENT).evictions.sum(); }
    @Override public long getContentBuildTimeMillis() { return this.tier(Tier.CONTENT).buildTimeMillis.sum(); }
    @Override public long getContentBuildTimeSamples() { return this.tier(Tier.CONTENT).buildTimeSamples.sum(); }
    @Override public double getContentAverageBuildTimeMillis() { return this.averageBuildTime(Tier.CONTENT); }
    @Override public long getContentMaxBuildTimeMillis() { return this.tier(Tier.CONTENT).maxBuildTimeMillis.get(); }
    @Override public long getMotionHits() { return this.tier(Tier.MOTION).hits.sum(); }
    @Override public long getMotionMisses() { return this.tier(Tier.MOTION).misses.sum(); }
    @Override public long getMotionBuilds() { return this.tier(Tier.MOTION).builds.sum(); }
    @Override public long getMotionFailures() { return this.tier(Tier.MOTION).failures.sum(); }
    @Override public long getMotionWaiters() { return this.tier(Tier.MOTION).waiters.sum(); }
    @Override public long getMotionInflight() { return this.tier(Tier.MOTION).inflight.get(); }
    @Override public long getMotionWeightBytes() { return this.tier(Tier.MOTION).weightBytes.get(); }
    @Override public long getMotionMaxWeightBytes() { return this.tier(Tier.MOTION).maxWeightBytes.get(); }
    @Override public long getMotionEvictions() { return this.tier(Tier.MOTION).evictions.sum(); }
    @Override public long getMotionBuildTimeMillis() { return this.tier(Tier.MOTION).buildTimeMillis.sum(); }
    @Override public long getMotionBuildTimeSamples() { return this.tier(Tier.MOTION).buildTimeSamples.sum(); }
    @Override public double getMotionAverageBuildTimeMillis() { return this.averageBuildTime(Tier.MOTION); }
    @Override public long getMotionMaxBuildTimeMillis() { return this.tier(Tier.MOTION).maxBuildTimeMillis.get(); }
    @Override public long getBlobHits() { return this.tier(Tier.BLOB).hits.sum(); }
    @Override public long getBlobMisses() { return this.tier(Tier.BLOB).misses.sum(); }
    @Override public long getBlobBuilds() { return this.tier(Tier.BLOB).builds.sum(); }
    @Override public long getBlobFailures() { return this.tier(Tier.BLOB).failures.sum(); }
    @Override public long getBlobWaiters() { return this.tier(Tier.BLOB).waiters.sum(); }
    @Override public long getBlobInflight() { return this.tier(Tier.BLOB).inflight.get(); }
    @Override public long getBlobCompletedEntries() { return this.tier(Tier.BLOB).completedEntries.get(); }
    @Override public long getBlobWeightBytes() { return this.tier(Tier.BLOB).weightBytes.get(); }
    @Override public long getBlobMaxWeightBytes() { return this.tier(Tier.BLOB).maxWeightBytes.get(); }
    @Override public long getBlobEvictions() { return this.tier(Tier.BLOB).evictions.sum(); }
    @Override public long getBlobBuildTimeMillis() { return this.tier(Tier.BLOB).buildTimeMillis.sum(); }
    @Override public long getBlobBuildTimeSamples() { return this.tier(Tier.BLOB).buildTimeSamples.sum(); }
    @Override public double getBlobAverageBuildTimeMillis() { return this.averageBuildTime(Tier.BLOB); }
    @Override public long getBlobMaxBuildTimeMillis() { return this.tier(Tier.BLOB).maxBuildTimeMillis.get(); }
    @Override public long getLayerHits() { return this.tier(Tier.LAYER).hits.sum(); }
    @Override public long getLayerMisses() { return this.tier(Tier.LAYER).misses.sum(); }
    @Override public long getLayerBuilds() { return this.tier(Tier.LAYER).builds.sum(); }
    @Override public long getLayerFailures() { return this.tier(Tier.LAYER).failures.sum(); }
    @Override public long getLayerWaiters() { return this.tier(Tier.LAYER).waiters.sum(); }
    @Override public long getLayerInflight() { return this.tier(Tier.LAYER).inflight.get(); }
    @Override public long getLayerWeightBytes() { return this.tier(Tier.LAYER).weightBytes.get(); }
    @Override public long getLayerMaxWeightBytes() { return this.tier(Tier.LAYER).maxWeightBytes.get(); }
    @Override public long getLayerEvictions() { return this.tier(Tier.LAYER).evictions.sum(); }
    @Override public long getLayerBuildTimeMillis() { return this.tier(Tier.LAYER).buildTimeMillis.sum(); }
    @Override public long getLayerBuildTimeSamples() { return this.tier(Tier.LAYER).buildTimeSamples.sum(); }
    @Override public double getLayerAverageBuildTimeMillis() { return this.averageBuildTime(Tier.LAYER); }
    @Override public long getLayerMaxBuildTimeMillis() { return this.tier(Tier.LAYER).maxBuildTimeMillis.get(); }
    @Override public long getRuntimeHits() { return this.tier(Tier.RUNTIME).hits.sum(); }
    @Override public long getRuntimeMisses() { return this.tier(Tier.RUNTIME).misses.sum(); }
    @Override public long getRuntimeBuilds() { return this.tier(Tier.RUNTIME).builds.sum(); }
    @Override public long getRuntimeFailures() { return this.tier(Tier.RUNTIME).failures.sum(); }
    @Override public long getRuntimeWaiters() { return this.tier(Tier.RUNTIME).waiters.sum(); }
    @Override public long getRuntimeInflight() { return this.tier(Tier.RUNTIME).inflight.get(); }
    @Override public long getRuntimeWeightBytes() { return this.tier(Tier.RUNTIME).weightBytes.get(); }
    @Override public long getRuntimeMaxWeightBytes() { return this.tier(Tier.RUNTIME).maxWeightBytes.get(); }
    @Override public long getRuntimeEvictions() { return this.tier(Tier.RUNTIME).evictions.sum(); }
    @Override public long getRuntimeBuildTimeMillis() { return this.tier(Tier.RUNTIME).buildTimeMillis.sum(); }
    @Override public long getRuntimeBuildTimeSamples() { return this.tier(Tier.RUNTIME).buildTimeSamples.sum(); }
    @Override public double getRuntimeAverageBuildTimeMillis() { return this.averageBuildTime(Tier.RUNTIME); }
    @Override public long getRuntimeMaxBuildTimeMillis() { return this.tier(Tier.RUNTIME).maxBuildTimeMillis.get(); }
    @Override public long getArtifactHits() { return this.tier(Tier.ARTIFACT).hits.sum(); }
    @Override public long getArtifactMisses() { return this.tier(Tier.ARTIFACT).misses.sum(); }
    @Override public long getArtifactBuilds() { return this.tier(Tier.ARTIFACT).builds.sum(); }
    @Override public long getArtifactFailures() { return this.tier(Tier.ARTIFACT).failures.sum(); }
    @Override public long getArtifactWaiters() { return this.tier(Tier.ARTIFACT).waiters.sum(); }
    @Override public long getArtifactInflight() { return this.tier(Tier.ARTIFACT).inflight.get(); }
    @Override public long getArtifactWeightBytes() { return this.tier(Tier.ARTIFACT).weightBytes.get(); }
    @Override public long getArtifactMaxWeightBytes() { return this.tier(Tier.ARTIFACT).maxWeightBytes.get(); }
    @Override public long getArtifactEvictions() { return this.tier(Tier.ARTIFACT).evictions.sum(); }
    @Override public long getArtifactBuildTimeMillis() { return this.tier(Tier.ARTIFACT).buildTimeMillis.sum(); }
    @Override public long getArtifactBuildTimeSamples() { return this.tier(Tier.ARTIFACT).buildTimeSamples.sum(); }
    @Override public double getArtifactAverageBuildTimeMillis() { return this.averageBuildTime(Tier.ARTIFACT); }
    @Override public long getArtifactMaxBuildTimeMillis() { return this.tier(Tier.ARTIFACT).maxBuildTimeMillis.get(); }
    @Override public long getActiveRuntimeLeases() { return this.activeRuntimeLeases.get(); }
    @Override public long getActiveArtifactLeases() { return this.activeArtifactLeases.get(); }
    @Override public long getActiveRuntimeWeightBytes() { return this.activeRuntimeWeightBytes.get(); }
    @Override public long getInflightEstimatedWeightBytes() { return this.inflightEstimatedWeightBytes.get(); }
    @Override public long getArtifactDiskBytes() { return this.diskBytes.get(); }
    @Override public long getArtifactDiskFiles() { return this.diskFiles.get(); }
    @Override public long getArtifactDiskDeletedBytes() { return this.diskDeletedBytes.sum(); }
    @Override public long getArtifactDiskDeletedFiles() { return this.diskDeletedFiles.sum(); }
    @Override public long getArtifactDiskCleanupFailures() { return this.diskCleanupFailures.sum(); }
    @Override public long getArtifactDiskMetricRefreshFailures() { return this.diskMetricRefreshFailures.sum(); }
    @Override public long getActiveArtifactBuildWorkspaces() { return this.activeArtifactBuildWorkspaces.get(); }
    @Override public long getArtifactBuildWorkspaceBytes() { return this.artifactBuildWorkspaceBytes.get(); }
    @Override public long getArtifactBuildWorkspaceFiles() { return this.artifactBuildWorkspaceFiles.get(); }
    @Override public long getArtifactBuildWorkspacePeakBytes() { return this.artifactBuildWorkspacePeakBytes.get(); }
    @Override public long getArtifactBuildWorkspaceDeletedBytes() {
        return this.artifactBuildWorkspaceDeletedBytes.sum();
    }
    @Override public long getArtifactBuildWorkspaceDeletedFiles() {
        return this.artifactBuildWorkspaceDeletedFiles.sum();
    }
    @Override public long getArtifactBuildWorkspaceCleanupFailures() {
        return this.artifactBuildWorkspaceCleanupFailures.sum();
    }
    @Override public long getCasDiskBytes() { return this.casDiskBytes.get(); }
    @Override public long getCasDiskFiles() { return this.casDiskFiles.get(); }
    @Override public long getCasDiskDeletedBytes() { return this.casDiskDeletedBytes.sum(); }
    @Override public long getCasDiskDeletedFiles() { return this.casDiskDeletedFiles.sum(); }
    @Override public long getCasDiskCleanupFailures() { return this.casDiskCleanupFailures.sum(); }
    @Override public long getAliasConflicts() { return this.aliasConflicts.sum(); }
    @Override public long getTrustedAliasConflictTombstones() {
        return this.trustedAliasConflictTombstones.get();
    }
    @Override public long getTrustedAliasConflictTombstoneBytes() {
        return this.trustedAliasConflictTombstoneBytes.get();
    }
    @Override public long getTrustedAliasConflictTombstoneCapacity() {
        return this.trustedAliasConflictTombstoneCapacity.get();
    }
    @Override public boolean getTrustedAliasGlobalQuarantine() {
        return this.trustedAliasGlobalQuarantine.get();
    }
    @Override public String getRegistrationId() { return this.registrationId; }
    @Override public long getBuildTimeMillis() { return this.buildTimeMillis.sum(); }
    @Override public long getBuildTimeSamples() { return this.buildTimeSamples.sum(); }
    @Override public double getAverageBuildTimeMillis() {
        final long samples = this.buildTimeSamples.sum();
        return samples == 0L ? 0D : (double) this.buildTimeMillis.sum() / samples;
    }
    @Override public long getMaxBuildTimeMillis() { return this.maxBuildTimeMillis.get(); }
    @Override public long getExecutorRejections() { return this.executorRejections.sum(); }
    @Override public long getCpuExecutorRejections() { return this.cpuExecutorRejections.sum(); }
    @Override public int getCpuExecutorWorkerCount() { return this.cpuExecutorWorkerCount.get(); }
    @Override public int getCpuExecutorQueueCapacity() { return this.cpuExecutorQueueCapacity.get(); }
    @Override public int getCpuExecutorActiveThreads() { return this.cpuExecutorActiveThreads.get(); }
    @Override public int getCpuExecutorQueueSize() { return this.cpuExecutorQueueSize.get(); }
    @Override public long getIoExecutorRejections() { return this.ioExecutorRejections.sum(); }
    @Override public int getIoExecutorWorkerCount() { return this.ioExecutorWorkerCount.get(); }
    @Override public int getIoExecutorQueueCapacity() { return this.ioExecutorQueueCapacity.get(); }
    @Override public int getIoExecutorActiveThreads() { return this.ioExecutorActiveThreads.get(); }
    @Override public int getIoExecutorQueueSize() { return this.ioExecutorQueueSize.get(); }
    @Override public long getPendingHttpRequests() { return this.pendingHttpRequests.get(); }
    @Override public long getMaxPendingHttpRequestsPerToken() {
        return this.maxPendingHttpRequestsPerToken.get();
    }
    @Override public long getMaxPendingHttpRequestsGlobal() { return this.maxPendingHttpRequestsGlobal.get(); }
    @Override public long getHttpTokenRequestRejections() { return this.httpTokenRequestRejections.sum(); }
    @Override public long getHttpGlobalRequestRejections() { return this.httpGlobalRequestRejections.sum(); }

    private double averageBuildTime(final Tier tier) {
        final Counters counters = this.tier(tier);
        final long samples = counters.buildTimeSamples.sum();
        return samples == 0L ? 0D : (double) counters.buildTimeMillis.sum() / samples;
    }

    public enum Tier {
        ARCHIVE,
        CONTENT,
        MOTION,
        BLOB,
        LAYER,
        RUNTIME,
        ARTIFACT
    }

    private static final class Counters {
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder builds = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder waiters = new LongAdder();
        private final AtomicLong inflight = new AtomicLong();
        private final AtomicLong completedEntries = new AtomicLong();
        private final AtomicLong weightBytes = new AtomicLong();
        private final AtomicLong maxWeightBytes = new AtomicLong();
        private final LongAdder evictions = new LongAdder();
        private final LongAdder buildTimeMillis = new LongAdder();
        private final LongAdder buildTimeSamples = new LongAdder();
        private final AtomicLong maxBuildTimeMillis = new AtomicLong();
    }

}
