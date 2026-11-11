/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.util.Utils.printWarn
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PAGE_HOOKS = "Lapp/morphe/extension/music/patches/downloads/DownloadsPage;"
private const val PAGE_SERVICE_REFRESH = $$"Lapp/morphe/extension/music/patches/downloads/DownloadsPage$PageRefresh;"

/**
 * The controller of a Downloads page: the class that puts itself in a field of the page service when it is made, is
 * given the page fragment when the page is attached (the one method that takes the fragment and stores it first), and
 * whose fragment has the method a pull to refresh runs (no parameter, it calls its own method that takes a boolean
 * with true). The service reaches the fragment through it, so the page can be refreshed as the user would.
 */
private class PageController(
    val type: String,
    /** The field of the service that holds the controller, with the type that field has. */
    val serviceField: FieldReference,
    val hostField: FieldReference,
    val attach: Method,
    val refreshOwner: String,
    val refreshName: String,
)

/**
 * Lets the extension refresh the Downloads page in place, as a pull to refresh does, when the stream copies changed
 * while the page was on screen or in the back stack. Found by shape in every version (9.40.51: the service `Lhtd`,
 * its controller `Lgmn`, the fragment `Lgoa` whose base class has `v()`; 9.15.51 `Lnck`, `Ljnm`, `Ljqt`, `Ljma.w()`;
 * 8.30.54 `Lmpe`, `Ljew`, `Ljid`, `Ljde.o()`). Optional: where the shape is not found the page keeps the copies it
 * was given until it is opened again.
 *
 * @return the patch step to run, or null when the shape was not found.
 */
internal fun BytecodePatchContext.prepareDownloadsPageRefresh(serviceType: String): (() -> Unit)? {
    try {
        val controller = findPageController(serviceType)
        val service = mutableClassDefBy(serviceType)
        return {
            service.interfaces.add(PAGE_SERVICE_REFRESH)
            service.addDownloadsBridge("rvxRefreshPage", emptyList(), "Z", 2, """
                iget-object v0, p0, $serviceType->${controller.serviceField.name}:${controller.serviceField.type}
                if-eqz v0, :none
                check-cast v0, ${controller.type}
                iget-object v0, v0, ${controller.type}->${controller.hostField.name}:${controller.hostField.type}
                if-eqz v0, :none
                invoke-virtual {v0}, ${controller.refreshOwner}->${controller.refreshName}()V
                const/4 v0, 0x1
                return v0
                :none
                const/4 v0, 0x0
                return v0
            """)

            val attach = mutableClassDefBy(controller.type).methods.single {
                it.name == controller.attach.name && it.parameterTypes == controller.attach.parameterTypes
            }
            attach.addInstructions(0, "invoke-static {}, $PAGE_HOOKS->onPageAttached()V")
        }
    } catch (ex: Exception) {
        printWarn("OfflineReplay: the Downloads page cannot be refreshed, it keeps its copies: ${ex.message}")
        return null
    }
}

private fun BytecodePatchContext.findPageController(serviceType: String): PageController {
    val found = mutableListOf<PageController>()
    classDefForEach { cls ->
        val serviceField = serviceFieldOf(cls, serviceType) ?: return@classDefForEach
        val (attach, hostField) = attachOf(cls) ?: return@classDefForEach
        val refresh = refreshOf(hostField.type) ?: return@classDefForEach
        found.add(PageController(cls.type, serviceField, hostField, attach, refresh.first, refresh.second))
    }
    check(found.size == 1) { "page refresh: found ${found.map { it.type }} controllers" }
    return found.single()
}

/** The register of `this` in a method that is not static. */
private fun Method.thisRegister() = implementation!!.registerCount - parameterTypes.size - 1

/** The field of the service the class stores itself in (in any of its methods), or null. */
private fun serviceFieldOf(cls: ClassDef, serviceType: String): FieldReference? {
    for (method in cls.methods) {
        val steps = method.implementation?.instructions ?: continue
        if (AccessFlags.STATIC.isSet(method.accessFlags)) continue

        for (step in steps) {
            if (step.opcode != Opcode.IPUT_OBJECT || step !is TwoRegisterInstruction) continue

            val field = step.getReference<FieldReference>() ?: continue
            val isItself = field.type == cls.type || field.type == cls.superclass || field.type in cls.interfaces
            if (field.definingClass == serviceType && step.registerA == method.thisRegister() && isItself) return field
        }
    }
    return null
}

/** The method that takes the fragment and stores it first, with the field it stores it in, or null. */
private fun attachOf(cls: ClassDef): Pair<Method, FieldReference>? {
    val attaches = cls.methods.mapNotNull { method ->
        val first = method.implementation?.instructions?.firstOrNull() ?: return@mapNotNull null
        if (method.returnType != "V" || method.parameterTypes.size != 1) return@mapNotNull null
        if (AccessFlags.STATIC.isSet(method.accessFlags)) return@mapNotNull null
        if (first.opcode != Opcode.IPUT_OBJECT || first !is TwoRegisterInstruction) return@mapNotNull null

        val field = first.getReference<FieldReference>() ?: return@mapNotNull null
        val stores = first.registerA == method.thisRegister() + 1 && first.registerB == method.thisRegister()
        if (stores && field.definingClass == cls.type && field.type == method.parameterTypes[0].toString()) {
            method to field
        } else {
            null
        }
    }
    return attaches.singleOrNull()
}

/** The owner and name of the method a pull to refresh runs, in the fragment class or its bases. */
private fun BytecodePatchContext.refreshOf(hostType: String): Pair<String, String>? {
    val found = mutableListOf<Pair<String, String>>()
    var type: String? = hostType
    while (type != null) {
        val cls = classDefByOrNull(type) ?: break
        cls.methods.filter { it.isRefreshAction() }.forEach { found.add(cls.type to it.name) }
        type = cls.superclass
    }
    return found.singleOrNull()
}

/** `const/4 v, 0x1; invoke-virtual {this, v}, <own method that takes a boolean>; return-void`. */
private fun Method.isRefreshAction(): Boolean {
    if (returnType != "V" || parameterTypes.isNotEmpty() || AccessFlags.STATIC.isSet(accessFlags)) return false

    val steps = implementation?.instructions?.toList() ?: return false
    if (steps.size != 3 || steps[2].opcode != Opcode.RETURN_VOID) return false

    val constant = steps[0] as? NarrowLiteralInstruction
    val flag = steps[0] as? OneRegisterInstruction
    val call = steps[1] as? FiveRegisterInstruction
    if (steps[0].opcode != Opcode.CONST_4 || constant?.narrowLiteral != 1 || flag == null || call == null) return false

    val target = steps[1].getReference<MethodReference>() ?: return false
    return steps[1].opcode == Opcode.INVOKE_VIRTUAL && call.registerCount == 2 &&
        call.registerC == thisRegister() && call.registerD == flag.registerA &&
        target.parameterTypes.map(CharSequence::toString) == listOf("Z") && target.returnType == "V"
}
