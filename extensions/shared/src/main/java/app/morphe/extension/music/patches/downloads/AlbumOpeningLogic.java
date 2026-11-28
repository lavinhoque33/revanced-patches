/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The decisions of {@link AlbumOpening}: what a tap on a downloaded album in the Library's Downloads chip does while
 * online. The album page ({@code MPREb_…}) is the album's own page, with its header, Play and Shuffle; the playlist
 * page ({@code VLOLAK5uy_…}) the chip falls back to is only its rows. Pure, so the host tests pin every branch.
 */
final class AlbumOpeningLogic {
    /** How long a tap waits for the album page of an album it has none for, before the saved page opens instead. */
    static final long WAIT_MS = 8_000;
    /** How long an album whose page could not be proved goes straight to its saved page, with no wait. */
    static final long UNRESOLVED_MS = 5 * 60_000;

    enum Route {
        /** The app runs the command as it is. */
        STOCK,
        /** The command is taken and the album's page opens in its place: it is already proved and recorded. */
        OPEN_ALBUM_PAGE,
        /** The command is taken until the album's page is proved; then that page opens. */
        RESOLVE,
        /** The app runs the command and opens the saved page of the album, with no online request. */
        SAVED
    }

    record Decision(Route route, @Nullable String sourceId, @Nullable String pageId) {
        static final Decision STOCK = new Decision(Route.STOCK, null, null);
    }

    private AlbumOpeningLogic() {}

    /**
     * Decides a browse command when it is dispatched. Only the playlist page of a downloaded album, asked for by the
     * Downloads chip while online, is not left to the app: offline the saved page is read by the page itself.
     *
     * @param online            The device reaches the internet now.
     * @param downloadsChip     The Library page on screen shows the Downloads chip.
     * @param mappingLoaded     The proved album pages are known: until they are, the app's own command runs.
     * @param browseId          The page a browse command opens, or null for any other command.
     * @param isSource          Whether an id names a downloaded album or playlist.
     * @param albumPageOf       The proved album page of a downloaded album, or null when it has none.
     * @param unresolvedRecently Whether the album page of the album could not be proved a moment ago.
     */
    static Decision routeOf(boolean online, boolean downloadsChip, boolean mappingLoaded, @Nullable String browseId,
                            Predicate<String> isSource, Function<String, String> albumPageOf,
                            Predicate<String> unresolvedRecently) {
        if (!online || !downloadsChip || !mappingLoaded || browseId == null || !browseId.startsWith("VL")) {
            return Decision.STOCK;
        }

        String sourceId = OfflineReplayLogic.sourceOfBrowseId(browseId);
        if (!DownloadsPageLogic.isAlbum(sourceId) || !isSource.test(sourceId)) return Decision.STOCK;

        String page = albumPageOf.apply(sourceId);
        if (page != null) return new Decision(Route.OPEN_ALBUM_PAGE, sourceId, page);

        return new Decision(unresolvedRecently.test(sourceId) ? Route.SAVED : Route.RESOLVE, sourceId, null);
    }

    /**
     * Whether a taken tap may still open its page: nothing else was opened or started since, and the Library page
     * still shows the Downloads chip. Otherwise the user moved on while the page was being found.
     */
    static boolean stillWanted(long tapSerial, long currentSerial, boolean downloadsChip) {
        return tapSerial == currentSerial && downloadsChip;
    }

    /** Whether an album that failed at {@code failedAt} still goes straight to its saved page at {@code now}. */
    static boolean unresolvedRecently(@Nullable Long failedAt, long now) {
        return failedAt != null && now - failedAt < UNRESOLVED_MS;
    }

    /**
     * The tap a running lookup holds. A repeated tap on the same album joins the lookup instead of starting a second
     * one, and the lookup then opens for that latest tap: its serial (the last tap is the one nothing was opened
     * after) and its command.
     */
    static final class HeldTap {
        private long serial;
        private byte[] command;
        private boolean ended;

        HeldTap(long serial, byte[] command) {
            this.serial = serial;
            this.command = command;
        }

        /** Makes a later tap the one held; false when the lookup already ended, so that tap starts its own. */
        synchronized boolean adopt(long serial, byte[] command) {
            if (ended) return false;

            this.serial = serial;
            this.command = command;
            return true;
        }

        /** Ends the lookup once: true for the caller that ended it, false for every later one. */
        synchronized boolean end() {
            if (ended) return false;

            ended = true;
            return true;
        }

        synchronized long serial() {
            return serial;
        }

        synchronized byte[] command() {
            return command;
        }
    }
}
