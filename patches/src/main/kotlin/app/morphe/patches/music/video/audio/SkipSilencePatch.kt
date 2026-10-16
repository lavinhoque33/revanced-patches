/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3467
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * Ported to RVX by lavinhoque33, 2026-10-16.
 */

package app.morphe.patches.music.video.audio

import app.morphe.patches.music.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.utils.patch.PatchList.SKIP_SILENCE
import app.morphe.patches.music.utils.playservice.is_9_00_or_greater
import app.morphe.patches.music.utils.playservice.versionCheckPatch
import app.morphe.patches.music.utils.settings.CategoryType
import app.morphe.patches.music.utils.settings.addSwitchPreference
import app.morphe.patches.music.utils.settings.addListPreference
import app.morphe.patches.music.utils.settings.settingsPatch
import app.morphe.patches.music.utils.extension.sharedExtensionPatch
import app.morphe.patches.shared.misc.audio.silence.skipSilencePatch
import app.morphe.patches.music.utils.settings.ResourceUtils.updatePatchStatus

@Suppress("unused")
val skipSilencePatch = skipSilencePatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            versionCheckPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    },
    targetCompatible = { is_9_00_or_greater },
) {
    addSwitchPreference(
        CategoryType.PLAYER,
        "revanced_skip_silence",
        "false"
    )
    addListPreference(
        CategoryType.PLAYER,
        "revanced_skip_silence_minimum_pause",
        "revanced_skip_silence",
        false
    )
    updatePatchStatus(SKIP_SILENCE)
}
