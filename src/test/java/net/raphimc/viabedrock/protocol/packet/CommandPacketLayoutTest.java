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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CommandOriginType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CommandOutputType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CommandPermissionLevel;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CurrentCmdVersion;
import net.raphimc.viabedrock.protocol.model.CommandData;
import net.raphimc.viabedrock.protocol.model.CommandOriginData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;
import net.raphimc.viabedrock.protocol.types.model.CommandDataArrayType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandPacketLayoutTest {

    @Test
    void netease860UsesIntegerCommandLayout() {
        assertFalse(CommandPacketLayout.usesStringLayout(true, 860));
        assertTrue(CommandPacketLayout.usesStringLayout(false, 860));
        assertTrue(CommandPacketLayout.usesStringLayout(true, 897));
    }

    @Test
    void parsesNetease860CommandOutputOriginWithoutReadingAStringType() {
        final UUID uuid = UUID.fromString("4e6c903a-dda0-4435-bd47-429bb00e0326");
        final CommandOriginData origin = new CommandOriginData(CommandOriginType.Player, uuid, "");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CommandPacketLayout.writeOrigin(buffer, origin, false, true);
            buffer.writeByte(CommandOutputType.AllOutput.getValue());
            BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, 1);
            buffer.writeBoolean(true);
            BedrockTypes.STRING.write(buffer, "commands.generic.usage");
            BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, 0);

            final CommandOriginData decoded = CommandPacketLayout.readOrigin(buffer, false, true);
            assertEquals(CommandOriginType.Player, decoded.type());
            assertEquals(uuid, decoded.uuid());
            assertEquals("", decoded.requestId());
            assertEquals(CommandOutputType.AllOutput, CommandPacketLayout.readOutputType(buffer, false));
            assertEquals(1, CommandPacketLayout.readSuccessCount(buffer, false));
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860CommandOutputAs897ReadsAnEmptyOriginType() {
        final CommandOriginData origin = new CommandOriginData(CommandOriginType.Player, UUID.randomUUID(), "");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CommandPacketLayout.writeOrigin(buffer, origin, false, true);
            assertThrows(IllegalStateException.class, () -> CommandPacketLayout.readOrigin(buffer, true, true));
        } finally {
            buffer.release();
        }
    }

    @Test
    void writesNetease860CommandRequestOriginAsZigzagVarInt() {
        final UUID uuid = UUID.fromString("4e6c903a-dda0-4435-bd47-429bb00e0326");
        final CommandOriginData origin = new CommandOriginData(CommandOriginType.Player, uuid, "");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CommandPacketLayout.writeOrigin(buffer, origin, false, false);
            CommandPacketLayout.writeRequestVersion(buffer, false);
            assertEquals(CommandOriginType.Player, CommandPacketLayout.readOrigin(buffer, false, false).type());
            assertEquals(CurrentCmdVersion.Latest.getValue(), (int) BedrockTypes.VAR_INT.read(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860EnumIndexesFitInAByteWhenThereAreFewLiterals() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            CommandPacketLayout.writeEnumValueIndex(buffer, 7, 12, false);
            assertEquals(1, buffer.readableBytes());
            assertEquals(7, CommandPacketLayout.readEnumValueIndex(buffer, 12, false));
            CommandPacketLayout.writePermission(buffer, CommandPermissionLevel.Any, false);
            assertEquals(CommandPermissionLevel.Any, CommandPacketLayout.readPermission(buffer, false));
            CommandPacketLayout.writeSubCommandOffset(buffer, 3, false);
            assertEquals(3, CommandPacketLayout.readSubCommandOffset(buffer, false));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860SkipsThreeByteEnumConstraintTrailerAfterEmptyCount() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeEmptyMotAvailableCommands(buffer);
            buffer.writeByte(0x01);
            buffer.writeByte(0x02);
            buffer.writeByte(0x03);

            final CommandData[] commands = CommandDataArrayType.readClassic(buffer, true);
            assertEquals(0, commands.length);
            assertFalse(buffer.isReadable(), "NetEase leftover after MOT empty constraint count must be consumed");
        } finally {
            buffer.release();
        }
    }

    @Test
    void netease860SkipsThreeByteTrailerEvenWhenItLooksLikeANonZeroCount() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeEmptyMotAvailableCommandsWithoutConstraints(buffer);
            buffer.writeByte(0x01);
            buffer.writeByte(0x02);
            buffer.writeByte(0x03);

            final CommandData[] commands = CommandDataArrayType.readClassic(buffer, true);
            assertEquals(0, commands.length);
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    void treatingNetease860ConstraintTrailerAsOfficialIntRecordsFails() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            writeEmptyMotAvailableCommandsWithoutConstraints(buffer);
            buffer.writeByte(0x01);
            buffer.writeByte(0x02);
            buffer.writeByte(0x03);
            assertThrows(IndexOutOfBoundsException.class, () -> CommandDataArrayType.readClassic(buffer, false));
        } finally {
            buffer.release();
        }
    }

    private static void writeEmptyMotAvailableCommands(final ByteBuf buffer) {
        writeEmptyMotAvailableCommandsWithoutConstraints(buffer);
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // MOT 空约束数量
    }

    private static void writeEmptyMotAvailableCommandsWithoutConstraints(final ByteBuf buffer) {
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 枚举字面量
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 子命令字面量
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 后缀
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 枚举
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 链式子命令
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 命令
        BedrockTypes.UNSIGNED_VAR_INT.write(buffer, 0); // 软枚举
    }
}
