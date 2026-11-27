/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-25
 */

package app.morphe.extension.music.patches.utils;

import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class MusicLightThemeElementsTest {
    private static final String SHELF = "music_speed_dial_shelf.eml-js-fe|27cb8c57bfa90fa6|CellType|";
    private static final String TILE = "music_speed_dial_item.eml-fe|25ac56eecf77073|CellType|";
    private static final String TITLE = "ContainerType|ContainerType|ContainerType|TextType|";

    @Before
    public void enableLight() {
        setStartupTheme(MusicLightTheme.VALUE);
    }

    @After
    public void resetTheme() {
        setStartupTheme(null);
    }

    @Test
    public void speedDialTitleReplacesOnlyTheStockForeground() {
        SpannableString stock = new SpannableString("Liked Music");
        ForegroundColorSpan stockForeground = new ForegroundColorSpan(Color.WHITE);
        StyleSpan bold = new StyleSpan(Typeface.BOLD);
        stock.setSpan(stockForeground, 0, stock.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        stock.setSpan(bold, 0, 5, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);

        Spanned title = (Spanned) style(SHELF + TILE + TITLE, stock);
        assertEquals(stock.toString(), title.toString());
        ForegroundColorSpan[] colors = title.getSpans(0, title.length(), ForegroundColorSpan.class);
        assertEquals(1, colors.length);
        assertEquals(MusicLightTheme.FOREGROUND, colors[0].getForegroundColor());
        assertEquals(0, title.getSpanStart(colors[0]));
        assertEquals(title.length(), title.getSpanEnd(colors[0]));
        assertEquals(5, title.getSpanEnd(bold));
        assertEquals(Spanned.SPAN_INCLUSIVE_EXCLUSIVE, title.getSpanFlags(bold));
        assertEquals(0, stock.getSpanStart(stockForeground));
    }

    @Test
    public void speedDialMatchesPlainTitlesAcrossHashesButLeavesOtherNodesAlone() {
        String text = "Track title";
        String titlePath = SHELF + TILE + TITLE;
        Spanned title = (Spanned) style(titlePath, text);
        assertEquals(MusicLightTheme.FOREGROUND,
                title.getSpans(0, title.length(), ForegroundColorSpan.class)[0].getForegroundColor());
        assertSame(text, style(SHELF + "music_shelf_header.eml-fe|abc|CellType|TextType|", text));
        assertSame(text, style(SHELF + TILE + "ContainerType|ContainerType|ContainerType|ImageType|", text));
        assertSame(text, style("other_shelf.eml-fe|abc|CellType|" + TILE + TITLE, text));

        setStartupTheme("dark");
        assertSame(text, style(titlePath, text));
    }

    @Test
    public void onlyTheCommentsFooterGetsLightFillsAndHint() {
        FrameLayout wrapper = new FrameLayout(RuntimeEnvironment.getApplication());
        View presenter = new View(wrapper.getContext());
        View other = new View(wrapper.getContext());
        wrapper.addView(presenter);
        wrapper.addView(other);

        String hint = "Share your thoughts…";
        int band = 0xFF212121;
        int pill = 0xFF363636;
        int logo = 0xFF002040;
        MusicLightTheme.markCommentComposer(wrapper, "music-comment-panel");
        assertEquals(MusicLightTheme.SURFACE, MusicLightTheme.styleCommentComposerFill(presenter, band));
        assertEquals(MusicLightTheme.CONTROL_SURFACE, MusicLightTheme.styleCommentComposerFill(presenter, pill));
        assertEquals(logo, MusicLightTheme.styleCommentComposerFill(presenter, logo));
        assertEquals(0x80212121, MusicLightTheme.styleCommentComposerFill(presenter, 0x80212121));
        assertEquals(pill, MusicLightTheme.styleCommentComposerFill(other, pill));
        assertSame(hint, MusicLightTheme.styleElementsText(null, other, hint));
        Spanned styledHint = (Spanned) MusicLightTheme.styleElementsText(null, presenter, hint);
        assertEquals(hint, styledHint.toString());
        assertEquals(0xFF41464C,
                styledHint.getSpans(0, hint.length(), ForegroundColorSpan.class)[0].getForegroundColor());

        MusicLightTheme.markCommentComposer(wrapper, "other-panel");
        assertEquals(pill, MusicLightTheme.styleCommentComposerFill(presenter, pill));
        assertSame(hint, MusicLightTheme.styleElementsText(null, presenter, hint));
    }

    @Test
    public void expandedCommentEditorKeepsDisclosureLinksReadableAndClickable() {
        FrameLayout wrapper = new FrameLayout(RuntimeEnvironment.getApplication());
        View editor = new View(wrapper.getContext());
        wrapper.addView(editor);
        MusicLightTheme.markExpandedCommentComposer(wrapper);

        assertEquals(MusicLightTheme.SURFACE, MusicLightTheme.styleCommentComposerFill(editor, 0xFF212121));
        assertEquals(MusicLightTheme.CONTROL_SURFACE, MusicLightTheme.styleCommentComposerFill(editor, 0x1AFFFFFF));
        assertEquals(Color.RED, MusicLightTheme.styleCommentComposerFill(editor, Color.RED));

        SpannableString disclosure = new SpannableString("Read these terms");
        URLSpan link = new URLSpan("https://example.org/terms");
        int flags = Spanned.SPAN_EXCLUSIVE_INCLUSIVE;
        disclosure.setSpan(link, 11, 16, flags);
        disclosure.setSpan(new ForegroundColorSpan(0xFF3EA6FF), 11, 16, flags);
        assertSame(disclosure, MusicLightTheme.styleElementsText(null, wrapper, disclosure));

        Spanned result = (Spanned) MusicLightTheme.styleElementsText(null, editor, disclosure);
        assertSame(link, result.getSpans(11, 16, URLSpan.class)[0]);
        ForegroundColorSpan foreground = result.getSpans(11, 16, ForegroundColorSpan.class)[0];
        assertEquals(0xFF065FD4, foreground.getForegroundColor());
        assertEquals(11, result.getSpanStart(foreground));
        assertEquals(16, result.getSpanEnd(foreground));
        assertEquals(flags, result.getSpanFlags(foreground));

        setStartupTheme("modern_youtube");
        assertSame(disclosure, MusicLightTheme.styleElementsText(null, editor, disclosure));
        assertEquals(0xFF212121, MusicLightTheme.styleCommentComposerFill(editor, 0xFF212121));
    }

    @Test
    public void coverHeadingRestoresBothForegroundRolesWithoutDisturbingLinks() {
        String header = "music_page_header.eml-js-fe|62e1e0b1106bdf99|ContainerType|TextType|";
        SpannableString byline = new SpannableString("Author · 1M views");
        URLSpan link = new URLSpan("https://example.org/author");
        int flags = Spanned.SPAN_EXCLUSIVE_INCLUSIVE;
        byline.setSpan(link, 0, 6, flags);
        byline.setSpan(new ForegroundColorSpan(MusicLightTheme.FOREGROUND), 0, 6, flags);
        byline.setSpan(new ForegroundColorSpan(0xFF606060), 6, byline.length(), flags);

        Spanned result = (Spanned) style(header, byline);
        assertEquals(0xFFF1F1F1, result.getSpans(0, 6, ForegroundColorSpan.class)[0].getForegroundColor());
        assertEquals(0xFFAAAAAA, result.getSpans(6, result.length(), ForegroundColorSpan.class)[0]
                .getForegroundColor());
        assertSame(link, result.getSpans(0, 6, URLSpan.class)[0]);
        assertEquals(flags, result.getSpanFlags(link));
        assertSame(byline, style("music_shelf_header.eml-fe|rollout|TextType|", byline));
        assertSame(byline, style(header + "play_button.eml-fe|rollout|TextType|", byline));

        setStartupTheme("modern_youtube");
        assertSame(byline, style(header, byline));
    }

    @Test
    public void coverGlyphPaletteExcludesArtworkAndPlay() {
        PaintedGlyph shuffle = new PaintedGlyph();
        MusicLightDetailHeader.styleGlyph("yt_outline_experimental_shuffle_vd_theme_24", shuffle);
        assertEquals(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN), shuffle.filter);

        PaintedGlyph play = new PaintedGlyph();
        MusicLightDetailHeader.styleGlyph("yt_fill_experimental_play_vd_theme_24", play);
        assertNull(play.filter);
        MusicLightDetailHeader.styleGlyph(null, play);
        assertNull(play.filter);
    }

    @Test
    public void libraryChipsKeepBothOfflineAndSelectedLabelsReadable() {
        FrameLayout header = new FrameLayout(RuntimeEnvironment.getApplication());
        header.setTag("morphe_light_library_chip_header");
        View chip = new View(header.getContext());
        header.addView(chip);
        StringBuilder path = new StringBuilder("chip_bar.eml-js-fe|chip.eml-fe|TextType|");
        SpannableString label = new SpannableString("Downloads");
        label.setSpan(new ForegroundColorSpan(0xFFF1F1F1), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        Spanned styled = (Spanned) MusicLightTheme.styleElementsText(path, chip, label);
        assertEquals(MusicLightTheme.FOREGROUND,
                styled.getSpans(0, label.length(), ForegroundColorSpan.class)[0].getForegroundColor());
        assertSame(label, MusicLightTheme.styleElementsText(path, new View(header.getContext()), label));

        SpannableString selected = new SpannableString("Downloads");
        selected.setSpan(new ForegroundColorSpan(Color.WHITE), 0, selected.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        assertSame(selected, MusicLightTheme.styleElementsText(path, chip, selected));

        setStartupTheme("modern_youtube");
        assertSame(label, MusicLightTheme.styleElementsText(path, chip, label));
    }

    @Test
    public void playerRatingPaletteReplacesOnlyPaleRatingGlyphs() {
        StringBuilder path = new StringBuilder(
                "video_action_bar.eml-js-fe|segmented_like_dislike_button.eml-js-fe|ImageType|");
        int role = MusicLightTheme.elementsGlyphRole(path);
        PaintedGlyph rating = new PaintedGlyph();
        String like = "yt_outline_experimental_thumb_up_vd_theme_24";
        String dislike = "yt_outline_experimental_thumb_down_vd_theme_24";
        PorterDuffColorFilter readable = new PorterDuffColorFilter(0xFF5F6368, PorterDuff.Mode.SRC_IN);
        rating.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));

        assertSame(rating, MusicLightTheme.styleElementsGlyph(role, like, rating));
        assertEquals(readable, rating.getColorFilter());

        rating.setColorFilter(new PorterDuffColorFilter(0xFFF1F1F1, PorterDuff.Mode.SRC_IN));
        MusicLightTheme.styleElementsGlyph(role, dislike, rating);
        assertEquals(readable, rating.getColorFilter());

        PorterDuffColorFilter online = new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.SRC_IN);
        rating.setColorFilter(online);
        MusicLightTheme.styleElementsGlyph(role, like, rating);
        assertSame(online, rating.getColorFilter());

        PorterDuffColorFilter elsewhere = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        rating.setColorFilter(elsewhere);
        MusicLightTheme.styleElementsGlyph(MusicLightTheme.elementsGlyphRole(new StringBuilder("like_button.")),
                like, rating);
        assertSame(elsewhere, rating.getColorFilter());
    }

    @Test
    public void queueTitleRestoresLightTextOnlyWithTheLightTheme() {
        TextView title = new TextView(RuntimeEnvironment.getApplication());
        title.setTextColor(MusicLightTheme.FOREGROUND);
        MusicLightTheme.styleQueueTitle(title);
        assertEquals(0xFFFFFFFE, title.getCurrentTextColor());

        setStartupTheme("dark");
        title.setTextColor(0xFFDADADA);
        MusicLightTheme.styleQueueTitle(title);
        assertEquals(0xFFDADADA, title.getCurrentTextColor());
    }

    private static final class PaintedGlyph extends ColorDrawable {
        private ColorFilter filter;

        @Override
        public void setColorFilter(ColorFilter filter) {
            this.filter = filter;
        }

        @Override
        public ColorFilter getColorFilter() {
            return filter;
        }
    }

    private static CharSequence style(String path, CharSequence text) {
        return MusicLightTheme.styleElementsText(new StringBuilder(path), null, text);
    }

    private static void setStartupTheme(String value) {
        ReflectionHelpers.setStaticField(MusicLightTheme.class, "startupTheme", value);
    }
}
