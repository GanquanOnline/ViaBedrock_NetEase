/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock;

import net.raphimc.viabedrock.platform.ResourcePackDeliveryMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViaBedrockConfigTest {

    @Test
    void normalizesNonPositiveResourcePackCacheLimits(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                resource-pack-cache:
                  memory-budget-mib: -1
                  memory-hard-limit-mib: -1
                  cpu-workers: -1
                  io-workers: -1
                  queue-capacity: -1
                  idle-expire-minutes: -1
                  build-timeout-seconds: -1
                  disk-budget-mib: -1
                  disk-idle-days: -1
                  max-archive-mib: -1
                  max-expanded-mib: -1
                  max-entry-mib: -1
                  max-entries: -1
                  max-compression-ratio: -1
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertEquals(0, config.getResourcePackCacheMemoryBudgetMiB());
        assertEquals(0, config.getResourcePackCacheMemoryHardLimitMiB());
        assertEquals(0, config.getResourcePackCacheCpuWorkers());
        assertEquals(1, config.getResourcePackCacheIoWorkers());
        assertEquals(1, config.getResourcePackCacheQueueCapacity());
        assertEquals(1, config.getResourcePackCacheIdleExpireMinutes());
        assertEquals(1, config.getResourcePackCacheBuildTimeoutSeconds());
        assertEquals(2, config.getResourcePackCacheDiskBudgetMiB());
        assertEquals(1, config.getResourcePackCacheDiskIdleDays());
        assertEquals(1, config.getResourcePackMaxArchiveMiB());
        assertEquals(1, config.getResourcePackMaxExpandedMiB());
        assertEquals(1, config.getResourcePackMaxEntryMiB());
        assertEquals(1, config.getResourcePackMaxEntries());
        assertEquals(1, config.getResourcePackMaxCompressionRatio());
        assertEquals(ResourcePackDeliveryMode.EMBEDDED, config.getResourcePackDeliveryMode());
    }

    @Test
    void loadsRemoteResourcePackDelivery(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                resource-pack-delivery:
                  mode: remote
                  internal-url: http://viabedrock-pack-service:8081/
                  public-url: http://je-res-test.easecation.net/
                  shared-secret: test-secret
                  connect-timeout-millis: 1500
                  request-timeout-millis: 9000
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertEquals(ResourcePackDeliveryMode.REMOTE, config.getResourcePackDeliveryMode());
        assertEquals("http://viabedrock-pack-service:8081/", config.getRemotePackServiceInternalUrl());
        assertEquals("http://je-res-test.easecation.net/", config.getRemotePackServicePublicUrl());
        assertEquals("test-secret", config.getRemotePackServiceSecret());
        assertEquals(1500, config.getRemotePackServiceConnectTimeoutMillis());
        assertEquals(9000, config.getRemotePackServiceRequestTimeoutMillis());
    }

    @Test
    void neteaseGameVersionDefaultsToMotNetEaseEnumName(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                netease:
                  enabled: true
                  protocol-version: 860
                  raknet-protocol-version: 8
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertTrue(config.shouldEmulateNetEaseClient());
        assertEquals(860, config.getNetEaseProtocolVersion());
        assertEquals(8, config.getNetEaseRakNetProtocolVersion());
        assertEquals("1.21.124_NetEase", config.getNetEaseGameVersion());
    }

    @Test
    void neteaseDefaultsToModpc310ProtocolTuple(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                netease:
                  enabled: true
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertTrue(config.shouldEmulateNetEaseClient());
        assertEquals(898, config.getNetEaseProtocolVersion());
        assertEquals(8, config.getNetEaseRakNetProtocolVersion());
        assertEquals("1.21.130_NetEase", config.getNetEaseGameVersion());
    }

    @Test
    void neteaseAutoProtocolResolvesToNewestProfile(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                netease:
                  enabled: true
                  protocol-version: 0
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertEquals(898, config.getNetEaseProtocolVersion());
        assertEquals("1.21.130_NetEase", config.getNetEaseGameVersion());
    }

    @Test
    void neteaseAcceptsBothMotProfiles(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                netease:
                  enabled: true
                  protocol-version: 898
                  game-version: 1.21.124_NetEase
                  raknet-protocol-version: 11
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertEquals(898, config.getNetEaseProtocolVersion());
        assertEquals("1.21.130_NetEase", config.getNetEaseGameVersion());
        assertEquals(11, config.getNetEaseRakNetProtocolVersion());
    }

    @Test
    void unsupportedNetEaseProtocolFallsBackToNewestProfile(@TempDir final Path tempDir) throws Exception {
        final Path configFile = tempDir.resolve("viabedrock.yml");
        Files.writeString(configFile, """
                netease:
                  enabled: true
                  protocol-version: 999
                  game-version: 1.21.0
                  raknet-protocol-version: 10
                """);

        final ViaBedrockConfig config = new ViaBedrockConfig(configFile.toFile(), Logger.getAnonymousLogger());
        config.reload();

        assertTrue(config.shouldEmulateNetEaseClient());
        assertEquals(898, config.getNetEaseProtocolVersion());
        assertEquals(8, config.getNetEaseRakNetProtocolVersion());
        assertEquals("1.21.130_NetEase", config.getNetEaseGameVersion());
    }

}
