/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The last load of each Downloads chip view: the service that built the page, what it was asked and the callback it
 * answered to. A view in the back stack keeps the page it was given, so when the stream copies change the same load is
 * run again to give the same callback a current page. Only the newest load of each view is kept, so at most one
 * callback per chip is held; the app's own request objects are small.
 */
final class PageLoads {
    record Load(Object layout, Object service, Object request, Object options, Object callback) {}

    private final Map<Object, Load> latest = new LinkedHashMap<>();

    /** Remembers the load, replacing the earlier load of the same view. */
    synchronized void remember(Object layout, Object service, Object request, Object options, Object callback) {
        latest.remove(layout);
        latest.put(layout, new Load(layout, service, request, options, callback));
    }

    /** The loads to run again, oldest first. */
    synchronized List<Load> all() {
        return new ArrayList<>(latest.values());
    }
}
