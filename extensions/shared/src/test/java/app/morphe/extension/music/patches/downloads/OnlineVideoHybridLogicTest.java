/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-28.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ByteRange;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ExoPlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.MediaCommonConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

/** A saved raw audio keeps the online video of its own response only when that video is directly playable. */
public class OnlineVideoHybridLogicTest {
    private static final String VIDEO_ID = "J7p4bzqLvCw";
    private static final long LENGTH_MS = 200_040;
    private static final String VIDEO_URL = "https://rr1---sn.googlevideo.com/videoplayback?itag=137";

    private static Format.Builder video(int itag) {
        return Format.newBuilder().setItag(itag).setMimeType("video/mp4; codecs=\"avc1.640028\"").setUrl(VIDEO_URL)
                .setApproxDurationMs(LENGTH_MS - 40).setContentLength(9_000_000)
                .setInitRange(ByteRange.newBuilder().setStart(0).setEnd(740))
                .setIndexRange(ByteRange.newBuilder().setStart(741).setEnd(1200));
    }

    private static Format audio() {
        return Format.newBuilder().setItag(251).setMimeType("audio/webm; codecs=\"opus\"")
                .setUrl("http://127.0.0.1:1/rvx?v=" + VIDEO_ID + "&i=251&m=raw&t=key").setApproxDurationMs(LENGTH_MS)
                .setContentLength(3_000_000).build();
    }

    private static PlayerResponse response(StreamingData.Builder streaming) {
        return PlayerResponse.newBuilder()
                .setVideoDetails(VideoDetails.newBuilder().setVideoId(VIDEO_ID).setLengthSeconds(200))
                .setStreamingData(streaming.setExpiresInSeconds(21_540))
                .setPlayerConfig(PlayerConfig.newBuilder()
                        .setMediaCommonConfig(MediaCommonConfig.newBuilder().setEnableSabr(true))
                        .setExoPlayerConfig(ExoPlayerConfig.newBuilder().setUseExoPlayer(true).setForceAudioOnly(true)))
                .build();
    }

    private static OnlineVideoHybridLogic.Verdict verdict(StreamingData.Builder streaming) {
        return OnlineVideoHybridLogic.verdict(response(streaming), VIDEO_ID, audio());
    }

    @Test public void directVideoOnlyFormatsGoBesideTheSavedAudio() {
        StreamingData.Builder streaming = StreamingData.newBuilder()
                .addFormats(video(18).setMimeType("video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""))
                .addAdaptiveFormats(video(137)).addAdaptiveFormats(video(136))
                .addAdaptiveFormats(Format.newBuilder().setItag(140).setMimeType("audio/mp4").setUrl(VIDEO_URL))
                .setHlsManifestUrl("https://manifest.googlevideo.com/hls")
                .setServerAbrStreamingUrl("https://rr1---sn.googlevideo.com/sabr");
        PlayerResponse online = response(streaming);

        OnlineVideoHybridLogic.Verdict verdict = OnlineVideoHybridLogic.verdict(online, VIDEO_ID, audio());
        assertTrue(verdict.reason(), verdict.eligible());
        assertTrue(verdict.summary().contains("videoFormats=2 withUrl=2 cipher=0"));
        assertTrue(verdict.summary().contains("muxed=1"));
        assertTrue(verdict.summary().contains("itags=[137,136]"));

        PlayerResponse replacement = OnlineVideoHybridLogic.hybrid(online, verdict.video(), audio());
        StreamingData hybrid = replacement.getStreamingData();
        assertEquals(0, hybrid.getFormatsCount());
        assertEquals(List.of(137, 136, 251), hybrid.getAdaptiveFormatsList().stream().map(Format::getItag).toList());
        assertFalse(hybrid.hasHlsManifestUrl());
        assertFalse(hybrid.hasServerAbrStreamingUrl());
        assertEquals(audio(), hybrid.getAdaptiveFormats(2));
        assertFalse(replacement.getPlayerConfig().getMediaCommonConfig().hasEnableSabr());
        assertFalse(replacement.getPlayerConfig().getExoPlayerConfig().hasForceAudioOnly());
        assertTrue(replacement.getPlayerConfig().getExoPlayerConfig().getUseExoPlayer());
    }

    @Test public void urlLessSabrFormatsAreRefused() {
        OnlineVideoHybridLogic.Verdict verdict = verdict(StreamingData.newBuilder()
                .addAdaptiveFormats(video(137).clearUrl()).setServerAbrStreamingUrl("https://sabr"));

        assertFalse(verdict.eligible());
        assertEquals("no directly playable video format of the same timeline", verdict.reason());
        assertTrue(verdict.video().isEmpty());
        assertTrue(verdict.summary().contains("withUrl=0"));
    }

    @Test public void cipheredOrPlainHttpFormatsAreRefused() {
        assertFalse(verdict(StreamingData.newBuilder()
                .addAdaptiveFormats(video(137).clearUrl().setSignatureCipher("s=abc&url=x"))).eligible());
        assertFalse(verdict(StreamingData.newBuilder()
                .addAdaptiveFormats(video(137).setSignatureCipher("s=abc"))).eligible());
        assertFalse(verdict(StreamingData.newBuilder()
                .addAdaptiveFormats(video(137).setUrl("http://insecure.example/v"))).eligible());
    }

    @Test public void aFormatWithoutRangesOrOfAnotherTimelineIsLeftOut() {
        OnlineVideoHybridLogic.Verdict verdict = verdict(StreamingData.newBuilder()
                .addAdaptiveFormats(video(137).clearIndexRange())
                .addAdaptiveFormats(video(136).setApproxDurationMs(LENGTH_MS + 30_000))
                .addAdaptiveFormats(video(135)));

        assertTrue(verdict.eligible());
        assertEquals(List.of(135), verdict.video().stream().map(Format::getItag).toList());
    }

    @Test public void onlyMuxedFormatsAreRefused() {
        OnlineVideoHybridLogic.Verdict verdict = verdict(StreamingData.newBuilder()
                .addFormats(video(18)));

        assertFalse(verdict.eligible());
        assertEquals("no video-only adaptive format", verdict.reason());
    }

    @Test public void anotherCutOrVideoOrExpiredStreamsAreRefused() {
        PlayerResponse online = response(StreamingData.newBuilder().addAdaptiveFormats(video(137)));

        Format longer = audio().toBuilder().setApproxDurationMs(LENGTH_MS + 60_000).build();
        assertEquals("the saved audio is another cut",
                OnlineVideoHybridLogic.verdict(online, VIDEO_ID, longer).reason());
        assertEquals("the saved audio has no length", OnlineVideoHybridLogic.verdict(online, VIDEO_ID,
                audio().toBuilder().clearApproxDurationMs().build()).reason());
        assertEquals("the response is of another video",
                OnlineVideoHybridLogic.verdict(online, "4fxbo3k3sHM", audio()).reason());

        PlayerResponse expired = online.toBuilder().setStreamingData(online.getStreamingData().toBuilder()
                .setExpiresInSeconds(0)).build();
        assertEquals("expired streams", OnlineVideoHybridLogic.verdict(expired, VIDEO_ID, audio()).reason());

        PlayerResponse live = online.toBuilder().setVideoDetails(online.getVideoDetails().toBuilder()
                .setLengthSeconds(0)).build();
        assertEquals("no length, a live or unknown stream",
                OnlineVideoHybridLogic.verdict(live, VIDEO_ID, audio()).reason());
    }
}
