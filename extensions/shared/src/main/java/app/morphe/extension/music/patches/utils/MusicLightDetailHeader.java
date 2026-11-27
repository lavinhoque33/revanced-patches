/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */

package app.morphe.extension.music.patches.utils;

import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageButton;
import android.widget.ImageView;

import app.morphe.extension.shared.utils.ResourceUtils;

/** Foreground roles for the stock dark scrim, not the light surface underneath the track list. */
public final class MusicLightDetailHeader {
    private static final int PRIMARY = 0xFFF1F1F1;
    private static final int SECONDARY = 0xFFAAAAAA;
    private static final String HEADER = "music_page_header.eml";

    private MusicLightDetailHeader() {
    }

    static boolean isCoverHeader(StringBuilder path) {
        return path != null && (path.indexOf(HEADER) == 0 || path.indexOf("|" + HEADER) >= 0)
                && path.indexOf("|play_button.") < 0;
    }

    static CharSequence styleText(CharSequence original) {
        if (original instanceof Spanned spanned) {
            ForegroundColorSpan[] colors = spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
            if (colors.length != 0) return restoreTextColors(spanned, colors);
        }

        SpannableString text = new SpannableString(original);
        text.setSpan(new ForegroundColorSpan(PRIMARY), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static CharSequence restoreTextColors(Spanned original, ForegroundColorSpan[] colors) {
        SpannableString text = null;
        for (ForegroundColorSpan span : colors) {
            int color = switch (span.getForegroundColor()) {
                case 0xFF000000, MusicLightTheme.FOREGROUND -> PRIMARY;
                case 0xFF606060, 0xFF41464C -> SECONDARY;
                default -> 0;
            };
            if (color == 0) continue;

            if (text == null) text = new SpannableString(original);
            text.removeSpan(span);
            text.setSpan(new ForegroundColorSpan(color),
                    original.getSpanStart(span), original.getSpanEnd(span), original.getSpanFlags(span));
        }
        return text == null ? original : text;
    }

    /** Only named action glyphs, never a cover, an avatar, a gradient, or the primary Play button. */
    public static Drawable styleGlyph(String asset, Drawable drawable) {
        if (drawable == null || asset == null || !asset.contains("_vd_theme_")
                || !(asset.startsWith("yt_") || asset.equals("quantum_ic_comment_vd_theme_24"))) {
            return drawable;
        }
        if (asset.contains("_shuffle_") || asset.contains("_download_") || asset.contains("_bookmark_")
                || asset.contains("_text_bubble_") || asset.contains("_comment_") || asset.contains("_share_")
                || asset.contains("_more_") || asset.contains("_library_add_") || asset.contains("_add_to_")) {
            Drawable tinted = drawable.mutate();
            tinted.setColorFilter(asset.contains("_shuffle_") ? GlyphTints.SHUFFLE : GlyphTints.ACTION);
            return tinted;
        }
        return drawable;
    }

    static void bindToolbar(ViewGroup elements) {
        ToolbarTint tint = new ToolbarTint();
        elements.addOnAttachStateChangeListener(tint);
        if (elements.isAttachedToWindow()) tint.onViewAttachedToWindow(elements);
    }

    private static final class GlyphTints {
        private static final PorterDuffColorFilter ACTION =
                new PorterDuffColorFilter(PRIMARY, PorterDuff.Mode.SRC_IN);
        private static final PorterDuffColorFilter SHUFFLE =
                new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
    }

    /** Native menu items use the Light XML tint. Change it only while the cover is behind the toolbar. */
    private static final class ToolbarTint implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener {
        private static final ColorStateList OVER_COVER = ColorStateList.valueOf(PRIMARY);
        private static final ColorStateList OVER_SURFACE = ColorStateList.valueOf(MusicLightTheme.FOREGROUND);
        private static final int COLLAPSING_TOOLBAR = ResourceUtils.getIdIdentifier("detail_page_collapsing_toolbar");
        private static final int TOOLBAR = ResourceUtils.getIdIdentifier("detail_page_toolbar");
        private static final int TOOLBAR_GLYPH = ResourceUtils.getIdIdentifier("top_bar_menu_item_image_view");

        private ViewGroup toolbar;
        private ColorStateList applied;

        @Override
        public void onViewAttachedToWindow(View elements) {
            if (!(elements.getParent() instanceof View header) || !(header.getParent() instanceof View parent)
                    || parent.getId() != COLLAPSING_TOOLBAR
                    || !(parent.findViewById(TOOLBAR) instanceof ViewGroup group)) return;

            toolbar = group;
            ViewTreeObserver observer = group.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);
            observer.addOnPreDrawListener(this);
        }

        @Override
        public void onViewDetachedFromWindow(View elements) {
            if (toolbar == null) return;

            ViewTreeObserver observer = toolbar.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(this);
                observer.removeOnPreDrawListener(this);
            }
            tintIcons(toolbar, OVER_SURFACE, true);
            toolbar = null;
            applied = null;
        }

        @Override
        public void onGlobalLayout() {
            // A presenter can replace the menu items even when the cover stays expanded.
            applied = null;
        }

        @Override
        public boolean onPreDraw() {
            if (toolbar == null) return true;

            Drawable background = toolbar.getBackground();
            ColorStateList tint = background == null || background.getAlpha() < 128 ? OVER_COVER : OVER_SURFACE;
            if (applied != tint) {
                tintIcons(toolbar, tint, true);
                applied = tint;
            }
            return true;
        }

        private static void tintIcons(ViewGroup group, ColorStateList tint, boolean toolbarRoot) {
            for (int index = 0, count = group.getChildCount(); index < count; index++) {
                View child = group.getChildAt(index);
                if (child instanceof ViewGroup nested) {
                    tintIcons(nested, tint, false);
                } else if (child instanceof ImageView icon
                        && (child.getId() == TOOLBAR_GLYPH
                        || (toolbarRoot && child.getId() == View.NO_ID && child instanceof ImageButton))
                        && icon.getImageTintList() != tint) {
                    icon.setImageTintList(tint);
                }
            }
        }
    }
}
