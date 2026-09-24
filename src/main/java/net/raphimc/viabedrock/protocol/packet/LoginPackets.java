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

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.ProtocolInfo;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import com.viaversion.viaversion.libs.gson.JsonArray;
import com.viaversion.viaversion.libs.gson.JsonObject;
import com.viaversion.viaversion.protocols.base.ClientboundLoginPackets;
import com.viaversion.viaversion.protocols.base.ServerboundHandshakePackets;
import com.viaversion.viaversion.protocols.base.ServerboundLoginPackets;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.util.CryptUtil;
import net.raphimc.viabedrock.api.util.FNV1;
import net.raphimc.viabedrock.api.util.JavaClientDevice;
import net.raphimc.viabedrock.api.util.Jwt;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.api.util.ServerBlacklist;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ClientboundBedrockPackets;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.AuthenticationType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.PacketCompressionAlgorithm;
import net.raphimc.viabedrock.protocol.model.JavaSkinData;
import net.raphimc.viabedrock.experimental.tablist.PlayerIdentity;
import net.raphimc.viabedrock.protocol.provider.NettyPipelineProvider;
import net.raphimc.viabedrock.protocol.provider.SkinProvider;
import net.raphimc.viabedrock.protocol.storage.AuthData;
import net.raphimc.viabedrock.protocol.storage.ExternalJavaSkinStorage;
import net.raphimc.viabedrock.protocol.storage.HandshakeStorage;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class LoginPackets {

    private static final int CLOCK_SKEW = 60;

    public static void register(final BedrockProtocol protocol) {
        protocol.registerClientbound(ClientboundBedrockPackets.NETWORK_SETTINGS, null, wrapper -> {
            wrapper.cancel();
            final HandshakeStorage handshakeStorage = wrapper.user().get(HandshakeStorage.class);
            final AuthData authData = wrapper.user().get(AuthData.class);

            final int threshold = wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE); // compression threshold
            final PacketCompressionAlgorithm algorithm = PacketCompressionAlgorithm.getByValue(wrapper.read(BedrockTypes.UNSIGNED_SHORT_LE), PacketCompressionAlgorithm.None); // compression algorithm
            Via.getManager().getProviders().get(NettyPipelineProvider.class).enableCompression(wrapper.user(), algorithm, threshold);

            boolean isSelfSigned = false;
            try {
                if (authData.getMultiplayerToken() != null) {
                    Jwts.parser().clockSkewSeconds(CLOCK_SKEW).verifyWith(authData.getSessionKeyPair().getPublic()).build().parseSignedClaims(authData.getMultiplayerToken());
                    isSelfSigned = true;
                }
            } catch (JwtException e) {
                isSelfSigned = false;
            }

            // Build skinJwt now (deferred from HELLO handler to allow async Java skin fetch)
            if (authData.getSkinJwt() == null) {
                authData.setSkinJwt(Jwts.builder()
                        .signWith(authData.getSessionKeyPair().getPrivate(), Jwts.SIG.ES384)
                        .header().add("x5u", Base64.getEncoder().encodeToString(authData.getSessionKeyPair().getPublic().getEncoded())).and()
                        .claims(Via.getManager().getProviders().get(SkinProvider.class).getClientPlayerSkin(wrapper.user()))
                        .compact());
            }

            final JsonObject authInfoObj = new JsonObject();
            final List<String> certificateChain = authData.getCertificateChain();
            final boolean fullAuth = (authData.getMultiplayerToken() != null && !isSelfSigned) || certificateChain.size() == 3;
            authInfoObj.addProperty("AuthenticationType", (fullAuth ? AuthenticationType.Full : AuthenticationType.SelfSigned).ordinal());
            if (!certificateChain.isEmpty()) {
                final JsonObject certificateChainObj = new JsonObject();
                certificateChainObj.add("chain", certificateChain.stream().collect(JsonArray::new, JsonArray::add, JsonArray::addAll));
                authInfoObj.addProperty("Certificate", certificateChainObj.toString());
            } else {
                authInfoObj.addProperty("Certificate", "{\"chain\":[\"..\"]}\n");
            }
            authInfoObj.addProperty("Token", authData.getMultiplayerToken() != null ? authData.getMultiplayerToken() : "");
            final String authInfo = authInfoObj.toString();

            final PacketWrapper login = PacketWrapper.create(ServerboundBedrockPackets.LOGIN, wrapper.user());
            login.write(Types.INT, resolveBedrockProtocolVersion(handshakeStorage)); // protocol version
            login.write(BedrockTypes.UNSIGNED_VAR_INT, authInfo.length() + authData.getSkinJwt().length() + Integer.BYTES * 2); // length
            login.write(BedrockTypes.ASCII_STRING, authInfo); // auth info
            login.write(BedrockTypes.ASCII_STRING, authData.getSkinJwt()); // client properties
            login.sendToServer(BedrockProtocol.class);
        });
        protocol.registerClientbound(ClientboundBedrockPackets.SERVER_TO_CLIENT_HANDSHAKE, null, wrapper -> {
            wrapper.cancel();
            final KeyPair sessionKeyPair = wrapper.user().get(AuthData.class).getSessionKeyPair();
            final Jws<Claims> jwt = Jwts.parser().clockSkewSeconds(CLOCK_SKEW).keyLocator(CryptUtil.X5U_KEY_LOCATOR).build().parseSignedClaims(wrapper.read(BedrockTypes.STRING)); // jwt
            final byte[] salt = Base64.getDecoder().decode(jwt.getPayload().get("salt", String.class));
            final SecretKey secretKey = ecdhKeyExchange(sessionKeyPair.getPrivate(), CryptUtil.X5U_KEY_LOCATOR.locate(jwt.getHeader()), salt);
            Via.getManager().getProviders().get(NettyPipelineProvider.class).enableEncryption(wrapper.user(), secretKey);

            final PacketWrapper clientToServerHandshake = PacketWrapper.create(ServerboundBedrockPackets.CLIENT_TO_SERVER_HANDSHAKE, wrapper.user());
            clientToServerHandshake.sendToServer(BedrockProtocol.class);
        });

        protocol.registerServerboundTransition(ServerboundHandshakePackets.CLIENT_INTENTION, null, wrapper -> {
            wrapper.cancel();
            final int protocolVersion = wrapper.read(Types.VAR_INT); // protocol version
            final String hostname = wrapper.read(Types.STRING); // hostname
            final int port = wrapper.read(Types.UNSIGNED_SHORT); // port
            wrapper.user().put(HandshakeStorage.fromHandshake(protocolVersion, hostname, port));
        });
        protocol.registerServerboundTransition(ServerboundLoginPackets.HELLO, ServerboundBedrockPackets.REQUEST_NETWORK_SETTINGS, wrapper -> {
            final HandshakeStorage handshakeStorage = wrapper.user().get(HandshakeStorage.class);

            if (!ViaBedrock.getConfig().shouldDisableServerBlacklist() && ServerBlacklist.isBlacklisted(handshakeStorage.hostname())) {
                wrapper.cancel();
                try {
                    final PacketWrapper loginDisconnect = PacketWrapper.create(ClientboundLoginPackets.LOGIN_DISCONNECT, wrapper.user());
                    PacketFactory.writeJavaDisconnect(loginDisconnect, "§cThis server is blacklisted by ViaBedrock because the server is known to ban players joining with ViaBedrock (Due to the server's anti-cheat).\n\n§7If you want to join the server anyway, set disable-server-blacklist to true in the ViaBedrock config file.");
                    loginDisconnect.send(BedrockProtocol.class);
                } catch (Throwable ignored) {
                }
                if (wrapper.user().getChannel() != null) {
                    wrapper.user().getChannel().flush();
                    wrapper.user().getChannel().close();
                }
                return;
            }

            final String javaUsername = wrapper.read(Types.STRING); // username
            final UUID javaUuid = wrapper.read(Types.UUID); // uuid
            wrapper.write(Types.INT, resolveBedrockProtocolVersion(handshakeStorage)); // protocol version

            final ProtocolInfo protocolInfo = wrapper.user().getProtocolInfo();
            protocolInfo.setUsername(javaUsername);
            protocolInfo.setUuid(javaUuid);

            try {
                validateAndFillAuthData(wrapper.user(), javaUsername, javaUuid, handshakeStorage);
            } catch (Throwable e) {
                throw new RuntimeException("Could not validate and fill auth data", e);
            }
        });
        protocol.registerServerboundTransition(ServerboundLoginPackets.LOGIN_ACKNOWLEDGED, null, PacketWrapper::cancel);
    }

    private static void validateAndFillAuthData(final UserConnection user, final String javaUsername, final UUID javaUuid) {
        validateAndFillAuthData(user, javaUsername, javaUuid, user.get(HandshakeStorage.class));
    }

    private static void validateAndFillAuthData(final UserConnection user, final String javaUsername, final UUID javaUuid, final HandshakeStorage handshakeStorage) {
        if (user.has(AuthData.class)) { // Externally supplied auth data
            final AuthData authData = user.get(AuthData.class);
            if (authData.getMojangJwt() != null && authData.getSelfSignedJwt() == null) {
                final KeyPair sessionKeyPair = authData.getSessionKeyPair();
                final Jwt mojangJwt = Jwt.parse(authData.getMojangJwt());
                authData.setSelfSignedJwt(Jwts.builder()
                        .signWith(sessionKeyPair.getPrivate(), Jwts.SIG.ES384)
                        .header().add("x5u", Base64.getEncoder().encodeToString(sessionKeyPair.getPublic().getEncoded())).and()
                        .claim("certificateAuthority", true)
                        .claim("identityPublicKey", mojangJwt.header().get("x5u").getAsString())
                        .expiration(Date.from(Instant.now().plus(2, ChronoUnit.DAYS)))
                        .notBefore(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                        .compact());
            }
        } else {
            final Instant now = Instant.now();
            final KeyPair sessionKeyPair = CryptUtil.generateEcdsa384KeyPair();
            final String encodedPublicKey = Base64.getEncoder().encodeToString(sessionKeyPair.getPublic().getEncoded());
            // Xbox/Waterdog/MOT expect a decimal XUID ([1-9]\d{0,19}). Keep the same 32-bit
            // identity source as the previous hex format, but emit it as an unsigned decimal.
            final String xuid = decimalXuid(javaUuid, javaUsername);
            final UUID identity = javaUuid != null
                    ? javaUuid
                    : UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + xuid).getBytes(StandardCharsets.UTF_8));
            if (!ViaBedrock.getConfig().getViaProxyAuthSecret().isEmpty() || ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                final Map<String, Object> extraData = new HashMap<>();
                extraData.put("displayName", javaUsername);
                extraData.put("XUID", xuid);
                extraData.put("identity", identity);
                if (ViaBedrock.getConfig().shouldEmulateNetEaseClient()) {
                    extraData.putAll(createNetEaseExtraData(javaUsername, javaUuid, xuid,
                            handshakeStorage != null ? handshakeStorage.device() : JavaClientDevice.JAVA_EDITION, user));
                }

                final String identityJwt = Jwts.builder()
                        .signWith(sessionKeyPair.getPrivate(), Jwts.SIG.ES384)
                        .header().add("x5u", encodedPublicKey).and()
                        .claim("identityPublicKey", encodedPublicKey)
                        .claim("extraData", extraData)
                        .expiration(Date.from(now.plus(365, ChronoUnit.DAYS)))
                        .notBefore(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                        .compact();
                user.put(AuthData.fromIdentityJwt(identityJwt, sessionKeyPair, UUID.randomUUID()));
            } else {
                final String multiplayerToken = Jwts.builder()
                        .signWith(sessionKeyPair.getPrivate(), Jwts.SIG.ES384)
                        .header().add("x5u", encodedPublicKey).and()
                        .claim(Claims.AUDIENCE, "api://auth-minecraft-services/multiplayer") // audience
                        .claim("cpk", encodedPublicKey) // client public key
                        .claim("leguuid", identity) // ? (Should be the same as SelfSignedId)
                        .claim("mid", xuid.toUpperCase(Locale.ROOT)) // PlayFab entity id
                        .claim("nid", "") // ?
                        .claim("nname", "") // ?
                        .claim("pid", "") // ?
                        .claim("pname", "") // ?
                        .claim("xid", xuid) // xuid
                        .claim("xname", javaUsername) // display name
                        .issuedAt(Date.from(now))
                        .expiration(Date.from(now.plus(365, ChronoUnit.DAYS)))
                        .compact();
                user.put(new AuthData(multiplayerToken, sessionKeyPair));
            }
        }

        final AuthData authData = user.get(AuthData.class);
        if (authData.getDisplayName() == null || authData.getXuid() == null) {
            if (authData.getIdentityJwt() != null) {
                final Jwt identityJwt = Jwt.parse(authData.getIdentityJwt());
                final JsonObject extraData = identityJwt.payload().getAsJsonObject("extraData");
                authData.setDisplayName(extraData.get("displayName").getAsString());
                authData.setXuid(extraData.get("XUID").getAsString());
            } else {
                authData.setXuid(decimalXuid(javaUuid, javaUsername));
            }
        }
        if (authData.getDeviceId() == null) {
            authData.setDeviceId(UUID.randomUUID()); // Not correct, but should be fine for most cases
        }
        if (authData.getSelfSignedId() == null) {
            authData.setSelfSignedId(javaUuid != null ? javaUuid : UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + authData.getXuid()).getBytes(StandardCharsets.UTF_8))); // Not correct, but should be fine for most cases
        }
        if (authData.getClientRandomId() == null) {
            authData.setClientRandomId(FNV1.fnv1_64(authData.getSelfSignedId().toString().getBytes(StandardCharsets.UTF_8))); // Not correct, but should be fine for most cases
        }
        if (authData.getSkinJwt() == null) {
            final int skinFetchTimeout = ViaBedrock.getConfig().getJavaSkinFetchTimeout();
            configureJavaSkinFuture(
                    authData,
                    user.get(ExternalJavaSkinStorage.class),
                    skinFetchTimeout,
                    javaUuid,
                    uuid -> Via.getManager().getProviders().get(SkinProvider.class).fetchJavaSkinAsync(uuid));
        }
    }

    static int resolveBedrockProtocolVersion(final HandshakeStorage handshakeStorage) {
        final int configured = ViaBedrock.getConfig().getNetEaseProtocolVersion();
        if (ViaBedrock.getConfig().shouldEmulateNetEaseClient() && configured > 0) {
            return configured;
        }
        return handshakeStorage.protocolVersion();
    }

    static String decimalXuid(final UUID javaUuid, final String javaUsername) {
        final int bits = javaUuid != null
                ? (int) javaUuid.getLeastSignificantBits()
                : (int) FNV1.fnv1_64(javaUsername.getBytes(StandardCharsets.UTF_8));
        final String decimal = Long.toUnsignedString(Integer.toUnsignedLong(bits));
        return decimal.equals("0") ? "1" : decimal;
    }

    static java.util.Map<String, Object> createNetEaseExtraData(final String javaUsername, final UUID javaUuid, final String xuid) {
        return createNetEaseExtraData(javaUsername, javaUuid, xuid, JavaClientDevice.JAVA_EDITION, null);
    }

    static java.util.Map<String, Object> createNetEaseExtraData(final String javaUsername, final UUID javaUuid, final String xuid,
                                                                final JavaClientDevice device) {
        return createNetEaseExtraData(javaUsername, javaUuid, xuid, device, null);
    }

    static java.util.Map<String, Object> createNetEaseExtraData(final String javaUsername, final UUID javaUuid, final String xuid,
                                                                final JavaClientDevice device, final UserConnection user) {
        final java.util.Map<String, Object> extraData = new java.util.HashMap<>();
        final long uid = javaUuid != null ? Math.abs(javaUuid.getMostSignificantBits()) : Math.abs(FNV1.fnv1_64(javaUsername.getBytes(StandardCharsets.UTF_8)));
        extraData.put("uid", uid);
        extraData.put("netease_sid", "je-" + (javaUuid != null ? javaUuid.toString().replace("-", "") : xuid));
        extraData.put("platform", "pc_java");
        extraData.put("java_version", PlayerIdentity.javaVersionName(user));
        extraData.put("os_name", device != null ? device.osName() : "windows");
        extraData.put("env", "release");
        extraData.put("engineVersion", ViaBedrock.getConfig().getNetEaseGameVersion());
        extraData.put("patchVersion", ViaBedrock.getConfig().getNetEaseGameVersion());
        extraData.put("bit", "64");
        return extraData;
    }

    static void configureJavaSkinFuture(
            final AuthData authData,
            final ExternalJavaSkinStorage externalSkin,
            final int mojangFetchTimeoutMs,
            final UUID javaUuid,
            final Function<UUID, CompletableFuture<JavaSkinData>> mojangFetcher) {
        if (externalSkin != null) {
            authData.setJavaSkinFuture(externalSkin.future());
            authData.setExternalJavaSkinSource(externalSkin.source());
            authData.setJavaSkinWaitTimeoutMs(externalSkin.waitTimeoutMs());
            return;
        }
        if (mojangFetchTimeoutMs > 0 && javaUuid != null && javaUuid.version() == 4) {
            authData.setJavaSkinFuture(mojangFetcher.apply(javaUuid));
            authData.setExternalJavaSkinSource(null);
            authData.setJavaSkinWaitTimeoutMs(mojangFetchTimeoutMs);
        }
    }

    private static SecretKey ecdhKeyExchange(final PrivateKey localPrivateKey, final Key remotePublicKey, final byte[] salt) {
        try {
            final KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
            ecdh.init(localPrivateKey);
            ecdh.doPhase(remotePublicKey, true);
            final byte[] sharedSecret = ecdh.generateSecret();

            final MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(salt);
            sha256.update(sharedSecret);
            return new SecretKeySpec(sha256.digest(), "AES");
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to perform ECDH key exchange", e);
        }
    }

}
