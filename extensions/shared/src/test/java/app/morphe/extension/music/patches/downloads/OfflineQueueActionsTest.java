/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class OfflineQueueActionsTest {
    private static final class Row implements OfflineQueueActions.Row {
        final String videoId;
        final ByteBuffer renderer;
        private Object insertion;

        Row(String videoId) {
            this.videoId = videoId;
            renderer = rendererBytes(videoId);
        }

        @Override public Object rvxOfflineInsertion() { return insertion; }
        @Override public void rvxOfflineInsertion(Object insertion) { this.insertion = insertion; }
        @Override public boolean equals(Object other) {
            return other instanceof Row row && renderer.equals(row.renderer);
        }
        @Override public int hashCode() { return renderer.hashCode(); }

        private static ByteBuffer rendererBytes(String videoId) {
            byte[] watch = new ProtoWriter().message(CommandRewrite.WATCH_ENDPOINT_FIELD,
                    new ProtoWriter().string(CommandRewrite.VIDEO_ID_FIELD, videoId).toByteArray()).toByteArray();
            return ByteBuffer.wrap(OfflineWatchNext.queueRow(
                    new OfflineWatchNext.Item(videoId, videoId, "", "", 0, null), watch, null));
        }
    }

    private record Queue(List<Row> rows, int cursor) implements OfflineQueueActions.Queue {
        @Override public int rvxOfflineCursor() { return cursor; }
        @Override public int rvxOfflineSize() { return rows.size(); }
        @Override public Object rvxOfflineItem(int index) { return rows.get(index); }
        @Override public String rvxOfflineVideoId(Object occurrence) { return ((Row) occurrence).videoId; }
    }

    @Test public void nativeQueueClickSelectsBoundDuplicateButNotAnExternalRow() {
        Row first = new Row("sameVideo");
        Row second = new Row("sameVideo");
        Row externalPlaylistRow = new Row("sameVideo");
        Queue queue = new Queue(List.of(first, second), 0);

        assertEquals(1, OfflineQueueActions.nativeOccurrenceIndex(queue, second, -1));
        assertEquals(1, OfflineQueueActions.nativeOccurrenceIndex(queue, second, 1));
        assertEquals(-1, OfflineQueueActions.nativeOccurrenceIndex(queue, second, 0));
        assertEquals(-1, OfflineQueueActions.nativeOccurrenceIndex(queue, externalPlaylistRow, -1));
        assertFalse(OfflineQueueActions.onQueueRowClick(externalPlaylistRow));
    }

    @Test public void nativeCommitUsesLiveSelectedOccurrenceDespiteIdenticalRendererBytes() {
        Row first = new Row("duplicate01");
        Row second = new Row("duplicate01");
        Row external = new Row("duplicate01");
        List<Row> lane = List.of(first, second);
        Queue edited = new Queue(lane, 0);
        int epoch = 7;
        var selection = new OfflineQueueActions.Selection(edited, second, 1, epoch);

        assertNotSame(first, second);
        assertEquals(first.renderer, second.renderer);
        assertEquals(0, lane.indexOf(second));
        assertEquals(1, OfflineQueueActions.nativeCommitIndex(edited, lane, second, selection, epoch));
        assertEquals(-1, OfflineQueueActions.nativeCommitIndex(edited, List.of(first), second, selection, epoch));
        assertEquals(0, OfflineQueueActions.nativeCommitIndex(edited, lane, external, selection, epoch));
        assertEquals(0, OfflineQueueActions.nativeCommitIndex(edited, lane, second, selection, epoch + 1));
        var staleIndex = new OfflineQueueActions.Selection(edited, second, 0, epoch);
        assertEquals(0, OfflineQueueActions.nativeCommitIndex(edited, lane, second, staleIndex, epoch));
        assertEquals(0, OfflineQueueActions.nativeCommitIndex(new Queue(lane, 0), lane, second, selection, epoch));
        assertEquals(0, OfflineQueueActions.nativeCommitIndex(edited, lane, second));
    }

    @Test public void adjacentDuplicatesPublishBothNextAndPreviousWithoutChangingStockEquality() {
        Row first = new Row("duplicate01");
        Row second = new Row("duplicate01");
        Row following = new Row("following01");
        List<Row> lane = List.of(first, second, following);
        Queue edited = new Queue(lane, 0);
        Queue unedited = new Queue(lane, 0);
        Navigator owner = new Navigator(edited);
        int epoch = 7;
        var insertion = new OfflineQueueActions.Insertion(
                new WeakReference<>(edited), new WeakReference<>(owner), epoch, null, null);
        first.rvxOfflineInsertion(insertion);
        second.rvxOfflineInsertion(insertion);

        int cursor = 0;
        for (int target : new int[]{1, 2, 1, 0}) {
            Row previous = lane.get(cursor);
            Row current = lane.get(target);
            var selection = new OfflineQueueActions.Selection(edited, current, target, epoch);
            cursor = OfflineQueueActions.nativeCommitIndex(edited, lane, current, selection, epoch);
            assertEquals(target, cursor);
            assertFalse(OfflineQueueActions.samePublishedOccurrence(previous, current,
                    OfflineQueueActions.distinctEditedOccurrences(edited, previous, current, epoch)));
        }
        assertTrue(OfflineQueueActions.samePublishedOccurrence(first, second, false)); // connected
        assertFalse(OfflineQueueActions.distinctEditedOccurrences(unedited, first, second, epoch));
        assertFalse(OfflineQueueActions.distinctEditedOccurrences(edited, first, second, epoch + 1));
        assertFalse(OfflineQueueActions.distinctEditedOccurrences(edited, second, second, epoch));
        owner.queue = unedited;
        assertFalse(OfflineQueueActions.distinctEditedOccurrences(edited, first, second, epoch));
    }

    @Test public void freshRowPlayAndShuffleSupersedeEditedSelectionButExactNextRetainsIt() throws Exception {
        Row first = new Row("duplicate01");
        Row second = new Row("duplicate01");
        Queue edited = new Queue(List.of(first, second), 1);
        Navigator owner = new Navigator(edited);
        Request next = new Request(second.videoId);
        Request otherVideo = new Request("otherVideo1");
        Request otherRequest = new Request(second.videoId);
        Field currentSelection = field(PlayerFallback.class, "selection");
        Field generation = field(PlayerFallback.class, "navigationGeneration");
        int epoch = ((AtomicInteger) field(OfflineQueueActions.class, "connectivity").get(null)).get();
        var occurrence = new OfflineQueueActions.Selection(edited, second, 1, epoch);
        PlayerFallback.captureNavigator(owner);
        try {
            for (int start : new int[]{2, 0, -1}) {
                var active = new PlayerFallback.Selection(owner, 1, second.videoId, true, 1,
                        null, generation.getLong(null), occurrence);
                currentSelection.set(null, active);
                owner.request = next;
                assertSame(active, PlayerFallback.editedQueueSelection(next, second.videoId));
                assertTrue(OfflineQueueActions.sameRows(edited, edited.rows(), occurrence.index()));
                assertNull(PlayerFallback.editedQueueSelection(otherRequest, second.videoId));
                assertNull(PlayerFallback.editedQueueSelection(next, otherVideo.videoId()));
                owner.request = otherVideo;
                assertNull(PlayerFallback.editedQueueSelection(otherVideo, otherVideo.videoId()));
                owner.queue = null;
                owner.request = next;
                assertNull(PlayerFallback.editedQueueSelection(next, second.videoId));
                owner.queue = edited;

                byte[] command = OfflineWatchCommand.build(second.videoId, "PL-other", Math.max(0, start),
                        CommandRewrite.TYPE_ATV, start < 0);
                if (start < 0) {
                    var shuffled = new Root(command);
                    OfflineReplay.dispatchShuffleCommand((actual, context) -> {
                        assertSame(shuffled, actual);
                        assertNull(PlayerFallback.editedQueueSelection(next, second.videoId));
                    }, shuffled, Map.of());
                } else {
                    assertTrue(OfflineReplay.noteNativePlaylistStart(command));
                }
                owner.request = otherRequest;
                assertNull(currentSelection.get(null));
                assertNull(PlayerFallback.editedQueueSelection(otherRequest, second.videoId));
                assertFalse(PlayerFallback.ownsQueueContinuation(active, next));
            }
        } finally {
            PlayerFallback.playlistStarted();
        }
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record Root(byte[] toByteArray) implements NativeDownloads.Command {
        @Override public Object rvxWithBytes(byte[] bytes) { return new Root(bytes); }
    }

    private record Request(String videoId) implements PlayerFallback.Request {
        @Override public String rvxVideoId() { return videoId; }
        @Override public Object rvxPlayerResponse(byte[] bytes) { throw new AssertionError(); }
        @Override public void rvxCancelPlayer(Object callback) { throw new AssertionError(); }
    }

    private static final class Navigator implements PlayerFallback.Navigator, OfflineQueueActions.Owner {
        Queue queue;
        Request request;

        Navigator(Queue queue) { this.queue = queue; }
        @Override public Object rvxOfflineQueue() { return queue; }
        @Override public List<?> rvxItems() { return queue.rows(); }
        @Override public String rvxVideoId(Object item) { return queue.rvxOfflineVideoId(item); }
        @Override public long rvxItemId(Object item) { throw new AssertionError(); }
        @Override public int rvxCurrent() { return queue.cursor(); }
        @Override public Object rvxPlayback() { throw new AssertionError(); }
        @Override public void rvxCancelAndStop() { throw new AssertionError(); }
        @Override public boolean rvxOwnsRequest(Object request) { return this.request == request; }
        @Override public void rvxCancelLoad() { throw new AssertionError(); }
        @Override public Object rvxJump(Object item, boolean restart) { throw new AssertionError(); }
        @Override public void rvxDispatch(Object command) { throw new AssertionError(); }
    }

    @Test public void onlyIdenticalOrderAndCursorRetainDuplicateOccurrences() {
        Row nowPlaying = new Row("keepPlaying");
        Row firstAdd = new Row("repeatedAdd");
        Row secondAdd = new Row("repeatedAdd");
        List<Row> ordered = List.of(nowPlaying, firstAdd, secondAdd);

        assertTrue(OfflineQueueActions.sameRows(new Queue(ordered, 2), ordered, 2));
        assertFalse(OfflineQueueActions.sameRows(new Queue(ordered, 1), ordered, 2));
        assertFalse(OfflineQueueActions.sameRows(new Queue(List.of(nowPlaying, secondAdd, firstAdd), 2), ordered, 2));
        assertFalse(OfflineQueueActions.sameRows(new Queue(List.of(nowPlaying, firstAdd), 1), ordered, 1));
        assertFalse(OfflineQueueActions.sameRows(
                new Queue(List.of(nowPlaying, firstAdd, new Row("repeatedAdd")), 2), ordered, 2));
    }

    private static final String SONG = "abcdefghijk";
    private static final String OTHER_SONG = "lmnopqrstuv";
    private static final int QUEUE_ENDPOINT = 163_162_354;
    private static final int PLAY_NEXT = 1;
    private static final int ADD_TO_QUEUE = 2;
    private static final OfflineQueueActions.AddConditions CONNECTED = new Conditions(true, false);

    private record Conditions(boolean reachable, boolean jamOwnsQueue) implements OfflineQueueActions.AddConditions {}

    private static final class CarrierRoot implements NativeDownloads.Command, OfflineQueueActions.Carrier {
        private final byte[] bytes;
        private Object ticket;

        CarrierRoot(byte[] bytes) { this.bytes = bytes; }

        @Override public byte[] toByteArray() { return bytes; }
        @Override public Object rvxWithBytes(byte[] copy) { return new CarrierRoot(copy); }
        @Override public Object rvxQueueTicket() { return ticket; }
        @Override public void rvxQueueTicket(Object value) { ticket = value; }
    }

    /** A native lookup client that only has the stock request. */
    private static class StockClient implements OfflineQueueActions.Lookup {
        final ReplayFuture stockReply = new ReplayFuture();
        int stockRequests;

        @Override public Object rvxRestoreSong(byte[] renderer) { throw new AssertionError(); }
        @Override public Object rvxOfflineResult(List<?> rows) { throw new AssertionError(); }
        @Override public ListenableFuture<?> rvxStockQueueLookup(Object command, Executor executor) {
            stockRequests++;
            return stockReply;
        }
    }

    private static final class IsolatingClient extends StockClient implements OfflineQueueActions.IsolatedLookup {
        final ReplayFuture isolatedReply = new ReplayFuture();
        int isolatedRequests;
        Object isolatedCommand;

        @Override public ListenableFuture<?> rvxIsolatedQueueLookup(Object command, Executor executor) {
            isolatedRequests++;
            isolatedCommand = command;
            return isolatedReply;
        }

        @Override public List<?> rvxIsolatedQueueRows(Object result) { return (List<?>) result; }
    }

    private static byte[] queueCommand(ProtoWriter target, int position, int marker) {
        ProtoWriter endpoint = new ProtoWriter().message(1, target.toByteArray()).int32(2, position);
        if (marker != 0) endpoint.bool(marker, true);
        return new ProtoWriter().string(2, "tracking").message(QUEUE_ENDPOINT, endpoint.toByteArray()).toByteArray();
    }

    private static byte[] addSong(String videoId) {
        return queueCommand(new ProtoWriter().string(1, videoId), ADD_TO_QUEUE, 0);
    }

    private static Navigator playlistSession() {
        return new Navigator(new Queue(List.of(new Row("current0001"), new Row("tail0000001")), 0));
    }

    private static Object prepare(Navigator local, Object command, OfflineQueueActions.AddConditions live) {
        return OfflineQueueActions.prepare(local, command, Map.of(), endpoint -> { }, new Object(), live);
    }

    private static void assertStaysStock(Navigator local, byte[] bytes, OfflineQueueActions.AddConditions live) {
        CarrierRoot original = new CarrierRoot(bytes);
        Object prepared = prepare(local, original, live);
        assertSame(original, prepared);
        assertNull(original.rvxQueueTicket());

        IsolatingClient client = new IsolatingClient();
        assertSame(client.stockReply, OfflineQueueActions.lookup(local, client, prepared, Runnable::run, live));
        assertEquals(1, client.stockRequests);
        assertEquals(0, client.isolatedRequests);
    }

    @Test public void connectedLocalSingleSongAddUsesTheIsolatedLookupAndLeavesTheOriginalAlone() {
        Navigator local = playlistSession();
        CarrierRoot original = new CarrierRoot(addSong(SONG));

        Object scoped = prepare(local, original, CONNECTED);
        assertNotSame(original, scoped);
        assertNull(original.rvxQueueTicket());
        assertArrayEquals(original.toByteArray(), ((CarrierRoot) scoped).toByteArray());

        IsolatingClient client = new IsolatingClient();
        var reply = OfflineQueueActions.lookup(local, client, scoped, Runnable::run, CONNECTED);
        assertSame(client.isolatedReply, reply);
        assertSame(scoped, client.isolatedCommand);
        assertEquals(1, client.isolatedRequests);
        assertEquals(0, client.stockRequests);
        client.isolatedReply.set(List.of("native row"));

        // Nothing offline owns the Add: Jam still decides, and the "added" notice is delivered at once.
        assertFalse(OfflineQueueActions.localTicket(local, scoped));
        int[] notices = {0};
        OfflineQueueActions.noticeIfUnscoped(endpoint -> notices[0]++, new Object(), scoped);
        assertEquals(1, notices[0]);
    }

    /** A local queue model that also exposes the tail placement ABI. */
    private static final class TailModel implements OfflineQueueActions.Queue, QueueAddTail.Model {
        final List<Object> rows = new ArrayList<>(List.of("current0001", "tail0000001"));

        @Override public int rvxOfflineCursor() { return 0; }
        @Override public int rvxOfflineSize() { return rows.size(); }
        @Override public Object rvxOfflineItem(int index) { return rows.get(index); }
        @Override public String rvxOfflineVideoId(Object occurrence) { return String.valueOf(occurrence); }
        @Override public int rvxTailSize(int lane) { return lane == 0 ? rows.size() : 0; }
        @Override public Object rvxTailItem(int lane, int index) { return rows.get(index); }
        @Override public int rvxTailCurrent() { return 0; }
        @Override public void rvxTailMove(int from, int to) { rows.add(to, rows.remove(from)); }
        @Override public void rvxTailAppend(List<?> added) { rows.addAll(added); }
        @Override public List<Object> rvxTailBaseline() { return null; }
    }

    private record TailOwner(Object queue) implements OfflineQueueActions.Owner {
        @Override public Object rvxOfflineQueue() { return queue; }
    }

    private enum TailArm { NEXT }

    @Test public void isolatedAddRowsReachTheTailPlacementBeforeMusicInsertsThem() {
        QueueAddTail.reset();
        TailModel model = new TailModel();
        TailOwner owner = new TailOwner(model);
        QueueAddTail.Environment stock = new QueueAddTail.Environment() {
            @Override public boolean jamOwnsQueue() { return false; }
            @Override public boolean fifoOwnsPlacement() { return false; }
            @Override public boolean reachable() { return true; }
        };

        Object scoped = OfflineQueueActions.prepare(owner, new CarrierRoot(addSong(SONG)), Map.of(),
                endpoint -> { }, new Object(), CONNECTED);
        IsolatingClient client = new IsolatingClient();
        OfflineQueueActions.lookup(owner, client, scoped, Runnable::run, CONNECTED);

        // The reply completes first; Music then inserts the same native row object and commits.
        String added = new String("native row");
        client.isolatedReply.set(List.of(added));
        model.rows.add(added);
        QueueAddTail.committed(owner, List.of(added), stock);

        QueueAddTail.pageArm(model, TailArm.NEXT);
        model.rows.add("page row");
        QueueAddTail.pageAppended(model, 0, 3, List.of("page row"), stock);

        assertEquals(List.of("current0001", "tail0000001", "page row", added), model.rows);
        QueueAddTail.reset();
    }

    @Test public void everyAddGetsItsOwnCommandCopy() {
        Navigator local = playlistSession();
        CarrierRoot original = new CarrierRoot(addSong(SONG));

        Object first = prepare(local, original, CONNECTED);
        Object second = prepare(local, original, CONNECTED);

        assertNotSame(first, second);
        assertNotNull(((CarrierRoot) first).rvxQueueTicket());
        assertNotNull(((CarrierRoot) second).rvxQueueTicket());
    }

    @Test public void playNextStaysOnTheStockRequest() {
        assertStaysStock(playlistSession(), OnlineQueueCommand.playNext(SONG), CONNECTED);
        assertStaysStock(playlistSession(), queueCommand(new ProtoWriter().string(1, SONG), PLAY_NEXT, 0), CONNECTED);
    }

    @Test public void jamOwnedQueueStaysOnTheStockRequest() {
        assertStaysStock(playlistSession(), addSong(SONG), new Conditions(true, true));
    }

    @Test public void castBranchNeverCarriesTheIsolatedRoute() {
        // Only the populated non-Cast arm calls prepare. The Cast arm's own hook hands the command on untouched.
        CarrierRoot cast = new CarrierRoot(addSong(SONG));
        assertSame(cast, OfflineQueueActions.castCommand(cast));
        assertNull(cast.rvxQueueTicket());

        IsolatingClient client = new IsolatingClient();
        assertSame(client.stockReply,
                OfflineQueueActions.lookup(playlistSession(), client, cast, Runnable::run, CONNECTED));
        assertEquals(0, client.isolatedRequests);
    }

    @Test public void playlistAlbumAndMultiVideoTargetsStayOnTheStockRequest() {
        byte[] playlistWatch = OnlineWatchCommand.build(SONG, "PLplaylist01", 3);
        assertStaysStock(playlistSession(),
                queueCommand(new ProtoWriter().string(1, SONG).string(2, "PLplaylist01"), ADD_TO_QUEUE, 0), CONNECTED);
        assertStaysStock(playlistSession(),
                queueCommand(new ProtoWriter().string(1, SONG).string(2, "OLAK5uy_album"), ADD_TO_QUEUE, 0), CONNECTED);
        assertStaysStock(playlistSession(),
                queueCommand(new ProtoWriter().string(1, SONG).message(4, playlistWatch), ADD_TO_QUEUE, 0), CONNECTED);
        assertStaysStock(playlistSession(),
                queueCommand(new ProtoWriter().string(1, SONG).string(1, OTHER_SONG), ADD_TO_QUEUE, 0), CONNECTED);
        assertStaysStock(playlistSession(), OnlineWatchCommand.build(SONG, null, 0), CONNECTED);
    }

    @Test public void offlineMarkedAddStaysOnTheStockRequest() {
        int marker = OfflineQueueCommand.PLAYER_ONLY_FIELD;
        assertStaysStock(playlistSession(), queueCommand(new ProtoWriter().string(1, SONG), ADD_TO_QUEUE, marker),
                CONNECTED);
    }

    @Test public void offlineOrUnreachableAddStaysOnTheStockRequest() {
        assertStaysStock(playlistSession(), addSong(SONG), new Conditions(false, false));
    }

    @Test public void addWithoutAPopulatedLocalQueueStaysOnTheStockRequest() {
        assertStaysStock(new Navigator(new Queue(List.of(), -1)), addSong(SONG), CONNECTED);
        assertStaysStock(new Navigator(new Queue(List.of(new Row("current0001")), -1)), addSong(SONG), CONNECTED);
        assertStaysStock(new Navigator(null), addSong(SONG), CONNECTED);
    }

    @Test public void aClientWithoutTheIsolatedLookupKeepsTheStockRequest() {
        Navigator local = playlistSession();
        Object scoped = prepare(local, new CarrierRoot(addSong(SONG)), CONNECTED);

        StockClient client = new StockClient();
        assertSame(client.stockReply, OfflineQueueActions.lookup(local, client, scoped, Runnable::run, CONNECTED));
        assertEquals(1, client.stockRequests);
    }

    @Test public void theRouteIsCheckedAgainWhenTheEnqueueReachesItsLookup() {
        Navigator local = playlistSession();
        Object scoped = prepare(local, new CarrierRoot(addSong(SONG)), CONNECTED);

        IsolatingClient jam = new IsolatingClient();
        OfflineQueueActions.lookup(local, jam, scoped, Runnable::run, new Conditions(true, true));
        assertEquals(1, jam.stockRequests);
        assertEquals(0, jam.isolatedRequests);

        IsolatingClient offline = new IsolatingClient();
        OfflineQueueActions.lookup(local, offline, scoped, Runnable::run, new Conditions(false, false));
        assertEquals(1, offline.stockRequests);
        assertEquals(0, offline.isolatedRequests);

        // A new playlist session replaced the queue the Add was meant for.
        Navigator replaced = playlistSession();
        IsolatingClient other = new IsolatingClient();
        OfflineQueueActions.lookup(replaced, other, scoped, Runnable::run, CONNECTED);
        assertEquals(1, other.stockRequests);
        assertEquals(0, other.isolatedRequests);

        local.queue = new Queue(List.of(new Row("current0001")), 0);
        IsolatingClient swapped = new IsolatingClient();
        OfflineQueueActions.lookup(local, swapped, scoped, Runnable::run, CONNECTED);
        assertEquals(1, swapped.stockRequests);
        assertEquals(0, swapped.isolatedRequests);
    }

    @Test public void commandsThatNeverPassedTheResolverKeepTheStockRequest() {
        // Jam's companion, Smart Shuffle and the manual-queue FIFO reach the native enqueue without a route.
        IsolatingClient client = new IsolatingClient();
        assertSame(client.stockReply, OfflineQueueActions.lookup(playlistSession(), client,
                new CarrierRoot(addSong(SONG)), Runnable::run, CONNECTED));
        assertEquals(0, client.isolatedRequests);
    }

    @Test public void stockReasonNamesWhyAnAddKeepsTheStockRequest() throws Exception {
        Queue loaded = new Queue(List.of(new Row("current0001")), 0);
        OfflineQueueCommand add = OfflineQueueCommand.parse(addSong(SONG));
        OfflineQueueCommand next = OfflineQueueCommand.parse(OnlineQueueCommand.playNext(SONG));

        assertNull(OfflineQueueActions.stockReason(add, loaded, CONNECTED));
        assertEquals("wire mode 1 is not Add to queue", OfflineQueueActions.stockReason(next, loaded, CONNECTED));
        assertEquals("Jam owns the queue",
                OfflineQueueActions.stockReason(add, loaded, new Conditions(true, true)));
        assertEquals("no internet", OfflineQueueActions.stockReason(add, loaded, new Conditions(false, false)));
        assertEquals("no populated local queue", OfflineQueueActions.stockReason(add, null, CONNECTED));
        assertEquals("no populated local queue",
                OfflineQueueActions.stockReason(add, new Queue(List.of(), -1), CONNECTED));
        assertEquals("not a single song (playlist, album, several videos or unknown command)",
                OfflineQueueActions.stockReason(null, loaded, CONNECTED));
    }
}
