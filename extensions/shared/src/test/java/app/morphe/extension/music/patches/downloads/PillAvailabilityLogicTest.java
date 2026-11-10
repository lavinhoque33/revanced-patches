/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Set;
import java.util.function.Predicate;

public class PillAvailabilityLogicTest {
    private static final Predicate<String> COPIES = Set.of("video-1")::contains;

    @Test public void aDimPillIsLitForTheVideoBeingPlayedThatHasACopy() {
        assertTrue(PillAvailabilityLogic.forcesBright(false, "video-1", "", COPIES));
    }

    @Test public void aDimPillIsLitForTheVideoOfTheLastPlayerResponseThatHasACopy() {
        assertTrue(PillAvailabilityLogic.forcesBright(false, "song-1", "video-1", COPIES));
    }

    @Test public void aLitPillIsTheAppsOwn() {
        assertFalse(PillAvailabilityLogic.forcesBright(true, "video-1", "video-1", COPIES));
    }

    @Test public void aVideoWithoutACopyKeepsTheAppsDimPill() {
        assertFalse(PillAvailabilityLogic.forcesBright(false, "video-2", "video-3", COPIES));
    }

    @Test public void noVideoAsksNothingOfTheStore() {
        Predicate<String> never = id -> {
            throw new AssertionError("asked for " + id);
        };

        assertFalse(PillAvailabilityLogic.forcesBright(false, "", "", never));
        assertFalse(PillAvailabilityLogic.forcesBright(false, null, null, never));
    }
}
