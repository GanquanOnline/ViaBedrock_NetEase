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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketSendInterceptor;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.InformativeException;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.BossEventOperationType;
import net.raphimc.viabedrock.protocol.packet.JoinPackets;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Queue;
import java.util.UUID;

public class JoinGate extends StoredObject {

    private static final long OPEN_FALLBACK_NANOS = 3_500_000_000L;
    private static final int MAX_CLIENTBOUND_QUEUE_SIZE = 4096;
    private static final int MAX_SERVERBOUND_QUEUE_SIZE = 4096;

    private static final EnumSet<ClientboundPackets26_1> PASS_CLIENTBOUND_BEFORE_LOGIN = EnumSet.of(
            ClientboundPackets26_1.LOGIN,
            ClientboundPackets26_1.DISCONNECT,
            ClientboundPackets26_1.START_CONFIGURATION,
            ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER,
            ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS
    );
    private static final EnumSet<ClientboundPackets26_1> DROP_CLIENTBOUND_BEFORE_LOGIN = EnumSet.of(
            ClientboundPackets26_1.BLOCK_CHANGED_ACK,
            ClientboundPackets26_1.CHUNK_BATCH_START,
            ClientboundPackets26_1.CHUNK_BATCH_FINISHED,
            ClientboundPackets26_1.FORGET_LEVEL_CHUNK,
            ClientboundPackets26_1.KEEP_ALIVE,
            ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT,
            ClientboundPackets26_1.LIGHT_UPDATE,
            ClientboundPackets26_1.PING
    );
    private static final EnumSet<ClientboundPackets26_1> PASS_CLIENTBOUND_BEFORE_OPEN = EnumSet.of(
            ClientboundPackets26_1.LOGIN,
            ClientboundPackets26_1.DISCONNECT,
            ClientboundPackets26_1.START_CONFIGURATION,
            ClientboundPackets26_1.PLAYER_POSITION,
            ClientboundPackets26_1.CHUNK_BATCH_START,
            ClientboundPackets26_1.LEVEL_CHUNK_WITH_LIGHT,
            ClientboundPackets26_1.FORGET_LEVEL_CHUNK,
            ClientboundPackets26_1.LIGHT_UPDATE,
            ClientboundPackets26_1.CHUNK_BATCH_FINISHED,
            ClientboundPackets26_1.SET_CHUNK_CACHE_CENTER,
            ClientboundPackets26_1.SET_CHUNK_CACHE_RADIUS,
            ClientboundPackets26_1.GAME_EVENT,
            ClientboundPackets26_1.KEEP_ALIVE,
            ClientboundPackets26_1.PING,
            ClientboundPackets26_1.PONG_RESPONSE,
            ClientboundPackets26_1.PLAYER_INFO_UPDATE,
            ClientboundPackets26_1.PLAYER_INFO_REMOVE,
            ClientboundPackets26_1.TAB_LIST
    );

    private static final EnumSet<ServerboundPackets26_1> ALLOW_SERVERBOUND_BEFORE_OPEN = EnumSet.of(
            ServerboundPackets26_1.ACCEPT_TELEPORTATION,
            ServerboundPackets26_1.CHUNK_BATCH_RECEIVED,
            ServerboundPackets26_1.CUSTOM_PAYLOAD,
            ServerboundPackets26_1.KEEP_ALIVE,
            ServerboundPackets26_1.PING_REQUEST,
            ServerboundPackets26_1.PLAYER_LOADED,
            ServerboundPackets26_1.PONG
    );
    private static final EnumSet<ServerboundPackets26_1> QUEUE_SERVERBOUND_BEFORE_OPEN = EnumSet.noneOf(ServerboundPackets26_1.class);

    private final Queue<ClientboundEntry> clientboundQueue = new ArrayDeque<>();
    private final Queue<ServerboundEntry> serverboundQueue = new ArrayDeque<>();

    private JoinPhase joinPhase = JoinPhase.WAITING_LOGIN;
    private boolean javaConfigurationAcked;
    private boolean playerChunkReady;
    private boolean playerLoaded;
    private int playerChunkX;
    private int playerChunkZ;
    private long javaLoginSentNanos;

    public JoinGate(final UserConnection user) {
        super(user);
    }

    public static JoinGate install(final UserConnection user) {
        final JoinGate joinGate = new JoinGate(user);
        user.put(joinGate);
        user.put(new PacketSendInterceptor(user, wrapper -> {
            final JoinGate currentJoinGate = user.get(JoinGate.class);
            final PacketType packetType = wrapper.getPacketType();
            if (currentJoinGate == null || !(packetType instanceof ClientboundPackets26_1 packet)) {
                return false;
            }

            return currentJoinGate.interceptClientbound(packet, wrapper);
        }));
        return joinGate;
    }

    @Override
    public void onRemove() {
        for (ClientboundEntry queuedPacket : this.clientboundQueue) {
            queuedPacket.payload().release();
        }
        this.clientboundQueue.clear();

        for (ServerboundEntry queuedPacket : this.serverboundQueue) {
            queuedPacket.payload().release();
        }
        this.serverboundQueue.clear();
    }

    public void onJavaConfigurationAck() {
        if (this.javaConfigurationAcked) {
            return;
        }

        this.javaConfigurationAcked = true;
    }

    public void onPlayerChunkReady(final int chunkX, final int chunkZ) {
        if (this.playerChunkReady) {
            return;
        }

        this.playerChunkReady = true;
        this.playerChunkX = chunkX;
        this.playerChunkZ = chunkZ;
    }

    public void onPlayerChunkSent() {
        if (!this.markPlayerChunkSent()) {
            return;
        }

        if (this.playerLoaded) {
            this.tryOpenGate();
        }
    }

    public void onPlayerLoaded() {
        this.playerLoaded = true;
        this.tryOpenGate();
    }

    public boolean trySendJavaLogin() {
        if (!this.javaConfigurationAcked || !this.playerChunkReady || !this.markJavaLoginSent()) {
            return false;
        }

        JoinPackets.sendJavaLoginAndInitialPackets(this.user());
        return true;
    }

    public void sendReadyPlayerChunk() {
        if (!this.atLeastJoinPhase(JoinPhase.LOGIN_SENT) || !this.playerChunkReady || this.atLeastJoinPhase(JoinPhase.PLAYER_CHUNK_SENT)) {
            return;
        }

        final ChunkTracker chunkTracker = this.user().get(ChunkTracker.class);
        if (chunkTracker != null) {
            chunkTracker.alignCenterToClientPlayer();
            chunkTracker.sendCurrentCacheSettingsToJava();
            chunkTracker.sendChunk(this.playerChunkX, this.playerChunkZ);
        }
    }

    public void tick() {
        if (this.isJoinPhase(JoinPhase.OPEN) || !this.atLeastJoinPhase(JoinPhase.PLAYER_CHUNK_SENT)) {
            return;
        }
        if (System.nanoTime() - this.javaLoginSentNanos < OPEN_FALLBACK_NANOS) {
            return;
        }

        final EntityTracker entityTracker = this.user().get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
        if (clientPlayer != null && clientPlayer.hasPendingPositionSync()) {
            return;
        }

        this.tryOpenGate();
    }

    public void tryOpenGate() {
        if (!this.openGate()) {
            return;
        }

        this.flushClientboundQueue();
        this.flushServerboundQueue();
    }

    public boolean interceptClientbound(final ClientboundPackets26_1 packet, final PacketWrapper wrapper) throws InformativeException {
        if (this.isJoinPhase(JoinPhase.OPEN)) {
            return false;
        }
        if (!this.atLeastJoinPhase(JoinPhase.LOGIN_SENT) && PASS_CLIENTBOUND_BEFORE_LOGIN.contains(packet)) {
            return false;
        }
        if (!this.atLeastJoinPhase(JoinPhase.LOGIN_SENT) && DROP_CLIENTBOUND_BEFORE_LOGIN.contains(packet)) {
            return true;
        }
        if (this.atLeastJoinPhase(JoinPhase.LOGIN_SENT) && PASS_CLIENTBOUND_BEFORE_OPEN.contains(packet)) {
            return false;
        }

        return this.queueClientbound(packet, wrapper);
    }

    static boolean dropsClientboundBeforeLogin(final ClientboundPackets26_1 packet) {
        return DROP_CLIENTBOUND_BEFORE_LOGIN.contains(packet);
    }

    static boolean passesClientboundBeforeLogin(final ClientboundPackets26_1 packet) {
        return PASS_CLIENTBOUND_BEFORE_LOGIN.contains(packet);
    }

    public boolean interceptUnknownClientbound(final PacketWrapper wrapper) {
        if (this.isJoinPhase(JoinPhase.OPEN)) {
            return false;
        }
        return true;
    }

    public boolean interceptServerboundBeforeOpen(final ServerboundPackets26_1 packet, final PacketWrapper wrapper) {
        if (this.isJoinPhase(JoinPhase.OPEN) || ALLOW_SERVERBOUND_BEFORE_OPEN.contains(packet)) {
            return false;
        }
        if (QUEUE_SERVERBOUND_BEFORE_OPEN.contains(packet)) {
            return this.queueServerbound(packet, wrapper);
        }

        if (wrapper != null) {
            wrapper.cancel();
        }
        return true;
    }

    public boolean interceptUnknownServerboundBeforeOpen(final PacketWrapper wrapper) {
        if (this.isJoinPhase(JoinPhase.OPEN)) {
            return false;
        }

        if (wrapper != null) {
            wrapper.cancel();
        }
        return true;
    }

    boolean atLeastJoinPhase(final JoinPhase joinPhase) {
        return this.joinPhase.ordinal() >= joinPhase.ordinal();
    }

    private boolean isJoinPhase(final JoinPhase joinPhase) {
        return this.joinPhase == joinPhase;
    }

    public boolean isOpen() {
        return this.isJoinPhase(JoinPhase.OPEN);
    }

    private boolean markJavaLoginSent() {
        if (this.atLeastJoinPhase(JoinPhase.LOGIN_SENT)) {
            return false;
        }

        this.joinPhase = JoinPhase.LOGIN_SENT;
        this.javaLoginSentNanos = System.nanoTime();
        return true;
    }

    private boolean markPlayerChunkSent() {
        if (this.atLeastJoinPhase(JoinPhase.PLAYER_CHUNK_SENT)) {
            return false;
        }

        this.joinPhase = JoinPhase.PLAYER_CHUNK_SENT;
        return true;
    }

    private boolean openGate() {
        if (this.isJoinPhase(JoinPhase.OPEN) || !this.atLeastJoinPhase(JoinPhase.PLAYER_CHUNK_SENT)) {
            return false;
        }

        this.joinPhase = JoinPhase.OPEN;
        return true;
    }

    private boolean queueClientbound(final ClientboundPackets26_1 packet, final PacketWrapper wrapper) throws InformativeException {
        if (wrapper == null || this.clientboundQueue.size() >= MAX_CLIENTBOUND_QUEUE_SIZE) {
            return true;
        }

        final ByteBuf payload = this.copyPayloadWithoutPacketId(wrapper);
        this.clientboundQueue.add(new ClientboundEntry(packet, payload));
        return true;
    }

    private boolean queueServerbound(final ServerboundPackets26_1 packet, final PacketWrapper wrapper) {
        if (wrapper == null || this.serverboundQueue.size() >= MAX_SERVERBOUND_QUEUE_SIZE) {
            return true;
        }

        try {
            final ByteBuf payload = this.copyPayloadWithoutPacketId(wrapper);
            this.serverboundQueue.add(new ServerboundEntry(packet, payload));
        } catch (InformativeException ignored) {
        }
        if (wrapper != null) {
            wrapper.cancel();
        }
        return true;
    }

    private ByteBuf copyPayloadWithoutPacketId(final PacketWrapper wrapper) throws InformativeException {
        final ByteBuf packetBuffer = this.user().getChannel().alloc().buffer();
        try {
            wrapper.writeToBuffer(packetBuffer);
            Types.VAR_INT.readPrimitive(packetBuffer); // Drop the 26_1 packet id; replay restores it from the packet type.
            return packetBuffer.readBytes(packetBuffer.readableBytes());
        } finally {
            packetBuffer.release();
        }
    }

    private void flushClientboundQueue() {
        if (this.clientboundQueue.isEmpty()) {
            return;
        }

        final Iterator<ClientboundEntry> iterator = this.clientboundQueue.iterator();
        while (iterator.hasNext()) {
            final ClientboundEntry queuedPacket = iterator.next();
            iterator.remove();
            try {
                final PacketWrapper wrapper = PacketWrapper.create(queuedPacket.packet(), queuedPacket.payload().duplicate(), this.user());
                wrapper.send(BedrockProtocol.class);
                this.markQueuedBossBarAddDelivered(queuedPacket);
            } catch (InformativeException ignored) {
            } finally {
                queuedPacket.payload().release();
            }
        }
    }

    private void markQueuedBossBarAddDelivered(final ClientboundEntry queuedPacket) {
        if (queuedPacket.packet() != ClientboundPackets26_1.BOSS_EVENT) return;

        final ByteBuf payload = queuedPacket.payload().duplicate();
        final UUID uuid = Types.UUID.read(payload);
        final int operation = Types.VAR_INT.readPrimitive(payload);
        if (operation == BossEventOperationType.ADD.ordinal()) {
            this.user().get(BossBarStorage.class).markClientVisible(uuid);
        }
    }

    private void flushServerboundQueue() {
        if (this.serverboundQueue.isEmpty()) {
            return;
        }

        final Iterator<ServerboundEntry> iterator = this.serverboundQueue.iterator();
        while (iterator.hasNext()) {
            final ServerboundEntry queuedPacket = iterator.next();
            iterator.remove();
            try {
                final PacketWrapper wrapper = PacketWrapper.create(queuedPacket.packet(), queuedPacket.payload().duplicate(), this.user());
                wrapper.sendToServer(BedrockProtocol.class);
            } catch (InformativeException ignored) {
            } finally {
                queuedPacket.payload().release();
            }
        }
    }

    private record ClientboundEntry(ClientboundPackets26_1 packet, ByteBuf payload) {
    }

    private record ServerboundEntry(ServerboundPackets26_1 packet, ByteBuf payload) {
    }

    enum JoinPhase {
        WAITING_LOGIN,
        LOGIN_SENT,
        PLAYER_CHUNK_SENT,
        OPEN
    }

}
