/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.AudioConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ByteRange;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.CaptionTrack;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.CaptionTrackList;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Captions;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.MediaCommonConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.MediaUstreamerRequestConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayabilityStatus;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Status;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailEntry;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailList;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

public class OfflineResponseTrimTest {
    private static final byte[] TRACKING = {10, 3, 'u', 'r', 'l'};

    private static VideoDetails details() {
        return VideoDetails.newBuilder().setVideoId("abcdefghijk").setTitle("Song").setLengthSeconds(200)
                .setOwnerChannelName("Artist - Topic")
                .setThumbnail(ThumbnailList.newBuilder().addThumbnails(
                        ThumbnailEntry.newBuilder().setUrl("https://t/1").setWidth(544).setHeight(544))).build();
    }

    private static Format audio(int itag) {
        return Format.newBuilder().setItag(itag).setMimeType("audio/webm; codecs=\"opus\"")
                .setUrl("https://rr/stream").setSignatureCipher("s=1&url=2").setContentLength(5000)
                .setLastModified(77).setBitrate(130000)
                .setInitRange(ByteRange.newBuilder().setStart(0).setEnd(99))
                .setIndexRange(ByteRange.newBuilder().setStart(100).setEnd(199)).build();
    }

    /** A player response of the kind the app receives, with everything the trim is meant to drop. */
    private static byte[] online(boolean withExpiry) throws IOException {
        StreamingData.Builder streaming = StreamingData.newBuilder()
                .addFormats(Format.newBuilder().setItag(18).setUrl("https://muxed"))
                .addAdaptiveFormats(audio(140)).addAdaptiveFormats(audio(251))
                .addAdaptiveFormats(Format.newBuilder().setItag(248).setUrl("https://video"))
                .setHlsManifestUrl("https://hls").setServerAbrStreamingUrl("https://sabr");
        if (withExpiry) streaming.setExpiresInSeconds(21540);
        PlayerConfig config = PlayerConfig.newBuilder()
                .setAudioConfig(AudioConfig.parseFrom(loudness()).toBuilder().setMuteOnStart(false))
                .setMediaCommonConfig(MediaCommonConfig.newBuilder().setEnableSabr(true)
                        .setMediaUstreamerRequestConfig(MediaUstreamerRequestConfig.newBuilder()
                                .setVideoPlaybackUstreamerConfig(ByteString.copyFrom(new byte[]{1, 2}))))
                .build();
        PlayerResponse response = PlayerResponse.newBuilder()
                .setPlayabilityStatus(PlayabilityStatus.newBuilder().setStatus(Status.LOGIN_REQUIRED).setReason("no"))
                .setStreamingData(streaming).setVideoDetails(details()).setPlayerConfig(config)
                .setCaptions(Captions.newBuilder().setPlayerCaptionsTracklistRenderer(CaptionTrackList.newBuilder()
                        .addCaptionTracks(CaptionTrack.newBuilder().setBaseUrl("https://cc"))))
                .build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream wire = CodedOutputStream.newInstance(bytes);
        wire.writeRawBytes(response.toByteArray());
        wire.writeBytes(9, ByteString.copyFrom(TRACKING));
        wire.writeString(7, "adPlacements");
        wire.flush();
        return bytes.toByteArray();
    }

    /** An audio config field the generated message does not declare, kept as an unknown field. */
    private static byte[] loudness() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream wire = CodedOutputStream.newInstance(bytes);
        wire.writeFloat(1, -7.5f);
        wire.flush();
        return bytes.toByteArray();
    }

    private static Set<Integer> topLevelFields(byte[] message) throws IOException {
        Set<Integer> fields = new TreeSet<>();
        CodedInputStream input = CodedInputStream.newInstance(message);
        for (int tag = input.readTag(); tag != 0; tag = input.readTag()) {
            fields.add(tag >>> 3);
            input.skipField(tag);
        }
        return fields;
    }

    @Test public void keepsOnlyTheStoredAudioFormatWithoutItsUrl() throws Exception {
        PlayerResponse trimmed = PlayerResponse.parseFrom(OfflineResponseTrim.trim(online(true), 251));
        StreamingData streaming = trimmed.getStreamingData();
        assertEquals(1, streaming.getAdaptiveFormatsCount());
        assertEquals(0, streaming.getFormatsCount());
        Format kept = streaming.getAdaptiveFormats(0);
        assertEquals(251, kept.getItag());
        assertEquals("", kept.getUrl());
        assertEquals("", kept.getSignatureCipher());
        assertEquals(audio(251).toBuilder().clearUrl().clearSignatureCipher().build(), kept);
        assertFalse(streaming.hasHlsManifestUrl());
        assertFalse(streaming.hasServerAbrStreamingUrl());
        assertEquals(21540, streaming.getExpiresInSeconds());
    }

    @Test public void aResponseWithoutExpiryGetsTheDefault() throws Exception {
        PlayerResponse trimmed = PlayerResponse.parseFrom(OfflineResponseTrim.trim(online(false), 251));
        assertEquals(OfflineResponseTrim.DEFAULT_EXPIRES_SECONDS, trimmed.getStreamingData().getExpiresInSeconds());
    }

    @Test public void forcesPlayabilityOkAndKeepsEveryVideoDetail() throws Exception {
        PlayerResponse trimmed = PlayerResponse.parseFrom(OfflineResponseTrim.trim(online(true), 251));
        assertEquals(Status.OK, trimmed.getPlayabilityStatus().getStatus());
        assertTrue(trimmed.getPlayabilityStatus().hasStatus());
        assertEquals("", trimmed.getPlayabilityStatus().getReason());
        assertEquals(details(), trimmed.getVideoDetails());
    }

    @Test public void keepsLoudnessAndDropsSabrCaptionsAndTheRest() throws Exception {
        byte[] trimmedBytes = OfflineResponseTrim.trim(online(true), 251);
        PlayerResponse trimmed = PlayerResponse.parseFrom(trimmedBytes);
        assertArrayEquals(loudness(), trimmed.getPlayerConfig().getAudioConfig().toBuilder().clearMuteOnStart()
                .build().toByteArray());
        assertFalse(trimmed.getPlayerConfig().hasMediaCommonConfig());
        assertFalse(trimmed.hasCaptions());
        assertEquals(new TreeSet<>(Set.of(2, 4, 9, 11, 15)), topLevelFields(trimmedBytes));
    }

    @Test public void keepsPlaybackTrackingBytes() throws Exception {
        byte[] trimmedBytes = OfflineResponseTrim.trim(online(true), 251);
        CodedInputStream input = CodedInputStream.newInstance(trimmedBytes);
        byte[] tracking = null;
        for (int tag = input.readTag(); tag != 0; tag = input.readTag()) {
            if (tag >>> 3 == 9) tracking = input.readBytes().toByteArray();
            else input.skipField(tag);
        }
        assertArrayEquals(TRACKING, tracking);
    }

    @Test public void aResponseWithoutTheItagHasNothingToStore() throws Exception {
        assertNull(OfflineResponseTrim.trim(online(true), 250));
    }

    @Test public void theTrimmedResponseIsSmall() throws Exception {
        assertTrue(OfflineResponseTrim.trim(online(true), 251).length < OfflineCopyFiles.MAX_RESPONSE_BYTES);
    }

    @Test public void aDownloadResponseIsMinimalAndValid() throws Exception {
        Format downloaded = audio(251);
        PlayerResponse built = PlayerResponse.parseFrom(OfflineResponseTrim.fromDownload(downloaded, details()));
        assertEquals(Status.OK, built.getPlayabilityStatus().getStatus());
        assertEquals(details(), built.getVideoDetails());
        assertEquals(OfflineResponseTrim.DEFAULT_EXPIRES_SECONDS, built.getStreamingData().getExpiresInSeconds());
        assertEquals(1, built.getStreamingData().getAdaptiveFormatsCount());
        assertEquals(downloaded.toBuilder().clearUrl().clearSignatureCipher().build(),
                built.getStreamingData().getAdaptiveFormats(0));
        assertFalse(built.hasPlayerConfig());
        assertEquals(new TreeSet<>(Set.of(2, 4, 11)), topLevelFields(built.toByteArray()));
    }

    @Test public void aVideoCopysResponseKeepsTheMuxedFormatWithoutItsUrlAndTheRealLength() throws Exception {
        Format muxed = Format.newBuilder().setItag(18).setMimeType("video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
                .setUrl("https://example.invalid/v").setSignatureCipher("cipher").setBitrate(444226).build();
        VideoDetails details = VideoDetails.newBuilder().setVideoId("clip").setLengthSeconds(213).build();

        PlayerResponse stored = PlayerResponse.parseFrom(OfflineResponseTrim.fromVideoDownload(muxed, details,
                11_829_048));

        assertEquals(Status.OK, stored.getPlayabilityStatus().getStatus());
        assertEquals("clip", stored.getVideoDetails().getVideoId());
        assertEquals(0, stored.getStreamingData().getAdaptiveFormatsCount());
        assertEquals(1, stored.getStreamingData().getFormatsCount());
        Format kept = stored.getStreamingData().getFormats(0);
        assertEquals(18, kept.getItag());
        assertEquals("", kept.getUrl());
        assertEquals("", kept.getSignatureCipher());
        assertEquals(11_829_048, kept.getContentLength());
        assertEquals(444226, kept.getBitrate());
        assertEquals(OfflineResponseTrim.DEFAULT_EXPIRES_SECONDS, stored.getStreamingData().getExpiresInSeconds());
        assertTrue(VideoCopyLogic.stored(stored) != null);
    }
}
