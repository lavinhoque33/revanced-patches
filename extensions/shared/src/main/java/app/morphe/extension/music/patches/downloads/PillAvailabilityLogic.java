/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import java.util.function.Predicate;

/** When {@link PillAvailability} lights a dim pill. A pure rule, so it can be tested without Android. */
final class PillAvailabilityLogic {
    private PillAvailabilityLogic() {}

    /**
     * A dim pill is lit when the video being played, or the one the last player response was for, has a video copy.
     * A lit pill is the app's own and is left alone, as is every video without a copy.
     *
     * @param shown      Whether the app has the pill lit.
     * @param playing    The id of the video being played, empty when none.
     * @param response   The id of the last player response, empty when none.
     * @param hasCopy    Whether a video id has a committed video copy.
     */
    static boolean forcesBright(boolean shown, String playing, String response, Predicate<String> hasCopy) {
        if (shown) return false;

        return namesCopy(playing, hasCopy) || namesCopy(response, hasCopy);
    }

    private static boolean namesCopy(String videoId, Predicate<String> hasCopy) {
        return videoId != null && !videoId.isEmpty() && hasCopy.test(videoId);
    }
}
