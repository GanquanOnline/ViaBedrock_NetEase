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
 * Shared JE PY_RPC transport. Bedrock PY_RPC bytes are forwarded unchanged
 * through floodgate:netease; JE C2S bytes are wrapped back into PY_RPC.
 * <p>
 * NukkitMaster {@code ClientEventListener} waits for the engine call
 * {@code ClientLoadAddonsFinishedFromGac} before it will send
 * {@code GetClientPlayerUidEvent} / HUD. Vanilla NetEase clients emit that
 * after addons load; Java clients never do, so this module synthesizes it
 * after MOT {@code SET_LOCAL_PLAYER_AS_INITIALIZED} (which fires
 * {@code PlayerJoinEvent} and allocates {@code PlayerInfo}).
 */
public class PyRpcDispatcherModule implements FeatureModule {

    // ModUIClient payload channel handshake: the client registers "moduiclient:confirm"
    // (minecraft:register) and exchanges data on "moduiclient:data". The old
    // "floodgate:netease" name matched nothing in the client jar, so both directions
    // of the bridge were dead.
    public static final String CONFIRM_CHANNEL = "moduiclient:confirm";
    public static final String DATA_CHANNEL = "moduiclient:data";

    // NukkitMaster's PyRpcMessageListener only accepts this magic msgId (the NetEase client
    // always sends it). S2C uses PyRpcPacket.DEFAULT_MSG_ID = 9753608 instead.
    static final int MSG_ID = 98247598;
    /** NukkitMaster {@code ClientEventListener} engine-call gate for HUD / player-info. */
    public static final String CLIENT_LOAD_ADDONS_FINISHED = "ClientLoadAddonsFinishedFromGac";
    /** Delay after SET_LOCAL_PLAYER_AS_INITIALIZED so MOT can finish PlayerJoinEvent first. */
    static final long ADDONS_FINISHED_DELAY_MS = 250L;

    private static void sendPyRpc(final com.viaversion.viaversion.api.connection.UserConnection user, final byte[] msgpackData) {
        try {
            final PacketWrapper pyRpc = PacketWrapper.create(ServerboundBedrockPackets.PY_RPC, user);
            // Layout per Nukkit-MOT PyRpcPacket.decode(): [BYTE_ARRAY payload][uint32 LE msgId].
            // msgId must be the magic 98247598 — anything else is logged as "invalid PyRpc msgId"
            // and the batch containing it fails to decode ("Sent malformed packet").
            pyRpc.write(BedrockTypes.BYTE_ARRAY, msgpackData);
            pyRpc.write(BedrockTypes.INT_LE, MSG_ID);
            pyRpc.scheduleSendToServer(BedrockProtocol.class);
        } catch (final Exception e) {
            ViaBedrock.getPlatform().getLogger().warning("[PY_RPC] Failed to send C2S event: " + e.getMessage());
        }
    }

    /**
     * Builds ["ModEventC2S", [modName, systemName, eventName, {}], nil] exactly like the
     * reference ModUIClient packer: fixarray(3), MsgPack StringValue strings (NOT bin8 —
     * the server decodes via Value.toJson() and binary values base64-encode, breaking
     * string matching), fixmap(0) for empty event data, and a trailing nil.
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
     * ScreenInfoEvent C2S mirroring the reference ModUIClient packer:
     * {screen: {width, height}, view: {width, height, offsetX, offsetY}} with 1920x1080
     * logical and 2x physical, typical NetEase PC client geometry. HUD systems
     * wait for screen info before they will serve HUD node data.
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
        // MsgPack int32 big-endian
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
     * MOT {@code PyRpcProtocol.asString} accepts MessagePack str (fixstr / str8 / str16).
     * Keep this path — bin8 would base64-encode in some Master JSON helpers.
     * {@code ClientLoadAddonsFinishedFromGac} is 31 bytes (fits fixstr); keep str8
     * for any longer engine-call names.
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
     * Engine callback envelope used by NukkitMaster {@code processEngineCallback}:
     * MessagePack array of {@code [method]} (no argument array). MOT regression
     * {@code testPyRpcPacketDecodesStoreBuySuccessSubPacket} uses the same shape.
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
     * MOT {@code SetLocalPlayerAsInitializedProcessor} calls {@code doFirstSpawn()},
     * which fires {@code PlayerJoinEvent}. NukkitMaster then allocates
     * {@code PlayerInfo}. Send the engine-call one tick later so
     * {@code getPlayerInfo(player) != null} when HUD is pushed.
     * <p>
     * Do not mark the one-shot as sent until the payload actually leaves. A
     * CONFIGURATION-state PlayerSpawn or a still-inactive Netty channel used
     * to store the flag and return, so later PLAY retries never fired.
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
     * MOT {@code PyRpcWriter.writeBinaryString} encodes method names as MessagePack
     * bin ({@code 0xC4} + length). Some Master / third-party senders use str
     * ({@code fixstr}/{@code str8}). Accept both so HUD lifecycle still starts.
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
            // S2C layout is [payload][int32 LE msgId] (payload-first), the opposite of C2S.
            // msgId-first decoding strips 4 bytes and breaks isModEventS2C().
            final byte[] data = wrapper.read(BedrockTypes.BYTE_ARRAY); // MsgPack data
            final int msgId = wrapper.read(BedrockTypes.INT_LE); // msgId (not needed for S2C forwarding)
            if (ViaBedrock.getConfig().shouldEmulateNetEaseClient() && isModEventS2C(data)) {
                // NetEase HUD systems push VertexHudUpdate over ModEventS2C and expect the client
                // to participate in the ModUI protocol. Drive the lifecycle from the proxy itself
                // (ScreenInfoEvent then RequestHudNodeDataEvent) so join no longer depends on the
                // Java client having ModUIClient installed.
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

            // ModUIClient payload format: int32 type ordinal prefix, then the raw bytes.
            // 0=CONFIRM, 1=PY_RPC_DATA, 2=ENTITY_MAPPING. Without the prefix the client's
            // STREAM_CODEC decoder throws and the payload is silently dropped.
            final PacketWrapper msg = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, wrapper.user());
            msg.write(Types.STRING, targetChannel);
            if (DATA_CHANNEL.equals(targetChannel)) {
                msg.write(Types.INT, 1); // PayloadType.PY_RPC_DATA
            }
            msg.write(Types.REMAINING_BYTES, data);
            msg.scheduleSend(BedrockProtocol.class);
        });
    }

    @Override
    public void onChannelRegistered(final com.viaversion.viaversion.api.connection.UserConnection user, final java.util.Set<String> channels) {
        // ModUIClient only registers "moduiclient:confirm" (minecraft:register). Fabric's
        // payload registration then makes "moduiclient:data" usable, so treat confirm as the
        // capability signal and answer the handshake with an empty CONFIRM payload — the
        // client's UIManager.setConnected(true) will not run without it.
        if (!channels.contains(CONFIRM_CHANNEL) || user.get(ChannelStorage.class).hasChannel(DATA_CHANNEL)) {
            return;
        }

        final PacketWrapper confirm = PacketWrapper.create(ClientboundPackets26_1.CUSTOM_PAYLOAD, user);
        confirm.write(Types.STRING, DATA_CHANNEL);
        confirm.write(Types.INT, 0); // PayloadType.CONFIRM
        confirm.write(Types.REMAINING_BYTES, new byte[0]);
        confirm.scheduleSend(BedrockProtocol.class);
        ViaBedrock.getPlatform().getLogger().info("[PY_RPC] ModUIClient confirm channel seen; sent CONFIRM handshake");
    }

    @Override
    public boolean handleCustomPayload(final String channel, final PacketWrapper wrapper) {
        if (!channel.equals(DATA_CHANNEL)) {
            return false;
        }

        try {
            final int payloadType = wrapper.read(Types.INT); // PayloadType ordinal
            if (payloadType != 1) { // PY_RPC_DATA; CONFIRM/ENTITY_MAPPING are client-internal
                return true;
            }
            final byte[] msgpackData = wrapper.read(Types.REMAINING_BYTES);
            final PacketWrapper pyRpc = PacketWrapper.create(ServerboundBedrockPackets.PY_RPC, wrapper.user());
            pyRpc.write(BedrockTypes.BYTE_ARRAY, msgpackData); // payload first, msgId last — see sendPyRpc
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
