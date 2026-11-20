/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-19.
 */

package app.morphe.extension.youtube.patches.general.offline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

import app.morphe.extension.youtube.patches.general.offline.OfflineHls.Segment;

public class OfflineHlsTest {
    private static final String PLAYLIST = "https://example.org/premium/index.m3u8";

    @Test
    public void resolvesInitAndMediaByteRangesInOrder() throws IOException {
        String manifest = """
                #EXTM3U
                #EXT-X-MAP:URI="video.mp4",BYTERANGE="100@0"
                #EXT-X-BYTERANGE:500@100
                video.mp4
                #EXT-X-BYTERANGE:400
                video.mp4
                #EXT-X-ENDLIST
                """;
        String uri = "https://example.org/premium/video.mp4";

        assertEquals(List.of(new Segment(uri, 0, 100), new Segment(uri, 100, 500), new Segment(uri, 600, 400)),
                OfflineHls.parseMediaPlaylist(manifest, PLAYLIST).segments());
    }

    @Test
    public void rejectsEncryptedMedia() {
        String manifest = """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key"
                segment.mp4
                """;

        assertThrows(IOException.class, () -> OfflineHls.parseMediaPlaylist(manifest, PLAYLIST));
    }
}
