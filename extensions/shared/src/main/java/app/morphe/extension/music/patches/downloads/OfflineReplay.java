/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import app.morphe.extension.music.patches.downloads.NativeDownloads.Command;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Plays a downloaded song with no network. The Downloads patch hooks the app's own offline playback path: the gate
 * that drops a watch command while offline, the player request that fails for a song the app did not download, and
 * the watch-next request of the queue. Each hook answers only for an offline request of a copy that has stored data,
 * and only while the setting is on; every other request, and every request while online, is left to the stock code.
 */
@SuppressWarnings("unused")
public final class OfflineReplay {
    /** The id of the list of every stored song, next to the ids of the downloaded playlists and albums. */
    public static final String SONGS = "songs";

    /** Added by the Downloads patch to the class that dispatches a command, so a command can be run again. */
    public interface Dispatcher {
        void rvxDispatch(Object command, Map<?, ?> context);
    }

    /** Added by the Downloads patch to the watch-next request. */
    public interface NextRequest {
        /** The watch command the request was made for. */
        Object rvxCommand();

        /** The stock response object of the watch-next response {@code bytes}. */
        Object rvxNextResponse(byte[] bytes);
    }

    /** What the last dispatched command left behind: the way to run a command of our own. */
    private record Captured(WeakReference<Dispatcher> dispatcher, Command template,
                            WeakReference<Map<?, ?>> context) {}

    private static final String OFFLINE_BROWSE_PREFIX = "FEmusic_offline";
    private static final String HOOK_GATE = "gate";
    private static final String HOOK_PLAYER = "player";
    private static final String HOOK_NEXT = "next";
    private static final String HOOK_COMMAND = "command";
    private static final String HOOK_TAB = "tab";
    private static final String HOOK_SEEK = "seek";
    private static final String HOOK_GATE_FLAG = "gate-flag";
    private static final String HOOK_CHIP = "library-chip";

    private static final ConcurrentMap<String, Long> skipLoggedAt = new ConcurrentHashMap<>();
    private static final Set<String> aliveHooks = ConcurrentHashMap.newKeySet();
    private static volatile Captured captured;
    private static volatile boolean lastGateFlag;

    private OfflineReplay() {}

    static boolean enabled() {
        return OfflineReplayHooks.usable() && Settings.PLAY_DOWNLOADS_LOCALLY.get();
    }

    /**
     * Whether the device has a network that reaches the internet. An unknown state counts as online, so the stock
     * behavior stays when this cannot tell; no network at all counts as offline.
     */
    static boolean networkAvailable() {
        try {
            Context context = Utils.getContext();
            if (context == null || !(context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    instanceof ConnectivityManager manager)) {
                return true;
            }

            Network active = manager.getActiveNetwork();
            if (active == null) return false;

            NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
            if (capabilities == null) return false;

            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ex) {
            Logger.printDebug(() -> "OfflineReplay: network state unknown, counted as online", ex);
            return true;
        }
    }

    static String playlistId(String sourceId) {
        return OfflineReplayLogic.playlistId(sourceId);
    }

    @Nullable
    static String sourceOf(@Nullable String playlistId) {
        return OfflineReplayLogic.sourceOf(playlistId);
    }

    /** Whether the song has stored data. In memory, so the main thread may ask. */
    static boolean holds(String videoId) {
        return OfflineCopyStore.has(videoId);
    }

    // region queue and commands

    /**
     * The tracks of a list that can play with no network, in the order the list shows them. Off the main thread.
     * The all-songs list is sorted by title.
     */
    static List<OfflineWatchNext.Item> queueOf(String sourceId) {
        if (SONGS.equals(sourceId)) return OfflineReplayLogic.itemsOfSongs(OfflineCopyStore.copiesWithData());
        return OfflineReplayLogic.itemsOfTracks(OfflineCopyStore.tracksOf(sourceId));
    }

    /** The command that starts track {@code index} of the list's queue with no network, or null outside it. */
    @Nullable
    static byte[] offlineCommand(String sourceId, int index) {
        List<OfflineWatchNext.Item> items = queueOf(sourceId);
        if (index < 0 || index >= items.size()) return null;

        return OfflineWatchCommand.build(items.get(index).videoId(), playlistId(sourceId), index);
    }

    /**
     * Starts track {@code index} of the list's queue. Offline the command is the offline watch command; online it
     * is the normal watch command, so the online response still owns radio, lyrics and the rest, and only the audio
     * comes from the copy. On the main thread.
     *
     * @return false when no command was seen yet to run one with: nothing was started.
     */
    static boolean play(String sourceId, int index) {
        if (!canDispatch()) return false;

        // A tap is a fresh selection, so a video mode the last song left behind does not hold for it. The stream
        // copy is audio only: offline, the video mode would ask for the video stream and the swap would decline.
        if (!networkAvailable()) logDecision(HOOK_COMMAND, "audio mode: " + AudioFirst.startAudioMode());

        Utils.runOnBackgroundThread(() -> {
            try {
                byte[] command = playCommand(sourceId, index);
                if (command == null) {
                    Logger.printWarn(() -> "OfflineReplay: no track " + index + " in " + sourceId);
                    return;
                }
                dispatchCommand(command);
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not start " + sourceId + " at " + index, ex);
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
        if (!canDispatch()) return false;

        Utils.runOnBackgroundThread(() -> {
            try {
                dispatchCommand(onlineCommand(sourceId, videoId));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not start online " + videoId + " of " + sourceId, ex);
            }
        });
        return true;
    }

    @Nullable
    private static byte[] playCommand(String sourceId, int index) {
        if (!networkAvailable()) return offlineCommand(sourceId, index);

        List<OfflineWatchNext.Item> items = queueOf(sourceId);
        if (index < 0 || index >= items.size()) return null;

        return onlineCommand(sourceId, items.get(index).videoId());
    }

    /**
     * The ordinary online watch command of a song tapped in a list: the playlist's id and the song's place in it
     * for a playlist or album, the song alone for the all-songs list, where the app builds the song's own radio.
     */
    private static byte[] onlineCommand(String sourceId, String videoId) {
        return OnlineWatchCommand.build(videoId, OfflineReplayLogic.onlinePlaylistId(sourceId, SONGS),
                indexInSource(sourceId, videoId));
    }

    /** The place of the song in the whole downloaded list: the online playlist counts the tracks that are not here. */
    private static int indexInSource(String sourceId, String videoId) {
        for (OfflineSourceStore.Source source : OfflineCopyStore.sources()) {
            if (source.id().equals(sourceId)) return Math.max(0, source.videoIds().indexOf(videoId));
        }
        return 0;
    }

    private static boolean canDispatch() {
        Captured seen = captured;
        return seen != null && seen.dispatcher().get() != null;
    }

    /**
     * Runs the command through the dispatcher the app's own taps use, on the main thread.
     *
     * @return false when no command was seen yet to run one with: nothing was dispatched.
     */
    static boolean dispatchCommand(byte[] commandBytes) {
        Captured seen = captured;
        Dispatcher dispatcher = seen == null ? null : seen.dispatcher().get();
        if (dispatcher == null) return false;

        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                dispatcher.rvxDispatch(seen.template().rvxWithBytes(commandBytes), contextOf(seen));
            } catch (Exception ex) {
                Logger.printException(() -> "OfflineReplay: could not dispatch the command", ex);
            }
        });
        return true;
    }

    /** A copy of the context the last command had, or an empty one when it is gone or cannot be copied. */
    private static Map<?, ?> contextOf(Captured seen) {
        Map<?, ?> context = seen.context().get();
        if (context == null) return Collections.emptyMap();

        try {
            return new HashMap<Object, Object>(context);
        } catch (Exception ex) {
            Logger.printDebug(() -> "OfflineReplay: the command's context cannot be copied, using none", ex);
            return Collections.emptyMap();
        }
    }

    /**
     * Injection point: every command the app dispatches, on the main thread. Remembers how to dispatch a command
     * while the setting is on, and hands a browse command of the Downloads page to the offline list: that page
     * belongs to the list whether or not the setting is on, so it decides by itself whether it opens.
     *
     * @return true when the command was taken and must not be dispatched.
     */
    static boolean onCommand(Object dispatcher, Object command, Map<?, ?> context) {
        try {
            noteAlive(HOOK_COMMAND);
            if (!(dispatcher instanceof Dispatcher stockDispatcher) || !(command instanceof Command message)) {
                return false;
            }

            if (!enabled()) return false;

            captured = new Captured(new WeakReference<>(stockDispatcher), message, new WeakReference<>(context));
            OfflineCopyStore.warmUp();
            OfflineBackfill.kick();

            String browseId = OfflineReplayLogic.browseIdOf(message.toByteArray());
            if (browseId != null) logDecision(HOOK_COMMAND, "browse=" + browseId);
            return browseId != null && browseId.startsWith(OFFLINE_BROWSE_PREFIX)
                    && OfflineLibrary.onDownloadsBrowse(browseId, OfflineLibraryLogic.Route.COMMAND);
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: could not read the command", ex);
            return false;
        }
    }

    /**
     * Injection point: the Library page loads a chip, with the browse id the chip resolved to, on the main thread.
     * The Downloads chip sends no command, so it is the only way to hand its page to the offline list.
     */
    static void onLibraryChip(String browseId) {
        noteAlive(HOOK_CHIP);
        logDecision(HOOK_CHIP, "browse=" + browseId + " enabled=" + enabled());
        if (enabled()) OfflineLibrary.onLibraryChip(browseId);
    }

    /**
     * Opens the list from the settings page, the entry that does not depend on any page of the app: it is there with
     * no network and whatever the home of the app shows. Does nothing but say so while the replay is off.
     */
    static void openList(Context context) {
        Activity activity = context instanceof Activity own ? own : Utils.getActivity();
        if (activity == null || !enabled()) {
            Utils.showToastShort(str("morphe_music_offline_library_not_enabled"));
            return;
        }

        OfflineLibrary.openFromSettings(activity);
    }

    /**
     * Injection point: the bottom bar navigates to a tab's page, on the main thread. The Library tab while offline
     * goes to the Downloads page, which the list replaces; the stock navigation goes on behind it. Online the tab
     * is the app's.
     */
    static void onTabNavigation(Object command) {
        noteAlive(HOOK_TAB);
        if (!enabled() || !(command instanceof Command message)) return;

        String browseId = OfflineReplayLogic.browseIdOf(message.toByteArray());
        logDecision(HOOK_TAB, "browse=" + browseId);
        if (browseId != null) OfflineLibrary.onTab(browseId);
    }

    /**
     * Injection point: the app is asked to remember a page as the Library tab's launch page. A tap on the Library
     * page's Downloads chip asks for the stock Downloads page, which the list replaces; remembered, the Library tab
     * would restore that page later, empty for a user whose copies are ours, so the request is dropped.
     *
     * @return true when the page is not to be remembered.
     */
    static boolean skipLibraryPersist(Object command) {
        if (!enabled() || !(command instanceof Command message)) return false;

        String browseId = OfflineReplayLogic.persistedBrowseId(message.toByteArray());
        boolean skip = OfflineLibraryLogic.keepsLaunchPage(browseId, OfflineCopyStore.hasAnything());
        if (skip) Logger.printInfo(() -> "OfflineReplay: launch page " + browseId + " not remembered");
        return skip;
    }

    // endregion

    // region hooks

    /** Injection point: the flag the gate read, to tell in the log which of its two paths a version takes. */
    static void noteGateFlag(boolean flag) {
        noteAlive(HOOK_GATE_FLAG);
        lastGateFlag = flag;
    }

    /**
     * Injection point: the gate that drops a watch command while the app believes it is offline. True only when
     * there is no network and the song has stored data: the command then goes on to the stock playback.
     */
    static boolean holdsForGate(String videoId) {
        try {
            noteAlive(HOOK_GATE);
            boolean ours = videoId != null && holds(videoId);
            boolean enabled = enabled();
            boolean offline = enabled && !networkAvailable();
            String reason = OfflineReplayLogic.skipReason(enabled, offline, ours, true);
            if (reason != null) {
                logDecision(HOOK_GATE, "declined:" + reason + " v=" + videoId + " ours=" + ours);
                return false;
            }

            Logger.printWarn(() -> "OfflineReplay: gate bypass v=" + videoId + " holds=true flag=" + lastGateFlag
                    + " network=offline");
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: gate check failed", ex);
            return false;
        }
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

            OfflineCopyFiles.Stored stored = null;
            if (enabled() && offlineRequest && videoId != null) stored = OfflineCopyStore.find(videoId);

            String reason = OfflineReplayLogic.skipReason(enabled(), offlineRequest, stored != null, true);
            if (reason != null) {
                logDecision(HOOK_PLAYER, "lookup declined:" + reason + " v=" + videoId);
                return null;
            }

            byte[] prepared = OfflineReplayResponse.prepare(stored.response());
            Logger.printWarn(() -> "OfflineReplay: player v=" + videoId + " stock failed, answering from the copy"
                    + " stored=" + prepared.length + "B");
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
        try {
            Object swapped = StreamSwap.swapStored(response);
            boolean swap = swapped != response;
            Logger.printWarn(() -> "OfflineReplay: player v=" + videoId + " swap=" + (swap ? "SWAP" : "DECLINED")
                    + " " + describe(swapped));
            if (swap) NoConnectionSheet.onCopyReplayed(videoId);
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

    /**
     * Injection point: the watch-next future of a request. While online, with the setting off, and for any request
     * that is not an offline watch of ours the stock future is returned as it is. For ours, a stock failure is
     * answered with the queue of the list.
     */
    static ListenableFuture<?> wrapNext(Object request, ListenableFuture<?> stock) {
        try {
            noteAlive(HOOK_NEXT);
            OfflineReplayLogic.WatchTarget target = watchTargetOf(request);
            boolean ours = target != null
                    && (sourceOf(target.playlistId()) != null || holds(target.videoId()));
            String reason = OfflineReplayLogic.skipReason(enabled(), target != null, ours, true);
            if (reason != null) {
                logSkip(HOOK_NEXT, reason);
                return stock;
            }

            return answerNextFailure((NextRequest) request, target, stock);
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: could not wrap the watch-next request", ex);
            return stock;
        }
    }

    @Nullable
    private static OfflineReplayLogic.WatchTarget watchTargetOf(Object request) {
        if (!(request instanceof NextRequest next) || !(next.rvxCommand() instanceof Command command)) return null;

        return OfflineReplayLogic.offlineWatchOf(command.toByteArray());
    }

    private static ListenableFuture<?> answerNextFailure(NextRequest request, OfflineReplayLogic.WatchTarget target,
                                                         ListenableFuture<?> stock) {
        ReplayFuture result = new ReplayFuture();
        stock.addListener(() -> {
            try {
                result.set(stock.get());
            } catch (Exception failure) {
                if (!result.isCancelled()) {
                    Utils.runOnBackgroundThread(() -> answerFromStore(request, target, failure, result));
                }
            }
        }, Runnable::run);
        result.addListener(() -> {
            if (result.isCancelled()) stock.cancel(false);
        }, Runnable::run);
        return result;
    }

    private static void answerFromStore(NextRequest request, OfflineReplayLogic.WatchTarget target,
                                        Exception failure, ReplayFuture result) {
        try {
            byte[] bytes = nextBytes(target.playlistId(), target.videoId(), target.index(), true);
            if (bytes == null) {
                result.setException(failure);
                return;
            }

            result.set(request.rvxNextResponse(bytes));
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: next v=" + target.videoId() + " could not be answered", ex);
            result.setException(failure);
        }
    }

    /**
     * The watch-next response of the queue the request belongs to, or null to let the stock failure stand. A request
     * that names no list of ours but a song with stored data gets that song as a queue of one. Off the main thread.
     */
    @Nullable
    public static byte[] nextBytes(String playlistId, String videoId, int index, boolean offlineRequest) {
        boolean ours = sourceOf(playlistId) != null || holds(videoId);
        String reason = OfflineReplayLogic.skipReason(enabled(), offlineRequest, ours, true);
        if (reason != null) {
            logSkip(HOOK_NEXT, reason);
            return null;
        }

        String sourceId = sourceOf(playlistId);
        List<OfflineWatchNext.Item> items = sourceId == null ? List.of() : queueOf(sourceId);
        int current = OfflineReplayLogic.currentIndex(items, videoId, index);
        if (current < 0) {
            OfflineCopyFiles.Stored stored = OfflineCopyStore.find(videoId);
            if (stored == null) {
                logSkip(HOOK_NEXT, OfflineReplayLogic.REASON_NOT_OURS);
                return null;
            }

            items = List.of(OfflineReplayLogic.itemOf(stored.metadata()));
            current = 0;
        }

        String queueId = playlistId == null || playlistId.isEmpty() ? playlistId(SONGS) : playlistId;
        byte[] response = OfflineWatchNext.build(queueId, items, current);
        int queued = items.size();
        Logger.printWarn(() -> "OfflineReplay: next v=" + videoId + " playlist=" + queueId + " items=" + queued
                + " bytes=" + response.length);
        return response;
    }

    // endregion

    /** A skipped hook is the normal case while online: each reason is said once a minute, and only for debugging. */
    private static void logSkip(String hook, String reason) {
        long now = System.currentTimeMillis();
        if (OfflineReplayLogic.shouldLogSkip(skipLoggedAt, hook + ":" + reason, now)) {
            Logger.printDebug(() -> "OfflineReplay: " + hook + " skipped reason=" + reason);
        }
    }

    /** A decision of a hook that only runs for an offline request or a stock failure: every one is said. */
    /**
     * What the seek snap answers to keep the dragged position. The player compares the snap with the target: a
     * negative snap cancels the seek, one below the target moves it there, one at or above it seeks to the target.
     */
    private static final long KEEP_TARGET = Long.MAX_VALUE;

    /**
     * Injection point: the seek snap the player computed for a drag on the progress bar, in milliseconds. A song
     * played from a copy has no span in the stream cache, so with no network the snap is 0 and the seek restarts the
     * song; there an answer above any target makes the player seek to the dragged position.
     */
    static long seekSnap(long snappedMillis) {
        try {
            noteAlive(HOOK_SEEK);
            boolean drop = OfflineReplayLogic.dropsSeekSnap(enabled(), networkAvailable(), snappedMillis);
            logDecision(HOOK_SEEK, "snap=" + snappedMillis + " online=" + networkAvailable()
                    + (drop ? " dropped, seeking to the dragged position" : " kept"));
            return drop ? KEEP_TARGET : snappedMillis;
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineReplay: seek snap", ex);
            return snappedMillis;
        }
    }

    private static void logDecision(String hook, String decision) {
        Logger.printDebug(() -> "OfflineReplay: " + hook + " " + decision);
    }

    /** Says once per process that a hook was reached: proof, online or offline, that its injection is alive. */
    private static void noteAlive(String hook) {
        if (!aliveHooks.add(hook)) return;

        Logger.printDebug(() -> "OfflineReplay: hook alive hook=" + hook + " enabled=" + enabled()
                + " network=" + (networkAvailable() ? "online" : "offline"));
    }
}
