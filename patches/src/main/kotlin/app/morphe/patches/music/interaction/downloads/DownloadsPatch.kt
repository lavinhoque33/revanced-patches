/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.music.actionbar.components.actionBarComponentsPatch
import app.morphe.patches.music.flyoutmenu.components.flyoutMenuComponentsPatch
import app.morphe.patches.music.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.utils.extension.sharedExtensionPatch
import app.morphe.patches.music.utils.patch.PatchList.DOWNLOADS
import app.morphe.patches.music.utils.settings.CategoryType
import app.morphe.patches.music.utils.settings.ResourceUtils.addPreferenceCategory
import app.morphe.patches.music.utils.settings.ResourceUtils.updatePatchStatus
import app.morphe.patches.music.utils.settings.addSwitchPreference
import app.morphe.patches.music.utils.settings.settingsPatch
import app.morphe.patches.shared.misc.media.MediaSessionSetMetadataFingerprint
import app.morphe.patches.shared.misc.media.hookMediaSessionArgument
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/downloads/LocalDownloadManager;"

// modified by lavinhoque33, 2026-10-14
private const val EXTENSION_OFFLINE_SERVICE_HOOKS =
    "Lapp/morphe/extension/music/patches/downloads/OfflineServiceHooks;"

private val downloadsResourcePatch = resourcePatch {
    dependsOn(settingsPatch, actionBarComponentsPatch)

    execute {
        addPreferenceCategory(CategoryType.MISC.value)
        addSwitchPreference(
            CategoryType.MISC,
            "morphe_music_in_app_downloads",
            "false",
            "revanced_external_downloader_action"
        )

        // modified by lavinhoque33, 2026-10-14: the manifest is no longer edited. Offline playback
        // used to be a service that this patch declared, plus permissions. A root install mounts
        // only base.apk, so the package manager keeps the stock manifest and could never start
        // that service. Playback now borrows the stock BackgroundPlayerService (mediaPlayback),
        // which already comes with WAKE_LOCK, FOREGROUND_SERVICE,
        // FOREGROUND_SERVICE_MEDIA_PLAYBACK and POST_NOTIFICATIONS in the stock manifest.
    }
}

// Returns early with the instruction at [index] as the label to Music's code.
// A null scratch register means the first local register.
private fun MutableMethod.skipIfTrue(index: Int, call: String, scratch: String?, skip: String) {
    val register = scratch ?: run {
        if (implementation!!.registerCount - parameters.size - 1 < 1) {
            throw PatchException("$name has no local register")
        }
        "v0"
    }
    addInstructionsWithLabels(
        index,
        """
            $call
            move-result $register
            if-eqz $register, :music_code
            $skip
        """,
        ExternalLabel("music_code", getInstruction(index)),
    )
}

@Suppress("unused")
val downloadsPatch = bytecodePatch(
    DOWNLOADS.title,
    DOWNLOADS.summary,
) {
    dependsOn(
        downloadsResourcePatch,
        sharedExtensionPatch,
        settingsPatch,
        actionBarComponentsPatch,
        flyoutMenuComponentsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        MediaSessionSetMetadataFingerprint.hookMediaSessionArgument(
            "$EXTENSION_CLASS->onSetMetadata(Landroid/media/MediaMetadata;)V"
        )

        // modified by lavinhoque33, 2026-10-14: BackgroundPlayerService is a mediaPlayback
        // foreground service in the stock manifest and is not the media browser (that is
        // MusicBrowserService, which Android Auto binds), so offline playback borrows it with its
        // own intent actions. Music's own use of it is left intact by the extension.
        backgroundPlayerServiceOnCreateFingerprint.method.apply {
            // After the Hilt injection in the super call, before Music's own setup.
            val index = indexOfFirstInstructionOrThrow(Opcode.INVOKE_SUPER) + 1
            skipIfTrue(
                index,
                "invoke-static {p0}, $EXTENSION_OFFLINE_SERVICE_HOOKS->onServiceCreate(Landroid/app/Service;)Z",
                null,
                "return-void",
            )
        }
        backgroundPlayerServiceOnStartCommandFingerprint.method.skipIfTrue(
            0,
            // p2 (flags) is not used by the stock code, so it is free as a scratch register.
            "invoke-static {p0, p1, p3}, $EXTENSION_OFFLINE_SERVICE_HOOKS->onServiceStartCommand(Landroid/app/Service;Landroid/content/Intent;I)Z",
            "p2",
            """
                const/4 p2, 0x2
                return p2
            """,
        )
        backgroundPlayerServiceOnBindFingerprint.method.addInstruction(
            0,
            "invoke-static {p0}, $EXTENSION_OFFLINE_SERVICE_HOOKS->onServiceBind(Landroid/app/Service;)V",
        )
        backgroundPlayerServiceOnTaskRemovedFingerprint.method.skipIfTrue(
            0,
            "invoke-static {p0}, $EXTENSION_OFFLINE_SERVICE_HOOKS->onServiceTaskRemoved(Landroid/app/Service;)Z",
            null,
            "return-void",
        )
        backgroundPlayerServiceOnDestroyFingerprint.let {
            it.method.skipIfTrue(
                0,
                "invoke-static {p0}, $EXTENSION_OFFLINE_SERVICE_HOOKS->onServiceDestroy(Landroid/app/Service;)Z",
                null,
                """
                    invoke-super {p0}, ${it.classDef.superclass}->onDestroy()V
                    return-void
                """,
            )
        }

        updatePatchStatus(DOWNLOADS)
    }
}
