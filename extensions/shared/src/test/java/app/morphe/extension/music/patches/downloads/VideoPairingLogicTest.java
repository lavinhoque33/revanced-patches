/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class VideoPairingLogicTest {
    private static VideoPairingLogic.Song song(String id, long seconds, String title) {
        return new VideoPairingLogic.Song(id, seconds, title, "Artist");
    }

    private static WatchQueueLogic.NextItem item(int index, String id, String type, String length, String title) {
        return new WatchQueueLogic.NextItem(index, "plain", id, type, title, "Artist", length, "", "", "", "");
    }

    private static VideoPairingLogic.Result pair(List<VideoPairingLogic.Song> songs,
                                                 List<WatchQueueLogic.NextItem> queue) {
        return VideoPairingLogic.pair(songs, queue, ignored -> false);
    }

    @Test public void explicitSameIdOfficialVideoIsSavedEvenWithAMissingOrReorderedQueueRow() {
        VideoPairingLogic.Result result = pair(
                List.of(song("aaaaaaaaaaa", 200, "First"), song("bbbbbbbbbbb", 200, "Second"),
                        song("ccccccccccc", 200, "Third")),
                List.of(item(0, "ccccccccccc", "ATV", "3:20", "Third"),
                        item(1, "aaaaaaaaaaa", "OMV", "3:20", "First")));

        assertNull(result.skipReason());
        assertEquals(List.of(new VideoPairingLogic.Pair("aaaaaaaaaaa", "aaaaaaaaaaa")), result.pairs());
        assertEquals(VideoPairingLogic.BY_OFFICIAL_VIDEO, result.decisions().get(0).by());
        assertEquals(VideoPairingLogic.BY_NONE, result.decisions().get(1).by());
        assertEquals(VideoPairingLogic.BY_SAME, result.decisions().get(2).by());
    }

    @Test public void savedOfficialVideoTypeCanFillAMissingQueueRow() {
        VideoPairingLogic.Song video = song("aaaaaaaaaaa", 200, "First");
        VideoPairingLogic.Song song = song("bbbbbbbbbbb", 200, "Second");
        VideoPairingLogic.Result result = VideoPairingLogic.pair(
                List.of(video, song), List.of(), video.videoId()::equals);

        assertNull(result.skipReason());
        assertEquals(List.of(new VideoPairingLogic.Pair(video.videoId(), video.videoId())), result.pairs());
    }

    @Test public void aTypedSongWithTheSameIdDoesNotDownloadVideo() {
        VideoPairingLogic.Result result = pair(
                List.of(song("aaaaaaaaaaa", 200, "Homesick")),
                List.of(item(0, "aaaaaaaaaaa", "ATV", "3:20", "Homesick")));

        assertTrue(result.pairs().isEmpty());
    }

    @Test public void positionalFallbackRequiresMatchingTitleArtistAndDuration() {
        VideoPairingLogic.Song song = song("aaaaaaaaaaa", 233, "Homesick");
        assertEquals(VideoPairingLogic.BY_RECORDING, pair(List.of(song),
                List.of(item(0, "vvvvvvvvvvv", "OMV", "3:28", "Homesick [Official Video]")))
                .decisions().get(0).by());

        for (WatchQueueLogic.NextItem rejected : List.of(
                item(0, "vvvvvvvvvvv", "OMV", "3:28", "A different track"),
                item(0, "vvvvvvvvvvv", "OMV", "3:28", "Homesick (Live)"),
                item(0, "vvvvvvvvvvv", "OMV", "9:59", "Homesick"),
                item(0, "vvvvvvvvvvv", "OMV", "", "Homesick"),
                item(0, "vvvvvvvvvvv", "ATV", "3:53", "Homesick"),
                new WatchQueueLogic.NextItem(0, "plain", "vvvvvvvvvvv", "OMV", "Homesick",
                        "Another Artist", "3:28", "", "", "", ""))) {
            assertTrue(pair(List.of(song), List.of(rejected)).pairs().isEmpty());
        }
    }

    @Test public void anExactIdCannotBeClaimedAsAnotherRowsPositionalMatch() {
        VideoPairingLogic.Result result = pair(
                List.of(song("aaaaaaaaaaa", 200, "Same title"), song("bbbbbbbbbbb", 200, "Same title")),
                List.of(item(0, "bbbbbbbbbbb", "OMV", "3:20", "Same title"),
                        item(1, "", "", "3:20", "Same title")));

        assertEquals(List.of(new VideoPairingLogic.Pair("bbbbbbbbbbb", "bbbbbbbbbbb")), result.pairs());
    }

    @Test public void aMissingQueueItemDisablesPositionalButNotTypedCounterpartPairing() {
        VideoPairingLogic.Song a = song("aaaaaaaaaaa", 200, "First");
        VideoPairingLogic.Song b = song("bbbbbbbbbbb", 200, "Second");
        WatchQueueLogic.NextItem linkedB = new WatchQueueLogic.NextItem(0, "wrapper", "vvvvvvvvvvv", "OMV",
                "First", "Artist", "3:20", b.videoId(), "ATV", "3:20", b.videoId());

        assertEquals(List.of(new VideoPairingLogic.Pair(b.videoId(), linkedB.videoId())),
                pair(List.of(a, b), List.of(linkedB)).pairs());
    }

    @Test public void anItemThatNamesTheSongAsItsCounterpartIsPairedWithoutLookingAtTitleOrLength() {
        VideoPairingLogic.Song song = song("aaaaaaaaaaa", 100, "Homesick");
        WatchQueueLogic.NextItem video = new WatchQueueLogic.NextItem(0, "wrapper", "vvvvvvvvvvv", "OMV", "Clip",
                "A", "9:00", "aaaaaaaaaaa", "ATV", "1:40", "aaaaaaaaaaa");
        WatchQueueLogic.NextItem songFirst = new WatchQueueLogic.NextItem(0, "wrapper", "aaaaaaaaaaa", "ATV",
                "Homesick", "A", "1:40", "wwwwwwwwwww", "OMV", "9:00", "");

        VideoPairingLogic.Result first = pair(List.of(song), List.of(video));
        VideoPairingLogic.Result second = pair(List.of(song), List.of(songFirst));

        assertEquals(List.of(new VideoPairingLogic.Pair("aaaaaaaaaaa", "vvvvvvvvvvv")), first.pairs());
        assertEquals(List.of(new VideoPairingLogic.Pair("aaaaaaaaaaa", "wwwwwwwwwww")), second.pairs());
        assertEquals(VideoPairingLogic.BY_COUNTERPART, second.decisions().get(0).by());
        assertEquals(540, second.decisions().get(0).videoSeconds());
    }

    @Test public void titlesAreComparedWithoutCaseAccentsSuffixesAndFeaturing() {
        assertEquals("beyonce halo", VideoPairingLogic.normalizeTitle("Beyonc\u00e9 Halo (Official Video)"));
        assertEquals("never gonna give you up", VideoPairingLogic.normalizeTitle(
                "Never Gonna Give You Up [Remastered 2022]"));
        assertEquals("homesick", VideoPairingLogic.normalizeTitle("Homesick (feat. Someone)"));
        assertEquals("homesick", VideoPairingLogic.normalizeTitle("Homesick feat. Someone Else"));
        assertEquals("homesick", VideoPairingLogic.normalizeTitle("HOMESICK - Official Music Video"));
        assertEquals("what s up", VideoPairingLogic.normalizeTitle("What's  Up?"));
        assertEquals("", VideoPairingLogic.normalizeTitle(null));
        assertEquals("", VideoPairingLogic.normalizeTitle("(Official Video)"));
        assertTrue(VideoPairingLogic.sameTitle("Cr\u00e8me Br\u00fbl\u00e9e", "creme brulee (Lyric Video)"));
        assertFalse(VideoPairingLogic.sameTitle("", ""));
        assertFalse(VideoPairingLogic.sameTitle("Halo", "Halo Part 2"));
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
