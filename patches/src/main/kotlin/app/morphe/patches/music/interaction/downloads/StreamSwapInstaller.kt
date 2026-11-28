/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-14.
 * Modified by lavinhoque33, 2026-11-22.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.spoof.SPOOF_STREAM_OVERRIDE_METHOD
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.cloneMutable
import app.morphe.util.fingerprint.matchSingle
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val SWAP = "Lapp/morphe/extension/music/patches/downloads/StreamSwap;"
private const val RESPONSE = $$"Lapp/morphe/extension/music/patches/downloads/StreamSwap$Response;"
private const val REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"
private const val REQUEST = "$SWAP->swap(Ljava/lang/Object;)Ljava/lang/Object;"
private const val HYBRID = "Lapp/morphe/extension/music/patches/downloads/OnlineVideoHybrid;"
private const val HYBRID_STREAMS = $$"Lapp/morphe/extension/music/patches/downloads/OnlineVideoHybrid$Streams;"
private const val OBJECT = "Ljava/lang/Object;"
internal const val SAVED_PLAYER_FACTORY = "rvxSavedPlayerResponse"

/**
 * Structurally resolved ABI, in 9.45.51 / 9.40.51 / 9.15.51 / 8.30.54 order:
 * request Labrf.d / Lablf.d / Lazpg.d / Lawjm.c; wrapper Lwjw / Lwem / Lapde / Lamwf;
 * streaming Lwjo / Lwed / Lapck / Lamvm; builders Lwjm / Lweb / Lapci / Lamvk.
 * Producer transform coverage is 4 / 4 / 4 / 5 sites, including legacy Labri.f on 9.45.
 * Resolve every dependency before mutation; an unfamiliar ABI only warns and leaves playback online.
 */
internal fun BytecodePatchContext.installStreamSwap(): (() -> Unit)? {
    if (packageMetadata.versionName !in setOf("9.45.51", "9.40.51", "9.15.51", "8.30.54", "8.28.54", "8.12.54")) {
        printWarn("Play downloads locally is unavailable on this Music version; online playback unchanged.")
        printWarn("Playback source inspector unavailable: no verified Music playback-source hooks for this version.")
        return null
    }
    return try {
        val installGuard = prepareStreamSwapGuard()
        val installLocalSource = prepareStreamSwapLocalSource()
        val supported = mutableClassDefBy(SWAP).methods.single { it.name == "supported" }
        val caller = streamSwapCallerFingerprint.matchSingle().method
        val instructions = caller.implementation!!.instructions.toList()
        val transformIndex = instructions.indices.single { instructions[it].isStreamTransform(caller) }
        val videoId = instructions[transformIndex - 2].getReference<MethodReference>()!!
        check(instructions[transformIndex - 2].opcode == Opcode.INVOKE_INTERFACE &&
            videoId.definingClass == caller.returnType && videoId.parameterTypes.isEmpty() &&
            videoId.returnType == "Ljava/lang/String;")
        val parameterTypes = caller.parameterTypes.map(CharSequence::toString)
        check(parameterTypes.all { it.startsWith("L") } &&
            caller.implementation!!.registerCount in parameterTypes.size + 2..16)
        val transform = instructions[transformIndex].getReference<MethodReference>()!!
        val producerSites = streamSwapProducerSites(transform)
        check(producerSites.any { it.method == caller }) { "Missing request-base transform site" }
        val consumer = streamSwapConsumerFingerprint(caller.returnType).matchSingle().method
        check(consumer.implementation!!.registerCount <= 256) { "Playback response register exceeds 8 bits" }

        val wrapperMatch = streamSwapWrapperFingerprint(caller.returnType).matchSingle()
        val wrapper = wrapperMatch.classDef
        val constructor = wrapperMatch.method
        val types = constructor.parameterTypes.map(CharSequence::toString)
        val proto = types[0]
        val streamIndex = if (types.size == 6) 3 else 2
        val streaming = mutableClassDefBy(types[streamIndex])
        // Follow constructor assignments on both the five- and six-parameter wrapper ABIs.
        val responseProto = constructor.streamSwapParameterField(0)
        val instant = if (types.size == 6) constructor.streamSwapParameterField(1) else null
        val timestamp = constructor.streamSwapParameterField(streamIndex - 1)
        val streams = constructor.streamSwapParameterField(streamIndex)
        val metadata = constructor.streamSwapParameterField(streamIndex + 1)
        val flag = constructor.streamSwapParameterField(streamIndex + 2)
        val serialize = wrapper.methods.single { method ->
            method.parameterTypes.isEmpty() && method.returnType == "[B" &&
                method.implementation?.instructions?.any {
                    it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>() == responseProto
                } == true &&
                method.implementation?.instructions?.any {
                    it.getReference<MethodReference>()?.name == "toByteArray"
                } == true
        }
        val protoClass = mutableClassDefBy(proto)
        val defaultProto = protoClass.fields.single { it.type == proto && AccessFlags.STATIC.isSet(it.accessFlags) }
        val streamingProto = protoClass.fields.single { it.type == STREAMING_PROTO }
        val stockSavedFactory = wrapper.methods.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType.startsWith("L") &&
                it.parameterTypes == listOf("[B", "J")
        }.minBy { it.implementation?.instructions?.count() ?: Int.MAX_VALUE }
        val (savedFactory, markSavedFactory) = stockSavedFactory.prepareSavedPlayerFactory(proto)
        val parserOwner = parseByteArrayMethodRef.get()!!.definingClass
        val parser = mutableClassDefBy(parserOwner).methods.single {
            it.name == "parseFrom" && it.parameterTypes == listOf(parserOwner, "[B", REGISTRY)
        }
        // Follow the wrapper's stock streaming factory to its online builder.
        // Reusing the streaming clone drops the playability-derived format list; a plain proto ctor drops O.
        val factory = wrapper.methods.single {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == streaming.type &&
                it.parameterTypes.size == 3 && it.parameterTypes[1] == proto && it.parameterTypes[2] == "J"
        }
        val factoryInstructions = factory.implementation!!.instructions.toList()
        val builderConstructor = factoryInstructions.mapNotNull {
            it.getReference<MethodReference>()
        }.single { it.name == "<init>" && it.parameterTypes == listOf(proto) }
        val builder = mutableClassDefBy(builderConstructor.definingClass)
        val nativeBuilderConstructor = builder.methods.single {
            it.name == "<init>" && it.parameterTypes == listOf(proto)
        }
        val offlineWrite = factoryInstructions.indices.single { index ->
            index > 0 && factoryInstructions[index].opcode == Opcode.IPUT_BOOLEAN &&
                factoryInstructions[index - 1].opcode == Opcode.IGET_BOOLEAN &&
                factoryInstructions[index].getReference<FieldReference>()?.definingClass == builder.type &&
                factoryInstructions[index - 1].getReference<FieldReference>()?.definingClass ==
                    factory.parameterTypes[0] &&
                (factoryInstructions[index] as TwoRegisterInstruction).registerA ==
                    (factoryInstructions[index - 1] as TwoRegisterInstruction).registerA
        }
        val offlineFlag = factoryInstructions[offlineWrite].getReference<FieldReference>()!!
        val setTimestamp = builder.methods.single { method ->
            method.parameterTypes == listOf("J") && method.returnType == "V" &&
                method.implementation?.instructions?.any {
                    it.getReference<MethodReference>()?.let { call ->
                        call.definingClass == "Ljava/lang/Long;" && call.name == "valueOf"
                    } == true
                } == true
        }
        val build = builder.methods.single { it.parameterTypes.isEmpty() && it.returnType == streaming.type }
        val (setNetwork, networkHolder) = builder.methods.mapNotNull { method ->
            if (AccessFlags.CONSTRUCTOR.isSet(method.accessFlags) || AccessFlags.STATIC.isSet(method.accessFlags) ||
                method.returnType != "V" || method.parameterTypes.size != 1 ||
                !method.parameterTypes[0].startsWith("L")) return@mapNotNull null
            val holders = method.implementation?.instructions?.mapNotNull {
                it.getReference<MethodReference>()
            }?.filter { call ->
                call.name == "<init>" && call.parameterTypes.size == 1 &&
                    builder.fields.any { it.type == call.definingClass }
            }?.map { mutableClassDefBy(it.definingClass) }?.filter { holder ->
                holder.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) } == 1 &&
                    streaming.fields.any { it.type == holder.type }
            } ?: emptyList()
            holders.singleOrNull()?.let { method to it }
        }.single()
        val network = streaming.fields.single { it.type == networkHolder.type }
        val networkValue = networkHolder.fields.single { !AccessFlags.STATIC.isSet(it.accessFlags) }
        val networkType = setNetwork.parameterTypes.single()
        // The streaming data the app reads from the response is the one an accepted hybrid is recorded on.
        val streamsReadByApp = streams.type == streaming.type && wrapper.methods.any { method ->
            method.parameterTypes.isEmpty() && method.returnType == streaming.type &&
                method.implementation?.instructions?.map { it.opcode } ==
                listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT) &&
                method.implementation!!.instructions.first().getReference<FieldReference>() == streams
        }
        val allocation = if (instant != null) """
            new-instance v2, ${wrapper.type}
            iget-object v4, p0, $instant
            iget-wide v5, p0, $timestamp
            iget-object v8, p0, $metadata
            iget-boolean v9, p0, $flag
            invoke-direct/range {v2 .. v9}, $constructor
            return-object v2
        """ else """
            move-object v6, v7
            new-instance v2, ${wrapper.type}
            iget-wide v4, p0, $timestamp
            iget-object v7, p0, $metadata
            iget-boolean v8, p0, $flag
            invoke-direct/range {v2 .. v8}, $constructor
            return-object v2
        """

        wrapper.addDownloadsBridge("rvxVideoId", emptyList(), "Ljava/lang/String;", 2, """
            invoke-interface {p0}, $videoId
            move-result-object v0
            return-object v0
        """)
        wrapper.addDownloadsBridge("rvxBytes", emptyList(), "[B", 2, """
            invoke-virtual {p0}, $serialize
            move-result-object v0
            return-object v0
        """)
        wrapper.addDownloadsBridge("rvxWithBytes", listOf("[B", "Z"), "Ljava/lang/Object;", 13, """
            sget-object v0, $defaultProto
            invoke-static {}, $REGISTRY->getGeneratedRegistry()$REGISTRY
            move-result-object v1
            invoke-static {v0, p1, v1}, $parser
            move-result-object v3
            check-cast v3, $proto
            iget-object v4, v3, $streamingProto
            if-eqz v4, :unchanged
            new-instance v2, ${builder.type}
            invoke-direct {v2, v3}, $builderConstructor
            iput-boolean p2, v2, $offlineFlag
            iget-wide v0, p0, $timestamp
            invoke-virtual {v2, v0, v1}, $setTimestamp
            iget-object v6, p0, $streams
            if-eqz v6, :no_network_metadata
            iget-object v6, v6, $network
            if-eqz v6, :no_network_metadata
            iget-object v6, v6, $networkValue
            check-cast v6, $networkType
            invoke-virtual {v2, v6}, $setNetwork
            :no_network_metadata
            invoke-virtual {v2}, $build
            move-result-object v7
            $allocation
            :unchanged
            return-object p0
        """)
        wrapper.addDownloadsBridge("rvxStreams", emptyList(), OBJECT, 2, """
            iget-object v0, p0, $streams
            return-object v0
        """)
        val rebuild = wrapper.methods.single {
            it.name == "rvxWithBytes" && it.parameterTypes == listOf("[B", "Z")
        }
        wrapper.methods.add(savedFactory)
        wrapper.interfaces.add(RESPONSE)
        producerSites.asReversed().forEach { site ->
            site.method.addInstructions(site.index, """
                invoke-static {v${site.result}}, $REQUEST
                move-result-object v${site.result}
                check-cast v${site.result}, ${caller.returnType}
            """)
        }
        consumer.addInstructions(0, """
            invoke-static/range {p1 .. p1}, $REQUEST
            move-result-object p1
            check-cast p1, ${caller.returnType}
        """)
        installGuard()
        val inspectorSupported = installLocalSource()
        printInfo("StreamSwap: response adapter installed ${wrapper.type}; producers=${producerSites.size}")
        if (inspectorSupported) registerPlaybackSourceInspector()
        if (inspectorSupported && streamsReadByApp) installOnlineVideoHybrid(streaming)
        else printWarn("Online video for saved audio unavailable: the clip's streaming data cannot be matched.")

        // Spoof streams is optional and can execute before or after Downloads.
        val finishSwap = {
            try {
                guardLocalStreamFromSpoof(protoClass, builder, nativeBuilderConstructor, rebuild, markSavedFactory)
                supported.returnEarly(true)
            } catch (ex: Exception) {
                printWarn("StreamSwap: client spoof handoff unavailable; online playback unchanged: ${ex.message}")
            }
        }
        finishSwap
    } catch (ex: Exception) {
        printWarn("StreamSwap: hook unavailable; online playback unchanged: ${ex.message}")
        null
    }
}

/**
 * The small stock ([B, long) factory parses with the generated registry and creates the seed response.
 * Clone it so only a saved replay can mark that freshly parsed proto, before the stock constructor
 * reaches the streaming-data builder. Keep the original parser, null paths and response metadata.
 */
internal fun Method.prepareSavedPlayerFactory(proto: String): Pair<MutableMethod, (FieldReference) -> Unit> {
    val code = implementation!!.instructions.toList()
    val construct = code.indices.single { index ->
        val instruction = code[index]
        instruction.opcode == Opcode.INVOKE_DIRECT &&
            instruction.getReference<MethodReference>()?.let { call ->
                call.definingClass == definingClass && call.name == "<init>" &&
                    call.parameterTypes.size == 3 && call.parameterTypes[0] == proto &&
                    call.parameterTypes[1] == "J"
            } == true
    }
    val invoke = code[construct] as FiveRegisterInstruction
    val create = construct - 2
    val allocation = code[create]
    val metadata = code[construct - 1]
    val nonnull = code[create - 1]
    val cast = code[create - 2]
    val parsed = code[create - 3]
    val parse = code[create - 4]
    val following = code[construct + 1]
    val returned = if (following.opcode == Opcode.GOTO) {
        code[(following as BuilderOffsetInstruction).target.location.index]
    } else {
        following
    }
    check(invoke.registerCount == 5 && invoke.registerC != invoke.registerD && invoke.registerG != invoke.registerD &&
        allocation.opcode == Opcode.NEW_INSTANCE &&
        (allocation as OneRegisterInstruction).registerA == invoke.registerC &&
        metadata.opcode == Opcode.SGET_OBJECT && (metadata as OneRegisterInstruction).registerA == invoke.registerG &&
        nonnull.opcode == Opcode.IF_EQZ && (nonnull as OneRegisterInstruction).registerA == invoke.registerD &&
        (nonnull as BuilderOffsetInstruction).target.location.index > construct &&
        cast.opcode == Opcode.CHECK_CAST && cast.getReference<TypeReference>()?.type == proto &&
        (cast as OneRegisterInstruction).registerA == invoke.registerD &&
        parsed.opcode == Opcode.MOVE_RESULT_OBJECT &&
        (parsed as OneRegisterInstruction).registerA == invoke.registerD &&
        parse.opcode == Opcode.INVOKE_STATIC &&
        parse.getReference<MethodReference>()?.parameterTypes?.firstOrNull() == "[B" &&
        returned.opcode == Opcode.RETURN_OBJECT && (returned as OneRegisterInstruction).registerA == invoke.registerC
    ) { "Saved player response factory changed: $this" }

    val saved = cloneMutable(name = SAVED_PLAYER_FACTORY)
    return saved to { marker: FieldReference ->
        saved.addInstructions(construct + 1, """
            const/4 v${invoke.registerG}, 0x0
            iput-boolean v${invoke.registerG}, v${invoke.registerD}, $marker
        """)
        saved.addInstructions(create, """
            const/4 v${invoke.registerC}, 0x1
            iput-boolean v${invoke.registerC}, v${invoke.registerD}, $marker
        """)
    }
}

/**
 * A hybrid lives on the native streaming data of the one response the adapter accepted, so prepared responses of
 * the same track never share it; the active clip reads the streaming data it was built from.
 */
private fun BytecodePatchContext.installOnlineVideoHybrid(streaming: MutableClass) {
    try {
        bindPlaybackStreams(streaming.type)
    } catch (ex: Exception) {
        printWarn("Online video for saved audio unavailable: ${ex.message}")
        return
    }

    streaming.instanceFields.add(ImmutableField(streaming.type, "rvxHybrid", OBJECT, AccessFlags.PUBLIC.value,
        null, null, null).toMutable())
    streaming.interfaces.add(HYBRID_STREAMS)
    streaming.addDownloadsBridge("rvxHybrid", emptyList(), OBJECT, 2, """
        iget-object v0, p0, ${streaming.type}->rvxHybrid:$OBJECT
        return-object v0
    """)
    streaming.addDownloadsBridge("rvxSetHybrid", listOf(OBJECT), "V", 2, """
        iput-object p1, p0, ${streaming.type}->rvxHybrid:$OBJECT
        return-void
    """)
    mutableClassDefBy(HYBRID).methods.single { it.name == "supported" }.returnEarly(true)
    printInfo("StreamSwap: online video for saved audio bound to ${streaming.type}")
}

/**
 * Spoof streams hooks the same constructor used to build a local response. Its streaming data override would
 * replace the RVX token before the player sees it. Keep the stock constructor, but skip that override only
 * during construction from the freshly parsed local response. No other response, including the original
 * online fallback for the same song, can carry this marker.
 */
private fun guardLocalStreamFromSpoof(
    proto: MutableClass,
    builder: MutableClass,
    constructor: MutableMethod,
    rebuild: MutableMethod,
    markSavedFactory: (FieldReference) -> Unit,
) {
    val helperCall = constructor.implementation!!.instructions.firstNotNullOfOrNull {
        it.getReference<MethodReference>()?.takeIf { call ->
            call.definingClass == builder.type && call.name == SPOOF_STREAM_OVERRIDE_METHOD
        }
    } ?: return
    val helper = builder.methods.single {
        it.name == helperCall.name && it.parameterTypes == helperCall.parameterTypes && it.returnType == "V"
    }
    val code = rebuild.implementation!!.instructions
    val create = code.indices.single { code[it].getReference<MethodReference>() == constructor }
    val call = code[create] as FiveRegisterInstruction
    val localFlag = rebuild.implementation!!.registerCount - 1
    check(call.opcode == Opcode.INVOKE_DIRECT && call.registerCount == 2 &&
        call.registerD in 0..15 && call.registerD != 4 && localFlag in 0..15 &&
        helper.parameterTypes.size == 2 && helper.parameterTypes.last() == proto.type &&
        helper.implementation!!.registerCount in 4..16) { "Local stream spoof handoff changed" }

    val marker = ImmutableField(proto.type, "rvxLocalStreamBuild", "Z",
        AccessFlags.PUBLIC.value or AccessFlags.TRANSIENT.value, null, null, null)
    proto.instanceFields.add(marker.toMutable())
    helper.addInstructionsWithLabels(0, """
        iget-boolean v0, p2, $marker
        if-eqz v0, :allow_stream_spoof
        return-void
        :allow_stream_spoof
        nop
    """)
    rebuild.addInstructions(create + 1, """
        const/4 v4, 0x0
        iput-boolean v4, v${call.registerD}, $marker
    """)
    rebuild.addInstructions(create, "iput-boolean v$localFlag, v${call.registerD}, $marker")
    markSavedFactory(marker)
    printInfo("StreamSwap: preserved locally issued streaming data through the client spoof constructor.")
}

/** Bind each retained value to the field that actually receives that constructor argument. */
private fun Method.streamSwapParameterField(parameterIndex: Int): FieldReference {
    val parameters = parameterTypes.map(CharSequence::toString)
    val widths = parameters.map { if (it == "J" || it == "D") 2 else 1 }
    val thisRegister = implementation!!.registerCount - widths.sum() - 1
    val parameterRegister = thisRegister + 1 + widths.take(parameterIndex).sum()
    val expectedOpcode = when (parameters[parameterIndex]) {
        "J", "D" -> Opcode.IPUT_WIDE
        "Z" -> Opcode.IPUT_BOOLEAN
        else -> Opcode.IPUT_OBJECT
    }

    return implementation!!.instructions.mapNotNull { instruction ->
        if (instruction.opcode != expectedOpcode) return@mapNotNull null
        val assignment = instruction as TwoRegisterInstruction
        val field = instruction.getReference<FieldReference>() ?: return@mapNotNull null

        field.takeIf {
            assignment.registerA == parameterRegister && assignment.registerB == thisRegister &&
                it.definingClass == definingClass && it.type == parameters[parameterIndex]
        }
    }.distinct().single()
}

private data class StreamSwapProducerSite(
    val method: MutableMethod,
    val index: Int,
    val result: Int,
)

/** Include legacy callbacks/retries as well as the feature-flagged unified request base. */
private fun BytecodePatchContext.streamSwapProducerSites(transform: MethodReference): List<StreamSwapProducerSite> {
    val matches = mutableListOf<Triple<ClassDef, Method, Int>>()
    classDefForEach { owner ->
        owner.methods.forEach { method ->
            method.implementation?.instructions?.forEachIndexed { index, instruction ->
                val call = instruction.getReference<MethodReference>()
                if (instruction.opcode == Opcode.INVOKE_VIRTUAL && call != null &&
                    call.definingClass == transform.definingClass &&
                    call.name == transform.name && call.returnType == transform.returnType &&
                    call.parameterTypes == transform.parameterTypes) {
                    matches.add(Triple(owner, method, index))
                }
            }
        }
    }
    return matches.map { (owner, method, index) ->
        val mutable = mutableClassDefBy(owner).findMutableMethodOf(method)
        val instructions = mutable.implementation!!.instructions.toList()
        check(instructions[index + 1].opcode == Opcode.MOVE_RESULT_OBJECT)
        val result = (instructions[index + 1] as OneRegisterInstruction).registerA
        check(result < 16) { "Producer response register exceeds invoke range" }
        StreamSwapProducerSite(mutable, index + 2, result)
    }
}
