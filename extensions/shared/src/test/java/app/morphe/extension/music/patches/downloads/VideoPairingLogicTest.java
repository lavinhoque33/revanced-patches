/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class VideoPairingLogicTest {
    private static VideoPairingLogic.Song song(String id, long seconds) {
        return new VideoPairingLogic.Song(id, seconds);
    }

    private static WatchQueueLogic.NextItem item(int index, String id, String length) {
        return new WatchQueueLogic.NextItem(index, "plain", id, "", "", "", length);
    }

    private static String id(String prefix, int number) {
        return String.format("%s%07d", prefix, number);
    }

    @Test public void theAlbumOfTenPairsTheThreeTracksThatHaveAVideo() {
        // The album of the device round: 10 tracks and 10 items, the first three are music videos.
        List<VideoPairingLogic.Song> songs = new ArrayList<>();
        List<WatchQueueLogic.NextItem> queue = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            songs.add(song(id("song", index), 214));
            queue.add(item(index, index < 3 ? id("clip", index) : id("song", index), "3:33"));
        }

        VideoPairingLogic.Result result = VideoPairingLogic.pair(songs, queue);

        assertNull(result.skipReason());
        assertEquals(List.of(new VideoPairingLogic.Pair("song0000000", "clip0000000"),
                new VideoPairingLogic.Pair("song0000001", "clip0000001"),
                new VideoPairingLogic.Pair("song0000002", "clip0000002")), result.pairs());
        assertEquals(0, result.unverified());
    }

    /** The playlist of the device round: 23 recorded tracks, one greyed out, so 22 downloaded and 22 queued. */
    @Test public void theGreyedRowIsSkippedBeforeThePositionsAreMatched() {
        List<String> recorded = new ArrayList<>();
        for (int index = 0; index < 23; index++) {
            recorded.add(index == 9 ? PlaylistPage.UNAVAILABLE : id("song", index));
        }
        List<VideoPairingLogic.Song> songs = new ArrayList<>();
        for (String track : PlaylistPage.available(recorded)) songs.add(song(track, 200));
        List<WatchQueueLogic.NextItem> queue = new ArrayList<>();
        for (int index = 0; index < songs.size(); index++) {
            boolean video = index == 11;
            queue.add(item(index, video ? "ugcvideo001" : songs.get(index).videoId(), "3:21"));
        }

        VideoPairingLogic.Result result = VideoPairingLogic.pair(songs, queue);

        assertEquals(22, songs.size());
        assertNull(result.skipReason());
        assertEquals(List.of(new VideoPairingLogic.Pair(songs.get(11).videoId(), "ugcvideo001")), result.pairs());
    }

    @Test public void theGhostRowCountedAsATrackWouldMisalignEverythingAfterItSoTheCountDecides() {
        List<VideoPairingLogic.Song> songs = new ArrayList<>();
        for (int index = 0; index < 23; index++) songs.add(song(id("song", index), 200));
        List<WatchQueueLogic.NextItem> queue = new ArrayList<>();
        for (int index = 0; index < 22; index++) queue.add(item(index, id("song", index), "3:20"));

        VideoPairingLogic.Result result = VideoPairingLogic.pair(songs, queue);

        assertEquals("the queue has 22 items for 23 tracks", result.skipReason());
        assertTrue(result.pairs().isEmpty());
    }

    @Test public void aQueueWithoutAnyDifferentIdPairsNothingAndIsNotSkipped() {
        VideoPairingLogic.Result result = VideoPairingLogic.pair(
                List.of(song("aaaaaaaaaaa", 100), song("bbbbbbbbbbb", 100)),
                List.of(item(0, "aaaaaaaaaaa", "1:40"), item(1, "bbbbbbbbbbb", "1:40")));

        assertNull(result.skipReason());
        assertTrue(result.pairs().isEmpty());
    }

    @Test public void aVideoWithinThreeSecondsOfItsSongIsPairedAndFourIsNot() {
        VideoPairingLogic.Song song = song("aaaaaaaaaaa", 214);

        assertEquals(1, VideoPairingLogic.pair(List.of(song), List.of(item(0, "ccccccccccc", "3:33"))).pairs().size());
        assertEquals(1, VideoPairingLogic.pair(List.of(song), List.of(item(0, "ccccccccccc", "3:37"))).pairs().size());

        VideoPairingLogic.Result tooLong = VideoPairingLogic.pair(List.of(song),
                List.of(item(0, "ccccccccccc", "3:38")));
        assertNotNull(tooLong.skipReason());
        assertTrue(tooLong.skipReason().contains("ccccccccccc is 218 s, track aaaaaaaaaaa is 214 s"));
        assertTrue(tooLong.pairs().isEmpty());
    }

    @Test public void oneProvenMismatchSkipsTheWholeListEvenWhenOthersFit() {
        VideoPairingLogic.Result result = VideoPairingLogic.pair(
                List.of(song("aaaaaaaaaaa", 100), song("bbbbbbbbbbb", 100)),
                List.of(item(0, "xxxxxxxxxxx", "1:40"), item(1, "yyyyyyyyyyy", "5:00")));

        assertNotNull(result.skipReason());
        assertTrue(result.pairs().isEmpty());
    }

    @Test public void aLengthThatCannotBeCheckedLeavesOnlyThatItemUnpaired() {
        VideoPairingLogic.Result result = VideoPairingLogic.pair(
                List.of(song("aaaaaaaaaaa", 100), song("bbbbbbbbbbb", 0), song("ccccccccccc", 100)),
                List.of(item(0, "xxxxxxxxxxx", ""), item(1, "yyyyyyyyyyy", "1:40"), item(2, "zzzzzzzzzzz", "1:41")));

        assertNull(result.skipReason());
        assertEquals(List.of(new VideoPairingLogic.Pair("ccccccccccc", "zzzzzzzzzzz")), result.pairs());
        assertEquals(2, result.unverified());
    }

    @Test public void anItemWithoutAVideoIdSkipsTheList() {
        VideoPairingLogic.Result result = VideoPairingLogic.pair(List.of(song("aaaaaaaaaaa", 100)),
                List.of(item(0, "", "1:40")));

        assertEquals("item 0 has no video id", result.skipReason());
    }

    @Test public void lengthTextsAreParsedInMinutesAndHours() {
        assertEquals(214, VideoPairingLogic.lengthSeconds("3:34"));
        assertEquals(5, VideoPairingLogic.lengthSeconds("0:05"));
        assertEquals(3723, VideoPairingLogic.lengthSeconds("1:02:03"));
        assertEquals(214, VideoPairingLogic.lengthSeconds(" 3:34 "));
        assertEquals(-1, VideoPairingLogic.lengthSeconds(""));
        assertEquals(-1, VideoPairingLogic.lengthSeconds("214"));
        assertEquals(-1, VideoPairingLogic.lengthSeconds("3:"));
        assertEquals(-1, VideoPairingLogic.lengthSeconds("a:bc"));
        assertEquals(-1, VideoPairingLogic.lengthSeconds("1:2:3:4"));
        assertEquals(-1, VideoPairingLogic.lengthSeconds(null));
    }
}
