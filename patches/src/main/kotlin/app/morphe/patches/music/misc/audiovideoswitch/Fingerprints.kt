/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3471
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 *
 * modified by lavinhoque33, 2026-10-17: collected from Morphe's
 * music/layout/hide/general/Fingerprints.kt (pill container) and the queue fingerprints of
 * music/interaction/jam/Fingerprints.kt that the audio/video switch needs (Jam is not in the fork).
 * Resource literals use the fork's resourceMappingPatch based resourceLiteral.
 */

package app.morphe.patches.music.misc.audiovideoswitch

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.string
import app.morphe.patches.shared.mapping.ResourceType
import app.morphe.patches.shared.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/** Every inflation site of the Song/Video pill container in the watch page. */
internal object AudioVideoSwitchPillContainerFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "audio_video_switch_pill_container"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

/**
 * The queue operation emits stable diagnostic strings from YouTube Music's queue manager. Every
 * other queue member is derived from this semantic entry point.
 */
internal object QueueEnqueueFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf(
        "com/google/android/apps/youtube/music/player/queue/MusicPlaybackQueueOperationsManager",
        "enqueue",
        "enqueue item, QueueTarget: %s, position: %s"
    )
)

/** The operations manager has a single construction/capture point. */
internal fun queueManagerConstructorFingerprint(managerType: String) = Fingerprint(
    definingClass = managerType,
    name = "<init>"
)

/** Removal updates both native queue displays, unlike lane-specific operations. */
internal fun queueRemovalFingerprint(
    managerType: String,
    displayFields: List<FieldReference>,
) = Fingerprint(
    definingClass = managerType,
    parameters = listOf("L"),
    returnType = "V",
    filters = listOf(fieldAccess(reference = displayFields[0])),
    custom = { method, _ ->
        method.indexOfFirstInstruction(fieldAccess(reference = displayFields[1])) >= 0
    }
)

internal fun queueLaneMoveFingerprint(laneType: String) = Fingerprint(
    definingClass = laneType,
    returnType = "V",
    parameters = listOf("I", "I")
)

/** Enum initialization binds the stable LOCAL name to its native singleton. */
internal fun localQueueModeFingerprint(modeType: String) = Fingerprint(
    definingClass = modeType,
    name = "<clinit>",
    filters = listOf(
        string("LOCAL"),
        methodCall("Ljava/lang/Enum;-><init>(Ljava/lang/String;I)V"),
        fieldAccess(definingClass = modeType, type = modeType, opcode = Opcode.SPUT_OBJECT)
    )
)

/** The manager owns a store that exposes both snapshots and observable, movable lanes. */
internal fun queueStorageFingerprint(manager: ClassDef) = Fingerprint(
    parameters = listOf("I"),
    returnType = "Ljava/util/List;",
    custom = { _, owner -> manager.fields.any { it.type == owner.type } },
)

internal fun BytecodePatchContext.queueLaneAccessorFingerprint(storageType: String) = Fingerprint(
    definingClass = storageType,
    parameters = listOf("I"),
    returnType = "L",
    custom = { method, _ ->
        method.returnType != "Ljava/util/List;" && classDefByOrNull(method.returnType) != null
    }
)

internal fun queueLaneSliceFingerprint(laneType: String) = Fingerprint(
    definingClass = laneType,
    parameters = listOf("I", "I"),
    returnType = "Ljava/util/List;",
)

/** State accessors forward to an interface held by the resolved native store. */
internal fun BytecodePatchContext.queueStateAccessorFingerprint(
    storageType: String,
    mode: Boolean,
) = Fingerprint(
    definingClass = storageType,
    parameters = emptyList(),
    returnType = if (mode) "L" else "I",
    custom = { method, owner ->
        (!mode || classDefByOrNull(method.returnType)?.let {
            AccessFlags.ENUM.isSet(it.accessFlags)
        } == true) && owner.fields.any { field ->
            method.indexOfFirstInstruction(
                methodCall(
                    definingClass = field.type,
                    parameters = emptyList(),
                    returnType = method.returnType,
                    opcodes = listOf(
                        Opcode.INVOKE_INTERFACE,
                        Opcode.INVOKE_INTERFACE_RANGE
                    ),
                )
            ) >= 0
        }
    }
)

internal fun protoDefaultInstanceFingerprint(messageType: String) = Fingerprint(
    definingClass = messageType,
    name = "<clinit>",
    filters = listOf(
        fieldAccess(
            definingClass = messageType,
            type = messageType,
            opcode = Opcode.SPUT_OBJECT
        )
    )
)

/** Generic protobuf parser inherited by the already resolved native message type. */
internal fun protoParserFingerprint(messageHierarchy: Set<String>) = Fingerprint(
    name = "parseFrom",
    parameters = listOf("L", "[B", "Lcom/google/protobuf/ExtensionRegistryLite;"),
    returnType = "L",
    custom = { method, owner ->
        owner.type in messageHierarchy &&
                method.returnType == owner.type &&
                method.parameterTypes[0].toString() == owner.type &&
                AccessFlags.STATIC.isSet(method.accessFlags)
    },
)

internal fun queueVideoIdFingerprint(itemTypes: Set<String>) = Fingerprint(
    parameters = emptyList(),
    returnType = "Ljava/lang/String;",
    custom = { _, owner -> owner.type in itemTypes },
)
