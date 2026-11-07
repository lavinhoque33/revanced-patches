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
 * Green "downloaded" checks for what the in-app downloader saved: a small check next to every downloaded song
 * of a list, and one on the Download button of a playlist or album page whose every track is downloaded.
 * The checks are our own views, driven by an in-memory {@link DownloadedRegistry}: nothing is read from disk
 * or MediaStore while a row or header binds.
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
    private static int badgesContainerId = -1;

    // Main thread only. Values hold weak references: a value must never keep its key's view tree alive.
    private static final Map<View, RowState> rows = new WeakHashMap<>();
    private static final Map<ViewGroup, DownloadedHeaders.State> headers = new WeakHashMap<>();

    private static final class RowState {
        static final RowState NOT_A_ROW = new RowState();

        WeakReference<Object> model = new WeakReference<>(null);
        WeakReference<DownloadedCheck> check = new WeakReference<>(null);
    }

    private DownloadedMarkers() {}

    static boolean enabled() {
        return Settings.IN_APP_DOWNLOADS.get() && Settings.DOWNLOADED_CHECKMARKS.get();
    }

    static boolean isSourceDownloaded(@Nullable String sourceId) {
        return loaded && registry.isSourceDownloaded(sourceId);
    }

    /** Injection point: the final bind of every list row presenter, with its root view and its model. */
    public static void onRowBound(View view, Object model) {
        try {
            RowState state = rows.get(view);
            if (state == null) {
                state = view.findViewById(badgesContainerId()) == null ? RowState.NOT_A_ROW : new RowState();
                rows.put(view, state);
            }
            if (state == RowState.NOT_A_ROW) return;

            state.model = new WeakReference<>(model);
            if (enabled()) ensureLoaded();
            applyRow(view, state);
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

    private static int badgesContainerId() {
        if (badgesContainerId == -1) badgesContainerId = ResourceUtils.getIdIdentifier("subtitle_badges_container");
        return badgesContainerId;
    }

    private static void applyRow(View view, RowState state) {
        Object model = state.model.get();
        boolean show = enabled() && model instanceof MessageLite lite && registry.hasTracks()
                && registry.findDownloaded(lite.toByteArray()) != null;
        DownloadedCheck check = state.check.get();
        if (!show) {
            if (check != null) check.setShown(false);
            return;
        }

        ViewGroup badges = view.findViewById(badgesContainerId());
        if (badges == null || !(badges.getParent() instanceof LinearLayout line)) return;

        if (check == null || check.getParent() != line) {
            check = DownloadedCheck.create(view.getContext(), false);
            if (check == null) return;

            int size = DownloadedCheck.dp(ROW_CHECK_DP);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.gravity = Gravity.CENTER_VERTICAL;
            params.setMarginEnd(DownloadedCheck.dp(ROW_CHECK_MARGIN_DP));
            line.addView(check, 0, params);
            state.check = new WeakReference<>(check);
        }
        check.restyle();
        check.setShown(true);
    }

    /** Called when a download finished: the track is downloaded from now on. */
    static void onTrackDownloaded(String videoId) {
        registry.add(videoId);
        refreshLater();
    }

    /** Called when the track's audio is confirmed to be gone. */
    static void onTrackForgotten(String videoId) {
        registry.remove(videoId);
        refreshLater();
    }

    /** Called when a whole playlist or album was downloaded and published as a native playlist. */
    static void onPlaylistSaved(String sourceId, List<String> videoIds) {
        registry.addAll(videoIds);
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
                registry.addAll(NativeAudioStore.rememberedVideoIds());
                for (Map.Entry<String, ?> entry : trackPreferences().getAll().entrySet()) {
                    if (entry.getValue() instanceof String encoded) {
                        registry.putSource(entry.getKey(), DownloadedRegistry.decode(encoded));
                    }
                }
                loaded = true;
                refreshLater();

                recoverEarlierPlaylists();
                revalidate();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load the downloaded songs", ex);
            }
            return null;
        });
    }

    /**
     * Playlists downloaded before the track lists were remembered: read their tracks back from the native
     * playlist database. A playlist with a track the app did not download keeps no check.
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

    /** Checks in the background that the remembered downloads still exist; at most once in a while. */
    private static void revalidateLater() {
        long now = System.currentTimeMillis();
        if (!loaded || now - lastRevalidation < REVALIDATE_INTERVAL_MILLIS
                || !revalidating.compareAndSet(false, true)) return;

        lastRevalidation = now;
        Utils.submitOnBackgroundThread(() -> {
            try {
                revalidate();
            } finally {
                revalidating.set(false);
            }
            return null;
        });
    }

    private static void revalidate() {
        Set<String> knownBefore = registry.snapshot();
        Set<String> existing = NativeAudioStore.existingVideoIds();
        if (existing == null) return;

        registry.prune(knownBefore, existing);
        refreshLater();
    }

    /** Re-evaluates every known row and header on the main thread; requests made while queued are merged. */
    private static void refreshLater() {
        refresh.request();
    }

    private static void refreshViews() {
        for (Map.Entry<View, RowState> row : new ArrayList<>(rows.entrySet())) {
            View view = row.getKey();
            if (view != null && row.getValue() != RowState.NOT_A_ROW) applyRow(view, row.getValue());
        }
        for (Map.Entry<ViewGroup, DownloadedHeaders.State> header : new ArrayList<>(headers.entrySet())) {
            ViewGroup container = header.getKey();
            if (container != null) DownloadedHeaders.apply(container, header.getValue());
        }
    }
}
