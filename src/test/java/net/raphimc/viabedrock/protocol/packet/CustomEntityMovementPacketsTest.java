package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.debug.DebugHandlerImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_7to1_21_9.packet.ClientboundConfigurationPackets1_21_9;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.entity.CustomEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.ClientChannelDiscoveryStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CustomEntityMovementPacketsTest {
    @BeforeAll
    static void initializeTypes() {
        assertNotNull(StructuredDataKey.CUSTOM_DATA);
    }

    @Test
    void absoluteAndDeltaTeleportsPreserveTheWholePositionAndRotation() throws Exception {
        for (boolean delta : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(true, true)) {
                final PacketWrapper packet = fixture.move(delta, true);
                assertEquals(ClientboundPackets26_1.CUSTOM_PAYLOAD, packet.getPacketType());
                final ByteBuf wire = fixture.serialize(packet);
                assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(wire));
                assertEquals(13, wire.readInt());
                assertEquals(1, wire.readUnsignedByte());
                assertEquals(fixture.entity.javaUuid(), Types.UUID.read(wire));
                assertEquals(10, wire.readDouble());
                assertEquals(64, wire.readDouble());
                assertEquals(-2, wire.readDouble());
                assertEquals(90, wire.readFloat());
                assertEquals(0, wire.readFloat());
                assertTrue(wire.readBoolean());
                assertFalse(wire.isReadable());
                assertEquals(new Position3f(10, 64, -2), fixture.entity.position());
            }
        }
    }

    @Test
    void normalMovementOldClientsAndVanillaEntitiesKeepNativePositionPackets() throws Exception {
        for (boolean delta : new boolean[]{false, true}) {
            for (boolean capable : new boolean[]{false, true}) {
                for (boolean custom : new boolean[]{false, true}) {
                    try (Fixture fixture = new Fixture(capable, custom)) {
                        final PacketWrapper packet = fixture.move(delta, false);
                        assertEquals(ClientboundPackets26_1.ENTITY_POSITION_SYNC, packet.getPacketType());
                        if (!capable || !custom) {
                            assertEquals(ClientboundPackets26_1.ENTITY_POSITION_SYNC,
                                    fixture.move(delta, true).getPacketType());
                        }
                    }
                }
            }
        }
    }

    @Test
    void handshakeRequiresBothCapabilitiesAndWorksWhenTheyArriveSeparately() throws Exception {
        try (Fixture fixture = new Fixture(false, true)) {
            ViaBedrockUtilityInterface.confirmCustomEntityMotion(fixture.user);
            assertTrue(fixture.sent.isEmpty());
            fixture.user.get(ChannelStorage.class).addChannels(
                    List.of(ViaBedrockUtilityInterface.CUSTOM_ENTITY_MOTION_V1_CAPABILITY));
            fixture.user.getProtocolInfo().setClientState(State.CONFIGURATION);
            fixture.user.getProtocolInfo().setServerState(State.CONFIGURATION);
            ViaBedrockUtilityInterface.confirmCustomEntityMotion(fixture.user);
            assertEquals(ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, fixture.sent.getLast().getPacketType());
            final ByteBuf wire = fixture.serialize(fixture.sent.getLast());
            assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(wire));
            assertEquals(13, wire.readInt());
            assertEquals(0, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        }
    }

    @Test
    void missingChannelStorageCannotReplaceTheNativePacket() throws Exception {
        try (Fixture fixture = new Fixture(true, true)) {
            fixture.user.remove(ChannelStorage.class);
            assertEquals(ClientboundPackets26_1.ENTITY_POSITION_SYNC, fixture.move(false, true).getPacketType());
        }
    }

    @Test
    void registrationRepliesFollowTheOutboundPhaseWhileAcknowledgementsArePending() throws Exception {
        for (State clientState : new State[]{State.CONFIGURATION, State.PLAY}) {
            for (State serverState : new State[]{State.CONFIGURATION, State.PLAY}) {
                try (Fixture fixture = new Fixture(true, true)) {
                    fixture.user.getProtocolInfo().setClientState(clientState);
                    fixture.user.getProtocolInfo().setServerState(serverState);
                    fixture.registerChannels(MultiStatePackets.CUSTOM_PAYLOAD_HANDLER);
                    assertEquals(2, fixture.sent.size());
                    for (PacketWrapper reply : fixture.sent) {
                        assertEquals(serverState == State.CONFIGURATION
                                ? ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD
                                : ClientboundPackets26_1.CUSTOM_PAYLOAD, reply.getPacketType());
                    }
                }
            }
        }
    }

    @Test
    void motionConfirmationUsesPlayAfterFinishConfigurationBeforeItsAcknowledgement() throws Exception {
        try (Fixture fixture = new Fixture(true, true)) {
            fixture.user.getProtocolInfo().setClientState(State.CONFIGURATION);
            fixture.user.getProtocolInfo().setServerState(State.PLAY);
            ViaBedrockUtilityInterface.confirmCustomEntityMotion(fixture.user);
            final PacketWrapper reply = fixture.sent.getLast();
            assertEquals(ClientboundPackets26_1.CUSTOM_PAYLOAD, reply.getPacketType());
            final ByteBuf wire = Unpooled.buffer();
            fixture.buffers.add(wire);
            reply.writeToBuffer(wire);
            assertEquals(29, wire.readableBytes());
            assertEquals(ClientboundPackets26_1.CUSTOM_PAYLOAD.getId(), Types.VAR_INT.readPrimitive(wire));
            assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(wire));
            assertEquals(13, wire.readInt());
            assertEquals(0, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        }
    }

    @Test
    void skullMotionNeedsItsOwnCapabilityAndUsesTheOutboundProtocolPhase() throws Exception {
        for (State phase : new State[]{State.CONFIGURATION, State.PLAY}) {
            try (Fixture fixture = new Fixture(true, false)) {
                ViaBedrockUtilityInterface.confirmWitherSkullMotion(fixture.user);
                assertTrue(fixture.sent.isEmpty());
                fixture.user.get(ChannelStorage.class).addChannels(
                        List.of(ViaBedrockUtilityInterface.WITHER_SKULL_MOTION_V1_CAPABILITY));
                fixture.user.getProtocolInfo().setServerState(phase);
                ViaBedrockUtilityInterface.confirmWitherSkullMotion(fixture.user);
                final PacketWrapper reply = fixture.sent.getLast();
                assertEquals(phase == State.PLAY ? ClientboundPackets26_1.CUSTOM_PAYLOAD
                        : ClientboundConfigurationPackets1_21_9.CUSTOM_PAYLOAD, reply.getPacketType());
                final ByteBuf wire = fixture.serialize(reply);
                assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(wire));
                assertEquals(13, wire.readInt());
                assertEquals(2, wire.readUnsignedByte());
                assertFalse(wire.isReadable());
                final ChannelStorage skullOnly = new ChannelStorage();
                skullOnly.addChannels(List.of(ViaBedrockUtilityInterface.WITHER_SKULL_MOTION_V1_CAPABILITY));
                fixture.user.put(skullOnly);
                final int sent = fixture.sent.size();
                ViaBedrockUtilityInterface.confirmWitherSkullMotion(fixture.user);
                assertEquals(sent, fixture.sent.size());
            }
        }
    }

    @Test
    void clientRegistrationConfirmsSkullMotionAlongsideExistingCustomEntitySupport() throws Exception {
        try (Fixture fixture = new Fixture(false, false)) {
            fixture.user.getProtocolInfo().setServerState(State.PLAY);
            fixture.registerChannels(MultiStatePackets.CUSTOM_PAYLOAD_HANDLER,
                    ViaBedrockUtilityInterface.WITHER_SKULL_MOTION_V1_CAPABILITY);
            assertEquals(3, fixture.sent.size());
            final ByteBuf wire = fixture.serialize(fixture.sent.getLast());
            assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(wire));
            assertEquals(13, wire.readInt());
            assertEquals(2, wire.readUnsignedByte());
            assertFalse(wire.isReadable());
        }
    }

    @Test
    void nativeSkullTeleportsAreExplicitButNormalMovementAndCustomTypeOverridesStayNative() throws Exception {
        for (boolean delta : new boolean[]{false, true}) {
            for (Integer customId : new Integer[]{null, 999}) {
                try (Fixture fixture = new Fixture(false, false, EntityTypes1_21_11.WITHER_SKULL, customId)) {
                    assertEquals(ClientboundPackets26_1.ENTITY_POSITION_SYNC, fixture.move(delta, true).getPacketType());
                    fixture.user.get(ChannelStorage.class).addChannels(
                            List.of(ViaBedrockUtilityInterface.WITHER_SKULL_MOTION_V1_CAPABILITY));
                    assertEquals(ClientboundPackets26_1.ENTITY_POSITION_SYNC, fixture.move(delta, false).getPacketType());
                    final PacketWrapper teleport = fixture.move(delta, true);
                    if (customId != null) {
                        assertEquals(ClientboundPackets26_1.ENTITY_POSITION_SYNC, teleport.getPacketType());
                        continue;
                    }
                    final ByteBuf wire = fixture.serialize(teleport);
                    assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(wire));
                    assertEquals(13, wire.readInt());
                    assertEquals(3, wire.readUnsignedByte());
                    assertEquals(fixture.entity.javaUuid(), Types.UUID.read(wire));
                    assertEquals(10, wire.readDouble());
                    assertEquals(64, wire.readDouble());
                    assertEquals(-2, wire.readDouble());
                    assertEquals(90, wire.readFloat());
                    assertEquals(0, wire.readFloat());
                    assertTrue(wire.readBoolean());
                    assertFalse(wire.isReadable());
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final UserConnectionImpl user = new UserConnectionImpl(channel);
        private final List<ByteBuf> buffers = new ArrayList<>();
        private final List<PacketWrapper> sent = new ArrayList<>();
        private final Field managerField = Via.class.getDeclaredField("manager");
        private final Field configField = ViaBedrock.class.getDeclaredField("config");
        private final Object oldManager;
        private final Object oldConfig;
        private final Entity entity;
        private PacketHandler absolute;
        private PacketHandler delta;

        private Fixture(boolean capable, boolean custom) throws Exception {
            this(capable, custom, EntityTypes1_21_11.ZOMBIE, null);
        }

        private Fixture(boolean capable, boolean custom, EntityTypes1_21_11 type, Integer customTypeId) throws Exception {
            managerField.setAccessible(true);
            configField.setAccessible(true);
            oldManager = managerField.get(null);
            oldConfig = configField.get(null);
            configField.set(null, Proxy.newProxyInstance(ViaBedrockConfig.class.getClassLoader(),
                    new Class[]{ViaBedrockConfig.class}, (proxy, method, args) -> {
                        if (method.getName().equals("shouldBridgeJavaCustomPayloads")) return false;
                        throw new UnsupportedOperationException(method.getName());
                    }));
            final ProtocolManager protocolManager = (ProtocolManager) Proxy.newProxyInstance(
                    ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("createPacketWrapper")) {
                            return new PacketWrapperImpl((PacketType) args[0], null, (UserConnection) args[2]) {
                                @Override
                                public void send(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) {
                                    sent.add(this);
                                }
                            };
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            final ViaManager manager = (ViaManager) Proxy.newProxyInstance(
                    ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getProtocolManager")) return protocolManager;
                        if (method.getName().equals("debugHandler")) return new DebugHandlerImpl();
                        throw new UnsupportedOperationException(method.getName());
                    });
            managerField.set(null, manager);
            final ChannelStorage channels = new ChannelStorage();
            channels.addChannels(List.of(ViaBedrockUtilityInterface.CONFIRM_CHANNEL));
            if (capable) channels.addChannels(List.of(ViaBedrockUtilityInterface.CUSTOM_ENTITY_MOTION_V1_CAPABILITY));
            user.put(channels);
            user.put(new ClientChannelDiscoveryStorage());
            entity = custom ? new CustomEntity(user, 1, 2, "easecation:ghosarea_hunter_2", 300, null) {
                @Override
                public void setPosition(Position3f position) {
                    this.position = position;
                }
            }
                    : new Entity(user, 1, 2, type.identifier(), 300, UUID.randomUUID(), type, customTypeId);
            entity.setPosition(Position3f.ZERO);
            user.getStoredObjects().put(EntityTracker.class, new EntityTracker(user) {
                @Override
                public Entity getEntityByRid(long runtimeId) {
                    return runtimeId == 2 ? entity : null;
                }
            });
            EntityPackets.register(new BedrockProtocol() {
                @Override
                public void registerClientbound(ClientboundBedrockPackets type, ClientboundPackets26_1 mapped,
                                                PacketHandler handler, boolean override) {
                    if (type == ClientboundBedrockPackets.MOVE_ENTITY_ABSOLUTE) absolute = handler;
                    if (type == ClientboundBedrockPackets.MOVE_ENTITY_DELTA) delta = handler;
                }
            });
        }

        private PacketWrapper move(boolean useDelta, boolean teleport) throws Exception {
            final ByteBuf input = Unpooled.buffer();
            buffers.add(input);
            BedrockTypes.UNSIGNED_VAR_LONG.writePrimitive(input, 2);
            if (useDelta) input.writeShortLE(127 | (teleport ? 128 : 0));
            else input.writeByte(1 | (teleport ? 2 : 0));
            BedrockTypes.POSITION_3F.write(input, new Position3f(10, 64, -2));
            input.writeByte(0).writeByte(64).writeByte(64);
            final PacketWrapper packet = new PacketWrapperImpl(ClientboundPackets26_1.ENTITY_POSITION_SYNC, input, user);
            (useDelta ? delta : absolute).handle(packet);
            assertFalse(input.isReadable());
            return packet;
        }

        private ByteBuf serialize(PacketWrapper packet) throws Exception {
            packet.setId(-1);
            final ByteBuf buffer = Unpooled.buffer();
            buffers.add(buffer);
            packet.writeToBuffer(buffer);
            return buffer;
        }

        private void registerChannels(PacketHandler handler, String... additionalChannels) throws Exception {
            final ByteBuf input = Unpooled.buffer();
            buffers.add(input);
            Types.STRING.write(input, "minecraft:register");
            input.writeBytes((ViaBedrockUtilityInterface.CONFIRM_CHANNEL + "\0"
                    + ViaBedrockUtilityInterface.CUSTOM_ENTITY_MOTION_V1_CAPABILITY
                    + (additionalChannels.length == 0 ? "" : "\0" + String.join("\0", additionalChannels)))
                    .getBytes(StandardCharsets.UTF_8));
            handler.handle(new PacketWrapperImpl(-1, input, user));
            assertFalse(input.isReadable());
        }

        @Override
        public void close() throws Exception {
            managerField.set(null, oldManager);
            configField.set(null, oldConfig);
            buffers.forEach(ByteBuf::release);
            channel.finishAndReleaseAll();
        }
    }
}
