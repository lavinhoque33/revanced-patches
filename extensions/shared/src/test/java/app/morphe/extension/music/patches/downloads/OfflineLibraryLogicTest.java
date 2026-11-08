/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.Decision;
import static app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SongRow;
import static app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SourceRow;
import static app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.Tap;
import static org.junit.Assert.*;

import com.google.protobuf.CodedInputStream;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OfflineLibraryLogicTest {
    private static final String GENERIC = OfflineLibraryLogic.DOWNLOADS_BROWSE_ID;

    private static OfflineCopyMetadata metadata(String id, String title) {
        return new OfflineCopyMetadata(id, title, "Artist " + id, null, 185_000, null, 1, "online");
    }

    private static OfflineSourceStore.Source source(String id, String title, String... videoIds) {
        return new OfflineSourceStore.Source(id, title, "", "", List.of(videoIds), 1);
    }

    private static OfflineSourceStore.Track track(String id, boolean stored) {
        return new OfflineSourceStore.Track(id, stored ? metadata(id, "Title " + id) : null);
    }

    // region decide

    @Test public void opensForTheDownloadsPageWhenSomethingIsStored() {
        assertEquals(Decision.OPEN, OfflineLibraryLogic.decide(GENERIC, true, true, false));
        assertEquals(Decision.OPEN, OfflineLibraryLogic.decide("FEmusic_offline_songs", true, true, false));
    }

    @Test public void staysStockWithoutTheReplayOrWithNothingStored() {
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide(GENERIC, false, true, false));
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide(GENERIC, true, false, false));
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide(GENERIC, false, false, true));
    }

    @Test public void staysStockForOtherPages() {
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide("FEmusic_library_landing", true, true, false));
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide("FEmusic_library_sideloaded_tracks", true, true, true));
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide(null, true, true, false));
    }

    @Test public void withDeviceFilesTheGenericPageGetsTheFooter() {
        assertEquals(Decision.OPEN_WITH_DEVICE_FILES, OfflineLibraryLogic.decide(GENERIC, true, true, true));
    }

    @Test public void withDeviceFilesTheSubPagesKeepTheDeviceFilesRedirect() {
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide("FEmusic_offline_songs", true, true, true));
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide("FEmusic_offline_albums", true, true, true));
        assertEquals(Decision.STOCK, OfflineLibraryLogic.decide("FEmusic_offline_playlists", true, true, true));
    }

    @Test public void onlyTheDownloadsChipItselfOpensTheList() {
        assertTrue(OfflineLibraryLogic.isDownloadsChip("FEmusic_offline"));
        assertFalse(OfflineLibraryLogic.isDownloadsChip("FEmusic_offline_songs"));
        assertFalse(OfflineLibraryLogic.isDownloadsChip("FEmusic_liked_playlists"));
        assertFalse(OfflineLibraryLogic.isDownloadsChip(null));
    }

    // endregion

    // region rows

    @Test public void sourceRowsKeepTheOrderCountSongsAndTakeTheCoverOfTheFirstStoredSong() {
        List<SourceRow> rows = OfflineLibraryLogic.sourceRows(
                List.of(source("PLb", "B", "x", "y"), source("OLAK5uy_a", "A", "p", "q", "r")),
                Map.of("PLb", List.of(track("x", false), track("y", true)),
                        "OLAK5uy_a", List.of(track("p", true), track("q", true), track("r", false)))::get);
        assertEquals(List.of(new SourceRow("PLb", "B", 2, 1, "y"), new SourceRow("OLAK5uy_a", "A", 3, 2, "p")), rows);
    }

    @Test public void aSourceWithoutAnySongIsLeftOutAndOneWithoutStoredDataHasNoCover() {
        List<SourceRow> rows = OfflineLibraryLogic.sourceRows(
                List.of(source("PLa", "A", "x"), source("PLb", "B", "y")),
                Map.of("PLa", List.<OfflineSourceStore.Track>of(), "PLb", List.of(track("y", false)))::get);
        assertEquals(List.of(new SourceRow("PLb", "B", 1, 0, null)), rows);
    }

    @Test public void trackRowsMapTheQueueIndexByVideoId() {
        List<SongRow> rows = OfflineLibraryLogic.trackRows(
                List.of(track("a", true), track("b", false), track("c", true)), List.of("c", "a"));
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).queueIndex());
        assertEquals(-1, rows.get(1).queueIndex());
        assertFalse(rows.get(1).availableOffline());
        assertEquals("", rows.get(1).title());
        assertEquals(0, rows.get(2).queueIndex());
        assertEquals("Title c", rows.get(2).title());
    }

    @Test public void aStoredTrackThatIsNotInTheQueueCannotPlayOffline() {
        List<SongRow> rows = OfflineLibraryLogic.trackRows(List.of(track("a", true)), List.of());
        assertFalse(rows.get(0).availableOffline());
    }

    @Test public void songRowsSortStoredSongsByTitleThenListTheOnesWithoutData() {
        List<SongRow> rows = OfflineLibraryLogic.songRows(
                List.of(metadata("b", "banana"), metadata("a", "Cherry"), metadata("c", "apple")),
                Set.of("a", "b", "c", "z", "y"), List.of("c", "b", "a"));
        assertEquals(List.of("c", "b", "a", "y", "z"), rows.stream().map(SongRow::videoId).toList());
        assertEquals(List.of(0, 1, 2, -1, -1), rows.stream().map(SongRow::queueIndex).toList());
        assertEquals("apple", rows.get(0).title());
        assertTrue(rows.get(2).availableOffline());
        assertFalse(rows.get(3).availableOffline());
    }

    @Test public void songRowsSkipStoredDataOfACopyThatIsGone() {
        List<SongRow> rows = OfflineLibraryLogic.songRows(
                List.of(metadata("a", "A"), metadata("gone", "Gone")), Set.of("a"), List.of("a"));
        assertEquals(List.of("a"), rows.stream().map(SongRow::videoId).toList());
    }

    @Test public void songRowsAreEmptyWithoutCopies() {
        assertTrue(OfflineLibraryLogic.songRows(List.of(), Set.of(), List.of()).isEmpty());
    }

    // endregion

    // region taps

    @Test public void aStoredSongPlaysFromTheQueueOnlineAndOffline() {
        SongRow row = new SongRow("a", "A", "X", 1000, 3);
        assertEquals(Tap.PLAY_QUEUE, OfflineLibraryLogic.tapOn(row, true));
        assertEquals(Tap.PLAY_QUEUE, OfflineLibraryLogic.tapOn(row, false));
    }

    @Test public void aSongWithoutStoredDataPlaysOnlineAndExplainsOffline() {
        SongRow row = new SongRow("a", "", "", 0, -1);
        assertEquals(Tap.PLAY_ONLINE, OfflineLibraryLogic.tapOn(row, true));
        assertEquals(Tap.EXPLAIN, OfflineLibraryLogic.tapOn(row, false));
    }

    // endregion

    // region text and bytes

    @Test public void durationTextHasMinutesAndHours() {
        assertEquals("", OfflineLibraryLogic.durationText(0));
        assertEquals("0:05", OfflineLibraryLogic.durationText(5_400));
        assertEquals("3:07", OfflineLibraryLogic.durationText(187_000));
        assertEquals("10:00", OfflineLibraryLogic.durationText(600_000));
        assertEquals("1:02:03", OfflineLibraryLogic.durationText(3_723_000));
    }

    @Test public void subtitleJoinsWhateverIsKnown() {
        assertEquals("Artist · 3:07", OfflineLibraryLogic.songSubtitle("Artist", 187_000));
        assertEquals("Artist", OfflineLibraryLogic.songSubtitle("Artist", 0));
        assertEquals("3:07", OfflineLibraryLogic.songSubtitle("", 187_000));
        assertEquals("", OfflineLibraryLogic.songSubtitle("", 0));
    }

    @Test public void sampleSizeHalvesUntilTheLongerSideFits() {
        assertEquals(1, OfflineLibraryLogic.sampleSize(120, 90, 192));
        assertEquals(1, OfflineLibraryLogic.sampleSize(192, 192, 192));
        assertEquals(2, OfflineLibraryLogic.sampleSize(380, 300, 192));
        assertEquals(4, OfflineLibraryLogic.sampleSize(544, 544, 192));
        assertEquals(64, OfflineLibraryLogic.sampleSize(Integer.MAX_VALUE, 1, 1));
    }

    @Test public void browseCommandIsTheBrowseEndpointWithThePageInField2() throws Exception {
        byte[] bytes = OfflineLibraryLogic.browseCommand(OfflineLibraryLogic.DEVICE_FILES_BROWSE_ID);
        CodedInputStream command = CodedInputStream.newInstance(bytes);
        assertEquals(48_687_626 << 3 | 2, command.readTag());
        CodedInputStream endpoint = CodedInputStream.newInstance(command.readByteArray());
        assertTrue(command.isAtEnd());
        assertEquals(2 << 3 | 2, endpoint.readTag());
        assertEquals("FEmusic_library_sideloaded_tracks",
                new String(endpoint.readByteArray(), StandardCharsets.UTF_8));
        assertTrue(endpoint.isAtEnd());
    }

    // endregion
}
