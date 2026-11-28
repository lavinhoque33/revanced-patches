/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import com.google.protobuf.CodedOutputStream;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.DownloadAudioTrack;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

public class NativeMirrorProtoTest {
    @Test public void playlistDataHasIdThumbnailTitleAndTime() throws Exception {
        byte[] data = NativeMirrorProto.playlistData("PLabc", "Road trip", "https://i/x.jpg", 1_700_000_000L);

        assertEquals(List.of(1, 2, 5, 7), ProtoFields.numbers(data));
        assertEquals("PLabc", ProtoFields.string(data, 1));
        assertEquals("Road trip", ProtoFields.string(data, 5));
        assertEquals(1_700_000_000L, ProtoFields.varint(data, 7));

        byte[] thumbnail = ProtoFields.one(ProtoFields.one(data, 2), 1);
        assertEquals("https://i/x.jpg", ProtoFields.string(thumbnail, 1));
    }

    @Test public void playlistDataWithoutThumbnailUrlHasNoThumbnail() throws Exception {
        byte[] data = NativeMirrorProto.playlistData("OLAKxyz", "Album", "", 5);

        assertFalse(ProtoFields.has(data, 2));
        assertEquals("OLAKxyz", ProtoFields.string(data, 1));
    }

    @Test public void mirroredResponseHasExactFormatsButNoOfflinePolicy() throws Exception {
        Format localVideo = Format.newBuilder().setItag(134).setContentLength(20).setLastModified(100).build();
        Format localAudio = Format.newBuilder().setItag(251).setContentLength(10).setLastModified(200).build();
        Format online = Format.newBuilder().setItag(140).build();
        PlayerResponse source = PlayerResponse.newBuilder()
                .setVideoDetails(VideoDetails.newBuilder().setVideoId("abc").setChannelId("UC1"))
                .setStreamingData(StreamingData.newBuilder().addAdaptiveFormats(online)).build();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        source.writeTo(bytes);
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeByteArray(14, new byte[] { 24, 5 });
        output.flush();
        assertTrue(ProtoFields.has(bytes.toByteArray(), 14));

        PlayerResponse mirrored = NativeMirrorProto.playerResponse("abc", PlayerResponse.parseFrom(bytes.toByteArray()),
                StreamingData.newBuilder().addAdaptiveFormats(localVideo).addAdaptiveFormats(localAudio));
        assertEquals(source.getVideoDetails(), mirrored.getVideoDetails());
        assertEquals(List.of(localVideo, localAudio), mirrored.getStreamingData().getAdaptiveFormatsList());
        assertFalse(ProtoFields.has(mirrored.toByteArray(), 14));
    }

    @Test public void nativeCacheUsesItsFlatTrackIdentity() {
        Format nested = Format.newBuilder().setItag(251)
                .setAudioTrack(DownloadAudioTrack.newBuilder().setId("en.4")).build();
        assertEquals("en.4", NativeMirrorProto.indexFormat(nested, true).getNativeAudioTrackId());
        Format flat = nested.toBuilder().setNativeAudioTrackId("es.4").build();
        assertEquals(flat, NativeMirrorProto.indexFormat(flat, true));
        assertEquals(nested, NativeMirrorProto.indexFormat(nested, false));
    }
}
