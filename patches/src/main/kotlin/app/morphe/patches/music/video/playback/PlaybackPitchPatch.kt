/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3295
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

// modified by lavinhoque33, 2026-10-17: port of Morphe's music "Change pitch with speed" ExoPlayer hook.
package app.morphe.patches.music.video.playback

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.utils.extension.Constants.VIDEO_PATH
import app.morphe.patches.music.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_PLAYBACK_SPEED_CLASS_DESCRIPTOR =
    "$VIDEO_PATH/PlaybackSpeedPatch;"

/**
 * Matches method {androidx.media3.common.PlaybackParameters}.toString()
 */
private object MusicPlaybackParametersToStringFingerprint : Fingerprint(
    name = "toString",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(definingClass = "this", opcode = Opcode.IGET, type = "F"),
        fieldAccess(opcode = Opcode.IGET, type = "F")
    ),
    strings = listOf(
        "PlaybackParameters(speed=%.2f, pitch=%.2f)"
    )
)

/**
 * Matches method {androidx.media3.exoplayer.ExoPlayerImpl.setPlaybackParameters(PlaybackParameters p1)}
 */
private fun getMusicPlaybackParametersSetterFingerprint(playbackParametersType: String) =
    object : Fingerprint(
        accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
        returnType = "V",
        parameters = listOf(playbackParametersType),
        custom = { methodDef, classDef ->
            methodDef.implementation != null
                    && classDef.interfaces.contains("Landroidx/media3/exoplayer/ExoPlayer;")
        }
    ) {}

/**
 * Overrides the pitch of every PlaybackParameters passed to ExoPlayer.setPlaybackParameters
 * (speed changes from the speed menu, remembered speed and new tracks).
 * The extension decides whether pitch follows speed.
 */
internal val playbackPitchPatch = bytecodePatch(
    description = "Hooks ExoPlayer playback parameters to optionally change pitch with speed."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val playbackParametersType = MusicPlaybackParametersToStringFingerprint.classDef.type

        // The PlaybackParameters primary constructor with 2 arguments (speed, pitch).
        val playbackParametersConstructorReference = "$playbackParametersType-><init>(FF)V"

        // The toString() method reads the speed field before the pitch field.
        val speedAndPitchFields = MusicPlaybackParametersToStringFingerprint.instructionMatches.let {
            it.first().instruction.let { i -> (i as ReferenceInstruction).reference as FieldReference } to
                    it.last().instruction.let { i -> (i as ReferenceInstruction).reference as FieldReference }
        }
        val speedField = speedAndPitchFields.first
        val pitchField = speedAndPitchFields.second

        // Need to construct a new PlaybackParameters instance as it has final fields.
        getMusicPlaybackParametersSetterFingerprint(playbackParametersType).method.addInstructions(
            0,
            """
                iget v0, p1, $speedField
                iget v1, p1, $pitchField
                invoke-static { v0, v1 }, $EXTENSION_PLAYBACK_SPEED_CLASS_DESCRIPTOR->overridePlaybackPitch(FF)F
                move-result v1
                new-instance p1, $playbackParametersType
                invoke-direct { p1, v0, v1 }, $playbackParametersConstructorReference
            """
        )
    }
}
