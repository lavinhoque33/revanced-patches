/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-12.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.List;

public class StreamSwapSourcesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private RawAudioFiles.Entry raw() throws Exception {
        var file = temporary.newFile();
        java.nio.file.Files.write(file.toPath(), new byte[1000]);
        return new RawAudioFiles.Entry(file, 251, "audio/webm; codecs=\"opus\"",
                1000, 123, "content://media/external/audio/media/42", "digest", file.lastModified());
    }

    private static StreamSwapSources.Source lookup(StreamSwapSources.Source source) {
        return StreamSwapSources.lookup(source.token.substring(source.token.lastIndexOf("&t=") + 3));
    }

    @Test public void nullOnlineUrlStillIssuesAndReusesALocalToken() throws Exception {
        RawAudioFiles.Entry raw = raw();
        StreamSwapSources.Source source = StreamSwapSources.issue("no_url", raw, null);
        assertNull(source.online);
        assertTrue(source.token.startsWith("http://127.0.0.1:1/rvx?v=no_url&i=251&m=raw&t="));
        assertSame(source, StreamSwapSources.issue("no_url", raw, null));
    }

    @Test public void emptySabrUrlIsNormalizedToAnAbsentFallback() throws Exception {
        StreamSwapSources.Source source = StreamSwapSources.issue("empty_url", raw(), "");
        assertNull(source.online);
        assertThrows(IOException.class, source::fallback);
    }

    @Test public void unavailableSourceWithoutOnlineUrlFailsOpenWithIOException() throws Exception {
        StreamSwapSources.Source source = StreamSwapSources.issue("missing_url", raw(), null);
        source.available = false;
        IOException error = assertThrows(IOException.class, source::fallback);
        assertTrue(error.getMessage().contains("reload player response: missing_url"));
    }

    @Test public void httpsFormatStillProvidesItsOriginalOnlineFallback() throws Exception {
        String online = "https://example.invalid/videoplayback?id=track";
        StreamSwapSources.Source source = StreamSwapSources.issue("https_url", raw(), online);
        source.available = false;
        assertEquals(online, source.fallback());
    }

    @Test public void concurrentClipsKeepTheirTokenAcrossResponseRefreshes() throws Exception {
        RawAudioFiles.Entry raw = raw();
        StreamSwapSources.Source first = StreamSwapSources.issue("busy", raw, "https://example.invalid/first");
        for (int index = 0; index < 8; index++) {
            assertSame(first, StreamSwapSources.issue("busy", raw, "https://example.invalid/" + index));
        }

        assertSame(first, lookup(first));
        assertTrue(first.available);
        assertEquals("https://example.invalid/7", first.fallback());
        assertSame(first, StreamSwapSources.issue("busy", raw, null));
    }

    @Test public void modifiedFileGetsANewTokenWithoutForgettingTheOldClipsFallback() throws Exception {
        RawAudioFiles.Entry raw = raw();
        String url = "https://example.invalid/same-stream";
        StreamSwapSources.Source first = StreamSwapSources.issue("replaced", raw, url);
        assertTrue(raw.file().setLastModified(first.served.fileModified() + 10_000));

        assertNull(StreamSwapSources.issue("replaced", raw, url));
        RawAudioFiles.Entry refreshed = new RawAudioFiles.Entry(raw.file(), raw.itag(), raw.mime(), raw.length(),
                raw.modified(), raw.muxed(), raw.digest(), raw.file().lastModified());
        StreamSwapSources.Source replacement = StreamSwapSources.issue("replaced", refreshed, url);
        assertNotSame(first, replacement);
        assertEquals(first.served.fileModified() + 10_000, replacement.served.fileModified());
        assertFalse(first.available);
        assertTrue(replacement.available);
        assertSame(first, lookup(first));
        assertEquals(url, first.fallback());
        assertSame(replacement, lookup(replacement));
    }

    private VideoCopyFiles.Entry copy() throws Exception {
        return new VideoCopyFiles.Entry(
                part(134, "video/mp4; codecs=\"avc1.4D401E\"", 1000, 123, "d1"),
                part(251, "audio/webm; codecs=\"opus\"", 400, 456, "d2"),
                temporary.newFile(), "clip00000000", "song0000000", null, 213_000);
    }

    private VideoCopyFiles.Part part(int itag, String mime, int length, long modified, String digest)
            throws Exception {
        var file = temporary.newFile();
        java.nio.file.Files.write(file.toPath(), new byte[length]);
        return new VideoCopyFiles.Part(file, itag, mime, length, modified, digest, file.lastModified());
    }

    @Test public void aVideoCopyIssuesOneTokenPerStreamAndEachResolvesToItsOwnFile() throws Exception {
        VideoCopyFiles.Entry copy = copy();
        StreamSwapSources.Source video = StreamSwapSources.issueVideo("clip00000000", copy, null);
        StreamSwapSources.Source audio = StreamSwapSources.issueVideoAudio("clip00000000", copy, null);

        assertTrue(video.token.startsWith("http://127.0.0.1:1/rvx?v=clip00000000&i=134&m=video&t="));
        assertTrue(audio.token.startsWith("http://127.0.0.1:1/rvx?v=clip00000000&i=251&m=vaudio&t="));
        assertNotSame(video, audio);
        assertSame(video, lookup(video));
        assertSame(audio, lookup(audio));
        assertSame(video, StreamSwapSources.issueVideo("clip00000000", copy, null));
        assertSame(audio, StreamSwapSources.issueVideoAudio("clip00000000", copy, null));
        assertEquals(StreamSwapSources.KIND_VIDEO, video.served.kind());
        assertEquals(copy.video().file(), video.served.file());
        assertEquals(1000, video.served.length());
        assertEquals(StreamSwapSources.KIND_VIDEO_AUDIO, audio.served.kind());
        assertEquals(copy.audio().file(), audio.served.file());
        assertEquals(400, audio.served.length());
    }

    @Test public void invalidatingAVideoDropsBothStreamsOfItsCopy() throws Exception {
        VideoCopyFiles.Entry copy = copy();
        StreamSwapSources.Source video = StreamSwapSources.issueVideo("gone00000000", copy, null);
        StreamSwapSources.Source audio = StreamSwapSources.issueVideoAudio("gone00000000", copy, null);

        StreamSwapSources.invalidate("gone00000000");

        assertFalse(video.available);
        assertFalse(audio.available);
        assertNotSame(video, StreamSwapSources.issueVideo("gone00000000", copy, null));
    }

    @Test public void issuedButDeclinedSourcesDoNotCountAsLocalPlayback() throws Exception {
        StreamSwapSources.issue("declined", raw(), null);

        assertFalse(StreamSwapSources.playsLocal("declined"));
    }

    @Test public void localDecisionDoesNotLeakToTheNextItemOrUnknownId() throws Exception {
        StreamSwapSources.Source source = StreamSwapSources.issue("current_local", raw(), null);
        StreamSwapSources.select(List.of(source));

        assertTrue(StreamSwapSources.playsLocal("current_local"));
        assertFalse(StreamSwapSources.playsLocal("next_online"));
        assertFalse(StreamSwapSources.playsLocal(""));
    }

    @Test public void nextResponseCannotHideTheCurrentItemsLocalDecision() throws Exception {
        StreamSwapSources.select(List.of(StreamSwapSources.issue("still_playing", raw(), null)));
        StreamSwapSources.select(List.of(StreamSwapSources.issue("prefetched", raw(), null)));
        StreamSwapSources.selectOnline("prefetched");

        assertTrue(StreamSwapSources.playsLocal("still_playing"));
    }

    @Test public void anOnlineReloadOfTheSameItemReplacesItsOldLocalDecision() throws Exception {
        StreamSwapSources.select(List.of(StreamSwapSources.issue("reload_online", raw(), null)));
        StreamSwapSources.selectOnline("reload_online");

        assertFalse(StreamSwapSources.playsLocal("reload_online"));
    }

    @Test public void aNewLocalResponseReplacesTheInvalidSourceOfTheSameItem() throws Exception {
        StreamSwapSources.Source old = StreamSwapSources.issue("reload_local", raw(), null);
        StreamSwapSources.select(List.of(old));
        old.available = false;
        StreamSwapSources.select(List.of(StreamSwapSources.issue("reload_local", raw(), null)));

        assertTrue(StreamSwapSources.playsLocal("reload_local"));
    }

    @Test public void aDeletedOrFallbackSourceDoesNotCountAsCurrentLocalPlayback() throws Exception {
        StreamSwapSources.Source source = StreamSwapSources.issue("fallback_seek", raw(), null);
        StreamSwapSources.select(List.of(source));
        source.available = false;

        assertFalse(StreamSwapSources.playsLocal("fallback_seek"));
    }

    @Test public void bothSelectedLocalSourcesAreInvalidatedTogether() throws Exception {
        VideoCopyFiles.Entry copy = copy();
        StreamSwapSources.Source video = StreamSwapSources.issueVideo("seek_video", copy, null);
        StreamSwapSources.Source audio = StreamSwapSources.issueVideoAudio("seek_video", copy, null);
        StreamSwapSources.select(List.of(video, audio));

        assertTrue(StreamSwapSources.playsLocal("seek_video"));

        StreamSwapSources.invalidate("seek_video");
        assertFalse(StreamSwapSources.playsLocal("seek_video"));
    }
}
