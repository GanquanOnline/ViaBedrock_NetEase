// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum PlayerAuthInputPacket_InputData {

    /** 向上飞的触摸输入，类似 WantUp */
    Ascend(0),
    /** 向下飞的触摸输入，类似 WantDown */
    Descend(1),
    /** 未使用 */
    NorthJump_DEPRECATED(2),
    /** 跳跃输入是否按下。不一定意味着玩家正在跳跃。 */
    JumpDown(3),
    /** 疾跑输入是否按下。不一定意味着玩家想开始疾跑 */
    SprintDown(4),
    /** 向下飞的触摸输入 */
    ChangeHeight(5),
    /** 跳跃输入按下或自动跳跃时为真，即使在飞行、游泳等非跳跃情形下也如此 */
    Jumping(6),
    /** 接触水面时正在触发自动跳跃则为真。若已正确处理 Jumping 可忽略 */
    AutoJumpingInWater(7),
    /** 玩家是否正在潜行，可能来自输入，也可能因为空间不足以站立 */
    Sneaking(8),
    /** 潜行输入是否按下；取决于输入权限以及玩家是否改为爬行，这不代表玩家正在潜行 */
    SneakDown(9),
    /** 本地空间的向上输入。等价于移动输入 Y 为正。 */
    Up(10),
    /** 本地空间的向下输入。等价于移动输入 Y 为负。 */
    Down(11),
    /** 本地空间的向左输入。等价于移动输入 X 为负。 */
    Left(12),
    /** 本地空间的向右输入。等价于移动输入 X 为正。 */
    Right(13),
    /** 本地空间的左上对角。等价于移动输入 (-1, 1) 归一化。 */
    UpLeft(14),
    /** 本地空间的右上对角。等价于移动输入 (1, 1) 归一化。 */
    UpRight(15),
    /** 向上飞行，例如按住空格，适用于所有输入模式 */
    WantUp(16),
    /** 向下飞行，例如按住 shift，适用于所有输入模式 */
    WantDown(17),
    /** 手柄的备用向下飞行输入 */
    WantDownSlow(18),
    /** 手柄的备用向上飞行输入 */
    WantUpSlow(19),
    /** 客户端是否认为自己正在疾跑。该值的变化以开始和停止疾跑动作的形式传入 */
    Sprinting(20),
    /** 触摸专用的攀爬脚手架上升输入 */
    AscendBlock(21),
    /** 触摸专用的攀爬脚手架下降输入 */
    DescendBlock(22),
    /** 触摸与手柄下按住潜行切换键时设置。切换状态见 Sneaking。 */
    SneakToggleDown(23),
    /** 使用触摸输入时始终为 true */
    PersistSneak(24),
    /**
     * 玩家想开始疾跑时设置，例如连续两次点击前进。<br>
     * 服务器应回复带 ActorFlags::SPRINTING 的 SetActorDataPacket，以及用于应用疾跑加速的 UpdateAttributesPacket。
     */
    StartSprinting(25),
    /**
     * 玩家想停止疾跑时发送，例如疾跑时松开前进输入。<br>
     * 服务器应回复带 ActorFlags::SPRINTING 的 SetActorDataPacket，以及清除疾跑速度加成的 UpdateAttributesPacket。
     */
    StopSprinting(26),
    /**
     * 玩家想开始潜行时发送，例如按下 shift。<br>
     * 服务器若接受应回复 ActorFlags::SNEAKING 为 true 的 SetActorDataPacket，拒绝则为 false，并附带碰撞箱更新。
     */
    StartSneaking(27),
    /**
     * 玩家想要停止潜行（例如松开 shift）时发送。<br>
     * 若被接受，服务端应以 SetActorDataPacket 响应，把 ActorFlags::SNEAKING 置为 false；若被拒绝则置为 true，并附带包围盒更新。
     */
    StopSneaking(28),
    /**
     * 玩家想要进入游泳模式（例如在水中前进时按下 control）时发送。<br>
     * 若被接受，服务端应以 SetActorDataPacket 响应，把 ActorFlags::SWIMMING 置为 true；若被拒绝则置为 false，并附带包围盒更新。
     */
    StartSwimming(29),
    /**
     * 玩家想要退出游泳模式（例如游泳时松开前进输入）时发送。<br>
     * 若被接受，服务端应以 SetActorDataPacket 响应，把 ActorFlags::SWIMMMING 置为 false；若被拒绝则置为 true，并附带包围盒更新。
     */
    StopSwimming(30),
    /** 在客户端触发非载具跳跃的那一刻设置 */
    StartJumping(31),
    /**
     * 玩家想要开始鞘翅滑翔（例如在空中按下空格键）时发送。<br>
     * 若被接受，服务端应以 SetActorDataPacket 响应，把 ActorFlags::GLIDING 置为 true；若被拒绝则置为 false，并附带包围盒更新。
     */
    StartGliding(32),
    /**
     * 玩家正在鞘翅滑翔但预期它要停止（例如接触到地面）时发送。<br>
     * 若被接受，服务端应以 SetActorDataPacket 响应，把 ActorFlags::GLIDING 置为 false；若被拒绝则置为 true，并附带包围盒更新。
     */
    StopGliding(33),
    /** 表示 mItemUseTransaction 将被写入该包 */
    PerformItemInteraction(34),
    /** 表示 mPlayerBlockActions 将被写入该包 */
    PerformBlockActions(35),
    /** 表示 mItemStackRequest 将被写入该包 */
    PerformItemStackRequest(36),
    /**
     * 用于告知服务端我们已收到导致传送的 MovePlayerPacket，并重新启用客户端权威移动。<br>
     * 从发出 MovePlayerPacket 起直到收到该动作为止，服务端应忽略任何客户端预测的位置。
     */
    HandledTeleport(37),
    /** 若玩家当前正在播放表情动作，见 EmotePacket */
    Emoting(38),
    /**
     * 客户端想要播放挥臂动画（例如左键）时发送。<br>
     * 服务端应广播一个动作为 LevelSoundEvent::AttackNoDamage 的 LevelSoundEventPacket。
     */
    MissedSwing(39),
    /**
     * 玩家处于站立状态但认为没有足够空间站立时发送。<br>
     * 服务端应响应包含包围盒更新的 SetActorDataPacket。<br>
     * 若被接受，服务端应响应把 ActorFlags::CRAWLING 置为 true 的 SetActorDataPacket；若被拒绝则置为 false。
     */
    StartCrawling(40),
    /**
     * 玩家原本在爬行但认为有空间站立时发送。<br>
     * 服务端应响应包含包围盒更新的 SetActorDataPacket。<br>
     * 若被接受，服务端应响应把 ActorFlags::CRAWLING 置为 false 的 SetActorDataPacket；若被拒绝则置为 true。
     */
    StopCrawling(41),
    /**
     * 玩家预期飞行被开启（例如双击空格键）时发送。<br>
     * 服务端应响应 UpdateAbilitiesPacket 以接受或拒绝。
     */
    StartFlying(42),
    /**
     * 玩家预期飞行被关闭（例如双击空格键）时发送。<br>
     * 服务端应响应 UpdateAbilitiesPacket 以接受或拒绝。
     */
    StopFlying(43),
    /** 按 StartGamePacket 中规定使用服务端权威移动时不发送 */
    ClientAckServerData(44),
    /**
     * 当客户端在控制客户端预测载具时用于发送输入。即马和船。<br>
     * 若设置，将写入 Vehicle Rotation 与 Client Predicted Vehicle
     */
    IsInClientPredictedVehicle(45),
    /**
     * 玩家在船中并按住划桨输入。<br>
     * 服务端应响应 SetActorDataPacket 对船的 ROW_TIME_LEFT 的更新<br>
     * 详见 Player Auth Input
     */
    PaddlingLeft(46),
    /**
     * 玩家在船中并按住划桨输入。<br>
     * 服务端应响应 SetActorDataPacket 对船的 ROW_TIME_RIGHT 的更新<br>
     * 详见 Player Auth Input
     */
    PaddlingRight(47),
    /** 对创造模式下的触摸输入：若当前模式下的方块破坏应带延迟则为 true，应立即发生则为 false。 */
    BlockBreakingDelayEnabled(48),
    /**
     * 客户端预测到水平碰撞时设置。用于参与客户端接受逻辑。<br>
     * 可作为给服务端的提示，或按期望的严格程度忽略
     */
    HorizontalCollision(49),
    /**
     * 客户端预测到垂直碰撞时设置。用于参与客户端接受逻辑。<br>
     * 可作为给服务端的提示，或按期望的严格程度忽略。<br>
     * 与玩家的“在地面”状态强相关
     */
    VerticalCollision(50),
    /** 本地空间的左下对角方向。等价于移动输入 (-1, -1) 归一化。 */
    DownLeft(51),
    /** 本地空间的右下对角方向。等价于移动输入 (1, -1) 归一化。 */
    DownRight(52),
    /**
     * 当客户端预测到物品使用动画开始的那一刻设置，例如为三叉戟抬臂或喝药水。<br>
     * 同一刻会有一个类型为 ComplexInventoryTransaction::Type::ItemUseTransaction 的 InventoryTransactionPacket。<br>
     * 若同意，服务端应响应包含 ActorFlags::USINGITEM 为 true 的 SetActorDataPacket，否则为 false。
     */
    StartUsingItem(53),
    /** 未使用 */
    IsCameraRelativeMovementEnabled_DEPRECATED(54),
    /** 未使用 */
    IsRotControlledByMoveDirection_DEPRECATED(55),
    /**
     * 当客户端预测激流旋转攻击开始的那一刻设置，此时 PlayerActionComponent 中设置了 PlayerActionType::StartSpinAttack。<br>
     * 并在 SetActorDataPacket 中把 ActorFlags::DAMAGENEARBYMOBS 置为 true
     */
    StartSpinAttack(56),
    /**
     * 当客户端认为激流旋转攻击已结束的那一刻设置，此时 PlayerActionComponent 中设置了 PlayerActionType::StopSpinAttack<br>
     * 并在 SetActorDataPacket 中把 ActorFlags::DAMAGENEARBYMOBS 置为 false
     */
    StopSpinAttack(57),
    /** 表示触摸是否只允许在触摸栏中使用，而不可用于游戏内操作。 */
    IsHotbarOnlyTouch(58),
    /**
     * 表示自上一个包以来跳跃按钮是否被松开。<br>
     * 即使输入权限被禁用也会发送。
     */
    JumpReleasedRaw(59),
    /**
     * 表示自上一个包以来跳跃按钮是否被按下。<br>
     * 即使输入权限被禁用也会发送。
     */
    JumpPressedRaw(60),
    /**
     * 表示跳跃按钮当前是否按下。<br>
     * 即使输入权限被禁用也会发送。
     */
    JumpCurrentRaw(61),
    /**
     * 表示自上一个包以来潜行按钮是否被松开。<br>
     * 即使输入权限被禁用也会发送。
     */
    SneakReleasedRaw(62),
    /**
     * 表示自上一个包以来潜行按钮是否被按下。<br>
     * 即使输入权限被禁用也会发送。
     */
    SneakPressedRaw(63),
    /**
     * 表示潜行按钮当前是否按下。<br>
     * 即使输入权限被禁用也会发送。
     */
    SneakCurrentRaw(64),
    ;

    private static final Int2ObjectMap<PlayerAuthInputPacket_InputData> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (PlayerAuthInputPacket_InputData value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static PlayerAuthInputPacket_InputData getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static PlayerAuthInputPacket_InputData getByValue(final int value, final PlayerAuthInputPacket_InputData fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static PlayerAuthInputPacket_InputData getByName(final String name) {
        for (PlayerAuthInputPacket_InputData value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static PlayerAuthInputPacket_InputData getByName(final String name, final PlayerAuthInputPacket_InputData fallback) {
        for (PlayerAuthInputPacket_InputData value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    PlayerAuthInputPacket_InputData(final PlayerAuthInputPacket_InputData value) {
        this(value.value);
    }

    PlayerAuthInputPacket_InputData(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
