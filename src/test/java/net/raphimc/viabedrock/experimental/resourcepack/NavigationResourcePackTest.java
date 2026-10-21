package net.raphimc.viabedrock.experimental.resourcepack;

import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.platform.ViaBedrockPlatform;
import net.raphimc.viabedrock.protocol.storage.ResourcePackStorage;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class NavigationResourcePackTest {
    @Test
    void copiesOriginalFramesAndTexturesWithStackPriorityAndCacheInvalidation() throws Exception {
        Field platform = ViaBedrock.class.getDeclaredField("platform");
        platform.setAccessible(true);
        Object previous = platform.get(null);
        platform.set(null, new ViaBedrockPlatform() {
            @Override public Logger getLogger() { return Logger.getLogger("navigation-test"); }
            @Override public File getDataFolder() { throw new UnsupportedOperationException(); }
        });
        try {
            InMemoryContent top = content("00000000-0000-0000-0000-000000000001");
            InMemoryContent bottom = content("00000000-0000-0000-0000-000000000002");
            byte[] png = new byte[]{1,2,3};
            byte[] frames = "{\"frames\":[]}".getBytes(StandardCharsets.UTF_8);
            top.put("sfx/frame/navi_yellow.png", png);
            top.put("sfx/frame/navi_yellow.json", frames);
            top.put("textures/ui/panel.png", png);
            top.put("sfx/frame/not-a-texture.bin", png);
            top.put("sfx/unrelated.png", png);
            bottom.put("sfx/frame/navi_yellow.png", new byte[]{9});
            bottom.put("sfx/frame/navi_yellow.json", new byte[]{9});
            InMemoryContent output = new InMemoryContent();
            UITextureResourceRewriter rewriter = new UITextureResourceRewriter();
            rewriter.apply(new ResourcePackStorage(List.of(new ResourcePack(top), new ResourcePack(bottom))), output);
            assertArrayEquals(png, output.get("assets/minecraft/sfx/frame/navi_yellow.png"));
            assertArrayEquals(frames, output.get("assets/minecraft/sfx/frame/navi_yellow.json"));
            assertArrayEquals(png, output.get("assets/minecraft/textures/ui/panel.png"));
            assertFalse(output.contains("assets/minecraft/sfx/frame/not-a-texture.bin"));
            assertFalse(output.contains("assets/minecraft/sfx/unrelated.png"));
            assertEquals("2", rewriter.artifactFingerprint());
        } finally {
            platform.set(null, previous);
        }
    }

    private static InMemoryContent content(String uuid) {
        InMemoryContent result = new InMemoryContent();
        result.put("manifest.json", ("{\"format_version\":2,\"header\":{\"uuid\":\"" + uuid
                + "\",\"name\":\"Navigation fixture\",\"version\":[1,0,0]}}")
                .getBytes(StandardCharsets.UTF_8));
        return result;
    }
}
