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
package net.raphimc.viabedrock.experimental.pyrpc;

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.storage.GlowProjectionTracker;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

/**
 * JE 共享的 PY_RPC 传输通道。基岩的 PY_RPC 字节经 floodgate:netease
 * 原样转发；JE 的客户端→服务端字节则重新包装成 PY_RPC。
 * <p>
 * NukkitMaster 的 {@code ClientEventListener} 会等待引擎调用
 * {@code ClientLoadAddonsFinishedFromGac}，然后才发送
 * {@code GetClientPlayerUidEvent} / HUD。原版网易客户端在插件加载完成
 * 后会发出该调用；Java 客户端从不发出，因此本模块在 MOT 的
 * {@code SET_LOCAL_PLAYER_AS_INITIALIZED}（该事件触发
 * {@code PlayerJoinEvent} 并分配 {@code PlayerInfo}）之后合成它。
 */
public class PyRpcDispatcherModule implements FeatureModule {

    // ModUIClient 负载通道握手：客户端注册 "moduiclient:confirm"
    // （minecraft:register），并在 "moduiclient:data" 上交换数据。旧的
    // "floodgate:netease" 名称在客户端 jar 中匹配不到任何东西，因此桥接的
    // 两个方向都不通。
    public static final String CONFIRM_CHANNEL = "moduiclient:confirm";
    public static final String DATA_CHANNEL = "moduiclient:data";

    // NukkitMaster 的 PyRpcMessageListener 只接受这个魔法 msgId（网易客户端
    // 总是发送它）。服务端→客户端方向则改用 PyRpcPacket.DEFAULT_MSG_ID = 9753608。
    static final int MSG_ID = 98247598;
    /** ModUIClient 负载类型序号：0=CONFIRM，1=PY_RPC_DATA（对应 ModUIPayload.PayloadType）。 */
    static final int CONFIRM_PAYLOAD_TYPE = 0;
    static final int PY_RPC_DATA_PAYLOAD_TYPE = 1;
    /** NukkitMaster {@code ClientEventListener} 中 HUD / 玩家信息的引擎调用开关。 */
    public static final String CLIENT_LOAD_ADDONS_FINISHED = "ClientLoadAddonsFinishedFromGac";
    /** SET_LOCAL_PLAYER_AS_INITIALIZED 之后的延迟，好让 MOT 先处理完 PlayerJoinEvent。 */
    static final long ADDONS_FINISHED_DELAY_MS = 250L;

    private static void sendPyRpc(final com.viaversion.viaversion.api.connection.UserConnection user, final byte[] msgpackData) {
        try {
            final PacketWrapper pyRpc = PacketWrapper.create(ServerboundBedrockPackets.PY_RPC, user);
            // 布局按 Nukkit-MOT 的 PyRpcPacket.decode()：[BYTE_ARRAY payload][uint32 LE msgId]。
            // msgId 必须是魔法值 98247598 —— 其他值会被记录为 "invalid PyRpc msgId"，
            // 并且包含它的批次解码失败（"Sent malformed packet"）。
            pyRpc.write(BedrockTypes.BYTE_ARRAY, msgpackData);
            pyRpc.write(BedrockTypes.INT_LE, MSG_ID);
            pyRpc.scheduleSendToServer(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().warning("[PY_RPC] Failed to send C2S event: " + e.getMessage());
        }
    }

    /**
     * 完全按参考实现 ModUIClient 的打包器构造 ["ModEventC2S", [modName, systemName, eventName, {}], nil]：
     * fixarray(3)、MsgPack StringValue 字符串（不是 bin8 —— 服务端通过 Value.toJson() 解码，
     * 二进制值会被 base64 编码，导致字符串匹配失败）、空事件数据用 fixmap(0)，
     * 以及结尾的 nil。
     */
    private static byte[] buildC2sEvent(final String modName, final String systemName, final String eventName, final byte[] ignoredEventData) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(128);
        out.write(0x93); // fixarray(3)
        writeFixStr(out, "ModEventC2S");
        out.write(0x94); // fixarray(4)
        writeFixStr(out, modName);
        writeFixStr(out, systemName);
        writeFixStr(out, eventName);
        out.write(0x80); // fixmap(0)
        out.write(0xc0); // nil
        return out.toByteArray();
    }

    /**
     * 客户端→服务端的 ScreenInfoEvent，与参考实现 ModUIClient 的打包器一致：
     * {screen: {width, height}, view: {width, height, offsetX, offsetY}}，采用 1920x1080
     * 逻辑分辨率和 2 倍物理分辨率，即典型的网易 PC 客户端几何参数。HUD 系统
     * 要等到屏幕信息后才会提供 HUD 节点数据。
     */
    private static byte[] buildScreenInfoC2s() {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(128);
        out.write(0x93); // fixarray(3)
        writeFixStr(out, "ModEventC2S");
        out.write(0x94); // fixarray(4)
        writeFixStr(out, "ECNukkitClientMod");
        writeFixStr(out, "ECNukkitClientSystem");
        writeFixStr(out, "ScreenInfoEvent");
        out.write(0x82); // fixmap(2)
        writeFixStr(out, "screen");
        out.write(0x82); // fixmap(2)
        writeFixStr(out, "width"); writeInt(out, 1920);
        writeFixStr(out, "height"); writeInt(out, 1080);
        writeFixStr(out, "view");
        out.write(0x84); // fixmap(4)
        writeFixStr(out, "width"); writeInt(out, 3840);
        writeFixStr(out, "height"); writeInt(out, 2160);
        writeFixStr(out, "offsetX"); writeInt(out, 0);
        writeFixStr(out, "offsetY"); writeInt(out, 0);
        out.write(0xc0); // nil
        return out.toByteArray();
    }

    private static void writeInt(final java.io.ByteArrayOutputStream out, final int value) {
        // MsgPack int32 大端序
        out.write(0xd2);
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    static void writeFixStr(final java.io.ByteArrayOutputStream out, final String s) {
        writeMsgPackStr(out, s);
    }

    /**
     * MOT 的 {@code PyRpcProtocol.asString} 接受 MessagePack 的 str 类型（fixstr / str8 / str16）。
     * 保持这条路径 —— bin8 在 Master 的某些 JSON 辅助代码里会被 base64 编码。
     * {@code ClientLoadAddonsFinishedFromGac} 是 31 字节（可放入 fixstr）；更长的引擎调用名
     * 一律用 str8。
     */
    static void writeMsgPackStr(final java.io.ByteArrayOutputStream out, final String s) {
        final byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (b.length <= 31) {
            out.write(0xa0 | b.length);
        } else if (b.length <= 255) {
            out.write(0xd9);
            out.write(b.length);
        } else if (b.length <= 65535) {
            out.write(0xda);
            out.write((b.length >>> 8) & 0xFF);
            out.write(b.length & 0xFF);
        } else {
            throw new IllegalArgumentException("PY_RPC string too long: " + b.length);
        }
        out.writeBytes(b);
    }

    /**
     * NukkitMaster {@code processEngineCallback} 使用的引擎回调信封：
     * {@code [method]} 的 MessagePack 数组（不带参数数组）。MOT 回归测试
     * {@code testPyRpcPacketDecodesStoreBuySuccessSubPacket} 使用相同的结构。
     */
    public static byte[] buildEngineCall(final String method) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64);
        out.write(0x91); // fixarray 1
        writeFixStr(out, method);
        return out.toByteArray();
    }

    public static byte[] buildClientLoadAddonsFinished() {
        return buildEngineCall(CLIENT_LOAD_ADDONS_FINISHED);
    }

    /**
     * MOT 的 {@code SetLocalPlayerAsInitializedProcessor} 会调用 {@code doFirstSpawn()}，
     * 后者触发 {@code PlayerJoinEvent}。NukkitMaster 随后分配
     * {@code PlayerInfo}。把引擎调用推迟一刻发送，这样推送 HUD 时
     * {@code getPlayerInfo(player) != null} 才成立。
     * <p>
     * 在负载真正发出之前不要标记一次性调用已发送。以前在 CONFIGURATION 阶段的
     * PlayerSpawn 或 Netty 通道尚未激活时会写入标志并返回，
     * 导致后续 PLAY 阶段的重试再也不会触发。
     */
    public static void scheduleClientLoadAddonsFinished(final com.viaversion.viaversion.api.connection.UserConnection user) {
        if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
            return;
        }
        NetEaseAddonsFinishedStorage storage = user.get(NetEaseAddonsFinishedStorage.class);
        if (storage == null) {
            storage = new NetEaseAddonsFinishedStorage(user);
            user.put(storage);
        }
        if (storage.sent) {
            return;
        }
        enqueueClientLoadAddonsFinished(user, storage, ADDONS_FINISHED_DELAY_MS);
    }

    static void enqueueClientLoadAddonsFinished(final com.viaversion.viaversion.api.connection.UserConnection user,
                                                final NetEaseAddonsFinishedStorage storage,
                                                final long delayMs) {
        if (storage.sent || storage.scheduled) {
            return;
        }
        storage.scheduled = true;
        storage.attempts++;
        final Runnable send = () -> {
            storage.scheduled = false;
            if (storage.sent) {
                return;
            }
            final io.netty.channel.Channel channel = user.getChannel();
            if (channel == null || !channel.isActive()) {
                if (storage.attempts < NetEaseAddonsFinishedStorage.MAX_ATTEMPTS) {
                    enqueueClientLoadAddonsFinished(user, storage, ADDONS_FINISHED_DELAY_MS);
                } else {
                    ViaBedrock.getPlatform().getLogger().warning("[PY_RPC] giving up ClientLoadAddonsFinishedFromGac; channel never became active");
                }
                return;
            }
            sendPyRpc(user, buildClientLoadAddonsFinished());
            storage.sent = true;
            ViaBedrock.getPlatform().getLogger().info("[PY_RPC] sent ClientLoadAddonsFinishedFromGac");
        };
        final io.netty.channel.Channel channel = user.getChannel();
        if (channel != null && channel.eventLoop() != null) {
            channel.eventLoop().schedule(send, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return;
        }
        com.viaversion.viaversion.api.Via.getPlatform().runSync(send, Math.max(1L, delayMs / 50L));
    }

    /**
     * MOT 的 {@code PyRpcWriter.writeBinaryString} 把方法名编码成 MessagePack
     * 的 bin（{@code 0xC4} + 长度）。部分 Master / 第三方发送方使用 str
     * （{@code fixstr}/{@code str8}）。两者都接受，以保证 HUD 生命周期仍能启动。
     */
    static boolean isModEventS2C(final byte[] data) {
        return "ModEventS2C".equals(readFirstMsgPackString(data));
    }

    static String readFirstMsgPackString(final byte[] data) {
        if (data == null || data.length < 3 || data[0] != (byte) 0x93) {
            return null;
        }
        int index = 1;
        final int code = data[index] & 0xFF;
        final int length;
        if ((code & 0xE0) == 0xA0) { // fixstr
            length = code & 0x1F;
            index += 1;
        } else if (code == 0xC4 || code == 0xD9) { // bin8 / str8
            if (index + 2 > data.length) {
                return null;
            }
            length = data[index + 1] & 0xFF;
            index += 2;
        } else if (code == 0xC5 || code == 0xDA) { // bin16 / str16
            if (index + 3 > data.length) {
                return null;
            }
            length = ((data[index + 1] & 0xFF) << 8) | (data[index + 2] & 0xFF);
            index += 3;
        } else {
            return null;
        }
        if (length < 0 || index + length > data.length) {
            return null;
        }
        return new String(data, index, length, java.nio.charset.StandardCharsets.UTF_8);
    }


    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.PY_RPC, null, wrapper -> {
            wrapper.cancel();
            // 与 MOT PyRpcPacket.encode() 一致：服务端→客户端同样是 [payload][int32 LE msgId]。
            // 先读 msgId 会把负载削掉 4 个字节，使 isModEventS2C() 失效。
            final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // MsgPack 数据
            final int msgId = wrapper.read(BedrockTypes.INT_LE); // msgId（转发服务端→客户端时不需要）
            if (ViaBedrock.getConfig().shouldEmulateNetEaseClient() && isModEventS2C(data)) {
                // 网易 HUD 系统通过 ModEventS2C 推送 VertexHudUpdate，并期望客户端参与
                // ModUI 协议。改由代理自己驱动该生命周期（先 ScreenInfoEvent，再
                // RequestHudNodeDataEvent），这样进服不再依赖 Java 客户端安装 ModUIClient。
                if (!wrapper.user().has(ModUiLifecycleStorage.class)) {
                    wrapper.user().put(new ModUiLifecycleStorage());
                    sendPyRpc(wrapper.user(), buildScreenInfoC2s());
                    final byte[] request = buildC2sEvent("ECNukkitClientMod", "ECNukkitClientSystem", "RequestHudNodeDataEvent", new byte[0]);
                    sendPyRpc(wrapper.user(), request);
                }
            }

            final GlowProjectionTracker glow = wrapper.user().get(GlowProjectionTracker.class);
            if (glow != null) {
                GlowModEventCodec.decode(data).ifPresent(glow::apply);
            }

            final String routedChannel = ExperimentalFeatures.dispatchResolveClientboundPyRpcChannel(data);
            final String targetChannel = routedChannel != null ? routedChannel : DATA_CHANNEL;
            final ChannelStorage channels = wrapper.user().get(ChannelStorage.class);
            if (DATA_CHANNEL.equals(targetChannel)) {
                if (!channels.hasChannel(CONFIRM_CHANNEL)) {
                    return;
                }
            } else if (!channels.hasChannel(targetChannel)) {
                return;
            }

            // ModUIClient 负载格式：先写 int32 类型序号前缀，再写原始字节。
            // 0=CONFIRM，1=PY_RPC_DATA，2=ENTITY_MAPPING。缺少前缀时客户端的
            // STREAM_CODEC 解码器会抛异常，负载被静默丢弃。
            final PacketWrapper msg = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, wrapper.user());
            msg.write(Types.STRING, targetChannel);
            if (DATA_CHANNEL.equals(targetChannel)) {
                msg.write(Types.INT, PY_RPC_DATA_PAYLOAD_TYPE);
            }
            msg.write(Types.REMAINING_BYTES, data);
            msg.scheduleSend(BedrockProtocol.class);
        });
    }

    /**
     * 回应 ModUIClient 握手：一个 type=0 的空负载。客户端收到后才会
     * {@code UIManager.setConnected(true)} 并开始请求 HUD。
     *
     * @return 仅在 PLAY 状态实际发出时返回 true
     */
    static boolean sendConfirm(final com.viaversion.viaversion.api.connection.UserConnection user) {
        // minecraft:register 在 CONFIGURATION 阶段也会到达；客户端只在 PLAY 注册接收器，
        // 在错误的状态发 CONFIRM 会让客户端因未知负载断线。
        if (user.getProtocolInfo().getClientState() != State.PLAY) {
            return false;
        }
        final PacketWrapper confirm = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        confirm.write(Types.STRING, DATA_CHANNEL);
        confirm.write(Types.INT, CONFIRM_PAYLOAD_TYPE);
        confirm.write(Types.REMAINING_BYTES, new byte[0]);
        confirm.scheduleSend(BedrockProtocol.class);
        return true;
    }

    @Override
    public void onChannelRegistered(final com.viaversion.viaversion.api.connection.UserConnection user, final java.util.Set<String> channels) {
        // ModUIClient 在 JOIN 时用 minecraft:register 宣告 "moduiclient:confirm"，
        // Fabric 随后再宣告真正接收数据的 "moduiclient:data"。这里只认 confirm，
        // 不再看 data 是否已知 —— 两个注册包的先后由 Fabric 决定，不能当状态用。
        if (!channels.contains(CONFIRM_CHANNEL)) {
            return;
        }

        if (sendConfirm(user)) {
            ViaBedrock.getPlatform().getLogger().info("[PY_RPC] ModUIClient confirm channel seen; sent CONFIRM handshake");
        }
    }

    @Override
    public boolean handleCustomPayload(final String channel, final PacketWrapper wrapper) {
        if (!channel.equals(DATA_CHANNEL)) {
            return false;
        }

        try {
            final int payloadType = wrapper.read(Types.INT); // PayloadType 序号
            if (payloadType == CONFIRM_PAYLOAD_TYPE) {
                // 客户端主动问候：握手不再依赖 minecraft:register 的到达顺序。
                // 同时补上能力标记，否则 S2C 转发门槛仍会把 HUD 响应丢掉。
                final ChannelStorage channels = wrapper.user().get(ChannelStorage.class);
                if (channels != null) {
                    channels.addChannels(java.util.List.of(CONFIRM_CHANNEL, DATA_CHANNEL));
                }
                sendConfirm(wrapper.user());
                return true;
            }
            if (payloadType != PY_RPC_DATA_PAYLOAD_TYPE) {
                return true;
            }
            final byte[] msgpackData = wrapper.read(Types.REMAINING_BYTES);
            final PacketWrapper pyRpc = PacketWrapper.create(ServerboundBedrockPackets.PY_RPC, wrapper.user());
            pyRpc.write(BedrockTypes.BYTE_ARRAY, msgpackData); // 负载在前，msgId 在后 —— 见 sendPyRpc
            pyRpc.write(BedrockTypes.INT_LE, MSG_ID);
            pyRpc.sendToServer(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().severe("[PY_RPC] Failed to forward JE C2S payload: " + e.getMessage());
        }
        return true;
    }

    @Override
    public void onStorageRegistration(final com.viaversion.viaversion.api.connection.UserConnection user) {
        user.put(new GlowProjectionTracker(user));
    }

    @Override
    public void onEntityAdded(final com.viaversion.viaversion.api.connection.UserConnection user, final Entity entity) {
        final GlowProjectionTracker glow = user.get(GlowProjectionTracker.class);
        if (glow != null) {
            glow.onEntityAdded(entity);
        }
    }

    @Override
    public void onEntityRemoved(final com.viaversion.viaversion.api.connection.UserConnection user, final Entity entity) {
        final GlowProjectionTracker glow = user.get(GlowProjectionTracker.class);
        if (glow != null) {
            glow.onEntityRemoved(entity);
        }
    }

}
