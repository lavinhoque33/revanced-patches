/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.utils.Logger;

/**
 * Keeps the Song/Video pill available for a video that has a video copy.
 *
 * <p>The app decides whether the pill is lit from the state of the player and of the queue item. While online the
 * decision is lit for a video the stream swap plays from a copy. Offline the stored answer reached the player but the
 * pill stayed dim, and a press showed "Video not available" instead of changing the display. The copy is the video:
 * whatever the app's gate says, the pill is lit for it, so a press changes the display as it does online.
 */
@SuppressWarnings("unused")
public final class PillAvailability {
    /** The toggle's view, by the two methods the patch adds to it. */
    public interface Gate {
        /** Lights the pill, as the app's own state method does: the click listener lets a press through. */
        void rvxBright();

        /** Whether the app has the pill lit now. */
        boolean rvxShown();
    }

    private PillAvailability() {}

    /**
     * Injection point: the end of the method that computes the state of the pill, which runs when the playback, the
     * queue item or the session changes.
     *
     * @param view The toggle's view, which the patch gave the {@link Gate} methods.
     */
    public static void onPillState(@Nullable Object view) {
        try {
            if (!(view instanceof Gate gate) || !StreamSwap.enabled()) return;

            String playing = VideoInformation.getVideoId();
            String response = VideoInformation.getPlayerResponseVideoId();
            boolean shown = gate.rvxShown();
            if (!PillAvailabilityLogic.forcesBright(shown, playing, response, VideoCopyStore::has)) return;

            gate.rvxBright();
            Logger.printDebug(() -> "PillAvailability: pill was dim for a video with a copy, lit it v=" + playing
                    + " response=" + response);
        } catch (Exception ex) {
            Logger.printException(() -> "PillAvailability: pill state failure", ex);
        }
    }
}
