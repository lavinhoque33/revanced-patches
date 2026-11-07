/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

/**
 * Management of the private stream copies of Play downloads locally. They are invisible in Device files and the
 * library, so the settings page needs its own way to see what they take and to delete them. Both calls do disk
 * work and must be made off the main thread.
 */
public final class StreamDownloads {
    /** How many stream copies exist and how many bytes of audio they hold. */
    public record Summary(int count, long bytes) {}

    private StreamDownloads() {}

    public static Summary summary() {
        RawAudioFiles.Stats stats = RawAudioStore.stats();
        return new Summary(stats.count(), stats.bytes());
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
