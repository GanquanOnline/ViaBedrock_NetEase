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
package net.raphimc.viabedrock.experimental.riding;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.FeatureModule;
import net.raphimc.viabedrock.experimental.model.PlayerAuthInputContext;
import net.raphimc.viabedrock.experimental.storage.JavaPassengerTracker;
import net.raphimc.viabedrock.experimental.storage.RidingTracker;
import net.raphimc.viabedrock.experimental.util.ProtocolUtil;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InteractPacket_Action;
import net.raphimc.viabedrock.protocol.data.enums.java.InputFlag;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.PlayerCommandAction;
import net.raphimc.viabedrock.protocol.model.EntityLink;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.packet.EntityPacketLayout;
import net.raphimc.viabedrock.protocol.packet.InteractPacketLayout;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.EnumSet;
import java.util.Set;
import java.util.logging.Level;

public class RidingModule implements FeatureModule {

    @Override
    public void onStorageRegistration(final UserConnection user) {
        user.put(new JavaPassengerTracker(user));
        user.put(new RidingTracker(user));
    }

    @Override
    public void onPacketRegistration(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.SET_ENTITY_LINK, null, wrapper -> {
            wrapper.cancel();
            final RidingTracker tracker = wrapper.user().get(RidingTracker.class);
            if (tracker == null) {
                return;
            }
            try {
                tracker.handleLink(wrapper.read(BedrockTypes.ENTITY_LINK));
            } catch (final Exception e) {
                ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to inspect SET_ENTITY_LINK riding link", e);
            }
        });

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.PLAYER_INPUT, wrapper -> {
            final RidingTracker tracker = wrapper.user().get(RidingTracker.class);
            if (tracker == null) {
                return;
            }

            final Set<InputFlag> inputFlags = inputFlags(wrapper.passthrough(Types.BYTE));
            final Entity vehicle = tracker.localVehicle();
            if (vehicle == null) {
                tracker.setLastInputFlags(inputFlags);
                tracker.updateRidingShift(false);
                return;
            }

            if (!inputFlags.contains(InputFlag.SHIFT)) {
                tracker.setLastInputFlags(inputFlags);
                tracker.updateRidingShift(false);
                return;
            }

            if (tracker.updateRidingShift(true)) {
                tracker.requestLocalDismount(vehicle);
                sendInteract(wrapper.user(), vehicle.runtimeId(), InteractPacket_Action.StopRiding);
            }
            inputFlags.remove(InputFlag.SHIFT);
            tracker.setLastInputFlags(inputFlags);
            wrapper.cancel();
        });

        // Java PADDLE_BOAT is auto-cancelled. MOT 860 Player.handle(AnimatePacket)
        // only updates boat paddle metadata from ROW_LEFT/ROW_RIGHT + rowingTime.
        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.PADDLE_BOAT, wrapper -> {
            wrapper.cancel();
            final boolean leftPaddle = wrapper.read(Types.BOOLEAN);
            final boolean rightPaddle = wrapper.read(Types.BOOLEAN);
            sendBoatPaddleAnimate(wrapper.user(), leftPaddle, rightPaddle);
        });

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.PLAYER_COMMAND, wrapper -> {
            wrapper.passthrough(Types.VAR_INT); // entity id
            final int actionId = wrapper.passthrough(Types.VAR_INT); // action
            wrapper.passthrough(Types.VAR_INT); // data
            if (actionId < 0 || actionId >= PlayerCommandAction.values().length) {
                wrapper.cancel();
                return;
            }

            final PlayerCommandAction action = PlayerCommandAction.values()[actionId];
            switch (action) {
                case START_RIDING_JUMP, STOP_RIDING_JUMP -> wrapper.cancel();
                case OPEN_INVENTORY -> {
                    final RidingTracker tracker = wrapper.user().get(RidingTracker.class);
                    final Entity vehicle = tracker != null ? tracker.localVehicle() : null;
                    if (vehicle != null) {
                        sendInteract(wrapper.user(), vehicle.runtimeId(), InteractPacket_Action.OpenInventory);
                    } else {
                        // Java PLAYER_COMMAND.OPEN_INVENTORY has no Bedrock equivalent besides Interact.6.
                        // GanAC PacketFlow requires that precursor before TYPE_NORMAL inventory clicks.
                        PacketFactory.sendBedrockOpenInventory(wrapper.user());
                    }
                    wrapper.cancel();
                }
            }
        });

        ProtocolUtil.prependServerbound(protocol, ServerboundPackets26_1.MOVE_VEHICLE, wrapper -> {
            final double x = wrapper.passthrough(Types.DOUBLE); // x
            final double y = wrapper.passthrough(Types.DOUBLE); // y
            final double z = wrapper.passthrough(Types.DOUBLE); // z
            final float yaw = wrapper.passthrough(Types.FLOAT); // yaw
            final float pitch = wrapper.passthrough(Types.FLOAT); // pitch
            final boolean onGround = wrapper.passthrough(Types.BOOLEAN); // on ground

            final RidingTracker tracker = wrapper.user().get(RidingTracker.class);
            if (tracker != null && tracker.isLocalRiding()) {
                tracker.handleMoveVehicle(x, y, z, yaw, pitch, onGround);
            }
            wrapper.cancel();
        });
    }

    @Override
    public void onDimensionChange(final UserConnection user) {
        final RidingTracker ridingTracker = user.get(RidingTracker.class);
        if (ridingTracker != null) {
            ridingTracker.resetForDimensionChange();
        }
        final JavaPassengerTracker passengerTracker = user.get(JavaPassengerTracker.class);
        if (passengerTracker != null) {
            passengerTracker.resetForDimensionChange();
        }
    }

    @Override
    public void onEntityAdded(final UserConnection user, final Entity entity) {
        final RidingTracker tracker = user.get(RidingTracker.class);
        if (tracker != null) {
            tracker.onEntityAdded(entity);
        }
    }

    @Override
    public void onEntityRemoved(final UserConnection user, final Entity entity) {
        final RidingTracker tracker = user.get(RidingTracker.class);
        if (tracker != null) {
            tracker.onEntityRemoved(entity);
        }
    }

    @Override
    public void onEntityLinks(final UserConnection user, final EntityLink[] links) {
        final RidingTracker tracker = user.get(RidingTracker.class);
        if (tracker == null) {
            return;
        }
        for (final EntityLink link : links) {
            tracker.handleLink(link);
        }
    }

    @Override
    public void onEntityDataChanged(final UserConnection user, final Entity entity, final EntityData[] entityData) {
        final RidingTracker tracker = user.get(RidingTracker.class);
        if (tracker != null) {
            tracker.onEntityDataChanged(entity);
        }
    }

    @Override
    public void onEntityMoved(final UserConnection user, final Entity entity) {
        final RidingTracker tracker = user.get(RidingTracker.class);
        if (tracker != null) {
            tracker.onEntityMoved(entity);
        }
    }

    @Override
    public void onPlayerAuthInput(final UserConnection user, final ClientPlayerEntity clientPlayer, final PlayerAuthInputContext context) {
        final RidingTracker tracker = user.get(RidingTracker.class);
        if (tracker != null) {
            tracker.applyAuthInput(clientPlayer, context);
        }
    }

    private static Set<InputFlag> inputFlags(final short flags) {
        final Set<InputFlag> inputFlags = EnumSet.noneOf(InputFlag.class);
        for (final InputFlag flag : InputFlag.values()) {
            if ((flags & flag.getBit()) != 0) {
                inputFlags.add(flag);
            }
        }
        return inputFlags;
    }

    static void sendBoatPaddleAnimate(final UserConnection user, final boolean leftPaddle, final boolean rightPaddle) {
        final EntityTracker entityTracker = user.get(EntityTracker.class);
        final ClientPlayerEntity clientPlayer = entityTracker != null ? entityTracker.getClientPlayer() : null;
        if (clientPlayer == null) {
            return;
        }
        if (leftPaddle) {
            sendRowAnimate(user, clientPlayer.runtimeId(), EntityPacketLayout.ROW_LEFT_ACTION);
        }
        if (rightPaddle) {
            sendRowAnimate(user, clientPlayer.runtimeId(), EntityPacketLayout.ROW_RIGHT_ACTION);
        }
    }

    private static void sendRowAnimate(final UserConnection user, final long runtimeId, final int action) {
        final PacketWrapper animate = PacketWrapper.create(ServerboundBedrockPackets.ANIMATE, user);
        EntityPacketLayout.writeAnimateAction(animate, action);
        animate.write(BedrockTypes.UNSIGNED_VAR_LONG, runtimeId);
        animate.write(BedrockTypes.FLOAT_LE, 0F);
        // MOT 860 onPaddle() only copies a non-zero rowingTime into ROW_TIME_LEFT/RIGHT.
        EntityPacketLayout.writeRowingTime(animate, action, 1F);
        EntityPacketLayout.writeAnimateTrailer(animate, null);
        animate.sendToServer(BedrockProtocol.class);
    }

    private static void sendInteract(final UserConnection user, final long targetRuntimeId, final InteractPacket_Action action) {
        final PacketWrapper interact = PacketWrapper.create(ServerboundBedrockPackets.INTERACT, user);
        interact.write(Types.UNSIGNED_BYTE, (short) action.getValue()); // action
        interact.write(BedrockTypes.UNSIGNED_VAR_LONG, targetRuntimeId); // target entity runtime id
        InteractPacketLayout.writePosition(interact, action, Position3f.ZERO);
        interact.sendToServer(BedrockProtocol.class);
    }

}
