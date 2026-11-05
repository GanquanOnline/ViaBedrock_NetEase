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
package net.raphimc.viabedrock.protocol.packet;

import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

public final class ScriptMessagePackets {

    private ScriptMessagePackets() {
    }

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.SCRIPT_MESSAGE, null, wrapper -> {
            wrapper.cancel();
            final String messageId = wrapper.read(BedrockTypes.STRING); // 消息 ID
            final String payload = wrapper.read(BedrockTypes.STRING); // 值

            if (JavaCustomPayloadBridge.bridgeClientbound(messageId, payload, wrapper.user())) {
                return;
            } else if (PlayerLatencyPackets.isLatencyMessage(messageId)) {
                PlayerLatencyPackets.handle(wrapper, payload);
            } else if (PlayerIdentityPackets.isIdentityMessage(messageId)) {
                PlayerIdentityPackets.handle(wrapper, payload);
            } else if (SpectatorCameraPackets.MESSAGE_ID_V1.equals(messageId)) {
                SpectatorCameraPackets.handleLegacy(wrapper, payload);
            } else if (SpectatorCameraPackets.MESSAGE_ID_V2.equals(messageId)) {
                SpectatorCameraPackets.handle(wrapper, payload);
            }
        });
    }

}
