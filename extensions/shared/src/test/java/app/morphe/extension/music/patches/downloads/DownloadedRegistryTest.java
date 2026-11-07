/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import com.google.protobuf.CodedOutputStream;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class DownloadedRegistryTest {
    private static final String SONG = "dQw4w9WgXcQ";
    private static final String OTHER = "9bZkp7q19f0";
    private static final String THIRD = "kJQP7kiw5Fk";

    private static byte[] message(Object... fields) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        for (int index = 0; index < fields.length; index += 2) {
            int number = (Integer) fields[index];
            if (fields[index + 1] instanceof byte[] nested) output.writeByteArray(number, nested);
            else output.writeString(number, (String) fields[index + 1]);
        }
        output.flush();
        return bytes.toByteArray();
    }

    private static DownloadedRegistry registryOf(String... videoIds) {
        DownloadedRegistry registry = new DownloadedRegistry();
        registry.addAll(Arrays.asList(videoIds));
        return registry;
    }

    @Test public void addAndRemoveTrackMembership() {
        DownloadedRegistry registry = new DownloadedRegistry();
        assertFalse(registry.hasTracks());

        registry.add(SONG);
        assertTrue(registry.hasTracks());
        assertTrue(registry.isDownloaded(SONG));
        assertFalse(registry.isDownloaded(OTHER));

        registry.remove(SONG);
        assertFalse(registry.isDownloaded(SONG));
        assertFalse(registry.hasTracks());
    }

    @Test public void pruneForgetsOnlyWhatWasKnownBeforeTheCheckAndIsGone() {
        DownloadedRegistry registry = registryOf(SONG, OTHER);
        Set<String> knownBefore = registry.snapshot();

        // A download finishes while MediaStore is being asked: it is not in the answer, and must survive.
        registry.add(THIRD);
        registry.prune(knownBefore, Collections.singleton(SONG));

        assertTrue(registry.isDownloaded(SONG));
        assertFalse(registry.isDownloaded(OTHER));
        assertTrue(registry.isDownloaded(THIRD));
    }

    @Test public void loadingAfterConcurrentAddsLosesNothing() throws Exception {
        DownloadedRegistry registry = new DownloadedRegistry();
        List<String> remembered = new ArrayList<>();
        for (int index = 0; index < 500; index++) remembered.add(String.format("track%06d", index));
        CountDownLatch go = new CountDownLatch(1);
        Thread loader = new Thread(() -> {
            await(go);
            registry.addAll(remembered);
        });
        Thread downloader = new Thread(() -> {
            await(go);
            for (int index = 0; index < 500; index++) registry.add(String.format("later%06d", index));
        });
        loader.start();
        downloader.start();
        go.countDown();
        loader.join();
        downloader.join();

        assertEquals(1000, registry.snapshot().size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test public void aSourceIsDownloadedOnlyWhileEveryTrackIs() {
        DownloadedRegistry registry = registryOf(SONG, OTHER, THIRD);
        registry.putSource("OLAK5uy_album", Arrays.asList(SONG, OTHER, THIRD));
        assertTrue(registry.isSourceDownloaded("OLAK5uy_album"));

        registry.remove(OTHER);
        assertFalse(registry.isSourceDownloaded("OLAK5uy_album"));

        registry.add(OTHER);
        assertTrue(registry.isSourceDownloaded("OLAK5uy_album"));
    }

    @Test public void aSourceWithoutRecordOrTracksIsNeverDownloaded() {
        DownloadedRegistry registry = registryOf(SONG);
        registry.putSource("empty", Collections.emptyList());

        assertFalse(registry.isSourceDownloaded("empty"));
        assertFalse(registry.isSourceDownloaded("PLunknown"));
        assertFalse(registry.isSourceDownloaded(null));
    }

    @Test public void repeatedTracksOfAPlaylistNeedBeDownloadedOnce() {
        DownloadedRegistry registry = registryOf(SONG);
        registry.putSource("PLrepeat", Arrays.asList(SONG, SONG));

        assertTrue(registry.isSourceDownloaded("PLrepeat"));
    }

    @Test public void aRememberedSourceIsACopy() {
        DownloadedRegistry registry = registryOf(SONG, OTHER);
        List<String> tracks = new ArrayList<>(Arrays.asList(SONG, OTHER));
        registry.putSource("PLcopy", tracks);
        tracks.add(THIRD);

        assertTrue(registry.isSourceDownloaded("PLcopy"));
        registry.removeSource("PLcopy");
        assertFalse(registry.hasSource("PLcopy"));
    }

    @Test public void scannerFindsADownloadedIdAnywhereInTheModel() throws Exception {
        byte[] navigation = message(1, SONG);
        byte[] menu = message(5, "unrelated", 7, navigation);
        byte[] row = message(1, "title", 2, menu, 9, "subtitle");

        assertEquals(SONG, registryOf(SONG).findDownloaded(row));
    }

    @Test public void scannerIgnoresIdsThatAreNotDownloaded() throws Exception {
        byte[] row = message(1, message(1, OTHER), 2, "plain text here");

        assertNull(registryOf(SONG).findDownloaded(row));
        assertNull(new DownloadedRegistry().findDownloaded(row));
    }

    @Test public void scannerOnlyReadsWholeElevenCharacterStrings() throws Exception {
        // The id is part of a longer string (not length 11): not a video id field.
        byte[] longer = message(1, "x" + SONG + "y");
        // A downloaded id whose characters are not even valid cannot match.
        byte[] punctuation = message(1, "dQw4w9WgXc!");

        DownloadedRegistry registry = registryOf(SONG, "dQw4w9WgXc!");
        assertNull(registry.findDownloaded(longer));
        assertNull(registry.findDownloaded(punctuation));
    }

    @Test public void scannerReachesTheLastBytesAndPicksTheDownloadedOneOfSeveral() throws Exception {
        byte[] row = message(1, OTHER, 2, THIRD, 3, SONG);

        assertEquals(SONG, registryOf(SONG).findDownloaded(row));
        assertNull(registryOf(SONG).findDownloaded(Arrays.copyOf(row, row.length - 1)));
        assertNull(registryOf(SONG).findDownloaded(new byte[0]));
    }

    @Test public void earlierPlaylistsAreRecoveredOnlyWhenEveryTrackIsADownload() {
        Map<Long, String> videoIdByMedia = new HashMap<>();
        videoIdByMedia.put(10L, SONG);
        videoIdByMedia.put(11L, OTHER);

        assertEquals(Arrays.asList(OTHER, SONG, OTHER),
                DownloadedRegistry.resolveTracks(Arrays.asList(11L, 10L, 11L), videoIdByMedia));
        assertNull(DownloadedRegistry.resolveTracks(Arrays.asList(10L, 99L), videoIdByMedia));
        assertNull(DownloadedRegistry.resolveTracks(Collections.emptyList(), videoIdByMedia));
    }

    @Test public void trackListsSurviveTheirStoredForm() {
        List<String> tracks = Arrays.asList(SONG, OTHER, SONG);

        assertEquals(tracks, DownloadedRegistry.decode(DownloadedRegistry.encode(tracks)));
        assertEquals(new HashSet<>(), new HashSet<>(DownloadedRegistry.decode("")));
    }
}
