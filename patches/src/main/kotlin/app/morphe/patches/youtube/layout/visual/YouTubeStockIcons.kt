/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-29
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

package app.morphe.patches.youtube.layout.visual

/**
 * RVX icon key to the name inside yt_outline_experimental_<name>_vd_theme_24.
 * Aliases inherit their glyph source's mapping unless their own key overrides it.
 * Missing counterparts deliberately retain the rounded RVX glyph, including Username's @ icon.
 */
internal val youtubeStockIcons = mapOf(
    // RVX root screens.
    "revanced_preference_screen_general" to "adjust",
    "revanced_preference_screen_ads" to "megaphone",
    "revanced_preference_screen_player" to "play_circle",
    "revanced_preference_screen_shorts" to "shorts",
    "revanced_preference_screen_ryd" to "thumb_down",
    "revanced_preference_screen_gemini" to "spark",
    "revanced_preference_screen_swipe_controls" to "gesture_swipe_right",
    "revanced_preference_screen_video" to "play_square_stack",
    "revanced_preference_screen_sb" to "shield",
    "revanced_preference_screen_alt_thumbnails" to "image",
    "revanced_preference_screen_feed" to "view_list",
    "revanced_preference_screen_vot" to "waveform",

    // Shared glyphs with direct counterparts in YouTube's own settings.
    "general_key" to "adjust",
    "account_switcher_key" to "person_square_stack",
    "parent_tools_key" to "person_child",
    "time_management_key" to "chart_bar_square",
    "video_quality_settings_key" to "hd",
    "pair_with_tv_key" to "tv",
    "history_key" to "history",
    "notification_key" to "bell",
    "captions_key" to "closed_captions",
    "accessibility_settings_key" to "accessibility",
    "about_key" to "info_circle",

    // Sub-screen headers with unambiguous counterparts.
    "revanced_preference_screen_settings_menu" to "gear",
    "revanced_preference_screen_vot_google" to "translate",
    "revanced_preference_screen_vot_yandex" to "translate",
)
