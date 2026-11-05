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
package net.raphimc.viabedrock.experimental.tablist;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

/**
 * 显示在玩家列表名称旁的版本类型与版本号。远端身份来自
 * Waterdog 插件快照；本地 Java 玩家始终是 JE。
 */
public record PlayerIdentity(boolean javaEdition, String version) {

    public static final PlayerIdentity BEDROCK_UNKNOWN = bedrock("");

    public PlayerIdentity {
        version = version == null ? "" : version.trim();
    }

    public static PlayerIdentity javaEdition(final String version) {
        return new PlayerIdentity(true, version);
    }

    public static PlayerIdentity bedrock(final String version) {
        return new PlayerIdentity(false, version);
    }

    public String prefix() {
        return this.javaEdition ? "[JE]" : "[BE]";
    }

    public String label() {
        final String edition = this.javaEdition ? "JE" : "BE";
        return this.version.isEmpty() ? edition : edition + " " + this.version;
    }

    public static String javaVersionName(final UserConnection user) {
        if (user != null && user.getProtocolInfo() != null && user.getProtocolInfo().protocolVersion() != null) {
            return user.getProtocolInfo().protocolVersion().getName();
        }
        return ProtocolVersion.v1_21_11.getName();
    }

    public static String javaVersionName(final int protocol) {
        return ProtocolVersion.getProtocol(protocol).getName();
    }
}
