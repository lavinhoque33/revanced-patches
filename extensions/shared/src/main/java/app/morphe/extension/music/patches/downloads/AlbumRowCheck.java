/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.utils.BaseThemeUtils;

/**
 * The green check of one track row of an album page. The row is a Litho view with no child we could add to, so the
 * check is a drawable on the row's overlay, placed under the track number (see {@link ElementRowLayout}). Main thread
 * only; the drawable belongs to the row's overlay, this object is only held weakly by the page.
 */
final class AlbumRowCheck {
    @Nullable
    private Drawable check;
    private boolean attached;
    @Nullable
    private Boolean tintedForDarkTheme;
    @Nullable
    private DownloadedAlbumRows.Cell placedAt;

    /**
     * Shows the check under the number of the row; false when the check drawable is unavailable. Does nothing when
     * the check is already shown at that cell in the current theme.
     */
    boolean show(View row, DownloadedAlbumRows.Cell cell) {
        boolean darkTheme = BaseThemeUtils.isDarkModeEnabled();
        if (attached && cell.equals(placedAt) && tintedForDarkTheme != null && tintedForDarkTheme == darkTheme) {
            return true;
        }

        if (check == null) check = DownloadedCheck.createDrawable(row.getContext());
        if (check == null) return false;

        if (tintedForDarkTheme == null || tintedForDarkTheme != darkTheme) {
            DownloadedCheck.tint(check, darkTheme);
            tintedForDarkTheme = darkTheme;
        }
        placedAt = cell;

        float density = row.getResources().getDisplayMetrics().density;
        int[] origin = ElementRowLayout.checkOrigin(cell.left(), cell.top(), cell.width(), cell.height(), density);
        int size = Math.round(ElementRowLayout.CHECK_DP * density);
        check.setBounds(origin[0], origin[1], origin[0] + size, origin[1] + size);
        if (!attached) {
            row.getOverlay().add(check);
            attached = true;
        }
        return true;
    }

    void hide(View row) {
        if (!attached) return;

        attached = false;
        if (check != null) row.getOverlay().remove(check);
    }
}
