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
package net.raphimc.viabedrock.protocol.provider;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.platform.providers.Provider;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonElement;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.libs.gson.JsonParser;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.modinterface.BedrockSkinUtilityInterface;
import net.raphimc.viabedrock.api.modinterface.ViaBedrockUtilityInterface;
import net.raphimc.viabedrock.api.resourcepack.content.Content;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.data.DataValues;
import net.raphimc.viabedrock.protocol.data.ProtocolConstants;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.MemoryTier;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.GraphicsMode;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InputMode;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.UIProfile;
import net.raphimc.viabedrock.api.util.JavaClientDevice;
import net.raphimc.viabedrock.protocol.model.JavaSkinData;
import net.raphimc.viabedrock.protocol.model.SkinData;
import net.raphimc.viabedrock.protocol.storage.AuthData;
import net.raphimc.viabedrock.protocol.storage.ChannelStorage;
import net.raphimc.viabedrock.protocol.storage.ClientSettingsStorage;
import net.raphimc.viabedrock.protocol.storage.HandshakeStorage;
import net.raphimc.viabedrock.protocol.storage.PlayerListStorage;
import net.raphimc.viabedrock.protocol.types.primitive.ImageType;

import javax.crypto.SecretKey;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SkinProvider implements Provider {

    private static final int SKIN_WORKERS = 4;
    private static final int SKIN_QUEUE_CAPACITY = 64;
    private static final ExecutorService SKIN_EXECUTOR = createSkinExecutor();

    private static ExecutorService createSkinExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(
                SKIN_WORKERS, SKIN_WORKERS, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(SKIN_QUEUE_CAPACITY), runnable -> {
                    final Thread thread = new Thread(runnable, "ViaBedrock-Skin-Fetcher");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Asynchronously fetches a Java Edition player's skin from Mojang API.
     * Runs on a worker thread to avoid blocking the Netty EventLoop.
     */
    public CompletableFuture<JavaSkinData> fetchJavaSkinAsync(final UUID uuid) {
        try {
            return CompletableFuture.supplyAsync(() -> {
            try {
                final String uuidStr = uuid.toString().replace("-", "");

                // 1. Fetch profile from Mojang session server
                final HttpURLConnection profileConn = (HttpURLConnection) new URL(
                        "https://sessionserver.mojang.com/session/minecraft/profile/" + uuidStr)
                        .openConnection();
                profileConn.setConnectTimeout(5000);
                profileConn.setReadTimeout(5000);
                profileConn.setRequestProperty("User-Agent", "ViaBedrock");

                if (profileConn.getResponseCode() != 200) {
                    ViaBedrock.getPlatform().getLogger().warning(
                            "Mojang API returned " + profileConn.getResponseCode() + " for " + uuid);
                    return null;
                }

                final String profileBody;
                try (InputStream is = profileConn.getInputStream()) {
                    profileBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }

                // 2. Parse textures property
                final JsonObject profileJson = JsonParser.parseString(profileBody).getAsJsonObject();
                final JsonArray properties = profileJson.getAsJsonArray("properties");
                String texturesBase64 = null;
                for (final JsonElement prop : properties) {
                    final JsonObject propObj = prop.getAsJsonObject();
                    if ("textures".equals(propObj.get("name").getAsString())) {
                        texturesBase64 = propObj.get("value").getAsString();
                        break;
                    }
                }
                if (texturesBase64 == null) {
                    return null;
                }

                final JsonObject texturesJson = JsonParser.parseString(
                        new String(Base64.getDecoder().decode(texturesBase64), StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");

                // 3. Download skin image
                BufferedImage skinImage = null;
                boolean isSlim = false;
                if (texturesJson.has("SKIN")) {
                    final JsonObject skinObj = texturesJson.getAsJsonObject("SKIN");
                    final String skinUrl = skinObj.get("url").getAsString();
                    isSlim = skinObj.has("metadata")
                            && skinObj.getAsJsonObject("metadata").has("model")
                            && "slim".equals(skinObj.getAsJsonObject("metadata").get("model").getAsString());

                    final HttpURLConnection skinConn = (HttpURLConnection) new URL(skinUrl).openConnection();
                    skinConn.setConnectTimeout(5000);
                    skinConn.setReadTimeout(5000);
                    skinConn.setRequestProperty("User-Agent", "ViaBedrock");
                    try (InputStream is = skinConn.getInputStream()) {
                        skinImage = ImageIO.read(is);
                    }
                }

                // 4. Download cape image (optional)
                BufferedImage capeImage = null;
                if (texturesJson.has("CAPE")) {
                    try {
                        final String capeUrl = texturesJson.getAsJsonObject("CAPE").get("url").getAsString();
                        final HttpURLConnection capeConn = (HttpURLConnection) new URL(capeUrl).openConnection();
                        capeConn.setConnectTimeout(3000);
                        capeConn.setReadTimeout(3000);
                        capeConn.setRequestProperty("User-Agent", "ViaBedrock");
                        try (InputStream is = capeConn.getInputStream()) {
                            capeImage = ImageIO.read(is);
                        }
                    } catch (Exception e) {
                        // Cape download failure is non-critical
                    }
                }

                if (skinImage != null) {
                    return new JavaSkinData(skinImage, capeImage, isSlim, "mojang:" + uuid);
                }
                return null;
            } catch (Exception e) {
                ViaBedrock.getPlatform().getLogger().warning(
                        "Failed to fetch Java skin for " + uuid + ": " + e.getMessage());
                return null;
            }
            }, SKIN_EXECUTOR);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public Map<String, Object> getClientPlayerSkin(final UserConnection user) {
        final AuthData authData = user.get(AuthData.class);
        final Map<String, Object> claims = new HashMap<>();

        { // Skin claims
            final Content skinPackContent = BedrockProtocol.MAPPINGS.getBedrockSkinPacks().get(DataValues.VANILLA_SKIN_PACK_KEY).content();
            final BufferedImage skin = skinPackContent.getImage("steve.png").getImage();
            final JsonObject skinGeometry = skinPackContent.getSortedJson("geometry.json");

            claims.put("SkinId", UUID.randomUUID().toString());
            claims.put("SkinData", Base64.getEncoder().encodeToString(ImageType.getImageData(skin)));
            claims.put("SkinImageWidth", skin.getWidth());
            claims.put("SkinImageHeight", skin.getHeight());
            claims.put("SkinGeometryData", Base64.getEncoder().encodeToString(skinGeometry.toString().getBytes(StandardCharsets.UTF_8)));
            claims.put("SkinGeometryDataEngineVersion", Base64.getEncoder().encodeToString("0.0.0".getBytes(StandardCharsets.UTF_8)));
            claims.put("SkinResourcePatch", Base64.getEncoder().encodeToString("{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8)));
            claims.put("SkinAnimationData", "");
            claims.put("SkinColor", "#0");
            claims.put("PremiumSkin", false);
            claims.put("PersonaSkin", false);
            claims.put("TrustedSkin", false);
            claims.put("OverrideSkin", false);
            claims.put("ArmSize", "wide");
            claims.put("AnimatedImageData", new ArrayList<>());
            claims.put("PersonaPieces", new ArrayList<>());
            claims.put("PieceTintColors", new ArrayList<>());
        }
        { // Cape claims (default empty)
            claims.put("CapeId", "");
            claims.put("CapeData", "");
            claims.put("CapeImageWidth", 0);
            claims.put("CapeImageHeight", 0);
            claims.put("CapeOnClassicSkin", false);
        }

        // Try to apply Java Edition skin from async fetch result
        if (authData.getJavaSkinFuture() != null) {
            final int timeout = authData.getJavaSkinWaitTimeoutMs() > 0
                    ? authData.getJavaSkinWaitTimeoutMs()
                    : ViaBedrock.getConfig().getJavaSkinFetchTimeout();
            JavaSkinData result = null;
            try {
                result = awaitJavaSkin(
                        authData.getJavaSkinFuture(),
                        timeout,
                        authData.getExternalJavaSkinSource() == null);
            } catch (TimeoutException e) {
                ViaBedrock.getPlatform().getLogger().warning(
                        "Java skin fetch timed out after " + timeout + "ms for "
                                + user.getProtocolInfo().getUsername() + ", using Steve skin");
            } catch (Exception e) {
                ViaBedrock.getPlatform().getLogger().warning(
                        "Failed to fetch Java skin for " + user.getProtocolInfo().getUsername()
                                + "; using Steve skin");
            }

            if (result != null) {
                ViaBedrock.getPlatform().getLogger().info(
                        "Using Java skin for " + user.getProtocolInfo().getUsername()
                                + " (" + result.skin().getWidth() + "x" + result.skin().getHeight()
                                + ", " + (result.slim() ? "slim" : "wide") + ")");
                applyJavaSkinClaims(claims, result);
            }
        }

        final HandshakeStorage handshakeStorage = user.get(HandshakeStorage.class);
        { // Session claims
            claims.put("ServerAddress", handshakeStorage.hostname() + ":" + handshakeStorage.port());
            claims.put("ThirdPartyName", user.getProtocolInfo().getUsername());
        }
        { // Client claims
            claims.put("GameVersion", ViaBedrock.getConfig().shouldEmulateNetEaseClient()
                    ? ViaBedrock.getConfig().getNetEaseGameVersion()
                    : ProtocolConstants.BEDROCK_VERSION_NAME);
            if (ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                claims.put("IsReconnect", false);
            }
            final ClientSettingsStorage clientSettings = user.get(ClientSettingsStorage.class);
            claims.put("LanguageCode", convertLocaleFormat(clientSettings != null ? clientSettings.locale() : "en_us"));
            claims.put("GraphicsMode", GraphicsMode.Fancy.getValue());
            claims.put("GuiScale", -1);
            claims.put("UIProfile", UIProfile.Classic.getValue());
            claims.put("ClientRandomId", authData.getClientRandomId());
            claims.put("SelfSignedId", authData.getSelfSignedId());
            claims.put("IsEditorMode", false);
            claims.put("FilterProfanity", false);
        }
        { // Device claims
            claims.put("DeviceId", authData.getDeviceId().toString().replace("-", ""));
            applyDeviceClaims(claims, handshakeStorage != null ? handshakeStorage.device() : null);
            claims.put("CurrentInputMode", InputMode.Mouse.getValue());
            claims.put("DefaultInputMode", InputMode.Mouse.getValue());
        }
        { // Hardware claims
            claims.put("MemoryTier", MemoryTier.SuperHigh.ordinal());
            claims.put("MaxViewDistance", 96);
            claims.put("CompatibleWithClientSideChunkGen", false);
        }
        { // Platform claims
            claims.put("PlatformType", 0);
            claims.put("PlatformOfflineId", "");
            claims.put("PlatformOnlineId", "");
        }

        { // ViaProxy auth token
            // Waterdog HandshakeEntry treats a non-empty ViaProxyAuthToken as a Java
            // client and skips fake-dimension transfers. The HMAC secret is optional;
            // without it we still emit a sentinel so same-dimension proxy transfers work.
            final String authSecret = ViaBedrock.getConfig().getViaProxyAuthSecret();
            if (authSecret != null && !authSecret.isEmpty()) {
                final long timestamp = System.currentTimeMillis() / 1000;
                putJavaClientAuthToken(claims, authSecret, ViaProxyAuthToken.create(
                        authSecret,
                        user.getProtocolInfo().getUuid(),
                        user.getProtocolInfo().getUsername(),
                        Via.getManager().getProviders().get(ClientAddressProvider.class).getClientAddress(user),
                        timestamp
                ));
                addJavaClientEncryptionKeyClaim(
                        claims,
                        authSecret,
                        Via.getManager().getProviders().get(JavaClientEncryptionKeyProvider.class)
                                .getJavaClientEncryptionKey(user)
                );
            } else {
                putJavaClientAuthToken(claims, authSecret, null);
            }
        }

        return claims;
    }

    static final String JAVA_CLIENT_MARKER = "ViaProxy";

    static void putJavaClientAuthToken(final Map<String, Object> claims, final String authSecret,
                                       final String signedToken) {
        if (authSecret != null && !authSecret.isEmpty() && signedToken != null && !signedToken.isEmpty()) {
            claims.put("ViaProxyAuthToken", signedToken);
            return;
        }
        claims.put("ViaProxyAuthToken", JAVA_CLIENT_MARKER);
    }

    static void applyDeviceClaims(final Map<String, Object> claims, final JavaClientDevice device) {
        final JavaClientDevice resolved = device != null ? device : JavaClientDevice.JAVA_EDITION;
        claims.put("DeviceModel", resolved.model());
        claims.put("DeviceOS", resolved.deviceOs());
    }

    static void addJavaClientEncryptionKeyClaim(final Map<String, Object> claims, final String authSecret,
                                                 final SecretKey encryptionKey) {
        if (authSecret == null || authSecret.isEmpty() || encryptionKey == null) {
            return;
        }
        final byte[] encodedKey = encryptionKey.getEncoded();
        if (encodedKey == null || encodedKey.length == 0) {
            return;
        }
        claims.put("JavaClientEncryptionKey", Base64.getEncoder().encodeToString(encodedKey));
    }

    static JavaSkinData awaitJavaSkin(
            final CompletableFuture<JavaSkinData> future,
            final int timeoutMs,
            final boolean cancelOnTimeout
    ) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (cancelOnTimeout) {
                future.cancel(true);
            }
            throw e;
        }
    }

    static void applyJavaSkinClaims(final Map<String, Object> claims, final JavaSkinData result) {
        claims.put("SkinData", Base64.getEncoder().encodeToString(ImageType.getImageData(result.skin())));
        claims.put("SkinImageWidth", result.skin().getWidth());
        claims.put("SkinImageHeight", result.skin().getHeight());
        claims.put("ArmSize", result.slim() ? "slim" : "wide");

        final String geoName = result.slim() ? "geometry.humanoid.customSlim" : "geometry.humanoid.custom";
        claims.put("SkinResourcePatch", Base64.getEncoder().encodeToString(
                ("{\"geometry\":{\"default\":\"" + geoName + "\"}}").getBytes(StandardCharsets.UTF_8)));

        if (result.cape() != null) {
            claims.put("CapeData", Base64.getEncoder().encodeToString(ImageType.getImageData(result.cape())));
            claims.put("CapeImageWidth", result.cape().getWidth());
            claims.put("CapeImageHeight", result.cape().getHeight());
            claims.put("CapeId", UUID.randomUUID().toString());
        }
    }

    private static String convertLocaleFormat(final String locale) {
        final int underscoreIndex = locale.indexOf('_');
        if (underscoreIndex > 0 && underscoreIndex < locale.length() - 1) {
            return locale.substring(0, underscoreIndex + 1) + locale.substring(underscoreIndex + 1).toUpperCase();
        }
        return locale;
    }

    public void setSkin(final UserConnection user, final UUID playerUuid, final SkinData skin) {
        SkinData outgoing = skin;
        final PlayerListStorage playerListStorage = user.get(PlayerListStorage.class);
        if (playerListStorage != null) {
            outgoing = playerListStorage.rememberAndApplyArmHint(playerUuid, skin);
        }
        final ChannelStorage channelStorage = user.get(ChannelStorage.class);
        final boolean hasVBU = channelStorage.hasChannel(ViaBedrockUtilityInterface.CHANNEL);
        final boolean hasBSU = channelStorage.hasChannel(BedrockSkinUtilityInterface.CHANNEL);
        ViaBedrock.getPlatform().getLogger().fine("setSkin: uuid=" + playerUuid
                + " persona=" + outgoing.persona()
                + " armSize=" + outgoing.armSize()
                + " skinData=" + (outgoing.skinData() != null ? outgoing.skinData().getWidth() + "x" + outgoing.skinData().getHeight() : "null")
                + " hasVBU=" + hasVBU + " hasBSU=" + hasBSU);
        if (hasVBU) {
            ViaBedrockUtilityInterface.sendSkin(user, playerUuid, outgoing);
        } else if (hasBSU) {
            BedrockSkinUtilityInterface.sendSkin(user, playerUuid, outgoing);
        }
    }

}
