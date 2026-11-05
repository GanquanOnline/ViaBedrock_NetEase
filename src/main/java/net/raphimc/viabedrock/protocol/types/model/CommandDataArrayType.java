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
package net.raphimc.viabedrock.protocol.types.model;

import com.google.common.collect.Sets;
import com.viaversion.viaversion.api.type.Type;
import io.netty.buffer.ByteBuf;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.CommandRegistry_HardNonTerminal;
import net.raphimc.viabedrock.protocol.model.CommandData;
import net.raphimc.viabedrock.protocol.packet.CommandPacketLayout;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.util.*;
import java.util.function.Consumer;

public class CommandDataArrayType extends Type<CommandData[]> {

    private static final int FLAG_VALID = 1 << 20;
    private static final int FLAG_ENUM = 1 << 21;
    private static final int FLAG_POSTFIX = 1 << 24;
    private static final int FLAG_SOFT_ENUM = 1 << 26;
    private static final int FLAG_SUB_COMMAND = 1 << 27;

    public CommandDataArrayType() {
        super(CommandData[].class);
    }

    private static boolean isNetEaseLegacyProtocol() {
        return ViaBedrock.getConfig().shouldEmulateNetEaseClient()
                && ViaBedrock.getConfig().getNetEaseProtocolVersion() > 0
                && ViaBedrock.getConfig().getNetEaseProtocolVersion() < 898;
    }

    @Override
    public CommandData[] read(ByteBuf buffer) {
        final int startReaderIndex = buffer.readerIndex();
        final boolean netEaseLegacy = isNetEaseLegacyProtocol();
        final int originalWriterIndex = buffer.writerIndex();
        try {
            return readClassic(buffer, netEaseLegacy);
        } catch (RuntimeException | Error first) {
            ViaBedrock.getPlatform().getLogger().warning(
                    "Failed to parse AVAILABLE_COMMANDS data after consuming "
                            + (buffer.readerIndex() - startReaderIndex) + " of "
                            + (originalWriterIndex - startReaderIndex) + " bytes: " + first);
            throw first;
        }
    }

    public static CommandData[] readClassic(ByteBuf buffer, final boolean netEaseLegacy) {
        final String[] enumLiterals = BedrockTypes.STRING_ARRAY.read(buffer); // 枚举字面量
        final String[] subCommandLiterals = BedrockTypes.STRING_ARRAY.read(buffer); // 子命令字面量
        final String[] postFixLiterals = BedrockTypes.STRING_ARRAY.read(buffer); // 后缀字面量

        final CommandData.EnumData[] enumPalette = new CommandData.EnumData[BedrockTypes.UNSIGNED_VAR_INT.read(buffer)];
        final Map<String, CommandData.EnumData> enumPaletteMap = new HashMap<>(enumPalette.length);
        for (int i = 0; i < enumPalette.length; i++) {
            final String name = BedrockTypes.STRING.read(buffer); // 名称
            final int count = BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // 值数量
            final Set<String> values = new HashSet<>(Math.max(16, Math.min(count, 1024)));
            for (int j = 0; j < count; j++) {
                final int index = CommandPacketLayout.readEnumValueIndex(buffer, enumLiterals.length, !netEaseLegacy);
                if (index >= 0 && index < enumLiterals.length) {
                    values.add(enumLiterals[index]);
                } else {
                    values.add("default");
                }
            }

            final CommandData.EnumData enumData;
            if (enumPaletteMap.containsKey(name)) {
                enumData = enumPaletteMap.get(name);
                enumData.addValues(values);
            } else {
                enumPaletteMap.put(name, enumData = new CommandData.EnumData(name, values, false));
            }
            enumPalette[i] = enumData;
        }

        final CommandData.SubCommandData[] subCommands = new CommandData.SubCommandData[BedrockTypes.UNSIGNED_VAR_INT.read(buffer)];
        final Map<String, CommandData.SubCommandData> subCommandMap = new HashMap<>(subCommands.length);
        for (int i = 0; i < subCommands.length; i++) {
            final String name = BedrockTypes.STRING.read(buffer); // 名称
            final int count = BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // 值数量
            final Map<String, Integer> values = new HashMap<>(Math.max(16, Math.min(count, 1024)));
            for (int j = 0; j < count; j++) {
                final int index = CommandPacketLayout.readSubCommandValueIndex(buffer, !netEaseLegacy); // 值
                final int type = CommandPacketLayout.readSubCommandValueIndex(buffer, !netEaseLegacy); // 类型
                if (index >= 0 && index < subCommandLiterals.length) {
                    values.put(subCommandLiterals[index], type);
                }
            }

            final CommandData.SubCommandData subCommandData;
            if (subCommandMap.containsKey(name)) {
                subCommandData = subCommandMap.get(name);
                subCommandData.addValues(values);
            } else {
                subCommandMap.put(name, subCommandData = new CommandData.SubCommandData(name, values));
            }
            subCommands[i] = subCommandData;
        }

        final List<Consumer<CommandData.EnumData[]>> softEnumResolvers = new ArrayList<>();
        final CommandData[] commands = new CommandData[BedrockTypes.UNSIGNED_VAR_INT.read(buffer)];
        final Map<String, CommandData> commandMap = new HashMap<>(commands.length);
        for (int i = 0; i < commands.length; i++) {
            final String name = BedrockTypes.STRING.read(buffer); // 名称
            final String description = BedrockTypes.STRING.read(buffer); // 描述
            final int flags = buffer.readUnsignedShortLE(); // 标志
            final byte permission = (byte) CommandPacketLayout.readPermission(buffer, !netEaseLegacy).getValue(); // 权限
            final int aliasIndex = buffer.readIntLE(); // 别名
            final boolean validAliasPointer = aliasIndex >= 0 && aliasIndex < enumPalette.length;
            final CommandData.EnumData alias;
            if (validAliasPointer) {
                alias = enumPalette[aliasIndex];
            } else {
                alias = null;
            }

            final int subCommandCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer);
            for (int j = 0; j < subCommandCount; j++) {
                CommandPacketLayout.readSubCommandOffset(buffer, !netEaseLegacy); // 子命令索引
            }

            final CommandData.OverloadData[] overloads = new CommandData.OverloadData[BedrockTypes.UNSIGNED_VAR_INT.read(buffer)];
            for (int j = 0; j < overloads.length; j++) {
                final boolean chaining = buffer.readBoolean();
                final CommandData.OverloadData.ParamData[] params = new CommandData.OverloadData.ParamData[BedrockTypes.UNSIGNED_VAR_INT.read(buffer)];
                for (int k = 0; k < params.length; k++) {
                    final String paramName = BedrockTypes.STRING.read(buffer); // 名称
                    final int param = buffer.readIntLE(); // 参数
                    final boolean optional = buffer.readBoolean(); // 可选
                    final short paramFlags = buffer.readUnsignedByte(); // 标志
                    CommandRegistry_HardNonTerminal type = null;
                    CommandData.EnumData enumData = null;
                    CommandData.SubCommandData subCommandData = null;
                    String postfix = null;

                    if ((param & FLAG_SOFT_ENUM) != 0) {
                        final int index = param & ~FLAG_SOFT_ENUM & ~FLAG_VALID;
                        final int finalK = k;
                        softEnumResolvers.add(softEnumPalette -> {
                            if (index >= 0 && index < softEnumPalette.length) {
                                params[finalK] = new CommandData.OverloadData.ParamData(paramName, optional, paramFlags, null, softEnumPalette[index], null, null);
                            } else {
                                params[finalK] = new CommandData.OverloadData.ParamData(paramName, optional, paramFlags, CommandRegistry_HardNonTerminal.Id, null, null, null);
                            }
                        });
                        continue;
                    } else if ((param & FLAG_POSTFIX) != 0) {
                        final int index = param & ~FLAG_POSTFIX;
                        postfix = postFixLiterals[index];
                    } else if ((param & FLAG_ENUM) != 0) {
                        final int index = param & ~FLAG_ENUM & ~FLAG_VALID;
                        enumData = enumPalette[index];
                    } else if ((param & FLAG_SUB_COMMAND) != 0) {
                        final int index = param & ~FLAG_SUB_COMMAND & ~FLAG_VALID;
                        subCommandData = subCommands[index];
                    } else if ((param & FLAG_VALID) != 0) {
                        type = CommandRegistry_HardNonTerminal.getByValue(param);
                    }

                    params[k] = new CommandData.OverloadData.ParamData(paramName, optional, paramFlags, type, enumData, subCommandData, postfix);
                }
                overloads[j] = new CommandData.OverloadData(chaining, params);
            }

            CommandData commandData;
            if (commandMap.containsKey(name)) {
                final CommandData oldCommandData = commandMap.get(name);
                final CommandData.EnumData newAlias = validAliasPointer ? alias : oldCommandData.alias();
                final CommandData.OverloadData[] newOverloads = new CommandData.OverloadData[oldCommandData.overloads().length + overloads.length];
                System.arraycopy(oldCommandData.overloads(), 0, newOverloads, 0, oldCommandData.overloads().length);
                System.arraycopy(overloads, 0, newOverloads, oldCommandData.overloads().length, overloads.length);
                commandMap.put(name, commandData = new CommandData(name, oldCommandData.description(), oldCommandData.flags(), oldCommandData.permission(), newAlias, newOverloads));
            } else {
                commandMap.put(name, commandData = new CommandData(name, description, flags, permission, alias, overloads));
            }
            commands[i] = commandData;
        }

        final CommandData.EnumData[] softEnumPalette = new CommandData.EnumData[BedrockTypes.UNSIGNED_VAR_INT.read(buffer)];
        final Map<String, CommandData.EnumData> softEnumPaletteMap = new HashMap<>(softEnumPalette.length);
        for (int i = 0; i < softEnumPalette.length; i++) {
            final String name = BedrockTypes.STRING.read(buffer); // 名称
            final Set<String> values = Sets.newHashSet(BedrockTypes.STRING_ARRAY.read(buffer)); // 值

            final CommandData.EnumData enumData;
            if (softEnumPaletteMap.containsKey(name)) {
                enumData = softEnumPaletteMap.get(name);
                enumData.addValues(values);
            } else {
                enumData = new CommandData.EnumData(name, values, true);
                softEnumPaletteMap.put(name, enumData);
            }
            softEnumPalette[i] = enumData;
        }
        softEnumResolvers.forEach(c -> c.accept(softEnumPalette));

        if (!buffer.isReadable()) {
            return commands;
        }
        final int enumFlagsCount = BedrockTypes.UNSIGNED_VAR_INT.read(buffer); // 枚举标志数量
        if (netEaseLegacy) {
            // MOT 总是写入 count 0，且从不发出约束记录。线上网易
            // 860 在该空 count 之后仍会追加 3 个残留字节。这些字节
            // 不是完整的官方 {int32, int32, byte[]} 记录；把它当作一条记录读取
            // 就是历史上导致命令树被丢弃的 55592-of-55595 IndexOutOfBoundsException。
            // 跳过这段尾部数据，而不是凭空造一个 3 字节字段。
            CommandPacketLayout.skipNetEaseEnumConstraintTrailer(buffer);
            return commands;
        }
        for (int i = 0; i < enumFlagsCount; i++) {
            final int valueIndex = buffer.readIntLE(); // 值索引
            final int enumIndex = buffer.readIntLE(); // 枚举索引
            final byte[] constraints = BedrockTypes.BYTE_ARRAY.read(buffer); // 约束
            if (valueIndex < 0 || valueIndex >= enumLiterals.length || enumIndex < 0 || enumIndex >= enumPalette.length) {
                continue;
            }
            final String valueKey = enumLiterals[valueIndex];
            final CommandData.EnumData enumData = enumPalette[enumIndex];
            final Set<Byte> constraintsSet = enumData.values().get(valueKey);
            if (constraintsSet != null) {
                for (byte constraint : constraints) {
                    constraintsSet.add(constraint);
                }
            }
        }

        return commands;
    }

    @Override
    public void write(ByteBuf buffer, CommandData[] value) {
        throw new UnsupportedOperationException("Cannot serialize CommandDataArrayType");
    }

}
