/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResourcePackAnnouncementFingerprintTest {

    @BeforeAll
    static void installViaManager() {
        net.raphimc.viabedrock.test.ViaTestBootstrap.installDefaultManager();
    }

    private static final ResourcePack.Key FIRST_KEY = new ResourcePack.Key(
            UUID.fromString("10203040-5060-7080-90a0-b0c0d0e0f000"), "1.0.0");
    private static final ResourcePack.Key SECOND_KEY = new ResourcePack.Key(
            UUID.fromString("f0e0d0c0-b0a0-9080-7060-504030201000"), "2.0.0");
    private static final ResourcePackLoadStateTracker.AnnouncementHeader HEADER =
            new ResourcePackLoadStateTracker.AnnouncementHeader(
                    true, true, false, true,
                    UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), "1.21.90");

    @Test
    void fingerprintPreservesAnnouncementOrderAndHeader() throws Exception {
        final ResourcePackLoadStateTracker.Info first = info(FIRST_KEY, 100L, "key-a", "content-a",
                "sub-a", false, true, false, "https://cdn.example/first");
        final ResourcePackLoadStateTracker.Info second = info(SECOND_KEY, 200L, "key-b", "content-b",
                "sub-b", true, false, true, "https://cdn.example/second");
        final String baseline = fingerprint(HEADER, first, second);

        assertNotEquals(baseline, fingerprint(HEADER, second, first));
        assertNotEquals(baseline, fingerprint(new ResourcePackLoadStateTracker.AnnouncementHeader(
                false, true, false, true, HEADER.worldTemplateId(), HEADER.worldTemplateVersion()), first, second));
        assertNotEquals(baseline, fingerprint(new ResourcePackLoadStateTracker.AnnouncementHeader(
                true, true, false, true, HEADER.worldTemplateId(), "1.21.91"), first, second));
    }

    @Test
    void fingerprintCoversEveryPerPackDeclarationField() throws Exception {
        final ResourcePackLoadStateTracker.Info baselineInfo = info(FIRST_KEY, 100L, "key-a", "content-a",
                "sub-a", false, true, false, "https://cdn.example/first");
        final String baseline = fingerprint(HEADER, baselineInfo);
        final List<ResourcePackLoadStateTracker.Info> variants = List.of(
                info(SECOND_KEY, 100L, "key-a", "content-a", "sub-a", false, true, false,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 101L, "key-a", "content-a", "sub-a", false, true, false,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 100L, "key-b", "content-a", "sub-a", false, true, false,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 100L, "key-a", "content-b", "sub-a", false, true, false,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 100L, "key-a", "content-a", "sub-b", false, true, false,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 100L, "key-a", "content-a", "sub-a", true, true, false,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 100L, "key-a", "content-a", "sub-a", false, false, false,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 100L, "key-a", "content-a", "sub-a", false, true, true,
                        "https://cdn.example/first"),
                info(FIRST_KEY, 100L, "key-a", "content-a", "sub-a", false, true, false,
                        "https://cdn.example/other"));

        for (ResourcePackLoadStateTracker.Info variant : variants) {
            assertNotEquals(baseline, fingerprint(HEADER, variant));
        }
    }

    @Test
    void trackerAllowsIdenticalDuplicateKeysButRejectsConflictingDeclarations() throws Exception {
        final ResourcePackLoadStateTracker.Info baseline = info(FIRST_KEY, 100L, "key-a", "content-a",
                "sub-a", false, true, false, "https://cdn.example/first");
        final ResourcePackLoadStateTracker.Info identical = info(FIRST_KEY, 100L, "key-a", "content-a",
                "sub-a", false, true, false, "https://cdn.example/first");
        final ResourcePackLoadStateTracker.Info conflict = info(FIRST_KEY, 101L, "key-a", "content-a",
                "sub-a", false, true, false, "https://cdn.example/first");
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            assertDoesNotThrow(() -> new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[]{baseline, identical},
                    HEADER));
            assertThrows(IllegalArgumentException.class, () -> new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[]{baseline, conflict},
                    HEADER));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ResourcePackLoadStateTracker.Info info(
            final ResourcePack.Key key, final long size, final String contentKey, final String contentId,
            final String subpacks, final boolean scripts, final boolean addon, final boolean rayTracing,
            final String cdnUrl) throws Exception {
        return new ResourcePackLoadStateTracker.Info(
                key, size, contentKey.getBytes(StandardCharsets.UTF_8), contentId, subpacks,
                new URL(cdnUrl), cdnUrl, scripts, addon, rayTracing);
    }

    private static String fingerprint(final ResourcePackLoadStateTracker.AnnouncementHeader header,
                                      final ResourcePackLoadStateTracker.Info... infos) {
        return ResourcePackLoadStateTracker.fingerprintAnnouncementSequence(header, infos);
    }

}
