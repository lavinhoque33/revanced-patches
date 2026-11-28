/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.music.patches.downloads.UpstreamVerdict.Answer.INCONCLUSIVE;
import static app.morphe.extension.music.patches.downloads.UpstreamVerdict.Answer.REACHED;
import static app.morphe.extension.music.patches.downloads.UpstreamVerdict.Answer.UNREACHABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class UpstreamVerdictTest {
    private static final Object WIFI = new Object();
    private static final Object CELLULAR = new Object();

    /** One finished probe on Wi-Fi, started at {@code now}. */
    private static UpstreamVerdict.Transition probeAt(UpstreamVerdict verdict, long now, boolean alive) {
        UpstreamVerdict.Attempt attempt = verdict.begin(now, false);
        assertNotNull(attempt);
        verdict.finished(attempt);
        return verdict.settle(attempt, alive ? REACHED : UNREACHABLE, WIFI, now);
    }

    private static UpstreamVerdict.Transition failAt(UpstreamVerdict verdict, long now) {
        return probeAt(verdict, now, false);
    }

    @Test
    public void startsUnknownAndNotDead() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        assertEquals(UpstreamVerdict.State.UNKNOWN, verdict.state());
        assertFalse(verdict.dead());
    }

    @Test
    public void failedProbeMakesTheUpstreamDead() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        assertEquals(UpstreamVerdict.Change.BECAME_DEAD, failAt(verdict, 0).change());
        assertTrue(verdict.dead());
        assertEquals(WIFI, verdict.network());
    }

    @Test
    public void answeredProbeMakesTheUpstreamAlive() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        UpstreamVerdict.Attempt attempt = verdict.begin(0, false);
        assertEquals(UpstreamVerdict.Change.PROVED_ALIVE, verdict.settle(attempt, REACHED, WIFI, 40).change());
        assertEquals(UpstreamVerdict.State.ALIVE, verdict.state());
    }

    @Test
    public void timeoutIsInconclusiveWhateverTheCurrentVerdict() {
        for (var state : UpstreamVerdict.State.values()) {
            UpstreamVerdict verdict = new UpstreamVerdict();
            switch (state) {
                case DEAD -> failAt(verdict, 0);
                case ALIVE -> probeAt(verdict, 0, true);
                case UNKNOWN -> verdict.networkIs(WIFI, 0);
            }
            var attempt = verdict.begin(UpstreamVerdict.MIN_START_GAP_MS, false);
            assertEquals(UpstreamVerdict.Change.NONE, verdict.settle(attempt, INCONCLUSIVE, WIFI, 12_877).change());
            assertEquals(state, verdict.state());
            assertEquals(UpstreamVerdict.FIRST_REPROBE_MS, verdict.reprobeDelayMs());
        }
    }

    @Test
    public void slowAnswerStillProvesTheUpstreamAliveWithoutAnOutage() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        UpstreamVerdict.Attempt attempt = verdict.begin(0, false);
        assertEquals(UpstreamVerdict.Change.PROVED_ALIVE, verdict.settle(attempt, REACHED, WIFI, 12_877).change());
        assertFalse(verdict.dead());
    }

    @Test
    public void timeoutDoesNotBecomeDeadEvidenceEvenWhenWrapped() {
        assertEquals(INCONCLUSIVE, UpstreamProbe.failureAnswer(new SocketTimeoutException()));
        assertEquals(INCONCLUSIVE, UpstreamProbe.failureAnswer(new IOException(new SocketTimeoutException())));
        var connect = new ConnectException();
        connect.initCause(new SocketTimeoutException());
        assertEquals(INCONCLUSIVE, UpstreamProbe.failureAnswer(connect));
        assertEquals(UNREACHABLE, UpstreamProbe.failureAnswer(new ConnectException()));
        assertEquals(UNREACHABLE, UpstreamProbe.failureAnswer(new UnknownHostException()));
        assertEquals(INCONCLUSIVE, UpstreamProbe.failureAnswer(new IllegalStateException()));
    }

    @Test
    public void sameValidatedNetworkNotifiesRecoveryOnlyOnItsFirstAnswerAfterDead() {
        var verdict = new UpstreamVerdict();
        verdict.networkIs(WIFI, 0);
        assertEquals(UpstreamVerdict.Change.BECAME_DEAD, failAt(verdict, 0).change());
        long epoch = verdict.epoch();

        var timeout = verdict.begin(2_000, false);
        assertEquals(UpstreamVerdict.Change.NONE, verdict.settle(timeout, INCONCLUSIVE, WIFI, 7_000).change());
        verdict.finished(timeout);
        assertTrue(verdict.dead());

        var recovered = probeAt(verdict, 10_000, true);
        assertEquals(UpstreamVerdict.Change.BECAME_ALIVE, recovered.change());
        assertTrue(verdict.isCurrent(recovered));
        assertFalse(verdict.dead());
        assertEquals(UpstreamVerdict.Change.NONE, probeAt(verdict, 20_000, true).change());
        assertEquals(epoch, verdict.epoch());
    }

    @Test
    public void answeredTapCannotBeRedispatchedByTheDeadline() {
        var tap = tap(7);
        var pending = new AtomicReference<>(tap);
        assertFalse(tap.acceptAnswer(pending, 1_300, 6, "song"));
        assertTrue(tap.acceptAnswer(pending, 1_300, 7, "song"));
        assertFalse(tap.takeForReplay(pending, 1_900, 0, 7));
    }

    @Test
    public void lateStockAnswerCannotClaimTheSameTapAfterItsLocalStart() {
        var tap = tap(7);
        var pending = new AtomicReference<>(tap);
        assertFalse(tap.takeForReplay(pending, 1_900, 1, 7));
        assertFalse(tap.takeForReplay(pending, 1_900, 0, 8));
        assertTrue(tap.takeForReplay(pending, 1_900, 0, 7));

        var newer = tap(8);
        pending.set(newer);
        assertFalse(tap.acceptAnswer(pending, 2_000, 7, "song"));
        assertFalse(newer.acceptAnswer(pending, 2_000, 7, "song"));
        assertFalse(tap.takeForReplay(pending, 2_000, 0, 7));
        assertTrue(newer.acceptAnswer(pending, 2_100, 8, "song"));
    }

    private static UpstreamProbe.Tap tap(long navigation) {
        return new UpstreamProbe.Tap(new UpstreamVerdict.Start(1_000, 0, navigation, Set.of("song")),
                "test", () -> true, () -> { });
    }

    @Test
    public void anOlderProbeCannotOverrideANewerOne() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        UpstreamVerdict.Attempt older = verdict.begin(0, false);
        verdict.finished(older);
        UpstreamVerdict.Attempt newer = verdict.begin(UpstreamVerdict.MIN_START_GAP_MS, false);
        assertEquals(UpstreamVerdict.Change.NONE, verdict.settle(older, UNREACHABLE, WIFI, 2_100).change());
        assertEquals(UpstreamVerdict.State.UNKNOWN, verdict.state());
        assertEquals(UpstreamVerdict.Change.BECAME_DEAD, verdict.settle(newer, UNREACHABLE, WIFI, 2_200).change());
    }

    @Test
    public void onlyOneProbeRunsAtATimeUntilItIsStuck() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        assertNotNull(verdict.begin(0, false));
        assertNull(verdict.begin(UpstreamVerdict.MIN_START_GAP_MS, false));
        assertNull(verdict.begin(UpstreamVerdict.STUCK_MS - 1, false));
        assertNotNull(verdict.begin(UpstreamVerdict.STUCK_MS, false));
    }

    @Test
    public void twoProbesNeverStartCloserThanTheMinimumGap() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        UpstreamVerdict.Attempt first = verdict.begin(0, false);
        verdict.finished(first);
        assertNull(verdict.begin(UpstreamVerdict.MIN_START_GAP_MS - 1, false));
        assertNotNull(verdict.begin(UpstreamVerdict.MIN_START_GAP_MS, false));
    }

    @Test
    public void freshAliveVerdictSkipsAProbeOnlyWhenAsked() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        probeAt(verdict, 100, true);

        assertNull(verdict.begin(UpstreamVerdict.FRESH_MS - 1, true));
        assertNotNull(verdict.begin(100 + UpstreamVerdict.FRESH_MS, true));
    }

    @Test
    public void staleAliveVerdictIsProbedAgain() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        probeAt(verdict, 100, true);
        assertNotNull(verdict.begin(10_000, true));
    }

    @Test
    public void reprobeDelayDoublesFromTenSecondsToAMinute() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        long now = 0;
        long[] expected = {10_000, 20_000, 40_000, 60_000, 60_000};
        for (long delay : expected) {
            failAt(verdict, now);
            assertEquals(delay, verdict.reprobeDelayMs());
            now += 60_000;
        }
    }

    @Test
    public void reprobeDelayStartsOverAfterTheUpstreamAnswers() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        failAt(verdict, 0);
        failAt(verdict, 60_000);
        assertEquals(20_000, verdict.reprobeDelayMs());
        assertEquals(UpstreamVerdict.Change.BECAME_ALIVE, probeAt(verdict, 120_000, true).change());
        failAt(verdict, 180_000);
        assertEquals(UpstreamVerdict.FIRST_REPROBE_MS, verdict.reprobeDelayMs());
    }

    @Test
    public void stayingDeadIsReportedOnlyAsStayingDead() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        assertEquals(UpstreamVerdict.Change.BECAME_DEAD, failAt(verdict, 0).change());
        assertEquals(UpstreamVerdict.Change.STAYED_DEAD, failAt(verdict, 60_000).change());
    }

    @Test
    public void staleDeadTransitionAfterAnAliveProbeIsNotCurrent() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        UpstreamVerdict.Transition dead = failAt(verdict, 0);
        assertTrue(verdict.isCurrent(dead));

        UpstreamVerdict.Transition alive = probeAt(verdict, 5_000, true);
        assertEquals(UpstreamVerdict.Change.BECAME_ALIVE, alive.change());
        assertFalse(verdict.isCurrent(dead));
        assertTrue(verdict.isCurrent(alive));
    }

    @Test
    public void staleDeadTransitionAfterANetworkChangeIsNotCurrent() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        verdict.networkIs(WIFI, 0);
        UpstreamVerdict.Transition dead = failAt(verdict, 100);

        UpstreamVerdict.Transition dropped = verdict.networkIs(CELLULAR, 200);
        assertEquals(UpstreamVerdict.Change.DROPPED_DEAD, dropped.change());
        assertFalse(verdict.isCurrent(dead));
        assertTrue(verdict.isCurrent(dropped));
    }

    @Test
    public void stayingDeadKeepsTheDeadTransitionCurrent() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        UpstreamVerdict.Transition dead = failAt(verdict, 0);
        failAt(verdict, 60_000);
        assertTrue(verdict.isCurrent(dead));
    }

    @Test
    public void probeOnAnotherNetworkThanTheVerdictsSaysNothing() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        verdict.networkIs(WIFI, 0);
        UpstreamVerdict.Attempt attempt = verdict.begin(0, false);
        assertEquals(UpstreamVerdict.Change.NONE, verdict.settle(attempt, UNREACHABLE, CELLULAR, 100).change());
        assertEquals(UpstreamVerdict.State.UNKNOWN, verdict.state());
        assertEquals(WIFI, verdict.network());
    }

    @Test
    public void firstNetworkIsAdoptedWithoutAChange() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        assertEquals(UpstreamVerdict.Change.NONE, verdict.networkIs(WIFI, 0).change());
        assertEquals(WIFI, verdict.network());
        assertEquals(UpstreamVerdict.Change.NONE, verdict.networkIs(WIFI, 10).change());
    }

    @Test
    public void otherNetworkStartsOverFromUnknown() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        verdict.networkIs(WIFI, 0);
        probeAt(verdict, 50, true);

        assertEquals(UpstreamVerdict.Change.RESET, verdict.networkIs(CELLULAR, 5_000).change());
        assertEquals(UpstreamVerdict.State.UNKNOWN, verdict.state());
        assertEquals(CELLULAR, verdict.network());
        assertEquals(1, verdict.epoch());
    }

    @Test
    public void leavingANetworkWhereTheUpstreamWasDeadIsReportedAsSuch() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        verdict.networkIs(WIFI, 0);
        failAt(verdict, 100);

        assertEquals(UpstreamVerdict.Change.DROPPED_DEAD, verdict.networkIs(CELLULAR, 5_000).change());
        assertFalse(verdict.dead());
        assertNotNull(verdict.begin(5_001, false));
    }

    @Test
    public void replacementNetworkMustProveAliveAfterDroppingDead() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        failAt(verdict, 100);
        assertEquals(UpstreamVerdict.Change.DROPPED_DEAD, verdict.networkIs(CELLULAR, 200).change());
        assertEquals(UpstreamVerdict.State.UNKNOWN, verdict.state());

        UpstreamVerdict.Attempt attempt = verdict.begin(200, false);
        assertNotNull(attempt);
        assertEquals(UpstreamVerdict.Change.PROVED_ALIVE, verdict.settle(attempt, REACHED, CELLULAR, 300).change());
        assertEquals(UpstreamVerdict.State.ALIVE, verdict.state());
    }

    @Test
    public void probeOfAnOldNetworkIsDiscarded() {
        UpstreamVerdict verdict = new UpstreamVerdict();
        verdict.networkIs(WIFI, 0);
        UpstreamVerdict.Attempt attempt = verdict.begin(0, false);

        verdict.networkIs(CELLULAR, 100);
        assertEquals(UpstreamVerdict.Change.NONE, verdict.settle(attempt, UNREACHABLE, WIFI, 200).change());
        assertEquals(UpstreamVerdict.State.UNKNOWN, verdict.state());
    }

    @Test
    public void startIsAnsweredOnlyByItsOwnNavigationAndSongs() {
        UpstreamVerdict.Start start = new UpstreamVerdict.Start(1_000, 0, 7, Set.of("song", "video"));

        assertTrue(start.answeredBy(1_200, 7, "song"));
        assertTrue(start.answeredBy(1_200, 7, "video"));
        assertFalse(start.answeredBy(1_200, 6, "song"));
        assertFalse(start.answeredBy(1_200, 7, "prefetchedNext"));
        assertFalse(start.answeredBy(900, 7, "song"));
    }

    @Test
    public void unrelatedAnswerDuringAPendingPlaylistStartIsNotItsOwn() {
        UpstreamVerdict.Start start = new UpstreamVerdict.Start(1_000, 0, 7, Set.of());

        assertFalse(start.answeredBy(1_200, 6, "previousQueueSong"));
        assertTrue(start.answeredBy(1_200, 7, "firstSongOfTheList"));
        assertTrue(start.answeredBy(1_200, 7, ""));
    }

    @Test
    public void startIsRunAgainOnlyWhileYoungCurrentAndOnTheSameNetworkAndNavigation() {
        UpstreamVerdict.Start start = new UpstreamVerdict.Start(1_000, 2, 7, Set.of());

        assertTrue(start.redoable(1_000, 2, 7, true));
        assertTrue(start.redoable(1_000 + UpstreamVerdict.TAP_WINDOW_MS, 2, 7, true));
        assertFalse(start.redoable(1_001 + UpstreamVerdict.TAP_WINDOW_MS, 2, 7, true));
        assertFalse(start.redoable(999, 2, 7, true));
        assertFalse(start.redoable(1_500, 2, 7, false));
        assertFalse(start.redoable(1_500, 3, 7, true));
        assertFalse(start.redoable(1_500, 2, 8, true));
    }
}
