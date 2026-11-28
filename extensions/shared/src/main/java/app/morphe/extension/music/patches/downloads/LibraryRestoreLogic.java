/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * Whether the Library tab's restored start page is replaced by the Library itself, with no Android dependency.
 * Undo a saved Downloads selection on the first Library entry of each Music activity, and only when the
 * ordinary Library can be filled without selecting Downloads.
 */
final class LibraryRestoreLogic {
    private static final String LANDING_BROWSE_ID = "FEmusic_library_landing";

    private LibraryRestoreLogic() {
    }

    static boolean isLanding(@Nullable String browseId) {
        return LANDING_BROWSE_ID.equals(browseId);
    }

    /**
     * Called only after the activity's initial Library entry has been claimed.
     *
     * @param plainAvailable Whether the native landing is reachable online or usable with verified stream copies.
     * @param chosenId     The browse id of the command the app is about to open, null if it is no browse command.
     * @param fallbackId   The browse id of the tab's own start command, null if it is no browse command.
     * @return Whether the tab's own start command should replace a saved Downloads selection.
     */
    static boolean opensLibrary(boolean plainAvailable, @Nullable String chosenId, @Nullable String fallbackId) {
        return plainAvailable && savedDownloadsTab(fallbackId, chosenId);
    }

    static boolean savedDownloadsTab(@Nullable String tab, @Nullable String savedBrowseId) {
        return isLanding(tab) && OfflineLibraryLogic.DOWNLOADS_BROWSE_ID.equals(savedBrowseId);
    }

    /** Shared by the two stock tab-start routes; all calls are on the UI thread. */
    static final class InitialEntry {
        @Nullable
        private WeakReference<Object> activity;
        private boolean claimed;

        void begin(Object owner) {
            activity = new WeakReference<>(owner);
            claimed = false;
        }

        boolean end(Object owner) {
            if (activity() != owner) return false;
            activity = null;
            return true;
        }

        @Nullable
        Object activity() {
            return activity == null ? null : activity.get();
        }

        boolean pending() {
            return !claimed && activity() != null;
        }

        boolean claim() {
            if (!pending()) return false;
            claimed = true;
            return true;
        }
    }

    enum DirectStart { STOCK, LANDING, LOAD_COPIES }

    static DirectStart directStart(boolean eligible, boolean loaded, boolean playable) {
        if (!eligible) return DirectStart.STOCK;
        if (playable) return DirectStart.LANDING;
        return loaded ? DirectStart.STOCK : DirectStart.LOAD_COPIES;
    }

    /** Both the worker and the deadline run this on main; only the first current result may dispatch. */
    static final class DirectRequest {
        private final long navigation;
        private final long network;
        private final int copies;
        private boolean finished;

        DirectRequest(long navigation, long network, int copies) {
            this.navigation = navigation;
            this.network = network;
            this.copies = copies;
        }

        @Nullable
        DirectStart finish(long currentNavigation, long currentNetwork, int currentCopies,
                           boolean online, boolean usableOfflineRows) {
            if (finished) return null;
            finished = true;
            if (navigation != currentNavigation) return null;
            boolean offlineLanding = usableOfflineRows && network == currentNetwork && copies == currentCopies;
            return online || offlineLanding ? DirectStart.LANDING : DirectStart.STOCK;
        }
    }
}
