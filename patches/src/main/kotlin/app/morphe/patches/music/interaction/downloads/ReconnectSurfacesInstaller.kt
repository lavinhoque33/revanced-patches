/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.util.Utils.printInfo
import app.morphe.util.Utils.printWarn
import app.morphe.util.cloneMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SURFACES = "Lapp/morphe/extension/music/patches/downloads/ReconnectSurfaces;"
private const val PRESENTER = $$"Lapp/morphe/extension/music/patches/downloads/ReconnectSurfaces$Presenter;"
private const val CONTROLLER = $$"Lapp/morphe/extension/music/patches/downloads/ReconnectSurfaces$Controller;"
private const val TITLE = $$"Lapp/morphe/extension/music/patches/downloads/ReconnectSurfaces$Title;"
private const val MESSAGE = $$"Lapp/morphe/extension/music/patches/downloads/ReconnectSurfaces$Message;"
private const val SOURCE = $$"Lapp/morphe/extension/music/patches/downloads/ReconnectSurfaces$Source;"
private const val COMMAND_PARSER = $$"Lapp/morphe/extension/music/patches/downloads/ReconnectSurfaces$CommandParser;"
private const val OPTIONAL = $$"Lj$/util/Optional;"
private const val SURFACES_OBJECT = "Ljava/lang/Object;"
private const val SURFACES_STRING = "Ljava/lang/String;"
private const val SURFACES_REGISTRY = "Lcom/google/protobuf/ExtensionRegistryLite;"
private const val TITLE_TEXT = "Lcom/google/android/libraries/youtube/common/ui/YouTubeTextView;"

/**
 * The surfaces of the redesigned player that a song played from its local copy lacks after the connection returns:
 * the Lyrics pill and the engagement panel it opens, and the command behind the title (the Related page). The metadata
 * read of [installWatchMetadata] already holds the song's own served response; this gives the player's own code that
 * response, so nothing is drawn or opened by the fork.
 *
 * The redesigned player (9.40.51 and 9.45.51, which share one shape) keeps the actions beside the title in a presenter
 * whose direct binder `f(videoId, response)` binds the pill. The engagement panels of a response are registered by a
 * controller from the same response; its single-panel method is filtered while a response is replayed, so that only
 * the panel the pill refers to is registered. The title answers with an optional command, which is empty for a local
 * song; its getter is wrapped to answer the command of the response while that response's song is the one playing.
 *
 * Older versions keep the legacy Lyrics and Related tabs of [installMetadataTabs], which serve them there.
 */
internal fun BytecodePatchContext.installReconnectSurfaces(
    version: String, host: String, reply: String, proto: String, titleType: String,
) {
    val abi = surfacesAbi(version)
    if (abi == null) {
        printInfo("ReconnectSurfaces: skipped on $version, the legacy Lyrics and Related tabs serve it")
        return
    }

    try {
        installSurfaces(abi, host, reply, proto, titleType)
    } catch (failure: Exception) {
        printWarn("ReconnectSurfaces: not installed on $version, ${failure.message}")
    }
}

private fun BytecodePatchContext.installSurfaces(
    abi: SurfacesAbi, host: String, reply: String, proto: String, titleType: String,
) {
    val presenter = mutableClassDefBy(abi.presenter)
    val bind = presenter.methods.single { it.name == "f" && it.parameterTypes == listOf(SURFACES_STRING, reply) }
    val pill = presenter.methods.single { it.name == "d" && it.parameterTypes == listOf(abi.pillView, abi.action) }

    val controller = mutableClassDefBy(abi.controller)
    val register = controller.methods.single { it.name == "q" && it.parameterTypes == listOf(reply) }
    val panel = controller.methods.single { it.name == abi.panelName && it.parameterTypes == abi.panelParameters }
    val panelFirst = panel.implementation!!.instructions.first()
    check(panelFirst.opcode == Opcode.INVOKE_STATIC &&
            panelFirst.getReference<MethodReference>()?.toString() == abi.panelId) {
        "the panel method does not start with its panel id"
    }

    val title = mutableClassDefBy(titleType)
    val getter = title.methods.single { it.name == "e" && it.parameterTypes.isEmpty() && it.returnType == OPTIONAL }
    check(getter.implementation!!.instructions.any { it.getReference<MethodReference>()?.name == "findFirst" }) {
        "the title getter does not read its command from a stream"
    }
    check(title.methods.any { it.name == abi.titleRefresh && it.parameterTypes.isEmpty() && it.returnType == "V" }) {
        "the title has no refresh method"
    }

    val serializeAction = serializerOf(abi.action)
    val serializeProto = serializerOf(proto)
    val parserOwner = parseByteArrayMethodRef.get()!!.definingClass
    val parser = mutableClassDefBy(parserOwner).methods.single {
        it.name == "parseFrom" && it.parameterTypes == listOf(parserOwner, "[B", SURFACES_REGISTRY)
    }
    val commandType = abi.command
    val commandDefault = mutableClassDefBy(commandType).fields.single {
        it.type == commandType && AccessFlags.STATIC.isSet(it.accessFlags)
    }

    presenter.interfaces.add(PRESENTER)
    presenter.addDownloadsBridge("rvxBindLyrics", listOf(SURFACES_STRING, SURFACES_OBJECT), "V", 4, """
        check-cast p2, $reply
        invoke-virtual {p0, p1, p2}, ${presenter.type}->f($SURFACES_STRING$reply)V
        return-void
    """)
    // The stock visibility observer calls f again with the actual Watch reply when the player opens.
    bind.addInstructions(0, """
        invoke-static/range {p0 .. p2}, $SURFACES->presenter($SURFACES_OBJECT$SURFACES_STRING$SURFACES_OBJECT)V
    """)
    pill.addInstructions(0, "invoke-static {p0, p2}, $SURFACES->pillAction($SURFACES_OBJECT$SURFACES_OBJECT)V")

    val action = mutableClassDefBy(abi.action)
    action.interfaces.add(MESSAGE)
    action.addDownloadsBridge("rvxMessageBytes", emptyList(), "[B", 2, """
        invoke-virtual {p0}, $serializeAction
        move-result-object v0
        return-object v0
    """)

    controller.interfaces.add(CONTROLLER)
    controller.addDownloadsBridge("rvxRegisterPanels", listOf(SURFACES_OBJECT), "V", 3, """
        check-cast p1, $reply
        invoke-virtual {p0, p1}, ${controller.type}->q($reply)V
        return-void
    """)
    register.addInstructions(0, "invoke-static/range {p0 .. p0}, $SURFACES->controller($SURFACES_OBJECT)V")
    panel.addInstructionsWithLabels(0, """
        invoke-static {p1}, ${abi.panelId}
        move-result-object v0
        invoke-static {v0}, $SURFACES->allowsPanel($SURFACES_STRING)Z
        move-result v1
        if-nez v1, :rvx_surfaces_panel
        return-void
        :rvx_surfaces_panel
        nop
    """)

    val replyClass = mutableClassDefBy(reply)
    replyClass.interfaces.add(SOURCE)
    replyClass.addDownloadsBridge("rvxMetadataBytes", emptyList(), "[B", 2, """
        iget-object v0, p0, $reply->a:$proto
        invoke-virtual {v0}, $serializeProto
        move-result-object v0
        return-object v0
    """)

    val hostClass = mutableClassDefBy(host)
    hostClass.interfaces.add(COMMAND_PARSER)
    hostClass.addDownloadsBridge("rvxParseCommand", listOf("[B"), SURFACES_OBJECT, 4, """
        sget-object v0, $commandDefault
        invoke-static {}, $SURFACES_REGISTRY->getGeneratedRegistry()$SURFACES_REGISTRY
        move-result-object v1
        invoke-static {v0, p1, v1}, $parser
        move-result-object v0
        check-cast v0, $commandType
        return-object v0
    """)

    installRelatedGetter(title, getter, abi, titleType)
    installPanelLookupTrace(abi, controller)

    printInfo("ReconnectSurfaces: lyrics presenter ${abi.presenter}")
    printInfo("ReconnectSurfaces: panel controller ${abi.controller}")
    printInfo("ReconnectSurfaces: related $titleType.e()")
}

/** Observe the actual direct and nested key lookups in the 9.45 controller, without changing stock dispatch. */
private fun BytecodePatchContext.installPanelLookupTrace(abi: SurfacesAbi, controller: MutableClass) {
    val lookupType = abi.lookupType ?: return
    val resolver = controller.methods.single {
        it.name == "f" && it.parameterTypes == listOf(abi.command, "Lvof;", "Z", "Z") && it.returnType == "Lvoh;"
    }
    val registry = mutableClassDefBy(lookupType)
    val watchController = registry.fields.single { it.name == "d" && it.type == controller.type }
    val lookup = registry.methods.single {
        it.name == "a" && it.parameterTypes == listOf(SURFACES_STRING) && it.returnType == "Lvpa;"
    }
    val positions = resolver.implementation!!.instructions.withIndex().filter {
        it.value.opcode == Opcode.INVOKE_VIRTUAL &&
            it.value.getReference<MethodReference>()?.toString() == lookup.toString()
    }
    check(positions.size == 3) { "unexpected native panel resolver" }
    val trace = "$SURFACES->panelLookup($SURFACES_OBJECT$SURFACES_STRING${SURFACES_OBJECT}Z)V"

    // The third lookup is a different endpoint family, not either show-panel path.
    for ((branch, position) in positions.take(2).withIndex()) {
        val name = if (branch == 0) "rvxFindDirectPanel" else "rvxFindNestedPanel"
        registry.addDownloadsBridge(name, listOf(SURFACES_STRING), lookup.returnType, 4, """
            invoke-virtual {p0, p1}, $lookup
            move-result-object v0
            const/4 v1, $branch
            iget-object p0, p0, $watchController
            invoke-static {p0, p1, v0, v1}, $trace
            return-object v0
        """)
        val call = position.value as FiveRegisterInstruction
        resolver.replaceInstruction(position.index,
            "invoke-virtual {v${call.registerC}, v${call.registerD}}, " +
                "$lookupType->$name($SURFACES_STRING)${lookup.returnType}")
    }
}

/** The title keeps the stock getter under another name and answers with the song's command when stock has none. */
private fun installRelatedGetter(
    title: app.morphe.patcher.util.proxy.mutableTypes.MutableClass,
    getter: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod,
    abi: SurfacesAbi,
    titleType: String,
) {
    val stock = getter.cloneMutable(name = "rvxStockRelated")
    val wrapper = getter.cloneMutable(clearImplementation = true, registerCount = 3)
    wrapper.addInstructionsWithLabels(0, """
        invoke-virtual {p0}, $titleType->rvxStockRelated()$OPTIONAL
        move-result-object v0
        invoke-virtual {v0}, $OPTIONAL->isPresent()Z
        move-result v1
        if-nez v1, :rvx_related_stock
        invoke-static {p0}, $SURFACES->related($SURFACES_OBJECT)$SURFACES_OBJECT
        move-result-object v1
        if-eqz v1, :rvx_related_stock
        invoke-static {v1}, $OPTIONAL->of($SURFACES_OBJECT)$OPTIONAL
        move-result-object v0
        :rvx_related_stock
        return-object v0
    """)
    title.methods.remove(getter)
    title.methods.add(stock)
    title.methods.add(wrapper)

    title.interfaces.add(TITLE)
    title.addDownloadsBridge("rvxRefreshRelated", emptyList(), "V", 3, """
        invoke-virtual {p0}, $titleType->${abi.titleRefresh}()V
        iget-object v0, p0, $titleType->r:$TITLE_TEXT
        const/4 v1, 0x1
        invoke-virtual {v0, v1}, Landroid/view/View;->setClickable(Z)V
        return-void
    """)
}

private class SurfacesAbi(
    val presenter: String,
    val pillView: String,
    val action: String,
    val controller: String,
    val panelName: String,
    val panelParameters: List<String>,
    val panelId: String,
    val titleRefresh: String,
    val command: String,
    val lookupType: String? = null,
)

/** The redesigned player's presenter, panel controller and title, by version. Other versions are served by tabs. */
private fun surfacesAbi(version: String): SurfacesAbi? = when (version) {
    "9.45.51" -> SurfacesAbi(
        "Ljls;", "Ljlw;", "Lakya;", "Lvoy;", "y",
        listOf("Lalax;", "Lamjv;", "Z", "Lakda;", "Z", "Laedt;"),
        "Lsza;->bx(Lalax;)Ljava/lang/String;", "y", "Lakda;", "Lvpc;",
    )
    "9.40.51" -> SurfacesAbi(
        "Ljlw;", "Ljma;", "Lakmy;", "Lvki;", "x",
        listOf("Lakpu;", "Lalyo;", "Z", "Lajsj;", "Z", "Ladsk;"),
        "Ltwf;->R(Lakpu;)Ljava/lang/String;", "x", "Lajsj;",
    )
    else -> null
}
