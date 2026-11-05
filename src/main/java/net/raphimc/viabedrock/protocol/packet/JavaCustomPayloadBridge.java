/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.JavaCustomPayloadRateLimitStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** 双向桥接 Java 自定义载荷与基岩 ScriptMessage。 */
public final class JavaCustomPayloadBridge {

    public static final String SCRIPT_MESSAGE_ID = "easecation:java_custom_payload_v1";

    private JavaCustomPayloadBridge() {
    }

    public static void bridgeServerbound(final String channel, final PacketWrapper wrapper) {
        if (!ViaBedrock.getConfig().shouldBridgeJavaCustomPayloads()) {
            return;
        }
        if (wrapper.user().getProtocolInfo().getClientState() != State.PLAY
                || wrapper.user().getProtocolInfo().getServerState() != State.PLAY) {
            return;
        }

        try {
            final byte[] channelBytes = channel.getBytes(StandardCharsets.UTF_8);
            if (!isWithinLimits(channelBytes, new byte[0])) {
                return;
            }
            if (!(wrapper instanceof PacketWrapperImpl packetWrapper)) {
                return;
            }
            final ByteBuf inputBuffer = packetWrapper.getInputBuffer();
            if (inputBuffer == null || inputBuffer.readableBytes()
                    > ViaBedrock.getConfig().getJavaCustomPayloadMaxPayloadBytes()) {
                return;
            }
            final byte[] payload = copyRemainingPayload(inputBuffer);

            final JavaCustomPayloadRateLimitStorage rateLimit = wrapper.user().get(JavaCustomPayloadRateLimitStorage.class);
            if (rateLimit == null || !rateLimit.tryAcquire(System.nanoTime(), payload.length,
                    ViaBedrock.getConfig().getJavaCustomPayloadMaxMessagesPerMinute(),
                    ViaBedrock.getConfig().getJavaCustomPayloadMaxBytesPerMinute())) {
                return;
            }

            final PacketWrapper scriptMessage = PacketWrapper.create(ServerboundBedrockPackets.SCRIPT_MESSAGE, wrapper.user());
            scriptMessage.write(BedrockTypes.STRING, SCRIPT_MESSAGE_ID);
            scriptMessage.write(BedrockTypes.STRING, encodeEnvelope(channelBytes, payload));
            scriptMessage.sendToServer(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().warning("Unable to bridge a Java custom payload: " + e.getClass().getSimpleName());
        }
    }

    /** @return 当该 ScriptMessage 属于此桥接且已被消费时返回 true */
    public static boolean bridgeClientbound(final String messageId, final String encodedValue, final UserConnection user) {
        if (!SCRIPT_MESSAGE_ID.equals(messageId)) {
            return false;
        }
        if (!ViaBedrock.getConfig().shouldBridgeJavaCustomPayloads()) {
            return true;
        }
        if (user.getProtocolInfo().getClientState() != State.PLAY || user.getProtocolInfo().getServerState() != State.PLAY) {
            return true;
        }

        try {
            final DecodedPayload decoded = decodeEnvelope(encodedValue);
            if (!isWithinLimits(decoded.channel().getBytes(StandardCharsets.UTF_8), decoded.payload())) {
                return true;
            }
            final ChannelStorage channels = user.get(ChannelStorage.class);
            if (channels == null || !channels.hasChannel(decoded.channel())) {
                return true;
            }

            final PacketWrapper payload = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
            payload.write(Types.STRING, decoded.channel());
            payload.write(Types.REMAINING_BYTES, decoded.payload());
            payload.scheduleSend(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().warning("Unable to bridge a backend ScriptMessage: " + e.getClass().getSimpleName());
        }
        return true;
    }

    static byte[] copyRemainingPayload(final ByteBuf inputBuffer) {
        final byte[] payload = new byte[inputBuffer.readableBytes()];
        inputBuffer.getBytes(inputBuffer.readerIndex(), payload);
        return payload;
    }

    static String encodeEnvelope(final byte[] channelBytes, final byte[] payload) {
        if (channelBytes.length == 0 || channelBytes.length > 255) {
            throw new IllegalArgumentException("Channel must fit in an unsigned byte");
        }
        final byte[] envelope = new byte[1 + channelBytes.length + payload.length];
        envelope[0] = (byte) channelBytes.length;
        System.arraycopy(channelBytes, 0, envelope, 1, channelBytes.length);
        System.arraycopy(payload, 0, envelope, 1 + channelBytes.length, payload.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }

    static DecodedPayload decodeEnvelope(final String encodedValue) {
        final byte[] envelope = Base64.getUrlDecoder().decode(encodedValue);
        if (envelope.length < 2) {
            throw new IllegalArgumentException("Envelope is too short");
        }
        final int channelLength = Byte.toUnsignedInt(envelope[0]);
        if (channelLength == 0 || envelope.length < 1 + channelLength) {
            throw new IllegalArgumentException("Envelope has an invalid channel length");
        }
        return new DecodedPayload(
                new String(envelope, 1, channelLength, StandardCharsets.UTF_8),
                Arrays.copyOfRange(envelope, 1 + channelLength, envelope.length)
        );
    }

    private static boolean isWithinLimits(final byte[] channel, final byte[] payload) {
        return channel.length > 0
                && channel.length <= ViaBedrock.getConfig().getJavaCustomPayloadMaxChannelBytes()
                && payload.length <= ViaBedrock.getConfig().getJavaCustomPayloadMaxPayloadBytes();
    }

    record DecodedPayload(String channel, byte[] payload) {
    }
}
