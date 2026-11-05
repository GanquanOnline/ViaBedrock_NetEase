/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.api.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.io.IOException;

/** 用于 MOT 大端网络 NBT 变体的 DataInput 适配器。 */
public final class BigEndianNetworkByteBufInputStream extends ByteBufInputStream {

    private final ByteBuf buffer;

    public BigEndianNetworkByteBufInputStream(final ByteBuf buffer) {
        super(buffer);
        this.buffer = buffer;
    }

    @Override
    public short readShort() {
        return this.buffer.readShort();
    }

    @Override
    public int readUnsignedShort() {
        return this.buffer.readUnsignedShort();
    }

    @Override
    public char readChar() {
        return this.buffer.readChar();
    }

    @Override
    public int readInt() throws IOException {
        try {
            return BedrockTypes.VAR_INT.readPrimitive(this.buffer);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public long readLong() throws IOException {
        try {
            return BedrockTypes.VAR_LONG.readPrimitive(this.buffer);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }

    @Override
    public float readFloat() {
        return this.buffer.readFloat();
    }

    @Override
    public double readDouble() {
        return this.buffer.readDouble();
    }

    @Override
    public String readUTF() throws IOException {
        try {
            return BedrockTypes.STRING.read(this.buffer);
        } catch (final Exception e) {
            throw new IOException(e);
        }
    }
}
