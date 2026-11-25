/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-25
 */
package app.morphe.patches.music.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.music.utils.extension.Constants.UTILS_PATH
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val STORE = "Lcom/google/android/libraries/elements/interfaces/ThemeStore;"
private const val CLIENT_INFO = "Lcom/google/protos/youtube/api/innertube/InnertubeContext\$ClientInfo;"

private object MusicThemeStoreFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = STORE,
    filters = listOf(
        methodCall(returnType = CLIENT_INFO),
        opcode(Opcode.INT_TO_LONG),
        methodCall(definingClass = STORE, name = "create", returnType = STORE),
    ),
    custom = { method, _ -> method.parameterTypes.size == 2 },
)

internal fun BytecodePatchContext.installLightMusicElementsHook() {
    val match = MusicThemeStoreFingerprint.match()
    val method = match.method
    val widen = match.instructionMatches[1].index
    val theme = method.getInstruction<TwoRegisterInstruction>(widen).registerB

    // Default-keyed server components follow this theme. Explicit component/overlay theme keys
    // and the optional second argument to ThemeStore.create(JZ) retain their original meaning.
    method.addInstructions(
        widen,
        """
            invoke-static/range { v$theme .. v$theme }, $UTILS_PATH/MusicLightTheme;->selectElementsTheme(I)I
            move-result v$theme
        """,
    )
}
