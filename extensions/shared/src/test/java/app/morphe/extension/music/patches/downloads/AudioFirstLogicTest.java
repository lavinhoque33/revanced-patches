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

    private static final java.util.List<String> ALBUM = java.util.List.of("atv0", "atv1", "atv2", "atv3");

    private static java.util.function.Predicate<String> copies(String... ids) {
        return java.util.Set.of(ids)::contains;
    }

    @Test public void aCommandIdWithACopyArmsByTheId() {
        var arm = AudioFirstLogic.chooseArm(true, java.util.List.of("omv", "atv1"), null, -1, false, copies("atv1"));
        assertEquals(new AudioFirstLogic.Arm("id", "atv1"), arm);
    }

    @Test public void anyIdOfTheCommandCounts() {
        var arm = AudioFirstLogic.chooseArm(true, java.util.List.of("a", "b", "c"), null, -1, false, copies("c"));
        assertEquals("c", arm.videoId());
    }

    @Test public void anOmvRowOfADownloadedAlbumArmsByTheTappedPosition() {
        var arm = AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), ALBUM, 2, false, copies("atv2"));
        assertEquals(new AudioFirstLogic.Arm("playlist-index", "atv2"), arm);
    }

    @Test public void anAbsentIndexIsTheFirstRowUnlessTheCommandHasParams() {
        assertEquals("atv0",
                AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), ALBUM, -1, false, copies("atv0")).videoId());
        assertNull(AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), ALBUM, -1, true, copies("atv0")));
    }

    @Test public void aRowWithoutACopyInAPartlyDownloadedAlbumDoesNotArm() {
        assertNull(AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), ALBUM, 3, false, copies("atv0", "atv1")));
    }

    @Test public void aFullyDownloadedAlbumArmsForAnyTapOfItsPlaylist() {
        var all = copies("atv0", "atv1", "atv2", "atv3");
        var arm = AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), ALBUM, 99, false, all);
        assertEquals(new AudioFirstLogic.Arm("playlist-source", "omv"), arm);
        assertEquals("playlist-source",
                AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), ALBUM, -1, true, all).rule());
        assertEquals("atv0", AudioFirstLogic.chooseArm(true, java.util.List.of(), ALBUM, -1, true, all).videoId());
    }

    @Test public void nothingTiedToACopyNeverArms() {
        assertNull(AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), null, -1, false, copies("atv0")));
        assertNull(AudioFirstLogic.chooseArm(true, java.util.List.of("omv"), java.util.List.of(), 0, false,
                copies("atv0")));
        assertNull(AudioFirstLogic.chooseArm(true, java.util.List.of(), ALBUM, 0, false, copies()));
    }

    @Test public void aDisabledStartNeverArms() {
        assertNull(AudioFirstLogic.chooseArm(false, java.util.List.of("atv1"), ALBUM, 1, false,
                copies("atv0", "atv1", "atv2", "atv3")));
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

    @Test public void onlyTheVideoFallbackIsTurnedIntoTheAudioMode() {
        assertEquals("ATV_PREFERRED", AudioFirstLogic.modeToSet(true, true, "OMV_PREFERRED"));
    }

    @Test public void theModeIsLeftAloneInEveryOtherCase() {
        assertNull(AudioFirstLogic.modeToSet(true, true, "OMV_PREFERRED_USER_TRIGGERED"));
        assertNull(AudioFirstLogic.modeToSet(true, true, "ATV_PREFERRED_USER_TRIGGERED"));
        assertNull(AudioFirstLogic.modeToSet(true, true, "DONT_PLAY_VIDEO_OVERRIDE"));
        assertNull(AudioFirstLogic.modeToSet(true, true, "ATV_PREFERRED"));
        assertNull(AudioFirstLogic.modeToSet(true, true, null));
        assertNull(AudioFirstLogic.modeToSet(true, false, "OMV_PREFERRED"));
        assertNull(AudioFirstLogic.modeToSet(false, true, "OMV_PREFERRED"));
    }

    @Test public void aRowIsRetargetedInEveryModeButTheUsersVideoChoice() {
        assertFalse(AudioFirstLogic.retargetsIn("OMV_PREFERRED_USER_TRIGGERED"));
        assertTrue(AudioFirstLogic.retargetsIn("OMV_PREFERRED"));
        assertTrue(AudioFirstLogic.retargetsIn("ATV_PREFERRED"));
        assertTrue(AudioFirstLogic.retargetsIn("ATV_PREFERRED_USER_TRIGGERED"));
        assertTrue(AudioFirstLogic.retargetsIn("DONT_PLAY_VIDEO_OVERRIDE"));
        assertTrue(AudioFirstLogic.retargetsIn(null));
    }
}
