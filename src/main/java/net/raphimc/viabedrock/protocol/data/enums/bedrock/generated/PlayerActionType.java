// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum PlayerActionType {

    Unknown(-1),
    /** 在 Player Auth Input 的 Block Actions 中携带位置与朝向发送 */
    StartDestroyBlock(0),
    /** 在 Player Auth Input 的 Block Actions 中携带位置与朝向发送 */
    AbortDestroyBlock(1),
    /** 在 Player Auth Input 的 Block Actions 中发送，不带额外数据 */
    StopDestroyBlock(2),
    GetUpdatedBlock(3),
    DropItem(4),
    /** 在 Player Action 中发送 */
    StartSleeping(5),
    /** 在 Player Action 中发送 */
    StopSleeping(6),
    /** 在 Player Action 中发送 */
    Respawn(7),
    /**
     * 在玩家触发跳跃的那一 tick 设置。<br>
     * 对应 Player Auth Input InputData::StartJumping 位 31
     */
    StartJump(8),
    /**
     * 玩家想开始疾跑时设置，例如连续两次点击前进。<br>
     * 服务器应回复带 ActorFlags::SPRINTING 的 SetActorDataPacket，以及用于应用疾跑加速的 UpdateAttributesPacket。<br>
     * 对应 Player Auth Input InputData::StartSprinting 位 25
     */
    StartSprinting(9),
    /**
     * 玩家想停止疾跑时发送，例如疾跑时松开前进输入。<br>
     * 服务器应回复带 ActorFlags::SPRINTING 的 SetActorDataPacket，以及清除疾跑速度加成的 UpdateAttributesPacket。<br>
     * 对应 Player Auth Input InputData::StopSprinting 位 26
     */
    StopSprinting(10),
    /**
     * 玩家想开始潜行时发送，例如按下 shift。<br>
     * 服务器若接受应回复 ActorFlags::SNEAKING 为 true 的 SetActorDataPacket，拒绝则为 false，并附带碰撞箱更新。<br>
     * 对应 Player Auth Input InputData::StartSneaking 位 27
     */
    StartSneaking(11),
    /**
     * 玩家想停止潜行时发送，例如松开 shift。<br>
     * 服务器若接受应回复 ActorFlags::SNEAKING 为 false 的 SetActorDataPacket，拒绝则为 true，并附带碰撞箱更新。<br>
     * 对应 Player Auth Input InputData::StopSneaking 位 28
     */
    StopSneaking(12),
    /**
     * 在创造模式下尝试破坏方块时发送，例如左键点击它。期望服务器破坏该方块，并可选地下发新的方块或区块信息。<br>
     * 曾经是 ChangeDimension 动作。<br>
     * 在 Player Action 中发送。
     */
    CreativeDestroyBlock(13),
    /** 在 Player Action 中发送；这是服务端向客户端发送动作以开始切换维度的唯一情形。 */
    ChangeDimensionAck(14),
    /**
     * 玩家想开始鞘翅滑翔时发送，例如在空中按下空格。<br>
     * 服务器若接受应回复 ActorFlags::GLIDING 为 true 的 SetActorDataPacket，拒绝则为 false，并附带碰撞箱更新。<br>
     * 对应 Player Auth Input InputData::StartGliding 位 32
     */
    StartGliding(15),
    /**
     * 玩家正在鞘翅滑翔但期望停止时发送，例如接触地面时。<br>
     * 服务器若接受应回复 ActorFlags::GLIDING 为 false 的 SetActorDataPacket，拒绝则为 true，并附带碰撞箱更新。<br>
     * 对应 Player Auth Input InputData::StopGliding 位 33
     */
    StopGliding(16),
    /**
     * 客户端认为自己在该位置不被允许破坏方块、并希望得到拒绝粒子效果时发送。<br>
     * 在教育版中于 Player Action 发送
     */
    DenyDestroyBlock(17),
    /**
     * 客户端期望服务器广播带相应裂纹事件的 LevelEventPacket 作为回应。<br>
     * 仅在 StartGamePacket 中禁用服务端权威破坏方块时发送。<br>
     * 在 Player Auth Input 的 Block Actions 中携带位置与朝向发送。
     */
    CrackBlock(18),
    ChangeSkin(19),
    UpdatedEnchantingSeed(20),
    /**
     * 玩家想进入游泳模式时发送，例如在水中前进时按下 control。<br>
     * 服务器若接受应回复 ActorFlags::SWIMMING 为 true 的 SetActorDataPacket，拒绝则为 false，并附带碰撞箱更新。<br>
     * 对应 Player Auth Input InputData::StartSwimming 位 29
     */
    StartSwimming(21),
    /**
     * 玩家想退出游泳模式时发送，例如游泳时松开前进输入。<br>
     * 服务器若接受应回复 ActorFlags::SWIMMMING 为 false 的 SetActorDataPacket，拒绝则为 true，并附带碰撞箱更新。<br>
     * 对应 Player Auth Input InputData::StopSwimming 位 30
     */
    StopSwimming(22),
    /**
     * 在客户端预测三叉戟激流旋转攻击开始的那一 tick 发送。会伴随一个类型为 ComplexInventoryTransaction::Type::ItemUseTransaction 的 InventoryTransactionPacket。<br>
     * 服务器若接受应发送 ActorFlags::DAMAGENEARBYMOBS 为 true 的 SetActorDataPacket，拒绝则为 false，并附带碰撞箱更新。<br>
     * 在 Player Action 中发送，但很快会改为 Player Auth Input InputData 的一位
     */
    StartSpinAttack(23),
    /**
     * 客户端认为三叉戟激流旋转攻击已结束时发送。<br>
     * 服务器若接受应发送 ActorFlags::DAMAGENEARBYMOBS 为 false 的 SetActorDataPacket，拒绝则为 true，并附带碰撞箱更新。<br>
     * 在 Player Action 中发送，但很快会改为 Player Auth Input InputData 的一位
     */
    StopSpinAttack(24),
    InteractWithBlock(25),
    /**
     * 在 Player Auth Input 的 Block Actions 中携带位置与朝向发送。<br>
     * 用于客户端告知服务器它预测了玩家破坏方块。<br>
     * 若服务器不认同，可以下发方块、区块或物品信息；若不回应则表示认同。<br>
     * 仅在 StartGamePacket 指定的服务端权威破坏方块开关打开时使用
     */
    PredictDestroyBlock(26),
    /**
     * 在 Player Auth Input 的 Block Actions 中携带位置与朝向发送。<br>
     * 用于告知服务器客户端当前破坏的方块已改变。<br>
     * 服务器应据此推进方块破坏进度，并等待接下来到达的 PredictDestroyBlock 动作。<br>
     * 服务器还应广播被破坏方块裂痕的 LevelEventPackets。<br>
     * 仅在 StartGamePacket 指定的服务端权威破坏方块开关打开时发送
     */
    ContinueDestroyBlock(27),
    /**
     * 开始右键长按式物品使用时发送。<br>
     * 在 Player Action 中发送。<br>
     * 服务器可预期它随一个内含 ItemUseInventoryTransaction 的 InventoryTransactionPacket 到达。
     */
    StartItemUseOn(28),
    /**
     * 松开右键长按式物品使用时发送。这是取消该动作，与射箭不同，后者会是内含 ItemUseInventoryTransaction 的 InventoryTransactionPacket。<br>
     * 在 Player Action 中发送
     */
    StopItemUseOn(29),
    /**
     * 用于告知服务器我们已收到引发传送的 MovePlayerPacket，并重新启用客户端权威移动。<br>
     * 从发出 MovePlayerPacket 起直到收到本动作，服务器应忽略客户端预测的位置。<br>
     * 对应 Player Auth Input InputData::HandledTeleport 位 37
     */
    HandledTeleport(30),
    /**
     * 客户端想播放手臂挥动动画时发送，例如左键点击。<br>
     * 服务器应广播带 LevelSoundEvent::AttackNoDamage 的 LevelSoundEventPacket。<br>
     * 对应 Player Auth Input InputData::MissedSwing 位 39
     */
    MissedSwing(31),
    /**
     * 玩家正在站立但认为自己没有足够空间站立时发送。<br>
     * 服务器应回复包含碰撞箱更新的 SetActorDataPacket。<br>
     * 服务器若接受应回复 ActorFlags::CRAWLING 为 true 的 SetActorDataPacket，拒绝则为 false。<br>
     * 对应 Player Auth Input InputData::StartCrawling 位 40
     */
    StartCrawling(32),
    /**
     * 玩家正在爬行且认为有空间站立时发送。<br>
     * 服务器应回复包含碰撞箱更新的 SetActorDataPacket。<br>
     * 服务器若接受应回复 ActorFlags::CRAWLING 为 false 的 SetActorDataPacket，拒绝则为 true。<br>
     * 对应 Player Auth Input InputData::StopCrawling 位 41
     */
    StopCrawling(33),
    /**
     * 玩家期望开启飞行时发送，例如双击空格。<br>
     * 服务器应回复 UpdateAbilitiesPacket 以接受或拒绝。<br>
     * 对应 Player Auth Input InputData::StartFlying 位 42
     */
    StartFlying(34),
    /**
     * 玩家期望关闭飞行时发送，例如双击空格。<br>
     * 服务器应回复 UpdateAbilitiesPacket 以接受或拒绝。<br>
     * 对应 Player Auth Input InputData::StopFlying 位 43
     */
    StopFlying(35),
    ClientAckServerData(36),
    /** 用于告知服务器我们正在预测使用物品。 */
    StartUsingItem(37),
    ;

    private static final Int2ObjectMap<PlayerActionType> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (PlayerActionType value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static PlayerActionType getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static PlayerActionType getByValue(final int value, final PlayerActionType fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static PlayerActionType getByName(final String name) {
        for (PlayerActionType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static PlayerActionType getByName(final String name, final PlayerActionType fallback) {
        for (PlayerActionType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    PlayerActionType(final PlayerActionType value) {
        this(value.value);
    }

    PlayerActionType(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
