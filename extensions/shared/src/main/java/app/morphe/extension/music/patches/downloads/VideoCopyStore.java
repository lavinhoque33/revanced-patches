/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-09.
 * Modified by lavinhoque33, 2026-11-10
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Android lifecycle of the video copies ({@link VideoCopyFiles}): what the downloader keeps, the stream swap plays,
 * and the audio copy's lifecycle ({@link RawAudioStore}) deletes with it. A video copy never outlives the audio copy
 * of its song, and never decides whether a song has a stream copy: the green check follows the audio copy alone.
 */
final class VideoCopyStore {
    record Playable(VideoCopyFiles.Entry copy, byte[] response, VideoCopyLogic.Stored formats) {}

    private static final class Storage {
        static final VideoCopyFiles files = new VideoCopyFiles(
                new File(Utils.getContext().getFilesDir(), "rvx_video"));
    }

    /** Published only after the worker has loaded all commits. UI lookups never initialize Storage. */
    @Nullable
    private static volatile VideoCopyFiles index;
    private static final AtomicBoolean loadStarted = new AtomicBoolean();
    /** The videos whose online response was kept in this run. */
    private static final Set<String> liveKept = ConcurrentHashMap.newKeySet();
    /** Player response fields the copy never uses: 4 streaming data, 7 ad placements. */
    private static final Set<Integer> LIVE_DROPPED_FIELDS = Set.of(4, 7);

    private VideoCopyStore() {}

    /** Starts loading the committed pairs in the background. Until it is done {@link #has} is false. */
    static void warmUp() {
        if (!loadStarted.compareAndSet(false, true)) return;

        Utils.runOnBackgroundThread(VideoCopyStore::loadedFiles);
    }

    static boolean playbackIndexReady() {
        VideoCopyFiles files = index;
        return files != null && files.loaded();
    }

    static void loadPlaybackData() {
        Utils.verifyOffMainThread();
        loadedFiles();
    }

    private static VideoCopyFiles loadedFiles() {
        VideoCopyFiles files = index;
        if (files != null) return files;

        files = Storage.files;
        files.ensureLoaded();
        index = files;
        return files;
    }

    /** Whether the video has a video copy. In memory, so the main thread may ask; false until loaded. */
    static boolean has(String videoId) {
        warmUp();
        VideoCopyFiles files = index;
        return files != null && files.has(videoId);
    }

    /** The copy that may be played, or null. Hashes the file the first time. */
    @Nullable
    static VideoCopyFiles.Entry find(String videoId) {
        Utils.verifyOffMainThread();
        return loadedFiles().find(videoId);
    }

    /** Both media files and their descriptors must be valid before the pair takes precedence over raw audio. */
    @Nullable
    static Playable playable(String videoId) {
        Utils.verifyOffMainThread();
        VideoCopyFiles files = loadedFiles();
        if (!files.has(videoId)) return null;

        VideoCopyFiles.Entry copy = files.find(videoId);
        if (copy == null) return null;

        byte[] bytes = files.response(videoId);
        if (bytes == null) return null;

        try {
            var formats = VideoCopyLogic.stored(PlayerResponse.parseFrom(bytes), copy);
            return formats == null ? null : new Playable(copy, bytes, formats);
        } catch (IOException ignored) {
            return null;
        }
    }

    /** The stored player response of a video copy, or null. */
    @Nullable
    static byte[] response(String videoId) {
        Utils.verifyOffMainThread();
        VideoCopyFiles files = loadedFiles();
        return files.has(videoId) ? files.response(videoId) : null;
    }

    /** The response the app itself got online for a video copy (see {@link #keepLive}), or null. */
    @Nullable
    static byte[] live(String videoId) {
        Utils.verifyOffMainThread();
        return loadedFiles().live(videoId);
    }

    /**
     * Keeps the response the app got online for the video of a copy, once per run: without the streaming data and
     * the ad placements, which the copy never uses. The replay answers offline with the shape of this response, so
     * the app's player selection sees what it sees online. Never fails the caller; a failure is only logged.
     */
    static void keepLive(String videoId, byte[] onlineResponse) {
        if (!liveKept.add(videoId)) return;

        Utils.runOnBackgroundThread(() -> {
            boolean kept = false;
            try {
                byte[] live = ResponseFields.without(onlineResponse, LIVE_DROPPED_FIELDS);
                kept = Storage.files.keepLive(videoId, live);
                if (kept) Logger.printDebug(() -> "VideoCopyStore: kept online response of v=" + videoId
                        + " " + live.length + "B");
            } catch (Exception ex) {
                Logger.printException(() -> "VideoCopyStore: could not keep the online response of v=" + videoId, ex);
            } finally {
                if (!kept) liveKept.remove(videoId);
            }
        });
    }

    /** The id of the video copy that belongs to the song, or null. */
    @Nullable
    static String videoOf(String songId) {
        Utils.verifyOffMainThread();
        return loadedFiles().videoOf(songId);
    }

    @Nullable
    static String songOf(String videoId) {
        return Storage.files.songOf(videoId);
    }

    /** A saved row's artwork counterpart, without starting or waiting for a library-wide scan. Worker only. */
    @Nullable
    static String artworkPartner(String videoId, @Nullable String aliasSong) {
        return Storage.files.artworkPartner(videoId, aliasSong);
    }

    /** The committed pairs for the main thread: none until they are loaded, as {@link #has}. */
    static Map<String, String> pairsIfLoaded() {
        warmUp();
        VideoCopyFiles files = index;
        return files == null ? Map.of() : files.pairs();
    }

    /** The committed pairs, video id to song id. */
    static Map<String, String> pairs() {
        Utils.verifyOffMainThread();
        return loadedFiles().pairs();
    }

    /**
     * Keeps a finished video download. Works only while Play downloads locally is on; the song's audio copy must exist.
     *
     * @return Whether the copy now exists. False is never fatal for the song or the list.
     */
    static boolean retain(String videoId, String songId, @Nullable String sourceId, File videoTemporary,
                          File audioTemporary, VideoCopyLogic.Download download) {
        try {
            Utils.verifyOffMainThread();
            VideoCopyFiles files = loadedFiles();
            if (!StreamSwap.supported()) return false;

            boolean kept = files.retain(videoId, songId, sourceId, videoTemporary, audioTemporary, download);
            if (!kept) return false;

            // Switching off during a download must not leave a newly published extra copy.
            if (!StreamSwap.supported()) {
                delete(videoId);
                return false;
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "VideoCopy: could not retain v=" + videoId, ex);
            return false;
        }
    }

    static void delete(String videoId) {
        Utils.verifyOffMainThread();
        StreamSwapSources.invalidate(videoId);
        Storage.files.delete(videoId);
    }

    /** The audio copy of the song is deleted: its video copy goes with it. */
    static void deleteForSong(String songId) {
        Utils.verifyOffMainThread();
        Storage.files.ensureLoaded();
        String videoId = Storage.files.videoOf(songId);
        if (videoId != null) delete(videoId);
    }

    /** Every stream copy is deleted: so is every video copy. @return How many video copies were deleted. */
    static int clearAll() {
        Utils.verifyOffMainThread();
        StreamSwapSources.invalidateAll();
        return Storage.files.clear();
    }

    /** Orphan cleanup: removes the video copies whose song has no audio copy in {@code songIds}, and leftovers. */
    static void retainOnly(Set<String> songIds, long songIdsReadAt) {
        Utils.verifyOffMainThread();
        Storage.files.ensureLoaded();
        for (String videoId : Storage.files.retainOnly(songIds, songIdsReadAt)) {
            StreamSwapSources.invalidate(videoId);
        }
    }

    static VideoCopyFiles.Stats stats() {
        Utils.verifyOffMainThread();
        return Storage.files.stats();
    }
}
