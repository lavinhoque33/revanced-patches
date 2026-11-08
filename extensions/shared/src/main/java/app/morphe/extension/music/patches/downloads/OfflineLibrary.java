/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.Decision;
import app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SongRow;
import app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SourceRow;
import app.morphe.extension.music.patches.downloads.OfflineLibraryScreen.Item;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Library > Downloads for the stream copies: the albums and playlists that were downloaded, and every song, played
 * from their stored data with or without a network. It replaces the Downloads page, which has no entry for a
 * stream copy, wherever the app navigates to it (the Library tab while offline, the account menu, the offline
 * banner). Tapping a song starts it through {@link OfflineReplay}, which picks the online or the offline command.
 * All store reads and cover decoding run in the background.
 */
final class OfflineLibrary {
    /** The list on screen, if any: a second navigation to the Downloads page must not open another. */
    private static volatile OfflineLibraryScreen shown;
    private final boolean deviceFilesRow;
    private OfflineLibraryScreen screen;
    /** The home view of the last load, shown again when a source view goes back. */
    private List<Item> home = List.of();
    private boolean inSource;
    /** Changes with every view; a load that finishes for an older one is dropped. */
    private int view;

    private OfflineLibrary(boolean deviceFilesRow) {
        this.deviceFilesRow = deviceFilesRow;
    }

    /**
     * Called when the Library page loads one of its chips, with the browse id the chip resolved to, on the main
     * thread. The Downloads chip is no navigation, so {@link #onDownloadsBrowse} never sees it: the list opens in
     * front of the page's own (empty) Downloads, which loads behind it.
     */
    static void onLibraryChip(String browseId) {
        if (OfflineLibraryLogic.isDownloadsChip(browseId)) onDownloadsBrowse(browseId);
    }

    /**
     * Called for a browse command to a Downloads page, on the main thread.
     *
     * @return true when the list was opened and the navigation is to be dropped; false leaves it as it is.
     */
    static boolean onDownloadsBrowse(String browseId) {
        try {
            Decision decision = OfflineLibraryLogic.decide(browseId, OfflineReplay.enabled(),
                    OfflineCopyStore.hasAnything(), Settings.IN_APP_DOWNLOADS.get());
            Activity activity = Utils.getActivity();
            if (decision == Decision.STOCK || activity == null || activity.isFinishing()) {
                Logger.printDebug(() -> "OfflineLibrary: stock browse=" + browseId + " decision=" + decision
                        + " activity=" + (activity != null));
                return false;
            }

            OfflineLibraryScreen open = shown;
            if (open != null && open.isShowing()) {
                Logger.printDebug(() -> "OfflineLibrary: already open, browse=" + browseId + " taken");
                return true;
            }

            Logger.printInfo(() -> "OfflineLibrary: open browse=" + browseId + " decision=" + decision);
            new OfflineLibrary(decision == Decision.OPEN_WITH_DEVICE_FILES).open(activity);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "OfflineLibrary: could not open the Downloads list", ex);
            return false;
        }
    }

    private void open(Activity activity) {
        screen = new OfflineLibraryScreen(activity, this::onBack);
        shown = screen;
        screen.setContent(str("morphe_music_offline_library_title"), List.of());
        screen.show();
        loadHome();
    }

    private void onBack() {
        if (!inSource) {
            close();
            return;
        }

        view++;
        inSource = false;
        screen.setContent(str("morphe_music_offline_library_title"), home);
    }

    private void close() {
        view++;
        screen.dismiss();
    }

    // region Home view

    private void loadHome() {
        int requested = ++view;
        Utils.runOnBackgroundThread(() -> {
            List<SourceRow> sources = List.of();
            List<SongRow> songs = List.of();
            try {
                sources = OfflineLibraryLogic.sourceRows(OfflineCopyStore.sources(), OfflineCopyStore::tracksOf);
                songs = OfflineLibraryLogic.songRows(OfflineCopyStore.copiesWithData(), RawAudioStore.copyIds(),
                        queueIds(OfflineReplay.SONGS));
            } catch (RuntimeException ex) {
                Logger.printException(() -> "OfflineLibrary: could not read the stream copies", ex);
            }

            List<SourceRow> loadedSources = sources;
            List<SongRow> loadedSongs = songs;
            Utils.runOnMainThread(() -> {
                if (requested == view) showHome(loadedSources, loadedSongs);
            });
        });
    }

    private void showHome(List<SourceRow> sources, List<SongRow> songs) {
        List<Item> items = new ArrayList<>();
        if (!sources.isEmpty()) {
            items.add(Item.header(str("morphe_music_offline_library_albums")));
            for (SourceRow source : sources) {
                items.add(Item.entry(sourceTitle(source), songCountText(source.songCount()),
                        source.coverVideoId(), false, () -> openSource(source)));
            }
        }
        if (!songs.isEmpty()) {
            items.add(Item.header(str("morphe_music_offline_library_songs")));
            for (SongRow song : songs) items.add(songItem(OfflineReplay.SONGS, song));
        }
        if (sources.isEmpty() && songs.isEmpty()) {
            items.add(Item.plain(str("morphe_music_offline_library_empty"), true, null));
        }
        if (deviceFilesRow) {
            items.add(Item.plain(str("morphe_music_offline_library_device_files"), false, this::openDeviceFiles));
        }

        home = items;
        inSource = false;
        screen.setContent(str("morphe_music_offline_library_title"), items);
    }

    // endregion

    // region Source view

    private void openSource(SourceRow source) {
        int requested = ++view;
        inSource = true;
        Utils.runOnBackgroundThread(() -> {
            List<SongRow> tracks = List.of();
            try {
                tracks = OfflineLibraryLogic.trackRows(OfflineCopyStore.tracksOf(source.sourceId()),
                        queueIds(source.sourceId()));
            } catch (RuntimeException ex) {
                Logger.printException(() -> "OfflineLibrary: could not read " + source.sourceId(), ex);
            }

            List<SongRow> loaded = tracks;
            Utils.runOnMainThread(() -> {
                if (requested == view) showSource(source, loaded);
            });
        });
    }

    private void showSource(SourceRow source, List<SongRow> tracks) {
        List<Item> items = new ArrayList<>();
        for (SongRow track : tracks) items.add(songItem(source.sourceId(), track));
        screen.setContent(sourceTitle(source), items);
    }

    // endregion

    private Item songItem(String sourceId, SongRow song) {
        if (song.availableOffline()) {
            return Item.entry(song.title(), OfflineLibraryLogic.songSubtitle(song.artist(), song.durationMs()),
                    song.videoId(), false,
                    () -> onSongTap(sourceId, song));
        }

        return Item.entry(str("morphe_music_offline_library_unavailable_title"),
                str("morphe_music_offline_library_unavailable"), null, true, () -> onSongTap(sourceId, song));
    }

    private void onSongTap(String sourceId, SongRow song) {
        boolean online = OfflineReplay.networkAvailable();
        switch (OfflineLibraryLogic.tapOn(song, online)) {
            case PLAY_QUEUE -> finishStart(OfflineReplay.play(sourceId, song.queueIndex()));
            case PLAY_ONLINE -> finishStart(
                    OfflineReplay.dispatchCommand(OnlineWatchCommand.build(song.videoId(), null, 0)));
            case EXPLAIN -> Utils.showToastShort(str("morphe_music_offline_library_unavailable_toast"));
        }
    }

    private void openDeviceFiles() {
        finishStart(OfflineReplay.dispatchCommand(
                OfflineLibraryLogic.browseCommand(OfflineLibraryLogic.DEVICE_FILES_BROWSE_ID)));
    }

    /** The list is closed once what the tap asked for has been handed to the app; otherwise it stays and says why. */
    private void finishStart(boolean handedOver) {
        if (handedOver) {
            close();
        } else {
            Utils.showToastShort(str("morphe_music_offline_library_not_ready"));
        }
    }

    /** The video ids of the offline queue of a list, in play order. Reads the store: not for the main thread. */
    private static List<String> queueIds(String sourceId) {
        List<String> ids = new ArrayList<>();
        for (OfflineWatchNext.Item item : OfflineReplay.queueOf(sourceId)) ids.add(item.videoId());
        return ids;
    }

    private static String sourceTitle(SourceRow source) {
        return source.title().isEmpty() ? str("morphe_music_offline_library_untitled") : source.title();
    }

    private static String songCountText(int count) {
        return count == 1
                ? str("morphe_music_offline_library_song_one")
                : str("morphe_music_offline_library_songs_count", count);
    }
}
