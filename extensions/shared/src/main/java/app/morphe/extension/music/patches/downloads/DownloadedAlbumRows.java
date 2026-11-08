/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.ResourceUtils;

/**
 * The green checks on the track rows of an album page. The rows are Litho views that are mounted by the page's list
 * without a bind that reports a model, so a row cannot tell which song it shows. What a row does show is its track
 * number, drawn in a cell at its start. When the page is the album whose track list was recorded (the header names
 * it, see {@link DownloadedHeaders}), the row that shows the number n is the n-th track of that list, and it gets a
 * check when that track has a stream copy. The numbers must make sense (see {@link AlbumTrackNumbers#problem}):
 * otherwise no row of the page is marked. A row whose number is unknown is never marked.
 *
 * <p>The page is looked at after every layout and scroll of the window while it is shown, and only when the album has
 * a recorded track list. Main thread only; the page holds only weak references to views.
 */
final class DownloadedAlbumRows {
    private static final String[] PAGE_IDS = {"results_container", "content_container"};
    private static final String ALBUM_PREFIX = "OLAK5uy_";
    private static final int MAX_PARENTS = 8;
    private static final int MAX_VIEWS = 1500;
    /** A track row has a few dozen views; a view that is not one is given up on sooner. */
    private static final int MAX_CELL_VIEWS = 200;
    private static final int MAX_TEXT_DEPTH = 12;
    private static final long RETRY_MILLIS = 300;
    private static final int MAX_RETRIES = 5;
    private static final int SLACK_PX = 1;

    /** The number cell of a row, in the row's pixels. */
    record Cell(int left, int top, int width, int height) {}

    /** A track row of the page. */
    static final class Row {
        final View view;
        final Cell cell;
        final int top;
        final List<String> insideCell = new ArrayList<>();
        final List<String> inRow = new ArrayList<>();
        int number = AlbumTrackNumbers.UNKNOWN;

        Row(View view, Cell cell, int top) {
            this.view = view;
            this.cell = cell;
            this.top = top;
        }
    }

    /** Per header container. */
    static final class State {
        @Nullable
        String sourceId;
        WeakReference<ViewGroup> page = new WeakReference<>(null);
        boolean observing;
        boolean scanQueued;
        int retries;
        int highestNumber;
        final Map<View, AlbumRowCheck> checks = new WeakHashMap<>();
        final DownloadedAlbumLog.PageLog log = new DownloadedAlbumLog.PageLog();
    }

    private DownloadedAlbumRows() {}

    /** The header was bound or laid out, or the stream copies changed: look at the page again. */
    static void apply(ViewGroup container, State state, List<String> sourceIds) {
        String source = wantedSource(sourceIds);
        if (source == null || !source.equals(state.sourceId)) {
            state.highestNumber = 0;
            state.retries = 0;
            state.log.reset();
        }
        state.sourceId = source;
        if (source == null) {
            hideAll(state);
            return;
        }

        observe(container, state);
        scanLater(container, state);
    }

    /** The one album whose recorded track list this page shows, or null when no row may be marked. */
    @Nullable
    private static String wantedSource(List<String> sourceIds) {
        if (!DownloadedMarkers.enabled() || sourceIds.size() != 1 || !sourceIds.get(0).startsWith(ALBUM_PREFIX)) {
            return null;
        }

        DownloadedRegistry registry = DownloadedMarkers.loadedRegistry();
        List<String> tracks = registry == null ? null : registry.sourceTracks(sourceIds.get(0));
        return tracks == null || tracks.isEmpty() ? null : sourceIds.get(0);
    }

    private static void observe(ViewGroup container, State state) {
        if (state.observing) return;

        state.observing = true;
        WeakReference<ViewGroup> weakContainer = new WeakReference<>(container);
        ViewTreeObserver.OnGlobalLayoutListener onLayout = () -> {
            ViewGroup current = weakContainer.get();
            if (current != null) scanLater(current, state);
        };
        ViewTreeObserver.OnScrollChangedListener onScroll = () -> {
            ViewGroup current = weakContainer.get();
            if (current != null) scanLater(current, state);
        };
        container.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                ViewTreeObserver observer = view.getViewTreeObserver();
                observer.addOnGlobalLayoutListener(onLayout);
                observer.addOnScrollChangedListener(onScroll);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                ViewTreeObserver observer = view.getViewTreeObserver();
                if (!observer.isAlive()) return;

                observer.removeOnGlobalLayoutListener(onLayout);
                observer.removeOnScrollChangedListener(onScroll);
            }
        });
        if (container.isAttachedToWindow()) {
            ViewTreeObserver observer = container.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(onLayout);
            observer.addOnScrollChangedListener(onScroll);
        }
    }

    private static void scanLater(ViewGroup container, State state) {
        if (state.scanQueued || state.sourceId == null) return;

        state.scanQueued = true;
        container.post(() -> scanQueued(container, state));
    }

    private static void scanQueued(ViewGroup container, State state) {
        state.scanQueued = false;
        try {
            scan(container, state);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not mark the downloaded tracks of an album page", ex);
        }
    }

    private static void scan(ViewGroup container, State state) {
        DownloadedRegistry registry = DownloadedMarkers.loadedRegistry();
        List<String> tracks = registry == null || state.sourceId == null ? null
                : registry.sourceTracks(state.sourceId);
        ViewGroup page = findPage(container, state);
        if (tracks == null || tracks.isEmpty() || page == null) {
            hideAll(state);
            state.log.note("no page or no recorded tracks", state.sourceId);
            return;
        }

        List<Row> rows = findRows(page);
        List<Integer> numbers = new ArrayList<>();
        boolean unnumbered = false;
        for (Row row : rows) {
            collectTexts(row.view, 0, 0, row, 0);
            row.number = AlbumTrackNumbers.pick(row.insideCell, row.inRow);
            numbers.add(row.number);
            unnumbered |= row.number == AlbumTrackNumbers.UNKNOWN;
            state.highestNumber = Math.max(state.highestNumber, row.number);
        }

        String problem = AlbumTrackNumbers.problem(numbers, state.highestNumber, tracks.size());
        Set<View> marked = new HashSet<>();
        List<String> verdicts = new ArrayList<>();
        for (Row row : rows) {
            String verdict = problem != null ? "unusable" : verdict(row, tracks, registry, state, marked);
            verdicts.add(verdict);
        }
        for (Map.Entry<View, AlbumRowCheck> entry : new ArrayList<>(state.checks.entrySet())) {
            if (!marked.contains(entry.getKey())) entry.getValue().hide(entry.getKey());
        }
        state.log.scanned(page, state.sourceId, tracks.size(), rows, verdicts, problem);

        if (unnumbered && state.retries++ < MAX_RETRIES) {
            // A row that is not mounted yet has no text; look again once it is.
            state.scanQueued = true;
            container.postDelayed(() -> scanQueued(container, state), RETRY_MILLIS);
        }
    }

    /** Marks or unmarks one row; returns what happened, for the log. */
    private static String verdict(Row row, List<String> tracks, DownloadedRegistry registry, State state,
                                  Set<View> marked) {
        if (row.number == AlbumTrackNumbers.UNKNOWN) return "no-number";

        String videoId = tracks.get(row.number - 1);
        if (!registry.isDownloaded(videoId)) return row.number + ":no-copy";

        AlbumRowCheck check = state.checks.computeIfAbsent(row.view, view -> new AlbumRowCheck());
        if (!check.show(row.view, row.cell)) return row.number + ":no-icon";

        marked.add(row.view);
        return row.number + ":check";
    }

    private static void hideAll(State state) {
        for (Map.Entry<View, AlbumRowCheck> entry : new ArrayList<>(state.checks.entrySet())) {
            entry.getValue().hide(entry.getKey());
        }
    }

    /** The list part of the page: below the header, found by the id the page gives it. */
    @Nullable
    private static ViewGroup findPage(ViewGroup container, State state) {
        ViewGroup known = state.page.get();
        if (known != null && known.isAttachedToWindow()) return known;

        View current = container;
        for (int level = 0; level < MAX_PARENTS && current != null; level++) {
            for (String name : PAGE_IDS) {
                int id = ResourceUtils.getIdIdentifier(name);
                if (id != 0 && current.findViewById(id) instanceof ViewGroup found) {
                    state.page = new WeakReference<>(found);
                    return found;
                }
            }
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return null;
    }

    /** Every track-shaped row of the page, top to bottom; a row's own views are not looked at for more rows. */
    static List<Row> findRows(ViewGroup page) {
        float density = page.getResources().getDisplayMetrics().density;
        List<Row> rows = new ArrayList<>();
        ArrayDeque<ViewGroup> pending = new ArrayDeque<>();
        pending.add(page);
        int visited = 0;
        int[] at = new int[2];
        while (!pending.isEmpty() && visited < MAX_VIEWS) {
            ViewGroup group = pending.poll();
            for (int i = 0; i < group.getChildCount() && visited++ < MAX_VIEWS; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || !(child instanceof ViewGroup childGroup)) continue;

                Cell cell = ElementRowLayout.isTrackRow(child.getWidth(), child.getHeight(), density)
                        ? findNumberCell(childGroup) : null;
                if (cell == null) {
                    pending.add(childGroup);
                    continue;
                }
                child.getLocationInWindow(at);
                rows.add(new Row(child, cell, at[1]));
            }
        }
        rows.sort(Comparator.comparingInt(row -> row.top));
        return rows;
    }

    /** The cell of the track number in a laid out row, or null when the row has none. */
    @Nullable
    static Cell findNumberCell(ViewGroup row) {
        float density = row.getResources().getDisplayMetrics().density;
        boolean rightToLeft = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        ArrayDeque<ViewGroup> pending = new ArrayDeque<>();
        ArrayDeque<int[]> origins = new ArrayDeque<>();
        pending.add(row);
        origins.add(new int[]{0, 0});
        int visited = 0;
        while (!pending.isEmpty() && visited < MAX_CELL_VIEWS) {
            ViewGroup group = pending.poll();
            int[] origin = origins.poll();
            for (int i = 0; i < group.getChildCount() && visited++ < MAX_CELL_VIEWS; i++) {
                View child = group.getChildAt(i);
                int left = origin[0] + child.getLeft();
                int top = origin[1] + child.getTop();
                int start = rightToLeft ? row.getWidth() - (left + child.getWidth()) : left;
                if (ElementRowLayout.isNumberCell(start, child.getWidth(), child.getHeight(), row.getWidth(),
                        row.getHeight(), density)) {
                    return new Cell(left, top, child.getWidth(), child.getHeight());
                }
                if (child instanceof ViewGroup childGroup) {
                    pending.add(childGroup);
                    origins.add(new int[]{left, top});
                }
            }
        }
        return null;
    }

    /**
     * The texts the row's Litho hosts draw: those of the hosts that lie inside the number cell go to
     * {@link Row#insideCell}, all of them to {@link Row#inRow}, in the order the hosts are mounted.
     */
    static void collectTexts(View view, int left, int top, Row row, int depth) {
        if (view instanceof ComponentHost host) {
            boolean inside = isInside(row.cell, left, top, host.getWidth(), host.getHeight());
            for (CharSequence item : hostTexts(host)) {
                if (item == null) continue;

                String text = item.toString().trim();
                row.inRow.add(text);
                if (inside) row.insideCell.add(text);
            }
        }
        if (!(view instanceof ViewGroup group) || depth >= MAX_TEXT_DEPTH) return;

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE) {
                collectTexts(child, left + child.getLeft(), top + child.getTop(), row, depth + 1);
            }
        }
    }

    /** The texts a Litho host draws itself (not those of its child hosts); empty when it draws none. */
    static List<CharSequence> hostTexts(ComponentHost host) {
        try {
            TextContent content = host.getTextContent();
            List<CharSequence> items = content == null ? null : content.getTextItems();
            return items == null ? Collections.emptyList() : items;
        } catch (RuntimeException unavailable) {
            return Collections.emptyList();
        }
    }

    private static boolean isInside(Cell cell, int left, int top, int width, int height) {
        return left >= cell.left() - SLACK_PX && top >= cell.top() - SLACK_PX
                && left + width <= cell.left() + cell.width() + SLACK_PX
                && top + height <= cell.top() + cell.height() + SLACK_PX;
    }
}
