/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.connection.StorableObject;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PlayerAuthInputPacket_InputData;

public final class JavaPlayerStateStorage implements StorableObject {

    public static final int PROTOCOL_VERSION = 1;
    public static final int FLAG_CRAWLING = 1 << 0;

    private int desiredStateFlags;
    private boolean forwardedCrawling;
    private boolean hasDesiredState;
    private boolean forceNextCrawlingTransition;

    public boolean updateFromPayload(final byte[] payload) {
        final ByteBuf buffer = Unpooled.wrappedBuffer(payload);
        try {
            if (!buffer.isReadable() || buffer.readUnsignedByte() != PROTOCOL_VERSION) {
                return false;
            }

            final int flagsStart = buffer.readerIndex();
            final int stateFlags = Types.VAR_INT.readPrimitive(buffer);
            final int encodedFlagsLength = buffer.readerIndex() - flagsStart;
            if (stateFlags < 0 || encodedFlagsLength != varIntLength(stateFlags) || buffer.isReadable()) {
                return false;
            }

            this.desiredStateFlags = stateFlags;
            this.hasDesiredState = true;
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            buffer.release();
        }
    }

    public int stateFlags() {
        return this.desiredStateFlags;
    }

    public boolean hasState(final int flag) {
        return this.hasDesiredState && (this.desiredStateFlags & flag) != 0;
    }

    /**
     * 当 ViaBedrockUtility 的 {@code player_state} 曾经上报过姿态时优先使用它。
     * 工作区版 VBU 没有发送方，所以 Java 的 1 格爬行否则永远到不了
     * MOT SAI 的 START/STOP_CRAWLING。仅在 VBU 沉默时才自行推断。
     */
    public PlayerAuthInputPacket_InputData consumeCrawlingTransition() {
        return consumeCrawlingTransition(false);
    }

    public PlayerAuthInputPacket_InputData consumeCrawlingTransition(final boolean inferredCrawling) {
        final boolean desiredCrawling = this.hasDesiredState ? this.hasState(FLAG_CRAWLING) : inferredCrawling;
        // VBU 沉默时的推断仍必须在 StartCrawling 之后发出 StopCrawling。
        if ((!this.hasDesiredState && !inferredCrawling && !this.forwardedCrawling && !this.forceNextCrawlingTransition)
                || (!this.forceNextCrawlingTransition && desiredCrawling == this.forwardedCrawling)) {
            return null;
        }
        this.forwardedCrawling = desiredCrawling;
        this.forceNextCrawlingTransition = false;
        return this.forwardedCrawling
                ? PlayerAuthInputPacket_InputData.StartCrawling
                : PlayerAuthInputPacket_InputData.StopCrawling;
    }

    public void reset() {
        this.hasDesiredState = false;
        this.desiredStateFlags = 0;
        this.forceNextCrawlingTransition = true;
    }

    private static int varIntLength(int value) {
        int length = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            length++;
        }
        return length;
    }
}
