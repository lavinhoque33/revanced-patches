/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.LibraryRestoreLogic.DirectStart.LOAD_COPIES;
import static app.morphe.extension.music.patches.downloads.LibraryRestoreLogic.DirectStart.STOCK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LibraryRestoreLogicTest {
    private static final String DOWNLOADS = "FEmusic_offline";
    private static final String LANDING = "FEmusic_library_landing";
    private static final LibraryRestoreLogic.DirectStart PLAIN = LibraryRestoreLogic.DirectStart.LANDING;

    @Test
    public void firstOnlineRestoreOfDownloadsOpensLibrary() {
        assertTrue(LibraryRestoreLogic.opensLibrary(true, DOWNLOADS, LANDING));
    }

    @Test
    public void anOfflineColdStartWithoutAUsableLandingKeepsDownloads() {
        assertFalse(LibraryRestoreLogic.opensLibrary(false, DOWNLOADS, LANDING));
    }

    @Test
    public void revisitingLibraryInTheSameActivityDoesNotClaimAnotherRestore() {
        var entry = new LibraryRestoreLogic.InitialEntry();
        Object activity = new Object();
        entry.begin(activity);
        assertTrue(entry.claim());
        assertFalse(entry.claim());
        assertTrue(entry.end(activity));
    }

    @Test
    public void recreatingMusicActivityInTheSameProcessRestoresPlainLibraryAgain() {
        var entry = new LibraryRestoreLogic.InitialEntry();
        Object original = new Object();
        entry.begin(original);
        assertTrue(entry.claim() && LibraryRestoreLogic.opensLibrary(true, DOWNLOADS, LANDING));
        assertTrue(entry.end(original));
        assertFalse(entry.claim());

        Object recreated = new Object();
        entry.begin(recreated);
        assertFalse(entry.end(original));
        assertTrue(entry.claim() && LibraryRestoreLogic.opensLibrary(true, DOWNLOADS, LANDING));
        assertFalse(entry.claim());
        assertTrue(entry.end(recreated));
    }

    @Test
    public void otherSavedPagesAreKept() {
        assertFalse(LibraryRestoreLogic.opensLibrary(true, LANDING, LANDING));
        assertFalse(LibraryRestoreLogic.opensLibrary(true, "FEmusic_liked_playlists", LANDING));
        assertFalse(LibraryRestoreLogic.opensLibrary(true, null, LANDING));
    }

    @Test
    public void tabWithAnotherStartIsKept() {
        assertFalse(LibraryRestoreLogic.opensLibrary(true, DOWNLOADS, "FEmusic_home"));
        assertFalse(LibraryRestoreLogic.opensLibrary(true, DOWNLOADS, null));
    }

    @Test
    public void onlySavedDownloadsUnderTheLibraryRootIsRewritten() {
        assertTrue(LibraryRestoreLogic.savedDownloadsTab(LANDING, DOWNLOADS));
        assertFalse(LibraryRestoreLogic.savedDownloadsTab(DOWNLOADS, DOWNLOADS));
        assertFalse(LibraryRestoreLogic.savedDownloadsTab("FEmusic_home", DOWNLOADS));
        assertFalse(LibraryRestoreLogic.savedDownloadsTab(null, DOWNLOADS));
        assertFalse(LibraryRestoreLogic.savedDownloadsTab(LANDING, LANDING));
        assertFalse(LibraryRestoreLogic.savedDownloadsTab(LANDING, null));
    }

    @Test
    public void onlyAnEligibleUnloadedOfflineStartDefers() {
        assertEquals(STOCK, LibraryRestoreLogic.directStart(false, false, false));
        assertEquals(STOCK, LibraryRestoreLogic.directStart(false, true, true));
        assertEquals(STOCK, LibraryRestoreLogic.directStart(true, true, false));
        assertEquals(PLAIN, LibraryRestoreLogic.directStart(true, true, true));
        assertEquals(PLAIN, LibraryRestoreLogic.directStart(true, false, true));
        assertEquals(LOAD_COPIES, LibraryRestoreLogic.directStart(true, false, false));
    }

    @Test
    public void aTimedOutStartUsesStockAndCannotDispatchAgain() {
        var request = new LibraryRestoreLogic.DirectRequest(1, 2, 3);
        assertEquals(STOCK, request.finish(1, 2, 3, false, false));
        assertNull(request.finish(1, 2, 3, false, true));
    }

    @Test
    public void changedNavigationOrCopiesCannotApplyAStaleStart() {
        var request = new LibraryRestoreLogic.DirectRequest(1, 2, 3);
        assertNull(request.finish(4, 2, 3, false, true));
        assertNull(request.finish(1, 2, 3, false, true));
        assertEquals(STOCK, new LibraryRestoreLogic.DirectRequest(1, 2, 3).finish(1, 2, 4, false, true));
        assertEquals(STOCK, new LibraryRestoreLogic.DirectRequest(1, 2, 3).finish(1, 4, 3, false, true));
        assertEquals(PLAIN, new LibraryRestoreLogic.DirectRequest(1, 2, 3).finish(1, 2, 3, false, true));
        assertEquals(PLAIN, new LibraryRestoreLogic.DirectRequest(1, 2, 3).finish(1, 4, 3, true, false));
    }
}
