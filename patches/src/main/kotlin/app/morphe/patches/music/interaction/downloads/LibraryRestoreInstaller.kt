/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val LIBRARY_RESTORE = "Lapp/morphe/extension/music/patches/downloads/LibraryRestore;"
private const val NAVIGATOR = $$"Lapp/morphe/extension/music/patches/downloads/LibraryRestore$Navigator;"
private const val MUSIC_ACTIVITY = "Lcom/google/android/apps/youtube/music/activities/MusicActivity;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/** The open step of the tab navigation: the one method of the app that takes a tab id and a command. */
private object TabNavigationFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("FEmusic_search", "hide_search_back_action"),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == STRING
    },
)

private fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Where a method reads the saved command: `Optional.orElse(start)`, the result cast to the command type. */
private class SavedCommandRead(val orElseIndex: Int, val callIndex: Int)

/**
 * The index of the `Optional.orElse` whose cast result is passed, with a tab id, to [target] (matched by name and
 * parameter types, as the call is made through an interface in some versions), or null when the method does not.
 */
private fun Method.savedCommandRead(target: Method): SavedCommandRead? {
    val code = implementation?.instructions?.toList() ?: return null
    val call = code.indexOfFirst {
        val ref = it.methodRef()
        (it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_INTERFACE) &&
            ref?.name == target.name && ref.parameterTypes == target.parameterTypes && ref.returnType == "V"
    }
    if (call < 0) return null

    val orElse = (0 until call).lastOrNull {
        val ref = code[it].methodRef()
        code[it].opcode == Opcode.INVOKE_VIRTUAL && ref?.name == "orElse" && ref.definingClass == "Lj$/util/Optional;"
    } ?: return null

    val resultIsCast = code.getOrNull(orElse + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
        code.getOrNull(orElse + 2)?.opcode == Opcode.CHECK_CAST
    return if (resultIsCast) SavedCommandRead(orElse, call) else null
}

/** The saved-command callback is independent of the direct-offline route. */
internal fun BytecodePatchContext.installLibraryRestore() {
    try {
        val navigation = TabNavigationFingerprint.matchSingle().method
        val consumer = Fingerprint(
            returnType = "V",
            custom = { method, _ -> method.savedCommandRead(navigation) != null },
        ).matchSingle()

        val read = consumer.method.savedCommandRead(navigation)!!
        val code = consumer.method.implementation!!.instructions.toList()
        val orElse = code[read.orElseIndex] as FiveRegisterInstruction
        val result = code[read.orElseIndex + 1] as OneRegisterInstruction
        val commandType = ((code[read.orElseIndex + 2] as ReferenceInstruction).reference as TypeReference).type
        val activity = mutableClassDefBy(MUSIC_ACTIVITY)
        val create = activity.methods.single {
            it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") && it.returnType == "V"
        }
        val destroy = activity.methods.single {
            it.name == "onDestroy" && it.parameterTypes.isEmpty() && it.returnType == "V"
        }

        mutableClassDefBy(consumer.classDef.type).findMutableMethodOf(consumer.method).addInstructions(
            read.orElseIndex + 3, """
                invoke-static {v${result.registerA}, v${orElse.registerD}}, $LIBRARY_RESTORE->restoreLibraryStart(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v${result.registerA}
                check-cast v${result.registerA}, $commandType
            """
        )
        // Back can destroy this activity while its process and extension statics stay alive.
        create.addInstructions(0, """
            invoke-static/range {p0 .. p0}, $LIBRARY_RESTORE->activityCreated(Landroid/app/Activity;)V
        """)
        destroy.addInstructions(0, """
            invoke-static/range {p0 .. p0}, $LIBRARY_RESTORE->activityDestroyed(Landroid/app/Activity;)V
        """)
        printInfo("Library restore: ${consumer.classDef.type}->${consumer.method.name}, " +
            "start ${navigation.definingClass}->${navigation.name}")
    } catch (ex: Exception) {
        printWarn("The Library start after a restart is unavailable on this Music version: ${ex.message}")
    }
}

/** Advertise offline restore only after the native Library cache-miss path is actually installed. */
internal fun BytecodePatchContext.enableOfflineLibraryRestore() {
    val extension = mutableClassDefBy(LIBRARY_RESTORE)
    val capability = extension.methods.single { it.name == "offlineLandingAvailable" && it.returnType == "Z" }
    capability.addInstructions(0, """
        const/4 v0, 0x1
        return v0
    """)
}

/**
 * Native direct-offline opens Downloads without consulting the saved command. Only install the worker-first
 * alternative when the plain Library response and binder hooks are also available.
 */
internal fun BytecodePatchContext.prepareLibraryDirectOfflineStart(
    libraryHost: String,
    modelType: String,
    modelCommand: FieldReference,
): () -> Unit {
    val navigation = TabNavigationFingerprint.matchSingle().method
    val direct = Fingerprint(
        returnType = "V",
        strings = listOf("FEmusic_library_landing", "FEmusic_offline"),
        custom = { method, _ -> method.directOfflineStart(navigation) >= 0 },
    ).matchSingle()
    val site = direct.method.directOfflineStart(navigation)
    val code = direct.method.implementation!!.instructions.toList()
    val call = code[site] as FiveRegisterInstruction
    val commandType = navigation.parameterTypes[1]
    val factory = checkNotNull(code[site - 2].methodRef())
    val scratch = (0 until direct.method.implementation!!.registerCount - 2).first {
        it != call.registerC && it != call.registerD && it != call.registerE
    }
    val startHook = "$LIBRARY_RESTORE->openDirectOfflineStart($OBJECT$STRING$OBJECT$OBJECT)Z"
    val restoreFragment = try {
        prepareSavedLibraryFragment(libraryHost, modelType, modelCommand, factory)
    } catch (ex: Exception) {
        printWarn("Saved Library fragment restore is unavailable on this Music version: ${ex.message}")
        null
    }
    return {
        val navigator = mutableClassDefBy(navigation.definingClass)
        navigator.interfaces.add(NAVIGATOR)
        navigator.addDownloadsBridge("rvxOpenLibrary", listOf(STRING, OBJECT), "V", 3, """
            check-cast p2, $commandType
            invoke-virtual {p0, p1, p2}, $navigation
            return-void
        """)
        val changing = "invoke-static {}, $LIBRARY_RESTORE->navigationChanging()V"
        navigator.findMutableMethodOf(navigation).addInstructions(0, changing)
        val target = mutableClassDefBy(direct.classDef.type).findMutableMethodOf(direct.method)
        target.addInstructionsWithLabels(site, """
            invoke-static {}, $LIBRARY_RESTORE->shouldPrepareDirectOfflineStart()Z
            move-result v$scratch
            if-eqz v$scratch, :rvx_stock_start
            invoke-static {v${call.registerD}}, $factory
            move-result-object v$scratch
            invoke-static {v${call.registerC}, v${call.registerD}, v${call.registerE}, v$scratch}, $startHook
            move-result v$scratch
            if-eqz v$scratch, :rvx_stock_start
            return-void
        """, ExternalLabel("rvx_stock_start", target.getInstruction<Instruction>(site)))
        target.addInstructions(0, changing)
        restoreFragment?.invoke()
        printInfo("Library direct start: ${direct.classDef.type}->${direct.method.name}")
    }
}

/**
 * When Android restores the task itself, BrowseFragment unparcels its model before tab startup is consulted.
 * Change only the Library root's saved Downloads command, before the stock fragment consumes that model.
 */
private fun BytecodePatchContext.prepareSavedLibraryFragment(
    host: String,
    modelType: String,
    command: FieldReference,
    factory: MethodReference,
): () -> Unit {
    check(factory.returnType == command.type) { "Library start command differs from native model command" }
    val parents = generateSequence(classDefByOrNull(host)) { it.superclass?.let(::classDefByOrNull) }
        .toList()
    val create = parents.flatMap { it.methods }.single { method ->
        method.name == "onCreate" && method.parameterTypes == listOf("Landroid/os/Bundle;") &&
            method.implementation?.instructions?.any {
                it.getReference<StringReference>()?.string == "model"
            } == true
    }
    val code = create.implementation!!.instructions.toList()
    val read = code.indexOfFirst {
        val ref = it.methodRef()
        ref?.definingClass == "Landroid/os/Bundle;" && ref.name == "getParcelable"
    }
    check(read > 0 && code[read - 1].getReference<StringReference>()?.string == "model")
    val model = code[read + 1] as OneRegisterInstruction
    check(model.opcode == Opcode.MOVE_RESULT_OBJECT &&
        code[read + 2].opcode == Opcode.CHECK_CAST &&
        (code[read + 2] as OneRegisterInstruction).registerA == model.registerA &&
        code[read + 2].getReference<TypeReference>()?.type == modelType)
    val fragment = create.implementation!!.registerCount - 2
    val store = (read + 3 until code.size).single { index ->
        val instruction = code[index] as? TwoRegisterInstruction
        instruction?.opcode == Opcode.IPUT_OBJECT && instruction.registerA == model.registerA &&
            instruction.registerB == fragment &&
            instruction.getReference<FieldReference>()?.let {
                it.definingClass == create.definingClass && it.type == modelType
            } == true
    }
    val tag = parents.flatMap { it.methods }.first {
        it.name == "getTag" && it.parameterTypes.isEmpty() && it.returnType == STRING
    }

    return {
        val owner = mutableClassDefBy(create.definingClass)
        val bridge = "rvxRestoreSavedLibrary"
        owner.addDownloadsBridge(bridge, listOf(modelType), "V", 4, """
            instance-of v0, p0, $host
            if-eqz v0, :stock
            if-eqz p1, :stock
            invoke-virtual {p0}, $tag
            move-result-object v0
            iget-object v1, p1, $command
            invoke-static {v0, v1}, $LIBRARY_RESTORE->restoreSavedFragment($STRING$OBJECT)Z
            move-result v0
            if-eqz v0, :stock
            const-string v0, "FEmusic_library_landing"
            invoke-static {v0}, $factory
            move-result-object v0
            iput-object v0, p1, $command
            :stock
            return-void
        """)
        owner.findMutableMethodOf(create).addInstructions(store,
            "invoke-virtual {v$fragment, v${model.registerA}}, ${owner.type}->$bridge($modelType)V")
        printInfo("Library saved fragment restore: ${owner.type}->${create.name}")
    }
}

/** The stock Downloads command, created and dispatched immediately in the Library tab's offline branch. */
private fun Method.directOfflineStart(navigation: Method): Int {
    val code = implementation?.instructions?.toList() ?: return -1
    val index = code.indexOfFirst { it.getReference<StringReference>()?.string == "FEmusic_offline" }
    if (index < 0 || code.size <= index + 4) return -1

    val literal = code[index] as? OneRegisterInstruction ?: return -1
    val factory = code[index + 1] as? FiveRegisterInstruction ?: return -1
    val factoryRef = code[index + 1].methodRef() ?: return -1
    val result = code[index + 2] as? OneRegisterInstruction ?: return -1
    val dispatch = code[index + 3] as? FiveRegisterInstruction ?: return -1
    val dispatchRef = code[index + 3].methodRef() ?: return -1
    return if (factory.opcode == Opcode.INVOKE_STATIC && factory.registerC == literal.registerA &&
        factoryRef.parameterTypes == listOf(STRING) && factoryRef.returnType == navigation.parameterTypes[1] &&
        result.opcode == Opcode.MOVE_RESULT_OBJECT && dispatch.registerCount == 3 &&
        dispatch.registerE == result.registerA && dispatchRef.name == navigation.name &&
        dispatchRef.parameterTypes == navigation.parameterTypes && dispatchRef.returnType == "V" &&
        code[index + 4].opcode == Opcode.RETURN_VOID
    ) index + 3 else -1
}
