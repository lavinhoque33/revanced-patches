/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.io.File;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * DEBUG AID, to be removed before release: tells whether the offline video copy fails because of the stored answer or
 * because of the missing network.
 *
 * <p>Offline the stored answer of a video copy gives the app's player no video track, while the live online answer
 * (the same copy files, the same stream pair) does. With a marker file in the app's {@code files} folder the
 * ONLINE player request of a video copy is answered from the stored bytes, exactly as the offline lookup answers it
 * ({@link OfflineReplay#storedResponseOf}, {@link OfflineReplayResponse}): if the Song/Video switch then selects the
 * video, the cause is the missing network; if it does not, the cause is in the stored answer.
 * <ul>
 *   <li>{@value #WITH_MARKER_FILE}: the stored answer as the offline path serves it, with the offline marker;
 *   <li>{@value #WITHOUT_MARKER_FILE}: the same without the offline marker (response field 14), which tells whether
 *   the marker makes the app treat the answer as an offline one.
 * </ul>
 * The files are created and removed by the tester; nothing here is a setting. A file is looked for once per player
 * request of a video copy.
 */
final class DebugStoredAnswer {
    static final String WITH_MARKER_FILE = "rvx_debug_stored_online";
    static final String WITHOUT_MARKER_FILE = "rvx_debug_stored_online_plain";

    private DebugStoredAnswer() {}

    /** The stored answer for an online request of {@code videoId}, or null when no marker file exists. */
    @Nullable
    static byte[] answerFor(String videoId) {
        try {
            File files = Utils.getContext().getFilesDir();
            boolean withMarker = new File(files, WITH_MARKER_FILE).exists();
            boolean withoutMarker = new File(files, WITHOUT_MARKER_FILE).exists();
            if (!withMarker && !withoutMarker) return null;

            byte[] stored = OfflineReplay.storedResponseOf(videoId);
            if (stored == null) return null;

            byte[] answer = OfflineReplayResponse.prepare(stored, !withoutMarker);
            Logger.printDebug(() -> "StreamSwap: DEBUG " + (withoutMarker ? WITHOUT_MARKER_FILE : WITH_MARKER_FILE)
                    + " v=" + videoId + " answering the ONLINE request from the stored bytes " + answer.length + "B");
            return answer;
        } catch (Exception ex) {
            Logger.printException(() -> "DebugStoredAnswer: no stored answer for v=" + videoId, ex);
            return null;
        }
    }
}
