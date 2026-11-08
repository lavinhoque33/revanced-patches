/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Debug log of the song-row checks (tag "DownloadedRowLog", only with debug logging on): one line when the result
 * of a row changes, at most {@link #BUDGET} per process, so a fast scroll cannot flood the log. Main thread only.
 */
final class DownloadedRowLog {
    private static final int BUDGET = 40;

    private int linesLeft = BUDGET;

    /** A row was evaluated: says why it is, or is not, marked. Nothing is built unless the line is logged. */
    void decided(DownloadedMarkers.RowState state, String reason, @Nullable String videoId, String outcome) {
        if (linesLeft <= 0 || !BaseSettings.DEBUG.get()) return;

        String result = reason + "/" + outcome;
        if (result.equals(state.lastOutcome)) return;

        state.lastOutcome = result;
        linesLeft--;
        Logger.printDebug(() -> "Row " + reason + " -> " + outcome + (videoId == null ? "" : " song=" + videoId));
    }
}
