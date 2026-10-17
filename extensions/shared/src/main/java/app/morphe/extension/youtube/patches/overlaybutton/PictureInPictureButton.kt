/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3397
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

// modified by lavinhoque33, 2026-10-17: ported Morphe's picture-in-picture button as an RVX overlay button.

package app.morphe.extension.youtube.patches.overlaybutton

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Rational
import android.view.View
import app.morphe.extension.shared.utils.Logger
import app.morphe.extension.shared.utils.ResourceUtils
import app.morphe.extension.shared.utils.Utils
import app.morphe.extension.youtube.settings.Settings
import app.morphe.extension.youtube.shared.PlayerControlButton
import app.morphe.extension.youtube.shared.RootView.isAdProgressTextVisible

@Suppress("unused")
object PictureInPictureButton {
    private var instance: PlayerControlButton? = null

    private val DEFAULT_ASPECT_RATIO = Rational(16, 9)

    /**
     * Picture-in-Picture rejects aspect ratios wider than 2.39:1 or taller than 1:2.39.
     */
    private const val MAX_ASPECT_RATIO = 2.39f
    private const val MIN_ASPECT_RATIO = 1 / MAX_ASPECT_RATIO

    /**
     * Injection point.
     */
    @JvmStatic
    fun initializeButton(controlsView: View) {
        try {
            instance = PlayerControlButton(
                controlsViewGroup = controlsView,
                imageViewButtonId = "revanced_pip_button",
                buttonVisibility = { isButtonEnabled() },
                onClickListener = { onClick() }
            )
        } catch (ex: Exception) {
            Logger.printException({ "initializeButton failure" }, ex)
        }
    }

    /**
     * injection point
     */
    @JvmStatic
    fun setVisibilityNegatedImmediate() {
        instance?.setVisibilityNegatedImmediate()
    }

    /**
     * injection point
     */
    @JvmStatic
    fun setVisibilityImmediate(visible: Boolean) {
        instance?.setVisibilityImmediate(visible)
    }

    /**
     * injection point
     */
    @JvmStatic
    fun setVisibility(visible: Boolean, animated: Boolean) {
        instance?.setVisibility(visible, animated)
    }

    private fun isPipSupported(): Boolean {
        return Utils.getContext().packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun isButtonEnabled(): Boolean {
        return Settings.OVERLAY_BUTTON_PICTURE_IN_PICTURE.get()
                && isPipSupported()
                && !isAdProgressTextVisible()
    }

    private fun onClick() {
        Logger.printDebug { "Picture-in-picture button tapped" }
        enterPictureInPicture()
    }

    private fun enterPictureInPicture() {
        try {
            val activity = Utils.getActivity()
            if (activity == null) {
                Logger.printDebug { "Activity is null, cannot enter Picture-in-Picture" }
                return
            }

            val entered = activity.enterPictureInPictureMode(buildPipParams(activity))
            Logger.printDebug { "enterPictureInPictureMode result: $entered" }
        } catch (ex: Exception) {
            Logger.printException({ "enterPictureInPicture failure" }, ex)
        }
    }

    private fun buildPipParams(activity: Activity): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        var aspectRatio = DEFAULT_ASPECT_RATIO

        val playerView = activity.findViewById<View>(ResourceUtils.getIdIdentifier("player_view"))
        if (playerView != null && playerView.width > 0 && playerView.height > 0) {
            val width = playerView.width
            val height = playerView.height
            val ratio = width.toFloat() / height
            if (ratio in MIN_ASPECT_RATIO..MAX_ASPECT_RATIO) {
                aspectRatio = Rational(width, height)
            }

            val sourceRect = Rect()
            if (playerView.getGlobalVisibleRect(sourceRect)) {
                builder.setSourceRectHint(sourceRect)
            }
        }

        return builder.setAspectRatio(aspectRatio).build()
    }
}
