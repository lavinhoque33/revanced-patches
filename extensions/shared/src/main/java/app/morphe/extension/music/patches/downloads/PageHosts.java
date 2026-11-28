/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Tracks the last requested chip and last delivered revision separately for each native Library host. */
final class PageHosts {
    static final class Load {
        private final long requested;
        private long rendered;
        private long failedAt = -1;
        private boolean pending = true;

        Load(long requested, @Nullable Load previous) {
            this.requested = requested;
            rendered = previous == null ? -1 : previous.rendered;
        }

        boolean needsRefresh(long revision) {
            return !pending && Math.max(rendered, failedAt) < revision;
        }
    }

    private final WeakHashMap<Object, Load> downloadsPages = new WeakHashMap<>();
    private WeakReference<Object> startedController = new WeakReference<>(null);
    private WeakReference<Object> startedHost = new WeakReference<>(null);
    private Load beforeStart;

    synchronized void started(Object controller, Object host) {
        startedController = new WeakReference<>(controller);
        startedHost = new WeakReference<>(host);
        if (beforeStart != null) {
            downloadsPages.put(host, beforeStart);
            beforeStart = null;
        }
    }

    synchronized void stopped(Object controller) {
        if (startedController.get() != controller) return;

        startedController = new WeakReference<>(null);
        startedHost = new WeakReference<>(null);
    }

    synchronized Load downloadsRequested(long revision) {
        Object host = startedHost.get();
        Load previous = host == null ? beforeStart : downloadsPages.get(host);
        Load load = new Load(revision, previous);
        if (host == null) beforeStart = load;
        else downloadsPages.put(host, load);
        return load;
    }

    synchronized void libraryRequested() {
        Object host = startedHost.get();
        if (host == null) beforeStart = null;
        else downloadsPages.remove(host);
    }

    /** Whether the Library page that is started shows the Downloads chip: a tap now is a tap on one of its rows. */
    synchronized boolean downloadsStarted() {
        Object host = startedHost.get();
        return host != null && downloadsPages.containsKey(host);
    }

    synchronized boolean isCurrent(Load load) {
        return beforeStart == load || downloadsPages.containsValue(load);
    }

    synchronized boolean failed(Load load, long revision) {
        if (!load.pending || !isCurrent(load)) return false;
        load.pending = false;
        load.failedAt = load.requested;
        return load.needsRefresh(revision);
    }

    synchronized void delivered(Load load, long revision) {
        load.rendered = revision;
        load.pending = false;
    }

    @Nullable
    synchronized Object startedDownloadsPage(long revision) {
        Object host = startedHost.get();
        Load load = downloadsPages.get(host);
        return load != null && load.needsRefresh(revision) ? host : null;
    }
}
