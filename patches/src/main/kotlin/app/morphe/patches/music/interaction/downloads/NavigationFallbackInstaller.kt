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
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.music.interaction.jam.invokeKind
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.addFreeRegisters
import app.morphe.util.cloneMutable
import app.morphe.util.findMutableMethodOf
import app.morphe.util.fingerprint.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val FALLBACK = "Lapp/morphe/extension/music/patches/downloads/PlayerFallback;"
private const val IDENTITY = "Lapp/morphe/extension/music/patches/downloads/PlaybackRequestIdentity;"
private const val NAVIGATOR = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$Navigator;"
private const val WATCH_NAVIGATOR = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$WatchNavigator;"
private const val WATCH_RUNNER = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$WatchRunner;"
private const val OBJECT = "Ljava/lang/Object;"
private const val NAVIGATION = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$Navigation;"
private const val QUEUE_ITEM = $$"Lapp/morphe/extension/music/patches/downloads/QueueItems$Item;"
private const val QUEUE_REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"
private const val SINGLETON_MAP = "Ljava/util/Collections;->singletonMap(" +
    "Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"
private const val QUEUE_ROW = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$QueueRow;"

private data class NavigationAbi(
    val owner: String, val consumer: String, val command: String, val kind: String,
    val projection: String, val projectionField: String, val listMethod: String,
    val provider: String, val providerField: String, val providerGet: String, val storage: String,
    val playback: String, val playbackField: String, val requests: String, val requestsField: String,
    val cancel: String, val publisher: String, val stop: String,
    val publisherField: String = "i", val publishMethod: String = "j", val publishOwnerHelper: String? = null,
)

/** Exact-version ABI receipts; every method and field is checked before the installation mutates anything. */
internal fun BytecodePatchContext.prepareNavigationFallback(requestType: String): () -> Unit {
    val modern = packageMetadata.versionName in setOf("9.45.51", "9.40.51")
    val abi = when (packageMetadata.versionName) {
        "9.45.51" -> NavigationAbi("Licy;", "Licu;", "Labvx;", "Labvw;", "Lidc;", "e", "e",
            "Lawug;", "d", "is", "Lablq;", "Labxq;", "o", "Labrl;", "m", "e", "Lacgv;", "ar",
            "B", "l", "ay")
        "9.40.51" -> NavigationAbi("Lidn;", "Lidj;", "Labpw;", "Labpv;", "Lidr;", "e", "e",
            "Lawgw;", "d", "hX", "Labfq;", "Labrl;", "o", "Labll;", "n", "e", "Lacaj;", "ak")
        "9.15.51" -> NavigationAbi("Loji;", "Loji;", "Lazzm;", "Lazzl;", "Lojp;", "b", "f",
            "Lckoh;", "h", "gG", "Lazbj;", "Lbacw;", "d", "Lazpx;", "p", "d", "Lbazk;", "c")
        "8.30.54" -> NavigationAbi("Lnun;", "Lnun;", "Lawvp;", "Lawvo;", "Lnuu;", "b", "f",
            "Lccoj;", "h", "fY", "Lavvb;", "Lawyy;", "d", "Lawkj;", "p", "d", "Laxsa;", "c")
        "8.28.54" -> NavigationAbi("Lntf;", "Lntf;", "Lawnc;", "Lawnb;", "Lntm;", "b", "f",
            "Lcbua;", "h", "fT", "Lavmq;", "Lawql;", "d", "Lawbw;", "p", "d", "Laxht;", "c")
        "8.12.54" -> NavigationAbi("Lnlq;", "Lnlq;", "Laukp;", "Lauko;", "Lnlx;", "b", "f",
            "Lbzau;", "h", "a", "Latkq;", "Lauob;", "d", "Latzk;", "p", "d", "Laven;", "c")
        else -> error("native queue fallback has no ABI for this version")
    }
    fun field(owner: String, name: String, type: String) = classDefBy(owner).fields.single {
        it.name == name && it.type == type
    }
    fun method(owner: String, name: String, parameters: List<String>, result: String) =
        classDefBy(owner).methods.single {
            it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters && it.returnType == result
        }
    val itemAccess: Pair<MethodReference, MethodReference> = when (packageMetadata.versionName) {
        "9.45.51" -> method("Labmf;", "t", emptyList(), "Ljava/lang/String;") to
            method("Labmg;", "m", emptyList(), "J")
        "9.40.51" -> method("Labgf;", "t", emptyList(), "Ljava/lang/String;") to
            method("Labgg;", "m", emptyList(), "J")
        "9.15.51" -> method("Lazcf;", "t", emptyList(), "Ljava/lang/String;") to
            method("Lazcj;", "m", emptyList(), "J")
        "8.30.54" -> method("Lavvx;", "s", emptyList(), "Ljava/lang/String;") to
            method("Lavwb;", "o", emptyList(), "Ljava/lang/Long;")
        "8.28.54" -> method("Lavnm;", "s", emptyList(), "Ljava/lang/String;") to
            method("Lmzn;", "o", emptyList(), "Ljava/lang/Long;")
        "8.12.54" -> method("Latlm;", "r", emptyList(), "Ljava/lang/String;") to
            method("Lmte;", "n", emptyList(), "Ljava/lang/Long;")
        else -> error("native queue item access has no ABI for this version")
    }
    val (videoId, persistentId) = itemAccess
    val rendererGetter = Fingerprint(
        name = "rvxRenderer",
        returnType = "Ljava/lang/Object;",
        parameters = emptyList(),
        custom = { _, owner -> QUEUE_ITEM in owner.interfaces },
    ).matchSingle().method
    val rendererType = rendererGetter.implementation!!.instructions.mapNotNull {
        it.getReference<FieldReference>()
    }.single().type
    fun hasItemContract(type: String): Boolean = type == videoId.definingClass ||
        classDefByOrNull(type)?.interfaces?.any(::hasItemContract) == true
    val rowFields = mutableListOf<FieldReference>()
    classDefForEach { candidate ->
        if (candidate.interfaces.none(::hasItemContract)) return@classDefForEach
        val fields = candidate.methods.filter {
            it.parameterTypes.isEmpty() && it.returnType == requestType
        }.flatMap { getter ->
            getter.implementation?.instructions?.mapNotNull { it.getReference<FieldReference>() }.orEmpty()
        }.filter { it.definingClass == candidate.type && it.type == rendererType }.distinctBy(FieldReference::toString)
        fields.singleOrNull()?.let(rowFields::add)
    }
    check(rowFields.size >= 2) { "player fallback: single and paired-renderer queue rows not resolved" }
    val rendererDefault = classDefBy(rendererType).fields.single {
        it.type == rendererType && AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val parserOwner = checkNotNull(parseByteArrayMethodRef.get()).definingClass
    val parser = mutableClassDefBy(parserOwner).methods.single {
        it.name == "parseFrom" && it.parameterTypes == listOf(parserOwner, "[B", QUEUE_REGISTRY)
    }
    val projection = field(abi.owner, abi.projectionField, abi.projection)
    val list = method(abi.projection, abi.listMethod, emptyList(), "Ljava/util/List;")
    val provider = field(abi.owner, abi.providerField, abi.provider)
    val providerGet = method(abi.provider, abi.providerGet, emptyList(), "Ljava/lang/Object;")
    val current = method(abi.storage, "a", emptyList(), "I")
    val playback = field(abi.owner, abi.playbackField, abi.playback)
    val requests = field(abi.playback, abi.requestsField, abi.requests)
    val cancel = method(abi.requests, abi.cancel, emptyList(), "V")
    val runner = classDefBy(abi.requests).fields.single { it.name == "g" }
    val runnerRequest = field(runner.type, "a", requestType)
    val rowRequest = classDefBy(videoId.definingClass).methods.single {
        it.parameterTypes.isEmpty() && it.returnType == requestType
    }
    val jumpKind = field(abi.kind, "e", abi.kind)
    val jumpConstructor = classDefBy(abi.command).methods.single {
        it.name == "<init>" && it.parameterTypes.size == 4 && it.parameterTypes[0] == abi.kind &&
            it.parameterTypes[1] == requestType && it.parameterTypes[3] == "Ljava/util/Map;"
    }
    val clone = classDefBy(requestType).methods.single {
        it.name == "f" && it.parameterTypes.isEmpty()
    }
    val start = field(clone.returnType, if (modern) "k" else "j", "J")
    val pause = method(clone.returnType, "f", listOf("Z"), "V")
    val build = method(clone.returnType, "a", emptyList(), requestType)
    check(listOf(abi.owner, abi.projection).any { type ->
        classDefBy(type).methods.any { candidate ->
            candidate.implementation?.instructions?.any {
                it.getReference<StringReference>()?.string == "avSwitchPlaybackStartTime"
            } == true
        }
    })
    val dispatch = method(abi.owner, if (modern) "i" else "d", listOf(abi.command), "V")
    val stop = method(abi.playback, abi.stop, if (modern) listOf("Z", "I") else emptyList(), "V")
    val publisher = field(abi.playback, abi.publisherField, abi.publisher)
    val publishStopped = method(abi.publisher,
        if (packageMetadata.versionName == "8.12.54") "i" else abi.publishMethod, listOf("Z"), "V")
    val publishOwner = abi.publishOwnerHelper?.let { method(abi.playback, it, listOf("Z"), "V") }
    val kind = field(abi.command, "e", abi.kind)
    val consumer = classDefBy(abi.consumer).methods.single {
        if (modern) it.name == "invokeSuspend" else it.parameterTypes == listOf(abi.command) && it.returnType == "V"
    }
    val code = consumer.implementation!!.instructions.toList()
    val resolve = code.indices.single { index ->
        val call = code[index].getReference<MethodReference>()
        code[index].opcode == Opcode.INVOKE_INTERFACE && call?.parameterTypes == listOf(abi.command) &&
            call.returnType == "I"
    }
    check(code[resolve + 1].opcode == Opcode.MOVE_RESULT)
    val indexRegister = (code[resolve + 1] as OneRegisterInstruction).registerA
    val commandRegister = (code[resolve] as FiveRegisterInstruction).registerD
    val ownerRegister = when {
        modern -> consumer.implementation!!.registerCount - 1
        packageMetadata.versionName == "8.12.54" -> 0
        else -> consumer.implementation!!.registerCount - 2
    }
    check(listOf(indexRegister, commandRegister, ownerRegister).all { it < 16 })
    val installWatchCommit = prepareWatchCommit(
        abi, consumer, resolve, ownerRegister, requestType, videoId.definingClass, provider, providerGet, current,
    )
    val playRecovery = preparePlayRecovery(playback, providerGet)

    return {
        val owner = mutableClassDefBy(abi.owner)
        owner.interfaces.add(NAVIGATOR)
        owner.addDownloadsBridge("rvxItems", emptyList(), "Ljava/util/List;", 2, """
            iget-object v0, p0, $projection
            invoke-virtual {v0}, $list
            move-result-object v0
            return-object v0
        """)
        owner.addDownloadsBridge("rvxPlayback", emptyList(), "Ljava/lang/Object;", 2, """
            iget-object v0, p0, $playback
            return-object v0
        """)
        owner.addDownloadsBridge("rvxCurrent", emptyList(), "I", 2, """
            iget-object v0, p0, $provider
            invoke-interface {v0}, $providerGet
            move-result-object v0
            check-cast v0, ${abi.storage}
            invoke-virtual {v0}, $current
            move-result v0
            return v0
        """)
        owner.addDownloadsBridge("rvxVideoId", listOf("Ljava/lang/Object;"), "Ljava/lang/String;", 3, """
            check-cast p1, ${videoId.definingClass}
            ${invokeKind(videoId)} {p1}, $videoId
            move-result-object v0
            return-object v0
        """)
        val readIdentity = if (persistentId.returnType == "J") "move-result-wide v0" else """
            move-result-object v0
            invoke-virtual {v0}, Ljava/lang/Long;->longValue()J
            move-result-wide v0
        """
        owner.addDownloadsBridge("rvxItemId", listOf("Ljava/lang/Object;"), "J", 4, """
            check-cast p1, ${persistentId.definingClass}
            ${invokeKind(persistentId)} {p1}, $persistentId
            $readIdentity
            return-wide v0
        """)
        owner.addDownloadsBridge("rvxOwnsRequest", listOf("Ljava/lang/Object;"), "Z", 4, """
            iget-object v0, p0, $playback
            iget-object v0, v0, $requests
            iget-object v0, v0, $runner
            const/4 v1, 0x0
            if-eqz v0, :done
            iget-object v0, v0, $runnerRequest
            invoke-static {v0, p1}, $IDENTITY->same(Ljava/lang/Object;Ljava/lang/Object;)Z
            move-result v1
            :done
            return v1
        """)
        owner.addDownloadsBridge("rvxCancelLoad", emptyList(), "V", 3, """
            iget-object v0, p0, $playback
            iget-object v0, v0, $requests
            invoke-virtual {v0}, $cancel
            return-void
        """)
        owner.addDownloadsBridge("rvxJump", listOf("Ljava/lang/Object;", "Z"), "Ljava/lang/Object;", 8, """
            check-cast p1, ${rowRequest.definingClass}
            ${invokeKind(rowRequest)} {p1}, $rowRequest
            move-result-object v1
            if-eqz p2, :row_ready
            invoke-virtual {v1}, $clone
            move-result-object v1
            const-wide/16 v2, 0x0
            iput-wide v2, v1, $start
            const/4 v2, 0x0
            invoke-virtual {v1, v2}, $pause
            invoke-virtual {v1}, $build
            move-result-object v1
            :row_ready
            new-instance v0, ${abi.command}
            sget-object v2, $jumpKind
            const/4 v3, 0x0
            const/4 v4, 0x0
            if-eqz p2, :command_ready
            const-wide/16 v3, 0x0
            invoke-static {v3, v4}, Ljava/lang/Long;->valueOf(J)Ljava/lang/Long;
            move-result-object v4
            const-string v3, "avSwitchPlaybackStartTime"
            invoke-static {v3, v4}, $SINGLETON_MAP
            move-result-object v4
            const/4 v3, 0x0
            :command_ready
            invoke-direct {v0, v2, v1, v3, v4}, $jumpConstructor
            return-object v0
        """)
        owner.addDownloadsBridge("rvxDispatch", listOf("Ljava/lang/Object;"), "V", 3, """
            check-cast p1, ${abi.command}
            invoke-virtual {p0, p1}, $dispatch
            return-void
        """)
        val stopCall = if (modern) """
            const/4 v1, 0x0
            const/4 v2, 0x1
            invoke-virtual {v0, v1, v2}, $stop
        """ else "invoke-virtual {v0}, $stop"
        val publish = if (publishOwner != null) {
            mutableClassDefBy(abi.playback).addDownloadsBridge("rvxPublishStopped", emptyList(), "V", 2, """
                const/4 v0, 0x0
                invoke-direct {p0, v0}, $publishOwner
                return-void
            """)
            "invoke-virtual {v0}, ${abi.playback}->rvxPublishStopped()V"
        } else """
            iget-object v0, v0, $publisher
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $publishStopped
        """
        owner.addDownloadsBridge("rvxCancelAndStop", emptyList(), "V", 4, """
            invoke-virtual {p0}, ${abi.owner}->rvxCancelLoad()V
            iget-object v0, p0, $playback
            $stopCall
            $publish
            return-void
        """)
        val command = mutableClassDefBy(abi.command)
        command.interfaces.add(NAVIGATION)
        command.instanceFields.add(ImmutableField(abi.command, "rvxRecovery", "Ljava/lang/Object;",
            AccessFlags.PRIVATE.value, null, emptySet(), emptySet()).toMutable())
        command.addDownloadsBridge("rvxRecovery", emptyList(), "Ljava/lang/Object;", 2, """
            iget-object v0, p0, ${abi.command}->rvxRecovery:Ljava/lang/Object;
            return-object v0
        """)
        command.addDownloadsBridge("rvxRecovery", listOf("Ljava/lang/Object;"), "V", 2, """
            iput-object p1, p0, ${abi.command}->rvxRecovery:Ljava/lang/Object;
            return-void
        """)
        command.addDownloadsBridge("rvxKind", emptyList(), "Ljava/lang/Enum;", 2, """
            iget-object v0, p0, $kind
            return-object v0
        """)
        val choose = "$FALLBACK->chooseIndex(Ljava/lang/Object;Ljava/lang/Object;I)I"
        val consumerOwner = mutableClassDefBy(abi.consumer)
        val mutableConsumer = consumerOwner.findMutableMethodOf(consumer)
        val (installedConsumer, chooseAt, capture) = installWatchCommit?.invoke(owner, consumerOwner, mutableConsumer)
            ?: Triple(mutableConsumer, resolve + 2, "")
        installedConsumer.addInstructions(chooseAt, """
            invoke-static {v$ownerRegister, v$commandRegister, v$indexRegister}, $choose
            move-result v$indexRegister
            $capture
        """)
        owner.findMutableMethodOf(dispatch).addInstructions(0, """
            invoke-static/range {p0 .. p1}, $FALLBACK->navigationStarted(Ljava/lang/Object;Ljava/lang/Object;)V
        """)
        owner.methods.filter { it.name == "<init>" }.forEach { constructor ->
            val returns = constructor.implementation!!.instructions.toList().indices.filter {
                constructor.implementation!!.instructions.elementAt(it).opcode == Opcode.RETURN_VOID
            }
            returns.reversed().forEach { index ->
                constructor.addInstructions(index,
                    "invoke-static/range {p0 .. p0}, $FALLBACK->captureNavigator(Ljava/lang/Object;)V")
            }
        }
        rowFields.forEach { field ->
            val item = mutableClassDefBy(field.definingClass)
            item.interfaces.add(QUEUE_ROW)
            item.addDownloadsBridge("rvxQueueRenderer", emptyList(), "Ljava/lang/Object;", 2, """
                iget-object v0, p0, $field
                return-object v0
            """)
            // The metadata-only apply path must keep the native row object and its occurrence identity.
            val rendererField = item.fields.single { it.name == field.name && it.type == field.type }
            rendererField.setAccessFlags(
                (rendererField.accessFlags and AccessFlags.FINAL.value.inv()) or AccessFlags.VOLATILE.value,
            )
            item.addDownloadsBridge("rvxParseArtworkRenderer", listOf("[B"), "Ljava/lang/Object;", 4, """
                sget-object v0, $rendererDefault
                invoke-static {}, $QUEUE_REGISTRY->getGeneratedRegistry()$QUEUE_REGISTRY
                move-result-object v1
                invoke-static {v0, p1, v1}, $parser
                move-result-object v0
                return-object v0
            """)
            item.addDownloadsBridge("rvxApplyArtworkRenderer", List(2) { "Ljava/lang/Object;" }, "V", 4, """
                iget-object v0, p0, $field
                if-ne v0, p1, :rvx_done
                check-cast p2, $rendererType
                iput-object p2, p0, $field
                :rvx_done
                return-void
            """)
        }
        playRecovery()
        printInfo("PlayerFallback: native queue ${consumer.definingClass}->${consumer.name}, projection $list")
    }
}

private data class WatchCommitAbi(
    val result: String, val success: String, val queue: String, val row: String,
    val decorator: String, val delegateOwner: String, val delegateType: String,
    val retainedHandler: String, val retainedResult: String, val event: String, val playbackBuilder: String,
    val dispatcher: String, val dispatchMethod: String, val requestDecorator: String,
)

/** Preserve the selection across suspension, then observe only the stock cursor-write call with its actual row. */
private fun BytecodePatchContext.prepareWatchCommit(
    abi: NavigationAbi,
    consumer: Method,
    resolve: Int,
    navigatorRegister: Int,
    requestType: String,
    rowType: String,
    provider: FieldReference,
    providerGet: MethodReference,
    current: Method,
): ((MutableClass, MutableClass, MutableMethod) -> Triple<MutableMethod, Int, String>)? {
    val watch = when (packageMetadata.versionName) {
        "9.45.51" -> WatchCommitAbi("Labwt;", "Labyj;", "Lablf;", "Labmf;", "Lhzm;", "Labla;", "Lablm;",
            "Lidm;", "Lidr;", "Labwh;", "Labwz;", "Labyo;", "j", "Labwf;")
        "9.40.51" -> WatchCommitAbi("Labqp;", "Labsd;", "Labff;", "Labgf;", "Liag;", "Labfa;", "Labfm;",
            "Liec;", "Lieh;", "Labqe;", "Labqv;", "Labsj;", "k", "Labqc;")
        else -> {
            printWarn("OfflineWatchMetadata: using legacy Watch apply on ${packageMetadata.versionName}")
            return null
        }
    }
    check(watch.row == rowType)
    val commit = classDefBy(abi.owner).methods.single {
        it.name == "j" && it.parameterTypes == listOf("I", watch.result) && it.returnType == "V"
    }
    val requestGetter = "${watch.success}->a()$requestType"
    val cursorWrite = "${watch.queue}->M(${watch.row})I"
    val commitCode = commit.implementation!!.instructions.toList()
    val result = commitCode.indices.single {
        commitCode[it].getReference<MethodReference>()?.toString() == requestGetter
    }
    val write = commitCode.indices.single { commitCode[it].getReference<MethodReference>()?.toString() == cursorWrite }
    val registers = commit.implementation!!.registerCount
    val requestRegister = (commitCode[result + 1] as OneRegisterInstruction).registerA
    val writeRegisters = commitCode[write] as FiveRegisterInstruction
    check(registers + 1 < 16 && write > result + 1 && commitCode[result + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        commitCode[write + 1].opcode != Opcode.MOVE_RESULT && commitCode[write].opcode == Opcode.INVOKE_INTERFACE)

    val delegate = classDefBy(watch.delegateOwner).fields.single {
        it.name == "k" && it.type == watch.delegateType && AccessFlags.PUBLIC.isSet(it.accessFlags)
    }
    val storage = classDefBy(abi.storage)
    val model = current.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }.single()
    check(model.definingClass == storage.type && model.type == watch.delegateType)
    val publish = storage.methods.single {
        it.name == "x" && it.parameterTypes.firstOrNull() == model.type && it.returnType == "V"
    }
    val publishCode = publish.implementation!!.instructions.toList()
    val modelWrite = publishCode.indices.single {
        publishCode[it].opcode == Opcode.IPUT_OBJECT && publishCode[it].getReference<FieldReference>() == model
    }
    val published = publishCode[modelWrite] as TwoRegisterInstruction
    check(published.registerB < 16)
    check(classDefBy(watch.decorator).superclass == watch.delegateOwner)
    val delegatedWrite = classDefBy(watch.delegateOwner).methods.single {
        it.name == "M" && it.parameterTypes == listOf(watch.row) && it.returnType == "I"
    }
    check(delegatedWrite.implementation!!.instructions.first().getReference<FieldReference>() == delegate)

    val consumeCode = consumer.implementation!!.instructions.toList()
    val commitCall = consumeCode.indices.single { consumeCode[it].getReference<MethodReference>() == commit }
    val commitArguments = consumeCode[commitCall] as FiveRegisterInstruction
    val consumeRegisters = consumer.implementation!!.registerCount
    check(consumeRegisters < 16 && commitArguments.registerCount == 3 &&
        consumeCode[commitCall].opcode == Opcode.INVOKE_VIRTUAL)
    val managerRunner = classDefBy(abi.requests).fields.single { it.name == "g" }
    val manager = classDefBy(abi.playback).fields.single { it.name == abi.requestsField && it.type == abi.requests }
    val playback = classDefBy(abi.owner).fields.single { it.name == abi.playbackField && it.type == abi.playback }
    val parameters = "I${watch.result}"
    val installDispatchCopies = prepareWatchDispatchCopies(watch, requestType)
    // Explicit Watch/playlist playback starts here without entering the indexed navigation consumer.
    val start = classDefBy(abi.owner).methods.single {
        it.name == "b" && it.returnType == OBJECT && it.parameterTypes.size == 3 &&
            it.parameterTypes.first() == requestType
    }

    return { owner, continuation, original ->
        installDispatchCopies()
        owner.findMutableMethodOf(start).addInstructions(0,
            "invoke-static/range {p0 .. p0}, $FALLBACK->captureNavigator($OBJECT)V")

        val queueStorage = mutableClassDefBy(storage.type)
        queueStorage.addDownloadsBridge("rvxWatchQueue", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $model
            instance-of p0, v0, ${watch.decorator}
            if-eqz p0, :unwrapped
            check-cast v0, ${watch.decorator}
            iget-object v0, v0, $delegate
            :unwrapped
            return-object v0
        """)
        queueStorage.addDownloadsBridge("rvxPublishWatchQueue", emptyList(), "V", 2, """
            invoke-virtual {p0}, ${storage.type}->rvxWatchQueue()$OBJECT
            move-result-object v0
            invoke-static {v0}, $FALLBACK->watchQueuePublished($OBJECT)V
            return-void
        """)
        queueStorage.findMutableMethodOf(publish).addInstructions(modelWrite + 1, """
            invoke-virtual {v${published.registerB}}, ${storage.type}->rvxPublishWatchQueue()V
        """)
        owner.addDownloadsBridge("rvxCurrentWatchQueue", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $provider
            invoke-interface {v0}, $providerGet
            move-result-object v0
            check-cast v0, ${storage.type}
            if-eqz v0, :done
            invoke-virtual {v0}, ${storage.type}->rvxWatchQueue()$OBJECT
            move-result-object v0
            :done
            return-object v0
        """)

        owner.interfaces.add(WATCH_NAVIGATOR)
        owner.addDownloadsBridge("rvxCurrentWatchRunner", emptyList(), WATCH_RUNNER, 2, """
            iget-object v0, p0, $playback
            if-eqz v0, :done
            iget-object v0, v0, $manager
            if-eqz v0, :done
            iget-object v0, v0, $managerRunner
            return-object v0
            :done
            const/4 v0, 0x0
            return-object v0
        """)
        owner.addDownloadsBridge("rvxObserveWatchCommit",
            listOf(watch.queue, watch.row, requestType, OBJECT), "V", 6, """
            instance-of v0, p1, ${watch.decorator}
            if-eqz v0, :unwrapped
            check-cast p1, ${watch.decorator}
            iget-object p1, p1, $delegate
            :unwrapped
            invoke-static {p0, p1, p2, p3, p4}, $FALLBACK->watchCommitted($OBJECT$OBJECT$OBJECT$OBJECT$OBJECT)V
            return-void
        """)

        // Keep both the originating coroutine's selection and the result's request after stock reuses its registers.
        val commitParameters = commit.parameters + ImmutableMethodParameter(OBJECT, null, null)
        val observed = commit.cloneMutable(name = "rvxWatchCommit", parameters = commitParameters,
            additionalRegisters = 2)
        val shift = commitParameters.size + 1
        val savedRequest = registers + 1
        val postCommit = "${abi.owner}->rvxObserveWatchCommit(${watch.queue}${watch.row}$requestType$OBJECT)V"
        val postRegisters = listOf(registers - 3, writeRegisters.registerC, writeRegisters.registerD,
            savedRequest, registers).joinToString(", ") { "v$it" }
        observed.addInstructions(write + shift + 1, "invoke-virtual {$postRegisters}, $postCommit")
        observed.addInstructions(result + shift + 2, "move-object v$savedRequest, v$requestRegister")
        observed.addInstructions(shift, "const/4 v$savedRequest, 0x0")
        owner.methods.add(observed)

        val field = "${continuation.type}->rvxWatchSelection:$OBJECT"
        continuation.instanceFields.add(ImmutableField(continuation.type, "rvxWatchSelection", OBJECT,
            AccessFlags.PRIVATE.value, null, emptySet(), emptySet()).toMutable())
        continuation.addDownloadsBridge("rvxCaptureWatchSelection", listOf(OBJECT), "V", 3, """
            invoke-static {p1}, $FALLBACK->watchSelection($OBJECT)$OBJECT
            move-result-object v0
            iput-object v0, p0, $field
            return-void
        """)
        continuation.addDownloadsBridge("rvxCommitWatchSelection", listOf(abi.owner, "I", watch.result), "V", 5, """
            iget-object v0, p0, $field
            if-eqz v0, :stock
            invoke-virtual {p1, p2, p3, v0}, ${abi.owner}->rvxWatchCommit($parameters$OBJECT)V
            return-void
            :stock
            invoke-virtual {p1, p2, p3}, $commit
            return-void
        """)
        val (mutable, offset) = original.addFreeRegisters(2)
        val target = "${continuation.type}->rvxCommitWatchSelection(${abi.owner}$parameters)V"
        val args = "v$consumeRegisters, v${commitArguments.registerC}, v${commitArguments.registerD}," +
            " v${commitArguments.registerE}"
        val capture = "invoke-virtual {v$consumeRegisters, v$navigatorRegister}," +
            " ${continuation.type}->rvxCaptureWatchSelection($OBJECT)V"
        mutable.replaceInstruction(commitCall + offset, "invoke-virtual {$args}, $target")
        printInfo("OfflineWatchMetadata: post-cursor commit $commit; request $requestGetter; row $cursorWrite")
        Triple(mutable, resolve + offset + 2, capture)
    }
}

/** Join only the native copies of this successful retained-navigation payload, not arbitrary request builds. */
private fun BytecodePatchContext.prepareWatchDispatchCopies(watch: WatchCommitAbi, request: String): () -> Unit {
    val handler = classDefBy(watch.retainedHandler).methods.single {
        it.name == "b" && it.parameterTypes == listOf(watch.result, watch.event) && it.returnType == "V"
    }
    val handlerCode = handler.implementation!!.instructions.toList()
    val setRequest = "${watch.playbackBuilder}->b($request)V"
    val payloadGetter = "${watch.retainedResult}->a()$request"
    val set = handlerCode.indices.single { handlerCode[it].getReference<MethodReference>()?.toString() == setRequest }
    // Both build branches converge before the final payload read. The original and the dispatch copy are both live.
    val source = (set - 1 downTo 0).first {
        handlerCode[it].getReference<MethodReference>()?.toString() == payloadGetter
    }
    check(set == source + 6 && handlerCode[source + 1].opcode == Opcode.MOVE_RESULT_OBJECT &&
        handlerCode[set - 1].opcode == Opcode.MOVE_RESULT_OBJECT && handlerCode[set].opcode == Opcode.INVOKE_VIRTUAL)
    val sourceRegister = (handlerCode[source + 1] as OneRegisterInstruction).registerA
    val setRegisters = handlerCode[set] as FiveRegisterInstruction
    check(setRegisters.registerCount == 2 && sourceRegister == setRegisters.registerC &&
        sourceRegister != setRegisters.registerD &&
        (handlerCode[set - 1] as OneRegisterInstruction).registerA == setRegisters.registerC)

    val dispatcher = classDefBy(watch.dispatcher).methods.single {
        it.name == watch.dispatchMethod && it.returnType == "V" &&
            it.parameterTypes == listOf(watch.success, watch.event, "Ljava/util/function/Predicate;")
    }
    val dispatcherCode = dispatcher.implementation!!.instructions.toList()
    val decorate = "${watch.requestDecorator}->b($request)$request"
    val decorateAt = dispatcherCode.indices.single {
        dispatcherCode[it].getReference<MethodReference>()?.toString() == decorate
    }
    check(dispatcherCode[decorateAt].opcode == Opcode.INVOKE_INTERFACE &&
        dispatcherCode[decorateAt + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
    val decorateRegisters = dispatcherCode[decorateAt] as FiveRegisterInstruction
    val decoratedRegister = (dispatcherCode[decorateAt + 1] as OneRegisterInstruction).registerA
    check(decorateRegisters.registerCount == 2 && decoratedRegister != decorateRegisters.registerD)
    val noteCopy = "$FALLBACK->noteWatchRequestCopy($OBJECT$OBJECT)V"

    return {
        mutableClassDefBy(watch.retainedHandler).findMutableMethodOf(handler).addInstructions(source + 2, """
            invoke-static {v${setRegisters.registerD}, v$sourceRegister}, $noteCopy
        """)
        mutableClassDefBy(watch.dispatcher).findMutableMethodOf(dispatcher).addInstructions(decorateAt + 2, """
            invoke-static {v$decoratedRegister, v${decorateRegisters.registerD}}, $noteCopy
        """)
        printInfo("OfflineWatchMetadata: retained dispatch request copies $handler and $dispatcher")
    }
}
