/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Set;

public class VideoCopyFilesTest {
    private static final String MP4 = "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"";
    private static final byte[] RESPONSE = {1, 2, 3, 4};

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private VideoCopyFiles files;

    @Before public void createFiles() throws Exception {
        files = new VideoCopyFiles(new File(temporary.getRoot(), "rvx_video"));
    }

    private File download(int length) throws Exception {
        File file = temporary.newFile();
        Files.write(file.toPath(), new byte[length]);
        return file;
    }

    private boolean retain(String video, String song, int length) throws Exception {
        return files.retain(video, song, "PLlist", download(length), 18, MP4, length, 123, 213_000, RESPONSE);
    }

    @Test public void aRetainedCopyIsFoundWithItsPairAndItsStoredResponse() throws Exception {
        assertTrue(retain("omvOne00000", "atvOne00000", 100));

        VideoCopyFiles.Entry entry = files.find("omvOne00000");
        assertNotNull(entry);
        assertEquals("atvOne00000", entry.songId());
        assertEquals("PLlist", entry.sourceId());
        assertEquals(100, entry.length());
        assertEquals(213_000, entry.durationMs());
        assertEquals(18, entry.itag());
        assertArrayEquals(RESPONSE, files.response("omvOne00000"));
        assertEquals("omvOne00000", files.videoOf("atvOne00000"));
        assertTrue(files.has("omvOne00000"));
        assertEquals(1, files.stats().count());
        assertEquals(100, files.stats().bytes());
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.Assert.assertArrayEquals(expected, actual);
    }

    @Test public void theCommittedPairsAreReadBackFromTheDisk() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);

        VideoCopyFiles reopened = new VideoCopyFiles(new File(temporary.getRoot(), "rvx_video"));
        assertFalse(reopened.has("omvOne00000"));
        reopened.ensureLoaded();

        assertTrue(reopened.has("omvOne00000"));
        assertEquals("atvOne00000", reopened.pairs().get("omvOne00000"));
    }

    @Test public void aCopyThatIsNotAnMp4OfItag18IsRefused() throws Exception {
        assertFalse(files.retain("omvOne00000", "atvOne00000", null, download(10), 137, MP4, 10, 1, 1, RESPONSE));
        assertFalse(files.retain("omvOne00000", "atvOne00000", null, download(10), 18, "video/webm", 10, 1, 1,
                RESPONSE));
        assertFalse(files.has("omvOne00000"));
    }

    @Test public void aFileOfAnotherLengthThanTheRecordedOneIsRefused() throws Exception {
        assertFalse(files.retain("omvOne00000", "atvOne00000", null, download(10), 18, MP4, 11, 1, 1, RESPONSE));
        assertFalse(files.retain("omvOne00000", "atvOne00000", null, download(10), 18, MP4, 10, 1, 1, new byte[0]));
    }

    @Test public void aChangedFileIsNotPlayed() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);
        File video = files.find("omvOne00000").file();

        Files.write(video.toPath(), new byte[100]);
        assertNotNull(files.find("omvOne00000"));

        byte[] other = new byte[100];
        other[50] = 1;
        Files.write(video.toPath(), other);
        assertNull(files.find("omvOne00000"));
    }

    @Test public void aTruncatedFileIsNoCopy() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);
        File video = files.find("omvOne00000").file();

        Files.write(video.toPath(), new byte[40]);

        assertNull(files.find("omvOne00000"));
        assertEquals(0, files.stats().count());
    }

    @Test public void deletingACopyRemovesItsFilesAndItsPair() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);

        files.delete("omvOne00000");

        assertFalse(files.has("omvOne00000"));
        assertNull(files.videoOf("atvOne00000"));
        assertNull(files.find("omvOne00000"));
        assertEquals(0, new File(temporary.getRoot(), "rvx_video").list().length);
    }

    @Test public void clearingRemovesEveryCopyAndCountsThem() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);
        retain("omvTwo00000", "atvTwo00000", 50);

        assertEquals(2, files.clear());
        assertEquals(0, files.stats().count());
        assertTrue(files.pairs().isEmpty());
    }

    @Test public void theOrphanCleanupKeepsOnlyTheCopiesOfSongsThatStillHaveAudio() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);
        retain("omvTwo00000", "atvTwo00000", 50);

        Set<String> removed = files.retainOnly(Set.of("atvOne00000"), System.currentTimeMillis() + 60_000);

        assertEquals(Set.of("omvTwo00000"), removed);
        assertTrue(files.has("omvOne00000"));
        assertFalse(files.has("omvTwo00000"));
        assertNotNull(files.find("omvOne00000"));
    }

    @Test public void theOrphanCleanupRemovesLeftoversButNotFilesPublishedAfterTheIdsWereRead() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);
        File directory = new File(temporary.getRoot(), "rvx_video");
        File leftover = new File(directory, "omvTwo00000.resp.part");
        Files.write(leftover.toPath(), new byte[3]);

        files.retainOnly(Set.of("atvOne00000"), 0);
        assertTrue("newer than the read, kept", leftover.exists());

        files.retainOnly(Set.of("atvOne00000"), System.currentTimeMillis() + 60_000);
        assertFalse(leftover.exists());
        assertTrue(files.has("omvOne00000"));
    }

    @Test public void aReplacedCopyForAnotherSongTakesOverThePair() throws Exception {
        retain("omvOne00000", "atvOne00000", 100);
        retain("omvOne00000", "atvTwo00000", 120);

        assertEquals("atvTwo00000", files.find("omvOne00000").songId());
        assertNull(files.videoOf("atvOne00000"));
        assertEquals(120, files.stats().bytes());
    }
}
