/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

/**
 * The one-time move from the two independent switches of the earlier builds (Download in app, Play downloads
 * locally) to the song and playlist download methods. Contains no Android code.
 */
public final class DownloadMethodMigration {
    /** The methods a user of the earlier switches gets. */
    public record Methods(SongDownloadMethod songs, PlaylistDownloadMethod playlists) {}

    private DownloadMethodMigration() {}

    /**
     * Keeps what the user had. The stream copy switch on (alone, or together with the file switch, where one
     * download used to feed both) is the stream method for both kinds; the file switch alone is the file method for
     * both; with neither switch on, a user who had turned an external downloader override on keeps handing songs to
     * the external app (playlists stayed with Music itself), and anyone else gets the defaults.
     *
     * @return The methods to save, or null to leave the defaults.
     */
    @Nullable
    public static Methods of(boolean inAppFiles, boolean streamCopies, boolean externalButton, boolean externalMenu) {
        if (streamCopies) return null;
        if (inAppFiles) return new Methods(SongDownloadMethod.DEVICE_FILE, PlaylistDownloadMethod.DEVICE_FILES);
        if (externalButton || externalMenu) {
            return new Methods(SongDownloadMethod.EXTERNAL_APP, PlaylistDownloadMethod.MUSIC_OWN);
        }
        return null;
    }
}
