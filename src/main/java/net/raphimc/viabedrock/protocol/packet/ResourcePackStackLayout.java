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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Bedrock RESOURCE_PACK_STACK（数据包 0x07）的行为包栈辅助方法。
 * <p>
 * Nukkit-MOT 只在会话协议低于 {@code 1.21.130.28}（897）时先序列化行为包栈，
 * 与是否仿真网易无关：网易 860 带这一段，网易 898 已移除。因此这个栈只由
 * 协议号决定；网易轴原来那条 {@code BEHAVIOUR_PACK_STACK_TRAILER} 偏差行
 * 现在与协议号判断重合（枚举顺序决定位掩码，只能追加，行保留）。
 * 多读或少读这一段都会让后面的资源包条目整体错位，最后以字符串长度越界的形式失败。
 */
public final class ResourcePackStackLayout {

    public static final int BEHAVIOUR_PACK_STACK_PROTOCOL = 897;

    private ResourcePackStackLayout() {
    }

    public static boolean sendsBehaviourPackStack() {
        return sendsBehaviourPackStack(BedrockWire.effectiveProtocol());
    }

    public static boolean sendsBehaviourPackStack(final int protocol) {
        return protocol < BEHAVIOUR_PACK_STACK_PROTOCOL;
    }

    /** 旧名与旧签名的布尔参数只为兼容既有调用点，布局只由协议号决定。 */
    public static boolean sendsNetEaseBehaviourPackStack() {
        return sendsBehaviourPackStack();
    }

    public static boolean sendsNetEaseBehaviourPackStack(final boolean emulateNetEase, final int protocol) {
        return sendsBehaviourPackStack(protocol);
    }

    /**
     * 跳过行为包栈，返回跳过的条目数。
     */
    public static int skipBehaviourPackStack(final PacketWrapper wrapper) {
        final int count = wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
        for (int i = 0; i < count; i++) {
            wrapper.read(BedrockTypes.STRING); // 包 ID
            wrapper.read(BedrockTypes.STRING); // 包版本
            wrapper.read(BedrockTypes.STRING); // 子包名称
        }
        return count;
    }

    public static int skipBehaviourPackStack(final ByteBuf buffer) {
        final int count = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
        for (int i = 0; i < count; i++) {
            BedrockTypes.STRING.read(buffer);
            BedrockTypes.STRING.read(buffer);
            BedrockTypes.STRING.read(buffer);
        }
        return count;
    }
}
