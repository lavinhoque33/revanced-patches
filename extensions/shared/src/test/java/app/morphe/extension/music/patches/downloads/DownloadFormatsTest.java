/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;

public class DownloadFormatsTest {
    private static final String OPUS = "audio/webm; codecs=\"opus\"";
    private static final String AAC = "audio/mp4; codecs=\"mp4a.40.2\"";

    private static Format format(int itag, String mime, int bitrate) {
        return Format.newBuilder().setItag(itag).setMimeType(mime).setBitrate(bitrate)
                .setUrl("https://example.invalid/" + itag).build();
    }

    private static PlayerResponse adaptive(Format... formats) {
        StreamingData.Builder streaming = StreamingData.newBuilder();
        for (Format format : formats) streaming.addAdaptiveFormats(format);
        return PlayerResponse.newBuilder().setStreamingData(streaming).build();
    }

    @Test public void aSongWithAVideoDownloadsOnlyItsAudio() {
        PlayerResponse response = adaptive(
                format(313, "video/webm; codecs=\"vp9\"", 9_000_000),
                format(137, "video/mp4; codecs=\"avc1.640028\"", 4_000_000),
                format(251, OPUS, 140_000));
        assertEquals(251, DownloadFormats.bestAudio(response, true).getItag());
        assertEquals(251, DownloadFormats.bestAudio(response, false).getItag());
    }

    @Test public void aVideoOnlyResponseDownloadsNothing() {
        PlayerResponse response = adaptive(format(313, "video/webm; codecs=\"vp9\"", 9_000_000));
        assertNull(DownloadFormats.bestAudio(response, true));
        assertNull(DownloadFormats.bestAudio(response, false));
    }

    @Test public void aMuxedFormatIsNeverTaken() {
        StreamingData muxed = StreamingData.newBuilder()
                .addFormats(format(18, "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", 600_000))
                .addFormats(format(251, OPUS, 140_000)).build();
        PlayerResponse response = PlayerResponse.newBuilder().setStreamingData(muxed).build();
        assertNull(DownloadFormats.bestAudio(response, true));
        assertNull(DownloadFormats.bestAudio(response, false));
    }

    @Test public void streamOnlyPrefersTheOpusItag251EvenWhenAacHasAHigherBitrate() {
        PlayerResponse response = adaptive(format(140, AAC, 256_000), format(251, OPUS, 140_000));
        assertEquals(251, DownloadFormats.bestAudio(response, true).getItag());
    }

    @Test public void streamOnlyNeedsItag251() {
        PlayerResponse response = adaptive(format(140, AAC, 128_000), format(250, OPUS, 70_000));
        assertNull(DownloadFormats.bestAudio(response, true));
        assertEquals(140, DownloadFormats.bestAudio(response, false).getItag());
    }

    @Test public void aFormatWithoutAUrlIsSkipped() {
        Format noUrl = Format.newBuilder().setItag(251).setMimeType(OPUS).setBitrate(140_000).build();
        assertNull(DownloadFormats.bestAudio(adaptive(noUrl), true));
    }
}
