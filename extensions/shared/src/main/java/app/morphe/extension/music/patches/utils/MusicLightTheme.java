/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-25
 */

package app.morphe.extension.music.patches.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.UserManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.utils.ResourceUtils;

public final class MusicLightTheme {
    public static final int SURFACE = 0xFFF8F9FA;
    public static final int FOREGROUND = 0xFF0F0F0F;
    public static final int CONTROL_SURFACE = 0xFFF1F3F4;
    private static final int MUTED = 0xFF5F6368;
    private static final int COVER_GLYPH = 1;
    private static final int RATING_GLYPH = 2;
    public static final String THEME_PREFERENCE_KEY = "morphe_dark_theme";

    static final String VALUE = "light";
    private static final int LIGHT_MCC = 816;

    // Application and Activity attach run on the main thread; renderers also read this on workers.
    private static volatile String startupTheme;

    private MusicLightTheme() {
    }

    /** Injection from the Music application and UI hosts before they attach the incoming context. */
    public static Context wrapBaseContext(Context base) {
        if (startupTheme == null) {
            startupTheme = readThemePreference(base);
        }
        if (!isEnabled()) {
            return base;
        }

        Configuration override = new Configuration();
        override.mcc = LIGHT_MCC;
        return base.createConfigurationContext(override);
    }

    private static String readThemePreference(Context base) {
        // The first attach can be for an Android Direct Boot service before credential storage opens.
        UserManager users = base.getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) {
            return DrawableColorPatch.DEFAULT_DARK_THEME;
        }

        // Same file as Setting.preferences; neither Setting nor Utils can initialize before attach.
        try {
            return base.getSharedPreferences("revanced", Context.MODE_PRIVATE)
                    .getString(THEME_PREFERENCE_KEY, DrawableColorPatch.DEFAULT_DARK_THEME);
        } catch (ClassCastException ignored) {
            // A malformed value must not break startup. The normal settings loader will clean it up.
            return DrawableColorPatch.DEFAULT_DARK_THEME;
        }
    }

    public static boolean isEnabled() {
        return VALUE.equals(startupTheme);
    }

    public static int settingsBackColor(int stock) {
        return isEnabled() ? FOREGROUND : stock;
    }

    public static int listRowSecondaryResource(int stock) {
        return isEnabled() ? NativeColors.SECONDARY : stock;
    }

    /** Preserve the stock idle state while adapting the active monochrome glyph. */
    public static int flyoutIconResource(int stock) {
        return isEnabled() && stock == NativeColors.FLYOUT_ACTIVE ? NativeColors.PRIMARY : stock;
    }

    /** The Up Next sheet keeps its dark background even in Light. Restore the layout's stock white. */
    public static void styleQueueTitle(TextView title) {
        if (isEnabled()) title.setTextColor(0xFFFFFFFE);
    }

    private static final class NativeColors {
        private static final int FLYOUT_ACTIVE = ResourceUtils.getColorIdentifier("ytm_icon_color_active");
        private static final int PRIMARY = ResourceUtils.getColorIdentifier("ytm_text_color_primary");
        private static final int SECONDARY = ResourceUtils.getColorIdentifier("ytm_text_color_secondary");
    }

    /** Adapt only the text leaves that bypass the selected Elements palette. */
    public static CharSequence styleElementsText(StringBuilder path, View presenter, CharSequence original) {
        if (!isEnabled() || original == null || original.length() == 0) return original;

        final ForegroundColorSpan color;
        final CommentComposerRole composer = presenter == null ? null : CommentComposer.presenters.get(presenter);
        if (composer == CommentComposerRole.EXPANDED) {
            return styleExpandedCommentLinks(original);
        }
        if (composer == CommentComposerRole.INLINE) {
            color = ElementTextColors.COMMENT_COMPOSER;
        } else if (MusicLightDetailHeader.isCoverHeader(path)) {
            return MusicLightDetailHeader.styleText(original);
        } else if (isLibraryChipHost(presenter)) {
            return styleLibraryChipLabel(original);
        } else if (path != null && isSpeedDialTitle(path)) {
            color = ElementTextColors.SPEED_DIAL_TITLE;
        } else {
            return original;
        }

        SpannableString text = new SpannableString(original);
        for (ForegroundColorSpan old : text.getSpans(0, text.length(), ForegroundColorSpan.class)) {
            text.removeSpan(old);
        }
        text.setSpan(color, 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static CharSequence styleExpandedCommentLinks(CharSequence original) {
        if (!(original instanceof Spanned spanned)) return original;

        SpannableString text = null;
        for (ForegroundColorSpan span : spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class)) {
            if (span.getForegroundColor() != 0xFF3EA6FF) continue;

            // Change only the stock light-blue disclosure link color; preserve its range and click action.
            if (text == null) text = new SpannableString(original);
            text.removeSpan(span);
            text.setSpan(new ForegroundColorSpan(0xFF065FD4),
                    spanned.getSpanStart(span), spanned.getSpanEnd(span), spanned.getSpanFlags(span));
        }
        return text == null ? original : text;
    }

    static boolean isLibraryChipHost(View presenter) {
        for (Object host = presenter; host instanceof View view; host = view.getParent()) {
            if ("morphe_light_library_chip_header".equals(view.getTag())) return true;
        }
        return false;
    }

    private static CharSequence styleLibraryChipLabel(CharSequence original) {
        if (!(original instanceof Spanned spanned)) return original;

        SpannableString text = null;
        for (ForegroundColorSpan span : spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class)) {
            // The temporary offline chips use the dark foreground. The selected Light chip
            // uses pure white against a dark fill and must keep that color.
            if (span.getForegroundColor() != 0xFFF1F1F1) continue;

            if (text == null) text = new SpannableString(original);
            text.removeSpan(span);
            text.setSpan(new ForegroundColorSpan(FOREGROUND),
                    spanned.getSpanStart(span), spanned.getSpanEnd(span), spanned.getSpanFlags(span));
        }
        return text == null ? original : text;
    }

    public static int elementsGlyphRole(StringBuilder path) {
        if (MusicLightDetailHeader.isCoverHeader(path)) return COVER_GLYPH;
        if (path == null || path.indexOf("video_action_bar.") < 0) return 0;

        return path.indexOf("segmented_like_dislike_button.") >= 0
                || path.indexOf("|like_button.") >= 0
                || path.indexOf("|dislike_button.") >= 0 ? RATING_GLYPH : 0;
    }

    public static Drawable styleElementsGlyph(int role, String asset, Drawable drawable) {
        return switch (role) {
            case COVER_GLYPH -> MusicLightDetailHeader.styleGlyph(asset, drawable);
            case RATING_GLYPH -> styleRatingGlyph(asset, drawable);
            default -> drawable;
        };
    }

    private static Drawable styleRatingGlyph(String asset, Drawable drawable) {
        if (drawable == null || asset == null || (!asset.contains("_thumb_up_") && !asset.contains("_thumb_down_"))) {
            return drawable;
        }

        // The client-generated offline bar supplies a dark-palette filter. Keep the already-dark
        // online glyph and the selected/disabled variants of the stock drawable.
        ColorFilter filter = drawable.getColorFilter();
        boolean pale = filter == null ? asset.contains("_white_") || asset.contains("_disabled_")
                : RatingTints.WHITE.equals(filter) || RatingTints.OFF_WHITE.equals(filter);
        if (!pale) return drawable;

        Drawable tinted = drawable.mutate();
        tinted.setColorFilter(RatingTints.READABLE);
        return tinted;
    }

    private static final class RatingTints {
        private static final PorterDuffColorFilter WHITE =
                new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        private static final PorterDuffColorFilter OFF_WHITE =
                new PorterDuffColorFilter(0xFFF1F1F1, PorterDuff.Mode.SRC_IN);
        private static final PorterDuffColorFilter READABLE =
                new PorterDuffColorFilter(MUTED, PorterDuff.Mode.SRC_IN);
    }

    private static boolean isSpeedDialTitle(StringBuilder path) {
        final int item = path.indexOf("|music_speed_dial_item.");
        if (item < 0 || path.indexOf("music_speed_dial_shelf.") != 0) return false;

        // Skip the template's rollout hash; only the text leaf is a title.
        final String title = "|CellType|ContainerType|ContainerType|ContainerType|TextType|";
        final int hashStart = path.indexOf("|", item + 1);
        final int titleStart = path.indexOf("|", hashStart + 1);
        return hashStart > item && titleStart > hashStart && path.length() - titleStart == title.length()
                && path.indexOf(title, titleStart) == titleStart;
    }

    /** Bind the mark to the reused footer presenter; comment rows have different presenters. */
    public static void markCommentComposer(ViewGroup wrapper, String panelId) {
        if (!isEnabled() || wrapper == null) return;

        View presenter = wrapper.getChildAt(0);
        if (presenter == null) return;

        if ("music-comment-panel".equals(panelId) || "comment-item-section".equals(panelId)) {
            CommentComposer.presenters.put(presenter, CommentComposerRole.INLINE);
        } else {
            CommentComposer.presenters.remove(presenter);
        }
    }

    /** The expanded editor owns a separate Elements wrapper and renders only after this mark. */
    public static void markExpandedCommentComposer(ViewGroup wrapper) {
        if (!isEnabled()) return;

        View presenter = wrapper.getChildAt(0);
        if (presenter != null) {
            CommentComposer.presenters.put(presenter, CommentComposerRole.EXPANDED);
        }
    }

    /** Leave the stock drawable, corner radius, states and ripple in place. */
    public static int styleCommentComposerFill(View presenter, int original) {
        if (!isEnabled() || presenter == null) return original;

        int shade = original & 0xFF;
        boolean opaqueMonochrome = (original >>> 24) == 0xFF && shade <= 0x80
                && (original & 0xFFFFFF) == shade * 0x010101;
        // The editor pill can be a 10% white overlay on the band, rather than an opaque fill.
        if (!opaqueMonochrome && original != 0x1AFFFFFF) return original;

        CommentComposerRole role = CommentComposer.presenters.get(presenter);
        if (role == CommentComposerRole.EXPANDED) {
            return switch (original) {
                case 0xFF212121 -> SURFACE;
                case 0xFF373737, 0x1AFFFFFF -> CONTROL_SURFACE;
                default -> original;
            };
        }
        if (role != CommentComposerRole.INLINE || !opaqueMonochrome) return original;

        // The near-black band and the lighter input pill need distinct surfaces.
        return shade <= 0x21 ? SURFACE : CONTROL_SURFACE;
    }

    private enum CommentComposerRole {
        INLINE, EXPANDED
    }

    private static final class CommentComposer {
        private static final Map<View, CommentComposerRole> presenters =
                Collections.synchronizedMap(new WeakHashMap<>());
    }

    private static final class ElementTextColors {
        private static final ForegroundColorSpan SPEED_DIAL_TITLE = new ForegroundColorSpan(FOREGROUND);
        private static final ForegroundColorSpan COMMENT_COMPOSER = new ForegroundColorSpan(0xFF41464C);
    }

    /** Innertube ThemeStore's Light value is 1; keep the stock value for the dark presets. */
    public static int selectElementsTheme(int original) {
        return isEnabled() ? 1 : original;
    }

    static String getStartupDarkTheme() {
        String theme = startupTheme;
        return theme == null || VALUE.equals(theme) ? DrawableColorPatch.DEFAULT_DARK_THEME : theme;
    }

    /** Music-only setThemeColor hook; returns true when the shared color lookup can be skipped. */
    public static boolean applySharedTheme() {
        boolean light = isEnabled();
        BaseThemeUtils.updateMusicThemeStatus(!light);
        if (light) {
            BaseThemeUtils.setThemeLightColor(SURFACE);
            BaseThemeUtils.setThemeDarkColor(FOREGROUND);
        }
        return light;
    }
}
