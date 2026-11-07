/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class RawAudioFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String MIME = "audio/webm; codecs=\"opus\"";
    private static final String MUXED = "content://media/external/audio/media/42";

    private RawAudioFiles store() throws Exception {
        return new RawAudioFiles(new File(temporary.getRoot(), "rvx_raw"));
    }

    private File raw(String name) {
        return new File(temporary.getRoot(), "rvx_raw/" + name);
    }

    private File input() throws Exception {
        File input = temporary.newFile();
        Files.write(input.toPath(), new byte[]{0x1a, 0x45, (byte) 0xdf, (byte) 0xa3, 1, 2, 3, 4});
        return input;
    }

    private void retain(RawAudioFiles store, String id) throws Exception {
        store.retain(id, input(), 251, MIME, 8, 123, MUXED);
    }

    private void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void publishesOriginalBytesAndIdentityLast() throws Exception {
        RawAudioFiles store = store();
        File input = input();
        byte[] expected = Files.readAllBytes(input.toPath());
        store.retain("track", input, 251, MIME, 8, 123, MUXED);
        RawAudioFiles.Entry entry = store.find("track", MUXED);
        assertNotNull(entry);
        assertArrayEquals(expected, Files.readAllBytes(entry.file().toPath()));
        assertEquals(251, entry.itag());
        assertEquals(MIME, entry.mime());
        assertEquals(8, entry.length());
        assertEquals(123, entry.modified());
        assertEquals(64, entry.digest().length());
        assertFalse(input.exists());
        assertFalse(raw("track.json.part").exists());
    }

    @Test public void corruptJsonDeclinesWithoutBreakingPlayback() throws Exception {
        RawAudioFiles store = store();
        retain(store, "track");
        write(raw("track.json"), "not json");
        assertNull(store.find("track", MUXED));
    }

    @Test public void oldDownloadsAndInProgressCopiesDecline() throws Exception {
        RawAudioFiles store = store();
        assertNull(store.find("old", MUXED));
        File directory = raw("unused").getParentFile();
        assertTrue(directory.mkdir());
        Files.write(raw("track.251").toPath(), new byte[8]);
        write(raw("track.json.part"), "{}");
        assertNull(store.find("track", MUXED));
    }

    @Test public void nativeMappingIsPartOfTheIdentity() throws Exception {
        RawAudioFiles store = store();
        retain(store, "track");
        assertNull(store.find("track", null));
        assertNull(store.find("track", MUXED + "9"));
    }

    @Test public void missingAndShortFilesDecline() throws Exception {
        RawAudioFiles store = store();
        retain(store, "track");
        Files.write(raw("track.251").toPath(), new byte[7]);
        assertNull(store.find("track", MUXED));
        assertTrue(raw("track.251").delete());
        assertNull(store.find("track", MUXED));
    }

    @Test public void sameLengthCorruptionDeclines() throws Exception {
        RawAudioFiles store = store();
        retain(store, "track");
        byte[] changed = new byte[8];
        Arrays.fill(changed, (byte) 9);
        Files.write(raw("track.251").toPath(), changed);
        assertNull(store.find("track", MUXED));
    }

    @Test public void jsonIdentityMismatchDeclines() throws Exception {
        RawAudioFiles store = store();
        retain(store, "track");
        JSONObject metadata = new JSONObject(new String(
                Files.readAllBytes(raw("track.json").toPath()), StandardCharsets.UTF_8));
        metadata.put("itag", 140);
        write(raw("track.json"), metadata.toString());
        assertNull(store.find("track", MUXED));
    }

    @Test public void deletionRemovesOnlyTheTrackAndItsPendingWrites() throws Exception {
        RawAudioFiles store = store();
        retain(store, "track");
        retain(store, "other");
        write(raw("track.json.part"), "{}");
        store.delete("track");
        assertNull(store.find("track", MUXED));
        assertFalse(raw("track.251").exists());
        assertFalse(raw("track.json.part").exists());
        assertNotNull(store.find("other", MUXED));
    }

    @Test public void cleanupRetainsOnlyValidDownloadedCommits() throws Exception {
        RawAudioFiles store = store();
        retain(store, "valid");
        retain(store, "deleted");
        retain(store, "corrupt");
        Files.write(raw("corrupt.251").toPath(), new byte[8]);
        write(raw("bad.json"), "not json");
        Files.write(raw("orphan.251").toPath(), new byte[8]);
        write(raw("pending.json.part"), "{}");
        store.cleanup(id -> id.equals("deleted") ? null : MUXED);
        assertNotNull(store.find("valid", MUXED));
        String[] remaining = raw("unused").getParentFile().list();
        Arrays.sort(remaining);
        assertArrayEquals(new String[]{"valid.251", "valid.json"}, remaining);
    }

    @Test public void invalidMetadataCannotPublishAndPathTraversalCannotDelete() throws Exception {
        RawAudioFiles store = store();
        File input = input();
        store.retain("../escape", input, 251, MIME, 8, 123, MUXED);
        store.retain("track", input, 251, MIME, 7, 123, MUXED);
        store.retain("track", input, 140, "audio/mp4", 8, 123, MUXED);
        store.retain("track", input, 251, MIME, 8, 0, MUXED);
        assertTrue(input.exists());
        assertNull(store.find("track", MUXED));
        store.delete("../escape");
        assertTrue(input.exists());
    }

    @Test public void replacementInvalidatesOldMetadata() throws Exception {
        RawAudioFiles store = store();
        retain(store, "track");
        File replacement = input();
        Files.write(replacement.toPath(), new byte[8]);
        store.retain("track", replacement, 251, MIME, 8, 456, MUXED);
        assertEquals(456, store.find("track", MUXED).modified());
        assertArrayEquals(new byte[8], Files.readAllBytes(raw("track.251").toPath()));
    }
}
