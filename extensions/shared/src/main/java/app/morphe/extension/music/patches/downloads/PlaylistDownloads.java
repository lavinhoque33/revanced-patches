/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-18.
 * Modified by lavinhoque33, 2026-11-07.
 * Modified by lavinhoque33, 2026-11-10
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import app.morphe.extension.music.patches.misc.requests.MusicBrowseRequest;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Downloads a playlist or album, keeping interrupted Stream downloads available to resume. */
public final class PlaylistDownloads {
    private static final ConcurrentHashMap<String, Run> active = new ConcurrentHashMap<>();
    private static final ThreadLocal<Run> streamWorker = new ThreadLocal<>();

    private static final class Run {
        final DownloadMode mode;
        volatile boolean removed;

        Run(DownloadMode mode) {
            this.mode = mode;
        }
    }

    private PlaylistDownloads() {}

    /**
     * The header Download of a playlist or album: a stream download that is saved already offers Remove instead,
     * so the playlist is never saved twice, and while its download runs a tap says so ({@link HeaderDownloadState}).
     */
    static void onDownloadButton(String playlistId) {
        boolean streamCopy = LocalDownloadManager.downloadMode().streamCopy();
        boolean running = isRunning(playlistId);
        HeaderDownloadState.Tap tap = HeaderDownloadState.tapOf(streamCopy,
                streamCopy && DownloadedMarkers.isSourceDownloaded(playlistId), running);
        Logger.printDebug(() -> "PlaylistDownloads: header Download of " + playlistId + ", tap=" + tap);
        switch (tap) {
            case REMOVE -> DownloadRemoval.confirmSource(playlistId);
            case IN_PROGRESS -> Utils.showToastShort(str("morphe_music_downloads_already_running"));
            case DOWNLOAD -> enqueue(playlistId);
        }
    }

    /** Whether any list is being downloaded. */
    static boolean anyRunning() {
        return !active.isEmpty();
    }

    /** Whether a download of the list is running. */
    static boolean isRunning(@Nullable String playlistId) {
        return playlistId != null && active.containsKey(playlistId);
    }

    /** The mode of an active run takes priority over a setting changed since it started. */
    static boolean showsStreamProgress(String playlistId) {
        Run run = active.get(playlistId);
        return run != null ? run.mode.streamCopy() : LocalDownloadManager.downloadMode().streamCopy();
    }

    static void resume(String playlistId) {
        if (DownloadedMarkers.isPlaylistPending(playlistId) && LocalDownloadManager.downloadMode().streamCopy()) {
            enqueue(playlistId, DownloadMode.STREAM_COPY);
        }
    }

    /** Called under the source mutation lock; an explicit Remove must win over an outstanding worker. */
    static void onSourceRemoved(String playlistId) {
        Run run = active.get(playlistId);
        if (run != null && run.mode.streamCopy()) run.removed = true;
    }

    static <T> T publishMedia(Callable<T> write) throws Exception {
        Run run = streamWorker.get();
        if (run == null) return PlaylistSync.publishMedia(write);

        synchronized (PlaylistSync.MUTATION) {
            if (run.removed) throw new IOException("Playlist download was removed");
            return write.call();
        }
    }

    public static void enqueue(String playlistId) {
        enqueue(playlistId, LocalDownloadManager.downloadMode());
    }

    private static void enqueue(String playlistId, DownloadMode mode) {
        // Decided once for the whole playlist, even if the setting changes while the worker runs.
        DownloadQuality quality = Settings.DOWNLOAD_QUALITY.get();
        if (mode == DownloadMode.OFF) return;
        if (mode.localFiles() && !NativeDownloads.ensureStoragePermission()) return;
        Run run = new Run(mode);
        if (active.putIfAbsent(playlistId, run) != null) {
            Utils.showToastShort(str("morphe_music_downloads_already_running"));
            return;
        }
        if (mode.streamCopy() && !Utils.isInternetReachable()) {
            active.remove(playlistId, run);
            Utils.showToastShort(str("morphe_music_playlist_download_connect"));
            return;
        }
        if (mode.streamCopy()) DownloadedMarkers.onPlaylistActivityChanged();

        Utils.showToastShort(str("morphe_music_playlist_download_started"));
        Utils.submitOnBackgroundThread(() -> {
            String title = playlistId;
            try {
                if (mode.localFiles()) NativeDownloads.migrateLegacy();

                String browseId = "VL" + playlistId;
                JSONObject first = MusicBrowseRequest.browse(Utils.getContext(), browseId, null, null);
                SourceHeader header = header(playlistId, first);
                if (!header.hasTitle()) throw new IOException("Playlist title is unavailable");
                title = header.title();

                List<String> tracks = PlaylistPage.readPlaylist(mode.streamCopy() ? playlistId : null, first,
                        token -> MusicBrowseRequest.browse(Utils.getContext(), browseId, token, null));
                List<String> available = PlaylistPage.available(tracks);
                int skipped = tracks.size() - available.size();
                if (available.isEmpty()) throw new IOException("Playlist contains no downloadable tracks");
                if (skipped > 0) Logger.printDebug(() -> "PlaylistDownloads: " + playlistId + " skips " + skipped
                        + " unavailable of " + tracks.size() + " tracks");

                if (mode.streamCopy()) {
                    downloadStream(playlistId, header, tracks, available, skipped, quality, run);
                } else {
                    downloadDeviceFiles(playlistId, title, available, skipped, quality, mode);
                }
            } catch (Exception ex) {
                if (run.removed) return null;

                Logger.printException(() -> "Playlist download failed", ex);
                // Completed songs remain playable, but never label a truncated playlist complete.
                LocalDownloadManager.awaitNotificationSlot();
                String failed = str("morphe_music_playlist_download_failed", PlaylistText.failureReason(ex));
                LocalDownloadManager.showDownloadNotification("playlist:" + playlistId, title, 0, false, failed);
                Utils.showToastLong(failed);
            } finally {
                active.remove(playlistId, run);
                if (mode.streamCopy()) DownloadedMarkers.onPlaylistFinished();
                // tracks now in a playlist leave "Not in a playlist";
                // songs kept from a failed download join it.
                if (mode.localFiles()) NativePlaylistStore.refreshUnsortedLater();
            }
            return null;
        });
    }

    private static void downloadDeviceFiles(String playlistId, String title, List<String> tracks,
                                            int skipped, DownloadQuality quality, DownloadMode mode) throws Exception {
        List<Long> mediaIds = new ArrayList<>(tracks.size());
        for (int index = 0; index < tracks.size(); index++) {
            if (LocalDownloadManager.canPostProgress()) {
                LocalDownloadManager.showDownloadNotification("playlist:" + playlistId, title,
                        index * 100 / tracks.size(), true,
                        str("morphe_music_playlist_download_progress", index + 1, tracks.size()));
            }
            LocalDownloadManager.Saved saved = LocalDownloadManager.download(tracks.get(index), mode, quality);
            mediaIds.add(NativeAudioStore.mediaId(saved.nativeTrack()));
        }
        NativePlaylistStore.save(playlistId, title, mediaIds);
        reportComplete(playlistId, title, completionText(mode, 0, tracks.size(), skipped,
                VideoCopyDownloader.Outcome.NONE));
    }

    private static void downloadStream(String playlistId, SourceHeader header, List<String> tracks,
                                       List<String> available, int skipped, DownloadQuality quality, Run run)
            throws Exception {
        synchronized (PlaylistSync.MUTATION) {
            if (run.removed) return;
            DownloadedMarkers.onPlaylistStarted(playlistId, tracks, header);
        }

        UnaryOperator<String> canonical = streamCopyIds();
        Predicate<String> ready = PlaylistDownloads::offlineReady;
        Set<String> failures = new HashSet<>();
        String title = header.title();
        streamWorker.set(run);
        try {
            PlaylistTransfer.Outcome transfer = PlaylistTransfer.fill(available, canonical, ready, () -> canTransfer(run),
                    id -> ensureStreamTrack(id, playlistId, title, quality), (id, ex) -> {
                        if (run.removed) return;
                        failures.add(id);
                        Logger.printDebug(() -> "PlaylistDownloads: list=" + playlistId + " track=" + id + " failed", ex);
                    });
            if (run.removed) return;

            VideoCopyDownloader.Outcome videos = transfer.allReady() && canTransfer(run)
                    ? VideoCopyDownloader.ensure(playlistId, title, available, quality, canonical,
                            () -> canTransfer(run))
                    : VideoCopyDownloader.Outcome.NONE;
            PlaylistTransfer.Progress result;
            boolean completed;
            synchronized (PlaylistSync.MUTATION) {
                if (run.removed) return;
                // A song or all copies may have been explicitly deleted during the transfers.
                UnaryOperator<String> currentCopyId = streamCopyIds();
                var readyCopies = new HashMap<String, Boolean>();
                result = PlaylistTransfer.progress(available,
                        id -> readyCopies.computeIfAbsent(currentCopyId.apply(id), PlaylistDownloads::offlineReady));
                completed = result.complete() && !Thread.currentThread().isInterrupted();
                if (completed) DownloadedMarkers.onPlaylistCompleted(playlistId);
            }
            if (completed) {
                reportComplete(playlistId, title, completionText(run.mode, transfer.downloaded(), result.total(),
                        skipped, videos));
            } else {
                int failed = 0;
                for (String id : available) if (failures.contains(canonical.apply(id))) failed++;
                reportPartial(playlistId, title, result, Math.min(failed, result.total() - result.ready()));
            }
        } finally {
            streamWorker.remove();
        }
    }

    private static UnaryOperator<String> streamCopyIds() {
        Set<String> own = RawAudioStore.copyIds();
        var aliases = RawAudioStore.aliases();
        return id -> {
            String copyId = OfflineCopyStore.copyId(id, own, aliases);
            return !id.equals(copyId) && RawAudioStore.find(copyId) == null ? id : copyId;
        };
    }

    private static boolean canTransfer(Run run) {
        return !run.removed && !Thread.currentThread().isInterrupted() && Utils.isInternetReachable();
    }

    private static boolean offlineReady(String copyId) {
        return OfflineCopyStore.metadataOf(copyId) != null && RawAudioStore.find(copyId) != null;
    }

    private static boolean ensureStreamTrack(String id, String sourceId, String title, DownloadQuality quality)
            throws Exception {
        if (LocalDownloadManager.canPostProgress()) {
            PlaylistTransfer.Progress progress = DownloadedMarkers.playlistProgress(sourceId);
            if (progress != null) {
                LocalDownloadManager.showDownloadNotification("playlist:" + sourceId, title,
                        progress.ready() * 100 / progress.total(), true, progressText(progress, false));
            }
        }

        return PlaylistTransfer.prepare(id, key -> RawAudioStore.find(key) != null,
                key -> OfflineCopyStore.metadataOf(key) != null,
                key -> LocalDownloadManager.download(key, DownloadMode.STREAM_COPY, quality).downloaded(),
                key -> OfflineBackfill.fetchAndStore(key, true));
    }

    static String progressText(PlaylistTransfer.Progress progress, boolean resume) {
        return str(resume ? "morphe_music_playlist_resume" : "morphe_music_playlist_ready",
                progress.ready(), progress.total());
    }

    private static void reportComplete(String playlistId, String title, String text) {
        LocalDownloadManager.awaitNotificationSlot();
        LocalDownloadManager.showDownloadNotification("playlist:" + playlistId, title, 100, false, text);
        Utils.showToastShort(text);
    }

    private static void reportPartial(String playlistId, String title, PlaylistTransfer.Progress progress, int failed) {
        String text = failed == 0 ? progressText(progress, true)
                : str("morphe_music_playlist_partial_failed", progress.ready(), progress.total(), failed);
        Logger.printDebug(() -> "PlaylistDownloads: pending " + playlistId + " ready=" + progress.ready()
                + " total=" + progress.total() + " failed=" + failed);
        LocalDownloadManager.awaitNotificationSlot();
        LocalDownloadManager.showDownloadNotification("playlist:" + playlistId, title, 0, false, text);
        Utils.showToastLong(text);
    }

    /**
     * The text of a finished playlist download: {@code total} tracks were available and {@code skipped} were not. A
     * stream-only download that found every copy already saved, audio and videos, says so, because nothing was
     * downloaded. The videos the list has are counted; videos that failed are said last.
     */
    static String completionText(DownloadMode mode, int downloaded, int total, int skipped,
                                 VideoCopyDownloader.Outcome videos) {
        String done;
        if (mode.localFiles()) {
            done = str("morphe_music_playlist_download_complete", total);
        } else {
            String key = VideoCopyLogic.completionKey(downloaded, videos.saved(), videos.videos());
            done = videos.videos() > 0 ? str(key, total, videos.videos()) : str(key, total);
        }
        if (mode.streamCopy() && videos.failed() > 0) {
            done += " (" + str("morphe_music_playlist_videos_failed", videos.failed()) + ")";
        }
        return skipped == 0 ? done : done + " (" + str("morphe_music_playlist_unavailable_skipped", skipped) + ")";
    }

    /** The header of a playlist or album, read off the network. For use on a worker thread. */
    static SourceHeader fetchHeader(String playlistId) throws Exception {
        return header(playlistId, MusicBrowseRequest.browse(Utils.getContext(), "VL" + playlistId, null, null));
    }

    private static SourceHeader header(String playlistId, JSONObject response) throws Exception {
        SourceHeader header = SourceHeader.read(response);
        return header.hasTitle() ? header : albumHeader(playlistId, response);
    }

    /**
     * Album playlists (OLAK…) have no header in their playlist response. Every row links to
     * the album page instead; use that page's header only if all rows agree on one album and
     * the album page itself points back at this playlist.
     */
    private static SourceHeader albumHeader(String playlistId, JSONObject response) throws Exception {
        Set<String> albums = new HashSet<>();
        collectAlbums(response, albums);
        if (albums.size() != 1) return SourceHeader.EMPTY;
        JSONObject album = MusicBrowseRequest.browse(Utils.getContext(), albums.iterator().next(), null, null);
        return album.toString().contains("\"" + playlistId + "\"") ? SourceHeader.read(album) : SourceHeader.EMPTY;
    }

    private static void collectAlbums(Object node, Set<String> albums) {
        if (node instanceof JSONObject object) {
            JSONObject endpoint = object.optJSONObject("browseEndpoint");
            String id = endpoint == null ? "" : endpoint.optString("browseId");
            if (id.startsWith("MPREb_")) albums.add(id);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectAlbums(object.opt(keys.next()), albums);
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collectAlbums(array.opt(i), albums);
        }
    }
}
