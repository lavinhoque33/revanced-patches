/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;

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

    @Test public void onlyTheLatestTokensOfATrackStayIssuedAndTheLatestStillResolves() throws Exception {
        RawAudioFiles.Entry raw = raw();
        StreamSwapSources.Source other = StreamSwapSources.issue("other_track", raw, null);
        StreamSwapSources.Source[] issued = new StreamSwapSources.Source[StreamSwapSources.TOKENS_PER_VIDEO + 2];
        for (int index = 0; index < issued.length; index++) {
            issued[index] = StreamSwapSources.issue("busy", raw, "https://example.invalid/" + index);
        }

        for (int index = 0; index < issued.length; index++) {
            boolean kept = index >= issued.length - StreamSwapSources.TOKENS_PER_VIDEO;
            assertEquals("token " + index, kept, StreamSwapSources.lookup(issued[index].key) == issued[index]);
        }
        assertSame(other, StreamSwapSources.lookup(other.key));
        assertSame(issued[issued.length - 1], StreamSwapSources.issue("busy", raw,
                "https://example.invalid/" + (issued.length - 1)));
    }

    private VideoCopyFiles.Entry video() throws Exception {
        return new VideoCopyFiles.Entry(temporary.newFile(), temporary.newFile(), "clip00000000", "song0000000",
                null, 18, "video/mp4", 1000, 123, 213_000, "digest");
    }

    @Test public void aVideoCopyGetsATokenOfItsOwnKindThatTheAudioKindCannotBeConfusedWith() throws Exception {
        VideoCopyFiles.Entry video = video();
        StreamSwapSources.Source source = StreamSwapSources.issueVideo("clip00000000", video, null);

        assertTrue(source.token.startsWith("http://127.0.0.1:1/rvx?v=clip00000000&i=18&m=video&t="));
        assertSame(source, StreamSwapSources.lookup(source.key));
        assertSame(source, StreamSwapSources.issueVideo("clip00000000", video, null));
        assertEquals(StreamSwapSources.KIND_VIDEO, source.served.kind());
        assertEquals(video.file(), source.served.file());
        assertEquals(1000, source.served.length());
    }
}
