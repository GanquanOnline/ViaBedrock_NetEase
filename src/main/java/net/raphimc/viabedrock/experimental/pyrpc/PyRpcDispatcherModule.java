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
    /**
     * 进服后若服务端一直没有主动推送 ModEventS2C，到此延迟就由代理补发
     * ScreenInfoEvent + RequestHudNodeDataEvent。留在 JE 客户端自己的请求之后，
     * 这样客户端报的真实分辨率优先；只有服务端始终不开口时才用代理的几何参数。
     */
    static final long BOOTSTRAP_FALLBACK_DELAY_MS = 2500L;

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
            scheduleBootstrapFallback(user);
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

    private static ModUiLifecycleStorage storage(final com.viaversion.viaversion.api.connection.UserConnection user) {
        ModUiLifecycleStorage storage = user.get(ModUiLifecycleStorage.class);
        if (storage == null) {
            storage = new ModUiLifecycleStorage(user);
            user.put(storage);
        }
        return storage;
    }

    /**
     * 解析 {@code [method, [modName, systemName, eventName, ...], nil]}。引擎调用
     * （如 ClientLoadAddonsFinishedFromGac）只有一层的 {@code [method]}，此时返回单元素数组。
     *
     * @return null 表示信封无法解析
     */
    static String[] readModEventTriple(final byte[] data) {
        if (data == null) {
            return null;
        }
        final Cursor cursor = new Cursor(data);
        final int outerSize = cursor.readArrayHeader();
        if (outerSize < 1) {
            return null;
        }
        final String method = cursor.readString();
        if (method == null) {
            return null;
        }
        if (outerSize < 2 || cursor.readArrayHeader() < 3) {
            return new String[]{method};
        }
        final String modName = cursor.readString();
        final String systemName = cursor.readString();
        final String eventName = cursor.readString();
        if (modName == null || systemName == null || eventName == null) {
            return new String[]{method};
        }
        return new String[]{method, modName, systemName, eventName};
    }

    /** 只够走完 ModEvent 信封前三个字符串的最小 MsgPack 游标。 */
    private static final class Cursor {

        private final byte[] data;
        private int index;

        private Cursor(final byte[] data) {
            this.data = data;
        }

        int readArrayHeader() {
            if (this.index >= this.data.length) {
                return -1;
            }
            final int code = this.data[this.index++] & 0xFF;
            if ((code & 0xF0) == 0x90) {
                return code & 0x0F;
            }
            if (code == 0xDC && this.index + 2 <= this.data.length) { // array16
                final int size = ((this.data[this.index] & 0xFF) << 8) | (this.data[this.index + 1] & 0xFF);
                this.index += 2;
                return size;
            }
            return -1;
        }

        String readString() {
            if (this.index >= this.data.length) {
                return null;
            }
            final int code = this.data[this.index] & 0xFF;
            final int length;
            if ((code & 0xE0) == 0xA0) { // fixstr
                length = code & 0x1F;
                this.index += 1;
            } else if (code == 0xC4 || code == 0xD9) { // bin8 / str8
                if (this.index + 2 > this.data.length) {
                    return null;
                }
                length = this.data[this.index + 1] & 0xFF;
                this.index += 2;
            } else if (code == 0xC5 || code == 0xDA) { // bin16 / str16
                if (this.index + 3 > this.data.length) {
                    return null;
                }
                length = ((this.data[this.index + 1] & 0xFF) << 8) | (this.data[this.index + 2] & 0xFF);
                this.index += 3;
            } else {
                return null;
            }
            if (this.index + length > this.data.length) {
                return null;
            }
            final String value = new String(this.data, this.index, length, java.nio.charset.StandardCharsets.UTF_8);
            this.index += length;
            return value;
        }

    }

    /**
     * 记录 PY_RPC 两端的每一次事件。HUD 不渲染时这行日志直接决定是服务端没发、
     * 被通道门槛丢掉，还是发了但 JE 侧没画。同名事件只在首次以 INFO 打印。
     */
    static void logPyRpc(final String direction, final com.viaversion.viaversion.api.connection.UserConnection user, final byte[] data, final int msgId) {
        final String[] triple = readModEventTriple(data);
        if (triple == null) {
            ViaBedrock.getPlatform().getLogger().info("[PY_RPC] " + direction + " msgId=" + msgId
                    + " bytes=" + (data == null ? 0 : data.length) + " (undecodable envelope)");
            return;
        }
        final boolean isModEvent = triple.length > 3;
        final ModUiLifecycleStorage storage = storage(user);
        if (isModEvent && "ModEventS2C".equals(triple[0])) {
            storage.s2cSeen = true;
        }
        final String key = isModEvent ? triple[0] + '\u007c' + triple[3] : triple[0];
        final int seen = storage.countEvent(key);
        final String line = "[PY_RPC] " + direction + " msgId=" + msgId + " method=" + triple[0]
                + (isModEvent ? " mod=" + triple[1] + " system=" + triple[2] + " event=" + triple[3] : "")
                + " bytes=" + (data == null ? 0 : data.length);
        // 前三次附负载预览：未知事件（如服务端自定义的 VertexHudUpdate）只能靠它定格式。
        final boolean withPreview = seen < 3;
        final String text = withPreview ? line + " payload=" + preview(data) : line;
        if (seen == 0) {
            ViaBedrock.getPlatform().getLogger().info(text);
        } else {
            ViaBedrock.getPlatform().getLogger().fine(text);
        }
    }

    /**
     * MsgPack 里的字符串/键名在字节流里是明文，直接抽可打印字符就足以看出信封结构。
     */
    static String preview(final byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        final int limit = Math.min(data.length, 320);
        final String raw = new String(data, 0, limit, java.nio.charset.StandardCharsets.UTF_8);
        final StringBuilder sb = new StringBuilder(raw.length() + 3);
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            sb.append(c < 0x20 || c == 0x7F ? '.' : c);
        }
        if (data.length > limit) {
            sb.append("...");
        }
        return sb.toString();
    }

    static void logS2CDropped(final com.viaversion.viaversion.api.connection.UserConnection user, final String channel, final byte[] data) {
        final ModUiLifecycleStorage storage = storage(user);
        if (storage.droppedLogs >= 8) {
            return;
        }
        storage.droppedLogs++;
        final String[] triple = readModEventTriple(data);
        final String what = triple != null && triple.length > 3
                ? "event=" + triple[3] + " mod=" + triple[1] + " system=" + triple[2]
                : "event=?";
        ViaBedrock.getPlatform().getLogger().info("[PY_RPC] dropping S2C payload (" + what + "): channel " + channel
                + " was never announced by the JE client");
    }

    /**
     * 代理侧驱动 ModUI 生命周期：先 ScreenInfoEvent（HUD 系统要屏幕几何才提供节点数据），
     * 再 RequestHudNodeDataEvent。每次连接只发一次。
     *
     * @return 本次调用真正发出时返回 true
     */
    static boolean ensureModUiBootstrap(final com.viaversion.viaversion.api.connection.UserConnection user, final String reason) {
        if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
            return false;
        }
        if (user.getProtocolInfo().getClientState() != State.PLAY) {
            return false;
        }
        final ModUiLifecycleStorage storage = storage(user);
        if (storage.bootstrapSent) {
            return false;
        }
        storage.bootstrapSent = true;
        sendPyRpc(user, buildScreenInfoC2s());
        sendPyRpc(user, buildC2sEvent("ECNukkitClientMod", "ECNukkitClientSystem", "RequestHudNodeDataEvent", new byte[0]));
        ViaBedrock.getPlatform().getLogger().info("[PY_RPC] ModUI bootstrap sent (ScreenInfoEvent + RequestHudNodeDataEvent), reason=" + reason);
        return true;
    }

    /**
     * 服务端始终不主动说话时的兜底：进服 {@link #BOOTSTRAP_FALLBACK_DELAY_MS} 之后
     * 还没见到任何 ModEventS2C，就补发一次请求。只排在 Netty 事件循环上，
     * 不在 Via 主线程 sleep。
     */
    static void scheduleBootstrapFallback(final com.viaversion.viaversion.api.connection.UserConnection user) {
        if (!ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
            return;
        }
        final ModUiLifecycleStorage storage = storage(user);
        if (storage.fallbackScheduled) {
            return;
        }
        final io.netty.channel.Channel channel = user.getChannel();
        if (channel == null || channel.eventLoop() == null) {
            return;
        }
        storage.fallbackScheduled = true;
        channel.eventLoop().schedule(() -> {
            if (user.getProtocolInfo().getClientState() != State.PLAY || storage.s2cSeen) {
                return;
            }
            ensureModUiBootstrap(user, "no-server-s2c-after-" + BOOTSTRAP_FALLBACK_DELAY_MS + "ms");
        }, BOOTSTRAP_FALLBACK_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }


    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.PY_RPC, null, wrapper -> {
            wrapper.cancel();
            // 与 MOT PyRpcPacket.encode() 一致：服务端→客户端同样是 [payload][int32 LE msgId]。
            // 先读 msgId 会把负载削掉 4 个字节，使 isModEventS2C() 失效。
            final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // MsgPack 数据
            final int msgId = wrapper.read(BedrockTypes.INT_LE); // msgId（转发服务端→客户端时不需要）
            logPyRpc("S2C", wrapper.user(), data, msgId);
            if (isModEventS2C(data)) {
                // 服务端先开口时立即参与 ModUI 协议，不等回退定时器。
                ensureModUiBootstrap(wrapper.user(), "server-s2c");
            }

            final GlowProjectionTracker glow = wrapper.user().get(GlowProjectionTracker.class);
            if (glow != null) {
                GlowModEventCodec.decode(data).ifPresent(glow::apply);
            }

            final String routedChannel = ExperimentalFeatures.dispatchResolveClientboundPyRpcChannel(data);
            final String targetChannel = routedChannel != null ? routedChannel : DATA_CHANNEL;
            final ChannelStorage channels = wrapper.user().get(ChannelStorage.class);
            if (DATA_CHANNEL.equals(targetChannel)) {
                // confirm 是客户端专门用来声明「我会 ModUI」的能力通道，而且客户端确实
                // 在 data 上发过 ModEventC2S。Fabric 不会自动宣告 data，只认 data
                // 会把服务端发的每一个 ModUI 负载全部丢掉。
                if (!channels.hasChannel(DATA_CHANNEL) && !channels.hasChannel(CONFIRM_CHANNEL)) {
                    logS2CDropped(wrapper.user(), targetChannel, data);
                    return;
                }
            } else if (!channels.hasChannel(targetChannel)) {
                logS2CDropped(wrapper.user(), targetChannel, data);
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
        final ModUiLifecycleStorage storage = storage(user);
        if (storage.confirmSent) {
            return false;
        }
        storage.confirmSent = true;
        final PacketWrapper confirm = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        confirm.write(Types.STRING, DATA_CHANNEL);
        confirm.write(Types.INT, CONFIRM_PAYLOAD_TYPE);
        confirm.write(Types.REMAINING_BYTES, new byte[0]);
        confirm.scheduleSend(BedrockProtocol.class);
        scheduleBootstrapFallback(user);
        return true;
    }

    @Override
    public void onChannelRegistered(final com.viaversion.viaversion.api.connection.UserConnection user, final java.util.Set<String> channels) {
        // 客户端用 minecraft:register 宣告 "moduiclient:confirm"（能力）与
        // "moduiclient:data"（业务）。两个包都认，先到的那个就把 CONFIRM 回过去。
        if (!channels.contains(CONFIRM_CHANNEL) && !channels.contains(DATA_CHANNEL)) {
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
            logPyRpc("C2S", wrapper.user(), msgpackData, MSG_ID);
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
