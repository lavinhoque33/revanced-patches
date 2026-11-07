/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.database.ContentObserver;
import android.net.Uri;
import android.provider.MediaStore;

import java.io.File;
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
            Storage.files.cleanup(id -> {
                Uri uri = NativeAudioStore.find(id);
                return uri == null ? null : uri.toString();
            });
            StreamSwapSources.invalidateMissing();
        } catch (Exception ex) {
            Logger.printException(() -> "StreamSwap: raw cleanup failed", ex);
        }
    }

    static void retain(String videoId, File temporary, Format format, Uri muxed) {
        try {
            Utils.verifyOffMainThread();
            if (!StreamSwap.enabled()) return;
            Storage.files.retain(videoId, temporary, format.getItag(), format.getMimeType(), format.getContentLength(),
                    format.getLastModified(), muxed.toString());
            // Switching off during a download must not leave a newly published extra copy.
            if (!StreamSwap.enabled()) delete(videoId);
        } catch (Exception ex) {
            // A failed optional copy never turns a completed normal download into a failure.
            Logger.printException(() -> "StreamSwap: could not retain raw copy v=" + videoId, ex);
        }
    }

    static RawAudioFiles.Entry find(String videoId) throws Exception {
        Utils.verifyOffMainThread();
        Uri muxed = NativeAudioStore.find(videoId);
        return Storage.files.find(videoId, muxed == null ? null : muxed.toString());
    }

    static void delete(String videoId) {
        Utils.verifyOffMainThread();
        StreamSwapSources.invalidate(videoId);
        Storage.files.delete(videoId);
    }
}
