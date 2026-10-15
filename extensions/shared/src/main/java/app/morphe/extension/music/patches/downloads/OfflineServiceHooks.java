/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-14.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Injection points of the stock foreground service that offline playback borrows.
 * <p>
 * Patched root installs cannot register new manifest components: only {@code base.apk} is
 * mounted, so the package manager keeps the stock manifest and refuses to start a service that
 * the patch added. Offline playback therefore runs in YouTube Music's
 * {@code BackgroundPlayerService} (foregroundServiceType mediaPlayback), the same way the YouTube
 * offline player borrows YouTube's copy of that service.
 * <p>
 * The service stays usable by YouTube Music itself: an instance that Music created, bound or
 * started is marked as attached, and then Music's own lifecycle code keeps running. Only an
 * instance that exists purely for offline playback skips Music's code.
 */
@SuppressWarnings("unused")
public final class OfflineServiceHooks {
    private static final String BACKGROUND_SERVICE =
            "com.google.android.libraries.youtube.player.background.service.BackgroundPlayerService";

    private static final String NAME = "BackgroundPlayerService";
    private static final int FOREGROUND_TYPE =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0;

    /** Set right before this feature starts the service, so onCreate knows who it is for. */
    private static volatile boolean claimed;
    /** Music created, bound or started the current instance. */
    private static volatile boolean musicAttached;
    private static volatile boolean foreground;
    private static volatile int lastStartId;
    private static WeakReference<Service> service = new WeakReference<>(null);

    private OfflineServiceHooks() {
    }

    private static boolean isOurs(@Nullable Intent intent) {
        String action = intent == null ? null : intent.getAction();
        return action != null && action.startsWith(OfflinePlayer.ACTION_PREFIX);
    }

    static Intent serviceIntent(Context context, String action) {
        return new Intent(action).setClassName(context, BACKGROUND_SERVICE);
    }

    private static void markAttached(String reason) {
        if (!musicAttached) {
            Logger.printInfo(() -> NAME + " attached by YouTube Music (" + reason + ")");
        }
        musicAttached = true;
    }

    // region injection points

    /**
     * Injection point. Called after the Hilt injection in onCreate.
     *
     * @return True to skip Music's own setup, which would register the instance with
     * Music's background-playback notification controller.
     */
    public static boolean onServiceCreate(Service created) {
        service = new WeakReference<>(created);
        final boolean ours = claimed;
        musicAttached = !ours;
        Logger.printInfo(() -> NAME + " created " + (ours ? "for offline playback" : "by YouTube Music"));
        return ours;
    }

    /**
     * Injection point.
     *
     * @return True if the command was for offline playback and Music's code must not run.
     */
    public static boolean onServiceStartCommand(Service started, @Nullable Intent intent, int startId) {
        if (!isOurs(intent)) {
            markAttached("start command");
            return false;
        }
        service = new WeakReference<>(started);
        claimed = false;
        lastStartId = startId;
        final String action = intent.getAction();
        Logger.printDebug(() -> "Playback command: " + action);
        try {
            OfflinePlayer.handleCommand(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Playback command failed: " + action, ex);
        }
        try {
            // startForeground must be called for every startForegroundService, even if the
            // command could not start playback, or the app is killed.
            if (!foreground && (OfflinePlayer.ACTION_PLAY_FILE.equals(action) || OfflinePlayer.isSessionActive())) {
                promote(started, OfflinePlayer.buildNotification());
            }
            if (!OfflinePlayer.isSessionActive()) stopPlaybackForeground(false);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not finish playback command: " + action, ex);
        }
        return true;
    }

    /**
     * Injection point. Music binds the service for its own background playback.
     */
    public static void onServiceBind(Service bound) {
        service = new WeakReference<>(bound);
        markAttached("bind");
    }

    /**
     * Injection point.
     *
     * @return True to skip Music's own handling, because Music never set the instance up.
     */
    public static boolean onServiceTaskRemoved(Service removed) {
        return !musicAttached;
    }

    /**
     * Injection point.
     *
     * @return True to skip Music's own teardown, because Music never set the instance up.
     */
    public static boolean onServiceDestroy(Service destroyed) {
        final boolean skipMusic = !musicAttached;
        Logger.printInfo(() -> NAME + " destroyed, offline only: " + skipMusic);
        service = new WeakReference<>(null);
        foreground = false;
        musicAttached = false;
        claimed = false;
        if (OfflinePlayer.isSessionActive()) {
            // Music stopped the shared instance (it stops every service it registered) while
            // offline playback still runs. Start it again; this succeeds while the app is visible.
            Utils.runOnMainThreadDelayed(() -> {
                if (OfflinePlayer.isSessionActive() && service.get() == null) {
                    Logger.printInfo(() -> "Restarting the playback service for offline playback");
                    startPlaybackForeground();
                }
            }, 500);
        }
        return skipMusic;
    }

    // endregion

    // region used by the player

    /**
     * Starts offline playback. Must be called while the app is in the foreground (Android 12+
     * restriction), which holds for a tap in the library.
     */
    static void startPlaybackService(Intent command) {
        Context context = Utils.getContext();
        command.setClassName(context, BACKGROUND_SERVICE);
        claimed = service.get() == null;
        try {
            context.startForegroundService(command);
        } catch (Exception ex) {
            claimed = false;
            Logger.printException(() -> "Could not start " + NAME, ex);
        }
    }

    private static void startPlaybackForeground() {
        startPlaybackService(new Intent(OfflinePlayer.ACTION_START));
    }

    private static void promote(Service host, Notification notification) {
        try {
            if (FOREGROUND_TYPE != 0) {
                host.startForeground(OfflinePlayer.notificationId(), notification, FOREGROUND_TYPE);
            } else {
                host.startForeground(OfflinePlayer.notificationId(), notification);
            }
            foreground = true;
        } catch (Exception ex) {
            // Android 12+ refuses to promote a service from the background. The notification is
            // still shown, and the next update from the foreground promotes it again.
            Logger.printInfo(() -> "Could not promote " + NAME + " to the foreground", ex);
            NotificationManager manager = (NotificationManager) host.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(OfflinePlayer.notificationId(), notification);
        }
    }

    static void updatePlaybackNotification() {
        Service host = service.get();
        if (host == null || !OfflinePlayer.isSessionActive()) return;
        // startForeground again rather than notify: it re-promotes the instance if Music demoted it.
        promote(host, OfflinePlayer.buildNotification());
    }

    /**
     * On an instance that Music also uses, Music's notification controller removes the foreground
     * notification (stopForeground, or startForeground with its own id) when its own player
     * reacts to losing audio focus. Posts the offline notification again if it is gone.
     */
    static void ensurePlaybackNotification() {
        Service host = service.get();
        if (host == null || !OfflinePlayer.isSessionActive()) return;
        NotificationManager manager = (NotificationManager) host.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        try {
            for (StatusBarNotification active : manager.getActiveNotifications()) {
                if (active.getId() == OfflinePlayer.notificationId()) return;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the active notifications", ex);
            return;
        }
        Logger.printInfo(() -> "Offline playback notification was removed, posting it again");
        updatePlaybackNotification();
    }

    // modified by lavinhoque33, 2026-10-14
    /**
     * @param focusLost The session ended because Music (or another app) took audio focus. On an
     *                  instance that Music uses, the foreground state then belongs to Music's own
     *                  notification, which must not be detached or removed.
     */
    static void stopPlaybackForeground(boolean focusLost) {
        Service host = service.get();
        if (host != null) {
            try {
                if (musicAttached) {
                    if (!focusLost) {
                        // Music still uses the instance. Detach instead of removing, in case the
                        // foreground notification currently is Music's own.
                        host.stopForeground(Service.STOP_FOREGROUND_DETACH);
                    }
                } else {
                    host.stopForeground(Service.STOP_FOREGROUND_REMOVE);
                }
                // Only stops if no newer start request (for example Music's) arrived since ours.
                // A bound instance stays alive until Music unbinds.
                if (!(musicAttached && focusLost)) host.stopSelfResult(lastStartId);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not stop " + NAME, ex);
            }
        }
        foreground = false;
        claimed = false;
        Context context = Utils.getContext();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(OfflinePlayer.notificationId());
            // The system still treats a detached notification as the service's for a moment and
            // ignores the first cancel, so it is repeated unless a new session reused the id.
            Utils.runOnMainThreadDelayed(() -> {
                if (!OfflinePlayer.isSessionActive()) manager.cancel(OfflinePlayer.notificationId());
            }, 1_000);
        }
    }

    // endregion
}
