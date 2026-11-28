/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.Utils.printInfo
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val IMPORT = "Lapp/morphe/extension/music/patches/downloads/NativeOfflineImport;"
private const val SOURCE = $$"Lapp/morphe/extension/music/patches/downloads/NativeOfflineImport$Source;"
private const val CANCEL = $$"Lapp/morphe/extension/music/patches/downloads/NativeOfflineImport$Cancellation;"
private const val TRANSFER = $$"Lapp/morphe/extension/music/patches/downloads/NativeOfflineImport$Transfer;"
private const val DIRECT = "Lapp/morphe/extension/music/patches/downloads/NativeOfflineDirect;"
private const val WRITER = "Lynz;"
private const val FORMAT = "Lwhg;"
private const val SPEC = "Lbun;"
private const val UPSTREAM = "Lbuj;"
private const val OBJECT = "Ljava/lang/Object;"
private const val MAP = "Ljava/util/Map;"
private const val STRING = "Ljava/lang/String;"
private const val URI = "Landroid/net/Uri;"
private const val OFFLINE_WRITER = "$WRITER->rvxIsOfflineWriter:Z"
private const val DIRECT_TRANSFER = "$WRITER->rvxDirectTransfer:$TRANSFER"

/** Stock 9.45.51 policy and transfer task -> Laauv.g -> Lynz.b. Do not touch the shared playback cache reader. */
internal fun BytecodePatchContext.installNativeOfflineImport() {
    if (packageMetadata.versionName != "9.45.51") return

    val writer = mutableClassDefBy(WRITER)
    val write = writer.methods.single {
        it.name == "b" && it.parameterTypes.map(CharSequence::toString) ==
            listOf(FORMAT, "J", "J", "Lwji;", STRING, "Lzgo;", "Lzgo;") && it.returnType == "V"
    }
    val offlineWrite = mutableClassDefBy("Laauv;").methods.single { method ->
        method.name == "g" && method.implementation?.instructions?.any {
            it.getReference<MethodReference>() == write
        } == true
    }
    val startOffline = offlineWrite.implementation!!.instructions.toList()
    val offlineCall = startOffline.indexOfFirst { it.getReference<MethodReference>() == write }
    check((startOffline[offlineCall] as RegisterRangeInstruction).startRegister == 4)
    check(startOffline[offlineCall - 3].opcode == Opcode.CONST_4 &&
        (startOffline[offlineCall - 3] as OneRegisterInstruction).registerA == 10)

    val body = write.implementation!!.instructions.toList()
    val upstreamFactory = body.indexOfFirst {
        it.getReference<MethodReference>()?.let { ref ->
            ref.definingClass == "Lzgp;" && ref.name == "b" && ref.returnType == UPSTREAM
        } == true
    }
    check(upstreamFactory >= 0 && body[upstreamFactory + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
    val join = body[upstreamFactory + 2] as TwoRegisterInstruction
    check(join.opcode == Opcode.MOVE_OBJECT_FROM16 && join.registerA == 16 && join.registerB == 2)
    check(body[upstreamFactory + 3].opcode == Opcode.IGET_OBJECT &&
        (body[upstreamFactory + 3] as TwoRegisterInstruction).registerA == 2)
    val firstFactory = body.indexOfFirst {
        it.getReference<MethodReference>()?.let { ref ->
            ref.definingClass == "Lzhc;" && ref.name == "bK" && ref.returnType == "Z"
        } == true
    }
    check(firstFactory in 0 until upstreamFactory)
    val cachePipeline = body.single {
        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { ref ->
            ref.definingClass == WRITER && ref.name == "l" && ref.type == "Lj$/util/Optional;"
        } == true
    }

    // The application component exists without scheduling a stock transfer, unlike the transfer service.
    val graph = mutableClassDefBy("Lgeh;").methods.single { it.name == "<init>" }
    val graphReturn = graph.implementation!!.instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    check(graphReturn >= 0)
    graph.addInstructionsWithLabels(graphReturn, """
        invoke-static/range {p0 .. p0}, $DIRECT->captureGraph($OBJECT)V
    """)

    val nativeSource = mutableClassDefBy(UPSTREAM)
    val transfer = mutableClassDefBy(TRANSFER)
    nativeSource.interfaces.add(SOURCE)
    nativeSource.addDownloadsBridge("rvxImportOpen", listOf(OBJECT), "J", 4, """
        check-cast p1, $SPEC
        invoke-interface {p0, p1}, $UPSTREAM->b($SPEC)J
        move-result-wide v0
        return-wide v0
    """)
    nativeSource.addDownloadsBridge("rvxImportRead", listOf("[B", "I", "I"), "I", 5, """
        invoke-interface {p0, p1, p2, p3}, $UPSTREAM->a([BII)I
        move-result v0
        return v0
    """)
    nativeSource.addDownloadsBridge("rvxImportUri", emptyList(), URI, 2, """
        invoke-interface {p0}, $UPSTREAM->c()$URI
        move-result-object v0
        return-object v0
    """)
    nativeSource.addDownloadsBridge("rvxImportHeaders", emptyList(), MAP, 2, """
        invoke-interface {p0}, $UPSTREAM->d()$MAP
        move-result-object v0
        return-object v0
    """)
    nativeSource.addDownloadsBridge("rvxImportListen", listOf(OBJECT), "V", 2, """
        check-cast p1, Lbvl;
        invoke-interface {p0, p1}, $UPSTREAM->e(Lbvl;)V
        return-void
    """)
    nativeSource.addDownloadsBridge("rvxImportClose", emptyList(), "V", 1, """
        invoke-interface {p0}, $UPSTREAM->f()V
        return-void
    """)

    transfer.interfaces.add(UPSTREAM)
    transfer.addDownloadsBridge("a", listOf("[B", "I", "I"), "I", 5, """
        invoke-virtual {p0, p1, p2, p3}, $TRANSFER->read([BII)I
        move-result v0
        return v0
    """)
    transfer.addDownloadsBridge("b", listOf(SPEC), "J", 9, """
        move-object v0, p0
        move-object v1, p1
        iget-wide v2, p1, $SPEC->h:J
        iget-wide v4, p1, $SPEC->i:J
        iget-object v6, p1, $SPEC->j:$STRING
        invoke-virtual/range {v0 .. v6}, $TRANSFER->open(${OBJECT}JJ$STRING)J
        move-result-wide v0
        return-wide v0
    """)
    transfer.addDownloadsBridge("c", emptyList(), URI, 2, """
        invoke-virtual {p0}, $TRANSFER->uri()$URI
        move-result-object v0
        return-object v0
    """)
    transfer.addDownloadsBridge("d", emptyList(), MAP, 2, """
        invoke-virtual {p0}, $TRANSFER->headers()$MAP
        move-result-object v0
        return-object v0
    """)
    transfer.addDownloadsBridge("e", listOf("Lbvl;"), "V", 2, """
        invoke-virtual {p0, p1}, $TRANSFER->listen($OBJECT)V
        return-void
    """)
    transfer.addDownloadsBridge("f", emptyList(), "V", 1, """
        invoke-virtual {p0}, $TRANSFER->close()V
        return-void
    """)

    writer.instanceFields.add(
        ImmutableField(WRITER, "rvxIsOfflineWriter", "Z", AccessFlags.PUBLIC.value,
            null, emptySet(), emptySet()).toMutable()
    )
    writer.instanceFields.add(
        ImmutableField(WRITER, "rvxDirectTransfer", TRANSFER, AccessFlags.PUBLIC.value,
            null, emptySet(), emptySet()).toMutable()
    )
    writer.interfaces.add(CANCEL)
    writer.addDownloadsBridge("rvxImportCancelled", emptyList(), "Z", 2, """
        iget-boolean v0, p0, $WRITER->c:Z
        return v0
    """)
    writer.addDownloadsBridge("rvxImportUpstream", listOf(FORMAT, SPEC, UPSTREAM), UPSTREAM, 13, """
        iget-boolean v0, p0, $OFFLINE_WRITER
        if-eqz v0, :stock
        iget-object v0, p1, $FORMAT->c:$STRING
        invoke-static {v0}, $IMPORT->includes($STRING)Z
        move-result v0
        if-eqz v0, :stock

        move-object v0, p3
        move-object v1, p0
        iget-object v2, p2, $SPEC->j:$STRING
        invoke-virtual {p1}, $FORMAT->j()J
        move-result-wide v3
        invoke-virtual {p1}, $FORMAT->k()J
        move-result-wide v5
        const/4 v7, 0x0
        iget-object v8, p0, $WRITER->f:Ljqf;
        if-eqz v8, :prepare
        iget-object v8, p0, $WRITER->g:Ljava/security/Key;
        if-eqz v8, :prepare
        iget-object v8, p0, $WRITER->h:Ljava/security/Key;
        if-eqz v8, :prepare
        const/4 v7, 0x1
        :prepare
        invoke-static/range {v0 .. v7}, $IMPORT->prepare($SOURCE$CANCEL${STRING}JJZ)$TRANSFER
        move-result-object v0
        return-object v0
        :stock
        return-object p3
    """)

    // Mark only the offline transfer's call. The other Lynz.b caller is a cache-fill task, not authorized here.
    offlineWrite.addInstructionsWithLabels(offlineCall, """
        const/4 v10, 0x1
        iput-boolean v10, v4, $OFFLINE_WRITER
        const/4 v10, 0x0
    """)
    // Insert after both stock traffic factories join, before either cache wrapper or its encrypted sink is built.
    write.addInstructionsWithLabels(upstreamFactory + 3, """
        invoke-virtual {v1, v0, v13, v2}, $WRITER->rvxImportUpstream($FORMAT$SPEC$UPSTREAM)$UPSTREAM
        move-result-object v16
    """)
    // Feed the fork file to the stock encrypted sink, not to the optional upstream playback cache.
    write.addInstructionsWithLabels(firstFactory, """
        iget-object v14, v1, $DIRECT_TRANSFER
        if-eqz v14, :stock_factory
        move-object/from16 v16, v14
        goto :cache_pipeline
        :stock_factory
        nop
    """, ExternalLabel("cache_pipeline", cachePipeline))
    mutableClassDefBy(IMPORT).methods.single { it.name == "supported" }.returnEarly(true)
    printInfo("NativeOfflineImport: installed authorized and fork-file offline writer sources on 9.45.51")
}
