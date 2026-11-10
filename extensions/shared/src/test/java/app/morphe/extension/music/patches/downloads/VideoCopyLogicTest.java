/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;

public class VideoCopyLogicTest {
    private static final String MP4 = "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"";

    private static Format.Builder muxed() {
        return Format.newBuilder().setItag(18).setMimeType(MP4).setUrl("https://example.invalid/v").setBitrate(444226);
    }

    private static PlayerResponse response(Format... formats) {
        StreamingData.Builder streaming = StreamingData.newBuilder();
        for (Format format : formats) streaming.addFormats(format);
        return PlayerResponse.newBuilder().setStreamingData(streaming).build();
    }

    @Test public void onlyTheMuxedMp4OfItag18IsTheVideoCopy() {
        assertTrue(VideoCopyLogic.supportedVideo(18, MP4));
        assertFalse(VideoCopyLogic.supportedVideo(137, "video/mp4; codecs=\"avc1.640028\""));
        assertFalse(VideoCopyLogic.supportedVideo(313, "video/webm; codecs=\"vp9\""));
        assertFalse(VideoCopyLogic.supportedVideo(18, "video/webm"));
        assertFalse(VideoCopyLogic.supportedVideo(18, null));
    }

    @Test public void theMuxedFormatIsTakenFromFormatsAndNeverFromAdaptiveFormats() {
        Format video = Format.newBuilder().setItag(137).setMimeType("video/mp4").setUrl("https://example.invalid/a")
                .build();
        PlayerResponse adaptiveOnly = PlayerResponse.newBuilder().setStreamingData(
                StreamingData.newBuilder().addAdaptiveFormats(muxed()).addAdaptiveFormats(video)).build();

        assertNull(VideoCopyLogic.muxed(adaptiveOnly));
        assertNull(VideoCopyLogic.muxed(response(video)));
        assertNotNull(VideoCopyLogic.muxed(response(video, muxed().build())));
    }

    @Test public void aMuxedFormatWithoutAPlainUrlIsNotDownloadableButIsTheStoredOne() {
        PlayerResponse withoutUrl = response(muxed().clearUrl().build());

        assertNull(VideoCopyLogic.muxed(withoutUrl));
        assertNotNull(VideoCopyLogic.stored(withoutUrl));
    }

    @Test public void theCopyIsAnnouncedWithTheLocalTokenTheRealLengthAndTheCopysDuration() {
        Format local = VideoCopyLogic.local(muxed().clearUrl().setContentLength(1).build(), "http://127.0.0.1:1/rvx",
                11_829_048, 213_000);

        assertEquals("http://127.0.0.1:1/rvx", local.getUrl());
        assertEquals(11_829_048, local.getContentLength());
        assertEquals(213_000, local.getApproxDurationMs());
        assertEquals(18, local.getItag());
        assertEquals(444226, local.getBitrate());
    }

    @Test public void aDurationTheFormatAlreadyHasIsKept() {
        Format local = VideoCopyLogic.local(muxed().setApproxDurationMs(213_400).build(), "t", 10, 99_000);

        assertEquals(213_400, local.getApproxDurationMs());
    }

    @Test public void theOnlineLengthMustMatchTheCopyOrBeUnknown() {
        assertTrue(VideoCopyLogic.sameLength(213, 213_000));
        assertTrue(VideoCopyLogic.sameLength(216, 213_000));
        assertFalse(VideoCopyLogic.sameLength(217, 213_000));
        assertTrue(VideoCopyLogic.sameLength(0, 213_000));
        assertTrue(VideoCopyLogic.sameLength(213, 0));
    }

    @Test public void theCopyNeedsItsLengthPlusTheReserveFree() {
        long reserve = VideoCopyLogic.MIN_FREE_BYTES_AFTER;

        assertTrue(VideoCopyLogic.hasRoomFor(reserve + 11_829_048, 11_829_048));
        assertFalse(VideoCopyLogic.hasRoomFor(reserve + 11_829_047, 11_829_048));
    }

    @Test public void theFinishedTextNamesTheVideosOnlyWhenTheListHasSome() {
        assertEquals("morphe_music_playlist_stream_complete", VideoCopyLogic.completionKey(5, 0, 0));
        assertEquals("morphe_music_playlist_stream_complete_videos", VideoCopyLogic.completionKey(5, 2, 3));
        assertEquals("morphe_music_playlist_stream_complete_videos", VideoCopyLogic.completionKey(0, 2, 3));
        assertEquals("morphe_music_playlist_stream_complete_videos", VideoCopyLogic.completionKey(5, 0, 3));
    }

    @Test public void aListIsAlreadySavedOnlyWhenNeitherAudioNorVideoWasSavedNow() {
        assertEquals("morphe_music_playlist_stream_already_saved", VideoCopyLogic.completionKey(0, 0, 0));
        assertEquals("morphe_music_playlist_stream_already_saved_videos", VideoCopyLogic.completionKey(0, 0, 3));
    }
}
