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

import com.viaversion.viaversion.connection.UserConnectionImpl;
import io.netty.channel.embedded.EmbeddedChannel;
import net.raphimc.viabedrock.api.resourcepack.ResourcePack;
import net.raphimc.viabedrock.api.resourcepack.http.RemotePackServiceClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePackLoadStateTrackerAsyncTest {

    @BeforeAll
    static void installViaManager() {
        net.raphimc.viabedrock.test.ViaTestBootstrap.installDefaultManager();
    }

    @Test
    void claimsEachRemoteLookupCancellationOnlyOnce() {
        final EmbeddedChannel firstChannel = new EmbeddedChannel();
        final EmbeddedChannel secondChannel = new EmbeddedChannel();
        try {
            final UUID token = UUID.randomUUID();
            final RemotePackServiceClient.Lookup lookup = new RemotePackServiceClient.Lookup(
                    "a".repeat(64), null, null, -1L, token, token,
                    "https://packs.example.test/packs/pending/" + token);
            final ResourcePackLoadStateTracker first = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(firstChannel), new ResourcePackLoadStateTracker.Info[0]);
            final ResourcePackLoadStateTracker second = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(secondChannel), new ResourcePackLoadStateTracker.Info[0]);
            first.startRemotePackLookup(() -> CompletableFuture.completedFuture(lookup));
            second.startRemotePackLookup(() -> CompletableFuture.completedFuture(lookup));

            assertSame(lookup, first.claimRemotePackCancellationFuture().join());
            assertNull(first.claimRemotePackCancellationFuture());
            assertSame(lookup, second.claimRemotePackCancellationFuture().join());
            assertNull(second.claimRemotePackCancellationFuture());
        } finally {
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
        }
    }

    @Test
    void startsRemoteLookupOnceAndSharesItsCanonicalFuture() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final CompletableFuture<RemotePackServiceClient.Lookup> source = new CompletableFuture<>();
            final AtomicInteger starts = new AtomicInteger();

            final CompletableFuture<RemotePackServiceClient.Lookup> first =
                    tracker.startRemotePackLookup(() -> {
                        starts.incrementAndGet();
                        return source;
                    });
            final CompletableFuture<RemotePackServiceClient.Lookup> duplicate =
                    tracker.startRemotePackLookup(() -> {
                        throw new AssertionError("Duplicate lookup must not be started");
                    });

            assertSame(first, duplicate);
            assertEquals(1, starts.get());
            assertFalse(first.isDone());
            final RemotePackServiceClient.Lookup lookup = lookup();
            source.complete(lookup);
            assertSame(lookup, first.join());
            assertEquals(ResourcePackLoadStateTracker.RemoteDeliveryOutcome.LOOKUP_READY,
                    tracker.remoteDeliveryFuture().join());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void synchronousRemoteLookupFailureTerminatesBothLookupAndDelivery() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final IllegalStateException expected = new IllegalStateException("lookup start failed");

            final CompletableFuture<RemotePackServiceClient.Lookup> lookup =
                    tracker.startRemotePackLookup(() -> {
                        throw expected;
                    });

            assertSame(expected, assertThrows(java.util.concurrent.CompletionException.class,
                    lookup::join).getCause());
            assertSame(expected, assertThrows(java.util.concurrent.CompletionException.class,
                    tracker.remoteDeliveryFuture()::join).getCause());
            assertEquals(ResourcePackLoadStateTracker.RemoteDeliveryPhase.FAILED,
                    tracker.remoteDeliveryPhase());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void cancellationBeforeLookupCompletionStillExposesTheLateLookupOnce() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final CompletableFuture<RemotePackServiceClient.Lookup> source = new CompletableFuture<>();
            tracker.startRemotePackLookup(() -> source);

            final CompletableFuture<RemotePackServiceClient.Lookup> cancellation =
                    tracker.claimRemotePackCancellationFuture();
            assertNull(tracker.claimRemotePackCancellationFuture());
            assertEquals(ResourcePackLoadStateTracker.RemoteDeliveryOutcome.CANCELLED,
                    tracker.remoteDeliveryFuture().join());
            assertFalse(cancellation.isDone());

            final RemotePackServiceClient.Lookup lookup = lookup();
            source.complete(lookup);
            assertSame(lookup, cancellation.join());
            assertFalse(tracker.shouldPublishRemotePack());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void cancelledLookupFailureTerminatesTheCancellationWaiter() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final CompletableFuture<RemotePackServiceClient.Lookup> source = new CompletableFuture<>();
            tracker.startRemotePackLookup(() -> source);
            final CompletableFuture<RemotePackServiceClient.Lookup> cancellation =
                    tracker.claimRemotePackCancellationFuture();

            final IOException expected = new IOException("lookup failed after cancellation");
            source.completeExceptionally(expected);

            assertSame(expected, assertThrows(java.util.concurrent.CompletionException.class,
                    cancellation::join).getCause());
            assertEquals(ResourcePackLoadStateTracker.RemoteDeliveryOutcome.CANCELLED,
                    tracker.remoteDeliveryFuture().join());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void remoteDeliveryNotApplicableIsAnExplicitTerminalState() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);

            tracker.markRemoteDeliveryNotApplicable();
            tracker.markRemoteDeliveryNotApplicable();

            assertEquals(ResourcePackLoadStateTracker.RemoteDeliveryOutcome.NOT_APPLICABLE,
                    tracker.remoteDeliveryFuture().join());
            assertThrows(IllegalStateException.class,
                    () -> tracker.startRemotePackLookup(
                            () -> CompletableFuture.completedFuture(lookup())));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void stackBarrierFailsCanonicallyWhenInfoDidNotInitializeRemoteDelivery() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);

            final CompletableFuture<ResourcePackLoadStateTracker.RemoteDeliveryOutcome> first =
                    tracker.requireRemoteDeliveryDecision();
            final CompletableFuture<ResourcePackLoadStateTracker.RemoteDeliveryOutcome> duplicate =
                    tracker.requireRemoteDeliveryDecision();

            assertSame(first, duplicate);
            final Throwable failure = assertThrows(
                    java.util.concurrent.CompletionException.class, first::join).getCause();
            assertTrue(failure.getMessage().contains("not initialized"));
            assertEquals(ResourcePackLoadStateTracker.RemoteDeliveryPhase.FAILED,
                    tracker.remoteDeliveryPhase());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void sourceLoadStartsOnceAndSharesItsFailure() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);

            final CompletableFuture<Void> first = tracker.loadRequestedResourcePacks();
            final CompletableFuture<Void> duplicate = tracker.loadRequestedResourcePacks();

            assertSame(first, duplicate);
            final Throwable failure = assertThrows(
                    java.util.concurrent.CompletionException.class, first::join).getCause();
            assertTrue(failure.getMessage().contains("download tracker is unavailable"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void negotiationBecomesReadyOnlyAfterStackPublicationAndJavaTerminalState() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final ResourcePackStorage storage = ResourcePackStorage.createUnshared(List.of());

            assertEquals(ResourcePackLoadStateTracker.StackStart.STARTED,
                    tracker.beginResourcePackStack(new ResourcePack.Key[0], new String[0]));
            tracker.markJavaClientAccepted();
            tracker.completeResourcePackStack(storage);
            assertFalse(tracker.negotiationReadyFuture().isDone());

            tracker.markJavaClientLoaded();
            assertSame(storage, tracker.negotiationReadyFuture().join());
            assertTrue(tracker.claimResourcePackStackFinished());
            assertFalse(tracker.claimResourcePackStackFinished());
            storage.onRemove();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void earlyJavaLoadedAndDeclinedOutcomesStillWaitForStackPublication() {
        final EmbeddedChannel loadedChannel = new EmbeddedChannel();
        final EmbeddedChannel declinedChannel = new EmbeddedChannel();
        final ResourcePackStorage loadedStorage = ResourcePackStorage.createUnshared(List.of());
        final ResourcePackStorage declinedStorage = ResourcePackStorage.createUnshared(List.of());
        try {
            final ResourcePackLoadStateTracker loaded = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(loadedChannel), new ResourcePackLoadStateTracker.Info[0]);
            loaded.beginResourcePackStack(new ResourcePack.Key[0], new String[0]);
            loaded.markJavaClientAccepted();
            loaded.markJavaClientLoaded();
            assertFalse(loaded.negotiationReadyFuture().isDone());
            loaded.completeResourcePackStack(loadedStorage);
            assertSame(loadedStorage, loaded.negotiationReadyFuture().join());

            final ResourcePackLoadStateTracker declined = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(declinedChannel), new ResourcePackLoadStateTracker.Info[0]);
            declined.beginResourcePackStack(new ResourcePack.Key[0], new String[0]);
            declined.markJavaClientDeclined();
            assertFalse(declined.negotiationReadyFuture().isDone());
            declined.completeResourcePackStack(declinedStorage);
            assertSame(declinedStorage, declined.negotiationReadyFuture().join());
        } finally {
            loadedStorage.onRemove();
            declinedStorage.onRemove();
            loadedChannel.finishAndReleaseAll();
            declinedChannel.finishAndReleaseAll();
        }
    }

    @Test
    void duplicateStackIsIdempotentButConflictingStackFails() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final ResourcePack.Key key = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");

            assertEquals(ResourcePackLoadStateTracker.StackStart.STARTED,
                    tracker.beginResourcePackStack(new ResourcePack.Key[]{key}, new String[]{"hd"}));
            assertEquals(ResourcePackLoadStateTracker.StackStart.DUPLICATE,
                    tracker.beginResourcePackStack(new ResourcePack.Key[]{key}, new String[]{"hd"}));
            assertThrows(IllegalStateException.class,
                    () -> tracker.beginResourcePackStack(
                            new ResourcePack.Key[]{key}, new String[]{"low"}));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void deferredStartGameAllowsOnlyItsMarkedReplay() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);

            assertTrue(tracker.deferStartGame());
            assertFalse(tracker.deferStartGame());
            assertFalse(tracker.claimStartGameProcessing());
            tracker.markDeferredStartGameReady();
            assertTrue(tracker.claimStartGameProcessing());
            assertFalse(tracker.claimStartGameProcessing());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void sessionRemovalClosesEveryPendingBarrierAndRejectsLatePublication() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            tracker.startRemotePackLookup(CompletableFuture::new);
            tracker.beginResourcePackStack(new ResourcePack.Key[0], new String[0]);

            tracker.onRemove();

            assertThrows(CancellationException.class, tracker.javaPackTerminalFuture()::join);
            assertThrows(CancellationException.class, tracker.remoteDeliveryFuture()::join);
            assertThrows(CancellationException.class, tracker.resourcePackStackFuture()::join);
            assertTrue(assertThrows(java.util.concurrent.CompletionException.class,
                    tracker.negotiationReadyFuture()::join).getCause() instanceof CancellationException);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void resolvesVersionedAndVersionlessTransferNamesToTheAnnouncedIdentity() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key key = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker.Info info = new ResourcePackLoadStateTracker.Info(
                    key, 128L, new byte[0], "content", "", null);
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[]{info});

            final ResourcePackLoadStateTracker.ResolvedRequest versioned =
                    tracker.resolveTransferRequest(key.toString());
            final ResourcePackLoadStateTracker.ResolvedRequest versionless =
                    tracker.resolveTransferRequest(key.id().toString());

            assertEquals(key, versioned.key());
            assertSame(info, versioned.info());
            assertEquals(key, versionless.key());
            assertSame(info, versionless.info());
            assertThrows(IllegalArgumentException.class,
                    () -> ResourcePack.Key.fromString(key.id().toString()));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsAmbiguousVersionlessTransferNamesWithoutGuessing() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final UUID id = UUID.randomUUID();
            final ResourcePack.Key firstKey = new ResourcePack.Key(id, "1.0.0");
            final ResourcePack.Key secondKey = new ResourcePack.Key(id, "2.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(
                                    firstKey, 128L, new byte[0], "first", "", null),
                            new ResourcePackLoadStateTracker.Info(
                                    secondKey, 256L, new byte[0], "second", "", null)
                    });

            assertEquals(firstKey, tracker.resolveTransferRequest(firstKey.toString()).key());
            assertEquals(secondKey, tracker.resolveTransferRequest(secondKey.toString()).key());
            final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> tracker.resolveTransferRequest(id.toString()));
            assertTrue(failure.getMessage().contains("matches 2 announced versions"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void missingStackDiagnosticDistinguishesDeclaredAndDifferentVersion() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final UUID id = UUID.randomUUID();
            final ResourcePack.Key declared = new ResourcePack.Key(id, "1.0.0");
            final ResourcePack.Key differentVersion = new ResourcePack.Key(id, "2.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(
                                    declared, 128L, new byte[0], "content", "", null)
                    });

            final String declaredMessage =
                    tracker.missingResourcePackException(declared, true).getMessage();
            final String versionMessage =
                    tracker.missingResourcePackException(differentVersion, true).getMessage();

            assertTrue(declaredMessage.contains("declaredInInfo=true"));
            assertTrue(versionMessage.contains("declaredInInfo=false"));
            assertTrue(versionMessage.contains("announcedVersionsForUuid=[1.0.0]"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMalformedAndUnannouncedTransferNames() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);

            assertThrows(IllegalArgumentException.class,
                    () -> tracker.resolveTransferRequest("not-a-uuid"));
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.resolveTransferRequest(UUID.randomUUID().toString()));
            assertThrows(IllegalArgumentException.class,
                    () -> tracker.resolveTransferRequest(UUID.randomUUID() + "_1.0.0"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void preparationSnapshotsProtocolArraysAndRunsOnTheSubmittedWorker() throws Exception {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final AtomicReference<Thread> preparationThread = new AtomicReference<>();
        final AtomicReference<ResourcePack.Key> preparedKey = new AtomicReference<>();
        final AtomicReference<String> preparedSubpack = new AtomicReference<>();
        final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]) {
            @Override
            PreparedStack prepareResourcePackStack(final ResourcePack.Key[] keys,
                                                   final String[] selectedSubpacks) {
                preparationThread.set(Thread.currentThread());
                preparedKey.set(keys[0]);
                preparedSubpack.set(selectedSubpacks[0]);
                return new PreparedStack(List.of(), List.of());
            }
        };
        final ResourcePack.Key originalKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
        final ResourcePack.Key[] keys = {originalKey};
        final String[] subpacks = {"hd"};
        final AtomicReference<Callable<ResourcePackLoadStateTracker.PreparedStack>> submitted =
                new AtomicReference<>();
        final CompletableFuture<ResourcePackLoadStateTracker.PreparedStack> result = new CompletableFuture<>();

        final CompletableFuture<ResourcePackLoadStateTracker.PreparedStack> preparation =
                tracker.prepareResourcePackStackAsync(keys, subpacks, task -> {
                    submitted.set(task);
                    return result;
                });
        keys[0] = new ResourcePack.Key(UUID.randomUUID(), "2.0.0");
        subpacks[0] = "mutated";

        final ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.execute(() -> {
                try {
                    result.complete(submitted.get().call());
                } catch (Throwable e) {
                    result.completeExceptionally(e);
                }
            });

            preparation.get(2, TimeUnit.SECONDS);
            assertNotSame(Thread.currentThread(), preparationThread.get());
            assertEquals(originalKey, preparedKey.get());
            assertEquals("hd", preparedSubpack.get());
        } finally {
            worker.shutdownNow();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsMismatchedStackArraysBeforeSubmittingWork() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel), new ResourcePackLoadStateTracker.Info[0]);
            final ResourcePack.Key[] keys = {new ResourcePack.Key(UUID.randomUUID(), "1.0.0")};

            final CompletableFuture<ResourcePackLoadStateTracker.PreparedStack> preparation =
                    tracker.prepareResourcePackStackAsync(keys, new String[0], task -> {
                        throw new AssertionError("Mismatched input must not be submitted");
                    });

            assertThrows(ExecutionException.class, preparation::get);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void cancellingConnectionWaiterDoesNotCancelSharedSource() {
        final CompletableFuture<String> sharedSource = new CompletableFuture<>();
        final CompletableFuture<String> connectionWaiter =
                ResourcePackLoadStateTracker.detachedWaiter(sharedSource);
        final ResourcePackDownloadTracker connectionTracker = new ResourcePackDownloadTracker();
        connectionTracker.trackConnectionStage(connectionWaiter);

        connectionTracker.onRemove();

        assertTrue(connectionWaiter.isCancelled());
        assertFalse(sharedSource.isDone());
        assertFalse(sharedSource.isCancelled());
        sharedSource.complete("shared-result");
        assertEquals("shared-result", sharedSource.join());
        assertThrows(CancellationException.class, connectionWaiter::join);
    }

    @Test
    void validatesCdnBytesAgainstTrustedAnnouncementSize() throws Exception {
        ResourcePackLoadStateTracker.validateCdnSize(-1L, 7L);
        ResourcePackLoadStateTracker.validateCdnSize(7L, 7L);

        final IOException failure = assertThrows(IOException.class,
                () -> ResourcePackLoadStateTracker.validateCdnSize(8L, 7L));
        assertTrue(failure.getMessage().contains("7 != 8"));
    }

    @Test
    void strictSharedModeDoesNotTreatAnAnnouncedIdentityAsABundledPack() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key announcedKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel),
                    new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(announcedKey, new byte[0], "", null)
                    });

            assertFalse(tracker.mayUseBundledResourcePack(announcedKey, true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void strictSharedModeStillAllowsTrulyLocalBundledLayers() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key announcedKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePack.Key localKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel),
                    new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(announcedKey, new byte[0], "", null)
                    });

            assertTrue(tracker.mayUseBundledResourcePack(localKey, true));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void legacyModeKeepsUuidVersionBundledPackCompatibility() {
        final EmbeddedChannel channel = new EmbeddedChannel();
        try {
            final ResourcePack.Key announcedKey = new ResourcePack.Key(UUID.randomUUID(), "1.0.0");
            final ResourcePackLoadStateTracker tracker = new ResourcePackLoadStateTracker(
                    new UserConnectionImpl(channel),
                    new ResourcePackLoadStateTracker.Info[]{
                            new ResourcePackLoadStateTracker.Info(announcedKey, new byte[0], "", null)
                    });

            assertTrue(tracker.mayUseBundledResourcePack(announcedKey, false));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static RemotePackServiceClient.Lookup lookup() {
        final UUID token = UUID.randomUUID();
        return new RemotePackServiceClient.Lookup(
                "a".repeat(64), null, null, -1L, token, token,
                "https://packs.example.test/packs/pending/" + token);
    }

}
