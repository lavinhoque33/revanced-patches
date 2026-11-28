/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class OfflineWatchMetadataTest {
    private static final String VIDEO = "JGwWNGJdvx8";

    private record Tab(int rvxMetadataTabType) implements OfflineWatchMetadata.Tab { }

    @Test public void preserveLocalUpNextAndAdmitOnlyLyricsAndRelated() {
        Tab localUpNext = new Tab(6827);
        Tab remoteUpNext = new Tab(6827);
        Tab lyrics = new Tab(0x1737e);
        Tab related = new Tab(0x1737d);
        Tab unrelated = new Tab(76);
        List<?> tabs = OfflineWatchMetadata.mergeTabs(List.of(localUpNext),
                List.of(remoteUpNext, related, unrelated, lyrics));

        assertEquals(3, tabs.size());
        assertSame(localUpNext, tabs.get(0));
        assertSame(lyrics, tabs.get(1));
        assertSame(related, tabs.get(2));
    }

    @Test public void neverInstallRemoteUpNextWithoutLocalTabs() {
        assertNull(OfflineWatchMetadata.mergeTabs(List.of(), List.of(new Tab(6827), new Tab(0x1737e))));
        assertNull(OfflineWatchMetadata.mergeTabs(List.of(new Tab(6827)), List.of(new Tab(6827))));
    }

    @Test public void appendMissingTabsWithoutMovingOrReplacingMountedViews() {
        Object upNext = new Object();
        Tab mountedLyrics = new Tab(0x1737e);
        Tab related = new Tab(0x1737d);
        List<?> remote = List.of(new Tab(0x1737e), new Tab(6827), related);
        List<?> tabs = OfflineWatchMetadata.appendTabs(List.of(upNext, mountedLyrics), remote);

        assertEquals(3, tabs.size());
        assertSame(upNext, tabs.get(0));
        assertSame(mountedLyrics, tabs.get(1));
        assertSame(related, tabs.get(2));
        assertSame(tabs, OfflineWatchMetadata.appendTabs(tabs, remote));
    }

    @Test public void cancelsTheTransportOnceWhetherCancellationPrecedesOrFollowsBinding() {
        Object scheduledHandle = new Object();
        List<Object> cancelled = new ArrayList<>();
        ReplayFuture before = new ReplayFuture();
        before.cancel(false);
        OfflineWatchMetadata.bindTransport(before, cancelled::add, scheduledHandle);

        ReplayFuture after = new ReplayFuture();
        OfflineWatchMetadata.bindTransport(after, cancelled::add, scheduledHandle);
        assertEquals(List.of(scheduledHandle), cancelled);
        after.cancel(false);
        after.cancel(false);
        assertEquals(List.of(scheduledHandle, scheduledHandle), cancelled);

        ReplayFuture completed = new ReplayFuture();
        OfflineWatchMetadata.bindTransport(completed, cancelled::add, scheduledHandle);
        completed.set(new Object());
        completed.cancel(false);
        assertEquals(List.of(scheduledHandle, scheduledHandle), cancelled);
    }

    @Test public void onlyTheOwningPlaylistOnlyRequestCanUseTheLocalSeed() {
        Object owner = new Object();
        assertSame(VIDEO, PlayerFallback.localSeedOf("", owner, owner, VIDEO));
        assertNull(PlayerFallback.localSeedOf(VIDEO, owner, owner, VIDEO));
        assertNull(PlayerFallback.localSeedOf("", owner, new Object(), VIDEO));
        assertNull(PlayerFallback.localSeedOf("", owner, null, VIDEO));
        assertNull(PlayerFallback.localSeedOf("", owner, owner, ""));
    }

    @Test public void firstNativeNavigatorKeepsTheAlreadyAdmittedColdHeaderStart() throws Exception {
        var navigator = PlayerFallback.class.getDeclaredField("navigator");
        navigator.setAccessible(true);
        Object previous = navigator.get(null);
        try {
            navigator.set(null, null);
            PlayerFallback.playlistStarted();

            var seedless = new RequestCopy();
            var effective = PlaybackRequestIdentity.inherit(new RequestCopy(), seedless);
            var saved = new StoredPlayer();
            PlayerFallback.markLocalPlayer(saved, PlayerFallback.localPlayerOwner(effective));
            long generation = PlayerFallback.navigationGeneration();

            var owner = new InitialNavigator();
            var reply = new StubReply(VIDEO);
            owner.runner = new Runner(seedless, reply);
            owner.queue = new NativeQueue(List.of(VIDEO), 0);
            PlayerFallback.captureNavigator(owner);
            assertEquals(generation, PlayerFallback.navigationGeneration());

            PlayerFallback.watchPlayerDelivered(owner.runner, saved);
            var occurrence = PlayerFallback.watchOccurrence(owner.queue, seedless, VIDEO, reply);
            assertNotNull(occurrence);

            PlayerFallback.captureNavigator(new InitialNavigator());
            assertFalse(PlayerFallback.ownsWatchOccurrence(occurrence));
        } finally {
            PlayerFallback.playlistStarted();
            navigator.set(null, previous);
        }
    }

    @Test public void headerPlayJoinsAfterTheSeededPlayerAndTheNativeWatchApply() {
        assertInitialAlbumJoin(true);
    }

    @Test public void rowTapJoinsAfterThePlayerAndTheNativeWatchApply() {
        assertInitialAlbumJoin(false);
    }

    private static void assertInitialAlbumJoin(boolean headerPlay) {
        var owner = new InitialNavigator();
        PlayerFallback.captureNavigator(owner);
        PlayerFallback.playlistStarted();

        var payload = new RequestCopy();
        var normalized = new RequestCopy();
        PlayerFallback.noteWatchRequestCopy(normalized, payload);
        // Header Play first resolves a seedless request via Watch Next. Row taps already have the player seed.
        Object player = headerPlay ? PlaybackRequestIdentity.inherit(new RequestCopy(), normalized) : normalized;
        var reply = new StubReply(VIDEO);
        var runner = new Runner(normalized, reply);
        var saved = new StoredPlayer();
        Object proof = PlayerFallback.localPlayerOwner(player);
        assertNotNull(proof);
        PlayerFallback.markLocalPlayer(saved, proof);
        assertNull(PlayerFallback.watchSelection(owner));
        owner.runner = runner;

        // The initial start does not have an indexed cursor commit. The guarded player callback arrives first.
        PlayerFallback.watchPlayerDelivered(runner, saved);
        assertNull(PlayerFallback.takeStoredPlayerOwner(saved));

        Object first = new String(VIDEO);
        Object duplicate = new String(VIDEO);
        NativeQueue queue = new NativeQueue(List.of(first, duplicate), 0);
        assertNull(PlayerFallback.watchOccurrence(queue, payload, VIDEO, new StubReply(VIDEO)));
        assertNull(PlayerFallback.watchOccurrence(queue, new RequestCopy(), VIDEO, reply));
        assertNull(PlayerFallback.watchOccurrence(queue, payload, VIDEO, reply));

        // A model that merely has the same row is not the storage publication of the applied host.
        owner.queue = new NativeQueue(queue.items, 0);
        PlayerFallback.watchQueuePublished(owner.queue);
        assertNull(PlayerFallback.watchOccurrence(queue, payload, VIDEO, reply));
        owner.queue = queue;
        PlayerFallback.watchQueuePublished(queue);

        var occurrence = PlayerFallback.watchOccurrence(queue, payload, VIDEO, reply);
        assertNotNull(occurrence);
        assertSame(queue, occurrence.queue());
        assertSame(first, occurrence.row());
        assertSame(payload, occurrence.request());
        assertTrue(PlayerFallback.ownsWatchOccurrence(occurrence));
        assertSame(occurrence, PlayerFallback.watchOccurrence(queue, payload, VIDEO, reply));

        owner.queue = new NativeQueue(queue.items, 0);
        assertFalse(PlayerFallback.ownsWatchOccurrence(occurrence));
        assertNull(PlayerFallback.watchOccurrence(owner.queue, payload, VIDEO, reply));

        owner.queue = queue;
        queue.cursor = 1;
        assertFalse(PlayerFallback.ownsWatchOccurrence(occurrence));
        assertNull(PlayerFallback.watchOccurrence(queue, payload, VIDEO, reply));
    }

    @Test public void staleOrUndeliveredInitialPlayerCannotWitnessTheNativeWatchApply() {
        var owner = new InitialNavigator();
        PlayerFallback.captureNavigator(owner);
        PlayerFallback.playlistStarted();

        var payload = new RequestCopy();
        var reply = new StubReply(VIDEO);
        var old = new Runner(payload, reply);
        owner.runner = old;
        var saved = new StoredPlayer();
        PlayerFallback.markLocalPlayer(saved, PlayerFallback.localPlayerOwner(payload));

        PlayerFallback.playlistStarted();
        var current = new Runner(payload, reply);
        owner.runner = current;
        owner.queue = new NativeQueue(List.of(VIDEO), 0);
        var prepared = new StoredPlayer();
        Object proof = PlayerFallback.localPlayerOwner(payload);
        assertNotNull(proof);
        PlayerFallback.markLocalPlayer(prepared, proof);
        PlayerFallback.watchPlayerDelivered(old, saved);
        PlayerFallback.watchPlayerDelivered(current, saved);
        PlayerFallback.watchPlayerDelivered(current, new StoredPlayer());
        assertNull(PlayerFallback.watchOccurrence(owner.queue, payload, VIDEO, reply));

        PlayerFallback.watchPlayerDelivered(current, prepared);
        assertNotNull(PlayerFallback.watchOccurrence(owner.queue, payload, VIDEO, reply));
    }

    @Test public void occurrenceRequiresSameRowOriginQueueAndSelectionGeneration() {
        Object first = new String(VIDEO);
        Object duplicate = new String(VIDEO);
        Object request = new Object();
        NativeQueue queue = new NativeQueue(List.of(first, duplicate), 0);
        var read = new PlayerFallback.WatchOccurrence(null, queue, first, request, 1, VIDEO, null);

        assertTrue(OfflineWatchMetadata.sameOccurrence(read,
                new PlayerFallback.WatchOccurrence(null, queue, first, request, 1, VIDEO, null)));
        assertFalse(OfflineWatchMetadata.sameOccurrence(read,
                new PlayerFallback.WatchOccurrence(null, queue, duplicate, request, 1, VIDEO, null)));
        assertFalse(OfflineWatchMetadata.sameOccurrence(read,
                new PlayerFallback.WatchOccurrence(null, queue, first, new Object(), 1, VIDEO, null)));
        assertFalse(OfflineWatchMetadata.sameOccurrence(read,
                new PlayerFallback.WatchOccurrence(null, queue, first, request, 2, VIDEO, null)));
        assertFalse(OfflineWatchMetadata.sameOccurrence(read,
                new PlayerFallback.WatchOccurrence(null, new NativeQueue(queue.items, 0), first, request,
                        1, VIDEO, null)));
        assertTrue(OfflineQueueActions.currentReplayOccurrence(queue, first));
        assertFalse(OfflineQueueActions.currentReplayOccurrence(queue, duplicate));
    }

    @Test public void committedSavedPlaybackRefreshesWithEitherWitnessFirst() {
        for (boolean commitFirst : new boolean[]{true, false}) {
            NativeQueue queue = new NativeQueue(List.of(VIDEO), 0);
            var runner = new Runner(new Object());
            var join = selected(queue, 7);
            var key = key(join, runner);
            var saved = new StoredPlayer();
            PlayerFallback.markLocalPlayer(saved, key);

            if (commitFirst) {
                assertEquals("accepted-awaiting-delivery", commit(join, key, runner, null));
                assertNull(join.ready(runner));
            }
            assertTrue(join.acceptPlayer(runner, null, PlayerFallback.takeStoredPlayerOwner(saved)));
            if (!commitFirst) {
                assertNull(join.ready(runner));
                assertEquals("accepted-joined", commit(join, key, runner, null));
            }
            var occurrence = join.ready(runner);
            assertSame(key, occurrence.retained());
            assertSame(queue, occurrence.queue());
            assertNull(join.reply(key));
            assertNull(PlayerFallback.takeStoredPlayerOwner(saved));

            var published = new StubReply(VIDEO);
            join.attach(key, published);
            assertSame(occurrence, join.ready(runner));
            assertSame(published, join.reply(key));
        }
    }

    @Test public void cursorWaitsForItsExactAcceptedRequestWhenRunnerPublicationIsLate() {
        for (String expected : List.of("pending-null-runner", "pending-retired-runner",
                "pending-request-origin-mismatch")) {
            var runner = new Runner(new Object());
            var retired = new Runner(runner.rvxWatchRequest());
            var unrelated = new Runner(new Object());
            Runner beforePublication = switch (expected) {
                case "pending-retired-runner" -> retired;
                case "pending-request-origin-mismatch" -> unrelated;
                default -> null;
            };
            var join = selected(new NativeQueue(List.of(VIDEO), 0), 7);
            var key = key(join, runner);

            assertEquals(expected, commit(join, key, beforePublication, retired));
            assertNull(join.ready(runner));
            assertFalse(join.acceptPlayer(retired, retired, key(join, retired)));
            assertTrue(join.acceptPlayer(unrelated, retired, key(join, unrelated)));
            assertEquals("accepted-request-origin-mismatch", join.deliveryReason());
            assertNull(join.ready(unrelated));

            var published = new StubReply(VIDEO);
            join.attach(key, published);
            assertNull(join.ready(runner));
            assertTrue(join.acceptPlayer(runner, retired, key));
            assertSame(published, join.reply(key));
            assertEquals("accepted-joined", join.deliveryReason());
            assertSame(key, join.ready(runner).retained());
            assertNull(join.ready(retired));
        }
    }

    @Test public void onlyProvenRetainedRequestCopiesCanJoin() {
        var payload = new Object();
        var dispatchCopy = new RequestCopy();
        var decorated = new RequestCopy();
        var normalized = new RequestCopy();
        PlayerFallback.noteWatchRequestCopy(dispatchCopy, payload);
        PlayerFallback.noteWatchRequestCopy(decorated, dispatchCopy);
        PlayerFallback.noteWatchRequestCopy(normalized, decorated);

        var join = selected(new NativeQueue(List.of(VIDEO), 0), 7);
        var runner = new Runner(normalized);
        var proof = new PlayerFallback.WatchKey(join, runner, payload);
        var unrelated = new Runner(new RequestCopy());
        assertEquals("pending-request-origin-mismatch",
                join.commit(join.queue, join.row, payload, unrelated, null));
        assertFalse(join.acceptPlayer(unrelated, null, new PlayerFallback.WatchKey(join, unrelated, payload)));
        assertNull(join.ready(unrelated));

        assertTrue(join.acceptPlayer(runner, null, proof));
        assertEquals("accepted-joined", join.deliveryReason());
        assertSame(proof, join.ready(runner).retained());
    }

    @Test public void onlyTheExactSelectedQueueAndCurrentRowCanWitnessTheCursor() {
        Object first = new String(VIDEO);
        Object duplicate = new String(VIDEO);
        NativeQueue queue = new NativeQueue(List.of(first, duplicate), 0);
        var join = selected(queue, 7);
        var runner = new Runner(new Object());
        var key = key(join, runner);
        assertTrue(join.acceptPlayer(runner, null, key));

        assertEquals("wrong-queue-row", join.commit(new NativeQueue(queue.items, 0), first,
                key.request(), runner, null));
        assertEquals("missing-commit-request", join.commit(queue, first, null, runner, null));
        assertEquals("wrong-queue-row", join.commit(queue, duplicate, key.request(), runner, null));
        queue.cursor = 1;
        assertEquals("wrong-cursor-row", commit(join, key, runner, null));
        assertNull(join.ready(runner));

        queue.cursor = 0;
        assertEquals("accepted-joined", commit(join, key, runner, null));
        assertSame(first, join.ready(runner).row());
    }

    @Test public void anOnlineResponseWinningTheFutureDoesNotWitnessAPreparedSavedLoser() throws Exception {
        var join = selected(new NativeQueue(List.of(VIDEO), 0), 7);
        var key = key(join, new Runner(new Object()));
        commit(join, key, key.runner(), null);
        var saved = new StoredPlayer();
        PlayerFallback.markLocalPlayer(saved, key);

        var online = new StoredPlayer(); // Same native wrapper type, including when online playback swaps to FILE.
        ReplayFuture future = new ReplayFuture();
        assertTrue(future.set(online));
        assertFalse(future.set(saved));
        assertFalse(join.acceptPlayer(key.runner(), null, PlayerFallback.takeStoredPlayerOwner(future.get())));
        assertNull(join.ready(key.runner()));
    }

    @Test public void cancellationBeforeDeliveryDoesNotWitnessTheSavedResponse() {
        var join = selected(new NativeQueue(List.of(VIDEO), 0), 7);
        var key = key(join, new Runner(new Object()));
        commit(join, key, key.runner(), null);
        var saved = new StoredPlayer();
        PlayerFallback.markLocalPlayer(saved, key);

        ReplayFuture future = new ReplayFuture();
        assertTrue(future.cancel(false));
        assertFalse(future.set(saved));
        assertNull(join.ready(key.runner()));
    }

    @Test public void previousOccurrenceWithSameVideoAndOriginCannotJoinTheNewCommit() {
        Object first = new String(VIDEO);
        Object duplicate = new String(VIDEO);
        var runner = new Runner(new Object());
        NativeQueue queue = new NativeQueue(List.of(first, duplicate), 0);
        var old = selected(queue, 7);
        queue.cursor = 1;
        var next = selected(queue, 8);
        var oldKey = key(old, runner);
        var nextKey = key(next, runner);
        commit(next, nextKey, runner, null);

        assertFalse(next.acceptPlayer(runner, null, oldKey));
        assertNull(next.ready(runner));
        assertFalse(OfflineQueueActions.currentReplayOccurrence(queue, old.row));
        assertTrue(OfflineQueueActions.currentReplayOccurrence(queue, next.row));

        assertTrue(next.acceptPlayer(runner, null, nextKey));
        assertSame(nextKey, next.ready(runner).retained());
        assertEquals("stale-occurrence", OfflineWatchMetadata.completionRejection(true, true, false, true));
        assertNull(OfflineWatchMetadata.completionRejection(true, true, true, true));
    }

    @Test public void aReplacementRunnerOfTheSameRequestCannotInheritTheBoundCommit() {
        var firstRunner = new Runner(new Object());
        var join = selected(new NativeQueue(List.of(VIDEO), 0), 7);
        var key = key(join, firstRunner);
        commit(join, key, firstRunner, null);
        join.attach(key, new StubReply(VIDEO));

        var replacement = key(join, new Runner(key.request()));
        assertFalse(join.acceptPlayer(replacement.runner(), null, key));
        assertTrue(join.acceptPlayer(replacement.runner(), null, replacement));
        assertEquals("accepted-replaced-commit-runner", join.deliveryReason());
        assertNull(join.ready(replacement.runner()));
        assertNull(join.reply(replacement));
    }

    private static PlayerFallback.WatchJoin selected(NativeQueue queue, long generation) {
        Object row = queue.rvxOfflineItem(queue.rvxOfflineCursor());
        var selection = new PlayerFallback.Selection(null, 1, VIDEO, true, 1, null, generation, null);
        return new PlayerFallback.WatchJoin(selection, queue, row);
    }

    private static PlayerFallback.WatchKey key(PlayerFallback.WatchJoin join, Runner runner) {
        return new PlayerFallback.WatchKey(join, runner, runner.rvxWatchRequest());
    }

    private static String commit(PlayerFallback.WatchJoin join, PlayerFallback.WatchKey key,
                                 PlayerFallback.WatchRunner current, Runner retired) {
        return join.commit(join.queue, join.row, key.request(), current, retired);
    }

    private record Runner(Object rvxWatchRequest, Object rvxWatchReply) implements PlayerFallback.WatchRunner {
        Runner(Object request) { this(request, null); }
    }

    private static final class InitialNavigator implements PlayerFallback.WatchNavigator, OfflineQueueActions.Owner {
        NativeQueue queue;
        Runner runner;

        @Override public Object rvxOfflineQueue() { return null; }
        @Override public Object rvxCurrentWatchQueue() { return queue; }
        @Override public Runner rvxCurrentWatchRunner() { return runner; }
        @Override public List<?> rvxItems() { return queue.items; }
        @Override public String rvxVideoId(Object item) { return queue.rvxOfflineVideoId(item); }
        @Override public long rvxItemId(Object item) { throw new AssertionError(); }
        @Override public int rvxCurrent() { return queue.cursor; }
        @Override public Object rvxPlayback() { throw new AssertionError(); }
        @Override public void rvxCancelAndStop() { throw new AssertionError(); }
        @Override public boolean rvxOwnsRequest(Object request) { return runner.rvxWatchRequest() == request; }
        @Override public void rvxCancelLoad() { throw new AssertionError(); }
        @Override public Object rvxJump(Object item, boolean restart) { throw new AssertionError(); }
        @Override public void rvxDispatch(Object command) { throw new AssertionError(); }
    }

    private static final class RequestCopy implements PlayerFallback.WatchRequestCopy, PlaybackRequestIdentity.Request {
        private Object source;
        private Object origin;

        @Override public Object rvxRequestOrigin() { return origin; }
        @Override public void rvxRequestOrigin(Object request) { origin = request; }

        @Override public Object rvxWatchRequestSource() { return source; }

        @Override public void rvxWatchRequestSource(Object source) {
            this.source = source;
        }
    }

    private static final class StoredPlayer implements PlayerFallback.StoredPlayerResponse {
        private Object owner;

        @Override public Object rvxStoredPlayerOwner() { return owner; }

        @Override public void rvxStoredPlayerOwner(Object owner) {
            this.owner = owner;
        }
    }

    @Test public void ignoresForeignOrMissingResponseEndpoint() {
        assertTrue(OfflineWatchMetadata.matchesResponse(VIDEO, VIDEO));
        assertFalse(OfflineWatchMetadata.matchesResponse(VIDEO, "RgKAFK5djSk"));
        assertFalse(OfflineWatchMetadata.matchesResponse(VIDEO, null));
    }

    @Test public void rejectsMetadataReactionAfterLocalChangeOrSubscriberReplacement() {
        Object owner = new Object();
        assertTrue(OfflineWatchMetadata.currentReaction(1, 1, owner, owner));
        assertFalse(OfflineWatchMetadata.currentReaction(1, 2, owner, owner));
        assertFalse(OfflineWatchMetadata.currentReaction(1, 1, owner, new Object()));
    }

    @Test public void aReplyIsAppliedOnlyWhileEverythingItWasAskedForIsUnchanged() {
        assertNull(OfflineWatchMetadata.completionRejection(true, true, true, true));
        assertEquals("unvalidated", OfflineWatchMetadata.completionRejection(false, true, true, true));
        assertEquals("stale-validation", OfflineWatchMetadata.completionRejection(true, false, true, true));
        assertEquals("stale-occurrence", OfflineWatchMetadata.completionRejection(true, true, false, true));
        assertEquals("stale-future", OfflineWatchMetadata.completionRejection(true, true, true, false));
    }

    /** A reply the host decoded, counting what the decode asked of it. */
    private static final class StubReply implements OfflineWatchMetadata.Reply {
        final String videoId;
        final Object reaction = new Object();
        int tabReads;
        int reactionReads;

        StubReply(String videoId) {
            this.videoId = videoId;
        }

        @Override public Object rvxLocalWatchRequest() { return null; }
        @Override public void rvxLocalWatchRequest(Object request) { }
        @Override public String rvxMetadataVideoId() { return videoId; }
        @Override public String rvxMetadataPlaylistId() { return null; }
        @Override public List<?> rvxMetadataTabs() { tabReads++; return List.of("tab"); }
        @Override public Object rvxMetadataReaction() { reactionReads++; return reaction; }
    }

    private static OfflineWatchMetadata.Host hostDecoding(StubReply reply) {
        return new OfflineWatchMetadata.Host() {
            @Override public com.google.common.util.concurrent.ListenableFuture<?> rvxFetchWatchMetadata(
                    byte[] command) {
                throw new AssertionError("decode never fetches");
            }

            @Override public OfflineWatchMetadata.Reply rvxDecodeWatchMetadata(Object response) {
                return reply;
            }
        };
    }

    @Test public void aReplyOfAnotherSongIsDroppedBeforeAnythingIsReadFromIt() {
        StubReply reply = new StubReply("zzzzzzzzzzz");

        assertNull(OfflineWatchMetadata.decode(hostDecoding(reply), new Object(), "abcdefghijk", true));
        assertEquals(0, reply.tabReads);
        assertEquals(0, reply.reactionReads);
    }

    @Test public void aReplyOfTheSongGivesItsTabsAndTheReactionOnlyWhenAskedFor() {
        StubReply reply = new StubReply("abcdefghijk");

        OfflineWatchMetadata.Decoded withReaction =
                OfflineWatchMetadata.decode(hostDecoding(reply), new Object(), "abcdefghijk", true);
        OfflineWatchMetadata.Decoded without =
                OfflineWatchMetadata.decode(hostDecoding(reply), new Object(), "abcdefghijk", false);

        assertEquals(List.of("tab"), withReaction.tabs());
        assertSame(reply.reaction, withReaction.renderer());
        assertNull(without.renderer());
        assertEquals(1, reply.reactionReads);
        // A reply that cannot serve the redesigned player's surfaces gives none.
        assertNull(withReaction.surfaces());
    }

    private static final class NativeQueue implements OfflineQueueActions.Queue, OfflineWatchMetadata.Host {
        final List<Object> items;
        int cursor;

        NativeQueue(List<Object> items, int cursor) {
            this.items = items;
            this.cursor = cursor;
        }

        @Override public int rvxOfflineCursor() { return cursor; }
        @Override public int rvxOfflineSize() { return items.size(); }
        @Override public Object rvxOfflineItem(int index) { return items.get(index); }
        @Override public String rvxOfflineVideoId(Object occurrence) { return (String) occurrence; }
        @Override public com.google.common.util.concurrent.ListenableFuture<?> rvxFetchWatchMetadata(byte[] command) {
            throw new AssertionError("No transport in the queue ownership test");
        }
        @Override public OfflineWatchMetadata.Reply rvxDecodeWatchMetadata(Object response) {
            throw new AssertionError("No response in the queue ownership test");
        }
    }
}
