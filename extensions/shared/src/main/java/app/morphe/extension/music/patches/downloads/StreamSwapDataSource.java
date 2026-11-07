/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import java.io.File;

import app.morphe.extension.shared.utils.Logger;

/** Entry hook for the stock cache reader. A vanished/disabled copy resumes the original online representation. */
public final class StreamSwapDataSource {
    private StreamSwapDataSource() {}

    public static boolean isLocal(Uri uri) {
        return "file".equals(uri.getScheme());
    }

    /** Null for unrelated sources; file URI for a current raw copy; otherwise its original online URI. */
    public static Uri route(Uri uri, long position, long length) {
        StreamSwapSources.Source source = StreamSwapSources.find(uri);
        if (source == null) return null;
        if (!StreamSwap.enabled() || StreamSwap.onMainThread() || !source.available) return source.online;
        try {
            File file = source.raw.file();
            long size = source.raw.length();
            if (!file.isFile() || file.length() != size || file.lastModified() != source.fileModified
                    || position < 0 || position >= size || length < -1 || length > size - position) {
                source.available = false;
                return source.online;
            }
            Logger.printDebug(() -> "StreamSwap: source=FILE v=" + source.videoId + " position=" + position
                    + " length=" + length + " thread=" + Thread.currentThread().getName());
            return Uri.fromFile(file);
        } catch (Exception ex) {
            return source.online;
        }
    }
}
