/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.ResourceUtils;
import app.morphe.extension.shared.utils.Utils;

/**
 * Green "downloaded" checks for the stream copies of Play downloads locally: a small check next to every song of a
 * list that has a stream copy, and one on the Download button of a playlist or album page whose every track has
 * one. A song that was only saved as a local file has no stream copy and no check. The checks are our own views,
 * driven by an in-memory {@link DownloadedRegistry} of the stream copies: nothing is read from disk or MediaStore
 * while a row or header binds.
 */
@SuppressWarnings("unused")
public final class DownloadedMarkers {
    private static final String TRACKS_PREFERENCES = "rvx_native_playlist_tracks";
    private static final long REVALIDATE_INTERVAL_MILLIS = 5_000;
    private static final int ROW_CHECK_DP = 16;
    private static final int ROW_CHECK_MARGIN_DP = 4;

    private static final DownloadedRegistry registry = new DownloadedRegistry();
    private static final AtomicBoolean loadStarted = new AtomicBoolean();
    private static final CoalescedRunner refresh = new CoalescedRunner(Utils::runOnMainThread,
            DownloadedMarkers::refreshViews);
    private static final AtomicBoolean revalidating = new AtomicBoolean();
    private static volatile boolean loaded;
    private static volatile long lastRevalidation;
    static int badgesContainerId;
    private static final DownloadedRowLog rowLog = new DownloadedRowLog();

    // Main thread only. Values hold weak references: a value must never keep its key's view tree alive.
    private static final Map<View, RowState> rows = new WeakHashMap<>();
    private static final Map<ViewGroup, DownloadedHeaders.State> headers = new WeakHashMap<>();

    /** What is known of a list row's view; the model is only weakly held, the view tree owns the check. */
    static final class RowState {
        static final RowState NOT_A_ROW = new RowState();

        WeakReference<Object> model = new WeakReference<>(null);
        // One check per subtitle line of the row; a card row has one line per layout variant (see badgeLines).
        final List<WeakReference<DownloadedCheck>> checks = new ArrayList<>();
        String lastOutcome = "";

        List<DownloadedCheck> liveChecks() {
            List<DownloadedCheck> live = new ArrayList<>(checks.size());
            for (WeakReference<DownloadedCheck> reference : checks) {
                DownloadedCheck check = reference.get();
                if (check != null && check.getParent() != null) live.add(check);
            }
            return live;
        }
    }

    private DownloadedMarkers() {}

    static boolean enabled() {
        return StreamSwap.enabled() && Settings.DOWNLOADED_CHECKMARKS.get();
    }

    static int registrySize() {
        return registry.trackCount();
    }

    static boolean isLoaded() {
        return loaded;
    }

    static boolean isSourceDownloaded(@Nullable String sourceId) {
        return loaded && registry.isSourceDownloaded(sourceId);
    }

    /** Injection point: the final bind of every list row presenter, with its root view and its model. */
    public static void onRowBound(View view, Object model) {
        try {
            RowState state = rows.get(view);
            if (state == null) {
                state = hasBadgesContainer(view) ? new RowState() : RowState.NOT_A_ROW;
                rows.put(view, state);
            }
            rowLog.bound(view, model, state == RowState.NOT_A_ROW);
            if (state == RowState.NOT_A_ROW) return;

            state.model = new WeakReference<>(model);
            if (enabled()) ensureLoaded();
            applyRow(view, state, true);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not mark a downloaded song", ex);
        }
    }

    /** Injection point: the header of a playlist or album page, after its elements were added to the container. */
    public static void onHeaderBound(ViewGroup container, Object model) {
        try {
            DownloadedHeaders.State state = headers.get(container);
            if (state == null) {
                state = new DownloadedHeaders.State();
                headers.put(container, state);
            }
            boolean enabled = enabled();
            state.sourceIds = enabled && model instanceof MessageLite lite
                    ? HeaderPlaylistId.findAll(lite.toByteArray()) : Collections.emptyList();
            state.host = new WeakReference<>(null);
            if (enabled) {
                ensureLoaded();
                revalidateLater();
            }
            DownloadedHeaders.apply(container, state);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not mark a downloaded playlist", ex);
        }
    }

    private static boolean hasBadgesContainer(View row) {
        int id = badgesContainerId();
        return id != 0 && row.findViewById(id) != null;
    }

    private static int badgesContainerId() {
        if (badgesContainerId == 0) badgesContainerId = ResourceUtils.getIdIdentifier("subtitle_badges_container");
        return badgesContainerId;
    }

    /**
     * The subtitle line of every subtitle badges container in the row: the parent LinearLayout that holds the
     * container next to the subtitle text. A plain list row has one. A Search top-result card inflates all three of
     * its layout variants (default, thumbnail above, thumbnail to the side) and shows one, and each variant has
     * its own container with the same id, so findViewById alone could return the hidden variant's.
     * Lines are in view order and never repeat.
     */
    private static List<ViewGroup> badgeLines(View row) {
        List<ViewGroup> lines = new ArrayList<>(1);
        int id = badgesContainerId();
        if (id == 0) return lines;

        List<View> pending = new ArrayList<>();
        pending.add(row);
        while (!pending.isEmpty()) {
            View next = pending.remove(pending.size() - 1);
            if (next.getId() == id && next.getParent() instanceof ViewGroup line && !lines.contains(line)) {
                lines.add(line);
            }
            if (next instanceof ViewGroup group) {
                for (int i = group.getChildCount() - 1; i >= 0; i--) pending.add(group.getChildAt(i));
            }
        }
        return lines;
    }

    private static void applyRow(View view, RowState state, boolean binding) {
        Object model = state.model.get();
        byte[] bytes = null;
        String videoId = null;
        String reason;
        if (!enabled()) {
            reason = "setting-off";
        } else if (model == null) {
            reason = "model-gone";
        } else if (!registry.hasTracks()) {
            reason = "registry-empty";
        } else if ((bytes = DownloadedModels.serialize(model)) == null) {
            reason = "model-has-no-proto";
        } else {
            videoId = registry.findDownloaded(bytes);
            if (videoId == null) videoId = registry.findDownloadedLoose(bytes);
            reason = videoId == null ? "id-not-downloaded" : "downloaded";
        }

        String outcome;
        if (videoId == null) {
            for (DownloadedCheck check : state.liveChecks()) check.setShown(false);
            outcome = "hidden";
        } else {
            outcome = showRowChecks(view, state);
        }
        rowLog.decided(view, state, model, bytes, registry, reason, videoId, outcome, binding);
    }

    /** Adds or shows the checks in the row's subtitle lines; returns what happened, for the log. */
    private static String showRowChecks(View view, RowState state) {
        List<DownloadedCheck> checks = state.liveChecks();
        String outcome = "shown";
        if (checks.isEmpty()) {
            outcome = addRowChecks(view, state);
            if (!outcome.equals("added")) return outcome;

            checks = state.liveChecks();
        }
        for (DownloadedCheck check : checks) {
            check.restyle();
            check.setShown(true);
        }
        if (outcome.equals("added")) rowLog.afterLayout(checks);
        return outcome;
    }

    private static String addRowChecks(View view, RowState state) {
        List<ViewGroup> lines = badgeLines(view);
        if (lines.isEmpty()) return "no-container";

        state.checks.clear();
        boolean linear = false;
        for (ViewGroup line : lines) {
            if (!(line instanceof LinearLayout)) continue;

            linear = true;
            DownloadedCheck check = DownloadedCheck.create(view.getContext(), false);
            if (check == null) return "no-icon";

            int size = DownloadedCheck.dp(ROW_CHECK_DP);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.setMarginEnd(DownloadedCheck.dp(ROW_CHECK_MARGIN_DP));
            line.addView(check, 0, params);
            state.checks.add(new WeakReference<>(check));
        }
        return linear ? "added" : "container-parent-not-linear";
    }

    /** Called when a stream copy was kept: the track has one from now on. */
    static void onStreamCopyKept(String videoId) {
        registry.add(videoId);
        refreshLater();
    }

    /** Called when a track's stream copy was deleted. */
    static void onStreamCopyRemoved(String videoId) {
        registry.remove(videoId);
        refreshLater();
    }

    /** Called when every stream copy was deleted: no row or header keeps its check. */
    static void onStreamCopiesCleared() {
        registry.clear();
        refreshLater();
    }

    /**
     * Called when a whole playlist or album was downloaded with stream copies. Only the track list is remembered:
     * its tracks count as downloaded through their own stream copies, so a playlist whose tracks have none (saved
     * as local files only, or whose copies were deleted) never shows a check.
     */
    static void onPlaylistSaved(String sourceId, List<String> videoIds) {
        rememberSource(sourceId, videoIds);
        refreshLater();
    }

    private static SharedPreferences trackPreferences() {
        return Utils.getContext().getSharedPreferences(TRACKS_PREFERENCES, 0);
    }

    private static void rememberSource(String sourceId, List<String> videoIds) {
        registry.putSource(sourceId, videoIds);
        if (!trackPreferences().edit().putString(sourceId, DownloadedRegistry.encode(videoIds)).commit()) {
            Logger.printException(() -> "Could not remember the tracks of a downloaded playlist");
        }
    }

    /** Fills the registry once, in the background. Until then no check is shown. */
    private static void ensureLoaded() {
        if (!loadStarted.compareAndSet(false, true)) return;

        Utils.submitOnBackgroundThread(() -> {
            try {
                registry.addAll(RawAudioStore.copyIds());
                for (Map.Entry<String, ?> entry : trackPreferences().getAll().entrySet()) {
                    if (entry.getValue() instanceof String encoded) {
                        registry.putSource(entry.getKey(), DownloadedRegistry.decode(encoded));
                    }
                }
                loaded = true;
                refreshLater();

                recoverEarlierPlaylists();
                rescanStreamCopies();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load the downloaded songs", ex);
            }
            return null;
        });
    }

    /**
     * Playlists downloaded before the track lists were remembered: read their tracks back from the native
     * playlist database. A playlist with a track the app did not download keeps no check, and neither does one
     * whose tracks have no stream copy: a remembered playlist is checked through the stream copies of its tracks.
     */
    private static void recoverEarlierPlaylists() {
        Map<Long, List<Long>> nativeTracks = null;
        Map<Long, String> videoIdByMediaId = null;
        for (Map.Entry<String, Long> saved : NativePlaylistStore.savedPlaylists().entrySet()) {
            if (registry.hasSource(saved.getKey())) continue;

            if (nativeTracks == null) {
                nativeTracks = NativePlaylistStore.readPlaylistTracks();
                videoIdByMediaId = NativeAudioStore.videoIdsByMediaId();
            }
            List<Long> mediaIds = nativeTracks.get(saved.getValue());
            List<String> videoIds = mediaIds == null ? null
                    : DownloadedRegistry.resolveTracks(mediaIds, videoIdByMediaId);
            if (videoIds != null) rememberSource(saved.getKey(), videoIds);
        }
        refreshLater();
    }

    /** Checks in the background that the stream copies still exist; at most once in a while. */
    private static void revalidateLater() {
        long now = System.currentTimeMillis();
        if (!loaded || now - lastRevalidation < REVALIDATE_INTERVAL_MILLIS
                || !revalidating.compareAndSet(false, true)) return;

        lastRevalidation = now;
        Utils.submitOnBackgroundThread(() -> {
            try {
                rescanStreamCopies();
            } finally {
                revalidating.set(false);
            }
            return null;
        });
    }

    /**
     * Forgets the stream copies that vanished from the store (copies tied to a deleted local file, a cleanup).
     * The known set is taken before the scan, so a copy kept while the scan runs is never forgotten.
     */
    static void rescanStreamCopies() {
        Set<String> knownBefore = registry.snapshot();
        Set<String> present = RawAudioStore.copyIds();

        registry.prune(knownBefore, present);
        refreshLater();
    }

    /** Re-evaluates every known row and header on the main thread; requests made while queued are merged. */
    private static void refreshLater() {
        refresh.request();
    }

    private static void refreshViews() {
        for (Map.Entry<View, RowState> row : new ArrayList<>(rows.entrySet())) {
            View view = row.getKey();
            if (view == null || row.getValue() == RowState.NOT_A_ROW) continue;

            try {
                applyRow(view, row.getValue(), false);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not refresh a downloaded song", ex);
            }
        }
        for (Map.Entry<ViewGroup, DownloadedHeaders.State> header : new ArrayList<>(headers.entrySet())) {
            ViewGroup container = header.getKey();
            if (container != null) DownloadedHeaders.apply(container, header.getValue());
        }
    }
}
