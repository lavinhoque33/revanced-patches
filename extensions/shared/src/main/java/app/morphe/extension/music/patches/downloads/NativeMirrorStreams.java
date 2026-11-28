/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import java.io.File;

/** Exact byte-range identity of one stored representation; no itag-only or timestamp fallback. */
final class NativeMirrorStreams {
    record Stream(int itag, long lastModified, File file, long length, boolean audio, String audioTrack) {
        String key(String videoId) {
            return videoId + "." + itag + (audioTrack.isEmpty() ? "" : "." + audioTrack) + "." + lastModified;
        }

        boolean containsRange(long position, long requested) {
            return position >= 0 && position <= length && requested >= -1 && requested <= length - position;
        }
    }

    private NativeMirrorStreams() {}
}
