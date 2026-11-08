/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.interaction.playlisttracktap.CommandResolverFingerprint
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val AUDIO_FIRST_PATH = "Lapp/morphe/extension/music/patches/downloads/"
private const val AUDIO_FIRST = "${AUDIO_FIRST_PATH}AudioFirst;"
private const val OVERRIDE_MODE = "$AUDIO_FIRST->overrideMode(Ljava/lang/Enum;)Ljava/lang/Enum;"
private const val ON_COMMAND = "$AUDIO_FIRST->onCommand(Ljava/lang/Object;)V"
private const val OVERRIDE_COMMAND = "$AUDIO_FIRST->overrideCommand(Ljava/lang/Object;)Ljava/lang/Object;"
private const val MODE_HOLDER = $$"$${AUDIO_FIRST_PATH}AudioFirst$ModeHolder;"
private const val QUEUE_ITEMS = "${AUDIO_FIRST_PATH}QueueItems;"
private const val QUEUE_ITEM = $$"$${AUDIO_FIRST_PATH}QueueItems$Item;"
private const val QUEUE_RENDERER = $$"$${AUDIO_FIRST_PATH}QueueItems$Renderer;"
private const val ON_QUEUE_ITEM = "$QUEUE_ITEMS->onQueueItem(Ljava/lang/Object;)V"
private const val PILL = $$"$${AUDIO_FIRST_PATH}QueueItems$Pill;"
private const val ON_PILL_PRESS = "$QUEUE_ITEMS->onPillPress(Ljava/lang/Object;)Z"
private const val PILL_ITEM = "$QUEUE_ITEMS->pillItem(Ljava/lang/Object;)Ljava/lang/Object;"
private const val REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"
private const val CAPTURE_HOLDER = "$AUDIO_FIRST->captureHolder(Ljava/lang/Object;)V"
private const val OBJECT = "Ljava/lang/Object;"
private const val MAX_SETTER_INSTRUCTIONS = 6
private const val LEADING_HOOK_LIMIT = 8

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
    trackedItemType = null
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
        installQueueItemRetarget()
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

/** The queue item built from a response wrapper, found by the log string of its set-up, to name the renderer. */
private val wrapperItemFingerprint = Fingerprint(
    strings = listOf("Counterpart renderer absent in response. "),
)

/**
 * The queue item for a single panel renderer: a constructor of a long (or a Long), the renderer and a flag, a field of
 * each, and nothing else.
 */
private fun singleRendererItemFingerprint(rendererType: String) = Fingerprint(
    name = "<init>",
    custom = { method, owner ->
        val types = method.parameterTypes.map(CharSequence::toString)
        types.size == 3 && types[1] == rendererType && types[2] == "Z" &&
            (types[0] == "J" || types[0] == "Ljava/lang/Long;") && owner.fields.count() == 3 &&
            owner.fields.count { it.type == rendererType && !AccessFlags.STATIC.isSet(it.accessFlags) } == 1
    },
)

/**
 * The toggle's item listener: `instance-of <single item>`, negated and stored in a boolean (true for an item with a
 * counterpart), as its first three instructions.
 */
private fun pillItemListenerFingerprint(itemType: String) = Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val opcodes = method.implementation?.instructions?.take(3)?.map { it.opcode }
        method.parameterTypes.size == 1 &&
            opcodes == listOf(Opcode.INSTANCE_OF, Opcode.XOR_INT_LIT8, Opcode.IPUT_BOOLEAN) &&
            method.implementation!!.instructions.first().getReference<TypeReference>()?.type == itemType
    },
)

/**
 * The next songs of an album are queue items that hold only the music video's renderer, so in the audio mode Next
 * requested the video's id, which has no stream copy. The item keeps that renderer and a copy rebuilt around the song's
 * id, and shows the one the mode asks for, so the Song/Video pill still reaches the video: the item reports itself to
 * the extension when it is built or given another renderer, and the extension swaps the renderer (through the item's
 * two bridge methods, one reading its renderer field and one writing it, which is all its own replacing method does)
 * on every mode change. The renderer class gets one bridge, `rvxWithBytes`, which parses a renderer from bytes the way
 * the tap command's bridge does. The toggle enables itself for an item with a counterpart or a response with video,
 * and a song's response has neither, so its item listener is told the item has a counterpart, by handing the
 * `instance-of` test an object that is not the item.
 *
 * Resolved by shape, 9.40.51 / 9.15.51 / 8.30.54: item `Lhxp` / `Lnox` / `Lnas`, renderer `Laopp` / `Lbxxg` / `Lbsez`,
 * replacing method `u` / `u` / `t`, toggle listener `Lhvv.d` / `Lnkv.d` / `Lmxb.c`. Kept apart: if any part is missing
 * only this selection is, with a warning.
 */
private fun BytecodePatchContext.installQueueItemRetarget() {
    try {
        val wrapperItem = wrapperItemFingerprint.matchSingle().classDef
        // The renderer is the type of the four fields the wrapper item keeps its renderers in.
        val rendererType = wrapperItem.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            .groupBy { it.type }.filter { it.value.size >= 4 }.keys.single()

        val itemMatch = singleRendererItemFingerprint(rendererType).matchSingle()
        val itemType = itemMatch.classDef.type
        val item = mutableClassDefBy(itemType)
        val replacer = item.methods.single {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(rendererType)
        }
        val rendererField = item.fields.single { it.type == rendererType && !AccessFlags.STATIC.isSet(it.accessFlags) }
        val pill = pillItemListenerFingerprint(itemType).matchSingle()

        val renderer = mutableClassDefBy(rendererType)
        val defaultRenderer = renderer.fields.single {
            it.type == rendererType && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val parserOwner = parseByteArrayMethodRef.get()!!.definingClass
        val parser = mutableClassDefBy(parserOwner).methods.single {
            it.name == "parseFrom" && it.parameterTypes == listOf(parserOwner, "[B", REGISTRY)
        }

        renderer.interfaces.add(QUEUE_RENDERER)
        renderer.addDownloadsBridge("rvxWithBytes", listOf("[B"), OBJECT, 4, """
            sget-object v0, $defaultRenderer
            invoke-static {}, $REGISTRY->getGeneratedRegistry()$REGISTRY
            move-result-object v1
            invoke-static {v0, p1, v1}, $parser
            move-result-object v0
            return-object v0
        """)

        item.interfaces.add(QUEUE_ITEM)
        item.addDownloadsBridge("rvxRenderer", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $itemType->${rendererField.name}:$rendererType
            return-object v0
        """)
        item.addDownloadsBridge("rvxReplace", listOf(OBJECT), "V", 2, """
            check-cast p1, $rendererType
            iput-object p1, p0, $itemType->${rendererField.name}:$rendererType
            return-void
        """)

        // The extension tracks the item once it holds its renderer: before every return of both methods.
        for (method in listOf(itemMatch.method, replacer)) {
            val builder = item.findMutableMethodOf(method)
            builder.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }.reversed()
                .forEach { builder.addInstructions(it, "invoke-static {p0}, $ON_QUEUE_ITEM") }
        }

        val listener = mutableClassDefBy(pill.classDef.type).findMutableMethodOf(pill.method)
        val tested = listener.implementation!!.instructions.first() as TwoRegisterInstruction
        val firstParameter = listener.implementation!!.registerCount - listener.parameterTypes.size - 1
        val register = "p${tested.registerB - firstParameter}"
        listener.addInstructions(0, "invoke-static {$register}, $PILL_ITEM\nmove-result-object $register")

        printInfo("AudioFirst: queue item tracking $itemType, renderer $rendererType, replacer ${replacer.name}, " +
            "toggle listener ${pill.classDef.type}->${pill.method.name}")
        trackedItemType = itemType
    } catch (ex: Exception) {
        printWarn("Start downloaded songs in audio mode cannot keep the next songs of an album on their song " +
            "renderers on this Music version: ${ex.message}")
    }
}

/** The method of the toggle's owner that persists the user's choice: it is what a press of the pill runs. */
private val pillPressFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("Failed to update user last selected audio"),
)

/**
 * The pill's own press does nothing for an album item: the app changes the mode, and then reloads only a queue item
 * that has a counterpart, or, when its flag for it is off, follows the switch endpoint of the player response, which an
 * audio-only response of a song has not. So the press is taken over for a tracked item: the toggle's press method asks
 * the extension first, which changes the mode (the items then show the renderer of that mode) and has the toggle's
 * owner navigate to the item's own watch endpoint, as the app does for a tap on the row. The owner gets one bridge for
 * the navigation, through the executor its press method already navigates with: an interface method of the endpoint
 * alone (9.40.51, 9.15.51), or one of the endpoint, a context object and a null pair (8.30.54). The endpoint type is
 * the one the owner's one-argument calls of that kind take; the item gives the endpoint in a field of its watch
 * reference, the object its hash code is made of.
 *
 * Resolved by shape: toggle press method `Lhvr.d` (9.40.51) / `Lnjz.e` (9.15.51) / `Lmwg.d` (8.30.54). Only this step
 * is skipped, with a warning, when anything is missing. It is installed once every patch has run, like the tap hook,
 * after the hook another patch (crossfade) puts at the start of the same method.
 */
internal fun BytecodePatchContext.installPillPress() {
    val itemType = trackedItemType ?: return

    try {
        val item = classDefBy(itemType)
        val press = pillPressFingerprint.matchSingle()
        val owner = mutableClassDefBy(press.classDef.type)

        // The endpoint is what the owner's one-argument calls take. The item gives it by its watch reference: the
        // object its hash code is made of, which holds the endpoint in a field of its own.
        val endpointTypes = owner.methods.flatMap { it.implementation?.instructions.orEmpty() }
            .filter { it.opcode == Opcode.INVOKE_INTERFACE }.mapNotNull { it.getReference<MethodReference>() }
            .filter { it.returnType == "V" && it.parameterTypes.size == 1 }
            .map { it.parameterTypes.single().toString() }.distinct()
        val hashed = item.methods.single { it.name == "hashCode" && it.parameterTypes.isEmpty() }
            .implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
            .filter { it.definingClass == item.type && it.parameterTypes.isEmpty() }.map { it.name }.toSet()
        val holding = item.methods.filter { it.name in hashed }.mapNotNull { method ->
            val result = runCatching { classDefBy(method.returnType) }.getOrNull() ?: return@mapNotNull null
            val field = result.fields.singleOrNull {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.type in endpointTypes
            } ?: return@mapNotNull null
            method to field
        }.single()
        val referenceMethod = holding.first
        val endpointType = holding.second.type

        val calls = press.method.implementation!!.instructions
            .filter { it.opcode == Opcode.INVOKE_INTERFACE }.mapNotNull { it.getReference<MethodReference>() }
            .filter { it.returnType == "V" }.distinct()
        val single = calls.singleOrNull { it.parameterTypes.map(CharSequence::toString) == listOf(endpointType) }
        val triple = calls.singleOrNull {
            val types = it.parameterTypes.map(CharSequence::toString)
            types.size == 3 && types[0] == OBJECT && types[2] == "Landroid/util/Pair;"
        }
        val navigation = single ?: triple ?: error("no navigation call")
        val navigator = owner.fields.single {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == navigation.definingClass
        }

        val endpoint = "iget-object v0, v0, ${referenceMethod.returnType}->${holding.second.name}:$endpointType"
        val navigate = if (single != null) {
            "invoke-interface {v1, v0}, ${navigation.definingClass}->${navigation.name}($endpointType)V"
        } else {
            val context = owner.fields.single {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == navigation.parameterTypes[1].toString()
            }
            val signature = "$OBJECT${context.type}Landroid/util/Pair;"
            """
            iget-object v2, p0, ${owner.type}->${context.name}:${context.type}
            const/4 v3, 0x0
            invoke-interface {v1, v0, v2, v3}, ${navigation.definingClass}->${navigation.name}($signature)V
            """
        }

        owner.interfaces.add(PILL)
        owner.addDownloadsBridge("rvxNavigate", listOf(OBJECT), "V", 6, """
            check-cast p1, ${item.type}
            invoke-virtual {p1}, ${item.type}->${referenceMethod.name}()${referenceMethod.returnType}
            move-result-object v0
            if-eqz v0, :done
            $endpoint
            iget-object v1, p0, ${owner.type}->${navigator.name}:${navigator.type}
            $navigate
            :done
            return-void
        """)
        // The hook another patch (crossfade) puts at the start of this method ends in a nop its branch lands on: this
        // one goes after that nop, and its branch lands on the first instruction the app's own code starts with. The
        // target is an existing instruction: a label written into the inserted text lost its place when the method
        // already had a hook of that kind.
        val pressMethod = mutableClassDefBy(press.classDef.type).findMutableMethodOf(press.method)
        val leading = pressMethod.implementation!!.instructions.take(LEADING_HOOK_LIMIT)
        val after = if (leading.firstOrNull()?.opcode == Opcode.INVOKE_STATIC) leading.indexOfFirst {
            it.opcode == Opcode.NOP
        } + 1 else 0
        pressMethod.addInstructionsWithLabels(after, """
            invoke-static {p0}, $ON_PILL_PRESS
            move-result v0
            if-eqz v0, :rvx_stock
            return-void
        """, ExternalLabel("rvx_stock", pressMethod.getInstruction(after)))

        printInfo("AudioFirst: pill press ${press.classDef.type}->${press.method.name}, navigates with " +
            "${navigation.definingClass}->${navigation.name}, item reference ${referenceMethod.name}")
    } catch (ex: Exception) {
        printWarn("Start downloaded songs in audio mode cannot take over the song/video pill for the next songs " +
            "of an album on this Music version: ${ex.message}")
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

/** The queue item class [installQueueItemRetarget] tracks, for [installPillPress]; null when it could not. */
private var trackedItemType: String? = null
