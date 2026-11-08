/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

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
    private static final int HIDDEN_BUDGET = 10;
    private static final int HIDDEN_WITH_IDS_BUDGET = 40;
    private static final int SKIPPED_BUDGET = 3;
    private static final int LAYOUT_BUDGET = 16;
    private static final int CANDIDATES_SHOWN = 4;
    private static final int ALWAYS_BUDGET = 40;
    private static final int DUMP_BUDGET = 2;
    private static final int DUMP_MIN_BYTES = 1000;
    private static final int POSITIONS_SHOWN = 3;
    private static final int VIEW_DUMP_DEPTH = 8;
    private static final int VIEW_DUMP_NODES = 90;
    private static final int SHORT_TEXT = 3;

    private final Map<String, Integer> outcomes = new TreeMap<>();
    private final Map<String, Integer> models = new TreeMap<>();
    private int binds;
    private int notRows;
    private int transitionsLeft = TRANSITION_BUDGET;
    private int hiddenLeft = HIDDEN_BUDGET;
    private int hiddenWithIdsLeft = HIDDEN_WITH_IDS_BUDGET;
    private int layoutsLeft = LAYOUT_BUDGET;
    private int alwaysLeft = ALWAYS_BUDGET;
    private int dumpsLeft = DUMP_BUDGET;

    private static boolean enabled() {
        return BaseSettings.DEBUG.get();
    }

    /** The hook ran for a row: logs the first bind, rows that are not classic rows, and a summary at 1, 2, 4, 8... */
    void bound(View view, Object model, boolean notARow) {
        if (!enabled()) return;

        binds++;
        models.merge(model.getClass().getSimpleName(), 1, Integer::sum);
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
                    + " models=" + models + " outcomes=" + outcomes
                    + " registry=" + DownloadedMarkers.registrySize()
                    + " loaded=" + DownloadedMarkers.isLoaded());
        }
    }

    /**
     * A row was evaluated. The first rows of the process are always logged, each with what it names (video ids,
     * playlist and position) and what it resolved to, so a row that is not marked says why; after that a row is
     * logged only when its result changed, within a budget.
     */
    void decided(View view, DownloadedMarkers.RowState state, @Nullable Object model, @Nullable byte[] bytes,
                 DownloadedRegistry registry, String reason, @Nullable String videoId, String outcome,
                 boolean binding) {
        if (!enabled()) return;

        String result = reason + "/" + outcome;
        outcomes.merge(result, 1, Integer::sum);
        boolean changed = !result.equals(state.lastOutcome);
        boolean wasHidden = state.lastOutcome.isEmpty() || state.lastOutcome.endsWith("/hidden");
        state.lastOutcome = result;
        List<String> candidates = bytes == null ? null : DownloadedRegistry.candidateIds(bytes, CANDIDATES_SHOWN);
        boolean hasIds = candidates != null && !candidates.isEmpty();
        if (binding && alwaysLeft > 0) {
            alwaysLeft--;
        } else {
            if (!changed) return;

            // A hidden row that holds video ids says which ids a page carries, so it has a larger budget than one
            // without; neither may crowd out the rows that were marked.
            boolean quiet = outcome.equals("hidden") && wasHidden;
            if (quiet ? (hasIds ? hiddenWithIdsLeft-- : hiddenLeft--) <= 0 : transitionsLeft-- <= 0) return;
        }

        List<DownloadedRegistry.PlaylistPosition> positions = bytes == null ? null
                : DownloadedRegistry.playlistPositions(bytes, POSITIONS_SHOWN);
        String playlist = positions == null || positions.isEmpty() ? "-" : positions.get(0).playlistId;
        String index = positions == null || positions.isEmpty() || positions.get(0).index < 0
                ? "-" : String.valueOf(positions.get(0).index);
        Logger.printDebug(() -> "Row " + (binding ? "bind" : "refresh") + ": view=" + view.getClass().getSimpleName()
                + " model=" + (model == null ? "null" : model.getClass().getSimpleName())
                + " bytes=" + (bytes == null ? "-" : bytes.length) + " reason=" + reason
                + " candidates=" + candidates + " playlist=" + playlist + " index=" + index
                + " positions=" + positions + " resolved=" + (videoId == null ? "-" : videoId)
                + " registry=" + registry.trackCount() + " outcome=" + outcome);
    }

    /**
     * Once or twice per process, for a row that holds no video id at all and is big enough to have a song in it:
     * the outline of its model (see {@link RowModelDump}) and of its view tree, to see where the row keeps its song.
     */
    void dumpUnresolved(View view, @Nullable byte[] bytes, String reason) {
        if (!enabled() || dumpsLeft <= 0 || bytes == null || bytes.length < DUMP_MIN_BYTES
                || !reason.equals("id-not-downloaded")
                || !DownloadedRegistry.candidateIds(bytes, 1).isEmpty()) return;

        dumpsLeft--;
        List<String> lines = RowModelDump.lines(bytes);
        for (int index = 0; index < lines.size(); index++) {
            int part = index;
            Logger.printDebug(() -> "Row model dump " + (part + 1) + "/" + lines.size() + " of " + bytes.length
                    + " bytes: " + lines.get(part));
        }
        Logger.printDebug(() -> "Row view dump: " + viewOutline(view, 0, new int[]{VIEW_DUMP_NODES}));
    }

    /** Class, id name and the text (whole only when short, else its length) of a view and its children. */
    private static String viewOutline(View view, int depth, int[] nodes) {
        StringBuilder outline = new StringBuilder(view.getClass().getSimpleName());
        if (view.getId() != View.NO_ID) {
            try {
                outline.append('#').append(view.getResources().getResourceEntryName(view.getId()));
            } catch (Resources.NotFoundException ignored) {
                // An id of no resource has no name.
            }
        }
        if (view instanceof TextView textView) {
            CharSequence text = textView.getText();
            outline.append(text.length() <= SHORT_TEXT ? "(\"" + text + "\")" : "(" + text.length() + ")");
        }
        if (view instanceof ViewGroup group && depth < VIEW_DUMP_DEPTH && group.getChildCount() > 0) {
            outline.append('[');
            for (int child = 0; child < group.getChildCount() && nodes[0] > 0; child++) {
                nodes[0]--;
                if (child > 0) outline.append(' ');
                outline.append(viewOutline(group.getChildAt(child), depth + 1, nodes));
            }
            outline.append(']');
        }
        return outline.toString();
    }

    /**
     * After the checks were added: the real size and place of the check that is shown (or of the first one when
     * none is), to tell a check that is shown from one that is invisible. More than one check means a card row
     * with several layout variants, of which one is shown.
     */
    void afterLayout(List<DownloadedCheck> checks) {
        if (!enabled() || layoutsLeft <= 0) return;

        checks.get(0).post(() -> {
            if (layoutsLeft-- <= 0) return;

            DownloadedCheck reported = checks.get(0);
            int shownCount = 0;
            for (DownloadedCheck check : checks) {
                if (!check.isShown()) continue;

                if (shownCount++ == 0) reported = check;
            }
            DownloadedCheck check = reported;
            int shown = shownCount;
            Logger.printDebug(() -> "Row check laid out: " + check.getWidth() + "x" + check.getHeight()
                    + " left=" + check.getLeft() + " visibility=" + check.getVisibility()
                    + " variants=" + checks.size() + " shownVariants=" + shown
                    + " parent=" + check.getParent().getClass().getSimpleName());
        });
    }
}
