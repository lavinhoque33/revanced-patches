/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.downloads.DownloadsPageLogic.Layout;
import app.morphe.extension.music.patches.downloads.LibraryLandingLogic.BrowseFailure;
import app.morphe.extension.music.patches.downloads.LibraryLandingLogic.Target;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Adds playable local copies to the plain offline Library using the app's native tab and adapter. */
@SuppressWarnings("unused")
public final class LibraryLanding {
    public interface Page {
        @Nullable
        byte[] rvxLandingCommand();

        void rvxLandingMarkNoCache();

        boolean rvxLandingTakeNoCache();
    }

    public interface Holder {
        byte[] rvxContentBytes();

        boolean rvxLandingNoCache();
    }

    public interface Adapter {
        List<?> rvxLandingContents();

        @Nullable
        byte[] rvxLandingRenderBytes(Object renderer);

        boolean rvxLandingReplace(int index, byte[] rendererBytes);

        void rvxLandingAppend(int index, byte[] rendererBytes);
    }

    public interface Host {
        boolean rvxLive();

        boolean rvxLandingSelected();

        boolean rvxLandingFromDisk();

        @Nullable
        Object rvxLandingModel();

        @Nullable
        Object rvxLandingAdapter();
    }

    public interface ChipHost extends Host {
        @Nullable ViewGroup rvxLandingHeaderContainer();

        @Nullable Object rvxLandingReadHeader(Object holder);

        @Nullable byte[] rvxLandingHeaderWire(Object holder);
        @Nullable byte[] rvxLandingObservedCommandWire(Object command);
        byte[] rvxLandingElementsWire(Object elements);
    }

    public interface ChipAdapter extends Adapter {
        void rvxLandingBindHeader(Object payload);
    }

    private static final LibraryLandingLogic.RowsCache cache = new LibraryLandingLogic.RowsCache();
    private static final LibraryLandingLogic.NativeHeaders headers = new LibraryLandingLogic.NativeHeaders();

    // The bind and publish hooks run on main. The native request/commit hook can also revoke from its own thread.
    private static final AtomicLong contentEpoch = new AtomicLong();
    private static final AtomicLong requestEpoch = new AtomicLong();
    private static long pageEpoch;
    @Nullable
    private static volatile LibraryLandingLogic.Ownership pending;

    private LibraryLanding() {}

    /** Readiness for markers, not an acknowledgement of a Downloads response. */
    static boolean hasBuilt() {
        return cache.rowsFor(DownloadsPage.copiesGeneration()) != null;
    }

    /** A newer page bind revokes the previous lease even if the app reuses the model and adapter objects. */
    public static void pageStarted() {
        if (!Utils.isCurrentlyOnMainThread()) return;
        pageEpoch++;
        pending = null;
    }

    /** A native request or content commit on the owned adapter supersedes an outstanding Library shelf. */
    public static void adapterChanging(Object adapter) {
        if (ownsPendingAdapter(adapter)) contentEpoch.incrementAndGet();
    }

    public static void reloadRequested(Object adapter) {
        if (!(adapter instanceof Adapter)) return;
        requestEpoch.incrementAndGet();
        adapterChanging(adapter);
    }

    /** Diagnostics from the native continuation observer, with no dependency on its result. */
    public static void reloadObserved(Object host, @Nullable String token, @Nullable Object nativeCommand) {
        Logger.printDebug(() -> {
            var chipHost = (ChipHost) host;
            byte[] command = nativeCommand == null ? null : chipHost.rvxLandingObservedCommandWire(nativeCommand);
            Object plain = Utils.isCurrentlyOnMainThread() ? headers.plainPayload(host) : null;
            byte[] elements = plain == null ? null : chipHost.rvxLandingElementsWire(plain);
            return "LibraryLanding: reload-origin host=" + System.identityHashCode(host)
                    + " downloads-layout=" + DownloadsPageLogic.layoutOf(token)
                    + " target=" + LibraryLandingLogic.commandTarget(command)
                    + " command-wire=" + LibraryLandingLogic.wireShape(command)
                    + " reload-wire=" + LibraryLandingLogic.reloadShape(command, elements);
        });
    }

    private static boolean ownsPendingAdapter(Object adapter) {
        LibraryLandingLogic.Ownership waiting = pending;
        return waiting != null && waiting.adapter() == adapter;
    }

    /** A hidden or destroyed Library view can no longer publish. */
    public static void hostChanging(Object host) {
        requestEpoch.incrementAndGet();
        LibraryLandingLogic.Ownership waiting = pending;
        if (waiting != null && waiting.host() == host) contentEpoch.incrementAndGet();
    }

    public static void modelChanging(Object owner, Object next) {
        if (owner instanceof Host host && host.rvxLandingModel() != next) hostChanging(owner);
    }

    public static void hostHidden(Object host, boolean hidden) {
        if (hidden) hostChanging(host);
    }

    public static void loadingHeaderStarted(Object owner) {
        if (!Utils.isCurrentlyOnMainThread() || !(owner instanceof ChipHost host)) return;
        var container = host.rvxLandingHeaderContainer();
        if (container != null) headers.expectLoading(host, container);
    }

    public static int loadingHeaderChildCount(Object container, int childCount) {
        if (childCount == 0 || !Utils.isCurrentlyOnMainThread() || !canKeepLoadingChips()) return childCount;
        Object owner = headers.loadingOwner(container);
        // Let stock build its unselected payload even if the old filter still occupies the chip container.
        return owner != null && headers.forFallback(owner, true, true) == null ? 0 : childCount;
    }

    public static boolean loadingHeaderBuilt(Object container, Object payload) {
        if (!Utils.isCurrentlyOnMainThread() || !(container instanceof ViewGroup view)
                || !(headers.loadingOwner(container) instanceof ChipHost host)) {
            return true;
        }
        if (host.rvxLandingHeaderContainer() == container) headers.rememberLoading(host, payload);
        if (!canKeepLoadingChips()) return true;

        // A warm stock header or another filter may have bound while the local Elements asset loaded.
        return host.rvxLive() && host.rvxLandingHeaderContainer() == view && view.getChildCount() == 0
                && !host.rvxLandingSelected() && host.rvxLandingModel() instanceof Page page
                && LibraryLandingLogic.isPlainCommand(page.rvxLandingCommand());
    }

    private static boolean canKeepLoadingChips() {
        if (!streamModeOffline()) return false;
        var registry = DownloadedMarkers.loadedRegistry();
        return hasOfflineRows() || registry != null && registry.hasTracks();
    }

    public static void freshContentAccepted(Object owner, Object renderer, Object holder) {
        contentAccepted(owner, renderer, holder, Bind.FRESH);
    }

    public static void savedContentAccepted(Object owner, Object renderer) {
        contentAccepted(owner, renderer, null, Bind.SAVED);
    }

    /** Stock applied a RELOAD to the existing adapter without running the fragment's fresh binder. */
    public static void reloadContentAccepted(Object renderer, Object holder) {
        if (!Utils.isCurrentlyOnMainThread() || !(headers.plainAdapterOwner(renderer) instanceof ChipHost host)
                || !host.rvxLive() || host.rvxLandingAdapter() != renderer) return;
        Logger.printDebug(() -> {
            var container = host.rvxLandingHeaderContainer();
            int childCount = container == null ? -1 : container.getChildCount();
            Object payload = host.rvxLandingReadHeader(holder);
            boolean matches = headers.matchesRetainedPlain(host, payload, true);
            return "LibraryLanding: reload-header-shape host=" + System.identityHashCode(host)
                    + " adapter=" + System.identityHashCode(renderer) + " chips=" + childCount
                    + " elements=" + (payload != null) + " equals-fresh=" + matches
                    + " wire=" + LibraryLandingLogic.wireShape(host.rvxLandingHeaderWire(holder));
        });
        contentAccepted(host, renderer, holder, Bind.RELOAD);
    }

    private enum Bind { FRESH, SAVED, RELOAD }

    private static void contentAccepted(Object owner, Object renderer, @Nullable Object holder, Bind source) {
        if (!Utils.isCurrentlyOnMainThread()) return;
        long accepted = contentEpoch.incrementAndGet();
        pending = null;
        try {
            boolean saved = source == Bind.SAVED;
            if (source != Bind.RELOAD) debug(saved ? "saved" : "fresh", owner, renderer);
            if (!(owner instanceof Host host) || !(renderer instanceof Adapter adapter)
                    || !(host.rvxLandingModel() instanceof Page page) || host.rvxLandingAdapter() != adapter) {
                debug("no-owner", owner, renderer);
                return;
            }
            boolean nativeNoCache = source == Bind.FRESH && holder instanceof Holder stock
                    && stock.rvxLandingNoCache() && page.rvxLandingTakeNoCache();
            boolean diskCache = source == Bind.FRESH && host.rvxLandingFromDisk();
            boolean offline = bindEligible(nativeNoCache, diskCache);
            boolean canRememberHeader = source == Bind.FRESH && holder != null && host instanceof ChipHost;
            if ((!offline && !canRememberHeader) || host.rvxLandingSelected()) return;
            Target target = LibraryLandingLogic.commandTarget(page.rvxLandingCommand());
            if (target == Target.OTHER) return;
            if (canRememberHeader) {
                Object stockHeader = ((ChipHost) host).rvxLandingReadHeader(holder);
                if (stockHeader != null) {
                    headers.rememberPlainResponse(host, stockHeader);
                    headers.rememberPlainAdapter(host, adapter);
                    Logger.printDebug(() -> "LibraryLanding: fresh-header-shape host=" + System.identityHashCode(host)
                            + " wire=" + LibraryLandingLogic.wireShape(((ChipHost) host).rvxLandingHeaderWire(holder)));
                }
            }
            if (!offline) return;
            // The accepted section must prove its own header; a continuation token or visible chips alone cannot.
            if (source == Bind.RELOAD) {
                var chipHost = (ChipHost) host;
                var container = chipHost.rvxLandingHeaderContainer();
                int childCount = container == null ? -1 : container.getChildCount();
                Object payload = chipHost.rvxLandingReadHeader(holder);
                if (!headers.matchesRetainedPlain(host, payload, childCount > 0)) return;
            }

            List<?> contents = adapter.rvxLandingContents();
            int size = contents.size();
            if (saved && size > 2) return;
            Object first = size == 0 ? null : contents.get(0);
            Object last = size < 2 ? null : contents.get(size - 1);
            byte[] messageBytes = first == null || size > 2 ? null : adapter.rvxLandingRenderBytes(first);
            // A retained adapter can already own our appended row after a repeat commit, even with many native rows.
            byte[] lastBytes = last == null ? null : adapter.rvxLandingRenderBytes(last);
            if (LibraryLandingLogic.alreadyPublished(messageBytes, lastBytes)) return;
            boolean empty = messageBytes != null && (size == 1
                    ? LibraryLandingLogic.isEmptyRenderer(messageBytes)
                    : LibraryLandingLogic.isEmptyPair(messageBytes, lastBytes));
            boolean placeholder = empty && (saved || holder instanceof Holder stock
                    && LibraryLandingLogic.hasPlaceholder(stock.rvxContentBytes()));
            // A restored Elements adapter can contain a selected filter even while its model is the Library.
            // Preserve it unless this is the stock placeholder; a fresh bind is tied to the actual browse response.
            if (saved && !placeholder) return;

            PlaybackConnectivity.start();
            int revision = DownloadsPage.copiesGeneration();
            var waiting = new LibraryLandingLogic.Ownership(host, page, adapter, first, last, size, placeholder,
                    pageEpoch, accepted, PlaybackConnectivity.reconnectEpoch(), revision, nativeNoCache, diskCache);
            pending = waiting;
            debug(placeholder ? "accepted-empty" : "accepted-stock-content", host, adapter);
            if (source == Bind.RELOAD) debugBind("accepted-retained-plain", host, page, adapter);
            if (source == Bind.FRESH && target == Target.ELEMENTS_PLAIN) {
                debugBind("accepted-elements-plain", host, page, adapter);
            }
            if (nativeNoCache) debugBind("native-no-cache-bind", host, page, adapter);
            else if (diskCache) debugBind("native-disk-cache-bind", host, page, adapter);
            byte[] rows = cache.rowsFor(revision);
            if (rows == null) {
                buildRows(revision);
            } else {
                // The native binder still has to finish its tab and layout parcel bookkeeping.
                Utils.runOnMainThread(() -> publish(waiting, rows));
            }
        } catch (Exception ex) {
            debug("accept-error", owner, renderer);
        }
    }

    static boolean streamModeOffline() {
        return streamModeEnabled() && !PlaybackConnectivity.osReachable();
    }

    private static boolean bindEligible(boolean nativeNoCache, boolean diskCache) {
        return streamModeEnabled() && LibraryLandingLogic.bindOffline(nativeNoCache, diskCache,
                PlaybackConnectivity.osReachable(), PlaybackConnectivity.upstreamDead());
    }

    private static boolean streamModeEnabled() {
        return OfflineReplay.enabled() && DownloadsPageLogic.mergesStreamCopies(Settings.DOWNLOAD_METHOD.get());
    }

    /** Native persistent browse only calls this after its own cache missed. */
    public static boolean canOpenWithoutCache(Object model, Throwable failure) {
        try {
            if (Utils.isCurrentlyOnMainThread() || !streamModeEnabled() || !(model instanceof Page page)
                    || !LibraryLandingLogic.isPlainCommand(page.rvxLandingCommand())) {
                return false;
            }
            BrowseFailure reason = nativeBrowseFailure(failure);
            if (reason == BrowseFailure.NONE) return false;
            long request = requestEpoch.get();
            if (!prepareOfflineRows(failure) || request != requestEpoch.get()) return false;
            // The stock single-tab envelope accepts an empty section. Insert the shelf only after that
            // adapter binds, so a late native response cannot paint rows into a different chip or session.
            page.rvxLandingMarkNoCache();
            Logger.printDebug(() -> "LibraryLanding: native-no-cache-section reason=" + reason
                    + " model=" + System.identityHashCode(model));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "LibraryLanding: no-cache section unavailable", ex);
            return false;
        }
    }

    private static BrowseFailure nativeBrowseFailure(Throwable failure) {
        return LibraryLandingLogic.browseFailure(PlaybackConnectivity.osReachable(),
                PlaybackConnectivity.upstreamDead(), failure, Thread.currentThread().isInterrupted());
    }

    static boolean prepareOfflineRows() {
        return prepareOfflineRows(null);
    }

    private static boolean prepareOfflineRows(@Nullable Throwable failure) {
        if (Utils.isCurrentlyOnMainThread() || !canPrepareRows(failure)) return false;
        PlaybackConnectivity.start();
        long epoch = PlaybackConnectivity.reconnectEpoch();
        int revision = DownloadsPage.copiesGeneration();
        byte[] rows = cache.rowsFor(revision);
        if (rows == null && cache.startBuild(revision)) rows = loadRows(revision);
        return rows != null && rows.length != 0 && revision == DownloadsPage.copiesGeneration()
                && epoch == PlaybackConnectivity.reconnectEpoch() && canPrepareRows(failure);
    }

    private static boolean canPrepareRows(@Nullable Throwable failure) {
        return failure == null ? streamModeOffline()
                : streamModeEnabled() && nativeBrowseFailure(failure) != BrowseFailure.NONE;
    }

    static boolean offlineRowsReady() {
        return streamModeOffline() && hasOfflineRows();
    }

    private static boolean hasOfflineRows() {
        byte[] rows = cache.rowsFor(DownloadsPage.copiesGeneration());
        return rows != null && rows.length != 0;
    }

    private static void buildRows(int revision) {
        if (!cache.startBuild(revision)) return;
        Utils.runOnBackgroundThread(() -> {
            byte[] rows = loadRows(revision);
            Utils.runOnMainThread(() -> finishBuild(revision, rows));
        });
    }

    @Nullable
    private static byte[] loadRows(int revision) {
        try {
            OfflineCopyStore.loadPlaybackData();
            var sections = DownloadsPage.sectionsOf(Layout.ALL, false);
            byte[] rows = sections.playable() ? LibraryLandingLogic.shelfItem(sections.items())
                    : LibraryLandingLogic.NO_ROWS;
            if (rows != null && rows.length != 0) rows = LibraryLandingLogic.markShelf(rows);
            if (rows != null && cache.publish(revision, DownloadsPage.copiesGeneration(), rows)) return rows;
        } catch (Exception ex) {
            Logger.printException(() -> "LibraryLanding: cannot build offline rows", ex);
        }
        cache.failed(revision);
        return null;
    }

    private static void finishBuild(int revision, @Nullable byte[] rows) {
        LibraryLandingLogic.Ownership waiting = pending;
        if (waiting == null || waiting.revision() != revision) return;
        if (rows == null) debug("build-rejected", waiting.host(), waiting.adapter());
        publish(waiting, rows);
    }

    private static void publish(LibraryLandingLogic.Ownership waiting, @Nullable byte[] rows) {
        if (pending != waiting) return;
        Host host = (Host) waiting.host();
        Adapter adapter = (Adapter) waiting.adapter();
        try {
            boolean offline = bindEligible(waiting.nativeNoCache(), waiting.diskCache());
            if (!host.rvxLive() || !offline || host.rvxLandingSelected()) {
                debug("no-longer-eligible", host, adapter);
                return;
            }
            List<?> contents = adapter.rvxLandingContents();
            int count = waiting.count();
            Object model = host.rvxLandingModel();
            if (contents.size() != count || !waiting.stillOwns(host, model, host.rvxLandingAdapter(),
                    count == 0 ? null : contents.get(0), count < 2 ? null : contents.get(count - 1),
                    pageEpoch, contentEpoch.get(),
                    PlaybackConnectivity.reconnectEpoch(), DownloadsPage.copiesGeneration())
                    || !LibraryLandingLogic.isPlainCommand(((Page) model).rvxLandingCommand())) {
                debug("lease-rejected", host, adapter);
                return;
            }
            if (rows == null || rows.length == 0) return;
            if (waiting.placeholder()) {
                boolean replaced = adapter.rvxLandingReplace(0, rows) && contents.get(0) != waiting.content();
                debug(replaced ? "replaced" : "native-rejected", host, adapter);
            } else {
                adapter.rvxLandingAppend(count, rows);
                debug(contents.size() == count + 1 ? "appended" : "native-rejected", host, adapter);
            }
            if (waiting.nativeNoCache() && host instanceof ChipHost chipHost && adapter instanceof ChipAdapter chips) {
                ViewGroup container = chipHost.rvxLandingHeaderContainer();
                var header = headers.forFallback(host, true, container != null && container.getChildCount() == 0);
                if (header != null) {
                    chips.rvxLandingBindHeader(header.payload());
                    if (container.getChildCount() > 0) {
                        debug(header.fromPlainResponse()
                                        ? "restored-chip-header-plain" : "restored-chip-header-loading",
                                host, adapter);
                    }
                }
            }
        } catch (Exception ex) {
            debug("publish-error", host, adapter);
        } finally {
            if (pending == waiting) pending = null;
        }
    }

    private static void debugBind(String branch, Host host, Page page, Adapter adapter) {
        Logger.printDebug(() -> "LibraryLanding: " + branch + " host=" + System.identityHashCode(host)
                + " model=" + System.identityHashCode(page) + " adapter=" + System.identityHashCode(adapter));
    }

    private static void debug(String branch, Object host, Object adapter) {
        Logger.printDebug(() -> "LibraryLanding: " + branch + " host=" + System.identityHashCode(host)
                + " adapter=" + System.identityHashCode(adapter));
    }
}
