/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-14.
 * Written by lavinhoque33, 2026-11-14.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.Utils.printInfo
import app.morphe.util.cloneMutable
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val FALLBACK = "Lapp/morphe/extension/music/patches/downloads/PlayerFallback;"
private const val REQUEST = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$Request;"
private const val REPLAY = "Lapp/morphe/extension/music/patches/downloads/OfflineReplayHooks;"
private const val FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STORED_RESPONSE =
    $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$StoredPlayerResponse;"
private const val WATCH_COPY = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$WatchRequestCopy;"

/** Keep stock paired/packed assembly and alternate routing; replace only its per-request offline decision. */
internal fun BytecodePatchContext.preparePlayerFallback(
    requestType: String,
    offlineOnly: MethodReference,
    videoId: MethodReference,
    wrapper: ClassDef,
    savedFactory: MethodReference,
): () -> Unit {
    val selector = Fingerprint(
        returnType = FUTURE,
        parameters = listOf(requestType, STRING, "I", "L", "L"),
        custom = { method, _ ->
            AccessFlags.PUBLIC.isSet(method.accessFlags) && method.implementation?.instructions?.any {
                it.getReference<MethodReference>()?.toString() == offlineOnly.toString()
            } == true
        },
    ).matchSingle().classDef
    val sites = selector.methods.mapNotNull { method ->
        val calls = method.implementation?.instructions?.toList()?.withIndex()?.filter {
            it.value.getReference<MethodReference>()?.toString() == offlineOnly.toString()
        }.orEmpty()
        if (calls.isEmpty()) null else method to calls
    }
    val getterCalls = sites.sumOf { it.second.size }
    check(getterCalls == 13) { "player fallback: incomplete selector coverage ($getterCalls/13)" }
    val installTransport = preparePlayerTransport(selector, requestType)
    val installNavigation = prepareNavigationFallback(requestType)
    val installIdentity = preparePlaybackRequestIdentity(requestType)
    val installWatchProvenance = prepareWatchProvenance(requestType, wrapper)

    return {
        installIdentity?.invoke()
        installWatchProvenance?.invoke()
        val request = mutableClassDefBy(requestType)
        request.interfaces.add(REQUEST)
        request.addDownloadsBridge("rvxVideoId", emptyList(), STRING, 2, """
            invoke-virtual {p0}, $videoId
            move-result-object v0
            return-object v0
        """)
        request.addDownloadsBridge("rvxReplayOffline", emptyList(), "Z", 2, """
            invoke-virtual {p0}, $offlineOnly
            move-result v0
            invoke-static {p0, v0}, $FALLBACK->offlineLane(${OBJECT}Z)Z
            move-result v0
            return v0
        """)
        request.addDownloadsBridge("rvxPlayerResponse", listOf("[B"), OBJECT, 4, """
            invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J
            move-result-wide v0
            invoke-static {p1, v0, v1}, $savedFactory
            move-result-object v0
            return-object v0
        """)
        val owner = mutableClassDefBy(selector.type)
        sites.forEach { (original, calls) ->
            val method = owner.findMutableMethodOf(original)
            calls.forEach { (index, instruction) ->
                val register = when (instruction) {
                    is FiveRegisterInstruction -> instruction.registerC
                    is RegisterRangeInstruction -> instruction.startRegister
                    else -> error("player fallback: unsupported offline getter invocation")
                }
                method.replaceInstruction(index,
                    "invoke-virtual/range {v$register .. v$register}, $requestType->rvxReplayOffline()Z")
            }
            printInfo("PlayerFallback: selector ${method.definingClass}->${method.name}" +
                "(${method.parameterTypes.joinToString("")})${method.returnType}")
        }
        installTransport()
        installNavigation()
    }
}

private fun BytecodePatchContext.prepareWatchProvenance(request: String, response: ClassDef): (() -> Unit)? {
    val manager = when (packageMetadata.versionName) {
        "9.45.51" -> "Labrl;"
        "9.40.51" -> "Labll;"
        else -> return null
    }
    val normalize = classDefBy(manager).methods.single {
        it.name == "b" && it.parameterTypes == listOf(request) && it.returnType == request
    }
    val copyCode = normalize.implementation!!.instructions
    val builder = copyCode.mapNotNull { it.getReference<MethodReference>() }.single {
        it.definingClass == request && it.name == "g" && it.parameterTypes.isEmpty()
    }.returnType
    check(copyCode.any {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { call ->
            call.definingClass == builder && call.name == "a" && call.parameterTypes.isEmpty() &&
                call.returnType == request
        } == true
    }) { "OfflineWatchMetadata: unknown native descriptor normalization in $normalize" }

    return {
        val wrapper = mutableClassDefBy(response.type)
        val proof = "${wrapper.type}->rvxStoredPlayerOwner:$OBJECT"
        wrapper.interfaces.add(STORED_RESPONSE)
        wrapper.instanceFields.add(ImmutableField(wrapper.type, "rvxStoredPlayerOwner", OBJECT,
            AccessFlags.PRIVATE.value or AccessFlags.VOLATILE.value, null, emptySet(), emptySet()).toMutable())
        wrapper.addDownloadsBridge("rvxStoredPlayerOwner", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $proof
            return-object v0
        """)
        wrapper.addDownloadsBridge("rvxStoredPlayerOwner", listOf(OBJECT), "V", 2, """
            iput-object p1, p0, $proof
            return-void
        """)

        // This exact descriptor-normalization copy extends only the metadata identity.
        val nativeRequest = mutableClassDefBy(request)
        val source = "$request->rvxWatchRequestSource:$OBJECT"
        nativeRequest.interfaces.add(WATCH_COPY)
        nativeRequest.instanceFields.add(ImmutableField(request, "rvxWatchRequestSource", OBJECT,
            AccessFlags.PRIVATE.value or AccessFlags.VOLATILE.value, null, emptySet(), emptySet()).toMutable())
        nativeRequest.addDownloadsBridge("rvxWatchRequestSource", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $source
            return-object v0
        """)
        nativeRequest.addDownloadsBridge("rvxWatchRequestSource", listOf(OBJECT), "V", 2, """
            iput-object p1, p0, $source
            return-void
        """)
        val owner = mutableClassDefBy(manager)
        val original = owner.findMutableMethodOf(normalize)
        val stock = original.cloneMutable(name = "rvxNormalizeWatchRequest")
        val bridge = original.cloneMutable(registerCount = 3, clearImplementation = true)
        bridge.addInstructions(0, """
            invoke-virtual {p0, p1}, $stock
            move-result-object v0
            invoke-static {v0, p1}, $FALLBACK->noteWatchRequestCopy($OBJECT$OBJECT)V
            return-object v0
        """)
        owner.methods.remove(original)
        owner.methods.add(stock)
        owner.methods.add(bridge)
        printInfo("OfflineWatchMetadata: saved player provenance ${response.type}; request copy $normalize")
    }
}
