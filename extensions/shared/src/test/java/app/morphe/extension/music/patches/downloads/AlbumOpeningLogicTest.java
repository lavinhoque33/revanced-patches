/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class AlbumOpeningLogicTest {
    private static final String ALBUM = "OLAK5uy_mz6eafmqdRHSaR4IwG0ll6J6rgv0_ZpGw";
    private static final String PAGE = "MPREb_7ltM34kr0mH";
    private static final Set<String> DOWNLOADED = Set.of(ALBUM, "PLplaylist", "OLAK5uy_other");
    private static final Map<String, String> PROVED = Map.of("OLAK5uy_other", "MPREb_other");
    private static final Predicate<String> NEVER = id -> false;

    private static AlbumOpeningLogic.Decision route(boolean online, boolean chip, boolean loaded, String browseId,
                                                    Predicate<String> unresolved) {
        return AlbumOpeningLogic.routeOf(online, chip, loaded, browseId, DOWNLOADED::contains, PROVED::get, unresolved);
    }

    @Test public void anAlbumWithNoProvedPageIsResolvedBeforeItOpens() {
        AlbumOpeningLogic.Decision decision = route(true, true, true, "VL" + ALBUM, NEVER);

        assertEquals(AlbumOpeningLogic.Route.RESOLVE, decision.route());
        assertEquals(ALBUM, decision.sourceId());
        assertNull(decision.pageId());
    }

    @Test public void anAlbumWithAProvedPageOpensThatPageWhateverCommandAsked() {
        AlbumOpeningLogic.Decision decision = route(true, true, true, "VLOLAK5uy_other", NEVER);

        assertEquals(AlbumOpeningLogic.Route.OPEN_ALBUM_PAGE, decision.route());
        assertEquals("OLAK5uy_other", decision.sourceId());
        assertEquals("MPREb_other", decision.pageId());
    }

    @Test public void anAlbumThatCouldNotBeProvedARoundAgoOpensItsSavedPageWithoutWaiting() {
        AlbumOpeningLogic.Decision decision = route(true, true, true, "VL" + ALBUM, ALBUM::equals);

        assertEquals(AlbumOpeningLogic.Route.SAVED, decision.route());
        assertEquals(ALBUM, decision.sourceId());
    }

    @Test public void aProvedPageIsPreferredToARecentFailure() {
        AlbumOpeningLogic.Decision decision = route(true, true, true, "VLOLAK5uy_other", id -> true);

        assertEquals(AlbumOpeningLogic.Route.OPEN_ALBUM_PAGE, decision.route());
    }

    @Test public void offlineTheSavedPageIsLeftToThePageItself() {
        assertEquals(AlbumOpeningLogic.Route.STOCK, route(false, true, true, "VL" + ALBUM, NEVER).route());
    }

    @Test public void onlyTheDownloadsChipOfTheLibraryIsHeld() {
        assertEquals(AlbumOpeningLogic.Route.STOCK, route(true, false, true, "VL" + ALBUM, NEVER).route());
    }

    @Test public void nothingIsHeldBeforeTheProvedPagesAreKnown() {
        assertEquals(AlbumOpeningLogic.Route.STOCK, route(true, true, false, "VL" + ALBUM, NEVER).route());
    }

    @Test public void onlyThePlaylistPageOfADownloadedAlbumIsHeld() {
        for (String browseId : new String[] {null, PAGE, ALBUM, "VLPLplaylist", "VLOLAK5uy_notDownloaded",
                "FEmusic_home", "VL", ""}) {
            assertEquals(String.valueOf(browseId), AlbumOpeningLogic.Route.STOCK,
                    route(true, true, true, browseId, NEVER).route());
        }
    }

    @Test public void aStockDecisionNamesNothing() {
        AlbumOpeningLogic.Decision decision = route(true, true, true, null, NEVER);

        assertNull(decision.sourceId());
        assertNull(decision.pageId());
    }

    @Test public void aTapIsOnlyWantedWhileNothingElseWasOpenedAndTheChipIsShown() {
        assertTrue(AlbumOpeningLogic.stillWanted(4, 4, true));
        assertFalse(AlbumOpeningLogic.stillWanted(4, 5, true));
        assertFalse(AlbumOpeningLogic.stillWanted(4, 4, false));
    }

    @Test public void anAlbumIsUnresolvedForFiveMinutesAfterItFailed() {
        assertFalse(AlbumOpeningLogic.unresolvedRecently(null, 1_000_000));
        assertTrue(AlbumOpeningLogic.unresolvedRecently(1_000L, 1_000L));
        assertTrue(AlbumOpeningLogic.unresolvedRecently(1_000L, 1_000L + 5 * 60_000 - 1));
        assertFalse(AlbumOpeningLogic.unresolvedRecently(1_000L, 1_000L + 5 * 60_000));
    }

    @Test public void theWaitIsShorterThanTheTimeAFailureIsRemembered() {
        assertTrue(AlbumOpeningLogic.WAIT_MS < AlbumOpeningLogic.UNRESOLVED_MS);
        assertEquals(8_000, AlbumOpeningLogic.WAIT_MS);
    }

    @Test public void aSecondTapOnTheSameAlbumWhileItResolvesOpensForTheLatestTap() {
        byte[] first = {1};
        byte[] second = {2};
        long firstSerial = 7;
        long secondSerial = 8; // the second tap's own dispatch advanced the navigation serial
        AlbumOpeningLogic.HeldTap held = new AlbumOpeningLogic.HeldTap(firstSerial, first);

        assertTrue(held.adopt(secondSerial, second));

        // Nothing else was opened since the second tap: the lookup, which ends later, still opens the album.
        assertTrue(held.end());
        assertTrue(AlbumOpeningLogic.stillWanted(held.serial(), secondSerial, true));
        assertSame(second, held.command());
        // Held by the first tap's serial it would have been dropped, and no tap would have opened anything.
        assertFalse(AlbumOpeningLogic.stillWanted(firstSerial, secondSerial, true));
    }

    @Test public void aTapThatCameAfterTheLookupEndedStartsItsOwnAndTheEndedOneOpensOnce() {
        AlbumOpeningLogic.HeldTap held = new AlbumOpeningLogic.HeldTap(1, new byte[]{1});

        assertTrue(held.end());
        assertFalse(held.end());
        assertFalse(held.adopt(2, new byte[]{2}));
        assertEquals(1, held.serial());
    }

    @Test public void aTapThatMovedOnAfterTheSecondTapStillCancelsTheOpening() {
        AlbumOpeningLogic.HeldTap held = new AlbumOpeningLogic.HeldTap(7, new byte[]{1});
        held.adopt(8, new byte[]{2});

        // Another page was opened after the second tap (serial 9): the user moved on, nothing opens.
        assertFalse(AlbumOpeningLogic.stillWanted(held.serial(), 9, true));
    }
}
