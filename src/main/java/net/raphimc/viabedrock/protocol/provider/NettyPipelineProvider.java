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
package net.raphimc.viabedrock.protocol.provider;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.providers.Provider;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.State;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PacketCompressionAlgorithm;

import javax.crypto.SecretKey;

public abstract class NettyPipelineProvider implements Provider {

    /** 只由真实前端协议确定能否原样回送完整 int Ping ID。 */
    public boolean supportsFullJavaPingIds(final UserConnection user) {
        return false;
    }

    /**
     * 返回给定状态的 Java 服务端→客户端数据包能否注入
     * 平台管线而不跨越协议状态转换。
     *
     * <p>ViaBedrock 生成的数据包会经平台的服务端→客户端管线回灌。
     * 默认守卫要求 Via 的两个协议方向都一致。保留第二套
     * 数据包注册表的平台（例如 ViaProxy 的 NetMinecraft 解码器）
     * 还必须在其覆写中校验该注册表。</p>
     *
     * @param user  用户
     *
     * @param state 将要注入的 Java 数据包的状态
     *
     * @return 平台管线是否已为该数据包状态就绪
     */
    public boolean isJavaClientboundStateReady(final UserConnection user, final State state) {
        return user.getProtocolInfo().getClientState() == state
                && user.getProtocolInfo().getServerState() == state;
    }

    /**
     * 标记同步注入的 Java 服务端→客户端数据包的来源，用于平台级
     * 协议诊断。实现不得保留玩家内容。
     */
    public void beginJavaClientboundPacket(final UserConnection user, final String origin,
                                           final PacketType packetType) {
    }

    /** 清除由 {@link #beginJavaClientboundPacket(UserConnection, String, PacketType)} 安装的标记。 */
    public void endJavaClientboundPacket(final UserConnection user) {
    }

    /**
     * 返回代理与基岩服务器之间的传输 RTT。
     *
     * @param user 用户
     *
     * @return 以毫秒计的 RTT，不可用时为 {@code -1}
     */
    public int getServerTransportLatencyMillis(final UserConnection user) {
        return -1;
    }

    /**
     * 为给定用户启用压缩/解压。同一用户可能被多次调用。
     *
     * @param user                          用户
     *
     * @param preferredCompressionAlgorithm 首选的压缩算法
     *
     * @param threshold                     压缩阈值
     */
    public abstract void enableCompression(final UserConnection user, final PacketCompressionAlgorithm preferredCompressionAlgorithm, final int threshold);

    /**
     * 为给定用户启用加密/解密
     *
     * @param user 用户
     *
     * @param key  加密密钥
     */
    public abstract void enableEncryption(final UserConnection user, final SecretKey key);

}
