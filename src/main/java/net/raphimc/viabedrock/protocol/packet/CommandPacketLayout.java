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

import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CommandOriginType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CommandOutputType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CommandPermissionLevel;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CurrentCmdVersion;
import net.raphimc.viabedrock.protocol.model.CommandOriginData;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.Locale;
import java.util.UUID;

/**
 * AVAILABLE_COMMANDS / COMMAND_REQUEST / COMMAND_OUTPUT 的线格式布局辅助。
 * <p>
 * 官方基岩和 Nukkit-MOT 的协议 897+ 把若干命令字段
 * 从紧凑整数改成字符串（来源类型、权限、输出类型、命令
 * 版本），并加宽了枚举 / 子命令索引。网易 860 仍使用
 * 897 之前的整数布局。把这些载荷按字符串读取会把玩家来源
 * 0 变成一个空的 CommandOriginType，并以 SYSTEM_CHAT 中止 Java 会话。
 */
public final class CommandPacketLayout {

    public static final int STRING_LAYOUT_PROTOCOL = 897;
    /**
     * Nukkit-MOT 898 把网易的 {@code unlimit} 标志从包尾移到了
     * {@code internal} 之后。860 仍在命令版本之后发送它。
     */
    public static final int NETEASE_UNLIMIT_BEFORE_VERSION_PROTOCOL = 898;

    private CommandPacketLayout() {
    }

    public static boolean usesStringLayout() {
        return usesStringLayout(emulateNetEase(), netEaseProtocol());
    }

    public static boolean usesStringLayout(final boolean emulateNetEase, final int protocol) {
        return !emulateNetEase || protocol >= STRING_LAYOUT_PROTOCOL;
    }

    public static CommandOriginData readOrigin(final ByteBuf buffer) {
        return readOrigin(buffer, usesStringLayout(), true);
    }

    public static CommandOriginData readOrigin(final ByteBuf buffer, final boolean stringLayout, final boolean outputPacket) {
        final CommandOriginType type;
        if (stringLayout) {
            final String rawType = BedrockTypes.STRING.read(buffer);
            type = CommandOriginType.getByName(rawType);
            if (type == null) {
                throw new IllegalStateException("Unknown CommandOriginType: " + rawType);
            }
        } else if (outputPacket) {
            type = CommandOriginType.getByValue(BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer), CommandOriginType.Player);
        } else {
            type = CommandOriginType.getByValue(BedrockTypes.VAR_INT.readPrimitive(buffer), CommandOriginType.Player);
        }

        final UUID uuid = BedrockTypes.UUID.read(buffer);
        final String requestId = BedrockTypes.STRING.read(buffer);
        final long uniquePlayerId;
        if (stringLayout) {
            uniquePlayerId = buffer.readLongLE();
        } else if (type == CommandOriginType.DevConsole || type == CommandOriginType.Test) {
            uniquePlayerId = BedrockTypes.VAR_LONG.readPrimitive(buffer);
        } else {
            uniquePlayerId = 0L;
        }
        return new CommandOriginData(type, uuid, requestId, uniquePlayerId);
    }

    public static void writeOrigin(final ByteBuf buffer, final CommandOriginData value) {
        writeOrigin(buffer, value, usesStringLayout(), false);
    }

    public static void writeOrigin(final ByteBuf buffer, final CommandOriginData value, final boolean stringLayout, final boolean outputPacket) {
        if (stringLayout) {
            BedrockTypes.STRING.write(buffer, value.type().name().toLowerCase(Locale.ROOT));
        } else if (outputPacket) {
            BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, value.type().getValue());
        } else {
            BedrockTypes.VAR_INT.writePrimitive(buffer, value.type().getValue());
        }
        BedrockTypes.UUID.write(buffer, value.uuid());
        BedrockTypes.STRING.write(buffer, value.requestId());
        if (stringLayout) {
            buffer.writeLongLE(value.uniquePlayerId());
        } else if (value.type() == CommandOriginType.DevConsole || value.type() == CommandOriginType.Test) {
            BedrockTypes.VAR_LONG.writePrimitive(buffer, value.uniquePlayerId());
        }
    }

    public static void writeRequestVersion(final PacketWrapper wrapper) {
        writeRequestVersion(wrapper, usesStringLayout());
    }

    public static void writeNetEaseUnlimit(final PacketWrapper wrapper) {
        if (emulateNetEase() && netEaseProtocol() >= NETEASE_UNLIMIT_BEFORE_VERSION_PROTOCOL) {
            wrapper.write(Types.BOOLEAN, false); // unlimit
        }
    }

    public static void writeRequestVersion(final PacketWrapper wrapper, final boolean stringLayout) {
        if (stringLayout) {
            wrapper.write(BedrockTypes.STRING, "latest");
        } else {
            wrapper.write(BedrockTypes.VAR_INT, CurrentCmdVersion.Latest.getValue());
        }
    }

    public static void writeRequestVersion(final ByteBuf buffer, final boolean stringLayout) {
        if (stringLayout) {
            BedrockTypes.STRING.write(buffer, "latest");
        } else {
            BedrockTypes.VAR_INT.writePrimitive(buffer, CurrentCmdVersion.Latest.getValue());
        }
    }

    public static CommandOutputType readOutputType(final PacketWrapper wrapper) {
        return readOutputType(wrapper, usesStringLayout());
    }

    public static CommandOutputType readOutputType(final PacketWrapper wrapper, final boolean stringLayout) {
        if (stringLayout) {
            final String rawType = wrapper.read(BedrockTypes.STRING);
            final CommandOutputType type = CommandOutputType.getByName(rawType);
            if (type == null) {
                throw new IllegalStateException("Unknown CommandOutputType: " + rawType);
            }
            return type;
        }
        return CommandOutputType.getByValue(wrapper.read(Types.UNSIGNED_BYTE).intValue(), CommandOutputType.AllOutput);
    }

    public static CommandOutputType readOutputType(final ByteBuf buffer, final boolean stringLayout) {
        if (stringLayout) {
            final String rawType = BedrockTypes.STRING.read(buffer);
            final CommandOutputType type = CommandOutputType.getByName(rawType);
            if (type == null) {
                throw new IllegalStateException("Unknown CommandOutputType: " + rawType);
            }
            return type;
        }
        return CommandOutputType.getByValue(buffer.readUnsignedByte(), CommandOutputType.AllOutput);
    }

    public static int readSuccessCount(final PacketWrapper wrapper) {
        return readSuccessCount(wrapper, usesStringLayout());
    }

    public static int readSuccessCount(final PacketWrapper wrapper, final boolean stringLayout) {
        if (stringLayout) {
            return wrapper.read(BedrockTypes.UNSIGNED_INT_LE).intValue();
        }
        return wrapper.read(BedrockTypes.UNSIGNED_VAR_INT);
    }

    public static int readSuccessCount(final ByteBuf buffer, final boolean stringLayout) {
        return stringLayout ? buffer.readIntLE() : BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer);
    }

    public static CommandOutputMessage readOutputMessage(final PacketWrapper wrapper) {
        return readOutputMessage(wrapper, usesStringLayout());
    }

    public static CommandOutputMessage readOutputMessage(final PacketWrapper wrapper, final boolean stringLayout) {
        if (stringLayout) {
            final String messageId = wrapper.read(BedrockTypes.STRING);
            final boolean successful = wrapper.read(Types.BOOLEAN);
            final String[] parameters = wrapper.read(BedrockTypes.STRING_ARRAY);
            return new CommandOutputMessage(messageId, successful, parameters);
        }
        final boolean successful = wrapper.read(Types.BOOLEAN);
        final String messageId = wrapper.read(BedrockTypes.STRING);
        final String[] parameters = wrapper.read(BedrockTypes.STRING_ARRAY);
        return new CommandOutputMessage(messageId, successful, parameters);
    }

    public static CommandOutputMessage readOutputMessage(final ByteBuf buffer, final boolean stringLayout) {
        if (stringLayout) {
            final String messageId = BedrockTypes.STRING.read(buffer);
            final boolean successful = buffer.readBoolean();
            final String[] parameters = BedrockTypes.STRING_ARRAY.read(buffer);
            return new CommandOutputMessage(messageId, successful, parameters);
        }
        final boolean successful = buffer.readBoolean();
        final String messageId = BedrockTypes.STRING.read(buffer);
        final String[] parameters = BedrockTypes.STRING_ARRAY.read(buffer);
        return new CommandOutputMessage(messageId, successful, parameters);
    }

    public static void skipOutputData(final PacketWrapper wrapper, final CommandOutputType type) {
        skipOutputData(wrapper, type, usesStringLayout());
    }

    public static void skipOutputData(final PacketWrapper wrapper, final CommandOutputType type, final boolean stringLayout) {
        if (stringLayout) {
            wrapper.read(BedrockTypes.OPTIONAL_STRING);
        } else if (type == CommandOutputType.DataSet) {
            wrapper.read(BedrockTypes.STRING);
        }
    }

    public static void skipOutputData(final ByteBuf buffer, final CommandOutputType type, final boolean stringLayout) {
        if (stringLayout) {
            BedrockTypes.OPTIONAL_STRING.read(buffer);
        } else if (type == CommandOutputType.DataSet) {
            BedrockTypes.STRING.read(buffer);
        }
    }

    public static int readEnumValueIndex(final ByteBuf buffer, final int enumLiteralCount, final boolean stringLayout) {
        if (stringLayout) {
            return buffer.readIntLE();
        }
        if (enumLiteralCount < 256) {
            return buffer.readUnsignedByte();
        }
        if (enumLiteralCount < 65536) {
            return buffer.readUnsignedShortLE();
        }
        return buffer.readIntLE();
    }

    public static void writeEnumValueIndex(final ByteBuf buffer, final int index, final int enumLiteralCount, final boolean stringLayout) {
        if (stringLayout) {
            buffer.writeIntLE(index);
            return;
        }
        if (enumLiteralCount < 256) {
            buffer.writeByte(index);
        } else if (enumLiteralCount < 65536) {
            buffer.writeShortLE(index);
        } else {
            buffer.writeIntLE(index);
        }
    }

    public static int readSubCommandValueIndex(final ByteBuf buffer, final boolean stringLayout) {
        return stringLayout ? BedrockTypes.UNSIGNED_VAR_INT.readPrimitive(buffer) : buffer.readUnsignedShortLE();
    }

    public static void writeSubCommandValueIndex(final ByteBuf buffer, final int index, final boolean stringLayout) {
        if (stringLayout) {
            BedrockTypes.UNSIGNED_VAR_INT.writePrimitive(buffer, index);
        } else {
            buffer.writeShortLE(index);
        }
    }

    public static CommandPermissionLevel readPermission(final ByteBuf buffer, final boolean stringLayout) {
        if (stringLayout) {
            return CommandPermissionLevel.getByName(BedrockTypes.STRING.read(buffer), CommandPermissionLevel.Any);
        }
        return CommandPermissionLevel.getByValue(buffer.readUnsignedByte(), CommandPermissionLevel.Any);
    }

    public static void writePermission(final ByteBuf buffer, final CommandPermissionLevel permission, final boolean stringLayout) {
        if (stringLayout) {
            BedrockTypes.STRING.write(buffer, permission.name().toLowerCase(Locale.ROOT));
        } else {
            buffer.writeByte(permission.getValue());
        }
    }

    /**
     * MOT 的 {@code AvailableCommandsPacket.encode()} 总是以
     * {@code putUnsignedVarInt(0)} 结尾，从不写约束记录。
     * 实际运行的网易 860 在空计数之后还会多出 3 个遗留字节。
     * 把它们按官方 {@code {int32, int32, byte[]}} 读取，就是历史上那次
     * {@code 55592 of 55595 bytes} 解析失败，导致命令树被丢弃。
     */
    public static void skipNetEaseEnumConstraintTrailer(final ByteBuf buffer) {
        if (buffer.isReadable()) {
            buffer.skipBytes(buffer.readableBytes());
        }
    }

    public static int readSubCommandOffset(final ByteBuf buffer, final boolean stringLayout) {
        return stringLayout ? buffer.readIntLE() : buffer.readUnsignedShortLE();
    }

    public static void writeSubCommandOffset(final ByteBuf buffer, final int index, final boolean stringLayout) {
        if (stringLayout) {
            buffer.writeIntLE(index);
        } else {
            buffer.writeShortLE(index);
        }
    }

    public record CommandOutputMessage(String messageId, boolean successful, String[] parameters) {
    }

    private static boolean emulateNetEase() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient();
    }

    private static int netEaseProtocol() {
        return ViaBedrock.getConfig().getNetEaseProtocolVersion();
    }
}
