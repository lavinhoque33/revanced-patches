/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.patches.music.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.conversionContextFingerprintToString2
import app.morphe.util.addFreeRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val ANDROID_CONTEXT = "Landroid/content/Context;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val IMAGE_VIEW = "Landroid/widget/ImageView;"
private const val OBJECT = "Ljava/lang/Object;"
private const val PATH = "Ljava/lang/StringBuilder;"
private const val STRING = "Ljava/lang/String;"

/** Reuse the renderer's client-resource selector; the cover and avatar use remote image sources. */
internal fun BytecodePatchContext.installMusicLightElementsIcons() {
    val context = conversionContextFingerprintToString2.originalClassDef
    val path = context.fields.single { it.type == PATH && AccessFlags.PUBLIC.isSet(it.accessFlags) }
    val image = Fingerprint(name = "<init>", strings = listOf("GlideImage")).originalClassDef
    val targetType = image.methods.asSequence()
        .flatMap { it.implementation?.instructions?.asSequence().orEmpty() }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter {
            it.name == "<init>" && it.parameterTypes.getOrNull(1) == IMAGE_VIEW &&
                it.parameterTypes.any { type -> type == context.type || type == context.superclass }
        }
        .map { it.definingClass }
        .distinct()
        .single()
    val target = mutableClassDefBy(targetType)
    val targetContext = target.fields.single { it.type == context.type || it.type == context.superclass }
    val prepareDrawable = target.methods.single {
        it.returnType == DRAWABLE && it.parameterTypes.size == 3 && it.parameterTypes.first() == DRAWABLE &&
            it.implementation?.instructions?.any { insn ->
                insn.getReference<MethodReference>()?.toString() == "$DRAWABLE->setAutoMirrored(Z)V"
            } == true
    }
    val imageType = prepareDrawable.parameterTypes[1].toString()
    val (selectSource, resource, name) = stockGlyphSource(imageType)
    val loaded = target.methods.single {
        it.returnType == "V" && it.parameterTypes.size == 2 && it.parameterTypes.first() == OBJECT &&
            it.implementation?.instructions.orEmpty().any { insn ->
                insn.getReference<MethodReference>() == prepareDrawable
            }
    }
    val prepare = loaded.implementation!!.instructions.withIndex().single {
        it.value.getReference<MethodReference>() == prepareDrawable
    }
    val call = prepare.value as FiveRegisterInstruction
    check(call.opcode == Opcode.INVOKE_DIRECT && call.registerCount == 4)
    val result = loaded.getInstruction<OneRegisterInstruction>(prepare.index + 1)
    check(result.opcode == Opcode.MOVE_RESULT_OBJECT)

    val receiver = call.registerC
    val props = call.registerE
    val drawable = result.registerA
    val scope = loaded.implementation!!.registerCount
    val scratch = scope + 1
    check(maxOf(receiver, props, drawable, scratch) < 16)
    val (method, parameterMoves) = loaded.addFreeRegisters(2)
    val paintGlyph = "$MUSIC_LIGHT_THEME_CLASS_DESCRIPTOR->styleElementsGlyph(I$STRING$DRAWABLE)$DRAWABLE"

    // A Glide result can arrive after its component binds. Paint the glyph, not the recycled view.
    val insert = prepare.index + parameterMoves + 2
    val resume = ExternalLabel("done", method.getInstruction(insert))
    method.addInstructionsWithLabels(insert, """
        invoke-static {}, $MUSIC_LIGHT_THEME_CLASS_DESCRIPTOR->isEnabled()Z
        move-result v$scratch
        if-eqz v$scratch, :done

        iget-object v$scope, v$receiver, $targetContext
        instance-of v$scratch, v$scope, ${context.type}
        if-eqz v$scratch, :done
        check-cast v$scope, ${context.type}
        iget-object v$scope, v$scope, $path
        invoke-static {v$scope}, $MUSIC_LIGHT_THEME_CLASS_DESCRIPTOR->elementsGlyphRole($PATH)I
        move-result v$scratch
        if-eqz v$scratch, :done

        invoke-static {v$props}, $selectSource
        move-result-object v$scope
        if-eqz v$scope, :done
        invoke-interface {v$scope}, $resource
        move-result-object v$scope
        if-eqz v$scope, :done
        invoke-interface {v$scope}, $name
        move-result-object v$scope
        invoke-static {v$scratch, v$scope, v$drawable}, $paintGlyph
        move-result-object v$drawable
    """, resume)
}

private fun BytecodePatchContext.stockGlyphSource(
    imageType: String,
): Triple<MethodReference, MethodReference, MethodReference> {
    val resolver = Fingerprint(
        returnType = "Z",
        parameters = listOf(ANDROID_CONTEXT, imageType),
        filters = listOf(methodCall(
            smali = "Landroid/content/res/Resources;->getDrawable(ILandroid/content/res/Resources\$Theme;)$DRAWABLE",
        )),
    ).originalClassDef
    val lookup = resolver.methods.single {
        it.returnType == "I" && it.parameterTypes == listOf(ANDROID_CONTEXT, imageType)
    }
    val lookupCalls = lookup.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
    val select = lookupCalls.single {
        it.definingClass == resolver.type && it.parameterTypes == listOf(imageType) && it.returnType.startsWith('L')
    }
    val resolveId = resolver.methods.single {
        it.returnType == "I" && it.parameterTypes == listOf(ANDROID_CONTEXT, select.returnType)
    }
    val getters = resolveId.implementation!!.instructions.filter { it.opcode == Opcode.INVOKE_INTERFACE }
        .mapNotNull { it.getReference<MethodReference>() }
    val resource = getters.distinct().single {
        it.definingClass == select.returnType && it.parameterTypes.isEmpty() && it.returnType.startsWith('L')
    }
    val name = getters.single {
        it.definingClass == resource.returnType && it.parameterTypes.isEmpty() && it.returnType == STRING
    }
    return Triple(select, resource, name)
}
