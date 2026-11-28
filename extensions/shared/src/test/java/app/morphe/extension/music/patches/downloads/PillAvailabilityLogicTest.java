/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Set;
import java.util.function.Predicate;

public class PillAvailabilityLogicTest {
    private static final Predicate<String> COPIES = Set.of("video-1")::contains;

    @Test public void theVideoBeingPlayedThatHasACopyIsTheOne() {
        assertEquals("video-1", PillAvailabilityLogic.videoWithCopy("video-1", "", COPIES));
    }

    @Test public void aPrefetchedVideoDoesNotChangeThePlayingTracksAvailability() {
        assertNull(PillAvailabilityLogic.videoWithCopy("song-1", "video-1", COPIES));
    }

    @Test public void theResponseCanSupplyTheInitialIdBeforePlaybackStarts() {
        assertEquals("video-1", PillAvailabilityLogic.videoWithCopy("", "video-1", COPIES));
    }

    @Test public void aVideoWithoutACopyIsNone() {
        assertNull(PillAvailabilityLogic.videoWithCopy("video-2", "video-3", COPIES));
    }

    @Test public void noVideoAsksNothingOfTheStore() {
        Predicate<String> never = id -> {
            throw new AssertionError("asked for " + id);
        };

        assertNull(PillAvailabilityLogic.videoWithCopy("", "", never));
        assertNull(PillAvailabilityLogic.videoWithCopy(null, null, never));
    }

    @Test public void anAnswerOfTheAppThatIsTrueIsKept() {
        assertTrue(PillAvailabilityLogic.opens(true, false));
        assertTrue(PillAvailabilityLogic.opens(true, true));
    }

    @Test public void aFalseAnswerIsOverruledOnlyForAVideoWithACopy() {
        assertTrue(PillAvailabilityLogic.opens(false, true));
        assertFalse(PillAvailabilityLogic.opens(false, false));
    }

    @Test public void aHybridClipIsLitOnlineAndNotOfferedOffline() {
        assertTrue(PillAvailabilityLogic.look(false, false, true, true, false));
        assertFalse(PillAvailabilityLogic.look(true, false, true, false, false));
        // Offline in Video, the app's look stands so the press back to Song works.
        assertTrue(PillAvailabilityLogic.look(true, false, true, false, true));
        assertFalse(PillAvailabilityLogic.look(false, false, true, false, true));
    }

    @Test public void theLookOfAClipWithoutAHybridIsTheCopyRule() {
        assertTrue(PillAvailabilityLogic.look(false, true, false, false, false));
        assertTrue(PillAvailabilityLogic.look(true, false, false, false, false));
        assertFalse(PillAvailabilityLogic.look(false, false, false, true, true));
    }

    @Test public void theAppsSwitchFlipsAHybridOnlineAndOfflineOnlyBackToSong() {
        assertTrue(PillAvailabilityLogic.hybridSwitches(true, false));
        assertTrue(PillAvailabilityLogic.hybridSwitches(true, true));
        assertTrue(PillAvailabilityLogic.hybridSwitches(false, true));
        assertFalse(PillAvailabilityLogic.hybridSwitches(false, false));
    }
}
