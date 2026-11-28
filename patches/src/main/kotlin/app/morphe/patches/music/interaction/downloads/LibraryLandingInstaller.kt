/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.fingerprint.matchSingle
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val LANDING = "Lapp/morphe/extension/music/patches/downloads/LibraryLanding;"
private const val LANDING_PAGE = $$"Lapp/morphe/extension/music/patches/downloads/LibraryLanding$Page;"
private const val LANDING_HOLDER = $$"Lapp/morphe/extension/music/patches/downloads/LibraryLanding$Holder;"
private const val LANDING_ADAPTER = $$"Lapp/morphe/extension/music/patches/downloads/LibraryLanding$Adapter;"
private const val LANDING_HOST = $$"Lapp/morphe/extension/music/patches/downloads/LibraryLanding$Host;"
private const val CHIP_HOST = $$"Lapp/morphe/extension/music/patches/downloads/LibraryLanding$ChipHost;"
private const val CHIP_ADAPTER = $$"Lapp/morphe/extension/music/patches/downloads/LibraryLanding$ChipAdapter;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val OBJECT = "Ljava/lang/Object;"
private const val THROWABLE = "Ljava/lang/Throwable;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val OPTIONAL = "Lj$/util/Optional;"
private const val REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"
private const val ITERATOR = "Ljava/util/Iterator;"

/** The app's swipe to refresh view, which the tab binder of the Library fragment makes for every tab it shows. */
private const val SWIPE_REFRESH = "/ui/refresh/MusicSwipeRefreshLayout;"

/**
 * Where the Library fragment gives a tab its section list, in every supported version: the fragment's private method
 * of a tab list and an optional saved state (9.45.51 `Lgme.ak`, 9.40.51 `Lgoa.ai`, 9.15.51 `Ljqt.ai`, 8.30.54
 * `Ljid.W`, 8.28.54 `Ljhb.W`, 8.12.54 `Lizr.Y`) makes a swipe to refresh view and an adapter for each tab, takes the
 * tab's section list holder, and binds it to the adapter (`Ladzf.au(Lwgr;)V` in 9.45.51), unless a saved state of the
 * adapter restores the page.
 */
private class LandingAbi(
    val host: String,
    val binder: Method,
    val bindIndex: Int,
    val adapterRegister: Int,
    val holderRegister: Int,
    val holderType: String,
    val adapterBase: String,
    val pageStart: Method,
    val pageModel: String,
    val browseIdOwner: String,
    val browseIdName: String,
)

/** Native slots and wrappers verified on each supported stock binary. */
private data class LandingSlots(
    val modelField: String,
    val adapterField: String,
    val chipField: String,
    val contentsField: String,
    val wrapper: String,
    val message: String,
    val item: String,
    val commit: String,
    val replacement: String,
    val insertion: String,
    val removal: String? = null,
    val legacy: LegacySlots? = null,
)

/** Before 9.15, stock inserts the new binder and the native composite removes the old child. */
private data class LegacySlots(
    val bindersField: String,
    val composer: String,
    val binder: String,
    val child: String,
    val getChild: String,
    val stableKey: String,
    val savedChild: String,
)

private fun landingSlots(version: String) = when (version) {
    "9.45.51" -> LandingSlots("s", "w", "an", "p", "Lwgo;", "Lammy;", "Lapwf;", "aT", "ad", "S")
    "9.40.51" -> LandingSlots("s", "w", "ak", "o", "Lwbb;", "Lambp;", "Lapjy;", "aR", "ae", "T")
    "9.15.51" -> LandingSlots("B", "I", "at", "j", "Laoxy;", "Lbtbf;", "Lbzmc;", "aM", "R", "R", "Z")
    "8.30.54" -> LandingSlots("s", "z", "ai", "h", "Lamrc;", "Lboal;", "Lbtmq;", "J", "B", "B",
        legacy = LegacySlots("g", "Lazvi;", "Lbadh;", "Lazum;", "fh", "S", "Lbagg;"))
    "8.28.54" -> LandingSlots("s", "z", "ai", "i", "Lamkt;", "Lbnkz;", "Lbsud;", "I", "A", "A",
        legacy = LegacySlots("h", "Lazis;", "Lazqp;", "Lazhw;", "fc", "R", "Laztm;"))
    "8.12.54" -> LandingSlots("r", "y", "ai", "i", "Lakoq;", "Lblaw;", "Lbqhe;", "H", "y", "y",
        legacy = LegacySlots("h", "Laxce;", "Laxjy;", "Laxbi;", "eG", "Q", "Laxmu;"))
    else -> error("no native Library slots for $version")
}

/** A request is already in flight by the time the fragment's continuation observer gets called. */
private fun landingDispatch(version: String) = when (version) {
    "9.45.51" -> "Laeaj;" to "f"
    "9.40.51" -> "Ladpc;" to "h"
    "9.15.51" -> "Lbduj;" to "h"
    "8.30.54" -> "Lbacb;" to "e"
    "8.28.54" -> "Lazpm;" to "e"
    "8.12.54" -> "Laxiv;" to "g"
    else -> error("no native Library reload dispatcher for $version")
}

internal fun BytecodePatchContext.prepareLibraryLanding(): (() -> Unit)? {
    try {
        val abi = findLandingAbi()
        val slots = landingSlots(packageMetadata.versionName)
        val holder = landingHolderShape(abi.holderType)
        val serialize = serializerOf(holder.type)
        val emptySection = classDefByOrNull(holder.type)!!.fields.single {
            it.type == holder.type && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val parserOwner = checkNotNull(parseByteArrayMethodRef.get()).definingClass
        val parser = mutableClassDefBy(parserOwner).methods.single {
            it.name == "parseFrom" && it.parameterTypes == listOf(parserOwner, "[B", REGISTRY)
        }
        val itemClass = classDefByOrNull(slots.item)!!
        check(itemClass.superclass == parserOwner) { "landing: stock item parser" }
        val itemDefault = itemClass.fields.single { it.type == slots.item && AccessFlags.STATIC.isSet(it.accessFlags) }
        check(declaresFragmentState(abi.host)) { "landing: ${abi.host} is no fragment" }

        val binder = abi.binder.implementation!!.instructions.toList()
        val start = binder.first() as TwoRegisterInstruction
        check(start.opcode == Opcode.MOVE_OBJECT_FROM16 && start.registerA == 0 &&
            start.registerB == abi.binder.implementation!!.registerCount - 3) { "landing: fragment register" }
        val branch = binder[abi.bindIndex - 1]
        val jump = binder[abi.bindIndex + 1]
        check(branch.opcode == Opcode.IF_NEZ && jump.opcode == Opcode.GOTO &&
            (branch as OffsetInstruction).codeOffset == branch.codeUnits + binder[abi.bindIndex].codeUnits +
            jump.codeUnits) { "landing: saved adapter branch" }
        val savedIndex = abi.bindIndex + 2
        check(binder.drop(savedIndex).take(20).any {
            it.getReference<MethodReference>()?.name == "onRestoreInstanceState"
        }) { "landing: native layout parcel" }
        val model = binder.mapNotNull { it.getReference<FieldReference>() }.distinctBy(FieldReference::toString)
            .single { it.definingClass == abi.host && it.name == slots.modelField && it.type == abi.pageModel }
        val published = binder.mapNotNull { it.getReference<FieldReference>() }.distinctBy(FieldReference::toString)
            .single { it.definingClass == abi.host && it.name == slots.adapterField && it.type == OPTIONAL }
        val browseId = landingMethod(abi.browseIdOwner, abi.browseIdName, emptyList(), STRING)
        val command = checkNotNull(browseId.implementation!!.instructions.first().getReference<FieldReference>()) {
            "landing: native browse command"
        }
        check(command.definingClass == classDefByOrNull(abi.browseIdOwner)!!.superclass) {
            "landing: native command owner"
        }
        val serializeCommand = serializerOf(command.type)
        val cacheMetadata = Fingerprint(name = "toString", strings = listOf(", wasLoadedFromDiskCache="))
            .matchSingle()
        val cacheCode = cacheMetadata.method.implementation!!.instructions.toList()
        val cacheLabel = cacheCode.indexOfFirst {
            it.getReference<StringReference>()?.string == ", wasLoadedFromDiskCache="
        }
        val fromDisk = cacheCode.drop(cacheLabel + 1).first { it.opcode == Opcode.IGET_BOOLEAN }
            .getReference<FieldReference>()!!
        // Newer models expose the flag directly; legacy ones hold its abstract metadata superclass.
        val metadataOwner = cacheMetadata.classDef
        val modelFields = classDefByOrNull(abi.pageModel)!!.fields
            .filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }
        val responseMetadata = modelFields.single { it.type in listOf(metadataOwner.type, metadataOwner.superclass) }
        val readDiskFlag = if (responseMetadata.type == metadataOwner.type) {
            "iget-boolean v0, v0, $fromDisk"
        } else {
            val getter = metadataOwner.methods.single { method ->
                method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                    method.implementation?.instructions?.firstOrNull()?.getReference<FieldReference>() == fromDisk
            }
            val abstractGetter = landingMethod(responseMetadata.type, getter.name, emptyList(), "Z")
            "invoke-virtual {v0}, $abstractGetter\nmove-result v0"
        }
        val chip = classDefByOrNull(abi.host)!!.fields.single { it.name == slots.chipField }
        val selected = landingMethod(chip.type, "j", emptyList(), "Z")
        val contents = landingField(abi.adapterBase, slots.contentsField, LIST)
        val message = landingField(slots.wrapper, "a", slots.message)
        val serializeMessage = serializerOf(message.type)
        val convert = landingMethod(abi.holderType, "c", listOf(slots.item), OPTIONAL)
        check(convert.implementation!!.instructions.any {
            it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == slots.wrapper
        }) { "landing: stock renderer conversion" }
        val replace = replacementOf(abi, slots, contents, convert)
        val append = insertionOf(abi, slots, convert)
        val commit = classDefByOrNull(abi.adapterBase)!!.methods.single {
            it.name == slots.commit && it.parameterTypes.firstOrNull() == abi.holderType && it.returnType == "V"
        }
        val destroy = landingMethod(abi.host, "onDestroyView", emptyList(), "V")
        val hidden = landingMethod(abi.host, "onHiddenChanged", listOf("Z"), "V")
        // On 8.x the stock failure callback builds the sort/filter toolbar after the section factory splice.
        // The host mounts that top-level renderer separately, so replacing the section does not remove its menu.
        val legacyToolbar = packageMetadata.versionName in setOf("8.30.54", "8.28.54", "8.12.54")
        val installChipHeader = if (legacyToolbar) null else prepareLandingChipHeader(abi, holder)
        val startReload = prepareReloadRevocation(abi, command.type, commit.takeIf { installChipHeader != null })
        val installFallback = if (legacyToolbar || installChipHeader != null) {
            prepareLandingFailure(abi, emptySection)
        } else {
            null
        }
        val installDirect = installFallback?.let { prepareLibraryDirectOfflineStart(abi.host, abi.pageModel, command) }
        val installModelChanging = prepareModelChanging(model)

        return {
            val page = mutableClassDefBy(command.definingClass)
            page.interfaces.add(LANDING_PAGE)
            page.addDownloadsBridge("rvxLandingCommand", emptyList(), "[B", 2, """
                iget-object v0, p0, $command
                if-eqz v0, :missing
                invoke-virtual {v0}, $serializeCommand
                move-result-object v0
                return-object v0
                :missing
                const/4 v0, 0x0
                return-object v0
            """)
            // Concurrent cache misses can offer different models; do not let a refresh claim the first one's bind.
            val missField = "${page.type}->rvxLandingCacheMiss:Z"
            page.instanceFields.add(ImmutableField(
                page.type, "rvxLandingCacheMiss", "Z", AccessFlags.PRIVATE.value or AccessFlags.VOLATILE.value,
                null, emptySet(), emptySet(),
            ).toMutable())
            page.addDownloadsBridge("rvxLandingMarkNoCache", emptyList(), "V", 2, """
                const/4 v0, 0x1
                iput-boolean v0, p0, $missField
                return-void
            """)
            page.addDownloadsBridge("rvxLandingTakeNoCache", emptyList(), "Z", 3, """
                iget-boolean v0, p0, $missField
                const/4 v1, 0x0
                iput-boolean v1, p0, $missField
                return v0
            """)

            val holderClass = mutableClassDefBy(abi.holderType)
            holderClass.interfaces.add(LANDING_HOLDER)
            holderClass.addDownloadsBridge("rvxContentBytes", emptyList(), "[B", 2, """
                iget-object v0, p0, $holder
                invoke-virtual {v0}, $serialize
                move-result-object v0
                return-object v0
            """)
            holderClass.addDownloadsBridge("rvxLandingNoCache", emptyList(), "Z", 3, """
                iget-object v0, p0, $holder
                sget-object v1, $emptySection
                if-ne v0, v1, :not_default
                const/4 v0, 0x1
                return v0
                :not_default
                const/4 v0, 0x0
                return v0
            """)

            val adapter = mutableClassDefBy(abi.adapterBase)
            adapter.interfaces.add(LANDING_ADAPTER)
            adapter.addDownloadsBridge("rvxLandingContents", emptyList(), LIST, 2, """
                iget-object v0, p0, $contents
                return-object v0
            """)
            adapter.addDownloadsBridge("rvxLandingRenderBytes", listOf(OBJECT), "[B", 3, """
                instance-of v0, p1, ${slots.wrapper}
                if-eqz v0, :missing
                check-cast p1, ${slots.wrapper}
                iget-object v0, p1, $message
                invoke-virtual {v0}, $serializeMessage
                move-result-object v0
                return-object v0
                :missing
                const/4 v0, 0x0
                return-object v0
            """)
            adapter.addDownloadsBridge("rvxLandingReplace", listOf("I", "[B"), "Z", 11, """
                sget-object v0, $itemDefault
                invoke-static {}, $REGISTRY->getGeneratedRegistry()$REGISTRY
                move-result-object v1
                invoke-static {v0, p2, v1}, $parser
                move-result-object v0
                check-cast v0, ${slots.item}
                $replace
                const/4 v0, 0x1
                return v0
                :rejected
                const/4 v0, 0x0
                return v0
            """)
            adapter.addDownloadsBridge("rvxLandingAppend", listOf("I", "[B"), "V", 8, """
                sget-object v0, $itemDefault
                invoke-static {}, $REGISTRY->getGeneratedRegistry()$REGISTRY
                move-result-object v1
                invoke-static {v0, p2, v1}, $parser
                move-result-object v0
                check-cast v0, ${slots.item}
                $append
                :done
                return-void
            """)
            adapter.findMutableMethodOf(commit).addInstructions(0,
                "invoke-static/range {p0 .. p0}, $LANDING->adapterChanging($OBJECT)V")
            startReload()

            val host = mutableClassDefBy(abi.host)
            host.interfaces.add(LANDING_HOST)
            host.addDownloadsBridge("rvxLive", emptyList(), "Z", 2, """
                invoke-virtual {p0}, ${abi.host}->isAdded()Z
                move-result v0
                if-eqz v0, :gone
                invoke-virtual {p0}, ${abi.host}->isHidden()Z
                move-result v0
                if-nez v0, :gone
                const/4 v0, 0x1
                return v0
                :gone
                const/4 v0, 0x0
                return v0
            """)
            host.addDownloadsBridge("rvxLandingModel", emptyList(), OBJECT, 2, """
                iget-object v0, p0, $model
                return-object v0
            """)
            host.addDownloadsBridge("rvxLandingFromDisk", emptyList(), "Z", 2, """
                iget-object v0, p0, $model
                if-eqz v0, :missing
                iget-object v0, v0, $responseMetadata
                if-eqz v0, :missing
                $readDiskFlag
                return v0
                :missing
                const/4 v0, 0x0
                return v0
            """)
            host.addDownloadsBridge("rvxLandingAdapter", emptyList(), OBJECT, 3, """
                iget-object v0, p0, $published
                const/4 v1, 0x0
                invoke-virtual {v0, v1}, $OPTIONAL->orElse($OBJECT)$OBJECT
                move-result-object v0
                return-object v0
            """)
            host.addDownloadsBridge("rvxLandingSelected", emptyList(), "Z", 2, """
                iget-object v0, p0, $chip
                if-eqz v0, :no_legacy_chip
                invoke-virtual {v0}, $selected
                move-result v0
                return v0
                :no_legacy_chip
                const/4 v0, 0x0
                return v0
            """)
            host.findMutableMethodOf(destroy).addInstructions(0,
                "invoke-static/range {p0 .. p0}, $LANDING->hostChanging($OBJECT)V")
            host.findMutableMethodOf(hidden).addInstructions(0,
                "invoke-static/range {p0 .. p1}, $LANDING->hostHidden(${OBJECT}Z)V")
            installModelChanging()
            installChipHeader?.invoke()
            installFallback?.invoke()
            installDirect?.invoke()
            if (installFallback != null) enableOfflineLibraryRestore()
            host.findMutableMethodOf(abi.pageStart).addInstructions(0, "invoke-static {}, $LANDING->pageStarted()V")

            val adapterRegister = "v${abi.adapterRegister}"
            val holderRegister = "v${abi.holderRegister}"
            val target = host.findMutableMethodOf(abi.binder)
            target.addInstructionsAtControlFlowLabel(savedIndex, """
                invoke-static {v0, $adapterRegister}, $LANDING->savedContentAccepted($OBJECT$OBJECT)V
            """)
            val freshHook = "$LANDING->freshContentAccepted($OBJECT$OBJECT$OBJECT)V"
            target.addInstructions(abi.bindIndex + 1,
                "invoke-static {v0, $adapterRegister, $holderRegister}, $freshHook")
            printInfo("LibraryLanding: ${abi.host}->${abi.binder.name}, fresh/saved native renderer slots")
        }
    } catch (ex: Exception) {
        printWarn("LibraryLanding: the plain Library offline is the app's own: ${ex.message}")
        return null
    }
}

/**
 * Keep the persistent cache as the first choice. If its default Library browse fails, use the stock
 * single-tab response and toolbar builder with an empty section list instead of an error continuation.
 * The accepted adapter receives the shelf only under its own active-model/adapter lease.
 */
private fun BytecodePatchContext.prepareLandingFailure(
    abi: LandingAbi,
    default: FieldReference,
): () -> Unit {
    val sectionType = default.type
    val match = Fingerprint(
        returnType = "Lcom/google/common/util/concurrent/ListenableFuture;",
        parameters = listOf(OBJECT),
        strings = listOf("Failed to load response from PersistentBrowseService", "handleDefaultBrowse"),
    ).matchSingle()
    val method = match.method
    val code = method.implementation!!.instructions.toList()
    val callback = match.classDef.type
    val modelRead = code.indices.single { index ->
        val step = code[index]
        val field = step.getReference<FieldReference>()
        step.opcode == Opcode.IGET_OBJECT && field?.definingClass == callback && field.type == abi.pageModel
    }
    val modelField = code[modelRead].getReference<FieldReference>()!!
    val modelRegister = (code[modelRead] as TwoRegisterInstruction).registerA
    val receiver = (code[modelRead] as TwoRegisterInstruction).registerB
    val stockFactory = code.indices.single { index ->
        val step = code[index]
        val ref = step.getReference<MethodReference>()
        step.opcode == Opcode.INVOKE_STATIC && ref?.parameterTypes == listOf(STRING) && ref.returnType == sectionType
    }
    check(code[stockFactory + 1].opcode == Opcode.MOVE_RESULT_OBJECT) { "landing: section factory result" }
    val sectionRegister = (code[stockFactory + 1] as OneRegisterInstruction).registerA
    val metadata = (modelRead + 1 until stockFactory).last { index ->
        val call = code[index] as? FiveRegisterInstruction ?: return@last false
        val ref = call.getReference<MethodReference>() ?: return@last false
        ref.definingClass == abi.pageModel && ref.parameterTypes.isEmpty() && ref.returnType == "Z" &&
            call.registerC == modelRegister
    }
    val logMarker = code.indices.single { code[it].getReference<StringReference>()?.string == "handleDefaultBrowse" }
    val logger = (logMarker + 1 until modelRead).first {
        code[it].opcode == Opcode.INVOKE_STATIC_RANGE &&
            code[it].getReference<MethodReference>()?.parameterTypes?.lastOrNull() == THROWABLE
    }
    val logRegisters = code[logger] as RegisterRangeInstruction
    val failure = logRegisters.startRegister + logRegisters.registerCount - 1
    val cast = (0 until logMarker).single {
        code[it].opcode == Opcode.CHECK_CAST && (code[it] as OneRegisterInstruction).registerA == failure &&
            code[it].getReference<TypeReference>()?.type == THROWABLE
    }
    val sideloaded = code.indexOfFirst {
        it.getReference<StringReference>()?.string == "FEmusic_library_sideloaded_tracks"
    }
    val start: Int
    val carry: Int
    val restore: String
    if (sideloaded >= 0) {
        // This stock literal is dead after the optional Downloads/sideloaded side effects.
        carry = (code[sideloaded] as OneRegisterInstruction).registerA
        restore = "const-string v$carry, \"FEmusic_library_sideloaded_tracks\""
        start = (sideloaded + 1 until modelRead).first { code[it].opcode == Opcode.IF_EQZ }
    } else {
        // Legacy callbacks have no literal to spare. They finish logging before the allowlist read, which is where
        // the splice goes, and carry the local section in a register the stock code is not using there.
        start = logger + 1
        check(code[start].opcode == Opcode.SGET_OBJECT && receiver == method.implementation!!.registerCount - 2) {
            "landing: legacy cache failure registers"
        }
        carry = legacyCarryRegister(method, code, start, stockFactory,
            setOf(sectionRegister, modelRegister, receiver, failure))
        // The stock path leaves the splice with a null reference, which the type test takes, not the int flag.
        restore = "const/4 v$carry, 0x0"
    }
    check(failure in 0..15 && failure != carry && (cast + 1 until start).none { code[it].writes(failure) }) {
        "landing: native browse failure is not live at the fallback"
    }
    check(start < modelRead && sectionRegister != carry && modelRegister != carry && receiver != carry) {
        "landing: local section registers"
    }
    check((metadata until stockFactory).none { code[it].writes(carry) }) {
        "landing: stock response metadata overwrites the local section"
    }
    return {
        val cls = mutableClassDefBy(callback)
        val target = cls.findMutableMethodOf(method)
        // The stock default section contains no continuation; it is already a registered native proto.
        target.addInstructionsWithLabels(stockFactory, """
            instance-of v$sectionRegister, v$carry, $sectionType
            if-eqz v$sectionRegister, :rvx_stock_section
            move-object v$sectionRegister, v$carry
            check-cast v$sectionRegister, $sectionType
            goto :rvx_stock_header
        """, ExternalLabel("rvx_stock_section", target.getInstruction<Instruction>(stockFactory)),
            ExternalLabel("rvx_stock_header", target.getInstruction<Instruction>(stockFactory + 2)))
        target.addInstructionsWithLabels(start, """
            iget-object v$carry, v$receiver, $modelField
            invoke-static {v$carry, v$failure}, $LANDING->canOpenWithoutCache($OBJECT$THROWABLE)Z
            move-result v$carry
            if-eqz v$carry, :rvx_no_local_section
            sget-object v$carry, $default
            iget-object v$modelRegister, v$receiver, $modelField
            goto :rvx_stock_metadata
            :rvx_no_local_section
            $restore
        """, ExternalLabel("rvx_stock_metadata", target.getInstruction<Instruction>(metadata)))
        printInfo("LibraryLanding: ${match.classDef.type}->${method.name} native no-cache response")
    }
}

/**
 * The register the legacy splice at [start] borrows to carry the local section to [stockFactory]. The stock code
 * must have no use for it: it is a 4-bit register, because the spliced instructions take 4-bit operands; none of
 * the [taken] registers the splice uses for something else; not written by the stock code in between, so the carried
 * value is still there at [stockFactory]; and assigned before it is read on every stock path from [start].
 * Which register that is differs between versions, so it comes from the bytecode.
 */
private fun legacyCarryRegister(
    method: Method,
    code: List<Instruction>,
    start: Int,
    stockFactory: Int,
    taken: Set<Int>,
): Int {
    val implementation = method.implementation!!
    val carry = (0 until minOf(16, implementation.registerCount)).firstOrNull { register ->
        register !in taken &&
            (start until stockFactory).none { code[it].writes(register) } &&
            code.isRegisterDeadAt(start, register, implementation.tryBlocks)
    }
    return checkNotNull(carry) { "landing: no register is free for the local section" }
}

/** The stock setter is called for the model the fragment actually shows, not for speculative browse work. */
private fun BytecodePatchContext.prepareModelChanging(field: FieldReference): () -> Unit {
    val owner = generateSequence(classDefByOrNull(field.definingClass)) { cls ->
        cls.superclass?.let { classDefByOrNull(it) }
    }.first { cls -> cls.fields.any { it.name == field.name && it.type == field.type } }
    val declared = owner.fields.single { it.name == field.name && it.type == field.type }
    val setter = owner.methods.single { method ->
        method.parameterTypes == listOf(field.type) && method.returnType == "V" &&
            method.implementation?.instructions?.any {
                it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>() == declared
            } == true
    }
    return {
        mutableClassDefBy(owner.type).findMutableMethodOf(setter).addInstructions(0,
            "invoke-static/range {p0 .. p1}, $LANDING->modelChanging($OBJECT$OBJECT)V",
        )
    }
}

private fun BytecodePatchContext.prepareReloadRevocation(
    abi: LandingAbi,
    commandType: String,
    commit: Method?,
): () -> Unit {
    val observer = classDefByOrNull(abi.host)!!.methods.single { method ->
        method.parameterTypes.size == 2 && method.parameterTypes[1] == commandType && method.returnType == "V" &&
            method.implementation?.instructions?.any {
                it.getReference<StringReference>()?.string == "no_connection_error_continuation"
            } == true
    }
    val tokenType = observer.parameterTypes.first().toString()
    val observerCode = observer.implementation!!.instructions
    val kind = observerCode.first().getReference<MethodReference>()!!
    val reload = observerCode.elementAt(2).getReference<FieldReference>()!!
    check(kind.definingClass == tokenType && kind.returnType == reload.type) { "landing: native reload kind" }
    val tokenString = observerCode.mapNotNull { it.getReference<MethodReference>() }
        .distinctBy(MethodReference::toString).single {
            it.definingClass == tokenType && it.parameterTypes.isEmpty() && it.returnType == STRING
        }
    val (owner, name) = landingDispatch(packageMetadata.versionName)
    var type: String? = abi.adapterBase
    while (type != null && type != owner) type = classDefByOrNull(type)?.superclass
    check(type == owner) { "landing: reload dispatcher is not in the adapter chain" }
    val dispatch = classDefByOrNull(owner)!!.methods.single { it.name == name && it.parameterTypes.isNotEmpty() }
    val hook = "$owner->rvxLandingReloadRequested($tokenType)V"
    val code = dispatch.implementation!!.instructions.toList()
    val (index, invocation) = if (dispatch.parameterTypes.first() == tokenType) {
        0 to "invoke-virtual/range {p0 .. p1}, $hook"
    } else {
        val load = code.indexOfFirst {
            it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.type == tokenType
        }
        check(load in 0..4) { "landing: reload dispatcher token" }
        val constructor = (load + 1..load + 2).single { at ->
            val ref = code[at].getReference<MethodReference>()
            code[at].opcode == Opcode.INVOKE_DIRECT && ref?.name == "<init>" &&
                ref.parameterTypes == listOf(tokenType)
        }
        check(dispatch.implementation!!.registerCount <= 16) { "landing: reload dispatcher registers" }
        val register = (code[load] as TwoRegisterInstruction).registerA
        constructor + 1 to "invoke-virtual {p0, v$register}, $hook"
    }
    val acceptReload = if (commit != null) {
        prepareRetainedLandingReload(abi, tokenType, reload, commit)
    } else {
        printWarn("LibraryLanding: no retained Elements header reload; keeping the stock in-place response")
        null
    }
    return {
        val cls = mutableClassDefBy(owner)
        cls.addDownloadsBridge("rvxLandingReloadRequested", listOf(tokenType), "V", 4, """
            if-eqz p1, :done
            invoke-interface {p1}, $kind
            move-result-object v0
            sget-object v1, $reload
            if-ne v0, v1, :done
            invoke-interface {p1}, $tokenString
            move-result-object v0
            const-string v1, "no_connection_error_continuation"
            invoke-virtual {v1, v0}, $STRING->equals($OBJECT)Z
            move-result v0
            if-nez v0, :done
            invoke-static {p0}, $LANDING->reloadRequested($OBJECT)V
            :done
            return-void
        """)
        cls.findMutableMethodOf(dispatch).addInstructions(index, invocation)
        acceptReload?.invoke()
        if (acceptReload != null) {
            // Temporary shape-only evidence from the request that the native observer actually receives.
            val host = mutableClassDefBy(abi.host)
            val serialize = serializerOf(commandType)
            host.addDownloadsBridge("rvxLandingObservedCommandWire", listOf(OBJECT), "[B", 2, """
                check-cast p1, $commandType
                invoke-virtual {p1}, $serialize
                move-result-object p1
                return-object p1
            """)
            host.addDownloadsBridge("rvxLandingObserveReload", listOf(tokenType, commandType), "V", 5, """
                invoke-interface {p1}, $kind
                move-result-object v0
                sget-object v1, $reload
                if-ne v0, v1, :done
                invoke-interface {p1}, $tokenString
                move-result-object v0
                invoke-static {p0, v0, p2}, $LANDING->reloadObserved($OBJECT$STRING$OBJECT)V
                :done
                return-void
            """)
            host.findMutableMethodOf(observer).addInstructions(0,
                "invoke-virtual/range {p0 .. p2}, ${host.type}->rvxLandingObserveReload($tokenType$commandType)V")
        }
    }
}

/** Observe the RELOAD branch only after it has finished applying its replacement section. */
private fun BytecodePatchContext.prepareRetainedLandingReload(
    abi: LandingAbi,
    token: String,
    reload: FieldReference,
    commit: Method,
): (() -> Unit)? {
    try {
        val adapter = classDefByOrNull(abi.adapterBase)!!
        val apply = adapter.methods.single { method ->
            method.parameterTypes == listOf(abi.holderType, "Z") && method.returnType == "Z" &&
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.INVOKE_DIRECT && it.getReference<MethodReference>() == commit
                } == true
        }
        val receive = adapter.methods.single { method ->
            method.parameterTypes == listOf(abi.holderType, token) && method.returnType == "V" &&
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>() == reload
                } == true
        }
        val code = receive.implementation!!.instructions.toList()
        val site = code.indices.single {
            code[it].opcode == Opcode.INVOKE_VIRTUAL && code[it].getReference<MethodReference>() == apply
        }
        check(code[site - 4].opcode == Opcode.SGET_OBJECT &&
            code[site - 4].getReference<FieldReference>() == reload && code[site - 3].opcode == Opcode.IF_NE &&
            code[site - 2].opcode == Opcode.INVOKE_INTERFACE && code[site - 1].opcode == Opcode.MOVE_RESULT) {
            "landing: accepted section is not on the native RELOAD branch"
        }
        val call = code[site] as FiveRegisterInstruction
        val self = receive.implementation!!.registerCount - 3
        check(call.registerC == self && call.registerD == self + 1 &&
            code[site + 1].opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.RETURN_VOID)) {
            "landing: retained section apply registers"
        }
        return {
            val target = mutableClassDefBy(adapter.type).findMutableMethodOf(receive)
            target.addInstructions(site + 1,
                "invoke-static/range {p0 .. p1}, $LANDING->reloadContentAccepted($OBJECT$OBJECT)V")
            printInfo("LibraryLanding: ${adapter.type}->${receive.name} retained reload commit")
        }
    } catch (ex: Exception) {
        printWarn("LibraryLanding: retained reload commit unavailable: ${ex.message}")
        return null
    }
}

/** Stock Elements chip payload used by both the Library tab adapter and its local loading header. */
private class LandingChipSlots(
    val container: String,
    val loader: String,
    val adapter: String,
    val sectionHeader: String,
    val header: String,
    val elements: String,
)

private fun BytecodePatchContext.prepareLandingChipHeader(
    abi: LandingAbi,
    section: FieldReference,
): (() -> Unit)? {
    val slots = when (packageMetadata.versionName) {
        "9.45.51" -> LandingChipSlots("am", "Lect;", "Liyt;", "j", "Lapvw;", "Lakya;")
        "9.40.51" -> LandingChipSlots("aj", "Leev;", "Liyx;", "h", "Lapjp;", "Lakmy;")
        "9.15.51" -> LandingChipSlots("as", "Ljpe;", "Lqhb;", "h", "Lbzli;", "Lbpvt;")
        else -> {
            printWarn("LibraryLanding: no proven native unselected chip row; keeping stock cache misses")
            return null
        }
    }

    try {
        val container = landingField(abi.host, slots.container, VIEW_GROUP)
        val sectionHeader = landingField(section.type, slots.sectionHeader, slots.header)
        val headerKind = landingField(slots.header, "b", "I")
        val element = landingField(slots.header, "c", OBJECT)
        val serializeHeader = serializerOf(slots.header)
        val loading = classDefByOrNull(abi.host)!!.methods.single { method ->
            method.parameterTypes == listOf(abi.pageModel) && method.implementation?.instructions?.any {
                it.getReference<StringReference>()?.string == "loading_state_chip_downloads"
            } == true
        }
        val loadingCode = loading.implementation!!.instructions.toList()
        val countIndex = loadingCode.indices.single {
            loadingCode[it].getReference<MethodReference>()?.let { ref ->
                ref.definingClass == VIEW_GROUP && ref.name == "getChildCount"
            } == true
        }
        val groupRegister = (loadingCode[countIndex] as FiveRegisterInstruction).registerC
        val countRegister = (loadingCode[countIndex + 1] as OneRegisterInstruction).registerA
        check(groupRegister <= 15 && countRegister <= 15 &&
            loadingCode[countIndex + 1].opcode == Opcode.MOVE_RESULT &&
            loadingCode[countIndex + 2].opcode == Opcode.IF_GTZ) { "landing: native loading chip count" }

        val loader = classDefByOrNull(slots.loader)!!.methods.single { method ->
            method.implementation?.instructions?.any {
                it.getReference<MethodReference>()?.name == "isAttachedToWindow"
            } == true
        }
        val loaderCode = loader.implementation!!.instructions.toList()
        val builtIndex = loaderCode.indices.single {
            loaderCode[it].opcode == Opcode.CHECK_CAST &&
                loaderCode[it].getReference<TypeReference>()?.type == slots.elements
        }
        val payloadRegister = (loaderCode[builtIndex] as OneRegisterInstruction).registerA
        check(payloadRegister in 1..15) { "landing: native loading chip registers" }
        val attachedIndex = loaderCode.indices.single {
            loaderCode[it].getReference<MethodReference>()?.name == "isAttachedToWindow"
        }
        val skipRender = attachedIndex + 3
        val next = loaderCode[builtIndex + 1]
        check(loaderCode[skipRender].opcode == Opcode.SGET_OBJECT &&
            loaderCode[skipRender + 1].opcode == Opcode.RETURN_OBJECT &&
            next.opcode == Opcode.IGET_OBJECT) { "landing: native loading chip return" }
        val renderGateRegister = (next as TwoRegisterInstruction).registerA
        check(renderGateRegister != 0 && renderGateRegister != payloadRegister &&
            renderGateRegister != next.registerB) {
            "landing: native loading chip guard register"
        }

        val nativeAdapter = classDefByOrNull(slots.adapter)!!
        val nativeBind = nativeAdapter.methods.single { method ->
            method.parameterTypes == listOf(section.type) && method.returnType == "V" &&
                method.implementation?.instructions?.any { it.getReference<FieldReference>() == sectionHeader } == true
        }
        val bindCode = nativeBind.implementation!!.instructions.toList()
        val convertIndex = bindCode.indices.single {
            bindCode[it].opcode == Opcode.INVOKE_INTERFACE &&
                bindCode[it].getReference<MethodReference>()?.parameterTypes == listOf(slots.elements)
        }
        val converter = checkNotNull(bindCode[convertIndex].getReference<MethodReference>())
        val converterField = checkNotNull(bindCode[convertIndex - 1].getReference<FieldReference>())
        val render = checkNotNull(bindCode[convertIndex + 2].getReference<MethodReference>())
        val renderCall = when (bindCode[convertIndex + 2].opcode) {
            Opcode.INVOKE_DIRECT -> {
                check(render.definingClass == slots.adapter && render.parameterTypes.size == 2)
                "const/4 v1, 0x0\ninvoke-direct {p0, v0, v1}, $render"
            }
            Opcode.INVOKE_VIRTUAL -> {
                check(render.parameterTypes == listOf(OBJECT))
                "invoke-virtual {p0, v0}, $render"
            }
            else -> error("landing: native chip render invocation")
        }

        return {
            val host = mutableClassDefBy(abi.host)
            host.interfaces.add(CHIP_HOST)
            host.addDownloadsBridge("rvxLandingHeaderContainer", emptyList(), VIEW_GROUP, 2, """
                iget-object v0, p0, $container
                return-object v0
            """)
            host.addDownloadsBridge("rvxLandingReadHeader", listOf(OBJECT), OBJECT, 4, """
                check-cast p1, ${abi.holderType}
                iget-object v0, p1, $section
                iget-object v0, v0, $sectionHeader
                if-eqz v0, :absent
                iget v1, v0, $headerKind
                const p1, 0x9267492
                if-ne v1, p1, :absent
                iget-object v0, v0, $element
                return-object v0
                :absent
                const/4 v0, 0x0
                return-object v0
            """)
            val serializeElements = serializerOf(slots.elements)
            host.addDownloadsBridge("rvxLandingElementsWire", listOf(OBJECT), "[B", 2, """
                check-cast p1, ${slots.elements}
                invoke-virtual {p1}, $serializeElements
                move-result-object v0
                return-object v0
            """)
            host.addDownloadsBridge("rvxLandingHeaderWire", listOf(OBJECT), "[B", 3, """
                check-cast p1, ${abi.holderType}
                iget-object v0, p1, $section
                iget-object v0, v0, $sectionHeader
                if-eqz v0, :absent
                invoke-virtual {v0}, $serializeHeader
                move-result-object v0
                return-object v0
                :absent
                const/4 v0, 0x0
                return-object v0
            """)
            val loadingCallback = host.findMutableMethodOf(loading)
            loadingCallback.addInstructions(countIndex + 2, """
                invoke-static {v$groupRegister, v$countRegister}, $LANDING->loadingHeaderChildCount(${OBJECT}I)I
                move-result v$countRegister
            """)
            val loadingHook = "$LANDING->loadingHeaderStarted($OBJECT)V"
            loadingCallback.addInstructions(0, "invoke-static/range {p0 .. p0}, $loadingHook")

            val callback = mutableClassDefBy(slots.loader).findMutableMethodOf(loader)
            callback.addInstructionsWithLabels(builtIndex + 1, """
                invoke-static {v0, v$payloadRegister}, $LANDING->loadingHeaderBuilt($OBJECT$OBJECT)Z
                move-result v$renderGateRegister
                if-eqz v$renderGateRegister, :skip_native_header
            """, ExternalLabel("skip_native_header", callback.getInstruction<Instruction>(skipRender)))

            val adapter = mutableClassDefBy(nativeAdapter.type)
            adapter.interfaces.add(CHIP_ADAPTER)
            adapter.addDownloadsBridge("rvxLandingBindHeader", listOf(OBJECT), "V", 4, """
                check-cast p1, ${slots.elements}
                iget-object v0, p0, $converterField
                invoke-interface {v0, p1}, $converter
                move-result-object v0
                $renderCall
                return-void
            """)
            printInfo("LibraryLanding: ${abi.host}->${loading.name} native chip-header retention")
        }
    } catch (ex: Exception) {
        printWarn("LibraryLanding: native chip-row restore unavailable; keeping stock cache misses: ${ex.message}")
        return null
    }
}

private fun BytecodePatchContext.landingMethod(owner: String, name: String, args: List<String>, result: String) =
    classDefByOrNull(owner)!!.methods.single {
        it.name == name && it.parameterTypes.map(CharSequence::toString) == args && it.returnType == result
    }

private fun BytecodePatchContext.landingField(owner: String, name: String, type: String) =
    classDefByOrNull(owner)!!.fields.single { it.name == name && it.type == type }

/** Append through the same adapter operations used for insert actions, preserving all cached rows. */
private fun BytecodePatchContext.insertionOf(
    abi: LandingAbi,
    slots: LandingSlots,
    convert: MethodReference,
): String {
    val legacy = slots.legacy
    if (legacy == null) {
        val insert = landingMethod(abi.adapterBase, slots.insertion, listOf(slots.item, "I"), "V")
        return "invoke-virtual {p0, v0, p1}, $insert"
    }
    val insert = landingMethod(abi.adapterBase, slots.insertion, listOf(OBJECT, legacy.savedChild, "I"), "V")
    return """
        invoke-static {v0}, $convert
        move-result-object v0
        invoke-virtual {v0}, $OPTIONAL->isPresent()Z
        move-result v1
        if-eqz v1, :done
        invoke-virtual {v0}, $OPTIONAL->get()$OBJECT
        move-result-object v0
        const/4 v1, 0x0
        invoke-virtual {p0, v0, v1, p1}, $insert
    """.trimIndent()
}

/** Use stock whole-slot replacement where available; otherwise insert before deleting the original. */
private fun BytecodePatchContext.replacementOf(
    abi: LandingAbi,
    slots: LandingSlots,
    contents: FieldReference,
    convert: MethodReference,
): String {
    val owner = abi.adapterBase
    val legacy = slots.legacy
    if (legacy != null) return legacyReplacementOf(abi, slots, legacy, contents, convert)
    if (slots.removal == null) {
        val replace = landingMethod(owner, slots.replacement, listOf("I", slots.item), "V")
        check(replace.implementation!!.instructions.any {
            it.getReference<FieldReference>()?.toString() == contents.toString()
        }) { "landing: native indexed replacement list" }
        return "invoke-virtual {p0, p1, v0}, $replace"
    }

    val insert = landingMethod(owner, slots.replacement, listOf(slots.item, "I"), "V")
    val remove = landingMethod(owner, slots.removal, listOf("I"), "V")
    check(remove.implementation!!.instructions.any {
        it.getReference<FieldReference>()?.toString() == contents.toString()
    }) { "landing: native indexed removal list" }
    return """
        iget-object v1, p0, $contents
        invoke-interface {v1}, $LIST->size()I
        move-result v2
        invoke-virtual {p0, v0, p1}, $insert
        invoke-interface {v1}, $LIST->size()I
        move-result v3
        add-int/lit8 v2, v2, 0x1
        if-ne v2, v3, :rejected
        add-int/lit8 v2, p1, 0x1
        invoke-virtual {p0, v2}, $remove
    """.trimIndent()
}

private fun BytecodePatchContext.legacyReplacementOf(
    abi: LandingAbi,
    slots: LandingSlots,
    legacy: LegacySlots,
    contents: FieldReference,
    convert: MethodReference,
): String {
    val owner = abi.adapterBase
    val insert = landingMethod(owner, slots.replacement, listOf(OBJECT, legacy.savedChild, "I"), "V")
    val binders = landingField(owner, legacy.bindersField, LIST)
    val composer = landingField(owner, "d", legacy.composer)
    val key = landingMethod(owner, legacy.stableKey, listOf(legacy.binder), STRING)
    val child = landingMethod(legacy.binder, legacy.getChild, emptyList(), legacy.child)
    val childIndex = landingMethod(legacy.composer, "b", listOf(legacy.child), "I")
    val removeChild = landingMethod(legacy.composer, "u", listOf(legacy.child), "V")
    val nativeFields = insert.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
        .map(FieldReference::toString).toSet()
    check(listOf(contents, binders, composer).all { it.toString() in nativeFields }) {
        "landing: native composer/model alignment"
    }
    // Preserve the native renderer maps: only the unkeyed empty slot can take this legacy path.
    return """
        invoke-static {v0}, $convert
        move-result-object v0
        invoke-virtual {v0}, $OPTIONAL->isPresent()Z
        move-result v1
        if-eqz v1, :rejected
        invoke-virtual {v0}, $OPTIONAL->get()$OBJECT
        move-result-object v0
        iget-object v1, p0, $binders
        iget-object v2, p0, $contents
        invoke-interface {v1}, $LIST->size()I
        move-result v3
        invoke-interface {v2}, $LIST->size()I
        move-result v4
        if-ne v3, v4, :rejected
        invoke-interface {v1, p1}, $LIST->get(I)$OBJECT
        move-result-object v4
        check-cast v4, ${legacy.binder}
        invoke-static {v4}, $key
        move-result-object v5
        if-nez v5, :rejected
        invoke-interface {v4}, $child
        move-result-object v5
        iget-object v6, p0, $composer
        invoke-virtual {v6, v5}, $childIndex
        move-result v7
        if-ne v7, p1, :rejected
        const/4 v7, 0x0
        invoke-virtual {p0, v0, v7, p1}, $insert
        invoke-interface {v1}, $LIST->size()I
        move-result v7
        add-int/lit8 v3, v3, 0x1
        if-ne v7, v3, :rejected
        add-int/lit8 v7, p1, 0x1
        invoke-interface {v1, v7}, $LIST->remove(I)$OBJECT
        invoke-interface {v2, v7}, $LIST->remove(I)$OBJECT
        invoke-virtual {v6, v5}, $removeChild
    """.trimIndent()
}

private fun BytecodePatchContext.findLandingAbi(): LandingAbi {
    val match = Fingerprint(
        returnType = "V",
        custom = { method, _ ->
            !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.map(CharSequence::toString) == listOf(LIST, OPTIONAL) &&
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.NEW_INSTANCE &&
                        it.getReference<TypeReference>()?.type?.endsWith(SWIPE_REFRESH) == true
                } == true
        },
    ).matchSingle()
    val host = match.classDef.type
    val binder = match.method
    val code = binder.implementation!!.instructions.toList()

    val tab = tabHolderOf(code)
    val bind = bindCallOf(code, tab, host)
    val bindCall = code[bind] as FiveRegisterInstruction
    val bindRef = bindCall.getReference<MethodReference>()!!
    check(bindCall.opcode == Opcode.INVOKE_VIRTUAL) { "landing: the bind is no virtual call" }

    val pageStart = pageStartOf(host, binder)
    val pageModel = pageStart.parameterTypes[0].toString()
    val browseId = browseIdOf(pageModel)
    return LandingAbi(
        host, binder, bind, bindCall.registerC, bindCall.registerD, tab.type, bindRef.definingClass,
        pageStart, pageModel, browseId.definingClass, browseId.name,
    )
}

/** The holder the tab gives, and the register it is left in. */
private class TabHolder(val type: String, val register: Int, val callIndex: Int)

/**
 * The tab the loop takes: `Iterator.next`, the result cast to the tab type, a call of a method of it that takes
 * nothing and returns the holder, whose result is the next instruction.
 */
private fun tabHolderOf(code: List<Instruction>): TabHolder {
    for (index in 0..code.size - 5) {
        val next = code[index].getReference<MethodReference>()
        val isNext = code[index].opcode == Opcode.INVOKE_INTERFACE && next?.name == "next" &&
            next.definingClass == ITERATOR
        if (!isNext) continue

        val cast = code[index + 2]
        val call = code[index + 3]
        val result = code[index + 4]
        val shaped = code[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT && cast.opcode == Opcode.CHECK_CAST &&
            call.opcode == Opcode.INVOKE_VIRTUAL && result.opcode == Opcode.MOVE_RESULT_OBJECT
        if (!shaped) continue

        val tabType = cast.getReference<TypeReference>()?.type ?: continue
        val getter = call.getReference<MethodReference>() ?: continue
        val holder = getter.returnType
        if (getter.definingClass != tabType || getter.parameterTypes.isNotEmpty() || !holder.startsWith("L") ||
            holder == OPTIONAL
        ) {
            continue
        }
        return TabHolder(holder, (result as OneRegisterInstruction).registerA, index + 4)
    }
    error("landing: no tab holder in the binder")
}

/**
 * The index of the one call that binds the holder to an adapter: a virtual call of a method of another class that
 * takes the holder and returns nothing, with the holder in the register the tab left it in, which nothing writes in
 * between.
 */
private fun bindCallOf(code: List<Instruction>, tab: TabHolder, host: String): Int {
    val binds = code.indices.filter { index ->
        val step = code[index]
        val ref = step.getReference<MethodReference>()
        index > tab.callIndex && step.opcode == Opcode.INVOKE_VIRTUAL && step is FiveRegisterInstruction &&
            step.registerCount == 2 && step.registerD == tab.register && step.registerC != tab.register &&
            ref != null && ref.returnType == "V" && ref.definingClass != host &&
            ref.parameterTypes.map(CharSequence::toString) == listOf(tab.type)
    }
    check(binds.size == 1) { "landing: ${binds.size} calls bind the tab holder" }

    val bind = binds.single()
    val rewritten = (tab.callIndex + 1 until bind).any { code[it].writes(tab.register) }
    check(!rewritten) { "landing: the holder register is written before the bind" }
    return bind
}

private fun Instruction.writes(register: Int): Boolean {
    val target = this as? OneRegisterInstruction ?: return false
    return (opcode.setsRegister() && target.registerA == register) ||
        (opcode.setsWideRegister() && (target.registerA == register || target.registerA + 1 == register))
}

/** The one method of the fragment that calls the binder: it starts a page, and takes the page model. */
private fun BytecodePatchContext.pageStartOf(host: String, binder: Method): Method {
    val callers = classDefByOrNull(host)!!.methods.filter { method ->
        method.implementation?.instructions?.any {
            val ref = it.getReference<MethodReference>()
            ref != null && ref.definingClass == host && ref.name == binder.name &&
                ref.parameterTypes == binder.parameterTypes
        } == true
    }
    check(callers.size == 1) { "landing: ${callers.size} methods call the binder" }

    val method = callers.single()
    val takesPageModel = method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.size == 1 && method.parameterTypes[0].startsWith("L")
    check(takesPageModel) { "landing: ${method.name} is not a page start" }
    return method
}

/**
 * The method of the page model that returns the browse id: it takes nothing, returns a string, and reads the browse
 * endpoint out of the command the page model holds. That read is an extension lookup, which the protobuf runtime
 * does through `checkIsLite` on an extension field (the names of the app's own classes are obfuscated).
 */
private fun BytecodePatchContext.browseIdOf(pageModel: String): Method {
    val found = mutableListOf<Method>()
    var type: String? = pageModel
    while (type != null) {
        val owner = classDefByOrNull(type) ?: break
        owner.methods.filterTo(found) { method ->
            method.returnType == STRING && method.parameterTypes.isEmpty() &&
                !AccessFlags.STATIC.isSet(method.accessFlags) && method.readsBrowseEndpoint()
        }
        type = owner.superclass
    }
    check(found.size == 1) { "landing: ${found.size} browse id methods on $pageModel" }
    return found.single()
}

private fun Method.readsBrowseEndpoint(): Boolean {
    val steps = implementation?.instructions?.toList() ?: return false
    val readsExtension = steps.any { it.opcode == Opcode.SGET_OBJECT } && steps.any {
        it.getReference<MethodReference>()?.name?.endsWith("checkIsLite") == true
    }
    return name != "toString" && readsExtension
}

/** The class that holds a section list: one constructor of the list and one field of it. */
private fun BytecodePatchContext.landingHolderShape(holderType: String): FieldReference {
    val holder = classDefByOrNull(holderType) ?: error("landing: no class $holderType")
    val constructor = holder.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.size == 1 && it.parameterTypes[0].startsWith("L")
    } ?: error("landing: no constructor of a section list on $holderType")
    val sectionList = constructor.parameterTypes[0].toString()

    return holder.fields.singleOrNull { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == sectionList }
        ?: error("landing: no field of the section list on $holderType")
}

/** Whether the class, or one it extends, has the public `isAdded` and `isHidden` of a fragment. */
private fun BytecodePatchContext.declaresFragmentState(host: String): Boolean {
    val found = mutableSetOf<String>()
    var type: String? = host
    while (type != null) {
        val owner = classDefByOrNull(type) ?: break
        owner.methods.filter {
            it.parameterTypes.isEmpty() && it.returnType == "Z" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }.forEach { if (it.name == "isAdded" || it.name == "isHidden") found.add(it.name) }
        type = owner.superclass
    }
    return found.size == 2
}
