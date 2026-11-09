/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 * Modified by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import java.util.Locale;

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

    private static volatile Runnable readinessListener;

    /** The settings row that shows {@link #readiness()} asks to be told when it changes; null stops the calls. */
    public static void setReadinessListener(Runnable listener) {
        readinessListener = listener;
    }

    /** Tells the listener, on the main thread, that a copy got its stored data. */
    static void readinessChanged() {
        Runnable listener = readinessListener;
        if (listener != null) Utils.runOnMainThread(listener);
    }

    /**
     * Deletes every stream copy, including the copy kept beside a local file. Local files, Device files and
     * downloads in flight are untouched; the checks of the deleted copies disappear.
     *
     * @return How many copies were deleted.
     */
    public static int deleteAll() {
        return RawAudioStore.clearAll();
    }
}
