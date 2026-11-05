// 本文件由程序自动生成，请勿编辑！
package net.raphimc.viabedrock.protocol.data.enums.bedrock.generated;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;

public enum ItemReleaseInventoryTransaction_ActionType {

    /** 松开右键长按式物品使用，例如射箭 */
    Release(0),
    /** 完成右键长按式物品使用，例如给弩蓄力 */
    Use(1),
    ;

    private static final Int2ObjectMap<ItemReleaseInventoryTransaction_ActionType> BY_VALUE = new Int2ObjectOpenHashMap<>();

    static {
        for (ItemReleaseInventoryTransaction_ActionType value : values()) {
            if (!BY_VALUE.containsKey(value.value)) {
                BY_VALUE.put(value.value, value);
            }
        }
    }

    public static ItemReleaseInventoryTransaction_ActionType getByValue(final int value) {
        return BY_VALUE.get(value);
    }

    public static ItemReleaseInventoryTransaction_ActionType getByValue(final int value, final ItemReleaseInventoryTransaction_ActionType fallback) {
        return BY_VALUE.getOrDefault(value, fallback);
    }

    public static ItemReleaseInventoryTransaction_ActionType getByName(final String name) {
        for (ItemReleaseInventoryTransaction_ActionType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return null;
    }

    public static ItemReleaseInventoryTransaction_ActionType getByName(final String name, final ItemReleaseInventoryTransaction_ActionType fallback) {
        for (ItemReleaseInventoryTransaction_ActionType value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return value;
            }
        }
        return fallback;
    }

    private final int value;

    ItemReleaseInventoryTransaction_ActionType(final ItemReleaseInventoryTransaction_ActionType value) {
        this(value.value);
    }

    ItemReleaseInventoryTransaction_ActionType(final int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}
