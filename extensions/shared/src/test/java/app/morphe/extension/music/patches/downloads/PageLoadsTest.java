/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PageLoadsTest {
    private final Object service = new Object();
    private final Object request = new Object();
    private final Object options = new Object();

    @Test public void noLoadBeforeAViewWasOpened() {
        assertTrue(new PageLoads().all().isEmpty());
    }

    @Test public void aLoadKeepsWhatTheAppAskedWith() {
        PageLoads loads = new PageLoads();
        Object callback = new Object();

        loads.remember("all", service, request, options, callback);

        List<PageLoads.Load> all = loads.all();
        assertEquals(1, all.size());
        assertSame(service, all.get(0).service());
        assertSame(request, all.get(0).request());
        assertSame(options, all.get(0).options());
        assertSame(callback, all.get(0).callback());
    }

    @Test public void theNewestLoadOfAViewReplacesTheEarlierOne() {
        PageLoads loads = new PageLoads();
        Object second = new Object();

        loads.remember("all", service, request, options, new Object());
        loads.remember("all", service, request, options, second);

        List<PageLoads.Load> all = loads.all();
        assertEquals(1, all.size());
        assertSame(second, all.get(0).callback());
    }

    @Test public void eachViewKeepsItsOwnLoad() {
        PageLoads loads = new PageLoads();

        loads.remember("all", service, request, options, new Object());
        loads.remember("albums", service, request, options, new Object());

        assertEquals(2, loads.all().size());
    }
}
