/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.LOCAL_FILE;
import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.LOCAL_FILE_AND_STREAM_COPY;
import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.SKIP;
import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.STREAM_COPY;
import static app.morphe.extension.music.patches.downloads.DownloadMode.Work.STREAM_COPY_FOR_EXISTING_FILE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DownloadModeTest {
    @Test public void theTwoSettingsAreIndependent() {
        assertEquals(DownloadMode.OFF, DownloadMode.of(false, false));
        assertEquals(DownloadMode.LOCAL_FILES, DownloadMode.of(true, false));
        assertEquals(DownloadMode.STREAM_COPY, DownloadMode.of(false, true));
        assertEquals(DownloadMode.BOTH, DownloadMode.of(true, true));
    }

    @Test public void eachModeSaysWhichKindsOfDownloadItMakes() {
        assertFalse(DownloadMode.OFF.localFiles());
        assertFalse(DownloadMode.OFF.streamCopy());
        assertTrue(DownloadMode.LOCAL_FILES.localFiles());
        assertFalse(DownloadMode.LOCAL_FILES.streamCopy());
        assertFalse(DownloadMode.STREAM_COPY.localFiles());
        assertTrue(DownloadMode.STREAM_COPY.streamCopy());
        assertTrue(DownloadMode.BOTH.localFiles());
        assertTrue(DownloadMode.BOTH.streamCopy());
    }

    @Test public void workSaysWhatItWritesAndKeeps() {
        assertFalse(SKIP.writesLocalFile());
        assertFalse(SKIP.keepsStreamCopy());
        assertTrue(LOCAL_FILE.writesLocalFile());
        assertFalse(LOCAL_FILE.keepsStreamCopy());
        assertFalse(STREAM_COPY.writesLocalFile());
        assertTrue(STREAM_COPY.keepsStreamCopy());
        assertTrue(LOCAL_FILE_AND_STREAM_COPY.writesLocalFile());
        assertTrue(LOCAL_FILE_AND_STREAM_COPY.keepsStreamCopy());
        assertFalse(STREAM_COPY_FOR_EXISTING_FILE.writesLocalFile());
        assertTrue(STREAM_COPY_FOR_EXISTING_FILE.keepsStreamCopy());
    }

    @Test public void neitherSettingDownloadsNothing() {
        for (boolean nativeTrack : new boolean[]{false, true}) {
            for (boolean copy : new boolean[]{false, true}) {
                assertEquals(SKIP, DownloadMode.OFF.plan(() -> nativeTrack, () -> copy));
            }
        }
    }

    @Test public void localFilesOnlyIgnoresStreamCopiesAndSkipsOnTheNativeTrack() {
        for (boolean copy : new boolean[]{false, true}) {
            assertEquals(LOCAL_FILE, DownloadMode.LOCAL_FILES.plan(() -> false, () -> copy));
            assertEquals(SKIP, DownloadMode.LOCAL_FILES.plan(() -> true, () -> copy));
        }
    }

    @Test public void streamCopyOnlyIgnoresLocalFilesAndSkipsOnAValidCopy() {
        for (boolean nativeTrack : new boolean[]{false, true}) {
            assertEquals(STREAM_COPY, DownloadMode.STREAM_COPY.plan(() -> nativeTrack, () -> false));
            assertEquals(SKIP, DownloadMode.STREAM_COPY.plan(() -> nativeTrack, () -> true));
        }
    }

    @Test public void bothDownloadsOnceForWhatIsMissing() {
        assertEquals(LOCAL_FILE_AND_STREAM_COPY, DownloadMode.BOTH.plan(() -> false, () -> false));
        // The file exists (saved with Download in app only): only the stream copy is made, never a second file.
        assertEquals(STREAM_COPY_FOR_EXISTING_FILE, DownloadMode.BOTH.plan(() -> true, () -> false));
        // The stream copy exists (saved with Play downloads locally only): only the file is written.
        assertEquals(LOCAL_FILE, DownloadMode.BOTH.plan(() -> false, () -> true));
        assertEquals(SKIP, DownloadMode.BOTH.plan(() -> true, () -> true));
    }

    @Test public void everyPlanThatDownloadsWritesWhatTheModeAsksFor() {
        for (DownloadMode mode : DownloadMode.values()) {
            for (boolean nativeTrack : new boolean[]{false, true}) {
                for (boolean copy : new boolean[]{false, true}) {
                    DownloadMode.Work work = mode.plan(() -> nativeTrack, () -> copy);
                    if (!mode.localFiles()) assertFalse(mode + " " + work, work.writesLocalFile());
                    if (!mode.streamCopy()) assertFalse(mode + " " + work, work.keepsStreamCopy());
                    if (mode.localFiles() && !nativeTrack) assertTrue(mode + " " + work, work.writesLocalFile());
                    if (mode.streamCopy() && !copy && work != SKIP) assertTrue(mode + " " + work, work.keepsStreamCopy());
                }
            }
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
