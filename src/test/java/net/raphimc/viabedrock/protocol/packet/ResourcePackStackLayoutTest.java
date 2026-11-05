/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RESOURCE_PACK_STACK 的网易前缀：MOT 从 897（1.21.130.28）起不再序列化行为包栈。
 */
class ResourcePackStackLayoutTest {

    private static final List<String> RESOURCE_PACKS = List.of(
            "c8dd3f44-ba7b-4660-83fa-32cb3a53c0d2",
            "f29963da-d354-4a70-8e7f-c2fe0204cbc7",
            "00000001-0000-0000-0000-000000000000",
            "00000002-0000-0000-0000-000000000000",
            "00000003-0000-0000-0000-000000000000",
            "00000004-0000-0000-0000-000000000000",
            "00000005-0000-0000-0000-000000000000",
            "00000006-0000-0000-0000-000000000000",
            "00000007-0000-0000-0000-000000000000");
    private static final List<String> BEHAVIOUR_PACKS = List.of(
            "10000001-0000-0000-0000-000000000000",
            "10000002-0000-0000-0000-000000000000",
            "10000003-0000-0000-0000-000000000000");

    @Test
    void behaviourPackStackIsOnlySentBelowTheNetEase130Split() {
        assertTrue(ResourcePackStackLayout.sendsNetEaseBehaviourPackStack(true, 860));
        assertFalse(ResourcePackStackLayout.sendsNetEaseBehaviourPackStack(true, 898));
        assertFalse(ResourcePackStackLayout.sendsNetEaseBehaviourPackStack(false, 860));
        assertFalse(ResourcePackStackLayout.sendsNetEaseBehaviourPackStack(false, 898));
    }

    @Test
    void netEase898StackStartsWithResourcePackCount() {
        final StackHead head = parseHead(encodeStack(false), true, 898);

        assertEquals(RESOURCE_PACKS, head.keys());
        assertEquals("*", head.gameVersion());
        assertEquals(0, head.readableAfterParse());
    }

    @Test
    void netEase860StackStartsWithBehaviourPackStack() {
        final StackHead head = parseHead(encodeStack(true), true, 860);

        assertEquals(RESOURCE_PACKS, head.keys());
        assertEquals("*", head.gameVersion());
        assertEquals(0, head.readableAfterParse());
    }

    @Test
    void skippingBehaviourPackStackOnA898StackDesyncsTheResourcePacks() {
        // 898 的服务端不写行为包栈：跳过它会吃掉资源包条目，
        // 接着把 gameVersion 的 '*'（0x2A）当成 42 字节字符串长度，只剩包尾 6 字节。
        final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parseHead(encodeStack(false), true, 860));
        assertEquals("Length is larger than readable bytes: 42 > 6", error.getMessage());
    }

    @Test
    void notSkippingBehaviourPackStackOnA860StackDesyncsTheResourcePacks() {
        final StackHead head = parseHead(encodeStack(true), true, 898);

        assertNotEquals(RESOURCE_PACKS, head.keys());
        assertTrue(head.readableAfterParse() > 0);
    }

    private static ByteBuf encodeStack(final boolean behaviourPackStack) {
        final ByteBuf buffer = Unpooled.buffer();
        buffer.writeBoolean(false); // mustAccept
        if (behaviourPackStack) {
            BedrockTypes.UNSIGNED_VAR_INT.write(buffer, BEHAVIOUR_PACKS.size());
            for (final String id : BEHAVIOUR_PACKS) {
                writeEntry(buffer, id);
            }
        }
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, RESOURCE_PACKS.size());
        for (final String id : RESOURCE_PACKS) {
            writeEntry(buffer, id);
        }
        BedrockTypes.STRING.write(buffer, "*"); // gameVersion
        buffer.writeIntLE(0); // 实验性功能数量
        buffer.writeBoolean(false); // 是否曾切换实验性功能
        buffer.writeBoolean(false); // 是否包含编辑器包
        return buffer;
    }

    private static void writeEntry(final ByteBuf buffer, final String id) {
        BedrockTypes.STRING.write(buffer, id);
        BedrockTypes.STRING.write(buffer, "1.0.35");
        BedrockTypes.STRING.write(buffer, "");
    }

    private static StackHead parseHead(final ByteBuf buffer, final boolean emulateNetEase, final int protocol) {
        try {
            buffer.readBoolean(); // mustAccept
            if (ResourcePackStackLayout.sendsNetEaseBehaviourPackStack(emulateNetEase, protocol)) {
                ResourcePackStackLayout.skipBehaviourPackStack(buffer);
            }
            final int count = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            final ArrayList<String> keys = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                final String id = BedrockTypes.STRING.read(buffer);
                BedrockTypes.STRING.read(buffer); // 版本
                BedrockTypes.STRING.read(buffer); // 子包名称
                keys.add(id);
            }
            final String gameVersion = BedrockTypes.STRING.read(buffer);
            buffer.readIntLE(); // 实验性功能数量
            buffer.readBoolean();
            buffer.readBoolean();
            return new StackHead(keys, gameVersion, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }

    private record StackHead(List<String> keys, String gameVersion, int readableAfterParse) {
    }
}
