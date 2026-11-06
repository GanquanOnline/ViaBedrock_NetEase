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
package net.raphimc.viabedrock.experimental.storage;

import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.model.Position3f;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 按 MOT {@code DebugDrawerPacket.writeShape859} 的字段顺序拼字节，钉住 924 与 974 两个阈值。
 */
class ScriptDebugTextTrackerShapeHeaderTest {

    private static ScriptDebugTextTracker.ShapeReader reader(final ByteBuf buffer) {
        return new ScriptDebugTextTracker.ShapeReader() {
            @Override
            <T> T read(final Type<T> type) {
                return type.read(buffer);
            }
        };
    }

    @Test
    void before924ThereIsNoRenderDistanceAndNoAttachedEntity() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, 7L);
            buffer.writeBoolean(false); // type
            buffer.writeBoolean(true); // position
            BedrockTypes.POSITION_3F.write(buffer, new Position3f(1F, 2F, 3F));
            buffer.writeBoolean(false); // scale
            buffer.writeBoolean(false); // rotation
            buffer.writeBoolean(false); // totalTimeLeft
            buffer.writeBoolean(false); // color（974 之前没有 maximumRenderDistance）
            BedrockTypes.VAR_INT.write(buffer, 0); // dimension（必填，没有存在标志）
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 9); // payloadType

            final ScriptDebugTextTracker.ShapeHeader header = ScriptDebugTextTracker.readHeader(reader(buffer), 860);

            assertEquals(7L, header.id());
            assertNull(header.typeByte());
            assertNotNull(header.location());
            assertNull(header.maxRenderDistance(), "860 的公共头不写 maximumRenderDistance");
            assertNull(header.attachedRuntimeId(), "860 根本不写 attachedEntityId");
            assertEquals(9, header.payloadType());
            assertEquals(0, buffer.readableBytes(), "860 的形状头必须正好吃掉这些字节");
        } finally {
            buffer.release();
        }
    }

    @Test
    void from924TheDimensionIsOptionalAndAttachedEntityMayAppear() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, 11L);
            buffer.writeBoolean(true); // type
            buffer.writeByte(2);
            buffer.writeBoolean(false); // position
            buffer.writeBoolean(true); // scale
            BedrockTypes.FLOAT_LE.write(buffer, 1.5F);
            buffer.writeBoolean(false); // rotation
            buffer.writeBoolean(false); // totalTimeLeft
            buffer.writeBoolean(false); // color（924 仍没有 maximumRenderDistance）
            buffer.writeBoolean(true); // dimension 存在标志
            BedrockTypes.VAR_INT.write(buffer, 0);
            buffer.writeBoolean(true); // attachedEntityId 存在标志
            BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, 33L);
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 4); // payloadType

            final ScriptDebugTextTracker.ShapeHeader header = ScriptDebugTextTracker.readHeader(reader(buffer), 924);

            assertEquals(11L, header.id());
            assertEquals(Byte.valueOf((byte) 2), header.typeByte());
            assertEquals(1.5F, header.scale());
            assertNull(header.maxRenderDistance(), "924 仍不写 maximumRenderDistance");
            assertEquals(33L, header.attachedRuntimeId());
            assertEquals(4, header.payloadType());
            assertEquals(0, buffer.readableBytes(), "924 的形状头必须正好吃掉这些字节");
        } finally {
            buffer.release();
        }
    }

    @Test
    void from974TheMaximumRenderDistanceComesBeforeTheDimension() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            BedrockTypes.UNSIGNED_VAR_LONG.write(buffer, 12L);
            buffer.writeBoolean(false); // type
            buffer.writeBoolean(false); // position
            buffer.writeBoolean(false); // scale
            buffer.writeBoolean(false); // rotation
            buffer.writeBoolean(false); // totalTimeLeft
            buffer.writeBoolean(true); // maximumRenderDistance
            BedrockTypes.FLOAT_LE.write(buffer, 48F);
            buffer.writeBoolean(false); // color
            buffer.writeBoolean(false); // dimension 缺失
            buffer.writeBoolean(false); // attachedEntityId 缺失
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 1); // payloadType

            final ScriptDebugTextTracker.ShapeHeader header = ScriptDebugTextTracker.readHeader(reader(buffer), 974);

            assertEquals(12L, header.id());
            assertEquals(48F, header.maxRenderDistance());
            assertNull(header.attachedRuntimeId());
            assertEquals(1, header.payloadType());
            assertEquals(0, buffer.readableBytes(), "974 的形状头必须正好吃掉这些字节");
        } finally {
            buffer.release();
        }
    }
}
