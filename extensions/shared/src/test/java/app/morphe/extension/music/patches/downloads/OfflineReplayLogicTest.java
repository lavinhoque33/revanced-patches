/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class OfflineReplayLogicTest {
    private static OfflineCopyMetadata copy(String id, String title, String album) {
        return new OfflineCopyMetadata(id, title, "Artist", album, 185_000, "https://cover/" + id, 1, "online");
    }

    // region playlist ids

    @Test public void playlistIdRoundTrips() {
        String playlistId = OfflineReplayLogic.playlistId("PLabc");

        assertEquals("PPSVrvx:PLabc", playlistId);
        assertEquals("PLabc", OfflineReplayLogic.sourceOf(playlistId));
    }

    @Test public void playlistIdStartsWithTheStockQueuePrefix() {
        assertTrue(OfflineReplayLogic.playlistId("songs").startsWith("PPSV"));
    }

    @Test public void anIdThatIsNotOursHasNoSource() {
        assertNull(OfflineReplayLogic.sourceOf(null));
        assertNull(OfflineReplayLogic.sourceOf(""));
        assertNull(OfflineReplayLogic.sourceOf("PLabc"));
        assertNull(OfflineReplayLogic.sourceOf("PPSV123"));
        assertNull(OfflineReplayLogic.sourceOf("PPSVrvx:"));
    }

    @Test public void onlinePlaylistIdIsTheSourceForPlaylistsAndAlbumPlaylists() {
        assertEquals("PLabc", OfflineReplayLogic.onlinePlaylistId("PLabc", "songs"));
        assertEquals("OLAK5uy_x", OfflineReplayLogic.onlinePlaylistId("OLAK5uy_x", "songs"));
    }

    @Test public void onlinePlaylistIdIsNullForTheSongsListAndAlbumPages() {
        assertNull(OfflineReplayLogic.onlinePlaylistId("songs", "songs"));
        assertNull(OfflineReplayLogic.onlinePlaylistId("MPREb_abc", "songs"));
    }

    // endregion

    // region skip reasons

    @Test public void aSettingThatIsOffWinsOverEveryOtherReason() {
        assertEquals("setting off", OfflineReplayLogic.skipReason(false, false, false, false));
        assertEquals("setting off", OfflineReplayLogic.skipReason(false, true, true, true));
    }

    @Test public void anOnlineRequestIsLeftAlone() {
        assertEquals("online request", OfflineReplayLogic.skipReason(true, false, true, true));
    }

    @Test public void aRequestForSomethingNotStoredIsLeftAlone() {
        assertEquals("not ours", OfflineReplayLogic.skipReason(true, true, false, true));
    }

    @Test public void aStockAnswerIsKept() {
        assertEquals("stock answered", OfflineReplayLogic.skipReason(true, true, true, false));
    }

    @Test public void anOfflineRequestForAStoredCopyThatStockFailedActs() {
        assertNull(OfflineReplayLogic.skipReason(true, true, true, true));
    }

    // endregion

    // region skip log rate limit

    @Test public void aReasonIsLoggedOncePerMinute() {
        ConcurrentMap<String, Long> last = new ConcurrentHashMap<>();

        assertTrue(OfflineReplayLogic.shouldLogSkip(last, "gate:online request", 1_000));
        assertFalse(OfflineReplayLogic.shouldLogSkip(last, "gate:online request", 30_000));
        assertFalse(OfflineReplayLogic.shouldLogSkip(last, "gate:online request", 60_999));
        assertTrue(OfflineReplayLogic.shouldLogSkip(last, "gate:online request", 61_000));
    }

    @Test public void eachReasonHasItsOwnClock() {
        ConcurrentMap<String, Long> last = new ConcurrentHashMap<>();

        assertTrue(OfflineReplayLogic.shouldLogSkip(last, "gate:online request", 1_000));
        assertTrue(OfflineReplayLogic.shouldLogSkip(last, "gate:setting off", 1_000));
        assertTrue(OfflineReplayLogic.shouldLogSkip(last, "next:online request", 1_000));
    }

    // endregion

    // region commands

    @Test public void anOfflineWatchCommandNamesItsTarget() {
        byte[] command = OfflineWatchCommand.build("vid1", "PPSVrvx:PLabc", 4);

        OfflineReplayLogic.WatchTarget target = OfflineReplayLogic.offlineWatchOf(command);

        assertEquals(new OfflineReplayLogic.WatchTarget("vid1", "PPSVrvx:PLabc", 4), target);
    }

    @Test public void anOnlineWatchCommandIsNoOfflineWatchCommand() {
        assertNull(OfflineReplayLogic.offlineWatchOf(OnlineWatchCommand.build("vid1", "PLabc", 2)));
    }

    @Test public void garbageIsNoOfflineWatchCommand() {
        assertNull(OfflineReplayLogic.offlineWatchOf(new byte[]{(byte) 0xff, (byte) 0xff, 0x01}));
        assertNull(OfflineReplayLogic.offlineWatchOf(new byte[0]));
    }

    @Test public void aBrowseCommandNamesItsPage() {
        byte[] command = OfflineLibraryLogic.browseCommand("FEmusic_offline_songs");

        assertEquals("FEmusic_offline_songs", OfflineReplayLogic.browseIdOf(command));
    }

    @Test public void aWatchCommandIsNoBrowseCommand() {
        assertNull(OfflineReplayLogic.browseIdOf(OnlineWatchCommand.build("vid1", null, 0)));
        assertNull(OfflineReplayLogic.browseIdOf(new byte[0]));
    }

    // endregion

    // region fields

    @Test public void aFieldIsFoundByNumber() {
        byte[] message = {0x08, 0x01, 0x72, 0x02, 0x28, 0x01};

        assertTrue(OfflineReplayLogic.hasField(message, 1));
        assertTrue(OfflineReplayLogic.hasField(message, 14));
        assertFalse(OfflineReplayLogic.hasField(message, 2));
        assertFalse(OfflineReplayLogic.hasField(new byte[]{(byte) 0xff}, 14));
    }

    @Test public void theOfflineMarkerIsField14() {
        assertTrue(OfflineReplayLogic.hasOfflineMarker(OfflineReplayResponse.OFFLINE_MARKER));
        assertFalse(OfflineReplayLogic.hasOfflineMarker(new byte[]{0x08, 0x01}));
    }

    // endregion

    // region queue items

    @Test public void tracksWithoutStoredDataAreLeftOutAndTheOrderIsKept() {
        List<OfflineSourceStore.Track> tracks = List.of(
                new OfflineSourceStore.Track("b", copy("b", "Beta", null)),
                new OfflineSourceStore.Track("x", null),
                new OfflineSourceStore.Track("a", copy("a", "Alpha", "Album")));

        List<OfflineWatchNext.Item> items = OfflineReplayLogic.itemsOfTracks(tracks);

        assertEquals(List.of("b", "a"), idsOf(items));
        assertEquals("", items.get(0).album());
        assertEquals("Album", items.get(1).album());
    }

    @Test public void theSongsListIsSortedByTitleIgnoringCase() {
        List<OfflineCopyMetadata> copies = List.of(
                copy("c", "beta", null), copy("a", "Alpha", null), copy("b", "Beta", null),
                copy("d", "alpha", null));

        List<OfflineWatchNext.Item> items = OfflineReplayLogic.itemsOfSongs(copies);

        // Equal titles (ignoring case) fall back to the video id.
        assertEquals(List.of("a", "d", "b", "c"), idsOf(items));
    }

    @Test public void anItemCarriesTheMetadataOfItsCopy() {
        OfflineWatchNext.Item item = OfflineReplayLogic.itemOf(copy("a", "Alpha", "Album"));

        assertEquals(new OfflineWatchNext.Item("a", "Alpha", "Artist", "Album", 185_000, "https://cover/a"), item);
    }

    @Test public void theCurrentIndexPrefersTheRequestedPosition() {
        List<OfflineWatchNext.Item> items = itemsOf("a", "b", "a");

        assertEquals(2, OfflineReplayLogic.currentIndex(items, "a", 2));
    }

    @Test public void theCurrentIndexFollowsTheVideoWhenTheListMoved() {
        List<OfflineWatchNext.Item> items = itemsOf("a", "b", "c");

        assertEquals(1, OfflineReplayLogic.currentIndex(items, "b", 0));
        assertEquals(2, OfflineReplayLogic.currentIndex(items, "c", 9));
        assertEquals(-1, OfflineReplayLogic.currentIndex(items, "z", 0));
        assertEquals(0, OfflineReplayLogic.currentIndex(items, "a", -1));
    }

    // endregion

    private static List<OfflineWatchNext.Item> itemsOf(String... videoIds) {
        List<OfflineWatchNext.Item> items = new ArrayList<>();
        for (String id : videoIds) items.add(OfflineReplayLogic.itemOf(copy(id, "T" + id, null)));
        return items;
    }

    private static List<String> idsOf(List<OfflineWatchNext.Item> items) {
        List<String> ids = new ArrayList<>();
        for (OfflineWatchNext.Item item : items) ids.add(item.videoId());
        return ids;
    }
}
