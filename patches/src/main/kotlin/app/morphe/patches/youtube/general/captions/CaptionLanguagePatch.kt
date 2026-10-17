/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3566
 * https://github.com/MorpheApp/morphe-patches/pull/3754
 * Auto-translate adapted from https://github.com/itolkien/morphe-universal-captions
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * Ported to RVX by lavinhoque33, 2026-10-17.
 */

// modified by lavinhoque33, 2026-10-17: Morphe's preferredCaptionLanguagePatch and
// autoTranslateCaptionsPatch merged into one RVX patch. The settings live in the General screen
// next to RVX's existing auto captions setting, and RVX's own video start hooks are reused.
package app.morphe.patches.youtube.general.captions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.startVideoInformerFingerprint
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.extension.Constants.GENERAL_PATH
import app.morphe.patches.youtube.utils.extension.sharedExtensionPatch
import app.morphe.patches.youtube.utils.patch.PatchList.CAPTION_LANGUAGE
import app.morphe.patches.youtube.utils.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.utils.playservice.versionCheckPatch
import app.morphe.patches.youtube.utils.settings.ResourceUtils.addPreference
import app.morphe.patches.youtube.utils.settings.settingsPatch
import app.morphe.patches.youtube.video.information.onCreateHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.fingerprint.methodOrThrow
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PREFERRED_EXTENSION_CLASS =
    "$GENERAL_PATH/captions/PreferredCaptionLanguagePatch;"
private const val PREFERRED_SUBTITLE_MANAGER_INTERFACE =
    "$GENERAL_PATH/captions/PreferredCaptionLanguagePatch\$SubtitleManagerInterface;"
private const val PREFERRED_CAPTION_TRACK_INTERFACE =
    "$GENERAL_PATH/captions/PreferredCaptionLanguagePatch\$CaptionTrackInterface;"

private const val TRANSLATE_EXTENSION_CLASS =
    "$GENERAL_PATH/captions/AutoTranslateCaptionsPatch;"
private const val TRANSLATE_CAPTION_TRACK_INTERFACE =
    "$GENERAL_PATH/captions/AutoTranslateCaptionsPatch\$CaptionTrackInterface;"

/**
 * Adds a public final method.
 *
 * @param parameters Object parameter types.
 * @param freeRegisters Number of free registers, starting at v0.
 */
private fun MutableClass.addHelperMethod(
    name: String,
    returnType: String,
    parameters: List<String> = listOf(),
    freeRegisters: Int = 1,
    smali: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            // Parameter registers are p0 (this) and one for each parameter.
            MutableMethodImplementation(freeRegisters + 1 + parameters.size),
        ).toMutable().apply {
            addInstructions(0, smali)
        }
    )
}

@Suppress("unused")
val captionLanguagePatch = bytecodePatch(
    CAPTION_LANGUAGE.title,
    CAPTION_LANGUAGE.summary,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        videoInformationPatch,
    )

    execute {

        // region preferred caption language

        // Add interfaces and helper methods to allow extension code to call obfuscated code.

        val isDisableOptionFingerprint: Fingerprint
        val isAutoTranslatedFingerprint: Fingerprint
        if (is_21_02_or_greater) {
            isDisableOptionFingerprint = CaptionTrackIsDisableOptionFingerprint
            isAutoTranslatedFingerprint = CaptionTrackIsAutoTranslatedFingerprint
        } else {
            isDisableOptionFingerprint = CaptionTrackIsDisableOptionLegacyFingerprint
            isAutoTranslatedFingerprint = CaptionTrackIsAutoTranslatedLegacyFingerprint
        }

        isDisableOptionFingerprint.classDef.apply {
            /**
             * Field access or getter call of the matched instruction.
             */
            fun getterSmali(fingerprint: Fingerprint, matchIndex: Int): String {
                val reference = fingerprint.instructionMatches[matchIndex].instruction.getReference<Reference>()
                return if (is_21_02_or_greater) {
                    "iget-object v0, p0, $reference"
                } else {
                    """
                        invoke-virtual { p0 }, $reference
                        move-result-object v0
                    """
                }
            }

            interfaces.add(PREFERRED_CAPTION_TRACK_INTERFACE)

            addHelperMethod(
                "patch_getLanguageCode",
                "Ljava/lang/String;",
                smali = """
                    ${getterSmali(isDisableOptionFingerprint, 1)}
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_getVssId",
                "Ljava/lang/String;",
                smali = """
                    ${getterSmali(isAutoTranslatedFingerprint, 0)}
                    return-object v0
                """
            )
        }

        DefaultCaptionTrackFingerprint.classDef.apply {
            val tracksManagerField = SubtitleManagerDirectTracksFingerprint(
                CaptionTracksManagerDirectTracksFingerprint.method
            ).instructionMatches[0].instruction.getReference<FieldReference>()!!

            interfaces.add(PREFERRED_SUBTITLE_MANAGER_INTERFACE)

            fun addTracksGetter(name: String, tracksFingerprint: Fingerprint) {
                addHelperMethod(
                    name,
                    "Ljava/util/List;",
                    smali = """
                        iget-object v0, p0, $tracksManagerField
                        if-eqz v0, :null
                        invoke-virtual { v0 }, ${tracksFingerprint.method}
                        move-result-object v0
                        :null
                        return-object v0
                    """
                )
            }

            val defaultSourceMethod = CaptionTracksManagerDefaultSourceFingerprint(
                CaptionTrackBuilderBuildFingerprint.method.returnType
            ).method

            addTracksGetter("patch_getDirectCaptionTracks", CaptionTracksManagerDirectTracksFingerprint)
            addTracksGetter("patch_getAutoTranslateCaptionTracks", CaptionTracksManagerAutoTranslateTracksFingerprint)
            addHelperMethod(
                "patch_getDefaultCaptionSource",
                PREFERRED_CAPTION_TRACK_INTERFACE,
                smali = """
                    iget-object v0, p0, $tracksManagerField
                    if-eqz v0, :null
                    invoke-virtual { v0 }, $defaultSourceMethod
                    move-result-object v0
                    :null
                    return-object v0
                """
            )
        }

        // Override the default caption track.

        // Hook the call site and not the default track method itself,
        // so the subtitle manager instance is available.
        val trackType = DefaultCaptionTrackFingerprint.method.returnType

        Fingerprint(
            definingClass = DefaultCaptionTrackFingerprint.classDef.type,
            filters = listOf(
                methodCall(reference = DefaultCaptionTrackFingerprint.method),
                opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
            )
        // modified by lavinhoque33, 2026-10-17: Morphe hooks only the first call site. On 21.39 the
        // default track getter is also called by the video load path, which posts the initial track
        // without setSubtitleTrack, so captions that are already on ignored the preference. Hook all
        // call sites, last index first so earlier indices in the same method stay valid.
        ).matchAll(DefaultCaptionTrackFingerprint.classDef).sortedByDescending {
            it.instructionMatches[1].index
        }.forEach {
            val subtitleManagerRegister = it.instructionMatches[0]
                .getInstruction<FiveRegisterInstruction>().registerC
            val moveResultIndex = it.instructionMatches[1].index
            val trackRegister = it.instructionMatches[1].getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructions(
                moveResultIndex + 1,
                """
                    invoke-static { v$subtitleManagerRegister, v$trackRegister }, $PREFERRED_EXTENSION_CLASS->getPreferredCaptionTrack($PREFERRED_SUBTITLE_MANAGER_INTERFACE$PREFERRED_CAPTION_TRACK_INTERFACE)$PREFERRED_CAPTION_TRACK_INTERFACE
                    move-result-object v$trackRegister
                    check-cast v$trackRegister, $trackType
                """
            )
        }

        SetSubtitleTrackFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0, p1, p2 }, $PREFERRED_EXTENSION_CLASS->onSetSubtitleTrack($PREFERRED_SUBTITLE_MANAGER_INTERFACE${PREFERRED_CAPTION_TRACK_INTERFACE}Ljava/lang/Enum;)$PREFERRED_CAPTION_TRACK_INTERFACE
                move-result-object p1
                check-cast p1, $trackType
            """
        )

        // RVX equivalents of Morphe's onCreateHook (no player controller argument) and hookVideoStarted.
        onCreateHook(PREFERRED_EXTENSION_CLASS, "newVideoStarted")

        startVideoInformerFingerprint.methodOrThrow().addInstruction(
            0,
            "invoke-static { }, $PREFERRED_EXTENSION_CLASS->videoInformationLoaded()V"
        )

        // endregion

        // region additional auto-translate languages

        val labelField = CaptionTrackBuilderBuildFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!
        val urlField = CaptionTracksManagerAutoTranslateTracksFingerprint.instructionMatches[0]
            .instruction.getReference<FieldReference>()!!

        val setLanguageCodeMethod = CaptionTrackBuilderSetterFingerprint("Null languageCode").method
        val setUrlMethod = CaptionTrackBuilderSetterFingerprint("Null url").method
        val setVssIdMethod = CaptionTrackBuilderSetterFingerprint("Null vssId").method

        // Add interface and helper methods to allow extension code to call obfuscated code.
        mutableClassDefBy(CaptionTrackBuilderBuildFingerprint.method.returnType).apply {
            interfaces.add(TRANSLATE_CAPTION_TRACK_INTERFACE)

            // 20.x tracks are an abstract class, and the url field is in the subclass.
            addHelperMethod(
                "patch_getUrl",
                "Ljava/lang/String;",
                smali = """
                    move-object v0, p0
                    check-cast v0, ${urlField.definingClass}
                    iget-object v0, v0, $urlField
                    return-object v0
                """
            )

            addHelperMethod(
                "patch_copyTranslation",
                TRANSLATE_CAPTION_TRACK_INTERFACE,
                listOf(
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/String;",
                    "Ljava/lang/CharSequence;"
                ),
                smali = """
                    new-instance v0, ${CaptionTrackBuilderBuildFingerprint.classDef.type}
                    invoke-direct { v0, p0 }, ${CaptionTrackBuilderCopyConstructorFingerprint.method}
                    invoke-virtual { v0, p1 }, $setLanguageCodeMethod
                    invoke-virtual { v0, p2 }, $setUrlMethod
                    invoke-virtual { v0, p3 }, $setVssIdMethod
                    iput-object p4, v0, $labelField
                    invoke-virtual { v0 }, ${CaptionTrackBuilderBuildFingerprint.method}
                    move-result-object v0
                    return-object v0
                """
            )
        }

        val directTracks = CaptionTracksManagerDirectTracksFingerprint.method
        val autoTracks = CaptionTracksManagerAutoTranslateTracksFingerprint.method
        val defaultSource = CaptionTracksManagerDefaultSourceFingerprint(
            CaptionTrackBuilderBuildFingerprint.method.returnType
        ).method
        // Keep both original getters intact. The adapter calls them once and cannot call itself.
        CaptionTracksManagerDirectTracksFingerprint.classDef.addHelperMethod(
            "patch_getAutoTranslateTracks", "Ljava/util/List;", freeRegisters = 3,
            smali = """
                invoke-virtual { p0 }, $autoTracks
                move-result-object v0
                invoke-virtual { p0 }, $directTracks
                move-result-object v1
                invoke-virtual { p0 }, $defaultSource
                move-result-object v2
                invoke-static { v0, v1, v2 }, $TRANSLATE_EXTENSION_CLASS->restoreTranslationTracks(Ljava/util/List;Ljava/util/List;$PREFERRED_CAPTION_TRACK_INTERFACE)Ljava/util/List;
                move-result-object v0
                return-object v0
            """
        )

        val adapter = "${directTracks.definingClass}->patch_getAutoTranslateTracks()Ljava/util/List;"
        // Route the UI getter and the existing preferred-language interface getter through it.
        AutoTranslateTracksCallFingerprint(autoTracks).matchAll().forEach {
            val firstFilterMatch = it.instructionMatches[0]
            val register = firstFilterMatch.instruction.registersUsed[0]
            it.method.replaceInstruction(
                firstFilterMatch.index,
                "invoke-virtual/range { v$register .. v$register }, $adapter"
            )
        }

        // Reuse YouTube's localized option factory and preserve its remaining native checks.
        CaptionTracksManagerTranslationCountFingerprint.apply {
            val countMatch = instructionMatches[1]
            val count = countMatch.getInstruction<OneRegisterInstruction>().registerA
            val result = instructionMatches.last().getInstruction<OneRegisterInstruction>().registerA
            method.addInstructions(
                countMatch.index + 1,
                """
                    invoke-static { v$count, v$result }, $TRANSLATE_EXTENSION_CLASS->getTranslationCount(ILjava/util/List;)I
                    move-result v$count
                """
            )
        }

        CaptionTracksManagerAutoTranslateTracksFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructionsAtControlFlowLabel(
                    index,
                    """
                        invoke-static { v$register }, $TRANSLATE_EXTENSION_CLASS->addTranslationLanguages(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """
                )
            }
        }

        // endregion

        // region add settings

        addPreference(
            arrayOf(
                "PREFERENCE_SCREEN: GENERAL",
                "SETTINGS: CAPTION_LANGUAGE"
            ),
            CAPTION_LANGUAGE
        )

        // endregion

    }
}
