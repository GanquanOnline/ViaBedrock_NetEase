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
package net.raphimc.viabedrock.experimental.blockbreak;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.util.InstantBreakBlocks;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.storage.BlockBreakingProgressTracker;
import net.raphimc.viabedrock.experimental.storage.BlockBreakingProgressTracker.MiningPhase;
import net.raphimc.viabedrock.experimental.storage.BlockBreakingProgressTracker.MiningTarget;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AbilitiesIndex;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorSwingSource;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.LevelEvent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerActionType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerActionAction;
import net.raphimc.viabedrock.protocol.model.BlockChangeEntry;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.BlockNeighborView;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.TrackerNeighborView;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.List;

public final class BlockBreakingProgressModule implements FeatureModule {

    private static final int SUSPENDED_ABORT_TICKS = 3;

    @Override
    public void onStorageRegistration(final UserConnection user) {
        user.put(new BlockBreakingProgressTracker(user));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        ProtocolUtil.prependClientbound(protocol, ClientboundBedrockPackets.LEVEL_EVENT, this::handleLevelEvent);
        ProtocolUtil.prependClientbound(protocol, ClientboundBedrockPackets.UPDATE_BLOCK, this::handleBlockUpdate);
        ProtocolUtil.prependClientbound(protocol, ClientboundBedrockPackets.UPDATE_BLOCK_SYNCED, this::handleBlockUpdate);
        ProtocolUtil.prependClientbound(protocol, ClientboundBedrockPackets.UPDATE_SUB_CHUNK_BLOCKS, this::handleSubChunkBlockUpdates);
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.PLAYER_ACTION, this::handlePlayerAction);
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.SWING, this::handleSwing);
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.CLIENT_TICK_END, this::handleClientTickEnd);
    }

    private void handleLevelEvent(final PacketWrapper wrapper) {
        final int rawLevelEvent = wrapper.passthrough(BedrockTypes.VAR_INT);
        final Position3f position = wrapper.passthrough(BedrockTypes.POSITION_3F);
        final int data = wrapper.passthrough(BedrockTypes.VAR_INT);
        final LevelEvent levelEvent = LevelEvent.getByValue(rawLevelEvent);
        if (levelEvent == null) {
            return;
        }

        final BlockPosition blockPosition = new BlockPosition((int) position.x(), (int) position.y(), (int) position.z());
        final BlockBreakingProgressTracker tracker = wrapper.user().get(BlockBreakingProgressTracker.class);
        final boolean localTarget = tracker.isMiningTarget(blockPosition);
        switch (levelEvent) {
            case StartBlockCracking -> {
                tracker.handleStartCracking(blockPosition, data, localTarget);
                wrapper.cancel();
            }
            case UpdateBlockCracking -> {
                tracker.handleUpdateCracking(blockPosition, data, localTarget);
                wrapper.cancel();
            }
            case StopBlockCracking -> {
                tracker.handleStopCracking(blockPosition);
                wrapper.cancel();
            }
        }
    }

    private void handleBlockUpdate(final PacketWrapper wrapper) {
        final BlockPosition position = wrapper.passthrough(BedrockTypes.BLOCK_POSITION);
        wrapper.user().get(BlockBreakingProgressTracker.class).handleBlockUpdate(position);
    }

    private void handleSubChunkBlockUpdates(final PacketWrapper wrapper) {
        wrapper.passthrough(BedrockTypes.BLOCK_POSITION);
        final List<BlockPosition> positions = new ArrayList<>();
        for (BlockChangeEntry entry : wrapper.passthrough(BedrockTypes.BLOCK_CHANGE_ENTRY_ARRAY)) {
            positions.add(entry.position());
        }
        for (BlockChangeEntry entry : wrapper.passthrough(BedrockTypes.BLOCK_CHANGE_ENTRY_ARRAY)) {
            positions.add(entry.position());
        }

        final BlockBreakingProgressTracker tracker = wrapper.user().get(BlockBreakingProgressTracker.class);
        for (BlockPosition position : positions) {
            tracker.handleBlockUpdate(position);
        }
    }

    private void handlePlayerAction(final PacketWrapper wrapper) {
        final PlayerActionAction action = PlayerActionAction.values()[wrapper.passthrough(Types.VAR_INT)];
        final BlockPosition position = wrapper.passthrough(Types.BLOCK_POSITION1_14);
        final Direction direction = Direction.values()[wrapper.passthrough(Types.UNSIGNED_BYTE)];
        final int sequence = wrapper.passthrough(Types.VAR_INT);
        final boolean miningAction = action == PlayerActionAction.START_DESTROY_BLOCK
                || action == PlayerActionAction.ABORT_DESTROY_BLOCK
                || action == PlayerActionAction.STOP_DESTROY_BLOCK;
        if (!miningAction) {
            return;
        }

        wrapper.cancel();
        final UserConnection user = wrapper.user();
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final BlockBreakingProgressTracker tracker = user.get(BlockBreakingProgressTracker.class);

        if (gameSession.isImmutableWorld() || !clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Mine)) {
            final int fixedBlockState = chunkTracker.getResolvedJavaBlockState(position);
            PacketFactory.sendJavaBlockUpdate(user, position, fixedBlockState);
            PacketFactory.sendJavaBlockChangedAck(user, sequence);
            return;
        }

        switch (action) {
            case START_DESTROY_BLOCK -> this.startMining(user, clientPlayer, tracker, chunkTracker, position, direction, sequence);
            case ABORT_DESTROY_BLOCK -> this.suspendMining(clientPlayer, tracker, position);
            case STOP_DESTROY_BLOCK -> this.finishMining(user, gameSession, clientPlayer, tracker, chunkTracker, position, direction, sequence);
            default -> throw new IllegalStateException("Unhandled mining action: " + action);
        }
    }

    private void startMining(final UserConnection user, final ClientPlayerEntity clientPlayer, final BlockBreakingProgressTracker tracker, final ChunkTracker chunkTracker, final BlockPosition position, final Direction direction, final int sequence) {
        final MiningTarget oldTarget = tracker.miningTarget();
        if (oldTarget != null && !oldTarget.position().equals(position)) {
            this.abortBedrockMining(user, clientPlayer, oldTarget);
        }

        tracker.startMining(position, direction);
        clientPlayer.sendSwingPacketToServer();
        clientPlayer.cancelNextSwingPacket();
        clientPlayer.setBlockBreakingInfo(new ClientPlayerEntity.BlockBreakingInfo(position, direction));
        clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StartDestroyBlock, position, direction.ordinal()));

        // Java 创造破坏与瞬间破坏一样只发送 START，不会再发送 STOP，因此本帧就要完成破坏流程。
        // 同时登记 sequence，让服务端权威方块更新或已有超时恢复发送状态后，再通过 ACK 结束客户端预测。
        if (clientPlayer.javaGameMode() == GameMode.CREATIVE || this.isInstantBreak(user, chunkTracker, position)) {
            this.finishMining(user, user.get(GameSessionStorage.class), clientPlayer, tracker, chunkTracker, position, direction, sequence);
        }
    }

    private void suspendMining(final ClientPlayerEntity clientPlayer, final BlockBreakingProgressTracker tracker, final BlockPosition position) {
        clientPlayer.setBlockBreakingInfo(null);
        tracker.suspendMining(position);
    }

    private void finishMining(final UserConnection user, final GameSessionStorage gameSession, final ClientPlayerEntity clientPlayer, final BlockBreakingProgressTracker tracker, final ChunkTracker chunkTracker, final BlockPosition position, final Direction direction, final int sequence) {
        if (!tracker.isMiningTarget(position)) {
            PacketFactory.sendJavaBlockChangedAck(user, sequence);
            return;
        }

        clientPlayer.cancelNextSwingPacket();
        clientPlayer.setBlockBreakingInfo(null);
        if (!gameSession.isBlockBreakingServerAuthoritative()) {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StopDestroyBlock));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, position, 0));
        } else {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, position, 0));
        }
        tracker.finishMining(position, sequence, chunkTracker.getResolvedJavaBlockState(position));
    }

    private void handleSwing(final PacketWrapper wrapper) {
        final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)];
        wrapper.cancel();

        final UserConnection user = wrapper.user();
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        final ActorSwingSource swingSource = clientPlayer.swingSourceTracker().consume(hand);
        if (hand != InteractionHand.MAIN_HAND || clientPlayer.checkCancelSwingPacket()) {
            return;
        }

        clientPlayer.sendSwingPacketToServer(swingSource);
        if (swingSource != ActorSwingSource.Attack) {
            return;
        }
        final BlockBreakingProgressTracker tracker = user.get(BlockBreakingProgressTracker.class);
        final MiningTarget target = tracker.miningTarget();
        if (target != null && tracker.miningPhase() == MiningPhase.SUSPENDED) {
            tracker.resumeMining();
            clientPlayer.setBlockBreakingInfo(new ClientPlayerEntity.BlockBreakingInfo(target.position(), target.direction()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, target.position(), target.direction().ordinal()));
            return;
        }
        if (target != null && tracker.miningPhase() == MiningPhase.ACTIVE) {
            if (!gameSession.isBlockBreakingServerAuthoritative()) {
                clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, target.position(), target.direction().ordinal()));
            }
            return;
        }
        if (!tracker.shouldSuppressMissedSwing()) {
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.MissedSwing);
        }
    }

    private void handleClientTickEnd(final PacketWrapper wrapper) {
        final UserConnection user = wrapper.user();
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        final BlockBreakingProgressTracker tracker = user.get(BlockBreakingProgressTracker.class);
        final MiningTarget aborted = tracker.abortSuspendedMiningAfterTick(SUSPENDED_ABORT_TICKS);
        if (aborted != null) {
            this.abortBedrockMining(user, clientPlayer, aborted);
        }
    }

    private void abortBedrockMining(final UserConnection user, final ClientPlayerEntity clientPlayer, final MiningTarget target) {
        clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, target.position(), 0));
    }

    private boolean isInstantBreak(final UserConnection user, final ChunkTracker chunkTracker, final BlockPosition position) {
        final int javaBlockStateId = chunkTracker.getJavaBlockState(position);
        final BlockState javaBlockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        if (javaBlockState != null && InstantBreakBlocks.isVanillaInstantBreak(javaBlockState.identifier())) {
            return true;
        }
        final CustomMappingSyncStorage customMappingSync = user.get(CustomMappingSyncStorage.class);
        if (customMappingSync != null && customMappingSync.access().secondsToDestroy(javaBlockStateId) == 0.0F) {
            return true;
        }

        final String heldIdentifier = user.get(ItemRewriter.class).bedrockIdentifier(
                user.get(InventoryTracker.class).getInventoryContainer().getSelectedHotbarItem());
        final String customIdentifier = customMappingSync != null
                ? customMappingSync.access().identifierByJavaBlockStateId(javaBlockStateId) : null;
        return "minecraft:shears".equals(heldIdentifier)
                && InstantBreakBlocks.isShearsInstantBreak(javaBlockState != null ? javaBlockState.identifier() : null, customIdentifier);
    }

}
