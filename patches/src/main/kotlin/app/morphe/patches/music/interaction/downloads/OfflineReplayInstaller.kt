/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val REPLAY = "Lapp/morphe/extension/music/patches/downloads/OfflineReplayHooks;"
private const val NEXT_REQUEST = $$"Lapp/morphe/extension/music/patches/downloads/OfflineReplay$NextRequest;"
private const val STREAM_RESPONSE = $$"Lapp/morphe/extension/music/patches/downloads/StreamSwap$Response;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val MAP = "Ljava/util/Map;"
private const val LIST = "Ljava/util/List;"
private const val FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val EXECUTOR = "Ljava/util/concurrent/ScheduledExecutorService;"
private const val REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"

private val REPLAY_VERSIONS = setOf("9.40.51", "9.15.51", "8.30.54")

/** The experiment flag the offline watch command gate reads in every supported version (the id is the same). */
private const val GATE_EXPERIMENT = 0x02b88604L

/** Message of the failure the stock player lookup ends with when a song was not downloaded the stock way. */
private const val UNPLAYABLE = "Unplayable Video"

/** The two strings only the bottom bar's tab navigation uses together (`FEmusic_search` and its back action). */
private const val TAB_SEARCH_PAGE = "FEmusic_search"
private const val TAB_HIDE_SEARCH_BACK = "hide_search_back_action"

/** Message of the failure the Library chip loader ends with for a continuation it does not know. */
private const val UNSUPPORTED_CONTINUATION = "Unsupported continuation: "

/**
 * Offline playback of stream copies, in 9.40.51 / 9.15.51 / 8.30.54 order:
 * gate `Lgup.c` / `Lkch.c` / `Ljto.c`, player lookup `Laalc.c` / `Laxlp.c` / `Lauhp.c`,
 * watch-next entry `Lhyv.b` / `Lntb.b` / `Lnez.b`, watch-next request `Labjm` / `Lazmi` / `Lawgh`,
 * the Library chip loader (`Lhtd.a` in 9.40.51) and the tab navigation (`Lhis.c` in 9.40.51).
 * Every dependency of every hook is resolved before anything is changed, and the hooks go in together or not at all:
 * a version whose shape is unfamiliar only warns and plays as before. Needs [installStreamSwap]: the stored response
 * gets the same audio swap an online one does.
 */
internal fun BytecodePatchContext.installOfflineReplay() {
    if (packageMetadata.versionName !in REPLAY_VERSIONS) {
        printInfo("OfflineReplay: not available on this Music version; playback unchanged.")
        return
    }

    try {
        val commandType = nativeDownloadsCommandResolverFingerprint.method.parameterTypes[1].toString()
        val supported = mutableClassDefBy(REPLAY).methods.single { it.name == "supported" }
        val installGate = prepareReplayGate(commandType)
        val (requestType, installTail) = prepareReplayTail()
        val installNext = prepareReplayNext(requestType, commandType)
        val installChip = prepareLibraryChip()
        val installTab = prepareTabNavigation()

        installGate()
        installTail()
        installNext()
        installChip()
        installTab()
        supported.returnEarly(true)
        printInfo(
            "OfflineReplay: gate, player lookup, watch-next, library chip and tab navigation hooked; " +
                "request $requestType",
        )
    } catch (ex: Exception) {
        printWarn("OfflineReplay: hooks unavailable; online playback unchanged: ${ex.message}")
    }
}

/**
 * The offline watch command resolver (`Laocx` extension 57703777). Behind an experiment flag it either drops the
 * command while the app counts itself offline or goes on to the watch endpoint handler `d(command, message, context)`.
 * Right after the flag is read the song's id is asked of [REPLAY]: a song with stored data while the device has no
 * network goes straight to the handler. Both branches of the stock code are left as they are otherwise.
 */
private fun BytecodePatchContext.prepareReplayGate(commandType: String): () -> Unit {
    val match = Fingerprint(
        returnType = "V",
        parameters = listOf(commandType, MAP),
        strings = listOf("PPSV", "PPSE", "PPAD", "PPSDST"),
        custom = { method, _ -> method.hasWideLiteral(GATE_EXPERIMENT) },
    ).matchSingle()
    val method = match.method
    val owner = match.classDef
    val instructions = method.implementation!!.instructions.toList()

    val literal = instructions.indexOfFirst {
        it.opcode == Opcode.CONST_WIDE_32 && (it as WideLiteralInstruction).wideLiteral == GATE_EXPERIMENT
    }
    check(literal > 0 && instructions[literal - 1].opcode == Opcode.CHECK_CAST) { "gate: no message cast" }
    val cast = instructions[literal - 1]
    val messageType = cast.getReference<TypeReference>()!!.type
    val message = (cast as OneRegisterInstruction).registerA
    val scratch = (instructions[literal] as OneRegisterInstruction).registerA

    val flagCall = (literal + 1 until instructions.size).first {
        instructions[it].opcode == Opcode.INVOKE_VIRTUAL
    }
    val flagMethod = instructions[flagCall].getReference<MethodReference>()!!
    check(flagMethod.parameterTypes.map(CharSequence::toString) == listOf("J", "Z") && flagMethod.returnType == "Z") {
        "gate: unexpected experiment flag reader"
    }
    val flagResult = instructions[flagCall + 1]
    val flagTest = instructions[flagCall + 2]
    check(flagResult.opcode == Opcode.MOVE_RESULT && flagTest.opcode == Opcode.IF_EQZ) { "gate: no flag test" }
    val flag = (flagResult as OneRegisterInstruction).registerA
    check((flagTest as OneRegisterInstruction).registerA == flag && flag != scratch) { "gate: flag register" }
    val insertion = flagCall + 2

    val delegates = instructions.filter {
        it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_DIRECT
    }.filter {
        it.getReference<MethodReference>()?.let { call ->
            call.definingClass == owner.type && call.returnType == "V" &&
                call.parameterTypes.map(CharSequence::toString) == listOf(commandType, messageType, MAP)
        } == true
    }
    check(delegates.map { it.getReference<MethodReference>()!!.name }.distinct().size == 1) { "gate: no handler" }
    val handlerCall = delegates.last() as FiveRegisterInstruction
    check(handlerCall.registerCount == 4) { "gate: handler call shape" }
    val handler = handlerCall.getReference<MethodReference>()!!
    val self = handlerCall.registerC
    val command = handlerCall.registerD
    val context = handlerCall.registerF
    val invokeHandler = if (handlerCall.opcode == Opcode.INVOKE_DIRECT) "invoke-direct" else "invoke-virtual"
    val handlerSignature = "${owner.type}->${handler.name}(${commandType}${messageType}$MAP)V"

    val videoId = instructions.drop(literal).first {
        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.definingClass == messageType && field.type == STRING
        } == true
    }.getReference<FieldReference>()!!

    // The run from the cast to the flag test is straight code: it must keep what the handler call needs.
    for (index in literal - 1..flagCall + 1) {
        val step = instructions[index]
        check(index == literal - 1 || (!step.writes(message) && !step.writes(self) &&
            !step.writes(command) && !step.writes(context))) { "gate: handler registers overwritten" }
    }
    // The handler's registers are copies of the arguments made once at the start and never reassigned.
    for (register in listOf(self, command, context)) {
        check(instructions.count { it.writes(register) } == 1) { "gate: register v$register is reassigned" }
    }
    check(listOf(self, command, message, context, scratch, flag).all { it <= 15 }) { "gate: register range" }
    check(instructions.isDeadFrom(insertion + 1, scratch) &&
        instructions.isDeadFrom((flagTest as BuilderOffsetInstruction).target.location.index, scratch)) {
        "gate: scratch register v$scratch is live"
    }

    val stock = ExternalLabel("rvx_replay_gate_stock", method.getInstruction<Instruction>(insertion))
    return {
        method.addInstructionsWithLabels(insertion, """
            invoke-static {v$flag}, $REPLAY->noteGateFlag(Z)V
            iget-object v$scratch, v$message, ${videoId.definingClass}->${videoId.name}:$STRING
            invoke-static {v$scratch}, $REPLAY->holdsForGate($STRING)Z
            move-result v$scratch
            if-eqz v$scratch, :rvx_replay_gate_stock
            $invokeHandler {v$self, v$command, v$message, v$context}, $handlerSignature
            return-void
        """, stock)
    }
}

/**
 * The offline player lookup. A song the stock lookup does not know (the user's stream copies are no entity of the
 * app's own offline store) ends in the `Unplayable Video` failure, which is delivered to the callback. In front of
 * that failure the lookup asks [REPLAY] for the stored response of the request's video; when it has one, the response
 * is built with the app's own factory (so the audio swap routes it to the copy) and delivered instead.
 *
 * @return the type of the request and the installation.
 */
private fun BytecodePatchContext.prepareReplayTail(): Pair<String, () -> Unit> {
    val match = Fingerprint(
        returnType = "V",
        parameters = listOf("L", "L"),
        strings = listOf(UNPLAYABLE),
        custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
    ).matchSingle()
    val method = match.method
    val owner = mutableClassDefBy(match.classDef.type)
    val requestType = method.parameterTypes[0].toString()
    val callbackType = method.parameterTypes[1].toString()
    val instructions = method.implementation!!.instructions.toList()
    val registers = method.implementation!!.registerCount

    val unplayable = instructions.indexOfFirst { it.getReference<StringReference>()?.string == UNPLAYABLE }
    val guard = (unplayable - 1 downTo 0).first { instructions[it].opcode.name.startsWith("if-") }
    check(instructions[guard].opcode == Opcode.IF_NEZ && instructions[guard + 1].opcode == Opcode.SGET_OBJECT) {
        "player: unexpected failure guard"
    }
    val scratch = (instructions[guard + 1] as OneRegisterInstruction).registerA
    val skipped = (instructions[guard] as BuilderOffsetInstruction).target.location.index
    check(instructions.isDeadFrom(guard + 1, scratch) && instructions.isDeadFrom(skipped, scratch) && scratch <= 255) {
        "player: scratch register v$scratch is live"
    }
    val parameters = registers - 3 until registers
    check(instructions.none { step -> parameters.any { step.writes(it) } }) { "player: parameters reassigned" }

    // The request's two answers, as the lookup itself reads them: the offline-only flag, then the video id.
    fun requestCall(returnType: String) = instructions.first { step ->
        (step.opcode == Opcode.INVOKE_VIRTUAL || step.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            step.getReference<MethodReference>()?.let {
                it.definingClass == requestType && it.parameterTypes.isEmpty() && it.returnType == returnType
            } == true
    }.getReference<MethodReference>()!!
    val offlineOnly = requestCall("Z")
    val videoId = requestCall(STRING)

    val callbackNames = instructions.filter { it.opcode == Opcode.INVOKE_INTERFACE }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter {
            it.definingClass == callbackType && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT, OBJECT)
        }.map { it.name }.distinct()
    check(callbackNames.size == 1) { "player: no success callback" }
    val deliver = callbackNames.single()

    val wrapper = streamSwapAdapterClass()
    val factories = wrapper.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType.startsWith("L") &&
            it.parameterTypes.map(CharSequence::toString) == listOf("[B", "J")
    }
    check(factories.isNotEmpty()) { "player: no response factory" }
    // The small one only wraps the bytes; the large one is the variant for a feature flag.
    val factory = factories.minBy { it.implementation?.instructions?.count() ?: Int.MAX_VALUE }

    // Every failure of the lookup (an id the offline store lacks ends in a different catch block than a stale entry)
    // jumps to the guard: the hook takes over that jump target, so no failure path can skip it.
    return requestType to {
        owner.addDownloadsBridge("rvxTail", listOf(requestType, callbackType), "Z", 8, """
            invoke-virtual {p1}, ${offlineOnly.definingClass}->${offlineOnly.name}()Z
            move-result v0
            invoke-virtual {p1}, ${videoId.definingClass}->${videoId.name}()$STRING
            move-result-object v1
            invoke-static {v1, v0}, $REPLAY->storedForPlayer(${STRING}Z)[B
            move-result-object v2
            if-nez v2, :stored
            const/4 v0, 0x0
            return v0
            :stored
            invoke-static {}, Landroid/os/SystemClock;->elapsedRealtime()J
            move-result-wide v3
            invoke-static {v2, v3, v4}, ${wrapper.type}->${factory.name}([BJ)${factory.returnType}
            move-result-object v2
            invoke-static {v2, v1}, $REPLAY->finishPlayer($OBJECT$STRING)$OBJECT
            move-result-object v2
            if-nez v2, :deliver
            const/4 v0, 0x0
            return v0
            :deliver
            const/4 v0, 0x0
            invoke-interface {p2, v0, v2}, $callbackType->$deliver($OBJECT$OBJECT)V
            const/4 v0, 0x1
            return v0
        """)
        method.addInstructionsAtControlFlowLabel(guard, """
            invoke-virtual/range {p0 .. p2}, ${owner.type}->rvxTail($requestType$callbackType)Z
            move-result v$scratch
            if-eqz v$scratch, :rvx_replay_player_stock
            return-void
            :rvx_replay_player_stock
            nop
        """)
    }
}

/**
 * The Library page's chip loader (`Lhtd.a` in 9.40.51): it maps the continuation of a chip to the browse id of the
 * page behind it and throws `Unsupported continuation` for one it does not know. The Downloads chip is such a
 * continuation, not a navigation command, so it never reaches the command dispatcher; right after the browse id is
 * known to be non-null it is handed to [REPLAY], which opens the offline list for the Downloads page. The stock
 * load goes on behind the list.
 */
private fun BytecodePatchContext.prepareLibraryChip(): () -> Unit {
    val method = Fingerprint(
        returnType = "L",
        parameters = listOf("L"),
        strings = listOf(UNSUPPORTED_CONTINUATION),
    ).matchSingle().method
    val instructions = method.implementation!!.instructions.toList()

    val failure = instructions.indexOfFirst { it.getReference<StringReference>()?.string == UNSUPPORTED_CONTINUATION }
    val test = instructions.indices.single { index ->
        val step = instructions[index]
        step.opcode == Opcode.IF_EQZ &&
            (step as BuilderOffsetInstruction).target.location.index in failure - 8 until failure
    }
    val cast = instructions[test - 1]
    check(cast.opcode == Opcode.CHECK_CAST && cast.getReference<TypeReference>()!!.type == STRING) {
        "chip: the browse id is not a checked string"
    }
    val browseId = (instructions[test] as OneRegisterInstruction).registerA
    check((cast as OneRegisterInstruction).registerA == browseId && browseId <= 15) { "chip: browse id register" }
    check(instructions.none { it is BuilderOffsetInstruction && it.target.location.index == test + 1 }) {
        "chip: the insertion point is a jump target"
    }

    return {
        method.addInstruction(test + 1, "invoke-static {v$browseId}, $REPLAY->onLibraryChip($STRING)V")
    }
}

/**
 * The bottom bar's tab navigation (`Lhis.c(String, command)` in 9.40.51). The Library tab goes offline to the
 * Downloads page, and that navigation is not guaranteed to pass the command dispatcher: it may only switch to a
 * tab's own page. The command is handed to [REPLAY] first thing, which opens the offline list for the Downloads
 * page and leaves the stock navigation to go on behind it.
 */
private fun BytecodePatchContext.prepareTabNavigation(): () -> Unit {
    val method = Fingerprint(
        returnType = "V",
        parameters = listOf(STRING, "L"),
        strings = listOf(TAB_SEARCH_PAGE, TAB_HIDE_SEARCH_BACK),
    ).matchSingle().method
    check(!AccessFlags.STATIC.isSet(method.accessFlags)) { "tab navigation: static method" }

    return {
        method.addInstruction(0, "invoke-static {p2}, $REPLAY->onTabNavigation(Ljava/lang/Object;)V")
    }
}

/**
 * The offline watch-next request. The request is kept in the boolean argument's register (dead after the task is
 * built) and, at the return, handed with the future to [REPLAY]: its answer is the stock future, except that a failed
 * stock future of an offline watch of ours is answered with the queue built from the stored list. The request class
 * gets two bridges: its watch command, and the stock response object of a response's bytes.
 */
private fun BytecodePatchContext.prepareReplayNext(requestType: String, commandType: String): () -> Unit {
    val match = Fingerprint(
        returnType = FUTURE,
        parameters = listOf(requestType, "Z"),
        custom = { method, owner -> method.isDeferredNextEntry(owner) },
    ).matchSingle()
    val method = match.method
    val instructions = method.implementation!!.instructions.toList()
    val registers = method.implementation!!.registerCount
    val request = registers - 2
    val booleanArgument = registers - 1

    val taskInit = instructions.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT }
    val returnIndex = instructions.indexOfFirst { it.opcode == Opcode.RETURN_OBJECT }
    val future = (instructions[returnIndex] as OneRegisterInstruction).registerA
    check(instructions.drop(taskInit + 1).none { it.reads(booleanArgument) || it.writes(booleanArgument) }) {
        "next: the boolean argument is still used"
    }
    check(booleanArgument <= 15 && future <= 15 && request <= 15) { "next: register range" }

    val requestClass = mutableClassDefBy(requestType)
    val commandField = requestClass.fields.single {
        it.type == commandType && !AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val responseType = nextResponseType(requestType)
    val response = mutableClassDefBy(responseType)
    val responseInit = response.methods.single { it.name == "<init>" }
    check(responseInit.parameterTypes.size == 1) { "next: response constructor" }
    val protoType = responseInit.parameterTypes.single().toString()
    val protoDefault = mutableClassDefBy(protoType).fields.single {
        it.type == protoType && AccessFlags.STATIC.isSet(it.accessFlags)
    }
    val parserOwner = parseByteArrayMethodRef.get()!!.definingClass
    val parser = mutableClassDefBy(parserOwner).methods.single {
        it.name == "parseFrom" && it.parameterTypes == listOf(parserOwner, "[B", REGISTRY)
    }

    return {
        requestClass.interfaces.add(NEXT_REQUEST)
        requestClass.addDownloadsBridge("rvxCommand", emptyList(), OBJECT, 2, """
            iget-object v0, p0, ${commandField.definingClass}->${commandField.name}:${commandField.type}
            return-object v0
        """)
        requestClass.addDownloadsBridge("rvxNextResponse", listOf("[B"), OBJECT, 4, """
            sget-object v0, $protoDefault
            invoke-static {}, $REGISTRY->getGeneratedRegistry()$REGISTRY
            move-result-object v1
            invoke-static {v0, p1, v1}, $parser
            move-result-object v0
            check-cast v0, $protoType
            new-instance v1, $responseType
            invoke-direct {v1, v0}, $responseType-><init>($protoType)V
            return-object v1
        """)
        method.addInstructions(returnIndex, """
            invoke-static {v$booleanArgument, v$future}, $REPLAY->wrapNext($OBJECT$FUTURE)$FUTURE
            move-result-object v$future
        """)
        method.addInstruction(taskInit + 1, "move-object v$booleanArgument, v$request")
    }
}

/** The task-spawning entry: `new Task(this, request, flag); return Futures.submit(task, this.executor)`. */
private fun Method.isDeferredNextEntry(owner: ClassDef): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags)) return false

    val steps = implementation?.instructions?.toList() ?: return false
    val opcodes = steps.map { it.opcode }.filterNot { it.name.startsWith("const") }
    if (opcodes != listOf(Opcode.NEW_INSTANCE, Opcode.INVOKE_DIRECT, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC,
            Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT)) {
        return false
    }
    val executor = steps.first { it.opcode == Opcode.IGET_OBJECT }.getReference<FieldReference>()
    return executor?.definingClass == owner.type && executor.type == EXECUTOR
}

/** The response type the stock watch-next code builds for a request: the last argument of its builder method. */
private fun BytecodePatchContext.nextResponseType(requestType: String): String {
    val types = mutableSetOf<String>()
    classDefForEach { owner ->
        owner.methods.forEach { method ->
            val parameters = method.parameterTypes.map(CharSequence::toString)
            if (method.returnType == FUTURE && parameters.size == 4 && parameters[0] == requestType &&
                parameters[1].startsWith("L") && parameters[2] == LIST && parameters[3].startsWith("L")) {
                types.add(parameters[3])
            }
        }
    }
    check(types.size == 1) { "next: found ${types.size} response types" }
    return types.single()
}

/** The response adapter [installStreamSwap] added [STREAM_RESPONSE] to; without it the replay cannot swap audio. */
private fun BytecodePatchContext.streamSwapAdapterClass(): ClassDef = Fingerprint(
    name = "<init>",
    parameters = listOf("L", "Lj$/time/Instant;", "J", "L", "L", "Z"),
    custom = { _, owner -> STREAM_RESPONSE in owner.interfaces },
).matchSingle().classDef

private fun Method.hasWideLiteral(value: Long): Boolean = implementation?.instructions?.any {
    it.opcode == Opcode.CONST_WIDE_32 && (it as WideLiteralInstruction).wideLiteral == value
} == true

/** Whether the instruction assigns the register, as the destination of its first operand. */
private fun Instruction.writes(register: Int): Boolean {
    if (this !is OneRegisterInstruction) return false

    return (opcode.setsRegister() && registerA == register) ||
        (opcode.setsWideRegister() && (registerA == register || registerA + 1 == register))
}

/** Whether the instruction reads the register: any operand that is not only the destination. */
private fun Instruction.reads(register: Int): Boolean = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG)
        .take(registerCount).contains(register)
    is RegisterRangeInstruction -> register in startRegister until startRegister + registerCount
    is ThreeRegisterInstruction -> registerB == register || registerC == register || readsFirst(register)
    is TwoRegisterInstruction -> registerB == register || readsFirst(register)
    is OneRegisterInstruction -> readsFirst(register)
    else -> false
}

private fun OneRegisterInstruction.readsFirst(register: Int): Boolean {
    val destination = (opcode.setsRegister() || opcode.setsWideRegister()) && opcode != Opcode.CHECK_CAST
    return registerA == register && !destination
}

/**
 * Whether the register is assigned before it is read when execution starts at [start]. Only straight code is
 * followed: a jump ends the proof as unknown (false), a return or throw ends it as dead (true).
 */
private fun List<Instruction>.isDeadFrom(start: Int, register: Int): Boolean {
    for (index in start until size) {
        val step = this[index]
        if (step.reads(register)) return false
        if (step.writes(register)) return true

        val name = step.opcode.name
        if (name.startsWith("return") || step.opcode == Opcode.THROW) return true
        if (name.startsWith("if-") || name.startsWith("goto") || name.endsWith("switch")) return false
    }
    return false
}
