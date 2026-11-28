/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.function.Predicate;

/** What {@link PillAvailability} decides. Pure rules, so they can be tested without Android. */
final class PillAvailabilityLogic {
    private PillAvailabilityLogic() {}

    /**
     * The playing ID, or the initial response ID before a track starts, if it has a video copy.
     *
     * @param playing  The id of the video being played, empty when none.
     * @param response The id of the last player response, empty when none.
     * @param hasCopy  Whether a video id has a committed video copy.
     * @return The id, or null when neither has a copy.
     */
    @Nullable
    static String videoWithCopy(@Nullable String playing, @Nullable String response, Predicate<String> hasCopy) {
        if (playing != null && !playing.isEmpty()) return hasCopy.test(playing) ? playing : null;

        return response != null && !response.isEmpty() && hasCopy.test(response) ? response : null;
    }

    /**
     * An answer of the app's own checks, true for available, stays true; a false one is overruled for a video with a
     * copy and only for it.
     */
    static boolean opens(boolean appsAnswer, boolean videoHasCopy) {
        return appsAnswer || videoHasCopy;
    }

    /**
     * The pill's look. A video copy keeps it lit. An online-video hybrid clip (saved audio beside an online video)
     * is lit while online; offline its video cannot come, so it is not offered: the pill is dim unless the clip
     * shows the video already, where the app's look stands so the press back to Song still works.
     */
    static boolean look(boolean appsAnswer, boolean videoHasCopy, boolean hybrid, boolean online,
                        boolean videoMode) {
        if (videoHasCopy) return true;
        if (!hybrid) return appsAnswer;

        return online || videoMode && appsAnswer;
    }

    /**
     * Whether the app's own switch flips an online-video hybrid clip in place: online in both directions, offline
     * only back to Song, the saved audio.
     */
    static boolean hybridSwitches(boolean online, boolean videoMode) {
        return online || videoMode;
    }
}
