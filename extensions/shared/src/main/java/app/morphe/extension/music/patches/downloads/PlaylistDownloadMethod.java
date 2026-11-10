/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

/**
 * How the Download of a whole playlist or album works; one method at a time. The names are the saved values of the
 * setting and the entry values of its list.
 */
public enum PlaylistDownloadMethod {
    /** Private copies of the streams, played by the app itself, online or offline. */
    STREAM,
    /** The older feature: audio files in Music/RVX, and a playlist in Device files. */
    DEVICE_FILES,
    /** Leaves the Download button to YouTube Music itself, which needs Premium. */
    MUSIC_OWN
}
