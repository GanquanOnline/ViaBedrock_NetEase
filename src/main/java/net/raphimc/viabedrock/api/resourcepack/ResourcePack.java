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
package net.raphimc.viabedrock.api.resourcepack;

import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.util.GsonUtil;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.api.resourcepack.content.DirectoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.InMemoryContent;
import net.raphimc.viabedrock.api.resourcepack.content.ZipContent;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

// TODO: 依赖处理
// TODO: 子包处理
public class ResourcePack {

    private static final byte[] CONTENTS_JSON_ENCRYPTION_VERSION = new byte[]{(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};
    private static final byte[] CONTENTS_JSON_ENCRYPTION_MAGIC = new byte[]{(byte) 0xFC, (byte) 0xB9, (byte) 0xCF, (byte) 0x9B};
    private static final int CONTENTS_JSON_HEADER_BYTES = 256;
    private static final long MAX_MANIFEST_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_CONTENTS_JSON_BYTES = 32L * 1024L * 1024L;
    private static final Set<String> UNENCRYPTED_FILES = Set.of("manifest.json", "pack_manifest.json", "pack_icon.png", "pack_icon.jpg", "README.txt");

    private final Key key;
    private final String name;
    private final Content content;

    public ResourcePack(Content content) {
        try {
            if (!content.contains("manifest.json") && !content.contains("pack_manifest.json")) {
                // CDN 包允许在根目录包含单个 .zip 文件
                final List<String> files = content.getFilesDeep("", "");
                if (files.size() == 1 && files.get(0).endsWith(".zip")) {
                    content = new ZipContent(content.get(files.get(0)));
                }
            }
            if (!content.contains("manifest.json") && !content.contains("pack_manifest.json")) {
                content = unwrapSingleRootDirectory(content);
            }
            if (!content.contains("manifest.json") && !content.contains("pack_manifest.json")) {
                throw new IllegalStateException("Missing manifest.json");
            }
            final String manifestPath = content.contains("manifest.json") ? "manifest.json" : "pack_manifest.json";
            final JsonObject manifestJson = readJson(content, manifestPath, MAX_MANIFEST_BYTES);
            final int formatVersion = manifestJson.get("format_version").getAsInt();
            if (formatVersion < 1 || formatVersion > 3) {
                throw new IllegalStateException("Unsupported format version: " + formatVersion);
            }
            final JsonObject headerObj = manifestJson.getAsJsonObject("header");
            final UUID id = UUID.fromString(headerObj.get("uuid").getAsString());
            final String version;
            if (formatVersion >= 3) {
                version = headerObj.get("version").getAsString();
            } else {
                version = StreamSupport.stream(headerObj.getAsJsonArray("version").spliterator(), false).map(JsonElement::getAsString).collect(Collectors.joining("."));
            }
            this.key = new Key(id, version);
            this.name = headerObj.get("name").getAsString();
            /*if (formatVersion >= 2) { // Technically needed, but not feasible to implement currently
                final Semver minEngineVersion;
                if (formatVersion >= 3) {
                    minEngineVersion = new Semver(headerObj.get("min_engine_version").getAsString());
                } else {
                    minEngineVersion = new Semver(StreamSupport.stream(headerObj.getAsJsonArray("min_engine_version").spliterator(), false).map(JsonElement::getAsString).collect(Collectors.joining(".")));
                }
                if (minEngineVersion.isGreaterThan(ProtocolConstants.BEDROCK_VERSION_NAME)) {
                    throw new RuntimeException("Resource pack requires a newer game version: " + minEngineVersion + " > " + ProtocolConstants.BEDROCK_VERSION_NAME);
                }
            }*/
            this.content = content;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to parse resource pack", e);
        }
    }

    private static Content unwrapSingleRootDirectory(final Content content) {
        final List<String> files = content.getFilesDeep("", "");
        if (files.isEmpty()) {
            return content;
        }

        String rootDirectory = null;
        for (String file : files) {
            final int slashIndex = file.indexOf('/');
            if (slashIndex <= 0) {
                return content;
            }

            final String fileRootDirectory = file.substring(0, slashIndex);
            if (rootDirectory == null) {
                rootDirectory = fileRootDirectory;
            } else if (!rootDirectory.equals(fileRootDirectory)) {
                return content;
            }
        }

        final InMemoryContent unwrappedContent = new InMemoryContent();
        final int prefixLength = rootDirectory.length() + 1;
        for (String file : files) {
            unwrappedContent.put(file.substring(prefixLength), content.get(file));
        }
        return unwrappedContent;
    }

    public void decryptContent(final byte[] contentKey, final String expectedContentId) {
        try {
            if (!this.content.contains("contents.json")) {
                throw new IllegalStateException("Missing contents.json");
            }
            final long encryptedContentsSize = requireEntrySize(
                    this.content, "contents.json", MAX_CONTENTS_JSON_BYTES + CONTENTS_JSON_HEADER_BYTES);
            if (encryptedContentsSize < CONTENTS_JSON_HEADER_BYTES) {
                throw new IllegalStateException("contents.json is shorter than its encryption header");
            }
            try (DataInputStream contents = new DataInputStream(requireOpen(this.content, "contents.json"))) {
                final byte[] version = contents.readNBytes(4); // 版本
                if (!Arrays.equals(version, CONTENTS_JSON_ENCRYPTION_VERSION)) {
                    throw new IllegalStateException("contents.json version mismatch: " + Arrays.toString(version) + " != " + Arrays.toString(CONTENTS_JSON_ENCRYPTION_VERSION));
                }
                final byte[] magic = contents.readNBytes(4); // 魔数
                if (!Arrays.equals(magic, CONTENTS_JSON_ENCRYPTION_MAGIC)) {
                    throw new IllegalStateException("contents.json magic mismatch: " + Arrays.toString(magic) + " != " + Arrays.toString(CONTENTS_JSON_ENCRYPTION_MAGIC));
                }
                contents.skipNBytes(8); // 保留
                final int contentIdLength = contents.readUnsignedByte();
                if (contentIdLength > CONTENTS_JSON_HEADER_BYTES - 17) {
                    throw new IllegalStateException("contents.json content id exceeds its encryption header");
                }
                final String contentId = new String(contents.readNBytes(contentIdLength), StandardCharsets.UTF_8);
                if (!contentId.equalsIgnoreCase(expectedContentId)) {
                    throw new IllegalStateException("contents.json content id mismatch: " + contentId + " != " + expectedContentId);
                }
            }
            final Cipher aesCfb8 = Cipher.getInstance("AES/CFB8/NoPadding");
            aesCfb8.init(Cipher.DECRYPT_MODE, new SecretKeySpec(contentKey, "AES"), new IvParameterSpec(Arrays.copyOfRange(contentKey, 0, 16)));
            if (this.content instanceof DirectoryContent directoryContent) {
                directoryContent.decryptFile("contents.json", aesCfb8, CONTENTS_JSON_HEADER_BYTES,
                        MAX_CONTENTS_JSON_BYTES);
            } else {
                final int decryptedSize = Math.toIntExact(encryptedContentsSize - CONTENTS_JSON_HEADER_BYTES);
                final byte[] decrypted = new byte[decryptedSize];
                try (InputStream encrypted = requireOpen(this.content, "contents.json")) {
                    encrypted.skipNBytes(CONTENTS_JSON_HEADER_BYTES);
                    try (CipherInputStream input = new CipherInputStream(encrypted, aesCfb8)) {
                        if (input.readNBytes(decrypted, 0, decrypted.length) != decrypted.length || input.read() != -1) {
                            throw new IOException("contents.json decrypted size changed unexpectedly");
                        }
                    }
                }
                this.content.put("contents.json", decrypted);
            }

            final JsonObject contentsJson = readJson(this.content, "contents.json", MAX_CONTENTS_JSON_BYTES);
            final JsonArray contentArray = contentsJson.getAsJsonArray("content");
            final int maximumEntries = ViaBedrock.getConfig() != null
                    ? ViaBedrock.getConfig().getResourcePackMaxEntries() : 100_000;
            if (contentArray == null || contentArray.size() > maximumEntries) {
                throw new IllegalStateException("contents.json exceeds the configured entry limit");
            }
            final Set<String> declaredPaths = new HashSet<>(contentArray.size());
            final List<EncryptedEntry> encryptedEntries = new ArrayList<>();
            for (JsonElement element : contentArray) {
                final JsonObject contentItem = element.getAsJsonObject();
                final String path = validateContentPath(contentItem.get("path").getAsString());
                if (!declaredPaths.add(path)) {
                    throw new IllegalStateException("contents.json contains duplicate path: " + path);
                }
                if (!contentItem.has("key") || contentItem.get("key").isJsonNull()) {
                    continue;
                }
                if (path.equals("contents.json")) {
                    throw new IllegalStateException("contents.json must not declare itself as encrypted content");
                }
                if (!this.content.contains(path)) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Missing resource pack file: " + path);
                    continue;
                }
                if (this.content.size(path) < 0L) {
                    throw new IllegalStateException("Encrypted resource pack path is not a regular file: " + path);
                }
                if (UNENCRYPTED_FILES.contains(path)) {
                    continue;
                }

                final byte[] key = contentItem.get("key").getAsString().getBytes(StandardCharsets.ISO_8859_1);
                if (key.length != 16 && key.length != 24 && key.length != 32) {
                    throw new IllegalStateException("Invalid encryption key length for resource pack file: " + path);
                }
                encryptedEntries.add(new EncryptedEntry(path, key));
            }
            for (EncryptedEntry entry : encryptedEntries) {
                final byte[] key = entry.key();
                aesCfb8.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
                if (this.content instanceof DirectoryContent directoryContent) {
                    directoryContent.decryptFile(entry.path(), aesCfb8);
                } else {
                    this.content.put(entry.path(), aesCfb8.doFinal(this.content.get(entry.path())));
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException("Failed to decrypt content", e);
        }
    }

    public boolean isContentEncrypted() {
        try {
            if (!this.content.contains("contents.json")) {
                return false;
            }
            try (DataInputStream contents = new DataInputStream(requireOpen(this.content, "contents.json"))) {
                final byte[] version = contents.readNBytes(4); // 版本
                if (!Arrays.equals(version, CONTENTS_JSON_ENCRYPTION_VERSION)) {
                    return false;
                }
                final byte[] magic = contents.readNBytes(4); // 魔数
                return Arrays.equals(magic, CONTENTS_JSON_ENCRYPTION_MAGIC);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    private static JsonObject readJson(final Content content, final String path, final long maxBytes) throws IOException {
        requireEntrySize(content, path, maxBytes);
        try (InputStream input = requireOpen(content, path);
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            final JsonObject json = GsonUtil.getGson().fromJson(reader, JsonObject.class);
            if (json == null) {
                throw new IOException("Empty resource pack metadata: " + path);
            }
            return json;
        }
    }

    private static long requireEntrySize(final Content content, final String path, final long maxBytes) throws IOException {
        final long size = content.size(path);
        if (size < 0L) {
            throw new IOException("Missing resource pack metadata: " + path);
        }
        if (size > maxBytes) {
            throw new IOException("Resource pack metadata exceeds its size limit: " + path);
        }
        return size;
    }

    private static InputStream requireOpen(final Content content, final String path) throws IOException {
        final InputStream input = content.open(path);
        if (input == null) {
            throw new IOException("Missing resource pack content: " + path);
        }
        return input;
    }

    private static String validateContentPath(final String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.endsWith("/")
                || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("contents.json contains a non-canonical path: " + path);
        }
        if (path.length() >= 2 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':') {
            throw new IllegalArgumentException("contents.json contains a drive-prefixed path: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("contents.json contains a non-canonical path: " + path);
            }
        }
        for (int i = 0; i < path.length(); i++) {
            if (Character.isISOControl(path.charAt(i))) {
                throw new IllegalArgumentException("contents.json contains a control character in its path");
            }
        }
        return path;
    }

    private record EncryptedEntry(String path, byte[] key) {
    }

    public Key key() {
        return this.key;
    }

    public UUID id() {
        return this.key.id();
    }

    public String version() {
        return this.key.version();
    }

    public String name() {
        return this.name;
    }

    public Content content() {
        return this.content;
    }

    public record Key(UUID id, String version) {

        public static Key fromString(final String s) {
            final String[] parts = s.split("_", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid resource pack key: " + s);
            }
            return new Key(UUID.fromString(parts[0]), parts[1]);
        }

        @Override
        public String toString() {
            return this.id + "_" + this.version;
        }

    }

}
