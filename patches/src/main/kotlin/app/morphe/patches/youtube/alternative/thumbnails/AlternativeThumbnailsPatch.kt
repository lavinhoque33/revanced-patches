package app.morphe.patches.youtube.alternative.thumbnails

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.imageurl.addImageUrlErrorCallbackHook
import app.morphe.patches.shared.imageurl.addImageUrlHook
import app.morphe.patches.shared.imageurl.addImageUrlSuccessCallbackHook
import app.morphe.patches.shared.imageurl.cronetImageUrlHookPatch
import app.morphe.patches.youtube.layout.originaltitles.videoTitlesHookPatch
import app.morphe.patches.youtube.utils.compatibility.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.utils.extension.Constants.ALTERNATIVE_THUMBNAILS_CLASS_DESCRIPTOR
import app.morphe.patches.youtube.utils.navigation.navigationBarHookPatch
import app.morphe.patches.youtube.utils.patch.PatchList.ALTERNATIVE_THUMBNAILS
import app.morphe.patches.youtube.utils.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.utils.settings.ResourceUtils.addPreference
import app.morphe.patches.youtube.utils.settings.settingsPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources

// modified by lavinhoque33, 2026-10-17: icon shown before DeArrow titles (ported from Morphe's DeArrow patch).
private val deArrowTitleIconResourcePatch = resourcePatch(
    description = "deArrowTitleIconResourcePatch"
) {
    execute {
        copyResources(
            "youtube/alternativethumbnails",
            ResourceGroup("drawable", "revanced_dearrow_title_icon.xml")
        )
    }
}

@Suppress("unused")
val alternativeThumbnailsPatch = bytecodePatch(
    ALTERNATIVE_THUMBNAILS.title,
    ALTERNATIVE_THUMBNAILS.summary,
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        cronetImageUrlHookPatch(true),
        navigationBarHookPatch,
        playerTypeHookPatch,
        settingsPatch,
        // modified by lavinhoque33, 2026-10-17: DeArrow titles (ported from Morphe's DeArrow patch)
        // are replaced by the same hooks that restore the original titles.
        videoTitlesHookPatch,
        deArrowTitleIconResourcePatch,
    )
    execute {

        addImageUrlHook(ALTERNATIVE_THUMBNAILS_CLASS_DESCRIPTOR)
        addImageUrlSuccessCallbackHook(ALTERNATIVE_THUMBNAILS_CLASS_DESCRIPTOR)
        addImageUrlErrorCallbackHook(ALTERNATIVE_THUMBNAILS_CLASS_DESCRIPTOR)

        // region add settings

        addPreference(
            arrayOf(
                "PREFERENCE_SCREEN: ALTERNATIVE_THUMBNAILS",
                "SETTINGS: ALTERNATIVE_THUMBNAILS",
                // modified by lavinhoque33, 2026-10-17: DeArrow titles settings.
                "SETTINGS: DEARROW_TITLES"
            ),
            ALTERNATIVE_THUMBNAILS
        )

        // endregion

    }
}
