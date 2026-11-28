/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.util.Utils.printInfo
import app.morphe.util.numberOfParameterRegisters
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val METADATA = "Lapp/morphe/extension/music/patches/downloads/OfflineWatchMetadata"
private const val HOST = "${METADATA}\$Host;"
private const val REPLY = "${METADATA}\$Reply;"
private const val CANCELLATION = "${METADATA}\$TransportCancellation;"
private const val TAB = "${METADATA}\$Tab;"
private const val VIEW = "${METADATA}\$View;"
private const val REACTION = "${METADATA}\$Reaction;"
private const val FALLBACK = "Lapp/morphe/extension/music/patches/downloads/PlayerFallback;"
private const val WATCH_RUNNER = $$"Lapp/morphe/extension/music/patches/downloads/PlayerFallback$WatchRunner;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"

internal fun BytecodePatchContext.installWatchMetadata() {
    val abi = watchMetadataAbi(packageMetadata.versionName)
    installMetadataTransport(abi)
    installMetadataReply(abi)
    installRetainedWatchDelivery(abi)
    installMetadataTabs(abi)
    installReconnectSurfaces(packageMetadata.versionName, abi.host, abi.reply, abi.proto, abi.tabs.view)
    installWatchReaction(abi.reaction)
    printInfo("OfflineWatchMetadata: isolated next transport and occurrence-owned player tabs/reaction")
}

private data class WatchDeliveryAbi(
    val runner: String, val posted: String, val callback: String, val player: String,
)

/** Observe stock acceptance after cancellation and delivery, independently of when the navigator commits its row. */
private fun BytecodePatchContext.installRetainedWatchDelivery(abi: WatchMetadataAbi) {
    val delivery = when (packageMetadata.versionName) {
        "9.45.51" -> WatchDeliveryAbi("Labsf;", "Labcg;", "Labse;", "Lwjt;")
        "9.40.51" -> WatchDeliveryAbi("Labmf;", "Laazj;", "Labme;", "Lwei;")
        else -> return
    }
    val runner = mutableClassDefBy(delivery.runner)
    fun field(name: String, type: String) = runner.fields.single { it.name == name && it.type == type }
    val request = field("a", abi.request)
    val reply = field("i", abi.reply)
    val callback = field("d", delivery.callback)
    val context = field("c", STRING)
    val cancelled = field("g", "Z")
    val posted = mutableClassDefBy(delivery.posted).methods.single {
        it.name == "run" && it.parameterTypes.isEmpty() && it.returnType == "V"
    }
    val code = posted.implementation!!.instructions.toList()
    val playerCall = "${delivery.callback}->d(${delivery.player})V"
    val watchCall = "${delivery.callback}->g(${abi.reply}$STRING)V"

    fun observe(
        original: String,
        type: String,
        callbackOffset: Int,
        name: String,
        witness: String,
        watch: Boolean,
    ) {
        val invoke = code.indices.single { code[it].getReference<MethodReference>()?.toString() == original }
        val callbackLoad = invoke - callbackOffset
        val call = code[invoke] as FiveRegisterInstruction
        val load = code[callbackLoad] as TwoRegisterInstruction
        check(code[invoke].opcode == Opcode.INVOKE_INTERFACE && code[callbackLoad].opcode == Opcode.IGET_OBJECT &&
            code[callbackLoad].getReference<FieldReference>() == callback && call.registerC == load.registerA)

        val guard = (callbackLoad - 1 downTo callbackLoad - 6).first {
            code[it].getReference<FieldReference>() == cancelled
        }
        check(code[guard].opcode == Opcode.IGET_BOOLEAN &&
            (code[guard] as TwoRegisterInstruction).registerB == load.registerB &&
            code[guard + 1].opcode in setOf(Opcode.IF_NEZ, Opcode.IF_EQZ))

        val deliver = if (watch) {
            val contextLoad = code[callbackLoad + 1] as TwoRegisterInstruction
            check(code[callbackLoad + 1].getReference<FieldReference>() == context &&
                contextLoad.registerB == load.registerB && contextLoad.registerA == call.registerE)
            posted.replaceInstruction(callbackLoad + 1, "nop")
            "iget-object v1, p0, $context\ninvoke-interface {v0, p1, v1}, $original"
        } else "invoke-interface {v0, p1}, $original"
        runner.addDownloadsBridge(name, listOf(type), "V", 4, """
            iget-object v0, p0, $callback
            $deliver
            invoke-static {p0, p1}, $FALLBACK->$witness($OBJECT$OBJECT)V
            return-void
        """)
        val keepRunner = if (watch) "nop" else "check-cast v${call.registerD}, $type"
        posted.replaceInstruction(callbackLoad, keepRunner)
        posted.replaceInstruction(invoke, "invoke-virtual {v${load.registerB}, v${call.registerD}}, " +
            "${delivery.runner}->$name($type)V")
        printInfo("OfflineWatchMetadata: cancellation-guarded ${delivery.posted} delivery $original")
    }

    runner.interfaces.add(WATCH_RUNNER)
    runner.addDownloadsBridge("rvxWatchRequest", emptyList(), OBJECT, 2, """
        iget-object v0, p0, $request
        return-object v0
    """)
    runner.addDownloadsBridge("rvxWatchReply", emptyList(), OBJECT, 2, """
        iget-object v0, p0, $reply
        return-object v0
    """)
    observe(playerCall, delivery.player, 1, "rvxDeliverWatchPlayer", "watchPlayerDelivered", false)
    observe(watchCall, abi.reply, 3, "rvxDeliverWatchReply", "watchPublished", true)
}

private fun BytecodePatchContext.installMetadataTransport(abi: WatchMetadataAbi) {
    val transportAbi = abi.transport
    val host = mutableClassDefBy(abi.host)
    val transport = mutableClassDefBy(transportAbi.type)
    val stockStart = transport.methods.single {
        it.name == transportAbi.extractor && it.parameterTypes.firstOrNull() == abi.request &&
            it.parameterTypes.size == 3 && it.returnType == "V"
    }
    val stockNext = transport.methods.single {
        it.name == transportAbi.dispatch && it.parameterTypes.size in 10..11 && it.returnType == "V"
    }
    val start = stockStart.cloneMutable(name = "rvxStartWatchMetadata")
    val delegate = start.implementation!!.instructions.withIndex().single {
        it.value.getReference<MethodReference>()?.toString() == stockNext.toString()
    }
    val range = delegate.value as RegisterRangeInstruction
    val first = range.startRegister
    val last = first + range.registerCount - 1
    val parameters = stockNext.parameterTypes.map(CharSequence::toString)
    val read = "${transport.type}->rvxReadWatchMetadata(${parameters.joinToString("")})V"
    start.replaceInstruction(delegate.index, "invoke-virtual/range {v$first .. v$last}, $read")
    transport.methods.add(start)

    // Keep the stock request packing and authenticated transport, without the response plugins or next cache.
    val raw = installMetadataCancellation(transportAbi)
    val factory = stockNext.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
        .single { it.definingClass == transport.type && it.name == transportAbi.factory }
    val argumentCount = parameters.size - 5
    val callback = argumentCount + 1
    val receiver = factory.parameterTypes.size
    val flags = transportAbi.rawFlag?.let { "iput-boolean v3, v0, $it" }.orEmpty()
    val extraContext = transportAbi.context?.let { "iget-object v${receiver - 1}, v$receiver, $it" }.orEmpty()
    val arguments = (1..argumentCount).joinToString("\n") { parameter ->
        val move = if (parameters[parameter - 1] == "I") "move/from16" else "move-object/from16"
        "$move v${parameter + 1}, p$parameter"
    }
    val optionals = (1..3).joinToString("\n") { offset ->
        "move-object/from16 v${argumentCount + 1 + offset}, p${callback + 1 + offset}"
    }
    val tracer = argumentCount + 5
    check(tracer + 1 + (if (transportAbi.context == null) 0 else 1) == receiver)
    transport.addDownloadsBridge("rvxReadWatchMetadata", parameters, "V", receiver + 1 + range.registerCount, """
        move-object/from16 v$receiver, p0
        iget-object v0, v$receiver, ${transportAbi.requestContext}
        check-cast v0, ${factory.parameterTypes[0]}
        iget-object v1, v$receiver, ${transportAbi.requestFactory}
        check-cast v1, ${factory.parameterTypes[1]}
        $arguments
        $optionals
        const/4 v$tracer, 0x0
        $extraContext
        invoke-static/range {v0 .. v${receiver - 1}}, $factory
        move-result-object v0
        const/4 v3, 0x0
        $flags
        iget-object v1, v$receiver, ${transportAbi.service}
        check-cast v1, ${transportAbi.serviceType}
        iget-object v1, v1, ${transportAbi.transport}
        move-object/from16 v2, p$callback
        invoke-virtual {v1, v0, v2, v3}, $raw
        return-void
    """)

    val startInvoke = if (AccessFlags.PRIVATE.isSet(start.accessFlags)) "invoke-direct" else "invoke-virtual"
    transport.addDownloadsBridge("rvxFetchWatchMetadata", listOf(abi.request), FUTURE, 4, """
        new-instance v0, ${transportAbi.future}
        invoke-direct {v0}, ${transportAbi.future}-><init>()V
        const/4 v1, 0x0
        $startInvoke {p0, p1, v0, v1}, $start
        return-object v0
    """)

    val parserOwner = checkNotNull(parseByteArrayMethodRef.get()).definingClass
    val parser = mutableClassDefBy(parserOwner).methods.single {
        it.name == "parseFrom" && it.parameterTypes == listOf(parserOwner, "[B", REGISTRY)
    }
    host.interfaces.add(HOST)
    host.addDownloadsBridge("rvxFetchWatchMetadata", listOf("[B"), FUTURE, 4, """
        sget-object v0, ${abi.command}->a:${abi.command}
        invoke-static {}, $REGISTRY->getGeneratedRegistry()$REGISTRY
        move-result-object v1
        invoke-static {v0, p1, v1}, $parser
        move-result-object v0
        check-cast v0, ${abi.command}
        new-instance v1, ${abi.builder}
        invoke-direct {v1}, ${abi.builder}-><init>()V
        iput-object v0, v1, ${abi.builder}->a:${abi.command}
        invoke-virtual {v1}, ${abi.builder}->a()${abi.request}
        move-result-object v1
        iget-object v0, p0, ${host.type}->${abi.transportField}:${transport.type}
        invoke-virtual {v0, v1}, ${transport.type}->rvxFetchWatchMetadata(${abi.request})$FUTURE
        move-result-object v0
        return-object v0
    """)
    host.addDownloadsBridge("rvxDecodeWatchMetadata", listOf(OBJECT), REPLY, 3, """
        check-cast p1, ${abi.proto}
        new-instance v0, ${abi.reply}
        invoke-direct {v0, p1}, ${abi.reply}-><init>(${abi.proto})V
        return-object v0
    """)

    val apply = host.methods.single {
        it.name == abi.apply && it.parameterTypes.firstOrNull() == abi.reply && it.returnType == "V"
    }
    val stockApply = apply.cloneMutable(name = "rvxApplyWatchNext")
    val count = apply.numberOfParameterRegisters
    val wrappedApply = apply.cloneMutable(registerCount = count, clearImplementation = true)
    val applyInvoke = if (AccessFlags.PRIVATE.isSet(apply.accessFlags)) "invoke-direct" else "invoke-virtual"
    wrappedApply.addInstructions(0, """
        $applyInvoke/range {p0 .. p${count - 1}}, $stockApply
        invoke-static {p0, p1}, $METADATA;->applied($OBJECT$OBJECT)V
        return-void
    """)
    host.methods.remove(apply)
    host.methods.add(stockApply)
    host.methods.add(wrappedApply)
}

private fun BytecodePatchContext.installMetadataReply(abi: WatchMetadataAbi) {
    val reply = mutableClassDefBy(abi.reply)
    val request = "${reply.type}->rvxMetadataRequest:$OBJECT"
    reply.interfaces.add(REPLY)
    reply.instanceFields.add(ImmutableField(reply.type, "rvxMetadataRequest", OBJECT,
        AccessFlags.PRIVATE.value, null, emptySet(), emptySet()).toMutable())
    reply.addDownloadsBridge("rvxLocalWatchRequest", emptyList(), OBJECT, 2, """
        iget-object v0, p0, $request
        return-object v0
    """)
    reply.addDownloadsBridge("rvxLocalWatchRequest", listOf(OBJECT), "V", 2, """
        iput-object p1, p0, $request
        return-void
    """)
    reply.addDownloadsBridge("rvxMetadataVideoId", emptyList(), STRING, 2, """
        iget-object v0, p0, ${reply.type}->b:$STRING
        return-object v0
    """)
    reply.addDownloadsBridge("rvxMetadataPlaylistId", emptyList(), STRING, 2, """
        iget-object v0, p0, ${reply.type}->c:$STRING
        return-object v0
    """)
    reply.addDownloadsBridge("rvxMetadataTabs", emptyList(), LIST, 2, """
        iget-object v0, p0, ${reply.type}->a:${abi.proto}
        invoke-static {v0}, ${abi.extractors}->${abi.list}(${abi.proto})$LIST
        move-result-object v0
        return-object v0
    """)
    reply.addDownloadsBridge("rvxMetadataReaction", emptyList(), OBJECT, 2, """
        invoke-static {p0}, ${abi.extractors}->${abi.reaction.reader}(${reply.type})${abi.reaction.renderer}
        move-result-object v0
        return-object v0
    """)
}

private fun BytecodePatchContext.installMetadataCancellation(abi: WatchTransportAbi): String {
    val owner = mutableClassDefBy(abi.raw.substringBefore("->"))
    val raw = owner.methods.single { it.toString() == abi.raw }.cloneMutable(name = "rvxDispatchWatchMetadata")
    val schedules = raw.implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
        val call = instruction.getReference<MethodReference>() ?: return@mapNotNull null
        if (instruction.opcode == Opcode.INVOKE_INTERFACE && call.parameterTypes == listOf(call.returnType)) {
            index to call
        } else null
    }
    val handle = schedules.map { it.second.returnType }.distinct().single()
    check(schedules.size == 2)
    val (cancel, callback) = when (packageMetadata.versionName) {
        "9.45.51", "9.40.51" -> "w" to 4
        "9.15.51" -> "x" to 4
        else -> "r" to 3
    }
    check(classDefBy(handle).methods.any { it.name == cancel && it.parameterTypes.isEmpty() && it.returnType == "V" })
    val result = mutableClassDefBy(abi.future)
    result.interfaces.add(CANCELLATION)
    result.addDownloadsBridge("rvxCancelMetadataTransport", listOf(OBJECT), "V", 2, """
        check-cast p1, $handle
        invoke-interface {p1}, $handle->$cancel()V
        return-void
    """)
    // Both raw factory branches retain the callback in the same argument register.
    schedules.asReversed().forEach { (index, _) ->
        raw.addInstructions(index + 1, """
            move-result-object v0
            invoke-static {v$callback, v$callback, v0}, $METADATA;->bindTransport($FUTURE$CANCELLATION$OBJECT)V
        """)
    }
    owner.methods.add(raw)
    printInfo("OfflineWatchMetadata: cancellable raw next $handle->$cancel()V")
    return raw.toString()
}

private fun BytecodePatchContext.installMetadataTabs(abi: WatchMetadataAbi) {
    val tabs = abi.tabs
    val view = mutableClassDefBy(tabs.view)
    val binder = mutableClassDefBy(tabs.binder).methods.single { it.name == "a" && it.parameterTypes == listOf(OBJECT) }
    val tabBind = binder.implementation!!.instructions.withIndex().single {
        it.value.getReference<MethodReference>()?.toString() == tabs.setter
    }
    val listRegister = (tabBind.value as FiveRegisterInstruction).registerE
    val registers = "{v$listRegister, v${tabs.viewRegister}, v${tabs.replyRegister}}"
    binder.addInstructions(tabBind.index, """
        invoke-static $registers, $METADATA;->visibleTabs($LIST$OBJECT$OBJECT)$LIST
        move-result-object v$listRegister
    """)

    view.interfaces.add(VIEW)
    val append = installMetadataTabAppend(tabs)
    view.addDownloadsBridge("rvxAppendWatchTab", listOf(OBJECT), "V", 4, """
        iget-object v0, p0, ${tabs.controllerField}
        iget-object v1, p0, ${tabs.panelField}
        check-cast p1, ${tabs.tab}
        invoke-virtual {v0, v1, p1}, $append
        return-void
    """)

    val tab = mutableClassDefBy(tabs.tab)
    val classifier = if (tabs.pageType == null) tabs.classifier else installPageTypeId(tabs)
    tab.interfaces.add(TAB)
    tab.addDownloadsBridge("rvxMetadataTabType", emptyList(), "I", 2, """
        iget-object v0, p0, ${tab.type}->${tabs.rendererField}
        instance-of p0, v0, ${tabs.renderer}
        if-eqz p0, :unknown
        check-cast v0, ${tabs.renderer}
        iget-object v0, v0, ${tabs.renderer}->d:${abi.command}
        if-eqz v0, :unknown
        invoke-static {v0}, $classifier
        move-result v0
        return v0
        :unknown
        const/4 v0, 0x0
        return v0
    """)
}

private fun BytecodePatchContext.installMetadataTabAppend(tabs: WatchTabsAbi): String {
    val owner = mutableClassDefBy(tabs.setter.substringBefore("->"))
    val setter = owner.methods.single { it.toString() == tabs.setter }
    val implementation = setter.implementation!!
    check(implementation.tryBlocks.isEmpty())
    // Only the ordinary tab factory and insertion. Exclude the reset, selection and queue scroll.
    val (start, end, countMethod) = when (packageMetadata.versionName) {
        "9.45.51" -> Triple(0x027b, 0x0308, "c")
        "9.40.51" -> Triple(0x027d, 0x030c, "c")
        "9.15.51" -> Triple(0x026c, 0x0304, "d")
        "8.30.54", "8.28.54" -> Triple(0x01bf, 0x022c, "c")
        "8.12.54" -> Triple(0x01c7, 0x0234, "c")
        else -> error("Unknown watch tab ABI for ${packageMetadata.versionName}")
    }
    var address = 0
    val addresses = implementation.instructions.map { instruction ->
        address.also { address += instruction.codeUnits }
    }
    val first = addresses.indexOf(start)
    val last = addresses.indexOf(end)
    val instructions = implementation.instructions
    check(first > 0 && instructions[first].opcode == Opcode.IGET_OBJECT &&
        instructions[last].opcode == Opcode.ADD_INT_LIT8)

    val controls = checkNotNull(instructions.firstNotNullOfOrNull { instruction ->
        val field = instruction.getReference<FieldReference>()
        if (instruction.opcode == Opcode.IGET_OBJECT && field?.definingClass == owner.type &&
            field.type != "Lcom/google/android/apps/youtube/music/ui/tabs/TabbedView;") field else null
    })
    val count = classDefBy(controls.type).methods.single {
        it.name == countMethod && it.parameterTypes.isEmpty() && it.returnType == "I"
    }
    val toggle = instructions.indexOfFirst {
        it.getReference<StringReference>()?.string == "sharedToggleMenuItemMutations"
    }
    val lookup = checkNotNull(instructions[toggle + 1].getReference<MethodReference>())
    val toggleType = checkNotNull(instructions[toggle + 3].getReference<TypeReference>())

    val append = setter.cloneMutable(name = "rvxAppendWatchMetadataTab", parameters = listOf(
        setter.parameters.first(), ImmutableMethodParameter(tabs.tab, emptySet(), null)
    ))
    val body = append.implementation!!
    for (index in body.instructions.lastIndex downTo last) body.removeInstruction(index)
    for (index in first - 1 downTo 0) body.removeInstruction(index)
    val legacy = packageMetadata.versionName.startsWith("8.")
    val tab = if (legacy) 13 else 5
    val controller = if (legacy) 3 else 4
    val mutations = if (legacy) 6 else 3
    val decoration = if (legacy) 12 else 7
    append.addInstructions(0, """
        move-object/from16 v0, p0
        move-object/from16 v1, p1
        move-object/from16 v$tab, p2
        iget-object v$controller, v0, $controls
        invoke-virtual {v$controller}, $count
        move-result v9
        const-string v$mutations, "sharedToggleMenuItemMutations"
        invoke-virtual {v1, v$mutations}, $lookup
        move-result-object v$mutations
        check-cast v$mutations, $toggleType
        const/4 v$decoration, 0x0
    """)
    append.addInstructions(body.instructions.size, "return-void")
    owner.methods.add(append)
    return append.toString()
}

private fun BytecodePatchContext.installPageTypeId(tabs: WatchTabsAbi): String {
    // Older binders construct a page key from the same integer IDs. Classify without allocating that key.
    val owner = mutableClassDefBy(tabs.classifier.substringBefore("->"))
    val stock = owner.methods.single { it.toString() == tabs.classifier }
    val method = stock.cloneMutable(name = "rvxMetadataPageType", returnType = "I",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)
    val instructions = method.implementation!!.instructions
    val conversions = instructions.withIndex().filter {
        it.value.getReference<MethodReference>()?.let { ref ->
            ref.returnType == tabs.pageType && ref.parameterTypes == listOf("I")
        } == true
    }
    check(conversions.size == 3)
    for ((index, instruction) in conversions) {
        val register = (instruction as FiveRegisterInstruction).registerC
        val move = instructions[index + 1] as OneRegisterInstruction
        val result = instructions[index + 2] as OneRegisterInstruction
        check(move.opcode == Opcode.MOVE_RESULT_OBJECT && result.opcode == Opcode.RETURN_OBJECT)
        check(move.registerA == result.registerA)
        method.replaceInstruction(index, "nop")
        method.replaceInstruction(index + 1, "nop")
        method.replaceInstruction(index + 2, "return v$register")
    }
    owner.methods.add(method)
    return method.toString()
}

private fun BytecodePatchContext.installWatchReaction(abi: WatchReactionAbi) {
    val stage = mutableClassDefBy(abi.stage).methods.single { it.name == "handleSequencerStageEvent" }
    val loads = stage.implementation!!.instructions.withIndex().filter {
        if (abi.ownerField == null) {
            it.value.opcode == Opcode.CHECK_CAST && it.value.getReference<TypeReference>()?.type == abi.owner
        } else {
            it.value.opcode == Opcode.IGET_OBJECT &&
                it.value.getReference<FieldReference>()?.toString() == abi.ownerField
        }
    }
    check(loads.isNotEmpty())
    for ((index, instruction) in loads.asReversed()) {
        val ownerRegister = (instruction as OneRegisterInstruction).registerA
        stage.addInstructions(index + 1, "invoke-static {v$ownerRegister}, $METADATA;->reactionOwner($OBJECT)V")
    }
    val owner = mutableClassDefBy(abi.owner)
    val writers = owner.methods.filter {
        (it.name == "a" && it.parameterTypes == listOf(abi.status, STRING)) ||
            (it.name == "c" && it.parameterTypes == listOf(STRING, abi.status))
    }
    check(writers.size == 2)
    writers.forEach { it.addInstructions(0, "invoke-static {}, $METADATA;->reactionChanged()V") }
    val current = "${owner.type}->${abi.current}:${abi.state}"
    val pending = "${owner.type}->${abi.pending}:${abi.state}"
    owner.interfaces.add(REACTION)
    owner.addDownloadsBridge("rvxReactionState", emptyList(), OBJECT, 2, """
        iget-object v0, p0, $current
        return-object v0
    """)
    owner.addDownloadsBridge("rvxShowWatchReaction", listOf(OBJECT, STRING, OBJECT), "V", 7, """
        iget-object v0, p0, $current
        if-ne v0, p3, :done
        iget-object v0, p0, $pending
        if-nez v0, :done
        check-cast p1, ${abi.renderer}
        iget-object v0, p1, ${abi.renderer}->c:${abi.target}
        if-eqz v0, :done
        iget-object v0, v0, ${abi.target}->c:$STRING
        invoke-virtual {p2, v0}, Ljava/lang/String;->equals($OBJECT)Z
        move-result v0
        if-eqz v0, :done
        new-instance v1, ${abi.state}
        invoke-direct {v1, p1}, ${abi.state}-><init>(${abi.renderer})V
        iput-object v1, p0, $current
        iget-object v0, p0, ${owner.type}->${abi.listeners}:Ljava/util/Set;
        invoke-interface {v0}, Ljava/util/Set;->iterator()Ljava/util/Iterator;
        move-result-object v0
        :listeners
        invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z
        move-result v2
        if-eqz v2, :done
        invoke-interface {v0}, Ljava/util/Iterator;->next()$OBJECT
        move-result-object v2
        check-cast v2, ${abi.listener}
        invoke-interface {v2, v1}, ${abi.listener}->a(${abi.state})V
        goto :listeners
        :done
        return-void
    """)
}

private data class WatchMetadataAbi(
    val host: String,
    val apply: String,
    val transportField: String,
    val request: String,
    val builder: String,
    val command: String,
    val reply: String,
    val proto: String,
    val extractors: String,
    val list: String,
    val transport: WatchTransportAbi,
    val tabs: WatchTabsAbi,
    val reaction: WatchReactionAbi,
)

private class WatchTransportAbi(
    val type: String,
    val extractor: String,
    val dispatch: String,
    val factory: String,
    val future: String,
    requestContext: String,
    requestFactory: String,
    service: String,
    val serviceType: String,
    val transport: String,
    val raw: String,
    val rawFlag: String? = null,
    val context: String? = null,
) {
    val requestContext = "$type->$requestContext"
    val requestFactory = "$type->$requestFactory"
    val service = "$type->$service"
}

private class WatchTabsAbi(
    val view: String,
    val binder: String,
    val viewRegister: Int,
    val replyRegister: Int,
    controllerField: String,
    panelField: String,
    val setter: String,
    val tab: String,
    val rendererField: String,
    val renderer: String,
    val classifier: String,
    val pageType: String? = null,
) {
    val controllerField = "$view->$controllerField"
    val panelField = "$view->$panelField"
}

private data class WatchReactionAbi(
    val stage: String,
    val owner: String,
    val state: String,
    val listener: String,
    val renderer: String,
    val target: String,
    val status: String,
    val reader: String,
    val ownerField: String? = null,
    val current: String = "e",
    val pending: String = "f",
    val listeners: String = "c",
)

/** Stock next producer, player tab binder and reaction listener for each Downloads ABI. */
private fun watchMetadataAbi(version: String): WatchMetadataAbi = when (version) {
    "9.45.51" -> WatchMetadataAbi(
        "Lhzx;", "v", "D", "Labpl;", "Labpk;", "Lakda;", "Lwgw;", "Lamgw;", "Lhko;", "bq",
        WatchTransportAbi("Lahzu;", "l", "n", "i", "Lzzy;", "c:$OBJECT", "e:$OBJECT", "a:$OBJECT",
            "Lwva;", "Lwva;->c:Lwuy;", "Lwns;->e(Lwmf;Lzzz;Lwme;)V", rawFlag = "Lwks;->h:Z"),
        WatchTabsAbi("Ljmv;", "Ljml;", 4, 2, "k:Ljmh;", "F:Ladox;", "Ljmh;->l(Ladox;$LIST)V",
            "Lafzz;", "b:$OBJECT", "Laqqu;", "Lhko;->aw(Lakda;)I"),
        WatchReactionAbi("Lhet;", "Lheu;", "Lheq;", "Lher;", "Lamrl;", "Lamsa;", "Lamrz;", "bf"),
    )
    "9.40.51" -> WatchMetadataAbi(
        "Liar;", "u", "D", "Labjm;", "Labjl;", "Lajsj;", "Lwbk;", "Lalvp;", "Lfgk;", "af",
        WatchTransportAbi("Lawzr;", "p", "r", "m", "Lzuz;", "b:$OBJECT", "a:$OBJECT", "d:$OBJECT",
            "Lwpw;", "Lwpw;->c:Lwpu;", "Lwio;->e(Lwgz;Lzva;Lwgy;)V", rawFlag = "Lwfj;->h:Z"),
        WatchTabsAbi("Ljna;", "Ljmr;", 4, 2, "k:Ljmm;", "F:Ladhj;", "Ljmm;->l(Ladhj;$LIST)V",
            "Lafof;", "a:$OBJECT", "Laqej;", "Lhll;->Q(Lajsj;)I"),
        WatchReactionAbi("Lhfr;", "Lhfs;", "Lhfo;", "Lhfp;", "Lamgb;", "Lamgq;", "Lamgp;", "U"),
    )
    "9.15.51" -> WatchMetadataAbi(
        "Lnyb;", "u", "D", "Lazmi;", "Lazmh;", "Lboht;", "Laoyp;", "Lbspe;", "Lkrz;", "n",
        WatchTransportAbi("Lazua;", "f", "e", "a", "Lavxw;", "a:Laiue;", "c:Laqct;", "b:Laqcr;",
            "Laqcr;", "Laqcr;->a:Laqcp;", "Laplg;->f(Lapim;Lavxx;Lapil;)V", context = "Lazua;->d:Lazkw;"),
        WatchTabsAbi("Lrpb;", "Lrnk;", 3, 1, "o:Lrmz;", "L:Lbdlx;", "Lrmz;->n(Lbdlx;$LIST)V",
            "Laoyi;", "a:Lcbaj;", "Lcbaj;", "Lkwt;->a(Lboht;)I"),
        WatchReactionAbi("Llbu;", "Llbw;", "Llbl;", "Llbm;", "Lbtkp;", "Lbtlj;", "Lbtlh;", "d",
            ownerField = "Llbu;->a:Llbw;", current = "h", pending = "i"),
    )
    "8.30.54" -> WatchMetadataAbi(
        "Lnko;", "t", "c", "Lawgh;", "Lawgg;", "Lbjue;", "Lamrt;", "Lbnqt;", "Lkht;", "n",
        WatchTransportAbi("Lawpe;", "g", "e", "f", "Lastx;", "a:Lagxv;", "c:Lantg;", "b:Lante;",
            "Lante;", "Lante;->a:Lantc;", "Landb;->f(Lanbe;Lasty;Lanbd;)V"),
        WatchTabsAbi("Lrap;", "Lqzv;", 5, 2, "o:Lqyk;", "M:Lazvq;", "Lqyk;->n(Lazvq;$LIST)V",
            "Lamrm;", "a:Lbuvi;", "Lbuvi;", "Lqyk;->g(Lbjue;)Laoem;", "Laoem;"),
        WatchReactionAbi("Lkqz;", "Lkrb;", "Lkqt;", "Lkqu;", "Lbojf;", "Lbojx;", "Lbojv;", "d",
            ownerField = "Lkqz;->a:Lkrb;", listeners = "a"),
    )
    "8.28.54" -> WatchMetadataAbi(
        "Lnjh;", "s", "c", "Lavxu;", "Lavxt;", "Lbjfy;", "Lamlk;", "Lbnbl;", "Lkgn;", "n",
        WatchTransportAbi("Lawgr;", "g", "e", "f", "Lasmk;", "a:Laguw;", "c:Lanms;", "b:Lanmq;",
            "Lanmq;", "Lanmq;->a:Lanmo;", "Lamwp;->f(Lamuu;Lasml;Lamut;)V"),
        WatchTabsAbi("Lqzh;", "Lqyn;", 5, 2, "o:Lqxc;", "M:Lazja;", "Lqxc;->n(Lazja;$LIST)V",
            "Lamld;", "a:Lbucj;", "Lbucj;", "Lqxc;->g(Lbjfy;)Lanxw;", "Lanxw;"),
        WatchReactionAbi("Lkps;", "Lkpu;", "Lkpm;", "Lkpn;", "Lbntp;", "Lbnuh;", "Lbnuf;", "d",
            ownerField = "Lkps;->a:Lkpu;", listeners = "a"),
    )
    "8.12.54" -> WatchMetadataAbi(
        "Lncl;", "q", "c", "Latvj;", "Latvi;", "Lbgxm;", "Lakph;", "Lbkrf;", "Lkac;", "o",
        WatchTransportAbi("Lauef;", "g", "e", "f", "Laqma;", "a:Lafjm;", "c:Lalqg;", "b:Lalqf;",
            "Lalqf;", "Lalqf;->a:Lalqd;", "Lalak;->f(Lakyp;Laqmb;Lakyo;)V"),
        WatchTabsAbi("Lqqa;", "Lqpf;", 5, 2, "p:Lqns;", "M:Laxcn;", "Lqns;->m(Laxcn;$LIST)V",
            "Lakpa;", "a:Lbrov;", "Lbrov;", "Lqns;->f(Lbgxm;)Laman;", "Laman;"),
        WatchReactionAbi("Lkjz;", "Lkkb;", "Lkjt;", "Lkju;", "Lblji;", "Lbljx;", "Lbljv;", "d",
            ownerField = "Lkjz;->a:Lkkb;", listeners = "a"),
    )
    else -> error("No watch metadata ABI for $version")
}
