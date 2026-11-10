/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val TRACK_SELECTION_LOG = "Lapp/morphe/extension/music/patches/downloads/TrackSelectionLog;"
private const val ON_FILTER = "$TRACK_SELECTION_LOG->onFilter(Ljava/lang/Object;Ljava/util/Collection;" +
    "Ljava/util/Collection;Ljava/lang/Object;Ljava/util/Set;Ljava/util/Set;)V"
private const val ON_FILTERED = "$TRACK_SELECTION_LOG->onFiltered(Ljava/lang/Object;)V"

/**
 * The one method of the app's player that filters the formats of a response: by mime type, the itags its decision
 * tree allows, the codec sets, the viewport and the quality limits. It reports a failure of its viewport check under
 * a string of its own, which finds it in every version (`Lyjx.a` in 9.40.51, `Latoi.b` in 9.15.51, `Laqmi.b` in
 * 8.30.54): an instance method of 13 parameters, the first the configuration, then the video and the audio formats,
 * the quality limits and the allowed video and audio itags.
 */
private val formatFilterFingerprint = Fingerprint(strings = listOf("c.supportedViewport"))

/**
 * DEBUG AID: the extension logs what the filter was given and what it left ([app.morphe.extension.music.patches
 * .downloads.TrackSelectionLog]), so a device log says why the app selects no video track for a video copy offline.
 * Only reads: the start passes registers p1 to p6, every return passes the register it returns. Optional: a version
 * where the filter is not found only has no such log.
 */
internal fun BytecodePatchContext.installTrackSelectionLog() {
    try {
        val match = formatFilterFingerprint.matchSingle()
        val method = mutableClassDefBy(match.classDef.type).findMutableMethodOf(match.method)
        check(method.parameterTypes.size == 13 && method.returnType != "V") { "unexpected shape of ${method.name}" }

        val returns = method.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_OBJECT }
            .map { it.index to method.registerName((it.value as OneRegisterInstruction).registerA) }
        check(returns.isNotEmpty()) { "${method.name} returns nothing" }

        returns.asReversed().forEach { (index, register) ->
            method.addInstructions(index, "invoke-static/range {$register .. $register}, $ON_FILTERED")
        }
        method.addInstructions(0, "invoke-static/range {p1 .. p6}, $ON_FILTER")

        printInfo("TrackSelectionLog: format filter ${match.classDef.type}->${match.method.name}, " +
            "${returns.size} returns")
    } catch (ex: Exception) {
        printWarn("The track selection log of the Music player is unavailable on this Music version: ${ex.message}")
    }
}
