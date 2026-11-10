/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DownloadMethodsTest {
    @Test public void theStreamFeaturesAreInUseWhenEitherKindChoosesTheStreamMethod() {
        assertTrue(DownloadMethods.streamChosen(SongDownloadMethod.STREAM, PlaylistDownloadMethod.STREAM));
        assertTrue(DownloadMethods.streamChosen(SongDownloadMethod.STREAM, PlaylistDownloadMethod.DEVICE_FILES));
        assertTrue(DownloadMethods.streamChosen(SongDownloadMethod.DEVICE_FILE, PlaylistDownloadMethod.STREAM));
        assertFalse(DownloadMethods.streamChosen(SongDownloadMethod.DEVICE_FILE, PlaylistDownloadMethod.DEVICE_FILES));
        assertFalse(DownloadMethods.streamChosen(SongDownloadMethod.EXTERNAL_APP, PlaylistDownloadMethod.MUSIC_OWN));
    }

    @Test public void theDeviceLibraryTakesTheDownloadsChipOnlyWhenNoStreamListNeedsIt() {
        SongDownloadMethod device = SongDownloadMethod.DEVICE_FILE;
        assertTrue(DownloadMethods.deviceLibrary(SongDownloadMethod.STREAM, PlaylistDownloadMethod.DEVICE_FILES));
        assertTrue(DownloadMethods.deviceLibrary(device, PlaylistDownloadMethod.DEVICE_FILES));
        assertTrue(DownloadMethods.deviceLibrary(device, PlaylistDownloadMethod.MUSIC_OWN));
        assertFalse(DownloadMethods.deviceLibrary(device, PlaylistDownloadMethod.STREAM));
        assertFalse(DownloadMethods.deviceLibrary(SongDownloadMethod.STREAM, PlaylistDownloadMethod.STREAM));
        assertFalse(DownloadMethods.deviceLibrary(SongDownloadMethod.EXTERNAL_APP, PlaylistDownloadMethod.MUSIC_OWN));
    }

    @Test public void deviceFilesAreInUseWhenEitherKindChoosesThem() {
        assertTrue(DownloadMethods.filesChosen(SongDownloadMethod.DEVICE_FILE, PlaylistDownloadMethod.STREAM));
        assertTrue(DownloadMethods.filesChosen(SongDownloadMethod.STREAM, PlaylistDownloadMethod.DEVICE_FILES));
        assertFalse(DownloadMethods.filesChosen(SongDownloadMethod.STREAM, PlaylistDownloadMethod.STREAM));
    }

    @Test public void androidAutoOffersPlaylistsTheAppDownloadedItself() {
        assertTrue(DownloadMethods.playlistsKept(PlaylistDownloadMethod.STREAM));
        assertTrue(DownloadMethods.playlistsKept(PlaylistDownloadMethod.DEVICE_FILES));
        assertFalse(DownloadMethods.playlistsKept(PlaylistDownloadMethod.MUSIC_OWN));
    }

    @Test public void theStreamCopySwitchOfTheEarlierBuildsMeansTheStreamMethodWhateverElseWasOn() {
        assertNull(DownloadMethodMigration.of(false, true, false, false));
        assertNull(DownloadMethodMigration.of(true, true, true, true));
        assertNull(DownloadMethodMigration.of(false, true, true, false));
    }

    @Test public void theFileSwitchAloneMeansDeviceFilesForSongsAndPlaylists() {
        DownloadMethodMigration.Methods methods = DownloadMethodMigration.of(true, false, true, false);
        assertEquals(SongDownloadMethod.DEVICE_FILE, methods.songs());
        assertEquals(PlaylistDownloadMethod.DEVICE_FILES, methods.playlists());
    }

    @Test public void anExternalDownloaderWithoutTheNewSwitchesKeepsHandingSongsToIt() {
        for (boolean menu : new boolean[]{false, true}) {
            DownloadMethodMigration.Methods methods = DownloadMethodMigration.of(false, false, !menu, menu);
            assertEquals(SongDownloadMethod.EXTERNAL_APP, methods.songs());
            assertEquals(PlaylistDownloadMethod.MUSIC_OWN, methods.playlists());
        }
    }

    @Test public void nothingSetMeansTheDefaultsAndSavesNothing() {
        assertNull(DownloadMethodMigration.of(false, false, false, false));
    }

    @Test public void aSongRouteFollowsTheMethodAndTheExternalSwitchOfThePlaceThatWasTapped() {
        assertEquals(SongDownloadRoute.OURS, SongDownloadRoute.of(SongDownloadMethod.STREAM, true, false));
        assertEquals(SongDownloadRoute.OURS, SongDownloadRoute.of(SongDownloadMethod.STREAM, true, true));
        assertEquals(SongDownloadRoute.STOCK, SongDownloadRoute.of(SongDownloadMethod.STREAM, false, true));
        assertEquals(SongDownloadRoute.OURS, SongDownloadRoute.of(SongDownloadMethod.DEVICE_FILE, false, false));
        assertEquals(SongDownloadRoute.EXTERNAL_APP, SongDownloadRoute.of(SongDownloadMethod.EXTERNAL_APP, true, true));
        assertEquals(SongDownloadRoute.STOCK, SongDownloadRoute.of(SongDownloadMethod.EXTERNAL_APP, true, false));
    }
}
