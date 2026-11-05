/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.types.entitydata;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.model.EntityProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityPropertiesTypeTest {

    @Test
    void readsIndexedIntegerAndFloatValuesFromMotWireLayout() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeUnsignedVarInt(buffer, 2); // 整数属性数量
            writeUnsignedVarInt(buffer, 0);
            writeSignedVarInt(buffer, 17);
            writeUnsignedVarInt(buffer, 3);
            writeSignedVarInt(buffer, -4);
            writeUnsignedVarInt(buffer, 1); // 浮点属性数量
            writeUnsignedVarInt(buffer, 2);
            buffer.writeFloatLE(1.25F);

            final EntityProperties properties = new EntityPropertiesType().read(buffer);
            assertEquals(17, properties.intProperties().get(0));
            assertEquals(-4, properties.intProperties().get(3));
            assertEquals(1.25F, properties.floatProperties().get(2));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private static void writeSignedVarInt(final ByteBuf buffer, final int value) {
        writeUnsignedVarInt(buffer, (value << 1) ^ (value >> 31));
    }

    private static void writeUnsignedVarInt(final ByteBuf buffer, int value) {
        while ((value & ~0x7F) != 0) {
            buffer.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }
}
