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

import com.google.common.collect.HashBiMap;
import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.ViaManager;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.entities.EntityTypes1_21_11;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.packet.PacketType;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.remapper.PacketHandler;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.packet.PacketWrapperImpl;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.packet.ClientboundPackets26_1;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.entity.CustomEntity;
import net.raphimc.viabedrock.api.model.entity.Entity;
import net.raphimc.viabedrock.api.model.entity.LivingEntity;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.BedrockMappingData;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ActorEvent;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.SharedTypes_Legacy_LevelSoundEvent;
import net.raphimc.viabedrock.protocol.data.enums.java.EntityEvent;
import net.raphimc.viabedrock.protocol.data.generated.java.Attributes;
import net.raphimc.viabedrock.protocol.data.generated.java.EntityDataFields;
import net.raphimc.viabedrock.protocol.storage.EntityTracker;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityDeathPacketsTest {

    @BeforeAll
    static void initializeProtocolTypes() {
        // 独立运行时先初始化组件键，避免 VersionedTypes 的循环静态初始化。
        assertNotNull(StructuredDataKey.CUSTOM_DATA);
    }

    @Test
    void customEntityDeathWritesJavaStatusWithoutPriorHurt() throws Exception {
        try (DeathFixture fixture = new DeathFixture()) {
            fixture.entity = new CustomEntity(fixture.user, 1L, 2L, "easecation:ghosarea_hunter_2", 300, null) {
                @Override
                public void playSound(final SharedTypes_Legacy_LevelSoundEvent soundEvent) {
                    fixture.sounds.add(soundEvent);
                }
            };

            final PacketWrapper translated = fixture.death();

            assertFalse(translated.isCancelled());
            assertEquals(ClientboundPackets26_1.ENTITY_EVENT, translated.getPacketType());
            final ByteBuf encoded = Unpooled.buffer();
            try {
                translated.writeToBuffer(encoded);
                assertEquals(ClientboundPackets26_1.ENTITY_EVENT.getId(), Types.VAR_INT.readPrimitive(encoded));
                assertEquals(300, encoded.readInt());
                assertEquals(EntityEvent.DEATH.getValue(), encoded.readByte());
                assertFalse(encoded.isReadable());
            } finally {
                encoded.release();
            }
            assertTrue(fixture.packets.isEmpty());
            assertEquals(List.of(SharedTypes_Legacy_LevelSoundEvent.Death), fixture.sounds);
        }
    }

    @Test
    void ordinaryLivingEntityDeathStillSendsZeroHealthAndDeathSound() throws Exception {
        try (DeathFixture fixture = new DeathFixture()) {
            final LivingEntity living = new LivingEntity(fixture.user, 1L, 2L, "minecraft:zombie", 300,
                    UUID.randomUUID(), EntityTypes1_21_11.ZOMBIE) {
                @Override
                public void playSound(final SharedTypes_Legacy_LevelSoundEvent soundEvent) {
                    fixture.sounds.add(soundEvent);
                }
            };
            fixture.entity = living;

            assertTrue(fixture.death().isCancelled());

            assertTrue(living.isDead());
            assertEquals(List.of(ClientboundPackets26_1.SET_ENTITY_DATA, ClientboundPackets26_1.UPDATE_ATTRIBUTES),
                    fixture.packets.stream().map(PacketWrapper::getPacketType).toList());
            final PacketWrapper metadata = fixture.packets.getFirst();
            assertEquals(300, metadata.get(Types.VAR_INT, 0));
            final List<EntityData> entityData = metadata.get(VersionedTypes.V26_1.entityDataList, 0);
            assertEquals(1, entityData.size());
            assertEquals(0F, (Float) entityData.getFirst().value());
            assertEquals(List.of(SharedTypes_Legacy_LevelSoundEvent.Death), fixture.sounds);
        }
    }

    @Test
    void missingEntityDeathDoesNotEmitAStatus() throws Exception {
        try (DeathFixture fixture = new DeathFixture()) {
            assertTrue(fixture.death().isCancelled());
            assertTrue(fixture.packets.isEmpty());
            assertTrue(fixture.sounds.isEmpty());
        }
    }

    private static final class DeathFixture implements AutoCloseable {

        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final UserConnectionImpl user = new UserConnectionImpl(this.channel);
        private final List<PacketWrapper> packets = new ArrayList<>();
        private final List<SharedTypes_Legacy_LevelSoundEvent> sounds = new ArrayList<>();
        private final List<ByteBuf> inputs = new ArrayList<>();
        private final Field managerField = Via.class.getDeclaredField("manager");
        private final Field fieldsField = BedrockMappingData.class.getDeclaredField("javaEntityDataFields");
        private final Field attributesField = BedrockMappingData.class.getDeclaredField("javaEntityAttributes");
        private final Object previousManager;
        private final Object previousFields;
        private final Object previousAttributes;
        private PacketHandler deathHandler;
        private Entity entity;

        private DeathFixture() throws Exception {
            this.managerField.setAccessible(true);
            this.fieldsField.setAccessible(true);
            this.attributesField.setAccessible(true);
            this.previousManager = this.managerField.get(null);
            this.previousFields = this.fieldsField.get(BedrockProtocol.MAPPINGS);
            this.previousAttributes = this.attributesField.get(BedrockProtocol.MAPPINGS);
            // 仅替换传输与映射输入，执行真实注册的实体事件处理器和血量发包路径。
            final ProtocolManager protocolManager = (ProtocolManager) Proxy.newProxyInstance(
                    ProtocolManager.class.getClassLoader(), new Class[]{ProtocolManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("createPacketWrapper")) {
                            return new PacketWrapperImpl((PacketType) args[0], null, (UserConnection) args[2]) {
                                @Override
                                public void send(final Class<? extends Protocol> protocol, final boolean skipCurrentPipeline) {
                                    packets.add(this);
                                }
                            };
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            final ViaManager manager = (ViaManager) Proxy.newProxyInstance(
                    ViaManager.class.getClassLoader(), new Class[]{ViaManager.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getProtocolManager")) {
                            return protocolManager;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            this.managerField.set(null, manager);
            this.fieldsField.set(BedrockProtocol.MAPPINGS,
                    Map.of(EntityTypes1_21_11.ZOMBIE, List.of(EntityDataFields.HEALTH)));
            this.attributesField.set(BedrockProtocol.MAPPINGS, HashBiMap.create(Map.of(Attributes.MAX_HEALTH, 1)));
            this.user.getStoredObjects().put(EntityTracker.class, new EntityTracker(this.user) {
                @Override
                public Entity getEntityByRid(final long runtimeId) {
                    return entity != null && entity.runtimeId() == runtimeId ? entity : null;
                }
            });
            EntityPackets.register(new BedrockProtocol() {
                @Override
                public void registerClientbound(final ClientboundBedrockPackets packetType, final ClientboundPackets26_1 mappedPacketType,
                                                final PacketHandler handler, final boolean override) {
                    if (packetType == ClientboundBedrockPackets.ENTITY_EVENT) {
                        assertEquals(ClientboundPackets26_1.ENTITY_EVENT, mappedPacketType);
                        deathHandler = handler;
                    }
                }
            });
            assertNotNull(this.deathHandler);
        }

        private PacketWrapper death() throws Exception {
            final ByteBuf input = Unpooled.buffer();
            this.inputs.add(input);
            BedrockTypes.UNSIGNED_VAR_LONG.writePrimitive(input, 2L);
            input.writeByte(ActorEvent.DEATH.getValue());
            BedrockTypes.VAR_INT.writePrimitive(input, 0);
            BedrockTypes.OPTIONAL_POSITION_3F.write(input, null);
            final PacketWrapper wrapper = new PacketWrapperImpl(ClientboundPackets26_1.ENTITY_EVENT, input, this.user);
            this.deathHandler.handle(wrapper);
            assertFalse(input.isReadable());
            return wrapper;
        }

        @Override
        public void close() throws Exception {
            this.managerField.set(null, this.previousManager);
            this.fieldsField.set(BedrockProtocol.MAPPINGS, this.previousFields);
            this.attributesField.set(BedrockProtocol.MAPPINGS, this.previousAttributes);
            this.inputs.forEach(ByteBuf::release);
            this.channel.finishAndReleaseAll();
        }
    }
}
