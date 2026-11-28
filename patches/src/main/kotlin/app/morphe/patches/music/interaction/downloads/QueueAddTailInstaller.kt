/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.music.interaction.jam.registerAt
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val TAIL = "Lapp/morphe/extension/music/patches/downloads/QueueAddTail;"
private const val TAIL_MODEL = $$"Lapp/morphe/extension/music/patches/downloads/QueueAddTail$Model;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val COLLECTION = "Ljava/util/Collection;"
private const val ENUM = "Ljava/lang/Enum;"
private const val SHUFFLE_LISTENER = $$"$ShuffleUpdateListener"
private const val UNSHUFFLE_LISTENER = $$"$UnshuffleUpdateListener"
private val ARM_NAMES = setOf("NEXT", "NEXT_RADIO", "PREVIOUS")
private const val MAX_ENTRY_STEPS = 12

/** The queue model, its lane base, the continuation reply class and the model's Shuffle reply method. */
private data class TailAbi(val model: String, val base: String, val reply: String, val shuffleEntry: String)

private val tailAbis = mapOf(
    "9.45.51" to TailAbi("Lhzx;", "Labld;", "Lgpo;", "x"),
    "9.40.51" to TailAbi("Liar;", "Labfd;", "Lgri;", "x"),
    "9.15.51" to TailAbi("Lnyb;", "Lazav;", "Lnyd;", "A"),
    "8.30.54" to TailAbi("Lnko;", "Lavuo;", "Lnkq;", "y"),
    "8.28.54" to TailAbi("Lnjh;", "Lavmd;", "Lnjj;", "x"),
    "8.12.54" to TailAbi("Lncl;", "Latkd;", "Lncn;", "w"),
)

private class LaneAbi(
    val insert: Method, val move: Method, val remove: Method, val size: Method, val current: Method, val item: Method,
    val baseline: FieldReference,
)

private class PageSite(
    val method: MutableMethod, val save: Int, val append: Int,
    val model: Int, val arm: Int, val lane: Int, val index: Int, val rows: Int,
)

/**
 * Keeps a connected Add to queue at the end of Up Next across the next page and a server Shuffle change. Discovery
 * is all-or-nothing: any failed check leaves the stock placement and only warns.
 */
internal fun BytecodePatchContext.installQueueAddTail() {
    var stage = "version ABI"
    try {
        val abi = checkNotNull(tailAbis[packageMetadata.versionName]) { "no matching native queue version" }
        val model = mutableClassDefBy(abi.model)

        stage = "lane methods of ${abi.base}"
        val lane = resolveLane(abi, model)

        stage = "Shuffle reply entry ${abi.model}->${abi.shuffleEntry}"
        val entry = resolveShuffleEntry(abi, model, lane)

        stage = "Up Next page append in ${abi.reply}"
        val page = resolvePageAppend(abi, mutableClassDefBy(abi.reply), model, lane)

        stage = "bridge and hook emission"
        addModelBridges(model, lane)
        addLaneEditHooks(mutableClassDefBy(abi.base), lane)
        val last = entry.steps().lastIndex
        entry.addInstruction(last, "invoke-static {p0}, $TAIL->shuffleEnd($OBJECT)V")
        entry.addInstruction(0, "invoke-static {p0}, $TAIL->shuffleBegin($OBJECT)V")
        page.method.addInstruction(page.append + 1, "invoke-static {v${page.model}, v${page.lane}, v${page.index}, " +
            "v${page.rows}}, $TAIL->pageAppended(${OBJECT}II$OBJECT)V")
        page.method.addInstruction(page.save,
            "invoke-static {v${page.model}, v${page.arm}}, $TAIL->pageArm($OBJECT$OBJECT)V")
        printInfo("QueueAddTail: 1 page append ${page.method} at ${page.append}, 1 Shuffle reply $entry, " +
            "lane ${lane.insert}, ${lane.move}, edits ${lane.move.name}/${lane.remove.name}")
    } catch (exception: Exception) {
        printWarn("QueueAddTail: Add to queue keeps stock placement on Music ${packageMetadata.versionName}: " +
            "$stage: ${exception.message}")
    }
}

private fun BytecodePatchContext.resolveLane(abi: TailAbi, model: MutableClass): LaneAbi {
    check(model.superclass == abi.base) { "the queue model does not extend the lane base" }

    val base = classDefBy(abi.base)
    val insert = base.laneMethod("insert") { it.parameterTypes == listOf("I", "I", COLLECTION) && it.returnType == "V" }
    val move = base.laneMethod("move") { it.parameterTypes == listOf("I", "I", "I", "I") && it.returnType == "V" }
    val remove = base.laneMethod("remove") { it.parameterTypes == listOf("I", "I", "I") && it.returnType == "V" }
    val size = base.laneMethod("size") { it.parameterTypes == listOf("I") && it.returnType == "I" }
    val current = base.laneMethod("current") { it.parameterTypes.isEmpty() && it.returnType == "I" }
    val item = base.laneMethod("item") { it.parameterTypes == listOf("I", "I") && it.returnType.startsWith("L") }

    // The move is the user drag: remove the row, then add it at the destination index.
    val moveCalls = move.steps().mapNotNull { it.call()?.toString() }
    check("$LIST->remove(I)$OBJECT" in moveCalls && "$LIST->add(I$OBJECT)V" in moveCalls) {
        "lane move is not remove then add"
    }
    // The removal clears a sub-list; the model must not override either edit, or the base hooks would miss it.
    check(remove.steps().any { it.call()?.toString() == "$LIST->clear()V" }) { "lane removal does not clear rows" }
    check(model.methods.none { method ->
        listOf(move, remove).any { it.name == method.name && it.parameterTypes == method.parameterTypes }
    }) { "the queue model overrides a lane edit" }

    val override = model.methods.singleOrNull { it.name == insert.name && it.parameterTypes == insert.parameterTypes }
    checkNotNull(override) { "the queue model does not override the lane insert" }
    val steps = override.steps()
    check(steps.any { it.opcode == Opcode.INVOKE_SUPER && it.call()?.name == insert.name }) {
        "the lane insert override does not call the stock insert"
    }
    val baseline = steps.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.getReference<FieldReference>() }
        .filter { it.definingClass == model.type && it.type == LIST }.distinct()
    return LaneAbi(insert, move, remove, size, current, item,
        baseline.singleOrNull() ?: error("no shuffle baseline field"))
}

private fun BytecodePatchContext.resolveShuffleEntry(abi: TailAbi, model: MutableClass, lane: LaneAbi): MutableMethod {
    val entry = checkNotNull(model.methods.singleOrNull {
        it.name == abi.shuffleEntry && it.parameterTypes.size == 1 && it.returnType == "V" &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    }) { "missing or ambiguous method" }
    val steps = entry.steps()
    val remove = steps.mapNotNull { it.call() }.filter { it.parameterTypes == listOf("I", "I", "I") }
    val sizes = steps.mapNotNull { it.call() }.filter { it.name == lane.size.name && it.parameterTypes == listOf("I") }
    check(steps.size <= MAX_ENTRY_STEPS && steps.last().opcode == Opcode.RETURN_VOID &&
        steps.none { it is OffsetInstruction || it is SwitchPayload } && remove.size == 1 && sizes.size == 1) {
        "not the straight-line lane reset of a server Shuffle reply"
    }

    // The entry must be reached only by the two server Shuffle and Unshuffle reply listeners.
    val listeners = listOf(SHUFFLE_LISTENER, UNSHUFFLE_LISTENER)
    val callers = mutableListOf<String?>()
    classDefForEach { classDef ->
        if (classDef.methods.any { method -> method.steps().any { it.isCallTo(entry, model.type) } }) {
            callers += classDef.methods.flatMap { it.steps() }.mapNotNull { it.text() }
                .firstNotNullOfOrNull { text -> listeners.firstOrNull { text.endsWith(it) } }
        }
    }
    check(callers.sortedBy { it.orEmpty() } == listeners.sorted()) {
        "the method is called by something other than the Shuffle and Unshuffle reply listeners"
    }
    return entry
}

/** The reply method inserts the previous page, then the next page. Hook the arm save and the second insert. */
private fun BytecodePatchContext.resolvePageAppend(
    abi: TailAbi, reply: MutableClass, model: MutableClass, lane: LaneAbi,
): PageSite {
    val method = checkNotNull(reply.methods.singleOrNull { candidate ->
        candidate.steps().count { it.isCallTo(lane.insert, abi.base) } == 2
    }) { "missing or ambiguous continuation reply method" }
    val steps = method.steps()
    val inserts = steps.indices.filter { steps[it].isCallTo(lane.insert, abi.base) }
    val append = inserts.last()

    val saves = (inserts.first() + 1 until append).filter { index ->
        steps[index].opcode == Opcode.INVOKE_VIRTUAL && steps[index].call()?.let {
            it.definingClass == model.type && it.returnType == "V" && it.parameterTypes.size == 2 &&
                isContinuationArm(it.parameterTypes[1].toString())
        } == true
    }
    val save = checkNotNull(saves.singleOrNull()) { "missing or ambiguous continuation arm save" }
    val saveCall = steps[save] as ReferenceInstruction
    val appendCall = steps[append] as ReferenceInstruction
    val modelRegister = requireNotNull(saveCall.registerAt(0))
    val registers = (1..3).map { requireNotNull(appendCall.registerAt(it)) }
    check(append + 1 < steps.size && (listOf(modelRegister, requireNotNull(saveCall.registerAt(2))) + registers)
        .all { it < 16 }) { "page append registers cannot be hooked" }
    check(steps.subList(save + 1, append + 1).none {
        it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == modelRegister
    }) { "the queue model register changes before the page append" }

    return PageSite(method, save, append, modelRegister, requireNotNull(saveCall.registerAt(2)),
        registers[0], registers[1], registers[2])
}

private fun BytecodePatchContext.isContinuationArm(type: String): Boolean {
    val enumClass = runCatching { classDefBy(type) }.getOrNull() ?: return false
    if (enumClass.superclass != ENUM) return false

    val names = enumClass.methods.filter { it.name == "<clinit>" }.flatMap { it.steps() }.mapNotNull { it.text() }
    return names.containsAll(ARM_NAMES)
}

private fun addModelBridges(model: MutableClass, lane: LaneAbi) {
    model.addDownloadsBridge("rvxTailSize", listOf("I"), "I", 3, """
        invoke-virtual {p0, p1}, ${lane.size}
        move-result v0
        return v0
    """)
    model.addDownloadsBridge("rvxTailItem", listOf("I", "I"), OBJECT, 4, """
        invoke-virtual {p0, p1, p2}, ${lane.item}
        move-result-object v0
        return-object v0
    """)
    model.addDownloadsBridge("rvxTailCurrent", emptyList(), "I", 2, """
        invoke-virtual {p0}, ${lane.current}
        move-result v0
        return v0
    """)
    model.addDownloadsBridge("rvxTailMove", listOf("I", "I"), "V", 4, """
        const/4 v0, 0x0
        invoke-virtual {p0, v0, p1, v0, p2}, ${lane.move}
        return-void
    """)
    model.addDownloadsBridge("rvxTailAppend", listOf(LIST), "V", 4, """
        const/4 v0, 0x0
        invoke-virtual {p0, v0}, ${lane.size}
        move-result v1
        invoke-virtual {p0, v0, v1, p1}, ${lane.insert}
        return-void
    """)
    model.addDownloadsBridge("rvxTailBaseline", emptyList(), LIST, 2, """
        iget-object v0, p0, ${lane.baseline}
        return-object v0
    """)
    model.interfaces.add(TAIL_MODEL)
}

/**
 * Every lane move and removal, from the user or any other path, reports the rows it touches before it runs. The
 * extension skips its own edits and the server Shuffle reply; anything else ends the touched added occurrence.
 */
private fun addLaneEditHooks(base: MutableClass, lane: LaneAbi) {
    fun MutableClass.edit(method: Method) =
        methods.single { it.name == method.name && it.parameterTypes == method.parameterTypes }

    base.edit(lane.move).addInstruction(0, "invoke-static/range {p0 .. p4}, $TAIL->moving(${OBJECT}IIII)V")
    base.edit(lane.remove).addInstruction(0, "invoke-static/range {p0 .. p3}, $TAIL->removing(${OBJECT}III)V")
}

/** The lane base's only public instance method of this shape. */
private fun ClassDef.laneMethod(what: String, shape: (Method) -> Boolean): Method =
    checkNotNull(methods.filter {
        shape(it) && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    }.singleOrNull()) { "missing or ambiguous lane $what" }

private fun Method.steps(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.call(): MethodReference? = getReference<MethodReference>()
private fun Instruction.text(): String? = getReference<StringReference>()?.string

/** A call to [target] by name and signature, optionally restricted to a defining class. */
private fun Instruction.isCallTo(target: Method, definingClass: String? = null): Boolean =
    call()?.let {
        it.name == target.name && it.parameterTypes == target.parameterTypes && it.returnType == target.returnType &&
            (definingClass == null || it.definingClass == definingClass)
    } == true
