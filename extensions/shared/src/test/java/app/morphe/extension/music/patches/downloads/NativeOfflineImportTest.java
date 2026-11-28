/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.Set;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

public class NativeOfflineImportTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void loadsOnlyAnExplicitPairOfExactIds() throws IOException {
        assertEquals(Set.of("ATV_1234567", "OMV-1234567"), pilots("ATV_1234567\nOMV-1234567\n"));
        assertEquals(Set.of("ATV_1234567"), pilots("ATV_1234567"));
    }

    @Test public void malformedOrOversizedPilotListsFailClosed() throws IOException {
        assertEquals(Set.of(), pilots("ATV_1234567\nnot-an-id\n"));
        assertEquals(Set.of(), pilots("ATV_1234567\nOMV-1234567\nXYZ_1234567\n"));
        assertEquals(Set.of(), pilots("ATV_1234567\nATV_1234567\n"));
        assertEquals(Set.of(), pilots(""));
    }

    @Test public void holdsTheExactStockRangeIncludingAnUnboundedTail() throws IOException {
        assertEquals(20, NativeOfflineImport.rangeLength(100, 10, 20));
        assertEquals(30, NativeOfflineImport.rangeLength(100, 70, -1));
        assertEquals(0, NativeOfflineImport.rangeLength(100, 100, -1));
        assertEquals(Long.MAX_VALUE - 7, NativeOfflineImport.rangeLength(Long.MAX_VALUE, 7, -1));
    }

    @Test public void rejectsRangesThatCannotBeFromTheSelectedFormat() {
        assertThrows(IOException.class, () -> NativeOfflineImport.rangeLength(100, -1, 5));
        assertThrows(IOException.class, () -> NativeOfflineImport.rangeLength(100, 101, -1));
        assertThrows(IOException.class, () -> NativeOfflineImport.rangeLength(100, 95, 6));
        assertThrows(IOException.class, () -> NativeOfflineImport.rangeLength(100, 0, -2));
        assertThrows(IOException.class, () -> NativeOfflineImport.rangeLength(Long.MAX_VALUE, 1, Long.MAX_VALUE));
    }

    @Test public void directFileSeeksAndStopsAtTheExactRangeEnd() throws IOException {
        File file = file("download", 8);
        String key = "ATV_1234567.140.9";
        NativeOfflineImport.Transfer transfer = NativeOfflineImport.fromFile(file, null, () -> false, key, 8);
        try {
            assertThrows(IOException.class, () -> transfer.open(null, 0, 1, "another"));
            assertEquals(3, transfer.open(null, 2, 3, key));
            byte[] buffer = new byte[3];
            assertEquals(3, transfer.read(buffer, 0, 3));
            assertArrayEquals(new byte[] {2, 3, 4}, buffer);
            assertEquals(-1, transfer.read(buffer, 0, 3));
            transfer.close();
            assertEquals(2, transfer.open(null, 6, -1, key));
            assertEquals(2, transfer.read(buffer, 0, 3));
            assertArrayEquals(new byte[] {6, 7, 4}, buffer);
        } finally {
            transfer.close();
        }
        assertThrows(IOException.class, () -> NativeOfflineImport.fromFile(file, null, () -> false, key, 9));
    }

    @Test public void audioOnlyPilotRequiresGenuineMatchingCompleteAudio() throws IOException {
        Format audio = format(140, "audio/mp4", 9);
        PlayerResponse player = response("ATV_1234567", 1, audio);
        File file = file("audio", 8);
        var downloaded = new NativeOfflineDirect.Representation(audio, file);
        assertFalse(NativeOfflineDirect.needsVideo(player));
        NativeOfflineDirect.verifyInput("ATV_1234567", player, downloaded, null);
        assertThrows(IOException.class, () -> NativeOfflineDirect.verifyInput("OMV-1234567", player, downloaded, null));
        var altered = new NativeOfflineDirect.Representation(audio.toBuilder().setLastModified(10).build(), file);
        assertThrows(IOException.class, () -> NativeOfflineDirect.verifyInput("ATV_1234567", player, altered, null));
        Format noIdentity = audio.toBuilder().clearLastModified().build();
        assertThrows(IOException.class, () -> NativeOfflineDirect.verifyInput("ATV_1234567",
                response("ATV_1234567", 1, noIdentity), new NativeOfflineDirect.Representation(noIdentity, file), null));
    }

    @Test public void musicVideoRequiresTwoCompleteDistinctRepresentationsFromItsOwnResponse() throws IOException {
        Format audio = format(140, "audio/mp4", 9);
        Format video = format(134, "video/mp4", 11);
        PlayerResponse player = response("OMV-1234567", 2, audio, video);
        File audioFile = file("audio", 8);
        var completeAudio = new NativeOfflineDirect.Representation(audio, audioFile);
        var completeVideo = new NativeOfflineDirect.Representation(video, file("video", 8));
        NativeOfflineDirect.verifyInput("OMV-1234567", player, completeAudio, completeVideo);
        assertThrows(IOException.class, () -> NativeOfflineDirect.verifyInput("OMV-1234567", player, completeAudio, null));
        var shortVideo = new NativeOfflineDirect.Representation(video, file("short", 7));
        assertThrows(IOException.class, () -> NativeOfflineDirect.verifyInput("OMV-1234567", player, completeAudio, shortVideo));
        var shared = new NativeOfflineDirect.Representation(video, audioFile);
        assertThrows(IOException.class, () -> NativeOfflineDirect.verifyInput("OMV-1234567", player, completeAudio, shared));
    }

    private File file(String name, int size) throws IOException {
        File file = files.newFile(name);
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) i;
        Files.write(file.toPath(), bytes);
        return file;
    }

    private static Format format(int itag, String mime, long lastModified) {
        return Format.newBuilder().setItag(itag).setMimeType(mime).setLastModified(lastModified)
                .setContentLength(8).setUrl("https://example.invalid/stream").build();
    }

    private static PlayerResponse response(String id, int type, Format... formats) {
        return PlayerResponse.newBuilder()
                .setVideoDetails(VideoDetails.newBuilder().setVideoId(id).setMusicVideoType(type))
                .setStreamingData(StreamingData.newBuilder().addAllAdaptiveFormats(java.util.List.of(formats)))
                .build();
    }

    private static Set<String> pilots(String contents) throws IOException {
        return NativeOfflineImport.readPilots(new BufferedReader(new StringReader(contents)));
    }
}
