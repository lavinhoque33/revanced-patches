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

import java.io.File;

public class NativeMirrorStreamsTest {
    private static final NativeMirrorStreams.Stream VIDEO =
            new NativeMirrorStreams.Stream(134, 1700000000000001L, new File("v.video"), 100, false, "");
    private static final NativeMirrorStreams.Stream VIDEO_AUDIO =
            new NativeMirrorStreams.Stream(251, 1700000000000002L, new File("v.audio"), 50, true, "en.4");

    @Test public void itagLmtAndOptionalAudioTrackArePartOfIdentity() {
        assertEquals("songId12345.134.1700000000000001", VIDEO.key("songId12345"));
        assertEquals("songId12345.251.en.4.1700000000000002", VIDEO_AUDIO.key("songId12345"));
    }

    @Test public void requestedRangesAreBoundedByTheExactRepresentation() {
        assertTrue(VIDEO.containsRange(20, 80));
        assertTrue(VIDEO_AUDIO.containsRange(30, -1));
        assertFalse(VIDEO.containsRange(20, 81));
        assertFalse(VIDEO.containsRange(20, Long.MAX_VALUE));
        assertFalse(VIDEO_AUDIO.containsRange(80, -1));
        assertFalse(VIDEO_AUDIO.containsRange(-1, -1));
        assertFalse(VIDEO_AUDIO.containsRange(30, -2));
    }
}
