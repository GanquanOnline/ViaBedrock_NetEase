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
package net.raphimc.viabedrock.protocol.storage;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.StoredObject;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.type.Types;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.cache.PackAlias;
import net.raphimc.viabedrock.api.resourcepack.content.ZipContent;
import net.raphimc.viabedrock.api.resourcepack.http.BedrockPackDownloader;
import net.raphimc.viabedrock.api.resourcepack.http.RemotePackServiceClient;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ResourcePackResponse;
import net.raphimc.viabedrock.protocol.model.Experiment;
import net.raphimc.viabedrock.protocol.provider.ResourcePackProvider;
import net.raphimc.viabedrock.protocol.types.BedrockTypes;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;

public class ResourcePackLoadStateTracker extends StoredObject {

    private final Map<ResourcePack.Key, Info> requests = new HashMap<>();
    private final Map<UUID, List<Info>> requestsById = new HashMap<>();
    private final Map<ResourcePack.Key, PackAlias> aliases = new HashMap<>();
    private final Map<ResourcePack.Key, ResourcePack> resourcePacks = new ConcurrentHashMap<>();
    private final AtomicInteger remainingResourcePackCount = new AtomicInteger();
    private final AtomicBoolean sourceLoadStarted = new AtomicBoolean();
    private final AtomicBoolean bedrockDownloadsFinishedClaimed = new AtomicBoolean();
    private final CompletableFuture<Void> loadFuture = new CompletableFuture<>();
    private final CompletableFuture<JavaPackOutcome> javaPackTerminalFuture = new CompletableFuture<>();
    private final CompletableFuture<RemoteDeliveryOutcome> remoteDeliveryFuture = new CompletableFuture<>();
    private final CompletableFuture<RemotePackServiceClient.Lookup> remotePackLookupFuture = new CompletableFuture<>();
    private final CompletableFuture<ResourcePackStorage> resourcePackStackFuture = new CompletableFuture<>();
    private final CompletableFuture<ResourcePackStorage> negotiationReadyFuture = this.resourcePackStackFuture
            .thenCombine(this.javaPackTerminalFuture, (resourcePackStorage, ignored) -> resourcePackStorage);
    private final String announcementSequenceFingerprint;
    private UUID httpToken;
    private volatile RemotePackServiceClient.Lookup remotePackLookup;
    private final AtomicBoolean remotePackCancellationClaimed = new AtomicBoolean();
    private final AtomicBoolean resourcePackStackFinishedClaimed = new AtomicBoolean();
    private JavaPackPhase javaPackPhase = JavaPackPhase.PENDING;
    private RemoteDeliveryPhase remoteDeliveryPhase = RemoteDeliveryPhase.UNDECIDED;
    private StackPhase stackPhase = StackPhase.NOT_RECEIVED;
    private StartGamePhase startGamePhase = StartGamePhase.NOT_SEEN;
    private StackAnnouncement stackAnnouncement;

    public ResourcePackLoadStateTracker(final UserConnection user, final ResourcePackLoadStateTracker.Info[] infos) {
        this(user, infos, AnnouncementHeader.compatibility(), backendScope(user));
    }

    public ResourcePackLoadStateTracker(final UserConnection user, final ResourcePackLoadStateTracker.Info[] infos,
                                        final AnnouncementHeader announcementHeader) {
        this(user, infos, announcementHeader, backendScope(user));
    }

    ResourcePackLoadStateTracker(final UserConnection user, final ResourcePackLoadStateTracker.Info[] infos,
                                 final AnnouncementHeader announcementHeader, final String backendScope) {
        super(user);
        Objects.requireNonNull(infos, "infos");
        Objects.requireNonNull(announcementHeader, "announcementHeader");
        Objects.requireNonNull(backendScope, "backendScope");
        this.announcementSequenceFingerprint = fingerprintAnnouncementSequence(announcementHeader, infos);
        for (Info info : infos) {
            final Info previous = this.requests.putIfAbsent(info.key(), info);
            if (previous != null && !previous.hasSameAnnouncement(info)) {
                throw new IllegalArgumentException(
                        "Conflicting RESOURCE_PACKS_INFO declarations for resource pack " + info.key());
            }
            if (previous == null) {
                this.requestsById.computeIfAbsent(info.key().id(), ignored -> new ArrayList<>()).add(info);
            }
            this.aliases.put(info.key(), info.alias(backendScope));
        }
        this.remainingResourcePackCount.set(this.requests.size());
    }

    public Info getRequest(final ResourcePack.Key key) {
        return this.requests.get(key);
    }

    /** 把协议中带可选版本的传输名解析成某个已通告的资源包身份。 */
    public ResolvedRequest resolveTransferRequest(final String transferName) {
        Objects.requireNonNull(transferName, "transferName");
        final int versionSeparator = transferName.indexOf('_');
        if (versionSeparator >= 0) {
            final ResourcePack.Key key = ResourcePack.Key.fromString(transferName);
            final Info info = this.requests.get(key);
            if (info == null) {
                throw new IllegalArgumentException("Unannounced resource pack transfer: " + transferName);
            }
            return new ResolvedRequest(key, info);
        }

        final UUID id;
        try {
            id = UUID.fromString(transferName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid resource pack transfer name: " + transferName, e);
        }
        final List<Info> candidates = this.requestsById.getOrDefault(id, List.of());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Unannounced resource pack transfer: " + transferName);
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("Ambiguous versionless resource pack transfer " + transferName
                    + " matches " + candidates.size() + " announced versions");
        }
        final Info info = candidates.getFirst();
        return new ResolvedRequest(info.key(), info);
    }

    public PackAlias getAlias(final ResourcePack.Key key) {
        return this.aliases.get(key);
    }

    public String announcementSequenceFingerprint() {
        return this.announcementSequenceFingerprint;
    }

    public boolean hasSameAnnouncementSequence(final AnnouncementHeader header, final Info[] infos) {
        return this.announcementSequenceFingerprint.equals(fingerprintAnnouncementSequence(header, infos));
    }

    public void addRemoteResourcePack(final ResourcePack resourcePack) {
        try {
            Via.getManager().getProviders().get(ResourcePackProvider.class).save(resourcePack);
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to save resource pack: " + resourcePack.key(), e);
        }
        this.addLocalResourcePack(resourcePack);
    }

    public void addLocalResourcePack(final ResourcePack resourcePack) {
        if (!this.requests.containsKey(resourcePack.key())) {
            throw new IllegalArgumentException(
                    "Loaded resource pack was not declared by RESOURCE_PACKS_INFO: " + resourcePack.key());
        }
        if (this.resourcePacks.putIfAbsent(resourcePack.key(), resourcePack) != null) {
            return;
        }
        if (this.remainingResourcePackCount.decrementAndGet() == 0) {
            ViaBedrock.getPlatform().getLogger().log(Level.INFO, "All resource packs have been loaded");
            this.loadFuture.complete(null);
        }
    }

    public ResourcePack getResourcePack(final ResourcePack.Key key) {
        return this.resourcePacks.get(key);
    }

    public CompletableFuture<Void> loadRequestedResourcePacks() {
        if (!this.sourceLoadStarted.compareAndSet(false, true)) {
            return this.loadFuture;
        }
        final ResourcePackDownloadTracker connectionTracker = this.user().get(ResourcePackDownloadTracker.class);
        if (connectionTracker == null) {
            this.loadFuture.completeExceptionally(
                    new IllegalStateException("Resource pack download tracker is unavailable"));
            return this.loadFuture;
        }
        final CompletableFuture<Void> sessionLoadFuture = this.loadFuture;
        connectionTracker.trackConnectionStage(sessionLoadFuture);
        if (sessionLoadFuture.isDone()) {
            return sessionLoadFuture;
        }

        final WeakReference<ResourcePackLoadStateTracker> trackerReference = new WeakReference<>(this);
        final List<CompletableFuture<Void>> asyncTasks = new ArrayList<>();
        final List<ResourcePack.Key> downloadList = Collections.synchronizedList(new ArrayList<>());
        final boolean sharedCache = ViaBedrock.isSharedResourcePackCacheEnabled();
        final ResourcePackProvider resourcePackProvider =
                Via.getManager().getProviders().get(ResourcePackProvider.class);
        for (Info info : this.requests.values()) {
            if (this.mayUseBundledResourcePack(info.key(), sharedCache)
                    && BedrockProtocol.MAPPINGS.getBedrockResourcePacks().containsKey(info.key())) {
                this.addLocalResourcePack(BedrockProtocol.MAPPINGS.getBedrockResourcePacks().get(info.key()));
            } else if (!sharedCache && resourcePackProvider.has(info.key())) {
                asyncTasks.add(ViaBedrock.getResourcePackWorkScheduler().runIo(() -> {
                    final ResourcePackLoadStateTracker tracker = trackerReference.get();
                    if (!isConnectionActive(tracker)) {
                        return;
                    }
                    try {
                        tracker.addLocalResourcePack(resourcePackProvider.load(info.key()));
                    } catch (Throwable e) {
                        if (!(e.getCause() instanceof InterruptedException)) {
                            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to load resource pack: " + info.key(), e);
                            downloadList.add(info.key());
                        }
                    }
                }));
            } else if (info.httpUrl() != null) {
                if (sharedCache) {
                    final PackAlias alias = this.getAlias(info.key());
                    final boolean trustAlias = ViaBedrock.getConfig().shouldTrustDeclaredPackAlias();
                    final CompletableFuture<ResourcePack> sharedSource =
                            ViaBedrock.getResourcePackArchiveStore().loadFromStreamSource(
                                    info.httpUrl().toExternalForm(), alias, this.announcementSequenceFingerprint,
                                    trustAlias, info.contentKey(), output -> {
                                final BedrockPackDownloader downloader = new BedrockPackDownloader(info.httpUrl());
                                final long maxBytes = (long) ViaBedrock.getConfig().getResourcePackMaxArchiveMiB()
                                        * 1024L * 1024L;
                                final long actualSize = downloader.downloadTo(output, maxBytes);
                                validateCdnSize(info.announcedSize(), actualSize);
                            });
                    final CompletableFuture<ResourcePack> connectionSource = detachedWaiter(sharedSource);
                    connectionTracker.trackConnectionStage(connectionSource);
                    asyncTasks.add(connectionSource.thenAccept(pack -> {
                        final ResourcePackLoadStateTracker tracker = trackerReference.get();
                        if (isConnectionActive(tracker)) {
                            tracker.addLocalResourcePack(pack);
                        }
                    }).exceptionally(e -> {
                        recordCdnFailure(e, info.key(), downloadList);
                        return null;
                    }));
                } else {
                    final CompletableFuture<Void> legacyDownload =
                            ViaBedrock.getResourcePackWorkScheduler().runIo(() -> {
                                try {
                                    final BedrockPackDownloader downloader = new BedrockPackDownloader(info.httpUrl());
                                    final long maxBytes = (long) ViaBedrock.getConfig().getResourcePackMaxArchiveMiB()
                                            * 1024L * 1024L;
                                    final byte[] archive = downloader.download(maxBytes);
                                    validateCdnSize(info.announcedSize(), archive.length);
                                    final ResourcePack pack = new ResourcePack(new ZipContent(archive));
                                    final ResourcePackLoadStateTracker tracker = trackerReference.get();
                                    if (isConnectionActive(tracker)) {
                                        tracker.addRemoteResourcePack(pack);
                                    }
                                } catch (Throwable e) {
                                    recordCdnFailure(e, info.key(), downloadList);
                                }
                            });
                    connectionTracker.trackConnectionStage(legacyDownload);
                    asyncTasks.add(legacyDownload.exceptionally(e -> null));
                }
            } else {
                downloadList.add(info.key());
            }
        }
        CompletableFuture.allOf(asyncTasks.toArray(CompletableFuture[]::new))
                .orTimeout(ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds(), TimeUnit.SECONDS)
                .thenRun(() -> {
            final ResourcePackLoadStateTracker tracker = trackerReference.get();
            if (!isConnectionActive(tracker)) {
                sessionLoadFuture.completeExceptionally(
                        new CancellationException("Resource pack connection closed while loading packs"));
                return;
            }
            if (!downloadList.isEmpty()) {
                ViaBedrock.getPlatform().getLogger().log(Level.INFO, "Downloading " + downloadList.size() + " resource packs over the game protocol");
                final PacketWrapper resourcePackClientResponse = PacketWrapper.create(
                        ServerboundBedrockPackets.RESOURCE_PACK_CLIENT_RESPONSE, tracker.user());
                resourcePackClientResponse.write(Types.BYTE, (byte) ResourcePackResponse.Downloading.getValue()); // 状态
                resourcePackClientResponse.write(BedrockTypes.SHORT_LE_STRING_ARRAY, downloadList.stream().map(ResourcePack.Key::toString).toArray(String[]::new)); // 正在下载的包
                resourcePackClientResponse.scheduleSendToServer(BedrockProtocol.class);
            } else {
                sessionLoadFuture.complete(null);
            }
        }).exceptionally(e -> {
            sessionLoadFuture.completeExceptionally(e);
            return null;
        });
        return sessionLoadFuture.orTimeout(
                ViaBedrock.getConfig().getResourcePackCacheBuildTimeoutSeconds(), TimeUnit.SECONDS);
    }

    static <T> CompletableFuture<T> detachedWaiter(final CompletionStage<T> sharedSource) {
        final CompletableFuture<T> dependent = new CompletableFuture<>();
        sharedSource.whenComplete((value, error) -> {
            if (error == null) {
                dependent.complete(value);
            } else {
                dependent.completeExceptionally(error);
            }
        });
        return dependent;
    }

    private static boolean isConnectionActive(final ResourcePackLoadStateTracker tracker) {
        return tracker != null && tracker.user().getChannel().isActive()
                && tracker.user().get(ResourcePackLoadStateTracker.class) == tracker;
    }

    private static Throwable unwrap(final Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void recordCdnFailure(final Throwable error, final ResourcePack.Key key,
                                         final List<ResourcePack.Key> downloadList) {
        final Throwable cause = unwrap(error);
        if (cause instanceof CancellationException || cause instanceof InterruptedException) {
            return;
        }
        ViaBedrock.getPlatform().getLogger().log(
                Level.WARNING, "Failed to download resource pack: " + key, error);
        downloadList.add(key);
    }

    static void validateCdnSize(final long announcedSize, final long actualSize) throws IOException {
        if (announcedSize >= 0L && actualSize != announcedSize) {
            throw new IOException("CDN resource pack size changed during acquisition: "
                    + actualSize + " != " + announcedSize);
        }
    }

    public void loadUnrequestedResourcePacks(final ResourcePack.Key[] keys) {
        final boolean sharedCache = ViaBedrock.isSharedResourcePackCacheEnabled();
        final ResourcePackProvider resourcePackProvider =
                Via.getManager().getProviders().get(ResourcePackProvider.class);
        for (ResourcePack.Key key : keys) {
            if (!this.mayUseBundledResourcePack(key, sharedCache)) {
                continue;
            }
            if (BedrockProtocol.MAPPINGS.getBedrockResourcePacks().containsKey(key)) {
                this.resourcePacks.put(key, BedrockProtocol.MAPPINGS.getBedrockResourcePacks().get(key));
            } else if (!sharedCache && resourcePackProvider.has(key)) {
                try {
                    this.resourcePacks.put(key, resourcePackProvider.load(key));
                } catch (Throwable e) {
                    ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "Failed to load resource pack: " + key, e);
                }
            }
        }
    }

    boolean mayUseBundledResourcePack(final ResourcePack.Key key, final boolean sharedCache) {
        // 仅来自资源包栈的内置项是本地输入；带相同键的 INFO 声明是不可信输入。
        return !sharedCache || !this.requests.containsKey(key);
    }

    public CompletableFuture<PreparedStack> prepareResourcePackStackAsync(final ResourcePack.Key[] keys,
                                                                           final String[] selectedSubpacks) {
        final StackPreparationExecutor executor;
        if (ViaBedrock.getResourcePackWorkScheduler() != null) {
            executor = ViaBedrock.getResourcePackWorkScheduler()::submitIo;
        } else {
            executor = ResourcePackLoadStateTracker::submitViaWorker;
        }
        return this.prepareResourcePackStackAsync(keys, selectedSubpacks, executor);
    }

    CompletableFuture<PreparedStack> prepareResourcePackStackAsync(final ResourcePack.Key[] keys,
                                                                    final String[] selectedSubpacks,
                                                                    final StackPreparationExecutor executor) {
        if (keys.length != selectedSubpacks.length) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Resource pack and subpack stack sizes differ"));
        }
        final ResourcePack.Key[] keysSnapshot = keys.clone();
        final String[] subpacksSnapshot = selectedSubpacks.clone();
        try {
            return executor.submit(() -> this.prepareResourcePackStack(keysSnapshot, subpacksSnapshot));
        } catch (Throwable e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    PreparedStack prepareResourcePackStack(final ResourcePack.Key[] keys, final String[] selectedSubpacks) {
        this.loadUnrequestedResourcePacks(keys);
        final List<ResourcePack> resourcePacks = new ArrayList<>(keys.length);
        final List<String> resolvedSubpacks = new ArrayList<>(keys.length);
        final List<ResourcePack> decryptedLegacyPacks = new ArrayList<>();
        final boolean sharedCache = ViaBedrock.isSharedResourcePackCacheEnabled();
        for (int i = 0; i < keys.length; i++) {
            final ResourcePack.Key key = keys[i];
            final ResourcePack resourcePack = this.getResourcePack(key);
            if (resourcePack == null) {
                throw this.missingResourcePackException(key, sharedCache);
            }
            if (sharedCache && !this.requests.containsKey(key)
                    && !BedrockProtocol.MAPPINGS.getBedrockResourcePacks().containsKey(key)) {
                throw new IllegalStateException(
                        "Unannounced resource pack cannot enter a shared stack: " + key);
            }

            final Info info = this.getRequest(key);
            if (resourcePack.isContentEncrypted()) {
                if (sharedCache) {
                    throw new IllegalStateException(
                            "Shared CAS resource pack remained encrypted after verification: " + key);
                }
                if (info != null && info.contentKey().length > 0) {
                    resourcePack.decryptContent(info.contentKey(), info.contentId());
                    decryptedLegacyPacks.add(resourcePack);
                }
            }
            resourcePacks.add(resourcePack);
            resolvedSubpacks.add(selectedSubpacks[i]);
        }

        if (!decryptedLegacyPacks.isEmpty()) {
            final ResourcePackProvider provider = Via.getManager().getProviders().get(ResourcePackProvider.class);
            for (ResourcePack resourcePack : decryptedLegacyPacks) {
                try {
                    provider.save(resourcePack);
                } catch (Throwable e) {
                    ViaBedrock.getPlatform().getLogger().log(
                            Level.WARNING, "Failed to save resource pack: " + resourcePack.key(), e);
                }
            }
        }
        return new PreparedStack(resourcePacks, resolvedSubpacks);
    }

    IllegalStateException missingResourcePackException(final ResourcePack.Key key,
                                                       final boolean sharedCache) {
        return new IllegalStateException("Missing resource pack required by stack: " + key
                + " (declaredInInfo=" + this.requests.containsKey(key)
                + ", loaded=" + this.resourcePacks.size()
                + ", announced=" + this.requests.size()
                + ", sharedCache=" + sharedCache
                + ", announcedVersionsForUuid="
                + this.requestsById.getOrDefault(key.id(), List.of()).stream()
                .map(info -> info.key().version()).toList() + ')');
    }

    private static <T> CompletableFuture<T> submitViaWorker(final Callable<T> task) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Via.getPlatform().runAsync(() -> {
                try {
                    future.complete(task.call());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Throwable e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    public synchronized JavaPackPhase javaPackPhase() {
        return this.javaPackPhase;
    }

    public synchronized void markJavaClientAccepted() {
        if (this.javaPackPhase == JavaPackPhase.ACCEPTED || this.javaPackPhase == JavaPackPhase.LOADED) return;
        if (this.javaPackPhase != JavaPackPhase.PENDING) {
            throw new IllegalStateException(
                    "Java resource pack cannot be accepted from state " + this.javaPackPhase);
        }
        this.javaPackPhase = JavaPackPhase.ACCEPTED;
    }

    public synchronized void markJavaClientLoaded() {
        if (this.javaPackPhase == JavaPackPhase.LOADED) return;
        if (this.javaPackPhase != JavaPackPhase.ACCEPTED) {
            throw new IllegalStateException(
                    "Java resource pack cannot finish loading from state " + this.javaPackPhase);
        }
        this.javaPackPhase = JavaPackPhase.LOADED;
        this.javaPackTerminalFuture.complete(JavaPackOutcome.LOADED);
    }

    public synchronized void markJavaClientDeclined() {
        if (this.javaPackPhase == JavaPackPhase.DECLINED) return;
        if (this.javaPackPhase != JavaPackPhase.PENDING) {
            throw new IllegalStateException(
                    "Java resource pack cannot be declined from state " + this.javaPackPhase);
        }
        this.javaPackPhase = JavaPackPhase.DECLINED;
        this.javaPackTerminalFuture.complete(JavaPackOutcome.DECLINED);
    }

    public synchronized void markJavaClientFailed() {
        if (this.javaPackPhase == JavaPackPhase.FAILED) return;
        if (this.javaPackPhase == JavaPackPhase.LOADED || this.javaPackPhase == JavaPackPhase.DECLINED) {
            throw new IllegalStateException(
                    "Java resource pack cannot fail from state " + this.javaPackPhase);
        }
        this.javaPackPhase = JavaPackPhase.FAILED;
        this.javaPackTerminalFuture.complete(JavaPackOutcome.FAILED);
    }

    public CompletableFuture<JavaPackOutcome> javaPackTerminalFuture() {
        return this.javaPackTerminalFuture;
    }

    public boolean claimBedrockDownloadsFinished() {
        return this.bedrockDownloadsFinishedClaimed.compareAndSet(false, true);
    }

    public UUID httpToken() {
        return this.httpToken;
    }

    public void setHttpToken(final UUID httpToken) {
        if (this.httpToken != null) {
            throw new IllegalStateException("Resource pack HTTP token was already assigned");
        }
        this.httpToken = Objects.requireNonNull(httpToken, "httpToken");
    }

    public RemotePackServiceClient.Lookup remotePackLookup() {
        return this.remotePackLookup;
    }

    public CompletableFuture<RemotePackServiceClient.Lookup> remotePackLookupFuture() {
        return this.remotePackLookupFuture;
    }

    public CompletableFuture<RemoteDeliveryOutcome> remoteDeliveryFuture() {
        return this.remoteDeliveryFuture;
    }

    public synchronized CompletableFuture<RemoteDeliveryOutcome> requireRemoteDeliveryDecision() {
        if (this.remoteDeliveryPhase == RemoteDeliveryPhase.UNDECIDED) {
            final IllegalStateException error = new IllegalStateException(
                    "Remote resource pack delivery was not initialized by RESOURCE_PACKS_INFO");
            this.remoteDeliveryPhase = RemoteDeliveryPhase.FAILED;
            this.remotePackLookupFuture.completeExceptionally(error);
            this.remoteDeliveryFuture.completeExceptionally(error);
        }
        return this.remoteDeliveryFuture;
    }

    public synchronized RemoteDeliveryPhase remoteDeliveryPhase() {
        return this.remoteDeliveryPhase;
    }

    public synchronized void markRemoteDeliveryNotApplicable() {
        if (this.remoteDeliveryPhase == RemoteDeliveryPhase.NOT_APPLICABLE) return;
        if (this.remoteDeliveryPhase != RemoteDeliveryPhase.UNDECIDED) {
            throw new IllegalStateException(
                    "Remote resource pack delivery cannot become inapplicable from state "
                            + this.remoteDeliveryPhase);
        }
        this.remoteDeliveryPhase = RemoteDeliveryPhase.NOT_APPLICABLE;
        this.remoteDeliveryFuture.complete(RemoteDeliveryOutcome.NOT_APPLICABLE);
    }

    public CompletableFuture<RemotePackServiceClient.Lookup> startRemotePackLookup(
            final Supplier<? extends CompletionStage<RemotePackServiceClient.Lookup>> lookupStarter) {
        Objects.requireNonNull(lookupStarter, "lookupStarter");
        synchronized (this) {
            if (this.remoteDeliveryPhase != RemoteDeliveryPhase.UNDECIDED) {
                if (this.remoteDeliveryPhase == RemoteDeliveryPhase.LOOKUP_PENDING
                        || this.remoteDeliveryPhase == RemoteDeliveryPhase.LOOKUP_READY
                        || this.remoteDeliveryPhase == RemoteDeliveryPhase.CANCELLED
                        || this.remoteDeliveryPhase == RemoteDeliveryPhase.FAILED) {
                    return this.remotePackLookupFuture;
                }
                throw new IllegalStateException(
                        "Remote resource pack lookup cannot start from state " + this.remoteDeliveryPhase);
            }
            this.remoteDeliveryPhase = RemoteDeliveryPhase.LOOKUP_PENDING;
        }

        final CompletionStage<RemotePackServiceClient.Lookup> lookupStage;
        try {
            lookupStage = Objects.requireNonNull(lookupStarter.get(), "remotePackLookupStage");
        } catch (Throwable error) {
            this.completeRemotePackLookup(null, error);
            return this.remotePackLookupFuture;
        }
        try {
            lookupStage.whenComplete(this::completeRemotePackLookup);
        } catch (Throwable error) {
            this.failRemoteDelivery(error);
        }
        return this.remotePackLookupFuture;
    }

    public synchronized void failRemoteDelivery(final Throwable error) {
        Objects.requireNonNull(error, "error");
        if (this.remoteDeliveryPhase == RemoteDeliveryPhase.FAILED) return;
        if (this.remoteDeliveryPhase == RemoteDeliveryPhase.CANCELLED) {
            this.remotePackLookupFuture.completeExceptionally(error);
            return;
        }
        if (this.remoteDeliveryPhase != RemoteDeliveryPhase.UNDECIDED
                && this.remoteDeliveryPhase != RemoteDeliveryPhase.LOOKUP_PENDING) {
            return;
        }
        this.remoteDeliveryPhase = RemoteDeliveryPhase.FAILED;
        this.remotePackLookupFuture.completeExceptionally(error);
        this.remoteDeliveryFuture.completeExceptionally(error);
    }

    private void completeRemotePackLookup(final RemotePackServiceClient.Lookup lookup, final Throwable error) {
        if (error != null) {
            this.failRemoteDelivery(error);
            return;
        }
        if (lookup == null) {
            this.failRemoteDelivery(
                    new NullPointerException("Remote resource pack lookup completed without a result"));
            return;
        }
        final RemotePackServiceClient.Lookup presentLookup = lookup;
        synchronized (this) {
            this.remotePackLookup = presentLookup;
            this.remotePackLookupFuture.complete(presentLookup);
            if (this.remoteDeliveryPhase == RemoteDeliveryPhase.LOOKUP_PENDING) {
                this.remoteDeliveryPhase = RemoteDeliveryPhase.LOOKUP_READY;
                this.remoteDeliveryFuture.complete(RemoteDeliveryOutcome.LOOKUP_READY);
            }
        }
    }

    /** 认领唯一的取消任务，包括在认领之后才完成的查询。 */
    public synchronized CompletableFuture<RemotePackServiceClient.Lookup> claimRemotePackCancellationFuture() {
        if (this.remoteDeliveryPhase == RemoteDeliveryPhase.NOT_APPLICABLE
                || this.remoteDeliveryPhase == RemoteDeliveryPhase.UNDECIDED
                || this.remoteDeliveryPhase == RemoteDeliveryPhase.FAILED) {
            return null;
        }
        this.remoteDeliveryPhase = RemoteDeliveryPhase.CANCELLED;
        this.remoteDeliveryFuture.complete(RemoteDeliveryOutcome.CANCELLED);
        if (!this.remotePackCancellationClaimed.compareAndSet(false, true)) return null;
        return this.remotePackLookupFuture;
    }

    public synchronized boolean shouldPublishRemotePack() {
        return this.remoteDeliveryPhase == RemoteDeliveryPhase.LOOKUP_READY;
    }

    public synchronized StackStart beginResourcePackStack(final ResourcePack.Key[] keys,
                                                          final String[] selectedSubpacks) {
        return this.beginResourcePackStack(
                keys, selectedSubpacks, false, "", new Experiment[0], false, false);
    }

    public synchronized StackStart beginResourcePackStack(
            final ResourcePack.Key[] keys, final String[] selectedSubpacks,
            final boolean resourcePackRequired, final String baseGameVersion,
            final Experiment[] experiments, final boolean experimentsPreviouslyToggled,
            final boolean includeEditorPacks) {
        final StackAnnouncement announcement = new StackAnnouncement(
                keys, selectedSubpacks, resourcePackRequired, baseGameVersion,
                experiments, experimentsPreviouslyToggled, includeEditorPacks);
        if (this.stackPhase == StackPhase.NOT_RECEIVED) {
            this.stackPhase = StackPhase.BUILDING;
            this.stackAnnouncement = announcement;
            return StackStart.STARTED;
        }
        if (this.stackAnnouncement != null && this.stackAnnouncement.matches(announcement)) {
            return StackStart.DUPLICATE;
        }
        throw new IllegalStateException("Conflicting RESOURCE_PACK_STACK received in state " + this.stackPhase);
    }

    public synchronized StackPhase stackPhase() {
        return this.stackPhase;
    }

    public synchronized boolean hasResourcePackStackStarted() {
        return this.stackPhase != StackPhase.NOT_RECEIVED;
    }

    public CompletableFuture<ResourcePackStorage> resourcePackStackFuture() {
        return this.resourcePackStackFuture;
    }

    public synchronized void completeResourcePackStack(final ResourcePackStorage resourcePackStorage) {
        if (this.stackPhase == StackPhase.PUBLISHED) return;
        if (this.stackPhase != StackPhase.BUILDING) {
            throw new IllegalStateException(
                    "Resource pack stack cannot be published from state " + this.stackPhase);
        }
        this.stackPhase = StackPhase.PUBLISHED;
        this.resourcePackStackFuture.complete(
                Objects.requireNonNull(resourcePackStorage, "resourcePackStorage"));
    }

    public synchronized void failResourcePackStack(final Throwable error) {
        Objects.requireNonNull(error, "error");
        if (this.stackPhase == StackPhase.FAILED) return;
        if (this.stackPhase != StackPhase.BUILDING) return;
        this.stackPhase = StackPhase.FAILED;
        this.resourcePackStackFuture.completeExceptionally(error);
    }

    public CompletableFuture<ResourcePackStorage> negotiationReadyFuture() {
        return this.negotiationReadyFuture;
    }

    public boolean claimResourcePackStackFinished() {
        return this.resourcePackStackFinishedClaimed.compareAndSet(false, true);
    }

    public synchronized boolean deferStartGame() {
        if (this.startGamePhase != StartGamePhase.NOT_SEEN) return false;
        this.startGamePhase = StartGamePhase.DEFERRED;
        return true;
    }

    public synchronized void markDeferredStartGameReady() {
        if (this.startGamePhase != StartGamePhase.DEFERRED) {
            throw new IllegalStateException(
                    "Deferred START_GAME cannot become ready from state " + this.startGamePhase);
        }
        this.startGamePhase = StartGamePhase.REPLAY_READY;
    }

    public synchronized boolean claimStartGameProcessing() {
        if (this.startGamePhase == StartGamePhase.NOT_SEEN
                || this.startGamePhase == StartGamePhase.REPLAY_READY) {
            this.startGamePhase = StartGamePhase.CONSUMED;
            return true;
        }
        return false;
    }

    public boolean hasAnnouncedResourcePacks() {
        return !this.requests.isEmpty();
    }

    @Override
    public void onRemove() {
        final CancellationException cancellation = new CancellationException("Resource pack session closed");
        this.loadFuture.completeExceptionally(cancellation);
        this.javaPackTerminalFuture.completeExceptionally(cancellation);
        this.remoteDeliveryFuture.completeExceptionally(cancellation);
        this.remotePackLookupFuture.completeExceptionally(cancellation);
        this.resourcePackStackFuture.completeExceptionally(cancellation);
    }

    public enum JavaPackPhase {
        PENDING,
        ACCEPTED,
        LOADED,
        DECLINED,
        FAILED
    }

    public enum JavaPackOutcome {
        LOADED,
        DECLINED,
        FAILED
    }

    public enum RemoteDeliveryPhase {
        UNDECIDED,
        NOT_APPLICABLE,
        LOOKUP_PENDING,
        LOOKUP_READY,
        CANCELLED,
        FAILED
    }

    public enum RemoteDeliveryOutcome {
        NOT_APPLICABLE,
        LOOKUP_READY,
        CANCELLED
    }

    public enum StackPhase {
        NOT_RECEIVED,
        BUILDING,
        PUBLISHED,
        FAILED
    }

    public enum StackStart {
        STARTED,
        DUPLICATE
    }

    public enum StartGamePhase {
        NOT_SEEN,
        DEFERRED,
        REPLAY_READY,
        CONSUMED
    }

    private record StackAnnouncement(ResourcePack.Key[] keys, String[] selectedSubpacks,
                                     boolean resourcePackRequired, String baseGameVersion,
                                     Experiment[] experiments, boolean experimentsPreviouslyToggled,
                                     boolean includeEditorPacks) {

        private StackAnnouncement {
            keys = keys.clone();
            selectedSubpacks = selectedSubpacks.clone();
            Objects.requireNonNull(baseGameVersion, "baseGameVersion");
            experiments = experiments.clone();
        }

        private boolean matches(final StackAnnouncement other) {
            return Arrays.equals(this.keys, other.keys)
                    && Arrays.equals(this.selectedSubpacks, other.selectedSubpacks)
                    && this.resourcePackRequired == other.resourcePackRequired
                    && this.baseGameVersion.equals(other.baseGameVersion)
                    && Arrays.equals(this.experiments, other.experiments)
                    && this.experimentsPreviouslyToggled == other.experimentsPreviouslyToggled
                    && this.includeEditorPacks == other.includeEditorPacks;
        }
    }

    public record Info(ResourcePack.Key key, long announcedSize, byte[] contentKey, String contentId,
                       String announcedSubpacks, URL httpUrl, String announcedCdnUrl,
                       boolean hasScripts, boolean addonPack, boolean rayTracingCapable) {

        public Info {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(contentKey, "contentKey");
            Objects.requireNonNull(contentId, "contentId");
            Objects.requireNonNull(announcedSubpacks, "announcedSubpacks");
            Objects.requireNonNull(announcedCdnUrl, "announcedCdnUrl");
            if (announcedSize < -1L) {
                throw new IllegalArgumentException("Resource pack announced size must be non-negative or unknown");
            }
            contentKey = contentKey.clone();
        }

        public Info(final ResourcePack.Key key, final long announcedSize, final byte[] contentKey,
                    final String contentId, final String announcedSubpacks, final URL httpUrl) {
            this(key, announcedSize, contentKey, contentId, announcedSubpacks, httpUrl,
                    httpUrl != null ? httpUrl.toExternalForm() : "", false, false, false);
        }

        public Info(final ResourcePack.Key key, final byte[] contentKey, final String contentId, final URL httpUrl) {
            this(key, -1L, contentKey, contentId, "", httpUrl);
        }

        @Override
        public byte[] contentKey() {
            return this.contentKey.clone();
        }

        PackAlias alias(final String backendScope) {
            return PackAlias.from(backendScope, this.key, this.announcedSize, this.contentId, this.contentKey);
        }

        boolean hasSameAnnouncement(final Info other) {
            return this.key.equals(other.key)
                    && this.announcedSize == other.announcedSize
                    && Arrays.equals(this.contentKey, other.contentKey)
                    && this.contentId.equals(other.contentId)
                    && this.announcedSubpacks.equals(other.announcedSubpacks)
                    && Objects.equals(this.httpUrl, other.httpUrl)
                    && this.announcedCdnUrl.equals(other.announcedCdnUrl)
                    && this.hasScripts == other.hasScripts
                    && this.addonPack == other.addonPack
                    && this.rayTracingCapable == other.rayTracingCapable;
        }
    }

    public record ResolvedRequest(ResourcePack.Key key, Info info) {

        public ResolvedRequest {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(info, "info");
            if (!key.equals(info.key())) {
                throw new IllegalArgumentException("Resolved resource pack key does not match its announcement");
            }
        }
    }

    public record AnnouncementHeader(boolean resourcePackRequired, boolean hasAddonPacks, boolean hasScripts,
                                     boolean forceDisableVibrantVisuals, UUID worldTemplateId,
                                     String worldTemplateVersion) {

        public AnnouncementHeader {
            Objects.requireNonNull(worldTemplateId, "worldTemplateId");
            Objects.requireNonNull(worldTemplateVersion, "worldTemplateVersion");
        }

        private static AnnouncementHeader compatibility() {
            return new AnnouncementHeader(false, false, false, false, new UUID(0L, 0L), "");
        }
    }

    public record PreparedStack(List<ResourcePack> resourcePacksTopToBottom,
                                List<String> selectedSubpacksTopToBottom) {

        public PreparedStack {
            resourcePacksTopToBottom = List.copyOf(resourcePacksTopToBottom);
            selectedSubpacksTopToBottom = List.copyOf(selectedSubpacksTopToBottom);
            if (resourcePacksTopToBottom.size() != selectedSubpacksTopToBottom.size()) {
                throw new IllegalArgumentException("Resource pack and subpack stack sizes differ");
            }
        }
    }

    @FunctionalInterface
    interface StackPreparationExecutor {
        CompletableFuture<PreparedStack> submit(Callable<PreparedStack> task);
    }

    static String fingerprintAnnouncementSequence(final AnnouncementHeader header, final Info[] infos) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        updateBytes(digest, "ViaBedrock-ResourcePacksInfo-v1\0".getBytes(StandardCharsets.US_ASCII));
        updateBoolean(digest, header.resourcePackRequired());
        updateBoolean(digest, header.hasAddonPacks());
        updateBoolean(digest, header.hasScripts());
        updateBoolean(digest, header.forceDisableVibrantVisuals());
        updateLong(digest, header.worldTemplateId().getMostSignificantBits());
        updateLong(digest, header.worldTemplateId().getLeastSignificantBits());
        updateString(digest, header.worldTemplateVersion());
        updateInt(digest, infos.length);
        for (Info info : infos) {
            updateLong(digest, info.key().id().getMostSignificantBits());
            updateLong(digest, info.key().id().getLeastSignificantBits());
            updateString(digest, info.key().version());
            updateLong(digest, info.announcedSize());
            updateBytes(digest, info.contentKey);
            updateString(digest, info.announcedSubpacks());
            updateString(digest, info.contentId());
            updateBoolean(digest, info.hasScripts());
            updateBoolean(digest, info.addonPack());
            updateBoolean(digest, info.rayTracingCapable());
            updateString(digest, info.announcedCdnUrl());
        }
        return toHex(digest.digest());
    }

    private static String backendScope(final UserConnection user) {
        final SocketAddress remoteAddress = user.getChannel().remoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetAddress) {
            final String host = inetAddress.getAddress() != null
                    ? inetAddress.getAddress().getHostAddress()
                    : inetAddress.getHostString().toLowerCase(Locale.ROOT);
            return "inet:" + host + ':' + inetAddress.getPort();
        }
        return remoteAddress != null ? remoteAddress.getClass().getName() + ':' + remoteAddress : "";
    }

    private static void updateBoolean(final MessageDigest digest, final boolean value) {
        digest.update(value ? (byte) 1 : (byte) 0);
    }

    private static void updateString(final MessageDigest digest, final String value) {
        updateBytes(digest, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateBytes(final MessageDigest digest, final byte[] value) {
        updateInt(digest, value.length);
        digest.update(value);
    }

    private static void updateInt(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(final MessageDigest digest, final long value) {
        digest.update((byte) (value >>> 56));
        digest.update((byte) (value >>> 48));
        digest.update((byte) (value >>> 40));
        digest.update((byte) (value >>> 32));
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static String toHex(final byte[] value) {
        final StringBuilder hex = new StringBuilder(value.length * 2);
        for (byte element : value) {
            hex.append(Character.forDigit(Byte.toUnsignedInt(element) >>> 4, 16));
            hex.append(Character.forDigit(Byte.toUnsignedInt(element) & 0x0F, 16));
        }
        return hex.toString();
    }

}
