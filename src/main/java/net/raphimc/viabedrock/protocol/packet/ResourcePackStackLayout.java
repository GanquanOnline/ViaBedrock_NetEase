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
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * Bedrock RESOURCE_PACK_STACK（数据包 0x07）的网易前缀辅助方法。
 * <p>
 * Nukkit-MOT 只在会话协议低于 {@code 1.21.130.28}（897）时先序列化行为包栈；
 * 网易 860（{@code V1_21_124_NETEASE}）带这一段，网易 898（{@code V1_21_130_NETEASE}）已移除。
 * 多读或少读这一段都会让后面的资源包条目整体错位，最后以字符串长度越界的形式失败。
 */
public final class ResourcePackStackLayout {

    public static final int NETEASE_BEHAVIOUR_STACK_END_PROTOCOL = 897;

    private ResourcePackStackLayout() {
    }

    public static boolean sendsNetEaseBehaviourPackStack() {
        return sendsNetEaseBehaviourPackStack(emulateNetEase(), netEaseProtocol());
    }

    public static boolean sendsNetEaseBehaviourPackStack(final boolean emulateNetEase, final int protocol) {
        return emulateNetEase && protocol < NETEASE_BEHAVIOUR_STACK_END_PROTOCOL;
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

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
