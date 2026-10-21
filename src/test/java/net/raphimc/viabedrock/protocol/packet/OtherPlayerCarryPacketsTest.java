package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.libs.fastutil.ints.Int2IntOpenHashMap;
import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.entity.ClientPlayerEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.experimental.ExperimentalFeatures;
import net.raphimc.viabedrock.experimental.riding.RidingModule;
import net.raphimc.viabedrock.experimental.storage.JavaPassengerTracker;
import net.raphimc.viabedrock.experimental.storage.RidingTracker;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorDataIDs;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.GameType;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.model.*;
import net.raphimc.viabedrock.protocol.rewriter.ItemRewriter;
import net.raphimc.viabedrock.protocol.storage.*;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.entitydata.EntityDataTypesBedrock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OtherPlayerCarryPacketsTest {
    @BeforeAll
    static void initializeProtocolTypes() throws Exception {
        Class.forName("com.viaversion.viaversion.api.minecraft.data.StructuredDataKey");
    }

    @Test
    void nativeEggSeatHasTheSameHeightAfterRemoteSpawnWalkingAndTurning() throws Exception {
        try (Fixture fixture = new Fixture()) {
            // Nukkit ADD_PLAYER supplies feet Y=65; MOVE_PLAYER supplies eye Y=66.62.
            // Native capture places this seat at egg-bottom Y=67.10 for a raw seat offset of 0.48.
            final Entity carrier = fixture.spawnRemote(new Position3f(12.5F, 65F, -4.5F));
            final Entity egg = fixture.egg();
            fixture.riding.handleLink(new EntityLink(carrier.uniqueId(), egg.uniqueId(), (byte) 1, false, false, 0F));
            final int anchorId = fixture.last(ClientboundPackets26_1.ADD_ENTITY).get(Types.VAR_INT, 0);
            position(fixture.last(ClientboundPackets26_1.ADD_ENTITY), 12.5, 67.10, -4.5);
            fixture.packets.clear();

            fixture.move(carrier.runtimeId(), new Position3f(13.5F, 66.62F, -4.5F), new Position3f(0F, 90F, 90F), (byte) 0);
            final PacketWrapper movedAnchor = fixture.anchorMove(anchorId);
            position(movedAnchor, 13.5, 67.10, -4.5);
            assertEquals(90F, movedAnchor.get(Types.FLOAT, 0));
            assertFalse(fixture.packets.stream().anyMatch(p -> p.getPacketType() == ClientboundPackets26_1.ADD_ENTITY));
        }
    }

    @Test
    void localServerTeleportCarriesTheEggAtTheNativeSeatHeight() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.move(fixture.client.runtimeId(), new Position3f(12.5F, 66.62F, -4.5F), Position3f.ZERO, (byte) 2);
            final Entity egg = fixture.egg();
            fixture.riding.handleLink(new EntityLink(fixture.client.uniqueId(), egg.uniqueId(), (byte) 1, false, false, 0F));
            final int anchorId = fixture.last(ClientboundPackets26_1.ADD_ENTITY).get(Types.VAR_INT, 0);
            position(fixture.last(ClientboundPackets26_1.ADD_ENTITY), 12.5, 67.10, -4.5);
            fixture.packets.clear();

            fixture.move(fixture.client.runtimeId(), new Position3f(14.5F, 66.62F, -4.5F), new Position3f(0F, 180F, 180F), (byte) 2);
            position(fixture.anchorMove(anchorId), 14.5, 67.10, -4.5);
        }
    }

    private static void position(PacketWrapper packet, double x, double y, double z) throws Exception {
        assertEquals(x, packet.get(Types.DOUBLE, 0), 0.00001);
        assertEquals(y, packet.get(Types.DOUBLE, 1), 0.00001);
        assertEquals(z, packet.get(Types.DOUBLE, 2), 0.00001);
    }

    static class Fixture implements AutoCloseable {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final UserConnectionImpl user = new UserConnectionImpl(channel);
        final List<PacketWrapper> packets = new ArrayList<>();
        final Map<Field, Object> mappings = new LinkedHashMap<>();
        final Field managerField = Via.class.getDeclaredField("manager");
        final Object previousManager;
        final RidingModule module = new RidingModule();
        final Field idField = EntityTypes1_21_11.class.getDeclaredField("id");
        final int oldPlayerId;
        final int oldDisplayId;
        final TestProtocol protocol;
        final EntityTracker entities;
        final ClientPlayerEntity client;
        final RidingTracker riding;

        Fixture() throws Exception {
            Class.forName("com.viaversion.viaversion.api.minecraft.data.StructuredDataKey");
            managerField.setAccessible(true);
            previousManager = managerField.get(null);
            idField.setAccessible(true);
            oldPlayerId = idField.getInt(EntityTypes1_21_11.PLAYER);
            oldDisplayId = idField.getInt(EntityTypes1_21_11.TEXT_DISPLAY);
            idField.setInt(EntityTypes1_21_11.PLAYER, 1);
            idField.setInt(EntityTypes1_21_11.TEXT_DISPLAY, 2);
            final ProtocolManager protocolManager = (ProtocolManager) Proxy.newProxyInstance(ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("createPacketWrapper")) {
                    return packet((PacketType) args[0]);
                }
                throw new UnsupportedOperationException(method.getName());
            });
            managerField.set(null, Proxy.newProxyInstance(ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("getProtocolManager")) return protocolManager;
                throw new UnsupportedOperationException(method.getName());
            }));
            mapping("javaEntityDataFields", Map.of(EntityTypes1_21_11.TEXT_DISPLAY, List.of(EntityDataFields.POS_ROT_INTERPOLATION_DURATION), EntityTypes1_21_11.PLAYER, List.of(EntityDataFields.PLAYER_MODE_CUSTOMISATION)));
            mapping("bedrockEntities", com.google.common.collect.HashBiMap.create());
            mapping("bedrockBlockItems", Set.of());
            mapping("bedrockEntityDataTypes", Map.of());
            this.protocol = new TestProtocol();
            OtherPlayerPackets.register(protocol);
            this.user.put(new GameSessionStorage(user));
            this.user.put(new ItemRewriter(user, new ItemEntry[0]));
            this.user.put(new PlayerListStorage());
            this.user.put(new SpectatorMenuProjection(user));
            this.user.put(new SpectatorCameraTracker(user));
            this.entities = new EntityTracker(user);
            this.user.put(entities);
            this.client = new ClientPlayerEntity(user, 70L, UUID.randomUUID(), new PlayerAbilities(7L, (byte) 0, (byte) 0));
            client.setPosition(new Position3f(0F, 66.62F, 0F));
            client.setRotation(Position3f.ZERO);
            entities.addEntity(client);
            this.riding = new RidingTracker(user);
            this.user.put(riding);
            this.user.put(new JavaPassengerTracker(user));
            ExperimentalFeatures.registerModule(module);
        }

        void mapping(String name, Object value) throws Exception {
            Field field = BedrockMappingData.class.getDeclaredField(name);
            field.setAccessible(true);
            mappings.put(field, field.get(BedrockProtocol.MAPPINGS));
            field.set(BedrockProtocol.MAPPINGS, value);
        }

        PacketWrapper packet(PacketType type) {
            return new PacketWrapperImpl(type, null, user) {
                @Override
                public void send(Class<? extends Protocol> protocol, boolean skipCurrentPipeline) {
                    packets.add(this);
                }
            };
        }

        Entity spawnRemote(Position3f feet) throws Exception {
            final PacketWrapper packet = packet(ClientboundBedrockPackets.ADD_PLAYER);
            packet.write(BedrockTypes.UUID, UUID.randomUUID());
            packet.write(BedrockTypes.STRING, "carrier");
            packet.write(BedrockTypes.UNSIGNED_VAR_LONG, 420L);
            packet.write(BedrockTypes.STRING, "");
            packet.write(BedrockTypes.POSITION_3F, feet);
            packet.write(BedrockTypes.POSITION_3F, Position3f.ZERO);
            packet.write(BedrockTypes.POSITION_3F, Position3f.ZERO);
            packet.write(user.get(ItemRewriter.class).itemType(), new BedrockItem(0));
            packet.write(BedrockTypes.VAR_INT, GameType.Survival.getValue());
            packet.write(BedrockTypes.ENTITY_DATA_ARRAY, new EntityData[0]);
            packet.write(BedrockTypes.ENTITY_PROPERTIES, new EntityProperties(new Int2IntOpenHashMap(), new Int2ObjectOpenHashMap<>()));
            packet.write(BedrockTypes.PLAYER_ABILITIES, new PlayerAbilities(42L, (byte) 0, (byte) 0));
            packet.write(BedrockTypes.ENTITY_LINK_ARRAY, new EntityLink[0]);
            packet.write(BedrockTypes.STRING, "");
            packet.write(BedrockTypes.INT_LE, 0);
            packet.resetReader();
            protocol.handle(ClientboundBedrockPackets.ADD_PLAYER, packet);
            position(last(ClientboundPackets26_1.ADD_ENTITY), feet.x(), 65, feet.z());
            return entities.getEntityByRid(420L);
        }

        void move(long runtimeId, Position3f eye, Position3f rotation, byte mode) throws Exception {
            final PacketWrapper packet = packet(ClientboundBedrockPackets.MOVE_PLAYER);
            packet.write(BedrockTypes.UNSIGNED_VAR_LONG, runtimeId);
            packet.write(BedrockTypes.POSITION_3F, eye);
            packet.write(BedrockTypes.POSITION_3F, rotation);
            packet.write(Types.BYTE, mode);
            packet.write(Types.BOOLEAN, true);
            packet.write(BedrockTypes.UNSIGNED_VAR_LONG, 0L);
            if (mode == 2) {
                packet.write(BedrockTypes.INT_LE, 0);
                packet.write(BedrockTypes.INT_LE, 0);
            }
            packet.write(BedrockTypes.UNSIGNED_VAR_LONG, 1L);
            packet.resetReader();
            protocol.handle(ClientboundBedrockPackets.MOVE_PLAYER, packet);
            if (!packet.isCancelled()) packets.add(packet);
        }

        Entity egg() {
            Entity egg = new Entity(user, 84L, 840L, "ec:egg", entities.getNextJavaEntityId(), UUID.randomUUID(), EntityTypes1_21_11.INTERACTION);
            egg.entityData().put(ActorDataIDs.RESERVED_056, new EntityData(56, EntityDataTypesBedrock.POSITION_3F, new Position3f(0F, 0.48F, 0F)));
            entities.addEntity(egg);
            return egg;
        }

        PacketWrapper last(ClientboundPackets26_1 type) {
            return packets.stream().filter(p -> p.getPacketType() == type).reduce((a, b) -> b).orElseThrow();
        }

        PacketWrapper anchorMove(int id) {
            return packets.stream().filter(p -> p.getPacketType() == ClientboundPackets26_1.ENTITY_POSITION_SYNC && p.get(Types.VAR_INT, 0) == id).reduce((a, b) -> b).orElseThrow();
        }

        @Override
        public void close() throws Exception {
            Field modulesField = ExperimentalFeatures.class.getDeclaredField("MODULES");
            modulesField.setAccessible(true);
            ((List<?>) modulesField.get(null)).remove(module);
            managerField.set(null, previousManager);
            idField.setInt(EntityTypes1_21_11.PLAYER, oldPlayerId);
            idField.setInt(EntityTypes1_21_11.TEXT_DISPLAY, oldDisplayId);
            for (var entry : mappings.entrySet()) entry.getKey().set(BedrockProtocol.MAPPINGS, entry.getValue());
            channel.finishAndReleaseAll();
        }
    }

    static final class TestProtocol extends BedrockProtocol {
        void handle(com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ServerboundPackets26_1 type, PacketWrapper wrapper) throws Exception {
            final var mapping = serverboundMappings.mappedPacket(type.state(), type.getId());
            mapping.applyType(wrapper);
            mapping.handler().handle(wrapper);
        }

        void handle(ClientboundBedrockPackets type, PacketWrapper wrapper) throws Exception {
            final var mapping = clientboundMappings.mappedPacket(type.state(), type.getId());
            mapping.applyType(wrapper);
            mapping.handler().handle(wrapper);
        }
    }
}
