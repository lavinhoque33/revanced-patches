/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-19.
 */

package app.morphe.extension.youtube.patches.general.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.youtube.patches.general.offline.OfflineFormats.Resolved;
import app.morphe.extension.youtube.patches.general.offline.OfflineFormats.Stream;

public class OfflineFormatsTest {
    @Test
    public void forbiddenRetryKeepsQualityCodecAndContainer() {
        Stream selected = video(248, "video/webm", OfflineFormats.CODEC_VP9, 30, true, false);
        Stream alternate = video(616, "video/mp4", OfflineFormats.CODEC_VP9, 30, true, true);
        Stream normalVp9 = video(247, "video/webm", OfflineFormats.CODEC_VP9, 30, false, false);
        Stream fastVp9 = video(303, "video/webm", OfflineFormats.CODEC_VP9, 60, true, false);
        Stream avc = video(137, "video/mp4", OfflineFormats.CODEC_H264, 30, true, false);
        Stream aac = audio(140, "audio/mp4", OfflineFormats.CODEC_AAC);
        Stream opus = audio(251, "audio/webm", OfflineFormats.CODEC_OPUS);
        Resolved response = response(List.of(avc, normalVp9, fastVp9, alternate), aac, opus);

        assertSame(alternate, response.findMatching(selected));
        assertEquals("webm", alternate.fileExtension());
        assertSame(opus, response.audioFor(alternate));
        assertEquals("mp4", avc.fileExtension());
        assertSame(aac, response.audioFor(avc));
        assertNull(response(List.of(avc, normalVp9, fastVp9), aac, opus).findMatching(selected));
    }

    @Test
    public void audioRetryDoesNotSubstituteAnotherCodec() {
        Stream selected = audio(251, "audio/webm", OfflineFormats.CODEC_OPUS);
        Stream alternate = audio(250, "audio/webm", OfflineFormats.CODEC_OPUS);
        Stream aac = audio(140, "audio/mp4", OfflineFormats.CODEC_AAC);

        assertSame(alternate, response(List.of(), aac, alternate).findMatching(selected));
        assertEquals("opus", alternate.fileExtension());
        assertEquals("m4a", aac.fileExtension());
        assertNull(response(List.of(), aac, null).findMatching(selected));
    }

    private static Resolved response(List<Stream> videos, Stream aac, Stream opus) {
        return new Resolved("video", 1, null, null, "Title", "Channel", "channel", 1000,
                null, videos, aac, opus, null);
    }

    private static Stream video(int itag, String mimeType, String codec, int fps, boolean premium, boolean hls) {
        return new Stream("https://example.org/" + itag, itag, mimeType, 1000, 1080, fps, codec,
                1_000_000, true, premium, hls);
    }

    private static Stream audio(int itag, String mimeType, String codec) {
        return new Stream("https://example.org/" + itag, itag, mimeType, 1000, 0, 0, codec,
                128_000, true, false, false);
    }
}
