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
     * Returns whether a Java clientbound packet for the given state can be injected into the
     * platform pipeline without crossing a protocol-state transition.
     *
     * <p>ViaBedrock-generated packets are fed back through the platform's clientbound pipeline.
     * The default guard requires both Via protocol directions to agree. Platforms which keep a
     * second packet registry (for example ViaProxy's NetMinecraft decoder) must additionally
     * verify that registry in their override.</p>
     *
     * @param user  The user
     * @param state The state of the Java packet that will be injected
     * @return Whether the platform pipeline is ready for that packet state
     */
    public boolean isJavaClientboundStateReady(final UserConnection user, final State state) {
        return user.getProtocolInfo().getClientState() == state
                && user.getProtocolInfo().getServerState() == state;
    }

    /**
     * Marks the origin of a synchronously injected Java clientbound packet for platform-level
     * protocol diagnostics. Implementations must not retain player content.
     */
    public void beginJavaClientboundPacket(final UserConnection user, final String origin,
                                           final PacketType packetType) {
    }

    /**
     * Clears a marker installed by {@link #beginJavaClientboundPacket(UserConnection, String, PacketType)}.
     */
    public void endJavaClientboundPacket(final UserConnection user) {
    }

    /**
     * Returns the transport RTT between the proxy and Bedrock server.
     *
     * @param user The user
     * @return RTT in milliseconds, or {@code -1} when unavailable
     */
    public int getServerTransportLatencyMillis(final UserConnection user) {
        return -1;
    }

    /**
     * Enables compression/decompression for the given user. May get called multiple times for the same user.
     *
     * @param user                          The user
     * @param preferredCompressionAlgorithm The preferred compression algorithm
     * @param threshold                     The compression threshold
     */
    public abstract void enableCompression(final UserConnection user, final PacketCompressionAlgorithm preferredCompressionAlgorithm, final int threshold);

    /**
     * Enables encryption/decryption for the given user
     *
     * @param user The user
     * @param key  The encryption key
     */
    public abstract void enableEncryption(final UserConnection user, final SecretKey key);

}
