/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import app.morphe.extension.shared.utils.Utils;

/**
 * Management of the private stream copies of Play downloads locally. They are invisible in Device files and the
 * library, so the settings page needs its own way to see what they take and to delete them. Both calls do disk
 * work and must be made off the main thread.
 */
public final class StreamDownloads {
    /** How many stream copies exist and how many bytes of audio they hold. */
    public record Summary(int count, long bytes) {}

    /** How many of the stream copies have stored data, so can play with no network, out of all of them. */
    public record Readiness(int ready, int total) {}

    private StreamDownloads() {}

    public static Summary summary() {
        RawAudioFiles.Stats stats = RawAudioStore.stats();
        return new Summary(stats.count(), stats.bytes());
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
