/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.iface.Method
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patches.music.layout.theme.ElementsContainerFingerprint
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MARKERS = "Lapp/morphe/extension/music/patches/downloads/DownloadedMarkers;"
private const val ROW_BOUND = "$MARKERS->onRowBound(Landroid/view/View;Ljava/lang/Object;)V"
private const val HEADER_BOUND = "$MARKERS->onHeaderBound(Landroid/view/ViewGroup;Ljava/lang/Object;)V"
private const val ELEMENTS_BOUND = "$MARKERS->onElementsBound(Landroid/view/View;Ljava/lang/Object;)V"
private const val COMPONENT_TREE = "Lcom/facebook/litho/ComponentTree;"
private const val OBJECT = "Ljava/lang/Object;"
private const val VIEW = "Landroid/view/View;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"

private fun List<CharSequence>.names() = map { it.toString() }

/**
 * The base of the list-row presenters, in 9.40.51 / 9.15.51 / 8.30.54 order: `Ladic.ho` / `Lbdmw.fT` / `Lazwn.fu`,
 * bridging to the abstract bind `d` / `fU` / `fv`, interface `Ladhl` / `Lbdlz` / `Lazvs` whose only `()View` method
 * is the presenter's root view. Matched by shape: the one final `(L, Object)V` of an abstract class with one
 * interface that asks its own abstract `(Object)[B` method first and ends by calling its own abstract `(L, Object)V`.
 */
internal val rowPresenterBindFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", OBJECT),
    custom = { method, owner ->
        val instructions = method.implementation?.instructions?.toList() ?: emptyList()
        val bytes = owner.methods.singleOrNull {
            AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.returnType == "[B" &&
                it.parameterTypes.names() == listOf(OBJECT)
        }
        val last = instructions.getOrNull(instructions.size - 2)?.getReference<MethodReference>()
        AccessFlags.ABSTRACT.isSet(owner.accessFlags) && owner.interfaces.size == 1 && bytes != null &&
            instructions.lastOrNull()?.opcode == Opcode.RETURN_VOID &&
            instructions.first().getReference<MethodReference>()?.let { it.name == bytes.name } == true &&
            last != null && last.definingClass == owner.type && last.parameterTypes == method.parameterTypes &&
            owner.methods.any {
                AccessFlags.ABSTRACT.isSet(it.accessFlags) && it.name == last.name &&
                    it.parameterTypes == method.parameterTypes
            }
    },
)

/**
 * The presenter of the Elements (Litho) views, in 9.40.51 / 9.15.51 / 8.30.54 order `Lactk` / `Lbclc` / `Layyf`: the
 * class that implements the presenter interface of the list rows, holds the Litho `ComponentTree` of its view and
 * has the synthetic bridge `(L, Object)V` that the interface's bind goes through (`ho` / `fT` / `fu`).
 */
internal val elementsPresenterFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("L", OBJECT),
    custom = { method, owner ->
        AccessFlags.SYNTHETIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags) &&
            !AccessFlags.ABSTRACT.isSet(owner.accessFlags) && owner.interfaces.size == 1 &&
            owner.fields.any { it.type == COMPONENT_TREE }
    },
)

/**
 * Shows the green downloaded checks. Rows: every list-row presenter bind reports its root view and model.
 * Header: the playlist/album header presenter (the class of [ElementsContainerFingerprint], `Ljbl` / `Lqmm` / `Lpxa`)
 * reports its elements container after adding the Elements view in `i` / `j` / `j` (`Lmbf.K` / `Lqhj.c` / `Lprn.c`).
 * Each hook is resolved before it is applied and an unfamiliar app only warns: no check, never a failed patch.
 */
internal fun BytecodePatchContext.installDownloadedMarkers() {
    try {
        installRowMarkers()
    } catch (ex: Exception) {
        printWarn("Downloaded checkmarks on song rows are unavailable on this Music version: ${ex.message}")
    }
    try {
        installElementRowMarkers()
    } catch (ex: Exception) {
        printWarn("Downloaded checkmarks on album track rows are unavailable on this Music version: ${ex.message}")
    }
    try {
        installHeaderMarker()
    } catch (ex: Exception) {
        printWarn("Downloaded checkmark on the playlist header is unavailable on this Music version: ${ex.message}")
    }
}

private fun BytecodePatchContext.installRowMarkers() {
    val match = rowPresenterBindFingerprint.matchSingle()
    val bind = match.method
    val owner = match.classDef
    val rootView = mutableClassDefBy(owner.interfaces.single()).methods.single {
        it.parameterTypes.isEmpty() && it.returnType == VIEW
    }
    val instructions = bind.implementation!!.instructions.toList()
    val returnIndex = instructions.lastIndex
    check(instructions[returnIndex].opcode == Opcode.RETURN_VOID)
    check(bind.implementation!!.registerCount >= 4) { "Row bind has no scratch register" }

    // p0 = this, p1 = the bind context, p2 = the row model: the last registers of the method.
    val model = bind.implementation!!.registerCount - 1
    check(model <= 15) { "Row model register exceeds 4 bits" }

    mutableClassDefBy(owner.type).findMutableMethodOf(bind).addInstructions(returnIndex, """
        invoke-interface {p0}, ${rootView.definingClass}->${rootView.name}()$VIEW
        move-result-object v0
        invoke-static {v0, v$model}, $ROW_BOUND
    """)
    printInfo("Downloaded checkmarks: rows via ${owner.type}->${bind.name}")
}

/** A call that gives a Litho view its tree: a void instance method of another class with the tree and maybe a flag. */
private fun Instruction.isSetTreeCall(owner: String): Boolean {
    val call = getReference<MethodReference>() ?: return false
    val parameters = call.parameterTypes.names()
    return opcode == Opcode.INVOKE_VIRTUAL && call.returnType == "V" && call.definingClass != owner &&
        parameters.isNotEmpty() && parameters.size <= 2 && parameters.first() == COMPONENT_TREE &&
        parameters.drop(1).all { it == "Z" }
}

/**
 * The index of the `return-void` that follows the call giving the view its tree, in a bind of the Elements presenter:
 * `(context, model, flag...)V`. The presenter has two or three of them (`h` and `f`, `g` and `h`, `e` and `h`), each
 * used by other Elements users; some end with an unreachable `throw` after that return. Null for any other method.
 */
private fun Method.elementsBindReturn(owner: String, contextType: String): Int? {
    val parameters = parameterTypes.names()
    val instructions = implementation?.instructions?.toList() ?: return null
    val shape = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.FINAL.isSet(accessFlags) &&
        !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" && parameters.size >= 3 &&
        parameters[0] == contextType && parameters[1].startsWith("L") && parameters[1] != OBJECT &&
        parameters.drop(2).all { it == "Z" }
    if (!shape) return null

    val calls = instructions.indices.filter { index ->
        index + 1 < instructions.size && instructions[index].isSetTreeCall(owner) &&
            instructions[index + 1].opcode == Opcode.RETURN_VOID
    }
    return calls.singleOrNull()?.plus(1)
}

private fun Instruction.writesRegister(register: Int): Boolean =
    opcode.setsRegister() && (this as? OneRegisterInstruction)?.registerA == register

/** Whether [type] is, through the app's own classes, a subclass of the framework's View. */
private fun BytecodePatchContext.isView(type: String): Boolean {
    var current = type
    repeat(8) {
        if (current == VIEW) return true
        if (current.startsWith("Landroid/")) return current == VIEW_GROUP || current.startsWith("Landroid/view/")
        current = mutableClassDefBy(current).superclass ?: return false
    }
    return false
}

/**
 * Album track rows: every bind of the Elements presenter reports the view it gave its tree and the model it bound.
 * Resolved for every bind first and applied to all or none, so a version where one differs gets no half hook.
 */
private fun BytecodePatchContext.installElementRowMarkers() {
    val match = elementsPresenterFingerprint.matchSingle()
    val owner = match.classDef
    val contextType = match.method.parameterTypes.first().toString()

    val hooks = owner.methods.mapNotNull { bind ->
        val returnIndex = bind.elementsBindReturn(owner.type, contextType) ?: return@mapNotNull null
        val call = bind.implementation!!.instructions.toList()[returnIndex - 1] as? FiveRegisterInstruction
            ?: error("${bind.name} sets the tree through a register range")
        check(isView(call.getReference<MethodReference>()!!.definingClass)) { "${bind.name} sets the tree of no view" }
        // p0 = this, p1 = the bind context, p2 = the model: the registers after the locals.
        val model = bind.implementation!!.registerCount - bind.parameterTypes.size + 1
        check(call.registerC <= 15 && model <= 15) { "${bind.name} has a register above 15" }
        check(bind.implementation!!.instructions.take(returnIndex).none { it.writesRegister(model) }) {
            "${bind.name} overwrites its model before it gives the view its tree"
        }
        Triple(bind, returnIndex, bind.registerName(call.registerC))
    }
    check(hooks.isNotEmpty()) { "${owner.type} has no bind that gives its view a tree" }

    for ((bind, returnIndex, view) in hooks) {
        // p0 = this, p1 = the bind context, p2 = the model.
        mutableClassDefBy(owner.type).findMutableMethodOf(bind).addInstructions(
            returnIndex, "invoke-static {$view, p2}, $ELEMENTS_BOUND"
        )
    }
    printInfo("Downloaded checkmarks: album rows via ${owner.type}->${hooks.joinToString("/") { it.first.name }}")
}

private fun isViewAddCall(instruction: Instruction): Boolean {
    val call = instruction.getReference<MethodReference>() ?: return false
    return instruction.opcode == Opcode.INVOKE_STATIC && call.returnType == VIEW &&
        call.parameterTypes.names().let {
            it.size == 4 && it[0] == OBJECT && it[1] == VIEW_GROUP && it[2].startsWith("L") && it[3].startsWith("L")
        }
}

private fun BytecodePatchContext.installHeaderMarker() {
    val owner: ClassDef = ElementsContainerFingerprint.matchSingle().classDef
    val bind = owner.methods.single { method ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags) &&
            !AccessFlags.SYNTHETIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.size == 2 && method.parameterTypes[1] != OBJECT &&
            method.implementation?.instructions?.any(::isViewAddCall) == true
    }
    val instructions = bind.implementation!!.instructions.toList()
    val callIndex = instructions.indexOfFirst(::isViewAddCall)
    val call = instructions[callIndex] as FiveRegisterInstruction
    val container = call.registerD
    check(instructions[callIndex + 1].opcode != Opcode.MOVE_RESULT_OBJECT) { "Elements view result is used" }

    // p0 = this, p1 = the bind context, p2 = the header model: the last registers of the method.
    val model = bind.implementation!!.registerCount - 1
    check(model <= 15) { "Header model register exceeds 4 bits" }

    mutableClassDefBy(owner.type).findMutableMethodOf(bind).addInstructions(callIndex + 1, """
        invoke-static {v$container, v$model}, $HEADER_BOUND
    """)
    printInfo("Downloaded checkmarks: header via ${owner.type}->${bind.name}")
}
