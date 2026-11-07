/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ByteRange;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

public class StreamSwapLogicTest {
    private static final String MIME = "audio/webm; codecs=\"opus\"";

    private RawAudioFiles.Entry raw() {
        return new RawAudioFiles.Entry(new File("track.251"), 251, MIME, 1000, 123, "muxed", "digest");
    }

    private Format.Builder audio() {
        return Format.newBuilder().setItag(251).setMimeType(MIME).setContentLength(1000).setLastModified(123)
                .setInitRange(ByteRange.newBuilder().setStart(0).setEnd(99))
                .setIndexRange(ByteRange.newBuilder().setStart(100).setEnd(199));
    }

    private PlayerResponse.Builder online() {
        return PlayerResponse.newBuilder().setVideoDetails(VideoDetails.newBuilder().setLengthSeconds(200))
                .setStreamingData(StreamingData.newBuilder().addAdaptiveFormats(audio()));
    }

    @Test public void bothSettingsAndWorkerAreRequired() {
        assertNull(StreamSwapLogic.gate(true, true, false));
        for (boolean downloads : new boolean[]{false, true}) {
            for (boolean local : new boolean[]{false, true}) {
                assertNotNull(StreamSwapLogic.gate(downloads, local, true));
                if (!downloads || !local) assertNotNull(StreamSwapLogic.gate(downloads, local, false));
            }
        }
    }

    @Test public void exactOriginalStreamCanSwap() {
        assertNull(StreamSwapLogic.mismatch(raw(), audio().build(), online().build()));
    }

    @Test public void lengthAndLastModifiedMustBePresentAndExact() {
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().clearContentLength().build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setContentLength(999).build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().clearLastModified().build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setLastModified(124).build(), online().build()));
    }

    @Test public void otherFormatsAndMimeTypesStayOnline() {
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setItag(140).build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setMimeType("audio/mp4").build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setMimeType("video/webm").build(), online().build()));
        RawAudioFiles.Entry otherItag = new RawAudioFiles.Entry(raw().file(), 140, MIME, 1000, 123, "m", "d");
        assertNotNull(StreamSwapLogic.mismatch(otherItag, audio().build(), online().build()));
        RawAudioFiles.Entry otherMime = new RawAudioFiles.Entry(raw().file(), 251, "audio/ogg", 1000, 123, "m", "d");
        assertNotNull(StreamSwapLogic.mismatch(otherMime, audio().build(), online().build()));
    }

    @Test public void liveAndSegmentedContentStaysOnline() {
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().build(), online()
                .setVideoDetails(VideoDetails.newBuilder()).build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().build(), online()
                .setStreamingData(StreamingData.newBuilder().setHlsManifestUrl("https://example/hls")).build()));
    }

    @Test public void rangesMustDescribeTheSavedBytes() {
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().clearInitRange().build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().clearIndexRange().build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setIndexRange(ByteRange.newBuilder()
                .setStart(100).setEnd(1000)).build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setInitRange(ByteRange.newBuilder()
                .setStart(-1).setEnd(99)).build(), online().build()));
        assertNotNull(StreamSwapLogic.mismatch(raw(), audio().setInitRange(ByteRange.newBuilder()
                .setStart(99).setEnd(98)).build(), online().build()));
    }

    @Test public void stockGuardPredicateRemainsAnExactHostMatch() {
        assertTrue(StreamSwapLogic.isGuardedHost("127.0.0.1"));
        assertTrue(StreamSwapLogic.isGuardedHost("localhost"));
        assertFalse(StreamSwapLogic.isGuardedHost("127.0.0.2"));
        assertFalse(StreamSwapLogic.isGuardedHost("localhost.example"));
        assertFalse(StreamSwapLogic.isGuardedHost(null));
    }
}
