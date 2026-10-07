/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-06.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.utils.extension.Constants.MISC_PATH
import app.morphe.patches.music.utils.patch.PatchList.ANDROID_AUTO_PLAYLISTS
import app.morphe.patches.music.utils.settings.ResourceUtils.updatePatchStatus
import app.morphe.patches.music.utils.settings.settingsPatch
import app.morphe.patches.shared.extension.Constants.EXTENSION_PATH
import app.morphe.patches.shared.misc.request.buildRequestPatch
import app.morphe.patches.shared.misc.request.hookBuildRequest

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$MISC_PATH/AndroidAutoPlaylistsPatch;"

private const val EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR =
    "$EXTENSION_PATH/innertube/utils/AuthUtils;"

/**
 * The Android Auto media browser tree that the server sends contains a "Library → Playlists" node
 * without children. This patch answers that node (and the playlists inside it) from the extension.
 */
@Suppress("unused")
val androidAutoPlaylistsPatch = bytecodePatch(
    ANDROID_AUTO_PLAYLISTS.title,
    ANDROID_AUTO_PLAYLISTS.summary,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(
        settingsPatch,
        buildRequestPatch,
    )

    execute {
        // The extension sends its browse requests with the app's own login headers.
        hookBuildRequest(
            "$EXTENSION_AUTH_UTILS_CLASS_DESCRIPTOR->setRequestHeaders(Ljava/lang/String;Ljava/util/Map;)V"
        )

        val sendResultMethod = MediaBrowserResultSendResultFingerprint.method
        val resultClass = sendResultMethod.definingClass
        val sendResultReference = "$resultClass->${sendResultMethod.name}(Ljava/lang/Object;)V"
        val detachReference = "$resultClass->${MediaBrowserResultDetachFingerprint.method.name}()V"

        // Remember the media id of the app's "Playlists" node.
        sendResultMethod.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->onSendResult(Ljava/lang/Object;)V"
        )

        // Let the extension send results through the obfuscated Result class.
        mutableClassDefBy { it.type == EXTENSION_CLASS_DESCRIPTOR }.methods
            .single { it.name == "sendResult" }
            .addInstructions(
                0,
                """
                    check-cast p0, $resultClass
                    invoke-virtual/range { p0 .. p1 }, $sendResultReference
                    return-void
                """
            )

        // Answer the "Playlists" node and the playlists inside it.
        MusicBrowserServiceOnLoadChildrenFingerprint.method.apply {
            if (parameterTypes[1].toString() != resultClass) {
                throw PatchException("onLoadChildren takes ${parameterTypes[1]}, expected $resultClass")
            }
            requireFreeRegisters(3)

            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->shouldLoadChildren(Ljava/lang/String;)Z
                    move-result v1
                    if-eqz v1, :original
                    move-object/from16 v1, p2
                    invoke-virtual { v1 }, $detachReference
                    move-object/from16 v2, p0
                    invoke-static { v2, v0, v1 }, $EXTENSION_CLASS_DESCRIPTOR->loadChildren(Landroid/content/Context;Ljava/lang/String;Ljava/lang/Object;)V
                    return-void
                """,
                ExternalLabel("original", getInstruction(0))
            )
        }

        // Play the tracks of these playlists through the app's own play-from-link path.
        val playFromUriMethod = MediaSessionOnPlayFromUriFingerprint.method
        val playFromUriReference =
            "${playFromUriMethod.definingClass}->${playFromUriMethod.name}(Landroid/net/Uri;Landroid/os/Bundle;)V"

        MediaSessionOnPlayFromMediaIdFingerprint.method.apply {
            requireFreeRegisters(3)

            addInstructionsWithLabels(
                0,
                """
                    move-object/from16 v0, p1
                    invoke-static { v0 }, $EXTENSION_CLASS_DESCRIPTOR->getPlaybackUri(Ljava/lang/String;)Landroid/net/Uri;
                    move-result-object v0
                    if-eqz v0, :original
                    move-object/from16 v1, p0
                    move-object/from16 v2, p2
                    invoke-virtual { v1, v0, v2 }, $playFromUriReference
                    return-void
                """,
                ExternalLabel("original", getInstruction(0))
            )
        }

        updatePatchStatus(ANDROID_AUTO_PLAYLISTS)
    }
}

/** The hooks above use v0..v(count-1) at the start of the method, before any local is written. */
private fun MutableMethod.requireFreeRegisters(count: Int) {
    val implementation = implementation!!
    val parameterRegisters = parameterTypes.sumOf { type ->
        if (type.toString() == "J" || type.toString() == "D") 2L else 1L
    }.toInt() + 1
    val locals = implementation.registerCount - parameterRegisters
    if (locals < count) {
        throw PatchException("$definingClass->$name has $locals locals, $count needed")
    }
}
