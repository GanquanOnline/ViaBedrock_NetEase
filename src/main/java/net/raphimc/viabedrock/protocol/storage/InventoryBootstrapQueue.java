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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.exception.InformativeException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.ScheduledFuture;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class InventoryBootstrapQueue extends StoredObject {

    static final int DEFAULT_MAX_PACKETS = 512;
    static final int DEFAULT_MAX_BYTES = 4 * 1024 * 1024;
    static final long DEFAULT_REGISTRY_TIMEOUT_MILLIS = 10_000L;

    private static final EnumSet<ClientboundBedrockPackets> INVENTORY_PACKETS = EnumSet.of(
            ClientboundBedrockPackets.INVENTORY_CONTENT,
            ClientboundBedrockPackets.INVENTORY_SLOT,
            ClientboundBedrockPackets.PLAYER_HOTBAR,
            // MOT 的 processLogin() 在 ItemComponent (ITEM_REGISTRY) 之后发送
            // CREATIVE_CONTENT / MOB_EQUIPMENT。只有在重写器已填充之后才重放它们，
            // 这样第一次手持物品重写不会是空的。
            ClientboundBedrockPackets.CREATIVE_CONTENT,
            ClientboundBedrockPackets.MOB_EQUIPMENT,
            ClientboundBedrockPackets.MOB_ARMOR_EQUIPMENT
    );

    private final Queue<DeferredPacket> inventoryPackets = new ArrayDeque<>();
    private final PacketReplayer packetReplayer;
    private final FailureHandler failureHandler;
    private final Consumer<String> debugLogger;
    private final int maxPackets;
    private final int maxBytes;
    private final long registryTimeoutMillis;

    private DeferredPacket earlyItemRegistry;
    private ScheduledFuture<?> registryTimeoutTask;
    private boolean playReady;
    private boolean registryReady;
    private boolean flushScheduled;
    private boolean flushing;
    private boolean failed;
    private int queuedBytes;
    private long firstDeferredNanos;

    public InventoryBootstrapQueue(final UserConnection user) {
        this(user,
                (packet, payload) -> PacketWrapper.create(packet, payload, user).send(BedrockProtocol.class, false),
                (reason, throwable) -> BedrockProtocol.kickForIllegalState(user, reason, throwable),
                message -> ViaBedrock.getPlatform().getLogger().fine(message),
                DEFAULT_MAX_PACKETS,
                DEFAULT_MAX_BYTES,
                DEFAULT_REGISTRY_TIMEOUT_MILLIS);
    }

    InventoryBootstrapQueue(final UserConnection user, final PacketReplayer packetReplayer, final FailureHandler failureHandler,
                            final Consumer<String> debugLogger, final int maxPackets, final int maxBytes,
                            final long registryTimeoutMillis) {
        super(user);
        this.packetReplayer = packetReplayer;
        this.failureHandler = failureHandler;
        this.debugLogger = debugLogger;
        this.maxPackets = maxPackets;
        this.maxBytes = maxBytes;
        this.registryTimeoutMillis = registryTimeoutMillis;
    }

    public boolean deferIfNeeded(final ClientboundBedrockPackets packet, final PacketWrapper wrapper, final State serverState) {
        if (this.failed) {
            return true;
        }

        if (packet == ClientboundBedrockPackets.ITEM_REGISTRY) {
            if (serverState == State.PLAY) {
                return false;
            }
            this.playReady = false;
            final ByteBuf payload = this.copyPayload(wrapper);
            return payload == null || this.deferOwnedPayload(packet, payload, serverState);
        }
        if (!INVENTORY_PACKETS.contains(packet)) {
            return false;
        }

        this.playReady = serverState == State.PLAY;
        if (this.playReady && this.registryReady) {
            return false;
        }
        final ByteBuf payload = this.copyPayload(wrapper);
        return payload == null || this.deferOwnedPayload(packet, payload, serverState);
    }

    boolean deferOwnedPayload(final ClientboundBedrockPackets packet, final ByteBuf payload, final State serverState) {
        if (this.failed) {
            payload.release();
            return true;
        }

        final int packetCount = this.inventoryPackets.size() + (this.earlyItemRegistry != null ? 1 : 0);
        if (packetCount >= this.maxPackets || payload.readableBytes() > this.maxBytes - this.queuedBytes) {
            payload.release();
            this.fail("Inventory bootstrap queue exceeded its safety limit", null);
            return true;
        }

        final DeferredPacket deferredPacket = new DeferredPacket(packet, payload);
        if (packet == ClientboundBedrockPackets.ITEM_REGISTRY) {
            if (this.earlyItemRegistry != null) {
                deferredPacket.release();
                this.fail("Received multiple ITEM_REGISTRY packets before PLAY", null);
                return true;
            }
            this.earlyItemRegistry = deferredPacket;
        } else {
            this.inventoryPackets.add(deferredPacket);
        }

        this.queuedBytes += payload.readableBytes();
        if (this.firstDeferredNanos == 0L) {
            this.firstDeferredNanos = System.nanoTime();
            this.debugLogger.accept("Deferring inventory bootstrap packets while waiting for PLAY and ITEM_REGISTRY");
        }
        if (serverState == State.PLAY) {
            this.playReady = true;
            this.ensureRegistryTimeout();
        }
        return true;
    }

    public void onPlayReady() {
        if (this.failed) {
            return;
        }
        this.playReady = true;
        this.ensureRegistryTimeout();
        this.scheduleFlush();
    }

    public void onItemRegistryReady() {
        if (this.failed) {
            return;
        }
        this.registryReady = true;
        this.cancelRegistryTimeout();
        this.scheduleFlush();
    }

    void onRegistryTimeout() {
        if (!this.failed && this.playReady && !this.registryReady && !this.inventoryPackets.isEmpty()) {
            this.fail("Timed out waiting for ITEM_REGISTRY with " + this.inventoryPackets.size() + " deferred inventory packets", null);
        }
    }

    @Override
    public void onRemove() {
        this.failed = true;
        this.cancelRegistryTimeout();
        this.releaseQueuedPackets();
    }

    private ByteBuf copyPayload(final PacketWrapper wrapper) {
        final Channel channel = this.user().getChannel();
        if (channel == null) {
            this.fail("Could not copy an inventory bootstrap packet payload", null);
            return null;
        }

        final ByteBuf packetBuffer = channel.alloc().buffer();
        try {
            wrapper.writeToBuffer(packetBuffer);
            Types.VAR_INT.readPrimitive(packetBuffer); // 重放会根据包类型还原基岩数据包 id。
            return packetBuffer.readBytes(packetBuffer.readableBytes());
        } catch (Throwable throwable) {
            this.fail("Could not copy an inventory bootstrap packet payload", throwable);
            return null;
        } finally {
            packetBuffer.release();
        }
    }

    private void scheduleFlush() {
        if (this.failed || !this.playReady || this.flushScheduled || this.flushing
                || (this.earlyItemRegistry == null && this.inventoryPackets.isEmpty())) {
            return;
        }
        final Channel channel = this.user().getChannel();
        if (channel == null || !channel.isActive()) {
            this.fail("Connection closed before inventory bootstrap packets could be replayed", null);
            return;
        }

        this.flushScheduled = true;
        channel.eventLoop().execute(() -> {
            this.flushScheduled = false;
            this.flush();
        });
    }

    private void flush() {
        if (this.failed || this.flushing || !this.playReady) {
            return;
        }

        this.flushing = true;
        try {
            if (this.earlyItemRegistry != null) {
                final DeferredPacket registry = this.earlyItemRegistry;
                this.earlyItemRegistry = null;
                this.queuedBytes -= registry.payload().readableBytes();
                this.replay(registry);
                if (this.inventoryPackets.isEmpty()) {
                    this.firstDeferredNanos = 0L;
                }
                if (!this.registryReady) {
                    this.ensureRegistryTimeout();
                }
                return;
            }
            if (!this.registryReady) {
                this.ensureRegistryTimeout();
                return;
            }

            final int replayedPackets = this.inventoryPackets.size();
            while (!this.inventoryPackets.isEmpty()) {
                final DeferredPacket packet = this.inventoryPackets.remove();
                this.queuedBytes -= packet.payload().readableBytes();
                this.replay(packet);
                if (this.failed) {
                    return;
                }
            }
            if (replayedPackets > 0) {
                final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.firstDeferredNanos);
                this.debugLogger.accept("Replayed " + replayedPackets + " inventory bootstrap packets after " + elapsedMillis + "ms");
                this.firstDeferredNanos = 0L;
            }
        } finally {
            this.flushing = false;
            if (!this.failed && this.playReady && this.registryReady && !this.inventoryPackets.isEmpty()) {
                this.scheduleFlush();
            }
        }
    }

    private void replay(final DeferredPacket packet) {
        try {
            this.packetReplayer.replay(packet.packet(), packet.payload().duplicate());
        } catch (Throwable throwable) {
            this.fail("Failed to replay deferred inventory bootstrap packet " + packet.packet(), throwable);
        } finally {
            packet.release();
        }
    }

    private void ensureRegistryTimeout() {
        if (this.failed || !this.playReady || this.registryReady || this.inventoryPackets.isEmpty() || this.registryTimeoutTask != null) {
            return;
        }
        final Channel channel = this.user().getChannel();
        if (channel == null || !channel.isActive()) {
            this.fail("Connection closed while waiting for ITEM_REGISTRY", null);
            return;
        }
        this.registryTimeoutTask = channel.eventLoop().schedule(this::onRegistryTimeout, this.registryTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void cancelRegistryTimeout() {
        if (this.registryTimeoutTask != null) {
            this.registryTimeoutTask.cancel(false);
            this.registryTimeoutTask = null;
        }
    }

    private void fail(final String reason, final Throwable throwable) {
        if (this.failed) {
            return;
        }
        this.failed = true;
        this.cancelRegistryTimeout();
        this.releaseQueuedPackets();
        this.failureHandler.fail(reason, throwable);
    }

    private void releaseQueuedPackets() {
        if (this.earlyItemRegistry != null) {
            this.earlyItemRegistry.release();
            this.earlyItemRegistry = null;
        }
        while (!this.inventoryPackets.isEmpty()) {
            this.inventoryPackets.remove().release();
        }
        this.queuedBytes = 0;
        this.firstDeferredNanos = 0L;
    }

    @FunctionalInterface
    interface PacketReplayer {
        void replay(ClientboundBedrockPackets packet, ByteBuf payload) throws InformativeException;
    }

    @FunctionalInterface
    interface FailureHandler {
        void fail(String reason, Throwable throwable);
    }

    private record DeferredPacket(ClientboundBedrockPackets packet, ByteBuf payload) {
        private void release() {
            this.payload.release();
        }
    }

}
