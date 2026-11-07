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
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val AUDIO_FIRST_PATH = "Lapp/morphe/extension/music/patches/downloads/"
private const val AUDIO_FIRST = "${AUDIO_FIRST_PATH}AudioFirst;"
private const val OVERRIDE_MODE = "$AUDIO_FIRST->overrideMode(Ljava/lang/Enum;)Ljava/lang/Enum;"
private const val ON_COMMAND = "$AUDIO_FIRST->onCommand(Ljava/lang/Object;)V"
private const val OVERRIDE_COMMAND = "$AUDIO_FIRST->overrideCommand(Ljava/lang/Object;)Ljava/lang/Object;"
private const val MODE_HOLDER = $$"$${AUDIO_FIRST_PATH}AudioFirst$ModeHolder;"
private const val QUEUE_ITEM = $$"$${AUDIO_FIRST_PATH}AudioFirst$QueueItem;"
private const val ON_QUEUE_ITEM = "$AUDIO_FIRST->onQueueItem(Ljava/lang/Object;)V"
private const val CAPTURE_HOLDER = "$AUDIO_FIRST->captureHolder(Ljava/lang/Object;)V"
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
 *  - every command resolved by a tap is reported, which arms a short flag when its song has a stream copy. The
 *    command hook is installed by [installAudioFirstCommandHook] once every patch has run, see there;
 *  - the holder's setter is routed through the extension, which turns an armed `OMV_PREFERRED` into
 *    `ATV_PREFERRED` and never touches the user-triggered values. It also records the mode for the swap.
 *
 * Resolved by shape, in 9.40.51 / 9.15.51 / 8.30.54 order: enum `Lhzo` / `Lnuu` / `Lngq`; holder `Lgdh` / `Lnuv` /
 * `Lngr`; setter `q` / `e` / `e`. Everything is resolved before anything is changed, and an unfamiliar version only
 * warns: no audio-first start, and the swap then declines in every mode it cannot read.
 */
internal fun BytecodePatchContext.installAudioFirst() {
    audioFirstResolved = false
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

        mutableClassDefBy(setter.classDef.type).findMutableMethodOf(setter.method).addInstructions(0, """
            invoke-static {p0}, $CAPTURE_HOLDER
            invoke-static {p1}, $OVERRIDE_MODE
            move-result-object p1
            check-cast p1, $enumType
        """)
        supported.returnEarly(true)
        audioFirstResolved = true
        installModeHolderAccess(setter.classDef.type, setter.method, enumType)
        installQueueItemSelection(setter.classDef.type, enumType, commandResolver.parameterTypes[0].toString())
        printInfo("AudioFirst: mode enum $enumType, holder ${setter.classDef.type}, setter ${setter.method.name}, " +
            "taps ${commandResolver.definingClass}->${commandResolver.name}")
    } catch (ex: Exception) {
        printWarn("Start downloaded songs in audio mode is unavailable on this Music version: ${ex.message}")
    }
}

/**
 * Lets the extension read the mode and call the app's own setter, so a tap can start the audio mode and not only
 * answer the video fallback later. The holder gets two bridge methods (the setter and its one getter) and every
 * constructor reports the new instance. Kept apart: if the holder cannot be bridged, only this part is missing.
 */
private fun BytecodePatchContext.installModeHolderAccess(holderType: String, setter: Method, enumType: String) {
    try {
        val holder = mutableClassDefBy(holderType)
        val getters = holder.methods.filter {
            it.parameterTypes.isEmpty() && it.returnType == enumType && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val getter = getters.singleOrNull() ?: throw IllegalStateException("${getters.size} mode getters")

        holder.interfaces.add(MODE_HOLDER)
        holder.addDownloadsBridge("rvxMode", emptyList(), OBJECT, 2, """
            invoke-virtual {p0}, $holderType->${getter.name}()$enumType
            move-result-object v0
            return-object v0
        """)
        holder.addDownloadsBridge("rvxSetMode", listOf(OBJECT), "V", 2, """
            check-cast p1, $enumType
            invoke-virtual {p0, p1}, $holderType->${setter.name}($enumType)V
            return-void
        """)

        // The setter reports the instances that set a mode. A single constructor is the holder itself and also reports
        // it, so the first tap after a start finds it; a merged class with many constructors is not hooked there.
        val constructors = holder.methods.filter { it.name == "<init>" }.takeIf { it.size == 1 }.orEmpty()
        for (constructor in constructors) {
            val returns = constructor.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            for (index in returns.reversed()) {
                constructor.addInstructions(index, "invoke-static {p0}, $CAPTURE_HOLDER")
            }
        }
        printInfo("AudioFirst: mode holder bridged, getter ${getter.name}, constructor hooks ${constructors.size}")
    } catch (ex: Exception) {
        printWarn("Start downloaded songs in audio mode cannot start the audio mode from the tap on this Music " +
            "version, it only answers the app's video fallback: ${ex.message}")
    }
}

/** The queue item class, found by the log string of its constructor's renderer set-up. */
private val queueItemFingerprint = Fingerprint(
    strings = listOf("Counterpart renderer absent in response. "),
)

/**
 * A queue item holds the renderer of a track's video and of its song, selects the one the server listed first and only
 * changes it when the mode is switched. In an audio mode the next items of an album therefore kept their video ids,
 * which have no stream copy. The item gets three bridge methods (its holder, the command of a mode's renderer, and
 * selecting a mode's renderer: the app's own `w(mode)`), and its renderer set-up reports the finished item to the
 * extension, which selects the song renderer when that song is downloaded and the mode is an audio one.
 *
 * Resolved by shape on 9.40.51 / 9.15.51 / 8.30.54: item `Lhxq` / `Lnpa` / `Lnav`, set-up `z()` / `z()` / `v()`,
 * renderer picker `v(mode)` / `v(mode)` / `u(mode)`, selector `w(mode)`, renderer command field `l` / `l` / `k`.
 * Kept apart: if any part is missing only this selection is, with a warning.
 */
private fun BytecodePatchContext.installQueueItemSelection(holderType: String, enumType: String, commandType: String) {
    try {
        val match = queueItemFingerprint.matchSingle()
        val itemType = match.classDef.type
        val item = mutableClassDefBy(itemType)

        fun Method.isInstance() = !AccessFlags.STATIC.isSet(accessFlags) && !AccessFlags.ABSTRACT.isSet(accessFlags)
        fun Method.takes(vararg types: String) = parameterTypes.map(CharSequence::toString) == types.toList()

        // The renderer is the type of the four fields the item keeps its renderers in (primary, current, song, video).
        val rendererType = item.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            .groupBy { it.type }.filter { it.value.size >= 4 }.keys.single()
        val picker = item.methods.single { it.isInstance() && it.takes(enumType) && it.returnType == rendererType }
        val selector = item.methods.single { it.isInstance() && it.takes(enumType) && it.returnType == "V" }
        val holderField = item.fields.single { it.type == holderType && !AccessFlags.STATIC.isSet(it.accessFlags) }
        val commandField = item.methods.filter { it.isInstance() && it.takes(rendererType) && it.returnType != "V" }
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { it.getReference<FieldReference>() }
            .filter { it.definingClass == rendererType && it.type == commandType }
            .map { it.name }.distinct().single()

        val builder = item.findMutableMethodOf(match.method)
        val returns = builder.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
        if (returns.isEmpty()) throw IllegalStateException("the renderer set-up has no return")

        item.interfaces.add(QUEUE_ITEM)
        item.addDownloadsBridge("rvxHolder", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $itemType->${holderField.name}:$holderType
            return-object v0
        """)
        item.addDownloadsBridge("rvxCommand", listOf(OBJECT), OBJECT, 3, """
            check-cast p1, $enumType
            invoke-virtual {p0, p1}, $itemType->${picker.name}($enumType)$rendererType
            move-result-object v0
            iget-object v0, v0, $rendererType->$commandField:$commandType
            return-object v0
        """)
        item.addDownloadsBridge("rvxSelect", listOf(OBJECT), "V", 2, """
            check-cast p1, $enumType
            invoke-virtual {p0, p1}, $itemType->${selector.name}($enumType)V
            return-void
        """)
        for (index in returns.reversed()) {
            builder.addInstructions(index, "invoke-static {p0}, $ON_QUEUE_ITEM")
        }
        printInfo("AudioFirst: queue item $itemType, set-up ${builder.name}, picker ${picker.name}, " +
            "selector ${selector.name}, command field $commandField")
    } catch (ex: Exception) {
        printWarn("Start downloaded songs in audio mode cannot keep the next songs of an album on their song " +
            "renderers on this Music version: ${ex.message}")
    }
}

/**
 * The tap hook of [installAudioFirst]. It runs after every patch, because the playlist track tap patch hooks the very
 * same method at its start: installed last, this one runs FIRST whichever patch executed first, so the start is armed
 * (and a row of a downloaded album retargeted) on the command the app built, with its playlist, before the track tap
 * action turns it into a single-track or queue command. Each hook then continues with the command the previous returned.
 *
 * When the command class cannot rebuild itself from bytes, only the arming is installed, with a warning.
 */
internal fun BytecodePatchContext.installAudioFirstCommandHook() {
    if (!audioFirstResolved) return

    try {
        val commandResolver = CommandResolverFingerprint.method
        val commandType = commandResolver.parameterTypes[0].toString()
        val rebuildable = mutableClassDefBy(commandType).interfaces.contains(NATIVE_COMMAND)

        if (rebuildable) {
            commandResolver.addInstructions(0, """
                invoke-static {p1}, $OVERRIDE_COMMAND
                move-result-object p1
                check-cast p1, $commandType
            """)
        } else {
            commandResolver.addInstructions(0, "invoke-static {p1}, $ON_COMMAND")
            printWarn("Start downloaded songs in audio mode cannot retarget an album row on this Music version: " +
                "$commandType cannot be rebuilt from bytes")
        }
        printInfo("AudioFirst: tap hook ${if (rebuildable) "rewrites" else "arms"} $commandType")
    } catch (ex: Exception) {
        printWarn("Start downloaded songs in audio mode lost its tap hook on this Music version: ${ex.message}")
    }
}

private var audioFirstResolved = false
