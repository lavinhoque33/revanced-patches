/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 *
 * modified by lavinhoque33, 2026-10-17: self-contained port. Morphe resolves these members through
 * its Jam patch (music/interaction/jam/JamABI.kt), which is not in the fork; only the parts the
 * audio/video switch consumes are ported here. Jam-only validation (queue listener attach/detach,
 * displayed-queue refresh/move members, primary/autoplay lane ordering) is not resolved because
 * the switch never uses those members.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.fingerprint.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXECUTOR = "Ljava/util/concurrent/Executor;"
private const val HANDLER = "Landroid/os/Handler;"

internal data class ProtoAbi(
    val type: String,
    val defaultInstance: FieldReference,
    val parser: MethodReference,
)

internal data class QueueStorageAbi(
    val field: FieldReference,
    val type: String,
    val items: MethodReference,
    val currentIndex: MethodReference,
    val mode: MethodReference,
    val localMode: FieldReference,
    val laneType: String,
)

/**
 * The members the switch needs, resolved from the same stable queue relationships Morphe's Jam
 * patch uses.
 */
internal data class AudioVideoQueueAbi(
    val managerType: String,
    val constructor: Method,
    val enqueue: MethodReference,
    val command: ProtoAbi,
    val executor: FieldReference,
    val storage: QueueStorageAbi,
    val remove: MethodReference,
    val itemType: String,
    val videoId: MethodReference,
)

internal fun BytecodePatchContext.resolveAudioVideoQueue(): AudioVideoQueueAbi {
    val queueMatch = QueueEnqueueFingerprint.matchSingle()
    val manager = queueMatch.originalClassDef
    val enqueue = queueMatch.originalMethod
    val command = resolveProto(
        enqueue.parameterTypes.singleOrNull()?.toString(),
        "queue command",
    )
    val constructor =
        queueManagerConstructorFingerprint(manager.type).matchSingle().originalMethod
    val executor =
        manager.fields.filter { it.type == EXECUTOR }.singleOrNull()
            ?: error("Missing or ambiguous audio/video switch queue executor")
    val storage = resolveStorage(manager)
    val displayFields = resolveDisplayFields(manager, storage)
    val remove = queueRemovalFingerprint(manager.type, displayFields).matchSingle().originalMethod
    val itemType = remove.parameterTypes.singleOrNull()?.toString()
    require(!itemType.isNullOrBlank() && itemType.startsWith("L")) {
        "Unable to resolve audio/video switch queue item type"
    }
    val videoId =
        queueVideoIdFingerprint(interfaceClosure(itemType))
            .matchAll()
            .groupBy { it.originalClassDef.type }
            .values
            .filter { it.size == 1 }
            .singleOrNull()
            ?.single()
            ?.originalMethod
            ?: error("Missing or ambiguous audio/video switch queue video ID")

    return AudioVideoQueueAbi(
        managerType = manager.type,
        constructor = constructor,
        enqueue = enqueue,
        command = command,
        executor = executor,
        storage = storage,
        remove = remove,
        itemType = itemType,
        videoId = videoId,
    )
}

private fun BytecodePatchContext.resolveProto(type: String?, concept: String): ProtoAbi {
    require(!type.isNullOrBlank() && (type.startsWith("L") || type.startsWith("["))) {
        "Unable to resolve audio/video switch $concept protobuf type"
    }
    val message = classDefByOrNull(type)
        ?: error("Unable to resolve audio/video switch $concept protobuf class")
    val defaultInstance =
        protoDefaultInstanceFingerprint(message.type)
            .matchSingle()
            .instructionMatches[0]
            .getFieldAccessed()
    require(
        AccessFlags.STATIC.isSet(defaultInstance.accessFlags) &&
            AccessFlags.PUBLIC.isSet(defaultInstance.accessFlags)
    ) {
        "Audio/video switch $concept protobuf default instance must be public and static"
    }
    val parser = protoParserFingerprint(interfaceClosure(message.type)).matchSingle().originalMethod
    return ProtoAbi(type, defaultInstance, parser)
}

private fun BytecodePatchContext.resolveStorage(manager: ClassDef): QueueStorageAbi {
    // Start at manager-owned snapshot methods, then resolve their lane contracts.
    val (snapshot, lanes) =
        queueStorageFingerprint(manager)
            .matchAll()
            .mapNotNull { match ->
                val lanes =
                    queueLaneAccessorFingerprint(match.originalClassDef.type)
                        .matchAllOrNull()
                        .orEmpty()
                        .map { it.originalMethod }
                        .filter { lane ->
                            queueLaneMoveFingerprint(lane.returnType).matchAllOrNull() != null &&
                                queueLaneSliceFingerprint(lane.returnType).matchAllOrNull() != null
                        }
                if (lanes.isEmpty()) null else match to lanes
            }
            .singleOrNull() ?: error("Missing or ambiguous audio/video switch queue storage")
    val storage = snapshot.originalClassDef
    val field =
        manager.fields.singleOrNull { it.type == storage.type }
            ?: error("Missing or ambiguous audio/video switch queue storage field")
    val lane = lanes.singleOrNull()
        ?: error("Missing or ambiguous audio/video switch queue lane accessor")
    val currentIndex =
        queueStateAccessorFingerprint(storage.type, false).matchSingle().originalMethod
    val mode = queueStateAccessorFingerprint(storage.type, true).matchSingle().originalMethod
    val localMode = resolveLocalMode(mode)

    check(manager.methods.any { it.indexOfFirstInstruction(fieldAccess(reference = field)) >= 0 }) {
        "Unable to confirm audio/video switch queue storage from queue-operation path"
    }
    return QueueStorageAbi(
        field = field,
        type = storage.type,
        items = snapshot.originalMethod,
        currentIndex = currentIndex,
        mode = mode,
        localMode = localMode,
        laneType = lane.returnType,
    )
}

private fun BytecodePatchContext.resolveLocalMode(mode: MethodReference): FieldReference {
    val enum = classDefBy(mode.returnType)
    require(AccessFlags.ENUM.isSet(enum.accessFlags)) {
        "Audio/video switch queue mode is not an enum"
    }
    return localQueueModeFingerprint(enum.type)
        .matchSingle()
        .instructionMatches[2]
        .getFieldAccessed()
}

/**
 * The manager holds two displayed-queue controllers (main queue and autoplay lane); the native
 * removal operation is the only single-item operation touching both.
 */
private fun BytecodePatchContext.resolveDisplayFields(
    manager: ClassDef,
    storage: QueueStorageAbi,
): List<FieldReference> {
    val fields =
        manager.fields.filter { field ->
            val display = classDefByOrNull(field.type) ?: return@filter false
            display.fields.any { it.type == storage.type } &&
                display.fields.any { it.type == HANDLER } &&
                display.fields.any { it.type == storage.laneType }
        }
    require(fields.size == 2) {
        "Unable to resolve audio/video switch displayed queue controllers: " +
            "expected main and autoplay, found ${fields.size}"
    }
    return fields
}

internal fun BytecodePatchContext.interfaceClosure(type: String): Set<String> {
    val visited = mutableSetOf<String>()
    fun visit(candidate: String) {
        if (!visited.add(candidate)) return
        val classDef = classDefByOrNull(candidate) ?: return
        classDef.interfaces.forEach(::visit)
        classDef.superclass?.let(::visit)
    }
    visit(type)
    return visited
}

/** @return the index of the first instruction matching [filter], or -1. */
internal fun Method.indexOfFirstInstruction(filter: InstructionFilter): Int {
    val method = this
    return instructionsOrNull?.indexOfFirst { filter.matches(method, it) } ?: -1
}
