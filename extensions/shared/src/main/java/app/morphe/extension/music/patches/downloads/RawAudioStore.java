/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 * Modified by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import android.database.ContentObserver;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Android lifecycle for original stream copies. Settings reads themselves are in-memory only. */
final class RawAudioStore {
    private static final AtomicBoolean observing = new AtomicBoolean();
    private static final CoalescedRunner cleanup = new CoalescedRunner(
            Utils::runOnBackgroundThread, RawAudioStore::cleanupNow);
    private static final class Storage {
        static final RawAudioFiles files = new RawAudioFiles(
                new File(Utils.getContext().getFilesDir(), "rvx_raw"));
    }

    private RawAudioStore() {}

    static void startCleanup() {
        // This method may be called by the native repository constructor on the main thread.
        Utils.runOnBackgroundThread(() -> {
            try {
                if (observing.compareAndSet(false, true)) {
                    Utils.getContext().getContentResolver().registerContentObserver(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, new ContentObserver(null) {
                                @Override
                                public void onChange(boolean selfChange) {
                                    cleanupLater();
                                }
                            });
                }
            } catch (Exception ex) {
                observing.set(false);
                Logger.printException(() -> "StreamSwap: could not observe native audio deletions", ex);
            }
            cleanupLater();
        });
    }

    private static void cleanupLater() {
        cleanup.request();
    }

    private static void cleanupNow() {
        try {
            Utils.verifyOffMainThread();
            Storage.files.cleanup(RawAudioStore::nativeUri);
            // Stored data published after the id list is read belongs to a copy kept meanwhile.
            Set<String> copyIds = Storage.files.committedIds();
            long copyIdsReadAt = System.currentTimeMillis();
            OfflineCopyStore.retainOnly(copyIds, copyIdsReadAt);
            VideoCopyStore.retainOnly(copyIds, copyIdsReadAt);
            StreamSwapSources.invalidateMissing();
            DownloadedMarkers.rescanStreamCopies();
        } catch (Exception ex) {
            Logger.printException(() -> "StreamSwap: raw cleanup failed", ex);
        }
    }

    @Nullable
    private static String nativeUri(String videoId) {
        Uri uri = NativeAudioStore.find(videoId);
        return uri == null ? null : uri.toString();
    }

    /**
     * Keeps the stream copy of a finished download. {@code nativeTrack} is the track written together with the
     * copy, or null for a stream-only copy. Works only while Play downloads locally is on.
     *
     * @return Whether the copy now exists. False is never fatal for a download that also wrote a local file.
     */
    static boolean retain(String videoId, File temporary, Format format, @Nullable Uri nativeTrack) {
        try {
            Utils.verifyOffMainThread();
            if (!StreamSwap.enabled()) return false;
            boolean kept = Storage.files.retain(videoId, temporary, format.getItag(), format.getMimeType(),
                    format.getContentLength(), format.getLastModified(),
                    nativeTrack == null ? null : nativeTrack.toString());
            if (!kept) return false;

            // Switching off during a download must not leave a newly published extra copy.
            if (!StreamSwap.enabled()) {
                delete(videoId);
                return false;
            }
            DownloadedMarkers.onStreamCopyKept(videoId);
            return true;
        } catch (Exception ex) {
            // A failed optional copy never turns a completed normal download into a failure.
            Logger.printException(() -> "StreamSwap: could not retain raw copy v=" + videoId, ex);
            return false;
        }
    }

    /** The copy that may be played, or null. Hashes the file the first time. */
    static RawAudioFiles.Entry find(String videoId) throws Exception {
        Utils.verifyOffMainThread();
        return Storage.files.find(videoId, RawAudioStore::nativeUri);
    }

    static void delete(String videoId) {
        Utils.verifyOffMainThread();
        StreamSwapSources.invalidate(videoId);
        Storage.files.delete(videoId);
        OfflineCopyStore.delete(videoId);
        VideoCopyStore.deleteForSong(videoId);
        DownloadedMarkers.onStreamCopyRemoved(videoId);
    }

    /**
     * The native track is confirmed gone: its copy goes with it. A stream-only copy never belonged to the native
     * track and stays.
     */
    static void deleteWithNativeTrack(String videoId) {
        Utils.verifyOffMainThread();
        StreamSwapSources.invalidate(videoId);
        if (Storage.files.deleteUnlessStreamOnly(videoId)) {
            OfflineCopyStore.delete(videoId);
            VideoCopyStore.deleteForSong(videoId);
            DownloadedMarkers.onStreamCopyRemoved(videoId);
        }
    }

    /** The ids of every committed stream copy. */
    static Set<String> copyIds() {
        Utils.verifyOffMainThread();
        return Storage.files.committedIds();
    }

    static RawAudioFiles.Stats stats() {
        Utils.verifyOffMainThread();
        return Storage.files.stats();
    }

    /**
     * Deletes every stream copy, tied or stream-only; local files are never touched.
     *
     * @return How many copies were deleted.
     */
    static int clearAll() {
        Utils.verifyOffMainThread();
        StreamSwapSources.invalidateAll();
        int count = Storage.files.clear();
        OfflineCopyStore.clearAll();
        VideoCopyStore.clearAll();
        DownloadedMarkers.onStreamCopiesCleared();
        return count;
    }
}
