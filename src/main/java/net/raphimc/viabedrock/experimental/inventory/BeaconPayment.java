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
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.Set;

/**
 * Java 的 {@code SET_BEACON} 会变成 MOT {@code BeaconPayment} 加上对支付槽位 0 的 Destroy。
 * 1.20.2+ 的 Java 效果 id 从 0 开始；MOT 从 1 开始。
 */
public final class BeaconPayment {

    private static final Set<Integer> ALLOWED_MOT_EFFECTS = Set.of(0, 1, 3, 5, 8, 10, 11);

    private BeaconPayment() {
    }

    public static boolean send(final UserConnection user, final Integer javaPrimary, final Integer javaSecondary) {
        final GameSessionStorage session = user.get(GameSessionStorage.class);
        if (session == null || !session.isInventoryServerAuthoritative()) {
            return false;
        }
        final InventoryTracker tracker = user.get(InventoryTracker.class);
        final Container container = tracker != null ? tracker.getCurrentContainer() : null;
        if (container == null || container.type() != ContainerType.BEACON) {
            return false;
        }
        final BedrockItem payment = container.getItem(0);
        if (payment == null || payment.isEmpty()) {
            return false;
        }
        final int primary = toMotEffect(javaPrimary);
        final int secondary = toMotEffect(javaSecondary);
        if (!ALLOWED_MOT_EFFECTS.contains(primary) || !ALLOWED_MOT_EFFECTS.contains(secondary)) {
            return false;
        }
        final ItemStackRequestEncoder.EncodedRequest encoded = ItemStackRequestEncoder.encodeBeaconPayment(
                tracker, primary, secondary);
        if (encoded.unsupported() || encoded.isEmpty()) {
            return false;
        }
        final PacketWrapper request = PacketWrapper.create(ServerboundBedrockPackets.ITEM_STACK_REQUEST, user);
        request.write(Types.REMAINING_BYTES, encoded.payload());
        request.sendToServer(BedrockProtocol.class);
        return true;
    }

    static int toMotEffect(final Integer javaEffectId) {
        if (javaEffectId == null || javaEffectId < 0) {
            return 0;
        }
        if (BedrockProtocol.MAPPINGS == null || BedrockProtocol.MAPPINGS.getJavaEffects() == null) {
            return -1;
        }
        final String javaIdentifier = BedrockProtocol.MAPPINGS.getJavaEffects().inverse().get(javaEffectId);
        if (javaIdentifier == null) {
            return -1;
        }
        for (final var entry : BedrockProtocol.MAPPINGS.getBedrockToJavaEffects().entrySet()) {
            if (javaIdentifier.equals(entry.getValue())) {
                final Integer bedrockId = BedrockProtocol.MAPPINGS.getBedrockEffects().get(entry.getKey());
                return bedrockId != null ? bedrockId : -1;
            }
        }
        return -1;
    }
}
