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
package net.raphimc.viabedrock.experimental.inventory;

import com.viaversion.viaversion.api.minecraft.item.StructuredItem;
import net.raphimc.viabedrock.test.StubUserConnection;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.experimental.storage.CreativeContentCache;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerEnumName;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeSlotSemanticsTest {

    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final StubUserConnection user = new StubUserConnection(this.channel);
    private final InventoryTracker tracker = new InventoryTracker(this.user);

    @AfterEach
    void closeChannel() {
        this.channel.finishAndReleaseAll();
    }

    @Test
    void javaCursorMinusOneMapsToHudCursor() {
        assertTrue(CreativeSlotSemantics.isJavaCursorSlot(-1));
        assertEquals(ContainerEnumName.CursorContainer, CreativeSlotSemantics.destinationSlot(-1, this.tracker).container());
        assertEquals(0, CreativeSlotSemantics.destinationSlot(-1, this.tracker).slot());
        assertEquals(ContainerEnumName.HotbarContainer, CreativeSlotSemantics.destinationSlot(36, this.tracker).container());
        assertEquals(0, CreativeSlotSemantics.destinationSlot(36, this.tracker).slot());
        assertEquals(ContainerEnumName.ArmorContainer, CreativeSlotSemantics.destinationSlot(5, this.tracker).container());
        assertEquals(ContainerEnumName.OffhandContainer, CreativeSlotSemantics.destinationSlot(45, this.tracker).container());
    }

    @Test
    void emptyDestroyNeedsAnExistingStack() {
        assertTrue(CreativeSlotSemantics.plan(-1, StructuredItem.empty(), this.tracker, null, new CreativeContentCache(this.user)).isEmpty());
        this.tracker.getHudContainer().setItemSilent(0, new BedrockItem(35, (short) 0, (byte) 16));
        final CreativeSlotSemantics.Plan destroy = CreativeSlotSemantics.plan(-1, StructuredItem.empty(), this.tracker, null, new CreativeContentCache(this.user));
        assertEquals(CreativeSlotSemantics.Kind.DESTROY, destroy.kind());
        assertEquals(16, destroy.count());
        assertEquals(ContainerEnumName.CursorContainer, destroy.destination().container());
    }

    @Test
    void emptyingHotbarDestroysTheAssignedSlot() {
        this.tracker.getInventoryContainer().setItemSilent(0, new BedrockItem(35, (short) 0, (byte) 16));
        final CreativeSlotSemantics.Plan destroy = CreativeSlotSemantics.plan(36, StructuredItem.empty(), this.tracker, null, new CreativeContentCache(this.user));
        assertEquals(CreativeSlotSemantics.Kind.DESTROY, destroy.kind());
        assertEquals(16, destroy.count());
        assertEquals(ContainerEnumName.HotbarContainer, destroy.destination().container());
        assertEquals(0, destroy.destination().slot());
        assertTrue(destroy.predicted().isEmpty());
    }

    @Test
    void emptyingBackpackDestroysTheAssignedSlot() {
        this.tracker.getInventoryContainer().setItemSilent(9, new BedrockItem(35, (short) 0, (byte) 8));
        final CreativeSlotSemantics.Plan destroy = CreativeSlotSemantics.plan(9, StructuredItem.empty(), this.tracker, null, new CreativeContentCache(this.user));
        assertEquals(CreativeSlotSemantics.Kind.DESTROY, destroy.kind());
        assertEquals(8, destroy.count());
        assertEquals(ContainerEnumName.InventoryContainer, destroy.destination().container());
        assertEquals(9, destroy.destination().slot());
    }

    @Test
    void spawnNeedsACreativeCache() {
        final StructuredItem wool = new StructuredItem(35, 64);
        assertTrue(CreativeSlotSemantics.plan(-1, wool, this.tracker, null, null).isUnsupported());
        assertTrue(CreativeSlotSemantics.plan(-1, wool, this.tracker, null, new CreativeContentCache(this.user)).isUnsupported());
    }
}
