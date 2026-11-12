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

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPacketLayoutTest {

    @Test
    void pre897OmitsPost860EntityTrailers() {
        assertFalse(EntityPacketLayout.usesAmbient(true, 860));
        assertFalse(EntityPacketLayout.usesSwingSource(true, 860));
        assertFalse(EntityPacketLayout.usesEntityEventFireAtPosition(true, 860));
        assertFalse(EntityPacketLayout.usesAmbient(false, 860));
        assertFalse(EntityPacketLayout.usesSwingSource(false, 896));
        assertTrue(EntityPacketLayout.usesAmbient(false, 897));
        assertTrue(EntityPacketLayout.usesAmbient(true, 897));
        assertTrue(EntityPacketLayout.usesSwingSource(true, 897));
        assertTrue(EntityPacketLayout.usesEntityEventFireAtPosition(true, 974));
        assertFalse(EntityPacketLayout.usesEntityEventFireAtPosition(false, 973));
        assertTrue(EntityPacketLayout.usesEntityEventFireAtPosition(false, 974));
    }

    @Test
    void parsesNetease860MobEffectTrailerWithoutReadingAmbient() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeMobEffectTrailer(buffer, 0L, true, true, 860);
            assertEquals(0L, BedrockTypes.UNSIGNED_VAR_LONG.read(buffer));
            assertFalse(buffer.isReadable(), "NetEase 860 effect trailer must end after tick");
            assertFalse(EntityPacketLayout.readAmbient(buffer, true, 860));
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860MobEffectTrailerAs897ReadsPastThePacket() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeMobEffectTrailer(buffer, 0L, true, true, 860);
            assertEquals(0L, BedrockTypes.UNSIGNED_VAR_LONG.read(buffer));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> EntityPacketLayout.readAmbient(buffer, true, 897));
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialMobEffectTrailerReadsAmbientFrom897() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeMobEffectTrailer(buffer, 0L, true, false, 896);
            assertEquals(0L, BedrockTypes.UNSIGNED_VAR_LONG.read(buffer));
            assertFalse(EntityPacketLayout.readAmbient(buffer, false, 896));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
        final ByteBuf withAmbient = Unpooled.buffer();
        try {
            EntityPacketLayout.writeMobEffectTrailer(withAmbient, 0L, true, false, 897);
            assertEquals(0L, BedrockTypes.UNSIGNED_VAR_LONG.read(withAmbient));
            assertTrue(EntityPacketLayout.readAmbient(withAmbient, false, 897));
            assertFalse(withAmbient.isReadable());
        } finally {
            withAmbient.release();
        }
    }

    @Test
    void netease860AnimateAndEntityEventTrailersAreEmpty() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeAnimateTrailer(buffer, "attack", true, 860);
            EntityPacketLayout.writeEntityEventTrailer(buffer, true, 860);
            assertEquals(0, buffer.readableBytes());
            EntityPacketLayout.skipSwingSource(buffer, true, 860);
            EntityPacketLayout.skipEntityEventFireAtPosition(buffer, true, 860);
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease898WritesAttackIdAfterSwingSource() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeAnimateAction(buffer, 1, true, 898);
            BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, 7L);
            BedrockTypes.FLOAT_LE.write(buffer, 0F);
            EntityPacketLayout.writeAnimateTrailer(buffer, "attack", true, 898);
            EntityPacketLayout.writeNetEaseAttackId(buffer, true, 898);

            assertEquals(1, EntityPacketLayout.readAnimateAction(buffer, true, 898));
            assertEquals(7L, BedrockTypes.UNSIGNED_VAR_LONG.readPrimitive(buffer));
            assertEquals(0F, BedrockTypes.FLOAT_LE.read(buffer));
            EntityPacketLayout.skipSwingSource(buffer, true, 898);
            assertEquals(0L, BedrockTypes.VAR_LONG.readPrimitive(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void attackIdIsAbsentBelow898AndOnOfficial() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeNetEaseAttackId(buffer, true, 860);
            EntityPacketLayout.writeNetEaseAttackId(buffer, true, 897);
            EntityPacketLayout.writeNetEaseAttackId(buffer, false, 898);
            EntityPacketLayout.writeNetEaseAttackId(buffer, false, 975);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860WritesVarIntAnimateActionWithoutSwingSource() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeAnimateAction(buffer, 1, true, 860);
            EntityPacketLayout.writeAnimateTrailer(buffer, "attack", true, 860);
            assertEquals(1, EntityPacketLayout.readAnimateAction(buffer, true, 860));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860WritesRowingTimeAfterRowAnimate() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeAnimateAction(buffer, EntityPacketLayout.ROW_LEFT_ACTION, true, 860);
            BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, 1L);
            BedrockTypes.FLOAT_LE.write(buffer, 0F);
            EntityPacketLayout.writeRowingTime(buffer, EntityPacketLayout.ROW_LEFT_ACTION, 1F, true, 860);
            EntityPacketLayout.writeAnimateTrailer(buffer, null, true, 860);
            assertEquals(EntityPacketLayout.ROW_LEFT_ACTION, EntityPacketLayout.readAnimateAction(buffer, true, 860));
            assertEquals(1L, BedrockTypes.UNSIGNED_VAR_LONG.read(buffer).longValue());
            assertEquals(0F, BedrockTypes.FLOAT_LE.read(buffer));
            EntityPacketLayout.skipRowingTime(buffer, EntityPacketLayout.ROW_LEFT_ACTION, true, 860);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void rowingTimeOnlyExistsBelow897() {
        assertTrue(EntityPacketLayout.usesRowingTime(false, 860));
        assertTrue(EntityPacketLayout.usesRowingTime(true, 860));
        assertTrue(EntityPacketLayout.usesRowingTime(false, 896));
        assertFalse(EntityPacketLayout.usesRowingTime(false, 897));
        assertFalse(EntityPacketLayout.usesRowingTime(true, 897));
        assertFalse(EntityPacketLayout.usesRowingTime(false, 0));
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeRowingTime(buffer, EntityPacketLayout.ROW_RIGHT_ACTION, 1F, false, 975);
            EntityPacketLayout.writeRowingTime(buffer, EntityPacketLayout.ROW_RIGHT_ACTION, 1F, true, 897);
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    @Test
    void officialAnimateStillWritesByteActionAndSwingSource() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            EntityPacketLayout.writeAnimateAction(buffer, 1, false, 975);
            EntityPacketLayout.writeAnimateTrailer(buffer, "attack", false, 975);
            assertEquals(1, EntityPacketLayout.readAnimateAction(buffer, false, 975));
            EntityPacketLayout.skipSwingSource(buffer, false, 975);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void entityInteractionRuntimeIdPreservesUnsigned64BitPattern() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            for (final long runtimeId : new long[]{0L, 127L, 128L, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
                buffer.clear();
                BedrockTypes.UNSIGNED_VAR_LONG.writePrimitive(buffer, runtimeId);

                assertEquals(runtimeId, BedrockTypes.UNSIGNED_VAR_LONG.readPrimitive(buffer));
                assertFalse(buffer.isReadable());
            }
        } finally {
            buffer.release();
        }
    }
}
