/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.music.patches.downloads.NativeDownloads.Command;
import app.morphe.extension.music.patches.jam.JamUI;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Substitutes only the metadata future of a populated local queue's native enqueue. */
@SuppressWarnings("unused")
public final class OfflineQueueActions {
    public interface Carrier {
        @Nullable Object rvxQueueTicket();
        void rvxQueueTicket(Object value);
    }

    /** Reads the stock live local queue; implementations do not cache a model across replacements. */
    public interface Owner {
        @Nullable Object rvxOfflineQueue();
    }

    public interface Queue {
        int rvxOfflineCursor();
        int rvxOfflineSize();
        Object rvxOfflineItem(int index);
        String rvxOfflineVideoId(Object occurrence);
    }

    public interface TapSource {
        @Nullable Object rvxBoundQueueRow();
        Object rvxBoundQueueNavigator();
    }

    public interface Row {
        @Nullable Object rvxOfflineInsertion();
        void rvxOfflineInsertion(Object queue);
    }

    public interface Lookup {
        Object rvxRestoreSong(byte[] renderer);
        Object rvxOfflineResult(List<?> rows);
        ListenableFuture<?> rvxStockQueueLookup(Object command, Executor executor);
    }

    /**
     * The same native lookup without a reply token, observer or live queue-context write. The patch adds this
     * interface only where that clone was resolved.
     */
    public interface IsolatedLookup {
        ListenableFuture<?> rvxIsolatedQueueLookup(Object command, Executor executor);
        List<?> rvxIsolatedQueueRows(Object result);
    }

    /** Live conditions that keep a connected Add to queue off the isolated lookup. */
    interface AddConditions {
        boolean reachable();
        boolean jamOwnsQueue();
    }

    private record PlayerCommand(int connectivity) {}

    public interface NoticeSource {
        void rvxQueueNotice(Object endpoint);
    }

    static final class Notice {
        private final WeakReference<NoticeSource> source;
        @Nullable private Object endpoint;

        Notice(NoticeSource source, Object endpoint) {
            this.source = new WeakReference<>(source);
            this.endpoint = endpoint;
        }

        synchronized void deliver() {
            Object value = endpoint;
            endpoint = null;
            NoticeSource dispatcher = source.get();
            if (value != null && dispatcher != null) {
                Utils.runOnMainThreadNowOrLater(() -> dispatcher.rvxQueueNotice(value));
            }
        }
    }

    private record Ticket(Owner owner, Queue queue, OfflineQueueCommand command, @Nullable Object tag,
                          int connectivity, Notice notice) {}

    /**
     * A connected single-song Add to queue admitted to the isolated lookup. Never a Ticket: nothing is offline.
     * {@code tailEpoch} is the tail placement's offline-edge count when the Add was prepared.
     */
    private record IsolatedAdd(Owner owner, Queue queue, String videoId, int tailEpoch) {}

    record Insertion(WeakReference<Queue> queue, WeakReference<Owner> owner, int connectivity,
                     @Nullable WeakReference<Object> playerOccurrence, @Nullable Notice notice) {}

    /** Selected occurrence in the native queue. */
    record Selection(Queue queue, Object occurrence, int index, int connectivity) {}

    record Continuation(Selection selected, Object playbackSelection, Object request, int tailFrom,
                        @Nullable OfflinePlaybackArtwork.Retained artwork) {}

    public interface TailQueue {
        void rvxAppendReplayRows(List<?> rows);
    }

    private static final String TAG = "com.google.android.libraries.youtube.innertube.endpoint.tag";
    private static final int MENU_WRAPPER = 20;
    private static final int MENU_RENDERER = 66_439_850;
    private static final int INSERT_AT_END = 2;
    private static final AddConditions LIVE_CONDITIONS = new AddConditions() {
        @Override public boolean reachable() {
            return PlaybackConnectivity.reachable();
        }

        @Override public boolean jamOwnsQueue() {
            return Settings.JAM_ENABLED.get() && JamUI.ownsQueue();
        }
    };
    private static final AtomicInteger connectivity = new AtomicInteger();

    private OfflineQueueActions() {}

    /** The patch flips this only after the resolver, factory and replay commit ABI have all been installed. */
    public static boolean supported() {
        return false;
    }

    /** The player passes its own native storage and exact current occurrence, even before any navigation. */
    static boolean available(Object nativeStorage, String videoId, Object occurrence) {
        return offline() && mayHold(videoId) && nativeStorage instanceof Owner owner
                && owner.rvxOfflineQueue() instanceof Queue queue
                && currentOccurrence(queue, occurrence) && videoId.equals(queue.rvxOfflineVideoId(occurrence));
    }

    /** Recognize synthesized commands before dispatch; only the actual queue resolver admits them. */
    static boolean markPlayerCommand(Object command) {
        if (!(command instanceof Carrier carrier) || !offline()) return false;
        carrier.rvxQueueTicket(new PlayerCommand(connectivity.get()));
        return true;
    }

    @Nullable
    private static PlayerCommand playerCommand(Object command) {
        return command instanceof Carrier carrier && carrier.rvxQueueTicket() instanceof PlayerCommand player
                ? player : null;
    }

    private static boolean offline() {
        return supported() && OfflineReplay.enabled() && LocalDownloadManager.downloadMode().streamCopy()
                && !PlaybackConnectivity.reachable();
    }

    private static boolean mayHold(String videoId) {
        if (!RawAudioFiles.validId(videoId)) return false;
        if (OfflineReplay.holds(videoId)) return true;

        var markers = DownloadedMarkers.loadedRegistry();
        if (markers != null && OfflineReplay.holds(markers.canonicalId(videoId))) return true;
        return OfflineReplayLogic.videoOf(videoId, VideoCopyStore.pairsIfLoaded()) != null;
    }

    private static boolean populated(Queue queue) {
        synchronized (queue) {
            int cursor = queue.rvxOfflineCursor();
            return cursor >= 0 && cursor < queue.rvxOfflineSize();
        }
    }

    private static boolean currentOccurrence(Queue queue, @Nullable Object occurrence) {
        if (occurrence == null) return false;
        synchronized (queue) {
            int cursor = queue.rvxOfflineCursor();
            return cursor >= 0 && cursor < queue.rvxOfflineSize() && queue.rvxOfflineItem(cursor) == occurrence;
        }
    }

    static void reconnected() {
        connectivity.incrementAndGet();
    }

    /** The marked player command must not enter the empty-queue or other nonlocal stock arms. */
    @Nullable
    public static Object checkPlayerCommand(Object manager, Object original, @Nullable Map<?, ?> context) {
        PlayerCommand player = playerCommand(original);
        if (player == null) return original;
        Object occurrence = context == null ? null : context.get(TAG);
        return player.connectivity() == connectivity.get() && offline() && manager instanceof Owner owner
                && owner.rvxOfflineQueue() instanceof Queue queue && currentOccurrence(queue, occurrence)
                ? original : null;
    }

    /** The non-Cast arm owns local insertion; a marked command can never move a Cast queue. */
    @Nullable
    public static Object castCommand(Object original) {
        return playerCommand(original) == null ? original : null;
    }

    /** Called in the populated non-Cast arm; defer its notice until insertion commits. */
    @Nullable
    public static Object prepare(Object manager, Object original, @Nullable Map<?, ?> context,
                                 NoticeSource notifier, Object endpoint) {
        return prepare(manager, original, context, notifier, endpoint, LIVE_CONDITIONS);
    }

    @Nullable
    static Object prepare(Object manager, Object original, @Nullable Map<?, ?> context,
                          NoticeSource notifier, Object endpoint, AddConditions live) {
        PlayerCommand player = playerCommand(original);
        if (!(manager instanceof Owner owner) || !(original instanceof Command command)) {
            return player == null ? original : null;
        }
        if (!offline()) return player == null ? scopeConnectedAdd(owner, command, live) : null;
        Object tag = context == null ? null : context.get(TAG);

        try {
            byte[] bytes = command.toByteArray();
            OfflineQueueCommand target = OfflineQueueCommand.parse(bytes);
            if (target == null) {
                PlayerFallback.unavailable();
                return null;
            }
            if (target.playerOnly() != (player != null)) return target.playerOnly() ? null : original;

            // The exact populated non-Cast resolver arm owns this manager, independently of navigation.
            if (!(owner.rvxOfflineQueue() instanceof Queue queue) || !populated(queue)) {
                return player == null ? original : null;
            }
            int generation = connectivity.get();
            if (player != null && (player.connectivity() != generation || !currentOccurrence(queue, tag)
                    || !target.videoId().equals(queue.rvxOfflineVideoId(tag)))) {
                return null;
            }

            // A dedicated Root carries this invocation's ticket. The original can be shared by menus and threads.
            Object copy = command.rvxWithBytes(bytes);
            if (copy != original && copy instanceof Carrier carrier) {
                carrier.rvxQueueTicket(new Ticket(owner, queue, target, tag, generation,
                        new Notice(notifier, endpoint)));
                return copy;
            }
        } catch (Exception | LinkageError failure) {
            Logger.printDebug(() -> "OfflineQueueActions: could not scope enqueue (" +
                    failure.getClass().getSimpleName() + ")");
        }
        return player == null ? original : null;
    }

    /**
     * Only the resolver's populated non-Cast arm calls this, so a Cast queue never reaches it. A user Add to queue
     * of one song, on a local queue without Jam, gets a copy of its command that carries the isolated route.
     */
    private static Object scopeConnectedAdd(Owner owner, Command command, AddConditions live) {
        OfflineQueueCommand target = null;
        String reason;
        try {
            byte[] bytes = command.toByteArray();
            target = OfflineQueueCommand.parse(bytes);
            Queue queue = owner.rvxOfflineQueue() instanceof Queue local ? local : null;
            reason = stockReason(target, queue, live);
            if (reason == null) {
                // A dedicated Root carries this invocation's route. The original can be shared by menus and threads.
                Object copy = command.rvxWithBytes(bytes);
                if (copy != command && copy instanceof Carrier carrier) {
                    carrier.rvxQueueTicket(new IsolatedAdd(owner, queue, target.videoId(),
                            QueueAddTail.connectivityEpoch()));
                    return copy;
                }
                reason = "the command cannot carry the isolated route";
            }
        } catch (Exception | LinkageError failure) {
            reason = "could not scope enqueue (" + failure.getClass().getSimpleName() + ")";
        }

        logStockRequest(target == null ? "?" : target.videoId(), reason);
        return command;
    }

    /** Why an Add to queue keeps the stock request and its reply token; null when the isolated lookup may serve it. */
    @Nullable
    static String stockReason(@Nullable OfflineQueueCommand target, @Nullable Queue queue, AddConditions live) {
        if (target == null) return "not a single song (playlist, album, several videos or unknown command)";
        if (target.playerOnly()) return "offline player action";
        if (target.position() != INSERT_AT_END) return "wire mode " + target.position() + " is not Add to queue";
        if (queue == null || !populated(queue)) return "no populated local queue";
        if (!live.reachable()) return "no internet";
        return live.jamOwnsQueue() ? "Jam owns the queue" : null;
    }

    private static void logStockRequest(String videoId, String reason) {
        Logger.printDebug(() -> "QueueAdd: stock request v=" + videoId + " (" + reason + ")");
    }

    /** Jam may wrap the stock enqueue; a ticket can only come from the resolver's local branch. */
    public static boolean localTicket(Object manager, Object command) {
        return playerCommand(command) != null || (command instanceof Carrier carrier
                && carrier.rvxQueueTicket() instanceof Ticket ticket && ticket.owner() == manager);
    }

    /** Replaces the sole lookup call inside stock enqueue; callback and insertion still belong to Music. */
    public static ListenableFuture<?> lookup(Object manager, Lookup client, Object command, Executor executor) {
        return lookup(manager, client, command, executor, LIVE_CONDITIONS);
    }

    static ListenableFuture<?> lookup(Object manager, Lookup client, Object command, Executor executor,
                                      AddConditions live) {
        if (playerCommand(command) != null) {
            // The click was admitted, but stock took an arm other than the populated local queue.
            ReplayFuture declined = new ReplayFuture();
            declined.cancel(false);
            return declined;
        }
        if (command instanceof Carrier routed && routed.rvxQueueTicket() instanceof IsolatedAdd add) {
            return isolatedLookup(manager, client, command, executor, add, live);
        }
        if (!(command instanceof Carrier carrier) || !(carrier.rvxQueueTicket() instanceof Ticket ticket)
                || ticket.owner() != manager) {
            return client.rvxStockQueueLookup(command, executor);
        }

        ReplayFuture reply = new ReplayFuture();
        Utils.runOnBackgroundThread(() -> {
            if (reply.isDone()) return;
            try {
                if (!stillOwns(ticket)) {
                    reply.cancel(false);
                    return;
                }
                OfflineWatchNext.Item stored = storedRow(ticket.command().videoId());
                if (stored == null) {
                    if (stillOwns(ticket)) PlayerFallback.unavailable();
                    reply.cancel(false);
                    return;
                }
                byte[] watch = watchFor(ticket.command(), stored);
                byte[] menu = capturedMenu(ticket.tag(), ticket.command().videoId(), stored.videoId());
                Object item = client.rvxRestoreSong(OfflineWatchNext.queueRow(stored, watch, menu));
                if (!(item instanceof Row row)) throw new IOException("native row has no occurrence marker");
                Object result = client.rvxOfflineResult(List.of(item));
                if (!stillOwns(ticket)) {
                    reply.cancel(false);
                    return;
                }

                WeakReference<Object> occurrence = ticket.command().playerOnly()
                        ? new WeakReference<>(ticket.tag()) : null;
                row.rvxOfflineInsertion(new Insertion(new WeakReference<>(ticket.queue()),
                        new WeakReference<>(ticket.owner()), ticket.connectivity(), occurrence, ticket.notice()));
                reply.set(result);
            } catch (Exception | LinkageError failure) {
                reply.setException(failure);
                Logger.printDebug(() -> "OfflineQueueActions: local native reply failed (" +
                        failure.getClass().getSimpleName() + ")");
            }
        });
        return reply;
    }

    /**
     * Sends the same request without the reply token and returns its mapped native result, so Music inserts the row
     * itself. The shared queue context is neither read for the request nor written by the reply.
     */
    private static ListenableFuture<?> isolatedLookup(Object manager, Lookup client, Object command,
                                                      Executor executor, IsolatedAdd add, AddConditions live) {
        String reason = isolatedBlocker(manager, client, add, live);
        if (reason != null) {
            logStockRequest(add.videoId(), reason);
            return client.rvxStockQueueLookup(command, executor);
        }

        IsolatedLookup isolated = (IsolatedLookup) client;
        ListenableFuture<?> future = isolated.rvxIsolatedQueueLookup(command, executor);
        future.addListener(() -> recordIsolatedRows(add, isolated, future), Runnable::run);
        return future;
    }

    /** The route was admitted in the resolver; it must still hold when the enqueue reaches its lookup. */
    @Nullable
    private static String isolatedBlocker(Object manager, Lookup client, IsolatedAdd add, AddConditions live) {
        if (!(client instanceof IsolatedLookup)) return "the native lookup could not be isolated";
        if (add.owner() != manager || manager instanceof Owner owner && owner.rvxOfflineQueue() != add.queue()) {
            return "the local queue was replaced before the request";
        }
        if (!live.reachable()) return "no internet";
        return live.jamOwnsQueue() ? "Jam owns the queue" : null;
    }

    /** Logs the mapped rows and hands them to the tail placement, before Music's own callback inserts them. */
    private static void recordIsolatedRows(IsolatedAdd add, IsolatedLookup client, ListenableFuture<?> future) {
        String videoId = add.videoId();
        try {
            List<?> rows = client.rvxIsolatedQueueRows(future.get());
            QueueAddTail.pending(add.queue(), rows, add.tailEpoch());
            Logger.printDebug(() -> "QueueAdd: isolated lookup v=" + videoId + " rows=" + rows.size());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | CancellationException failure) {
            Logger.printDebug(() -> "QueueAdd: isolated lookup v=" + videoId + " ended: " + failure);
        } catch (Exception | LinkageError failure) {
            Logger.printDebug(() -> "QueueAdd: isolated lookup v=" + videoId + " rows unknown: " + failure);
        }
    }

    private static boolean stillOwns(Ticket ticket) {
        Queue queue = ticket.queue();
        return ticket.owner().rvxOfflineQueue() == queue && (ticket.command().playerOnly()
                ? currentOccurrence(queue, ticket.tag()) : populated(queue));
    }

    /** The native completion executor may run after the target queue has already been replaced. */
    public static boolean allowsReply(Object manager, @Nullable List<?> items) {
        if (items == null || items.size() != 1 || !(items.get(0) instanceof Row row)
                || !(row.rvxOfflineInsertion() instanceof Insertion insertion)) return true;

        Queue live = insertion.queue().get();
        if (live == null || !(manager instanceof Owner owner) || owner.rvxOfflineQueue() != live) return false;

        WeakReference<Object> occurrence = insertion.playerOccurrence();
        boolean allowed = occurrence == null ? populated(live) : currentOccurrence(live, occurrence.get());
        if (allowed) Logger.printDebug(() -> "OfflineQueueActions: typed reply admitted to native insertion");
        return allowed;
    }

    public static void noticeIfUnscoped(NoticeSource source, Object endpoint, Object command) {
        if (!(command instanceof Carrier carrier) || !(carrier.rvxQueueTicket() instanceof Ticket)) {
            source.rvxQueueNotice(endpoint);
        }
    }

    public static void fastInsertionCommitted(Object command) {
        if (command instanceof Carrier carrier && carrier.rvxQueueTicket() instanceof Ticket ticket) {
            ticket.notice().deliver();
        }
    }

    public static void insertionCommitted(@Nullable List<?> items) {
        if (items == null || items.size() != 1 || !(items.get(0) instanceof Row row)
                || !(row.rvxOfflineInsertion() instanceof Insertion insertion) || insertion.notice() == null) return;

        Owner owner = insertion.owner().get();
        Queue queue = insertion.queue().get();
        if (owner == null || queue == null || owner.rvxOfflineQueue() != queue) return;
        boolean inserted;
        synchronized (queue) {
            inserted = nativeOccurrenceIndex(queue, row, -1) >= 0;
        }
        if (inserted) insertion.notice().deliver();
    }

    /** Called inside the stock primary queue monitor after validating the exact replay continuation. */
    public static void appendReplayTail(Object queue, Object token, List<?> parsedRows) {
        if (!(queue instanceof TailQueue target) || !(token instanceof Continuation replay)
                || replay.tailFrom() < 0 || parsedRows.size() <= 1
                || !PlayerFallback.markTailInstalled(queue, replay.tailFrom())) return;

        target.rvxAppendReplayRows(parsedRows.subList(1, parsedRows.size()));
        Logger.printDebug(() -> "OfflineReplay: appended " + (parsedRows.size() - 1) + " saved tail rows");
    }

    @Nullable
    private static OfflineWatchNext.Item storedRow(String original) throws IOException {
        OfflineWatchNext.Item exact = videoRow(original, null);
        if (exact != null) return exact;

        exact = audioRow(original);
        if (exact != null) return exact;

        // No arbitrary metadata fallback: only a committed song/video pair or stored audio alias can change ID.
        Map<String, String> pairs = VideoCopyStore.pairs();
        String song = pairs.get(original);
        if (song == null) song = RawAudioStore.aliases().get(original);
        if (song == null) song = original;
        if (!original.equals(song)) {
            OfflineWatchNext.Item alias = audioRow(song);
            if (alias != null) return alias;
        }
        String video = OfflineReplayLogic.videoOf(song, pairs);
        return video == null || original.equals(video) ? null : videoRow(video, song);
    }

    @Nullable
    private static OfflineWatchNext.Item audioRow(String id) throws IOException {
        OfflineCopyFiles.Stored saved = OfflineCopyStore.find(id);
        if (saved == null || !id.equals(saved.metadata().videoId())) return null;
        RawAudioFiles.Entry raw = RawAudioStore.find(id);
        if (raw == null || raw.tiedToNativeTrack()) return null;

        PlayerResponse player = PlayerResponse.parseFrom(saved.response());
        return StreamSwapLogic.savedAudio(id, id, raw, player, player) == null ? null
                : localArtwork(saved.metadata(), id, CommandRewrite.TYPE_ATV);
    }

    @Nullable
    private static OfflineWatchNext.Item videoRow(String id, @Nullable String expectedSong) throws IOException {
        VideoCopyFiles.Entry copy = VideoCopyStore.find(id);
        if (copy == null || !id.equals(copy.videoId())
                || (expectedSong != null && !expectedSong.equals(copy.songId()))) return null;
        byte[] response = VideoCopyStore.response(id);
        if (response == null) return null;

        PlayerResponse player = PlayerResponse.parseFrom(response);
        var details = player.getVideoDetails();
        if (!id.equals(details.getVideoId())
                || VideoCopyLogic.stored(player, copy.video().itag(), copy.audio().itag()) == null) return null;
        OfflineCopyMetadata metadata = OfflineCopyStore.metadataOf(copy.songId());
        return metadata != null && copy.songId().equals(metadata.videoId())
                ? localArtwork(metadata, id, CommandRewrite.TYPE_OMV)
                : new OfflineWatchNext.Item(id, details.getTitle(), details.getOwnerChannelName(), "",
                        copy.durationMs(), OfflineCopyStore.coverUrl(id, null), CommandRewrite.TYPE_OMV);
    }

    private static OfflineWatchNext.Item localArtwork(OfflineCopyMetadata copy, String id, int type) {
        return new OfflineWatchNext.Item(id, copy.title(), copy.artist(), copy.album(), copy.durationMs(),
                OfflineCopyStore.coverUrl(copy.videoId(), null), type);
    }

    private static byte[] watchFor(OfflineQueueCommand original, OfflineWatchNext.Item stored) {
        String id = stored.videoId();
        if (id.equals(original.videoId()) && original.watch() != null) return original.watch();

        byte[] music = new ProtoWriter().int32(CommandRewrite.MUSIC_VIDEO_TYPE_FIELD, stored.musicVideoType())
                .toByteArray();
        byte[] config = new ProtoWriter().message(CommandRewrite.MUSIC_CONFIG_FIELD, music).toByteArray();
        byte[] watch = new ProtoWriter().string(CommandRewrite.VIDEO_ID_FIELD, id)
                .message(CommandRewrite.SUPPORTED_CONFIG_FIELD, config).toByteArray();
        return new ProtoWriter().message(CommandRewrite.WATCH_ENDPOINT_FIELD, watch).toByteArray();
    }

    @Nullable
    private static byte[] capturedMenu(@Nullable Object tag, String original, String playable) {
        Object renderer = tag instanceof PlayerFallback.QueueRow row ? row.rvxQueueRenderer() : tag;
        if (!original.equals(playable) || !(renderer instanceof MessageLite proto)) return null;
        try {
            byte[] bytes = proto.toByteArray();
            byte[] id = NativeDownloads.fieldBytes(bytes, OfflineWatchNext.TRACK_VIDEO_ID_FIELD);
            byte[] watch = NativeDownloads.fieldBytes(bytes, OfflineWatchNext.TRACK_ENDPOINT_FIELD);
            if (id == null || watch == null || !original.equals(new String(id, StandardCharsets.UTF_8))) {
                return null;
            }
            var target = OfflineReplayLogic.onlineWatchOf(watch);
            if (target == null) target = OfflineReplayLogic.offlineWatchOf(watch);
            if (target == null || !original.equals(target.videoId())) return null;

            byte[] wrapper = NativeDownloads.fieldBytes(bytes, MENU_WRAPPER);
            return wrapper == null ? null : NativeDownloads.fieldBytes(wrapper, MENU_RENDERER);
        } catch (IOException malformed) {
            return null;
        }
    }

    /** Before the stock wrapper can mistake an edited row's Watch for an external playlist launch. */
    public static boolean onQueueRowClick(Object source) {
        if (!(source instanceof TapSource presenter) || !offline()) return false;
        Object row = presenter.rvxBoundQueueRow();
        if (row == null) return false;

        Object owner = presenter.rvxBoundQueueNavigator();
        Selection selected = select(owner, row, -1);
        return selected != null && PlayerFallback.playEditedQueueRow(owner, selected);
    }

    @Nullable
    static Selection select(Object owner, Object row, int index) {
        if (!supported() || !(owner instanceof Owner access) || !(access.rvxOfflineQueue() instanceof Queue queue)) {
            return null;
        }
        synchronized (queue) {
            int cursor = nativeOccurrenceIndex(queue, row, index);
            return cursor < 0 ? null : new Selection(queue, row, cursor, connectivity.get());
        }
    }

    /** Caller holds the native monitor. An unindexed presenter may find only its own bound occurrence. */
    static int nativeOccurrenceIndex(Queue queue, Object occurrence, int index) {
        if (occurrence == null) return -1;
        int count = queue.rvxOfflineSize();
        if (index >= 0) return index < count && queue.rvxOfflineItem(index) == occurrence ? index : -1;
        for (int i = 0; i < count; i++) {
            if (queue.rvxOfflineItem(i) == occurrence) return i;
        }
        return -1;
    }

    /** Runs inside the native lane monitor; the model still owns cursor writes and notifications. */
    public static int nativeCommitIndex(Object model, List<?> lane, Object occurrence) {
        Selection selected = PlayerFallback.queueSelectionAtCommit(model, occurrence);
        if (selected == null || !offline()) return lane.indexOf(occurrence);
        return nativeCommitIndex(model, lane, occurrence, selected, connectivity.get());
    }

    static int nativeCommitIndex(Object model, List<?> lane, Object occurrence,
                                 @Nullable Selection selected, int epoch) {
        if (selected == null || selected.queue() != model || selected.occurrence() != occurrence
                || selected.connectivity() != epoch
                || nativeOccurrenceIndex(selected.queue(), occurrence, selected.index()) < 0) {
            return lane.indexOf(occurrence);
        }
        // The native factory assigns unique identities even when two Watch descriptors compare equal.
        for (int i = 0, size = lane.size(); i < size; i++) {
            if (lane.get(i) == occurrence) return i;
        }
        // This occurrence is still live, but in another lane. Let the native commit try that lane.
        return -1;
    }

    /**
     * Only the native current-row publisher treats a live offline insertion as a distinct occurrence. The same
     * publisher tells the tail placement which added occurrence started playing.
     */
    public static boolean samePublishedOccurrence(Object model, @Nullable Object previous, @Nullable Object current) {
        QueueAddTail.published(model, current);
        boolean distinguish = distinctEditedOccurrences(model, previous, current, connectivity.get()) && offline();
        return samePublishedOccurrence(previous, current, distinguish);
    }

    static boolean samePublishedOccurrence(@Nullable Object previous, @Nullable Object current, boolean distinguish) {
        return previous == current || !distinguish && previous != null && previous.equals(current);
    }

    static boolean distinctEditedOccurrences(Object model, @Nullable Object previous, @Nullable Object current,
                                              int epoch) {
        return previous != null && current != null && previous != current
                && (liveInsertion(model, previous, epoch) || liveInsertion(model, current, epoch));
    }

    private static boolean liveInsertion(Object model, Object occurrence, int epoch) {
        if (!(occurrence instanceof Row row) || !(row.rvxOfflineInsertion() instanceof Insertion insertion)
                || insertion.queue().get() != model || insertion.connectivity() != epoch) return false;
        Owner owner = insertion.owner().get();
        return owner != null && owner.rvxOfflineQueue() == model;
    }

    static boolean ownsSelectedOccurrence(Object owner, Selection selected) {
        return offline() && ownsLiveOccurrence(owner, selected);
    }

    static boolean ownsLiveOccurrence(Object owner, Selection selected) {
        Queue queue = selected.queue();
        if (!(owner instanceof Owner access) || access.rvxOfflineQueue() != queue) return false;
        synchronized (queue) {
            return nativeOccurrenceIndex(queue, selected.occurrence(), selected.index()) >= 0;
        }
    }

    static boolean currentReplayOccurrence(Queue queue, Object occurrence) {
        int cursor = queue.rvxOfflineCursor();
        return cursor >= 0 && cursor < queue.rvxOfflineSize() && queue.rvxOfflineItem(cursor) == occurrence;
    }

    /** Called under the native model's monitor by the R9 metadata-only splice. */
    static boolean retains(Object queue, Continuation reply) {
        Queue live = reply.selected().queue();
        if (live != queue || live != PlayerFallback.localQueue()
                || !PlayerFallback.ownsQueueContinuation(reply.playbackSelection(), reply.request())) return false;
        boolean same = currentReplayOccurrence(live, reply.selected().occurrence());
        if (same) Logger.printDebug(() -> "OfflineQueueActions: retained exact native queue occurrence");
        if (same) {
            if (reply.artwork() != null) reply.artwork().apply();
        }
        return same;
    }

    static boolean sameRows(Queue live, List<?> expected, int cursor) {
        if (cursor < 0 || cursor >= expected.size()
                || live.rvxOfflineCursor() != cursor || live.rvxOfflineSize() != expected.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (live.rvxOfflineItem(index) != expected.get(index)) return false;
        }
        return true;
    }
}
