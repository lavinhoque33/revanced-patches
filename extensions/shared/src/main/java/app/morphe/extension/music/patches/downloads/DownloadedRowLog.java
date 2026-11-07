/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.view.View;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Debug log of the song-row checks (tag "DownloadedRowLog", only with debug logging on). It is rate limited so a fast
 * scroll cannot flood the log, and every line says where a row stopped: the hook never ran (no lines at all),
 * the view is not a classic row, the model is not a protobuf, the registry is empty, the model holds no
 * downloaded id, the container is missing, or the check was added or shown.
 * Used on the main thread only.
 */
final class DownloadedRowLog {
    private static final int TRANSITION_BUDGET = 40;
    private static final int HIDDEN_BUDGET = 25;
    private static final int SKIPPED_BUDGET = 3;
    private static final int LAYOUT_BUDGET = 10;
    private static final int CANDIDATES_SHOWN = 3;

    private final Map<String, Integer> outcomes = new TreeMap<>();
    private int binds;
    private int notRows;
    private int transitionsLeft = TRANSITION_BUDGET;
    private int hiddenLeft = HIDDEN_BUDGET;
    private int layoutsLeft = LAYOUT_BUDGET;

    private static boolean enabled() {
        return BaseSettings.DEBUG.get();
    }

    /** The hook ran for a row: logs the first bind, rows that are not classic rows, and a summary at 1, 2, 4, 8... */
    void bound(View view, Object model, boolean notARow) {
        if (!enabled()) return;

        binds++;
        if (notARow) notRows++;
        if (binds == 1) {
            Logger.printDebug(() -> "Row hook alive: first bind view=" + view.getClass().getSimpleName()
                    + " model=" + model.getClass().getName() + " badgesContainerId=0x"
                    + Integer.toHexString(DownloadedMarkers.badgesContainerId));
        }
        if (notARow && notRows <= SKIPPED_BUDGET) {
            Logger.printDebug(() -> "Row skipped: no subtitle_badges_container in view="
                    + view.getClass().getSimpleName() + " model=" + model.getClass().getName()
                    + " badgesContainerId=0x" + Integer.toHexString(DownloadedMarkers.badgesContainerId));
        }
        if ((binds & (binds - 1)) == 0) {
            int bindsNow = binds;
            int notRowsNow = notRows;
            Logger.printDebug(() -> "Row summary: binds=" + bindsNow + " notClassicRows=" + notRowsNow
                    + " outcomes=" + outcomes + " registry=" + DownloadedMarkers.registrySize()
                    + " loaded=" + DownloadedMarkers.isLoaded());
        }
    }

    /** A row was evaluated; logs only when its result changed, within a budget. */
    void decided(View view, DownloadedMarkers.RowState state, @Nullable Object model, @Nullable byte[] bytes,
                 DownloadedRegistry registry, String reason, @Nullable String videoId, String outcome,
                 boolean binding) {
        if (!enabled()) return;

        String result = reason + "/" + outcome;
        outcomes.merge(result, 1, Integer::sum);
        if (result.equals(state.lastOutcome)) return;

        boolean wasHidden = state.lastOutcome.isEmpty() || state.lastOutcome.endsWith("/hidden");
        boolean quiet = outcome.equals("hidden") && wasHidden;
        state.lastOutcome = result;
        if (quiet ? hiddenLeft-- <= 0 : transitionsLeft-- <= 0) return;

        List<String> candidates = bytes == null ? null : DownloadedRegistry.candidateIds(bytes, CANDIDATES_SHOWN);
        Logger.printDebug(() -> "Row " + (binding ? "bind" : "refresh") + ": view=" + view.getClass().getSimpleName()
                + " model=" + (model == null ? "null" : model.getClass().getSimpleName())
                + " bytes=" + (bytes == null ? "-" : bytes.length) + " reason=" + reason
                + " id=" + (videoId == null ? "-" : videoId) + " candidates=" + candidates
                + " registry=" + registry.trackCount() + " outcome=" + outcome);
    }

    /** After a check was added: its real size, to tell a check that is shown from one that is invisible. */
    void afterLayout(View check) {
        if (!enabled() || layoutsLeft <= 0) return;

        check.post(() -> {
            if (layoutsLeft-- <= 0) return;

            Logger.printDebug(() -> "Row check laid out: " + check.getWidth() + "x" + check.getHeight()
                    + " visibility=" + check.getVisibility() + " shown=" + check.isShown()
                    + " parent=" + check.getParent().getClass().getSimpleName());
        });
    }
}
