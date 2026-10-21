package net.raphimc.viabedrock.protocol.rewriter.resourcepack;

import com.viaversion.viaversion.libs.gson.JsonObject;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomAttachableGuiIconTest {

    private static final String ITEM_PATH = "assets/viabedrock/items/attachables/test/egg.json";
    private static final String HELD_MODEL_PATH = "assets/viabedrock/models/attachables/test/egg/default.json";
    private static final String ICON_MODEL_PATH = "assets/viabedrock/models/item_textures/egg_icon/0.json";

    @Test
    void emptyHeldGeometryRetainsItsInventoryIcon() {
        final InMemoryContent output = convert(true, false);
        assertGuiIconAndHeldSelector(output);
        assertTrue(output.getJson(HELD_MODEL_PATH).getAsJsonArray("elements").isEmpty());
    }

    @Test
    void declaredInventoryIconDoesNotReplaceTheHeldGeometry() {
        final InMemoryContent output = convert(true, true);
        assertGuiIconAndHeldSelector(output);
        assertFalse(output.getJson(HELD_MODEL_PATH).getAsJsonArray("elements").isEmpty());
    }

    @Test
    void attachableWithoutInventoryIconRetainsItsGeometrySelector() {
        final InMemoryContent output = convert(false, true);
        final JsonObject selector = output.getJson(ITEM_PATH).getAsJsonObject("model");
        assertEquals("minecraft:custom_model_data", selector.get("property").getAsString());
        assertEquals("viabedrock:attachables/test/egg/default", selector.getAsJsonArray("cases")
                .get(0).getAsJsonObject().getAsJsonObject("model").get("model").getAsString());
    }

    private static void assertGuiIconAndHeldSelector(final InMemoryContent output) {
        final JsonObject selector = output.getJson(ITEM_PATH).getAsJsonObject("model");
        assertEquals("minecraft:select", selector.get("type").getAsString());
        assertEquals("minecraft:display_context", selector.get("property").getAsString());
        final var cases = selector.getAsJsonArray("cases");
        assertEquals(1, cases.size());
        final JsonObject gui = cases.get(0).getAsJsonObject();
        assertEquals("gui", gui.get("when").getAsString());
        assertEquals("minecraft:model", gui.getAsJsonObject("model").get("type").getAsString());
        assertEquals("viabedrock:item_textures/egg_icon/0", gui.getAsJsonObject("model")
                .get("model").getAsString());
        assertEquals("minecraft:item/generated", output.getJson(ICON_MODEL_PATH).get("parent").getAsString());
        assertEquals("viabedrock:item/item_textures/items/egg_icon", output.getJson(ICON_MODEL_PATH)
                .getAsJsonObject("textures").get("layer0").getAsString());
        final JsonObject held = selector.getAsJsonObject("fallback");
        assertEquals("minecraft:custom_model_data", held.get("property").getAsString());
        assertEquals("default", held.getAsJsonArray("cases").get(0).getAsJsonObject()
                .get("when").getAsString());
        assertEquals("viabedrock:attachables/test/egg/default", held.getAsJsonArray("cases")
                .get(0).getAsJsonObject().getAsJsonObject("model").get("model").getAsString());
    }

    private static InMemoryContent convert(final boolean withIcon, final boolean withCubes) {
        final InMemoryContent input = new InMemoryContent();
        input.putString("manifest.json", """
                {"format_version":2,"header":{"name":"attachable-gui-test","description":"attachable-gui-test",
                "uuid":"fd029ed8-aa9b-4b42-8ab1-23b2b1ca056f","version":[1,0,0],"min_engine_version":[1,21,0]}}
                """);
        input.putString("items/egg.json", """
                {"format_version":"1.21.40","minecraft:item":{"description":{"identifier":"test:egg"},
                "components":%s}}
                """.formatted(withIcon ? "{\"minecraft:icon\":\"egg_icon\"}" : "{}"));
        input.putString("textures/item_texture.json", """
                {"resource_pack_name":"test","texture_name":"atlas.items",
                "texture_data":{"egg_icon":{"textures":"textures/items/egg_icon"}}}
                """);
        input.putString("attachables/egg.json", """
                {"format_version":"1.10.0","minecraft:attachable":{"description":{"identifier":"test:egg",
                "materials":{"default":"entity_alphatest"},"textures":{"default":"textures/entity/egg"},
                "geometry":{"default":"geometry.test.egg"},"render_controllers":[]}}}
                """);
        input.putString("models/entity/egg.geo.json", """
                {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.test.egg",
                "texture_width":16,"texture_height":16},"bones":[{"name":"root","pivot":[0,0,0],"cubes":%s}]}]}
                """.formatted(withCubes ? "[{\"origin\":[0,0,0],\"size\":[2,2,2],\"uv\":[0,0]}]" : "[]"));
        final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of(new ResourcePack(input)));
        final InMemoryContent output = new InMemoryContent();
        new CustomItemTextureResourceRewriter().apply(storage, output);
        new CustomAttachableResourceRewriter().apply(storage, output);
        return output;
    }
}
