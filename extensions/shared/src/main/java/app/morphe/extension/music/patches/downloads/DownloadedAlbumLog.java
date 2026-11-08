/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Debug log (only with debug logging on) of an album page, to tell why a track row is or is not marked: what the page
 * scan found (rows, their cells, numbers and verdicts), where the rows live (the list and its children), where the
 * number of a row is drawn, and what the row's Litho view still holds of its model. Within budgets: nothing here
 * logs once per frame. Main thread only.
 */
final class DownloadedAlbumLog {
    private static final int PAGE_LINES = 80;
    private static final int TREES = 3;
    private static final int TEXT_ROWS = 2;
    private static final int MODEL_ROWS = 3;
    private static final int MODEL_DUMP_CHARS = 12_000;
    private static final int FOUND_LISTED = 12;
    private static final int CHILDREN_LISTED = 16;
    private static final int TEXTS_PER_HOST = 6;

    private static int pageLinesLeft = PAGE_LINES;
    private static int treesLeft = TREES;
    private static int textRowsLeft = TEXT_ROWS;
    private static int modelRowsLeft = MODEL_ROWS;

    private DownloadedAlbumLog() {}

    /** What one page remembers of what it logged, so that a line is only repeated when it changed. */
    static final class PageLog {
        private String lastLine = "";
        private String lastNote = "";
        private boolean treeLogged;

        void reset() {
            lastLine = "";
            lastNote = "";
            treeLogged = false;
        }

        /** Why a scan could not look at the page, once per distinct reason. */
        void note(String text, @Nullable String source) {
            if (!BaseSettings.DEBUG.get()) return;

            String line = "Album page: " + text + " source=" + source;
            if (line.equals(lastNote) || pageLinesLeft <= 0) return;

            lastNote = line;
            pageLinesLeft--;
            Logger.printDebug(() -> line);
        }

        /** After a scan that found rows or changed what it found. */
        void scanned(ViewGroup page, @Nullable String source, int recorded, List<DownloadedAlbumRows.Row> rows,
                     List<String> verdicts, @Nullable String problem) {
            if (!BaseSettings.DEBUG.get()) return;

            String line = "Album page rows: source=" + source + " recorded=" + recorded + " page="
                    + page.getClass().getSimpleName() + page.getWidth() + "x" + page.getHeight()
                    + " rows=" + summary(rows, verdicts) + (problem == null ? "" : " problem=" + problem);
            if (!line.equals(lastLine) && pageLinesLeft > 0) {
                lastLine = line;
                pageLinesLeft--;
                Logger.printDebug(() -> line);
            }
            if (rows.isEmpty()) return;

            if (!treeLogged && treesLeft > 0) {
                treeLogged = true;
                treesLeft--;
                logTree(page, rows);
            }
            if (textRowsLeft > 0) {
                textRowsLeft--;
                logRowTexts(rows.get(0));
            }
            if (modelRowsLeft > 0) {
                // The first row is dumped completely; the next scans list what two other rows reach, to compare.
                int order = MODEL_ROWS - modelRowsLeft--;
                logModel(rows.get(Math.min(order, rows.size() - 1)), order);
            }
        }
    }

    private static String summary(List<DownloadedAlbumRows.Row> rows, List<String> verdicts) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < rows.size(); i++) {
            DownloadedAlbumRows.Row row = rows.get(i);
            if (i > 0) text.append(' ');
            text.append(row.view.getWidth()).append('x').append(row.view.getHeight()).append("@y").append(row.top)
                    .append("(cell ").append(row.cell.width()).append('x').append(row.cell.height()).append('@')
                    .append(row.cell.left()).append(',').append(row.cell.top())
                    .append(row.insideCell.isEmpty() ? " no text inside" : " texts " + row.insideCell)
                    .append(")=").append(verdicts.get(i));
        }
        return text.append(']').toString();
    }

    /** The views above the first row up to the page, and the children of the list among them. */
    private static void logTree(ViewGroup page, List<DownloadedAlbumRows.Row> rows) {
        View first = rows.get(0).view;
        StringBuilder path = new StringBuilder();
        RecyclerView list = null;
        for (View view = first; view != null; view = view.getParent() instanceof View parent ? parent : null) {
            path.append(view.getClass().getName()).append(' ').append(view.getWidth()).append('x')
                    .append(view.getHeight()).append(" < ");
            if (list == null && view instanceof RecyclerView found) list = found;
            if (view == page) break;
        }
        Logger.printDebug(() -> "Album page tree, first row up to the page: " + path);
        if (list == null) {
            Logger.printDebug(() -> "Album page: no RecyclerView above the first row");
            return;
        }

        ViewGroup recycler = list;
        Logger.printDebug(() -> "Album page list: " + recycler.getClass().getName() + " "
                + recycler.getWidth() + "x" + recycler.getHeight() + " children=" + recycler.getChildCount());
        for (int i = 0; i < Math.min(recycler.getChildCount(), CHILDREN_LISTED); i++) {
            View child = recycler.getChildAt(i);
            int index = i;
            Logger.printDebug(() -> "Album page list child " + index + ": " + child.getClass().getName() + " "
                    + child.getWidth() + "x" + child.getHeight() + " top=" + child.getTop()
                    + " holderInts=" + holderInts(child) + " trackRow=" + numberOfRowIn(child, rows));
        }
    }

    /** The number the track row inside (or being) [child] shows, or "-" when [child] holds no track row. */
    private static String numberOfRowIn(View child, List<DownloadedAlbumRows.Row> rows) {
        for (DownloadedAlbumRows.Row row : rows) {
            for (View view = row.view; view != null; view = view.getParent() instanceof View parent ? parent : null) {
                if (view == child) return row.number == AlbumTrackNumbers.UNKNOWN ? "?" : String.valueOf(row.number);
            }
        }
        return "-";
    }

    /**
     * The int fields of the list's view holder of a child (its adapter position is among them): the layout params
     * hold the holder in an obfuscated field, found as the one whose type has a field that holds the child's view.
     */
    private static String holderInts(View child) {
        ViewGroup.LayoutParams params = child.getLayoutParams();
        if (params == null) return "[no layout params]";

        try {
            for (Class<?> type = params.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;

                    field.setAccessible(true);
                    Object holder = field.get(params);
                    if (holder != null && holdsView(holder, child)) return intFields(holder);
                }
            }
        } catch (IllegalAccessException | RuntimeException ex) {
            return "[unreadable: " + ex.getClass().getSimpleName() + "]";
        }
        return "[no holder]";
    }

    private static boolean holdsView(Object holder, View child) throws IllegalAccessException {
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !View.class.isAssignableFrom(field.getType())) continue;

                field.setAccessible(true);
                if (field.get(holder) == child) return true;
            }
        }
        return false;
    }

    private static String intFields(Object holder) throws IllegalAccessException {
        StringBuilder ints = new StringBuilder("[");
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;

                field.setAccessible(true);
                if (ints.length() > 1) ints.append(' ');
                ints.append(field.getName()).append('=').append(field.getInt(holder));
            }
        }
        return ints.append(']').toString();
    }

    /** Which of the row's Litho hosts draw text, where, and what: to see where the track number is drawn. */
    private static void logRowTexts(DownloadedAlbumRows.Row row) {
        StringBuilder hosts = new StringBuilder();
        appendHostTexts(row.view, 0, 0, hosts, 0);
        Logger.printDebug(() -> "Album row texts: row=" + row.view.getWidth() + "x" + row.view.getHeight()
                + " cell=" + row.cell.width() + "x" + row.cell.height() + "@" + row.cell.left() + ","
                + row.cell.top() + " hosts: " + hosts);
    }

    private static void appendHostTexts(View view, int left, int top, StringBuilder out, int depth) {
        if (view instanceof ComponentHost host) {
            List<CharSequence> items = DownloadedAlbumRows.hostTexts(host);
            if (!items.isEmpty()) {
                out.append(host.getClass().getSimpleName()).append(host.getWidth()).append('x').append(host.getHeight())
                        .append('@').append(left).append(',').append(top).append(" depth=").append(depth).append('=');
                out.append(items.subList(0, Math.min(items.size(), TEXTS_PER_HOST))).append("; ");
            }
        }
        if (!(view instanceof ViewGroup group)) return;

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            appendHostTexts(child, left + child.getLeft(), top + child.getTop(), out, depth + 1);
        }
    }

    /**
     * What the row's Litho view holds of its model: every protobuf and byte array it reaches, shallowest first. The
     * first row's are dumped completely (within a character limit), later rows only listed, to compare.
     */
    private static void logModel(DownloadedAlbumRows.Row row, int order) {
        List<LithoModelSearch.Found> found = LithoModelSearch.find(row.view);
        StringBuilder listed = new StringBuilder();
        for (int i = 0; i < Math.min(found.size(), FOUND_LISTED); i++) {
            LithoModelSearch.Found one = found.get(i);
            listed.append(" #").append(i).append(' ').append(one.path()).append(" depth=").append(one.depth())
                    .append(' ').append(one.type()).append(' ').append(one.bytes().length).append("B;");
        }
        Logger.printDebug(() -> "Album row " + order + " model: reachable=" + found.size() + listed);
        if (order != 0) return;

        Set<String> ids = new LinkedHashSet<>();
        for (LithoModelSearch.Found one : found) ids.addAll(RowModelDump.ids(one.bytes()));
        Logger.printDebug(() -> "Album row 0 model ids: " + ids);

        int budget = MODEL_DUMP_CHARS;
        for (int i = 0; i < Math.min(found.size(), FOUND_LISTED) && budget > 0; i++) {
            List<String> lines = RowModelDump.lines(found.get(i).bytes(), budget);
            List<String> numbered = new ArrayList<>();
            for (int line = 0; line < lines.size(); line++) {
                budget -= lines.get(line).length();
                numbered.add("Album row 0 model dump #" + i + " line " + (line + 1) + "/" + lines.size() + " of "
                        + found.get(i).bytes().length + " bytes: " + lines.get(line));
            }
            for (String line : numbered) Logger.printDebug(() -> line);
        }
    }
}
