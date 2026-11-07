/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

/**
 * Pure rules of the audio-first start, so they can be tested without Android. The modes are the names of the
 * five-value audio/video mode of the app.
 */
final class AudioFirstLogic {
    static final String ATV_PREFERRED = "ATV_PREFERRED";
    static final String OMV_PREFERRED = "OMV_PREFERRED";
    static final String OMV_PREFERRED_USER_TRIGGERED = "OMV_PREFERRED_USER_TRIGGERED";

    /** How long a tap may wait for the app to set its mode. */
    static final long ARM_TTL_MILLIS = 10_000;

    private AudioFirstLogic() {}

    /** A tapped song arms the start only with the setting on, the stream copies in effect and a copy of this song. */
    static boolean shouldArm(boolean settingOn, boolean streamCopiesEnabled, boolean hasCopy) {
        return settingOn && streamCopiesEnabled && hasCopy;
    }

    static boolean isLive(@Nullable String armedId, long armedAt, long now, long ttl) {
        return armedId != null && now >= armedAt && now - armedAt <= ttl;
    }

    /**
     * @return The mode the app should be set to: the audio mode in place of the video fallback the app chose for an
     * armed tap, and every other mode as it is. The user-triggered modes are never changed.
     */
    static String decide(String stateName, @Nullable String armedId, long armedAt, long now, long ttl) {
        if (!OMV_PREFERRED.equals(stateName)) return stateName;
        return isLive(armedId, armedAt, now, ttl) ? ATV_PREFERRED : stateName;
    }

    static boolean isVideoMode(@Nullable String stateName) {
        return OMV_PREFERRED.equals(stateName) || OMV_PREFERRED_USER_TRIGGERED.equals(stateName);
    }

    /**
     * Whether the swap must leave the response online because the player shows the video.
     *
     * @param stateName The last mode the app set, or null if it is not known on this version.
     * @param pendingId The song a tap armed, which the app is about to switch to the audio mode.
     * @return True for the video mode, or an unknown mode, unless the song is the one a tap just armed and the app
     * still has its video fallback set.
     */
    static boolean declineForVideo(@Nullable String stateName, String videoId, @Nullable String pendingId,
                                   long pendingAt, long now, long ttl) {
        if (stateName == null) return true;
        if (!isVideoMode(stateName)) return false;
        return !(videoId.equals(pendingId) && isLive(pendingId, pendingAt, now, ttl)
                && OMV_PREFERRED.equals(stateName));
    }
}
