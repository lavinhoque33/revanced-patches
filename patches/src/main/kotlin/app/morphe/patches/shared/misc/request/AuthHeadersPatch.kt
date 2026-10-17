/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 */
package app.morphe.patches.shared.misc.request

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.extension.Constants.EXTENSION_PATH

// modified by lavinhoque33, 2026-10-16: playlist downloads and Android Auto share one capture of the app's own auth headers.
internal val authHeadersPatch = bytecodePatch {
    dependsOn(buildRequestPatch)
    execute {
        hookBuildRequest("$EXTENSION_PATH/innertube/utils/AuthUtils;->setRequestHeaders(Ljava/lang/String;Ljava/util/Map;)V")
    }
}
