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
import net.raphimc.viabedrock.api.util.StringUtil;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.storage.ClientSettingsStorage;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.storage.RidingTracker;
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
import net.raphimc.viabedrock.api.util.MathUtil;
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

    // 初始生成与重生
    private boolean initiallySpawned;
    private DimensionChangeInfo dimensionChangeInfo;
    private boolean wasInsideUnloadedChunk;

    // 位置同步
    private int pendingTeleportId;
    private boolean waitingForPositionSync;
    // 当前正在等待客户端确认的位置同步包所用的传送 id（0 表示没有）。
    // 它与 pendingTeleportId 分开保存，后者会被每一个发出的位置包覆写，因此在同步确认到达前
    // 若有另一个由服务端驱动的位置包抬高了 pendingTeleportId，本次同步仍能被匹配 / 清除。
    // 见 beginPositionSync() / confirmTeleport()。
    private int positionSyncTeleportId;
    private boolean serverSideTeleportConfirmed;
    private final JavaMovementPositionCache javaMovementPositionCache = new JavaMovementPositionCache();
    // 放置求交保留 Java 原始脚底坐标，不能靠增大容差掩盖 Bedrock float 坐标的舍入误差。
    private JavaPosition javaPosition;

    // 移动看门狗（用于后端在切换后始终不发送解锁包时的失效保护）。
    // -1 = 未激活；年龄与 Entity.age 比较（每个客户端刻递增一次）。
    private int dimensionChangeStartAge = -1;
    private int chunkStuckStartAge = -1;
    private int lastChunkRadiusRequestAge = -1;

    // 服务端权威移动
    private Position3f prevPosition;
    private boolean prevOnGround;
    private final Set<PlayerAuthInputPacket_InputData> authInputData = EnumSet.noneOf(PlayerAuthInputPacket_InputData.class);
    private final List<AuthInputBlockAction> authInputBlockActions = new ArrayList<>();
    private BedrockInventoryTransaction authInputItemInteraction;
    private int inputLockFlags;
    private int sentInputLockFlags = -1;
    private boolean sentImmobile;
    private Set<InputFlag> inputFlags = EnumSet.noneOf(InputFlag.class);
    private Set<InputFlag> prevInputFlags = EnumSet.noneOf(InputFlag.class);
    private boolean horizontalCollision;
    private boolean sneaking;
    private boolean sprinting;
    // Java 从不发送基岩的 START/STOP_SWIMMING 或 STOP_GLIDING。swimming
    // 跟随 MOT 的 ActorFlags.SWIMMING，以便 AuthInput 可以重试 StartSwimming
    // 直到服务器确认；乐观地置为 true 会让观察者在 STANDING 状态被阻塞。
    private boolean swimming;
    // 最近一次已知的 MOT 风格脚部入水采样。缺失的区块列在 ChunkTracker 中表现为空气，
    // 因此让它独立于 swimming，用于 Start 的重试。
    private boolean lastInsideOfWater;
    private boolean gliding;
    // Java 的 START_FALL_FLYING 可能在 onGround 仍为 true 时到达。MOT/GanAC 的
    // ElytraF 会在该刻抑制 StartGliding，因此先挂起，直到离地再处理。
    private boolean pendingStartGliding;
    // MOT UPDATE_CLIENT_INPUT_LOCKS 的第 4 位。560+ 协议不再为移动锁设置 ActorFlags.NOAI，
    // 否则后端已冻结时 Java 仍会继续行走。
    private boolean inputMovementLocked;
    // MOT SAI 的 STOP_SPIN_ATTACK 是唯一会清除 DATA_FLAG_SPIN_ATTACK 的路径。
    // Java 从不发送停止姿态；在 MOT 的持续时间 50+(level<<5) 之后脉冲一次 Stop。
    private boolean riptideSpinning;
    private int riptideSpinStartAge = -1;
    private int riptideSpinDurationTicks;

    // 杂项数据
    private GameType gameType;
    private GameMode javaGameMode;
    private boolean cancelNextSwingPacket;
    private final JavaSwingSourceTracker swingSourceTracker = new JavaSwingSourceTracker();
    private BlockBreakingInfo blockBreakingInfo;
    private DelayedMotBreak delayedMotBreak;
    private boolean usingItem;
    private boolean blockItemUse;
    private boolean serverUsingItem;
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

    // 基岩服务器在玩家列表中分配给本地玩家的 UUID。它与 javaUuid 不同
    // （见 HudPackets 的 PLAYER_LIST 重映射），且是服务器在 PLAYER_SKIN 中定位
    // 本地玩家所用的 UUID。在 HudPackets 中捕获，以便 PLAYER_SKIN 能将其重映射为 javaUuid。
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
        // 使用失败时可能没有 SWING，未消费来源不得越过 Java CLIENT_TICK_END。
        this.swingSourceTracker.reset();
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

    @Override
    protected String javaTeamMemberName() {
        return localJavaTeamMemberName(this.user.getProtocolInfo().getUsername(), this.javaUuid);
    }

    static String localJavaTeamMemberName(final String username, final UUID javaUuid) {
        return username == null || username.isEmpty() ? StringUtil.encodeUUID(javaUuid) : username;
    }

    public void sendPlayerPositionPacketToClient(final Set<Relative> relatives) {
        final PacketWrapper playerPosition = PacketWrapper.create(ClientboundPackets26_1.PLAYER_POSITION, this.user);
        this.writePlayerPositionPacketToClient(playerPosition, relatives, true);
        playerPosition.send(BedrockProtocol.class);
    }

    public void writePlayerPositionPacketToClient(final PacketWrapper wrapper, final Set<Relative> relatives, final boolean fakeTeleport) {
        this.javaPosition = null;
        this.pendingTeleportId = TELEPORT_ID.getAndIncrement();

        if (this.prepareJavaMovementPositionCache()) {
            this.javaMovementPositionCache.issueTeleport(this.pendingTeleportId * (fakeTeleport ? -1 : 1),
                    (double) this.position.x(), (double) (this.position.y() - this.eyeOffset()), (double) this.position.z(),
                    MathUtil.wrapDegrees(this.rotation.y()), this.rotation.x(), relatives.isEmpty());
        }

        wrapper.write(Types.VAR_INT, this.pendingTeleportId * (fakeTeleport ? -1 : 1)); // 传送 id
        wrapper.write(Types.DOUBLE, relatives.contains(Relative.X) ? 0D : (double) this.position.x()); // x
        wrapper.write(Types.DOUBLE, relatives.contains(Relative.Y) ? 0D : (double) (this.position.y() - this.eyeOffset())); // y
        wrapper.write(Types.DOUBLE, relatives.contains(Relative.Z) ? 0D : (double) this.position.z()); // z
        wrapper.write(Types.DOUBLE, 0D); // 速度 x
        wrapper.write(Types.DOUBLE, 0D); // 速度 y
        wrapper.write(Types.DOUBLE, 0D); // 速度 z
        wrapper.write(Types.FLOAT, relatives.contains(Relative.Y_ROT) ? 0F : this.rotation.y()); // 偏航角
        wrapper.write(Types.FLOAT, relatives.contains(Relative.X_ROT) ? 0F : this.rotation.x()); // 俯仰角
        wrapper.write(Types.INT, EnumUtil.getIntBitmaskFromEnumSet(relatives, Relative::ordinal)); // 标志位
    }

    public void sendPlayerActionPacketToServer(final PlayerActionType action) {
        this.sendPlayerActionPacketToServer(action, 0);
    }

    public void sendPlayerActionPacketToServer(final PlayerActionType action, final int direction) {
        this.sendPlayerActionPacketToServer(action, new BlockPosition(0, 0, 0), direction);
    }

    public void sendPlayerActionPacketToServer(final PlayerActionType action, final BlockPosition blockPosition, final int direction) {
        final PacketWrapper playerAction = PacketWrapper.create(ServerboundBedrockPackets.PLAYER_ACTION, this.user);
        playerAction.write(BedrockTypes.UNSIGNED_VAR_LONG, this.runtimeId); // 实体运行时 ID
        playerAction.write(BedrockTypes.VAR_INT, action.getValue()); // 动作
        playerAction.write(BedrockTypes.BLOCK_POSITION, blockPosition); // 方块位置
        playerAction.write(BedrockTypes.BLOCK_POSITION, new BlockPosition(0, 0, 0)); // 结果位置
        playerAction.write(BedrockTypes.VAR_INT, direction); // 朝向
        playerAction.sendToServer(BedrockProtocol.class);
    }

    public void sendSwingPacketToServer() {
        // 明确的实体攻击或挖掘不得继承此前失败的使用动作。
        this.swingSourceTracker.reset();
        this.sendSwingPacketToServer(ActorSwingSource.Attack);
    }

    public void sendSwingPacketToServer(final ActorSwingSource source) {
        final PacketWrapper animate = PacketWrapper.create(ServerboundBedrockPackets.ANIMATE, this.user);
        EntityPacketLayout.writeAnimateAction(animate, AnimatePacketPayload_Action.Swing.getValue()); // 动作
        animate.write(BedrockTypes.UNSIGNED_VAR_LONG, this.runtimeId); // 实体运行时 ID
        animate.write(BedrockTypes.FLOAT_LE, 0F); // 数据
        EntityPacketLayout.writeAnimateTrailer(animate, source.name().toLowerCase(Locale.ROOT)); // 挥动来源（897+）
        animate.sendToServer(BedrockProtocol.class);
    }

    /** Java 位置包的原始脚底坐标，仅用于放置几何，不作为运动预测结果。 */
    public record JavaPosition(double x, double y, double z) {
    }

    public JavaPosition javaPosition() {
        final JavaPosition feet = this.javaPosition;
        // 服务端直接改写位置、换维度或传送后，旧的 Java 精确坐标不再属于当前实体位置。
        if (feet == null || !Double.isFinite(feet.x()) || !Double.isFinite(feet.y()) || !Double.isFinite(feet.z())
                || this.position.x() != (float) feet.x() || this.position.y() != (float) feet.y() + this.eyeOffset()
                || this.position.z() != (float) feet.z()) return null;
        return feet;
    }

    public void updatePlayerPosition(final short flags) {
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;
        final Position3f omittedPosition = this.prepareJavaMovementPositionCache()
                ? this.javaMovementPositionCache.omittedPosition() : null;

        if (omittedPosition != null && this.rejectImmobilePosition(omittedPosition, null)) {
            return;
        }
        if (!this.preMove(omittedPosition, null, newOnGround)) {
            return;
        }

        if (omittedPosition != null) {
            this.javaPosition = null;
            this.position = omittedPosition;
        }
        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
    }

    public void updatePlayerPosition(final double x, final double y, final double z, final short flags) {
        this.javaPosition = null;
        final Position3f newPosition = new Position3f((float) x, (float) y + this.eyeOffset(), (float) z);
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;
        final boolean cachePosition = this.prepareJavaMovementPositionCache();
        // 坐标报告是协议观测；即使实体无需移动，也已更新 Java 的普通位置缓存。
        if (cachePosition) {
            this.javaMovementPositionCache.recordPosition(newPosition);
            if (this.position.equals(newPosition)) this.javaPosition = new JavaPosition(x, y, z);
        }

        if (this.rejectImmobilePosition(newPosition, null)) {
            return;
        }
        if (!this.preMove(newPosition, null, newOnGround)) {
            return;
        }

        this.position = newPosition;
        this.javaPosition = new JavaPosition(x, y, z);
        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
        this.snapJavaChunkCacheIfPlayerLeftWindow();
    }

    public void updatePlayerPosition(final double x, final double y, final double z, final float yaw, final float pitch, final short flags) {
        this.javaPosition = null;
        final Position3f newPosition = new Position3f((float) x, (float) y + this.eyeOffset(), (float) z);
        final Position3f newRotation = new Position3f(pitch, yaw, yaw);
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;
        final boolean cachePosition = this.prepareJavaMovementPositionCache();
        if (cachePosition) {
            this.javaMovementPositionCache.recordPositionWithRotation(newPosition, x, y, z, yaw, pitch, flags);
            if (this.position.equals(newPosition)) this.javaPosition = new JavaPosition(x, y, z);
        }

        if (this.rejectImmobilePosition(newPosition, newRotation)) {
            return;
        }
        if (!this.preMove(newPosition, newRotation, newOnGround)) {
            return;
        }

        this.position = newPosition;
        this.javaPosition = new JavaPosition(x, y, z);
        this.rotation = newRotation;
        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
        this.snapJavaChunkCacheIfPlayerLeftWindow();
    }

    public void updatePlayerPosition(final float yaw, final float pitch, final short flags) {
        final Position3f newRotation = new Position3f(pitch, yaw, yaw);
        final boolean newOnGround = (flags & MovePlayerFlag.ON_GROUND.getBit()) != 0;
        final Position3f omittedPosition = this.prepareJavaMovementPositionCache()
                ? this.javaMovementPositionCache.omittedPosition() : null;

        if (omittedPosition != null && this.rejectImmobilePosition(omittedPosition, newRotation)) {
            return;
        }
        if (!this.preMove(omittedPosition, newRotation, newOnGround)) {
            return;
        }

        if (omittedPosition != null) {
            this.javaPosition = null;
            this.position = omittedPosition;
        }
        this.rotation = newRotation;
        this.onGround = newOnGround;
        this.horizontalCollision = (flags & MovePlayerFlag.HORIZONTAL_COLLISION.getBit()) != 0;
    }

    // 开始一次位置同步：闸住移动（preMove 返回 false），并发送一个客户端必须确认的位置包，
    // 确认后其移动才会被再次转发。记录本次同步的传送 id
    // （positionSyncTeleportId），这样即使之后由服务端驱动的位置包在确认到达前
    // 覆写了 pendingTeleportId，confirmTeleport() 仍能清除本次同步。
    public void beginPositionSync(final Set<Relative> relatives) {
        this.waitingForPositionSync = true;
        this.sendPlayerPositionPacketToClient(relatives);
        this.positionSyncTeleportId = this.pendingTeleportId;
    }

    public void confirmTeleport(final int teleportId) {
        this.javaMovementPositionCache.confirmTeleport(teleportId);
        // 只要客户端确认了 id 等于或超过本次同步 id 的任意传送，就清除挂起的位置同步。
        // 传送 id 单调递增且按顺序确认，因此即使 pendingTeleportId 在发送同步到确认到达之间被覆写，
        // 这一判断仍然可靠。关键点：此逻辑独立于下面的伪 / 真（符号）分支执行——一个真的服务端
        // 传送（正 id）也可能落在该窗口内，而它的确认会走 else 分支，该分支自身从不触碰
        // waitingForPositionSync，从而让同步永久卡住（服务端侧移动被冻结，客户端却能自由行走）。
        // 见 preMove() 中描述的确定性触发路径。
        if (this.waitingForPositionSync && this.positionSyncTeleportId != 0 && Math.abs(teleportId) >= this.positionSyncTeleportId) {
            this.waitingForPositionSync = false;
            this.positionSyncTeleportId = 0;
        }

        if (teleportId < 0) { // 伪传送
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
        this.javaPosition = null;
        super.setPosition(position);
        this.prevPosition = position;
        this.snapJavaChunkCacheIfPlayerLeftWindow();
    }

    /**
     * Java 1.21.11 会卸载 SET_CHUNK_CACHE_CENTER +/- radius 之外的区块列。
     * 若玩家走出了过期的 MOT publisher 窗口，则在下一个 LEVEL_CHUNK_WITH_LIGHT 之前
     * 把 Java 缓存对齐到当前区块列。
     * 参考：MOT Player.orderChunks；ChunkTracker.applyPublisher。
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

    public void setPositionFromServer(final Position3f position) {
        this.setPosition(position);
        final RidingTracker riding = this.user.get(RidingTracker.class);
        if (riding != null) {
            riding.onPlayerPositionCorrection(position);
        }
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

        // MOT 可能把旁观者伪装成创造模式（useClientSpectator=false），同时仍发送
        // 旁观者能力层 / NoClip。让 Java 保持在 ADVENTURE，物品栏才能继续操作；
        // VBU 会单独强制 Entity.noClip。
        final boolean promotedSpectator = abilities.hasSpectatorNoclip() && this.gameType != GameType.Spectator;
        if (promotedSpectator) {
            this.setGameType(GameType.Spectator);
        }

        byte flags = 0;
        if (abilities.getBooleanValue(AbilitiesIndex.Invulnerable)) flags |= AbilitiesFlag.INVULNERABLE.getBit();
        if (abilities.getBooleanValue(AbilitiesIndex.Flying)) flags |= AbilitiesFlag.FLYING.getBit();
        if (abilities.getBooleanValue(AbilitiesIndex.MayFly)) flags |= AbilitiesFlag.CAN_FLY.getBit();
        if (abilities.getBooleanValue(AbilitiesIndex.Instabuild)) flags |= AbilitiesFlag.INSTABUILD.getBit();
        javaAbilities.write(Types.BYTE, flags); // 标志位
        javaAbilities.write(Types.FLOAT, abilities.getFloatValue(AbilitiesIndex.FlySpeed)); // 飞行速度
        javaAbilities.write(Types.FLOAT, abilities.getFloatValue(AbilitiesIndex.WalkSpeed)); // 行走速度
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
        this.resendInputLocks();
    }

    public DimensionChangeInfo dimensionChangeInfo() {
        return this.dimensionChangeInfo;
    }

    public void setDimensionChangeInfo(final DimensionChangeInfo dimensionChangeInfo) {
        this.dimensionChangeInfo = dimensionChangeInfo;
        if (dimensionChangeInfo != null) {
            this.resetJavaMovementPosition();
            this.dimensionChangeStartAge = this.age; // 看门狗超时基准
        } else {
            this.resendInputLocks();
        }
    }

    public void resetJavaMovementPosition() {
        this.javaPosition = null;
        this.javaMovementPositionCache.reset();
    }

    private boolean prepareJavaMovementPositionCache() {
        final RidingTracker riding = this.user.get(RidingTracker.class);
        if (!this.initiallySpawned || this.isDead() || this.isImmobile() || this.dimensionChangeInfo != null
                || this.waitingForPositionSync || riding != null && riding.isLocalRiding()) {
            this.resetJavaMovementPosition();
            return false;
        }
        return true;
    }

    public Set<InputFlag> inputFlags() {
        return this.inputFlags;
    }

    public void setInputFlags(final Set<InputFlag> inputFlags) {
        this.inputFlags = this.isMovementInputLocked() ? EnumSet.noneOf(InputFlag.class) : inputFlags;
    }

    public void setInputLocks(final int flags) {
        if (this.inputLockFlags == flags) {
            return;
        }
        this.inputLockFlags = flags;
        if (this.isMovementInputLocked()) {
            this.inputFlags = EnumSet.noneOf(InputFlag.class);
        }
        this.synchronizeInputLocks(false);
    }

    public boolean isMovementInputLocked() {
        return this.isImmobile() || (this.inputLockFlags & (1 << 2)) != 0;
    }

    /** 元数据完整合批后再观察NOAI，只有本地玩家将其投影为输入与物理锁。 */
    @Override
    protected void onEntityDataChanged() {
        super.onEntityDataChanged();
        if (this.isMovementInputLocked()) this.inputFlags = EnumSet.noneOf(InputFlag.class);
        this.synchronizeInputLocks(false);
    }

    public void resendInputLocks() {
        this.synchronizeInputLocks(true);
    }

    private void synchronizeInputLocks(final boolean force) {
        if (!this.initiallySpawned || this.user.getProtocolInfo().getClientState() != State.PLAY) return;
        final int flags = this.inputLockFlags & ViaBedrockUtilityInterface.INPUT_LOCKS_V3_FLAGS;
        final boolean immobile = this.isImmobile();
        if (!force && flags == this.sentInputLockFlags && immobile == this.sentImmobile) return;
        if (ViaBedrockUtilityInterface.sendInputLocks(this.user, flags, immobile)) {
            this.sentInputLockFlags = flags;
            this.sentImmobile = immobile;
        }
    }

    /** 新世界不继承旧世界的NOAI；其余实体标志仍交给既有重生流程处理。 */
    public void resetInputLocks() {
        this.swingSourceTracker.reset();
        this.inputLockFlags = 0;
        final EntityData flags = this.entityData.get(ActorDataIDs.RESERVED_0);
        if (flags != null) {
            this.entityData.put(ActorDataIDs.RESERVED_0, new EntityData(flags.id(), flags.dataType(),
                    flags.<Long>value() & ~(1L << ActorFlags.NOAI.getValue())));
        }
        this.inputFlags = EnumSet.noneOf(InputFlag.class);
        this.resendInputLocks();
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

    public boolean lastInsideOfWater() {
        return this.lastInsideOfWater;
    }

    public void setLastInsideOfWater(final boolean lastInsideOfWater) {
        this.lastInsideOfWater = lastInsideOfWater;
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
        // 自行计算当前值，因为客户端只在自身更改修饰符时才会更新它
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
        this.swingSourceTracker.reset();
        this.cancelNextSwingPacket = true;
    }

    public JavaSwingSourceTracker swingSourceTracker() {
        return this.swingSourceTracker;
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
        this.blockItemUse = false;
        this.usingItemStartAge = -1;
        // 不要在 setUsingItem(false) 中清除快照——保留它用于校验
        this.crossbowChargeFinishSent = false;
        this.consumableFinishSent = false;
        this.shieldSneakEmulated = false;
    }

    public void startUsingItem(final InteractionHand hand, final byte containerId, final int containerSlot, final int transactionHotbarSlot, final BedrockItem item) {
        this.usingItem = true;
        this.blockItemUse = false;
        this.usingItemStartAge = this.age;
        this.itemUseSnapshot = new ItemUseSnapshot(hand, containerId, containerSlot, transactionHotbarSlot, item);
        this.crossbowChargeFinishSent = false;
        this.consumableFinishSent = false;
        this.shieldSneakEmulated = false;
        // 副手提升是独立的：startUsingItem 可能紧接着一次静默的 F 键交换发生。
    }

    public void prepareBlockItemUse(final InteractionHand hand, final byte containerId, final int containerSlot, final int transactionHotbarSlot, final BedrockItem item) {
        this.startUsingItem(hand, containerId, containerSlot, transactionHotbarSlot, item);
        this.usingItem = false;
        this.blockItemUse = true;
    }

    public void updateServerItemUse(final boolean usingItem) {
        if (this.serverUsingItem == usingItem) {
            return;
        }
        this.serverUsingItem = usingItem;
        if (this.blockItemUse && this.itemUseSnapshot != null) {
            this.usingItem = usingItem;
        }
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

    /** @param hand 显式的 Java 手，或 {@code null} 表示无手的玩家动作 */
    public boolean canProcessHandSensitiveAction(final InteractionHand hand,
                                                 final boolean allowOriginalOffhandUseContinuation) {
        return this.canProcessHandSensitiveAction(hand, allowOriginalOffhandUseContinuation, false);
    }

    /**
     * 复用标志刻意只留给 USE_ITEM 和 USE_ITEM_ON。实体交互、
     * 挥动和物品栏动作必须证明是延续操作，而不能在追踪器实际已提升时
     * 把 OFF_HAND 当作通用的逃生口。
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
                setHealth.write(Types.FLOAT, health.computeClampedValue()); // 生命值
                setHealth.write(Types.VAR_INT, (int) hunger.computeClampedValue()); // 饥饿值
                setHealth.write(Types.FLOAT, saturation.computeClampedValue()); // 饱和度
                setHealth.send(BedrockProtocol.class);

                if (attribute.name().equals("minecraft:health")) { // 调用父类以转换最大生命值
                    yield super.translateAttribute(attribute, javaAttributes, attributeCount, javaEntityData);
                } else {
                    yield true;
                }
            }
            case "minecraft:player.experience", "minecraft:player.level" -> {
                final EntityAttribute experience = attribute.name().equals("minecraft:player.experience") ? attribute : this.attributes.get("minecraft:player.experience");
                final EntityAttribute level = attribute.name().equals("minecraft:player.level") ? attribute : this.attributes.get("minecraft:player.level");
                final PacketWrapper setExperience = PacketWrapper.create(ClientboundPackets26_1.SET_EXPERIENCE, this.user);
                setExperience.write(Types.FLOAT, experience.computeClampedValue()); // 进度条进度
                setExperience.write(Types.VAR_INT, (int) level.computeClampedValue()); // 经验等级
                setExperience.write(Types.VAR_INT, 0); // 总经验
                setExperience.send(BedrockProtocol.class);
                yield true;
            }
            case "minecraft:player.exhaustion" -> true; // 忽略消耗度
            default -> super.translateAttribute(attribute, javaAttributes, attributeCount, javaEntityData);
        };
    }

    private boolean preMove(final Position3f newPosition, final Position3f newRotation, final boolean newOnGround) {
        final ChunkTracker chunkTracker = this.user.get(ChunkTracker.class);

        // 等待位置同步。在客户端确认同步传送之前静默丢弃移动（不做拉回）。
        // 这曾导致确定性死锁，以及现在的避免方式：
        // 1. 玩家切换世界 / 跨服；其位置短暂落在尚未加载的区块段
        // -> 下面的未加载区块分支调用 beginPositionSync()，它发送一个
        // 伪传送（负 id N）并置 waitingForPositionSync = true。
        // 2. 在客户端的 ACCEPT_TELEPORTATION(N) 返回之前，基岩后端为本地玩家发送了一个服务端
        // 传送：mode = Teleport 的 MOVE_PLAYER（OtherPlayerPackets:160-170，
        // 在世界 / 服务器切换后放置玩家时非常常见）。由于 fakeTeleport 仅在
        // mode = Respawn 时为 true，这会写入一个真传送（正 id M > N），并把
        // pendingTeleportId 从 N 覆写为 M。
        // 3. 客户端先确认 N 再确认 M。旧代码只在伪分支中、且
        // pendingTeleportId == -teleportId 时才清除同步：confirm(N) 失败（pendingTeleportId == M != N），
        // 而 confirm(M) 走了真 / else 分支，该分支从不清除同步。waitingForPositionSync
        // 永远为 true -> preMove 在此处永久返回 false -> PLAYER_AUTH_INPUT 持续
        // 发送冻结的旧位置（服务端侧玩家与脚步声停在原点），
        // 而客户端仍在本地预测移动（可自由行走）。这与观测到的
        // 「客户端在走、脚步声原地不动并随距离衰减」症状一致。
        // 修复：confirmTeleport() 现在会在任意 |id| >= positionSyncTeleportId 的确认时清除同步，
        // 因此第 2 步的真传送（或第 3 步的伪确认）都能解除它。见 confirmTeleport()。
        if (this.waitingForPositionSync) {
            return false;
        }
        // 较旧的传送确认不得绕过较新的位置同步。
        if (this.serverSideTeleportConfirmed) {
            this.serverSideTeleportConfirmed = false;
            return true;
        }
        // 尚未生成或正在重生
        if (!this.initiallySpawned || this.dimensionChangeInfo != null) {
            if (!this.position.equals(newPosition)) {
                this.sendPlayerPositionPacketToClient(Relative.NONE);
            }
            return false;
        }
        // 处于未加载的区块中
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
        // 已加载 -> 未加载的区块
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
     * 失效保护网，用于后端在世界 / 维度 / 服务器切换后始终不发送解锁移动的包的罕见情况，
     * 此时服务端侧玩家被冻结而客户端仍在移动（见 ViaBedrock 移动说明中的
     * 确定性分析）。在 super.tick() 之后每个客户端刻运行一次。
     * <p>
     * 由 {@code movementWatchdog} 配置段控制：OFF = 空操作（上游行为），OBSERVE = 仅在
     * 某个锁超过超时仍卡住时记录日志，ACTIVE = 额外执行保守且幂等的
     * 恢复（重新发送后端遗漏的标准握手收尾；对于卡住的区块，重新请求
     * 区块半径——它绝不会让玩家穿过未加载的地形）。超时值远高于任何正常
     * 握手，因此正常玩家不会触发它。从不向刻循环抛出异常。
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

            // 环节 A：后端从不清除维度锁（dimensionChangeInfo）-> preMove 永久拉回。
            if (this.dimensionChangeInfo != null) {
                final int elapsed = this.age - this.dimensionChangeStartAge;
                if (this.dimensionChangeStartAge >= 0
                        && elapsed > ViaBedrock.getConfig().getMovementWatchdogDimensionChangeTimeoutTicks()
                        && !chunkTracker.isInUnloadedChunkSection(this.position, this.eyeOffset())) { // 仅在新世界真正就绪后
                    if (active) {
                        final Long loadingScreenId = this.dimensionChangeInfo.loadingScreenId();
                        // 复刻标准 ChangeDimensionAck 的收尾逻辑（ClientPlayerPackets）。幂等：
                        // 若后端之后发送真实的 ack，其 dimensionChangeInfo != null 守卫会跳过它。
                        this.sendPlayerActionPacketToServer(PlayerActionType.ChangeDimensionAck);
                        PacketFactory.sendBedrockLoadingScreen(this.user, ServerboundLoadingScreenPacketType.EndLoadingScreen, loadingScreenId);
                        this.sendPlayerPositionPacketToClient(Relative.NONE);
                        this.setDimensionChangeInfo(null);
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] A: dimension change not finalized by backend after " + elapsed + " ticks; finalized client-side");
                    } else {
                        ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[movement-watchdog] A(observe): dimension lock stuck " + elapsed + " ticks (would finalize in active mode)");
                        this.dimensionChangeStartAge = this.age; // 重新设定基准，使 OBSERVE 每个超时窗口最多记录一次
                    }
                }
                return; // 维度切换期间不再同时运行区块看门狗
            }

            // 环节 B：卡在未加载的区块段（区块从未下发）-> preMove 永久拉回。
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
                        // 只重新请求区块；绝不放松移动闸门（放松会让玩家穿墙）。
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
            // 绝不让看门狗破坏刻循环。
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

