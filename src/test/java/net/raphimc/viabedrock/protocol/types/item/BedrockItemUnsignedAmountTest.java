/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.types.item;

import com.viaversion.viaversion.libs.fastutil.ints.Int2ObjectOpenHashMap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BedrockItemUnsignedAmountTest {

    @Test
    void roundTripsAccumulatedCraftOutputAbove255() {
        final BedrockItemType type = new BedrockItemType(0, new Int2ObjectOpenHashMap<>(), false);
        final BedrockItem output = new BedrockItem(2);
        output.setAmount(256);
        final ByteBuf buffer = Unpooled.buffer();
        try {
            type.write(buffer, output);
            final BedrockItem decoded = type.read(buffer);

            assertEquals(256, decoded.amount());
            assertFalse(decoded.isEmpty());
        } finally {
            buffer.release();
        }
    }

}
