/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * The app's own player configuration, kept for the video copies that play offline.
 *
 * <p>While online the app plays a copy from its own player response, whose {@code playerConfig} holds every setting
 * of its player: which codecs it may use, which quality it starts with and more. The response stored with a video
 * copy comes from the saving client and has only the two flags the ExoPlayer engine needs, so the app's player
 * selection works with defaults for the rest, and offline it never selected the video track of the copy. The
 * configuration is the app's and the same for every video, so the last one an online player response carried is
 * kept in a file (without the request hints that force SABR) and laid under the stored response of a video copy
 * when the replay answers from it.
 */
final class PlayerConfigTemplate {
    private static final String FILE_NAME = "rvx_player_config.pb";

    private static final Object lock = new Object();
    private static long lastChecked;
    private static boolean loaded;
    @Nullable
    private static PlayerConfig held;

    private PlayerConfigTemplate() {}

    /**
     * Keeps the configuration of an online player response when the held one is old or none is held. Called for
     * every online answer, so a response that is not due costs nothing but this check. Off the main thread.
     */
    static void rememberFrom(StreamSwap.Response online) {
        try {
            synchronized (lock) {
                if (!PlayerConfigTemplateLogic.due(lastChecked, SystemClock.elapsedRealtime())) return;
            }

            PlayerConfig live = PlayerResponse.parseFrom(online.rvxBytes()).getPlayerConfig();
            if (!PlayerConfigTemplateLogic.isAppConfig(live)) return;

            PlayerConfig template = PlayerConfigTemplateLogic.forOffline(live);
            synchronized (lock) {
                if (template.equals(current())) {
                    lastChecked = SystemClock.elapsedRealtime();
                    return;
                }

                write(template);
                held = template;
                lastChecked = SystemClock.elapsedRealtime();
            }
            Logger.printDebug(() -> "PlayerConfigTemplate: kept the app's player configuration "
                    + template.getSerializedSize() + "B");
        } catch (Exception ex) {
            Logger.printException(() -> "PlayerConfigTemplate: could not keep the player configuration", ex);
        }
    }

    /**
     * The stored response of a video copy with the app's configuration under its own; the response itself when
     * none has been kept yet. Reads the file once. Off the main thread.
     */
    static byte[] applyTo(byte[] storedResponse) throws IOException {
        PlayerConfig template;
        synchronized (lock) {
            template = current();
        }
        Logger.printDebug(() -> "PlayerConfigTemplate: " + (template == null
                ? "none kept yet, the stored configuration alone"
                : "laid under the stored configuration, " + template.getSerializedSize() + "B"));
        if (template == null) return storedResponse;

        return PlayerConfigTemplateLogic.withTemplate(PlayerResponse.parseFrom(storedResponse), template)
                .toByteArray();
    }

    /** The held configuration, read from the file the first time. Callers hold the lock. */
    @Nullable
    private static PlayerConfig current() {
        if (loaded) return held;

        loaded = true;
        File file = file();
        if (!file.isFile()) return null;

        try {
            held = PlayerConfig.parseFrom(Files.readAllBytes(file.toPath()));
        } catch (IOException ex) {
            Logger.printException(() -> "PlayerConfigTemplate: unreadable, dropped", ex);
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        return held;
    }

    private static void write(PlayerConfig template) throws IOException {
        File file = file();
        File pending = new File(file.getParentFile(), FILE_NAME + ".tmp");
        Files.write(pending.toPath(), template.toByteArray());
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static File file() {
        return new File(Utils.getContext().getFilesDir(), FILE_NAME);
    }
}
