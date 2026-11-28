/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import android.app.Activity;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Makes the Library tab start at the Library after a restart, not at the Downloads chip the last session used. */
@SuppressWarnings("unused")
public final class LibraryRestore {
    private static final LibraryRestoreLogic.InitialEntry INITIAL_ENTRY = new LibraryRestoreLogic.InitialEntry();
    private static final AtomicLong NAVIGATION_EPOCH = new AtomicLong();
    private static final long COPY_LOAD_DEADLINE_MS = 250;

    public interface Navigator {
        void rvxOpenLibrary(String tab, Object command);
    }

    private LibraryRestore() {
    }

    /** True only when the native no-cache Library response has also been patched. */
    private static boolean offlineLandingAvailable() {
        return false;
    }

    /**
     * Injection point: the tab's saved-command callback, just before it opens the command it chose. The
     * native direct-offline route skips this callback. The saved page is not changed in either route.
     *
     * @param chosen   The command the app restored, or the tab's own command when none was saved.
     * @param fallback The tab's own start command.
     */
    public static Object restoreLibraryStart(Object chosen, Object fallback) {
        try {
            if (!Utils.isCurrentlyOnMainThread() || !INITIAL_ENTRY.pending() || !hasLiveActivity()
                    || !(chosen instanceof NativeDownloads.Command chosenCommand)
                    || !(fallback instanceof NativeDownloads.Command fallbackCommand)) {
                return chosen;
            }

            byte[] start = fallbackCommand.toByteArray();
            String fallbackId = OfflineReplayLogic.browseIdOf(start);
            if (!LibraryRestoreLogic.isLanding(fallbackId) || !INITIAL_ENTRY.claim()) return chosen;
            if (chosen == fallback) return chosen;

            boolean online = Utils.isInternetReachable();
            boolean plainAvailable = online || offlineLandingAvailable() && LibraryLanding.streamModeOffline()
                    && LibraryLandingLogic.isPlainCommand(start)
                    && knownCopies(DownloadedMarkers.loadedRegistry());
            boolean replace = LibraryRestoreLogic.opensLibrary(plainAvailable,
                    OfflineReplayLogic.browseIdOf(chosenCommand.toByteArray()), fallbackId);
            if (replace) Logger.printDebug(() -> "LibraryRestore: initial saved Downloads -> Library");
            return replace ? fallback : chosen;
        } catch (Exception ex) {
            Logger.printException(() -> "LibraryRestore failed", ex);
            return chosen;
        }
    }

    /** The saved root will run the stock Library browse; its response hook independently checks for local copies. */
    public static boolean restoreSavedFragment(String tab, Object saved) {
        if (!LibraryRestoreLogic.isLanding(tab) || !INITIAL_ENTRY.pending() || !hasLiveActivity()
                || !(saved instanceof NativeDownloads.Command command)) {
            return false;
        }
        if (!LibraryRestoreLogic.savedDownloadsTab(tab, OfflineReplayLogic.browseIdOf(command.toByteArray()))
                || !INITIAL_ENTRY.claim()) {
            return false;
        }
        Logger.printDebug(() -> "LibraryRestore: saved Library fragment Downloads -> Library");
        return true;
    }

    public static void activityCreated(Activity activity) {
        INITIAL_ENTRY.begin(activity);
        navigationChanging();
    }

    public static void activityDestroyed(Activity activity) {
        if (INITIAL_ENTRY.end(activity)) navigationChanging();
    }

    private static boolean hasLiveActivity() {
        return INITIAL_ENTRY.activity() instanceof Activity activity && !activity.isFinishing();
    }

    public static void navigationChanging() {
        NAVIGATION_EPOCH.incrementAndGet();
    }

    public static boolean shouldPrepareDirectOfflineStart() {
        return Utils.isCurrentlyOnMainThread() && INITIAL_ENTRY.pending() && hasLiveActivity()
                && LibraryLanding.streamModeOffline();
    }

    /** Initial direct offline start only. Use the loaded copy index or bound the wait for worker verification. */
    public static boolean openDirectOfflineStart(Object target, String tab, Object downloads, Object landing) {
        if (!(target instanceof Navigator navigator) || !(landing instanceof NativeDownloads.Command command)
                || !LibraryRestoreLogic.isLanding(tab)
                || !shouldPrepareDirectOfflineStart() || !LibraryLandingLogic.isPlainCommand(command.toByteArray())
                || !INITIAL_ENTRY.claim()) {
            return false;
        }
        DownloadedRegistry registry = DownloadedMarkers.loadedRegistry();
        var start = LibraryRestoreLogic.directStart(true, registry != null, knownCopies(registry));
        if (start == LibraryRestoreLogic.DirectStart.STOCK) return false;
        if (start == LibraryRestoreLogic.DirectStart.LANDING) {
            Logger.printDebug(() -> "LibraryRestore: initial direct offline -> Library");
            navigator.rvxOpenLibrary(tab, landing);
            return true;
        }

        PlaybackConnectivity.start();
        var request = new LibraryRestoreLogic.DirectRequest(NAVIGATION_EPOCH.get(),
                PlaybackConnectivity.reconnectEpoch(), DownloadsPage.copiesGeneration());
        var owner = new WeakReference<>(navigator);
        Consumer<Boolean> complete = ready -> {
            Navigator live = owner.get();
            if (live == null || !hasLiveActivity()) return;
            var route = request.finish(NAVIGATION_EPOCH.get(), PlaybackConnectivity.reconnectEpoch(),
                    DownloadsPage.copiesGeneration(), Utils.isInternetReachable(),
                    ready && LibraryLanding.offlineRowsReady());
            if (route != null) live.rvxOpenLibrary(tab, route == LibraryRestoreLogic.DirectStart.LANDING
                    ? landing : downloads);
        };
        Utils.runOnMainThreadDelayed(() -> complete.accept(false), COPY_LOAD_DEADLINE_MS);
        Utils.runOnBackgroundThread(() -> {
            boolean available = LibraryLanding.prepareOfflineRows();
            Utils.runOnMainThread(() -> complete.accept(available));
        });
        return true;
    }

    private static boolean knownCopies(DownloadedRegistry registry) {
        return LibraryLanding.offlineRowsReady() || registry != null && registry.hasTracks();
    }
}
