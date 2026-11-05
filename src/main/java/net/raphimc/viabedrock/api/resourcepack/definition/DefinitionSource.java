package net.raphimc.viabedrock.api.resourcepack.definition;

import net.raphimc.viabedrock.api.resourcepack.content.PackContentView;
import java.util.List;

/** Sources owned by definition parsing; also used by its memory admission estimate. */
public enum DefinitionSource {
    MANIFEST("manifest.json"),
    TEXTS("texts/en_US.lang"),
    BLOCKS("blocks.json"),
    ITEMS("items/"),
    ATTACHABLES("attachables/"),
    TEXTURES("textures/item_texture.json"),
    TERRAIN_TEXTURES("textures/terrain_texture.json"),
    SOUND_DEFINITIONS("sounds/sound_definitions.json"),
    SOUNDS("sounds.json"),
    PARTICLES("particles/"),
    ENTITIES("entity/"),
    MODELS("models/"),
    LEGACY_MODEL("geometry.json"),
    FOGS("fogs/"),
    BIOMES("biomes/"),
    RENDER_CONTROLLERS("render_controllers/");

    private final String path;
    DefinitionSource(String path) { this.path = path; }
    public String path() { return this.path; }

    public List<String> files(PackContentView content) {
        return this.path.endsWith("/") ? content.getFilesDeep(this.path, ".json")
                : content.contains(this.path) ? List.of(this.path) : List.of();
    }
}
