/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Opens a downloaded album from the Library's Downloads chip as the album's own page while online. The chip falls
 * back to the playlist page of the album ({@code VLOLAK5uy_…}) when no album page is known for it yet: that page has
 * only the rows, no header, no Play and no Shuffle. The tap is held for the moment it takes to prove the album page
 * from the network and then opens that page, unless the user did something else meanwhile; an album that cannot be
 * proved opens its saved page, with no online request, rather than the bare rows.
 *
 * <p>Runs on the main thread; the proof and the disk work run on a worker thread.
 */
final class AlbumOpening {
    private static final String TAG = "AlbumOpen: ";

    private static final ConcurrentMap<String, AlbumOpeningLogic.HeldTap> pending = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Long> unresolved = new ConcurrentHashMap<>();
    private static final AtomicLong navigations = new AtomicLong();

    private AlbumOpening() {}

    /** A command that opens a page or starts playback was dispatched: a tap that is held is no longer the latest. */
    static void noteNavigation() {
        navigations.incrementAndGet();
    }

    /** Whether the album goes straight to its saved page: its album page could not be proved a moment ago. */
    static boolean opensSaved(String sourceId) {
        return AlbumOpeningLogic.unresolvedRecently(unresolved.get(sourceId), System.currentTimeMillis());
    }

    /**
     * Decides a browse command the app dispatches.
     *
     * @return true when the command is taken: the app must not run it, the album page opens in its place.
     */
    static boolean take(@Nullable String browseId, byte[] command, boolean online) {
        AlbumOpeningLogic.Decision decision = AlbumOpeningLogic.routeOf(online, DownloadsPage.downloadsChipShown(),
                OfflineBrowsePages.mappingLoaded(), browseId, OfflineCopyStore::hasSource,
                OfflineBrowsePages::albumPageIfLoaded, AlbumOpening::opensSaved);
        if (decision.route() == AlbumOpeningLogic.Route.STOCK) return false;

        Logger.printDebug(() -> TAG + "route=" + decision.route() + " browse=" + browseId + " source="
                + decision.sourceId() + " page=" + decision.pageId());
        return switch (decision.route()) {
            case OPEN_ALBUM_PAGE -> openLater(decision.sourceId(),
                    new AlbumOpeningLogic.HeldTap(navigations.get(), command), decision.pageId());
            case RESOLVE -> resolve(decision.sourceId(), command);
            default -> false;
        };
    }

    private static boolean resolve(String sourceId, byte[] command) {
        AlbumOpeningLogic.HeldTap held = pending.get(sourceId);
        if (held != null && held.adopt(navigations.get(), command)) {
            Logger.printDebug(() -> TAG + sourceId + " is already being resolved, the lookup is kept for this tap");
            return true;
        }

        AlbumOpeningLogic.HeldTap opening = new AlbumOpeningLogic.HeldTap(navigations.get(), command);
        pending.put(sourceId, opening);

        Utils.runOnBackgroundThread(() -> {
            String page = null;
            try {
                page = AlbumPageResolver.resolveAndSave(sourceId);
                if (page != null) DownloadsPage.onCopiesChanged();
            } catch (Exception ex) {
                Logger.printDebug(() -> TAG + "could not resolve " + sourceId, ex);
            }

            String proved = page;
            Utils.runOnMainThread(() -> finish(sourceId, opening, proved));
        });
        Utils.runOnMainThreadDelayed(() -> finish(sourceId, opening, null), AlbumOpeningLogic.WAIT_MS);
        return true;
    }

    private static boolean openLater(String sourceId, AlbumOpeningLogic.HeldTap opening, String pageId) {
        Utils.runOnMainThread(() -> finish(sourceId, opening, pageId));
        return true;
    }

    /** The held tap ends once: with the proved page, or with none (the wait ran out or the proof failed). */
    private static void finish(String sourceId, AlbumOpeningLogic.HeldTap opening, @Nullable String pageId) {
        if (!opening.end()) return;

        pending.remove(sourceId, opening);
        if (pageId == null) unresolved.put(sourceId, System.currentTimeMillis());
        else unresolved.remove(sourceId);

        if (!AlbumOpeningLogic.stillWanted(opening.serial(), navigations.get(), DownloadsPage.downloadsChipShown())) {
            Logger.printDebug(() -> TAG + "dropped the tap of " + sourceId + ", the user moved on");
            return;
        }

        byte[] next = pageId == null ? opening.command() : OfflineLibraryLogic.browseCommand(pageId);
        Logger.printDebug(() -> TAG + "opens " + (pageId == null ? "the saved page of " + sourceId : pageId));
        if (!OfflineReplay.dispatchCommand(next)) {
            Logger.printDebug(() -> TAG + "no dispatcher to open " + sourceId + " with");
        }
    }
}
