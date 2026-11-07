/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

public class AudioFirstLogicTest {
    private static final long TTL = AudioFirstLogic.ARM_TTL_MILLIS;

    @Test public void anArmedVideoFallbackBecomesTheAudioMode() {
        assertEquals("ATV_PREFERRED", AudioFirstLogic.decide("OMV_PREFERRED", "dQw4w9WgXcQ", 1_000, 2_000, TTL));
    }

    @Test public void theUserTriggeredModesAreNeverChanged() {
        assertEquals("OMV_PREFERRED_USER_TRIGGERED",
                AudioFirstLogic.decide("OMV_PREFERRED_USER_TRIGGERED", "id", 1_000, 2_000, TTL));
        assertEquals("ATV_PREFERRED_USER_TRIGGERED",
                AudioFirstLogic.decide("ATV_PREFERRED_USER_TRIGGERED", "id", 1_000, 2_000, TTL));
    }

    @Test public void otherModesAreLeftAlone() {
        assertEquals("ATV_PREFERRED", AudioFirstLogic.decide("ATV_PREFERRED", "id", 1_000, 2_000, TTL));
        assertEquals("DONT_PLAY_VIDEO_OVERRIDE",
                AudioFirstLogic.decide("DONT_PLAY_VIDEO_OVERRIDE", "id", 1_000, 2_000, TTL));
    }

    @Test public void withoutAnArmedTapNothingChanges() {
        assertEquals("OMV_PREFERRED", AudioFirstLogic.decide("OMV_PREFERRED", null, 0, 2_000, TTL));
    }

    @Test public void theFlagExpiresAfterTenSeconds() {
        assertEquals(10_000, TTL);
        assertEquals("ATV_PREFERRED", AudioFirstLogic.decide("OMV_PREFERRED", "id", 1_000, 11_000, TTL));
        assertEquals("OMV_PREFERRED", AudioFirstLogic.decide("OMV_PREFERRED", "id", 1_000, 11_001, TTL));
    }

    @Test public void aClockBeforeTheTapNeverCounts() {
        assertFalse(AudioFirstLogic.isLive("id", 5_000, 4_999, TTL));
    }

    @Test public void onlyAnEnabledSettingAndASongWithACopyArm() {
        assertTrue(AudioFirstLogic.shouldArm(true, true, true));
        assertFalse(AudioFirstLogic.shouldArm(false, true, true));
        assertFalse(AudioFirstLogic.shouldArm(true, false, true));
        assertFalse(AudioFirstLogic.shouldArm(true, true, false));
    }

    @Test public void theVideoModesDeclineTheSwap() {
        assertTrue(AudioFirstLogic.declineForVideo("OMV_PREFERRED", "id", null, 0, 2_000, TTL));
        assertTrue(AudioFirstLogic.declineForVideo("OMV_PREFERRED_USER_TRIGGERED", "id", "id", 1_000, 2_000, TTL));
    }

    @Test public void theAudioModesAllowTheSwap() {
        assertFalse(AudioFirstLogic.declineForVideo("ATV_PREFERRED", "id", null, 0, 2_000, TTL));
        assertFalse(AudioFirstLogic.declineForVideo("ATV_PREFERRED_USER_TRIGGERED", "id", null, 0, 2_000, TTL));
        assertFalse(AudioFirstLogic.declineForVideo("DONT_PLAY_VIDEO_OVERRIDE", "id", null, 0, 2_000, TTL));
    }

    @Test public void anUnknownModeDeclinesTheSwap() {
        assertTrue(AudioFirstLogic.declineForVideo(null, "id", "id", 1_000, 2_000, TTL));
    }

    @Test public void theSongAJustArmedTapIsSwitchingToAudioIsNotDeclined() {
        assertFalse(AudioFirstLogic.declineForVideo("OMV_PREFERRED", "id", "id", 1_000, 2_000, TTL));
        assertTrue(AudioFirstLogic.declineForVideo("OMV_PREFERRED", "id", "other", 1_000, 2_000, TTL));
        assertTrue(AudioFirstLogic.declineForVideo("OMV_PREFERRED", "id", "id", 1_000, 11_001, TTL));
    }
}
