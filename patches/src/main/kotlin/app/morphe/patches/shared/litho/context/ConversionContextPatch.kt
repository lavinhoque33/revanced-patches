/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1919
 * https://github.com/MorpheApp/morphe-patches/pull/3120
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * Ported to RVX by lavinhoque33, 2026-10-16.
 */

package app.morphe.patches.shared.litho.context

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

const val EXTENSION_CONTEXT_INTERFACE =
    "Lapp/morphe/extension/shared/patches/components/ContextInterface;"

/**
 * Holds the mutable class def of the conversion context class after the patch has run.
 */
lateinit var conversionContextClassDef: MutableClass
    internal set

/**
 * Shared patch for the ConversionContext class used by both YouTube and YT Music.
 *
 * Adds the [EXTENSION_CONTEXT_INTERFACE] interface to the app's ConversionContext class,
 * exposing helper methods that extension code can call to read the identifier and the
 * path-builder StringBuilder via obfuscation-safe names.
 *
 * Unlike the original Morphe port, toString() is not rewritten, because this fork's
 * legacy Litho filter still resolves this class by the strings inside toString().
 */
val conversionContextPatch = bytecodePatch(
    description = "Hooks the method to use the conversion context in an extension."
) {
    execute {
        conversionContextClassDef = ConversionContextToStringFingerprint.classDef
        val toStringMethod = ConversionContextToStringFingerprint.method

        val stringBuilderField = conversionContextClassDef.fields.single { field ->
            field.type == "Ljava/lang/StringBuilder;"
        }
        val identifierField = toStringMethod.findFieldFromToString(IDENTIFIER_PROPERTY)
        val horizontalSwipeField = toStringMethod.findFieldFromToString(HORIZONTAL_COLLECTION_SWIPE_PROTECTOR_PROPERTY)
        val heightConstraint = toStringMethod.findFieldFromToString(HEIGHT_CONSTRAINT_PROPERTY)

        conversionContextClassDef.apply {
            // Add interface and helper methods to allow extension code to call obfuscated methods.
            interfaces.add(EXTENSION_CONTEXT_INTERFACE)

            arrayOf(
                Triple(
                    "patch_getIdentifier",
                    "Ljava/lang/String;",
                    identifierField
                ),
                Triple(
                    "patch_getPathBuilder",
                    "Ljava/lang/StringBuilder;",
                    stringBuilderField
                ),
                Triple(
                    "patch_getHorizontalCollectionSwipeProtector",
                    "Ljava/lang/Object;",
                    horizontalSwipeField
                ),
                Triple(
                    "patch_getHeightConstraint",
                    "Ljava/lang/Integer;",
                    heightConstraint
                )
            ).forEach { (interfaceMethodName, interfaceMethodReturnType, classFieldReference) ->
                methods.add(
                    ImmutableMethod(
                        type,
                        interfaceMethodName,
                        listOf(),
                        interfaceMethodReturnType,
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                iget-object v0, p0, $classFieldReference
                                return-object v0
                            """
                        )
                    }
                )
            }
        }
    }
}
