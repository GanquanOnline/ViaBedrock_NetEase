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
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.Vector3d;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandlers;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import com.viaversion.viaversion.util.Pair;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.chunk.BedrockChunk;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.model.BlockState;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.BitSets;
import net.raphimc.viabedrock.api.util.EnumUtil;
import net.raphimc.viabedrock.api.util.InstantBreakBlocks;
import net.raphimc.viabedrock.api.util.MathUtil;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.TextUtil;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.ItemUseSemantics;
import net.raphimc.viabedrock.experimental.custommapping.CustomMappingSyncStorage;
import net.raphimc.viabedrock.experimental.inventory.ItemUseHandContext;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.experimental.storage.BlockBreakingProgressTracker;
import net.raphimc.viabedrock.experimental.storage.RidingTracker;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.generated.bedrock.CustomBlockTags;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.*;
import net.raphimc.viabedrock.protocol.model.Position2f;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.BlockStateRewriter;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.BlockNeighborView;
import net.raphimc.viabedrock.protocol.rewriter.neighbor.TrackerNeighborView;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public class ClientPlayerPackets {

    private static final Set<PlayerAuthInputPacket_InputData> IMMOBILE_MOVEMENT_INPUTS = EnumSet.of(
            PlayerAuthInputPacket_InputData.Ascend,
            PlayerAuthInputPacket_InputData.Descend,
            PlayerAuthInputPacket_InputData.JumpDown,
            PlayerAuthInputPacket_InputData.SprintDown,
            PlayerAuthInputPacket_InputData.ChangeHeight,
            PlayerAuthInputPacket_InputData.Jumping,
            PlayerAuthInputPacket_InputData.AutoJumpingInWater,
            PlayerAuthInputPacket_InputData.SneakDown,
            PlayerAuthInputPacket_InputData.Up,
            PlayerAuthInputPacket_InputData.Down,
            PlayerAuthInputPacket_InputData.Left,
            PlayerAuthInputPacket_InputData.Right,
            PlayerAuthInputPacket_InputData.UpLeft,
            PlayerAuthInputPacket_InputData.UpRight,
            PlayerAuthInputPacket_InputData.WantUp,
            PlayerAuthInputPacket_InputData.WantDown,
            PlayerAuthInputPacket_InputData.WantDownSlow,
            PlayerAuthInputPacket_InputData.WantUpSlow,
            PlayerAuthInputPacket_InputData.Sprinting,
            PlayerAuthInputPacket_InputData.AscendBlock,
            PlayerAuthInputPacket_InputData.DescendBlock,
            PlayerAuthInputPacket_InputData.DownLeft,
            PlayerAuthInputPacket_InputData.DownRight,
            PlayerAuthInputPacket_InputData.StartJumping,
            PlayerAuthInputPacket_InputData.JumpPressedRaw,
            PlayerAuthInputPacket_InputData.JumpCurrentRaw,
            PlayerAuthInputPacket_InputData.SneakPressedRaw,
            PlayerAuthInputPacket_InputData.SneakCurrentRaw
    );

    private static final PacketHandler CLIENT_PLAYER_GAME_MODE_INFO_UPDATE = wrapper -> {
        final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
        final GameMode gameMode = wrapper.user().get(SpectatorCameraTracker.class).projectJavaGameMode(clientPlayer.javaGameMode());

        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, wrapper.user());
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // 动作
        playerInfoUpdate.write(Types.VAR_INT, 1); // 长度
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // uuid
        playerInfoUpdate.write(Types.VAR_INT, gameMode.ordinal()); // 游戏模式
        playerInfoUpdate.send(BedrockProtocol.class);
    };

    private static final PacketHandler CLIENT_PLAYER_GAME_MODE_UPDATE = wrapper -> {
        final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
        final GameMode gameMode = wrapper.user().get(SpectatorCameraTracker.class).projectJavaGameMode(clientPlayer.javaGameMode());
        PacketFactory.sendJavaGameEvent(wrapper.user(), GameEventType.CHANGE_GAME_MODE, gameMode.ordinal());
    };

    public static void sendJavaGameMode(final UserConnection user, final GameMode gameMode) {
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        final PacketWrapper playerInfoUpdate = PacketWrapper.create(ClientboundPackets26_1.PLAYER_INFO_UPDATE, user);
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // 动作
        playerInfoUpdate.write(Types.VAR_INT, 1); // 长度
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // uuid
        playerInfoUpdate.write(Types.VAR_INT, gameMode.ordinal()); // 游戏模式
        playerInfoUpdate.send(BedrockProtocol.class);

        PacketFactory.sendJavaGameEvent(user, GameEventType.CHANGE_GAME_MODE, gameMode.ordinal());
    }

    private static boolean isInstantBreak(final UserConnection user, final ChunkTracker chunkTracker, final BlockPosition position) {
        final ClientPlayerEntity clientPlayer = user.get(EntityTracker.class).getClientPlayer();
        final int javaBlockStateId = chunkTracker.getJavaBlockState(position);
        final BlockState javaBlockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        final CustomMappingSyncStorage customMappingSync = user.get(CustomMappingSyncStorage.class);
        final String heldIdentifier = user.get(ItemRewriter.class).bedrockIdentifier(
                user.get(InventoryTracker.class).getInventoryContainer().getSelectedHotbarItem());
        final String customIdentifier = customMappingSync != null
                ? customMappingSync.access().identifierByJavaBlockStateId(javaBlockStateId) : null;
        final Float customSeconds = customMappingSync != null
                ? customMappingSync.access().secondsToDestroy(javaBlockStateId) : null;
        return InstantBreakBlocks.shouldCompleteOnJavaStart(
                clientPlayer != null && clientPlayer.javaGameMode() == GameMode.CREATIVE,
                javaBlockState != null ? javaBlockState.identifier() : null,
                customSeconds,
                heldIdentifier,
                customIdentifier
        );
    }

    static boolean isSelectedHandPlayerAction(final PlayerActionAction action) {
        return action == PlayerActionAction.DROP_ITEM
                || action == PlayerActionAction.DROP_ALL_ITEMS
                || action == PlayerActionAction.SWAP_ITEM_WITH_OFFHAND
                || action == PlayerActionAction.STAB;
    }

    public static boolean scheduleDelayedMotBreak(final ClientPlayerEntity clientPlayer, final ChunkTracker chunkTracker, final BlockPosition position, final Direction direction) {
        if (clientPlayer == null) {
            return false;
        }
        final String javaIdentifier = javaBlockIdentifier(chunkTracker, position);
        final int delayTicks = InstantBreakBlocks.delayedMotBreakTicks(javaIdentifier);
        if (delayTicks <= 0) {
            clientPlayer.clearDelayedMotBreak();
            return false;
        }
        clientPlayer.scheduleDelayedMotBreak(position, direction, clientPlayer.age() + delayTicks);
        return true;
    }

    static void completeDueDelayedMotBreak(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (user == null || clientPlayer == null) {
            return;
        }
        final ClientPlayerEntity.DelayedMotBreak due = clientPlayer.pollDueDelayedMotBreak();
        if (due == null) {
            return;
        }
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        if (gameSession == null || chunkTracker == null) {
            return;
        }
        final int javaBlockStateId = chunkTracker.getJavaBlockState(due.position());
        // 延迟的仅 START 方块不能与 StartDestroy 共用同一刻。MOT 860 的
        // EnumMap 先迭代 Predict 再迭代 Continue，因此 Predict 之后的 Continue 会重新开始挖掘。
        if (!gameSession.isBlockBreakingServerAuthoritative()) {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StopDestroyBlock));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, due.position(), due.direction().ordinal()));
        } else if (ViaBedrock.getConfig() != null && ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, due.position(), due.direction().ordinal()));
        } else {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, due.position(), due.direction().ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, due.position(), due.direction().ordinal()));
        }
        chunkTracker.handleBlockChange(due.position(), 0, chunkTracker.bedrockAirId());
        PacketFactory.sendJavaBlockUpdate(user, due.position(), ProtocolConstants.JAVA_AIR_ID);
        final BlockBreakingProgressTracker tracker = user.get(BlockBreakingProgressTracker.class);
        if (tracker != null) {
            tracker.finishMining(due.position(), 0, javaBlockStateId);
        }
    }

    static String javaBlockIdentifier(final ChunkTracker chunkTracker, final BlockPosition position) {
        if (chunkTracker == null || position == null) {
            return null;
        }
        final int javaBlockStateId = chunkTracker.getJavaBlockState(position);
        final BlockState javaBlockState = BedrockProtocol.MAPPINGS.getJavaBlockStates().inverse().get(javaBlockStateId);
        return javaBlockState != null ? javaBlockState.identifier() : null;
    }

    private static void finishBlockBreak(final UserConnection user, final GameSessionStorage gameSession, final ClientPlayerEntity clientPlayer, final ChunkTracker chunkTracker, final BlockPosition position, final Direction direction) {
        // MOT 的 PredictDestroy 已经先中止再完成；StopDestroy 也会中止。
        // 同一刻的 Abort(face=0) 是多余的，并且可能让 lastBlockAction=Abort。
        // 参考：MOT Player.java PREDICT_DESTROY_BLOCK / STOP_DESTROY_BLOCK。
        if (!gameSession.isBlockBreakingServerAuthoritative()) {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StopDestroyBlock));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, position, direction.ordinal()));
        } else {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, position, direction.ordinal()));
        }

        chunkTracker.handleBlockChange(position, 0, chunkTracker.bedrockAirId());
        PacketFactory.sendJavaBlockUpdate(user, position, ProtocolConstants.JAVA_AIR_ID);
        // 本地预测清空缓存后，邻居同样需要更新参数，不能等服务端恰好再发一次。
        final BlockNeighborView view = new TrackerNeighborView(chunkTracker);
        for (var entry : BedrockProtocol.MAPPINGS.getNeighborRewriter().resolveUpdate(view, position, ProtocolConstants.JAVA_AIR_ID).entrySet()) {
            chunkTracker.onJavaBlockChange(entry.getKey(), entry.getValue());
            if (!entry.getKey().equals(position)) PacketFactory.sendJavaBlockUpdate(user, entry.getKey(), entry.getValue());
        }
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS, null, wrapper -> {
            wrapper.cancel();
            final UpdateClientInputLocksLayout.DecodedLocks locks = UpdateClientInputLocksLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            if (clientPlayer == null) {
                return;
            }
            final boolean wasLocked = clientPlayer.isInputMovementLocked();
            // 先落移动锁再落位集：synchronizeInputLocks 读 NOAI，顺序反了 v1 会把旧锁位写进载荷。
            clientPlayer.setInputMovementLocked(locks.movementLocked());
            clientPlayer.setInputLocks(locks.lockComponentData());
            if (locks.movementLocked() && locks.serverPosition() != null) {
                clientPlayer.setPosition(locks.serverPosition());
                if (!wasLocked) {
                    clientPlayer.beginPositionSync(Relative.ROTATION);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.RESPAWN, ClientboundPackets26_1.RESPAWN, wrapper -> {
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            final byte rawState = wrapper.read(Types.BYTE); // 状态
            final PlayerRespawnState state = PlayerRespawnState.getByValue(rawState);
            if (state == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayerRespawnState: " + rawState);
                wrapper.cancel();
                return;
            }
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final ClientPlayerEntity respawningPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            if (respawningPlayer != null) {
                respawningPlayer.deferredEntityActions().clear();
                respawningPlayer.setUsingItem(false);
                ExperimentalFeatures.restorePromotedOffhand(wrapper.user(), respawningPlayer);
            }

            switch (state) {
                case ReadyToSpawn -> {
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    clientPlayer.resetJavaMovementPosition();
                    clientPlayer.setPosition(position);

                    if (clientPlayer.isInitiallySpawned()) {
                        final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                        final GameRulesStorage gameRulesStorage = wrapper.user().get(GameRulesStorage.class);
                        final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
                        final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                        wrapper.user().get(JavaPlayerStateStorage.class).reset();

                        if (clientPlayer.isDead() && !gameRulesStorage.<Boolean>getGameRule("keepInventory")) {
                            inventoryTracker.getInventoryContainer().clearItems();
                            inventoryTracker.getOffhandContainer().clearItems();
                            inventoryTracker.getArmorContainer().clearItems();
                            inventoryTracker.getHudContainer().clearItems();
                            // TODO: InventoryTransactionPacket(legacyRequestId=0, legacySlots=[], actions=[], transactionType=INVENTORY_MISMATCH, actionType=0, entityRuntimeId=0, blockPosition=null, blockFace=0, hotbarSlot=0, itemInHand=null, playerPosition=null, clickPosition=null, headPosition=null, usingNetIds=false, blockDefinition=null)
                        }
                        clientPlayer.clearEffects();

                        clientPlayer.setHealth(clientPlayer.attributes().get("minecraft:health").maxValue());
                        clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.Respawn, -1);
                        wrapper.write(Types.VAR_INT, chunkTracker.getDimension().ordinal()); // 维度 ID
                        wrapper.write(Types.STRING, chunkTracker.getDimensionKey()); // 维度名称
                        wrapper.write(Types.LONG, 0L); // 哈希种子
                        final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
                        wrapper.write(Types.BYTE, (byte) spectatorCamera.projectJavaGameMode(clientPlayer.javaGameMode()).ordinal()); // 游戏模式
                        wrapper.write(Types.BYTE, (byte) -1); // 上一游戏模式
                        wrapper.write(Types.BOOLEAN, false); // 是否为调试世界
                        wrapper.write(Types.BOOLEAN, gameSession.isFlatGenerator()); // 是否为超平坦
                        wrapper.write(Types.OPTIONAL_GLOBAL_POSITION, null); // 上次死亡位置
                        wrapper.write(Types.VAR_INT, 0); // 传送门冷却
                        wrapper.write(Types.VAR_INT, 64); // 海平面
                        wrapper.write(Types.BYTE, (byte) (RespawnKeepFlag.ATTRIBUTE_MODIFIERS.getBit() | RespawnKeepFlag.ENTITY_DATA.getBit())); // 保留数据掩码
                        PacketLeftoverLayout.discardUnreadInput(wrapper);
                        wrapper.send(BedrockProtocol.class);
                        clientPlayer.sendAttribute("minecraft:health"); // 确保生命值已同步
                        wrapper.user().get(PlayerArmorHudTracker.class).forceSync();
                        chunkTracker.resetJavaChunkLoading();
                        clientPlayer.setDimensionChangeInfo(null);
                        PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer()); // Java 客户端重生时总会重置物品栏。重新发送
                        inventoryTracker.getInventoryContainer().sendSelectedHotbarSlotToClient(); // Java 客户端重生时总会重置选中的快捷栏槽位。重新发送
                        spectatorCamera.restorePresentationAfterClientReset();
                    }
                    wrapper.cancel();

                    clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
                }
                case SearchingForSpawn -> {
                    wrapper.cancel();
                    PacketLeftoverLayout.discardUnreadInput(wrapper);
                    // MOT 的 Player.kill() 总会发送 SEARCHING。与
                    // DeathInfo 组合即为死亡信号；关闭 SHOW_DEATH_MESSAGES 时会
                    // 跳过 DeathInfo，因此这里仍须把 Java 玩家标记为
                    // 死亡。紧接着的立即重生会覆盖 SET_HEALTH。
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    if (clientPlayer == null || !clientPlayer.isInitiallySpawned() || clientPlayer.isDead()) {
                        return;
                    }
                    clientPlayer.setHealth(0F);
                    clientPlayer.sendAttribute("minecraft:health");
                    final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
                    if (gameSession.getDeathMessage() != null) {
                        final PacketWrapper playerCombatKill = PacketWrapper.create(ClientboundPackets26_1.PLAYER_COMBAT_KILL, wrapper.user());
                        playerCombatKill.write(Types.VAR_INT, clientPlayer.javaId());
                        playerCombatKill.write(Types.TAG, TextUtil.textComponentToNbt(gameSession.getDeathMessage()));
                        playerCombatKill.send(BedrockProtocol.class);
                    }
                }
                case ClientReadyToSpawn -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled PlayerRespawnState: " + state);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ACTION, null, wrapper -> {
            wrapper.cancel();
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 实体运行时 ID
            final int rawAction = wrapper.read(BedrockTypes.VAR_INT); // 动作
            final PlayerActionType action = PlayerActionType.getByValue(rawAction);
            if (action == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayerActionType: " + rawAction);
                return;
            }
            wrapper.read(BedrockTypes.BLOCK_POSITION); // 方块位置
            wrapper.read(BedrockTypes.BLOCK_POSITION); // 结果位置
            wrapper.read(BedrockTypes.VAR_INT); // 面

            if (action == PlayerActionType.ChangeDimensionAck) {
                final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                // Waterdog 会在 CHANGE_DIMENSION 之后注入一个由服务端发出的
                // DIMENSION_CHANGE_SUCCESS。即使 WorldPackets 已清除
                // dimensionChangeInfo，仍要回显该 ACK，好让 Java 同维度的 PHASE_2 仍能完成。
                clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.ChangeDimensionAck);
                if (clientPlayer.dimensionChangeInfo() != null) {
                    PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, clientPlayer.dimensionChangeInfo().loadingScreenId());
                    clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
                    clientPlayer.setDimensionChangeInfo(null);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CORRECT_PLAYER_MOVE_PREDICTION, ClientboundPackets26_1.PLAYER_POSITION, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final byte rawRewindType = wrapper.read(Types.BYTE); // 回退类型
            final RewindType rewindType = RewindType.getByValue(rawRewindType);
            if (rewindType == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown RewindType: " + rawRewindType);
                return;
            }
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // 位置
            wrapper.read(BedrockTypes.POSITION_3F); // 位置增量
            wrapper.read(BedrockTypes.POSITION_2F); // 载具旋转
            if (wrapper.read(Types.BOOLEAN)) {
                wrapper.read(BedrockTypes.FLOAT_LE); // 载具角速度
            }
            switch (rewindType) {
                case Player -> {
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    final boolean onGround = wrapper.read(Types.BOOLEAN); // 是否在地面
                    final long tick = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 刻
                    if (tick > clientPlayer.age() || tick < clientPlayer.age() - gameSession.getMovementRewindHistorySize()) {
                        wrapper.cancel();
                        return;
                    }

                    clientPlayer.setPositionFromServer(position);
                    clientPlayer.setOnGround(onGround);
                    clientPlayer.writePlayerPositionPacketToClient(wrapper, Relative.union(Relative.ROTATION, Relative.VELOCITY), true);
                }
                case Vehicle -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled RewindType: " + rewindType);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_PLAYER_GAME_TYPE, null, new PacketHandlers() {
            @Override
            protected void register() {
                handler(wrapper -> {
                    wrapper.cancel();
                    wrapper.user().get(EntityTracker.class).getClientPlayer().setGameType(GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT))); // 游戏类型
                });
                handler(CLIENT_PLAYER_GAME_MODE_INFO_UPDATE);
                handler(CLIENT_PLAYER_GAME_MODE_UPDATE);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SET_DEFAULT_GAME_TYPE, null, new PacketHandlers() {
            @Override
            protected void register() {
                handler(wrapper -> {
                    wrapper.cancel();
                    wrapper.user().get(GameSessionStorage.class).setLevelGameType(GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT))); // 游戏类型
                    wrapper.user().get(EntityTracker.class).getClientPlayer().updateJavaGameMode();
                });
                handler(CLIENT_PLAYER_GAME_MODE_INFO_UPDATE);
                handler(CLIENT_PLAYER_GAME_MODE_UPDATE);
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_PLAYER_GAME_TYPE, ClientboundPackets26_1.PLAYER_INFO_UPDATE, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final PlayerListStorage playerList = wrapper.user().get(PlayerListStorage.class);

            final GameType gameType = GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT)); // 游戏类型
            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // 实体唯一 ID
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // 刻

            final Pair<UUID, String> playerListEntry = playerList.getPlayer(entityUniqueId);
            if (playerListEntry == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // 动作
            wrapper.write(Types.VAR_INT, 1); // 长度
            wrapper.write(Types.UUID, playerListEntry.key()); // uuid
            final boolean clientPlayerUpdate = playerListEntry.key().equals(clientPlayer.javaUuid());
            GameMode javaGameMode = GameTypeRewriter.getEffectiveGameMode(
                    gameType,
                    gameSession.getLevelGameType(),
                    clientPlayerUpdate && ViaBedrockUtilityInterface.hasSpectatorNoclip(wrapper.user())
            );
            playerList.updateJavaGameMode(playerListEntry.key(), javaGameMode);
            if (clientPlayerUpdate) {
                clientPlayer.setGameType(gameType);
                javaGameMode = wrapper.user().get(SpectatorCameraTracker.class).projectJavaGameMode(clientPlayer.javaGameMode());
                CLIENT_PLAYER_GAME_MODE_UPDATE.handle(wrapper);
            }
            final SpectatorMenuProjection spectatorMenu = wrapper.user().get(SpectatorMenuProjection.class);
            if (spectatorMenu.isActive()) {
                wrapper.cancel();
                spectatorMenu.refreshProfile(playerListEntry.key());
                return;
            }
            wrapper.write(Types.VAR_INT, javaGameMode.ordinal()); // 游戏模式
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_ADVENTURE_SETTINGS, null, wrapper -> {
            wrapper.cancel();
            wrapper.read(Types.BOOLEAN); // 玩家不伤害生物
            wrapper.read(Types.BOOLEAN); // 生物不伤害玩家
            wrapper.user().get(GameSessionStorage.class).setImmutableWorld(wrapper.read(Types.BOOLEAN)); // 世界不可变
            wrapper.read(Types.BOOLEAN); // 显示名称标签
            wrapper.read(Types.BOOLEAN); // 自动跳跃
        });
        protocol.registerClientbound(ClientboundBedrockPackets.OPEN_SIGN, ClientboundPackets26_1.OPEN_SIGN_EDITOR, wrapper -> {
            // MOT 的 OpenSignPacket.encode() 是 getBlockVector3() + boolean。映射后的
            // Java OPEN_SIGN_EDITOR 没有尾部字段；残留的基岩字节会让 1.21.11 踢出玩家。
            wrapper.write(Types.BLOCK_POSITION1_14, wrapper.read(BedrockTypes.BLOCK_POSITION)); // 位置
            wrapper.write(Types.BOOLEAN, wrapper.read(Types.BOOLEAN)); // 正面
            PacketLeftoverLayout.discardUnreadInput(wrapper);
        });

        protocol.registerServerbound(ServerboundPackets26_1.CLIENT_COMMAND, ServerboundBedrockPackets.RESPAWN, wrapper -> {
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final ClientCommandAction action = ClientCommandAction.values()[wrapper.read(Types.VAR_INT)]; // 动作

            switch (action) {
                case PERFORM_RESPAWN -> {
                    wrapper.write(BedrockTypes.POSITION_3F, Position3f.ZERO); // 位置
                    wrapper.write(Types.BYTE, (byte) PlayerRespawnState.ClientReadyToSpawn.getValue()); // 状态
                    wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // 实体运行时 ID
                }
                case REQUEST_STATS, REQUEST_GAMERULE_VALUES -> wrapper.cancel();
                default -> throw new IllegalStateException("Unhandled ClientCommandAction: " + action);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.TELEPORT_TO_ENTITY, null, wrapper -> {
            wrapper.cancel();
            final UUID targetId = wrapper.read(Types.UUID);
            wrapper.user().get(SpectatorCameraTracker.class).requestTarget(targetId);
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_COMMAND, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            wrapper.read(Types.VAR_INT); // 实体 ID
            final PlayerCommandAction action = PlayerCommandAction.values()[wrapper.read(Types.VAR_INT)]; // 动作
            final int data = wrapper.read(Types.VAR_INT); // 数据
            if (action == PlayerCommandAction.STOP_SLEEPING) {
                // Java 的离床是 PLAYER_COMMAND。MOT 860 的 stopSleep() 是 PlayerAction 6。
                // 参考：MOT Player.java case 6 / PlayerActionPacket.ACTION_STOP_SLEEPING。
                clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.StopSleeping);
                return;
            }
            final PlayerAuthInputPacket_InputData inputData = playerCommandInputData(action);
            if (inputData == null) {
                return;
            }

            if (action == PlayerCommandAction.START_SPRINTING) {
                // Nukkit 的 START_SPRINTING 会调用 setUsingItem(false)。避免进食被
                // 在使用动画开始之后才发起的 Java 疾跑取消。
                if (ItemUseSemantics.suppressStartSprintingWhileUsingItem(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), clientPlayer.isUsingItem())) {
                    return;
                }
                clientPlayer.setSprinting(true);
            } else if (action == PlayerCommandAction.STOP_SPRINTING) {
                clientPlayer.setSprinting(false);
            } else if (action == PlayerCommandAction.START_FALL_FLYING) {
                // 不要在这个包上发出 StartGliding。Java 常常仍报告
                // onGround；GanAC 的 ElytraF 会把它抑制掉。改为在第一个
                // 离地的 AuthInput 上发出（applyJavaGlideStart）。
                clientPlayer.requestStartGliding();
                return;
            }
            clientPlayer.addAuthInputData(inputData);
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_ACTION, null, wrapper -> {
            wrapper.cancel();
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final ChunkTracker chunkTracker = wrapper.user().get(ChunkTracker.class);
            final PlayerActionAction action = PlayerActionAction.values()[wrapper.read(Types.VAR_INT)]; // 动作
            final BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // 方块位置
            final Direction direction = Direction.values()[wrapper.read(Types.UNSIGNED_BYTE)]; // 面
            final int sequence = wrapper.read(Types.VAR_INT); // 序列号

            if (isSelectedHandPlayerAction(action)
                    && !clientPlayer.canProcessHandSensitiveAction(InteractionHand.MAIN_HAND, false)) {
                ExperimentalFeatures.retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
                if (sequence > 0) {
                    PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                }
                return;
            }

            final boolean isMining = action == PlayerActionAction.START_DESTROY_BLOCK || action == PlayerActionAction.ABORT_DESTROY_BLOCK || action == PlayerActionAction.STOP_DESTROY_BLOCK;
            if (isMining && (gameSession.isImmutableWorld() || !clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Mine))) {
                // TODO: 阻止破坏方块，并取消所有会被发送的包（挥手、玩家动作）
                final int fixedBlockState = chunkTracker.getResolvedJavaBlockState(position);
                PacketFactory.sendJavaBlockUpdate(wrapper.user(), position, fixedBlockState);
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                return;
            }

            // TODO: 破坏方块：发送正确的物品栏事务

            switch (action) {
                case START_DESTROY_BLOCK -> {
                    clientPlayer.sendSwingPacketToServer();
                    clientPlayer.cancelNextSwingPacket();
                    // 创造模式和硬度为 0 的方块：Java 只发送 START_DESTROY_BLOCK。
                    // TODO: 测试破坏火
                    // TODO: Java 客户端在等待方块破坏冷却期间会不断发送挥手包。这些需要被取消

                    clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StartDestroyBlock, position, direction.ordinal()));
                    if (isInstantBreak(wrapper.user(), chunkTracker, position)) {
                        // 瞬破情形（创造模式、破坏时间为 0、剪刀剪树叶）：Java 只
                        // 发送 START_DESTROY_BLOCK，从不发送 STOP。MOT SAI 忽略 CreativeDestroyBlock(13)，
                        // 只在 PredictDestroyBlock 时才破坏，因此要在同一刻完成。
                        clientPlayer.clearDelayedMotBreak();
                        finishBlockBreak(wrapper.user(), gameSession, clientPlayer, chunkTracker, position, direction);
                    } else {
                        scheduleDelayedMotBreak(clientPlayer, chunkTracker, position, direction);
                        clientPlayer.setBlockBreakingInfo(new ClientPlayerEntity.BlockBreakingInfo(position, direction));
                    }
                }
                case ABORT_DESTROY_BLOCK -> {
                    final int abortFacing = abortDestroyFacing(direction, clientPlayer.blockBreakingInfo());
                    clientPlayer.clearDelayedMotBreak();
                    clientPlayer.setBlockBreakingInfo(null);
                    clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.AbortDestroyBlock, position, abortFacing));
                }
                case STOP_DESTROY_BLOCK -> {
                    clientPlayer.cancelNextSwingPacket();
                    clientPlayer.clearDelayedMotBreak();
                    clientPlayer.setBlockBreakingInfo(null);
                    finishBlockBreak(wrapper.user(), gameSession, clientPlayer, chunkTracker, position, direction);
                }
                case DROP_ALL_ITEMS, DROP_ITEM -> {
                    // 实验性物品栏开启时，由 ExperimentalFeatures 的前置处理负责此项。
                    // 为官方非实验性路径保留重新同步的回退。
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), wrapper.user().get(InventoryTracker.class).getInventoryContainer());
                }
                case RELEASE_USE_ITEM -> {
                    // 基岩的完成/取消转换由 ExperimentalFeatures 负责。在此重新同步
                    // 会用进食前的快照覆盖 Nukkit 刚消耗的堆叠。
                }
                case SWAP_ITEM_WITH_OFFHAND -> {
                    if (!ExperimentalFeatures.tryHandleSwapHands(wrapper.user())) {
                        PacketFactory.sendJavaContainerSetContent(wrapper.user(), wrapper.user().get(InventoryTracker.class).getInventoryContainer());
                    }
                }
                case STAB -> {
                    // Java 的激流是 PLAYER_ACTION.STAB。MOT 860 只会从
                    // AuthInput 的 START_SPIN_ATTACK（协议 >= 748）开始旋转攻击。线格式的 bit 58 仍落在
                    // 网易 860 的 64 位掩码内（序号 56 加 2 个额外标志）。
                    // 参考：MOT Player.java AuthInputAction.START_SPIN_ATTACK -> onSpinAttack。
                    clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartSpinAttack);
                    if (ItemUseSemantics.sendStandaloneSpinAttackPlayerAction(ViaBedrock.getConfig().shouldEmulateNetEaseClient())) {
                        clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.StartSpinAttack);
                    }
                    final net.raphimc.viabedrock.protocol.model.BedrockItem held = wrapper.user().get(InventoryTracker.class).getInventoryContainer().getSelectedHotbarItem();
                    clientPlayer.beginRiptideSpin(ItemUseSemantics.riptideDurationTicks(ItemUseSemantics.riptideLevel(held != null ? held.tag() : null)));
                }
                default -> throw new IllegalStateException("Unhandled PlayerActionAction: " + action);
            }

            if (sequence > 0) {
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.ATTACK, ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            final int entityId = wrapper.read(Types.VAR_INT); // 实体 ID
            final Entity entity = entityTracker.getEntityByJid(entityId);
            if (entity == null) {
                if (!clientPlayer.canProcessHandSensitiveAction(InteractionHand.MAIN_HAND, false)) {
                    ExperimentalFeatures.retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
                    wrapper.cancel();
                    return;
                }
                if (!ExperimentalFeatures.tryHandleItemFrameAttack(wrapper.user(), entityId)) {
                    ExperimentalFeatures.tryHandleCustomBlockOverlayAttack(wrapper.user(), entityId);
                }
                wrapper.cancel();
                return;
            }

            final Position3f clickPosition = attackClickPosition(clientPlayer);
            if (!clientPlayer.canProcessHandSensitiveAction(InteractionHand.MAIN_HAND, false)) {
                EntityInteractionPacketSender.enqueueDeferred(clientPlayer, entity,
                        ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue(), clickPosition, true);
                // Java 客户端在 ATTACK 之后还会另外发出一次 SWING。提升布局生效期间
                // 抑制它；延迟发送器会在确认之后补发一次。
                clientPlayer.cancelNextSwingPacket();
                wrapper.cancel();
                return;
            }
            EntityInteractionPacketSender.flushDeferred(wrapper.user(), clientPlayer);

            final InventoryContainer inventoryContainer = wrapper.user().get(InventoryTracker.class).getInventoryContainer();
            EntityInteractionPacketSender.write(wrapper, entity.runtimeId(),
                    ItemUseOnActorInventoryTransaction_ActionType.Attack.getValue(),
                    (int) inventoryContainer.getSelectedHotbarSlot(), inventoryContainer.getSelectedHotbarItem(),
                    clientPlayer.position(), clickPosition);

            clientPlayer.sendSwingPacketToServer();
            clientPlayer.cancelNextSwingPacket();
        });
        protocol.registerServerbound(ServerboundPackets26_1.INTERACT, ServerboundBedrockPackets.INVENTORY_TRANSACTION, wrapper -> {
            final EntityTracker entityTracker = wrapper.user().get(EntityTracker.class);
            final int entityId = wrapper.read(Types.VAR_INT); // 实体 ID
            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // 手
            final Entity entity = entityTracker.getEntityByJid(entityId);
            if (entity == null) {
                final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
                if (!clientPlayer.canProcessHandSensitiveAction(hand, false)) {
                    ExperimentalFeatures.retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
                    wrapper.cancel();
                    return;
                }
                // 物品展示框和自定义方块叠加层是覆盖在真实基岩方块上的假 Java 实体。
                // 把右键转换成服务端期望的方块交互。
                if (!ExperimentalFeatures.tryHandleItemFrameInteract(wrapper.user(), entityId, hand)) {
                    ExperimentalFeatures.tryHandleCustomBlockOverlayInteract(wrapper.user(), entityId, hand);
                }
                wrapper.cancel();
                return;
            }
            if (hand != InteractionHand.MAIN_HAND && !ViaBedrock.getConfig().shouldEnableExperimentalFeatures()) {
                wrapper.cancel();
                return;
            }

            final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
            final Vector3d location = wrapper.read(Types.LOW_PRECISION_VECTOR); // 位置
            final Position3f clickPosition = entity.position().add(
                    (float) location.x(), (float) location.y(), (float) location.z());
            wrapper.read(Types.BOOLEAN); // 使用次要操作

            if (!clientPlayer.canProcessHandSensitiveAction(hand, false)) {
                if (hand == InteractionHand.MAIN_HAND) {
                    EntityInteractionPacketSender.enqueueDeferred(clientPlayer, entity,
                            ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue(), clickPosition);
                }
                wrapper.cancel();
                return;
            }
            if (!clientPlayer.isOffhandPromoted()) {
                EntityInteractionPacketSender.flushDeferred(wrapper.user(), clientPlayer);
            }

            // MOT 的事务类型 3 会装备 hotbarSlot，把 itemInHand 与
            // getItemInHand() 做比较，并把该主手堆叠传给 Entity.onInteract。
            final ItemUseHandContext handContext = ExperimentalFeatures.motContextForEntityInteraction(
                    wrapper.user(), clientPlayer, hand);
            if (handContext == null) {
                wrapper.cancel();
                final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getOffhandContainer());
                return;
            }
            // 实体交互的挥手不是攻击：登记来源，避免后续 Swing 被当成攻击写入 MissedSwing。
            clientPlayer.swingSourceTracker().recordUse(hand, ActorSwingSource.Interact);

            // TODO: 基岩客户端在悬停实体变化时发送 INTERACT 包。可能被反作弊使用
            EntityInteractionPacketSender.write(wrapper, entity.runtimeId(),
                    ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue(),
                    handContext.entityTransactionHotbarSlot(wrapper.user().get(InventoryTracker.class)
                            .getInventoryContainer().getSelectedHotbarSlot()),
                    handContext.item(), clientPlayer.position(), clickPosition);

            if (hand == InteractionHand.OFF_HAND && clientPlayer.isOffhandPromoted()) {
                // 保持线格式顺序：提升交换、类型 3 交互、恢复交换。
                wrapper.sendToServer(BedrockProtocol.class);
                wrapper.cancel();
                ExperimentalFeatures.restorePromotedOffhand(wrapper.user(), clientPlayer);
            }
        });
        // movement 序号只为旧版 NyaAC 边界兼容保留；新边界直接以 CLIENT_TICK_END 定位 PAI。
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_STATUS_ONLY, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.UNSIGNED_BYTE));
            wrapper.user().get(PacketSyncStorage.class).recordJavaMovementFrame();
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_POS, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.UNSIGNED_BYTE));
            wrapper.user().get(PacketSyncStorage.class).recordJavaMovementFrame();
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_POS_ROT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), MathUtil.wrapDegrees(wrapper.read(Types.FLOAT)), wrapper.read(Types.FLOAT), wrapper.read(Types.UNSIGNED_BYTE));
            wrapper.user().get(PacketSyncStorage.class).recordJavaMovementFrame();
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_ROT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(MathUtil.wrapDegrees(wrapper.read(Types.FLOAT)), wrapper.read(Types.FLOAT), wrapper.read(Types.UNSIGNED_BYTE));
            wrapper.user().get(PacketSyncStorage.class).recordJavaMovementFrame();
        });
        protocol.registerServerbound(ServerboundPackets26_1.ACCEPT_TELEPORTATION, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.confirmTeleport(wrapper.read(Types.VAR_INT)); // 传送 ID
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_INPUT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final Set<InputFlag> inputFlags = EnumUtil.getEnumSetFromBitmask(InputFlag.class, wrapper.read(Types.BYTE), InputFlag::ordinal); // 输入标志
            final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
            if (spectatorCamera.handleShiftInput(inputFlags.contains(InputFlag.SHIFT))) {
                inputFlags.remove(InputFlag.SHIFT);
                clientPlayer.setSneaking(false);
            }
            clientPlayer.setInputFlags(inputFlags);
        });
        protocol.registerServerbound(ServerboundPackets26_1.CLIENT_TICK_END, ServerboundBedrockPackets.PLAYER_AUTH_INPUT, wrapper -> {
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final Position3f prevPosition = clientPlayer.prevPosition();            final boolean prevOnGround = clientPlayer.prevOnGround();
            final Set<InputFlag> prevInputFlags = clientPlayer.prevInputFlags();
            clientPlayer.tick();
            clientPlayer.deferredEntityActions().discardExpired(clientPlayer.age());
            final boolean immobile = clientPlayer.isInputMovementLocked() || clientPlayer.hasEntityFlag(ActorFlags.NOAI);
            // 输入锁只禁输入；服务端推来的位移仍要如实上报，否则后端看不到位移。
            final boolean noAi = clientPlayer.hasEntityFlag(ActorFlags.NOAI);

            // MOT 的 PlayerJumpEvent 只有 START_JUMPING。在按住 JUMP 的每个着地刻
            // 都发出它，会让 GanAC 的 AirJump 在 MOT 已经离地之后又看到一次跳跃。
            // 每次按下或每次落地发一次脉冲就足够了。
            // 参考：MOT Player.java START_JUMPING；GanAC AirJumpCheck。
            if (shouldEmitStartJumping(
                    prevOnGround,
                    clientPlayer.isOnGround(),
                    clientPlayer.inputFlags().contains(InputFlag.JUMP),
                    prevInputFlags.contains(InputFlag.JUMP),
                    isLocalRiding(wrapper.user()))) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartJumping);
            }

            if (!clientPlayer.isInitiallySpawned() || clientPlayer.isDead()) {
                wrapper.cancel();
                clientPlayer.clearDelayedMotBreak();
                discardPendingAuthInput(clientPlayer);
                clientPlayer.deferredEntityActions().clear();
                return;
            }
            completeDueDelayedMotBreak(wrapper.user(), clientPlayer);
            ExperimentalFeatures.retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
            EntityInteractionPacketSender.flushDeferred(wrapper.user(), clientPlayer);

            final PlayerAuthInputPacket_InputData crawlingTransition = wrapper.user()
                    .get(JavaPlayerStateStorage.class)
                    .consumeCrawlingTransition(inferJavaCrawling(wrapper.user(), clientPlayer));
            if (crawlingTransition != null) {
                clientPlayer.addAuthInputData(crawlingTransition);
            }

            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.BlockBreakingDelayEnabled);
            if (clientPlayer.isOnGround()) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.VerticalCollision);
            }
            if (clientPlayer.horizontalCollision()) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.HorizontalCollision);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.FORWARD)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Up);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.BACKWARD)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Down);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.LEFT)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Left);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.RIGHT)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.Right);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.JUMP)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.JumpDown, PlayerAuthInputPacket_InputData.Jumping, PlayerAuthInputPacket_InputData.WantUp, PlayerAuthInputPacket_InputData.JumpCurrentRaw);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.SHIFT)
                    || (clientPlayer.isUsingItem() && clientPlayer.isShieldSneakEmulated())) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakDown, PlayerAuthInputPacket_InputData.Sneaking, PlayerAuthInputPacket_InputData.WantDown, PlayerAuthInputPacket_InputData.SneakCurrentRaw);
                // MOT 中未使用的 PersistSneak：NukkitMOTJE 的 ShieldSneakListener
                // 会为 Java 的举盾即潜行恢复站立 AABB，而不缩到 1.49。
                if (ItemUseSemantics.persistSneakWhileShieldBlocking(
                        ViaBedrock.getConfig().shouldEmulateNetEaseClient(),
                        clientPlayer.isShieldSneakEmulated() && !clientPlayer.inputFlags().contains(InputFlag.SHIFT))) {
                    clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.PersistSneak);
                }
            }
            if (clientPlayer.inputFlags().contains(InputFlag.SPRINT)
                    && !ItemUseSemantics.suppressStartSprintingWhileUsingItem(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), clientPlayer.isUsingItem())) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SprintDown, PlayerAuthInputPacket_InputData.Sprinting);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.JUMP) && !prevInputFlags.contains(InputFlag.JUMP)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.JumpPressedRaw);
            }
            if (prevInputFlags.contains(InputFlag.JUMP) && !clientPlayer.inputFlags().contains(InputFlag.JUMP)) {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.JumpReleasedRaw);
            }
            if (clientPlayer.inputFlags().contains(InputFlag.SHIFT) && !prevInputFlags.contains(InputFlag.SHIFT)) {
                clientPlayer.setSneaking(true);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakPressedRaw, PlayerAuthInputPacket_InputData.StartSneaking);
            }
            if (prevInputFlags.contains(InputFlag.SHIFT) && !clientPlayer.inputFlags().contains(InputFlag.SHIFT)) {
                clientPlayer.setSneaking(false);
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.SneakReleasedRaw, PlayerAuthInputPacket_InputData.StopSneaking);
            }

            applyJavaPoseTransitions(wrapper.user(), clientPlayer);

            final Position3f positionDelta = clientPlayer.position().subtract(prevPosition);
            final Position3f velocity;
            if (noAi) {
                velocity = Position3f.ZERO;
            } else if (!clientPlayer.isInitiallySpawned() || clientPlayer.dimensionChangeInfo() != null || clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Flying)) {
                velocity = positionDelta;
            } else {
                float dx = positionDelta.x() * 0.98F;
                float dy = positionDelta.y();
                float dz = positionDelta.z() * 0.98F;
                final float friction = clientPlayer.isOnGround() ? ProtocolConstants.BLOCK_FRICTION : 1F;
                dx *= friction;
                dz *= friction;

                if (clientPlayer.effects().containsKey("minecraft:levitation")) {
                    dy += (0.05F * (clientPlayer.effects().get("minecraft:levitation").amplifier() + 1)) * 0.2F;
                } else {
                    dy -= neteaseAuthInputGravity(wrapper.user());
                }
                // 缓慢下落不会改变静止站立时的速度

                velocity = new Position3f(dx * 0.91F, dy * 0.98F, dz * 0.91F);
            }

            if (clientPlayer.isMovementInputLocked()) {
                removeImmobileMovementInput(clientPlayer.authInputData());
            }
            final PlayerAuthInputContext authInputContext = new PlayerAuthInputContext(clientPlayer.position(), velocity);
            ExperimentalFeatures.dispatchPlayerAuthInput(wrapper.user(), clientPlayer, authInputContext);
            if (immobile) {
                removeImmobileMovementInput(clientPlayer.authInputData());
                authInputContext.setPosition(clientPlayer.position());
            }

            // MOT 的 EnumMap 先迭代 START 再迭代 ABORT。在生成位掩码之前先合并，
            // 这样动作列表被清空时可以在线格式上去掉 PerformBlockActions。
            final List<ClientPlayerEntity.AuthInputBlockAction> blockActions =
                    coalesceAuthInputBlockActions(clientPlayer.authInputBlockActions());
            if (blockActions.isEmpty()) {
                clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.PerformBlockActions);
            }

            // wrapDegrees(180)==-180。写入经 wrap 处理的朝向，使 GanAC 的 BadPacketB
            // 和 MOT 的 AuthInput 都保持在 (-180, 180] 内。Java 的 yRot 可能是 +180。
            // 参考：MOT PlayerAuthInputPacket.decode() LFloat yaw；GanAC MathUtil.wrapDegrees。
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().x()); // 俯仰角
            wrapper.write(BedrockTypes.FLOAT_LE, MathUtil.wrapDegrees(clientPlayer.rotation().y())); // 偏航角
            wrapper.write(BedrockTypes.POSITION_3F, authInputContext.position()); // 位置
            wrapper.write(BedrockTypes.POSITION_2F, immobile ? new Position2f(0F, 0F) : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), clientPlayer.isSneaking())); // 移动向量
            wrapper.write(BedrockTypes.FLOAT_LE, MathUtil.wrapDegrees(clientPlayer.rotation().z())); // 头部偏航角
            if (PlayerAuthInputLayout.usesInputDataList()) {
                // 2168 起输入标志不再是 64 位掩码，而是“条目数 + 每条序号”；2168~2169 前面还多一个恒 true 外层
                if (PlayerAuthInputLayout.usesOptionalSectionOuterBool()) {
                    wrapper.write(Types.BOOLEAN, true); // 恒 true 外层
                }
                wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, clientPlayer.authInputData().size()); // 输入标志数量
                for (PlayerAuthInputPacket_InputData inputData : clientPlayer.authInputData()) {
                    wrapper.write(BedrockTypes.VAR_INT, inputData.getValue()); // 输入标志（zigzag varint，与 MOT getVarInt 同形）
                }
            } else {
                wrapper.write(BedrockTypes.UNSIGNED_VAR_BIG_INTEGER, PlayerAuthInputLayout.encodeBitmask(clientPlayer.authInputData())); // 输入标志
            }
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, InputMode.Mouse.getValue()); // 输入模式
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, ClientPlayMode.Screen.getValue()); // 游玩模式
            if (PlayerAuthInputLayout.usesInputDataList()) {
                wrapper.write(BedrockTypes.VAR_INT, NewInteractionModel.Crosshair.getValue()); // 交互模式（有符号 varint）
            } else {
                wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, NewInteractionModel.Crosshair.getValue()); // 交互模式
            }
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().x()); // 交互俯仰角
            wrapper.write(BedrockTypes.FLOAT_LE, MathUtil.wrapDegrees(clientPlayer.rotation().y())); // 交互偏航角
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, (long) clientPlayer.age()); // 刻
            wrapper.write(BedrockTypes.POSITION_3F, authInputContext.delta()); // 增量
            if (PlayerAuthInputLayout.usesCameraDeparted()) {
                wrapper.write(Types.BOOLEAN, false); // 相机已离开（网易 >= 422）
            }
            // 2168 起每个可选段前有一对 bool：恒 true 的外层加内层存在性；pre-2168 由输入标志隐式决定
            final boolean optionalSectionOuterBool = PlayerAuthInputLayout.usesOptionalSectionOuterBool();
            final boolean optionalSectionFlags = PlayerAuthInputLayout.usesInputDataList();
            final BedrockInventoryTransaction itemInteraction = clientPlayer.authInputData().contains(PlayerAuthInputPacket_InputData.PerformItemInteraction)
                    ? clientPlayer.authInputItemInteraction() : null;
            if (optionalSectionOuterBool) {
                wrapper.write(Types.BOOLEAN, true); // 恒 true 外层
            }
            if (optionalSectionFlags) {
                wrapper.write(Types.BOOLEAN, itemInteraction != null); // 物品交互存在性
            }
            if (itemInteraction != null) {
                wrapper.write(wrapper.user().get(InventoryTransactionRewriter.class).getItemInteractionDataType(), itemInteraction);
            }
            if (optionalSectionOuterBool) {
                wrapper.write(Types.BOOLEAN, true); // 恒 true 外层
            }
            if (optionalSectionFlags) {
                // 物品堆请求走独立包，内嵌段恒为空
                wrapper.write(Types.BOOLEAN, false); // 物品堆请求存在性
            }
            if (optionalSectionOuterBool) {
                wrapper.write(Types.BOOLEAN, true); // 恒 true 外层
            }
            if (optionalSectionFlags) {
                wrapper.write(Types.BOOLEAN, !blockActions.isEmpty()); // 方块动作存在性
            }
            if (!blockActions.isEmpty()) {
                if (PlayerAuthInputLayout.usesUnconditionalBlockActions()) {
                    wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, blockActions.size()); // 玩家方块动作数量
                } else {
                    wrapper.write(BedrockTypes.VAR_INT, blockActions.size()); // 玩家方块动作数量
                }
                for (ClientPlayerEntity.AuthInputBlockAction blockAction : blockActions) {
                    wrapper.write(BedrockTypes.VAR_INT, blockAction.action().getValue()); // 动作
                    // 2168 起位置与朝向恒在；pre-2168 只有会改变世界的动作带
                    // StopDestroyBlock 没有附加数据，尽管基岩协议文档声称它有
                    final boolean hasBody = PlayerAuthInputLayout.usesUnconditionalBlockActions()
                            || switch (blockAction.action()) {
                        case StartDestroyBlock, AbortDestroyBlock, CrackBlock, PredictDestroyBlock, ContinueDestroyBlock -> true;
                        default -> false;
                    };
                    if (hasBody) {
                        wrapper.write(BedrockTypes.SIGNED_BLOCK_POSITION, blockAction.position()); // 有符号位置
                        wrapper.write(BedrockTypes.VAR_INT, blockAction.direction()); // 朝向
                    }
                }
            }
            if (optionalSectionOuterBool) {
                wrapper.write(Types.BOOLEAN, true); // 恒 true 外层
            }
            if (optionalSectionFlags) {
                wrapper.write(Types.BOOLEAN, authInputContext.hasPredictedVehicle()); // 载具旋转存在性
            }
            if (authInputContext.hasPredictedVehicle()) {
                wrapper.write(BedrockTypes.FLOAT_LE, authInputContext.vehiclePitch()); // 载具俯仰角
                wrapper.write(BedrockTypes.FLOAT_LE, authInputContext.vehicleYaw()); // 载具偏航角
            }
            if (optionalSectionOuterBool) {
                wrapper.write(Types.BOOLEAN, true); // 恒 true 外层
            }
            if (optionalSectionFlags) {
                wrapper.write(Types.BOOLEAN, authInputContext.hasPredictedVehicle()); // 预测载具存在性
            }
            if (authInputContext.hasPredictedVehicle()) {
                wrapper.write(BedrockTypes.VAR_LONG, authInputContext.predictedVehicleUniqueId()); // 预测载具实体唯一 ID
            }
            final Position2f analogMoveVector = immobile
                    ? new Position2f(0F, 0F)
                    : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), clientPlayer.isSneaking());
            final Position2f rawMoveVector = immobile
                    ? new Position2f(0F, 0F)
                    : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), false);
            wrapper.write(BedrockTypes.POSITION_2F, analogMoveVector); // 模拟移动向量
            wrapper.write(BedrockTypes.POSITION_3F, MathUtil.calculateCameraOrientation(clientPlayer.rotation().y(), clientPlayer.rotation().x())); // 相机朝向
            wrapper.write(BedrockTypes.POSITION_2F, rawMoveVector); // 原始移动向量
            if (PlayerAuthInputLayout.usesNetEaseTail()) {
                // 网易 v1_21_50 起的固定尾部块，字段本身不被 MOT 使用，但少写会解码越界
                wrapper.write(Types.BOOLEAN, false); // 第三人称视角
                wrapper.write(BedrockTypes.POSITION_2F, new Position2f(clientPlayer.rotation().x(), MathUtil.wrapDegrees(clientPlayer.rotation().y()))); // 玩家到相机的旋转
                wrapper.write(Types.BOOLEAN, false); // 位置增量待同步
                wrapper.write(Types.BOOLEAN, clientPlayer.isOnGround()); // 着地
                wrapper.write(Types.BYTE, (byte) 0); // 重置位置
            }

            // 当前 AuthInput 的所有字段已构建完成，且已越过未出生/死亡的取消分支。
            // 在这里立即推进 tick-end 序号并释放就绪 ACK，不等待额外 movement 或其他包。
            // sendToServer 先发出 ACK，随后当前 wrapper 正常发出 PAI；线上顺序为 ACK → PAI。
            // 同一边界只消费一次，多个就绪 ACK 按入队顺序共同定位紧随其后的这一份 AuthInput。
            final PacketSyncStorage packetSyncStorage = wrapper.user().get(PacketSyncStorage.class);
            packetSyncStorage.recordJavaClientTickEndFrame();
            final List<PacketSyncStorage.NetworkStackLatencyResponse> clientTickEndBoundaries =
                    packetSyncStorage.consumeClientTickEndBoundaryResponses();
            for (PacketSyncStorage.NetworkStackLatencyResponse response : clientTickEndBoundaries) {
                final PacketWrapper latency = PacketWrapper.create(
                        ServerboundBedrockPackets.NETWORK_STACK_LATENCY, wrapper.user());
                MultiStatePackets.writeNetworkStackLatencyResponse(latency, response);
                latency.sendToServer(BedrockProtocol.class);
            }

            clientPlayer.authInputData().clear();
            clientPlayer.authInputBlockActions().clear();
            clientPlayer.clearAuthInputItemInteraction();
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_ABILITIES, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final byte flags = wrapper.read(Types.BYTE); // 标志
            final boolean flying = (flags & AbilitiesFlag.FLYING.getBit()) != 0;
            if (flying != clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Flying)) {
                clientPlayer.abilities().getOrCreateCacheLayer().setAbility(AbilitiesIndex.Flying, flying);
                clientPlayer.addAuthInputData(flying ? PlayerAuthInputPacket_InputData.StartFlying : PlayerAuthInputPacket_InputData.StopFlying);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.CHANGE_GAME_MODE, ServerboundBedrockPackets.SET_PLAYER_GAME_TYPE, new PacketHandlers() {
            @Override
            protected void register() {
                handler(wrapper -> {
                    if (!wrapper.user().get(SpectatorCameraTracker.class).acceptsJavaGameModeChange()) {
                        wrapper.cancel();
                        return;
                    }
                    final GameMode gameMode = GameMode.values()[wrapper.read(Types.VAR_INT)]; // 游戏模式
                    final GameType gameType = switch (gameMode) {
                        case SURVIVAL -> GameType.Survival;
                        case CREATIVE -> GameType.Creative;
                        case ADVENTURE -> GameType.Adventure;
                        case SPECTATOR -> GameType.Spectator;
                        default -> throw new IllegalStateException("Unhandled GameMode: " + gameMode);
                    };
                    wrapper.write(BedrockTypes.VAR_INT, gameType.getValue()); // 游戏类型
                    wrapper.user().get(EntityTracker.class).getClientPlayer().setGameType(gameType);
                });
                handler(CLIENT_PLAYER_GAME_MODE_INFO_UPDATE);
                handler(CLIENT_PLAYER_GAME_MODE_UPDATE);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SWING, ServerboundBedrockPackets.ANIMATE, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // 手
            final ActorSwingSource swingSource = clientPlayer.swingSourceTracker().consume(hand);
            final boolean cancelSwing = clientPlayer.checkCancelSwingPacket();
            if (hand != InteractionHand.MAIN_HAND || cancelSwing || clientPlayer.isOffhandPromoted()) {
                wrapper.cancel();
                if (clientPlayer.isOffhandPromoted()) {
                    ExperimentalFeatures.retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
                }
                return;
            }

            EntityPacketLayout.writeAnimateAction(wrapper, AnimatePacketPayload_Action.Swing.getValue()); // 动作
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // 实体运行时 ID
            wrapper.write(BedrockTypes.FLOAT_LE, 0F); // 数据
            EntityPacketLayout.writeAnimateTrailer(wrapper, swingSource.name().toLowerCase(Locale.ROOT)); // 挥手来源（897+）

            if (swingSource != ActorSwingSource.Attack) {
                return;
            }
            if (clientPlayer.blockBreakingInfo() != null) {
                if (!gameSession.isBlockBreakingServerAuthoritative()) {
                    final ClientPlayerEntity.BlockBreakingInfo blockBreakingInfo = clientPlayer.blockBreakingInfo();
                    clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, blockBreakingInfo.position(), blockBreakingInfo.direction().ordinal()));
                }
            } else {
                clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.MissedSwing);
            }
        });
    }

    static void removeImmobileMovementInput(final Set<PlayerAuthInputPacket_InputData> inputData) {
        inputData.removeAll(IMMOBILE_MOVEMENT_INPUTS);
    }

    /**
     * AuthInput 标志和方块动作会在同一刻内跨多个 Java 包排队，
     * 只有在 PLAYER_AUTH_INPUT 成功写入之后才清除。取消
     * CLIENT_TICK_END（未生成 / 已死亡）时仍必须丢弃这些残留，否则下一次
     * 有效的 AuthInput 会重放 START_JUMPING / PERFORM_BLOCK_ACTIONS。
     */
    static void discardPendingAuthInput(final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null) {
            return;
        }
        clientPlayer.authInputData().clear();
        clientPlayer.authInputBlockActions().clear();
        clientPlayer.clearAuthInputItemInteraction();
    }

    /**
     * MOT 860 把 AuthInput 方块动作存放在 {@code EnumMap<PlayerActionType,>} 中，
     * 并按声明顺序迭代（{@code START, ABORT, STOP, ..., PREDICT, CONTINUE}）。
     * 因此同一刻内 Java 对 Abort(old)+Start(new) 的重定向会先执行 START 再执行 ABORT，
     * 从而取消新的破坏。原生基岩不会把 Abort+Start 放进同一个 AuthInput；
     * 当下一个动作的位置不同时，MOT 已经会中止上一个目标。
     * 同一刻的 Start+Continue+Predict 予以保留：MOT 的 EnumMap 顺序是 START、
     * PREDICT、CONTINUE，即开始然后完成同一个方块。
     * 参考：MOT Player.java 的 SAI 循环；PlayerAuthInputPacket.decodeBlockActions。
     */
    static List<ClientPlayerEntity.AuthInputBlockAction> coalesceAuthInputBlockActions(
            final List<ClientPlayerEntity.AuthInputBlockAction> queued) {
        if (queued == null || queued.isEmpty()) {
            return List.of();
        }
        final List<ClientPlayerEntity.AuthInputBlockAction> coalesced = new ArrayList<>(queued.size());
        for (ClientPlayerEntity.AuthInputBlockAction action : queued) {
            if (action == null || action.action() == null) {
                continue;
            }
            coalesced.add(action);
            if (action.action() == PlayerActionType.StartDestroyBlock) {
                dropPriorAbortForRetarget(coalesced, action);
            }
        }
        return coalesced;
    }

    private static void dropPriorAbortForRetarget(
            final List<ClientPlayerEntity.AuthInputBlockAction> coalesced,
            final ClientPlayerEntity.AuthInputBlockAction start) {
        if (start.position() == null) {
            return;
        }
        coalesced.removeIf(existing -> existing != start
                && existing.action() == PlayerActionType.AbortDestroyBlock
                && existing.position() != null
                && !existing.position().equals(start.position()));
    }

    static float neteaseAuthInputGravity(final UserConnection user) {
        final GameSessionStorage gameSession = user.get(GameSessionStorage.class);
        return neteaseAuthInputGravity(gameSession != null ? gameSession.getNeteaseLevelGravity() : null);
    }

    static float neteaseAuthInputGravity(final Float gravity) {
        if (gravity == null) {
            return ProtocolConstants.PLAYER_GRAVITY;
        }
        // MOT 把 SET_LEVEL_GRAVITY 写成带符号的加速度（进入时重置为 -0.08）。
        // PLAYER_AUTH_INPUT 已经减去了一个正的重力常量，因此取绝对值。
        return Math.abs(gravity);
    }

    static PlayerAuthInputPacket_InputData playerCommandInputData(final PlayerCommandAction action) {
        return switch (action) {
            case START_SPRINTING -> PlayerAuthInputPacket_InputData.StartSprinting;
            case STOP_SPRINTING -> PlayerAuthInputPacket_InputData.StopSprinting;
            case START_FALL_FLYING -> PlayerAuthInputPacket_InputData.StartGliding;
            case STOP_SLEEPING, START_RIDING_JUMP, STOP_RIDING_JUMP, OPEN_INVENTORY -> null;
        };
    }

    /**
     * MOT 在 {@code START_JUMPING} 时触发 {@code PlayerJumpEvent}。原版基岩只在跳跃的
     * 第一个离地刻发出该位，而不是在按住 JUMP 的每个着地刻。
     * Java 可能在多次落地之间一直按住 JUMP，因此在着地时新按下，或
     * 在落地后的第一个着地刻且 JUMP 仍按住时发出。
     */
    static boolean shouldEmitStartJumping(final boolean prevOnGround, final boolean onGround,
                                          final boolean jumpHeld, final boolean prevJumpHeld) {
        return shouldEmitStartJumping(prevOnGround, onGround, jumpHeld, prevJumpHeld, false);
    }

    /**
     * 骑乘时的 JUMP 是 Java 的 START_RIDING_JUMP，不是玩家跳跃。MOT 860 从不
     * 根据 START_JUMPING 应用马跳跃，而 GanAC 的 AirJump 会在乘客离地时
     * 对该位计分。
     */
    static boolean shouldEmitStartJumping(final boolean prevOnGround, final boolean onGround,
                                          final boolean jumpHeld, final boolean prevJumpHeld,
                                          final boolean riding) {
        if (riding || !jumpHeld || (!prevOnGround && !onGround)) {
            return false;
        }
        return !prevJumpHeld || !prevOnGround;
    }

    /**
     * MOT 的中止粒子使用 BlockFace.fromIndex(facing)。优先取这个
     * 中止包中的面，其次取缓存的 START/CONTINUE 面，最后取 DOWN（MOT 自身在
     * 因目标方块变化而中止时用的就是 DOWN）。
     */
    static int abortDestroyFacing(final Direction packetDirection, final ClientPlayerEntity.BlockBreakingInfo breakingInfo) {
        if (packetDirection != null) {
            return packetDirection.ordinal();
        }
        if (breakingInfo != null && breakingInfo.direction() != null) {
            return breakingInfo.direction().ordinal();
        }
        return Direction.DOWN.ordinal();
    }

    /**
     * Java 从不发出基岩的 {@code START/STOP_SWIMMING} 或 {@code STOP_GLIDING}。MOT 860 和
     * GanAC 只会根据这些 AuthInput 标志进入游泳/滑翔姿态，因此在这里转换
     * Java 的水中疾跑以及落地/入水/卸下/上载具等滑翔停止边界。
     * 不合成 Java 的跳跃取消：烟花推进期间 JUMP 会一直按住。
     * 参考：MOT Player.java 的 START_GLIDING/STOP_GLIDING 以及 onGround/胸甲清除。
     */
    static void applyJavaPoseTransitions(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null) {
            return;
        }
        applyJavaGlideStart(user, clientPlayer);
        applyJavaGlideStop(user, clientPlayer);
        applyJavaSwimTransition(user, clientPlayer);
        applyJavaRiptideStop(clientPlayer);
    }

    /**
     * MOT SAI 的 STOP_SPIN_ATTACK 才是清除 DATA_FLAG_SPIN_ATTACK 的动作。riptideTicks
     * 只跳过速度检查，从不丢弃该标志。在 MOT 的时长 {@code 50+(level<<5)} 之后
     * 发出一次 Stop 脉冲。
     */
    static void applyJavaRiptideStop(final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null || !clientPlayer.shouldStopRiptideSpin()) {
            return;
        }
        clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StopSpinAttack);
        if (ItemUseSemantics.sendStandaloneSpinAttackPlayerAction(ViaBedrock.getConfig().shouldEmulateNetEaseClient())) {
            clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.StopSpinAttack);
        }
        clientPlayer.clearRiptideSpin();
    }

    /**
     * Java 会在 1 格高的空隙中自动爬行。MOT 860 只会根据 AuthInput 的
     * START_CRAWLING 进入爬行。当 VBU 从不报告姿态时推断：潜行 + onGround + 脚上方
     * 1.5 格处有实心方块（站立 AABB 会碰撞）。
     */
    static boolean inferJavaCrawling(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null || !clientPlayer.isSneaking() || !clientPlayer.isOnGround()
                || clientPlayer.isSwimming() || clientPlayer.isGliding() || isLocalRiding(user)) {
            return false;
        }
        return hasSolidAboveFeet(user, clientPlayer, 1);
    }

    static boolean hasSolidAboveFeet(final UserConnection user, final ClientPlayerEntity clientPlayer, final int blocksAbove) {
        if (user == null || clientPlayer == null || clientPlayer.position() == null) {
            return false;
        }
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final BlockStateRewriter blockStateRewriter = user.get(BlockStateRewriter.class);
        if (chunkTracker == null || blockStateRewriter == null) {
            return false;
        }
        final BlockPosition feet = feetBlockPosition(clientPlayer);
        final BlockPosition head = new BlockPosition(feet.x(), feet.y() + blocksAbove, feet.z());
        return isSolidCrawlCeiling(blockStateRewriter, chunkTracker.getBlockState(0, head));
    }

    static boolean isSolidCrawlCeiling(final BlockStateRewriter blockStateRewriter, final int bedrockBlockStateId) {
        if (blockStateRewriter == null || bedrockBlockStateId <= 0) {
            return false;
        }
        final BlockState state = blockStateRewriter.blockState(bedrockBlockStateId);
        if (state == null) {
            return false;
        }
        final String identifier = state.identifier();
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        if ("air".equals(identifier) || "cave_air".equals(identifier) || "void_air".equals(identifier)
                || isWaterIdentifier(identifier) || "lava".equals(identifier) || "flowing_lava".equals(identifier)) {
            return false;
        }
        return !identifier.endsWith("_slab")
                && !identifier.endsWith("_stairs")
                && !identifier.endsWith("_carpet")
                && !identifier.contains("trapdoor")
                && !identifier.contains("sign")
                && !identifier.endsWith("_button")
                && !identifier.endsWith("_pressure_plate")
                && !identifier.endsWith("_torch")
                && !identifier.equals("fire")
                && !identifier.equals("soul_fire");
    }

    /**
     * MOT 的 {@code START_SWIMMING} 使用的是应用本次 AuthInput
     * 位置<em>之前</em>的脚部方块。水中的第一刻会被 {@code needSendData} 取消，
     * 并且从不设置 {@code ActorFlags.SWIMMING}。Java 在本地仍预测姿态 3。
     * 不要在发出时就锁定 {@link ClientPlayerEntity#isSwimming()}；重试 Start
     * 直到 MOT 元数据确认，否则观察者会停留在 {@code POSE=STANDING}。
     */
    static void applyJavaSwimTransition(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        final boolean wantSwim = wantsJavaSwim(user, clientPlayer);
        final boolean motSwimming = clientPlayer.hasEntityFlag(ActorFlags.SWIMMING);
        final PlayerAuthInputPacket_InputData flag = swimTransitionFlag(wantSwim, motSwimming);
        if (flag != null) {
            clientPlayer.addAuthInputData(flag);
        }
        clientPlayer.setSwimming(localSwimmingAfterTransition(wantSwim, motSwimming));
    }

    static void applyJavaGlideStart(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (!clientPlayer.pendingStartGliding()) {
            return;
        }
        if (clientPlayer.isGliding() || shouldCancelPendingStartGliding(
                isInsideOfWater(user, clientPlayer),
                isLocalRiding(user),
                wearingElytra(user))) {
            clientPlayer.consumePendingStartGliding();
            return;
        }
        if (shouldEmitStartGliding(true, clientPlayer.isOnGround())
                && clientPlayer.consumePendingStartGliding()) {
            clientPlayer.setGliding(true);
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StartGliding);
        }
    }

    static boolean shouldEmitStartGliding(final boolean pending, final boolean onGround) {
        return pending && !onGround;
    }

    static boolean shouldCancelPendingStartGliding(final boolean inWater, final boolean riding,
                                                   final boolean wearingElytra) {
        return inWater || riding || !wearingElytra;
    }

    static void applyJavaGlideStop(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (shouldStopGliding(
                clientPlayer.isGliding(),
                clientPlayer.isOnGround(),
                isInsideOfWater(user, clientPlayer),
                isLocalRiding(user),
                wearingElytra(user))) {
            clientPlayer.setGliding(false);
            clientPlayer.addAuthInputData(PlayerAuthInputPacket_InputData.StopGliding);
        }
    }

    /**
     * {@code motSwimming} 是 MOT 的 {@link ActorFlags#SWIMMING}，不是本地预测。
     * {@code wantSwim && !motSwimming} 必须继续返回 Start，以便在 MOT 应用
     * 水中坐标之后重试被取消的第一刻。
     */
    static PlayerAuthInputPacket_InputData swimTransitionFlag(final boolean wantSwim, final boolean motSwimming) {
        if (wantSwim && !motSwimming) {
            return PlayerAuthInputPacket_InputData.StartSwimming;
        }
        if (!wantSwim && motSwimming) {
            return PlayerAuthInputPacket_InputData.StopSwimming;
        }
        return null;
    }

    /**
     * 只在 Java 仍想游泳时才镜像 MOT 的确认。即使 SET_ACTOR_DATA 尚未
     * 清除该标志，发出 Stop 也必须丢弃本地锁定状态。
     */
    static boolean localSwimmingAfterTransition(final boolean wantSwim, final boolean motSwimming) {
        return wantSwim && motSwimming;
    }

    static boolean shouldStopGliding(final boolean gliding, final boolean onGround) {
        return shouldStopGliding(gliding, onGround, false, false, true);
    }

    /**
     * MOT 会在落地、胸甲缺失/损坏或上载具时自动清除 GLIDING。Java 的入水
     * 也会取消滑翔；这里省略跳跃取消，以便烟花推进期间保持滑翔。
     */
    static boolean shouldStopGliding(final boolean gliding, final boolean onGround,
                                     final boolean inWater, final boolean riding,
                                     final boolean wearingElytra) {
        return gliding && (onGround || inWater || riding || !wearingElytra);
    }

    static boolean wearingElytra(final UserConnection user) {
        if (user == null) {
            return true;
        }
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        final ItemRewriter itemRewriter = user.get(ItemRewriter.class);
        if (inventoryTracker == null || itemRewriter == null) {
            return true;
        }
        final String identifier = itemRewriter.bedrockIdentifier(inventoryTracker.getArmorContainer().getItem(1));
        if (identifier == null) {
            return false;
        }
        return "minecraft:elytra".equals(identifier) || identifier.endsWith(":elytra");
    }

    static boolean wantsJavaSwim(final boolean gliding, final boolean flying, final boolean riding,
                                 final boolean sprinting, final boolean sprintHeld, final boolean inWater) {
        if (gliding || flying || riding) {
            return false;
        }
        return (sprinting || sprintHeld) && inWater;
    }

    static boolean wantsJavaSwim(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        return wantsJavaSwim(
                clientPlayer.isGliding(),
                clientPlayer.abilities().getBooleanValue(AbilitiesIndex.Flying),
                isLocalRiding(user),
                clientPlayer.isSprinting(),
                clientPlayer.inputFlags().contains(InputFlag.SPRINT),
                isInsideOfWater(user, clientPlayer)
        );
    }

    static boolean isLocalRiding(final UserConnection user) {
        if (user == null) {
            return false;
        }
        final RidingTracker ridingTracker = user.get(RidingTracker.class);
        return ridingTracker != null && ridingTracker.isLocalRiding();
    }

    /**
     * MOT 的 {@code Entity#isInsideOfWater()} 先检查 0 层的脚部方块，再检查 1 层的
     * 水浸。ClientPlayerEntity.position() 是眼睛位置，因此要减去 {@code eyeOffset()}。
     * 当柱缺失时，{@link ChunkTracker#getBlockState(int, BlockPosition)} 返回空气，
     * 这会颠倒游泳边界；在脚部区块加载之前保留最后一次水中采样。不要用
     * {@link ClientPlayerEntity#isSwimming()} 来做这个采样：
     * 在 MOT 设置 {@link ActorFlags#SWIMMING} 之前 StartSwimming 会被重试。
     */
    static boolean isInsideOfWater(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null) {
            return false;
        }
        final Boolean known = knownInsideOfWater(user, clientPlayer);
        if (known != null) {
            clientPlayer.setLastInsideOfWater(known);
        }
        return keepLastInsideOfWater(known, lastInsideOfWaterSample(clientPlayer));
    }

    /**
     * 挂起的 StartSwimming 刻还没有 MOT 标志。记住最后一次已加载的
     * 脚部采样，并在柱缺失时把已确认的 {@link ActorFlags#SWIMMING}
     * 视为仍在水中。
     */
    static boolean lastInsideOfWaterSample(final boolean lastInside, final boolean motSwimming) {
        return lastInside || motSwimming;
    }

    static boolean lastInsideOfWaterSample(final ClientPlayerEntity clientPlayer) {
        return lastInsideOfWaterSample(clientPlayer.lastInsideOfWater(), clientPlayer.hasEntityFlag(ActorFlags.SWIMMING));
    }

    static BlockPosition feetBlockPosition(final ClientPlayerEntity clientPlayer) {
        final Position3f position = clientPlayer.position();
        return new BlockPosition(
                (int) Math.floor(position.x()),
                (int) Math.floor(position.y() - clientPlayer.eyeOffset()),
                (int) Math.floor(position.z())
        );
    }

    /**
     * {@code null} 表示脚部柱尚未加载。已加载但没有 section
     * （Y 超出世界）的柱是干燥空气，与 {@link ChunkTracker#getBlockState(int, BlockPosition)} 一致。
     */
    static Boolean knownInsideOfWater(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        if (user == null || clientPlayer == null || clientPlayer.position() == null) {
            return Boolean.FALSE;
        }
        final ChunkTracker chunkTracker = user.get(ChunkTracker.class);
        final BlockStateRewriter blockStateRewriter = user.get(BlockStateRewriter.class);
        if (chunkTracker == null || blockStateRewriter == null) {
            return null;
        }
        final BlockPosition feet = feetBlockPosition(clientPlayer);
        final BedrockChunk chunk = chunkTracker.getChunk(feet.x() >> 4, feet.z() >> 4);
        if (chunk == null) {
            return null;
        }
        final int sectionIndex = (feet.y() >> 4) + Math.abs(chunkTracker.getMinY() >> 4);
        final boolean sectionInWorld = sectionIndex >= 0 && sectionIndex < chunk.getSections().length;
        if (!sectionInWorld) {
            return Boolean.FALSE;
        }
        if (chunk.getSections()[sectionIndex] == null) {
            return null;
        }
        return isWaterBlock(blockStateRewriter, chunkTracker.getBlockState(0, feet))
                || isWaterBlock(blockStateRewriter, chunkTracker.getBlockState(1, feet));
    }

    /**
     * {@code null} 会保留最后一次水中采样。已加载但 Y 超出世界的柱是干燥的；
     * 缺失的世界内 section 仍处于挂起状态，不能当作空气。
     */
    static Boolean waterSampleFromChunkState(final boolean chunkPresent, final boolean sectionInWorld,
                                             final boolean sectionPresent, final boolean water) {
        if (!chunkPresent) {
            return null;
        }
        if (!sectionInWorld) {
            return Boolean.FALSE;
        }
        if (!sectionPresent) {
            return null;
        }
        return water;
    }

    static boolean keepLastInsideOfWater(final Boolean known, final boolean lastInside) {
        return known != null ? known : lastInside;
    }

    static boolean isWaterBlock(final BlockStateRewriter blockStateRewriter, final int bedrockBlockStateId) {
        if (blockStateRewriter == null) {
            return false;
        }
        if (CustomBlockTags.WATER.equals(blockStateRewriter.tag(bedrockBlockStateId))) {
            return true;
        }
        final BlockState state = blockStateRewriter.blockState(bedrockBlockStateId);
        if (state == null) {
            return false;
        }
        return isWaterIdentifier(state.identifier());
    }

    static boolean isWaterIdentifier(final String identifier) {
        return "water".equals(identifier) || "flowing_water".equals(identifier);
    }

    /**
     * Java 的 ATTACK 只带实体 ID。MOT 的战斗逻辑不消费 clickPos；发出当前视线
     * 方向，使该字段是单位向量而不是 ZERO。
     */
    static Position3f attackClickPosition(final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null || clientPlayer.rotation() == null) {
            return Position3f.ZERO;
        }
        return MathUtil.calculateCameraOrientation(clientPlayer.rotation().y(), clientPlayer.rotation().x());
    }
}
