package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.experimental.blockbreak.BlockBreakingProgressModule;
import net.raphimc.viabedrock.experimental.storage.BlockBreakingProgressTracker;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.Direction;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorSwingSource;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AnimatePacketPayload_Action;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.InteractionHand;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementSwingPacketsTest {

    @Test
    void placementUseAndInteractionAnimateWithoutReportingAMissedAttack() throws Exception {
        for (boolean experimental : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(experimental)) {
                for (ActorSwingSource source : new ActorSwingSource[]{ActorSwingSource.Build, ActorSwingSource.Interact, ActorSwingSource.UseItem}) {
                    fixture.client.swingSourceTracker().recordUse(InteractionHand.MAIN_HAND, source);
                    fixture.swing(InteractionHand.MAIN_HAND);
                    fixture.assertLastAnimation(source);
                    assertFalse(fixture.missedSwing());
                    assertTrue(fixture.client.authInputBlockActions().isEmpty());
                }
                assertEquals(3, fixture.sent.size());
            }
        }
    }

    @Test
    void aRealAirAttackAfterPlacementStillReportsMissedSwing() throws Exception {
        for (boolean experimental : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(experimental)) {
                fixture.client.swingSourceTracker().recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Build);
                fixture.swing(InteractionHand.MAIN_HAND);
                assertFalse(fixture.missedSwing());
                fixture.swing(InteractionHand.MAIN_HAND);
                fixture.assertLastAnimation(ActorSwingSource.Attack);
                assertTrue(fixture.missedSwing());
            }
        }
    }

    @Test
    void unconsumedUseExpiresAtClientTickEndEvenBeforeSpawn() throws Exception {
        for (boolean experimental : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(experimental)) {
                fixture.client.swingSourceTracker().recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Interact);
                fixture.tickEnd();
                fixture.swing(InteractionHand.MAIN_HAND);
                fixture.assertLastAnimation(ActorSwingSource.Attack);
                assertTrue(fixture.missedSwing());
            }
        }
    }

    @Test
    void offhandPlacementDoesNotSuppressTheNextMainHandAirAttack() throws Exception {
        for (boolean experimental : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(experimental)) {
                fixture.client.swingSourceTracker().recordUse(InteractionHand.OFF_HAND, ActorSwingSource.Build);
                fixture.swing(InteractionHand.OFF_HAND);
                assertTrue(fixture.sent.isEmpty());
                fixture.swing(InteractionHand.MAIN_HAND);
                fixture.assertLastAnimation(ActorSwingSource.Attack);
                assertTrue(fixture.missedSwing());
            }
        }
    }

    @Test
    void anExplicitAttackClearsUseContextAndStillCancelsItsDuplicateSwing() throws Exception {
        for (boolean experimental : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(experimental)) {
                fixture.client.swingSourceTracker().recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Build);
                fixture.client.sendSwingPacketToServer();
                fixture.client.cancelNextSwingPacket();
                fixture.swing(InteractionHand.MAIN_HAND);
                assertEquals(1, fixture.sent.size());
                fixture.assertLastAnimation(ActorSwingSource.Attack);
                assertFalse(fixture.missedSwing());
                fixture.swing(InteractionHand.MAIN_HAND);
                assertTrue(fixture.missedSwing());
            }
        }
    }

    @Test
    void canceledUseSwingDoesNotLeakItsSourceIntoAFollowingAttack() throws Exception {
        for (boolean experimental : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(experimental)) {
                fixture.client.cancelNextSwingPacket();
                fixture.client.swingSourceTracker().recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Build);
                fixture.swing(InteractionHand.MAIN_HAND);
                assertTrue(fixture.sent.isEmpty());
                fixture.swing(InteractionHand.MAIN_HAND);
                fixture.assertLastAnimation(ActorSwingSource.Attack);
                assertTrue(fixture.missedSwing());
            }
        }
    }

    @Test
    void resetForANewWorldClearsUseContext() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            fixture.client.swingSourceTracker().recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Build);
            fixture.client.resetInputLocks();
            fixture.swing(InteractionHand.MAIN_HAND);
            assertTrue(fixture.missedSwing());
        }
    }

    @Test
    void placementDoesNotResumeSuspendedMiningButTheNextAttackDoes() throws Exception {
        try (Fixture fixture = new Fixture(true)) {
            final var position = new com.viaversion.viaversion.api.minecraft.BlockPosition(1, 64, 1);
            fixture.mining.startMining(position, Direction.UP);
            fixture.mining.suspendMining(position);
            fixture.client.swingSourceTracker().recordUse(InteractionHand.MAIN_HAND, ActorSwingSource.Build);
            fixture.swing(InteractionHand.MAIN_HAND);
            assertEquals(BlockBreakingProgressTracker.MiningPhase.SUSPENDED, fixture.mining.miningPhase());
            assertTrue(fixture.client.authInputBlockActions().isEmpty());
            assertFalse(fixture.missedSwing());
            fixture.swing(InteractionHand.MAIN_HAND);
            assertEquals(BlockBreakingProgressTracker.MiningPhase.ACTIVE, fixture.mining.miningPhase());
            assertEquals(1, fixture.client.authInputBlockActions().size());
            assertFalse(fixture.missedSwing());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
        private final TestProtocol protocol = new TestProtocol();
        private final List<PacketWrapper> sent = new ArrayList<>();
        private final Field managerField = Via.class.getDeclaredField("manager");
        private final Field configField = ViaBedrock.class.getDeclaredField("config");
        private final Field entitiesField = net.raphimc.viabedrock.protocol.data.BedrockMappingData.class.getDeclaredField("bedrockEntities");
        private final Object previousManager;
        private final Object previousConfig;
        private final Object previousEntities;
        private final ClientPlayerEntity client;
        private final BlockBreakingProgressTracker mining;

        private Fixture(final boolean experimental) throws Exception {
            this.managerField.setAccessible(true);
            this.configField.setAccessible(true);
            this.previousManager = this.managerField.get(null);
            this.previousConfig = this.configField.get(null);
            // 仅替换外部配置与网络发送；两条 SWING 处理器及 ANIMATE 编码使用生产实现。
            final ProtocolManager packetFactory = (ProtocolManager) Proxy.newProxyInstance(
                    ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("createPacketWrapper")) {
                            return new PacketWrapperImpl((PacketType) args[0], null, this.user) {
                                @Override
                                public void sendToServer(final Class<? extends Protocol> protocol, final boolean skipCurrentPipeline) {
                                    sent.add(this);
                                }
                            };
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            this.managerField.set(null, Proxy.newProxyInstance(ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("getProtocolManager")) return packetFactory;
                throw new UnsupportedOperationException(method.getName());
            }));
            this.configField.set(null, Proxy.newProxyInstance(ViaBedrockConfig.class.getClassLoader(), new Class[]{ViaBedrockConfig.class}, (proxy, method, args) -> {
                if (method.getName().equals("getMovementWatchdogMode")) return ViaBedrockConfig.MovementWatchdogMode.OFF;
                throw new UnsupportedOperationException(method.getName());
            }));
            this.entitiesField.setAccessible(true);
            this.previousEntities = this.entitiesField.get(BedrockProtocol.MAPPINGS);
            this.entitiesField.set(BedrockProtocol.MAPPINGS, com.google.common.collect.HashBiMap.create());
            this.user.put(new GameSessionStorage(this.user));
            this.client = new ClientPlayerEntity(this.user, 70L, UUID.randomUUID(), new PlayerAbilities(7L, (byte) 0, (byte) 0));
            this.client.setPosition(new Position3f(0F, 65.62F, 0F));
            this.user.getStoredObjects().put(EntityTracker.class, new EntityTracker(this.user) {
                @Override
                public ClientPlayerEntity getClientPlayer() {
                    return client;
                }
            });
            ClientPlayerPackets.register(this.protocol);
            this.mining = new BlockBreakingProgressTracker(this.user);
            this.user.put(this.mining);
            if (experimental) new BlockBreakingProgressModule().onPacketRegistration(this.protocol);
        }

        private void swing(final InteractionHand hand) throws Exception {
            final PacketWrapper wrapper = new PacketWrapperImpl(ServerboundPackets26_1.SWING, null, this.user);
            wrapper.write(Types.VAR_INT, hand.ordinal());
            wrapper.resetReader();
            this.protocol.handle(ServerboundPackets26_1.SWING, wrapper);
            if (!wrapper.isCancelled()) this.sent.add(wrapper);
        }

        private void tickEnd() throws Exception {
            this.protocol.handle(ServerboundPackets26_1.CLIENT_TICK_END,
                    new PacketWrapperImpl(ServerboundPackets26_1.CLIENT_TICK_END, null, this.user));
        }

        private boolean missedSwing() {
            return this.client.authInputData().contains(PlayerAuthInputPacket_InputData.MissedSwing);
        }

        private void assertLastAnimation(final ActorSwingSource source) throws Exception {
            final PacketWrapper packet = this.sent.getLast();
            assertEquals(ServerboundBedrockPackets.ANIMATE, packet.getPacketType());
            final ByteBuf encoded = Unpooled.buffer();
            try {
                packet.writeToBuffer(encoded);
                assertEquals(ServerboundBedrockPackets.ANIMATE.getId(), Types.VAR_INT.readPrimitive(encoded));
                assertEquals(AnimatePacketPayload_Action.Swing.getValue(), encoded.readUnsignedByte());
                assertEquals(this.client.runtimeId(), BedrockTypes.UNSIGNED_VAR_LONG.readPrimitive(encoded));
                assertEquals(0F, BedrockTypes.FLOAT_LE.read(encoded));
                assertEquals(source.name().toLowerCase(Locale.ROOT), BedrockTypes.OPTIONAL_STRING.read(encoded));
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
            }
        }

        @Override
        public void close() throws Exception {
            this.managerField.set(null, this.previousManager);
            this.configField.set(null, this.previousConfig);
            this.entitiesField.set(BedrockProtocol.MAPPINGS, this.previousEntities);
            this.channel.finishAndReleaseAll();
        }
    }

    private static final class TestProtocol extends BedrockProtocol {
        private void handle(final ServerboundPackets26_1 type, final PacketWrapper wrapper) throws Exception {
            final var mapping = serverboundMappings.mappedPacket(type.state(), type.getId());
            mapping.applyType(wrapper);
            mapping.handler().handle(wrapper);
        }
    }
}
