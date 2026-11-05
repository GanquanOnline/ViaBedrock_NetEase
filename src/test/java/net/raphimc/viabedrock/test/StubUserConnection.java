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
package net.raphimc.viabedrock.test;

import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.ProtocolStorables;
import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.data.entity.EntityTracker;
import com.viaversion.viaversion.api.data.item.ItemHasher;
import com.viaversion.viaversion.api.minecraft.ClientWorld;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolPipeline;
import com.viaversion.viaversion.api.protocol.packet.PacketTracker;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.exception.InformativeException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.CodecException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 轻量级 {@link UserConnection}，供只需要存储对象的测试使用。
 * {@link com.viaversion.viaversion.connection.UserConnectionImpl} 要求 Via
 * 已加载，因为它会从全局管理器计算协议可存储项的大小。
 */
public final class StubUserConnection implements UserConnection {

    private final Map<Class<?>, StorableObject> storedObjects = new ConcurrentHashMap<>();
    private final Channel channel;
    private final PacketTracker packetTracker = new PacketTracker(this);
    private final ProtocolInfo protocolInfo = new StubProtocolInfo();
    private boolean active = true;
    private boolean pendingDisconnect;

    public StubUserConnection(final Channel channel) {
        this.channel = channel;
    }

    @Override
    public <T extends StorableObject> T get(final Class<T> objectClass) {
        return (T) this.storedObjects.get(objectClass);
    }

    @Override
    public boolean has(final Class<? extends StorableObject> objectClass) {
        return this.storedObjects.containsKey(objectClass);
    }

    @Override
    public <T extends StorableObject> T remove(final Class<T> objectClass) {
        final StorableObject object = this.storedObjects.remove(objectClass);
        if (object != null) {
            object.onRemove();
        }
        return (T) object;
    }

    @Override
    public void put(final StorableObject object) {
        final StorableObject previous = this.storedObjects.put(object.getClass(), object);
        if (previous != null) {
            previous.onRemove();
        }
    }

    @Override
    public <T extends ProtocolStorables> T storables(final Protocol<?, ?, ?, ?> protocol) {
        throw new UnsupportedOperationException("stub does not load Via protocols");
    }

    @Override
    public Collection<EntityTracker> getEntityTrackers() {
        return List.of();
    }

    @Override
    public <T extends EntityTracker> T getEntityTracker(final Class<? extends Protocol> protocolClass) {
        return null;
    }

    @Override
    public <T extends EntityTracker> T getEntityTracker(final Protocol<?, ?, ?, ?> protocol) {
        return null;
    }

    @Override
    public void addEntityTracker(final Class<? extends Protocol> protocolClass, final EntityTracker tracker) {
    }

    @Override
    public <T extends ItemHasher> T getItemHasher(final Protocol<?, ?, ?, ?> protocol) {
        return null;
    }

    @Override
    public void addItemHasher(final Class<? extends Protocol> protocolClass, final ItemHasher itemHasher) {
    }

    @Override
    public <T extends ItemHasher> T getItemHasher(final Class<? extends Protocol> protocolClass) {
        return null;
    }

    @Override
    public <T extends ClientWorld> T getClientWorld(final Class<? extends Protocol> protocolClass) {
        return null;
    }

    @Override
    public void addClientWorld(final Class<? extends Protocol> protocolClass, final ClientWorld clientWorld) {
    }

    @Override
    public void clearStoredObjects() {
        this.storedObjects.clear();
    }

    @Override
    public void sendRawPacket(final ByteBuf packet) {
    }

    @Override
    public void scheduleSendRawPacket(final ByteBuf packet) {
    }

    @Override
    public ChannelFuture sendRawPacketFuture(final ByteBuf packet) {
        return this.channel != null ? this.channel.newSucceededFuture() : null;
    }

    @Override
    public PacketTracker getPacketTracker() {
        return this.packetTracker;
    }

    @Override
    public void disconnect(final String reason) {
        this.pendingDisconnect = true;
    }

    @Override
    public void sendRawPacketToServer(final ByteBuf packet) {
    }

    @Override
    public void scheduleSendRawPacketToServer(final ByteBuf packet) {
    }

    @Override
    public boolean checkServerboundPacket(final int bytes) {
        return true;
    }

    @Override
    public boolean checkClientboundPacket() {
        return true;
    }

    @Override
    public boolean shouldTransformPacket() {
        return false;
    }

    @Override
    public void transformClientbound(final ByteBuf buf, final Function<Throwable, CodecException> cancelSupplier) throws InformativeException {
    }

    @Override
    public void transformServerbound(final ByteBuf buf, final Function<Throwable, CodecException> cancelSupplier) throws InformativeException {
    }

    @Override
    public long getId() {
        return 0L;
    }

    @Override
    public Channel getChannel() {
        return this.channel;
    }

    @Override
    public ProtocolInfo getProtocolInfo() {
        return this.protocolInfo;
    }

    @Override
    public Map<Class<?>, StorableObject> getStoredObjects() {
        return this.storedObjects;
    }

    @Override
    public boolean isActive() {
        return this.active;
    }

    @Override
    public void setActive(final boolean active) {
        this.active = active;
    }

    @Override
    public boolean isPendingDisconnect() {
        return this.pendingDisconnect;
    }

    @Override
    public void setPendingDisconnect(final boolean pendingDisconnect) {
        this.pendingDisconnect = pendingDisconnect;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public long generatePassthroughToken() {
        return 0L;
    }

    private static final class StubProtocolInfo implements ProtocolInfo {
        private State clientState = State.PLAY;
        private State serverState = State.PLAY;
        private ProtocolVersion protocolVersion = ProtocolVersion.v1_21_11;
        private ProtocolVersion serverProtocolVersion = ProtocolVersion.unknown;
        private String username;
        private UUID uuid;
        private ProtocolPipeline pipeline;
        private boolean compressionEnabled;

        @Override
        public State getClientState() {
            return this.clientState;
        }

        @Override
        public State getServerState() {
            return this.serverState;
        }

        @Override
        public void setClientState(final State clientState) {
            this.clientState = clientState;
        }

        @Override
        public void setServerState(final State serverState) {
            this.serverState = serverState;
        }

        @Override
        public ProtocolVersion protocolVersion() {
            return this.protocolVersion;
        }

        @Override
        public void setProtocolVersion(final ProtocolVersion protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        @Override
        public ProtocolVersion serverProtocolVersion() {
            return this.serverProtocolVersion;
        }

        @Override
        public void setServerProtocolVersion(final ProtocolVersion protocolVersion) {
            this.serverProtocolVersion = protocolVersion;
        }

        @Override
        public String getUsername() {
            return this.username;
        }

        @Override
        public void setUsername(final String username) {
            this.username = username;
        }

        @Override
        public UUID getUuid() {
            return this.uuid;
        }

        @Override
        public void setUuid(final UUID uuid) {
            this.uuid = uuid;
        }

        @Override
        public boolean compressionEnabled() {
            return this.compressionEnabled;
        }

        @Override
        public void setCompressionEnabled(final boolean compressionEnabled) {
            this.compressionEnabled = compressionEnabled;
        }

        @Override
        public ProtocolPipeline getPipeline() {
            return this.pipeline;
        }

        @Override
        public void setPipeline(final ProtocolPipeline pipeline) {
            this.pipeline = pipeline;
        }
    }
}

