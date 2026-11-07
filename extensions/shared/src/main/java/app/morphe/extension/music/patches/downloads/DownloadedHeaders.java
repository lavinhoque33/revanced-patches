/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.utils.Logger;

/**
 * The green check on the Download button of a downloaded playlist or album page.
 * The header is a server-driven Elements (Litho) view, so its button cannot be restyled; instead a check view of
 * our own is laid over the header container, at the corner of the button's icon. The button is found by its
 * label, or else as the first icon-sized clickable view, and the check follows it on every layout.
 */
final class DownloadedHeaders {
    private static final String DOWNLOAD_LABEL = "Download";
    private static final int BADGE_DP = 18;
    private static final int BADGE_PADDING_DP = 1;
    private static final int BADGE_OFFSET_DP = 11;
    private static final int BUTTON_MIN_DP = 36;
    private static final int BUTTON_MAX_DP = 60;
    private static final int BUTTON_SQUARE_TOLERANCE_DP = 2;
    private static final int MAX_DEPTH = 12;
    private static final int MAX_VIEWS = 400;

    /** Per header container; holds only weak references to views. */
    static final class State {
        List<String> sourceIds = Collections.emptyList();
        WeakReference<View> host = new WeakReference<>(null);
        WeakReference<DownloadedCheck> badge = new WeakReference<>(null);
        boolean listening;
        boolean applyQueued;
    }

    private DownloadedHeaders() {}

    static void apply(ViewGroup container, State state) {
        listenForLayouts(container, state);

        DownloadedCheck badge = state.badge.get();
        boolean downloaded = false;
        if (DownloadedMarkers.enabled()) {
            for (String sourceId : state.sourceIds) downloaded |= DownloadedMarkers.isSourceDownloaded(sourceId);
        }
        if (!downloaded) {
            if (badge != null) badge.setShown(false);
            return;
        }

        View button = downloadButton(container, state, badge);
        if (button == null) {
            Logger.printDebug(() -> "No Download button in the header");
            if (badge != null) badge.setShown(false);
            return;
        }

        if (badge == null || badge.getParent() != container) {
            badge = DownloadedCheck.create(container.getContext(), true);
            if (badge == null) return;

            int size = DownloadedCheck.dp(BADGE_DP);
            int padding = DownloadedCheck.dp(BADGE_PADDING_DP);
            badge.setPadding(padding, padding, padding, padding);
            container.addView(badge, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT));
            state.badge = new WeakReference<>(badge);
        }
        badge.restyle();
        if (container.indexOfChild(badge) != container.getChildCount() - 1) badge.bringToFront();
        place(container, button, badge);
        badge.setShown(true);
    }

    /** The header lays its content out after it was bound, so the check is re-placed after every layout. */
    private static void listenForLayouts(ViewGroup container, State state) {
        if (state.listening) return;

        state.listening = true;
        container.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (state.sourceIds.isEmpty() && state.badge.get() == null || state.applyQueued) return;

            state.applyQueued = true;
            container.post(() -> {
                state.applyQueued = false;
                try {
                    apply(container, state);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not place the downloaded check", ex);
                }
            });
        });
    }

    private static void place(ViewGroup container, View button, DownloadedCheck badge) {
        int[] buttonAt = new int[2];
        int[] containerAt = new int[2];
        button.getLocationInWindow(buttonAt);
        container.getLocationInWindow(containerAt);

        int offset = DownloadedCheck.dp(BADGE_OFFSET_DP);
        boolean rightToLeft = container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        float centerX = buttonAt[0] - containerAt[0] + button.getWidth() / 2f + (rightToLeft ? -offset : offset);
        float centerY = buttonAt[1] - containerAt[1] + button.getHeight() / 2f + offset;
        float x = centerX - badge.getLayoutParams().width / 2f;
        float y = centerY - badge.getLayoutParams().height / 2f;
        if (badge.getTranslationX() != x) badge.setTranslationX(x);
        if (badge.getTranslationY() != y) badge.setTranslationY(y);
    }

    @Nullable
    private static View downloadButton(ViewGroup container, State state, @Nullable DownloadedCheck badge) {
        View cached = state.host.get();
        if (cached != null && cached.isAttachedToWindow() && cached.getWidth() > 0 && isInside(cached, container)) {
            return cached;
        }

        View[] labeled = new View[1];
        View[] iconSized = new View[1];
        scan(container, badge, labeled, iconSized, new int[]{MAX_VIEWS}, 0);
        View found = labeled[0] != null ? labeled[0] : iconSized[0];
        state.host = new WeakReference<>(found);
        return found;
    }

    private static boolean isInside(View view, ViewGroup container) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == container) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static void scan(ViewGroup parent, @Nullable View ignored, View[] labeled, View[] iconSized,
                             int[] budget, int depth) {
        for (int index = 0; index < parent.getChildCount() && labeled[0] == null; index++) {
            View child = parent.getChildAt(index);
            if (child == ignored || --budget[0] < 0) continue;
            if (!(child instanceof ViewGroup group)) continue;

            if (child.isClickable() && child.getWidth() > 0) {
                CharSequence label = child.getContentDescription();
                if (label != null && DOWNLOAD_LABEL.equalsIgnoreCase(label.toString().trim())) labeled[0] = child;
                else if (iconSized[0] == null && isIconSized(child)) iconSized[0] = child;
            }
            if (depth < MAX_DEPTH) scan(group, ignored, labeled, iconSized, budget, depth + 1);
        }
    }

    private static boolean isIconSized(View view) {
        int width = view.getWidth();
        int height = view.getHeight();
        return width >= DownloadedCheck.dp(BUTTON_MIN_DP) && width <= DownloadedCheck.dp(BUTTON_MAX_DP)
                && Math.abs(width - height) <= DownloadedCheck.dp(BUTTON_SQUARE_TOLERANCE_DP);
    }
}
