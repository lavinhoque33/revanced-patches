/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QueueAddTailTest {
    private enum Arm { NEXT, NEXT_RADIO, PREVIOUS }

    /** Native rows compare by identity; two rows can describe the same song. */
    private static final class Row {
        final String name;

        Row(String name) { this.name = name; }

        @Override public String toString() { return name; }
    }

    private record Env(boolean jam, boolean fifo, boolean reachable) implements QueueAddTail.Environment {
        Env(boolean jam, boolean fifo) { this(jam, fifo, true); }

        @Override public boolean jamOwnsQueue() { return jam; }
        @Override public boolean fifoOwnsPlacement() { return fifo; }
        @Override public boolean reachable() { return reachable; }
    }

    /**
     * The two lanes of the native queue model, with the model's own baseline bookkeeping on every insert. Its move,
     * removal and current-row changes call the same hooks the patched lane base and publisher call.
     */
    private static final class FakeModel implements QueueAddTail.Model, OfflineQueueActions.Owner {
        final List<Object> main = new ArrayList<>();
        final List<Object> radio = new ArrayList<>();
        @Nullable List<Object> baseline;
        @Nullable Object holds = this;
        int current;
        int moves;
        boolean failing;

        FakeModel(int current, Row... rows) {
            this.current = current;
            main.addAll(Arrays.asList(rows));
        }

        List<Object> lane(int lane) { return lane == 0 ? main : radio; }

        void stockInsert(List<?> rows) {
            if (baseline != null) baseline.addAll(rows);
            main.addAll(rows);
        }

        /** The native lane move: a drag in Up Next. */
        void move(int from, int to) {
            QueueAddTail.moving(this, 0, from, 0, to);
            main.add(to, main.remove(from));
        }

        /** The native lane removal: a remove in Up Next, or a clear for a new queue. */
        void remove(int index, int count) {
            QueueAddTail.removing(this, 0, index, count);
            main.subList(index, index + count).clear();
        }

        /** The native current-row publisher. */
        void play(int index) {
            current = index;
            QueueAddTail.published(this, main.get(index));
        }

        /** The server Shuffle reply: the lane is cleared through the native removal, then refilled. */
        void shuffleReply(Row... rows) {
            QueueAddTail.shuffleBegin(this, STOCK);
            remove(0, main.size());
            main.addAll(Arrays.asList(rows));
            current = 0;
            QueueAddTail.shuffleEnd(this, STOCK);
        }

        @Override public int rvxTailSize(int lane) {
            if (failing) throw new IllegalStateException("native queue failure");
            return lane(lane).size();
        }

        @Override public Object rvxTailItem(int lane, int index) { return lane(lane).get(index); }
        @Override public int rvxTailCurrent() { return current; }

        @Override public void rvxTailMove(int from, int to) {
            moves++;
            move(from, to);
        }

        @Override public void rvxTailAppend(List<?> rows) { stockInsert(rows); }
        @Override public List<Object> rvxTailBaseline() { return baseline; }
        @Override public Object rvxOfflineQueue() { return holds; }
    }

    private static final Env STOCK = new Env(false, false);

    @Before public void resetState() {
        QueueAddTail.reset();
    }

    private static Row row(String name) { return new Row(name); }

    private static List<String> names(List<?> rows) {
        return rows.stream().map(Object::toString).toList();
    }

    /** The lookup of an Add prepared in the current connectivity epoch completed with [rows]. */
    private static void pending(Object model, List<?> rows) {
        QueueAddTail.pending(model, rows, QueueAddTail.connectivityEpoch());
    }

    /** The isolated lookup returned [added]; Music then inserted the same rows at the end and committed. */
    private static void add(FakeModel model, Env env, Row... added) {
        List<Row> rows = Arrays.asList(added);
        pending(model, rows);
        model.stockInsert(rows);
        QueueAddTail.committed(model, rows, env);
    }

    private static void page(FakeModel model, Env env, Arm arm, int lane, Row... rows) {
        List<Row> page = Arrays.asList(rows);
        QueueAddTail.pageArm(model, arm);
        int index = model.lane(lane).size();
        if (lane == 0) model.stockInsert(page);
        else model.radio.addAll(page);
        QueueAddTail.pageAppended(model, lane, index, page, env);
    }

    private static void page(FakeModel model, Row... rows) {
        page(model, STOCK, Arm.NEXT, 0, rows);
    }

    @Test public void addedRowMovesBehindTheNextPageAndKeepsTheBaselineInOrder() {
        Row current = row("c"), a = row("a");
        FakeModel model = new FakeModel(0, current, row("s1"));
        model.baseline = new ArrayList<>(model.main);

        add(model, STOCK, a);
        page(model, row("p1"), row("p2"));

        assertEquals(List.of("c", "s1", "p1", "p2", "a"), names(model.main));
        assertSame(a, model.main.get(4));
        assertEquals(List.of("c", "s1", "p1", "p2", "a"), names(model.baseline));
        assertEquals(1, model.moves);
    }

    @Test public void theMoveHappensOncePerAddAndANewAddStartsAnotherPlacement() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        page(model, row("p1"));
        page(model, row("p2"));

        assertEquals(List.of("c", "p1", "a", "p2"), names(model.main));
        assertEquals(1, model.moves);

        add(model, STOCK, row("b"));
        page(model, row("p3"));

        // The first Add already moved once and stays; only the new Add moves behind the new page.
        assertEquals(List.of("c", "p1", "a", "p2", "p3", "b"), names(model.main));
    }

    @Test public void twoAddsBeforeOnePageMoveTogetherInOrder() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        add(model, STOCK, row("b"));
        page(model, row("p1"), row("p2"), row("p3"));

        assertEquals(List.of("c", "p1", "p2", "p3", "a", "b"), names(model.main));
        assertEquals(2, model.moves);
    }

    @Test public void radioArmRadioLaneAndPreviousPagesNeverMoveTheAdd() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));

        page(model, STOCK, Arm.NEXT_RADIO, 0, row("r1"));
        page(model, STOCK, Arm.NEXT, 1, row("r2"));
        page(model, STOCK, Arm.PREVIOUS, 0, row("r3"));

        assertEquals(List.of("c", "a", "r1", "r3"), names(model.main));
        assertEquals(0, model.moves);
    }

    @Test public void aNextPageWithoutAnArmingHookIsIgnored() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        model.stockInsert(List.of(row("p1")));

        QueueAddTail.pageAppended(model, 0, 2, List.of(row("p1")), STOCK);

        assertEquals(List.of("c", "a", "p1"), names(model.main));
    }

    @Test public void aDraggedRowIsLeftAloneWhileOtherAddedRowsStillMove() {
        Row dragged = row("a");
        FakeModel model = new FakeModel(0, row("c"), row("s1"));
        add(model, STOCK, dragged);
        add(model, STOCK, row("b"));
        model.move(2, 1);

        page(model, row("p1"));

        assertEquals(List.of("c", "a", "s1", "p1", "b"), names(model.main));
        assertSame(dragged, model.main.get(1));
    }

    @Test public void aRemovedOrPlayedRowIsNeverMoved() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        model.remove(1, 1);
        page(model, row("p1"));
        assertEquals(List.of("c", "p1"), names(model.main));
        assertEquals(0, model.moves);

        FakeModel played = new FakeModel(0, row("c"));
        add(played, STOCK, row("a"));
        played.play(1);
        page(played, row("p1"));
        assertEquals(List.of("c", "a", "p1"), names(played.main));
    }

    @Test public void aPageThatDoesNotEndTheMainLaneIsNotMovedBehind() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));

        QueueAddTail.pageArm(model, Arm.NEXT);
        model.stockInsert(List.of(row("p1"), row("p2")));
        QueueAddTail.pageAppended(model, 0, 1, List.of(row("p1")), STOCK);

        assertEquals(List.of("c", "a", "p1", "p2"), names(model.main));
    }

    @Test public void aServerShuffleChangeAppendsTheAddedRowAgainAtTheEnd() {
        Row a = row("a");
        FakeModel model = new FakeModel(0, row("c"), row("s1"));
        add(model, STOCK, a);

        QueueAddTail.shuffleBegin(model, STOCK);
        model.main.clear();
        model.main.addAll(List.of(row("n0"), row("n1"), row("n2")));
        QueueAddTail.shuffleEnd(model, STOCK);

        assertEquals(List.of("n0", "n1", "n2", "a"), names(model.main));
        assertSame(a, model.main.get(3));

        // It survives the toggle back as well, and still moves behind the next page once.
        QueueAddTail.shuffleBegin(model, STOCK);
        model.main.clear();
        model.main.addAll(List.of(row("o0"), row("o1")));
        QueueAddTail.shuffleEnd(model, STOCK);
        page(model, row("p1"));

        assertEquals(List.of("o0", "o1", "p1", "a"), names(model.main));
    }

    @Test public void aShuffleChangeNeverDuplicatesRowsTheReplyKept() {
        Row a = row("a"), b = row("b");
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, a);
        add(model, STOCK, b);

        QueueAddTail.shuffleBegin(model, STOCK);
        model.main.clear();
        model.main.addAll(List.of(row("n0"), a));
        model.radio.add(b);
        QueueAddTail.shuffleEnd(model, STOCK);

        assertEquals(List.of("n0", "a"), names(model.main));
        assertEquals(List.of("b"), names(model.radio));
    }

    @Test public void aShuffleReplyThatKeepsTheLaneAppendsNothing() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));

        QueueAddTail.shuffleBegin(model, STOCK);
        QueueAddTail.shuffleEnd(model, STOCK);

        assertEquals(List.of("c", "a"), names(model.main));
    }

    @Test public void aRowRemovedOrPlayedBeforeTheShuffleIsNotBroughtBack() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        model.main.remove(1);
        QueueAddTail.shuffleBegin(model, STOCK);
        model.main.clear();
        model.main.add(row("n0"));
        QueueAddTail.shuffleEnd(model, STOCK);
        assertEquals(List.of("n0"), names(model.main));

        FakeModel played = new FakeModel(0, row("c"));
        add(played, STOCK, row("a"));
        played.current = 1;
        QueueAddTail.shuffleBegin(played, STOCK);
        played.main.clear();
        played.main.add(row("n0"));
        QueueAddTail.shuffleEnd(played, STOCK);
        assertEquals(List.of("n0"), names(played.main));
    }

    @Test public void aNewStartThatNeverReachedTheShuffleMethodRestoresNothing() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));

        model.main.clear();
        model.main.addAll(List.of(row("n0"), row("n1")));
        QueueAddTail.shuffleBegin(model, STOCK);
        QueueAddTail.shuffleEnd(model, STOCK);
        page(model, row("p1"));

        assertEquals(List.of("n0", "n1", "p1"), names(model.main));
    }

    @Test public void aPageThatRepliesInsideTheShuffleChangeDoesNotMoveBeforeTheRestore() {
        Row a = row("a");
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, a);

        QueueAddTail.shuffleBegin(model, STOCK);
        model.main.clear();
        model.main.add(row("n0"));
        page(model, row("p1"));
        QueueAddTail.shuffleEnd(model, STOCK);

        assertEquals(List.of("n0", "p1", "a"), names(model.main));
    }

    @Test public void tapOrderPlacementKeepsMusicsPositionButStillSurvivesAShuffleChange() {
        Env tapOrder = new Env(false, true);
        Row a = row("a");
        FakeModel model = new FakeModel(0, row("c"), row("s1"), row("s2"));
        pending(model, List.of(a));
        model.main.add(1, a);
        QueueAddTail.committed(model, List.of(a), tapOrder);

        page(model, tapOrder, Arm.NEXT, 0, row("p1"));
        assertEquals(List.of("c", "a", "s1", "s2", "p1"), names(model.main));
        assertEquals(0, model.moves);

        QueueAddTail.shuffleBegin(model, tapOrder);
        model.main.clear();
        model.main.addAll(List.of(row("n0"), row("n1")));
        QueueAddTail.shuffleEnd(model, tapOrder);
        assertEquals(List.of("n0", "n1", "a"), names(model.main));
    }

    @Test public void rowsNotFromTheIsolatedLookupAreNeverTracked() {
        FakeModel model = new FakeModel(0, row("c"));
        Row foreign = row("playlist");
        model.stockInsert(List.of(foreign));
        QueueAddTail.committed(model, List.of(foreign), STOCK);
        page(model, row("p1"));

        assertEquals(List.of("c", "playlist", "p1"), names(model.main));

        // Rows recorded for one queue model are not claimed by a commit on another.
        FakeModel other = new FakeModel(0, row("c"));
        Row mine = row("a");
        pending(model, List.of(mine));
        other.stockInsert(List.of(mine));
        QueueAddTail.committed(other, List.of(mine), STOCK);
        page(other, row("p1"));
        assertEquals(List.of("c", "a", "p1"), names(other.main));
    }

    @Test public void aCommitFromAnotherOwnerOrWithoutTheRowInTheQueueIsIgnored() {
        FakeModel model = new FakeModel(0, row("c"));
        Row missing = row("a");
        pending(model, List.of(missing));
        QueueAddTail.committed(model, List.of(missing), STOCK);
        page(model, row("p1"));
        assertEquals(List.of("c", "p1"), names(model.main));

        Row stray = row("b");
        pending(model, List.of(stray));
        model.stockInsert(List.of(stray));
        QueueAddTail.committed(new Object(), List.of(stray), STOCK);
        page(model, row("p2"));
        assertEquals(List.of("c", "p1", "b", "p2"), names(model.main));
    }

    @Test public void jamOwnershipStopsEveryStep() {
        Env jam = new Env(true, false);

        FakeModel unadded = new FakeModel(0, row("c"));
        add(unadded, jam, row("a"));
        page(unadded, row("p1"));
        assertEquals(List.of("c", "a", "p1"), names(unadded.main));

        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        page(model, jam, Arm.NEXT, 0, row("p1"));
        assertEquals(List.of("c", "a", "p1"), names(model.main));

        FakeModel shuffled = new FakeModel(0, row("c"));
        add(shuffled, STOCK, row("a"));
        QueueAddTail.shuffleBegin(shuffled, jam);
        shuffled.main.clear();
        QueueAddTail.shuffleEnd(shuffled, STOCK);
        assertTrue(shuffled.main.isEmpty());
    }

    @Test public void aFailingNativeQueueDropsTheStateInsteadOfThrowing() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        model.failing = true;

        page(model, row("p1"));
        model.failing = false;
        page(model, row("p2"));

        assertEquals(List.of("c", "a", "p1", "p2"), names(model.main));
    }

    @Test public void recordedRowsAreBounded() {
        FakeModel model = new FakeModel(0, row("c"));
        Row evicted = row("evicted");
        pending(model, List.of(evicted));
        for (int count = 0; count < 40; count++) pending(model, List.of(row("f" + count)));
        List<Row> oversized = new ArrayList<>();
        for (int count = 0; count < 100; count++) oversized.add(row("o" + count));
        pending(model, oversized);

        model.stockInsert(List.of(evicted));
        QueueAddTail.committed(model, List.of(evicted), STOCK);
        page(model, row("p1"));
        assertEquals(List.of("c", "evicted", "p1"), names(model.main));

        add(model, STOCK, row("late"));
        page(model, row("p2"));
        assertEquals(List.of("c", "evicted", "p1", "p2", "late"), names(model.main));
    }

    @Test public void theBaselineOnlyCarriesRowsItAlreadyHeld() {
        Row a = row("a");
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, a);
        model.baseline = new ArrayList<>(List.of(row("c")));

        page(model, row("p1"));

        assertEquals(List.of("c", "p1"), names(model.baseline));
        assertEquals(List.of("c", "p1", "a"), names(model.main));
    }

    @Test public void aMovedOccurrenceSurvivesAShuffleChangeButNeverMovesBehindASecondPage() {
        Row a = row("a");
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, a);
        page(model, row("p1"));
        assertEquals(List.of("c", "p1", "a"), names(model.main));

        // The own move went through the native move hook and was not taken for a drag.
        model.shuffleReply(row("n0"), row("n1"));
        assertEquals(List.of("n0", "n1", "a"), names(model.main));

        page(model, row("p2"));
        assertEquals(List.of("n0", "n1", "a", "p2"), names(model.main));
        assertSame(a, model.main.get(2));
        assertEquals(1, model.moves);
    }

    @Test public void aLaterAddNeverReArmsAnOccurrenceThatAlreadyMoved() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        page(model, row("p1"));
        add(model, STOCK, row("b"));
        page(model, row("p2"));

        assertEquals(List.of("c", "p1", "a", "p2", "b"), names(model.main));
        assertEquals(2, model.moves);
    }

    @Test public void aDraggedOccurrenceIsNotRestoredByAShuffleChange() {
        FakeModel model = new FakeModel(0, row("c"), row("s1"));
        add(model, STOCK, row("a"));
        add(model, STOCK, row("b"));
        model.move(2, 1);

        model.shuffleReply(row("n0"));

        assertEquals(List.of("n0", "b"), names(model.main));
    }

    @Test public void aPlayedOccurrenceStaysForgottenWhenPlaybackGoesBack() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        add(model, STOCK, row("b"));
        model.play(1);
        model.play(0);

        model.shuffleReply(row("n0"));

        assertEquals(List.of("n0", "b"), names(model.main));
    }

    @Test public void aRemovedOccurrenceBroughtBackByUndoIsNotTrackedAgain() {
        Row a = row("a");
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, a);
        model.remove(1, 1);
        model.main.add(1, a);

        page(model, row("p1"));
        assertEquals(List.of("c", "a", "p1"), names(model.main));

        model.shuffleReply(row("n0"));
        assertEquals(List.of("n0"), names(model.main));
    }

    @Test public void twoAddsOfTheSameSongAreSeparateOccurrences() {
        Row first = row("a"), second = row("a");
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, first);
        add(model, STOCK, second);
        model.play(1);

        model.shuffleReply(row("n0"));

        assertEquals(List.of("n0", "a"), names(model.main));
        assertSame(second, model.main.get(1));
    }

    @Test public void anotherInsertionSuchAsPlayNextEndsTheTracking() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));
        Row next = row("next");
        model.main.add(1, next);
        QueueAddTail.committed(model, List.of(next), STOCK);

        model.shuffleReply(row("n0"));

        assertEquals(List.of("n0"), names(model.main));
    }

    @Test public void aLaneClearOutsideAShuffleReplyEndsTheTrackingEvenIfTheRowReturns() {
        Row a = row("a");
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, a);
        model.remove(0, 2);
        model.main.addAll(List.of(row("c2"), a));

        page(model, row("p1"));
        assertEquals(List.of("c2", "a", "p1"), names(model.main));
        assertEquals(0, model.moves);
    }

    @Test public void castOfflineOrAnotherOwnerEndsTheTracking() {
        FakeModel cast = new FakeModel(0, row("c"));
        add(cast, STOCK, row("a"));
        cast.holds = null;
        cast.shuffleReply(row("n0"));
        assertEquals(List.of("n0"), names(cast.main));

        FakeModel offline = new FakeModel(0, row("c"));
        add(offline, STOCK, row("a"));
        page(offline, new Env(false, false, false), Arm.NEXT, 0, row("p1"));
        page(offline, row("p2"));
        assertEquals(List.of("c", "a", "p1", "p2"), names(offline.main));
        assertEquals(0, offline.moves);
    }

    @Test public void anOfflineEdgeForgetsTheAddAndAReconnectNeverReArmsIt() {
        FakeModel model = new FakeModel(0, row("c"));
        add(model, STOCK, row("a"));

        // Offline, then online again with no page or Shuffle change in between.
        QueueAddTail.wentOffline();

        page(model, row("p1"));
        assertEquals(List.of("c", "a", "p1"), names(model.main));
        assertEquals(0, model.moves);
        model.shuffleReply(row("n0"));
        assertEquals(List.of("n0"), names(model.main));
    }

    @Test public void anAddWhoseLookupOrCommitCrossesAnOfflineEdgeIsNeverTracked() {
        FakeModel model = new FakeModel(0, row("c"));
        Row late = row("late"), recorded = row("recorded");

        // Prepared before the edge, its lookup completed after it.
        int preparedIn = QueueAddTail.connectivityEpoch();
        QueueAddTail.wentOffline();
        QueueAddTail.pending(model, List.of(late), preparedIn);
        model.stockInsert(List.of(late));
        QueueAddTail.committed(model, List.of(late), STOCK);

        // Recorded before the edge, committed after it.
        pending(model, List.of(recorded));
        QueueAddTail.wentOffline();
        model.stockInsert(List.of(recorded));
        QueueAddTail.committed(model, List.of(recorded), STOCK);

        // Committed while playback is already offline.
        add(model, new Env(false, false, false), row("offline"));

        page(model, row("p1"));
        assertEquals(List.of("c", "late", "recorded", "offline", "p1"), names(model.main));
        assertEquals(0, model.moves);
    }
}
