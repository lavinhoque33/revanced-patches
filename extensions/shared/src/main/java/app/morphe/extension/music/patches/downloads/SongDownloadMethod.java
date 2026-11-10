/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

/**
 * How the Download of a single song works; one method at a time. The names are the saved values of the setting and
 * the entry values of its list.
 */
public enum SongDownloadMethod {
    /** A private copy of the stream, played by the app itself, online or offline. */
    STREAM,
    /** The older feature: an audio file in Music/RVX that Device files and other apps can see. */
    DEVICE_FILE,
    /** Hands the song to the external downloader app chosen in the settings (or leaves Music's own download). */
    EXTERNAL_APP
}
