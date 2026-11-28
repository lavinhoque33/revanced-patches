/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which album page a downloaded album opens as: the pages its playlist rows link to, tried in the order of how many
 * rows link to them, and the first one whose own header names the album.
 */
public class AlbumPageResolverTest {
    private static final String PAGE = "MPREb_7ltM34kr0mH";
    private static final String ALBUM = "OLAK5uy_mz6eafmqdRHSaR4IwG0ll6J6rgv0_ZpGw";
    private static final int CONTENTS = 9;

    @Test public void theLinkedAlbumPageOfEveryRowIsTheCandidate() throws Exception {
        assertEquals(List.of(PAGE), AlbumPageResolver.candidates(playlist(rows(PAGE, 10))));
    }

    @Test public void theMostLinkedPageComesFirstAndTheLeastLinkedLast() throws Exception {
        JSONObject response = playlist(rows("MPREb_few", 1), rows(PAGE, 7), rows("MPREb_some", 3));

        assertEquals(List.of(PAGE, "MPREb_some", "MPREb_few"), AlbumPageResolver.candidates(response));
    }

    @Test public void aTieKeepsThePageOrderOfTheResponse() throws Exception {
        JSONObject response = playlist(rows("MPREb_first", 2), rows("MPREb_second", 2));

        assertEquals(List.of("MPREb_first", "MPREb_second"), AlbumPageResolver.candidates(response));
    }

    @Test public void atMostThreePagesAreAsked() throws Exception {
        JSONObject response = playlist(rows("MPREb_a", 5), rows("MPREb_b", 4), rows("MPREb_c", 3),
                rows("MPREb_d", 2), rows("MPREb_e", 1));

        assertEquals(List.of("MPREb_a", "MPREb_b", "MPREb_c"), AlbumPageResolver.candidates(response));
        assertEquals(3, AlbumPageResolver.MAX_CANDIDATES);
    }

    @Test public void aLinkOutsideTheTrackRowsIsOnlyUsedWhenTheRowsHaveNone() throws Exception {
        JSONObject elsewhere = new JSONObject().put("header", link("MPREb_header")).put("related", link("MPREb_rel"));

        JSONObject withRows = playlist(rows(PAGE, 2)).put("elsewhere", elsewhere);
        JSONObject withoutRows = playlist().put("elsewhere", elsewhere);

        assertEquals(List.of(PAGE), AlbumPageResolver.candidates(withRows));
        assertEquals(Set.of("MPREb_header", "MPREb_rel"), new HashSet<>(AlbumPageResolver.candidates(withoutRows)));
    }

    @Test public void onlyAlbumPagesAreCandidates() throws Exception {
        JSONArray row = new JSONArray().put(link("VLPLsomePlaylist")).put(link("UCsomeArtist")).put(link(PAGE));

        assertEquals(List.of(PAGE), AlbumPageResolver.candidates(playlist(row)));
        assertTrue(AlbumPageResolver.candidates(new JSONObject()).isEmpty());
    }

    @Test public void theRealPlaylistResponseLinksToItsAlbumPageFromEveryRow() throws Exception {
        JSONObject response = new JSONObject(new String(resource("/albumresolve/playlist-response.json"),
                StandardCharsets.UTF_8));

        assertEquals(List.of("MPREb_tIuZJn0Ec5n"), AlbumPageResolver.candidates(response));
    }

    @Test public void theFirstPageThatNamesTheAlbumWins() throws Exception {
        byte[] page = page();
        List<String> asked = new ArrayList<>();

        AlbumPageResolver.Resolved resolved = AlbumPageResolver.resolve(ALBUM,
                List.of("MPREb_wrong", PAGE, "MPREb_never"), id -> {
                    asked.add(id);
                    return page;
                });

        assertEquals(PAGE, resolved.pageId());
        assertSame(page, resolved.page());
        assertEquals(List.of("MPREb_wrong", PAGE), asked);
    }

    @Test public void noCandidateThatNamesTheAlbumIsNoPage() throws Exception {
        byte[] page = page();

        assertNull(AlbumPageResolver.resolve("OLAK5uy_otherAlbum", List.of(PAGE, "MPREb_other"), id -> page));
        assertNull(AlbumPageResolver.resolve(ALBUM, List.of(), id -> page));
    }

    @Test public void aPageThatIsNotThePageItWasAskedForIsNotAccepted() throws Exception {
        byte[] page = page();

        assertNull(AlbumPageResolver.resolve(ALBUM, List.of("MPREb_otherRelease"), id -> page));
    }

    @Test public void aFailedRequestEndsTheSearchRatherThanSkippingThePage() {
        IOException offline = new IOException("offline");

        IOException thrown = assertThrows(IOException.class, () -> AlbumPageResolver.resolve(ALBUM,
                List.of(PAGE, "MPREb_other"), id -> {
                    throw offline;
                }));

        assertSame(offline, thrown);
    }

    /** The native first page of the real 9.45.51 album: its header and context, one marker for the rows. */
    private static byte[] page() throws IOException {
        byte[] header = resource("/albumheader/public-album-9.45.51-header.pb");
        return new ProtoWriter().raw(header, 0, header.length).message(CONTENTS, new byte[] {8, 1}).toByteArray();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = AlbumPageResolverTest.class.getResourceAsStream(name)) {
            assertTrue(name, input != null);
            return input.readAllBytes();
        }
    }

    /** A playlist response with the track rows' menus of each group inside its shelf, as the real one has them. */
    private static JSONObject playlist(JSONArray... groups) throws Exception {
        JSONArray contents = new JSONArray();
        for (JSONArray group : groups) {
            for (int i = 0; i < group.length(); i++) contents.put(group.get(i));
        }
        JSONObject shelf = new JSONObject().put("musicPlaylistShelfRenderer",
                new JSONObject().put("contents", contents));
        return new JSONObject().put("contents", shelf);
    }

    private static JSONArray rows(String page, int count) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) rows.put(new JSONObject().put("menu", link(page)));
        return rows;
    }

    private static JSONObject link(String browseId) throws Exception {
        return new JSONObject().put("browseEndpoint", new JSONObject().put("browseId", browseId));
    }
}
