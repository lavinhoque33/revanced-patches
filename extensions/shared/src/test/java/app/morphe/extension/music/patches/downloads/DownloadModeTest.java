/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 * Modified by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.LOCAL_FILE;
import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.SKIP;
import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.STREAM_COPY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DownloadModeTest {
    @Test public void aSongMethodMapsToOneMode() {
        assertEquals(DownloadMode.STREAM_COPY, DownloadMode.ofSong(SongDownloadMethod.STREAM, true));
        assertEquals(DownloadMode.LOCAL_FILES, DownloadMode.ofSong(SongDownloadMethod.DEVICE_FILE, true));
        assertEquals(DownloadMode.OFF, DownloadMode.ofSong(SongDownloadMethod.EXTERNAL_APP, true));
    }

    @Test public void aPlaylistMethodMapsToOneMode() {
        assertEquals(DownloadMode.STREAM_COPY, DownloadMode.ofPlaylist(PlaylistDownloadMethod.STREAM, true));
        assertEquals(DownloadMode.LOCAL_FILES, DownloadMode.ofPlaylist(PlaylistDownloadMethod.DEVICE_FILES, true));
        assertEquals(DownloadMode.OFF, DownloadMode.ofPlaylist(PlaylistDownloadMethod.MUSIC_OWN, true));
    }

    @Test public void theStreamMethodNeedsTheSwapHooksAndLeavesTheDownloadToTheAppWithoutThem() {
        assertEquals(DownloadMode.OFF, DownloadMode.ofSong(SongDownloadMethod.STREAM, false));
        assertEquals(DownloadMode.OFF, DownloadMode.ofPlaylist(PlaylistDownloadMethod.STREAM, false));
        assertEquals(DownloadMode.LOCAL_FILES, DownloadMode.ofSong(SongDownloadMethod.DEVICE_FILE, false));
    }

    @Test public void noModeMakesBothKindsOfDownload() {
        for (DownloadMode mode : DownloadMode.values()) {
            assertFalse(mode.toString(), mode.localFiles() && mode.streamCopy());
        }
        assertTrue(DownloadMode.LOCAL_FILES.localFiles());
        assertTrue(DownloadMode.STREAM_COPY.streamCopy());
        assertFalse(DownloadMode.OFF.localFiles());
        assertFalse(DownloadMode.OFF.streamCopy());
    }

    @Test public void workSaysWhatItWritesAndKeeps() {
        assertFalse(SKIP.writesLocalFile());
        assertFalse(SKIP.keepsStreamCopy());
        assertTrue(LOCAL_FILE.writesLocalFile());
        assertFalse(LOCAL_FILE.keepsStreamCopy());
        assertFalse(STREAM_COPY.writesLocalFile());
        assertTrue(STREAM_COPY.keepsStreamCopy());
    }

    @Test public void offDownloadsNothing() {
        for (boolean nativeTrack : new boolean[]{false, true}) {
            for (boolean copy : new boolean[]{false, true}) {
                assertEquals(SKIP, DownloadMode.OFF.plan(() -> nativeTrack, () -> copy));
            }
        }
    }

    @Test public void localFilesIgnoreStreamCopiesAndSkipOnTheNativeTrack() {
        for (boolean copy : new boolean[]{false, true}) {
            assertEquals(LOCAL_FILE, DownloadMode.LOCAL_FILES.plan(() -> false, () -> copy));
            assertEquals(SKIP, DownloadMode.LOCAL_FILES.plan(() -> true, () -> copy));
        }
    }

    @Test public void streamCopyIgnoresLocalFilesAndSkipsOnAValidCopy() {
        for (boolean nativeTrack : new boolean[]{false, true}) {
            assertEquals(STREAM_COPY, DownloadMode.STREAM_COPY.plan(() -> nativeTrack, () -> false));
            assertEquals(SKIP, DownloadMode.STREAM_COPY.plan(() -> nativeTrack, () -> true));
        }
    }

    @Test public void onlyTheStoresTheModeCaresAboutAreAsked() {
        List<String> asked = new ArrayList<>();
        DownloadMode.LOCAL_FILES.plan(() -> {
            asked.add("native");
            return false;
        }, () -> {
            asked.add("stream");
            return false;
        });
        assertEquals(List.of("native"), asked);

        asked.clear();
        DownloadMode.STREAM_COPY.plan(() -> {
            asked.add("native");
            return false;
        }, () -> {
            asked.add("stream");
            return false;
        });
        assertEquals(List.of("stream"), asked);

        asked.clear();
        DownloadMode.OFF.plan(() -> {
            asked.add("native");
            return false;
        }, () -> {
            asked.add("stream");
            return false;
        });
        assertEquals(List.of(), asked);
    }
}
