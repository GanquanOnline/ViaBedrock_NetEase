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
package net.raphimc.viabedrock.api.modinterface;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.storage.ClientLightStorage;

import java.util.concurrent.TimeUnit;

/** ECClientLight 在配置阶段的能力协商。 */
public final class ECClientLightInterface {

    public static final String CONFIRM_CHANNEL = "ecclientlight:confirm_v1";

    private ECClientLightInterface() {
    }

    public static void probeClient(final UserConnection user) {
        if (!onEventLoop(user, () -> probeClient(user))) {
            return;
        }
        final ClientLightStorage storage = user.get(ClientLightStorage.class);
        if (storage == null || !storage.markProbeSent(System.nanoTime())) {
            return;
        }

        try {
            ClientChannelDiscovery.request(user);
        } catch (final RuntimeException e) {
            storage.close();
            throw e;
        }
        ViaBedrock.getPlatform().getLogger().fine("Requested Java client channel discovery for ECClientLight capability");
    }

    public static void confirmPresence(final UserConnection user) {
        if (!onEventLoop(user, () -> confirmPresence(user))) {
            return;
        }
        final ClientLightStorage storage = user.get(ClientLightStorage.class);
        if (storage == null || !storage.tryNegotiateClientComputed()) {
            return;
        }

        try {
            final PacketWrapper confirmation = PacketWrapper.create(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, user);
            confirmation.write(Types.STRING, CONFIRM_CHANNEL);
            confirmation.write(Types.SERVERBOUND_CUSTOM_PAYLOAD_DATA, new byte[0]);
            confirmation.send(BedrockProtocol.class);
            user.getChannel().flush();
        } catch (final RuntimeException e) {
            storage.close();
            throw e;
        }

        ViaBedrock.getPlatform().getLogger().fine("ECClientLight v1 negotiated; delegating chunk light computation to the Java client");
        final Runnable pendingFinish = storage.releasePendingFinishAfterNegotiation();
        if (pendingFinish != null) {
            pendingFinish.run();
        }
    }

    public static void finishConfigurationWhenReady(final UserConnection user, final Runnable finish) {
        if (!onEventLoop(user, () -> finishConfigurationWhenReady(user, finish))) {
            return;
        }
        final ClientLightStorage storage = user.get(ClientLightStorage.class);
        if (storage == null) {
            finish.run();
            return;
        }

        final ClientLightStorage.FinishRequest request = storage.requestFinish(System.nanoTime(), finish);
        switch (request.decision()) {
            case RUN -> finish.run();
            case WAIT -> {
                ViaBedrock.getPlatform().getLogger().fine("Waiting up to " + TimeUnit.NANOSECONDS.toMillis(request.waitNanos()) + " ms for ECClientLight negotiation");
                scheduleTimeout(user, storage, request.waitNanos());
            }
            case DUPLICATE -> ViaBedrock.getPlatform().getLogger().fine("Ignoring duplicate Java configuration finish request");
            case CLOSED -> {
            }
        }
    }

    private static void scheduleTimeout(final UserConnection user, final ClientLightStorage storage, final long waitNanos) {
        if (user.getChannel() == null || !user.getChannel().isActive()) {
            storage.close();
            return;
        }
        storage.attachTimeout(user.getChannel().eventLoop().schedule(() -> {
            if (user.getChannel() == null || !user.getChannel().isActive()) {
                storage.close();
                return;
            }

            final long nowNanos = System.nanoTime();
            final Runnable pendingFinish = storage.timeoutPendingFinish(nowNanos);
            if (pendingFinish != null) {
                ViaBedrock.getPlatform().getLogger().fine("ECClientLight negotiation timed out; using proxy-computed light");
                pendingFinish.run();
            } else if (storage.hasPendingFinish()) {
                scheduleTimeout(user, storage, Math.max(1L, storage.remainingNegotiationNanos(nowNanos)));
            }
        }, Math.max(1L, waitNanos), TimeUnit.NANOSECONDS));
    }

    private static boolean onEventLoop(final UserConnection user, final Runnable retry) {
        if (user.getChannel() == null || !user.getChannel().isActive()) {
            final ClientLightStorage storage = user.get(ClientLightStorage.class);
            if (storage != null) {
                storage.close();
            }
            return false;
        }
        if (!user.getChannel().eventLoop().inEventLoop()) {
            user.getChannel().eventLoop().execute(retry);
            return false;
        }
        return true;
    }

}
