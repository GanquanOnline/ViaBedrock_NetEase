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

class PlayerSneakPoseMetadataTest {

    // 使用独立索引检查输出字段，生产索引仍由实体映射提供。
    private static final int SHARED_FLAGS_INDEX = 0;
    private static final int SILENT_INDEX = 4;
    private static final int NO_GRAVITY_INDEX = 5;
    private static final int POSE_INDEX = 6;
    private static final int LIVING_FLAGS_INDEX = 8;

    @BeforeAll
    static void initializeProtocolTypes() {
        // 独立运行时先初始化组件键，避免 VersionedTypes 的循环静态初始化。
        assertNotNull(StructuredDataKey.CUSTOM_DATA);
    }

    @Test
    void localPlayerNeverReceivesSyntheticPoseFromAlternatingSneakSnapshots() {
        for (boolean sneaking : new boolean[]{true, false, true, false, true, false}) {
            final EntityData sharedFlags = sharedFlags((byte) (sneaking ? 0x02 : 0));
            final List<EntityData> data = new ArrayList<>(List.of(sharedFlags));

            EntityMetadataRewriter.writePlayerSneakPose(data, POSE_INDEX, sneaking, true);

            assertEquals(List.of(sharedFlags), roundTrip(data));
        }
    }

    @Test
    void localPlayerRetainsAllOtherMetadataAndSharedFlagBits() {
        for (boolean sneaking : new boolean[]{false, true}) {
            final List<EntityData> expected = List.of(
                    sharedFlags((byte) (sneaking ? 0xBB : 0xB9)),
                    new EntityData(SILENT_INDEX, VersionedTypes.V26_1.entityDataTypes().booleanType, true),
                    new EntityData(NO_GRAVITY_INDEX, VersionedTypes.V26_1.entityDataTypes().booleanType, true),
                    new EntityData(LIVING_FLAGS_INDEX, VersionedTypes.V26_1.entityDataTypes().byteType, (byte) 0x03));
            final List<EntityData> data = new ArrayList<>(expected);

            EntityMetadataRewriter.writePlayerSneakPose(data, POSE_INDEX, sneaking, true);

            assertEquals(expected, roundTrip(data));
        }
    }

    @Test
    void localPlayerDoesNotRemoveOrReplaceAPoseFromAnotherSource() {
        final EntityData swimming = new EntityData(POSE_INDEX,
                VersionedTypes.V26_1.entityDataTypes().poseType, 3);
        final List<EntityData> data = new ArrayList<>(List.of(swimming));

        EntityMetadataRewriter.writePlayerSneakPose(data, POSE_INDEX, true, true);
        EntityMetadataRewriter.writePlayerSneakPose(data, POSE_INDEX, false, true);

        assertEquals(List.of(swimming), roundTrip(data));
    }

    @Test
    void remotePlayerStillReceivesCrouchingAndStandingPoses() {
        for (boolean sneaking : new boolean[]{false, true, false}) {
            final EntityData sharedFlags = sharedFlags((byte) (sneaking ? 0x02 : 0));
            final List<EntityData> data = new ArrayList<>(List.of(sharedFlags));

            EntityMetadataRewriter.writePlayerSneakPose(data, POSE_INDEX, sneaking, false);

            final List<EntityData> decoded = roundTrip(data);
            assertEquals(2, decoded.size());
            assertTrue(decoded.contains(sharedFlags));
            assertTrue(decoded.contains(new EntityData(POSE_INDEX,
                    VersionedTypes.V26_1.entityDataTypes().poseType, sneaking ? 5 : 0)));
        }
    }

    @Test
    void remotePlayerPoseIsUpsertedWithoutDuplicateFields() {
        final EntityData sharedFlags = sharedFlags((byte) 0x02);
        final EntityData standing = new EntityData(POSE_INDEX,
                VersionedTypes.V26_1.entityDataTypes().poseType, 0);
        final List<EntityData> data = new ArrayList<>(List.of(sharedFlags, standing, standing));

        EntityMetadataRewriter.writePlayerSneakPose(data, POSE_INDEX, true, false);
        EntityMetadataRewriter.writePlayerSneakPose(data, POSE_INDEX, true, false);

        final List<EntityData> decoded = roundTrip(data);
        assertEquals(2, decoded.size());
        assertEquals(1L, decoded.stream().filter(entry -> entry.id() == POSE_INDEX).count());
        assertTrue(decoded.contains(sharedFlags));
        assertTrue(decoded.contains(new EntityData(POSE_INDEX,
                VersionedTypes.V26_1.entityDataTypes().poseType, 5)));
    }

    private static EntityData sharedFlags(final byte flags) {
        return new EntityData(SHARED_FLAGS_INDEX, VersionedTypes.V26_1.entityDataTypes().byteType, flags);
    }

    private static List<EntityData> roundTrip(final List<EntityData> data) {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            VersionedTypes.V26_1.entityDataList.write(buffer, data);
            final List<EntityData> decoded = VersionedTypes.V26_1.entityDataList.read(buffer);
            assertFalse(buffer.isReadable());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}
