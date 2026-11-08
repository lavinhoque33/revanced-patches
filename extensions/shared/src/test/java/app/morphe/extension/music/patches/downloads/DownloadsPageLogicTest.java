/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.DownloadsPageLogic.Layout;
import static app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SongRow;
import static app.morphe.extension.music.patches.downloads.OfflineLibraryLogic.SourceRow;
import static org.junit.Assert.*;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class DownloadsPageLogicTest {
    private static final int SHELF = 91_303_872;
    private static final int ROW = 161_429_595;
    private static final int EMPTY_STATE = 58_508_690;

    private static final DownloadsPageLogic.Labels LABELS = new DownloadsPageLogic.Labels("Albums and playlists",
            "Songs", "Playlist", "Downloaded song", "Plays offline after it has played online once",
            count -> count + " songs");

    private static final DownloadsPageLogic.Taps TAPS = new DownloadsPageLogic.Taps() {
        @Override public byte[] source(SourceRow row) {
            return ("source:" + row.sourceId()).getBytes(StandardCharsets.UTF_8);
        }

        @Override public byte[] song(SongRow row) {
            return ("song:" + row.videoId()).getBytes(StandardCharsets.UTF_8);
        }
    };

    private static final DownloadsPageLogic.CoverOf COVERS = (id, url) -> id == null ? url : "file:///" + id;

    private static final SourceRow PLAYLIST = new SourceRow("PLa", "Mix", 3, 2, "x", null);
    private static final SourceRow ALBUM = new SourceRow("OLAK5uy_b", "Album", 10, 0, null, "https://img/b");
    private static final SongRow SONG = new SongRow("x", "Song X", "Artist", 187_000, 0, null);
    private static final SongRow GHOST = new SongRow("y", "", "", 0, -1, null);

    // region requests

    @Test public void theTokenIsField7OfTheRequest() {
        byte[] request = new ProtoWriter().string(2, "FEmusic_offline")
                .string(7, "CONTENT_RELOAD_CONTINUATION_TOKEN_TRACKS").toByteArray();
        assertEquals("CONTENT_RELOAD_CONTINUATION_TOKEN_TRACKS", DownloadsPageLogic.tokenOf(request));
    }

    @Test public void aRequestWithoutATokenOrNotAMessageHasNone() {
        assertNull(DownloadsPageLogic.tokenOf(new ProtoWriter().string(2, "FEmusic_offline").toByteArray()));
        assertNull(DownloadsPageLogic.tokenOf(new byte[0]));
        assertNull(DownloadsPageLogic.tokenOf(new byte[]{(byte) 0xff, (byte) 0xff}));
    }

    @Test public void everyChipOfTheDownloadsPageHasItsLayout() {
        String prefix = "CONTENT_RELOAD_CONTINUATION_TOKEN_";
        assertEquals(Layout.ALL, DownloadsPageLogic.layoutOf(prefix + "DEFAULT"));
        assertEquals(Layout.SONGS, DownloadsPageLogic.layoutOf(prefix + "TRACKS"));
        assertEquals(Layout.PLAYLISTS, DownloadsPageLogic.layoutOf(prefix + "PLAYLISTS"));
        assertEquals(Layout.ALBUMS, DownloadsPageLogic.layoutOf(prefix + "ALBUMS"));
    }

    @Test public void theSubChipsOfTheDeviceFilterLikeTheirPlainTokens() {
        String prefix = "CONTENT_RELOAD_CONTINUATION_TOKEN_";
        assertEquals(Layout.SONGS, DownloadsPageLogic.layoutOf(prefix + "TRACKS_DOWNLOADED_SUBCHIP"));
        assertEquals(Layout.PLAYLISTS, DownloadsPageLogic.layoutOf(prefix + "PLAYLISTS_DOWNLOADED_SUBCHIP"));
        assertEquals(Layout.ALBUMS, DownloadsPageLogic.layoutOf(prefix + "ALBUMS_DOWNLOADED_SUBCHIP"));
    }

    @Test public void podcastsAndUnknownTokensAreLeftAlone() {
        String prefix = "CONTENT_RELOAD_CONTINUATION_TOKEN_";
        for (String name : new String[]{"EPISODE_CONTAINERS", "UNSPECIFIED", "EPISODE_CONTAINERS_DOWNLOADED_SUBCHIP",
                "_DOWNLOADED_SUBCHIP"}) {
            assertEquals(name, Layout.NONE, DownloadsPageLogic.layoutOf(prefix + name));
        }
        assertEquals(Layout.NONE, DownloadsPageLogic.layoutOf("something else"));
        assertEquals(Layout.NONE, DownloadsPageLogic.layoutOf(null));
    }

    // endregion

    // region sections

    @Test public void theDownloadsChipShowsTheSourcesThenTheSongsEachUnderItsTitle() {
        List<byte[]> items = sections(Layout.ALL, true, List.of(PLAYLIST), List.of(SONG));

        assertEquals(2, items.size());
        assertEquals("Albums and playlists", shelfTitle(items.get(0)));
        assertEquals("Songs", shelfTitle(items.get(1)));
        assertEquals(List.of("Mix"), rowTitles(items.get(0)));
        assertEquals(List.of("Song X"), rowTitles(items.get(1)));
    }

    @Test public void theSongsChipShowsOnlySongsAndASourceChipOnlyItsKindWithoutATitle() {
        List<SourceRow> sources = List.of(PLAYLIST, ALBUM);
        List<byte[]> songs = sections(Layout.SONGS, true, sources, List.of(SONG));
        assertEquals(1, songs.size());
        assertEquals(List.of("Song X"), rowTitles(songs.get(0)));
        assertNull(shelfTitle(songs.get(0)));

        assertEquals(List.of("Mix"), rowTitles(sections(Layout.PLAYLISTS, true, sources, List.of(SONG)).get(0)));
        assertEquals(List.of("Album"), rowTitles(sections(Layout.ALBUMS, true, sources, List.of(SONG)).get(0)));
        assertNull(shelfTitle(sections(Layout.ALBUMS, true, sources, List.of(SONG)).get(0)));
    }

    @Test public void thereIsNothingToAddForALayoutWithNothingToShow() {
        assertTrue(sections(Layout.NONE, true, List.of(PLAYLIST), List.of(SONG)).isEmpty());
        assertTrue(sections(Layout.ALL, true, List.of(), List.of()).isEmpty());
        assertTrue(sections(Layout.ALBUMS, true, List.of(PLAYLIST), List.of(SONG)).isEmpty());
        assertTrue(sections(Layout.SONGS, true, List.of(PLAYLIST), List.of()).isEmpty());
    }

    @Test public void offlineASongWithoutStoredDataAndASourceWithoutAPlayableSongAreLeftOut() {
        List<byte[]> items = sections(Layout.ALL, false, List.of(PLAYLIST, ALBUM), List.of(SONG, GHOST));

        assertEquals(List.of("Mix"), rowTitles(items.get(0)));
        assertEquals(List.of("Song X"), rowTitles(items.get(1)));
    }

    @Test public void onlineThoseRowsAreShownAndTheSongWithoutDataSaysItPlaysOfflineAfterOnePlay() {
        List<byte[]> items = sections(Layout.ALL, true, List.of(PLAYLIST, ALBUM), List.of(SONG, GHOST));

        assertEquals(List.of("Mix", "Album"), rowTitles(items.get(0)));
        assertEquals(List.of("Song X", "Downloaded song"), rowTitles(items.get(1)));
        assertEquals("Plays offline after it has played online once", rowSubtitles(items.get(1)).get(1));
    }

    @Test public void aRowCarriesItsSubtitleItsCoverAndItsTap() {
        byte[] shelf = sections(Layout.ALL, true, List.of(PLAYLIST, ALBUM), List.of(SONG)).get(0);

        assertEquals(List.of("3 songs", "10 songs"), rowSubtitles(shelf));
        assertEquals(List.of("file:///x", "https://img/b"), rowCovers(shelf));
        assertEquals(List.of("source:PLa", "source:OLAK5uy_b"), rowTaps(shelf));

        byte[] songs = sections(Layout.ALL, true, List.of(PLAYLIST), List.of(SONG)).get(1);
        assertEquals(List.of("Artist · 3:07"), rowSubtitles(songs));
        assertEquals(List.of("song:x"), rowTaps(songs));
    }

    @Test public void aRowWithoutACommandIsLeftOut() {
        DownloadsPageLogic.Taps none = new DownloadsPageLogic.Taps() {
            @Override public byte[] source(SourceRow row) {
                return null;
            }

            @Override public byte[] song(SongRow row) {
                return row.videoId().equals("x") ? null : TAPS.song(row);
            }
        };
        List<byte[]> items = DownloadsPageLogic.sections(Layout.ALL, true, List.of(PLAYLIST), List.of(SONG, GHOST),
                LABELS, none, COVERS);

        assertEquals(1, items.size());
        assertEquals(List.of("Downloaded song"), rowTitles(items.get(0)));
    }

    @Test public void aRowWithoutACoverHasNoImage() {
        SongRow plain = new SongRow("x", "Song X", "", 0, 0, null);
        byte[] shelf = DownloadsPageLogic.sections(Layout.SONGS, true, List.of(), List.of(plain), LABELS, TAPS,
                (id, url) -> null).get(0);

        assertEquals(List.of(), rowCovers(shelf));
    }

    @Test public void albumsAreTheSourcesOfAnAlbumPlaylistId() {
        assertTrue(DownloadsPageLogic.isAlbum("OLAK5uy_abc"));
        assertFalse(DownloadsPageLogic.isAlbum("PLabc"));
        assertFalse(DownloadsPageLogic.isAlbum("RDCLAK5uy_abc"));
    }

    // endregion

    // region merge

    @Test public void theEmptyStateIsReplacedByOurSectionsAndTheRestOfThePageStays() {
        byte[] chips = new ProtoWriter().string(1, "chips").toByteArray();
        byte[] continuation = new ProtoWriter().string(1, "token").toByteArray();
        byte[] page = new ProtoWriter()
                .message(1, shelfOf(entry(EMPTY_STATE, "no downloads"), headerEntry()))
                .message(2, continuation).message(6, chips).toByteArray();

        byte[] merged = DownloadsPageLogic.merge(page, sections(Layout.ALL, true, List.of(PLAYLIST), List.of(SONG)));

        List<byte[]> content = fields(merged, 1);
        assertEquals(2, content.size());
        assertEquals("Albums and playlists", shelfTitle(content.get(0)));
        assertEquals("Songs", shelfTitle(content.get(1)));
        assertArrayEquals(continuation, fields(merged, 2).get(0));
        assertArrayEquals(chips, fields(merged, 6).get(0));
    }

    @Test public void theShelfOfTheAppsOwnDownloadsStaysAndOursFollowIt() {
        byte[] own = shelfOf(entry(ROW, "stock row"));
        byte[] page = new ProtoWriter().message(1, own).toByteArray();

        byte[] merged = DownloadsPageLogic.merge(page, sections(Layout.SONGS, true, List.of(), List.of(SONG)));

        List<byte[]> content = fields(merged, 1);
        assertEquals(2, content.size());
        assertArrayEquals(own, content.get(0));
        assertEquals(List.of("Song X"), rowTitles(content.get(1)));
    }

    @Test public void theEmptyStateIsKeptWhenThereIsNothingToAdd() {
        byte[] page = new ProtoWriter().message(1, shelfOf(entry(EMPTY_STATE, "no downloads"))).toByteArray();

        assertNull(DownloadsPageLogic.merge(page, List.of()));
    }

    @Test public void aPageThatDoesNotParseIsLeftAlone() {
        List<byte[]> items = sections(Layout.SONGS, true, List.of(), List.of(SONG));

        assertNull(DownloadsPageLogic.merge(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff}, items));
    }

    @Test public void aPageOfAnotherShapeIsExtendedWithoutLosingAnyField() {
        byte[] page = new ProtoWriter().int32(3, 7).fixed32(4, 9).fixed64(5, 11L).string(6, "chips").toByteArray();

        byte[] merged = DownloadsPageLogic.merge(page, sections(Layout.SONGS, true, List.of(), List.of(SONG)));

        assertEquals(1, fields(merged, 1).size());
        assertArrayEquals("chips".getBytes(StandardCharsets.UTF_8), fields(merged, 6).get(0));
        assertTrue(merged.length > page.length);
    }

    // endregion

    // region helpers

    private static List<byte[]> sections(Layout layout, boolean online, List<SourceRow> sources, List<SongRow> songs) {
        return DownloadsPageLogic.sections(layout, online, sources, songs, LABELS, TAPS, COVERS);
    }

    private static byte[] headerEntry() {
        return new ProtoWriter().message(203_006_584, new ProtoWriter().string(1, "header").toByteArray())
                .toByteArray();
    }

    private static byte[] entry(int field, String text) {
        return new ProtoWriter().message(field, new ProtoWriter().string(1, text).toByteArray()).toByteArray();
    }

    private static byte[] shelfOf(byte[]... entries) {
        ProtoWriter shelf = new ProtoWriter();
        for (byte[] entry : entries) shelf.message(2, entry);
        shelf.bool(20, true);
        return new ProtoWriter().message(SHELF, shelf.toByteArray()).toByteArray();
    }

    private static List<byte[]> fields(byte[] message, int number) {
        List<byte[]> found = new ArrayList<>();
        try {
            CodedInputStream input = CodedInputStream.newInstance(message);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (WireFormat.getTagFieldNumber(tag) == number
                        && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    found.add(input.readByteArray());
                } else {
                    input.skipField(tag);
                }
            }
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
        return found;
    }

    private static byte[] shelfOfItem(byte[] item) {
        return fields(item, SHELF).get(0);
    }

    private static String shelfTitle(byte[] item) {
        List<byte[]> title = fields(shelfOfItem(item), 1);
        return title.isEmpty() ? null : textOf(title.get(0));
    }

    private static String textOf(byte[] text) {
        return new String(fields(fields(text, 1).get(0), 1).get(0), StandardCharsets.UTF_8);
    }

    private static List<byte[]> rows(byte[] item) {
        List<byte[]> rows = new ArrayList<>();
        for (byte[] entry : fields(shelfOfItem(item), 2)) rows.add(fields(entry, ROW).get(0));
        return rows;
    }

    private static List<String> rowTitles(byte[] item) {
        List<String> titles = new ArrayList<>();
        for (byte[] row : rows(item)) titles.add(textOf(fields(row, 3).get(0)));
        return titles;
    }

    private static List<String> rowSubtitles(byte[] item) {
        List<String> subtitles = new ArrayList<>();
        for (byte[] row : rows(item)) subtitles.add(textOf(fields(row, 4).get(0)));
        return subtitles;
    }

    private static List<String> rowTaps(byte[] item) {
        List<String> taps = new ArrayList<>();
        for (byte[] row : rows(item)) taps.add(new String(fields(row, 5).get(0), StandardCharsets.UTF_8));
        return taps;
    }

    private static List<String> rowCovers(byte[] item) {
        List<String> covers = new ArrayList<>();
        for (byte[] row : rows(item)) {
            List<byte[]> image = fields(row, 1);
            if (image.isEmpty()) continue;

            byte[] thumbnails = fields(fields(image.get(0), 164_480_666).get(0), 1).get(0);
            byte[] thumbnail = fields(thumbnails, 1).get(0);
            covers.add(new String(fields(thumbnail, 1).get(0), StandardCharsets.UTF_8));
        }
        return covers;
    }

    // endregion
}
