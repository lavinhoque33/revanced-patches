/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Written by lavinhoque33, 2026-11-12
 */

package app.morphe.extension.music.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrossfadeOutgoingTest {
    private static final float APP_VOLUME = 0.823568f;
    private static final float EPSILON = 0.000001f;

    @Test
    public void tapStartedSongIgnoresAnOldPauseHookAndNeverStepsToZero() {
        CrossfadeOutgoing outgoing = CrossfadeOutgoing.fromPlayer(true, 2690);

        assertTrue(outgoing.shouldPlay());
        assertEquals(APP_VOLUME, outgoing.volume(APP_VOLUME), EPSILON);
        assertEquals(APP_VOLUME, outgoing.volume(APP_VOLUME)
                * CrossfadeManager.FadeCurve.EQUAL_POWER.out(0.0f), EPSILON);
        assertTrue(outgoing.volume(APP_VOLUME) * CrossfadeManager.FadeCurve.EQUAL_POWER.out(0.05f) > 0.0f);
    }

    @Test
    public void genuinelyPausedOutgoingStaysSilentAtPromotionAndPreStartedFade() {
        CrossfadeOutgoing outgoing = CrossfadeOutgoing.fromPlayer(false, 2690);

        assertFalse(outgoing.shouldPlay());
        for (float progress : new float[]{0.0f, 0.05f, 0.5f, 1.0f}) {
            assertEquals(0.0f, outgoing.volume(APP_VOLUME)
                    * CrossfadeManager.FadeCurve.EQUAL_POWER.out(progress), EPSILON);
        }
    }

    @Test
    public void genuinelyPausedOutgoingCannotBeRestoredOnFailedLoad() {
        CrossfadeOutgoing outgoing = CrossfadeOutgoing.fromPlayer(false, 2690);

        assertFalse(outgoing.shouldPlay());
        assertEquals(0.0f, outgoing.volume(APP_VOLUME), EPSILON);
        assertEquals(0.0f, outgoing.volume(1.0f), EPSILON);
    }

    @Test
    public void actuallyPlayingOutgoingKeepsNormalizedVolumeOnRecovery() {
        CrossfadeOutgoing outgoing = CrossfadeOutgoing.fromPlayer(true, 2690);

        assertTrue(outgoing.shouldPlay());
        assertEquals(APP_VOLUME, outgoing.volume(APP_VOLUME), EPSILON);
    }

    @Test
    public void internalPauseImmediatelyBeforeStopStillFadesTheOutgoing() {
        assertTrue(CrossfadeOutgoing.fromPlayer(false, 0).shouldPlay());
        assertTrue(CrossfadeOutgoing.fromPlayer(false, 499).shouldPlay());
    }

    @Test
    public void pauseWindowEndsAtFiveHundredMilliseconds() {
        assertFalse(CrossfadeOutgoing.fromPlayer(false, 500).shouldPlay());
    }
}
