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
package net.raphimc.viabedrock.protocol.data;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.minecraft.data.StructuredDataContainer;
import com.viaversion.viaversion.api.protocol.Protocol;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.mcstructs.text.serializer.TextComponentCodec;
import com.viaversion.viaversion.protocols.v1_21_11to26_1.Protocol1_21_11To26_1;
import com.viaversion.viaversion.util.SerializerVersion;

public class ProtocolConstants {

    public static final ProtocolVersion JAVA_VERSION = ProtocolVersion.v26_1;
    public static final Class<? extends Protocol<?, ?, ?, ?>> JAVA_PROTOCOL_CLASS = Protocol1_21_11To26_1.class;
    public static final int JAVA_PACK_VERSION = 84;
    public static final TextComponentCodec JAVA_TEXT_COMPONENT_SERIALIZER = TextComponentCodec.V26_1;
    public static final SerializerVersion JAVA_TELLRAW_SERIALIZER = SerializerVersion.V1_21_6;
    public static final String JAVA_TELLRAW_MAGIC_HEADER = "EC_JAVA_TELLRAW_V1:";
    public static final int JAVA_TELLRAW_MAX_WIRE_BYTES = 32 * 1024;
    public static final int JAVA_AIR_ID = 0;

    public static final String BEDROCK_VERSION_NAME = "1.26.20";
    /** 未配置 {@code bedrock.protocol-version} 时使用的官方协议号；当前生效值走 {@link BedrockWire#current()}。 */
    public static final int DEFAULT_BEDROCK_PROTOCOL_VERSION = 975;
    /** 旧名保留：语义是"未配置时的默认值"，不再是唯一支持的协议号 */
    public static final int BEDROCK_PROTOCOL_VERSION = DEFAULT_BEDROCK_PROTOCOL_VERSION;
    public static final int BEDROCK_RAKNET_PROTOCOL_VERSION = 11;
    public static final int BEDROCK_RAKNET_DEFAULT_PORT = 19132;
    public static final int BEDROCK_NETHERNET_DEFAULT_PORT = 7551;
    public static final String BEDROCK_COMMAND_VERSION = "latest";
    public static final byte BEDROCK_REQUEST_CHUNK_RADIUS_MAX_RADIUS = 28;

    public static final int LAST_BLOCK_ITEM_ID = 255;
    public static final float PLAYER_GRAVITY = 0.08F;
    public static final float BLOCK_FRICTION = 0.6F;

    public static StructuredDataContainer createStructuredDataContainer() {
        final StructuredDataContainer data = new StructuredDataContainer();
        data.setIdLookup(Via.getManager().getProtocolManager().getProtocol(JAVA_PROTOCOL_CLASS), true);
        return data;
    }

}
