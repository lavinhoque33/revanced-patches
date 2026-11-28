/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.PillVideoSwitchLogic.Outcome.*;
import static app.morphe.extension.music.patches.downloads.PillVideoSwitchLogic.Video.*;
import static org.junit.Assert.*;

import org.junit.Test;

import app.morphe.extension.music.patches.downloads.PillVideoSwitchLogic.Facts;
import app.morphe.extension.music.patches.downloads.PillVideoSwitchLogic.Video;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;

public class PillVideoSwitchLogicTest {
    private static Facts facts(boolean songMode, boolean savedAudio, Video video) {
        return new Facts(songMode, false, false, savedAudio, video);
    }

    private static Format format(String mimeType) {
        return Format.newBuilder().setItag(1).setMimeType(mimeType).build();
    }

    private static PlayerResponse response(StreamingData.Builder streaming) {
        return PlayerResponse.newBuilder().setStreamingData(streaming).build();
    }

    @Test public void savedAudioOnlineWithAVideoTheClipCannotCarryDeclinesInPlace() {
        assertEquals(DECLINE_ONLINE, PillVideoSwitchLogic.online(facts(true, true, PRESENT)));
        assertTrue(DECLINE_ONLINE.taken());
    }

    @Test public void anUnknownVideoLeavesThePressToTheAppsOwnPill() {
        assertEquals(STOCK_NO_VIDEO, PillVideoSwitchLogic.online(facts(true, true, UNKNOWN)));
    }

    @Test public void aTrackWithoutOnlineVideoLeavesThePressToTheAppsOwnPill() {
        assertEquals(STOCK_NO_VIDEO, PillVideoSwitchLogic.online(facts(true, true, ABSENT)));
        assertFalse(STOCK_NO_VIDEO.taken());
    }

    @Test public void aPressTowardSongIsTheAppsInPlaceFlip() {
        assertEquals(STOCK_VIDEO_MODE, PillVideoSwitchLogic.online(facts(false, true, PRESENT)));
    }

    @Test public void aTrackNotPlayingFromSavedAudioIsTheApps() {
        assertEquals(STOCK_NOT_SAVED_AUDIO, PillVideoSwitchLogic.online(facts(true, false, PRESENT)));
    }

    @Test public void aVideoCopyPairKeepsItsInPlaceSwitchBeforeAnyOtherRule() {
        Facts pair = new Facts(true, true, false, true, PRESENT);

        assertEquals(STOCK_PAIR, PillVideoSwitchLogic.online(pair));
        assertEquals(STOCK_PAIR, PillVideoSwitchLogic.offline(true, true, true));
    }

    @Test public void aTrackedItemKeepsItsOwnTakeover() {
        assertEquals(STOCK_TRACKED, PillVideoSwitchLogic.online(new Facts(true, false, true, true, PRESENT)));
    }

    @Test public void offlineSavedAudioDeclinesAtOnceInSongMode() {
        assertEquals(DECLINE_OFFLINE, PillVideoSwitchLogic.offline(true, false, true));
        assertTrue(DECLINE_OFFLINE.taken());
    }

    @Test public void offlineAPressTowardSongIsSwallowedAsBefore() {
        assertEquals(CONSUME_OFFLINE, PillVideoSwitchLogic.offline(false, false, true));
        assertTrue(CONSUME_OFFLINE.taken());
    }

    @Test public void offlineWithoutACopyIsTheApps() {
        assertEquals(STOCK_NO_COPY, PillVideoSwitchLogic.offline(true, false, false));
        assertFalse(STOCK_NO_COPY.taken());
    }

    @Test public void aTrackPlayingFromTheCopyOfItsSongHasACopyThoughItHasNoEntryOfItsOwn() {
        String alias = "musicVideo01";
        String song = "songWithCopy";

        // Nothing is stored under the alias, but it names a song whose copy is: the press is declined, not the app's.
        assertTrue(PillVideoSwitchLogic.hasSavedCopy(alias, song::equals, id -> alias.equals(id) ? song : id,
                id -> false));
        assertEquals(DECLINE_OFFLINE, PillVideoSwitchLogic.offline(true, false,
                PillVideoSwitchLogic.hasSavedCopy(alias, song::equals, id -> alias.equals(id) ? song : id,
                        id -> false)));
    }

    @Test public void aTrackServedFromARawSourceHasACopyWhateverTheStoreHolds() {
        String playing = "rawServedId1";

        assertTrue(PillVideoSwitchLogic.hasSavedCopy(playing, id -> false, id -> id, playing::equals));
    }

    @Test public void aTrackWithNoEntryNoSongCopyAndNoRawSourceHasNone() {
        String playing = "streamedOnly";

        assertFalse(PillVideoSwitchLogic.hasSavedCopy(playing, id -> false, id -> "someSong", id -> false));
        assertTrue(PillVideoSwitchLogic.hasSavedCopy(playing, playing::equals, id -> id, id -> false));
    }

    @Test public void aSabrVideoFormatWithoutAUrlIsAVideo() {
        PlayerResponse sabr = response(StreamingData.newBuilder().setServerAbrStreamingUrl("https://x.invalid/sabr")
                .addAdaptiveFormats(format("audio/webm; codecs=\"opus\""))
                .addAdaptiveFormats(format("video/mp4; codecs=\"avc1.4d401f\"")));

        assertEquals(PRESENT, PillVideoSwitchLogic.videoOf(sabr));
    }

    @Test public void aMuxedFormatCountsAndSoDoPictureDimensionsWithoutAMimeType() {
        assertEquals(PRESENT, PillVideoSwitchLogic.videoOf(response(
                StreamingData.newBuilder().addFormats(format("video/mp4")))));
        assertEquals(PRESENT, PillVideoSwitchLogic.videoOf(response(StreamingData.newBuilder()
                .addAdaptiveFormats(Format.newBuilder().setItag(134).setHeight(360)))));
    }

    @Test public void aResponseOfAudioFormatsOnlyHasNoVideo() {
        PlayerResponse audio = response(StreamingData.newBuilder()
                .addAdaptiveFormats(format("audio/mp4; codecs=\"mp4a.40.2\""))
                .addAdaptiveFormats(format("audio/webm; codecs=\"opus\"")));

        assertEquals(ABSENT, PillVideoSwitchLogic.videoOf(audio));
    }

    @Test public void aResponseWithoutFormatsSaysNothing() {
        assertEquals(UNKNOWN, PillVideoSwitchLogic.videoOf(response(StreamingData.newBuilder())));
        assertEquals(UNKNOWN, PillVideoSwitchLogic.videoOf(PlayerResponse.getDefaultInstance()));
    }
}
