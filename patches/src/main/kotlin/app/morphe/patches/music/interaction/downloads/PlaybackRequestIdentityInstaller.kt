/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-14.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.Utils.printInfo
import app.morphe.util.cloneMutable
import app.morphe.util.findMutableMethodOf
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val OBJECT = "Ljava/lang/Object;"
private const val FALLBACK = "Lapp/morphe/extension/music/patches/downloads/PlayerFallback;"
private const val IDENTITY = "Lapp/morphe/extension/music/patches/downloads/PlaybackRequestIdentity;"
private const val REQUEST = $$"Lapp/morphe/extension/music/patches/downloads/PlaybackRequestIdentity$Request;"

/** Follow the sequencer's effective-request copies, never arbitrary uses of the stock request builder. */
internal fun BytecodePatchContext.preparePlaybackRequestIdentity(requestType: String): () -> Unit {
    val (factoryType, nextCopy) = when (packageMetadata.versionName) {
        "9.45.51" -> "Lacst;" to "d"
        "9.40.51" -> "Lacmb;" to "d"
        "9.15.51" -> "Lazqv;" to "a"
        "8.30.54" -> "Lawlq;" to "a"
        "8.28.54" -> "Lawdd;" to "a"
        "8.12.54" -> "Lauar;" to "a"
        else -> error("No playback request identity ABI for ${packageMetadata.versionName}")
    }
    val factory = classDefBy(factoryType)
    val next = factory.methods.single {
        it.name == nextCopy && it.parameterTypes.size == 3 && it.parameterTypes[1] == requestType &&
            it.returnType == requestType
    }
    val playback = if (packageMetadata.versionName == "9.45.51") {
        listOf("e", "f").map { name ->
            factory.methods.single {
                it.name == name && it.parameterTypes == listOf(requestType, "Lwjt;") &&
                    it.returnType == requestType
            }
        }
    } else emptyList()
    val copies = listOf(next) + playback
    check(next.implementation!!.instructions.any {
        it.getReference<MethodReference>()?.let { call ->
            call.definingClass == requestType && call.name == "f" && call.parameterTypes.isEmpty()
        } == true
    }) { "PlayerFallback: missing native watch-request copy in $next" }

    return {
        val request = mutableClassDefBy(requestType)
        request.interfaces.add(REQUEST)
        request.instanceFields.add(ImmutableField(requestType, "rvxRequestOrigin", OBJECT,
            AccessFlags.PRIVATE.value, null, emptySet(), emptySet()).toMutable())
        request.addDownloadsBridge("rvxRequestOrigin", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $requestType->rvxRequestOrigin:$OBJECT
            return-object v0
        """)
        request.addDownloadsBridge("rvxRequestOrigin", listOf(OBJECT), "V", 2, """
            iput-object p1, p0, $requestType->rvxRequestOrigin:$OBJECT
            return-void
        """)
        val owner = mutableClassDefBy(factory.type)
        copies.forEach { installCopyIdentity(owner, it, requestType, it == next) }
    }
}

private fun installCopyIdentity(owner: MutableClass, original: Method, requestType: String, fromNext: Boolean) {
    val method = owner.findMutableMethodOf(original)
    val stock = method.cloneMutable(name = "rvxCopy_${original.name}")
    val wrapper = method.cloneMutable(registerCount = method.numberOfParameterRegisters + 1, clearImplementation = true)
    val last = method.numberOfParameterRegisters - 1
    val receiver = if (AccessFlags.STATIC.isSet(original.accessFlags)) 0 else 1
    val source = original.parameterTypes.indexOf(requestType) + receiver
    val invoke = when {
        AccessFlags.STATIC.isSet(original.accessFlags) -> "invoke-static"
        AccessFlags.PRIVATE.isSet(original.accessFlags) -> "invoke-direct"
        else -> "invoke-virtual"
    }
    val seed = if (fromNext) {
        "invoke-static {p$source, p$receiver}, $FALLBACK->seedLocalPlayer($OBJECT$OBJECT)V"
    } else ""
    wrapper.addInstructions(0, """
        $invoke/range {p0 .. p$last}, $stock
        move-result-object v0
        invoke-static {v0, p$source}, $IDENTITY->inherit($OBJECT$OBJECT)$OBJECT
        move-result-object v0
        check-cast v0, $requestType
        $seed
        return-object v0
    """)
    owner.methods.remove(method)
    owner.methods.add(stock)
    owner.methods.add(wrapper)
    printInfo("PlayerFallback: request identity copy $original")
}
