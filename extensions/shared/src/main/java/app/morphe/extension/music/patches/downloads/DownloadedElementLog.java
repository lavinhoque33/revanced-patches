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

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Debug log (only with debug logging on) of the Elements rows of an album page, to tell
 * why a track row is not marked: whether the hook ran, which rows have a track number cell, which parts the row's
 * model has, which video ids, playlist and position they name, and what they resolved to. The first track rows also
 * get an outline of their model and their view tree. Main thread only.
 */
final class DownloadedElementLog {
    private static final int ROW_BUDGET = 40;
    private static final int DUMP_BUDGET = 2;
    private static final int IDS_SHOWN = 4;
    private static final int POSITIONS_SHOWN = 3;
    private static final int VIEW_DUMP_DEPTH = 10;
    private static final int VIEW_DUMP_NODES = 120;
    private static final int SHORT_TEXT = 3;

    private static int binds;
    private static int trackRows;
    private static int rowsLeft = ROW_BUDGET;
    private static int dumpsLeft = DUMP_BUDGET;

    private DownloadedElementLog() {}

    /** An Elements view was bound: counts it, and reports it once it is laid out and turns out to be a track row. */
    static void bound(View row, DownloadedElementRows.State state, DownloadedRegistry registry) {
        if (!BaseSettings.DEBUG.get()) return;

        binds++;
        state.boundCount++;
        Object model = state.model.get();
        if (binds == 1 && model != null) {
            Logger.printDebug(() -> "Elements hook alive: first bind view=" + row.getClass().getSimpleName()
                    + " model=" + model.getClass().getName());
        }
        if ((binds & (binds - 1)) == 0) {
            int bindsNow = binds;
            int rowsNow = trackRows;
            Logger.printDebug(() -> "Elements summary: binds=" + bindsNow + " trackRows=" + rowsNow
                    + " registry=" + registry.trackCount());
        }
        if (state.watchingLayouts) return;

        state.watchingLayouts = true;
        row.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                row.post(() -> report(row, state, registry)));
    }

    /** After a layout: a bound row that has a track number cell is reported, once per bind. */
    private static void report(View row, DownloadedElementRows.State state, DownloadedRegistry registry) {
        if (state.reportedCount == state.boundCount || !BaseSettings.DEBUG.get()) return;

        int[] cell = DownloadedElementRows.findNumberCell(row);
        Object model = state.model.get();
        if (cell == null || model == null) return;

        state.reportedCount = state.boundCount;
        trackRows++;
        List<byte[]> parts = DownloadedModels.parts(model);
        byte[] all = DownloadedModels.serializeParts(model);
        if (rowsLeft-- > 0 && all != null) {
            List<DownloadedRegistry.PlaylistPosition> positions =
                    DownloadedRegistry.playlistPositions(all, POSITIONS_SHOWN);
            String resolved = registry.findRowCopy(all);
            Logger.printDebug(() -> "Elements track row: model=" + model.getClass().getSimpleName()
                    + " parts=" + sizes(parts) + " cell=" + cell[2] + "x" + cell[3]
                    + " candidates=" + DownloadedRegistry.candidateIds(all, IDS_SHOWN)
                    + " positions=" + positions + " resolved=" + (resolved == null ? "-" : resolved)
                    + " registry=" + registry.trackCount() + " check=" + state.isShown());
        }
        if (dumpsLeft > 0 && all != null) {
            dumpsLeft--;
            dump(row, parts);
        }
    }

    private static List<Integer> sizes(List<byte[]> parts) {
        List<Integer> sizes = new ArrayList<>();
        for (byte[] part : parts) sizes.add(part.length);
        return sizes;
    }

    private static void dump(View row, List<byte[]> parts) {
        for (int part = 0; part < parts.size(); part++) {
            int partNumber = part;
            List<String> lines = RowModelDump.lines(parts.get(part));
            for (int index = 0; index < lines.size(); index++) {
                int line = index;
                Logger.printDebug(() -> "Elements model dump part " + (partNumber + 1) + "/" + parts.size()
                        + " " + (line + 1) + "/" + lines.size() + " of " + parts.get(partNumber).length
                        + " bytes: " + lines.get(line));
            }
        }
        Logger.printDebug(() -> "Elements view dump " + row.getWidth() + "x" + row.getHeight() + ": "
                + viewOutline(row, 0, new int[]{VIEW_DUMP_NODES}));
    }

    /** Class, size and the id name of a view, and its children; the text of a text view only when it is short. */
    private static String viewOutline(View view, int depth, int[] nodes) {
        StringBuilder outline = new StringBuilder(view.getClass().getSimpleName());
        outline.append(view.getWidth()).append('x').append(view.getHeight());
        String name = idName(view);
        if (name != null) outline.append('#').append(name);
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

    @Nullable
    private static String idName(View view) {
        if (view.getId() == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Resources.NotFoundException ignored) {
            return null;
        }
    }
}
