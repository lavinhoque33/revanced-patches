/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-06.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

/** androidx {@code MediaBrowserServiceCompat.Result.sendResult(Object)}. */
internal object MediaBrowserResultSendResultFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    strings = listOf("sendResult() called when either sendResult() or sendError() had already been called for: "),
)

/** androidx {@code MediaBrowserServiceCompat.Result.detach()}. */
internal object MediaBrowserResultDetachFingerprint : Fingerprint(
    classFingerprint = MediaBrowserResultSendResultFingerprint,
    returnType = "V",
    parameters = listOf(),
    strings = listOf("detach() called when detach() had already been called for: "),
)

/** {@code MusicBrowserService.onLoadChildren(String parentId, Result, Bundle options)}. */
internal object MusicBrowserServiceOnLoadChildrenFingerprint : Fingerprint(
    definingClass = "/MusicBrowserService;",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "L", "Landroid/os/Bundle;"),
    strings = listOf("com.google.android.apps.youtube.music.mediabrowser.locale"),
)

/** {@code MusicMediaSessionCallback.onPlayFromMediaId(String, Bundle)}. */
internal object MediaSessionOnPlayFromMediaIdFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Landroid/os/Bundle;"),
    strings = listOf("MSC: onPlayFromMediaId(). appPkg: '%s' mediaId: '%s'"),
)

/** {@code MusicMediaSessionCallback.onPlayFromUri(Uri, Bundle)}. */
internal object MediaSessionOnPlayFromUriFingerprint : Fingerprint(
    classFingerprint = MediaSessionOnPlayFromMediaIdFingerprint,
    returnType = "V",
    parameters = listOf("Landroid/net/Uri;", "Landroid/os/Bundle;"),
    strings = listOf("MSC: onPlayFromUri(). appPkg: '%s' uri: '%s'"),
)
