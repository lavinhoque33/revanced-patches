/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
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
        return new RawAudioFiles.Entry(temporary.newFile(), 251, "audio/webm; codecs=\"opus\"",
                1000, 123, "content://media/external/audio/media/42", "digest");
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

    @Test public void aNewOnlineFallbackKeepsTheTokenOfTheSameFile() throws Exception {
        RawAudioFiles.Entry raw = raw();
        StreamSwapSources.Source first = StreamSwapSources.issue("new_url", raw, "https://example.invalid/0");

        // Every online answer carries a new signed URL; the stream the player holds must keep its identity.
        StreamSwapSources.Source second = StreamSwapSources.issue("new_url", raw, "https://example.invalid/1");

        assertSame(first, second);
        assertEquals("https://example.invalid/1", second.fallback());
        assertSame(first, StreamSwapSources.lookup(first.key));
    }

    @Test public void aStoredAnswerWithoutUrlKeepsTheOnlineFallbackOfTheSameFile() throws Exception {
        RawAudioFiles.Entry raw = raw();
        StreamSwapSources.Source online = StreamSwapSources.issue("stored_late", raw, "https://example.invalid/a");

        assertSame(online, StreamSwapSources.issue("stored_late", raw, null));
        assertEquals("https://example.invalid/a", online.fallback());
    }

    @Test public void everyTokenOfAVideoCopyStaysResolvableAcrossManyAnswers() throws Exception {
        VideoCopyFiles.Entry copy = copy();
        StreamSwapSources.Source video = StreamSwapSources.issueVideo("switch00000", copy,
                "https://example.invalid/v0");
        StreamSwapSources.Source audio = StreamSwapSources.issueVideoAudio("switch00000", copy,
                "https://example.invalid/a0");

        // Song and Video flips, seeks and late answers re-swap the same response many times.
        for (int answer = 1; answer <= 10; answer++) {
            assertSame(video, StreamSwapSources.issueVideo("switch00000", copy, "https://example.invalid/v" + answer));
            assertSame(audio, StreamSwapSources.issueVideoAudio("switch00000", copy,
                    "https://example.invalid/a" + answer));
        }

        assertSame(video, StreamSwapSources.lookup(video.key));
        assertSame(audio, StreamSwapSources.lookup(audio.key));
        assertTrue(video.available);
        assertTrue(audio.available);
    }

    @Test public void aReplacedFileGetsANewTokenAndRetiresTheOldOne() throws Exception {
        RawAudioFiles.Entry raw = raw();
        StreamSwapSources.Source old = StreamSwapSources.issue("replaced", raw, null);
        assertTrue(raw.file().setLastModified(old.served.fileModified() + 10_000));

        StreamSwapSources.Source fresh = StreamSwapSources.issue("replaced", raw, null);

        assertNotSame(old, fresh);
        assertFalse(old.available);
        assertTrue(fresh.available);
    }

    @Test public void otherTracksKeepTheirTokens() throws Exception {
        RawAudioFiles.Entry raw = raw();
        StreamSwapSources.Source other = StreamSwapSources.issue("other_track", raw, null);
        StreamSwapSources.issue("busy_track", raw, "https://example.invalid/0");
        StreamSwapSources.issue("busy_track", raw, "https://example.invalid/1");

        assertSame(other, StreamSwapSources.lookup(other.key));
    }

    private VideoCopyFiles.Entry copy() throws Exception {
        return new VideoCopyFiles.Entry(
                new VideoCopyFiles.Part(temporary.newFile(), 134, "video/mp4; codecs=\"avc1.4D401E\"", 1000, 123, "d1"),
                new VideoCopyFiles.Part(temporary.newFile(), 251, "audio/webm; codecs=\"opus\"", 400, 456, "d2"),
                temporary.newFile(), "clip00000000", "song0000000", null, 213_000);
    }

    @Test public void aVideoCopyIssuesOneTokenPerStreamAndEachResolvesToItsOwnFile() throws Exception {
        VideoCopyFiles.Entry copy = copy();
        StreamSwapSources.Source video = StreamSwapSources.issueVideo("clip00000000", copy, null);
        StreamSwapSources.Source audio = StreamSwapSources.issueVideoAudio("clip00000000", copy, null);

        assertTrue(video.token.startsWith("http://127.0.0.1:1/rvx?v=clip00000000&i=134&m=video&t="));
        assertTrue(audio.token.startsWith("http://127.0.0.1:1/rvx?v=clip00000000&i=251&m=vaudio&t="));
        assertNotSame(video, audio);
        assertSame(video, StreamSwapSources.lookup(video.key));
        assertSame(audio, StreamSwapSources.lookup(audio.key));
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
        assertFalse(OfflineReplayLogic.dropsSeekSnap(true, true,
                StreamSwapSources.playsLocal("next_online"), 0));
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
        assertFalse(OfflineReplayLogic.dropsSeekSnap(true, true,
                StreamSwapSources.playsLocal("reload_online"), -1));
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

    @Test public void bothPillsOfAVideoCopyKeepTheSeekTargetOnline() throws Exception {
        VideoCopyFiles.Entry copy = copy();
        StreamSwapSources.Source video = StreamSwapSources.issueVideo("seek_video", copy, null);
        StreamSwapSources.Source audio = StreamSwapSources.issueVideoAudio("seek_video", copy, null);
        StreamSwapSources.select(List.of(video, audio));

        assertTrue(OfflineReplayLogic.dropsSeekSnap(true, true, StreamSwapSources.playsLocal("seek_video"), 0));
        assertTrue(OfflineReplayLogic.dropsSeekSnap(true, true, StreamSwapSources.playsLocal("seek_video"), -1));

        StreamSwapSources.invalidate("seek_video");
        assertFalse(StreamSwapSources.playsLocal("seek_video"));
    }
}
