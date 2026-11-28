/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-17.
 * Modified by lavinhoque33, 2026-11-14.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import app.morphe.extension.music.patches.downloads.NativeDownloads.Command;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Opens saved playlists in the native detail fragment and plays downloaded tracks through the app's own offline
 * command, player and watch-next paths. Online playback stays native; StreamSwap still supplies the local media.
 */
@SuppressWarnings("unused")
public final class OfflineReplay {
    /**
     * The id of the list of the songs no downloaded list holds (No Playlist), next to the ids of the downloaded
     * playlists and albums.
     */
    public static final String SONGS = "songs";

    private static final long QUEUE_WAIT_MS = 20_000;
    private static final long QUEUE_POLL_MS = 250;
    private static final long QUEUE_SETTLE_MS = 600;
    private static final long QUEUE_STEP_MS = 350;

    /** Added by the Downloads patch to the class that dispatches a command, so a command can be run again. */
    public interface Dispatcher {
        void rvxDispatch(Object command, Map<?, ?> context);
    }

    /** Added by the Downloads patch to the watch-next request. */
    public interface NextRequest {
        /** The watch command the request was made for. */
        Object rvxCommand();

        /** The stock response object, with either a fresh shuffle marker or an opaque continuation token. */
        Object rvxNextResponse(byte[] bytes, boolean shuffledStart, @Nullable Object continuation);
    }

    /** Added to the native primary queue only where the saved shuffle commit is bridged. */
    public interface LocalShuffleQueue {
        boolean rvxMatchesLocalShuffle(List<?> rows, int cursor);
    }

    /** Bridges on the native detail fragment; parsing and framework registration are worker-only. */
    public interface DetailPage {
        @Nullable String rvxInitialBrowseId(Object request);

        @Nullable Object rvxSavedPageModel(byte[] bytes);

        boolean rvxFinishInitialBrowse(Object request, @Nullable Object model);

        boolean rvxOwnsDetail(Object request);

        void rvxIssueDetail(Object request);

        void rvxFailDetail(Object request, Throwable failure);
    }


    /** Main-thread state of an exact native detail request; neither key nor request is retained strongly. */
    private static final Map<DetailPage, DetailLoad> localDetails = new java.util.WeakHashMap<>();

    private static final class DetailLoad {
        final WeakReference<Object> request;
        final String pageId;
        boolean loading = true;
        int readAttempt;
        boolean local;
        boolean live;
        @Nullable Throwable failure;

        DetailLoad(Object request, String pageId) {
            this.request = new WeakReference<>(request);
            this.pageId = pageId;
        }
    }

    /** What the last dispatched command left behind: the way to run a command of our own. */
    private record Captured(WeakReference<Dispatcher> dispatcher, Command template,
                            WeakReference<Map<?, ?>> context) {}

    /** Strong for the lifetime of a claimed action, not stored beyond its eventual dispatch. */
    private record Start(Dispatcher dispatcher, Command template, Map<?, ?> context, int generation) {}

    private record ShuffleSource(OfflineSourceStore.Source saved, boolean legacy) {}

    private record ShufflePlan(ShuffleSource source, List<OfflineWatchNext.Item> items, byte[] command) {}

    /**
     * The native watch-next request retains this exact Root, or the request the app built from it is bound to it
     * (see {@link #bindShuffleRequest}). Row endpoints never use it.
     */
    private static final class ShuffleCommand extends WeakReference<Command>
            implements OfflineReplayLogic.PlannedStart {
        final int generation;
        final ShufflePlan plan;
        @Nullable ReplayFuture reply;
        @Nullable WeakReference<Object> request;

        ShuffleCommand(Command command, int generation, ShufflePlan plan) {
            super(command);
            this.generation = generation;
            this.plan = plan;
        }

        @Override public int generation() {
            return generation;
        }

        @Override @Nullable public Object dispatched() {
            return get();
        }

        @Override public byte[] commandBytes() {
            return plan.command();
        }

        /** Whether the app built this request, or the one it was copied from, from this plan's dispatch. */
        boolean owns(Object request, Object original) {
            WeakReference<Object> bound = this.request;
            Object held = bound == null ? null : bound.get();
            return held != null && (held == request || held == original);
        }
    }

    private record ShuffleReply(WeakReference<Object> response, int generation, ShuffleSource source) {}

    record LiveShuffle(WeakReference<LocalShuffleQueue> owner, int generation, ShuffleSource source,
                       String playlistId) {}

    private record ShuffleContinuation(LiveShuffle live, List<?> rows, int cursor) {}

    private static final List<ShuffleCommand> shuffleCommands = new ArrayList<>();
    private static final ThreadLocal<Object> redispatchedShuffle = new ThreadLocal<>();
    private static volatile int shuffleGeneration;
    private static volatile ShuffleReply shuffleReply;
    private static volatile LiveShuffle liveShuffle;
    private static volatile WeakReference<Command> localStart;

    private static final String HOOK_GATE = "gate";
    private static final String HOOK_PLAYER = "player";
    private static final String HOOK_NEXT = "next";
    private static final String HOOK_COMMAND = "command";
    private static final String HOOK_SEEK = "seek";
    private static final String HOOK_BROWSE_CAPTURE = "browse-capture";
    private static final String HOOK_BROWSE_REPLAY = "browse-replay";
    private static final ThreadLocal<Object> localBrowseDelivery = new ThreadLocal<>();
    private static final String REASON_SETTING_OFF = "setting off";
    private static final String REASON_NO_ID = "no browse id";
    private static final String REASON_NOT_A_LIST = "not a downloaded list";
    private static final String REASON_OFFLINE = "offline";
    /** The reasons that are the normal case of nearly every page: never said. */
    private static final Set<String> QUIET_CAPTURE_SKIPS =
            Set.of(REASON_SETTING_OFF, REASON_NO_ID, REASON_NOT_A_LIST, REASON_OFFLINE);
    private static final String HOOK_GATE_FLAG = "gate-flag";

    private static final ConcurrentMap<String, Long> skipLoggedAt = new ConcurrentHashMap<>();
    private static final Set<String> aliveHooks = ConcurrentHashMap.newKeySet();
    private static volatile Captured captured;
    private static volatile boolean lastGateFlag;

    private OfflineReplay() {}

    static boolean enabled() {
        return OfflineReplayHooks.usable();
    }

    static String playlistId(String sourceId) {
        return OfflineReplayLogic.playlistId(sourceId);
    }

    @Nullable
    static String sourceOf(@Nullable String playlistId) {
        return OfflineReplayLogic.sourceOf(playlistId);
    }

    /** Whether the song, or the music video of a song, has stored data. In memory, so the main thread may ask. */
    static boolean holds(String videoId) {
        return OfflineCopyStore.has(videoId) || VideoCopyStore.has(videoId);
    }

    static boolean playbackIndexesReady() {
        return OfflineCopyStore.playbackIndexReady() && VideoCopyStore.playbackIndexReady()
                && DownloadedMarkers.loadedRegistry() != null;
    }

    static boolean mayHold(String videoId) {
        if (videoId == null || videoId.isEmpty()) return false;
        if (holds(videoId) || !playbackIndexesReady()) return true;
        DownloadedRegistry markers = DownloadedMarkers.loadedRegistry();
        return markers != null && OfflineCopyStore.has(markers.canonicalId(videoId));
    }

    private static void loadPlaybackIndexes() {
        OfflineCopyStore.loadPlaybackData();
        VideoCopyStore.loadPlaybackData();
    }

    static boolean isLocalStart(Object request) {
        WeakReference<Command> root = localStart;
        return root != null && request instanceof NextRequest next && root.get() == next.rvxCommand();
    }

    /**
     * The stored player response of the song, or of the music video that has a video copy; null when neither is
     * stored. A video copy answers in the shape of the response the app gets online ({@link VideoCopyAnswer}): the
     * app's player selection reads more of a response than the streaming data, and the response saved with the copy
     * (another client's) gave it no video track offline. The base is the app's online response of this video when
     * one was kept ({@link VideoCopyStore#keepLive}), else the app-generic part of the last online response of any
     * copy ({@link LiveTemplate}), else the saved response alone. The thumbnails are the song's, which the Song
     * display can show with no network ({@link VideoCopyAnswer#withSongArtwork}).
     */
    @Nullable
    private static byte[] storedResponseOf(String videoId) throws IOException {
        // An official video row can have both the raw song commit and the adaptive pair under this exact ID.
        VideoCopyStore.Playable pair = VideoCopyStore.playable(videoId);
        if (pair == null) {
            byte[] exact = OfflineCopyStore.response(videoId);
            if (exact != null) return exact;

            String copyId = canonicalCopyId(videoId);
            if (copyId.equals(videoId)) return null;

            byte[] saved = OfflineCopyStore.response(copyId);
            RawAudioFiles.Entry raw = RawAudioStore.find(copyId);
            if (saved == null || raw == null) return null;
            PlayerResponse response = PlayerResponse.parseFrom(saved);
            if (StreamSwapLogic.savedAudio(copyId, copyId, raw, response, response) == null) return null;
            return response.toBuilder().setVideoDetails(response.getVideoDetails().toBuilder()
                    .setVideoId(videoId)).build().toByteArray();
        }

        byte[] saved = pair.response();

        byte[] live = VideoCopyStore.live(videoId);
        VideoCopyAnswer.Answer answer = VideoCopyAnswer.of(saved, live, LiveTemplate::get);
        byte[] artwork = withSongArtwork(videoId, answer.bytes());
        Logger.printDebug(() -> "OfflineReplay: video copy v=" + videoId + " base=" + answer.base() + " saved="
                + saved.length + "B live=" + sizeOf(live) + " songArtwork=" + (artwork != answer.bytes()));
        return artwork;
    }

    /** The answer with the thumbnails of the song the video copy belongs to, or as it is when the song has none. */
    private static byte[] withSongArtwork(String videoId, byte[] answer) {
        String songId = VideoCopyStore.songOf(videoId);
        byte[] song = songId == null ? null : OfflineCopyStore.response(songId);
        return song == null ? answer : VideoCopyAnswer.withSongArtwork(answer, song);
    }

    private static String sizeOf(@Nullable byte[] bytes) {
        return bytes == null ? "none" : bytes.length + "B";
    }

    // region queue and commands

    /**
     * The tracks of a list that can play with no network, in the order the list shows them. Off the main thread.
     * The songs list holds the loose songs, sorted by title.
     */
    static List<OfflineWatchNext.Item> queueOf(String sourceId) {
        if (SONGS.equals(sourceId)) {
            Set<String> loose;
            synchronized (PlaylistSync.MUTATION) {
                loose = CopyOwnership.looseSongs(RawAudioStore.copyIds(), OfflineCopyStore.copyTrackLists());
                PlaylistSync.hideStagedCopies(loose);
            }
            return OfflineReplayLogic.itemsOfSongs(OfflineCopyStore.copiesWithData(loose), loose);
        }
        OfflineSourceStore.Source source = OfflineCopyStore.sourceForOpening(sourceId);
        return source == null ? List.of() : queueOf(source);
    }

    private static List<OfflineWatchNext.Item> queueOf(OfflineSourceStore.Source source) {
        loadPlaybackIndexes();
        Set<String> own = RawAudioStore.copyIds();
        DownloadedRegistry markers = DownloadedMarkers.loadedRegistry();
        Map<String, String> aliases = markers == null ? RawAudioStore.aliases() : Map.of();
        UnaryOperator<String> canonical = markers == null
                ? id -> OfflineCopyStore.copyId(id, own, aliases) : markers::canonicalId;
        return OfflineReplayLogic.itemsOfTracks(
                OfflineSourceStore.tracksOf(source, own, OfflineCopyStore::metadataOf, canonical));
    }

    @Nullable
    static List<byte[]> missingTail(String playlistId, List<String> covered, String currentId, byte[] command) {
        OfflineSourceStore.Source source = OfflineCopyStore.sourceForOpening(playlistId);
        if (source == null) return null;

        List<String> ids = source.videoIds();
        Map<String, String> videos = videoCopies();
        int loadedIndex = covered.size() - 1;
        if (loadedIndex < 0 || loadedIndex >= ids.size() - 1) return null;
        for (int i = 0; i <= loadedIndex; i++) {
            if (!canonicalCopyId(ids.get(i)).equals(canonicalSongOf(covered.get(i), videos))) return null;
        }

        List<byte[]> tail = new ArrayList<>(ids.size() - loadedIndex - 1);
        boolean showVideos = OfflineReplayLogic.showsVideos(currentId, videos, command);
        for (int index = loadedIndex + 1; index < ids.size(); index++) {
            String id = canonicalCopyId(ids.get(index));
            OfflineCopyMetadata metadata = OfflineCopyStore.metadataOf(id);
            if (metadata == null) continue;

            OfflineWatchNext.Item item = OfflineReplayLogic.itemOf(metadata);
            if (showVideos) item = OfflineReplayLogic.showingVideos(List.of(item), videos).get(0);
            if (!verifiedCopy(item.videoId())) continue;

            byte[] endpoint = OfflineWatchCommand.build(item.videoId(), playlistId, index, item.musicVideoType());
            tail.add(OfflineWatchNext.queueRow(item, endpoint, null));
        }
        return tail;
    }

    /**
     * Starts the song actually bound to the tapped No Playlist row. Offline it starts the saved queue; online the
     * native Watch owns radio and lyrics while the subsequent loose songs are inserted ahead of radio.
     *
     * @return false when no command was seen yet to run one with: nothing was started.
     */
    static boolean playLooseSong(String songId) {
        Start start = claimStart();
        if (start == null) return false;
        boolean replayingUnansweredTap = PlaybackConnectivity.replayingUnansweredTap();

        // A tap is a fresh selection that starts in the Song display, offline or online: the video copy plays with its
        // video surface off, and the app's own Song/Video switch flips it in place.
        logDecision(HOOK_COMMAND, "audio mode: " + AudioFirst.startAudioMode());

        Utils.runOnBackgroundThread(() -> {
            if (!currentStart(start)) return;

            try {
                List<OfflineWatchNext.Item> items = queueOf(SONGS);
                int index = OfflineReplayLogic.currentIndex(items, songId, -1);
                if (index < 0) {
                    dispatchStart(start, null);
                    return;
                }
                boolean online = !replayingUnansweredTap && PlaybackConnectivity.reachable();
                String paired = online ? OfflineReplayLogic.videoOf(songId, videoCopies()) : null;
                byte[] command = online ? onlineCommand(SONGS, songId, paired == null ? songId : paired)
                        : startCommand(SONGS, items, index);
                Runnable following = online ? () -> {
                    watchLooseSong(start, songId, paired);
                    Utils.runOnBackgroundThread(() -> {
                        try {
                            queueLooseSongs(start, items, index, songId, paired);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } : null;
                dispatchStart(start, command, following);
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not start downloaded song " + songId, ex);
                dispatchStart(start, null);
            }
        });
        return true;
    }

    static void cancelQueuedSongs() {
        advanceShuffleSelection();
    }

    private static void queueLooseSongs(Start start, List<OfflineWatchNext.Item> items, int index, String songId,
                                        @Nullable String paired) throws InterruptedException {
        List<String> following = OnlineQueueCommand.commandOrder(OnlineQueueCommand.following(
                items.stream().map(OfflineWatchNext.Item::videoId).toList(), index));
        if (following.isEmpty()) return;

        long deadline = android.os.SystemClock.elapsedRealtime() + QUEUE_WAIT_MS;
        while (!looseSongPlaying(songId, paired)) {
            if (!currentStart(start) || android.os.SystemClock.elapsedRealtime() >= deadline) return;
            Thread.sleep(QUEUE_POLL_MS);
        }
        Thread.sleep(QUEUE_SETTLE_MS);
        for (String next : following) {
            if (!currentStart(start) || !looseSongPlaying(songId, paired)) return;
            byte[] command = OnlineQueueCommand.playNext(next);
            Utils.runOnMainThread(() -> {
                if (!currentStart(start) || !looseSongPlaying(songId, paired)) return;
                try {
                    start.dispatcher().rvxDispatch(start.template().rvxWithBytes(command), start.context());
                } catch (Exception ex) {
                    Logger.printException(() -> "OfflineReplay: could not queue loose song " + next, ex);
                }
            });
            Thread.sleep(QUEUE_STEP_MS);
        }
    }

    private static boolean looseSongPlaying(String songId, @Nullable String paired) {
        String playing = VideoInformation.getVideoId();
        return songId.equals(playing) || (paired != null && paired.equals(playing));
    }

    private static boolean playSavedSource(String sourceId) {
        Start start = claimStart();
        if (start == null) return false;
        Utils.runOnBackgroundThread(() -> {
            if (!currentStart(start)) return;
            try {
                dispatchStart(start, startCommand(sourceId, 0));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not start " + sourceId, ex);
                dispatchStart(start, null);
            }
        });
        return true;
    }

    /**
     * Starts a song of a list that has no stored data, as a normal online song of that list. On the main thread.
     *
     * @return false when no command was seen yet to run one with: nothing was started.
     */
    static boolean playOnline(String sourceId, String videoId) {
        Start start = claimStart();
        if (start == null) return false;

        Utils.runOnBackgroundThread(() -> {
            try {
                if (currentStart(start)) dispatchStart(start, onlineCommand(sourceId, videoId, videoId));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not start online " + videoId + " of " + sourceId, ex);
            }
        });
        return true;
    }

    /**
     * The offline command a tap in a list starts: a song that has a video copy starts as its music video, which the
     * copy answers in either display of the app's own Song/Video switch; the rest start as the song.
     * Off the main thread.
     */
    @Nullable
    private static byte[] startCommand(String sourceId, int index) {
        return startCommand(sourceId, queueOf(sourceId), index);
    }

    @Nullable
    private static byte[] startCommand(String sourceId, List<OfflineWatchNext.Item> items, int index) {
        return startCommand(sourceId, items, index, false);
    }

    @Nullable
    private static byte[] startCommand(String sourceId, List<OfflineWatchNext.Item> items, int index,
                                       boolean shuffledStart) {
        if (index < 0 || index >= items.size()) return null;

        return startCommand(sourceId, items.get(index), index, shuffledStart, videoCopies());
    }

    static byte[] startCommand(String sourceId, OfflineWatchNext.Item item, int index, boolean shuffledStart,
                               Map<String, String> videos) {
        String songId = item.videoId();
        String videoId = OfflineReplayLogic.videoOf(songId, videos);
        return OfflineWatchCommand.build(videoId == null ? songId : videoId, playlistId(sourceId), index,
                OfflineReplayLogic.startTypeOf(songId, videos), shuffledStart);
    }

    /**
     * The ordinary online watch command of a song tapped in a list: the playlist's id and the song's place in it
     * for a playlist or album, the song alone for a song of no list, where the app builds the song's own radio.
     */
    private static byte[] onlineCommand(String sourceId, String songId, String videoId) {
        return OnlineWatchCommand.build(videoId, OfflineReplayLogic.onlinePlaylistId(sourceId, SONGS),
                indexInSource(sourceId, songId));
    }

    /** The place of the song in the whole downloaded list: the online playlist counts the tracks that are not here. */
    private static int indexInSource(String sourceId, String videoId) {
        for (OfflineSourceStore.Source source : OfflineCopyStore.sources()) {
            if (source.id().equals(sourceId)) return Math.max(0, source.videoIds().indexOf(videoId));
        }
        return 0;
    }

    /** Opens a whole, unfiltered stock MenuRenderer through the app's regular menu endpoint. */
    public static boolean openSongMenu(byte[] menu, View anchor) {
        byte[] container = new ProtoWriter().message(66_439_850, menu).toByteArray();
        byte[] endpoint = new ProtoWriter().message(1, container).toByteArray();
        return dispatchCommand(new ProtoWriter().message(98_150_882, endpoint).toByteArray(), anchor);
    }

    /** Positionless get_queue input. Menu consumers must isolate its response from queue updates and commands. */
    public static byte[] songMenuRequest(String songId) {
        byte[] target = new ProtoWriter().string(1, songId).toByteArray();
        byte[] request = new ProtoWriter().message(1, target).toByteArray();
        return new ProtoWriter().message(163_162_354, request).toByteArray();
    }

    static boolean canDispatch() {
        Captured seen = captured;
        return seen != null && seen.dispatcher().get() != null;
    }

    @Nullable
    private static Start claimStart() {
        Captured seen = captured;
        Dispatcher dispatcher = seen == null ? null : seen.dispatcher().get();
        if (dispatcher == null) return null;

        PlayerFallback.playlistStarted();
        return new Start(dispatcher, seen.template(), contextOf(seen, null), advanceShuffleSelection());
    }

    private static boolean currentStart(Start start) {
        return start.generation() == shuffleGeneration;
    }

    private static void dispatchStart(Start start, @Nullable byte[] bytes) {
        dispatchStart(start, bytes, null);
    }

    private static void dispatchStart(Start start, @Nullable byte[] bytes, @Nullable Runnable onStarted) {
        Utils.runOnMainThreadNowOrLater(() -> {
            if (!currentStart(start)) return;
            if (bytes != null && start.template().rvxWithBytes(bytes) instanceof Command command) {
                dispatchShuffleCommand(start.dispatcher(), command, start.context());
                if (onStarted != null && currentStart(start)) onStarted.run();
            } else {
                PlayerFallback.unavailable();
            }
        });
    }

    /**
     * Runs the command through the dispatcher the app's own taps use, on the main thread.
     *
     * @return false when no command was seen yet to run one with: nothing was dispatched.
     */
    static boolean dispatchCommand(byte[] commandBytes) {
        return dispatchCommand(commandBytes, null);
    }

    private static boolean dispatchCommand(byte[] commandBytes, @Nullable View sender) {
        Captured seen = captured;
        Dispatcher dispatcher = seen == null ? null : seen.dispatcher().get();
        if (dispatcher == null) return false;

        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                dispatcher.rvxDispatch(seen.template().rvxWithBytes(commandBytes), contextOf(seen, sender));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not dispatch the command", ex);
            }
        });
        return true;
    }

    /** A copy of the context the last command had, or an empty one when it is gone or cannot be copied. */
    private static Map<?, ?> contextOf(Captured seen, @Nullable View sender) {
        Map<?, ?> context = seen.context().get();
        if (context == null && sender == null) return Collections.emptyMap();

        try {
            Map<Object, Object> copied = context == null ? new HashMap<>() : new HashMap<>(context);
            if (sender != null) {
                copied.put("com.google.android.libraries.youtube.rendering.elements.sender_view", sender);
            }
            return copied;
        } catch (Exception ex) {
            Logger.printDebug(() -> "OfflineReplay: the command's context cannot be copied, using none", ex);
            return Collections.emptyMap();
        }
    }

    /**
     * Injection point: every command the app dispatches, on the main thread. Remembers how to dispatch a command
     * while the setting is on, and decides the tap of a row of the Downloads page now, with the network of this
     * moment: the rows carry the app's own commands (a page's browse, a song's watch), which the app runs as they
     * are while online. Offline, one that cannot work is taken and the downloaded copy plays instead.
     *
     * @return true when the command is taken: the app must not run it.
     */
    static boolean onCommand(Object dispatcher, Object command, Map<?, ?> context) {
        try {
            noteAlive(HOOK_COMMAND);
            if (!(dispatcher instanceof Dispatcher stockDispatcher) || !(command instanceof Command message)) {
                return false;
            }

            if (!enabled() || redispatchedShuffle.get() == command) return false;

            Captured origin = new Captured(new WeakReference<>(stockDispatcher), message,
                    new WeakReference<>(context));
            captured = origin;
            OfflineCopyStore.warmUp();
            OfflineBackfill.kick();

            byte[] bytes = message.toByteArray();
            String browseId = OfflineReplayLogic.browseIdOf(bytes);
            if (browseId != null) {
                logDecision(HOOK_COMMAND, "browse=" + browseId);
                PlaylistSync.onBrowse(browseId);
            }

            if (browseId != null || OfflineReplayLogic.startsPlaylist(bytes)
                    || OfflineReplayLogic.onlineWatchOf(bytes) != null) {
                AlbumOpening.noteNavigation();
            }

            return takeOffline(bytes, browseId, origin);
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: could not read the command", ex);
            return false;
        }
    }

    private static boolean takeOffline(byte[] command, @Nullable String browseId, Captured origin) {
        OfflineReplayLogic.WatchTarget watch = browseId == null ? OfflineReplayLogic.onlineWatchOf(command) : null;
        OfflineReplayLogic.WatchTarget playlist = browseId == null
                ? OfflineReplayLogic.playlistWatchOf(command) : null;
        boolean synthetic = browseId == null && noteNativePlaylistStart(command);
        if (browseId == null && watch == null && playlist == null && !synthetic) return false;
        if (synthetic) {
            localStart = new WeakReference<>(origin.template());
            return false;
        }

        boolean held = browseId == null ? holdsStart(command, watch, playlist) : holdsBrowse(browseId);
        boolean online = PlaybackConnectivity.reachableForTarget(held);
        if (browseId != null) UpstreamProbe.probeUnlessFresh("browse");
        if (browseId == null) {
            PlayerFallback.playlistStarted();
            int generation = advanceShuffleSelection();
            String orderedSource = savedOrderedPlaySource(online, held, watch, playlist, command);
            if (orderedSource != null && takeOrderedPlay(origin, generation, orderedSource)) {
                logStartCommand(command, origin, null, playlist, "saved-ordered-play");
                return true;
            }
            if (online) {
                String id = playlist == null ? watch.playlistId() : playlist.playlistId();
                PlayerFallback.startingOnlinePlaylist(id);
                if (held) watchOnlineStart(command, origin, generation);
                return false;
            }

            if (takeShufflePlay(command, origin, generation)) {
                logStartCommand(command, origin, watch, playlist, "saved-shuffle");
                return true;
            }
            logStartCommand(command, origin, watch, playlist, "no-saved-shuffle");
            if (watch == null || !mayHold(watch.videoId())) return leftToStock(watch, playlist);
            Dispatcher dispatcher = origin.dispatcher().get();
            if (dispatcher == null) return leftToStock(watch, playlist);
            playTrack(new Start(dispatcher, origin.template(), contextOf(origin, null), generation), watch);
            return true;
        }

        if (AlbumOpening.take(browseId, command, online)) return true;

        OfflineReplayLogic.Decision decision = OfflineReplayLogic.routeOf(online, browseId, null,
                SONGS, OfflineReplayHooks.localDetailSupported(), OfflineCopyStore::hasSource, OfflineBrowsePages::has,
                OfflineCopyStore::has, target -> null, videoId -> null);
        switch (decision.route()) {
            case OPEN_SONGS -> {
                logDecision(HOOK_COMMAND, "browse of the songs list opens it");
                NoPlaylistDialog.show();
                return true;
            }
            case PLAY_SOURCE -> {
                logDecision(HOOK_COMMAND, "offline browse or watch of " + decision.sourceId() + " plays the list");
                return playSavedSource(decision.sourceId());
            }
            default -> {
                return false;
            }
        }
    }

    @Nullable
    static String savedOrderedPlaySource(boolean online, boolean held, @Nullable OfflineReplayLogic.WatchTarget watch,
                                        @Nullable OfflineReplayLogic.WatchTarget playlist, byte[] command) {
        return !online && held && watch == null && playlist != null
                && OfflineReplayLogic.shufflePlaylistOf(command) == null ? playlist.playlistId() : null;
    }

    /** Whether a start has a downloaded copy to play: what the offline branches of {@link #takeOffline} take. */
    private static boolean holdsStart(byte[] command, @Nullable OfflineReplayLogic.WatchTarget watch,
                                      @Nullable OfflineReplayLogic.WatchTarget playlist) {
        if (canShuffleOffline()
                && OfflineReplayLogic.savedShufflePlaylistOf(command, OfflineCopyStore::mayHaveSource) != null) {
            return true;
        }
        if (watch != null && mayHold(watch.videoId())) return true;

        return playlist != null && OfflineCopyStore.mayHaveSource(playlist.playlistId());
    }

    /** Whether a page or a list opened by a browse command is a downloaded one. */
    private static boolean holdsBrowse(String browseId) {
        String sourceId = OfflineReplayLogic.sourceOfBrowseId(browseId);
        return OfflineBrowsePages.has(browseId) || OfflineCopyStore.hasSource(sourceId);
    }

    /**
     * A start the offline branches do not take goes on as the app's own. Android calling the network online, it
     * is told to the fallback as an online start, exactly as when the verdict is not dead.
     */
    private static boolean leftToStock(@Nullable OfflineReplayLogic.WatchTarget watch,
                                       @Nullable OfflineReplayLogic.WatchTarget playlist) {
        if (PlaybackConnectivity.osReachable()) {
            PlayerFallback.startingOnlinePlaylist(playlist == null ? watch.playlistId() : playlist.playlistId());
        }
        return false;
    }

    /**
     * A start of a downloaded item went the online way because nothing said the upstream is dead yet. If that
     * turns out in the next seconds and the selection is still this one and nothing was answered, the start is
     * taken again, now from the copies: the app's own online start is superseded as by a second tap. Its
     * navigation, moved on by {@link PlayerFallback#playlistStarted}, tells its requests from earlier ones.
     */
    private static void watchOnlineStart(byte[] command, Captured origin, int generation) {
        BooleanSupplier current = () -> generation == shuffleGeneration && origin.dispatcher().get() != null;
        Runnable again = () -> {
            if (!takeOffline(command, null, origin) && PlaybackConnectivity.replayingUnansweredTap()) {
                dispatchCommand(command);
            }
        };
        PlaybackConnectivity.watchStart("tap", List.of(), current, again);
    }

    /** {@link #watchOnlineStart} for a loose song, started without a new navigation: its songs tell it apart. */
    private static void watchLooseSong(Start start, String songId, @Nullable String paired) {
        List<String> videoIds = paired == null ? List.of(songId) : List.of(songId, paired);
        PlaybackConnectivity.watchStart("loose-song", videoIds, () -> currentStart(start), () -> {
            if (PlaybackConnectivity.replayingUnansweredTap()) playLooseSong(songId);
        });
    }

    private static void logStartCommand(byte[] bytes, Captured origin, @Nullable OfflineReplayLogic.WatchTarget watch,
                                        @Nullable OfflineReplayLogic.WatchTarget playlist, String branch) {
        Logger.printDebug(() -> {
            OfflineReplayLogic.WatchTarget target = playlist == null ? watch : playlist;
            TapCommand parsed = TapCommand.parse(bytes);
            return "OfflineReplay: command branch=" + branch + " type=" + (playlist == null ? "watch" : "playlist")
                    + " root=" + System.identityHashCode(origin.template())
                    + " v=" + (target == null ? "" : target.videoId())
                    + " list=" + (target == null ? "" : target.playlistId())
                    + " index=" + (target == null ? -1 : target.index())
                    + " watchParams=" + parsed.hasParams + " shuffle=" + OfflineReplayLogic.shufflePlaylistOf(bytes)
                    + " " + parsed.fields();
        });
    }

    /** Raw dispatched offline Watch, not the indexed commands the native Up Next navigator consumes. */
    static boolean noteNativePlaylistStart(byte[] command) {
        if (!OfflineReplayLogic.hasField(command, OfflineWatchCommand.ENDPOINT_FIELD)) return false;
        PlayerFallback.playlistStarted();
        return true;
    }

    private static int advanceShuffleSelection() {
        synchronized (shuffleCommands) {
            shuffleCommands.removeIf(command -> command.get() == null);
            localStart = null;
            shuffleReply = null;
            liveShuffle = null;
            return ++shuffleGeneration;
        }
    }

    private static boolean canShuffleOffline() {
        return enabled() && OfflineReplayHooks.localPlaylistShuffleSupported()
                && OfflineReplayHooks.localDetailSupported() && LocalDownloadManager.downloadMode().streamCopy();
    }

    private static boolean currentShuffle(int generation) {
        return generation == shuffleGeneration && canShuffleOffline();
    }

    private static boolean takeShufflePlay(byte[] command, Captured origin, int generation) {
        if (!canShuffleOffline()) return false;

        String sourceId = OfflineReplayLogic.savedShufflePlaylistOf(command, OfflineCopyStore::mayHaveSource);
        if (sourceId == null) return false;

        Dispatcher dispatcher = origin.dispatcher().get();
        if (dispatcher == null) return false;
        Start start = new Start(dispatcher, origin.template(), contextOf(origin, null), generation);

        Utils.runOnBackgroundThread(() -> {
            try {
                if (!currentShuffle(generation)) return;
                ShufflePlan plan = prepareShuffle(sourceId);
                Utils.runOnMainThread(() -> finishShufflePlay(start, plan));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not prepare Shuffle Play of " + sourceId, ex);
                Utils.runOnMainThread(() -> finishShufflePlay(start, null));
            }
        });
        return true;
    }

    @Nullable
    private static ShufflePlan prepareShuffle(String sourceId) {
        OfflineSourceStore.Source source = OfflineCopyStore.sourceForOpening(sourceId);
        if (source == null || !OfflineCopyStore.ownsStreamSource(source)
                || DownloadedMarkers.pendingPlaylistIds().contains(sourceId)) return null;

        OfflineCopyStore.loadPlaybackData();

        // Read this exact saved membership. Old stream sources may still be represented only in the track markers.
        boolean legacy = OfflineCopyStore.source(sourceId) == null;
        Set<String> copyIds = RawAudioStore.copyIds();
        DownloadedRegistry markers = DownloadedMarkers.loadedRegistry();
        Map<String, String> aliases = markers == null ? RawAudioStore.aliases() : Map.of();
        UnaryOperator<String> canonical = markers == null
                ? id -> OfflineCopyStore.copyId(id, copyIds, aliases) : markers::canonicalId;
        // The press starts on a random playable song, as Music's online Shuffle does. The plan, its start command and
        // the queue reply built from it all read this one reordered list; the native shuffle shuffles the rest.
        List<OfflineWatchNext.Item> items = OfflineReplayLogic.withRandomStart(OfflineReplayLogic.itemsOfTracks(
                OfflineSourceStore.tracksOf(source, copyIds, OfflineCopyStore::metadataOf, canonical)),
                ThreadLocalRandom.current());
        byte[] bytes = startCommand(sourceId, items, 0, true);
        return bytes == null ? null : new ShufflePlan(new ShuffleSource(source, legacy), items, bytes);
    }

    private static boolean shuffleSourceStillCurrent(ShuffleSource origin) {
        OfflineSourceStore.Source source = origin.saved();
        String id = source.id();
        if (DownloadedMarkers.isPlaylistPending(id) || !OfflineCopyStore.ownsStreamSource(source)) return false;

        OfflineSourceStore.Source indexed = OfflineCopyStore.source(id);
        if (!origin.legacy()) return source.equals(indexed);
        if (indexed != null) return source.equals(indexed);

        DownloadedRegistry markers = DownloadedMarkers.loadedRegistry();
        return markers == null ? DownloadedMarkers.completedStreamSource(source)
                : source.videoIds().equals(markers.sourceTracks(id));
    }

    private static void finishShufflePlay(Start start, @Nullable ShufflePlan plan) {
        int generation = start.generation();
        if (!currentShuffle(generation)) return;

        try {
            ShufflePlan marked = plan == null ? null : withDispatchToken(plan, generation);
            if (marked == null || !shuffleSourceStillCurrent(marked.source())
                    || !(start.template().rvxWithBytes(marked.command()) instanceof Command command)) {
                Logger.printDebug(() -> "OfflineReplay: no local shuffle plan for generation " + generation
                        + " (plan " + (plan == null ? "missing" : "stale or unreadable")
                        + "), the stock Shuffle Play runs");
                dispatchStockCommand(start);
                return;
            }

            synchronized (shuffleCommands) {
                if (generation != shuffleGeneration) return;
                shuffleCommands.removeIf(commandRef -> commandRef.get() == null);
                shuffleCommands.add(new ShuffleCommand(command, generation, marked));
            }
            Logger.printDebug(() -> "OfflineReplay: shuffle plan " + generation + " registered for command "
                    + System.identityHashCode(command) + " of " + plan.items().size() + " songs");
            logDecision(HOOK_COMMAND, "audio mode: " + AudioFirst.startAudioMode());
            if (currentShuffle(generation)) dispatchShuffleCommand(start.dispatcher(), command, start.context());
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: could not dispatch Shuffle Play", ex);
        }
    }

    /**
     * The plan dispatched as the shuffle of this selection: its command carries the number of the selection, which
     * only a request made from this very dispatch can have. Two presses of the same Shuffle Play are the same bytes
     * otherwise.
     */
    private static ShufflePlan withDispatchToken(ShufflePlan plan, int generation) {
        return new ShufflePlan(plan.source(), plan.items(),
                OfflineReplayLogic.withDispatchToken(plan.command(), generation));
    }

    /** Redispatches a command built from a verified start, without claiming it again. */
    static void dispatchShuffleCommand(Dispatcher dispatcher, Command command, Map<?, ?> context) {
        if (OfflineReplayLogic.offlineWatchOf(command.toByteArray()) != null) {
            localStart = new WeakReference<>(command);
        }
        dispatchReplayCommand(dispatcher, command, context);
    }

    private static void dispatchStockCommand(Start start) {
        dispatchReplayCommand(start.dispatcher(), start.template(), start.context());
    }

    private static void dispatchReplayCommand(Dispatcher dispatcher, Command command, Map<?, ?> context) {
        // Both kinds of redispatch supersede the previous playback selection without re-entering takeOffline.
        PlayerFallback.playlistStarted();
        redispatchedShuffle.set(command);
        try {
            dispatcher.rvxDispatch(command, context);
        } finally {
            redispatchedShuffle.remove();
        }
    }

    /**
     * Injection point: the watch request the app has just built from the command its watch controller was handed, on
     * the thread that dispatches it. When that command is the very one a Shuffle Play dispatch is running, the request
     * is bound to that dispatch's plan here, where both are still the objects the app has in hand. The binding stays
     * with the request whatever command it holds later, and with every copy of it the player follows.
     */
    static void bindShuffleRequest(Object request) {
        Object dispatched = redispatchedShuffle.get();
        if (dispatched == null || !(request instanceof NextRequest next) || next.rvxCommand() != dispatched) return;

        ShuffleCommand plan;
        synchronized (shuffleCommands) {
            plan = OfflineReplayLogic.dispatchedPlanOf(dispatched, shuffleCommands);
            if (plan == null || plan.request != null) return;
            plan.request = new WeakReference<>(request);
        }
        Logger.printDebug(() -> "OfflineReplay: shuffle plan " + plan.generation + " bound to request "
                + System.identityHashCode(request) + " of command " + System.identityHashCode(dispatched));
    }

    /**
     * The plan a watch-next request belongs to, by identity only: the request (or the one it was copied from) bound
     * to a Shuffle Play dispatch, the dispatched command itself, or a command that carries that dispatch's token.
     * Nothing else ties a request to its press: two presses of the same Shuffle Play look alike otherwise.
     */
    @Nullable
    private static ShuffleCommand shuffleCommandOf(Object request, Object original, @Nullable Object sourceCommand,
                                                   @Nullable Object command) {
        if (!OfflineReplayHooks.localPlaylistShuffleSupported()) return null;

        List<ShuffleCommand> live;
        synchronized (shuffleCommands) {
            ShuffleCommand owned = boundPlanOf(request, original);
            if (owned == null) owned = OfflineReplayLogic.dispatchedPlanOf(sourceCommand, shuffleCommands);
            if (owned == null) owned = OfflineReplayLogic.dispatchedPlanOf(command, shuffleCommands);
            if (owned != null) return owned;
            live = new ArrayList<>(shuffleCommands);
        }

        ShuffleCommand planned = tokenedShuffleOf(sourceCommand, live);
        return planned != null ? planned : tokenedShuffleOf(command, live);
    }

    /** Holds the lock of {@link #shuffleCommands}. The latest plan wins, as one request is bound to one plan. */
    @Nullable
    private static ShuffleCommand boundPlanOf(Object request, Object original) {
        for (int index = shuffleCommands.size() - 1; index >= 0; index--) {
            ShuffleCommand candidate = shuffleCommands.get(index);
            if (candidate.owns(request, original)) return candidate;
        }
        return null;
    }

    /**
     * The current plan whose dispatch token this command carries, when the app passed the request another object than
     * the one dispatched (a command rebuilt with a start time keeps the endpoint and the token of its dispatch).
     */
    @Nullable
    private static ShuffleCommand tokenedShuffleOf(@Nullable Object command, List<ShuffleCommand> live) {
        if (!(command instanceof Command rebuilt) || live.isEmpty()) return null;

        int generation = shuffleGeneration;
        ShuffleCommand planned = OfflineReplayLogic.plannedStartOf(rebuilt.toByteArray(), generation, live);
        if (planned != null) {
            Logger.printDebug(() -> "OfflineReplay: shuffle plan " + generation + " matched by token, the command "
                    + System.identityHashCode(command) + " is not the dispatched one");
        }
        return planned;
    }

    /**
     * Says why a shuffled start took no plan: the shape and token of its command, every plan's generation, token,
     * target and bound request, and whether it fails closed (a superseded press) or goes the ordinary way.
     */
    private static void logShuffleMiss(Object request, Object original, Object command, boolean superseded) {
        if (!(command instanceof Command root)) return;

        List<ShuffleCommand> live;
        synchronized (shuffleCommands) {
            live = new ArrayList<>(shuffleCommands);
        }
        byte[] bytes = root.toByteArray();
        StringBuilder plans = new StringBuilder();
        for (ShuffleCommand candidate : live) {
            byte[] planned = candidate.commandBytes();
            WeakReference<Object> bound = candidate.request;
            Object held = bound == null ? null : bound.get();
            plans.append(" [gen=").append(candidate.generation)
                    .append(" token=").append(OfflineReplayLogic.dispatchTokenOf(planned))
                    .append(" target=").append(OfflineReplayLogic.offlineWatchOf(planned))
                    .append(" dispatched=").append(System.identityHashCode(candidate.get()))
                    .append(" request=").append(held == null ? "none" : System.identityHashCode(held)).append(']');
        }
        Logger.printDebug(() -> "OfflineReplay: shuffle plan miss, current=" + shuffleGeneration
                + " request=" + System.identityHashCode(request) + " original=" + System.identityHashCode(original)
                + " command=" + System.identityHashCode(command)
                + " token=" + OfflineReplayLogic.dispatchTokenOf(bytes)
                + " target=" + OfflineReplayLogic.offlineWatchOf(bytes) + " bytes=" + bytes.length
                + " shape=" + OfflineReplayLogic.shapeOf(bytes) + " plans:" + plans
                + (superseded ? ", failing closed" : ", not adopted, the ordinary path answers"));
    }

    static boolean ownsLocalPlaylistShuffleReply(Object response) {
        return currentShuffleReply(response) != null;
    }

    @Nullable
    private static ShuffleReply currentShuffleReply(Object response) {
        ShuffleReply reply = shuffleReply;
        return reply != null && response != null && reply.response().get() == response
                && currentShuffle(reply.generation()) && shuffleSourceStillCurrent(reply.source())
                ? reply : null;
    }

    /** Binds the installed native shuffle to the very reply that produced it, not to the seed video. */
    static void localPlaylistShuffleApplied(Object queue, Object exactMarkedReply) {
        if (!(queue instanceof LocalShuffleQueue owner)) return;

        ShuffleReply reply = currentShuffleReply(exactMarkedReply);
        if (reply == null) return;

        synchronized (shuffleCommands) {
            if (shuffleReply != reply || reply.generation() != shuffleGeneration) return;
            liveShuffle = new LiveShuffle(new WeakReference<>(owner), reply.generation(), reply.source(),
                    playlistId(reply.source().saved().id()));
        }
    }

    @Nullable
    private static LiveShuffle liveShuffleFor(@Nullable OfflineReplayLogic.WatchTarget offline, byte[] command) {
        LiveShuffle live = liveShuffle;
        return live != null && offline != null && live.playlistId().equals(offline.playlistId())
                && !OfflineReplayLogic.isShuffleStart(command, offline) ? live : null;
    }

    @Nullable
    static Object localPlaylistContinuation(LiveShuffle live, List<?> rows, int cursor) {
        return live.owner().get() != null && currentLiveShuffle(live)
                ? new ShuffleContinuation(live, rows, cursor) : null;
    }

    private static boolean currentLiveShuffle(LiveShuffle live) {
        return live == liveShuffle && currentShuffle(live.generation()) && shuffleSourceStillCurrent(live.source());
    }

    static boolean retainLocalPlaylistShuffle(Object queue, @Nullable Object continuation) {
        if (!(continuation instanceof ShuffleContinuation captured)
                || !(queue instanceof LocalShuffleQueue owner)) return false;

        LiveShuffle live = captured.live();
        if (live.owner().get() != owner || !currentLiveShuffle(live)) return false;

        // Native apply holds the primary queue monitor across its stock selection guard and this match.
        return live == liveShuffle && owner.rvxMatchesLocalShuffle(captured.rows(), captured.cursor());
    }

    /** Give a saved header Play a real seed before stock can start the empty-id player lane. */
    private static boolean takeOrderedPlay(Captured origin, int generation, String sourceId) {
        Dispatcher dispatcher = origin.dispatcher().get();
        if (dispatcher == null) return false;

        Start start = new Start(dispatcher, origin.template(), contextOf(origin, null), generation);
        Utils.runOnBackgroundThread(() -> {
            if (!currentStart(start)) return;

            byte[] command = null;
            try {
                OfflineSourceStore.Source source = OfflineCopyStore.sourceForOpening(sourceId);
                if (source != null && OfflineCopyStore.ownsStreamSource(source)) {
                    command = startCommand(sourceId, queueOf(source), 0);
                }
            } catch (Exception failure) {
                Logger.printException(() -> "OfflineReplay: could not prepare ordered Play of " + sourceId, failure);
            }
            byte[] seeded = command;
            Utils.runOnMainThread(() -> {
                if (!currentStart(start)) return;

                // An unloaded source index can only suggest a copy. On a miss leave the genuine command to stock.
                if (seeded == null) dispatchStockCommand(start);
                else dispatchStart(start, seeded);
            });
        });
        return true;
    }

    /** Starts a song with stored data as a track of the list's offline queue, with no network. Main thread. */
    private static void playTrack(Start start, OfflineReplayLogic.WatchTarget watch) {
        logDecision(HOOK_COMMAND, "audio mode: " + AudioFirst.startAudioMode());
        Utils.runOnBackgroundThread(() -> {
            if (!currentStart(start)) return;
            try {
                loadPlaybackIndexes();
                Map<String, String> videos = videoCopies();
                String paired = canonicalSongOf(watch.videoId(), videos);
                String sourceId = savedSourceOf(watch.playlistId(), paired);
                OfflineSourceStore.Source source =
                        sourceId == null ? null : OfflineCopyStore.sourceForOpening(sourceId);
                List<OfflineWatchNext.Item> items = source == null ? List.of() : queueOf(source);
                String songId = OfflineReplayLogic.queueIdOf(items, watch.videoId(), paired, watch.index());
                int index = source == null ? -1 : PlaylistSyncPlan.readyIndex(source.videoIds(), items,
                        songId, watch.index(), OfflineReplay::canonicalCopyId);
                if (index < 0) {
                    OfflineCopyMetadata metadata = OfflineCopyStore.metadataOf(songId);
                    String videoId = OfflineReplayLogic.videoOf(songId, videos);
                    dispatchStart(start, metadata == null ? null : OfflineWatchCommand.build(
                            videoId == null ? songId : videoId, playlistId(SONGS), 0,
                            OfflineReplayLogic.startTypeOf(songId, videos)));
                    return;
                }

                dispatchStart(start, startCommand(sourceId, items, index));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not start saved track " + watch.videoId(), ex);
                dispatchStart(start, null);
            }
        });
    }

    /**
     * Starts a worker snapshot and, if connected, the normal native request. The native success owns the live
     * page; the snapshot can render first but can never overwrite a completed or replaced native request.
     */
    static boolean openInitialBrowse(Object fragment, Object request) {
        if (!(fragment instanceof DetailPage page)) return false;

        String pageId = page.rvxInitialBrowseId(request);
        if (pageId == null || !OfflineBrowsePages.mayOpen(pageId)) return false;

        DetailLoad load = new DetailLoad(request, pageId);
        localDetails.put(page, load);
        load.live = PlaybackConnectivity.reachableForTarget(true) && !AlbumOpening.opensSaved(pageId);
        if (load.live) page.rvxIssueDetail(request);
        readDetail(page, load);
        return true;
    }

    private static void readDetail(DetailPage page, DetailLoad load) {
        WeakReference<DetailPage> owner = new WeakReference<>(page);
        int attempt = ++load.readAttempt;
        Utils.runOnBackgroundThread(() -> {
            Object model = readInitialBrowse(owner, load.pageId);
            Utils.runOnMainThread(() -> finishInitialBrowse(owner, load, attempt, model));
        });
    }

    /** Only an offline refresh of the exact already displayed saved detail replaces the native issuer. */
    static boolean refreshSavedDetail(Object fragment, Object request) {
        if (!(fragment instanceof DetailPage page) || PlaybackConnectivity.reachable()) return false;
        DetailLoad load = localDetails.get(page);
        if (load == null || !load.local || load.request.get() != request || !page.rvxOwnsDetail(request)) return false;
        if (!load.loading) {
            load.loading = true;
            readDetail(page, load);
        }
        return true;
    }

    static void refreshSavedSource(String sourceId) {
        Utils.runOnMainThread(() -> localDetails.forEach((page, load) -> {
            Object request = load.request.get();
            if (load.local && request != null && page.rvxOwnsDetail(request)
                    && sourceId.equals(OfflineBrowsePages.sourceOf(load.pageId))) {
                load.loading = true;
                readDetail(page, load);
            }
        }));
    }

    static boolean keepSavedDetail(Object fragment, Object request, Throwable failure) {
        if (!(fragment instanceof DetailPage page) || !page.rvxOwnsDetail(request)) return false;
        DetailLoad load = localDetails.get(page);
        if (load == null || load.request.get() != request) return false;
        load.failure = failure;
        return load.local || load.loading;
    }

    static void detailSucceeded(Object fragment, Object request, Object model) {
        if (localBrowseDelivery.get() == model || !(model instanceof OfflineBrowsePages.Bytes)
                || !(fragment instanceof DetailPage page) || !page.rvxOwnsDetail(request)) return;
        DetailLoad load = localDetails.get(page);
        if (load != null && load.request.get() == request) localDetails.remove(page);
    }

    @Nullable
    private static Object readInitialBrowse(WeakReference<DetailPage> owner, String pageId) {
        try {
            byte[] response = OfflineBrowsePages.readForOpening(pageId);
            DetailPage page = owner.get();
            return response == null || page == null ? null
                    : page.rvxSavedPageModel(OfflinePlaylistArtwork.localize(pageId, response));
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineReplay: could not read the saved detail page", failure);
            return null;
        }
    }

    private static void finishInitialBrowse(WeakReference<DetailPage> owner, DetailLoad load, int attempt,
                                            @Nullable Object model) {
        DetailPage page = owner.get();
        Object request = load.request.get();
        if (page == null || request == null || localDetails.get(page) != load || attempt != load.readAttempt
                || !page.rvxOwnsDetail(request)) return;
        load.loading = false;

        if (model == null) {
            if (load.local) return;
            localDetails.remove(page);
            if (load.failure != null) page.rvxFailDetail(request, load.failure);
            else if (!load.live) page.rvxIssueDetail(request);
            return;
        }

        localBrowseDelivery.set(model);
        try {
            load.local = page.rvxFinishInitialBrowse(request, model);
            if (load.local) {
                logDecision(HOOK_BROWSE_REPLAY, "opened saved detail " + load.pageId);
            }
        } finally {
            localBrowseDelivery.remove();
        }
    }

    /**
     * Injection point: the success of a page the app's page fragment shows (a playlist, an album), on the main
     * thread, with the response model it renders. Records the page of a downloaded list the user opened while online,
     * to open it again with no network. The serialization and the write run on a worker thread: the response is
     * immutable. A recorded page is logged; of the pages that are not recorded only the odd reason is, once a minute.
     */
    static void onBrowseResponse(String browseId, Object result) {
        if (localBrowseDelivery.get() == result && result != null) return;

        noteAlive(HOOK_BROWSE_CAPTURE);
        String skipped = captureSkipReason(browseId, result);
        if (skipped != null) {
            if (!QUIET_CAPTURE_SKIPS.contains(skipped)) logSkip(HOOK_BROWSE_CAPTURE, skipped);
            return;
        }

        OfflineBrowsePages.Bytes holder = (OfflineBrowsePages.Bytes) result;
        String pageId = OfflineReplayLogic.sourceOfBrowseId(browseId);
        Utils.runOnBackgroundThread(() -> {
            try {
                byte[] response = holder.rvxBrowseBytes();
                if (OfflineBrowsePagesLogic.isContinuation(response)) {
                    logDecision(HOOK_BROWSE_CAPTURE, "skipped: " + browseId + " is a continuation");
                    return;
                }

                String list = OfflineBrowsePagesLogic.ownerOf(browseId, response,
                        OfflineCopyStore.tracksBySource().keySet());
                if (list == null) {
                    logSkip(HOOK_BROWSE_CAPTURE, "no downloaded list owns " + browseId);
                    return;
                }

                if (OfflineBrowsePagesLogic.isPlaylistId(list)) PlaylistSync.onOnlineDetail(list, response);
                int written = OfflineBrowsePages.capture(pageId, list, response);
                if (written < 0) return;

                int updates = OfflineBrowsePagesLogic.frameworkUpdatesSize(response);
                logDecision(HOOK_BROWSE_CAPTURE, "recorded " + browseId + " list=" + list + " bytes="
                        + response.length + " frameworkUpdates=" + updates + " gzip=" + written + " fields="
                        + OfflineBrowsePagesLogic.fieldSizes(response));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not record the page of " + browseId, ex);
            }
        });
    }

    private static boolean hasAlbumPlaylist() {
        for (String id : OfflineCopyStore.tracksBySource().keySet()) {
            if (id.startsWith("OLAK5uy_")) return true;
        }
        return false;
    }

    /** Why the page the success callback saw is not recorded, or null when it is. */
    @Nullable
    private static String captureSkipReason(@Nullable String browseId, Object result) {
        if (!enabled()) return REASON_SETTING_OFF;

        if (browseId == null || browseId.isEmpty()) return REASON_NO_ID;

        if (!OfflineBrowsePagesLogic.recordable(browseId, OfflineCopyStore::hasSource,
                OfflineReplay::hasAlbumPlaylist)) {
            return REASON_NOT_A_LIST;
        }

        if (!Utils.isInternetReachable()) return REASON_OFFLINE;

        if (!(result instanceof OfflineBrowsePages.Bytes)) return "no bytes bridge on the result";

        return null;
    }

    /**
     * Opens the Downloads page from the settings page with the app's own browse command, and closes the settings so
     * that the page is the one on screen. Says why it cannot while the replay is off or no command has run yet.
     */
    static void openList(Context context) {
        Activity activity = context instanceof Activity own ? own : Utils.getActivity();
        if (activity == null || !enabled()) {
            Utils.showToastShort(str("morphe_music_offline_library_not_enabled"));
            return;
        }

        if (!dispatchCommand(OfflineLibraryLogic.browseCommand(OfflineLibraryLogic.DOWNLOADS_BROWSE_ID))) {
            Utils.showToastShort(str("morphe_music_offline_library_not_ready"));
            return;
        }

        activity.finish();
    }

    // endregion

    // region hooks

    /** Injection point: the flag the gate read, to tell in the log which of its two paths a version takes. */
    static void noteGateFlag(boolean flag) {
        noteAlive(HOOK_GATE_FLAG);
        lastGateFlag = flag;
    }

    /**
     * When Android reports a network, only the exact synthetic redispatch may bypass stock's download recovery.
     * Neither the scoped tap timeout nor an upstream DEAD verdict grants that permission to unrelated commands.
     */
    static boolean holdsForGate(Object command, String videoId) {
        try {
            noteAlive(HOOK_GATE);
            boolean ours = mayHold(videoId);
            boolean enabled = enabled();
            boolean ownedLocal = ownsLocalDispatch(command);
            String reason = replayGateRejection(enabled, PlaybackConnectivity.osReachable(), ours, ownedLocal);
            if (reason != null) {
                logDecision(HOOK_GATE, "declined:" + reason + " v=" + videoId + " ours=" + ours);
                return false;
            }

            Logger.printWarn(() -> "OfflineReplay: gate bypass v=" + videoId + " holds=true flag=" + lastGateFlag
                    + (ownedLocal ? " exact-local-dispatch" : " network=offline"));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: gate check failed", ex);
            return false;
        }
    }

    static boolean ownsLocalDispatch(Object command) {
        WeakReference<Command> root = localStart;
        return command != null && command == redispatchedShuffle.get() && root != null && root.get() == command;
    }

    @Nullable
    static String replayGateRejection(boolean enabled, boolean deviceOnline, boolean held, boolean ownedLocal) {
        return OfflineReplayLogic.skipReason(enabled, !deviceOnline || ownedLocal, held, true);
    }

    /**
     * Injection point: the player request failed in the stock offline lookup. The stored response of the song ready
     * for the app's parser, or null to let the stock failure stand. Off the main thread.
     */
    @Nullable
    static byte[] storedForPlayer(String videoId, boolean offlineRequest) {
        try {
            noteAlive(HOOK_PLAYER);
            logDecision(HOOK_PLAYER, "lookup entered v=" + videoId + " offlineRequest=" + offlineRequest);

            boolean online = PlaybackConnectivity.reachableForTarget(mayHold(videoId));
            byte[] stored = null;
            boolean localRequest = offlineRequest || !online;
            if (enabled() && localRequest && videoId != null && !videoId.isEmpty()) {
                loadPlaybackIndexes();
                stored = storedResponseOf(videoId);
            }

            String reason = OfflineReplayLogic.skipReason(enabled(), localRequest, stored != null, true);
            if (reason != null) {
                logDecision(HOOK_PLAYER, "lookup declined:" + reason + " v=" + videoId);
                return null;
            }

            byte[] prepared = OfflineReplayResponse.prepare(stored);
            Logger.printWarn(() -> "OfflineReplay: player v=" + videoId + " stock failed, answering from the copy"
                    + " stored=" + prepared.length + "B " + OfflineResponseTrim.gateFacts(prepared));
            logDecision(HOOK_PLAYER, "lookup answered v=" + videoId);
            return prepared;
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: no stored response for v=" + videoId, ex);
            return null;
        }
    }

    /**
     * Injection point: the response built from the stored bytes. The same audio swap an online response gets turns
     * it into one that plays the copy.
     *
     * @return the response to answer with, or null when the swap declined and the stock failure must stand.
     */
    @Nullable
    static Object finishPlayer(Object response, String videoId) {
        Object candidate = preparePlayer(response, videoId);
        if (candidate != null) {
            StreamSwap.publishStored(candidate);
            NoConnectionSheet.onCopyReplayed(videoId);
        }
        return candidate;
    }

    @Nullable
    static Object preparePlayer(Object response, String videoId) {
        try {
            Object swapped = StreamSwap.swapStored(response);
            boolean swap = swapped != response;
            Logger.printWarn(() -> "OfflineReplay: player v=" + videoId + " swap=" + (swap ? "SWAP" : "DECLINED")
                    + " " + describe(swapped));
            return swap ? swapped : null;
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: could not finish the response of v=" + videoId, ex);
            return null;
        }
    }

    private static String describe(Object response) {
        boolean marker = response instanceof StreamSwap.Response adapter
                && OfflineReplayLogic.hasOfflineMarker(adapter.rvxBytes());
        return "flagR=" + marker;
    }

    /** Keeps online successes untouched; an in-flight online next request may fail after disconnection. */
    static ListenableFuture<?> wrapNext(Object request, ListenableFuture<?> stock) {
        try {
            noteAlive(HOOK_NEXT);
            if (!enabled() || watchTargetOf(request) == null) return stock;

            return PlayerFallback.wrapLane(request, stock, true);
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: could not wrap the watch-next request", ex);
            return stock;
        }
    }

    @Nullable
    private static OfflineReplayLogic.WatchTarget watchTargetOf(Object request) {
        if (!(request instanceof NextRequest next) || !(next.rvxCommand() instanceof Command command)) return null;
        return watchTargetOf(command);
    }

    @Nullable
    private static OfflineReplayLogic.WatchTarget watchTargetOf(Command command) {
        byte[] bytes = command.toByteArray();
        OfflineReplayLogic.WatchTarget offline = OfflineReplayLogic.offlineWatchOf(bytes);
        if (offline != null) return offline;
        OfflineReplayLogic.WatchTarget online = OfflineReplayLogic.onlineWatchOf(bytes, true);
        return online != null ? online : OfflineReplayLogic.playlistWatchOf(bytes);
    }

    static boolean mayAnswerNext(Object request) {
        OfflineReplayLogic.WatchTarget watch = watchTargetOf(request);
        if (watch == null) return false;
        return watch.videoId().isEmpty() ? OfflineCopyStore.mayHaveSource(watch.playlistId())
                : mayHold(watch.videoId());
    }

    /** Disconnected next requests complete from the committed queue before stock can schedule network work. */
    @Nullable
    static ListenableFuture<?> localNext(Object request) {
        if (!(request instanceof NextRequest next)) return null;
        Object command = next.rvxCommand();

        Object original = PlaybackRequestIdentity.original(request);
        Object sourceCommand = original instanceof NextRequest source ? source.rvxCommand() : command;
        ShuffleCommand shuffled = shuffleCommandOf(request, original, sourceCommand, command);
        Logger.printDebug(() -> "OfflineReplay: next shuffle-plan=" + (shuffled == null ? "none" : shuffled.generation)
                + " request=" + System.identityHashCode(request) + " original=" + System.identityHashCode(original)
                + " root=" + System.identityHashCode(command)
                + " originalRoot=" + System.identityHashCode(sourceCommand));
        if (shuffled != null) return localShuffleNext(next, shuffled);
        boolean superseded = isOldShuffleStart(sourceCommand) || isOldShuffleStart(command);
        if (superseded || isShufflePressStart(sourceCommand) || isShufflePressStart(command)) {
            logShuffleMiss(request, original, command, superseded);
        }
        if (superseded) return cancelledNext();
        if (!(command instanceof Command)) return null;
        if (!PlayerFallback.routeOffline(request) || !mayAnswerNext(request)) return null;

        ReplayFuture result = new ReplayFuture();
        Utils.runOnBackgroundThread(() -> {
            if (result.isCancelled()) return;
            Object response = nextResponse(request);
            if (!PlayerFallback.currentRoute(request)) result.cancel(false);
            else if (response != null) result.set(response);
            else PlayerFallback.finishUnavailable(request, result);
        });
        return result;
    }

    /**
     * Only the accepted shuffled start gets a fresh saved queue, never a previous native queue with the same seed.
     * The reply is built from the bytes of the plan, not from the command the request holds: the dispatched command
     * may be gone (it is only weakly held) or be a rebuilt copy of it, and the plan is the same either way.
     */
    private static ListenableFuture<?> localShuffleNext(NextRequest next, ShuffleCommand shuffled) {
        ReplayFuture result;
        synchronized (shuffleCommands) {
            if (shuffled.reply != null) return shuffled.reply;
            result = new ReplayFuture();
            shuffled.reply = result;
        }

        int generation = shuffled.generation;
        if (!currentShuffle(generation)) {
            result.cancel(false);
            return result;
        }

        Utils.runOnBackgroundThread(() -> {
            if (result.isCancelled()) return;
            try {
                Object response = buildShuffleReply(next, shuffled.plan.command(), generation, shuffled.plan);
                if (response != null && ownsLocalPlaylistShuffleReply(response)) {
                    result.set(response);
                    return;
                }
            } catch (Exception | LinkageError failure) {
                Logger.printException(() -> "OfflineReplay: could not answer the shuffled native queue", failure);
            }
            result.cancel(false);
        });
        return result;
    }

    @Nullable
    private static Object buildShuffleReply(NextRequest next, byte[] commandBytes, int generation, ShufflePlan plan) {
        if (!currentShuffle(generation) || !shuffleSourceStillCurrent(plan.source())) return null;

        String sourceId = plan.source().saved().id();
        if (plan.source().legacy() && !plan.source().saved().equals(OfflineCopyStore.sourceForOpening(sourceId))) {
            return null;
        }

        OfflineReplayLogic.WatchTarget target = OfflineReplayLogic.offlineWatchOf(commandBytes);
        if (target == null || !playlistId(sourceId).equals(target.playlistId())
                || !OfflineReplayLogic.isShuffleStart(commandBytes, target)) return null;

        List<OfflineWatchNext.Item> items = plan.items();
        Map<String, String> videos = videoCopies();
        String songId = OfflineReplayLogic.songOf(target.videoId(), videos);
        if (!songId.equals(items.get(0).videoId()) || !holds(target.videoId())) return null;
        if (OfflineReplayLogic.showsVideos(target.videoId(), videos, commandBytes)) {
            items = OfflineReplayLogic.showingVideos(items, videos);
        }

        String title = plan.source().saved().title();
        if (title.isEmpty()) title = str("morphe_music_offline_library_untitled");
        byte[] bytes = OfflineWatchNext.buildSavedShuffle(
                target.playlistId(), OfflinePlaybackArtwork.items(items), title);
        Object response = next.rvxNextResponse(bytes, true, null);
        if (response == null) return null;

        synchronized (shuffleCommands) {
            if (generation != shuffleGeneration) return null;
            shuffleReply = new ShuffleReply(new WeakReference<>(response), generation, plan.source());
        }
        return response;
    }

    /**
     * Whether the command is the shuffled start of a Shuffle Play press that a later press replaced: it carries the
     * token of an older dispatch (see {@link OfflineReplayLogic#isSupersededShuffleStart}). Such a request has no
     * queue to answer with, least of all the queue of the later press, so it fails closed. A request of the current
     * press without its token is never placed by content; it goes the ordinary way.
     */
    private static boolean isOldShuffleStart(Object command) {
        return command instanceof Command root
                && OfflineReplayLogic.isSupersededShuffleStart(root.toByteArray(), shuffleGeneration);
    }

    private static boolean isShufflePressStart(Object command) {
        return command instanceof Command root && OfflineReplayLogic.isShufflePressStart(root.toByteArray());
    }

    private static ListenableFuture<?> cancelledNext() {
        ReplayFuture result = new ReplayFuture();
        result.cancel(false);
        return result;
    }

    @Nullable
    static Object nextResponse(Object request) {
        try {
            if (!(request instanceof NextRequest next) || !(next.rvxCommand() instanceof Command command)) return null;
            Object original = PlaybackRequestIdentity.original(request);
            Object originalRoot = original instanceof NextRequest source ? source.rvxCommand() : command;
            if (shuffleCommandOf(request, original, originalRoot, command) != null
                    || isOldShuffleStart(originalRoot) || isOldShuffleStart(command)) return null;
            byte[] commandBytes = command.toByteArray();
            String shuffle = OfflineReplayLogic.shufflePlaylistOf(commandBytes);
            if (shuffle == null && originalRoot != command && originalRoot instanceof Command root) {
                shuffle = OfflineReplayLogic.shufflePlaylistOf(root.toByteArray());
            }
            if (shuffle != null) {
                int generation = shuffleGeneration;
                if (!currentShuffle(generation)) return null;

                ShufflePlan plan = prepareShuffle(shuffle);
                return plan == null ? null : buildShuffleReply(next, plan.command(), generation, plan);
            }

            OfflineReplayLogic.WatchTarget target = watchTargetOf(command);
            if (target == null) return null;

            loadPlaybackIndexes();
            if (!target.videoId().isEmpty() && !verifiedCopy(target.videoId())) return null;

            Object edited = PlayerFallback.editedQueueSelection(request, target.videoId());
            if (edited != null) {
                // Never fall back to a reconstructed saved list when an exact edited selection has gone stale.
                return PlayerFallback.editedNextResponse(edited, request, next,
                        target.playlistId(), target.videoId(), commandBytes);
            }

            byte[] bytes = nextBytes(target.playlistId(), target.videoId(), target.index(), true, commandBytes);
            return bytes == null ? null : next.rvxNextResponse(bytes, false, null);
        } catch (Exception failure) {
            Logger.printException(() -> "OfflineReplay: could not answer the native queue", failure);
            return null;
        }
    }

    /**
     * The watch-next response of the queue the request belongs to, or null to let the stock failure stand. A request
     * that names no list of ours but a song with stored data gets that song as a queue of one. Off the main thread.
     */
    @Nullable
    private static byte[] nextBytes(String playlistId, String videoId, int index, boolean offlineRequest,
                                    byte[] command) {
        String reason = OfflineReplayLogic.skipReason(enabled(), offlineRequest, true, true);
        if (reason != null) {
            logSkip(HOOK_NEXT, reason);
            return null;
        }

        loadPlaybackIndexes();
        Map<String, String> videos = videoCopies();
        String pairedSong = canonicalSongOf(videoId, videos);
        String syntheticSource = sourceOf(playlistId);
        String sourceId = syntheticSource != null ? syntheticSource : savedSourceOf(playlistId, pairedSong);
        OfflineSourceStore.Source source = sourceId == null || SONGS.equals(sourceId)
                ? null : OfflineCopyStore.sourceForOpening(sourceId);
        List<OfflineWatchNext.Item> items = sourceId == null ? List.of()
                : source != null ? queueOf(source) : queueOf(sourceId);
        String songId = OfflineReplayLogic.queueIdOf(items, videoId, pairedSong, index);
        int current = videoId.isEmpty() ? firstVerified(items)
                : source != null && syntheticSource == null
                ? PlaylistSyncPlan.readyIndex(source.videoIds(), items, songId, index, OfflineReplay::canonicalCopyId)
                : OfflineReplayLogic.currentIndex(items, songId, index);
        if (current < 0) {
            if (videoId.isEmpty()) return null;
            OfflineCopyFiles.Stored stored = OfflineCopyStore.find(videoId);
            if (stored == null && !videoId.equals(pairedSong)) stored = OfflineCopyStore.find(pairedSong);
            if (stored == null) {
                logSkip(HOOK_NEXT, OfflineReplayLogic.REASON_NOT_OURS);
                return null;
            }

            items = List.of(OfflineReplayLogic.itemOf(stored.metadata()));
            current = 0;
        }
        String queueId = playlistId == null || playlistId.isEmpty() ? playlistId(SONGS) : playlistId;
        // A request for the music video of a song shows the music video of every song that has a copy, as the queue of
        // an online album does; the video copy answers it in either display of the app's own Song/Video switch.
        if (OfflineReplayLogic.showsVideos(videoId, videos, command)) {
            items = OfflineReplayLogic.showingVideos(items, videos);
        }

        byte[] response = OfflineWatchNext.build(queueId, OfflinePlaybackArtwork.items(items), current);
        int queued = items.size();
        long shown = items.stream().filter(item -> item.musicVideoType() == CommandRewrite.TYPE_OMV).count();
        List<String> queueIds = items.stream().map(OfflineWatchNext.Item::videoId).collect(Collectors.toList());
        int committedIndex = current;
        Logger.printWarn(() -> "OfflineReplay: next v=" + videoId + " playlist=" + queueId + " items=" + queued
                + " current=" + committedIndex + " ids=" + queueIds
                + " videos=" + shown + " bytes=" + response.length);
        return response;
    }

    private static int firstVerified(List<OfflineWatchNext.Item> items) {
        for (int index = 0; index < items.size(); index++) {
            if (verifiedCopy(items.get(index).videoId())) return index;
        }
        return -1;
    }

    static String canonicalCopyId(String videoId) {
        if (videoId.isEmpty() || OfflineCopyStore.has(videoId)) return videoId;
        DownloadedRegistry registry = DownloadedMarkers.loadedRegistry();
        String canonical = registry == null ? videoId : registry.canonicalId(videoId);
        return videoId.equals(canonical) ? RawAudioStore.aliases().getOrDefault(videoId, videoId) : canonical;
    }

    private static String canonicalSongOf(String videoId, Map<String, String> videos) {
        return canonicalCopyId(OfflineReplayLogic.songOf(videoId, videos));
    }

    private static boolean verifiedCopy(String videoId) {
        if (VideoCopyStore.find(videoId) != null && VideoCopyStore.response(videoId) != null) return true;

        String canonical = canonicalCopyId(videoId);
        return RawAudioStore.find(canonical) != null && OfflineCopyStore.find(canonical) != null;
    }

    @Nullable
    private static String savedSourceOf(@Nullable String playlistId, String songId) {
        if (playlistId == null || playlistId.isEmpty()) return null;
        if (OfflineCopyStore.sourceForOpening(playlistId) != null) return playlistId;
        return OfflineReplayLogic.sourceOfPlaylist(playlistId, songId, OfflineCopyStore.tracksBySource());
    }

    /**
     * The committed video copies, music video id to song id; none when they cannot be read, so that a queue is
     * always answered, as plain items. Off the main thread.
     */
    private static Map<String, String> videoCopies() {
        try {
            return VideoCopyStore.pairs();
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: the video copies cannot be read, the queue has none", ex);
            return Map.of();
        }
    }

    // endregion

    /** A skipped hook is the normal case while online: each reason is said once a minute, and only for debugging. */
    private static void logSkip(String hook, String reason) {
        long now = System.currentTimeMillis();
        if (OfflineReplayLogic.shouldLogSkip(skipLoggedAt, hook + ":" + reason, now)) {
            Logger.printDebug(() -> "OfflineReplay: " + hook + " skipped reason=" + reason);
        }
    }

    /** The shared progress-bar and MediaSession seek supplies the seeking clip's selected formats. */
    static boolean seeksFile(@Nullable Uri first, @Nullable Uri second, boolean hasFirst, boolean hasSecond) {
        noteAlive(HOOK_SEEK);
        if (!enabled()) return false;

        boolean file = OfflineReplayLogic.seeksFile(hasFirst, StreamSwapDataSource.isAvailableToken(first),
                hasSecond, StreamSwapDataSource.isAvailableToken(second));
        logDecision(HOOK_SEEK, file ? "local clip, keeping requested position" : "stock cache lookup");
        return file;
    }

    /** A decision of a hook that only runs for an offline request or a stock failure: every one is said. */
    private static void logDecision(String hook, String decision) {
        Logger.printDebug(() -> "OfflineReplay: " + hook + " " + decision);
    }

    /** Says once per process that a hook was reached: proof, online or offline, that its injection is alive. */
    private static void noteAlive(String hook) {
        if (!aliveHooks.add(hook)) return;

        Logger.printDebug(() -> "OfflineReplay: hook alive hook=" + hook + " enabled=" + enabled()
                + " network=" + (PlaybackConnectivity.osReachable()
                ? (UpstreamProbe.dead() ? "dead" : "online") : "offline"));
    }
}
