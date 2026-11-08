/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

/**
 * Where the check goes in the row of a numbered track list (an album page). Those rows are Litho views whose text is
 * drawn, not made of views, so the one thing a check can be placed by is the cell that holds the track number: the
 * check goes under the number, centered in the cell. Pure arithmetic in pixels and the screen density, so it can be
 * tested without Android.
 */
final class ElementRowLayout {
    static final float CHECK_DP = 16;
    /** Below the middle of the cell: clears the number (or the icon that replaces it on the playing row). */
    static final float CHECK_OFFSET_DP = 9;

    private static final float MIN_ROW_DP = 48;
    private static final float MAX_ROW_DP = 80;
    private static final float MIN_CELL_DP = 28;
    private static final float MAX_CELL_DP = 40;
    private static final float MAX_CELL_START_DP = 24;

    private ElementRowLayout() {}

    /**
     * Whether a view of a row is the cell of the track number: narrow, tall, at the start of the row, in a row of the
     * height of a track row. A thumbnail (wider) or a button at the end (far from the start) is not.
     *
     * @param cellLeft Distance of the view from the start of the row.
     */
    static boolean isNumberCell(int cellLeft, int cellWidth, int cellHeight, int rowWidth, int rowHeight,
                                float density) {
        return rowWidth >= 280 * density && rowHeight >= MIN_ROW_DP * density && rowHeight <= MAX_ROW_DP * density
                && cellWidth >= MIN_CELL_DP * density && cellWidth <= MAX_CELL_DP * density
                && cellHeight >= rowHeight / 2 && cellLeft >= 0 && cellLeft <= MAX_CELL_START_DP * density;
    }

    /** The left and top of the check, centered in the cell horizontally and under the middle of it. */
    static int[] checkOrigin(int cellLeft, int cellTop, int cellWidth, int cellHeight, float density) {
        int size = Math.round(CHECK_DP * density);
        int left = cellLeft + (cellWidth - size) / 2;
        int top = cellTop + cellHeight / 2 + Math.round(CHECK_OFFSET_DP * density);
        return new int[]{left, top};
    }
}
