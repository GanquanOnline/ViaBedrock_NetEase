/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.types;

import com.viaversion.nbt.tag.CompoundTag;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BigEndianNetworkTagTypeTest {

    @Test
    void readsMotBigEndianNetworkNbt() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeByte(buffer, 10); // 根复合标签
            writeString(buffer, "");
            writeStringTag(buffer, "type", "minecraft:test");
            writeListStart(buffer, "properties", 10, 1);
            writeStringTag(buffer, "name", "minecraft:can_move");
            writeIntTag(buffer, "type", 2);
            writeEnd(buffer);
            writeFloatTag(buffer, "marker", 1.25F);
            writeEnd(buffer);

            final CompoundTag root = BedrockTypes.BIG_ENDIAN_NETWORK_COMPOUND_TAG.read(buffer);
            assertEquals("minecraft:test", root.getString("type"));
            assertEquals(1.25F, root.getFloat("marker"));
            assertEquals("minecraft:can_move",
                    root.getListTag("properties", CompoundTag.class).get(0).getString("name"));
            assertEquals(2, root.getListTag("properties", CompoundTag.class).get(0).getInt("type"));
        } finally {
            buffer.release();
        }
    }

    private static void writeByte(final ByteBuf buffer, final int value) {
        buffer.writeByte(value);
    }

    private static void writeString(final ByteBuf buffer, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUnsignedVarInt(buffer, bytes.length);
        buffer.writeBytes(bytes);
    }

    private static void writeStringTag(final ByteBuf buffer, final String name, final String value) {
        writeByte(buffer, 8);
        writeString(buffer, name);
        writeString(buffer, value);
    }

    private static void writeIntTag(final ByteBuf buffer, final String name, final int value) {
        writeByte(buffer, 3);
        writeString(buffer, name);
        writeSignedVarInt(buffer, value);
    }

    private static void writeFloatTag(final ByteBuf buffer, final String name, final float value) {
        writeByte(buffer, 5);
        writeString(buffer, name);
        buffer.writeFloat(value);
    }

    private static void writeListStart(final ByteBuf buffer, final String name, final int elementType, final int size) {
        writeByte(buffer, 9);
        writeString(buffer, name);
        writeByte(buffer, elementType);
        writeSignedVarInt(buffer, size);
    }

    private static void writeEnd(final ByteBuf buffer) {
        writeByte(buffer, 0);
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
