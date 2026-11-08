/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.function.Predicate;

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

    /** The rule that armed the start, and the song it is armed for. */
    record Arm(String rule, String videoId) {}

    static final String RULE_ID = "id";
    static final String RULE_PLAYLIST_INDEX = "playlist-index";
    static final String RULE_PLAYLIST_SOURCE = "playlist-source";

    /**
     * Ties a tap to a stream copy. The row of an album can carry the id of its music video while the copy is under the
     * id of the song, so the rules are layered, from the most to the least specific:
     * <ol>
     *   <li>{@link #RULE_ID}: any video id of the command has a stream copy;</li>
     *   <li>{@link #RULE_PLAYLIST_INDEX}: the command names a downloaded playlist or album and the track at the tapped
     *   position has a copy (an endpoint with params, such as a play or shuffle button, names no position);</li>
     *   <li>{@link #RULE_PLAYLIST_SOURCE}: the command names a playlist or album every track of which has a copy.
     *   The start only sets the mode, never the song, so a row that is not downloaded simply plays online in song
     *   mode.</li>
     * </ol>
     *
     * @param enabled      The setting is on and the stream copies are in effect; otherwise nothing is ever armed.
     * @param commandIds   The video ids found in the command.
     * @param sourceTracks The ordered tracks of the downloaded playlist the command names, or null.
     * @param index        The position the command names, or -1.
     * @param hasParams    The command carries params, so it is not a row tap.
     * @return The rule and the song to arm for, or null when no copy is tied to the tap.
     */
    @Nullable
    static Arm chooseArm(boolean enabled, List<String> commandIds, @Nullable List<String> sourceTracks, int index, boolean hasParams,
                         Predicate<String> hasCopy) {
        if (!enabled) return null;

        for (String id : commandIds) {
            if (hasCopy.test(id)) return new Arm(RULE_ID, id);
        }
        if (sourceTracks == null || sourceTracks.isEmpty()) return null;

        int position = index >= 0 ? index : (hasParams ? -1 : 0);
        if (position >= 0 && position < sourceTracks.size() && hasCopy.test(sourceTracks.get(position))) {
            return new Arm(RULE_PLAYLIST_INDEX, sourceTracks.get(position));
        }
        for (String track : sourceTracks) {
            if (!hasCopy.test(track)) return null;
        }
        return new Arm(RULE_PLAYLIST_SOURCE, commandIds.isEmpty() ? sourceTracks.get(0) : commandIds.get(0));
    }

    /**
     * Whether an arm names exactly the song of the tapped row, so the command may be retargeted at it: the rule
     * identified one video id (the header rule cannot), it differs from the id the command carries, and that id has no
     * copy of its own.
     */
    static boolean namesTheRow(String rule, String armedId, @Nullable String commandVideoId,
                               Predicate<String> hasCopy) {
        if (!RULE_ID.equals(rule) && !RULE_PLAYLIST_INDEX.equals(rule)) return false;
        return commandVideoId != null && !commandVideoId.equals(armedId) && !hasCopy.test(commandVideoId);
    }

    /**
     * @param enabled     The setting and the stream copies are on.
     * @param armed       A tap just armed the start for a song with a copy.
     * @param currentMode The mode the app holds now, or null when unknown.
     * @return The mode to set now, or null for none. A tap is a fresh selection (stock resets the mode itself on
     * every tap), so both video modes become the audio mode, the user's Video included; the audio modes and the
     * data-saving override stay. A later toggle by the user is a setter call of its own and is never changed.
     */
    @Nullable
    static String modeToSet(boolean enabled, boolean armed, @Nullable String currentMode) {
        return enabled && armed && isVideoMode(currentMode) ? ATV_PREFERRED : null;
    }

    /** The command asks for the audio mode unless the data-saving override is held, which is audio already. */
    static boolean hintsIn(@Nullable String currentMode) {
        return !"DONT_PLAY_VIDEO_OVERRIDE".equals(currentMode);
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
     * @param modeUnchangedSinceTap No mode was set since that tap: the video mode still held is the one the tap found
     *                              (a user's explicit Video, reset by the tap as a fresh selection), not a new choice.
     * @return True for the video mode, or an unknown mode, unless the song is the one a tap just armed and the mode
     * still is the video one the tap found (the fallback, or the user's choice a tap resets).
     */
    static boolean declineForVideo(@Nullable String stateName, String videoId, @Nullable String pendingId,
                                   long pendingAt, long now, long ttl, boolean modeUnchangedSinceTap) {
        if (stateName == null) return true;
        if (!isVideoMode(stateName)) return false;

        boolean armed = videoId.equals(pendingId) && isLive(pendingId, pendingAt, now, ttl);
        if (armed && OMV_PREFERRED.equals(stateName)) return false;
        return !(armed && OMV_PREFERRED_USER_TRIGGERED.equals(stateName) && modeUnchangedSinceTap);
    }

    /** The modes stock treats as audio: the audio fallback, the user's Song and the data-saving override. */
    static boolean isAudioMode(@Nullable String stateName) {
        return ATV_PREFERRED.equals(stateName) || "ATV_PREFERRED_USER_TRIGGERED".equals(stateName)
                || "DONT_PLAY_VIDEO_OVERRIDE".equals(stateName);
    }

    /**
     * Whether a queue item shows its song (the local copy's id) rather than the music video the server listed.
     *
     * @param hasSong The item's album position names a song with a stream copy.
     */
    static boolean showsSong(boolean enabled, @Nullable String mode, boolean hasSong) {
        return enabled && hasSong && isAudioMode(mode);
    }

    /** The user-triggered mode the pill sets from {@code mode}: the video from an audio mode, else the audio. */
    static String pillTarget(@Nullable String mode) {
        return isAudioMode(mode) ? "OMV_PREFERRED_USER_TRIGGERED" : "ATV_PREFERRED_USER_TRIGGERED";
    }
}
