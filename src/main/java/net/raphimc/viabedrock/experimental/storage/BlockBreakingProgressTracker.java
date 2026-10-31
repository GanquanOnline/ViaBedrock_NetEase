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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class BlockBreakingProgressTracker extends StoredObject {

    private static final int SYNTHETIC_BREAKER_ID_BASE = 1_000_000_000;
    private static final long STALE_PROGRESS_TIMEOUT_MS = 1_500L;
    private static final long BREAK_ACK_TIMEOUT_MS = 1_000L;
    private static final double BEDROCK_PROGRESS_SCALE = 65_535D;

    private final Map<BlockPosition, BreakProgress> activeProgress = new HashMap<>();
    private final Map<BlockPosition, PendingBreakAck> pendingBreakAcks = new HashMap<>();
    private MiningPhase miningPhase = MiningPhase.IDLE;
    private MiningTarget miningTarget;
    private int suspendedTicks;
    private int postFinishCooldownTicks;
    private int nextSyntheticBreakerId = SYNTHETIC_BREAKER_ID_BASE;

    public BlockBreakingProgressTracker(final UserConnection user) {
        super(user);
    }

    public MiningPhase miningPhase() {
        return this.miningPhase;
    }

    public MiningTarget miningTarget() {
        return this.miningTarget;
    }

    public boolean isMiningTarget(final BlockPosition position) {
        return this.miningTarget != null && this.miningTarget.position.equals(position);
    }

    public boolean shouldSuppressMissedSwing() {
        return this.postFinishCooldownTicks > 0 || this.miningPhase == MiningPhase.ACTIVE || this.miningPhase == MiningPhase.SUSPENDED;
    }

    public void startMining(final BlockPosition position, final Direction direction) {
        this.miningTarget = new MiningTarget(position, direction);
        this.miningPhase = MiningPhase.ACTIVE;
        this.suspendedTicks = 0;
    }

    public void suspendMining(final BlockPosition position) {
        if (!this.isMiningTarget(position)) {
            return;
        }
        this.miningPhase = MiningPhase.SUSPENDED;
        this.suspendedTicks = 0;
    }

    public void resumeMining() {
        if (this.miningPhase == MiningPhase.SUSPENDED) {
            this.miningPhase = MiningPhase.ACTIVE;
            this.suspendedTicks = 0;
        }
    }

    public void finishMining(final BlockPosition position, final int sequence, final int javaBlockStateId) {
        if (this.isMiningTarget(position)) {
            this.miningPhase = MiningPhase.FINISHING;
            this.miningTarget = null;
            this.suspendedTicks = 0;
            this.postFinishCooldownTicks = 5;
            this.expectJavaAckAfterBlockUpdate(position, sequence, javaBlockStateId);
        }
    }

    public MiningTarget abortSuspendedMiningAfterTick(final int suspendedAbortTicks) {
        if (this.miningPhase != MiningPhase.SUSPENDED || this.miningTarget == null) {
            return null;
        }
        if (++this.suspendedTicks <= suspendedAbortTicks) {
            return null;
        }

        final MiningTarget aborted = this.miningTarget;
        this.miningPhase = MiningPhase.IDLE;
        this.miningTarget = null;
        this.suspendedTicks = 0;
        return aborted;
    }

    public void handleStartCracking(final BlockPosition position, final int data, final boolean localTarget) {
        if (localTarget) {
            return;
        }

        final BreakProgress progress = this.activeProgress.compute(position, (key, old) -> {
            final int breakerId = old != null ? old.breakerId : this.breakerIdFor(position);
            return new BreakProgress(breakerId, 0D, -1, System.currentTimeMillis());
        });
        progress.addData(data);
        this.sendStage(position, progress, 0);
    }

    public void handleUpdateCracking(final BlockPosition position, final int data, final boolean localTarget) {
        if (localTarget) {
            return;
        }

        final BreakProgress progress = this.activeProgress.computeIfAbsent(position, key -> new BreakProgress(this.breakerIdFor(position), 0D, -1, System.currentTimeMillis()));
        progress.addData(data);
        final int stage = Math.max(0, Math.min(9, (int) Math.floor(progress.progress * 10D)));
        this.sendStage(position, progress, stage);
    }

    public void handleStopCracking(final BlockPosition position) {
        this.clearProgress(position);
    }

    public void expectJavaAckAfterBlockUpdate(final BlockPosition position, final int sequence, final int javaBlockStateId) {
        if (sequence > 0) {
            final var chunks = this.user().get(net.raphimc.viabedrock.protocol.storage.ChunkTracker.class);
            final var original = chunks == null ? null : chunks.getKnownSharedBlockState(position);
            this.pendingBreakAcks.put(position, new PendingBreakAck(sequence, javaBlockStateId, System.currentTimeMillis(), original));
        }
    }

    public void handleBlockUpdate(final BlockPosition position) {
        // prepend 阶段只清除裂纹，ACK 必须等待权威方块状态发送完成。
        this.clearProgress(position);
    }

    // ACK 与本地视听回显分别消费，避免服务端包到达顺序互相抢占状态。
    public Integer consumeAck(final BlockPosition position) {
        final PendingBreakAck ack = this.pendingBreakAcks.get(position);
        if (ack == null || !ack.ackPending) {
            return null;
        }
        ack.ackPending = false;
        this.removeCompletedAck(position, ack);
        return ack.sequence;
    }

    // Java 客户端已生成本地破坏声音和粒子，仅允许同坐标回显命中一次。
    public boolean consumeLocalBreakEffect(final BlockPosition position) {
        final PendingBreakAck ack = this.pendingBreakAcks.get(position);
        if (ack == null || !ack.effectPending) {
            return false;
        }
        ack.effectPending = false;
        this.removeCompletedAck(position, ack);
        return true;
    }

    public void tick() {
        if (this.postFinishCooldownTicks > 0) {
            this.postFinishCooldownTicks--;
        }

        final long now = System.currentTimeMillis();
        final List<BlockPosition> stalePositions = new ArrayList<>();
        for (Map.Entry<BlockPosition, BreakProgress> entry : this.activeProgress.entrySet()) {
            if (now - entry.getValue().lastUpdateTime > STALE_PROGRESS_TIMEOUT_MS) {
                stalePositions.add(entry.getKey());
            }
        }
        for (BlockPosition position : stalePositions) {
            this.clearProgress(position);
        }

        for (TimedOutBreakAck timedOut : this.collectTimedOutBreakAcks(now)) {
            final var chunks = this.user().get(net.raphimc.viabedrock.protocol.storage.ChunkTracker.class);
            final var current = new net.raphimc.viabedrock.protocol.rewriter.neighbor.TrackerNeighborView(chunks);
            final var view = new net.raphimc.viabedrock.protocol.rewriter.neighbor.BlockNeighborView() {
                @Override public int getJavaBlockState(BlockPosition pos) { return current.getJavaBlockState(pos); }
                @Override public net.raphimc.viabedrock.api.chunk.BedrockBlockEntity getBlockEntity(BlockPosition pos) { return current.getBlockEntity(pos); }
                @Override public net.easecation.blockstates.SharedBlockStates.State getBedrockState(BlockPosition pos) {
                    return pos.equals(timedOut.position()) && timedOut.original() != null ? timedOut.original() : current.getBedrockState(pos);
                }
            };
            // 恢复使用保存的 BE 身份和当前邻居，不反推旧 JE ID；全部状态必须先于 ACK。
            for (var update : BedrockProtocol.MAPPINGS.getNeighborRewriter().resolveUpdate(view, timedOut.position(), timedOut.javaBlockStateId()).entrySet()) {
                PacketFactory.sendJavaBlockUpdate(this.user(), update.getKey(), update.getValue());
                chunks.onJavaBlockChange(update.getKey(), update.getValue());
            }
            PacketFactory.sendJavaBlockChangedAck(this.user(), timedOut.sequence());
        }
    }

    List<TimedOutBreakAck> collectTimedOutBreakAcks(final long now) {
        final List<TimedOutBreakAck> timedOut = new ArrayList<>();
        final Iterator<Map.Entry<BlockPosition, PendingBreakAck>> it = this.pendingBreakAcks.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<BlockPosition, PendingBreakAck> entry = it.next();
            final PendingBreakAck ack = entry.getValue();
            if (now - ack.timestamp > BREAK_ACK_TIMEOUT_MS) {
                if (ack.ackPending) {
                    timedOut.add(new TimedOutBreakAck(entry.getKey(), ack.sequence, ack.javaBlockStateId, ack.original));
                }
                it.remove();
            }
        }
        return timedOut;
    }

    private void removeCompletedAck(final BlockPosition position, final PendingBreakAck ack) {
        if (!ack.ackPending && !ack.effectPending) {
            this.pendingBreakAcks.remove(position, ack);
        }
    }

    private int breakerIdFor(final BlockPosition position) {
        return this.nextSyntheticBreakerId++;
    }

    private void sendStage(final BlockPosition position, final BreakProgress progress, final int stage) {
        progress.lastUpdateTime = System.currentTimeMillis();
        if (progress.lastStage == stage) {
            return;
        }

        progress.lastStage = stage;
        this.sendJavaBlockDestruction(progress.breakerId, position, stage);
    }

    private void clearProgress(final BlockPosition position) {
        final BreakProgress progress = this.activeProgress.remove(position);
        if (progress != null) {
            this.sendJavaBlockDestruction(progress.breakerId, position, -1);
        }
    }

    private void sendJavaBlockDestruction(final int breakerId, final BlockPosition position, final int progress) {
        final PacketWrapper blockDestruction = PacketWrapper.create(ClientboundPackets26_1.BLOCK_DESTRUCTION, this.user());
        blockDestruction.write(Types.VAR_INT, breakerId);
        blockDestruction.write(Types.BLOCK_POSITION1_14, position);
        blockDestruction.write(Types.UNSIGNED_BYTE, (short) (progress & 0xFF));
        blockDestruction.send(BedrockProtocol.class);
    }

    private static final class BreakProgress {
        private final int breakerId;
        private double progress;
        private int lastStage;
        private long lastUpdateTime;

        private BreakProgress(final int breakerId, final double progress, final int lastStage, final long lastUpdateTime) {
            this.breakerId = breakerId;
            this.progress = progress;
            this.lastStage = lastStage;
            this.lastUpdateTime = lastUpdateTime;
        }

        private void addData(final int data) {
            if (data > 0) {
                this.progress = Math.min(1D, this.progress + data / BEDROCK_PROGRESS_SCALE);
            }
        }
    }

    private static final class PendingBreakAck {
        private final int sequence;
        private final int javaBlockStateId;
        private final long timestamp;
        private final net.easecation.blockstates.SharedBlockStates.State original;
        private boolean ackPending = true;
        private boolean effectPending = true;

        private PendingBreakAck(final int sequence, final int javaBlockStateId, final long timestamp,
                                final net.easecation.blockstates.SharedBlockStates.State original) {
            this.sequence = sequence;
            this.javaBlockStateId = javaBlockStateId;
            this.timestamp = timestamp;
            this.original = original;
        }
    }

    record TimedOutBreakAck(BlockPosition position, int sequence, int javaBlockStateId,
                           net.easecation.blockstates.SharedBlockStates.State original) {
    }

    public enum MiningPhase {
        IDLE,
        ACTIVE,
        SUSPENDED,
        FINISHING
    }

    public record MiningTarget(BlockPosition position, Direction direction) {
    }

}
