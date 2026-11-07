/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import java.util.function.BooleanSupplier;

/**
 * What a download does, from the two independent settings: local files (Download in app) and the private
 * stream copy (Play downloads locally). The mode is decided once when a download is requested; contains no
 * Android code.
 */
enum DownloadMode {
    /** Neither setting: stock behaviour, nothing is intercepted. */
    OFF,
    /** Files in Music/RVX only; no stream copy. */
    LOCAL_FILES,
    /** The private stream copy only: no file, no MediaStore row, no playlist rows. */
    STREAM_COPY,
    /** Both, from one network download per track. */
    BOTH;

    /** What is left to do for one track. */
    enum Work {
        SKIP,
        /** Write the local file; keep no stream copy. */
        LOCAL_FILE,
        /** Keep the stream copy only. */
        STREAM_COPY,
        /** Write the local file and keep a stream copy tied to it. */
        LOCAL_FILE_AND_STREAM_COPY,
        /** The local file already exists: keep a stream copy tied to it, and do not write the file again. */
        STREAM_COPY_FOR_EXISTING_FILE;

        boolean writesLocalFile() {
            return this == LOCAL_FILE || this == LOCAL_FILE_AND_STREAM_COPY;
        }

        boolean keepsStreamCopy() {
            return this == STREAM_COPY || this == LOCAL_FILE_AND_STREAM_COPY
                    || this == STREAM_COPY_FOR_EXISTING_FILE;
        }
    }

    static DownloadMode of(boolean localFiles, boolean streamCopy) {
        if (localFiles) return streamCopy ? BOTH : LOCAL_FILES;
        return streamCopy ? STREAM_COPY : OFF;
    }

    /** True when the mode writes local files (Music/RVX, MediaStore, playlists in Device files). */
    boolean localFiles() {
        return this == LOCAL_FILES || this == BOTH;
    }

    /** True when the mode keeps a private stream copy. */
    boolean streamCopy() {
        return this == STREAM_COPY || this == BOTH;
    }

    /**
     * What a track still needs. The lookups are only asked for what the mode cares about, because both can be
     * slow (a MediaStore query, hashing a stream copy).
     *
     * <ul>
     * <li>Local files only: skipped when the native track exists.
     * <li>Stream copy only: skipped when a valid stream copy exists.
     * <li>Both: skipped when both exist; a missing stream copy of an existing file is made without writing the
     * file again; a missing file is written, and a valid stream copy that already exists is left alone.
     * </ul>
     */
    Work plan(BooleanSupplier nativeTrackExists, BooleanSupplier streamCopyExists) {
        switch (this) {
            case LOCAL_FILES:
                return nativeTrackExists.getAsBoolean() ? Work.SKIP : Work.LOCAL_FILE;
            case STREAM_COPY:
                return streamCopyExists.getAsBoolean() ? Work.SKIP : Work.STREAM_COPY;
            case BOTH:
                if (nativeTrackExists.getAsBoolean()) {
                    return streamCopyExists.getAsBoolean() ? Work.SKIP : Work.STREAM_COPY_FOR_EXISTING_FILE;
                }
                return streamCopyExists.getAsBoolean() ? Work.LOCAL_FILE : Work.LOCAL_FILE_AND_STREAM_COPY;
            default:
                return Work.SKIP;
        }
    }
}
