/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 *
 * modified by lavinhoque33, 2026-10-17: bridge helpers from Morphe's Jam patch
 * (music/interaction/jam/JamBridge.kt) inlined because Jam is not in the fork. The queue capture
 * is inserted right after the super constructor call instead of before each return-void: the
 * queue manager constructor reuses p0 as a scratch register before returning, and the fork has no
 * cloneParameters() helper.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/misc/EnableAudioVideoSwitchPatch;"
private const val ACCESS =
    $$"Lapp/morphe/extension/music/patches/misc/EnableAudioVideoSwitchPatch$QueueAccess;"

/**
 * Every bridge is named patch_atv* and the original queue operations are never renamed here.
 */
internal fun BytecodePatchContext.installAudioVideoSwitchQueue(queue: AudioVideoQueueAbi) {
    val manager = mutableClassDefBy(queue.managerType)
    manager.interfaces.add(ACCESS)

    manager.addBridge(
        "patch_atvEnqueue",
        listOf("[B"),
        "V",
        4,
        body =
            queue.command.decode("p1", "v0") + """
            ${invokeKind(queue.enqueue)} {p0, v0}, ${queue.enqueue}
            return-void
        """
    )
    manager.addReferenceGetter("patch_atvExecutor", queue.executor)
    installNativeAccessor(
        manager,
        "patch_atvCurrentIndex",
        queue.storage.currentIndex,
        receiverField = queue.storage.field,
    )
    manager.addBridge(
        "patch_atvItems",
        emptyList(),
        "[Ljava/lang/Object;",
        3,
        body = """
            iget-object v0, p0, ${queue.storage.field}
            const/4 v1, 0x0
            ${invokeKind(queue.storage.items)} {v0, v1}, ${queue.storage.items}
            move-result-object v0
            invoke-interface {v0}, Ljava/util/List;->toArray()[Ljava/lang/Object;
            move-result-object v0
            return-object v0
        """
    )
    installNativeAccessor(
        manager,
        "patch_atvVideoId",
        queue.videoId,
        opaqueReceiver = true,
    )
    manager.addBridge(
        "patch_atvRemove",
        listOf("Ljava/lang/Object;"),
        "V",
        2,
        body = """
            check-cast p1, ${queue.itemType}
            ${invokeKind(queue.remove)} {p0, p1}, ${queue.remove}
            return-void
        """
    )
    manager.addBridge(
        "patch_atvLocal",
        emptyList(),
        "Z",
        3,
        body = """
            iget-object v0, p0, ${queue.storage.field}
            ${invokeKind(queue.storage.mode)} {v0}, ${queue.storage.mode}
            move-result-object v0
            sget-object v1, ${queue.storage.localMode}
            if-ne v0, v1, :remote
            const/4 v0, 0x1
            return v0
            :remote
            const/4 v0, 0x0
            return v0
        """
    )

    installAudioVideoSwitchCapture(queue)
}

private fun BytecodePatchContext.installAudioVideoSwitchCapture(queue: AudioVideoQueueAbi) {
    val constructor = mutableClassDefBy(queue.managerType).methods.single {
        it.name == queue.constructor.name &&
            it.parameterTypes.map(CharSequence::toString) ==
            queue.constructor.parameterTypes.map(CharSequence::toString)
    }
    val superClass = mutableClassDefBy(queue.managerType).superclass
    constructor.apply {
        val parameterRegisters = 1 + parameterTypes.sumOf { type ->
            val descriptor = type.toString()
            if (descriptor == "J" || descriptor == "D") 2 else 1
        }
        val thisRegister = implementation!!.registerCount - parameterRegisters
        // p0 is a valid receiver only after the super constructor and may be reused afterwards.
        val superInitIndex = indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_DIRECT &&
                getReference<MethodReference>()?.let {
                    it.name == "<init>" && it.definingClass == superClass
                } == true &&
                (this as FiveRegisterInstruction).registerC == thisRegister
        }
        addInstructions(
            superInitIndex + 1,
            "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->capture($ACCESS)V",
        )
    }
}

/** Small native access adapters. All queue and presentation policy belongs in the extension. */
private fun ProtoAbi.decode(bytes: String, result: String): String {
    val registry = if (result == "v1") "v2" else "v1"
    return """
        sget-object $result, $defaultInstance
        invoke-static {}, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
        move-result-object $registry
        invoke-static {$result, $bytes, $registry}, $parser
        move-result-object $result
        check-cast $result, $type
    """
}

private fun MutableClass.addBridge(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    accessFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    body: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            accessFlags,
            null,
            null,
            MutableMethodImplementation(registers),
        )
            .toMutable()
            .apply { addInstructions(0, body) }
    )
}

private fun BytecodePatchContext.invokeKind(reference: MethodReference): String {
    val owner = classDefByOrNull(reference.definingClass)
    return when {
        reference.name == "<init>" -> "invoke-direct"
        reference is Method && AccessFlags.PRIVATE.isSet(reference.accessFlags) -> "invoke-direct"
        owner != null && AccessFlags.INTERFACE.isSet(owner.accessFlags) -> "invoke-interface"
        else -> "invoke-virtual"
    }
}

/** Expose a zero-argument native accessor, optionally on a held or opaque receiver. */
private fun BytecodePatchContext.installNativeAccessor(
    owner: MutableClass,
    name: String,
    accessor: MethodReference,
    receiverField: FieldReference? = null,
    opaqueReceiver: Boolean = false,
    resultType: String = accessor.returnType,
) {
    require(accessor.parameterTypes.isEmpty()) {
        "Audio/video switch accessor must have no native arguments: $accessor"
    }
    require(receiverField == null || !opaqueReceiver) {
        "Audio/video switch accessor has two receivers"
    }
    val wide = resultType == "J" || resultType == "D"
    val suffix =
        when {
            wide -> "-wide"
            resultType.startsWith("L") || resultType.startsWith("[") -> "-object"
            else -> ""
        }
    val receiver = if (opaqueReceiver) "p1" else if (receiverField != null) "v0" else "p0"
    owner.addBridge(
        name,
        if (opaqueReceiver) listOf("Ljava/lang/Object;") else emptyList(),
        resultType,
        (if (wide) 2 else 1) + (if (opaqueReceiver) 2 else 1),
        body = buildString {
            if (opaqueReceiver) appendLine("check-cast p1, ${accessor.definingClass}")
            if (receiverField != null) appendLine("iget-object v0, p0, $receiverField")
            appendLine("${invokeKind(accessor)} {$receiver}, $accessor")
            appendLine("move-result$suffix v0")
            appendLine("return$suffix v0")
        }
    )
}

/** Expose one native reference field; type adaptation and fallback stay in Java. */
private fun MutableClass.addReferenceGetter(name: String, field: FieldReference) {
    require(field.type.startsWith("L") || field.type.startsWith("["))
    addBridge(
        name,
        emptyList(),
        field.type,
        2,
        body = """
            iget-object v0, p0, $field
            return-object v0
        """
    )
}
