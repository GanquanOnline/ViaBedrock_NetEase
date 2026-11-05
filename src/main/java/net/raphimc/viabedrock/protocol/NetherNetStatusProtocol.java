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
package net.raphimc.viabedrock.protocol;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Type;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.FixedByteArrayType;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.protocol.AbstractSimpleProtocol;
import com.viaversion.viaversion.protocols.base.ClientboundStatusPackets;
import com.viaversion.viaversion.protocols.base.ServerboundStatusPackets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.netty.nethernet.DiscoveryEncryptionCodec;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.GameType;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

public class NetherNetStatusProtocol extends AbstractSimpleProtocol {

    public static final NetherNetStatusProtocol INSTANCE = new NetherNetStatusProtocol();

    private static final long APPLICATION_ID = 0xDEADBEEFL;
    private static final short ID_DISCOVERY_REQUEST = 0;
    private static final short ID_DISCOVERY_RESPONSE = 1;
    private static final short ID_DISCOVERY_MESSAGE = 2;

    private static final byte[] PADDING_BYTES = new byte[8];
    private static final Type<byte[]> PADDING_TYPE = new FixedByteArrayType(PADDING_BYTES.length);

    private NetherNetStatusProtocol() {
        this.initialize();
    }

    @Override
    protected void registerPackets() {
        this.registerClientbound(State.STATUS, ID_DISCOVERY_RESPONSE, ClientboundStatusPackets.STATUS_RESPONSE.getId(), wrapper -> {
            wrapper.read(Types.BYTE); // 数据包 id 应该是 short，因此再读一个字节凑成 2 字节
            wrapper.user().get(PingInfoStorage.class).serverNetworkId = wrapper.read(BedrockTypes.LONG_LE); // 发送方网络 id
            wrapper.read(PADDING_TYPE); // 填充
            final String rawData = wrapper.read(BedrockTypes.ASCII_STRING); // 数据

            final JsonObject statusResponse = new JsonObject();
            final JsonObject version = new JsonObject();
            version.addProperty("name", "");
            version.addProperty("protocol", wrapper.user().getProtocolInfo().protocolVersion().getVersion());
            statusResponse.add("version", version);

            final ByteBuf data = Unpooled.wrappedBuffer(HexFormat.of().parseHex(rawData));
            final int dataVersion = data.readUnsignedByte(); // 版本
            if (dataVersion != 4) {
                throw new IllegalStateException("Unsupported NetherNet discovery response version: " + dataVersion);
            }

            final String serverName = BedrockTypes.STRING.read(data); // 服务器名称
            final String levelName = BedrockTypes.STRING.read(data); // 关卡名称
            statusResponse.addProperty("description", "Server: " + serverName + "\n§rLevel: " + levelName);

            final JsonArray samples = new JsonArray();
            samples.add(this.createPlayerSample("GameType: " + GameType.getByValue(data.readUnsignedByte() >> 1, GameType.Undefined).name())); // 游戏模式

            final JsonObject players = new JsonObject();
            players.addProperty("online", data.readIntLE()); // 玩家数量
            players.addProperty("max", data.readIntLE()); // 最大玩家数量
            players.add("sample", samples);
            statusResponse.add("players", players);

            samples.add(this.createPlayerSample("Editor World: " + data.readBoolean())); // 编辑器世界
            samples.add(this.createPlayerSample("Hardcore: " + data.readBoolean())); // 硬核

            data.skipBytes(2); // 未知
            data.release();

            wrapper.write(Types.STRING, statusResponse.toString()); // 状态
        });
        this.registerClientbound(State.STATUS, ID_DISCOVERY_MESSAGE, ClientboundStatusPackets.PONG_RESPONSE.getId(), wrapper -> {
            wrapper.read(Types.BYTE); // 数据包 id 应该是 short，因此再读一个字节凑成 2 字节
            wrapper.read(BedrockTypes.LONG_LE); // 发送方网络 id
            wrapper.read(PADDING_TYPE); // 填充
            wrapper.read(BedrockTypes.LONG_LE); // 接收方网络 id
            final String message = wrapper.read(BedrockTypes.ASCII_STRING); // 消息
            if (message.equals("Ping")) {
                wrapper.write(Types.LONG, wrapper.user().get(PingInfoStorage.class).pingTime); // 时间
            } else {
                throw new IllegalStateException("Unsupported NetherNet discovery message: " + message);
            }
        });

        this.registerServerbound(State.STATUS, ServerboundStatusPackets.STATUS_REQUEST.getId(), ID_DISCOVERY_REQUEST, wrapper -> {
            wrapper.write(Types.BYTE, (byte) 0); // 数据包 id 应该是 short，因此再写一个字节凑成 2 字节
            wrapper.write(BedrockTypes.LONG_LE, wrapper.user().get(PingInfoStorage.class).clientNetworkId); // 发送方网络 id
            wrapper.write(PADDING_TYPE, PADDING_BYTES); // 填充
        });
        this.registerServerbound(State.STATUS, ServerboundStatusPackets.PING_REQUEST.getId(), ID_DISCOVERY_MESSAGE, wrapper -> {
            final PingInfoStorage pingInfoStorage = wrapper.user().get(PingInfoStorage.class);
            pingInfoStorage.pingTime = wrapper.read(Types.LONG); // 时间

            wrapper.write(Types.BYTE, (byte) 0); // 数据包 id 应该是 short，因此再写一个字节凑成 2 字节
            wrapper.write(BedrockTypes.LONG_LE, pingInfoStorage.clientNetworkId); // 发送方网络 id
            wrapper.write(PADDING_TYPE, PADDING_BYTES); // 填充
            wrapper.write(BedrockTypes.LONG_LE, pingInfoStorage.serverNetworkId); // 接收方网络 id
            wrapper.write(BedrockTypes.ASCII_STRING, "Ping"); // 消息
        });
    }

    @Override
    public void init(final UserConnection connection) {
        connection.put(new PingInfoStorage(ThreadLocalRandom.current().nextLong()));

        if (connection.getChannel() != null) {
            try {
                connection.getChannel().pipeline().addBefore(Via.getManager().getInjector().getEncoderName(), DiscoveryEncryptionCodec.NAME, new DiscoveryEncryptionCodec(APPLICATION_ID));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Failed to initialize NetherNet encryption codec", e);
            }
        }
    }

    @Override
    public boolean isBaseProtocol() {
        return true;
    }

    private JsonObject createPlayerSample(final String text) {
        final JsonObject sample = new JsonObject();
        sample.addProperty("name", text);
        sample.addProperty("id", "00000000-0000-0000-0000-000000000000");
        return sample;
    }

    private static class PingInfoStorage implements StorableObject {

        private final long clientNetworkId;
        private long serverNetworkId;
        private long pingTime;

        private PingInfoStorage(final long clientNetworkId) {
            this.clientNetworkId = clientNetworkId;
        }

    }

}
