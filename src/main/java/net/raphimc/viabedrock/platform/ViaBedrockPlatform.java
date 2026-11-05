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
package net.raphimc.viabedrock.platform;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.ProtocolManager;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.ViaBedrockConfig;
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.BedrockDataTables;
import net.raphimc.viabedrock.protocol.data.BedrockProtocolProfile;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;

import java.io.File;
import java.util.logging.Logger;

public interface ViaBedrockPlatform {

    default void init(final File configFile) {
        this.init(new ViaBedrockConfig(configFile, this.getLogger()));
    }

    default void init(final net.raphimc.viabedrock.platform.ViaBedrockConfig config) {
        config.reload();
        Via.getManager().getConfigurationProvider().register(config);
        ViaBedrock.init(this, config);
        Via.getManager().getSubPlatforms().add(ViaBedrock.IMPL_VERSION);

        // 数据表闸门：缺文件直接在启动时报错，而不是连上后倒在映射加载里
        BedrockDataTables.validate(BedrockWire.current());

        // 每个已实现档案都要注册：下拉框选到新版本却找不到转换协议，会在连接时才炸
        final ProtocolManager protocolManager = Via.getManager().getProtocolManager();
        for (final BedrockProtocolProfile profile : BedrockProtocolProfile.values()) {
            final ProtocolVersion target = BedrockProtocolVersion.byProtocol(profile.protocolVersion());
            if (target == null) {
                throw new IllegalStateException("Bedrock profile " + profile.name() + " (" + profile.protocolVersion()
                        + ") is missing from BedrockProtocolVersion");
            }
            protocolManager.registerProtocol(new BedrockProtocol(), ProtocolConstants.JAVA_VERSION, target);
        }

        this.getServerPacksFolder().mkdirs();
        this.getBlobCacheFolder().mkdirs();
    }

    Logger getLogger();

    File getDataFolder();

    default File getDataCacheFolder() {
        return new File(this.getDataFolder(), "viabedrock");
    }

    default File getServerPacksFolder() {
        return new File(this.getDataCacheFolder(), "server_packs");
    }

    default File getBlobCacheFolder() {
        return new File(this.getDataCacheFolder(), "blob_cache");
    }

    default File getJavaPacksCacheFolder() {
        return new File(this.getDataCacheFolder(), "java_packs_cache");
    }

}
