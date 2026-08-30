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
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // actions
        playerInfoUpdate.write(Types.VAR_INT, 1); // length
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // uuid
        playerInfoUpdate.write(Types.VAR_INT, gameMode.ordinal()); // game mode
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
        playerInfoUpdate.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // actions
        playerInfoUpdate.write(Types.VAR_INT, 1); // length
        playerInfoUpdate.write(Types.UUID, clientPlayer.javaUuid()); // uuid
        playerInfoUpdate.write(Types.VAR_INT, gameMode.ordinal()); // game mode
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
        // Delayed START-only blocks must not share a tick with StartDestroy. MOT 860
        // EnumMap iterates Predict before Continue, so Continue after Predict restarts mining.
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
        // MOT PredictDestroy already aborts then completes; StopDestroy also aborts.
        // A same-tick Abort(face=0) is redundant and can leave lastBlockAction=Abort.
        // Ref: MOT Player.java PREDICT_DESTROY_BLOCK / STOP_DESTROY_BLOCK.
        if (!gameSession.isBlockBreakingServerAuthoritative()) {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StopDestroyBlock));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.CrackBlock, position, direction.ordinal()));
        } else {
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.ContinueDestroyBlock, position, direction.ordinal()));
            clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.PredictDestroyBlock, position, direction.ordinal()));
        }

        chunkTracker.handleBlockChange(position, 0, chunkTracker.bedrockAirId());
        PacketFactory.sendJavaBlockUpdate(user, position, ProtocolConstants.JAVA_AIR_ID);
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.RESPAWN, ClientboundPackets26_1.RESPAWN, wrapper -> {
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            final byte rawState = wrapper.read(Types.BYTE); // state
            final PlayerRespawnState state = PlayerRespawnState.getByValue(rawState);
            if (state == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayerRespawnState: " + rawState);
                wrapper.cancel();
                return;
            }
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final ClientPlayerEntity respawningPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            if (respawningPlayer != null) {
                respawningPlayer.deferredEntityActions().clear();
                respawningPlayer.setUsingItem(false);
                ExperimentalFeatures.restorePromotedOffhand(wrapper.user(), respawningPlayer);
            }

            switch (state) {
                case ReadyToSpawn -> {
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
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
                        wrapper.write(Types.VAR_INT, chunkTracker.getDimension().ordinal()); // dimension id
                        wrapper.write(Types.STRING, chunkTracker.getDimensionKey()); // dimension name
                        wrapper.write(Types.LONG, 0L); // hashed seed
                        final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
                        wrapper.write(Types.BYTE, (byte) spectatorCamera.projectJavaGameMode(clientPlayer.javaGameMode()).ordinal()); // game mode
                        wrapper.write(Types.BYTE, (byte) -1); // previous game mode
                        wrapper.write(Types.BOOLEAN, false); // is debug
                        wrapper.write(Types.BOOLEAN, gameSession.isFlatGenerator()); // is flat
                        wrapper.write(Types.OPTIONAL_GLOBAL_POSITION, null); // last death position
                        wrapper.write(Types.VAR_INT, 0); // portal cooldown
                        wrapper.write(Types.VAR_INT, 64); // sea level
                        wrapper.write(Types.BYTE, (byte) (RespawnKeepFlag.ATTRIBUTE_MODIFIERS.getBit() | RespawnKeepFlag.ENTITY_DATA.getBit())); // keep data mask
                        PacketLeftoverLayout.discardUnreadInput(wrapper);
                        wrapper.send(BedrockProtocol.class);
                        clientPlayer.sendAttribute("minecraft:health"); // Ensure health is synced
                        wrapper.user().get(PlayerArmorHudTracker.class).forceSync();
                        chunkTracker.resetJavaChunkLoading();
                        clientPlayer.setDimensionChangeInfo(null);
                        PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer()); // Java client always resets inventory on respawn. Resend it
                        inventoryTracker.getInventoryContainer().sendSelectedHotbarSlotToClient(); // Java client always resets selected hotbar slot on respawn. Resend it
                        spectatorCamera.restorePresentationAfterClientReset();
                    }
                    wrapper.cancel();

                    clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
                }
                case SearchingForSpawn -> {
                    wrapper.cancel();
                    PacketLeftoverLayout.discardUnreadInput(wrapper);
                    // MOT Player.kill() always sends SEARCHING. Combined with
                    // DeathInfo it is the death signal; SHOW_DEATH_MESSAGES off
                    // skips DeathInfo, so we still have to mark the Java player
                    // dead here. Immediate-respawn then overlays SET_HEALTH.
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
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS, null, wrapper -> {
            wrapper.cancel();
            final UpdateClientInputLocksLayout.DecodedLocks locks = UpdateClientInputLocksLayout.read(wrapper);
            PacketLeftoverLayout.discardUnreadInput(wrapper);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            if (clientPlayer == null) {
                return;
            }
            final boolean wasLocked = clientPlayer.isInputMovementLocked();
            clientPlayer.setInputMovementLocked(locks.movementLocked());
            if (locks.movementLocked()) {
                if (locks.serverPosition() != null) {
                    clientPlayer.setPosition(locks.serverPosition());
                }
                if (!wasLocked) {
                    clientPlayer.beginPositionSync(Relative.ROTATION);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.PLAYER_ACTION, null, wrapper -> {
            wrapper.cancel();
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // entity runtime id
            final int rawAction = wrapper.read(BedrockTypes.VAR_INT); // action
            final PlayerActionType action = PlayerActionType.getByValue(rawAction);
            if (action == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown PlayerActionType: " + rawAction);
                return;
            }
            wrapper.read(BedrockTypes.BLOCK_POSITION); // block position
            wrapper.read(BedrockTypes.BLOCK_POSITION); // result position
            wrapper.read(BedrockTypes.VAR_INT); // face

            if (action == PlayerActionType.ChangeDimensionAck) {
                final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                if (clientPlayer.dimensionChangeInfo() != null) {
                    clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.ChangeDimensionAck);
                    PacketFactory.sendBedrockLoadingScreen(wrapper.user(), ServerboundLoadingScreenPacketType.EndLoadingScreen, clientPlayer.dimensionChangeInfo().loadingScreenId());
                    clientPlayer.sendPlayerPositionPacketToClient(Relative.NONE);
                    clientPlayer.setDimensionChangeInfo(null);
                }
            }
        });
        protocol.registerClientbound(ClientboundBedrockPackets.CORRECT_PLAYER_MOVE_PREDICTION, ClientboundPackets26_1.PLAYER_POSITION, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);

            final byte rawRewindType = wrapper.read(Types.BYTE); // rewind type
            final RewindType rewindType = RewindType.getByValue(rawRewindType);
            if (rewindType == null) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Unknown RewindType: " + rawRewindType);
                return;
            }
            final Position3f position = wrapper.read(BedrockTypes.POSITION_3F); // position
            wrapper.read(BedrockTypes.POSITION_3F); // position delta
            wrapper.read(BedrockTypes.POSITION_2F); // vehicle rotation
            if (wrapper.read(Types.BOOLEAN)) {
                wrapper.read(BedrockTypes.FLOAT_LE); // vehicle angular velocity
            }
            switch (rewindType) {
                case Player -> {
                    final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
                    final boolean onGround = wrapper.read(Types.BOOLEAN); // on ground
                    final long tick = wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick
                    if (tick > clientPlayer.age() || tick < clientPlayer.age() - gameSession.getMovementRewindHistorySize()) {
                        wrapper.cancel();
                        return;
                    }

                    clientPlayer.setPosition(position);
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
                    wrapper.user().get(EntityTracker.class).getClientPlayer().setGameType(GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT))); // game type
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
                    wrapper.user().get(GameSessionStorage.class).setLevelGameType(GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT))); // game type
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

            final GameType gameType = GameTypeRewriter.fromWire(wrapper.read(BedrockTypes.VAR_INT)); // game type
            final long entityUniqueId = wrapper.read(BedrockTypes.VAR_LONG); // entity unique id
            wrapper.read(BedrockTypes.UNSIGNED_VAR_LONG); // tick

            final Pair<UUID, String> playerListEntry = playerList.getPlayer(entityUniqueId);
            if (playerListEntry == null) {
                wrapper.cancel();
                return;
            }

            wrapper.write(Types.PROFILE_ACTIONS_ENUM1_21_4, BitSets.create(8, PlayerInfoUpdateAction.UPDATE_GAME_MODE)); // actions
            wrapper.write(Types.VAR_INT, 1); // length
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
            wrapper.write(Types.VAR_INT, javaGameMode.ordinal()); // game mode
        });
        protocol.registerClientbound(ClientboundBedrockPackets.UPDATE_ADVENTURE_SETTINGS, null, wrapper -> {
            wrapper.cancel();
            wrapper.read(Types.BOOLEAN); // no player vs mobs
            wrapper.read(Types.BOOLEAN); // no mobs vs player
            wrapper.user().get(GameSessionStorage.class).setImmutableWorld(wrapper.read(Types.BOOLEAN)); // immutable world
            wrapper.read(Types.BOOLEAN); // show name tags
            wrapper.read(Types.BOOLEAN); // auto jump
        });
        protocol.registerClientbound(ClientboundBedrockPackets.OPEN_SIGN, ClientboundPackets26_1.OPEN_SIGN_EDITOR, wrapper -> {
            // MOT OpenSignPacket.encode() is getBlockVector3() + boolean. The mapped
            // Java OPEN_SIGN_EDITOR has no trailer; leftover Bedrock bytes kick 1.21.11.
            wrapper.write(Types.BLOCK_POSITION1_14, wrapper.read(BedrockTypes.BLOCK_POSITION)); // position
            wrapper.write(Types.BOOLEAN, wrapper.read(Types.BOOLEAN)); // front
            PacketLeftoverLayout.discardUnreadInput(wrapper);
        });

        protocol.registerServerbound(ServerboundPackets26_1.CLIENT_COMMAND, ServerboundBedrockPackets.RESPAWN, wrapper -> {
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final ClientCommandAction action = ClientCommandAction.values()[wrapper.read(Types.VAR_INT)]; // action

            switch (action) {
                case PERFORM_RESPAWN -> {
                    wrapper.write(BedrockTypes.POSITION_3F, Position3f.ZERO); // position
                    wrapper.write(Types.BYTE, (byte) PlayerRespawnState.ClientReadyToSpawn.getValue()); // state
                    wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // entity runtime id
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
            wrapper.read(Types.VAR_INT); // entity id
            final PlayerCommandAction action = PlayerCommandAction.values()[wrapper.read(Types.VAR_INT)]; // action
            final int data = wrapper.read(Types.VAR_INT); // data
            if (action == PlayerCommandAction.STOP_SLEEPING) {
                // Java leave-bed is PLAYER_COMMAND. MOT 860 stopSleep() is PlayerAction 6.
                // Ref: MOT Player.java case 6 / PlayerActionPacket.ACTION_STOP_SLEEPING.
                clientPlayer.sendPlayerActionPacketToServer(PlayerActionType.StopSleeping);
                return;
            }
            final PlayerAuthInputPacket_InputData inputData = playerCommandInputData(action);
            if (inputData == null) {
                return;
            }

            if (action == PlayerCommandAction.START_SPRINTING) {
                // Nukkit START_SPRINTING calls setUsingItem(false). Keep eating from being cancelled
                // by a Java sprint that started after the use animation began.
                if (ItemUseSemantics.suppressStartSprintingWhileUsingItem(ViaBedrock.getConfig().shouldEmulateNetEaseClient(), clientPlayer.isUsingItem())) {
                    return;
                }
                clientPlayer.setSprinting(true);
            } else if (action == PlayerCommandAction.STOP_SPRINTING) {
                clientPlayer.setSprinting(false);
            } else if (action == PlayerCommandAction.START_FALL_FLYING) {
                // Do not emit StartGliding on this packet. Java often still reports
                // onGround; GanAC ElytraF would suppress it. Emit on the first
                // airborne AuthInput instead (applyJavaGlideStart).
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
            final PlayerActionAction action = PlayerActionAction.values()[wrapper.read(Types.VAR_INT)]; // action
            final BlockPosition position = wrapper.read(Types.BLOCK_POSITION1_14); // block position
            final Direction direction = Direction.values()[wrapper.read(Types.UNSIGNED_BYTE)]; // face
            final int sequence = wrapper.read(Types.VAR_INT); // sequence number

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
                // TODO: Prevent breaking and cancel any packets that would be sent (swing, player action)
                final int rawBlockState = chunkTracker.getJavaBlockState(position);
                final BlockNeighborView view = new TrackerNeighborView(chunkTracker);
                final int fixedBlockState = BedrockProtocol.MAPPINGS.getNeighborRewriter().resolveUpdate(view, position, rawBlockState).getOrDefault(position, rawBlockState);
                PacketFactory.sendJavaBlockUpdate(wrapper.user(), position, fixedBlockState);
                PacketFactory.sendJavaBlockChangedAck(wrapper.user(), sequence);
                return;
            }

            // TODO: Block breaking: Send correct inventory transactions

            switch (action) {
                case START_DESTROY_BLOCK -> {
                    clientPlayer.sendSwingPacketToServer();
                    clientPlayer.cancelNextSwingPacket();
                    // Creative and hardness-0 blocks: Java only sends START_DESTROY_BLOCK.
                    // TODO: Test breaking fire
                    // TODO: The java client keeps spamming swing packets while waiting for the block break cooldown. Those need to be cancelled

                    clientPlayer.addAuthInputBlockAction(new ClientPlayerEntity.AuthInputBlockAction(PlayerActionType.StartDestroyBlock, position, direction.ordinal()));
                    if (isInstantBreak(wrapper.user(), chunkTracker, position)) {
                        // Instant-break cases (creative, 0 destroy time, shears on leaves): Java only
                        // sends START_DESTROY_BLOCK and never a STOP. MOT SAI ignores CreativeDestroyBlock(13)
                        // and only breaks on PredictDestroyBlock, so finish in this same tick.
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
                    // ExperimentalFeatures prepend owns this when experimental inventory is on.
                    // Keep a resync fallback for the official non-experimental path.
                    PacketFactory.sendJavaContainerSetContent(wrapper.user(), wrapper.user().get(InventoryTracker.class).getInventoryContainer());
                }
                case RELEASE_USE_ITEM -> {
                    // ExperimentalFeatures owns the Bedrock finish/cancel translation. Resyncing
                    // here would overwrite Nukkit's just-consumed stack with the pre-eat snapshot.
                }
                case SWAP_ITEM_WITH_OFFHAND -> {
                    if (!ExperimentalFeatures.tryHandleSwapHands(wrapper.user())) {
                        PacketFactory.sendJavaContainerSetContent(wrapper.user(), wrapper.user().get(InventoryTracker.class).getInventoryContainer());
                    }
                }
                case STAB -> {
                    // Java riptide is PLAYER_ACTION.STAB. MOT 860 only starts spin from
                    // AuthInput START_SPIN_ATTACK (protocol >= 748). Wire bit 58 still fits
                    // the NetEase 860 64-bit mask (ordinal 56 + 2 extra flags).
                    // Ref: MOT Player.java AuthInputAction.START_SPIN_ATTACK -> onSpinAttack.
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
            final int entityId = wrapper.read(Types.VAR_INT); // entity id
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
                // The Java client emits a separate SWING after ATTACK. Suppress it while the
                // promoted layout is active; the deferred sender emits one after confirmation.
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
            final int entityId = wrapper.read(Types.VAR_INT); // entity id
            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // hand
            final Entity entity = entityTracker.getEntityByJid(entityId);
            if (entity == null) {
                final ClientPlayerEntity clientPlayer = entityTracker.getClientPlayer();
                if (!clientPlayer.canProcessHandSensitiveAction(hand, false)) {
                    ExperimentalFeatures.retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
                    wrapper.cancel();
                    return;
                }
                // Item frames and custom-block overlays are fake Java entities over real Bedrock blocks.
                // Translate the right-click into the block interaction the server expects.
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
            final Vector3d location = wrapper.read(Types.LOW_PRECISION_VECTOR); // location
            final Position3f clickPosition = entity.position().add(
                    (float) location.x(), (float) location.y(), (float) location.z());
            wrapper.read(Types.BOOLEAN); // using secondary action

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

            // MOT transaction type 3 equips hotbarSlot, compares itemInHand with
            // getItemInHand(), and passes that main-hand stack to Entity.onInteract.
            final ItemUseHandContext handContext = ExperimentalFeatures.motContextForEntityInteraction(
                    wrapper.user(), clientPlayer, hand);
            if (handContext == null) {
                wrapper.cancel();
                final InventoryTracker inventoryTracker = wrapper.user().get(InventoryTracker.class);
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getInventoryContainer());
                PacketFactory.sendJavaContainerSetContent(wrapper.user(), inventoryTracker.getOffhandContainer());
                return;
            }

            // TODO: Bedrock client sends INTERACT packet when hovered entity changes. Might be used by anticheats
            EntityInteractionPacketSender.write(wrapper, entity.runtimeId(),
                    ItemUseOnActorInventoryTransaction_ActionType.Interact.getValue(),
                    handContext.entityTransactionHotbarSlot(wrapper.user().get(InventoryTracker.class)
                            .getInventoryContainer().getSelectedHotbarSlot()),
                    handContext.item(), clientPlayer.position(), clickPosition);

            if (hand == InteractionHand.OFF_HAND && clientPlayer.isOffhandPromoted()) {
                // Preserve wire order: promote swap, type-3 interaction, restore swap.
                wrapper.sendToServer(BedrockProtocol.class);
                wrapper.cancel();
                ExperimentalFeatures.restorePromotedOffhand(wrapper.user(), clientPlayer);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_STATUS_ONLY, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_POS, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_POS_ROT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), wrapper.read(Types.DOUBLE), MathUtil.wrapDegrees(wrapper.read(Types.FLOAT)), wrapper.read(Types.FLOAT), wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.MOVE_PLAYER_ROT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.updatePlayerPosition(MathUtil.wrapDegrees(wrapper.read(Types.FLOAT)), wrapper.read(Types.FLOAT), wrapper.read(Types.UNSIGNED_BYTE));
        });
        protocol.registerServerbound(ServerboundPackets26_1.ACCEPT_TELEPORTATION, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            clientPlayer.confirmTeleport(wrapper.read(Types.VAR_INT)); // teleport id
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_INPUT, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final Set<InputFlag> inputFlags = EnumUtil.getEnumSetFromBitmask(InputFlag.class, wrapper.read(Types.BYTE), InputFlag::ordinal); // input flags
            final SpectatorCameraTracker spectatorCamera = wrapper.user().get(SpectatorCameraTracker.class);
            if (spectatorCamera.handleShiftInput(inputFlags.contains(InputFlag.SHIFT))) {
                inputFlags.remove(InputFlag.SHIFT);
                clientPlayer.setSneaking(false);
            }
            clientPlayer.setInputFlags(inputFlags);
        });
        protocol.registerServerbound(ServerboundPackets26_1.CLIENT_TICK_END, ServerboundBedrockPackets.PLAYER_AUTH_INPUT, wrapper -> {
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final Position3f prevPosition = clientPlayer.prevPosition();
            final boolean prevOnGround = clientPlayer.prevOnGround();
            final Set<InputFlag> prevInputFlags = clientPlayer.prevInputFlags();
            clientPlayer.tick();
            clientPlayer.deferredEntityActions().discardExpired(clientPlayer.age());
            final boolean immobile = clientPlayer.isInputMovementLocked() || clientPlayer.hasEntityFlag(ActorFlags.NOAI);

            // MOT PlayerJumpEvent is START_JUMPING only. Emitting it every grounded
            // tick while JUMP is held makes GanAC AirJump see a second jump after MOT
            // is already airborne. One pulse per press or landing is enough.
            // Ref: MOT Player.java START_JUMPING; GanAC AirJumpCheck.
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
                // MOT unused PersistSneak: NukkitMOTJE ShieldSneakListener restores
                // standing AABB for Java shield-as-sneak without shrinking to 1.49.
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
            if (immobile) {
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
                // Slow falling does not change the velocity when standing still

                velocity = new Position3f(dx * 0.91F, dy * 0.98F, dz * 0.91F);
            }

            final PlayerAuthInputContext authInputContext = new PlayerAuthInputContext(clientPlayer.position(), velocity);
            ExperimentalFeatures.dispatchPlayerAuthInput(wrapper.user(), clientPlayer, authInputContext);
            if (immobile) {
                removeImmobileMovementInput(clientPlayer.authInputData());
                authInputContext.setPosition(clientPlayer.position());
                authInputContext.setDelta(Position3f.ZERO);
            }

            // MOT EnumMap iterates START then ABORT. Coalesce before the bitmask so
            // an emptied action list can drop PerformBlockActions on the wire.
            final List<ClientPlayerEntity.AuthInputBlockAction> blockActions =
                    coalesceAuthInputBlockActions(clientPlayer.authInputBlockActions());
            if (blockActions.isEmpty()) {
                clientPlayer.authInputData().remove(PlayerAuthInputPacket_InputData.PerformBlockActions);
            }

            // wrapDegrees(180)==-180. Write the wrapped heading so GanAC BadPacketB
            // and MOT AuthInput stay in (-180, 180]. Java yRot can be +180.
            // Ref: MOT PlayerAuthInputPacket.decode() LFloat yaw; GanAC MathUtil.wrapDegrees.
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().x()); // pitch
            wrapper.write(BedrockTypes.FLOAT_LE, MathUtil.wrapDegrees(clientPlayer.rotation().y())); // yaw
            wrapper.write(BedrockTypes.POSITION_3F, authInputContext.position()); // position
            wrapper.write(BedrockTypes.POSITION_2F, immobile ? new Position2f(0F, 0F) : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), clientPlayer.isSneaking())); // move vector
            wrapper.write(BedrockTypes.FLOAT_LE, MathUtil.wrapDegrees(clientPlayer.rotation().z())); // head yaw
            wrapper.write(BedrockTypes.UNSIGNED_VAR_BIG_INTEGER, PlayerAuthInputLayout.encodeBitmask(clientPlayer.authInputData())); // input flags
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, InputMode.Mouse.getValue()); // input mode
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, ClientPlayMode.Screen.getValue()); // play mode
            wrapper.write(BedrockTypes.UNSIGNED_VAR_INT, NewInteractionModel.Crosshair.getValue()); // interaction mode
            wrapper.write(BedrockTypes.FLOAT_LE, clientPlayer.rotation().x()); // interact pitch
            wrapper.write(BedrockTypes.FLOAT_LE, MathUtil.wrapDegrees(clientPlayer.rotation().y())); // interact yaw
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, (long) clientPlayer.age()); // tick
            wrapper.write(BedrockTypes.POSITION_3F, authInputContext.delta()); // delta
            if (PlayerAuthInputLayout.usesCameraDeparted()) {
                wrapper.write(Types.BOOLEAN, false); // camera departed (NetEase >= 422)
            }
            if (clientPlayer.authInputData().contains(PlayerAuthInputPacket_InputData.PerformItemInteraction)) {
                final BedrockInventoryTransaction itemInteraction = clientPlayer.authInputItemInteraction();
                if (itemInteraction != null) {
                    wrapper.write(wrapper.user().get(InventoryTransactionRewriter.class).getItemInteractionDataType(), itemInteraction);
                }
            }
            if (clientPlayer.authInputData().contains(PlayerAuthInputPacket_InputData.PerformBlockActions)) {
                wrapper.write(BedrockTypes.VAR_INT, blockActions.size()); // player block actions count
                for (ClientPlayerEntity.AuthInputBlockAction blockAction : blockActions) {
                    wrapper.write(BedrockTypes.VAR_INT, blockAction.action().getValue()); // action
                    switch (blockAction.action()) {
                        // StopDestroyBlock does not have additional data even tho bedrock protocol docs claim it does
                        case StartDestroyBlock, AbortDestroyBlock, CrackBlock, PredictDestroyBlock, ContinueDestroyBlock -> {
                            wrapper.write(BedrockTypes.SIGNED_BLOCK_POSITION, blockAction.position()); // signed position
                            wrapper.write(BedrockTypes.VAR_INT, blockAction.direction()); // facing
                        }
                    }
                }
            }
            if (authInputContext.hasPredictedVehicle()) {
                wrapper.write(BedrockTypes.FLOAT_LE, authInputContext.vehiclePitch()); // vehicle pitch
                wrapper.write(BedrockTypes.FLOAT_LE, authInputContext.vehicleYaw()); // vehicle yaw
                wrapper.write(BedrockTypes.VAR_LONG, authInputContext.predictedVehicleUniqueId()); // predicted vehicle entity unique id
            }
            final Position2f analogMoveVector = immobile
                    ? new Position2f(0F, 0F)
                    : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), clientPlayer.isSneaking());
            final Position2f rawMoveVector = immobile
                    ? new Position2f(0F, 0F)
                    : MathUtil.calculateMovementDirections(clientPlayer.authInputData(), false);
            wrapper.write(BedrockTypes.POSITION_2F, analogMoveVector); // analog move vector
            wrapper.write(BedrockTypes.POSITION_3F, MathUtil.calculateCameraOrientation(clientPlayer.rotation().y(), clientPlayer.rotation().x())); // camera orientation
            wrapper.write(BedrockTypes.POSITION_2F, rawMoveVector); // raw move vector

            clientPlayer.authInputData().clear();
            clientPlayer.authInputBlockActions().clear();
            clientPlayer.clearAuthInputItemInteraction();
        });
        protocol.registerServerbound(ServerboundPackets26_1.PLAYER_ABILITIES, null, wrapper -> {
            wrapper.cancel();
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final byte flags = wrapper.read(Types.BYTE); // flags
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
                    final GameMode gameMode = GameMode.values()[wrapper.read(Types.VAR_INT)]; // game mode
                    final GameType gameType = switch (gameMode) {
                        case SURVIVAL -> GameType.Survival;
                        case CREATIVE -> GameType.Creative;
                        case ADVENTURE -> GameType.Adventure;
                        case SPECTATOR -> GameType.Spectator;
                        default -> throw new IllegalStateException("Unhandled GameMode: " + gameMode);
                    };
                    wrapper.write(BedrockTypes.VAR_INT, gameType.getValue()); // game type
                    wrapper.user().get(EntityTracker.class).getClientPlayer().setGameType(gameType);
                });
                handler(CLIENT_PLAYER_GAME_MODE_INFO_UPDATE);
                handler(CLIENT_PLAYER_GAME_MODE_UPDATE);
            }
        });
        protocol.registerServerbound(ServerboundPackets26_1.SWING, ServerboundBedrockPackets.ANIMATE, wrapper -> {
            final GameSessionStorage gameSession = wrapper.user().get(GameSessionStorage.class);
            final ClientPlayerEntity clientPlayer = wrapper.user().get(EntityTracker.class).getClientPlayer();
            final InteractionHand hand = InteractionHand.values()[wrapper.read(Types.VAR_INT)]; // hand
            final boolean cancelSwing = clientPlayer.checkCancelSwingPacket();
            if (hand != InteractionHand.MAIN_HAND || cancelSwing || clientPlayer.isOffhandPromoted()) {
                wrapper.cancel();
                if (clientPlayer.isOffhandPromoted()) {
                    ExperimentalFeatures.retryPromotedOffhandRestore(wrapper.user(), clientPlayer);
                }
                return;
            }

            EntityPacketLayout.writeAnimateAction(wrapper, AnimatePacketPayload_Action.Swing.getValue()); // action
            wrapper.write(BedrockTypes.UNSIGNED_VAR_LONG, clientPlayer.runtimeId()); // entity runtime id
            wrapper.write(BedrockTypes.FLOAT_LE, 0F); // data
            EntityPacketLayout.writeAnimateTrailer(wrapper, ActorSwingSource.Attack.name().toLowerCase(Locale.ROOT)); // swing source (897+)

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
     * AuthInput flags and block actions are queued across Java packets in the same tick
     * and only cleared after a successful PLAYER_AUTH_INPUT write. Cancelling
     * CLIENT_TICK_END (not spawned / dead) must still drop those leftovers or the next
     * live AuthInput would replay START_JUMPING / PERFORM_BLOCK_ACTIONS.
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
     * MOT 860 stores AuthInput block actions in {@code EnumMap<PlayerActionType,>} and
     * iterates declaration order ({@code START, ABORT, STOP, ..., PREDICT, CONTINUE}).
     * A same-tick Java retarget of Abort(old)+Start(new) therefore runs START then ABORT
     * and cancels the new break. Native Bedrock does not put Abort+Start in one AuthInput;
     * MOT already aborts the previous target when the next action's position differs.
     * Same-tick Start+Continue+Predict is kept: MOT EnumMap order is START then
     * PREDICT then CONTINUE, which starts then finishes the same block.
     * Ref: MOT Player.java SAI loop; PlayerAuthInputPacket.decodeBlockActions.
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
        // MOT writes SET_LEVEL_GRAVITY as a signed acceleration (join reset = -0.08).
        // PLAYER_AUTH_INPUT already subtracts a positive gravity constant, so take abs.
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
     * MOT fires {@code PlayerJumpEvent} on {@code START_JUMPING}. Vanilla Bedrock pulses
     * that bit on the first airborne tick of a jump, not every grounded tick JUMP is held.
     * Java can keep JUMP down across landings, so emit on a new press while grounded or
     * on the first grounded tick after a landing with JUMP still held.
     */
    static boolean shouldEmitStartJumping(final boolean prevOnGround, final boolean onGround,
                                          final boolean jumpHeld, final boolean prevJumpHeld) {
        return shouldEmitStartJumping(prevOnGround, onGround, jumpHeld, prevJumpHeld, false);
    }

    /**
     * Riding JUMP is Java START_RIDING_JUMP, not a player jump. MOT 860 never
     * applies horse jump from START_JUMPING, and GanAC AirJump scores that bit
     * while the passenger is airborne.
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
     * MOT abort particles use BlockFace.fromIndex(facing). Prefer the face from this
     * abort packet, then the cached START/CONTINUE face, then DOWN (MOT itself uses
     * DOWN when aborting because the target block changed).
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
     * Java never emits Bedrock {@code START/STOP_SWIMMING} or {@code STOP_GLIDING}. MOT 860 and
     * GanAC only enter the swimming/gliding pose from those AuthInput flags, so translate the
     * Java sprint-in-water and land/water/unequip/vehicle glide-stop edges here.
     * Java jump-to-cancel is not synthesized: fireworks keep JUMP held while gliding.
     * Ref: MOT Player.java START_GLIDING/STOP_GLIDING and onGround/chestplate clear.
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
     * MOT SAI STOP_SPIN_ATTACK is what clears DATA_FLAG_SPIN_ATTACK. riptideTicks
     * only skips speed checks and never drops the flag. Pulse Stop after MOT
     * duration {@code 50+(level<<5)}.
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
     * Java auto-crawls in a 1-block gap. MOT 860 only enters crawl from AuthInput
     * START_CRAWLING. Infer when VBU never reports pose: sneak + onGround + a
     * solid 1.5 blocks above the feet (standing AABB would collide).
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

    static void applyJavaSwimTransition(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        final PlayerAuthInputPacket_InputData flag = swimTransitionFlag(wantsJavaSwim(user, clientPlayer), clientPlayer.isSwimming());
        if (flag == PlayerAuthInputPacket_InputData.StartSwimming) {
            clientPlayer.setSwimming(true);
            clientPlayer.addAuthInputData(flag);
        } else if (flag == PlayerAuthInputPacket_InputData.StopSwimming) {
            clientPlayer.setSwimming(false);
            clientPlayer.addAuthInputData(flag);
        }
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

    static PlayerAuthInputPacket_InputData swimTransitionFlag(final boolean wantSwim, final boolean currentlySwimming) {
        if (wantSwim && !currentlySwimming) {
            return PlayerAuthInputPacket_InputData.StartSwimming;
        }
        if (!wantSwim && currentlySwimming) {
            return PlayerAuthInputPacket_InputData.StopSwimming;
        }
        return null;
    }

    static boolean shouldStopGliding(final boolean gliding, final boolean onGround) {
        return shouldStopGliding(gliding, onGround, false, false, true);
    }

    /**
     * MOT auto-clears GLIDING on land, missing/broken chestplate, or vehicle. Java water
     * also cancels fall-flying; jump-to-cancel is omitted so firework boosts stay gliding.
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
     * MOT {@code Entity#isInsideOfWater()} checks the feet block on layer 0, then layer 1
     * waterlogging. ClientPlayerEntity.position() is the eye, so subtract {@code eyeOffset()}.
     * {@link ChunkTracker#getBlockState(int, BlockPosition)} returns air when the column is
     * missing, which would invert a swim edge; keep the last in-water sample until the feet
     * chunk is loaded.
     */
    static boolean isInsideOfWater(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        return keepLastInsideOfWater(
                knownInsideOfWater(user, clientPlayer),
                clientPlayer != null && clientPlayer.isSwimming()
        );
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
     * {@code null} means the feet column is not loaded yet. A loaded column with no section
     * (Y out of world) is dry air, matching {@link ChunkTracker#getBlockState(int, BlockPosition)}.
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
     * {@code null} keeps the last in-water sample. A loaded column whose Y is outside the
     * world is dry; a missing in-world section is still pending and must not look like air.
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
     * Java ATTACK is entity-id only. MOT combat does not consume clickPos; emit the current look
     * direction so the field is a unit vector instead of ZERO.
     */
    static Position3f attackClickPosition(final ClientPlayerEntity clientPlayer) {
        if (clientPlayer == null || clientPlayer.rotation() == null) {
            return Position3f.ZERO;
        }
        return MathUtil.calculateCameraOrientation(clientPlayer.rotation().y(), clientPlayer.rotation().x());
    }
}
