package net.raphimc.viabedrock.protocol.packet;

import com.google.common.collect.HashBiMap;
import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.debug.DebugHandlerImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.platform.ViaBedrockConfig;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.AbilitiesIndex;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorFlags;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;
import net.raphimc.viabedrock.protocol.data.enums.java.InputFlag;
import net.raphimc.viabedrock.protocol.data.enums.java.Relative;
import net.raphimc.viabedrock.protocol.data.generated.java.RegistryKeys;
import net.raphimc.viabedrock.protocol.model.PlayerAbilities;
import net.raphimc.viabedrock.protocol.model.Position2f;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.ClientSettingsStorage;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.storage.GameSessionStorage;
import net.raphimc.viabedrock.protocol.storage.JavaPlayerStateStorage;
import net.raphimc.viabedrock.protocol.storage.PacketSyncStorage;
import net.raphimc.viabedrock.protocol.storage.SpectatorCameraTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInputLocksPacketsTest {

    @Test
    void noAiAndInputPermissionsRemainIndependentInTheAtomicV2Snapshot() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V2_CAPABILITY))) {
            fixture.client.noAi(true);
            assertInputLocksV2Wire(fixture.packets.getLast(), 0, true);
            fixture.input((byte) 17);
            assertTrue(fixture.client.inputFlags().isEmpty());
            fixture.lock(6);
            assertInputLocksV2Wire(fixture.packets.getLast(), 6, true);
            fixture.client.noAi(false);
            assertInputLocksV2Wire(fixture.packets.getLast(), 6, false);
            assertTrue(fixture.client.isMovementInputLocked());
            fixture.lock(2);
            assertInputLocksV2Wire(fixture.packets.getLast(), 2, false);
            assertFalse(fixture.client.isMovementInputLocked());
            fixture.client.noAi(true);
            fixture.lock(0);
            assertInputLocksV2Wire(fixture.packets.getLast(), 0, true);
            assertTrue(fixture.client.isMovementInputLocked());
            fixture.client.setPositionFromServer(new Position3f(12F, 70F, 8F));
            assertEquals(new Position3f(12F, 70F, 8F), fixture.client.position());
            fixture.client.resetInputLocks();
            assertInputLocksV2Wire(fixture.packets.getLast(), 0, false);
            assertFalse(fixture.client.hasEntityFlag(ActorFlags.NOAI));
            assertFalse(fixture.client.isMovementInputLocked());
        }
    }

    @Test
    void noAiFallsBackToV1MovementWithoutLockingTheCamera() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V1_CAPABILITY))) {
            fixture.client.noAi(true);
            assertInputLocksWire(fixture.packets.getLast(), 4);
            fixture.client.noAi(true);
            assertEquals(1, fixture.packets.size());
            fixture.client.noAi(false);
            assertInputLocksWire(fixture.packets.getLast(), 0);
        }
    }

    @Test
    void capabilityArrivingAfterTheLockReceivesTheCurrentSnapshot() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.CONFIRM_CHANNEL))) {
            fixture.client.noAi(true);
            fixture.lock(2);
            assertTrue(fixture.packets.isEmpty());
            fixture.user.get(ChannelStorage.class).addChannels(List.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V2_CAPABILITY));
            fixture.client.resendInputLocks();
            assertInputLocksV2Wire(fixture.packets.getLast(), 2, true);
        }
    }

    private static void assertInputLocksV2Wire(final PacketWrapper packet, final int flags, final boolean immobile) throws Exception {
        final ByteBuf encoded = Unpooled.buffer();
        try {
            packet.setId(-1);
            packet.writeToBuffer(encoded);
            assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(encoded));
            assertEquals(14, encoded.readInt());
            assertEquals(flags, encoded.readInt());
            assertEquals(immobile ? 1 : 0, encoded.readUnsignedByte());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    @Test
    void oldVbuConfirmDoesNotReceiveUnsupportedInputLocksPayloads() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.CONFIRM_CHANNEL))) {
            fixture.lock(4);
            fixture.input((byte) 17);
            assertTrue(fixture.client.isMovementInputLocked());
            assertTrue(fixture.client.inputFlags().isEmpty());
            fixture.lock(0);
            fixture.input((byte) 17);
            assertFalse(fixture.client.isMovementInputLocked());
            assertTrue(fixture.client.inputFlags().contains(InputFlag.FORWARD));
            assertTrue(fixture.packets.isEmpty());
        }
    }

    @Test
    void absentCapabilityStorageDoesNotReceiveInputLocksPayloads() throws Exception {
        try (Fixture fixture = new Fixture(Set.of())) {
            fixture.user.remove(ChannelStorage.class);
            fixture.lock(4);
            fixture.lock(0);
            assertTrue(fixture.packets.isEmpty());
        }
    }

    @Test
    void capabilityAloneAllowsStableOrdinalTwelveAndBothLockAndUnlock() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V1_CAPABILITY))) {
            fixture.lock(6);
            assertInputLocksWire(fixture.packets.getLast(), 6);
            fixture.lock(6);
            assertEquals(1, fixture.packets.size());
            fixture.lock(0);
            assertInputLocksWire(fixture.packets.getLast(), 0);
            assertEquals(2, fixture.packets.size());
        }
    }

    @Test
    void unknownBedrockLockBitsDoNotEnterTheV1PayloadOrBecomeAMovementLock() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V1_CAPABILITY))) {
            fixture.lock(4 | (1 << 4) | (1 << 12));
            assertInputLocksWire(fixture.packets.getLast(), 4);
            fixture.lock((1 << 4) | (1 << 12));
            assertInputLocksWire(fixture.packets.getLast(), 0);
            fixture.input((byte) 17);
            assertFalse(fixture.client.isMovementInputLocked());
            assertTrue(fixture.client.inputFlags().contains(InputFlag.FORWARD));
        }
    }

    @Test
    void cameraLockPreservesMovementAndUnlockRestoresMovementInput() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V1_CAPABILITY))) {
            fixture.lock(2);
            fixture.input((byte) 17);
            assertInputLocksWire(fixture.packets.getLast(), 2);
            assertTrue(fixture.client.inputFlags().contains(InputFlag.FORWARD));
            assertTrue(fixture.client.inputFlags().contains(InputFlag.JUMP));
            fixture.lock(4);
            fixture.input((byte) 17);
            assertTrue(fixture.client.inputFlags().isEmpty());
            fixture.lock(0);
            fixture.input((byte) 17);
            assertInputLocksWire(fixture.packets.getLast(), 0);
            assertTrue(fixture.client.inputFlags().contains(InputFlag.FORWARD));
            assertTrue(fixture.client.inputFlags().contains(InputFlag.JUMP));
        }
    }

    @Test
    void dimensionChangeClearsTheRetainedPlayerLockBeforeAcceptingTheSameLockAgain() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V1_CAPABILITY))) {
            fixture.lock(4);
            assertInputLocksWire(fixture.packets.getLast(), 4);

            fixture.prepareDimensionChange();

            assertFalse(fixture.client.isMovementInputLocked());
            fixture.input((byte) 17);
            assertTrue(fixture.client.inputFlags().contains(InputFlag.FORWARD));
            assertTrue(fixture.client.inputFlags().contains(InputFlag.JUMP));
            fixture.lock(4);
            assertTrue(fixture.client.isMovementInputLocked());
            assertTrue(fixture.client.inputFlags().isEmpty());
            final List<PacketWrapper> locks = fixture.packets.stream()
                    .filter(packet -> packet.getPacketType() == ClientboundPackets26_1.CUSTOM_PAYLOAD).toList();
            assertEquals(3, locks.size());
            assertInputLocksWire(locks.get(0), 4);
            assertInputLocksWire(locks.get(1), 0);
            assertInputLocksWire(locks.get(2), 4);
        }
    }

    @Test
    void movementLockKeepsServerDisplacementViewRotationAndNonMovementActions() throws Exception {
        try (Fixture fixture = new Fixture(Set.of(ViaBedrockUtilityInterface.INPUT_LOCKS_V1_CAPABILITY))) {
            fixture.lock(4);
            fixture.client.setPositionFromServer(new Position3f(12.5F, 66.62F, -4.5F));
            final PacketWrapper correction = fixture.packet(ClientboundPackets26_1.PLAYER_POSITION);
            fixture.client.writePlayerPositionPacketToClient(correction, Relative.NONE, false);
            fixture.client.confirmTeleport(correction.get(Types.VAR_INT, 0));
            fixture.client.abilities().getOrCreateCacheLayer().setAbility(AbilitiesIndex.Flying, true);
            final PacketWrapper moved = fixture.packet(ServerboundPackets26_1.MOVE_PLAYER_POS_ROT);
            moved.write(Types.DOUBLE, 14.5D);
            moved.write(Types.DOUBLE, 66D);
            moved.write(Types.DOUBLE, -4.5D);
            moved.write(Types.FLOAT, 75F);
            moved.write(Types.FLOAT, 15F);
            moved.write(Types.UNSIGNED_BYTE, (short) 0);
            moved.resetReader();
            fixture.protocol.handle(ServerboundPackets26_1.MOVE_PLAYER_POS_ROT, moved);
            fixture.input((byte) 17);
            fixture.client.addAuthInputData(PlayerAuthInputPacket_InputData.Up,
                    PlayerAuthInputPacket_InputData.JumpDown, PlayerAuthInputPacket_InputData.MissedSwing);

            final PacketWrapper auth = fixture.packet(ServerboundPackets26_1.CLIENT_TICK_END);
            fixture.protocol.handle(ServerboundPackets26_1.CLIENT_TICK_END, auth);
            assertFalse(auth.isCancelled());
            assertEquals(new Position3f(14.5F, 67.62F, -4.5F), auth.get(BedrockTypes.POSITION_3F, 0));
            assertEquals(new Position3f(2F, 1F, 0F), auth.get(BedrockTypes.POSITION_3F, 1));
            assertEquals(75F, auth.get(BedrockTypes.FLOAT_LE, 1));
            assertEquals(15F, auth.get(BedrockTypes.FLOAT_LE, 0));
            assertEquals(new Position2f(0F, 0F), auth.get(BedrockTypes.POSITION_2F, 0));
            assertEquals(new Position2f(0F, 0F), auth.get(BedrockTypes.POSITION_2F, 2));
            final BigInteger flags = auth.get(BedrockTypes.UNSIGNED_VAR_BIG_INTEGER, 0);
            assertFalse(flags.testBit(PlayerAuthInputPacket_InputData.Up.getValue()));
            assertFalse(flags.testBit(PlayerAuthInputPacket_InputData.JumpDown.getValue()));
            assertTrue(flags.testBit(PlayerAuthInputPacket_InputData.MissedSwing.getValue()));
            assertFalse(fixture.client.hasEntityFlag(ActorFlags.NOAI));
        }
    }

    private static void assertInputLocksWire(final PacketWrapper packet, final int flags) throws Exception {
        assertEquals(ClientboundPackets26_1.CUSTOM_PAYLOAD, packet.getPacketType());
        final ByteBuf encoded = Unpooled.buffer();
        try {
            packet.writeToBuffer(encoded);
            assertEquals(ClientboundPackets26_1.CUSTOM_PAYLOAD.getId(), Types.VAR_INT.readPrimitive(encoded));
            assertEquals(ViaBedrockUtilityInterface.CHANNEL, Types.STRING.read(encoded));
            assertEquals(12, encoded.readInt());
            assertEquals(flags, encoded.readInt());
            assertFalse(encoded.isReadable());
        } finally {
            encoded.release();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
        private final List<PacketWrapper> packets = new ArrayList<>();
        private final Field managerField = Via.class.getDeclaredField("manager");
        private final Field entitiesField = BedrockMappingData.class.getDeclaredField("bedrockEntities");
        private final Field configField = ViaBedrock.class.getDeclaredField("config");
        private final Object previousManager;
        private final Object previousEntities;
        private final Object previousConfig;
        private final TestProtocol protocol = new TestProtocol();
        private final TestClientPlayer client;

        private Fixture(final Set<String> capabilities) throws Exception {
            this.managerField.setAccessible(true);
            this.entitiesField.setAccessible(true);
            this.configField.setAccessible(true);
            this.previousManager = this.managerField.get(null);
            this.previousEntities = this.entitiesField.get(BedrockProtocol.MAPPINGS);
            this.previousConfig = this.configField.get(null);
            // 仅替换传输和外部配置，锁消息、玩家输入及最终AuthInput使用实际处理器。
            final ProtocolManager protocolManager = (ProtocolManager) Proxy.newProxyInstance(
                    ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("createPacketWrapper")) return packet((PacketType) args[0]);
                        throw new UnsupportedOperationException(method.getName());
                    });
            this.managerField.set(null, Proxy.newProxyInstance(ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("getProtocolManager")) return protocolManager;
                if (method.getName().equals("debugHandler")) return new DebugHandlerImpl();
                throw new UnsupportedOperationException(method.getName());
            }));
            this.entitiesField.set(BedrockProtocol.MAPPINGS, HashBiMap.create());
            this.configField.set(null, Proxy.newProxyInstance(ViaBedrockConfig.class.getClassLoader(), new Class[]{ViaBedrockConfig.class}, (proxy, method, args) -> {
                if (method.getName().equals("getMovementWatchdogMode")) return ViaBedrockConfig.MovementWatchdogMode.OFF;
                throw new UnsupportedOperationException(method.getName());
            }));
            this.user.put(new GameSessionStorage(this.user));
            this.client = new TestClientPlayer(this.user);
            this.client.setPosition(new Position3f(0F, 66.62F, 0F));
            this.client.setRotation(Position3f.ZERO);
            this.client.setInitiallySpawned();
            final EntityTracker entities = new EntityTracker(this.user);
            this.user.put(entities);
            entities.addEntity(this.client, false);
            this.user.put(new SpectatorCameraTracker(this.user));
            this.user.put(new JavaPlayerStateStorage());
            this.user.put(new PacketSyncStorage(this.user));
            final ChannelStorage channels = new ChannelStorage();
            channels.addChannels(capabilities);
            this.user.put(channels);
            this.user.getProtocolInfo().setClientState(State.PLAY);
            ClientPlayerPackets.register(this.protocol);
        }

        private PacketWrapper packet(final PacketType type) {
            return new PacketWrapperImpl(type, null, this.user) {
                @Override
                public void send(final Class<? extends Protocol> protocol, final boolean skipCurrentPipeline) {
                    packets.add(this);
                }
            };
        }

        private void lock(final int flags) throws Exception {
            final ByteBuf input = Unpooled.buffer();
            try {
                BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(input, flags);
                final PacketWrapper packet = new PacketWrapperImpl(ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS, input, this.user);
                this.protocol.handle(ClientboundBedrockPackets.UPDATE_CLIENT_INPUT_LOCKS, packet);
                assertTrue(packet.isCancelled());
                assertFalse(input.isReadable());
            } finally {
                input.release();
            }
        }

        private void input(final byte flags) throws Exception {
            final PacketWrapper packet = packet(ServerboundPackets26_1.PLAYER_INPUT);
            packet.write(Types.BYTE, flags);
            packet.resetReader();
            this.protocol.handle(ServerboundPackets26_1.PLAYER_INPUT, packet);
        }

        private void prepareDimensionChange() throws Exception {
            final CompoundTag dimension = new CompoundTag();
            dimension.putInt("min_y", 0);
            dimension.putInt("height", 128);
            final CompoundTag dimensions = new CompoundTag();
            dimensions.put("minecraft:the_nether", dimension);
            final CompoundTag biomes = new CompoundTag();
            biomes.put("minecraft:plains", new CompoundTag());
            final CompoundTag registries = new CompoundTag();
            registries.put(RegistryKeys.DIMENSION_TYPE, dimensions);
            registries.put(RegistryKeys.WORLDGEN_BIOME, biomes);
            this.user.getStoredObjects().put(GameSessionStorage.class, new GameSessionStorage(this.user) {
                @Override
                public CompoundTag getJavaRegistries() {
                    return registries;
                }
            });
            this.user.put(new ClientSettingsStorage("en_us", 8, 0, true, (short) 0, 1, false, true, 0));
            final EntityTracker entities = new EntityTracker(this.user) {
                @Override
                public void prepareForRespawn() {
                    super.prepareForRespawn();
                    // 执行真实维度处理到实体清理边界；后续库存与世界初始化不属于输入锁回归范围。
                    throw new DimensionResetComplete();
                }
            };
            entities.addEntity(this.client, false);
            this.user.getStoredObjects().put(EntityTracker.class, entities);
            WorldPackets.register(this.protocol);
            final PacketWrapper dimensionChange = packet(ClientboundBedrockPackets.CHANGE_DIMENSION);
            dimensionChange.write(BedrockTypes.VAR_INT, 1);
            dimensionChange.write(BedrockTypes.POSITION_3F, new Position3f(8F, 64F, 8F));
            dimensionChange.write(Types.BOOLEAN, false);
            dimensionChange.write(Types.BOOLEAN, false);
            dimensionChange.resetReader();
            assertThrows(DimensionResetComplete.class,
                    () -> this.protocol.handle(ClientboundBedrockPackets.CHANGE_DIMENSION, dimensionChange));
        }

        @Override
        public void close() throws Exception {
            this.managerField.set(null, this.previousManager);
            this.entitiesField.set(BedrockProtocol.MAPPINGS, this.previousEntities);
            this.configField.set(null, this.previousConfig);
            this.channel.finishAndReleaseAll();
        }
    }

    private static final class DimensionResetComplete extends RuntimeException {
    }

    private static final class TestClientPlayer extends ClientPlayerEntity {
        private TestClientPlayer(UserConnection user) {
            super(user, 70L, UUID.randomUUID(), new PlayerAbilities(7L, (byte) 0, (byte) 0));
        }

        private void noAi(boolean locked) {
            this.entityData().put(ActorDataIDs.RESERVED_0, new EntityData(ActorDataIDs.RESERVED_0.getValue(),
                    EntityDataTypesBedrock.LONG, locked ? 1L << ActorFlags.NOAI.getValue() : 0L));
            this.onEntityDataChanged();
        }
    }

    private static final class TestProtocol extends BedrockProtocol {
        private void handle(final ServerboundPackets26_1 type, final PacketWrapper wrapper) throws Exception {
            final var mapping = serverboundMappings.mappedPacket(type.state(), type.getId());
            mapping.applyType(wrapper);
            mapping.handler().handle(wrapper);
        }

        private void handle(final ClientboundBedrockPackets type, final PacketWrapper wrapper) throws Exception {
            final var mapping = clientboundMappings.mappedPacket(type.state(), type.getId());
            mapping.applyType(wrapper);
            mapping.handler().handle(wrapper);
        }
    }
}
