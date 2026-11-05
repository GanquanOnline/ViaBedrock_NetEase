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

import com.viaversion.viaversion.api.configuration.Config;
import net.raphimc.viabedrock.protocol.provider.BlobCacheProvider;
import net.raphimc.viabedrock.protocol.provider.ResourcePackProvider;
import net.raphimc.viabedrock.protocol.provider.impl.*;

import java.util.function.Supplier;

public interface ViaBedrockConfig extends Config {

    /** @return 如果为 true，则启用实验性功能。这些功能几乎可以肯定尚未完全稳定/经过测试，可能导致意外问题 */
    boolean shouldEnableExperimentalFeatures();

    /** @return 如果为 true，Java 剑会获得仅客户端可见的格挡使用动画。需要启用实验性功能。 */
    boolean shouldEnableSwordBlockingAnimation();

    /** @return 如果为 true，则在两个方向上桥接 Java 版自定义载荷与基岩 ScriptMessage。 */
    default boolean shouldBridgeJavaCustomPayloads() {
        return false;
    }

    /** @return 接受的最大 Java 自定义载荷正文大小，单位为字节。 */
    default int getJavaCustomPayloadMaxPayloadBytes() {
        return 8 * 1024;
    }

    /** @return 接受的最大 UTF-8 自定义载荷通道大小，单位为字节。 */
    default int getJavaCustomPayloadMaxChannelBytes() {
        return 128;
    }

    /** @return 每个连接在固定的一分钟窗口内可转发的自定义载荷消息数上限。 */
    default int getJavaCustomPayloadMaxMessagesPerMinute() {
        return 120;
    }

    /** @return 每个连接在固定的一分钟窗口内可转发的自定义载荷字节数上限。 */
    default int getJavaCustomPayloadMaxBytesPerMinute() {
        return 256 * 1024;
    }

    /** @return 要使用的 blob 缓存模式。 */
    BlobCacheMode getBlobCacheMode();

    /** @return 如果为 true，则启动资源包 HTTP 服务器并启用资源包转换 */
    boolean shouldTranslateResourcePacks();

    /** @return 资源包 HTTP 服务器使用的主机。 */
    String getResourcePackHost();

    /** @return 资源包 HTTP 服务器使用的端口。 */
    int getResourcePackPort();

    /** @return 资源包 HTTP 服务器使用的 URL。 */
    String getResourcePackUrl();

    default ResourcePackDeliveryMode getResourcePackDeliveryMode() {
        return ResourcePackDeliveryMode.EMBEDDED;
    }

    default String getRemotePackServiceInternalUrl() {
        return "";
    }

    default String getRemotePackServicePublicUrl() {
        return "";
    }

    default String getRemotePackServiceSecret() {
        return "";
    }

    default int getRemotePackServiceConnectTimeoutMillis() {
        return 2_000;
    }

    default int getRemotePackServiceRequestTimeoutMillis() {
        return 10_000;
    }

    /** @return 要使用的包缓存模式。 */
    PackCacheMode getPackCacheMode();

    default boolean isSharedResourcePackCacheEnabled() {
        return true;
    }

    /**
     * 为将来完全限定作用域的观察缓存预留的兼容性设置。在共享的
     * 资源包缓存启用期间，它绝不会启用旧的 UUID/版本提供器快速路径。
     */
    default boolean shouldTrustDeclaredPackAlias() {
        return false;
    }

    default int getResourcePackCacheMemoryBudgetMiB() {
        return 0;
    }

    default int getResourcePackCacheMemoryHardLimitMiB() {
        return 0;
    }

    default int getResourcePackCacheCpuWorkers() {
        return 0;
    }

    default int getResourcePackCacheIoWorkers() {
        return 4;
    }

    default int getResourcePackCacheQueueCapacity() {
        return 64;
    }

    default int getResourcePackCacheIdleExpireMinutes() {
        return 30;
    }

    default int getResourcePackCacheBuildTimeoutSeconds() {
        return 120;
    }

    default int getResourcePackCacheDiskBudgetMiB() {
        return 20_480;
    }

    default int getResourcePackCacheDiskIdleDays() {
        return 7;
    }

    default int getResourcePackMaxArchiveMiB() {
        return 2_048;
    }

    default int getResourcePackMaxExpandedMiB() {
        return 4_096;
    }

    default int getResourcePackMaxEntryMiB() {
        return 512;
    }

    default int getResourcePackMaxEntries() {
        return 100_000;
    }

    default int getResourcePackMaxCompressionRatio() {
        return 200;
    }

    /** @return 如果为 true，则把基岩版的 showCoordinates 游戏规则转换为 Java 的 reduced debug info 标志 */
    boolean shouldTranslateShowCoordinatesGameRule();

    /** @return 如果为 true，则禁用内部服务器黑名单。这样你可以连接任何服务器，即使该服务器已知会封禁 ViaBedrock 客户端 */
    boolean shouldDisableServerBlacklist();

    /** @return 要发送给基岩服务器的语言代码（例如 "en_us"、"zh_cn"）。空 = "en_us" */
    String getLanguage();

    /**
     * @return 用于 ViaProxy 认证桥的共享密钥。设置后会在皮肤 JWT 中生成
     * HMAC 签名的身份令牌。空 = 禁用
     */
    String getViaProxyAuthSecret();

    /** @return 如果为 true，ViaBedrock 会伪装成网易 / 中国版基岩客户端。 */
    default boolean shouldEmulateNetEaseClient() {
        return false;
    }

    /**
     * @return 写入 REQUEST_NETWORK_SETTINGS 和 LOGIN 的基岩协议版本。
     * 0 表示保留 Java 握手的协议号（上游行为）。
     */
    default int getNetEaseProtocolVersion() {
        return 0;
    }

    /** @return 启用网易仿真时写入皮肤 JWT 的 GameVersion 字符串。 */
    default String getNetEaseGameVersion() {
        return "1.21.124";
    }

    /**
     * @return 连接基岩 / 网易服务器时使用的 RakNet 协议版本。
     * 0 表示保留 ProtocolConstants.BEDROCK_RAKNET_PROTOCOL_VERSION。
     */
    default int getNetEaseRakNetProtocolVersion() {
        return 0;
    }

    /**
     * @return 如果为 true，则在丢弃基岩包未读字节时按包统计并记录日志。
     * 用于升级网易协议时定位读不干净的布局。
     */
    default boolean shouldReportBedrockLayoutLeftovers() {
        return false;
    }

    /**
     * @return 如果为 true，则在未安装 ViaBedrockUtility mod 时启用基于 Display Entity 的服务端实体动画。
     * 禁用时，自定义实体只会显示为不可见的交互碰撞箱（上游行为）。
     * 安装了 ViaBedrockUtility mod 时该设置无效（始终使用客户端渲染）。
     */
    boolean shouldEnableServerEntityAnimation();

    /**
     * @return 登录期间从 Mojang API 获取 Java 版皮肤的毫秒超时时间。
     * 设为 0 可禁用 Java 皮肤获取（始终使用 Steve）。
     */
    int getJavaSkinFetchTimeout();

    /** @return 如果为 true，则在加入服务器时发送自定义的 Java 玩家列表页眉和页脚。 */
    boolean shouldSendTabList();

    /** @return Java 玩家列表页眉模板。 */
    String getTabListHeader();

    /** @return Java 玩家列表页脚模板。 */
    String getTabListFooter();

    /**
     * @return 如果为 true，则在把基岩地图像素（真 ARGB）转换为有限的 Java 地图
     * 调色板时应用 Floyd-Steinberg 抖动。这会用精细噪点换掉生硬的色带，
     * 大幅改善人脸/肖像之类的平滑渐变。需要启用实验性功能。
     */
    boolean shouldDitherMaps();

    boolean isCustomMappingSyncEnabled();

    int getCustomMappingSyncTimeoutMs();

    int getCustomMappingSyncMaxSnapshotBytes();

    int getCustomMappingSyncMaxPayloadBytes();

    int getCustomMappingSyncMaxCustomBlockStates();

    int getCustomMappingSyncMaxCustomBlockEntityTypes();

    int getCustomMappingSyncMaxJavaBlockStateId();

    CustomMappingSyncFailureMode getCustomMappingSyncFailureMode();

    String getCustomMappingSyncDefaultFallbackBlock();

    boolean isCustomMappingSyncChunksEnabled();

    boolean shouldSendCustomMappingSyncResult();

    /**
     * @return 移动看门狗模式。用于在世界/维度/服务器切换后，后端从不发送
     * 解锁移动所需数据包的罕见情况下的失效保护（见 ViaBedrock 移动相关文档）。
     * OFF = 禁用（行为与上游完全一致）；OBSERVE = 仅当锁超过超时仍然卡住时记录日志，
     * 不改变行为（默认，最保守）；ACTIVE = 额外执行保守、幂等的恢复
     * （重发后端遗漏的标准握手收尾）。
     */
    MovementWatchdogMode getMovementWatchdogMode();

    /**
     * @return 维度切换最多可以保持未收尾的刻数（20 刻/秒），超过后看门狗才会动作。默认 200（10 秒），
     * 远高于任何正常握手，因此正常玩家永远不会触发它。
     */
    int getMovementWatchdogDimensionChangeTimeoutTicks();

    /** @return 玩家可以卡在未加载区块区段中的刻数，超过后看门狗会重新请求区块。 */
    int getMovementWatchdogChunkStuckTimeoutTicks();

    /** @return 卡在未加载区块时，重复请求区块半径之间的节流（单位：刻）。 */
    int getMovementWatchdogChunkRadiusRequestIntervalTicks();

    enum MovementWatchdogMode {
        OFF,
        OBSERVE,
        ACTIVE;

        public static MovementWatchdogMode byName(final String name) {
            for (MovementWatchdogMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return OBSERVE;
        }
    }

    enum CustomMappingSyncFailureMode {
        SAFE_FALLBACK,
        KICK;

        public static CustomMappingSyncFailureMode byName(final String name) {
            for (CustomMappingSyncFailureMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }
            return SAFE_FALLBACK;
        }
    }

    enum BlobCacheMode {

        /** blob 缓存将被禁用。 */
        DISABLED(NoOpBlobCacheProvider::new),
        /** blob 缓存将被启用，且 blob 存储在内存中。 */
        MEMORY(InMemoryBlobCacheProvider::new),
        /** blob 缓存将被启用，且 blob 存储在磁盘上。 */
        DISK(DiskBlobCacheProvider::new);

        private final Supplier<BlobCacheProvider> providerSupplier;

        BlobCacheMode(final Supplier<BlobCacheProvider> providerSupplier) {
            this.providerSupplier = providerSupplier;
        }

        public static BlobCacheMode byName(String name) {
            for (BlobCacheMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }

            return DISABLED;
        }

        public BlobCacheProvider createProvider() {
            return this.providerSupplier.get();
        }

    }

    enum PackCacheMode {

        /** 包缓存将被禁用。 */
        DISABLED(NoOpResourcePackProvider::new),
        /** 包缓存将被启用，且包存储在内存中。 */
        MEMORY(InMemoryResourcePackProvider::new),
        /** 包缓存将被启用，且包存储在磁盘上。 */
        DISK(DiskResourcePackProvider::new);

        private final Supplier<ResourcePackProvider> providerSupplier;

        PackCacheMode(final Supplier<ResourcePackProvider> providerSupplier) {
            this.providerSupplier = providerSupplier;
        }

        public static PackCacheMode byName(String name) {
            for (PackCacheMode mode : values()) {
                if (mode.name().equalsIgnoreCase(name)) {
                    return mode;
                }
            }

            return DISABLED;
        }

        public ResourcePackProvider createProvider() {
            return this.providerSupplier.get();
        }

    }

}
