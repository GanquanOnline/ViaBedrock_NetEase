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
package net.raphimc.viabedrock.protocol.packet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.model.SkinData;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeteasePacketLayoutTest {

    @Test
    void registersNeteaseTitlePacketIds() {
        assertEquals(200, ClientboundBedrockPackets.PY_RPC.getId());
        assertEquals(203, ClientboundBedrockPackets.NETEASE_JSON.getId());
        assertEquals(228, ClientboundBedrockPackets.CONFIRM_SKIN.getId());
        assertEquals(236, ClientboundBedrockPackets.SYNC_SKIN.getId());
        assertEquals(ClientboundBedrockPackets.NETEASE_JSON, ClientboundBedrockPackets.getPacket(0xCB));
        assertEquals(ClientboundBedrockPackets.CONFIRM_SKIN, ClientboundBedrockPackets.getPacket(0xE4));
        assertEquals(ClientboundBedrockPackets.SYNC_SKIN, ClientboundBedrockPackets.getPacket(0xEC));
        assertEquals(163, ClientboundBedrockPackets.FILTER_TEXT.getId());
        assertEquals(163, ServerboundBedrockPackets.FILTER_TEXT.getId());
        assertEquals(176, ClientboundBedrockPackets.PLAYER_START_ITEM_COOLDOWN.getId());
        assertEquals(186, ClientboundBedrockPackets.TOAST_REQUEST.getId());
        assertEquals(ClientboundBedrockPackets.PLAYER_START_ITEM_COOLDOWN, ClientboundBedrockPackets.getPacket(176));
        assertEquals(ClientboundBedrockPackets.TOAST_REQUEST, ClientboundBedrockPackets.getPacket(186));
        assertEquals(173, ClientboundBedrockPackets.PHOTO_INFO_REQUEST.getId());
        assertEquals(197, ClientboundBedrockPackets.CLIENT_CHEAT_ABILITY.getId());
        assertEquals(301, ClientboundBedrockPackets.COMPRESSED_BIOME_DEFINITION_LIST.getId());
        assertEquals(319, ClientboundBedrockPackets.SET_MOVEMENT_AUTHORITY.getId());
        assertEquals(ClientboundBedrockPackets.FILTER_TEXT, ClientboundBedrockPackets.getPacket(163));
        assertEquals(ClientboundBedrockPackets.PHOTO_INFO_REQUEST, ClientboundBedrockPackets.getPacket(173));
        assertEquals(ClientboundBedrockPackets.CLIENT_CHEAT_ABILITY, ClientboundBedrockPackets.getPacket(197));
        assertEquals(ClientboundBedrockPackets.COMPRESSED_BIOME_DEFINITION_LIST, ClientboundBedrockPackets.getPacket(301));
        assertEquals(ClientboundBedrockPackets.SET_MOVEMENT_AUTHORITY, ClientboundBedrockPackets.getPacket(319));
        assertEquals(ServerboundBedrockPackets.FILTER_TEXT, ServerboundBedrockPackets.getPacket(163));
        assertEquals(342, ClientboundBedrockPackets.PARTY_CHANGED.getId());
        assertEquals(348, ClientboundBedrockPackets.UPDATE_SOUND_DATA.getId());
        assertEquals(349, ClientboundBedrockPackets.SEND_PARTY_DESTINATION_COOKIE.getId());
        assertEquals(350, ClientboundBedrockPackets.PARTY_DESTINATION_COOKIE_RESPONSE.getId());
        assertEquals(ClientboundBedrockPackets.PARTY_CHANGED, ClientboundBedrockPackets.getPacket(342));
        assertEquals(ClientboundBedrockPackets.UPDATE_SOUND_DATA, ClientboundBedrockPackets.getPacket(348));
        assertEquals(ClientboundBedrockPackets.SEND_PARTY_DESTINATION_COOKIE, ClientboundBedrockPackets.getPacket(349));
        assertEquals(ClientboundBedrockPackets.PARTY_DESTINATION_COOKIE_RESPONSE, ClientboundBedrockPackets.getPacket(350));
        assertEquals(305, ClientboundBedrockPackets.REFRESH_ENTITLEMENTS.getId());
        assertEquals(340, ClientboundBedrockPackets.RESOURCE_PACKS_READY_FOR_VALIDATION.getId());
        assertEquals(ClientboundBedrockPackets.REFRESH_ENTITLEMENTS, ClientboundBedrockPackets.getPacket(305));
        assertEquals(ClientboundBedrockPackets.RESOURCE_PACKS_READY_FOR_VALIDATION, ClientboundBedrockPackets.getPacket(340));
    }

    @Test
    void confirmSkinRoundTripsMotLayoutAndRebuildsRgbaSkin() {
        final UUID uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        final byte[] rgba = new byte[64 * 64 * 4];
        for (int i = 0; i < rgba.length; i += 4) {
            rgba[i] = (byte) 0x11;
            rgba[i + 1] = (byte) 0x22;
            rgba[i + 2] = (byte) 0x33;
            rgba[i + 3] = (byte) 0xFF;
        }
        final ConfirmSkinLayout.Entry written = new ConfirmSkinLayout.Entry(
                true, uuid, rgba, "123456", "{\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.humanoid.custom\"}}]}");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            ConfirmSkinLayout.writePacket(buffer, List.of(written));
            final List<ConfirmSkinLayout.Entry> decoded = ConfirmSkinLayout.readPacket(buffer);
            assertFalse(buffer.isReadable());
            assertEquals(1, decoded.size());
            final ConfirmSkinLayout.Entry entry = decoded.get(0);
            assertTrue(entry.valid());
            assertEquals(uuid, entry.uuid());
            assertEquals("123456", entry.uidStr());
            assertEquals(written.geoStr(), entry.geoStr());
            assertEquals(rgba.length, entry.skinBytes().length);

            final SkinData skin = ConfirmSkinLayout.toSkinData(entry);
            assertNotNull(skin.skinData());
            assertEquals(64, skin.skinData().getWidth());
            assertEquals(64, skin.skinData().getHeight());
            assertEquals(0xFF112233, skin.skinData().getRGB(0, 0));
            assertTrue(skin.skinResourcePatch().contains("geometry.humanoid.custom"));
            assertFalse(skin.skinResourcePatch().contains("customSlim"));
            assertEquals("wide", skin.armSize());
            assertEquals(written.geoStr(), skin.geometryData());
        } finally {
            buffer.release();
        }
    }

    @Test
    void confirmSkinDoesNotTreatMotCustomSlimMentionAsAlex() {
        final String steveGeo = """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [
                    {"description": {"identifier": "geometry.cape"}},
                    {
                      "description": {"identifier": "geometry.humanoid.custom"},
                      "bones": [
                        {"name": "leftArm", "cubes": [{"size": [4, 12, 4]}]},
                        {"name": "rightArm", "cubes": [{"size": [4, 12, 4]}]}
                      ]
                    },
                    {
                      "description": {"identifier": "geometry.humanoid.customSlim"},
                      "bones": [
                        {"name": "leftArm", "cubes": [{"size": [3, 12, 4]}]},
                        {"name": "rightArm", "cubes": [{"size": [3, 12, 4]}]}
                      ]
                    }
                  ]
                }
                """;
        assertTrue(steveGeo.contains("customSlim"));
        assertFalse(ConfirmSkinLayout.isSlimGeometry(steveGeo));
        assertNull(SkinArmClassifier.slimFromGeometry(steveGeo));

        final ConfirmSkinLayout.Entry entry = new ConfirmSkinLayout.Entry(
                true, UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), new byte[0], "uid", steveGeo);
        final SkinData skin = ConfirmSkinLayout.toSkinData(entry);
        assertEquals("", skin.armSize());
        assertEquals("", skin.skinResourcePatch());
    }

    @Test
    void confirmSkinDetectsSlimFromArmWidthAndIdentifier() {
        final String alexGeo = """
                {
                  "minecraft:geometry": [
                    {
                      "description": {"identifier": "geometry.humanoid.customSlim"},
                      "bones": [
                        {"name": "leftArm", "cubes": [{"size": [3, 12, 4]}]},
                        {"name": "rightArm", "cubes": [{"size": [3, 12, 4]}]}
                      ]
                    }
                  ]
                }
                """;
        assertTrue(ConfirmSkinLayout.isSlimGeometry(alexGeo));
        assertTrue(ConfirmSkinLayout.isSlimGeometry("geometry.humanoid.customSlim"));
        assertFalse(ConfirmSkinLayout.isSlimGeometry("geometry.humanoid.custom"));
        assertFalse(ConfirmSkinLayout.isSlimGeometry("geometry.humanoid.custom.1742391406.1704"));
        assertEquals(Boolean.FALSE, SkinArmClassifier.slimFromGeometry("geometry.humanoid.custom"));
        assertEquals(Boolean.TRUE, SkinArmClassifier.slimFromGeometry("geometry.humanoid.customSlim"));
    }

    @Test
    void unusedSteveArmColumnMarksAlexTextureSlim() {
        final BufferedImage steve = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fillArmColumn(steve, 0xFF3366AA);
        assertEquals(Boolean.FALSE, SkinArmClassifier.slimFromTexture(steve));

        final BufferedImage alex = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fillArmColumn(alex, 0x00000000);
        assertEquals(Boolean.TRUE, SkinArmClassifier.slimFromTexture(alex));
    }

    private static void fillArmColumn(final BufferedImage image, final int argb) {
        for (int y = 20; y < 32; y++) {
            image.setRGB(43, y, argb);
        }
    }

    @Test
    void confirmSkinInfersKnownRgbaSizes() {
        assertEquals(new ConfirmSkinLayout.SkinSize(64, 32), ConfirmSkinLayout.inferSkinSize(64 * 32 * 4));
        assertEquals(new ConfirmSkinLayout.SkinSize(64, 64), ConfirmSkinLayout.inferSkinSize(64 * 64 * 4));
        assertEquals(new ConfirmSkinLayout.SkinSize(128, 128), ConfirmSkinLayout.inferSkinSize(128 * 128 * 4));
        assertNull(ConfirmSkinLayout.inferSkinSize(7));
    }

    @Test
    void syncSkinRoundTripsMotLayoutThenOfficialSkin() {
        final UUID uuid = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00");
        final SkinData skin = dummySkin();
        final SyncSkinLayout.Entry written = new SyncSkinLayout.Entry(true, uuid, "a", "b", "c", "d");
        final ByteBuf buffer = Unpooled.buffer();
        try {
            SyncSkinLayout.writePacket(buffer, List.of(written), skin);
            final SyncSkinLayout.Packet decoded = SyncSkinLayout.readPacket(buffer);
            assertFalse(buffer.isReadable());
            assertEquals(1, decoded.entries().size());
            final SyncSkinLayout.Entry entry = decoded.entries().get(0);
            assertTrue(entry.flag());
            assertEquals(uuid, entry.uuid());
            assertEquals("a", entry.string1());
            assertEquals("b", entry.string2());
            assertEquals("c", entry.string3());
            assertEquals("d", entry.string4());
            assertNotNull(decoded.skin());
            assertEquals(skin.skinId(), decoded.skin().skinId());
            assertEquals(2, decoded.skin().skinData().getWidth());
            assertEquals(2, decoded.skin().skinData().getHeight());
        } finally {
            buffer.release();
        }
    }

    @Test
    void neteaseJsonParsesSetLevelGravityFromMotPayload() {
        final ByteBuf buffer = Unpooled.buffer();
        try {
            NeteaseJsonLayout.writeJson(buffer, "{\"eventName\":\"SET_LEVEL_GRAVITY\",\"gravity\":-0.08}");
            final String json = NeteaseJsonLayout.readJson(buffer);
            assertFalse(buffer.isReadable());
            final NeteaseJsonLayout.Event event = NeteaseJsonLayout.parse(json);
            assertEquals(NeteaseJsonLayout.EVENT_SET_LEVEL_GRAVITY, event.eventName());
            assertEquals(-0.08f, NeteaseJsonLayout.readGravity(event), 0.0001f);
        } finally {
            buffer.release();
        }
    }

    @Test
    void neteaseJsonUnknownEventsKeepEventNameWithoutInventedFields() {
        final NeteaseJsonLayout.Event event = NeteaseJsonLayout.parse("{\"eventName\":\"CAN_PLAYER_MOVE\"}");
        assertEquals(NeteaseJsonLayout.EVENT_CAN_PLAYER_MOVE, event.eventName());
        assertNull(NeteaseJsonLayout.readGravity(event));
    }

    private static SkinData dummySkin() {
        final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF112233);
        return new SkinData(
                "skin-id",
                "playfab",
                ConfirmSkinLayout.DEFAULT_RESOURCE_PATCH,
                image,
                Collections.emptyList(),
                null,
                "{}",
                "1.21.124",
                "",
                false,
                false,
                false,
                true,
                "",
                "full-skin-id",
                "wide",
                "#0",
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                false,
                null
        );
    }
}
