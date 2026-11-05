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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * 基岩 TOAST_REQUEST（186 号包）的线路布局辅助方法。
 * <p>
 * Nukkit-MOT {@code ToastRequestPacket.encode()} 写入 {@code string title + string content}，
 * 不按协议版本分支。Java 1.21.11 没有原生 toast 包，因此 ViaBedrock
 * 把它投影到 {@code SET_ACTION_BAR_TEXT}。MOT {@code Player.sendToast()} 在协议
 * 527 以下本来就回退到 {@code sendTitle(title, content)}；动作栏是
 * 最接近且不会抢占当前标题/副标题的 Java HUD。
 */
public final class ToastRequestLayout {

    private ToastRequestLayout() {
    }

    public static void write(final ByteBuf buffer, final String title, final String content) {
        BedrockTypes.STRING.write(buffer, title != null ? title : "");
        BedrockTypes.STRING.write(buffer, content != null ? content : "");
    }

    public static Packet read(final ByteBuf buffer) {
        final String title = BedrockTypes.STRING.read(buffer);
        final String content = BedrockTypes.STRING.read(buffer);
        return new Packet(title, content);
    }

    public static Packet read(final PacketWrapper wrapper) {
        final String title = wrapper.read(BedrockTypes.STRING);
        final String content = wrapper.read(BedrockTypes.STRING);
        return new Packet(title, content);
    }

    /**
     * 按 MOT {@code sendTitle(title, content)} 在 toast 不可用时的方式合并标题与内容：
     * 丢弃空 / 空白部分，剩余部分用空格连接。两者都为空时产出单个空格，
     * 使 Java 仍收到一个可见的（空白）动作栏数据包，与 MOT 的 {@code " "}
     * 回退一致。
     */
    public static String actionBarText(final String title, final String content) {
        final String trimmedTitle = title != null ? title.strip() : "";
        final String trimmedContent = content != null ? content.strip() : "";
        if (trimmedTitle.isEmpty() && trimmedContent.isEmpty()) {
            return " ";
        }
        if (trimmedTitle.isEmpty()) {
            return trimmedContent;
        }
        if (trimmedContent.isEmpty()) {
            return trimmedTitle;
        }
        return trimmedTitle + " " + trimmedContent;
    }

    public record Packet(String title, String content) {
    }
}
