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
}
