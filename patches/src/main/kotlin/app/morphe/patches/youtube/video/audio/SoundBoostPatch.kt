/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3412
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * Ported to RVX by lavinhoque33, 2026-10-16.
 */

package app.morphe.patches.youtube.video.audio

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.utils.extension.sharedExtensionPatch
import app.morphe.patches.youtube.utils.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/SoundBoostPatch;"

/**
 * Adds the session id hook of the volume boost. Applied as a dependency of the swipe controls patch,
 * because the boost is only reachable through the swipe controls or the volume keys.
 */
val soundBoostPatch = bytecodePatch(
    description = "soundBoostPatch"
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        AudioTrackSessionIdFingerprint.let {
            it.method.apply {
                val resultIndex = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(resultIndex).registerA

                addInstruction(
                    resultIndex + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->onAudioSessionId(I)V"
                )
            }
        }
    }
}
