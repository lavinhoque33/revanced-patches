/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-26.
 */

package app.morphe.extension.youtube.patches.feed;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Tints the background of the cards of the feed with a color sampled from their thumbnail, like the stock
 * app, which receives the sampled color from the server.
 * <p>
 * The thumbnail of a card is an image of the element of the card, which can be a bitmap drawable or a
 * drawable that draws the image itself, such as the drawable of the image library. Both are sampled:
 * the bitmap is read, and the other drawables are drawn in a small bitmap.
 * <p>
 * Only the background of the card is tinted: the surfaces inside it keep their colors and are drawn
 * over the tint.
 */
final class FeedCardColorSampler {
    /**
     * Number of samples of each side of a thumbnail.
     */
    private static final int GRID_SIZE = 8;
    /**
     * Saturation and brightness of the tint, for the dark and the light theme.
     * The values match the background of the cards of the stock app, which is a dark olive for a
     * green thumbnail in the dark theme, and a pale green in the light theme.
     */
    private static final float DARK_SATURATION = 0.9f;
    private static final float DARK_BRIGHTNESS = 0.11f;
    private static final float LIGHT_SATURATION = 0.22f;
    private static final float LIGHT_BRIGHTNESS = 0.97f;
    /**
     * Samples that are too dark or too gray to have the color of the thumbnail are ignored,
     * such as the black bars of a video.
     */
    private static final float MINIMUM_BRIGHTNESS = 0.1f;
    private static final float MINIMUM_SATURATION = 0.05f;
    /**
     * A thumbnail without at least this weight of colored samples is gray, and its card is not tinted.
     */
    private static final float MINIMUM_WEIGHT = 1.5f;
    /**
     * Smallest bitmap that is sampled, in pixels, which skips the avatars and the small placeholders.
     */
    private static final int MINIMUM_BITMAP_SIZE = 48;
    /**
     * Smallest image of a card, in pixels. The avatars and the icons are smaller, and they are square.
     */
    private static final int MINIMUM_THUMBNAIL_WIDTH = 96;
    /**
     * Limits of the work of one frame, which keeps scrolling smooth.
     */
    private static final int MAXIMUM_SEARCHES_PER_FRAME = 4;
    private static final int MAXIMUM_SAMPLES_PER_FRAME = 4;
    private static final int MAXIMUM_DRAWN_SAMPLES_PER_FRAME = 2;
    private static final int MAXIMUM_HARDWARE_SAMPLES_PER_FRAME = 1;
    private static final int MAXIMUM_THUMBNAIL_DEPTH = 12;
    /**
     * A thumbnail that is not readable yet, such as a placeholder that the image replaces while it loads,
     * is sampled again after this delay, up to this number of times.
     */
    private static final int MAXIMUM_SAMPLE_ATTEMPTS = 5;
    private static final long SAMPLE_RETRY_INTERVAL_MILLIS = 400;
    /**
     * Number of the filters of the tints that are kept, which is enough for the visible cards.
     */
    private static final int MAXIMUM_TINT_FILTERS = 64;
    /**
     * Number of the surfaces of a card that are kept, which is enough for the metadata of a card.
     */
    private static final int MAXIMUM_TINTED_SURFACES = 8;
    /**
     * The color of the surface of the cards of YouTube that have no tint, such as the cards that the
     * app draws without a surface below the thumbnail.
     */
    private static final int CARD_COLOR_DARK = 0xFF212121;
    private static final int CARD_COLOR_LIGHT = 0xFFFFFFFF;
    /**
     * Number of the log lines that help diagnose the sampling. The logs are limited because they are
     * written for every card that is seen, while scrolling.
     */
    private static final int MAXIMUM_LOGS = 32;
    private static final int MAXIMUM_SURFACE_LOGS = 6;
    private static final int MAXIMUM_MISSING_THUMBNAIL_LOGS = 6;
    private static final int MAXIMUM_NOT_CARD_LOGS = 6;
    private static final long SLOW_UPDATE_NANOS = 8_000_000L;
    private static final long SLOW_UPDATE_INTERVAL_MILLIS = 5_000;
    /**
     * A card without a tint, which keeps its own background color. The tints are opaque colors,
     * so they are never zero.
     */
    private static final int NO_TINT = 0;

    /**
     * Whether the item of a list is a card of the feed with a thumbnail, by the view of the item.
     */
    private static final Map<View, Boolean> cardItems = new WeakHashMap<>();
    /**
     * Thumbnail of the card of an item.
     */
    private static final Map<View, Thumbnail> cardThumbnails = new WeakHashMap<>();
    /**
     * Tint of the card of an item, which the background of the card looks up when it's drawn.
     */
    private static final Map<View, Integer> cardTints = new WeakHashMap<>();
    /**
     * Backgrounds of the card of an item, which apply the tint when they're drawn.
     */
    private static final Map<View, List<WeakReference<Drawable>>> cardBackgrounds = new WeakHashMap<>();
    /**
     * Backgrounds whose color was looked up before their item of the feed was mounted, by the view of the
     * background. A background looks up its color only when its bounds are set, so the bounds are set again
     * when the item is in the feed, and the background applies the tint of the card like the other surfaces.
     */
    private static final Map<View, List<WeakReference<Drawable>>> pendingBackgrounds = new WeakHashMap<>();
    /**
     * Hue of the thumbnail of a bitmap drawable. NaN if the thumbnail has no color to tint the card with.
     * The drawables are compared by identity: Glide reuses the drawable of a cached image, but the bitmap
     * inside it can be reused for other images after the drawable is discarded.
     */
    private static final Map<Drawable, Float> thumbnailHues = new WeakHashMap<>();
    /**
     * Views of the cards and the surfaces that were logged.
     */
    private static final Map<View, Boolean> loggedCards = new WeakHashMap<>();
    private static final Map<View, Boolean> loggedSurfaces = new WeakHashMap<>();
    private static final Map<RecyclerView, Boolean> loggedLists = new WeakHashMap<>();
    /**
     * Filters of the tints, which are shared by the surfaces of the cards of the same color.
     */
    private static final Map<Integer, PorterDuffColorFilter> tintFilters = new HashMap<>();
    /**
     * Backgrounds below the thumbnail of a card that the tint is applied to, by the view of the card.
     * Their filter is removed when the card loses the tint.
     */
    private static final Map<View, List<WeakReference<Drawable>>> tintedBackgrounds = new WeakHashMap<>();
    /**
     * ImageViews below the thumbnail of a card that the tint is applied to, by the view of the card.
     * The filter of the image is set on the view, because the drawable can be shared by the cards.
     */
    private static final Map<View, List<WeakReference<View>>> tintedImages = new WeakHashMap<>();
    /**
     * Surfaces that the patch draws for the cards that the app draws without a surface of its own,
     * such as the cards of the mixes, by the view of the card.
     */
    private static final Map<View, CardSurface> cardSurfaces = new WeakHashMap<>();
    private static final Map<View, Boolean> loggedCardSurfaces = new WeakHashMap<>();
    /**
     * Labels of the cards that were seen, which show that a recycled view shows another card.
     */
    private static final Map<View, CharSequence> cardLabels = new WeakHashMap<>();
    /**
     * State of the surfaces of the cards that was logged, whose changes are logged.
     */
    private static final Map<View, String> tintStates = new WeakHashMap<>();
    /**
     * Cards that the app draws a surface of the metadata of, which is the part below the thumbnail, such
     * as the band of the cards. The cards without one, such as the cards of the mixes, get one.
     */
    private static final Map<View, Boolean> appCardSurfaces = new WeakHashMap<>();
    /**
     * Surfaces of a card that were looked up, reused before every draw so that the walk allocates nothing.
     */
    private static final List<Drawable> foundBackgrounds = new ArrayList<>(4);
    private static final List<View> foundImages = new ArrayList<>(4);
    /**
     * Classes of the drawables that could not be sampled, which are logged once.
     */
    private static final Set<Class<?>> loggedDrawableClasses = new HashSet<>();
    /**
     * Row of pixels of a thumbnail, reused by the samplings.
     */
    private static int[] rowPixels = new int[0];
    private static final float[] sampleHsv = new float[3];
    private static final float[] tintHsv = new float[3];
    /**
     * Small bitmap that a drawable is drawn in, for the thumbnails which are not bitmap drawables.
     */
    private static Bitmap drawableBitmap;
    private static Canvas drawableCanvas;
    private static boolean darkMode;
    private static int logs;
    private static int surfaceAcceptedLogs;
    private static int surfaceLogs;
    private static int missingThumbnailLogs;
    private static int notCardLogs;
    private static long slowUpdateLogTime;

    private FeedCardColorSampler() {
    }

    /**
     * Called when the list sets the spacing of an item, before the item is drawn, and again whenever
     * a recycled view shows another item.
     */
    static void setCardItem(View view, boolean card) {
        cardItems.put(view, card);

        if (!card && !FeedCardLayoutPatch.isCardLabel(view.getContentDescription())) {
            // A recycled view must not keep the tint of the card it showed.
            cardTints.remove(view);
            cardThumbnails.remove(view);
            cardBackgrounds.remove(view);
            cardLabels.remove(view);
            appCardSurfaces.remove(view);
            tintStates.remove(view);
            tintCardBelowThumbnail(view, null);
            removeCardSurface(view);
        }
    }

    /**
     * @return Whether the item of the list is a card of the feed with a thumbnail.
     */
    static boolean isCardItem(View item) {
        // The cards converted by the patch have the label of the cards, also when the element of the item
        // is not a video, such as when the video plays in the card.
        if (FeedCardLayoutPatch.isCardLabel(item.getContentDescription())) {
            return true;
        }

        Boolean card = cardItems.get(item);
        return card != null && card;
    }

    /**
     * Called with the background of a drawable of an item, when its bounds are set.
     *
     * @param item         The item of the feed of the card, or null if the drawable is not in a card.
     * @param drawableView The view of the drawable, which is the item if the drawable is the background
     *                     of the card.
     * @return The tint of the card, or null if the card has no tint.
     */
    @Nullable
    static Integer getCardColor(@Nullable View item, @Nullable View drawableView, Drawable drawable) {
        final boolean dynamicColors = Settings.FEED_CARD_LAYOUT_DYNAMIC_COLORS.get();
        if (item != null && drawableView != null && isCardItem(item)) {
            final Rect bounds = getCardBounds(item, drawableView, drawable);
            // The backgrounds of the card are set again when its tint changes, also before it has a tint,
            // because a background looks up the color of the card only when its bounds are set. They are
            // recorded without the tint too, because a card without a surface of the app gets one.
            if (bounds != null && bounds.width() * 10 >= item.getWidth() * 9) {
                addBackground(item, drawable);
                if (!appCardSurfaces.containsKey(item) && isSurfaceBelowThumbnail(item, bounds)) {
                    appCardSurfaces.put(item, Boolean.TRUE);
                }
            }
            if (!dynamicColors) {
                return null;
            }

            final String rejection = getSurfaceRejection(item, drawableView, bounds);
            if (rejection != null) {
                logSurfaceRejected(item, drawableView, drawable, bounds, rejection);
                return null;
            }

            Integer tint = cardTints.get(item);
            if (tint != null) {
                // The color of the paint is not enough for the surfaces that set their color again while
                // they are drawn, so the tint is applied as a filter too, which keeps the shape.
                applyTintFilter(drawable, tint);
                logCardSurface(item, drawableView, drawable, bounds, tint);
            }
            return tint;
        }

        if (!dynamicColors) {
            return null;
        }
        if (item == null || drawableView == null) {
            // A background of an item that is not in the feed yet is looked up again when the item is
            // mounted. The backgrounds of the other views, such as the shelves and the watch page, are
            // not retried.
            if (drawableView != null && !drawableView.isAttachedToWindow()) {
                addPendingBackground(drawableView, drawable);
            }
            logSurfaceRejected(null, drawableView, drawable, null, "the drawable is not in the feed");
            return null;
        }

        logSurfaceRejected(item, drawableView, drawable, null, "the item is not a card");
        return null;
    }

    /**
     * Samples the thumbnails of the cards in the list and updates their tint, before the list is drawn.
     * Called for the list of the feed only, so the items of the shelves are never sampled.
     */
    static void updateCardTints(RecyclerView list) {
        try {
            if (loggedLists.put(list, Boolean.TRUE) == null) {
                Logger.printDebug(() -> "Card colors: list with " + list.getChildCount() + " items");
            }

            final boolean currentDarkMode = BaseThemeUtils.isDarkModeEnabled();
            if (currentDarkMode != darkMode) {
                darkMode = currentDarkMode;
                // The color of the cards depends on the theme, so the visible cards are tinted again.
                // The hues of the thumbnails are cached, so they are not sampled again.
                cardTints.clear();
                cardThumbnails.clear();
                Logger.printDebug(() -> "Card colors: dark mode " + currentDarkMode);
            }

            final boolean dynamicColors = Settings.FEED_CARD_LAYOUT_DYNAMIC_COLORS.get();
            if (!dynamicColors) {
                clearTints();
            }

            // The cards that the app draws without a surface of its own get one, also without the tint.
            for (int i = 0, count = list.getChildCount(); i < count; i++) {
                updateCardSurface(list.getChildAt(i));
            }

            if (!dynamicColors) {
                return;
            }

            final long startTime = System.nanoTime();
            int searches = 0;
            int samples = 0;
            int drawnSamples = 0;

            for (int i = 0, count = list.getChildCount(); i < count; i++) {
                View item = list.getChildAt(i);
                if (item.getHeight() <= 0) {
                    continue;
                }
                if (!isCardItem(item)) {
                    logNotCard(item);
                    continue;
                }

                Thumbnail thumbnail = cardThumbnails.get(item);
                if (thumbnail == null || !thumbnail.isAttachedTo(item)) {
                    if (searches >= MAXIMUM_SEARCHES_PER_FRAME) {
                        continue;
                    }
                    searches++;

                    ImageView imageView = findThumbnailView(item, 0);
                    if (imageView == null) {
                        cardThumbnails.remove(item);
                        removeTint(item);
                        logMissingThumbnail(item);
                        continue;
                    }
                    thumbnail = new Thumbnail(imageView);
                    cardThumbnails.put(item, thumbnail);
                }

                ImageView imageView = thumbnail.imageView();
                Drawable drawable = imageView == null ? null : imageView.getDrawable();
                if (drawable == null) {
                    cardThumbnails.remove(item);
                    removeTint(item);
                    continue;
                }

                final long now = System.currentTimeMillis();
                final CharSequence label = item.getContentDescription();
                if (!thumbnail.needsSample(drawable, label, now)) {
                    applyTint(item, thumbnail.tint());
                    continue;
                }

                BitmapDrawable bitmapDrawable = getBitmapDrawable(drawable);
                final boolean drawn = bitmapDrawable == null;
                final int maximumSamples = bitmapDrawable != null && isHardware(bitmapDrawable)
                        ? MAXIMUM_HARDWARE_SAMPLES_PER_FRAME
                        : MAXIMUM_SAMPLES_PER_FRAME;
                if (samples >= maximumSamples || (drawn && drawnSamples >= MAXIMUM_DRAWN_SAMPLES_PER_FRAME)) {
                    continue;
                }
                samples++;
                if (drawn) {
                    drawnSamples++;
                }

                final float hue = drawn ? sampleDrawn(drawable) : getBitmapHue(bitmapDrawable);
                final int tint = Float.isNaN(hue) ? NO_TINT : getTint(hue);
                thumbnail = thumbnail.sampled(drawable, label, now, tint);
                cardThumbnails.put(item, thumbnail);

                if (tint != NO_TINT) {
                    logTint(item, hue, tint);
                } else {
                    logUntinted(item, drawable);
                }
                applyTint(item, tint);
            }

            // The views of a card are mounted again while the list scrolls, so the tint of the card is
            // applied to the surfaces of the card again before every draw.
            for (int i = 0, count = list.getChildCount(); i < count; i++) {
                View item = list.getChildAt(i);
                Integer tint = cardTints.get(item);
                if (tint != null) {
                    applyCardBackgroundsTint(item, tint);
                    tintCardBelowThumbnail(item, tint);
                }
            }

            // The colors of the backgrounds of the cards were looked up before the cards were mounted,
            // and the tint of the cards did not change since then.
            if (!pendingBackgrounds.isEmpty()) {
                for (int i = 0, count = list.getChildCount(); i < count; i++) {
                    View item = list.getChildAt(i);
                    if (cardTints.containsKey(item)) {
                        restartPendingBackgrounds(item);
                    }
                }
            }

            logSlowUpdate(startTime, samples);
        } catch (Exception ex) {
            Logger.printException(() -> "updateCardTints failure", ex);
        }
    }

    /**
     * The stock app tints the surface of the card below the thumbnail, which is as wide as the card, also
     * when the title of the card is long and the surface is inset by the padding of the metadata.
     * The backgrounds inside the metadata, such as the chips and the buttons, are narrower, and the bar
     * of the progress of the video is thinner.
     *
     * @param bounds The bounds of the drawable relative to the item, or null if it's not in the item.
     * @return Why the drawable is not a surface of the card, or null if it is a surface of the card.
     */
    @Nullable
    private static String getSurfaceRejection(View item, View drawableView, @Nullable Rect bounds) {
        if (drawableView == item) {
            return null;
        }

        final int width = item.getWidth();
        final int height = item.getHeight();
        if (width <= 0 || height <= 0) {
            return "the item is not laid out";
        }
        if (bounds == null) {
            return "the drawable is not in the item";
        }
        // A surface of the card is as wide as the card, or the card without the padding of the metadata.
        if (bounds.width() * 20 < width * 17) {
            return "the drawable is narrower than the card";
        }
        if (bounds.height() * 20 < height) {
            return "the drawable is thinner than the card, such as the bar of the progress";
        }
        return null;
    }

    /**
     * @return The bounds of the drawable of the view, relative to the item, or null if the view is not
     *         inside the item.
     */
    @Nullable
    private static Rect getCardBounds(View item, View drawableView, Drawable drawable) {
        Rect bounds = drawable.copyBounds();
        View view = drawableView;
        while (view != item) {
            ViewParent parent = view.getParent();
            if (!(parent instanceof View parentView)) {
                return null;
            }
            bounds.offset(view.getLeft(), view.getTop());
            view = parentView;
        }
        return bounds;
    }

    private static void addBackground(View item, Drawable drawable) {
        List<WeakReference<Drawable>> backgrounds = cardBackgrounds.get(item);
        if (backgrounds == null) {
            backgrounds = new ArrayList<>(2);
            cardBackgrounds.put(item, backgrounds);
        }

        for (int i = 0, count = backgrounds.size(); i < count; i++) {
            if (backgrounds.get(i).get() == drawable) {
                return;
            }
        }

        // The drawables of a recycled view can be replaced.
        if (backgrounds.size() >= 4) {
            backgrounds.remove(0);
        }
        backgrounds.add(new WeakReference<>(drawable));
    }

    private static void addPendingBackground(View view, Drawable drawable) {
        List<WeakReference<Drawable>> backgrounds = pendingBackgrounds.get(view);
        if (backgrounds == null) {
            backgrounds = new ArrayList<>(2);
            pendingBackgrounds.put(view, backgrounds);
        }

        for (int i = 0, count = backgrounds.size(); i < count; i++) {
            if (backgrounds.get(i).get() == drawable) {
                return;
            }
        }

        if (backgrounds.size() >= 4) {
            backgrounds.remove(0);
        }
        backgrounds.add(new WeakReference<>(drawable));
    }

    /**
     * Sets the bounds of the backgrounds that were looked up before the item was mounted, which looks up
     * their color again, now that they are in the feed and the card of the item has a tint.
     */
    private static void restartPendingBackgrounds(View item) {
        if (pendingBackgrounds.isEmpty()) {
            return;
        }

        for (Map.Entry<View, List<WeakReference<Drawable>>> entry
                : new ArrayList<>(pendingBackgrounds.entrySet())) {
            View view = entry.getKey();
            if (!isInside(view, item)) {
                continue;
            }
            pendingBackgrounds.remove(view);
            for (WeakReference<Drawable> reference : entry.getValue()) {
                Drawable drawable = reference.get();
                if (drawable != null) {
                    restartBackground(drawable);
                }
            }
        }
    }

    private static boolean isInside(View view, View item) {
        while (view != null) {
            if (view == item) {
                return true;
            }
            ViewParent parent = view.getParent();
            view = parent instanceof View parentView ? parentView : null;
        }
        return false;
    }

    /**
     * @return The image of the item with the largest view, which is the thumbnail of a card,
     *         or null if the item has no image.
     */
    @Nullable
    private static ImageView findThumbnailView(View view, int depth) {
        if (depth > MAXIMUM_THUMBNAIL_DEPTH || (depth > 0 && view instanceof RecyclerView)) {
            return null;
        }

        ImageView best = null;
        int bestArea = 0;
        if (view instanceof ImageView imageView && isThumbnailView(imageView)) {
            best = imageView;
            bestArea = imageView.getWidth() * imageView.getHeight();
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                ImageView child = findThumbnailView(group.getChildAt(i), depth + 1);
                if (child == null) {
                    continue;
                }
                final int area = child.getWidth() * child.getHeight();
                if (area > bestArea) {
                    best = child;
                    bestArea = area;
                }
            }
        }

        return best;
    }

    /**
     * The thumbnail of a card is wide, and the avatars and the icons are not.
     */
    private static boolean isThumbnailView(ImageView view) {
        return view.getVisibility() == View.VISIBLE
                && view.getDrawable() != null
                && view.getWidth() > 0 && view.getHeight() > 0
                && view.getWidth() >= MINIMUM_THUMBNAIL_WIDTH
                && view.getWidth() * 5 >= view.getHeight() * 6;
    }

    /**
     * @return The drawable of a bitmap, also of the cross fade of the images, or null if the drawable
     *         has no bitmap that is large enough and landscape, such as the thumbnail of a video.
     */
    @Nullable
    private static BitmapDrawable getBitmapDrawable(@Nullable Drawable drawable) {
        if (drawable instanceof BitmapDrawable bitmapDrawable) {
            Bitmap bitmap = bitmapDrawable.getBitmap();
            // The avatars and the icons of a card are smaller than its thumbnail, and they are not landscape.
            if (bitmap == null || bitmap.isRecycled()
                    || bitmap.getWidth() < MINIMUM_BITMAP_SIZE
                    || bitmap.getHeight() < MINIMUM_BITMAP_SIZE
                    || bitmap.getWidth() * 5 < bitmap.getHeight() * 6) {
                return null;
            }
            return bitmapDrawable;
        }

        if (drawable instanceof DrawableWrapper wrapper) {
            return getBitmapDrawable(wrapper.getDrawable());
        }

        if (drawable instanceof LayerDrawable layerDrawable) {
            BitmapDrawable best = null;
            int bestArea = 0;
            for (int i = 0, count = layerDrawable.getNumberOfLayers(); i < count; i++) {
                BitmapDrawable layer = getBitmapDrawable(layerDrawable.getDrawable(i));
                if (layer == null) {
                    continue;
                }
                Bitmap bitmap = layer.getBitmap();
                final int area = bitmap.getWidth() * bitmap.getHeight();
                // The last layer of a cross fade is the new image, which is preferred.
                if (area >= bestArea) {
                    best = layer;
                    bestArea = area;
                }
            }
            return best;
        }

        return null;
    }

    private static boolean isHardware(BitmapDrawable drawable) {
        Bitmap bitmap = drawable.getBitmap();
        return bitmap != null && bitmap.getConfig() == Bitmap.Config.HARDWARE;
    }

    /**
     * @return The hue of a bitmap thumbnail, or NaN if the thumbnail is gray or too dark.
     */
    private static float getBitmapHue(BitmapDrawable drawable) {
        Float hue = thumbnailHues.get(drawable);
        if (hue == null) {
            hue = sampleBitmap(drawable.getBitmap());
            thumbnailHues.put(drawable, hue);
        }
        return hue;
    }

    /**
     * @return The hue of a thumbnail that is not a bitmap drawable, which is drawn in a small bitmap,
     *         or NaN if it has no bitmap to draw or it's gray.
     */
    private static float sampleDrawn(Drawable drawable) {
        if (!prepareDrawableCanvas()) {
            return Float.NaN;
        }

        Rect bounds = drawable.copyBounds();
        try {
            drawable.setBounds(0, 0, GRID_SIZE, GRID_SIZE);
            drawable.draw(drawableCanvas);
        } catch (Exception ex) {
            // A hardware bitmap can't be drawn in a software canvas, but the image library copies it
            // to a bitmap that can, and the ones that fail keep the color of the card.
            logUnreadableDrawable(drawable, ex);
            return Float.NaN;
        } finally {
            drawable.setBounds(bounds);
        }

        return sampleHue(drawableBitmap);
    }

    private static boolean prepareDrawableCanvas() {
        if (drawableBitmap == null || drawableBitmap.isRecycled()) {
            try {
                drawableBitmap = Bitmap.createBitmap(GRID_SIZE, GRID_SIZE, Bitmap.Config.ARGB_8888);
                drawableCanvas = new Canvas(drawableBitmap);
            } catch (Exception ex) {
                Logger.printException(() -> "Card colors: canvas failure", ex);
                return false;
            }
        }

        drawableCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        return true;
    }

    /**
     * @return The hue of the thumbnail, or NaN if the thumbnail is gray or too dark.
     */
    private static float sampleBitmap(Bitmap bitmap) {
        Bitmap readable = bitmap;

        // The pixels of a hardware bitmap can't be read, but it can be copied to a bitmap that can.
        if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            try {
                readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            } catch (Exception ex) {
                Logger.printException(() -> "Copy of a hardware bitmap failure", ex);
                return Float.NaN;
            }
            if (readable == null) {
                return Float.NaN;
            }
        }

        try {
            return sampleHue(readable);
        } finally {
            if (readable != bitmap) {
                readable.recycle();
            }
        }
    }

    /**
     * The hues of the samples are averaged as vectors, which keeps the hue of the dominant color
     * of a thumbnail that has more than one color, such as a red and a blue half.
     */
    private static float sampleHue(Bitmap bitmap) {
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return Float.NaN;
        }

        if (rowPixels.length < width) {
            rowPixels = new int[width];
        }

        float hueX = 0;
        float hueY = 0;
        float weight = 0;
        for (int rowIndex = 0; rowIndex < GRID_SIZE; rowIndex++) {
            final int y = height * (2 * rowIndex + 1) / (2 * GRID_SIZE);
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1);

            for (int columnIndex = 0; columnIndex < GRID_SIZE; columnIndex++) {
                final int x = width * (2 * columnIndex + 1) / (2 * GRID_SIZE);
                final int color = rowPixels[x];
                Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), sampleHsv);

                // The bright and saturated pixels are the color of the thumbnail, and the dark or gray
                // ones, such as the black bars of a video, are not.
                if (sampleHsv[2] < MINIMUM_BRIGHTNESS || sampleHsv[1] < MINIMUM_SATURATION) {
                    continue;
                }

                final float sampleWeight = sampleHsv[1] * sampleHsv[2];
                final double radians = Math.toRadians(sampleHsv[0]);
                hueX += (float) Math.cos(radians) * sampleWeight;
                hueY += (float) Math.sin(radians) * sampleWeight;
                weight += sampleWeight;
            }
        }

        if (weight < MINIMUM_WEIGHT) {
            return Float.NaN;
        }

        final double hue = Math.toDegrees(Math.atan2(hueY, hueX));
        return (float) (hue < 0 ? hue + 360 : hue);
    }

    private static void applyTint(View item, int tint) {
        final Integer previous = cardTints.get(item);
        if (tint == NO_TINT) {
            if (previous == null) {
                return;
            }
            cardTints.remove(item);
        } else {
            if (previous != null && previous.intValue() == tint) {
                // A recycled view mounts new surfaces of the card, which are tinted again.
                tintCardBelowThumbnail(item, tint);
                return;
            }
            cardTints.put(item, tint);
        }

        refreshBackground(item);
    }

    private static int getTint(float hue) {
        tintHsv[0] = hue;
        tintHsv[1] = darkMode ? DARK_SATURATION : LIGHT_SATURATION;
        tintHsv[2] = darkMode ? DARK_BRIGHTNESS : LIGHT_BRIGHTNESS;
        return Color.HSVToColor(tintHsv);
    }

    private static void removeTint(View item) {
        if (cardTints.remove(item) != null) {
            refreshBackground(item);
        }
    }

    private static void clearTints() {
        if (cardTints.isEmpty()) {
            return;
        }

        // The tints are removed before the backgrounds are drawn again, which looks up a tint again.
        List<View> items = new ArrayList<>(cardTints.keySet());
        cardTints.clear();
        for (View item : items) {
            refreshBackground(item);
        }
    }

    /**
     * The background of a card resolves its color only when its bounds are set, so the bounds are set
     * again to make it look up the new tint. This is the same trick that fixes the color of the chips.
     */
    private static void refreshBackground(View item) {
        // A card without a tint keeps the color of the surfaces, and the filter of the tint is removed.
        final Integer tint = cardTints.get(item);
        Drawable itemBackground = item.getBackground();
        if (itemBackground != null) {
            applyTintFilter(itemBackground, tint);
            restartBackground(itemBackground);
        }

        List<WeakReference<Drawable>> backgrounds = cardBackgrounds.get(item);
        if (backgrounds != null) {
            // The backgrounds of the card can be replaced while the bounds are set again.
            for (WeakReference<Drawable> reference : new ArrayList<>(backgrounds)) {
                Drawable drawable = reference.get();
                if (drawable != null && drawable != itemBackground) {
                    applyTintFilter(drawable, tint);
                    restartBackground(drawable);
                }
            }
        }

        // The backgrounds of the card that were looked up before the card was mounted.
        restartPendingBackgrounds(item);

        // The surfaces that the app paints over the background of the card, such as the channel row.
        tintCardBelowThumbnail(item, tint);
        updateCardSurface(item);

        item.invalidate();
    }

    /**
     * Applies the tint of the card to a surface as a filter, which keeps the shape and the translucency
     * of the surface, and overrides the color that the app sets while it draws.
     */
    private static void applyTintFilter(Drawable drawable, @Nullable Integer tint) {
        drawable.setColorFilter(tint == null ? null : getTintFilter(tint));
    }

    /**
     * @return The filter of a tint, which is shared by the surfaces of the cards of the same color.
     */
    private static PorterDuffColorFilter getTintFilter(int tint) {
        PorterDuffColorFilter filter = tintFilters.get(tint);
        if (filter == null) {
            if (tintFilters.size() >= MAXIMUM_TINT_FILTERS) {
                tintFilters.clear();
            }
            filter = new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN);
            tintFilters.put(tint, filter);
        }
        return filter;
    }

    /**
     * Applies the tint to the surfaces of the card that the app resolved, again before every draw, because
     * the views of a card are mounted again while the list scrolls.
     */
    private static void applyCardBackgroundsTint(View item, int tint) {
        List<WeakReference<Drawable>> backgrounds = cardBackgrounds.get(item);
        if (backgrounds == null) {
            return;
        }

        final PorterDuffColorFilter filter = getTintFilter(tint);
        for (int i = 0, count = backgrounds.size(); i < count; i++) {
            Drawable drawable = backgrounds.get(i).get();
            if (drawable != null && drawable.getColorFilter() != filter) {
                drawable.setColorFilter(filter);
            }
        }
    }

    /**
     * Tints the surfaces of the card below the thumbnail, which the app paints over the background of
     * the card in the color of the theme and hides the tint of the card with, such as the channel row.
     * The row is an ImageView whose image has the gradient of the card, and the image can be shared by
     * the cards, so its filter is set on the view, which keeps the shape of the row. The surfaces are
     * looked up again before every draw, because the views of a card are mounted again while scrolling.
     */
    private static void tintCardBelowThumbnail(View item, @Nullable Integer tint) {
        List<WeakReference<Drawable>> backgrounds = tintedBackgrounds.get(item);
        List<WeakReference<View>> images = tintedImages.get(item);

        if (tint == null) {
            // A card without a tint keeps the colors of the app.
            clearTintFilters(backgrounds);
            clearImageFilters(images);
            return;
        }

        if (item.getHeight() <= 0) {
            return;
        }

        foundBackgrounds.clear();
        foundImages.clear();
        collectCardSurfaces(item, item, 0, getThumbnailBottom(item), foundBackgrounds, foundImages);

        // The surfaces that the card no longer has keep no filter.
        removeTintFilters(backgrounds);
        removeImageFilters(images);

        final PorterDuffColorFilter filter = getTintFilter(tint);
        if (!foundBackgrounds.isEmpty()) {
            if (backgrounds == null) {
                backgrounds = new ArrayList<>(2);
                tintedBackgrounds.put(item, backgrounds);
            }
            for (int i = 0, count = foundBackgrounds.size(); i < count; i++) {
                Drawable drawable = foundBackgrounds.get(i);
                if (drawable.getColorFilter() != filter) {
                    drawable.setColorFilter(filter);
                }
                addTintedDrawable(backgrounds, drawable);
            }
        }

        if (!foundImages.isEmpty()) {
            if (images == null) {
                images = new ArrayList<>(2);
                tintedImages.put(item, images);
            }
            for (int i = 0, count = foundImages.size(); i < count; i++) {
                View image = foundImages.get(i);
                if (image instanceof ImageView imageView && imageView.getColorFilter() != filter) {
                    imageView.setColorFilter(filter);
                }
                addTintedView(images, image);
            }
        }

        // The log tells the state of the surfaces of the card, once per state of a card.
        final String state = (foundImages.isEmpty() ? "no row" : "row tinted")
                + ", backgrounds " + foundBackgrounds.size();
        if (!state.equals(tintStates.put(item, state))) {
            Logger.printDebug(() -> "Card tint: " + state);
        }
    }

    private static void clearTintFilters(@Nullable List<WeakReference<Drawable>> backgrounds) {
        if (backgrounds == null) {
            return;
        }
        for (WeakReference<Drawable> reference : backgrounds) {
            Drawable drawable = reference.get();
            if (drawable != null) {
                drawable.setColorFilter(null);
            }
        }
        backgrounds.clear();
    }

    private static void clearImageFilters(@Nullable List<WeakReference<View>> images) {
        if (images == null) {
            return;
        }
        for (WeakReference<View> reference : images) {
            if (reference.get() instanceof ImageView imageView) {
                imageView.clearColorFilter();
            }
        }
        images.clear();
    }

    /**
     * Removes the filters of the surfaces that the card no longer has, such as the views that a recycled
     * card mounted again.
     */
    private static void removeTintFilters(@Nullable List<WeakReference<Drawable>> backgrounds) {
        if (backgrounds == null) {
            return;
        }
        for (int i = backgrounds.size() - 1; i >= 0; i--) {
            Drawable drawable = backgrounds.get(i).get();
            if (drawable == null || !foundBackgrounds.contains(drawable)) {
                if (drawable != null) {
                    drawable.setColorFilter(null);
                }
                backgrounds.remove(i);
            }
        }
    }

    private static void removeImageFilters(@Nullable List<WeakReference<View>> images) {
        if (images == null) {
            return;
        }
        for (int i = images.size() - 1; i >= 0; i--) {
            View image = images.get(i).get();
            if (image == null || !foundImages.contains(image)) {
                if (image instanceof ImageView imageView) {
                    imageView.clearColorFilter();
                }
                images.remove(i);
            }
        }
    }

    private static void addTintedDrawable(List<WeakReference<Drawable>> tinted, Drawable drawable) {
        for (int i = 0, count = tinted.size(); i < count; i++) {
            if (tinted.get(i).get() == drawable) {
                return;
            }
        }
        if (tinted.size() >= MAXIMUM_TINTED_SURFACES) {
            tinted.remove(0);
        }
        tinted.add(new WeakReference<>(drawable));
    }

    private static void addTintedView(List<WeakReference<View>> tinted, View view) {
        for (int i = 0, count = tinted.size(); i < count; i++) {
            if (tinted.get(i).get() == view) {
                return;
            }
        }
        if (tinted.size() >= MAXIMUM_TINTED_SURFACES) {
            tinted.remove(0);
        }
        tinted.add(new WeakReference<>(view));
    }

    /**
     * Collects the surfaces of the card below the thumbnail that span its width: the backgrounds of its
     * views and the images of its ImageViews, such as the row of the channel.
     */
    private static void collectCardSurfaces(View item, View view, int topInItem, int thumbnailBottom,
                                            List<Drawable> backgrounds, List<View> images) {
        final int width = item.getWidth();
        final int height = item.getHeight();
        final int viewTop = topInItem + view.getTop();
        // A surface of the card is as wide as the card, or the card without the padding of the metadata.
        final boolean spansCard = view.getWidth() * 20 >= width * 17;

        // The surfaces of the thumbnail are above the metadata, and the images thinner than the card are
        // the bars of the progress and the separators.
        if (spansCard && viewTop >= thumbnailBottom && view.getHeight() * 20 >= height) {
            Drawable background = view.getBackground();
            if (background != null && !backgrounds.contains(background)) {
                backgrounds.add(background);
            }

            if (view instanceof ImageView imageView && imageView.getDrawable() != null
                    && !images.contains(view)) {
                images.add(view);
            }
        }

        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                collectCardSurfaces(item, group.getChildAt(i), viewTop, thumbnailBottom, backgrounds, images);
            }
        }
    }

    /**
     * Gives the card a surface of its own, because the app draws the cards of the mixes without any
     * surface of its own, so their metadata would lie on the background of the page.
     */
    private static void updateCardSurface(View item) {
        if (!isCardItem(item) || item.getHeight() <= 0 || hasAppCardSurface(item)) {
            removeCardSurface(item);
            return;
        }

        final CharSequence label = item.getContentDescription();
        if (!TextUtils.equals(cardLabels.put(item, label), label)) {
            // The view shows another card, and the surfaces of the app that were recorded are gone.
            cardBackgrounds.remove(item);
            appCardSurfaces.remove(item);
            removeCardSurface(item);
            return;
        }

        final Integer tint = cardTints.get(item);
        final int color = tint != null ? tint : (darkMode ? CARD_COLOR_DARK : CARD_COLOR_LIGHT);
        CardSurface surface = cardSurfaces.get(item);
        if (surface != null) {
            if (item.getBackground() == surface.drawable) {
                surface.setColor(color);
                return;
            }
            // The view was rebound, and the background of the card was replaced.
            cardSurfaces.remove(item);
        }

        // The clip of the card rounds the surface to the card, with its corners.
        FeedCardLayoutPatch.setCardClip(item);
        surface = new CardSurface(color, item.getBackground());
        cardSurfaces.put(item, surface);
        item.setBackground(surface.drawable);
        if (loggedCardSurfaces.put(item, Boolean.TRUE) == null) {
            Logger.printDebug(() -> "Card background: the card has no surface of the app, "
                    + "so the patch draws the card surface");
        }
    }

    private static boolean hasAppCardSurface(View item) {
        return Boolean.TRUE.equals(appCardSurfaces.get(item));
    }

    /**
     * @return Whether the bounds of a drawable of the card are below its thumbnail, in the metadata,
     *         which the app draws a surface of for the other cards.
     */
    private static boolean isSurfaceBelowThumbnail(View item, @Nullable Rect bounds) {
        return bounds != null && bounds.top >= getThumbnailBottom(item);
    }

    /**
     * @return The bottom of the thumbnail of the card in the card, which is the largest image of the card.
     *         The images of the metadata are in the lower part of the card, so an image that is not in the
     *         upper part is not the thumbnail, such as the row of the channel of a card with a long title.
     *         The cards whose thumbnail is drawn without a view keep the lower third for the metadata.
     */
    private static int getThumbnailBottom(View item) {
        final int height = item.getHeight();
        ImageView thumbnail = findThumbnailView(item, 0);
        if (thumbnail != null) {
            final int top = getTopInItem(thumbnail, item);
            // The thumbnail starts above the metadata, which is the lower part of the card.
            if (top * 4 <= height) {
                return top + thumbnail.getHeight();
            }
        }
        return height * 2 / 3;
    }

    private static void removeCardSurface(View item) {
        CardSurface surface = cardSurfaces.remove(item);
        if (surface != null && item.getBackground() == surface.drawable) {
            item.setBackground(surface.background);
        }
    }

    /**
     * The fill of the surface of a card: a rounded rectangle in the color of the card, inset like the
     * outline of the card, which keeps the corners of the card, also where the view does not clip it.
     */
    private static final class CardFillDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius = FeedCardLayoutPatch.CARD_CORNER_RADIUS;
        private final int horizontalPadding = Dim.dp(FeedCardLayoutPatch.CARD_HORIZONTAL_PADDING_DP);
        private final int verticalPadding = Dim.dp(FeedCardLayoutPatch.CARD_VERTICAL_PADDING_DP);
        private int color;

        CardFillDrawable(int color) {
            setColor(color);
        }

        void setColor(int color) {
            if (this.color != color) {
                this.color = color;
                paint.setColor(color);
                invalidateSelf();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            rect.set(bounds.left + horizontalPadding, bounds.top + verticalPadding,
                    bounds.right - horizontalPadding, bounds.bottom - verticalPadding);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The surface that the patch draws for a card that the app draws without a surface of its own, above
     * the background of the card that the app set, if any.
     */
    private static final class CardSurface {
        private final CardFillDrawable fill;
        private final Drawable background;
        private final Drawable drawable;
        private int color;

        CardSurface(int color, @Nullable Drawable background) {
            this.color = color;
            this.background = background;
            this.fill = new CardFillDrawable(color);
            this.drawable = background == null ? fill : new LayerDrawable(new Drawable[]{fill, background});
        }

        void setColor(int color) {
            if (this.color != color) {
                this.color = color;
                fill.setColor(color);
            }
        }
    }

    private static int getTopInItem(View view, View item) {
        int top = 0;
        View current = view;
        while (current != null && current != item) {
            top += current.getTop();
            ViewParent parent = current.getParent();
            current = parent instanceof View parentView ? parentView : null;
        }
        return top;
    }

    private static void restartBackground(Drawable drawable) {
        Rect bounds = drawable.copyBounds();
        if (bounds.isEmpty()) {
            drawable.invalidateSelf();
            return;
        }

        drawable.setBounds(0, 0, 0, 0);
        drawable.setBounds(bounds);
        drawable.invalidateSelf();
    }

    private static void logCardSurface(View item, View drawableView, Drawable drawable, @Nullable Rect bounds,
                                       int tint) {
        if (surfaceAcceptedLogs >= MAXIMUM_SURFACE_LOGS || loggedSurfaces.containsKey(item)) {
            return;
        }
        surfaceAcceptedLogs++;
        loggedSurfaces.put(item, Boolean.TRUE);

        Logger.printDebug(() -> String.format(Locale.US,
                "Card background: tint #%06X, %s of %s %s in %dx%d",
                tint & 0xFFFFFF, drawable.getClass().getSimpleName(),
                drawableView == item ? "the item" : describeView(drawableView),
                describeBounds(bounds), item.getWidth(), item.getHeight()));
    }

    private static void logTint(View item, float hue, int tint) {
        if (logs >= MAXIMUM_LOGS) {
            return;
        }
        logs++;

        Thumbnail thumbnail = cardThumbnails.get(item);
        ImageView imageView = thumbnail == null ? null : thumbnail.imageView();
        Drawable drawable = imageView == null ? null : imageView.getDrawable();
        Logger.printDebug(() -> String.format(Locale.US,
                "Card color: tint #%06X of hue %.1f, thumbnail %s with %s, background known %b",
                tint & 0xFFFFFF, hue,
                imageView == null ? "none" : imageView.getClass().getSimpleName(),
                drawable == null ? "no drawable" : drawable.getClass().getSimpleName(),
                cardBackgrounds.containsKey(item)));
    }

    private static void logUntinted(View item, Drawable drawable) {
        if (logs >= MAXIMUM_LOGS || loggedCards.containsKey(item)) {
            return;
        }
        logs++;
        loggedCards.put(item, Boolean.TRUE);

        Logger.printDebug(() -> "Card colors: no color in the thumbnail of "
                + drawable.getClass().getSimpleName() + " to tint the card with");
    }

    private static void logNotCard(View item) {
        if (notCardLogs >= MAXIMUM_NOT_CARD_LOGS) {
            return;
        }
        notCardLogs++;

        CharSequence label = item.getContentDescription();
        Logger.printDebug(() -> "Card colors: " + describeView(item) + " is not a card, label="
                + (label == null ? "none" : shorten(label.toString())));
    }

    private static void logMissingThumbnail(View item) {
        if (missingThumbnailLogs >= MAXIMUM_MISSING_THUMBNAIL_LOGS) {
            return;
        }
        missingThumbnailLogs++;

        Logger.printDebug(() -> "Card colors: no thumbnail in " + describeChildren(item));
    }

    private static void logSurfaceRejected(@Nullable View item, @Nullable View drawableView, Drawable drawable,
                                           @Nullable Rect bounds, String reason) {
        if (surfaceLogs >= MAXIMUM_SURFACE_LOGS) {
            return;
        }
        surfaceLogs++;

        Logger.printDebug(() -> "Card colors: " + drawable.getClass().getSimpleName() + " of "
                + (drawableView == null ? "an unknown view" : describeView(drawableView))
                + " " + describeBounds(bounds)
                + (item == null ? "" : " in " + item.getWidth() + "x" + item.getHeight())
                + " is not the card surface: " + reason);
    }

    /**
     * @return The bounds of a drawable relative to the item, such as "(0,567 1008,867)".
     */
    private static String describeBounds(@Nullable Rect bounds) {
        if (bounds == null) {
            return "with unknown bounds";
        }
        return "(" + bounds.left + "," + bounds.top + " " + bounds.right + "," + bounds.bottom + ")";
    }

    private static void logUnreadableDrawable(Drawable drawable, Exception ex) {
        if (!loggedDrawableClasses.add(drawable.getClass())) {
            return;
        }

        Logger.printDebug(() -> "Card colors: cannot sample the thumbnail of "
                + drawable.getClass().getSimpleName() + ": " + ex);
    }

    /**
     * @return The view and the classes of the drawables and the backgrounds of the views inside it,
     *         which are the images that could be the thumbnail of a card.
     */
    private static String describeChildren(View item) {
        StringBuilder description = new StringBuilder(describeView(item));
        appendChildDescriptions(item, 0, description);
        return description.toString();
    }

    private static void appendChildDescriptions(View view, int depth, StringBuilder description) {
        if (depth > 3 || description.length() > 500 || !(view instanceof ViewGroup group)) {
            return;
        }

        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            description.append(", ").append(describeView(child));
            appendChildDescriptions(child, depth + 1, description);
            if (description.length() > 500) {
                return;
            }
        }
    }

    private static String describeView(View view) {
        StringBuilder description = new StringBuilder(view.getClass().getSimpleName())
                .append(' ').append(view.getWidth()).append('x').append(view.getHeight());
        if (view instanceof ImageView imageView && imageView.getDrawable() != null) {
            description.append(' ').append(imageView.getDrawable().getClass().getSimpleName());
        }
        if (view.getBackground() != null) {
            description.append(" on ").append(view.getBackground().getClass().getSimpleName());
        }
        return description.toString();
    }

    private static String shorten(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "...";
    }

    private static void logSlowUpdate(long startTime, int samples) {
        if (samples == 0) {
            return;
        }

        final long duration = System.nanoTime() - startTime;
        if (duration < SLOW_UPDATE_NANOS) {
            return;
        }

        final long now = System.currentTimeMillis();
        if (now - slowUpdateLogTime < SLOW_UPDATE_INTERVAL_MILLIS) {
            return;
        }
        slowUpdateLogTime = now;

        Logger.printDebug(() -> String.format(Locale.US,
                "Card colors: sampled %d thumbnails in %d ms", samples, duration / 1_000_000));
    }

    /**
     * Thumbnail of the card of an item, with its tint.
     */
    private static final class Thumbnail {
        private final WeakReference<ImageView> imageView;
        @Nullable
        private final WeakReference<Drawable> sampledDrawable;
        @Nullable
        private final CharSequence sampledLabel;
        private final long sampledTime;
        private final int attempts;
        private final int tint;

        Thumbnail(ImageView imageView) {
            this(imageView, null, null, 0, 0, NO_TINT);
        }

        private Thumbnail(ImageView imageView, @Nullable Drawable sampledDrawable, @Nullable CharSequence sampledLabel,
                          long sampledTime, int attempts, int tint) {
            this.imageView = new WeakReference<>(imageView);
            this.sampledDrawable = sampledDrawable == null ? null : new WeakReference<>(sampledDrawable);
            this.sampledLabel = sampledLabel;
            this.sampledTime = sampledTime;
            this.attempts = attempts;
            this.tint = tint;
        }

        @Nullable
        ImageView imageView() {
            return imageView.get();
        }

        int tint() {
            return tint;
        }

        /**
         * @return Whether the image is still in the item, which the recycled views are not.
         */
        boolean isAttachedTo(View item) {
            View view = imageView.get();
            while (view != null) {
                if (view == item) {
                    return true;
                }
                view = view.getParent() instanceof View parent ? parent : null;
            }
            return false;
        }

        /**
         * @return Whether the thumbnail must be sampled again, because it's new or it was not readable yet.
         */
        boolean needsSample(Drawable drawable, @Nullable CharSequence label, long now) {
            if (sampledDrawable == null || drawable != sampledDrawable.get()) {
                return true;
            }
            if (!sameLabel(label)) {
                return true;
            }

            // The image can be set after the drawable of the item, such as a placeholder that the image
            // replaces while it loads.
            return tint == NO_TINT && attempts < MAXIMUM_SAMPLE_ATTEMPTS
                    && now - sampledTime >= SAMPLE_RETRY_INTERVAL_MILLIS;
        }

        /**
         * Compares the labels by identity first, which is the same object until the view shows another item,
         * and without copying the label of every card.
         */
        private boolean sameLabel(@Nullable CharSequence label) {
            if (label == sampledLabel) {
                return true;
            }
            if (label == null || sampledLabel == null) {
                return false;
            }
            return TextUtils.equals(label, sampledLabel);
        }

        Thumbnail sampled(Drawable drawable, @Nullable CharSequence label, long now, int tint) {
            ImageView view = imageView.get();
            if (view == null) {
                return this;
            }

            return new Thumbnail(view, drawable, label, now, attempts + 1, tint);
        }
    }
}
