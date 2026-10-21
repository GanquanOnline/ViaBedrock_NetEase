/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.experimental.rewriter;

import com.viaversion.viaversion.api.minecraft.data.StructuredDataKey;
import com.viaversion.viaversion.api.minecraft.entitydata.EntityData;
import com.viaversion.viaversion.api.type.types.version.VersionedTypes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreeperIgnitionMetadataTest {

    // 使用独立索引验证字段寻址，生产索引由实体映射提供。
    private static final int IGNITION_INDEX = 7;
    private static final int SWELL_DIR_INDEX = 11;
    private static final int POWERED_INDEX = 13;

    @BeforeAll
    static void initializeProtocolTypes() {
        // 独立运行时先初始化组件键，避免 VersionedTypes 的循环静态初始化。
        assertNotNull(StructuredDataKey.CUSTOM_DATA);
    }

    @Test
    void startsSwellAndCancelsBothFieldsInTheSameMetadataPacket() {
        final List<EntityData> data = new ArrayList<>();
        EntityMetadataRewriter.writeCreeperIgnition(data, IGNITION_INDEX, SWELL_DIR_INDEX, true);
        assertIgnitionPacket(data, true, 1);

        EntityMetadataRewriter.writeCreeperIgnition(data, IGNITION_INDEX, SWELL_DIR_INDEX, false);
        assertIgnitionPacket(data, false, -1);

        EntityMetadataRewriter.writeCreeperIgnition(data, IGNITION_INDEX, SWELL_DIR_INDEX, true);
        assertIgnitionPacket(data, true, 1);
    }

    @Test
    void initializesAnUnignitedCreeperWithTheShrinkingDirection() {
        final List<EntityData> data = new ArrayList<>();
        EntityMetadataRewriter.writeCreeperIgnition(data, IGNITION_INDEX, SWELL_DIR_INDEX, false);

        assertIgnitionPacket(data, false, -1);
    }

    @Test
    void replacesStaleDirectionWithoutChangingChargedAppearance() {
        final EntityData powered = new EntityData(POWERED_INDEX,
                VersionedTypes.V26_1.entityDataTypes().booleanType, true);
        final List<EntityData> data = new ArrayList<>(List.of(powered,
                new EntityData(SWELL_DIR_INDEX, VersionedTypes.V26_1.entityDataTypes().varIntType, 1),
                new EntityData(SWELL_DIR_INDEX, VersionedTypes.V26_1.entityDataTypes().varIntType, 1),
                new EntityData(IGNITION_INDEX, VersionedTypes.V26_1.entityDataTypes().booleanType, true)));

        EntityMetadataRewriter.writeCreeperIgnition(data, IGNITION_INDEX, SWELL_DIR_INDEX, false);

        assertEquals(3, data.size());
        assertEquals(powered, data.stream().filter(entry -> entry.id() == POWERED_INDEX).findFirst().orElseThrow());
        assertIgnitionPacket(data, false, -1);
    }

    private static void assertIgnitionPacket(final List<EntityData> data, final boolean ignited, final int swellDir) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            VersionedTypes.V26_1.entityDataList.write(buffer, data);
            final List<EntityData> decoded = VersionedTypes.V26_1.entityDataList.read(buffer);
            assertFalse(buffer.isReadable());
            assertEquals(1L, decoded.stream().filter(entry -> entry.id() == IGNITION_INDEX).count());
            assertEquals(1L, decoded.stream().filter(entry -> entry.id() == SWELL_DIR_INDEX).count());
            assertTrue(decoded.contains(new EntityData(IGNITION_INDEX,
                    VersionedTypes.V26_1.entityDataTypes().booleanType, ignited)));
            assertTrue(decoded.contains(new EntityData(SWELL_DIR_INDEX,
                    VersionedTypes.V26_1.entityDataTypes().varIntType, swellDir)));
        } finally {
            buffer.release();
        }
    }
}
