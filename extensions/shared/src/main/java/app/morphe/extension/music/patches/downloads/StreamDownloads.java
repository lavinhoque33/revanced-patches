/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-19.
 * Modified by lavinhoque33, 2026-11-17.
 * Modified by lavinhoque33, 2026-11-12.
 * Written by lavinhoque33, 2026-11-07.
 * Modified by lavinhoque33, 2026-11-10
 */
package app.morphe.extension.music.patches.downloads;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Management of the private stream copies of Play downloads locally. They are invisible in Device files and the
 * library, so the settings page needs its own way to see what they take and to delete them. Both calls do disk
 * work and must be made off the main thread.
 */
public final class StreamDownloads {
    /**
     * How many stream copies exist and how many bytes of audio they hold, and how many of them also have a video
     * copy and the bytes of those.
     */
    public record Summary(int count, long bytes, int videos, long videoBytes) {
        public long totalBytes() {
            return bytes + videoBytes;
        }
    }

    /** How many of the stream copies have stored data, so can play with no network, out of all of them. */
    public record Readiness(int ready, int total) {}

    private StreamDownloads() {}

    /** Whether playback has a private audio/alias or playlist video copy. A worker thread only. */
    public static boolean hasStreamCopy(String videoId) throws Exception {
        return VideoCopyStore.find(videoId) != null || RawAudioStore.find(videoId) != null;
    }

    public static Summary summary() {
        RawAudioFiles.Stats stats = RawAudioStore.stats();
        VideoCopyFiles.Stats videos = VideoCopyStore.stats();
        return new Summary(stats.count(), stats.bytes(), videos.count(), videos.bytes());
    }

    /** The string that sums the stream downloads up: the videos are named only when there are some. */
    public static String summaryKey(Summary summary) {
        if (summary.count() == 0) return "morphe_music_stream_downloads_none";
        return summary.videos() > 0 ? "morphe_music_stream_downloads_summary_videos"
                : "morphe_music_stream_downloads_summary";
    }

    /** The string that asks whether to delete them. */
    public static String deleteMessageKey(Summary summary) {
        return summary.videos() > 0 ? "morphe_music_stream_downloads_delete_message_videos"
                : "morphe_music_stream_downloads_delete_message";
    }

    /** Megabytes with one decimal, as the summary and the delete question show them. */
    public static String megabytes(long bytes, Locale locale) {
        return String.format(locale, "%.1f", bytes / (1024.0 * 1024.0));
    }

    public static Readiness readiness() {
        return OfflineCopyStore.readiness();
    }

    private static final List<WeakReference<Runnable>> copyListeners = new CopyOnWriteArrayList<>();
    private static final CoalescedRunner copyChanges = new CoalescedRunner(Utils::runOnMainThread, () -> {
        for (WeakReference<Runnable> reference : copyListeners) {
            Runnable listener = reference.get();
            if (listener == null) copyListeners.remove(reference);
            else listener.run();
        }
    });

    public static void addCopyListener(Runnable listener) {
        copyListeners.add(new WeakReference<>(listener));
    }

    public static void removeCopyListener(Runnable listener) {
        copyListeners.removeIf(reference -> reference.get() == listener);
    }

    /** Tells visible settings rows about committed copy changes, on the main thread. */
    static void readinessChanged() {
        if (!copyListeners.isEmpty()) copyChanges.request();
    }

    /**
     * Deletes the stream copy of one song with everything stored with it (its video copy, its stored data), as the
     * song menu's Remove download does. A playlist or album that lists the song keeps its record and loses a copy.
     * Off the main thread.
     */
    public static void remove(String videoId) {
        List<String> affected = new ArrayList<>();
        synchronized (PlaylistSync.MUTATION) {
            PlaylistSync.cancel();
            for (var source : OfflineCopyStore.copyTrackLists().entrySet()) {
                if (!source.getValue().contains(videoId)) continue;
                String sourceId = source.getKey();
                affected.add(sourceId);
                PlaylistDownloads.onSourceRemoved(sourceId);
                if (DownloadedMarkers.hasPendingDownload(sourceId)) {
                    try {
                        DownloadedMarkers.onPlaylistCompleted(sourceId);
                    } catch (java.io.IOException failure) {
                        Logger.printException(() -> "Could not cancel the pending download of " + sourceId, failure);
                    }
                }
            }
            RawAudioStore.delete(videoId);
            readinessChanged();
        }
        affected.forEach(OfflineReplay::refreshSavedSource);
    }

    /**
     * Removes a downloaded playlist or album: deletes the copies of its songs that no other downloaded list lists
     * and that were not downloaded on their own (see {@link CopyOwnership}), and forgets the list. Off the main
     * thread.
     *
     * @return How many songs lost their copy.
     */
    public static int removeSource(String sourceId) {
        synchronized (PlaylistSync.MUTATION) {
            PlaylistDownloads.onSourceRemoved(sourceId);
            PlaylistSync.cancel();
            Map<String, List<String>> sources = OfflineCopyStore.copyTrackLists();
            CopyOwnership.Removal removal = CopyOwnership.removing(sourceId, sources, RawAudioStore.individualIds(),
                    RawAudioStore.copyIds());
            for (String videoId : removal.deleteVideos()) VideoCopyStore.deleteForSong(videoId);
            for (String videoId : removal.deleteCopies()) RawAudioStore.delete(videoId);

            OfflineCopyStore.removeSource(sourceId);
            DownloadedMarkers.onSourceRemoved(sourceId);
            readinessChanged();
            return removal.deleteCopies().size();
        }
    }

    /**
     * Deletes every stream copy and cancels its active or pending transfers. Device files are untouched.
     *
     * @return How many copies were deleted.
     */
    public static int deleteAll() {
        synchronized (PlaylistSync.MUTATION) {
            PlaylistSync.cancel();
            PlaylistDownloads.cancelStreamRuns();
            LocalDownloadManager.cancelStreamDownloads();
            DownloadedMarkers.forgetStreamSources();
            int removed = RawAudioStore.clearAll();
            readinessChanged();
            return removed;
        }
    }
}
