// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum ComplexInventoryTransaction_Type {

    /** 在启用 ItemStackNetManager 时用于容器 UI 操作而发送 */
    NormalTransaction(0),
    /** 由服务端发往客户端，用于拒绝一次物品栏事务 */
    InventoryMismatch(1),
    /** 玩家执行右键式物品使用时发送。预期用途见其中 ItemUseInventoryTransaction::ActionType。 */
    ItemUseTransaction(2),
    /** 玩家右键点击实体或攻击实体时发送。具体是哪一种见 ItemUseInventoryTransaction::ActionType。 */
    ItemUseOnEntityTransaction(3),
    /**
     * 对弓之类可蓄力物品松开右键，或是弩之类完成蓄力时发送。这与提前取消物品使用不同，后者会在 Player Auth Input 中出现。<br>
     * 具体是哪一种见 ItemReleaseInventoryTransaction::ActionType。
     */
    ItemReleaseTransaction(4),
    ;

    private static final Int2ObjectMap<ComplexInventoryTransaction_Type> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (ComplexInventoryTransaction_Type value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static ComplexInventoryTransaction_Type getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static ComplexInventoryTransaction_Type getByValue(final int value, final ComplexInventoryTransaction_Type fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static ComplexInventoryTransaction_Type getByName(final String name) {
        for (ComplexInventoryTransaction_Type value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static ComplexInventoryTransaction_Type getByName(final String name, final ComplexInventoryTransaction_Type fallback) {
        for (ComplexInventoryTransaction_Type value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    ComplexInventoryTransaction_Type(final ComplexInventoryTransaction_Type value) {
        this(value.value);
    }

    ComplexInventoryTransaction_Type(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
