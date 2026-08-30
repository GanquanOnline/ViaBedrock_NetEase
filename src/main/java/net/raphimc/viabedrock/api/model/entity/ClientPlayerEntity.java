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

import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.BlockPosition;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.util.Pair;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.util.EnumUtil;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.storage.ClientSettingsStorage;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.*;
import net.raphimc.viabedrock.protocol.data.enums.java.*;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.GameMode;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.EntityAttribute;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.GameTypeRewriter;
import net.raphimc.viabedrock.protocol.storage.ChunkTracker;
import net.raphimc.viabedrock.protocol.storage.CommandsStorage;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.packet.ClientPlayerPackets;
import net.raphimc.viabedrock.protocol.packet.EntityPacketLayout;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class ClientPlayerEntity extends PlayerEntity {

    private final AtomicInteger TELEPORT_ID = new AtomicInteger(1);
    private final GameSessionStorage gameSession;

    // Initial spawn and respawning
    private boolean initiallySpawned;
    private DimensionChangeInfo dimensionChangeInfo;
    private boolean wasInsideUnloadedChunk;

    // Position syncing
    private int pendingTeleportId;
    private boolean waitingForPositionSync;
    // Teleport id of the position-sync packet we are currently waiting a client confirm for (0 = none).
    // Kept separate from pendingTeleportId, which is overwritten by EVERY outgoing position packet, so the
    // sync can still be matched/cleared if another server-driven position packet bumps pendingTeleportId
    // before the sync confirm arrives. See beginPositionSync() / confirmTeleport().
    private int positionSyncTeleportId;
    private boolean serverSideTeleportConfirmed;

    // Movement watchdog (fail-safe for a backend that never sends the unlock packet after a switch).
    // -1 = inactive; ages are compared against Entity.age (incremented once per client tick).
    private int dimensionChangeStartAge = -1;
    private int chunkStuckStartAge = -1;
    private int lastChunkRadiusRequestAge = -1;

    // Server Authoritative Movement
    private Position3f prevPosition;
    private boolean prevOnGround;
    private final Set<PlayerAuthInputPacket_InputData> authInputData = EnumSet.noneOf(PlayerAuthInputPacket_InputData.class);
    private final List<AuthInputBlockAction> authInputBlockActions = new ArrayList<>();
    private BedrockInventoryTransaction authInputItemInteraction;
    private Set<InputFlag> inputFlags = EnumSet.noneOf(InputFlag.class);
    private Set<InputFlag> prevInputFlags = EnumSet.noneOf(InputFlag.class);
    private boolean horizontalCollision;
    private boolean sneaking;
    private boolean sprinting;
    // Java never sends Bedrock START/STOP_SWIMMING or STOP_GLIDING. Track the
    // translated pose so AuthInput can emit the one-tick MOT flags GanAC needs.
    private boolean swimming;
    private boolean gliding;
    // Java START_FALL_FLYING can arrive while onGround is still true. MOT/GanAC
    // ElytraF suppress StartGliding on that tick, so hold it until airborne.
    private boolean pendingStartGliding;
    // MOT UPDATE_CLIENT_INPUT_LOCKS bit 4. Protocol 560+ no longer sets ActorFlags.NOAI
    // for movement locks, so Java would otherwise keep walking while the backend is frozen.
    private boolean inputMovementLocked;
    // MOT SAI STOP_SPIN_ATTACK is the only path that clears DATA_FLAG_SPIN_ATTACK.
    // Java never sends a stop pose; pulse Stop after MOT duration 50+(level<<5).
    private boolean riptideSpinning;
    private int riptideSpinStartAge = -1;
    private int riptideSpinDurationTicks;

    // Misc data
    private GameType gameType;
    private GameMode javaGameMode;
    private boolean cancelNextSwingPacket;
    private BlockBreakingInfo blockBreakingInfo;
    private DelayedMotBreak delayedMotBreak;
    private boolean usingItem;
    private int usingItemStartAge = -1;
    private ItemUseSnapshot itemUseSnapshot;
    private boolean crossbowChargeFinishSent;
    private boolean consumableFinishSent;
    private boolean shieldSneakEmulated;
    private final OffhandPromotionState offhandPromotionState = new OffhandPromotionState();
    private final OffhandRestoreIdentity offhandRestoreIdentity = new OffhandRestoreIdentity();
    private final DeferredEntityActionQueue deferredEntityActions = new DeferredEntityActionQueue();
    private int lastUseOnAge = -1;
    private int crossbowChargeFinishAge = -1;

    // The UUID the Bedrock server assigned to the local player in the player list. It differs from
    // javaUuid (see HudPackets PLAYER_LIST remap), and is the UUID the server uses to address the
    // local player in PLAYER_SKIN. Captured in HudPackets so PLAYER_SKIN can remap it to javaUuid.
    private UUID bedrockUuid;

    public ClientPlayerEntity(final UserConnection user, final long runtimeId, final UUID javaUuid, final PlayerAbilities abilities) {
        super(user, runtimeId, 0, javaUuid, abilities);
        this.attributes.put("minecraft:movement", new EntityAttribute("minecraft:movement", 0.7F, 0F, Float.MAX_VALUE));
        this.attributes.put("minecraft:player.hunger", new EntityAttribute("minecraft:player.hunger", 20F, 0F, 20F));
        this.attributes.put("minecraft:player.saturation", new EntityAttribute("minecraft:player.saturation", 5F, 0F, 20F));
        this.attributes.put("minecraft:player.experience", new EntityAttribute("minecraft:player.experience", 0F, 0F, 1F));
        this.attributes.put("minecraft:player.level", new EntityAttribute("minecraft:player.level", 0F, 0F, 24791F));

        this.gameSession = user.get(GameSessionStorage.class);
    }

    @Override
    public void tick() {
        super.tick();

        this.prevPosition = this.position;
        this.prevOnGround = this.onGround;
        this.prevInputFlags = this.inputFlags;

        this.movementWatchdog();
    }

    public UUID bedrockUuid() {
        return this.bedrockUuid;
    }

    public void setBedrockUuid(final UUID bedrockUuid) {
        this.bedrockUuid = bedrockUuid;
    }

    public void sendPlayerPositionPacketToClient(final Set<Relative> relatives) {
        final PacketWrapper playerPosition = PacketWrapper.create(ClientboundPackets26_1.PLAYER_POSITION, this.user);
        this.writePlayerPositionPacketToClient(playerPosition, relatives, true);
        playerPosition.send(BedrockProtocol.class);
    }

    public void writePlayerPositionPacketToClient(final PacketWrapper wrapper, final Set<Relative> relatives, final boolean fakeTeleport) {
        this.pendingTeleportId = TELEPORT_ID.getAndIncrement();

        wrapper.write(Types.VAR_INT, this.pendingTeleportId * (fakeTeleport ? -1 : 1)); // teleport id
        wrapper.write(Types.DOUBLE, relatives.contains(Relative.X) ? 0D : (double) this.position.x()); // x
        wrapper.write(Types.DOUBLE, relatives.contains(Relative.Y) ? 0D : (double) (this.position.y() - this.eyeOffset())); // y
        wrapper.write(Types.DOUBLE, relatives.contains(Relative.Z) ? 0D : (double) this.position.z()); // z
        wrapper.write(Types.DOUBLE, 0D); // velocity x
        wrapper.write(Types.DOUBLE, 0D); // velocity y
        wrapper.write(Types.DOUBLE, 0D); // velocity z
        wrapper.write(Types.FLOAT, relatives.contains(Relative.Y_ROT) ? 0F : this.rotation.y()); // yaw
        wrapper.write(Types.FLOAT, relatives.contains(Relative.X_ROT) ? 0F : this.rotation.x()); // pitch
        wrapper.write(Types.INT, EnumUtil.getIntBitmaskFromEnumSet(relatives, Relative::ordinal)); // flags
    }

    public void sendPlayerActionPacketToServer(final PlayerActionType action) {
        this.sendPlayerActionPacketToServer(action, 0);
    }

    public void sendPlayerActionPacketToServer(final PlayerActionType action, final int direction) {
        this.sendPlayerActionPacketToServer(action, new BlockPosition(0, 0, 0), direction);
    }

    public void sendPlayerActionPacketToServer(final PlayerActionType action, final BlockPosition blockPosition, final int direction) {
        final PacketWrapper playerAction = PacketWrapper.create(ServerboundBedrockPackets.PLAYER_ACTION, this.user);
        playerAction.write(BedrockTypes.UNSIGNED_VAR_LONG, this.runtimeId); // entity runtime id
        playerAction.write(BedrockTypes.VAR_INT, action.getValue()); // action
        playerAction.write(BedrockTypes.BLOCK_POSITION, blockPosition); // block position
        playerAction.write(BedrockTypes.BLOCK_POSITION, new BlockPosition(0, 0, 0)); // result position
        playerAction.write(BedrockTypes.VAR_INT, direction); // facing
        playerAction.sendToServer(BedrockProtocol.class);
    }

    public void sendSwingPacketToServer() {
        final PacketWrapper animate = PacketWrapper.create(ServerboundBedrockPackets.ANIMATE, this.user);
        EntityPacketLayout.writeAnimateAction(animate, AnimatePacketPayload_Action.Swing.getValue()); // action
        animate.write(BedrockTypes.UNSIGNED_VAR_LONG, this.runtimeId); // entity runtime id
        animate.write(BedrockTypes.FLOAT_LE, 0F); // data
        EntityPacketLayout.writeAnimateTrailer(animate, ActorSwingSource.Attack.name().toLowerCase(Locale.ROOT)); // swing source (897+)
        animate.sendToServer(BedrockProtocol.class);
    }

    public void updatePlayerPosition(final short flags) {
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;

        if (!this.preMove(null, null, newOnGround)) {
            return;
        }

        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
    }

    public void updatePlayerPosition(final double x, final double y, final double z, final short flags) {
        final Position3f newPosition = new Position3f((float) x, (float) y + this.eyeOffset(), (float) z);
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;

        if (this.rejectImmobilePosition(newPosition, null)) {
            return;
        }
        if (!this.preMove(newPosition, null, newOnGround)) {
            return;
        }

        this.position = newPosition;
        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
        this.snapJavaChunkCacheIfPlayerLeftWindow();
    }

    public void updatePlayerPosition(final double x, final double y, final double z, final float yaw, final float pitch, final short flags) {
        final Position3f newPosition = new Position3f((float) x, (float) y + this.eyeOffset(), (float) z);
        final Position3f newRotation = new Position3f(pitch, yaw, yaw);
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;

        if (this.rejectImmobilePosition(newPosition, newRotation)) {
            return;
        }
        if (!this.preMove(newPosition, newRotation, newOnGround)) {
            return;
        }

        this.position = newPosition;
        this.rotation = newRotation;
        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
        this.snapJavaChunkCacheIfPlayerLeftWindow();
    }

    public void updatePlayerPosition(final float yaw, final float pitch, final short flags) {
        final Position3f newRotation = new Position3f(pitch, yaw, yaw);
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;

        if (!this.preMove(null, newRotation, newOnGround)) {
            return;
        }

        this.rotation = newRotation;
        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
    }

    // Starts a position sync: gates movement (preMove returns false) and sends a position packet the
    // client must confirm before its movement is forwarded again. Records the teleport id of THIS sync
    // (positionSyncTeleportId) so confirmTeleport() can clear the sync even if a later server-driven
    // position packet overwrites pendingTeleportId before the confirm arrives.
    public void beginPositionSync(final Set<Relative> relatives) {
        this.waitingForPositionSync = true;
        this.sendPlayerPositionPacketToClient(relatives);
        this.positionSyncTeleportId = this.pendingTeleportId;
    }

    public void confirmTeleport(final int teleportId) {
        // Clear a pending position sync as soon as the client confirms ANY teleport whose id is at or
        // beyond the sync's id. Teleport ids increase monotonically and are confirmed in order, so this
        // is robust against pendingTeleportId being overwritten between sending the sync and the confirm
        // arriving. Crucially this runs regardless of the fake/real (sign) branch below: a REAL server
        // teleport (positive id) can land in that window and its confirm takes the else-branch, which on
        // its own never touches waitingForPositionSync — leaving the sync stuck forever (movement frozen
        // server-side while the client walks freely). See the deterministic trigger described in preMove().
        if (this.waitingForPositionSync && this.positionSyncTeleportId != 0 && Math.abs(teleportId) >= this.positionSyncTeleportId) {
            this.waitingForPositionSync = false;
            this.positionSyncTeleportId = 0;
        }

        if (teleportId < 0) { // Fake teleport
            if (this.pendingTeleportId == -teleportId) {
                this.pendingTeleportId = 0;
            }
        } else {
            this.serverSideTeleportConfirmed = true;
            if (!this.initiallySpawned) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Received teleport confirm for teleport id " + teleportId + " but player is not spawned yet");
            }
            this.authInputData.add(PlayerAuthInputPacket_InputData.HandledTeleport);
        }
    }

    public boolean hasPendingPositionSync() {
        return this.pendingTeleportId != 0 || this.waitingForPositionSync;
    }

    public Position3f prevPosition() {
        return this.prevPosition;
    }

    public boolean prevOnGround() {
        return this.prevOnGround;
    }

    public Set<PlayerAuthInputPacket_InputData> authInputData() {
        return this.authInputData;
    }

    private boolean isImmobile() {
        return this.inputMovementLocked || this.hasEntityFlag(ActorFlags.NOAI);
    }

    public boolean isInputMovementLocked() {
        return this.inputMovementLocked;
    }

    public void setInputMovementLocked(final boolean inputMovementLocked) {
        this.inputMovementLocked = inputMovementLocked;
    }

    public void addAuthInputData(final PlayerAuthInputPacket_InputData data) {
        this.authInputData.add(data);
    }

    public void addAuthInputData(final PlayerAuthInputPacket_InputData... data) {
        this.authInputData.addAll(Arrays.asList(data));
    }

    public List<AuthInputBlockAction> authInputBlockActions() {
        return this.authInputBlockActions;
    }

    public void addAuthInputBlockAction(final AuthInputBlockAction blockAction) {
        this.authInputData.add(PlayerAuthInputPacket_InputData.PerformBlockActions);
        this.authInputBlockActions.add(blockAction);
    }

    public BedrockInventoryTransaction authInputItemInteraction() {
        return this.authInputItemInteraction;
    }

    public void setAuthInputItemInteraction(final BedrockInventoryTransaction authInputItemInteraction) {
        this.authInputData.add(PlayerAuthInputPacket_InputData.PerformItemInteraction);
        this.authInputItemInteraction = authInputItemInteraction;
    }

    public void clearAuthInputItemInteraction() {
        this.authInputItemInteraction = null;
    }

    @Override
    public void setPosition(final Position3f position) {
        super.setPosition(position);
        this.prevPosition = position;
        this.snapJavaChunkCacheIfPlayerLeftWindow();
    }

    /**
     * Java 1.21.11 unloads columns outside SET_CHUNK_CACHE_CENTER +/- radius.
     * If the player walks off a stale MOT publisher window, snap the Java
     * cache onto the current column before the next LEVEL_CHUNK_WITH_LIGHT.
     * Ref: MOT Player.orderChunks; ChunkTracker.applyPublisher.
     */
    private void snapJavaChunkCacheIfPlayerLeftWindow() {
        final ChunkTracker chunkTracker = this.user.get(ChunkTracker.class);
        if (chunkTracker == null) {
            return;
        }
        final int playerChunkX = ChunkTracker.javaChunkCoord(this.position.x());
        final int playerChunkZ = ChunkTracker.javaChunkCoord(this.position.z());
        if (!ChunkTracker.shouldSnapJavaCacheCenterToPlayerColumn(
                chunkTracker.centerX(), chunkTracker.centerZ(), playerChunkX, playerChunkZ, chunkTracker.radius())) {
            return;
        }
        chunkTracker.alignCenterToClientPlayer();
        chunkTracker.sendCurrentCacheSettingsToJava();
    }

    @Override
    public void setOnGround(final boolean onGround) {
        super.setOnGround(onGround);
        this.prevOnGround = onGround;
    }

    @Override
    public String name() {
        final PlayerListStorage playerList = this.user.get(PlayerListStorage.class);
        final Pair<Long, String> entry = playerList.getPlayer(this.javaUuid);
        if (entry != null) {
            return entry.value();
        }

        return this.name;
    }

    @Override
    public void setAbilities(final PlayerAbilities abilities) {
        final PacketWrapper playerAbilities = PacketWrapper.create(ClientboundPackets26_1.PLAYER_ABILITIES, this.user);
        this.setAbilities(abilities, playerAbilities);
        playerAbilities.send(BedrockProtocol.class);
    }

    public void setAbilities(final PlayerAbilities abilities, final PacketWrapper javaAbilities) {
        final PlayerAbilities prevAbilities = this.abilities;
        super.setAbilities(abilities);

        if (abilities.commandPermission() != prevAbilities.commandPermission()) {
            final CommandsStorage commandsStorage = this.user.get(CommandsStorage.class);
            if (commandsStorage != null) {
                commandsStorage.updateCommandTree();
            }
        }

        final PlayerPermissionLevel playerPermission = PlayerPermissionLevel.getByValue(abilities.playerPermission());
        if (playerPermission == PlayerPermissionLevel.Operator || (playerPermission == PlayerPermissionLevel.Custom && abilities.getBooleanValue(AbilitiesIndex.OperatorCommands))) {
            PacketFactory.sendJavaEntityEvent(this.user, this, EntityEvent.PERMISSION_LEVEL_OWNERS);
        } else {
            PacketFactory.sendJavaEntityEvent(this.user, this, EntityEvent.PERMISSION_LEVEL_ALL);
        }

        // MOT may disguise spectator as creative (useClientSpectator=false) while still
        // sending a Spectator ability layer / NoClip. Keep Java on ADVENTURE so the
        // inventory stays movable; VBU forces Entity.noClip separately.
        final boolean promotedSpectator = abilities.hasSpectatorNoclip() && this.gameType != GameType.Spectator;
        if (promotedSpectator) {
            this.setGameType(GameType.Spectator);
        }

        byte flags = 0;
        if (abilities.getBooleanValue(AbilitiesIndex.Invulnerable)) flags |= AbilitiesFlag.INVULNERABLE.getBit();
        if (abilities.getBooleanValue(AbilitiesIndex.Flying)) flags |= AbilitiesFlag.FLYING.getBit();
        if (abilities.getBooleanValue(AbilitiesIndex.MayFly)) flags |= AbilitiesFlag.CAN_FLY.getBit();
        if (abilities.getBooleanValue(AbilitiesIndex.Instabuild)) flags |= AbilitiesFlag.INSTABUILD.getBit();
        javaAbilities.write(Types.BYTE, flags); // flags
        javaAbilities.write(Types.FLOAT, abilities.getFloatValue(AbilitiesIndex.FlySpeed)); // fly speed
        javaAbilities.write(Types.FLOAT, abilities.getFloatValue(AbilitiesIndex.WalkSpeed)); // walk speed
        if (promotedSpectator) {
            final ProtocolInfo protocolInfo = this.user.getProtocolInfo();
            if (this.initiallySpawned || (protocolInfo != null && protocolInfo.getServerState() == State.PLAY)) {
                ClientPlayerPackets.sendJavaGameMode(this.user, this.javaGameMode);
            }
        }
        ViaBedrockUtilityInterface.syncSpectatorNoclip(this.user, this.gameType == GameType.Spectator);
    }

    public boolean isInitiallySpawned() {
        return this.initiallySpawned;
    }

    public void setInitiallySpawned() {
        this.initiallySpawned = true;
    }

    public DimensionChangeInfo dimensionChangeInfo() {
        return this.dimensionChangeInfo;
    }

    public void setDimensionChangeInfo(final DimensionChangeInfo dimensionChangeInfo) {
        this.dimensionChangeInfo = dimensionChangeInfo;
        if (dimensionChangeInfo != null) {
            this.dimensionChangeStartAge = this.age; // watchdog timeout baseline
        }
    }

    public Set<InputFlag> inputFlags() {
        return this.inputFlags;
    }

    public void setInputFlags(final Set<InputFlag> inputFlags) {
        this.inputFlags = inputFlags;
    }

    public Set<InputFlag> prevInputFlags() {
        return this.prevInputFlags;
    }

    public boolean horizontalCollision() {
        return this.horizontalCollision;
    }

    public void setHorizontalCollision(final boolean horizontalCollision) {
        this.horizontalCollision = horizontalCollision;
    }

    public boolean isSneaking() {
        return this.sneaking;
    }

    public void setSneaking(final boolean sneaking) {
        this.sneaking = sneaking;
    }

    public boolean isSprinting() {
        return this.sprinting;
    }

    public boolean isSwimming() {
        return this.swimming;
    }

    public void setSwimming(final boolean swimming) {
        this.swimming = swimming;
    }

    public boolean isGliding() {
        return this.gliding;
    }

    public void setGliding(final boolean gliding) {
        this.gliding = gliding;
        if (!gliding) {
            this.pendingStartGliding = false;
        }
    }

    public void requestStartGliding() {
        this.pendingStartGliding = true;
    }

    public boolean pendingStartGliding() {
        return this.pendingStartGliding;
    }

    public boolean consumePendingStartGliding() {
        if (!this.pendingStartGliding) {
            return false;
        }
        this.pendingStartGliding = false;
        return true;
    }

    public void beginRiptideSpin(final int durationTicks) {
        this.riptideSpinning = true;
        this.riptideSpinStartAge = this.age;
        this.riptideSpinDurationTicks = Math.max(1, durationTicks);
    }

    public boolean shouldStopRiptideSpin() {
        return this.riptideSpinning
                && this.riptideSpinStartAge >= 0
                && this.age - this.riptideSpinStartAge >= this.riptideSpinDurationTicks;
    }

    public void clearRiptideSpin() {
        this.riptideSpinning = false;
        this.riptideSpinStartAge = -1;
        this.riptideSpinDurationTicks = 0;
    }

    public boolean isRiptideSpinning() {
        return this.riptideSpinning;
    }

    public void setSprinting(final boolean sprinting) {
        this.sprinting = sprinting;

        final EntityAttribute oldMovementAttribute = this.attributes.get("minecraft:movement");
        final List<EntityAttribute.Modifier> modifiers = new ArrayList<>(Arrays.asList(oldMovementAttribute.modifiers()));
        // 与属性翻译共用疾跑标识，避免乱序更新残留旧的疾跑修饰符变体。
        modifiers.removeIf(LivingEntity::isSprintingModifier);
        if (this.sprinting) {
            modifiers.add(new EntityAttribute.Modifier("d208fc00-42aa-4aad-9276-d5446530de43", "Sprinting speed boost", 0.3F, AttributeModifierOperation.OPERATION_MULTIPLY_TOTAL, AttributeOperands.OPERAND_CURRENT, false));
        }
        final EntityAttribute newMovementAttribute = oldMovementAttribute.withModifiers(modifiers.toArray(new EntityAttribute.Modifier[0]));
        // Compute the current value, as the client only updates it when a modifier is changed by itself
        this.updateAttributes(new EntityAttribute[]{newMovementAttribute.withValue(newMovementAttribute.computeCurrentValue())});
    }

    public GameType gameType() {
        return this.gameType;
    }

    public void setGameType(final GameType gameType) {
        this.gameType = gameType;
        this.updateJavaGameMode();
    }

    public GameMode javaGameMode() {
        return this.javaGameMode;
    }

    public void updateJavaGameMode() {
        this.javaGameMode = GameTypeRewriter.getEffectiveGameMode(
                this.gameType,
                this.gameSession.getLevelGameType(),
                ViaBedrockUtilityInterface.hasSpectatorNoclip(this.user)
        );

        final PlayerAbilities.AbilitiesLayer abilitiesLayer = this.abilities.getOrCreateCacheLayer();
        if (this.gameType == GameType.Spectator) {
            abilitiesLayer.setAbility(AbilitiesIndex.Invulnerable, true);
            abilitiesLayer.setAbility(AbilitiesIndex.Flying, true);
            abilitiesLayer.setAbility(AbilitiesIndex.MayFly, true);
            abilitiesLayer.setAbility(AbilitiesIndex.Instabuild, false);
            abilitiesLayer.setAbility(AbilitiesIndex.NoClip, true);
        } else {
            switch (this.javaGameMode) {
                case CREATIVE -> {
                    abilitiesLayer.setAbility(AbilitiesIndex.Invulnerable, true);
                    abilitiesLayer.setAbility(AbilitiesIndex.MayFly, true);
                    abilitiesLayer.setAbility(AbilitiesIndex.Instabuild, true);
                    abilitiesLayer.setAbility(AbilitiesIndex.NoClip, false);
                }
                case SPECTATOR -> {
                    abilitiesLayer.setAbility(AbilitiesIndex.Invulnerable, true);
                    abilitiesLayer.setAbility(AbilitiesIndex.Flying, true);
                    abilitiesLayer.setAbility(AbilitiesIndex.MayFly, true);
                    abilitiesLayer.setAbility(AbilitiesIndex.Instabuild, false);
                    abilitiesLayer.setAbility(AbilitiesIndex.NoClip, true);
                }
                default -> {
                    abilitiesLayer.setAbility(AbilitiesIndex.Invulnerable, false);
                    abilitiesLayer.setAbility(AbilitiesIndex.Flying, false);
                    abilitiesLayer.setAbility(AbilitiesIndex.MayFly, false);
                    abilitiesLayer.setAbility(AbilitiesIndex.Instabuild, false);
                    abilitiesLayer.setAbility(AbilitiesIndex.NoClip, false);
                }
            }
        }
        ViaBedrockUtilityInterface.syncSpectatorNoclip(this.user, this.gameType == GameType.Spectator);
    }

    public boolean checkCancelSwingPacket() {
        final boolean cancel = this.cancelNextSwingPacket;
        this.cancelNextSwingPacket = false;
        return cancel;
    }

    public void cancelNextSwingPacket() {
        this.cancelNextSwingPacket = true;
    }

    public BlockBreakingInfo blockBreakingInfo() {
        return this.blockBreakingInfo;
    }

    public void setBlockBreakingInfo(final BlockBreakingInfo blockBreakingInfo) {
        this.blockBreakingInfo = blockBreakingInfo;
    }

    public void scheduleDelayedMotBreak(final BlockPosition position, final Direction direction, final int dueAge) {
        this.delayedMotBreak = new DelayedMotBreak(position, direction, dueAge);
    }

    public void clearDelayedMotBreak() {
        this.delayedMotBreak = null;
    }

    public DelayedMotBreak pollDueDelayedMotBreak() {
        if (this.delayedMotBreak == null || this.age < this.delayedMotBreak.dueAge()) {
            return null;
        }
        final DelayedMotBreak due = this.delayedMotBreak;
        this.delayedMotBreak = null;
        this.blockBreakingInfo = null;
        return due;
    }

    public boolean isUsingItem() {
        return this.usingItem;
    }

    public void setUsingItem(final boolean usingItem) {
        if (usingItem) {
            throw new IllegalArgumentException("Starting item use requires a hand and item snapshot");
        }
        this.usingItem = usingItem;
        this.usingItemStartAge = -1;
        // Don't clear snapshot in setUsingItem(false) - keep it for validation
        this.crossbowChargeFinishSent = false;
        this.consumableFinishSent = false;
        this.shieldSneakEmulated = false;
    }

    public void startUsingItem(final InteractionHand hand, final byte containerId, final int containerSlot, final int transactionHotbarSlot, final BedrockItem item) {
        this.usingItem = true;
        this.usingItemStartAge = this.age;
        this.itemUseSnapshot = new ItemUseSnapshot(hand, containerId, containerSlot, transactionHotbarSlot, item);
        this.crossbowChargeFinishSent = false;
        this.consumableFinishSent = false;
        this.shieldSneakEmulated = false;
        // Offhand promotion is independent: startUsingItem may follow a silent F-swap.
    }

    public InteractionHand usingItemHand() {
        return this.itemUseSnapshot != null ? this.itemUseSnapshot.hand() : null;
    }

    public ItemUseSnapshot itemUseSnapshot() {
        return this.itemUseSnapshot;
    }

    public int usingItemTicks() {
        return this.usingItemStartAge == -1 ? 0 : this.age - this.usingItemStartAge;
    }

    public boolean isCrossbowChargeFinishSent() {
        return this.crossbowChargeFinishSent;
    }

    public void setCrossbowChargeFinishSent(final boolean crossbowChargeFinishSent) {
        this.crossbowChargeFinishSent = crossbowChargeFinishSent;
    }

    public boolean isShieldSneakEmulated() {
        return this.shieldSneakEmulated;
    }

    public void setShieldSneakEmulated(final boolean shieldSneakEmulated) {
        this.shieldSneakEmulated = shieldSneakEmulated;
    }

    public boolean isOffhandPromoted() {
        return this.offhandPromotionState.isPromoted();
    }

    public boolean isOffhandPromotionPending() {
        return this.offhandPromotionState.isPromotionPending();
    }

    public boolean shouldRetryOffhandRestore() {
        return this.offhandPromotionState.shouldRetryRestore();
    }

    /**
     * @param hand explicit Java hand, or {@code null} for a handless player action
     */
    public boolean canProcessHandSensitiveAction(final InteractionHand hand,
                                                 final boolean allowOriginalOffhandUseContinuation) {
        return this.canProcessHandSensitiveAction(hand, allowOriginalOffhandUseContinuation, false);
    }

    /**
     * The reuse flag is deliberately reserved for USE_ITEM and USE_ITEM_ON. Entity interaction,
     * swing, and inventory actions must prove a continuation instead of treating OFF_HAND as a
     * generic escape hatch while the tracker is physically promoted.
     */
    public boolean canProcessHandSensitiveAction(final InteractionHand hand,
                                                 final boolean allowOriginalOffhandUseContinuation,
                                                 final boolean allowPromotedOffhandReuse) {
        final boolean originalOffhandUseContinuation = allowOriginalOffhandUseContinuation
                && this.isOriginalOffhandUseContinuation(hand);
        return this.offhandPromotionState.canProcessHandSensitiveAction(
                hand == InteractionHand.OFF_HAND,
                originalOffhandUseContinuation,
                allowPromotedOffhandReuse
        );
    }

    public boolean isOriginalOffhandUseContinuation(final InteractionHand hand) {
        return (hand == null || hand == InteractionHand.OFF_HAND)
                && this.isUsingItem()
                && this.itemUseSnapshot != null
                && this.itemUseSnapshot.hand() == InteractionHand.OFF_HAND;
    }

    public boolean scheduleOffhandRestore() {
        return this.offhandPromotionState.scheduleRestore();
    }

    public boolean isOffhandRestoreScheduled() {
        return this.offhandPromotionState.isRestoreScheduled();
    }

    public boolean isOffhandRestoring() {
        return this.offhandPromotionState.isRestoring();
    }

    public Integer offhandRestoreRequestId() {
        return this.offhandPromotionState.restoreRequestId();
    }

    public Integer offhandPromotionRequestId() {
        return this.offhandPromotionState.promotionRequestId();
    }

    public void markOffhandPromotionPending(final Integer requestId) {
        this.offhandPromotionState.promotionPending(requestId);
        this.offhandRestoreIdentity.clear();
    }

    public boolean markOffhandPromotionConfirmed(final Integer requestId) {
        return this.offhandPromotionState.promotionConfirmed(requestId);
    }

    public boolean markOffhandPromotionRejected(final Integer requestId) {
        final boolean rejected = this.offhandPromotionState.promotionRejected(requestId);
        if (rejected) {
            this.offhandRestoreIdentity.clear();
        }
        return rejected;
    }

    public void markOffhandPromoted() {
        this.offhandPromotionState.promoted();
        this.offhandRestoreIdentity.clear();
    }

    public void markOffhandRestoreSubmissionFailed() {
        this.offhandPromotionState.restoreSubmissionFailed();
        this.offhandRestoreIdentity.clear();
    }

    public void captureOffhandRestoreIdentity(final BedrockItem promotedMainHand, final BedrockItem promotedOffhand) {
        this.offhandRestoreIdentity.capturePromotedHands(promotedMainHand, promotedOffhand);
    }

    public void markOffhandRestoreRequestSent(final int requestId) {
        this.offhandPromotionState.restoreRequestSent(requestId);
    }

    public boolean promotedHandsMatch(final BedrockItem mainHand, final BedrockItem offhand) {
        return this.offhandRestoreIdentity.matchesPromotedHands(mainHand, offhand);
    }

    public boolean restoredHandsMatch(final BedrockItem mainHand, final BedrockItem offhand) {
        return this.offhandRestoreIdentity.matchesRestoredHands(mainHand, offhand);
    }

    public boolean markOffhandRestoreConfirmed(final Integer requestId) {
        final boolean confirmed = this.offhandPromotionState.restoreConfirmed(requestId);
        if (confirmed) {
            this.offhandRestoreIdentity.clear();
        }
        return confirmed;
    }

    public boolean completeOffhandRestoreFlush() {
        return this.offhandPromotionState.completeRestoreFlush();
    }

    public boolean markOffhandRestoreRejected(final Integer requestId) {
        final boolean rejected = this.offhandPromotionState.restoreRejected(requestId);
        if (rejected) {
            this.offhandRestoreIdentity.clear();
        }
        return rejected;
    }

    public void abandonOffhandRestore() {
        this.offhandPromotionState.abandonRestore();
        this.offhandRestoreIdentity.clear();
    }

    public DeferredEntityActionQueue deferredEntityActions() {
        return this.deferredEntityActions;
    }

    public int lastUseOnAge() {
        return this.lastUseOnAge;
    }

    public void markUseOnThisTick() {
        this.lastUseOnAge = this.age;
    }

    public int ticksSinceCrossbowChargeFinish() {
        return this.crossbowChargeFinishAge < 0 ? Integer.MAX_VALUE : this.age - this.crossbowChargeFinishAge;
    }

    public void markCrossbowChargeFinished() {
        this.crossbowChargeFinishAge = this.age;
    }

    public boolean isConsumableFinishSent() {
        return this.consumableFinishSent;
    }

    public void setConsumableFinishSent(final boolean consumableFinishSent) {
        this.consumableFinishSent = consumableFinishSent;
    }

    @Override
    protected boolean translateAttribute(final EntityAttribute attribute, final PacketWrapper javaAttributes, final AtomicInteger attributeCount, final List<EntityData> javaEntityData) {
        return switch (attribute.name()) {
            case "minecraft:health", "minecraft:player.hunger", "minecraft:player.saturation" -> {
                final EntityAttribute health = attribute.name().equals("minecraft:health") ? attribute : this.attributes.get("minecraft:health");
                final EntityAttribute hunger = attribute.name().equals("minecraft:player.hunger") ? attribute : this.attributes.get("minecraft:player.hunger");
                final EntityAttribute saturation = attribute.name().equals("minecraft:player.saturation") ? attribute : this.attributes.get("minecraft:player.saturation");
                final PacketWrapper setHealth = PacketWrapper.create(ClientboundPackets26_1.SET_HEALTH, this.user);
                setHealth.write(Types.FLOAT, health.computeClampedValue()); // health
                setHealth.write(Types.VAR_INT, (int) hunger.computeClampedValue()); // food
                setHealth.write(Types.FLOAT, saturation.computeClampedValue()); // saturation
                setHealth.send(BedrockProtocol.class);

                if (attribute.name().equals("minecraft:health")) { // Call super to translate max health
                    yield super.translateAttribute(attribute, javaAttributes, attributeCount, javaEntityData);
                } else {
                    yield true;
                }
            }
            case "minecraft:player.experience", "minecraft:player.level" -> {
                final EntityAttribute experience = attribute.name().equals("minecraft:player.experience") ? attribute : this.attributes.get("minecraft:player.experience");
                final EntityAttribute level = attribute.name().equals("minecraft:player.level") ? attribute : this.attributes.get("minecraft:player.level");
                final PacketWrapper setExperience = PacketWrapper.create(ClientboundPackets26_1.SET_EXPERIENCE, this.user);
                setExperience.write(Types.FLOAT, experience.computeClampedValue()); // bar progress
                setExperience.write(Types.VAR_INT, (int) level.computeClampedValue()); // experience level
                setExperience.write(Types.VAR_INT, 0); // total experience
                setExperience.send(BedrockProtocol.class);
                yield true;
            }
            case "minecraft:player.exhaustion" -> true; // Ignore exhaustion
            default -> super.translateAttribute(attribute, javaAttributes, attributeCount, javaEntityData);
        };
    }

    private boolean preMove(final Position3f newPosition, final Position3f newRotation, final boolean newOnGround) {
        final ChunkTracker chunkTracker = this.user.get(ChunkTracker.class);

        // Allow position packet which is sent immediately after confirming a teleport
        if (this.serverSideTeleportConfirmed) {
            this.serverSideTeleportConfirmed = false;
            return true;
        }
        // Waiting for position sync. Silently drops movement (no rubber-band) until the client confirms
        // the sync teleport. Deterministic deadlock this used to cause, and how it is now prevented:
        //   1. Player switches world / cross-server; their position momentarily lands in a not-yet-loaded
        //      chunk section -> the unloaded-chunk branch below calls beginPositionSync(), which sends a
        //      FAKE teleport (negative id N) and sets waitingForPositionSync = true.
        //   2. BEFORE the client's ACCEPT_TELEPORTATION(N) returns, the Bedrock backend sends a server
        //      teleport for the local player: MOVE_PLAYER with mode = Teleport (OtherPlayerPackets:160-170,
        //      very common right after a world/server switch to place the player). Because fakeTeleport is
        //      only true for mode = Respawn, this writes a REAL teleport (positive id M > N) and overwrites
        //      pendingTeleportId from N to M.
        //   3. The client confirms N then M. Old code cleared the sync only in the fake branch when
        //      pendingTeleportId == -teleportId: confirm(N) failed (pendingTeleportId == M != N) and
        //      confirm(M) took the real/else branch which never cleared the sync. waitingForPositionSync
        //      stayed true forever -> preMove permanently returns false here -> PLAYER_AUTH_INPUT keeps
        //      sending the frozen old position (server-side player + footstep sound stuck at the origin)
        //      while the client keeps predicting movement locally (walks freely). Matches the observed
        //      "client walks, footstep stays put and fades with distance" symptom.
        // Fix: confirmTeleport() now clears the sync on ANY confirm with |id| >= positionSyncTeleportId,
        // so the real teleport in step 2 (or the fake confirm in step 3) resolves it. See confirmTeleport().
        if (this.waitingForPositionSync) {
            return false;
        }
        // Not spawned yet or respawning
        if (!this.initiallySpawned || this.dimensionChangeInfo != null) {
            if (!this.position.equals(newPosition)) {
                this.sendPlayerPositionPacketToClient(Relative.NONE);
            }
            return false;
        }
        // Is in unloaded chunk
        if (chunkTracker.isInUnloadedChunkSection(this.position, this.eyeOffset())) {
            this.wasInsideUnloadedChunk = true;
            if (!this.position.equals(newPosition)) {
                this.beginPositionSync(Relative.ROTATION);
            }
            return false;
        } else if (this.wasInsideUnloadedChunk) {
            this.wasInsideUnloadedChunk = false;
            this.beginPositionSync(Relative.ROTATION);
            return false;
        }
        // Loaded -> Unloaded chunk
        if (newPosition != null && chunkTracker.isInUnloadedChunkSection(newPosition, this.eyeOffset())) {
            this.beginPositionSync(Relative.ROTATION);
            return false;
        }

        if (newPosition != null && !this.position.equals(newPosition)) {
            return true;
        } else if (newRotation != null && !this.rotation.equals(newRotation)) {
            return true;
        } else if (this.onGround != newOnGround) {
            return true;
        }

        return false;
    }

    private boolean rejectImmobilePosition(final Position3f newPosition, final Position3f newRotation) {
        if (!shouldRejectImmobilePosition(this.initiallySpawned, this.isImmobile(), this.position, newPosition)) {
            return false;
        }

        if (newRotation != null) {
            this.rotation = newRotation;
        }
        if (!this.waitingForPositionSync) {
            this.beginPositionSync(Relative.ROTATION);
        }
        return true;
    }

    static boolean shouldRejectImmobilePosition(final boolean initiallySpawned, final boolean immobile, final Position3f currentPosition, final Position3f newPosition) {
        return initiallySpawned && immobile && newPosition != null && !currentPosition.equals(newPosition);
    }

    /**
     * Fail-safe net for the rare case where the backend never sends the packet that would unlock movement after a
     * world/dimension/server switch, leaving the server-side player frozen while the client keeps moving (see the
     * deterministic analysis in the ViaBedrock movement notes). Runs once per client tick after super.tick().
     * <p>
     * Controlled by the {@code movementWatchdog} config section: OFF = no-op (upstream behaviour), OBSERVE = log
     * only when a lock stays stuck past the timeout, ACTIVE = additionally perform the conservative, idempotent
     * recovery (re-send the standard handshake finalization the backend omitted; for stuck chunks, re-request the
     * chunk radius — it never lets the player phase through unloaded terrain). The timeouts are far above any normal
     * handshake, so normal players never trigger it. Never throws into the tick loop.
     */
    private void movementWatchdog() {
        final ViaBedrockConfig.MovementWatchdogMode mode = ViaBedrock.getConfig().getMovementWatchdogMode();
        if (mode == ViaBedrockConfig.MovementWatchdogMode.OFF || !this.initiallySpawned) {
            return;
        }
        try {
            final ChunkTracker chunkTracker = this.user.get(ChunkTracker.class);
            if (chunkTracker == null) {
                return;
            }
            final boolean active = mode == ViaBedrockConfig.MovementWatchdogMode.ACTIVE;

            // Link A: dimension lock (dimensionChangeInfo) never cleared by the backend -> preMove rubber-bands forever.
            if (this.dimensionChangeInfo != null) {
                final int elapsed = this.age - this.dimensionChangeStartAge;
                if (this.dimensionChangeStartAge >= 0
                        && elapsed > ViaBedrock.getConfig().getMovementWatchdogDimensionChangeTimeoutTicks()
                        && !chunkTracker.isInUnloadedChunkSection(this.position, this.eyeOffset())) { // only once the new world is actually ready
                    if (active) {
                        final Long loadingScreenId = this.dimensionChangeInfo.loadingScreenId();
                        // Replicates the standard ChangeDimensionAck finalization (ClientPlayerPackets). Idempotent:
                        // if the backend later sends the real ack, its dimensionChangeInfo != null guard skips it.
                        this.sendPlayerActionPacketToServer(PlayerActionType.ChangeDimensionAck);
                        PacketFactory.sendBedrockLoadingScreen(this.user, ServerboundLoadingScreenPacketType.EndLoadingScreen, loadingScreenId);
                        this.sendPlayerPositionPacketToClient(Relative.NONE);
                        this.setDimensionChangeInfo(null);
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] A: dimension change not finalized by backend after " + elapsed + " ticks; finalized client-side");
                    } else {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] A(observe): dimension lock stuck " + elapsed + " ticks (would finalize in active mode)");
                        this.dimensionChangeStartAge = this.age; // re-baseline so observe logs at most once per timeout window
                    }
                }
                return; // during a dimension change, do not also run the chunk watchdog
            }

            // Link B: stuck in an unloaded chunk section (chunks never delivered) -> preMove rubber-bands forever.
            if (chunkTracker.isInUnloadedChunkSection(this.position, this.eyeOffset())) {
                if (this.chunkStuckStartAge < 0) {
                    this.chunkStuckStartAge = this.age;
                }
                final int elapsed = this.age - this.chunkStuckStartAge;
                if (elapsed > ViaBedrock.getConfig().getMovementWatchdogChunkStuckTimeoutTicks()
                        && (this.lastChunkRadiusRequestAge < 0
                            || this.age - this.lastChunkRadiusRequestAge > ViaBedrock.getConfig().getMovementWatchdogChunkRadiusRequestIntervalTicks())) {
                    this.lastChunkRadiusRequestAge = this.age;
                    if (active) {
                        // Only re-request chunks; never relax the movement gate (relaxing would let the player phase).
                        final PacketWrapper requestChunkRadius = PacketWrapper.create(ServerboundBedrockPackets.REQUEST_CHUNK_RADIUS, this.user);
                        requestChunkRadius.write(BedrockTypes.VAR_INT, this.user.get(ClientSettingsStorage.class).viewDistance());
                        requestChunkRadius.write(Types.BYTE, ProtocolConstants.BEDROCK_REQUEST_CHUNK_RADIUS_MAX_RADIUS);
                        requestChunkRadius.sendToServer(BedrockProtocol.class);
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] B: stuck in unloaded chunk " + elapsed + " ticks; re-requested chunk radius");
                    } else {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] B(observe): stuck in unloaded chunk " + elapsed + " ticks (would re-request chunk radius in active mode)");
                    }
                }
            } else {
                this.chunkStuckStartAge = -1;
                this.lastChunkRadiusRequestAge = -1;
            }
        } catch (final Throwable t) {
            // Never let the watchdog break the tick loop.
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] internal error (ignored): " + t.getMessage());
        }
    }

    public record DimensionChangeInfo(Long loadingScreenId) {
    }

    public record BlockBreakingInfo(BlockPosition position, Direction direction) {
    }

    public record DelayedMotBreak(BlockPosition position, Direction direction, int dueAge) {
    }

    public record ItemUseSnapshot(InteractionHand hand, byte containerId, int containerSlot, int transactionHotbarSlot, BedrockItem item) {

        public ItemUseSnapshot {
            Objects.requireNonNull(hand, "hand");
            item = Objects.requireNonNull(item, "item").copy();
        }

        public boolean matches(final InteractionHand hand, final byte containerId, final int containerSlot, final int transactionHotbarSlot, final BedrockItem item) {
            return matches(hand, containerId, containerSlot, transactionHotbarSlot, item, false);
        }

        public boolean matches(final InteractionHand hand, final byte containerId, final int containerSlot, final int transactionHotbarSlot, final BedrockItem item, final boolean emulateNetEase) {
            if (this.hand != hand
                    || this.containerId != containerId
                    || this.containerSlot != containerSlot
                    || this.transactionHotbarSlot != transactionHotbarSlot) {
                return false;
            }
            if (item == null) {
                return false;
            }
            return net.raphimc.viabedrock.experimental.ItemUseSemantics.matchesUseItem(
                    emulateNetEase,
                    this.item.identifier(),
                    this.item.data(),
                    this.item.blockRuntimeId(),
                    this.item.tag(),
                    item.identifier(),
                    item.data(),
                    item.blockRuntimeId(),
                    item.tag()
            );
        }

    }

    public record AuthInputBlockAction(PlayerActionType action, BlockPosition position, int direction) {

        public AuthInputBlockAction(final PlayerActionType action) {
            this(action, null, -1);
        }

    }

}

