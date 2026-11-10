/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

/**
 * Which features follow from the chosen download methods. Contains no Android code.
 *
 * <p>The checks, the Downloads chip, No Playlist and the audio start belong to the stream copies. They are in use
 * while a stream download is chosen for songs or for playlists, and they keep working for copies that already exist
 * when the user switches to another method: a switch only changes what new downloads do, never what is saved.
 */
public final class DownloadMethods {
    private DownloadMethods() {}

    /** Whether new downloads can create stream copies, which is what the stream features are for. */
    public static boolean streamChosen(SongDownloadMethod songs, PlaylistDownloadMethod playlists) {
        return songs == SongDownloadMethod.STREAM || playlists == PlaylistDownloadMethod.STREAM;
    }

    /** Whether new downloads can write device files, which Music's own Device files library lists. */
    public static boolean filesChosen(SongDownloadMethod songs, PlaylistDownloadMethod playlists) {
        return songs == SongDownloadMethod.DEVICE_FILE || playlists == PlaylistDownloadMethod.DEVICE_FILES;
    }

    /**
     * Whether Library > Downloads opens the device files library, which lists the files and their playlists.
     * Device files playlists always do. Device files songs alone do too, unless playlists are stream downloads:
     * then the Downloads chip is the stream list, which the app's own page cannot show at the same time.
     */
    public static boolean deviceLibrary(SongDownloadMethod songs, PlaylistDownloadMethod playlists) {
        if (playlists == PlaylistDownloadMethod.DEVICE_FILES) return true;
        return songs == SongDownloadMethod.DEVICE_FILE && playlists != PlaylistDownloadMethod.STREAM;
    }

    /** Whether the playlists downloaded by this app (stream copies or device files) can be offered in Android Auto. */
    public static boolean playlistsKept(PlaylistDownloadMethod playlists) {
        return playlists != PlaylistDownloadMethod.MUSIC_OWN;
    }
}
