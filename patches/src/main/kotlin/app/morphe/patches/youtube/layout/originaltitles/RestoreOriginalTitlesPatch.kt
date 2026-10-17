/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

// modified by lavinhoque33, 2026-10-17: the title hooks moved to videoTitlesHookPatch, which DeArrow titles of 'Alternative thumbnails' also use.

package app.morphe.patches.youtube.layout.originaltitles

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.extension.sharedExtensionPatch
import app.morphe.patches.youtube.utils.patch.PatchList.RESTORE_ORIGINAL_TITLES
import app.morphe.patches.youtube.utils.settings.ResourceUtils.addPreference
import app.morphe.patches.youtube.utils.settings.settingsPatch

@Suppress("unused")
val restoreOriginalTitlesPatch = bytecodePatch(
    name = "Restore original titles",
    description = "Adds an option to show the original video titles and descriptions instead of the auto-translated ones.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        videoTitlesHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPreference(
            arrayOf("PREFERENCE_SCREEN: FEED", "SETTINGS: RESTORE_ORIGINAL_TITLES"),
            RESTORE_ORIGINAL_TITLES,
        )
    }
}
