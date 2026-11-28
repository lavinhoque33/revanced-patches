/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.getReference
import app.morphe.util.Utils.printInfo
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val NATIVE_MIRROR_VERSION = "9.45.51"
internal const val NATIVE_OFFLINE_INDEX_KEY = "morphe_music_native_offline_index"
private const val MIRROR = "Lapp/morphe/extension/music/patches/downloads/NativeMirror;"
private const val POLICY = "Lapp/morphe/extension/music/patches/downloads/NativeOfflinePolicy;"
private const val STORE_BRIDGE = $$"Lapp/morphe/extension/music/patches/downloads/NativeMirror$Store;"
private const val ACCOUNT_STORE = "Laaeq;"
private const val ACCOUNT_SELECTOR = "Laaes;"
private const val METADATA = "Laafw;"
private const val CACHE = "Laahh;"
private const val DATABASE = "Landroid/database/sqlite/SQLiteDatabase;"
private const val STRING = "Ljava/lang/String;"

/** Observe only the active account transition and reuse the stock metadata helper after its own initialization. */
internal fun BytecodePatchContext.installNativeMirror() {
    if (packageMetadata.versionName != NATIVE_MIRROR_VERSION) return

    val selector = mutableClassDefBy(ACCOUNT_SELECTOR)
    val store = mutableClassDefBy(ACCOUNT_STORE)
    val metadata = mutableClassDefBy(METADATA)
    val cache = mutableClassDefBy(CACHE)
    val active = selector.fields.single { it.name == "l" && it.type == ACCOUNT_STORE }
    val metadataAccessor = store.methods.single { it.name == "d" && it.parameterTypes.isEmpty() &&
            it.returnType == METADATA }
    val databaseAccessor = metadata.methods.single { it.name == "aA" && it.parameterTypes.isEmpty() &&
            it.returnType == DATABASE }
    val cacheField = store.fields.single { it.name == "G" && it.type == CACHE }
    val invalidateVideo = cache.methods.single { it.name == "n" && it.parameterTypes == listOf(STRING) &&
            it.returnType == "V" }
    val invalidatePlaylist = cache.methods.single { it.name == "m" && it.parameterTypes == listOf(STRING) &&
            it.returnType == "V" }

    // Both writes to the selected store (install and clear) publish their actual stock value, never a DB guessed
    // from mtimes. The branch into the no-op store does not create an offline store for a signed-out account.
    val transitions = listOf("k" to listOf("Lzwf;"), "h" to emptyList()).map { (name, parameters) ->
        val method = selector.methods.single { it.name == name && it.parameterTypes == parameters }
        val instructions = method.implementation!!.instructions.toList()
        val assignment = instructions.indices.singleOrNull {
            instructions[it].opcode == Opcode.IPUT_OBJECT &&
                    instructions[it].getReference<FieldReference>() == active
        } ?: throw PatchException("Native offline index: missing active-store transition $name")
        val value = (instructions[assignment] as OneRegisterInstruction).registerA
        val initialized = if (name == "k") {
            instructions.indices.single { index ->
                instructions[index].getReference<MethodReference>()?.let {
                    it.definingClass == ACCOUNT_STORE && it.name == "x" &&
                            it.parameterTypes == listOf("Ljava/lang/Runnable;") && it.returnType == "V"
                } == true
            }
        } else assignment
        Triple(method, initialized + 1, value)
    }

    store.interfaces.add(STORE_BRIDGE)
    metadata.addDownloadsBridge("rvxOfflineIndexDatabase", emptyList(), DATABASE, 2, """
        invoke-direct {p0}, $databaseAccessor
        move-result-object v0
        return-object v0
    """)
    // ART does not refine a reference to null on if-eqz. Keep the metadata and database types from merging.
    store.addDownloadsBridge("rvxOfflineIndexDatabase", emptyList(), DATABASE, 2, """
        invoke-virtual {p0}, $metadataAccessor
        move-result-object v0
        if-eqz v0, :null_database
        invoke-virtual {v0}, $METADATA->rvxOfflineIndexDatabase()$DATABASE
        move-result-object v0
        check-cast v0, $DATABASE
        return-object v0
        :null_database
        const/4 v0, 0x0
        return-object v0
    """)
    store.addDownloadsBridge("rvxOfflineIndexChanged", listOf(STRING, "Z"), "V", 4, """
        iget-object v0, p0, $cacheField
        if-eqz p2, :video
        invoke-virtual {v0, p1}, $invalidatePlaylist
        return-void
        :video
        invoke-virtual {v0, p1}, $invalidateVideo
        return-void
    """)
    transitions.forEach { (method, index, register) ->
        method.addInstruction(index, "invoke-static {v$register}, $MIRROR->onStoreChanged(Ljava/lang/Object;)V")
    }
    listOf(invalidateVideo, invalidatePlaylist).forEach { method ->
        // The first monitor exit is on the normal path. Do not read p1 here: the stock method has reused it.
        val exit = method.implementation!!.instructions.indexOfFirst { it.opcode == Opcode.MONITOR_EXIT }
        check(exit >= 0) { "Native offline index: missing metadata cache invalidation monitor exit" }
        method.addInstruction(exit + 1, "invoke-static {}, $MIRROR->onStockIndexChanged()V")
    }
    printInfo("Native offline index: installed account-scoped 9.45.51 metadata bridge")
}

/**
 * A free account's stock policy rejects the offline state of a saved video. Keep the saved video usable so the
 * stock player can select the fork's own representation; the cache and integrity checks stay stock.
 */
internal fun BytecodePatchContext.installNativeOfflinePolicy() {
    if (packageMetadata.versionName != NATIVE_MIRROR_VERSION) return

    val policy = mutableClassDefBy("Laanu;")
    val action = policy.methods.single { it.name == "f" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
    val usable = policy.methods.single { it.name == "e" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
    val accepted = policy.methods.single { it.name == "g" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
    action.replaceInstructions(0, listOf(
        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
        BuilderInstruction11x(Opcode.RETURN, 0),
    ))
    usable.replaceInstructions(0, listOf(
        BuilderInstruction11n(Opcode.CONST_4, 0, 0),
        BuilderInstruction11x(Opcode.RETURN, 0),
    ))
    accepted.replaceInstructions(0, listOf(
        BuilderInstruction11n(Opcode.CONST_4, 0, 1),
        BuilderInstruction11x(Opcode.RETURN, 0),
    ))

    // The stock refresh writes the declined server response; keep the fork's imported response instead.
    val refresh = mutableClassDefBy("Laahk;").methods.single {
        it.name == "m" && it.parameterTypes == listOf(STRING, "Lwjt;", "J", "J") && it.returnType == "V"
    }
    listOf(
        "invoke-static {p1, p2}, $POLICY->keepImportedResponse(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
        "move-result-object v0",
        "check-cast v0, Lwjt;",
        "move-object p2, v0",
    ).forEachIndexed { index, instruction -> refresh.addInstruction(index, instruction) }

    // The stock gate also asks the offline capability supplier. A signed-out account still has no store.
    val availability = mutableClassDefBy(ACCOUNT_SELECTOR).methods.single {
        it.name == "i" && it.parameterTypes.isEmpty() && it.returnType == "Z"
    }
    availability.addInstructions(0, """
        iget-object v0, p0, $ACCOUNT_SELECTOR->l:$ACCOUNT_STORE
        if-eqz v0, :deny_store
        iget-boolean v0, v0, $ACCOUNT_STORE->w:Z
        if-eqz v0, :deny_store
        const/4 p0, 0x1
        return p0
        :deny_store
        const/4 p0, 0x0
        return p0
    """)
    // A free account's offline snapshot carries no policy. Keep the accessor non-null so the forced
    // usable-state methods above are reachable; the stock store still owns cache and integrity checks.
    val policyAccessor = mutableClassDefBy("Laanv;").methods.single {
        it.name == "i" && it.parameterTypes.isEmpty() && it.returnType == "Laanu;"
    }
    policyAccessor.addInstructions(0, """
        iget-object v0, p0, Laanv;->q:Laanu;
        if-nez v0, :done_policy
        iget-object v0, p0, Laanv;->u:Laaho;
        if-eqz v0, :new_policy
        invoke-virtual {v0}, Laaho;->f()Laanu;
        move-result-object v0
        if-nez v0, :done_policy
        :new_policy
        new-instance v0, Laanu;
        invoke-direct {v0}, Laanu;-><init>()V
        :done_policy
        return-object v0
    """)
    // A free account's policy carries zeroed expiry fields, so the offline page labels an imported row
    // "Offline availability expired" before any source selection. Scoped to the opt-in native index;
    // genuine policies keep their stock date.
    val policyExpiry = mutableClassDefBy("Laanu;").methods.single {
        it.name == "a" && it.parameterTypes.isEmpty() && it.returnType == "J"
    }
    policyExpiry.addInstructions(0, """
        invoke-static {}, $POLICY->nativeOfflineIndexEnabled()Z
        move-result v5
        if-eqz v5, :stock_expiry
        invoke-static {}, Ljava/lang/System;->currentTimeMillis()J
        move-result-wide v0
        const-wide v2, 0x757b12c00L
        add-long/2addr v0, v2
        return-wide v0
        :stock_expiry
    """)
    // The import writes its row through aafw.B with the fork response, which carries no offline state.
    // Fill one before the write so the stored snapshot and the offline subsystem see a playable state.
    val responseWriter = mutableClassDefBy(METADATA).methods.single {
        it.name == "B" && it.parameterTypes.size == 5 && it.parameterTypes[1] == "Lwjt;"
    }
    responseWriter.addInstructions(0,
        "invoke-static {p1, p2}, $POLICY->attachPlayableState(Ljava/lang/String;Ljava/lang/Object;)V")
    printInfo("Native offline policy: installed 9.45.51 usable-state, kept-response and store-availability overrides")
}
