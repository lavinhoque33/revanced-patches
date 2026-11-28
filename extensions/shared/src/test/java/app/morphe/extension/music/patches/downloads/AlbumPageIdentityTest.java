/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The album relationship of a native page, read from the real response context and header of one album page as the
 * six supported versions serve it (the track rows are replaced by one marker field: they are not what is read).
 */
public class AlbumPageIdentityTest {
    private static final List<String> VERSIONS = List.of("8.12.54", "8.28.54", "8.30.54", "9.15.51", "9.40.51",
            "9.45.51");
    private static final String PAGE = "MPREb_7ltM34kr0mH";
    private static final String ALBUM = "OLAK5uy_mz6eafmqdRHSaR4IwG0ll6J6rgv0_ZpGw";
    private static final int CONTENTS = 9;
    private static final int[] HEADER_PATH = {13, 361_650_780, 2, 153_515_154, 172_660_663, 1, 168_777_401, 5};
    private static final int[] LEGACY_BUTTONS = {373_186_255, 14};
    private static final int[] LEGACY_OWNER = {1, 6, 169_495_254, 52_666_186};

    @Test public void everySupportedVersionNamesItsAlbumPlaylistInThePrimaryHeader() throws Exception {
        for (String version : VERSIONS) {
            assertEquals(version, ALBUM, PlaylistSyncPage.albumPlaylistOf(page(version)));
        }
    }

    @Test public void everySupportedVersionNamesItsBrowseIdInTheResponseContext() throws Exception {
        for (String version : VERSIONS) {
            assertEquals(version, PAGE, PlaylistSyncPage.browseIdOf(page(version)));
        }
    }

    @Test public void theRealPageIsTheAlbumPageOfItsPlaylistOnEveryVersion() throws Exception {
        for (String version : VERSIONS) {
            assertTrue(version, PlaylistSyncPage.isAlbumPage(PAGE, ALBUM, page(version)));
        }
    }

    @Test public void aPageIsNotTheAlbumPageOfAnotherPlaylistOrAnotherBrowseId() throws Exception {
        for (String version : VERSIONS) {
            byte[] page = page(version);
            assertFalse(version, PlaylistSyncPage.isAlbumPage(PAGE, "OLAK5uy_otherAlbum", page));
            assertFalse(version, PlaylistSyncPage.isAlbumPage("MPREb_otherRelease", ALBUM, page));
            assertFalse(version, PlaylistSyncPage.isAlbumPage(ALBUM, ALBUM, page));
        }
    }

    @Test public void theNextRowsOfAlbumAreNotItsPage() throws Exception {
        byte[] page = page("9.45.51");
        byte[] rows = new ProtoWriter().raw(page, 0, page.length)
                .message(OfflineBrowsePagesLogic.CONTINUATION_FIELD, new byte[] {1}).toByteArray();

        assertFalse(PlaylistSyncPage.isAlbumPage(PAGE, ALBUM, rows));
    }

    @Test public void aResponseWithoutContentsIsNotAPage() throws Exception {
        byte[] header = resource("9.45.51");

        assertFalse(PlaylistSyncPage.isAlbumPage(PAGE, ALBUM, header));
    }

    @Test public void aRelatedAlbumShelfDoesNotChangeTheOwner() throws Exception {
        byte[] header = resource("9.40.51");
        byte[] related = new ProtoWriter().string(1, "OLAK5uy_relatedAlbum").toByteArray();
        byte[] page = new ProtoWriter().raw(header, 0, header.length).message(CONTENTS, related).toByteArray();

        assertEquals(ALBUM, PlaylistSyncPage.albumPlaylistOf(page));
    }

    @Test public void aPageThatNamesNoBrowseIdIsStillRecognizedByItsPrimaryPlay() throws Exception {
        byte[] page = withHeader(legacyHeader(ALBUM));

        assertNull(PlaylistSyncPage.browseIdOf(page));
        assertTrue(PlaylistSyncPage.isAlbumPage(PAGE, ALBUM, page));
    }

    @Test public void twoDifferentOwnersInOneHeaderAreNoOwner() throws Exception {
        assertNull(PlaylistSyncPage.albumPlaylistOf(withHeader(legacyHeader(ALBUM, "OLAK5uy_otherAlbum"))));
    }

    @Test public void theSameOwnerRepeatedIsStillOneOwner() throws Exception {
        assertEquals(ALBUM, PlaylistSyncPage.albumPlaylistOf(withHeader(legacyHeader(ALBUM, ALBUM))));
    }

    @Test public void aPlayButtonOfAnythingButAnAlbumPlaylistIsNoOwner() throws Exception {
        assertNull(PlaylistSyncPage.albumPlaylistOf(withHeader(legacyHeader(ALBUM, "RDAMPL" + ALBUM))));
        assertNull(PlaylistSyncPage.albumPlaylistOf(withHeader(legacyHeader("PLsomePlaylist"))));
    }

    @Test public void twoDifferentBrowseIdsInTheContextAreNoProof() {
        byte[] page = new ProtoWriter().message(1, conflictingContext()).toByteArray();

        assertThrows(IOException.class, () -> PlaylistSyncPage.browseIdOf(page));
    }

    @Test public void theSameBrowseIdRepeatedIsStillOneBrowseId() throws Exception {
        byte[] context = new ProtoWriter().message(6, param("browse_id", PAGE))
                .message(6, param("browse_id", PAGE)).toByteArray();
        byte[] page = new ProtoWriter().message(1, context).toByteArray();

        assertEquals(PAGE, PlaylistSyncPage.browseIdOf(page));
    }

    @Test public void aValidPlayOwnerWithTwoDifferentBrowseIdsIsNotTheAlbumPage() throws Exception {
        byte[] header = withHeader(legacyHeader(ALBUM));
        byte[] page = new ProtoWriter().message(1, conflictingContext()).raw(header, 0, header.length).toByteArray();

        // Without the two ids the same header is the album page: the Play owner alone is valid proof.
        assertTrue(PlaylistSyncPage.isAlbumPage(PAGE, ALBUM, header));
        assertFalse(PlaylistSyncPage.isAlbumPage(PAGE, ALBUM, page));
        assertFalse(PlaylistSyncPage.isAlbumPage("MPREb_x", ALBUM, page));
    }

    @Test public void twoDifferentBrowseIdsInTwoContextsAreNoProofEither() {
        byte[] first = new ProtoWriter().message(6, param("browse_id", PAGE)).toByteArray();
        byte[] second = new ProtoWriter().message(6, param("browse_id", "MPREb_x")).toByteArray();
        byte[] page = new ProtoWriter().message(1, first).message(1, second).toByteArray();

        assertThrows(IOException.class, () -> PlaylistSyncPage.browseIdOf(page));
    }

    @Test public void theSameBrowseIdInTwoContextsIsStillOneBrowseId() throws Exception {
        byte[] context = new ProtoWriter().message(6, param("browse_id", PAGE)).toByteArray();
        byte[] page = new ProtoWriter().message(1, context).message(1, context).toByteArray();

        assertEquals(PAGE, PlaylistSyncPage.browseIdOf(page));
    }

    @Test public void aBrowseIdInALaterContextIsNotMissed() throws Exception {
        byte[] empty = new ProtoWriter().message(6, new byte[0]).toByteArray();
        byte[] named = new ProtoWriter().message(6, param("browse_id", PAGE)).toByteArray();
        byte[] page = new ProtoWriter().message(1, empty).message(1, named).toByteArray();

        assertEquals(PAGE, PlaylistSyncPage.browseIdOf(page));
    }

    @Test public void aPageWhoseContextsDisagreeIsNotTheAlbumPageEvenWithAValidPlayOwner() throws Exception {
        byte[] header = withHeader(legacyHeader(ALBUM));
        byte[] first = new ProtoWriter().message(6, param("browse_id", PAGE)).toByteArray();
        byte[] second = new ProtoWriter().message(6, param("browse_id", "MPREb_x")).toByteArray();
        byte[] page = new ProtoWriter().message(1, first).message(1, second).raw(header, 0, header.length)
                .toByteArray();

        assertFalse(PlaylistSyncPage.isAlbumPage(PAGE, ALBUM, page));
        assertFalse(PlaylistSyncPage.isAlbumPage("MPREb_x", ALBUM, page));
    }

    private static byte[] conflictingContext() {
        return new ProtoWriter().message(6, param("browse_id", PAGE))
                .message(6, param("browse_id", "MPREb_x")).toByteArray();
    }

    private static byte[] page(String version) throws IOException {
        byte[] header = resource(version);
        return new ProtoWriter().raw(header, 0, header.length).message(CONTENTS, new byte[] {8, 1}).toByteArray();
    }

    private static byte[] resource(String version) throws IOException {
        String name = "/albumheader/public-album-" + version + "-header.pb";
        try (InputStream input = AlbumPageIdentityTest.class.getResourceAsStream(name)) {
            assertTrue(name, input != null);
            return input.readAllBytes();
        }
    }

    private static byte[] param(String key, String value) {
        return new ProtoWriter().message(2, new ProtoWriter().string(1, key).string(2, value).toByteArray())
                .toByteArray();
    }

    /** The older header: several entries of one repeated field, each with the owner of its own button. */
    private static byte[] legacyHeader(String... owners) {
        ProtoWriter buttons = new ProtoWriter();
        for (String owner : owners) {
            byte[] entry = new ProtoWriter().string(1, owner).toByteArray();
            for (int i = LEGACY_OWNER.length - 1; i >= 0; i--) {
                entry = new ProtoWriter().message(LEGACY_OWNER[i], entry).toByteArray();
            }
            buttons.message(7, entry);
        }
        byte[] inner = buttons.toByteArray();
        byte[] header = new ProtoWriter().message(LEGACY_BUTTONS[1], inner).toByteArray();
        header = new ProtoWriter().message(LEGACY_BUTTONS[0], header).toByteArray();
        for (int i = HEADER_PATH.length - 1; i >= 0; i--) {
            header = new ProtoWriter().message(HEADER_PATH[i], header).toByteArray();
        }
        return header;
    }

    private static byte[] withHeader(byte[] header) {
        return new ProtoWriter().raw(header, 0, header.length).message(CONTENTS, new byte[] {8, 1}).toByteArray();
    }
}
