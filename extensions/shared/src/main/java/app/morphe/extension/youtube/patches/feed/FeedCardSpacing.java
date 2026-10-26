/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/lavinhoque33/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Additional Terms & Attribution Requirements
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Source Credit Preservation (Section 7(b)): This specific copyright notice
 *    and the list of original authors above must be preserved in any copy
 *    or derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin & Modification Marking (Section 7(c)): Modified versions must be
 *    clearly marked as such (e.g., by adding a "Modified by" line or a new
 *    copyright notice) and must not be misrepresented as the original work.
 *
 * 3. Version Control Attribution (Section 7(b)): Any ports or substantial
 *    modifications must retain historical authorship credit in version control
 *    systems (e.g., Git), listing original author(s) appropriately and
 *    modifiers as committers or co-authors.
 *
 * 4. User Interface Attribution (Section 7(b)): Any works containing or
 *    derived from this material must maintain a visible credit or
 *    acknowledgment to the original author(s) within the application's
 *    user interface (e.g., in an "About" or "Credits" section).
 */

package app.morphe.extension.youtube.patches.feed;

import android.graphics.Rect;
import android.support.v7.widget.RecyclerView;
import android.view.View;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.utils.Logger;

/**
 * Spacing of the items of the feeds, which the patch adds to the spacing of the list to keep the gaps
 * between the cards uniform, and to fill the gaps below the header and the last row of a Shorts shelf.
 *
 * The list sets the spacing of every item from the class of the item, which is looked up before the
 * spacing of the item is set. The patch removes that spacing, and calls {@link #setItemOffsets()}
 * with the item and the rectangle of the spacing of the item, before the lookup.
 */
final class FeedCardSpacing {
    static final int ITEM_UNKNOWN = 0;
    static final int ITEM_CARD = 1;
    static final int ITEM_SHELF_HEADER = 2;
    static final int ITEM_SHELF_ROW = 3;
    static final int ITEM_OTHER = 4;
    static final int ITEM_SPACER = 5;

    /**
     * Kind of the items of the feeds, by the view of the item, which is cleared when the view is reused.
     */
    private static final Map<View, Integer> itemTypes = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Whether the patch calls {@link #setItemOffsets()}, so the spacing of the feed items can be removed.
     */
    private static volatile boolean spacingAvailable;
    /**
     * Spacing of the item that is being set by the list.
     */
    @Nullable
    private static Rect offsetsRect;
    /**
     * View of the item whose spacing is being set by the list.
     */
    private static WeakReference<View> offsetsItemView = new WeakReference<>(null);
    /**
     * Items of the feed that are laid out without a height, which are the spacers of the list. They are not
     * visible, so the spacing of the neighbours is the whole gap.
     */
    private static final Map<View, Boolean> invisibleItems = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Number of items above an item that are looked through for the item that is not a spacer.
     */
    private static final int PREVIOUS_ITEM_SEARCH = 8;
    /**
     * Views that the spacing below the last row of a shelf was logged for, which is not logged repeatedly.
     */
    private static final Map<View, Boolean> shelfEndItems = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Spacing that was logged for every item, to log the spacing of an item only when it changes.
     */
    private static final Map<View, String> spacingOutcomes = Collections.synchronizedMap(new WeakHashMap<>());

    private FeedCardSpacing() {
    }

    /**
     * Called by the patch, which applies the spacing of the items.
     */
    static void markSpacingAvailable() {
        spacingAvailable = true;
    }

    /**
     * @return Whether the spacing of the items of the feed is replaced, so any spacing of the list
     *         that is looked up for a feed item must be removed.
     */
    static boolean isSpacingAvailable() {
        return spacingAvailable;
    }

    /**
     * Saves the item whose spacing the list is about to set, which is passed to {@link #setItemOffsets()}.
     * The view of the item is recycled for another item, so its kind is cleared.
     */
    static void setItemOffsetsInfo(Rect outRect, View item) {
        offsetsRect = outRect;
        offsetsItemView = new WeakReference<>(item);
        itemTypes.remove(item);
    }

    /**
     * Applies the spacing of the item, which the list calls before it looks up the spacing of the item,
     * so the spacing of the item is set from the item if the lookup of the item has no spacing. The spacing
     * that the list sets from the lookup replaces the values.
     */
    static void setItemOffsets() {
        try {
            if (!spacingAvailable || !FeedCardLayoutPatch.isCardLayoutEnabled()) {
                return;
            }

            Rect outRect = offsetsRect;
            View item = offsetsItemView.get();
            offsetsRect = null;
            offsetsItemView.clear();

            if (outRect == null || item == null) {
                return;
            }
            if (!(item.getParent() instanceof RecyclerView list) || list.getId() != FeedCardLayoutPatch.getFeedId()) {
                return;
            }
            // The spacers of the list are not visible, and the spacing of the items around them makes the gap.
            if (invisibleItems.containsKey(item)) {
                return;
            }

            // The item is not in the adapter while it is removed, and its spacing does not matter.
            int position = RecyclerView.patch_getChildAdapterPosition(list, item);
            if (position < 0) {
                return;
            }

            final int gap = FeedCardLayoutPatch.CARD_VERTICAL_SPACING;
            final int type = getItemType(item);
            switch (type) {
                case ITEM_CARD, ITEM_SHELF_ROW, ITEM_UNKNOWN, ITEM_SPACER -> {
                    // The cards and the rows of the shelves space themselves, and the spacers are not visible.
                }
                case ITEM_SHELF_HEADER -> {
                    // The card of the shelf starts at the header, so the header has the whole gap above it.
                    outRect.top += gap;
                }
                default -> {
                    // The item is not a card, and has no spacing of its own.
                    outRect.top += gap;
                    outRect.bottom += gap;
                }
            }

            View above = findItemBefore(list, position);
            final boolean afterRow = above != null && getItemType(above) == ITEM_SHELF_ROW;
            if (type == ITEM_SHELF_ROW) {
                // The rows of a shelf keep their own gap, which is below the gap of the cards.
                if (afterRow) {
                    outRect.top += FeedCardLayoutPatch.SHORTS_ROW_SPACING;
                }
            } else if (afterRow && type != ITEM_SPACER) {
                // The card of the shelf ends at the last Short, and the row keeps an area below it,
                // so the item below the last row gets the rest of the gap.
                final int inset = type == ITEM_CARD ? gap : 0;
                final int trailing = FeedCardLayoutPatch.getShortsShelfTrailing();
                outRect.top = Math.max(-4 * gap, 2 * gap - trailing - inset);
                if (shelfEndItems.put(above, Boolean.TRUE) == null) {
                    final int top = outRect.top;
                    Logger.printDebug(() -> "Spacing below the shelf: " + position + ", " + trailing
                            + " / " + top);
                }
            }

            final int top = outRect.top;
            final int bottom = outRect.bottom;
            final String description = "Item " + position + " (" + getTypeName(type) + ") spacing "
                    + top + " / " + bottom;
            if (!description.equals(spacingOutcomes.put(item, description))) {
                Logger.printDebug(() -> description);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setItemOffsets failure", ex);
        }
    }

    /**
     * Sets the kind of the item, with the element of the item.
     */
    static void setItemType(View item, int type) {
        Integer previous = itemTypes.put(item, type);
        if (previous == null || previous != type) {
            Logger.printDebug(() -> "Feed item spacing: " + getTypeName(type));
        }
    }

    /**
     * @return The kind of the item, from its element, or its label if its element is not known yet.
     */
    private static int getItemType(View item) {
        if (FeedCardLayoutPatch.isChipBar(item)) {
            // The bar of chips keeps the spacing of the list.
            return ITEM_UNKNOWN;
        }

        Integer type = itemTypes.get(item);
        if (type != null) {
            return type;
        }

        // The item has not been laid out with its element yet, so it is found by its label.
        if (FeedCardLayoutPatch.isCardItem(item)) {
            return ITEM_CARD;
        }
        if (FeedCardLayoutPatch.isShortsRow(item) || FeedCardLayoutPatch.isVideoShelfRow(item)) {
            return ITEM_SHELF_ROW;
        }
        if (FeedCardLayoutPatch.isShelfHeader(item)) {
            return ITEM_SHELF_HEADER;
        }
        if (FeedCardLayoutPatch.hasNestedList(item, 0)) {
            return ITEM_CARD;
        }
        return ITEM_UNKNOWN;
    }

    /**
     * Updates the items that are not visible, which the list lays out with no height. The spacing of an item
     * is set before the item is laid out, so the list is laid out again when an item is found.
     */
    static void updateInvisibleItems(RecyclerView list) {
        if (!FeedCardLayoutPatch.isCardLayoutEnabled() || list.getId() != FeedCardLayoutPatch.getFeedId()) {
            return;
        }

        boolean found = false;
        for (int i = 0, count = list.getChildCount(); i < count; i++) {
            View item = list.getChildAt(i);
            if (item.getHeight() == 0 && item.getWidth() > 0
                    && invisibleItems.put(item, Boolean.TRUE) == null) {
                found = true;

                final int position = RecyclerView.patch_getChildAdapterPosition(list, item);
                Logger.printDebug(() -> "Invisible item of the feed: " + position);
            }
        }
        if (found) {
            // The spacing of the item was set before its height was known.
            list.requestLayout();
        }
    }

    /**
     * @return The item above the item with this position that is not a spacer, if it is in the list.
     */
    @Nullable
    private static View findItemBefore(RecyclerView list, int position) {
        for (int p = position - 1, minimum = Math.max(0, position - PREVIOUS_ITEM_SEARCH); p >= minimum; p--) {
            View item = findItemAtPosition(list, p);
            if (item == null) {
                return null;
            }
            if (!invisibleItems.containsKey(item) && getItemType(item) != ITEM_SPACER) {
                return item;
            }
        }
        return null;
    }

    /**
     * @return The item of the list with this position, if it is in the list.
     */
    @Nullable
    private static View findItemAtPosition(RecyclerView list, int position) {
        for (int i = 0, count = list.getChildCount(); i < count; i++) {
            View item = list.getChildAt(i);
            if (RecyclerView.patch_getChildAdapterPosition(list, item) == position) {
                return item;
            }
        }
        return null;
    }

    private static String getTypeName(int type) {
        return switch (type) {
            case ITEM_CARD -> "card";
            case ITEM_SHELF_HEADER -> "shelf header";
            case ITEM_SHELF_ROW -> "shelf row";
            case ITEM_OTHER -> "other";
            case ITEM_SPACER -> "spacer";
            default -> "unknown";
        };
    }
}
