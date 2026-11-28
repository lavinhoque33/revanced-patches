/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class PageHostsTest {
    private final Object controller = new Object();
    private final Object downloads = new Object();
    private final Object playlist = new Object();

    @Test public void onlyADeliveredRevisionCanBecomeStale() {
        PageHosts hosts = new PageHosts();
        hosts.started(controller, downloads);
        PageHosts.Load load = hosts.downloadsRequested(2);

        assertNull(hosts.startedDownloadsPage(3));
        hosts.delivered(load, 2);
        assertNull(hosts.startedDownloadsPage(2));
        assertSame(downloads, hosts.startedDownloadsPage(3));
    }

    @Test public void distinctHostsKeepTheirOwnDeliveredRevisions() {
        PageHosts hosts = new PageHosts();
        PageHosts.Load first = hosts.downloadsRequested(1);
        hosts.started(controller, downloads);
        hosts.delivered(first, 1);
        hosts.stopped(controller);
        assertNull(hosts.startedDownloadsPage(2));

        hosts.started(controller, playlist);
        hosts.delivered(hosts.downloadsRequested(2), 2);
        assertNull(hosts.startedDownloadsPage(2));
        hosts.started(controller, downloads);
        assertSame(downloads, hosts.startedDownloadsPage(2));
    }

    @Test public void anotherRequestSupersedesTheChipWithoutLosingASeparateBackStackHost() {
        PageHosts hosts = new PageHosts();
        hosts.started(controller, downloads);
        PageHosts.Load initial = hosts.downloadsRequested(1);
        PageHosts.Load second = hosts.downloadsRequested(2);
        assertFalse(hosts.isCurrent(initial));
        assertTrue(hosts.isCurrent(second));

        hosts.libraryRequested();
        assertFalse(hosts.isCurrent(second));
        assertNull(hosts.startedDownloadsPage(3));
    }

    @Test public void aFailedRefreshRetriesOnlyWhenCopiesChangedDuringTheRequest() {
        PageHosts hosts = new PageHosts();
        hosts.started(controller, downloads);
        hosts.delivered(hosts.downloadsRequested(1), 1);
        PageHosts.Load first = hosts.downloadsRequested(2);
        assertNull(hosts.startedDownloadsPage(3));

        assertTrue(hosts.failed(first, 3));
        assertFalse(hosts.failed(first, 3));
        assertSame(downloads, hosts.startedDownloadsPage(3));
        PageHosts.Load retry = hosts.downloadsRequested(3);
        assertFalse(hosts.failed(retry, 3));
        assertNull(hosts.startedDownloadsPage(3));
        assertSame(downloads, hosts.startedDownloadsPage(4));
    }

    @Test public void theChipIsShownOnlyWhileItsPageIsTheStartedOne() {
        PageHosts hosts = new PageHosts();
        assertFalse(hosts.downloadsStarted());

        hosts.started(controller, downloads);
        assertFalse(hosts.downloadsStarted());

        hosts.downloadsRequested(1);
        assertTrue(hosts.downloadsStarted());

        hosts.started(controller, playlist);
        assertFalse(hosts.downloadsStarted());

        hosts.started(controller, downloads);
        assertTrue(hosts.downloadsStarted());

        hosts.stopped(controller);
        assertFalse(hosts.downloadsStarted());
    }

    @Test public void theLibraryRequestEndsTheChip() {
        PageHosts hosts = new PageHosts();
        hosts.started(controller, downloads);
        hosts.downloadsRequested(1);

        hosts.libraryRequested();

        assertFalse(hosts.downloadsStarted());
    }
}
