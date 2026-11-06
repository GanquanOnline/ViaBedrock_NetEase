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
package net.raphimc.viabedrock.api;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.protocol.SpecialProtocolVersion;
import net.raphimc.viabedrock.protocol.data.BedrockProtocolProfile;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BedrockProtocolVersion {

    public static final List<ProtocolVersion> PROTOCOLS = new ArrayList<>();

    private static final Set<Integer> PROTOCOL_NUMBERS = new HashSet<>();

    public static final ProtocolVersion bedrockLatest = new SpecialProtocolVersion(ProtocolConstants.DEFAULT_BEDROCK_PROTOCOL_VERSION, "Bedrock " + ProtocolConstants.BEDROCK_VERSION_NAME, ProtocolConstants.JAVA_VERSION) {
        @Override
        public ProtocolVersion getBaseProtocolVersion() {
            return null;
        }
    };

    static {
        ProtocolVersion.register(bedrockLatest);
        PROTOCOLS.add(bedrockLatest);
        PROTOCOL_NUMBERS.add(bedrockLatest.getVersion());
        // 每个已实现档案都注册成可选版本；默认档案复用 bedrockLatest，保持 upstream 的相等语义
        final Set<Integer> registered = new HashSet<>();
        registered.add(bedrockLatest.getVersion());
        for (final BedrockProtocolProfile profile : BedrockProtocolProfile.values()) {
            if (profile.protocolVersion() == ProtocolConstants.DEFAULT_BEDROCK_PROTOCOL_VERSION) {
                continue;
            }
            if (!registered.add(profile.protocolVersion())) {
                throw new IllegalStateException("Duplicate Bedrock protocol profile: " + profile.gameVersion() + " (" + profile.protocolVersion() + ")");
            }
            final ProtocolVersion version = new SpecialProtocolVersion(profile.protocolVersion(), "Bedrock " + profile.label(), ProtocolConstants.JAVA_VERSION) {
                @Override
                public ProtocolVersion getBaseProtocolVersion() {
                    return null;
                }
            };
            ProtocolVersion.register(version);
            PROTOCOLS.add(version);
            PROTOCOL_NUMBERS.add(version.getVersion());
        }
    }

    /** @return 该协议号对应的已注册基岩版本，未注册时返回 null */
    public static ProtocolVersion byProtocol(final int protocolVersion) {
        for (final ProtocolVersion version : PROTOCOLS) {
            if (version.getVersion() == protocolVersion) {
                return version;
            }
        }
        return null;
    }

    /**
     * 判定“这是基岩连接”的唯一入口。档案一旦多于一个，任何 {@code equals(bedrockLatest)}
     * 都会把新档案当成 Java 连接处理（端口默认值、RakNet 通道、账号类型全错）。
     */
    public static boolean isBedrock(final ProtocolVersion version) {
        return version != null && PROTOCOL_NUMBERS.contains(version.getVersion());
    }

}
