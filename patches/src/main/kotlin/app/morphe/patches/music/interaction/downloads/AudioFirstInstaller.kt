/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patches.music.interaction.playlisttracktap.CommandResolverFingerprint
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val AUDIO_FIRST = "Lapp/morphe/extension/music/patches/downloads/AudioFirst;"
private const val OVERRIDE_MODE = "$AUDIO_FIRST->overrideMode(Ljava/lang/Enum;)Ljava/lang/Enum;"
private const val ON_COMMAND = "$AUDIO_FIRST->onCommand(Ljava/lang/Object;)V"
private const val OBJECT = "Ljava/lang/Object;"
private const val MAX_SETTER_INSTRUCTIONS = 6

/** The five-value audio/video mode enum, found by the names of its constants. */
private val audioVideoModeEnumFingerprint = Fingerprint(
    name = "<clinit>",
    strings = listOf("ATV_PREFERRED", "OMV_PREFERRED", "DONT_PLAY_VIDEO_OVERRIDE"),
)

/** A tiny setter: it hands its argument to one `(Object)V` call on a subject and does nothing else of note. */
private fun Method.callsOneObjectSubject(): Boolean {
    val instructions = implementation?.instructions?.toList() ?: return false
    if (instructions.size > MAX_SETTER_INSTRUCTIONS) return false

    val calls = instructions.filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_INTERFACE }
        .mapNotNull { it.getReference<MethodReference>() }
    return calls.size == 1 && calls[0].returnType == "V" &&
        calls[0].parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
}

/**
 * Start downloaded songs in audio mode. Stock keeps one global audio/video mode (an enum of five values) in a state
 * holder, and falls back to `OMV_PREFERRED` for an account without Premium when a tap names no mode, so an album row
 * opens in video mode and requests the music video's id. Two hooks:
 *  - every command resolved by a tap is reported, which arms a short flag when its song has a stream copy;
 *  - the holder's setter is routed through the extension, which turns an armed `OMV_PREFERRED` into
 *    `ATV_PREFERRED` and never touches the user-triggered values. It also records the mode for the swap.
 *
 * Resolved by shape, in 9.40.51 / 9.15.51 / 8.30.54 order: enum `Lhzo` / `Lnuu` / `Lngq`; holder `Lgdh` / `Lnuv` /
 * `Lngr`; setter `q` / `e` / `e`. Everything is resolved before anything is changed, and an unfamiliar version only
 * warns: no audio-first start, and the swap then declines in every mode it cannot read.
 */
internal fun BytecodePatchContext.installAudioFirst() {
    try {
        val enumType = audioVideoModeEnumFingerprint.matchSingle().classDef.type
        val setter = Fingerprint(
            returnType = "V",
            parameters = listOf(enumType),
            custom = { method, owner ->
                !AccessFlags.STATIC.isSet(method.accessFlags) && !AccessFlags.ABSTRACT.isSet(method.accessFlags) &&
                    !AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) && owner.type != enumType &&
                    method.callsOneObjectSubject() &&
                    owner.methods.any {
                        it.parameterTypes.isEmpty() && it.returnType == enumType &&
                            !AccessFlags.STATIC.isSet(it.accessFlags)
                    }
            },
        ).matchSingle()
        val commandResolver = CommandResolverFingerprint.method
        val supported = mutableClassDefBy(AUDIO_FIRST).methods.single { it.name == "supported" }

        commandResolver.addInstructions(0, "invoke-static {p1}, $ON_COMMAND")
        mutableClassDefBy(setter.classDef.type).findMutableMethodOf(setter.method).addInstructions(0, """
            invoke-static {p1}, $OVERRIDE_MODE
            move-result-object p1
            check-cast p1, $enumType
        """)
        supported.returnEarly(true)
        printInfo("AudioFirst: mode enum $enumType, holder ${setter.classDef.type}, setter ${setter.method.name}, " +
            "taps ${commandResolver.definingClass}->${commandResolver.name}")
    } catch (ex: Exception) {
        printWarn("Start downloaded songs in audio mode is unavailable on this Music version: ${ex.message}")
    }
}
