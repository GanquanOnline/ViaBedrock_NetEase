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
package net.raphimc.viabedrock;

import com.viaversion.viaversion.util.Config;
import com.viaversion.viaversion.util.ConfigSection;
import net.raphimc.viabedrock.platform.ResourcePackDeliveryMode;
import net.raphimc.viabedrock.protocol.data.BedrockProtocolProfile;
import net.raphimc.viabedrock.protocol.data.BedrockWire;
import net.raphimc.viabedrock.protocol.data.NetEaseProtocolProfile;
import net.raphimc.viabedrock.protocol.data.NetEaseWire;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;

import java.io.File;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class ViaBedrockConfig extends Config implements net.raphimc.viabedrock.platform.ViaBedrockConfig {

    private static final String DEFAULT_TAB_LIST_HEADER = "%level_name%\n";
    private static final String DEFAULT_TAB_LIST_FOOTER = "\u00A7aViaBedrock \u00A73v%version%\n\u00A77https://github.com/RaphiMC/ViaBedrock";

    private boolean enableExperimentalFeatures;
    private boolean enableSwordBlockingAnimation;
    private boolean bridgeJavaCustomPayloads;
    private int javaCustomPayloadMaxPayloadBytes;
    private int javaCustomPayloadMaxChannelBytes;
    private int javaCustomPayloadMaxMessagesPerMinute;
    private int javaCustomPayloadMaxBytesPerMinute;
    private BlobCacheMode blobCacheMode;
    private boolean translateResourcePacks;
    private String resourcePackHost;
    private int resourcePackPort;
    private String resourcePackUrl;
    private ResourcePackDeliveryMode resourcePackDeliveryMode;
    private String remotePackServiceInternalUrl;
    private String remotePackServicePublicUrl;
    private String remotePackServiceSecret;
    private int remotePackServiceConnectTimeoutMillis;
    private int remotePackServiceRequestTimeoutMillis;
    private PackCacheMode packCacheMode;
    private boolean sharedResourcePackCacheEnabled;
    private boolean trustDeclaredPackAlias;
    private int resourcePackCacheMemoryBudgetMiB;
    private int resourcePackCacheMemoryHardLimitMiB;
    private int resourcePackCacheCpuWorkers;
    private int resourcePackCacheIoWorkers;
    private int resourcePackCacheQueueCapacity;
    private int resourcePackCacheIdleExpireMinutes;
    private int resourcePackCacheBuildTimeoutSeconds;
    private int resourcePackCacheDiskBudgetMiB;
    private int resourcePackCacheDiskIdleDays;
    private int resourcePackMaxArchiveMiB;
    private int resourcePackMaxExpandedMiB;
    private int resourcePackMaxEntryMiB;
    private int resourcePackMaxEntries;
    private int resourcePackMaxCompressionRatio;
    private boolean translateShowCoordinatesGameRule;
    private boolean disableServerBlacklist;
    private String language;
    private String viaProxyAuthSecret;
    private int bedrockProtocolVersion = ProtocolConstants.DEFAULT_BEDROCK_PROTOCOL_VERSION;
    private boolean emulateNetEaseClient;
    private int netEaseProtocolVersion;
    private String netEaseGameVersion;
    private int netEaseRakNetProtocolVersion;
    private boolean reportBedrockLayoutLeftovers;
    private boolean enableServerEntityAnimation;
    private int javaSkinFetchTimeout;
    private boolean sendTabList;
    private String tabListHeader;
    private String tabListFooter;
    private boolean ditherMaps;
    private boolean customMappingSyncEnabled;
    private int customMappingSyncTimeoutMs;
    private int customMappingSyncMaxSnapshotBytes;
    private int customMappingSyncMaxPayloadBytes;
    private int customMappingSyncMaxCustomBlockStates;
    private int customMappingSyncMaxCustomBlockEntityTypes;
    private int customMappingSyncMaxJavaBlockStateId;
    private CustomMappingSyncFailureMode customMappingSyncFailureMode;
    private String customMappingSyncDefaultFallbackBlock;
    private boolean customMappingSyncEnableChunks;
    private boolean customMappingSyncSendSyncResult;
    private MovementWatchdogMode movementWatchdogMode;
    private int movementWatchdogDimensionChangeTimeoutTicks;
    private int movementWatchdogChunkStuckTimeoutTicks;
    private int movementWatchdogChunkRadiusRequestIntervalTicks;

    public ViaBedrockConfig(final File configFile, final Logger logger) {
        super(configFile, logger);
    }

    @Override
    public void reload() {
        super.reload();
        this.loadFields();
    }

    private void loadFields() {
        this.enableExperimentalFeatures = this.getBoolean("enable-experimental-features", false);
        this.enableSwordBlockingAnimation = this.getBoolean("enable-sword-blocking-animation", false);
        final ConfigSection javaCustomPayloadBridge = this.getSection("java-custom-payload-bridge");
        this.bridgeJavaCustomPayloads = getBoolean(javaCustomPayloadBridge, "enabled", false);
        this.javaCustomPayloadMaxPayloadBytes = Math.max(1, getInt(javaCustomPayloadBridge, "max-payload-bytes", 8 * 1024));
        this.javaCustomPayloadMaxChannelBytes = Math.max(1, Math.min(128,
                getInt(javaCustomPayloadBridge, "max-channel-bytes", 128)));
        this.javaCustomPayloadMaxMessagesPerMinute = Math.max(1,
                getInt(javaCustomPayloadBridge, "max-messages-per-minute", 120));
        this.javaCustomPayloadMaxBytesPerMinute = Math.max(1,
                getInt(javaCustomPayloadBridge, "max-bytes-per-minute", 256 * 1024));
        this.blobCacheMode = BlobCacheMode.byName(this.getString("blob-cache", "disk"));
        this.translateResourcePacks = this.getBoolean("translate-resource-packs", true);
        this.resourcePackHost = this.getString("resource-pack-host", "127.0.0.1");
        this.resourcePackPort = this.getInt("resource-pack-port", 0);
        this.resourcePackUrl = this.getString("resource-pack-url", "");
        final ConfigSection resourcePackDelivery = this.getSection("resource-pack-delivery");
        this.resourcePackDeliveryMode = ResourcePackDeliveryMode.byName(
                getString(resourcePackDelivery, "mode", "embedded"));
        this.remotePackServiceInternalUrl = getString(resourcePackDelivery, "internal-url", "");
        this.remotePackServicePublicUrl = getString(resourcePackDelivery, "public-url", "");
        this.remotePackServiceSecret = getString(resourcePackDelivery, "shared-secret", "");
        this.remotePackServiceConnectTimeoutMillis = Math.max(1,
                getInt(resourcePackDelivery, "connect-timeout-millis", 2_000));
        this.remotePackServiceRequestTimeoutMillis = Math.max(1,
                getInt(resourcePackDelivery, "request-timeout-millis", 10_000));
        this.packCacheMode = PackCacheMode.byName(this.getString("pack-cache", "disk"));
        final ConfigSection resourcePackCache = this.getSection("resource-pack-cache");
        this.sharedResourcePackCacheEnabled = getBoolean(resourcePackCache, "enabled", true);
        this.trustDeclaredPackAlias = getBoolean(resourcePackCache, "trust-declared-pack-alias", false);
        this.resourcePackCacheMemoryBudgetMiB = Math.max(0, getInt(resourcePackCache, "memory-budget-mib", 0));
        this.resourcePackCacheMemoryHardLimitMiB = Math.max(0, getInt(resourcePackCache, "memory-hard-limit-mib", 0));
        this.resourcePackCacheCpuWorkers = Math.max(0, getInt(resourcePackCache, "cpu-workers", 0));
        this.resourcePackCacheIoWorkers = Math.max(1, getInt(resourcePackCache, "io-workers", 4));
        this.resourcePackCacheQueueCapacity = Math.max(1, getInt(resourcePackCache, "queue-capacity", 64));
        this.resourcePackCacheIdleExpireMinutes = Math.max(1, getInt(resourcePackCache, "idle-expire-minutes", 30));
        this.resourcePackCacheBuildTimeoutSeconds = Math.max(1, getInt(resourcePackCache, "build-timeout-seconds", 120));
        this.resourcePackCacheDiskBudgetMiB = Math.max(2, getInt(resourcePackCache, "disk-budget-mib", 20_480));
        this.resourcePackCacheDiskIdleDays = Math.max(1, getInt(resourcePackCache, "disk-idle-days", 7));
        this.resourcePackMaxArchiveMiB = Math.max(1, getInt(resourcePackCache, "max-archive-mib", 2_048));
        this.resourcePackMaxExpandedMiB = Math.max(1, getInt(resourcePackCache, "max-expanded-mib", 4_096));
        this.resourcePackMaxEntryMiB = Math.max(1, getInt(resourcePackCache, "max-entry-mib", 512));
        this.resourcePackMaxEntries = Math.max(1, getInt(resourcePackCache, "max-entries", 100_000));
        this.resourcePackMaxCompressionRatio = Math.max(1, getInt(resourcePackCache, "max-compression-ratio", 200));
        this.translateShowCoordinatesGameRule = this.getBoolean("translate-show-coordinates-game-rule", false);
        this.disableServerBlacklist = this.getBoolean("disable-server-blacklist", false);
        this.language = this.getString("language", "");
        this.viaProxyAuthSecret = this.getString("viaproxy-auth-secret", "");
        final ConfigSection bedrock = this.getSection("bedrock");
        final int configuredBedrockProtocol = parseBedrockProtocolVersion(bedrock != null ? bedrock.get("protocol-version") : null);
        if (configuredBedrockProtocol < 0) {
            // 负值不是协议号，继续跑只会拿错布局，直接拒绝启动
            throw new IllegalStateException("bedrock.protocol-version " + configuredBedrockProtocol
                    + " is not a valid protocol number (0 = auto, known profiles: "
                    + BedrockProtocolProfile.supportedList() + ")");
        }
        this.bedrockProtocolVersion = BedrockProtocolProfile.resolveProtocol(configuredBedrockProtocol);
        if (BedrockWire.inferred(this.bedrockProtocolVersion)) {
            this.logger.warning("bedrock.protocol-version " + this.bedrockProtocolVersion
                    + " has no implemented layout data (known profiles: " + BedrockProtocolProfile.supportedList()
                    + "); deriving the layout from deviation ranges and still sending "
                    + this.bedrockProtocolVersion + " in the handshake - the server may reject it");
        }
        this.logger.info("Bedrock wire: " + BedrockWire.describe(this.bedrockProtocolVersion));
        final ConfigSection netease = this.getSection("netease");
        this.emulateNetEaseClient = getBoolean(netease, "enabled", false);
        final int configuredNetEaseProtocol = getInt(netease, "protocol-version", NetEaseProtocolProfile.newest().protocolVersion());
        final String configuredNetEaseGameVersion = getString(netease, "game-version", "");
        final int configuredNetEaseRakNetProtocol = getInt(netease, "raknet-protocol-version", NetEaseProtocolProfile.NETEASE_RAKNET_PROTOCOL_VERSION);
        this.reportBedrockLayoutLeftovers = this.getBoolean("layout-report", false);
        if (this.emulateNetEaseClient) {
            final NetEaseProtocolProfile profile = NetEaseProtocolProfile.resolve(configuredNetEaseProtocol);
            final boolean knownProtocol = NetEaseProtocolProfile.isSelectable(configuredNetEaseProtocol);
            if (!knownProtocol && configuredNetEaseProtocol > 0) {
                // 保留未知协议号：偏差区间能给出确定布局，退回最近档案只会静默用错布局
                this.logger.warning("netease.protocol-version " + configuredNetEaseProtocol
                        + " is not a known NetEase profile (" + NetEaseProtocolProfile.supportedList()
                        + "); deriving the layout from deviation ranges");
            } else if (configuredNetEaseProtocol < 0) {
                this.logger.warning("netease.protocol-version " + configuredNetEaseProtocol
                        + " is not a valid protocol number; using " + NetEaseProtocolProfile.newest().protocolVersion());
            }
            if (!configuredNetEaseGameVersion.isEmpty() && !profile.gameVersion().equals(configuredNetEaseGameVersion)) {
                this.logger.warning("netease.game-version " + configuredNetEaseGameVersion
                        + " does not belong to protocol " + profile.protocolVersion()
                        + "; using " + profile.gameVersion());
            }
            this.netEaseProtocolVersion = !knownProtocol && configuredNetEaseProtocol > 0
                    ? configuredNetEaseProtocol : profile.protocolVersion();
            this.netEaseGameVersion = profile.gameVersion();
            this.netEaseRakNetProtocolVersion = NetEaseProtocolProfile.resolveRakNetProtocolVersion(configuredNetEaseRakNetProtocol);
            this.logger.info("Emulating NetEase client " + profile.gameVersion()
                    + " (protocol " + this.netEaseProtocolVersion
                    + ", RakNet " + this.netEaseRakNetProtocolVersion + ")");
            this.logger.info("NetEase wire: " + NetEaseWire.of(true, this.netEaseProtocolVersion).describe());
        } else {
            this.netEaseProtocolVersion = configuredNetEaseProtocol;
            this.netEaseGameVersion = configuredNetEaseGameVersion.isEmpty()
                    ? NetEaseProtocolProfile.newest().gameVersion() : configuredNetEaseGameVersion;
            this.netEaseRakNetProtocolVersion = NetEaseProtocolProfile.resolveRakNetProtocolVersion(configuredNetEaseRakNetProtocol);
        }
        this.enableServerEntityAnimation = this.getBoolean("enable-server-entity-animation", true);
        this.javaSkinFetchTimeout = this.getInt("java-skin-fetch-timeout", 1000);
        final ConfigSection tabList = this.getSection("tab-list");
        this.sendTabList = getBoolean(tabList, "enabled", true);
        this.tabListHeader = getString(tabList, "header", DEFAULT_TAB_LIST_HEADER);
        this.tabListFooter = getString(tabList, "footer", DEFAULT_TAB_LIST_FOOTER);
        this.ditherMaps = this.getBoolean("dither-maps", true);
        final ConfigSection customMappingSync = this.getSection("customMappingSync");
        this.customMappingSyncEnabled = getBoolean(customMappingSync, "enabled", true);
        this.customMappingSyncTimeoutMs = getInt(customMappingSync, "timeoutMs", 10000);
        this.customMappingSyncMaxSnapshotBytes = getInt(customMappingSync, "maxSnapshotBytes", 16 * 1024 * 1024);
        this.customMappingSyncMaxPayloadBytes = getInt(customMappingSync, "maxPayloadBytes", 28672);
        this.customMappingSyncMaxCustomBlockStates = getInt(customMappingSync, "maxCustomBlockStates", 65536);
        this.customMappingSyncMaxCustomBlockEntityTypes = getInt(customMappingSync, "maxCustomBlockEntityTypes", 4096);
        this.customMappingSyncMaxJavaBlockStateId = getInt(customMappingSync, "maxJavaBlockStateId", 1048575);
        this.customMappingSyncFailureMode = CustomMappingSyncFailureMode.byName(getString(customMappingSync, "failureMode", "SAFE_FALLBACK"));
        this.customMappingSyncDefaultFallbackBlock = getString(customMappingSync, "defaultFallbackBlock", "minecraft:stone");
        this.customMappingSyncEnableChunks = getBoolean(customMappingSync, "enableChunks", true);
        this.customMappingSyncSendSyncResult = getBoolean(customMappingSync, "sendSyncResult", false);
        final ConfigSection movementWatchdog = this.getSection("movementWatchdog");
        this.movementWatchdogMode = MovementWatchdogMode.byName(getString(movementWatchdog, "mode", "observe"));
        this.movementWatchdogDimensionChangeTimeoutTicks = getInt(movementWatchdog, "dimensionChangeTimeoutTicks", 200);
        this.movementWatchdogChunkStuckTimeoutTicks = getInt(movementWatchdog, "chunkStuckTimeoutTicks", 200);
        this.movementWatchdogChunkRadiusRequestIntervalTicks = getInt(movementWatchdog, "chunkRadiusRequestIntervalTicks", 100);
    }

    private static boolean getBoolean(final ConfigSection section, final String key, final boolean def) {
        return section != null ? section.getBoolean(key, def) : def;
    }

    private static int getInt(final ConfigSection section, final String key, final int def) {
        return section != null ? section.getInt(key, def) : def;
    }

    private static String getString(final ConfigSection section, final String key, final String def) {
        return section != null ? section.getString(key, def) : def;
    }

    /** 非整数的 YAML 值会被 ConfigSection.getInt 静默替换成默认值，这里显式拒绝，避免配错却按 975 跑。 */
    private static int parseBedrockProtocolVersion(final Object rawValue) {
        if (rawValue == null) {
            return ProtocolConstants.DEFAULT_BEDROCK_PROTOCOL_VERSION;
        }
        if (rawValue instanceof Number number && number.doubleValue() == number.longValue()
                && number.longValue() >= Integer.MIN_VALUE && number.longValue() <= Integer.MAX_VALUE) {
            return (int) number.longValue();
        }
        throw new IllegalStateException("bedrock.protocol-version " + rawValue
                + " is not an integer protocol number (0 = auto, known profiles: "
                + BedrockProtocolProfile.supportedList() + ")");
    }

    @Override
    public URL getDefaultConfigURL() {
        return this.getClass().getClassLoader().getResource("assets/viabedrock/viabedrock.yml");
    }

    @Override
    protected void handleConfig(Map<String, Object> map) {
    }

    @Override
    public boolean shouldEnableExperimentalFeatures() {
        return this.enableExperimentalFeatures;
    }

    @Override
    public boolean shouldEnableSwordBlockingAnimation() {
        return this.enableSwordBlockingAnimation;
    }

    @Override
    public boolean shouldBridgeJavaCustomPayloads() {
        return this.bridgeJavaCustomPayloads;
    }

    @Override
    public int getJavaCustomPayloadMaxPayloadBytes() {
        return this.javaCustomPayloadMaxPayloadBytes;
    }

    @Override
    public int getJavaCustomPayloadMaxChannelBytes() {
        return this.javaCustomPayloadMaxChannelBytes;
    }

    @Override
    public int getJavaCustomPayloadMaxMessagesPerMinute() {
        return this.javaCustomPayloadMaxMessagesPerMinute;
    }

    @Override
    public int getJavaCustomPayloadMaxBytesPerMinute() {
        return this.javaCustomPayloadMaxBytesPerMinute;
    }

    @Override
    public List<String> getUnsupportedOptions() {
        return Collections.emptyList();
    }

    @Override
    public BlobCacheMode getBlobCacheMode() {
        return this.blobCacheMode;
    }

    @Override
    public boolean shouldTranslateResourcePacks() {
        return this.translateResourcePacks;
    }

    @Override
    public String getResourcePackHost() {
        return this.resourcePackHost;
    }

    @Override
    public int getResourcePackPort() {
        return this.resourcePackPort;
    }

    @Override
    public String getResourcePackUrl() {
        return this.resourcePackUrl;
    }

    @Override
    public ResourcePackDeliveryMode getResourcePackDeliveryMode() {
        return this.resourcePackDeliveryMode;
    }

    @Override
    public String getRemotePackServiceInternalUrl() {
        return this.remotePackServiceInternalUrl;
    }

    @Override
    public String getRemotePackServicePublicUrl() {
        return this.remotePackServicePublicUrl;
    }

    @Override
    public String getRemotePackServiceSecret() {
        return this.remotePackServiceSecret;
    }

    @Override
    public int getRemotePackServiceConnectTimeoutMillis() {
        return this.remotePackServiceConnectTimeoutMillis;
    }

    @Override
    public int getRemotePackServiceRequestTimeoutMillis() {
        return this.remotePackServiceRequestTimeoutMillis;
    }

    @Override
    public PackCacheMode getPackCacheMode() {
        return this.packCacheMode;
    }

    @Override
    public boolean isSharedResourcePackCacheEnabled() {
        return this.sharedResourcePackCacheEnabled;
    }

    @Override
    public boolean shouldTrustDeclaredPackAlias() {
        return this.trustDeclaredPackAlias;
    }

    @Override
    public int getResourcePackCacheMemoryBudgetMiB() {
        return this.resourcePackCacheMemoryBudgetMiB;
    }

    @Override
    public int getResourcePackCacheMemoryHardLimitMiB() {
        return this.resourcePackCacheMemoryHardLimitMiB;
    }

    @Override
    public int getResourcePackCacheCpuWorkers() {
        return this.resourcePackCacheCpuWorkers;
    }

    @Override
    public int getResourcePackCacheIoWorkers() {
        return this.resourcePackCacheIoWorkers;
    }

    @Override
    public int getResourcePackCacheQueueCapacity() {
        return this.resourcePackCacheQueueCapacity;
    }

    @Override
    public int getResourcePackCacheIdleExpireMinutes() {
        return this.resourcePackCacheIdleExpireMinutes;
    }

    @Override
    public int getResourcePackCacheBuildTimeoutSeconds() {
        return this.resourcePackCacheBuildTimeoutSeconds;
    }

    @Override
    public int getResourcePackCacheDiskBudgetMiB() {
        return this.resourcePackCacheDiskBudgetMiB;
    }

    @Override
    public int getResourcePackCacheDiskIdleDays() {
        return this.resourcePackCacheDiskIdleDays;
    }

    @Override
    public int getResourcePackMaxArchiveMiB() {
        return this.resourcePackMaxArchiveMiB;
    }

    @Override
    public int getResourcePackMaxExpandedMiB() {
        return this.resourcePackMaxExpandedMiB;
    }

    @Override
    public int getResourcePackMaxEntryMiB() {
        return this.resourcePackMaxEntryMiB;
    }

    @Override
    public int getResourcePackMaxEntries() {
        return this.resourcePackMaxEntries;
    }

    @Override
    public int getResourcePackMaxCompressionRatio() {
        return this.resourcePackMaxCompressionRatio;
    }

    @Override
    public boolean shouldTranslateShowCoordinatesGameRule() {
        return this.translateShowCoordinatesGameRule;
    }

    @Override
    public boolean shouldDisableServerBlacklist() {
        return this.disableServerBlacklist;
    }

    @Override
    public String getLanguage() {
        return this.language;
    }

    @Override
    public String getViaProxyAuthSecret() {
        return this.viaProxyAuthSecret;
    }

    @Override
    public boolean shouldEmulateNetEaseClient() {
        return this.emulateNetEaseClient;
    }

    @Override
    public int getBedrockProtocolVersion() {
        return this.bedrockProtocolVersion;
    }

    @Override
    public int getNetEaseProtocolVersion() {
        return this.netEaseProtocolVersion;
    }

    @Override
    public String getNetEaseGameVersion() {
        return this.netEaseGameVersion;
    }

    @Override
    public int getNetEaseRakNetProtocolVersion() {
        return this.netEaseRakNetProtocolVersion;
    }

    @Override
    public boolean shouldReportBedrockLayoutLeftovers() {
        return this.reportBedrockLayoutLeftovers;
    }

    @Override
    public boolean shouldEnableServerEntityAnimation() {
        return this.enableServerEntityAnimation;
    }

    @Override
    public int getJavaSkinFetchTimeout() {
        return this.javaSkinFetchTimeout;
    }

    @Override
    public boolean shouldSendTabList() {
        return this.sendTabList;
    }

    @Override
    public String getTabListHeader() {
        return this.tabListHeader;
    }

    @Override
    public String getTabListFooter() {
        return this.tabListFooter;
    }

    @Override
    public boolean shouldDitherMaps() {
        return this.ditherMaps;
    }

    @Override
    public boolean isCustomMappingSyncEnabled() {
        return this.customMappingSyncEnabled;
    }

    @Override
    public int getCustomMappingSyncTimeoutMs() {
        return this.customMappingSyncTimeoutMs;
    }

    @Override
    public int getCustomMappingSyncMaxSnapshotBytes() {
        return this.customMappingSyncMaxSnapshotBytes;
    }

    @Override
    public int getCustomMappingSyncMaxPayloadBytes() {
        return this.customMappingSyncMaxPayloadBytes;
    }

    @Override
    public int getCustomMappingSyncMaxCustomBlockStates() {
        return this.customMappingSyncMaxCustomBlockStates;
    }

    @Override
    public int getCustomMappingSyncMaxCustomBlockEntityTypes() {
        return this.customMappingSyncMaxCustomBlockEntityTypes;
    }

    @Override
    public int getCustomMappingSyncMaxJavaBlockStateId() {
        return this.customMappingSyncMaxJavaBlockStateId;
    }

    @Override
    public CustomMappingSyncFailureMode getCustomMappingSyncFailureMode() {
        return this.customMappingSyncFailureMode;
    }

    @Override
    public String getCustomMappingSyncDefaultFallbackBlock() {
        return this.customMappingSyncDefaultFallbackBlock;
    }

    @Override
    public boolean isCustomMappingSyncChunksEnabled() {
        return this.customMappingSyncEnableChunks;
    }

    @Override
    public boolean shouldSendCustomMappingSyncResult() {
        return this.customMappingSyncSendSyncResult;
    }

    @Override
    public MovementWatchdogMode getMovementWatchdogMode() {
        return this.movementWatchdogMode;
    }

    @Override
    public int getMovementWatchdogDimensionChangeTimeoutTicks() {
        return this.movementWatchdogDimensionChangeTimeoutTicks;
    }

    @Override
    public int getMovementWatchdogChunkStuckTimeoutTicks() {
        return this.movementWatchdogChunkStuckTimeoutTicks;
    }

    @Override
    public int getMovementWatchdogChunkRadiusRequestIntervalTicks() {
        return this.movementWatchdogChunkRadiusRequestIntervalTicks;
    }

}
