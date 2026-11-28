/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-14.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.chromium.net.NetworkException;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class PlayerFallbackLogicTest {
    @Test
    public void onlineNeverConsultsLocalCopies() {
        assertEquals(PlayerFallbackLogic.Route.NETWORK,
                PlayerFallbackLogic.decide(true, true, "song", false, List.of(), -1, id -> {
                    throw new AssertionError("online request must stay stock");
                }).route());
    }

    @Test
    public void disabledNeverIntercepts() {
        assertEquals(PlayerFallbackLogic.Route.NETWORK,
                PlayerFallbackLogic.decide(false, false, "song", false, List.of(), -1, id -> {
                    throw new AssertionError("disabled request must stay stock");
                }).route());
    }

    @Test
    public void cachedOfflineRequestIsAnsweredLocally() {
        assertEquals(PlayerFallbackLogic.Route.LOCAL,
                PlayerFallbackLogic.decide(true, false, "saved", false, List.of(), -1, "saved"::equals).route());
    }

    @Test
    public void explicitUncachedSelectionIsUnavailableNotAnotherSong() {
        assertEquals(PlayerFallbackLogic.Route.UNAVAILABLE,
                PlayerFallbackLogic.decide(true, false, "missing", true,
                        List.of("missing", "saved"), 0, "saved"::equals).route());
    }

    @Test
    public void advanceSkipsToTheNextCachedItemInNativeOrder() {
        PlayerFallbackLogic.Decision decision = PlayerFallbackLogic.decide(true, false, "missing", false,
                List.of("current", "missing", "missing2", "saved", "later"), 1, "saved"::equals);
        assertEquals(PlayerFallbackLogic.Route.SKIP, decision.route());
        assertEquals(3, decision.nextIndex());
    }

    @Test
    public void noCopiesStopsAfterOneTraversalEvenWithRepeatedIds() {
        AtomicInteger visits = new AtomicInteger();
        List<String> queue = List.of("missing", "missing", "missing", "missing");
        assertEquals(-1, PlayerFallbackLogic.nextCached(queue, 0, queue.size(), id -> {
            visits.incrementAndGet();
            return false;
        }));
        assertEquals(queue.size() - 1, visits.get());
    }

    @Test
    public void emptySingletonAndUnknownQueuesDoNotLoop() {
        assertEquals(-1, PlayerFallbackLogic.nextCached(List.of(), -1, 0, id -> true));
        assertEquals(-1, PlayerFallbackLogic.nextCached(List.of("missing"), 0, 1, id -> true));
        assertEquals(-1, PlayerFallbackLogic.nextCached(List.of("saved"), -1, 1, id -> true));
    }

    @Test
    public void connectivityIsReadForEachRequestNotLatched() {
        assertEquals(PlayerFallbackLogic.Route.LOCAL,
                PlayerFallbackLogic.decide(true, false, "saved", false, List.of(), -1, id -> true).route());
        assertEquals(PlayerFallbackLogic.Route.NETWORK,
                PlayerFallbackLogic.decide(true, true, "saved", false, List.of(), -1, id -> true).route());
        assertTrue(PlayerFallbackLogic.nextCached(List.of("saved", "missing"), 1, 2, "saved"::equals) == -1);
    }

    @Test
    public void failedRequestRecoveryKeepsItsOriginalBudgetAfterQueueChanges() {
        List<String> queue = List.of("failed", "missing", "new missing", "saved");
        AtomicInteger visits = new AtomicInteger();
        assertEquals(-1, PlayerFallbackLogic.nextCached(queue, 0, 2, id -> {
            visits.incrementAndGet();
            return "saved".equals(id);
        }));
        assertEquals(1, visits.get());
        assertEquals(3, PlayerFallbackLogic.nextCached(queue, 1, 3, "saved"::equals));
    }

    @Test
    public void cronetNetworkErrorIsAFailureOfTheWayToYouTube() {
        assertTrue(PlayerFallbackLogic.isCronetNetworkFailure(new NetworkException("net::ERR_NAME_NOT_RESOLVED"),
                false));
        assertTrue(PlayerFallbackLogic.isCronetNetworkFailure(new NetworkException.Quic("quic handshake"), false));
    }

    @Test
    public void cronetNetworkErrorIsFoundAnywhereInTheCauseChain() {
        Throwable wrapped = new ExecutionException(new IOException("player request failed",
                new NetworkException("net::ERR_CONNECTION_REFUSED")));
        assertTrue(PlayerFallbackLogic.isCronetNetworkFailure(wrapped, false));
    }

    @Test
    public void otherFailuresAreNotNetworkFailures() {
        assertFalse(PlayerFallbackLogic.isCronetNetworkFailure(new IOException("HTTP 403"), false));
        assertFalse(PlayerFallbackLogic.isCronetNetworkFailure(new IllegalStateException("parse"), false));
        assertFalse(PlayerFallbackLogic.isCronetNetworkFailure(null, false));
    }

    @Test
    public void cancellationAndInterruptionAreNeverNetworkFailures() {
        NetworkException network = new NetworkException("net::ERR_ABORTED");
        assertFalse(PlayerFallbackLogic.isCronetNetworkFailure(new CancellationException(), false));
        assertFalse(PlayerFallbackLogic.isCronetNetworkFailure(
                new ExecutionException(new InterruptedException()), false));
        assertFalse(PlayerFallbackLogic.isCronetNetworkFailure(
                new CancellationException("cancelled while waiting").initCause(network), false));
        assertFalse(PlayerFallbackLogic.isCronetNetworkFailure(network, true));
    }
}
