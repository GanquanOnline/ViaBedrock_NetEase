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

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.player.InventoryContainer;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.DeferredEntityActionQueue;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.logging.Level;

public final class EntityInteractionPacketSender {

    private EntityInteractionPacketSender() {
    }

    public static void enqueueDeferred(final ClientPlayerEntity clientPlayer, final Entity entity,
                                       final int actionType, final Position3f clickPosition) {
        enqueueDeferred(clientPlayer, entity, actionType, clickPosition, false);
    }

    public static void enqueueDeferred(final ClientPlayerEntity clientPlayer, final Entity entity,
                                       final int actionType, final Position3f clickPosition,
                                       final boolean swingAfter) {
        final DeferredEntityActionQueue.EnqueueResult result = clientPlayer.deferredEntityActions().enqueue(
                actionType,
                entity.runtimeId(),
                entity.uniqueId(),
                clickPosition.x(),
                clickPosition.y(),
                clickPosition.z(),
                clientPlayer.age(),
                swingAfter
        );
        if (result == DeferredEntityActionQueue.EnqueueResult.FULL_FIRST) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING,
                    "Deferred entity action queue is full; dropping this and further actions until it drains");
        }
    }

    public static void flushDeferred(final UserConnection user, final ClientPlayerEntity clientPlayer) {
        final DeferredEntityActionQueue queue = clientPlayer.deferredEntityActions();
        queue.discardExpired(clientPlayer.age());
        if (clientPlayer.isOffhandRestoring() || queue.isEmpty()) {
            return;
        }

        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final InventoryTracker inventoryTracker = user.get(InventoryTracker.class);
        if (entityTracker == null || inventoryTracker == null) {
            queue.clear();
            return;
        }

        final InventoryContainer inventoryContainer = inventoryTracker.getInventoryContainer();
        while (!queue.isEmpty()) {
            final DeferredEntityActionQueue.Action action = queue.peekFirst();
            final Entity entity = entityTracker.getEntityByRid(action.entityRuntimeId());
            if (entity == null || !matchesTarget(action, entity.runtimeId(), entity.uniqueId())) {
                queue.removeFirst();
                continue;
            }

            final PacketWrapper transaction = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
            write(
                    transaction,
                    action.entityRuntimeId(),
                    action.actionType(),
                    0, // 延迟队列里的动作（攻击 / 主手交互）都是主手
                    (int) inventoryContainer.getSelectedHotbarSlot(),
                    inventoryContainer.getSelectedHotbarItem(),
                    clientPlayer.position(),
                    new Position3f(action.clickX(), action.clickY(), action.clickZ())
            );
            try {
                transaction.sendToServer(BedrockProtocol.class);
                if (action.swingAfter()) {
                    clientPlayer.sendSwingPacketToServer();
                }
            } catch (final Exception e) {
                throw new IllegalStateException("Unable to send deferred MOT entity interaction", e);
            }
            queue.removeFirst();
        }
    }

    static boolean matchesTarget(final DeferredEntityActionQueue.Action action,
                                 final long runtimeId, final long uniqueId) {
        return action.entityRuntimeId() == runtimeId && action.entityUniqueId() == uniqueId;
    }

    public static void write(final PacketWrapper wrapper, final long entityRuntimeId, final int actionType,
                             final int hand, final int hotbarSlot, final BedrockItem itemInHand,
                             final Position3f playerPosition, final Position3f clickPosition) {
        final int protocol = BedrockWire.current();
        wrapper.write(Types.REMAINING_BYTES, EntityInteractionPacketLayout.encode(
                wrapper.user().get(ItemRewriter.class).itemType(protocol),
                protocol,
                entityRuntimeId,
                actionType,
                hand,
                hotbarSlot,
                itemInHand,
                playerPosition,
                clickPosition
        ));
    }

}
