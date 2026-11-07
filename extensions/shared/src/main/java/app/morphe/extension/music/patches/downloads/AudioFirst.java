/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Starts a song with a stream copy in audio mode, so the local copy plays. Stock opens a tapped album row in video
 * mode for an account without Premium, which requests the music video and never finds a copy.
 *
 * <p>A tap that resolves a watch command for a song with a stream copy arms a short flag. The app's mode setter then
 * turns the video fallback the app picks into the audio mode once. The two user-triggered modes are never changed, so
 * the Song/Video switch keeps working. The mode is read back for the swap, which leaves the video mode online.
 *
 * <p>Nothing here touches storage: the stream copies come from the in-memory set the checkmarks keep.
 */
@SuppressWarnings("unused")
public final class AudioFirst {
    private static final Object lock = new Object();

    /** The song of the last eligible tap, until its mode was forced, or it expired. */
    @Nullable
    private static String armedId;
    private static long armedAt;

    private static final long COMMAND_LOG_INTERVAL_MILLIS = 300;
    private static long lastCommandLogAt;

    /** The last mode the app set, null until it first does. The app starts in the audio mode. */
    @Nullable
    private static volatile String lastMode;

    private AudioFirst() {}

    /** Enabled by the patch only after the mode setter and the tap hook resolve. */
    public static boolean supported() {
        return false;
    }

    /** The mode is read only where the setter is hooked, the app starting in the audio mode. */
    static String currentMode() {
        if (!supported()) return null;
        String mode = lastMode;
        return mode != null ? mode : AudioFirstLogic.ATV_PREFERRED;
    }

    /** Injection point: every command a tap resolves. Never changes the command. */
    public static void onCommand(@Nullable Object command) {
        try {
            if (!supported() || !(command instanceof MessageLite message)) return;
            if (!Settings.START_DOWNLOADED_IN_AUDIO.get() || !StreamSwap.enabled()) return;

            DownloadedMarkers.warmUp();
            DownloadedRegistry registry = DownloadedMarkers.loadedRegistry();
            // The first tap after a start can come before the copies are known: it is not armed.
            if (registry == null) return;

            byte[] bytes = message.toByteArray();
            TapCommand tap = TapCommand.parse(bytes);
            if (tap.ids.isEmpty() && !tap.isWatch()) return;

            String source = registry.findSource(bytes);
            logCommand(tap, source);

            AudioFirstLogic.Arm arm = AudioFirstLogic.chooseArm(true, tap.ids, registry.sourceTracks(source),
                    tap.index, tap.hasParams, registry::isDownloaded);
            // Only a watch command that ties to no copy disarms an earlier tap: a command of another kind may
            // follow the tap that armed.
            if (arm == null && !tap.isWatch()) return;

            synchronized (lock) {
                armedId = arm == null ? null : arm.videoId();
                armedAt = SystemClock.elapsedRealtime();
            }
            if (arm != null) {
                Logger.printDebug(() -> "AudioFirst: armed by " + arm.rule() + " v=" + arm.videoId());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "AudioFirst: onCommand failure", ex);
        }
    }

    /** What the tap carried, which decides how it ties to a copy; at most once every 300 ms. */
    private static void logCommand(TapCommand tap, @Nullable String source) {
        long now = SystemClock.elapsedRealtime();
        synchronized (lock) {
            if (now - lastCommandLogAt < COMMAND_LOG_INTERVAL_MILLIS) return;
            lastCommandLogAt = now;
        }
        Logger.printDebug(() -> "AudioFirst: command ids=" + tap.ids + " video=" + tap.videoId
                + " playlist=" + (tap.playlistId.isEmpty() ? "-" : tap.playlistId)
                + " index=" + (tap.index < 0 ? "-" : tap.index) + " params=" + tap.hasParams
                + " source=" + (source == null ? "-" : source) + " fields=" + tap.fields);
    }

    /** Injection point: the argument of the app's mode setter. */
    public static Enum<?> overrideMode(Enum<?> mode) {
        try {
            if (mode == null) return null;

            String name = mode.name();
            String forced = null;
            Enum<?> result = mode;
            if (AudioFirstLogic.OMV_PREFERRED.equals(name) && Settings.START_DOWNLOADED_IN_AUDIO.get()) {
                long now = SystemClock.elapsedRealtime();
                synchronized (lock) {
                    String decided = AudioFirstLogic.decide(name, armedId, armedAt, now,
                            AudioFirstLogic.ARM_TTL_MILLIS);
                    if (!name.equals(decided)) {
                        forced = armedId;
                        armedId = null;
                    }
                    result = forced == null ? mode : constant(mode, decided);
                }
                String id = forced;
                if (id != null) {
                    Logger.printDebug(() -> "AudioFirst: forced ATV_PREFERRED for v=" + id);
                } else {
                    Logger.printDebug(() -> "AudioFirst: expired/not armed");
                }
            }
            lastMode = result.name();
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "AudioFirst: overrideMode failure", ex);
            return mode;
        }
    }

    /** @return True if the player shows the video, or its mode is unknown, so the swap must not answer audio. */
    static boolean declinesForVideo(String videoId) {
        String pending;
        long pendingAt;
        synchronized (lock) {
            pending = armedId;
            pendingAt = armedAt;
        }
        return AudioFirstLogic.declineForVideo(currentMode(), videoId, pending, pendingAt,
                SystemClock.elapsedRealtime(), AudioFirstLogic.ARM_TTL_MILLIS);
    }

    private static Enum<?> constant(Enum<?> sample, String name) {
        for (Enum<?> candidate : sample.getDeclaringClass().getEnumConstants()) {
            if (candidate.name().equals(name)) return candidate;
        }
        return sample;
    }
}
