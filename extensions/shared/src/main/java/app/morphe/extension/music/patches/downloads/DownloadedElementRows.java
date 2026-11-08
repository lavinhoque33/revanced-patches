/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.utils.Logger;

/**
 * The green check on the rows of an album page. They are Elements rows, Litho views with no resource ids and no
 * child we could add to, so the check is a drawable on the overlay of the row's view, laid out after every layout of
 * the row under the track number (see {@link ElementRowLayout}). The song of a row comes from the row's own
 * model: {@link DownloadedRegistry#findRowCopy}. A row that is not a numbered track row (another Elements row that
 * names a downloaded song) has no number cell, so it gets no check. Main thread only.
 */
final class DownloadedElementRows {
    private static final int MAX_VIEWS = 200;
    private static final int LOG_BUDGET = 40;

    private static int logsLeft = LOG_BUDGET;

    /** A view group of the row and where it lies in the row's pixels. */
    private record Parent(ViewGroup group, int left, int top) {}

    /** What is known of a row's view; the model is only weakly held and the drawable belongs to the view's overlay. */
    static final class State {
        WeakReference<Object> model = new WeakReference<>(null);
        /** The song whose copy the row shows, or null. */
        @Nullable
        String copyId;
        @Nullable
        Drawable check;
        boolean attached;
        boolean listening;
        boolean placeQueued;
        @Nullable
        Boolean tintedForDarkTheme;
        String lastLogged = "";

        boolean isShown() {
            return attached;
        }
    }

    private DownloadedElementRows() {}

    /** Works out which song the bound model shows a copy of, then shows or hides the check of the row. */
    static void apply(View row, State state, boolean enabled, DownloadedRegistry registry) {
        Object model = state.model.get();
        byte[] bytes = null;
        String reason;
        state.copyId = null;
        if (!enabled) {
            reason = "setting-off";
        } else if (model == null) {
            reason = "model-gone";
        } else if (!registry.hasTracks()) {
            reason = "registry-empty";
        } else if ((bytes = DownloadedModels.serialize(model)) == null) {
            reason = "model-has-no-proto";
        } else {
            state.copyId = registry.findRowCopy(bytes);
            reason = state.copyId == null ? "no-copy" : "copy";
        }

        if (state.copyId != null) listenForLayouts(row, state);
        place(row, state);
        log(state, row, model, bytes, reason);
    }

    /** One line per change of what a row shows, within a budget; never one per bind. */
    private static void log(State state, View row, @Nullable Object model, @Nullable byte[] bytes, String reason) {
        if (!BaseSettings.DEBUG.get() || logsLeft <= 0) return;

        String result = reason + "/" + (state.attached ? "shown" : "hidden");
        // A row without a copy that was never shown says nothing: there are many of them.
        if (result.equals(state.lastLogged) || (state.lastLogged.isEmpty() && state.copyId == null)) return;

        state.lastLogged = result;
        logsLeft--;
        Logger.printDebug(() -> "Elements row: model=" + (model == null ? "null" : model.getClass().getSimpleName())
                + " bytes=" + (bytes == null ? "-" : bytes.length) + " reason=" + reason
                + " copy=" + state.copyId + " " + row.getWidth() + "x" + row.getHeight()
                + " shown=" + state.attached);
    }

    /** The row is laid out after it was bound, so the check is re-placed after every layout. */
    private static void listenForLayouts(View row, State state) {
        if (state.listening) return;

        state.listening = true;
        row.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if ((state.copyId == null && !state.attached) || state.placeQueued) return;

            state.placeQueued = true;
            row.post(() -> {
                state.placeQueued = false;
                try {
                    place(row, state);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not place the downloaded check of a row", ex);
                }
            });
        });
    }

    private static void place(View row, State state) {
        if (state.copyId == null) {
            detach(row, state);
            return;
        }

        int[] cell = findNumberCell(row);
        if (cell == null) {
            detach(row, state);
            logOnce(state);
            return;
        }

        Drawable check = state.check;
        if (check == null) {
            check = DownloadedCheck.createDrawable(row.getContext());
            if (check == null) return;

            state.check = check;
        }
        boolean darkTheme = BaseThemeUtils.isDarkModeEnabled();
        if (state.tintedForDarkTheme == null || state.tintedForDarkTheme != darkTheme) {
            DownloadedCheck.tint(check, darkTheme);
            state.tintedForDarkTheme = darkTheme;
        }

        float density = row.getResources().getDisplayMetrics().density;
        int[] origin = ElementRowLayout.checkOrigin(cell[0], cell[1], cell[2], cell[3], density);
        int size = Math.round(ElementRowLayout.CHECK_DP * density);
        check.setBounds(origin[0], origin[1], origin[0] + size, origin[1] + size);
        if (!state.attached) {
            row.getOverlay().add(check);
            state.attached = true;
        }
    }

    private static void detach(View row, State state) {
        if (!state.attached) return;

        state.attached = false;
        if (state.check != null) row.getOverlay().remove(state.check);
    }

    private static void logOnce(State state) {
        if (!BaseSettings.DEBUG.get() || state.lastLogged.equals("no-cell")) return;

        state.lastLogged = "no-cell";
        Logger.printDebug(() -> "Elements row has a copy but no track number cell: no check");
    }

    /**
     * The cell of the track number in the laid out row: {left, top, width, height} in the row's pixels, or null
     * when the row is not laid out or is not a numbered track row.
     */
    @Nullable
    private static int[] findNumberCell(View row) {
        if (!(row instanceof ViewGroup root) || root.getChildCount() == 0) return null;

        float density = row.getResources().getDisplayMetrics().density;
        boolean rightToLeft = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        ArrayDeque<Parent> pending = new ArrayDeque<>();
        pending.add(new Parent(root, 0, 0));
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_VIEWS) {
            Parent next = pending.poll();
            ViewGroup group = next.group();
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                int left = next.left() + child.getLeft();
                int top = next.top() + child.getTop();
                int start = rightToLeft ? row.getWidth() - (left + child.getWidth()) : left;
                if (ElementRowLayout.isNumberCell(start, child.getWidth(), child.getHeight(), row.getWidth(),
                        row.getHeight(), density)) {
                    return new int[]{left, top, child.getWidth(), child.getHeight()};
                }
                if (child instanceof ViewGroup childGroup) pending.add(new Parent(childGroup, left, top));
            }
        }
        return null;
    }
}
