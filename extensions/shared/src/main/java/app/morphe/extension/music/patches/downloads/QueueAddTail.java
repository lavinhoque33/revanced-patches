/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.patches.jam.JamUI;
import app.morphe.extension.music.patches.manualqueue.ManualQueue;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Keeps a connected "Add to queue" song at the end of Up Next.
 * <p>
 * Stock appends the Add to the loaded main lane, so the next Up Next page lands behind it, and a server Shuffle toggle
 * replaces the whole lane and loses it. Each added occurrence, the exact native row object, is tracked on its own:
 * <ul>
 * <li>the Add commits: its rows are the exact objects the isolated lookup returned;</li>
 * <li>a main-lane page lands right behind it: the occurrence moves behind that page, once in its lifetime;</li>
 * <li>the server Shuffle or Unshuffle reply replaces the lane: the occurrence is appended again.</li>
 * </ul>
 * The native lane move, the lane removal and the current-row publisher report every other edit. An occurrence the user
 * moved or removed, or that became the playing row, is forgotten for good. Another queue insertion (Play next, a
 * playlist or offline Add), Jam, Cast, going offline or a new queue model ends all tracking. With Add to queue in tap
 * order, Music's placement stays and only the Shuffle restore applies.
 */
@SuppressWarnings("unused") // Native bytecode hooks.
public final class QueueAddTail {
    private static final int MAIN_LANE = 0;
    private static final int RADIO_LANE = 1;
    private static final int MAX_PENDING = 32;
    private static final int MAX_LOGGED = 4;
    private static final String NEXT_ARM = "NEXT";

    /** The native queue model. Every method runs under the model monitor held by the caller. */
    public interface Model {
        int rvxTailSize(int lane);
        Object rvxTailItem(int lane, int index);
        int rvxTailCurrent();
        void rvxTailMove(int from, int to);
        void rvxTailAppend(List<?> rows);
        @Nullable List<Object> rvxTailBaseline();
    }

    /** Live conditions, replaceable in tests. */
    interface Environment {
        boolean jamOwnsQueue();
        boolean fifoOwnsPlacement();
        boolean reachable();
    }

    private static final Environment LIVE = new Environment() {
        @Override public boolean jamOwnsQueue() {
            return Settings.JAM_ENABLED.get() && JamUI.ownsQueue();
        }

        @Override public boolean fifoOwnsPlacement() {
            return ManualQueue.enabled();
        }

        @Override public boolean reachable() {
            return PlaybackConnectivity.reachable();
        }
    };

    private record Added(Model model, List<Object> rows) {}

    /** One added row object. Its page move is spent once, whatever happens to the queue afterwards. */
    private static final class Occurrence {
        final boolean tapOrder;
        boolean pageMoved;

        Occurrence(boolean tapOrder) {
            this.tapOrder = tapOrder;
        }
    }

    /** The added occurrences of one queue model, keyed by row identity. Fields change only under {@link #LOCK}. */
    private static final class Tracked {
        final WeakReference<Model> model;
        final WeakReference<OfflineQueueActions.Owner> owner;
        final Map<Object, Occurrence> occurrences = new IdentityHashMap<>();
        @Nullable List<Object> snapshot;

        Tracked(Model model, OfflineQueueActions.Owner owner) {
            this.model = new WeakReference<>(model);
            this.owner = new WeakReference<>(owner);
        }
    }

    /**
     * Guards {@link #PENDING} and every {@link Tracked} field. It is taken inside the model monitor and native queue
     * methods are never called while holding it.
     */
    private static final Object LOCK = new Object();
    private static final Map<Object, Object> PENDING = new IdentityHashMap<>();
    private static final ThreadLocal<Boolean> NEXT_PAGE = new ThreadLocal<>();
    /** Set while this class moves or appends rows, so the lane hooks do not take them for user edits. */
    private static final ThreadLocal<Boolean> OWN_EDIT = new ThreadLocal<>();
    /** The model whose server Shuffle reply is replacing the lanes on this thread. */
    private static final ThreadLocal<Model> SHUFFLING = new ThreadLocal<>();
    @Nullable private static volatile Tracked tracked;
    /** How many times playback went offline. An Add prepared before the latest edge is never tracked. */
    private static volatile int offlineEdges;

    private QueueAddTail() {}

    /** The offline-edge count to hand back to {@link #pending} when the Add's lookup completes. */
    static int connectivityEpoch() {
        return offlineEdges;
    }

    /**
     * The playback connectivity's own online-to-offline transition, after its network debounce. Everything recorded
     * before it is stale: pending rows and tracked occurrences are forgotten and never re-armed by a reconnect.
     */
    static void wentOffline() {
        boolean held;
        synchronized (LOCK) {
            offlineEdges++;
            held = tracked != null || !PENDING.isEmpty();
            PENDING.clear();
            tracked = null;
        }
        if (held) Logger.printDebug(() -> "QueueAddTail: stopped tracking, playback went offline");
    }

    /**
     * The isolated lookup returned these native rows; Music inserts the same objects when it commits the Add.
     * [epoch] is {@link #connectivityEpoch()} from when the Add was prepared.
     */
    public static void pending(@Nullable Object model, @Nullable List<?> rows, int epoch) {
        if (!(model instanceof Model)) {
            Logger.printDebug(() -> "QueueAddTail: pending ignored, " + describe(model) + " is not a queue model");
            return;
        }
        if (rows == null || rows.isEmpty() || rows.size() > MAX_PENDING) {
            Logger.printDebug(() -> "QueueAddTail: pending ignored, " + (rows == null ? 0 : rows.size()) + " row(s)");
            return;
        }

        synchronized (LOCK) {
            if (epoch != offlineEdges) {
                Logger.printDebug(() -> "QueueAddTail: pending ignored, playback went offline during the Add");
                return;
            }
            if (PENDING.size() + rows.size() > MAX_PENDING) PENDING.clear();
            for (Object row : rows) {
                if (row != null) PENDING.put(row, model);
            }
        }
        Logger.printDebug(() -> "QueueAddTail: pending " + rows.size() + " row(s) " + describeAll(rows) + " for "
                + describe(model));
    }

    /** Called after Music committed a queue reply. Only replies of the isolated Add carry pending rows. */
    public static void committed(@Nullable Object manager, @Nullable List<?> items) {
        committed(manager, items, LIVE);
    }

    static void committed(@Nullable Object manager, @Nullable List<?> items, Environment live) {
        Added added = takePending(items);
        if (added == null) {
            Logger.printDebug(() -> "QueueAddTail: commit of " + (items == null ? 0 : items.size()) + " item(s) "
                    + (items == null ? "[]" : describeAll(items)) + " matches no pending row " + pendingSummary());
            if (items != null && !items.isEmpty()) dropAll("another queue insertion committed");
            return;
        }

        try {
            track(manager, added, live);
        } catch (Exception | LinkageError failure) {
            failed(added.model(), "commit", failure);
        }
    }

    /** Before the page's cursor is saved: remembers whether the page continues the main Up Next list. */
    public static void pageArm(@Nullable Object model, @Nullable Object arm) {
        if (tracked == null) return;

        NEXT_PAGE.set(arm instanceof Enum<?> value && NEXT_ARM.equals(value.name()));
    }

    /** After the page rows were inserted into [lane] at [index]. */
    public static void pageAppended(@Nullable Object model, int lane, int index, @Nullable Object rows) {
        pageAppended(model, lane, index, rows, LIVE);
    }

    static void pageAppended(@Nullable Object model, int lane, int index, @Nullable Object rows, Environment live) {
        Boolean next = NEXT_PAGE.get();
        if (next == null) return;

        NEXT_PAGE.remove();
        if (!(model instanceof Model queue) || !(rows instanceof Collection<?> page)) {
            Logger.printDebug(() -> "QueueAddTail: page ignored, model " + describe(model)
                    + ", rows " + describe(rows));
            return;
        }
        if (!next || lane != MAIN_LANE) {
            Logger.printDebug(() -> "QueueAddTail: page ignored, " + (next ? "lane " + lane : "the arm is not NEXT"));
            return;
        }
        Tracked state = trackedFor(queue);
        if (state == null) {
            Logger.printDebug(() -> "QueueAddTail: page ignored, the queue is not tracked");
            return;
        }

        try {
            movePast(queue, state, index, page.size(), live);
        } catch (Exception | LinkageError failure) {
            failed(queue, "page move", failure);
        }
    }

    /** Entry of the model method that applies a server Shuffle or Unshuffle reply. */
    public static void shuffleBegin(@Nullable Object model) {
        shuffleBegin(model, LIVE);
    }

    static void shuffleBegin(@Nullable Object model, Environment live) {
        SHUFFLING.remove();
        if (!(model instanceof Model queue)) return;

        Tracked state = trackedFor(queue);
        if (state == null) return;

        try {
            synchronized (queue) {
                if (!stillValid(queue, state, live)) return;

                // Only occurrences still ahead of the playing row are restored; the rest are history now.
                List<Object> ahead = presentRows(queue, trackedRows(state));
                int forgotten = retainOnly(state, ahead);
                if (ahead.isEmpty()) {
                    drop(queue, "no added row is ahead of the playing row");
                    return;
                }
                synchronized (LOCK) {
                    state.snapshot = ahead;
                }
                SHUFFLING.set(queue);
                Logger.printDebug(() -> "QueueAddTail: shuffle snapshot " + ahead.size() + " row(s)"
                        + (forgotten > 0 ? ", " + forgotten + " behind the playing row forgotten" : ""));
            }
        } catch (Exception | LinkageError failure) {
            failed(queue, "shuffle snapshot", failure);
        }
    }

    /** Exit of that method, after the reply replaced the lanes. */
    public static void shuffleEnd(@Nullable Object model) {
        shuffleEnd(model, LIVE);
    }

    static void shuffleEnd(@Nullable Object model, Environment live) {
        SHUFFLING.remove();
        if (!(model instanceof Model queue)) return;

        Tracked state = trackedFor(queue);
        if (state == null) return;

        try {
            synchronized (queue) {
                restore(queue, state, live);
            }
        } catch (Exception | LinkageError failure) {
            failed(queue, "shuffle restore", failure);
        }
    }

    /** Entry of the native lane move: a drag, a local shuffle or Jam. Any of them ends the moved occurrence. */
    public static void moving(@Nullable Object model, int fromLane, int from, int toLane, int to) {
        if (tracked == null || OWN_EDIT.get() != null || fromLane == toLane && from == to) return;
        if (!(model instanceof Model queue)) return;

        Tracked state = trackedFor(queue);
        if (state == null) return;

        try {
            synchronized (queue) {
                if (from < 0 || from >= queue.rvxTailSize(fromLane)) return;

                forget(state, Collections.singletonList(queue.rvxTailItem(fromLane, from)), "moved in Up Next");
            }
        } catch (Exception | LinkageError failure) {
            failed(queue, "move check", failure);
        }
    }

    /**
     * Entry of the native lane removal: the user's remove, a lane clear for a new queue, or a reply replacing rows.
     * Inside a server Shuffle reply the removal is expected and the snapshot restores the occurrences afterwards.
     */
    public static void removing(@Nullable Object model, int lane, int index, int count) {
        if (tracked == null || count <= 0 || OWN_EDIT.get() != null) return;
        if (!(model instanceof Model queue) || SHUFFLING.get() == queue) return;

        Tracked state = trackedFor(queue);
        if (state == null) return;

        try {
            synchronized (queue) {
                int end = Math.min(index + count, queue.rvxTailSize(lane));
                List<Object> removed = new ArrayList<>();
                for (int position = Math.max(index, 0); position < end; position++) {
                    removed.add(queue.rvxTailItem(lane, position));
                }
                forget(state, removed, "removed from Up Next");
            }
        } catch (Exception | LinkageError failure) {
            failed(queue, "removal check", failure);
        }
    }

    /** The native current-row publisher: an added occurrence that becomes the playing row has been played. */
    public static void published(@Nullable Object model, @Nullable Object current) {
        if (tracked == null || current == null || !(model instanceof Model queue)) return;

        Tracked state = trackedFor(queue);
        if (state != null) forget(state, List.of(current), "played");
    }

    static void reset() {
        synchronized (LOCK) {
            PENDING.clear();
            tracked = null;
        }
        NEXT_PAGE.remove();
        OWN_EDIT.remove();
        SHUFFLING.remove();
    }

    @Nullable
    private static Added takePending(@Nullable List<?> items) {
        if (items == null || items.isEmpty()) return null;

        synchronized (LOCK) {
            if (PENDING.isEmpty()) return null;

            Model model = null;
            List<Object> rows = new ArrayList<>();
            for (Object item : items) {
                if (PENDING.remove(item) instanceof Model owner) {
                    model = owner;
                    rows.add(item);
                }
            }
            return model == null ? null : new Added(model, rows);
        }
    }

    private static void track(@Nullable Object manager, Added added, Environment live) {
        Model model = added.model();
        if (!(manager instanceof OfflineQueueActions.Owner owner)) {
            Logger.printDebug(() -> "QueueAddTail: commit ignored, " + describe(manager) + " is not a queue owner");
            return;
        }
        Object current = owner.rvxOfflineQueue();
        if (current != model) {
            Logger.printDebug(() -> "QueueAddTail: commit ignored, the owner holds " + describe(current)
                    + " but the Add targeted " + describe(model));
            return;
        }

        synchronized (model) {
            String blocker = live.jamOwnsQueue() ? "Jam owns the queue"
                    : !live.reachable() ? "playback is offline" : null;
            if (blocker != null) {
                Logger.printDebug(() -> "QueueAddTail: not tracking, " + blocker);
                drop(model, blocker);
                return;
            }
            List<Object> placed = presentRows(model, added.rows());
            if (placed.isEmpty()) {
                Logger.printDebug(() -> "QueueAddTail: the added row is not in the Up Next list (size "
                        + model.rvxTailSize(MAIN_LANE) + ", current " + model.rvxTailCurrent() + "), not tracking");
                return;
            }

            // Earlier occurrences of the same queue keep their own state; a new Add never re-arms their page move.
            boolean tapOrder = live.fifoOwnsPlacement();
            int size = model.rvxTailSize(MAIN_LANE);
            int total;
            synchronized (LOCK) {
                Tracked state = tracked;
                if (state == null || state.model.get() != model || state.owner.get() != owner) {
                    state = new Tracked(model, owner);
                    tracked = state;
                }
                for (Object row : placed) state.occurrences.put(row, new Occurrence(tapOrder));
                total = state.occurrences.size();
            }
            Logger.printDebug(() -> "QueueAddTail: tracking " + placed.size() + " row(s) (" + total + " in total), "
                    + (tapOrder ? "tap-order placement" : "end placement") + ", Up Next size " + size);
        }
    }

    private static void movePast(Model model, Tracked state, int index, int pageSize, Environment live) {
        synchronized (model) {
            if (!stillValid(model, state, live)) return;
            if (snapshotOf(state) != null) {
                Logger.printDebug(() -> "QueueAddTail: page ignored, inside a Shuffle change");
                return;
            }

            int size = model.rvxTailSize(MAIN_LANE);
            if (pageSize == 0 || index + pageSize != size) {
                Logger.printDebug(() -> "QueueAddTail: page ignored, it does not end the main lane (index " + index
                        + ", page " + pageSize + ", size " + size + ")");
                return;
            }

            List<Object> run = tailRun(model, trackedRows(state), index, model.rvxTailCurrent());
            List<Object> eligible = unmoved(state, run);
            if (eligible.isEmpty()) {
                Logger.printDebug(() -> "QueueAddTail: page ignored, none of the " + run.size()
                        + " added row(s) in front of it may move");
                return;
            }

            moveToEnd(model, eligible);
            mirrorInBaseline(model, eligible);
            Logger.printDebug(() -> "QueueAddTail: moved " + eligible.size() + " row(s) behind a page of " + pageSize
                    + " row(s)" + (eligible.size() < run.size() ? ", " + (run.size() - eligible.size())
                    + " already moved or in tap order stay" : ""));
        }
    }

    private static void restore(Model model, Tracked state, Environment live) {
        List<Object> snapshot;
        synchronized (LOCK) {
            snapshot = state.snapshot;
            state.snapshot = null;
        }
        if (snapshot == null || !stillValid(model, state, live)) return;

        // An occurrence forgotten during the reply (played or edited by another path) is not brought back.
        Set<Object> wanted = identitySet(trackedRows(state));
        Set<Object> kept = identitySet(List.of());
        collectRows(model, MAIN_LANE, snapshot, kept);
        collectRows(model, RADIO_LANE, snapshot, kept);
        List<Object> missing = new ArrayList<>();
        for (Object row : snapshot) {
            if (wanted.contains(row) && !kept.contains(row)) missing.add(row);
        }
        if (!missing.isEmpty()) ownEdit(() -> model.rvxTailAppend(missing));

        List<Object> ahead = presentRows(model, snapshot);
        retainOnly(state, ahead);
        if (ahead.isEmpty()) {
            drop(model, "the added rows did not survive the Shuffle change");
            return;
        }
        Logger.printDebug(() -> "QueueAddTail: shuffle restored " + missing.size() + " of " + snapshot.size()
                + " row(s)");
    }

    /** The jam, connectivity and owner conditions every decision needs; a failed one drops the tracking. */
    private static boolean stillValid(Model model, Tracked state, Environment live) {
        OfflineQueueActions.Owner owner = state.owner.get();
        String reason = live.jamOwnsQueue() ? "Jam owns the queue"
                : !live.reachable() ? "the device is offline"
                : owner == null || owner.rvxOfflineQueue() != model ? "the local queue changed owner or is cast"
                : null;
        if (reason == null) return true;

        drop(model, reason);
        return false;
    }

    /** The rows still in the main lane after the current song, in list order. */
    private static List<Object> presentRows(Model model, Collection<Object> rows) {
        Set<Object> wanted = identitySet(rows);
        List<Object> present = new ArrayList<>();
        int size = model.rvxTailSize(MAIN_LANE);
        for (int index = Math.max(model.rvxTailCurrent() + 1, 0); index < size; index++) {
            Object row = model.rvxTailItem(MAIN_LANE, index);
            if (wanted.contains(row)) present.add(row);
        }
        return present;
    }

    /** The tracked rows that fill the lane just before [end], all after the current song, in list order. */
    private static List<Object> tailRun(Model model, Collection<Object> rows, int end, int current) {
        Set<Object> wanted = identitySet(rows);
        List<Object> run = new ArrayList<>();
        for (int index = end - 1; index > current && index < model.rvxTailSize(MAIN_LANE); index--) {
            Object row = model.rvxTailItem(MAIN_LANE, index);
            if (!wanted.contains(row)) break;
            run.add(0, row);
        }
        return run;
    }

    /** Moves [rows] to the end of the main lane in their order and spends their page move. */
    private static void moveToEnd(Model model, List<Object> rows) {
        int last = model.rvxTailSize(MAIN_LANE) - 1;
        for (Object row : rows) {
            int from = indexOf(model, row);
            if (from >= 0) ownEdit(() -> model.rvxTailMove(from, last));
        }
        synchronized (LOCK) {
            Tracked state = tracked;
            if (state == null) return;
            for (Object row : rows) {
                Occurrence occurrence = state.occurrences.get(row);
                if (occurrence != null) occurrence.pageMoved = true;
            }
        }
    }

    private static int indexOf(Model model, Object row) {
        int size = model.rvxTailSize(MAIN_LANE);
        for (int index = 0; index < size; index++) {
            if (model.rvxTailItem(MAIN_LANE, index) == row) return index;
        }
        return -1;
    }

    private static void ownEdit(Runnable edit) {
        OWN_EDIT.set(Boolean.TRUE);
        try {
            edit.run();
        } finally {
            OWN_EDIT.remove();
        }
    }

    private static void collectRows(Model model, int lane, List<Object> rows, Set<Object> into) {
        Set<Object> wanted = identitySet(rows);
        int size = model.rvxTailSize(lane);
        for (int index = 0; index < size; index++) {
            Object row = model.rvxTailItem(lane, index);
            if (wanted.contains(row)) into.add(row);
        }
    }

    /** Keeps the local-shuffle baseline in the same order as the main lane, so a local unshuffle agrees. */
    private static void mirrorInBaseline(Model model, List<Object> run) {
        List<Object> baseline = model.rvxTailBaseline();
        if (baseline == null) return;

        List<Object> carried = new ArrayList<>();
        for (Object row : run) {
            if (removeIdentity(baseline, row)) carried.add(row);
        }
        baseline.addAll(carried);
    }

    private static boolean removeIdentity(List<Object> list, Object row) {
        for (int index = list.size() - 1; index >= 0; index--) {
            if (list.get(index) == row) {
                list.remove(index);
                return true;
            }
        }
        return false;
    }

    private static Set<Object> identitySet(Collection<Object> rows) {
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>());
        set.addAll(rows);
        return set;
    }

    @Nullable
    private static Tracked trackedFor(Model model) {
        Tracked state = tracked;
        return state != null && state.model.get() == model ? state : null;
    }

    private static List<Object> trackedRows(Tracked state) {
        synchronized (LOCK) {
            return new ArrayList<>(state.occurrences.keySet());
        }
    }

    @Nullable
    private static List<Object> snapshotOf(Tracked state) {
        synchronized (LOCK) {
            return state.snapshot;
        }
    }

    /** The rows of [run] whose page move is still unspent and whose placement is not tap order. */
    private static List<Object> unmoved(Tracked state, List<Object> run) {
        List<Object> eligible = new ArrayList<>();
        synchronized (LOCK) {
            for (Object row : run) {
                Occurrence occurrence = state.occurrences.get(row);
                if (occurrence != null && !occurrence.tapOrder && !occurrence.pageMoved) eligible.add(row);
            }
        }
        return eligible;
    }

    /** Forgets every tracked occurrence not in [rows]; returns how many were forgotten. */
    private static int retainOnly(Tracked state, List<Object> rows) {
        Set<Object> keep = identitySet(rows);
        synchronized (LOCK) {
            int before = state.occurrences.size();
            state.occurrences.keySet().retainAll(keep);
            return before - state.occurrences.size();
        }
    }

    /** Forgets the tracked occurrences among [rows] for good; the tracking ends with the last one. */
    private static void forget(Tracked state, List<Object> rows, String reason) {
        int forgotten = 0;
        boolean ended;
        synchronized (LOCK) {
            for (Object row : rows) {
                if (state.occurrences.remove(row) != null) forgotten++;
            }
            ended = state.occurrences.isEmpty() && tracked == state;
            if (ended) tracked = null;
        }
        if (forgotten == 0) return;

        int count = forgotten;
        Logger.printDebug(() -> "QueueAddTail: forgot " + count + " added row(s), " + reason
                + (ended ? "; nothing left to track" : ""));
    }

    private static void drop(Model model, String reason) {
        synchronized (LOCK) {
            Tracked state = tracked;
            if (state == null || state.model.get() != model) return;

            tracked = null;
        }
        Logger.printDebug(() -> "QueueAddTail: stopped tracking, " + reason);
    }

    private static void dropAll(String reason) {
        synchronized (LOCK) {
            if (tracked == null) return;

            tracked = null;
        }
        Logger.printDebug(() -> "QueueAddTail: stopped tracking, " + reason);
    }

    /** A hook never throws into native code; the reason is logged even when nothing was tracked yet. */
    private static void failed(Model model, String stage, Throwable failure) {
        Logger.printDebug(() -> "QueueAddTail: " + stage + " failed: " + failure);
        drop(model, stage + " failed");
    }

    private static String pendingSummary() {
        synchronized (LOCK) {
            return PENDING.isEmpty() ? "(nothing pending)" : "(pending " + describeAll(PENDING.keySet()) + ")";
        }
    }

    /** Class and identity of up to {@link #MAX_LOGGED} objects, enough to tell a copy from the original row. */
    private static String describeAll(Collection<?> objects) {
        StringBuilder text = new StringBuilder("[");
        int shown = 0;
        for (Object object : objects) {
            if (shown == MAX_LOGGED) {
                text.append(", ...");
                break;
            }
            if (shown++ > 0) text.append(", ");
            text.append(describe(object));
        }
        return text.append(']').toString();
    }

    private static String describe(@Nullable Object object) {
        if (object == null) return "null";

        return object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object));
    }
}
