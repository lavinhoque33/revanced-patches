/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 * Modified by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;


import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class OfflineReplayLogicTest {
    private static OfflineCopyMetadata copy(String id, String title, String album) {
        return new OfflineCopyMetadata(id, title, "Artist", album, 185_000, "https://cover/" + id, 1, "online");
    }

    // region no-connection sheet

    @Test public void theSheetShownLaterIsDismissedOfflineWhileTheReplayedCopyPlays() {
        assertTrue(OfflineReplayLogic.dismissesSheetShownLater("abc", "abc", false));
    }

    @Test public void theSheetShownLaterStaysWhenOnline() {
        assertFalse(OfflineReplayLogic.dismissesSheetShownLater("abc", "abc", true));
    }

    @Test public void theSheetShownLaterStaysWhenNothingOfOursPlays() {
        assertFalse(OfflineReplayLogic.dismissesSheetShownLater(null, "abc", false));
        assertFalse(OfflineReplayLogic.dismissesSheetShownLater("abc", null, false));
        assertFalse(OfflineReplayLogic.dismissesSheetShownLater("abc", "other", false));
    }

    // endregion

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

    // region seek snap

    @Test public void aSnapOfZeroIsDroppedOfflineWhenTheReplayIsOn() {
        assertTrue(OfflineReplayLogic.dropsSeekSnap(true, false, 0));
    }

    @Test public void aRealSnapOfflineIsKept() {
        assertFalse(OfflineReplayLogic.dropsSeekSnap(true, false, 12_000));
    }

    @Test public void theSnapOnlineIsAlwaysLeftAlone() {
        assertFalse(OfflineReplayLogic.dropsSeekSnap(true, true, 0));
    }

    @Test public void theSnapIsLeftAloneWhileTheSettingIsOff() {
        assertFalse(OfflineReplayLogic.dropsSeekSnap(false, false, 0));
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

    // region routes

    private static final Map<String, List<String>> TRACKS = Map.of(
            "PLa", List.of("x", "y"), "MPREb_c", List.of("z"));

    private static OfflineReplayLogic.Decision route(boolean online, String browseId, byte[] watchCommand,
                                                     boolean page) {
        OfflineReplayLogic.WatchTarget watch = watchCommand == null ? null
                : OfflineReplayLogic.onlineWatchOf(watchCommand);
        return OfflineReplayLogic.routeOf(online, browseId, watch, "songs", TRACKS::containsKey, id -> page,
                id -> id.equals("x") || id.equals("z"),
                target -> OfflineReplayLogic.sourceOfPlaylist(target.playlistId(), target.videoId(), TRACKS));
    }

    @Test public void anOnlineWatchNamesItsVideoPlaylistAndPlace() {
        assertEquals(new OfflineReplayLogic.WatchTarget("x", "", 0),
                OfflineReplayLogic.onlineWatchOf(OnlineWatchCommand.build("x", null, 0)));
        assertEquals(new OfflineReplayLogic.WatchTarget("x", "PLa", 3),
                OfflineReplayLogic.onlineWatchOf(OnlineWatchCommand.build("x", "PLa", 3)));

        assertNull(OfflineReplayLogic.onlineWatchOf(OfflineLibraryLogic.browseCommand("VLPLa")));
        assertNull(OfflineReplayLogic.onlineWatchOf(new byte[]{(byte) 0xff}));
    }

    @Test public void aSourceIsNamedWithOrWithoutThePlaylistPrefix() {
        assertEquals("PLa", OfflineReplayLogic.sourceOfBrowseId("VLPLa"));
        assertEquals("OLAK5uy_b", OfflineReplayLogic.sourceOfBrowseId("VLOLAK5uy_b"));
        assertEquals("MPREb_c", OfflineReplayLogic.sourceOfBrowseId("MPREb_c"));
    }

    @Test public void aPlaylistWatchBelongsToTheListWithThatIdOrTheAlbumHoldingTheSong() {
        assertEquals("PLa", OfflineReplayLogic.sourceOfPlaylist("PLa", "x", TRACKS));
        assertEquals("MPREb_c", OfflineReplayLogic.sourceOfPlaylist("OLAK5uy_q", "z", TRACKS));
        assertNull(OfflineReplayLogic.sourceOfPlaylist("OLAK5uy_q", "other", TRACKS));
        assertNull(OfflineReplayLogic.sourceOfPlaylist("PLother", "x", TRACKS));
    }

    @Test public void onlineEveryCommandStaysTheApps() {
        assertEquals(OfflineReplayLogic.Decision.STOCK, route(true, "VLPLa", null, false));
        assertEquals(OfflineReplayLogic.Decision.STOCK,
                route(true, null, OnlineWatchCommand.build("x", "PLa", 0), false));
    }

    @Test public void offlineTheBrowseOfADownloadedListPlaysItUnlessAPageWasRecorded() {
        assertEquals(new OfflineReplayLogic.Decision(OfflineReplayLogic.Route.PLAY_SOURCE, "PLa", null),
                route(false, "VLPLa", null, false));
        assertEquals(OfflineReplayLogic.Decision.STOCK, route(false, "VLPLa", null, true));
    }

    @Test public void offlineOtherPagesAndUnknownListsStayTheApps() {
        assertEquals(OfflineReplayLogic.Decision.STOCK, route(false, "FEmusic_offline", null, false));
        assertEquals(OfflineReplayLogic.Decision.STOCK, route(false, "VLPLother", null, false));
        assertEquals(OfflineReplayLogic.Decision.STOCK, route(false, null, null, false));
    }

    @Test public void offlineTheWatchOfADownloadedSongPlaysItsCopyAsATrackOfItsList() {
        assertEquals(new OfflineReplayLogic.Decision(OfflineReplayLogic.Route.PLAY_TRACK, "songs", "x"),
                route(false, null, OnlineWatchCommand.build("x", null, 0), false));
        assertEquals(new OfflineReplayLogic.Decision(OfflineReplayLogic.Route.PLAY_TRACK, "PLa", "x"),
                route(false, null, OnlineWatchCommand.build("x", "PLa", 4), true));
        assertEquals(new OfflineReplayLogic.Decision(OfflineReplayLogic.Route.PLAY_TRACK, "MPREb_c", "z"),
                route(false, null, OnlineWatchCommand.build("z", "OLAK5uy_q", 0), true));
    }

    @Test public void offlineAWatchOfASongWithNoCopyStartsItsListOrStaysTheApps() {
        assertEquals(new OfflineReplayLogic.Decision(OfflineReplayLogic.Route.PLAY_SOURCE, "PLa", null),
                route(false, null, OnlineWatchCommand.build("y", "PLa", 1), true));
        assertEquals(OfflineReplayLogic.Decision.STOCK,
                route(false, null, OnlineWatchCommand.build("no-copy", null, 0), false));
        assertEquals(OfflineReplayLogic.Decision.STOCK,
                route(false, null, OnlineWatchCommand.build("x", "PLother", 1), false));
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

    @Test public void aVideoModeShowsTheMusicVideoOfTheSongsThatHaveACopy() {
        List<OfflineWatchNext.Item> items = itemsOf("a", "b", "c");

        List<OfflineWatchNext.Item> wrapped = OfflineReplayLogic.showingVideos(items, Map.of("vb", "b"));

        assertEquals(List.of("a", "vb", "c"), idsOf(wrapped));
        assertEquals("a", wrapped.get(0).videoId());
        assertEquals(1, wrapped.get(0).musicVideoType());
        assertEquals("vb", wrapped.get(1).videoId());
        assertEquals(2, wrapped.get(1).musicVideoType());
        assertEquals("Tb", wrapped.get(1).title());
        assertEquals("c", wrapped.get(2).videoId());
    }

    @Test public void noVideoCopiesLeavesTheItemsAsTheyWere() {
        List<OfflineWatchNext.Item> items = itemsOf("a", "b");

        assertSame(items, OfflineReplayLogic.showingVideos(items, Map.of()));
    }

    @Test public void aVideoCopyOfAnotherSongIsIgnoredAndACopyNeverPointsAtItself() {
        List<OfflineWatchNext.Item> items = itemsOf("a", "b");

        List<OfflineWatchNext.Item> wrapped = OfflineReplayLogic.showingVideos(items,
                Map.of("vz", "z", "b", "b"));

        assertEquals(List.of("a", "b"), idsOf(wrapped));
        assertEquals(1, wrapped.get(1).musicVideoType());
    }

    @Test public void aRequestForAMusicVideoNamesItsSong() {
        Map<String, String> videos = Map.of("vb", "b");

        assertEquals("b", OfflineReplayLogic.songOf("vb", videos));
        assertEquals("b", OfflineReplayLogic.songOf("b", videos));
        assertEquals("q", OfflineReplayLogic.songOf("q", videos));
    }

    @Test public void theQueueIndexOfAVideoRequestIsTheOneOfItsSong() {
        List<OfflineWatchNext.Item> items = itemsOf("a", "b", "c");
        Map<String, String> videos = Map.of("vc", "c");

        assertEquals(2, OfflineReplayLogic.currentIndex(items, OfflineReplayLogic.songOf("vc", videos), 2));
        assertEquals(-1, OfflineReplayLogic.currentIndex(items, "vc", 2));
    }

    @Test public void aSongWithAVideoCopyStartsAsItsMusicVideo() {
        Map<String, String> videos = Map.of("vb", "b");

        assertEquals("vb", OfflineReplayLogic.videoOf("b", videos));
        assertNull(OfflineReplayLogic.videoOf("a", videos));
        assertNull(OfflineReplayLogic.videoOf("b", Map.of("b", "b")));
        assertEquals(2, OfflineReplayLogic.startTypeOf("b", videos));
        assertEquals(1, OfflineReplayLogic.startTypeOf("a", videos));
        assertEquals(1, OfflineReplayLogic.startTypeOf("a", Map.of()));
    }

    @Test public void theQueueShowsVideosOnlyForARequestOfAMusicVideo() {
        Map<String, String> videos = Map.of("vb", "b");

        assertTrue(OfflineReplayLogic.showsVideos("vb", videos));
        assertFalse(OfflineReplayLogic.showsVideos("b", videos));
        assertFalse(OfflineReplayLogic.showsVideos("a", videos));
        assertFalse(OfflineReplayLogic.showsVideos("vb", Map.of()));
    }

    @Test public void theOmvStartCommandNamesTheVideoAndItsType() {
        byte[] command = OfflineWatchCommand.build("vb", "PPSVrvx:PLabc", 3, OfflineReplayLogic.startTypeOf("b",
                Map.of("vb", "b")));

        assertEquals(new OfflineReplayLogic.WatchTarget("vb", "PPSVrvx:PLabc", 3),
                OfflineReplayLogic.offlineWatchOf(command));
    }
}
