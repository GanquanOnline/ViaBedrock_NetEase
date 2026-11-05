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

import com.viaversion.viaversion.api.minecraft.BlockPosition;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.model.container.ChestContainer;
import net.raphimc.viabedrock.api.model.container.EnderChestContainer;
import net.raphimc.viabedrock.api.model.container.GenericContainer;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerType;
import net.raphimc.viabedrock.protocol.packet.JavaContainerTitles;
import net.raphimc.viabedrock.test.StubUserConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTrackerDummyContainerTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private InventoryTracker tracker;

    @BeforeEach
    void setUp() {
        this.tracker = new InventoryTracker(this.user);
        this.user.put(this.tracker);
    }

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void dummyGenericChestStaysOpenWithoutWorldBlock() {
        final GenericContainer fake = new GenericContainer(this.user, (byte) 12, ContainerType.CONTAINER, null, new BlockPosition(0, 0, 0), 27);
        this.tracker.setCurrentContainer(fake);
        assertTrue(fake.isValidBlockTag("air"));
        assertTrue(fake.isValidBlockTag(null));
        assertTrue(!fake.isWorldBacked());
        this.tracker.tick();
        assertSame(fake, this.tracker.getCurrentContainer());
        assertEquals(InventoryTracker.ContainerState.OPEN, this.tracker.getContainerState());
    }

    @Test
    void dummyWorldOriginDoesNotInheritSpawnEnderChestTitleOrType() {
        assertEquals("container.chest", JavaContainerTitles.key(null, ContainerType.CONTAINER));
        assertEquals("container.chest", JavaContainerTitles.key("air", ContainerType.CONTAINER));
        assertFalse("container.enderchest".equals(JavaContainerTitles.key(null, ContainerType.CONTAINER)));
        assertFalse("container.shulkerBox".equals(JavaContainerTitles.key("air", ContainerType.CONTAINER)));
    }

    @Test
    void dummyChestAtWorldOriginStaysOpenEvenIfWorldBacked() {
        // 修复前的路径：在 (0,0,0) 生成箱子会让插件 GUI 变成由世界方块支撑，
        // 于是 tick() 会因 6 格距离校验而关闭它们。
        final ChestContainer spawnTagged = new ChestContainer(this.user, (byte) 12, null, new BlockPosition(0, 0, 0), 27);
        this.tracker.setCurrentContainer(spawnTagged);
        assertTrue(spawnTagged.isWorldBacked());
        this.tracker.tick();
        assertSame(spawnTagged, this.tracker.getCurrentContainer());
        assertEquals(InventoryTracker.ContainerState.OPEN, this.tracker.getContainerState());
    }

    @Test
    void dummyEnderChestAtWorldOriginStaysOpenEvenIfWorldBacked() {
        final EnderChestContainer spawnEnder = new EnderChestContainer(this.user, (byte) 13, null, new BlockPosition(0, 0, 0));
        this.tracker.setCurrentContainer(spawnEnder);
        assertTrue(spawnEnder.isWorldBacked());
        this.tracker.tick();
        assertSame(spawnEnder, this.tracker.getCurrentContainer());
        assertEquals(InventoryTracker.ContainerState.OPEN, this.tracker.getContainerState());
    }
}
