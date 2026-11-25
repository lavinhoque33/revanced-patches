/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-25
 */

package app.morphe.patches.music.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patches.shared.mapping.ResourceType
import app.morphe.patches.shared.mapping.getResourceId
import app.morphe.patches.shared.mapping.resourceLiteral
import app.morphe.util.indexOfFirstLiteralInstruction
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SETTINGS_ACTIVITY = "Lcom/google/android/apps/youtube/music/settings/SettingsCompatActivity;"

/** The native presenters bypass the XML colors for the up icon and parts of browse chrome. */
internal fun BytecodePatchContext.installMusicLightNativeChromeHooks() {
    val settings = mutableClassDefByOrNull(SETTINGS_ACTIVITY)?.methods?.singleOrNull {
        it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;")
    }
    val upColor = getResourceId(ResourceType.COLOR, "ytm_icon_color_active")
    if (settings != null && upColor >= 0) {
        val index = settings.indexOfFirstLiteralInstruction(upColor)
        if (index >= 0 && settings.getInstruction(index + 1).getReference<MethodReference>()?.name == "getColor") {
            settings.insertLiteralOverride(index, "$MUSIC_LIGHT_THEME_CLASS_DESCRIPTOR->settingsBackColor(I)I")
        }
    }

    // Preserve white-at-70% over media. Only substitute it where these native constructors
    // use it for text beside or below artwork, including Search's recent carousel.
    for (layout in listOf("music_two_column_item", "music_two_row_item", "music_carousel_shelf_basic_header")) {
        Fingerprint(
            name = "<init>",
            filters = listOf(
                resourceLiteral(ResourceType.LAYOUT, layout),
                resourceLiteral(ResourceType.COLOR, "yt_white1_opacity70"),
            ),
        ).matchAllOrNull().orEmpty().forEach { match ->
            val color = match.instructionMatches.last()
            val register = (color.instruction as OneRegisterInstruction).registerA
            match.method.addInstructions(
                color.index + 1,
                """
                    invoke-static/range {v$register .. v$register}, $MUSIC_LIGHT_THEME_CLASS_DESCRIPTOR->listRowSecondaryResource(I)I
                    move-result v$register
                """,
            )
        }
    }
}
