/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-14.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint

// The stock service class name is kept by R8 because the manifest references it.
private const val BACKGROUND_PLAYER_SERVICE_SUFFIX = "/BackgroundPlayerService;"

private fun serviceMethodFingerprint(methodName: String) = Fingerprint(
    custom = { method, classDef ->
        classDef.type.endsWith(BACKGROUND_PLAYER_SERVICE_SUFFIX) && method.name == methodName
    },
)

internal val backgroundPlayerServiceOnCreateFingerprint = serviceMethodFingerprint("onCreate")
internal val backgroundPlayerServiceOnStartCommandFingerprint = serviceMethodFingerprint("onStartCommand")
internal val backgroundPlayerServiceOnBindFingerprint = serviceMethodFingerprint("onBind")
internal val backgroundPlayerServiceOnTaskRemovedFingerprint = serviceMethodFingerprint("onTaskRemoved")
internal val backgroundPlayerServiceOnDestroyFingerprint = serviceMethodFingerprint("onDestroy")
