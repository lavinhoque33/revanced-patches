/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-14.
 * Written by lavinhoque33, 2026-11-14.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.content.Intent;
import android.util.Pair;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Per-request replay, with native queue navigation and cancellation ownership retained by Music. */
@SuppressWarnings("unused")
public final class PlayerFallback {
    public interface Request {
        String rvxVideoId();
        Object rvxPlayerResponse(byte[] bytes);
        void rvxCancelPlayer(Object callback);
    }

    public interface Navigation {
        Enum<?> rvxKind();
        Object rvxRecovery();
        void rvxRecovery(Object token);
    }

    public interface Navigator {
        List<?> rvxItems();
        String rvxVideoId(Object item);
        long rvxItemId(Object item);
        int rvxCurrent();
        Object rvxPlayback();
        void rvxCancelAndStop();
        boolean rvxOwnsRequest(Object request);
        void rvxCancelLoad();
        Object rvxJump(Object item, boolean restart);
        void rvxDispatch(Object command);
    }

    /** Only installed where the success callbacks and post-cursor commit are both known. */
    public interface WatchNavigator extends Navigator {
        @Nullable WatchRunner rvxCurrentWatchRunner();
        @Nullable Object rvxCurrentWatchQueue();
    }

    public interface WatchRunner {
        Object rvxWatchRequest();
        @Nullable Object rvxWatchReply();
    }

    public interface WatchRequestCopy {
        @Nullable Object rvxWatchRequestSource();
        void rvxWatchRequestSource(Object source);
    }

    public interface StoredPlayerResponse {
        @Nullable Object rvxStoredPlayerOwner();
        void rvxStoredPlayerOwner(@Nullable Object owner);
    }

    public interface PlaySource {
        Object rvxPlayNavigator();
    }

    public interface Packed {
        Object rvxRecover(Object request);
    }

    public interface QueueRow {
        Object rvxQueueRenderer();
        Object rvxParseArtworkRenderer(byte[] renderer);
        void rvxApplyArtworkRenderer(Object expected, Object updated);
    }

    private static final class Route {
        final WeakReference<Object> request;
        final long generation;
        final boolean offline;
        volatile String localSeed;

        Route(Object request, long generation, boolean offline) {
            this.request = new WeakReference<>(request);
            this.generation = generation;
            this.offline = offline;
        }
    }

    private static final List<Route> routes = new ArrayList<>();
    private static final long LOCAL_DEADLINE_MS = 1_100;

    record Selection(Navigator owner, long itemId, String videoId, boolean advancing, int remaining,
                     ExhaustedPlayback.Occurrence previous, long navigationGeneration,
                     @Nullable OfflineQueueActions.Selection editedQueue) { }

    record WatchOccurrence(Navigator owner, OfflineQueueActions.Queue queue, Object row,
                           Object request, long generation, String videoId, @Nullable WatchKey retained) { }

    record WatchKey(WatchJoin turn, WatchRunner runner, Object request) {
        boolean matches(@Nullable WatchKey other) {
            return other != null && turn == other.turn && runner == other.runner
                    && request == other.request;
        }
    }

    private record InitialSavedPlayer(Object request, long generation) { }

    /** A new sequence has a native runner before it has an indexed navigation selection. */
    private record InitialWatchKey(WatchNavigator owner, WatchRunner runner, Object request, long generation) {
        boolean matches(@Nullable InitialWatchKey other) {
            return other != null && owner == other.owner && runner == other.runner
                    && request == other.request && generation == other.generation;
        }
    }

    private record InitialWatchApply(InitialWatchKey key, OfflineWatchMetadata.Host host,
                                     OfflineWatchMetadata.Reply reply, WatchOccurrence occurrence) { }

    /** One selected row. All witness state is confined to this selection's native queue monitor. */
    static final class WatchJoin {
        final Selection selected;
        final OfflineQueueActions.Queue queue;
        final Object row;
        @Nullable private WatchKey owner;
        private boolean delivered;
        @Nullable private Object committedRequest;
        @Nullable private WatchRunner committedRunner;
        @Nullable private WatchOccurrence joined;
        @Nullable private OfflineWatchMetadata.Reply reply;

        WatchJoin(Selection selected, OfflineQueueActions.Queue queue, Object row) {
            this.selected = selected;
            this.queue = queue;
            this.row = row;
        }

        private void observe(WatchKey key) {
            if (key.matches(owner)) return;

            owner = key;
            delivered = false;
            joined = null;
            reply = null;
        }

        boolean acceptPlayer(WatchRunner actual, @Nullable WatchRunner retired, @Nullable Object proof) {
            if (actual == retired || !(proof instanceof WatchKey saved) || saved.turn() != this
                    || saved.runner() != actual || saved.request() != watchRequestOrigin(actual.rvxWatchRequest())) {
                return false;
            }

            observe(saved);
            delivered = true;
            join(saved.runner());
            return true;
        }

        String commit(Object actualQueue, Object actualRow, @Nullable Object payloadRequest,
                      @Nullable WatchRunner active, @Nullable WatchRunner retired) {
            if (actualQueue != queue || actualRow != row) return "wrong-queue-row";
            if (!OfflineQueueActions.currentReplayOccurrence(queue, row)) return "wrong-cursor-row";

            Object request = watchRequestOrigin(payloadRequest);
            if (request == null) return "missing-commit-request";
            if (committedRequest != null && committedRequest != request) return "conflicting-commit-request";

            // Stock can move the cursor before assigning its runner. Keep the result's exact request, not the
            // previous runner or a guess from the queue index, and join when that request actually wins playback.
            committedRequest = request;
            if (active == null) return "pending-null-runner";
            if (active == retired) return "pending-retired-runner";
            if (request != watchRequestOrigin(active.rvxWatchRequest())) return "pending-request-origin-mismatch";
            if (committedRunner != null && committedRunner != active) return "replaced-commit-runner";

            committedRunner = active;
            join(active);
            return joined == null ? "accepted-awaiting-delivery" : "accepted-joined";
        }

        private void join(WatchRunner active) {
            WatchKey key = owner;
            if (joined != null || !delivered || key == null || key.runner() != active
                    || committedRequest != key.request()
                    || committedRunner != null && committedRunner != active) return;

            committedRunner = active;
            joined = new WatchOccurrence(selected.owner(), queue, row, key.request(),
                    selected.navigationGeneration(), selected.videoId(), key);
        }

        String deliveryReason() {
            if (joined != null) return "accepted-joined";
            if (committedRequest == null) return "accepted-awaiting-commit";
            if (owner == null || owner.request() != committedRequest) return "accepted-request-origin-mismatch";
            return "accepted-replaced-commit-runner";
        }

        void attach(WatchKey key, OfflineWatchMetadata.Reply published) {
            observe(key);
            reply = published;
        }

        @Nullable WatchOccurrence ready(WatchRunner active) {
            return active == committedRunner ? joined : null;
        }

        @Nullable OfflineWatchMetadata.Reply reply(WatchKey key) {
            return key.matches(owner) ? reply : null;
        }
    }

    private record Recovery(Navigator owner, int index, long itemId, String videoId, Selection previous, int remaining,
                            boolean resume, long generation) { }

    private record QueueTap(Navigator owner, OfflineQueueActions.Selection selected, long navigationGeneration) { }

    private static volatile Selection selection;
    @Nullable private static volatile WatchJoin watchJoin;
    @Nullable private static volatile InitialWatchKey initialWatchDelivery;
    @Nullable private static volatile InitialWatchApply initialWatchApply;
    private static final Object UNOWNED_SAVED_PLAYER = new Object();
    private static volatile WeakReference<WatchRunner> retiredWatchRunner = new WeakReference<>(null);
    private static volatile Navigator navigator;
    @Nullable private static volatile OnlinePlaylist onlinePlaylist;
    private static final ExhaustedPlayback EXHAUSTED = new ExhaustedPlayback();
    private static volatile long navigationGeneration;

    /** A loaded page remains covered even if the user later removes its last occurrence. */
    private static final class OnlinePlaylist {
        final String id;
        volatile WeakReference<Object> queue = new WeakReference<>(null);
        final TreeMap<Integer, String> loaded = new TreeMap<>();
        boolean ambiguous;
        boolean tailAdded;

        OnlinePlaylist(String id) {
            this.id = id;
        }
    }
    private PlayerFallback() { }

    /** Share the admission decision across effective request copies and both native producer lanes. */
    public static boolean routeOffline(Object request) {
        if (!(request instanceof Request replay) || !OfflineReplay.enabled()) return false;

        return routedOffline(route(request), replay);
    }

    private static boolean routedOffline(Route route, Request replay) {
        return route.offline || route.generation == navigationGeneration && replay.rvxVideoId().equals(route.localSeed);
    }

    /**
     * Injection point: whether the app's player lane for the request is its offline lane. The app's own flag (the
     * request the stock code marks offline-only) is one input; the route of the extension is the other. Every place
     * of the app that asks gets the same answer for a request.
     */
    public static boolean offlineLane(Object request, boolean stockOfflineOnly) {
        if (!(request instanceof Request replay) || !OfflineReplay.enabled()) return stockOfflineOnly;

        Route route = route(request);
        return stockOfflineOnly || routedOffline(route, replay);
    }

    private static Route route(Object request) {
        request = PlaybackRequestIdentity.original(request);
        synchronized (routes) {
            Route previous = existingRoute(request);
            if (previous != null) return previous;

            Route created = createRoute(request);
            routes.add(created);
            return created;
        }
    }

    /** The caller holds the routes monitor; {@code request} is an original. */
    @Nullable
    private static Route existingRoute(Object request) {
        for (int index = routes.size() - 1; index >= 0; index--) {
            Route previous = routes.get(index);
            Object origin = previous.request.get();
            if (origin == null) routes.remove(index);
            else if (request == origin) return previous;
        }
        return null;
    }

    /** Tells the upstream probe once a player answer, online or from a copy, completes the request's future. */
    private static void notePublishedWhenDone(Object request, ListenableFuture<?> result) {
        result.addListener(() -> {
            if (!result.isCancelled() && doneValue(result) != null) notePublished(request);
        }, Runnable::run);
    }

    /**
     * A player answer, online or from a copy, was handed to the app for the request: tells the upstream probe,
     * with the navigation the request belongs to. A request never routed is counted to the current navigation:
     * that can only keep a start from being run again, never cause it.
     */
    static void notePublished(Object request) {
        if (!(request instanceof Request replay)) return;

        Route route;
        synchronized (routes) {
            route = existingRoute(PlaybackRequestIdentity.original(request));
        }
        UpstreamProbe.notePublished(route == null ? navigationGeneration : route.generation, replay.rvxVideoId());
    }

    /** The tap already won the one-shot replay claim; cancel only the native load admitted by its navigation. */
    static void cancelUnansweredLoad(long generation) {
        Navigator owner = navigator;
        if (owner == null || generation != navigationGeneration) return;

        Object active = null;
        synchronized (routes) {
            for (int index = routes.size() - 1; index >= 0; index--) {
                Route route = routes.get(index);
                Object request = route.request.get();
                if (route.generation == generation && request != null && owner.rvxOwnsRequest(request)) {
                    active = request;
                    break;
                }
            }
        }
        if (active != null && owner == navigator && generation == navigationGeneration
                && owner.rvxOwnsRequest(active)) owner.rvxCancelLoad();
    }

    @Nullable
    private static Object doneValue(ListenableFuture<?> result) {
        try {
            return result.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | RuntimeException failed) {
            return null;
        }
    }

    /** The lane of a request is decided here, once, when its identity is first seen. */
    private static Route createRoute(Object request) {
        boolean online = PlaybackConnectivity.reachableFor(request);
        if (online && request instanceof Request held && OfflineReplay.mayHold(held.rvxVideoId())) {
            UpstreamProbe.probeUnlessFresh("route");
        }
        boolean offline = OfflineReplay.isLocalStart(request) || !online;
        return new Route(request, navigationGeneration, offline);
    }

    static boolean currentRoute(Object request) {
        return route(request).generation == navigationGeneration;
    }

    /** The playlist-only player continuation consumes the seed of the winning local Watch Next reply. */
    public static void seedLocalPlayer(Object request, Object reply) {
        if (!(request instanceof Request original) || !(reply instanceof OfflineWatchMetadata.Reply watch)) return;

        String seed = localSeedOf(original.rvxVideoId(), request, watch.rvxLocalWatchRequest(),
                watch.rvxMetadataVideoId());
        if (seed == null) return;

        Route route = route(request);
        if (route.generation == navigationGeneration) route.localSeed = seed;
    }

    @Nullable
    static String localSeedOf(String requested, Object request, @Nullable Object replyOwner, @Nullable String seed) {
        return requested.isEmpty() && seed != null && !seed.isEmpty()
                && PlaybackRequestIdentity.same(replyOwner, request) ? seed : null;
    }

    /** Native preparation can start a separate Onesie load before the ordinary response callback arrives. */
    public static boolean skipOnesiePreparation(Object request) {
        return routeOffline(request) && OfflineReplay.mayHold(((Request) request).rvxVideoId());
    }

    public static void captureNavigator(Object owner) {
        if (!(owner instanceof Navigator nativeOwner) || nativeOwner == navigator) return;

        // A cold start can admit Watch Next before instantiating the first navigator. That is the same
        // navigation, not a replacement: retiring its generation cancels the only seed for a header Play.
        boolean replacing = navigator != null;
        navigator = nativeOwner;
        if (replacing) {
            selection = null;
            EXHAUSTED.clear();
            navigationGeneration++;
            invalidateWatch();
        }
    }

    @Nullable
    static Object localQueue() {
        Navigator owner = navigator;
        return owner instanceof OfflineQueueActions.Owner access ? access.rvxOfflineQueue() : null;
    }

    @Nullable
    static WatchOccurrence watchOccurrence(OfflineWatchMetadata.Host host, Object request, String videoId,
                                           OfflineWatchMetadata.Reply reply) {
        Navigator owner = navigator;
        if (owner instanceof WatchNavigator modern) {
            return initialWatchOccurrence(modern, host, request, videoId, reply);
        }

        long generation = navigationGeneration;
        if (!(owner instanceof OfflineQueueActions.Owner access)
                || !(access.rvxOfflineQueue() instanceof OfflineQueueActions.Queue queue)) {
            OfflineWatchMetadata.witness("queue-apply", "wrong-queue");
            return null;
        }
        WatchOccurrence occurrence = null;
        String reason;
        synchronized (queue) {
            int index = queue.rvxOfflineCursor();
            Object row = index < 0 || index >= queue.rvxOfflineSize() ? null : queue.rvxOfflineItem(index);
            if (row == null || !videoId.equals(queue.rvxOfflineVideoId(row))) {
                reason = "wrong-cursor-row";
            } else {
                Object origin = PlaybackRequestIdentity.original(request);
                WatchOccurrence candidate = new WatchOccurrence(owner, queue, row, origin, generation, videoId, null);
                occurrence = ownsWatchOccurrence(candidate) ? candidate : null;
                reason = occurrence == null ? "wrong-runner" : "accepted";
            }
        }
        OfflineWatchMetadata.witness("queue-apply", reason);
        return occurrence;
    }

    /** The initial Watch reply is owned by the runner, not by the metadata transport's receiver. */
    @Nullable
    private static WatchOccurrence initialWatchOccurrence(
            WatchNavigator owner, OfflineWatchMetadata.Host host, Object request, String videoId,
            OfflineWatchMetadata.Reply reply) {
        if (!(host instanceof OfflineQueueActions.Queue queue)) {
            OfflineWatchMetadata.witness("queue-apply", "unsupported-initial-host");
            return null;
        }

        WatchOccurrence ready = null;
        String reason;
        synchronized (queue) {
            InitialWatchKey key = initialWatchKey(owner, request);
            if (key == null) {
                reason = "wrong-initial-request";
            } else if (key.runner().rvxWatchReply() != reply) {
                reason = "wrong-initial-reply";
            } else {
                int cursor = queue.rvxOfflineCursor();
                Object row = cursor < 0 || cursor >= queue.rvxOfflineSize() ? null : queue.rvxOfflineItem(cursor);
                InitialWatchApply applied = initialWatchApply;
                if (applied != null && !key.matches(applied.key())) applied = null;

                if (row == null || !videoId.equals(queue.rvxOfflineVideoId(row))) {
                    reason = "wrong-cursor-row";
                } else if (applied != null && (applied.occurrence().queue() != queue
                        || applied.occurrence().row() != row || applied.reply() != reply || applied.host() != host)) {
                    reason = "replaced-initial-occurrence";
                } else {
                    if (applied == null) {
                        var occurrence = new WatchOccurrence(owner, queue, row, key.request(),
                                key.generation(), videoId, null);
                        applied = new InitialWatchApply(key, host, reply, occurrence);
                        initialWatchApply = applied;
                    }
                    boolean delivered = key.matches(initialWatchDelivery);
                    if (delivered && owner.rvxCurrentWatchQueue() == queue) ready = applied.occurrence();
                    reason = ready != null ? "accepted" : !delivered
                            ? "accepted-awaiting-delivery" : "accepted-awaiting-queue-publication";
                }
            }
        }
        OfflineWatchMetadata.witness("queue-apply", reason);
        return ready;
    }

    /** Native storage can publish the initial model after it receives the player's exact Watch reply. */
    public static void watchQueuePublished(@Nullable Object queue) {
        InitialWatchApply applied = initialWatchApply;
        if (applied == null || applied.occurrence().queue() != queue
                || !applied.key().matches(initialWatchDelivery)) return;

        OfflineWatchMetadata.witness("queue-publication", "accepted-initial-joined");
        OfflineWatchMetadata.activateAfterApply(applied.host(), applied.occurrence(), applied.reply());
    }

    /** Caller holds the native queue monitor. */
    static boolean ownsWatchOccurrence(WatchOccurrence occurrence) {
        if (occurrence.owner() != navigator || occurrence.generation() != navigationGeneration
                || watchQueue(occurrence) != occurrence.queue()
                || !OfflineQueueActions.currentReplayOccurrence(occurrence.queue(), occurrence.row())) return false;

        WatchKey key = occurrence.retained();
        if (key == null) {
            if (!(occurrence.owner() instanceof WatchNavigator)) {
                return occurrence.owner().rvxOwnsRequest(occurrence.request());
            }
            InitialWatchApply applied = initialWatchApply;
            return applied != null && applied.occurrence() == occurrence
                    && applied.key().matches(initialWatchDelivery) && ownsInitialWatch(applied.key())
                    && applied.key().runner().rvxWatchReply() == applied.reply();
        }
        return ownsWatchKey(key) && key.turn().ready(key.runner()) == occurrence;
    }

    @Nullable
    private static Object watchQueue(WatchOccurrence occurrence) {
        return occurrence.retained() == null && occurrence.owner() instanceof WatchNavigator modern
                ? modern.rvxCurrentWatchQueue() : localQueue();
    }

    private static void invalidateWatch() {
        watchJoin = null;
        initialWatchDelivery = null;
        initialWatchApply = null;
        OfflineWatchMetadata.invalidate();
    }

    private static void retireWatchRunner() {
        Navigator active = navigator;
        if (active instanceof WatchNavigator modern) {
            WatchRunner previous = modern.rvxCurrentWatchRunner();
            if (previous != retiredWatchRunner.get()) retiredWatchRunner = new WeakReference<>(previous);
        }
    }

    private static boolean ownsWatchTurn(WatchJoin turn) {
        Selection chosen = turn.selected;
        return turn == watchJoin && chosen == selection && chosen.owner() == navigator
                && chosen.navigationGeneration() == navigationGeneration
                && localQueue() == turn.queue;
    }

    private static boolean ownsWatchKey(WatchKey key) {
        return ownsWatchTurn(key.turn())
                && key.runner() != retiredWatchRunner.get()
                && ((WatchNavigator) key.turn().selected.owner()).rvxCurrentWatchRunner() == key.runner()
                && key.request() == watchRequestOrigin(key.runner().rvxWatchRequest());
    }

    @Nullable
    private static InitialWatchKey initialWatchKey(WatchNavigator owner, Object request) {
        long generation = navigationGeneration;
        WatchRunner runner = owner.rvxCurrentWatchRunner();
        Object origin = watchRequestOrigin(request);
        return origin != null && ownsInitialWatch(owner, runner, origin, generation)
                ? new InitialWatchKey(owner, runner, origin, generation) : null;
    }

    private static boolean ownsInitialWatch(InitialWatchKey key) {
        return ownsInitialWatch(key.owner(), key.runner(), key.request(), key.generation());
    }

    private static boolean ownsInitialWatch(WatchNavigator owner, @Nullable WatchRunner runner, Object request,
                                            long generation) {
        return watchJoin == null && owner == navigator && generation == navigationGeneration
                && !hasCurrentSelection(generation)
                && runner != null && runner != retiredWatchRunner.get() && owner.rvxCurrentWatchRunner() == runner
                && request == watchRequestOrigin(runner.rvxWatchRequest());
    }

    private static boolean hasCurrentSelection(long generation) {
        Selection chosen = selection;
        return chosen != null && chosen.navigationGeneration() == generation;
    }

    /** Proven native dispatch copies extend only metadata identity, not the playback lane or cancellation. */
    public static void noteWatchRequestCopy(Object copy, Object source) {
        if (copy != source && copy instanceof WatchRequestCopy request) {
            request.rvxWatchRequestSource(watchRequestOrigin(source));
        }
    }

    private static Object watchRequestOrigin(Object request) {
        Object origin = PlaybackRequestIdentity.original(request);
        if (origin instanceof WatchRequestCopy copy) {
            Object source = copy.rvxWatchRequestSource();
            if (source != null) return source;
        }
        return origin;
    }

    @Nullable
    public static Object watchSelection(Object owner) {
        WatchJoin turn = watchJoin;
        return turn != null && owner == turn.selected.owner() && ownsWatchTurn(turn) ? turn : null;
    }

    /** A saved response gets provenance before it competes with stock; this alone is not a delivery. */
    @Nullable
    public static Object localPlayerOwner(Object request) {
        return watchProvenance(request, true);
    }

    @Nullable
    static WatchKey retainedWatchKey(Object request) {
        return (WatchKey) watchProvenance(request, false);
    }

    @Nullable
    private static Object watchProvenance(Object request, boolean includeInitial) {
        try {
            WatchJoin turn = watchJoin;
            if (turn != null) return retainedWatchKey(turn, request);
            // Stock can produce a saved response before the initial runner is visible to this worker.
            // Only the producer's exact request is available here; the guarded callback proves which runner won.
            if (!includeInitial) return null;

            long generation = navigationGeneration;
            Object origin = hasCurrentSelection(generation) ? null : watchRequestOrigin(request);
            return origin != null ? new InitialSavedPlayer(origin, generation) : null;
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineWatchMetadata: could not capture saved player provenance", failure);
            return null;
        }
    }

    @Nullable
    private static WatchKey retainedWatchKey(WatchJoin turn, Object request) {
        if (!ownsWatchTurn(turn)) return null;

        WatchRunner runner = ((WatchNavigator) turn.selected.owner()).rvxCurrentWatchRunner();
        Object origin = watchRequestOrigin(request);
        return runner != null && runner != retiredWatchRunner.get()
                && origin == watchRequestOrigin(runner.rvxWatchRequest()) ? new WatchKey(turn, runner, origin) : null;
    }

    public static void markLocalPlayer(Object response, @Nullable Object owner) {
        if (response instanceof StoredPlayerResponse stored) {
            stored.rvxStoredPlayerOwner(owner == null ? UNOWNED_SAVED_PLAYER : owner);
        }
    }

    /** From the native success callback, after its cancellation guard and the callback's return. */
    public static void watchPlayerDelivered(Object runner, Object response) {
        WatchJoin turn = watchJoin;
        if (!(runner instanceof WatchRunner source)) return;
        try {
            Object proof = takeStoredPlayerOwner(response);
            if (turn == null) {
                Navigator owner = navigator;
                if (proof instanceof InitialSavedPlayer saved && owner instanceof WatchNavigator modern
                        && ownsInitialWatch(modern, source, saved.request(), saved.generation())) {
                    var key = new InitialWatchKey(modern, source, saved.request(), saved.generation());
                    initialWatchDelivery = key;
                    InitialWatchApply applied = initialWatchApply;
                    boolean ready = applied != null && key.matches(applied.key())
                            && modern.rvxCurrentWatchQueue() == applied.occurrence().queue();
                    OfflineWatchMetadata.witness("player-delivery",
                            ready ? "accepted-initial-joined" : "accepted-awaiting-initial-apply");
                    if (ready) OfflineWatchMetadata.activateAfterApply(applied.host(), applied.occurrence(),
                            applied.reply());
                } else if (proof != null) {
                    logInitialDeliveryRejection(owner, source, proof);
                }
                return;
            }

            WatchOccurrence ready = null;
            String reason;
            synchronized (turn.queue) {
                if (proof == null) reason = "not-saved";
                else if (proof == UNOWNED_SAVED_PLAYER) reason = "saved-without-owner";
                else if (!ownsWatchTurn(turn)
                        || ((WatchNavigator) turn.selected.owner()).rvxCurrentWatchRunner() != source
                        || !turn.acceptPlayer(source, retiredWatchRunner.get(), proof)) reason = "stale-saved";
                else {
                    ready = turn.ready(source);
                    reason = turn.deliveryReason();
                }
            }
            finishWatchWitness("player-delivery", reason, ready);
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineWatchMetadata: could not observe player delivery", failure);
        }
    }

    private static void logInitialDeliveryRejection(@Nullable Navigator owner, WatchRunner source, Object proof) {
        Logger.printDebug(() -> {
            InitialSavedPlayer saved = proof instanceof InitialSavedPlayer initial ? initial : null;
            WatchNavigator modern = owner instanceof WatchNavigator current ? current : null;
            WatchRunner active = modern == null ? null : modern.rvxCurrentWatchRunner();
            return "OfflineWatchMetadata: player-delivery reason=no-owned-initial-start"
                    + " savedOrigin=" + (saved != null)
                    + " sameGeneration=" + (saved != null && saved.generation() == navigationGeneration)
                    + " activeSelection=" + hasCurrentSelection(navigationGeneration)
                    + " runnerPresent=" + (active != null) + " sameRunner=" + (source == active)
                    + " retired=" + (source == retiredWatchRunner.get())
                    + " sameRequest="
                    + (saved != null && saved.request() == watchRequestOrigin(source.rvxWatchRequest()));
        });
    }

    @Nullable
    static Object takeStoredPlayerOwner(Object response) {
        if (!(response instanceof StoredPlayerResponse stored)) return null;

        Object saved = stored.rvxStoredPlayerOwner();
        if (saved != null) stored.rvxStoredPlayerOwner(null);
        return saved;
    }

    /** The actual native row and result payload, not the index with which navigation was started. */
    public static void watchCommitted(Object owner, Object queue, Object row, @Nullable Object request, Object token) {
        WatchJoin turn = watchJoin;
        if (turn == null) {
            if (token != null) OfflineWatchMetadata.witness("cursor-commit", "stale-selection");
            return;
        }
        if (turn != token || owner != turn.selected.owner()) {
            OfflineWatchMetadata.witness("cursor-commit", "stale-selection");
            return;
        }
        try {
            WatchOccurrence ready = null;
            String reason;
            synchronized (turn.queue) {
                if (!ownsWatchTurn(turn)) {
                    reason = "stale-selection";
                } else {
                    WatchRunner runner = ((WatchNavigator) owner).rvxCurrentWatchRunner();
                    reason = turn.commit(queue, row, request, runner, retiredWatchRunner.get());
                    if ("accepted-joined".equals(reason)) ready = turn.ready(runner);
                }
            }
            finishWatchWitness("cursor-commit", reason, ready);
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineWatchMetadata: could not observe native commit", failure);
        }
    }

    public static void watchPublished(Object runner, Object response) {
        WatchJoin turn = watchJoin;
        if (turn != null && runner instanceof WatchRunner source
                && response instanceof OfflineWatchMetadata.Reply reply) {
            try {
                attachWatchReply(turn, source, reply, "watch-publication");
            } catch (Exception | LinkageError failure) {
                Logger.printException(() -> "OfflineWatchMetadata: could not observe Watch publication", failure);
            }
        }
    }

    /** The standalone/playlist path is unchanged; a retained navigation cannot use queue apply as delivery proof. */
    static boolean attachRetainedWatch(Object queue, Object request, OfflineWatchMetadata.Reply reply) {
        WatchJoin turn = watchJoin;
        if (turn == null || queue != turn.queue || !ownsWatchTurn(turn)) return false;

        WatchRunner runner = ((WatchNavigator) turn.selected.owner()).rvxCurrentWatchRunner();
        if (runner == null || watchRequestOrigin(request) != watchRequestOrigin(runner.rvxWatchRequest())) {
            OfflineWatchMetadata.witness("queue-apply", "wrong-runner");
        } else {
            attachWatchReply(turn, runner, reply, "queue-apply");
        }
        return true;
    }

    private static void attachWatchReply(WatchJoin turn, WatchRunner runner,
                                         OfflineWatchMetadata.Reply reply, String edge) {
        Object request = watchRequestOrigin(runner.rvxWatchRequest());
        Object tag = reply.rvxLocalWatchRequest();
        WatchKey key = new WatchKey(turn, runner, request);
        WatchOccurrence ready = null;
        String reason;
        synchronized (turn.queue) {
            if (!ownsWatchKey(key) || tag != null && watchRequestOrigin(tag) != request) {
                reason = "wrong-runner";
            } else if (!turn.selected.videoId().equals(reply.rvxMetadataVideoId())) {
                reason = "wrong-response-video";
            } else {
                turn.attach(key, reply);
                ready = turn.ready(runner);
                reason = ready == null ? "accepted-awaiting-join" : "accepted-attached";
            }
        }
        finishWatchWitness(edge, reason, ready);
    }

    private static void finishWatchWitness(String edge, String reason, @Nullable WatchOccurrence ready) {
        OfflineWatchMetadata.witness(edge, reason);
        if (ready != null) OfflineWatchMetadata.committed(ready);
    }

    @Nullable
    static OfflineQueueActions.Selection queueSelectionAtCommit(Object model, Object occurrence) {
        Selection current = selection;
        if (current == null || current.owner() != navigator
                || current.navigationGeneration() != navigationGeneration) return null;
        var edited = current.editedQueue();
        return edited != null && edited.queue() == model && edited.occurrence() == occurrence
                && localQueue() == model ? edited : null;
    }

    static boolean ownsQueueContinuation(Object token, Object request) {
        if (!(token instanceof Selection selected) || selected != selection ||
                selected.owner() != navigator || selected.navigationGeneration() != navigationGeneration ||
                !(request instanceof Request replay) || !selected.videoId().equals(replay.rvxVideoId()) ||
                !selected.owner().rvxOwnsRequest(request) || selected.editedQueue() == null ||
                !OfflineQueueActions.ownsLiveOccurrence(selected.owner(), selected.editedQueue())) return false;
        return selected == selection && selected.navigationGeneration() == navigationGeneration;
    }

    /** A new external Watch is not a continuation of the previously selected native queue. */
    static void playlistStarted() {
        retireWatchRunner();
        navigationGeneration++;
        selection = null;
        onlinePlaylist = null;
        EXHAUSTED.clear();
        invalidateWatch();
    }

    static void reconnected() {
        EXHAUSTED.reconnected();
        OfflineQueueActions.reconnected();
    }

    /** Invalidate before the coroutine/index boundary, including a replacement command queued before Play. */
    public static void navigationStarted(Object owner, Object command) {
        if (owner == navigator && command instanceof Navigation navigation && navigation.rvxRecovery() == null) {
            retireWatchRunner();
            navigationGeneration++;
            EXHAUSTED.clear();
            invalidateWatch();
        }
    }

    /** Raw explicit PLAY only: preserve stock toggle/headset multi-tap, Pause, key-up and repeated-key handling. */
    public static boolean recoverMediaPlay(Object source, Intent intent) {
        if (!EXHAUSTED.armed() || intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return false;
        }
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        return event != null && PlayerFallbackLogic.isExplicitPlayKey(event.getAction(), event.getRepeatCount(),
                event.getKeyCode()) && recoverPlay(source);
    }

    /** Native UI, session and raw-key entrypoints share the same one-shot, occurrence-scoped decision. */
    public static boolean recoverPlay(Object source) {
        if (!EXHAUSTED.armed()) return false;
        if (!OfflineReplay.enabled() || PlaybackConnectivity.reachable()) {
            EXHAUSTED.clear();
            return false;
        }
        Navigator owner = navigator;
        if (owner == null || (source instanceof PlaySource playSource
                ? playSource.rvxPlayNavigator() != owner : owner.rvxPlayback() != source)) return false;

        List<?> items = owner.rvxItems();
        int current = owner.rvxCurrent();
        long itemId = current >= 0 && current < items.size() ? owner.rvxItemId(items.get(current)) : Long.MIN_VALUE;
        ExhaustedPlayback.Resume resume = EXHAUSTED.take(owner, selection, itemId, true, false);
        if (resume == null) return false;
        int target = findOccurrence(owner, items, resume.previous());
        if (target < 0 || !OfflineReplay.holds(resume.previous().videoId())) return false;

        try {
            Object command = owner.rvxJump(items.get(target), true);
            ((Navigation) command).rvxRecovery(new Recovery(owner, target, resume.previous().itemId(),
                    resume.previous().videoId(), selection, 1, true, resume.generation()));
            Logger.printDebug(() -> "PlayerFallback: exhausted Play -> " + target + "#"
                    + resume.previous().itemId() + " v=" + resume.previous().videoId() + " start=0");
            owner.rvxDispatch(command);
            return true;
        } catch (Exception failure) {
            Logger.printException(() -> "PlayerFallback: exhausted Play dispatch failed", failure);
            return false;
        }
    }

    private static int findOccurrence(Navigator owner, List<?> items, ExhaustedPlayback.Occurrence target) {
        int found = -1;
        for (int index = 0; index < items.size(); index++) {
            Object item = items.get(index);
            if (owner.rvxItemId(item) != target.itemId()) continue;
            if (found >= 0 || !target.videoId().equals(owner.rvxVideoId(item))) return -1;
            found = index;
        }
        return found;
    }

    private static ExhaustedPlayback.Occurrence previousPlayable(Navigator owner, List<?> items) {
        if (!OfflineReplay.enabled()) return null;
        String playing = VideoInformation.getVideoId();
        int current = owner.rvxCurrent();
        if (current >= 0 && current < items.size()) {
            Object item = items.get(current);
            String videoId = owner.rvxVideoId(item);
            if (playing.equals(videoId) && OfflineReplay.holds(videoId)) {
                return new ExhaustedPlayback.Occurrence(owner.rvxItemId(item), videoId);
            }
        }
        Selection prior = selection;
        if (prior != null && prior.owner() == owner && prior.previous() != null
                && playing.equals(prior.previous().videoId()) && OfflineReplay.holds(playing)
                && findOccurrence(owner, items, prior.previous()) >= 0) return prior.previous();
        return null;
    }

    private static void logStop(Navigator owner, List<?> items, int failed, int remaining,
                                boolean advancing, String reason) {
        Logger.printDebug(() -> {
            List<ExhaustedPlayback.Occurrence> queue = new ArrayList<>(items.size());
            for (Object item : items) {
                queue.add(new ExhaustedPlayback.Occurrence(owner.rvxItemId(item), owner.rvxVideoId(item)));
            }
            return PlayerFallbackLogic.describeStop(queue, owner.rvxCurrent(), failed, remaining,
                    advancing, reason, OfflineReplay::holds);
        });
    }

    static Object ownership(Object request) {
        Navigator active = navigator;
        if (active == null || !active.rvxOwnsRequest(request)) return null;
        Selection selected = selection;
        return selected == null ? active : selected;
    }

    static boolean owns(Object request, Object ownership) {
        Navigator active = navigator;
        return ownership != null && active != null
                && (ownership == selection || selection == null && ownership == active)
                && active.rvxOwnsRequest(request);
    }

    public static boolean replayFailure(Object request, Throwable failure) {
        return PlaybackConnectionLoss.find(failure) != null || routeOffline(request)
                || OfflineReplay.enabled() && request instanceof Request && upstreamFailure(failure);
    }

    /** A failure of the way to YouTube itself, not of what it answered: a socket, a DNS, a TLS or a Cronet error. */
    static boolean upstreamFailure(Throwable failure) {
        boolean interrupted = Thread.currentThread().isInterrupted();
        return DownloadRetryPlan.isTransportFailure(failure, interrupted)
                || PlayerFallbackLogic.isCronetNetworkFailure(failure, interrupted);
    }

    /** Whether the request is for something that has a copy to play: a song, or a playlist-only watch next. */
    static boolean holdsRequest(Object request) {
        if (!(request instanceof Request replay)) return false;

        String videoId = replay.rvxVideoId();
        return videoId.isEmpty() ? OfflineReplay.mayAnswerNext(request) : OfflineReplay.mayHold(videoId);
    }

    static boolean currentFailure(Object request, ReplayFuture result, Throwable failure) {
        PlaybackConnectionLoss loss = PlaybackConnectionLoss.find(failure);
        if (loss == null || PlaybackRequestIdentity.same(loss.request, request) && owns(request, loss.ownership)) {
            return true;
        }
        result.cancel(false);
        return false;
    }

    static void completeRecovery(Object request, ReplayFuture result, Object response, Throwable failure) {
        if (PlaybackConnectionLoss.find(failure) != null) {
            Utils.runOnMainThreadNowOrLater(() -> {
                if (result.isCancelled() || !currentFailure(request, result, failure)) return;
                if (response == null) finishUnavailable(request, result);
                else if (setLocal(result, response)) {
                    Logger.printDebug(() -> "PlayerFallback: connectivity loss answered v="
                            + ((Request) request).rvxVideoId());
                }
            });
        } else if (response != null) setLocal(result, response);
        else finishUnavailable(request, result);
    }

    static boolean setLocal(ReplayFuture result, Object response) {
        if (!(response instanceof StreamSwap.Response media)) return result.set(response);
        boolean delivered = result.set(response, () -> StreamSwap.publishStored(response));
        if (delivered) NoConnectionSheet.onCopyReplayed(media.rvxVideoId());
        return delivered;
    }

    /** Starts the stock index navigation with the clicked row's request, not its standalone Watch endpoint. */
    static boolean playEditedQueueRow(Object owner, OfflineQueueActions.Selection selected) {
        if (!(owner instanceof Navigator nativeOwner)) return false;
        Object command = nativeOwner.rvxJump(selected.occurrence(), false);
        if (!(command instanceof Navigation navigation)) return false;

        captureNavigator(nativeOwner);
        retireWatchRunner();
        navigation.rvxRecovery(new QueueTap(nativeOwner, selected, ++navigationGeneration));
        invalidateWatch();
        Logger.printDebug(() -> "OfflineQueueActions: native Up Next selection index=" + selected.index());
        nativeOwner.rvxDispatch(command);
        return true;
    }

    static long navigationGeneration() {
        return navigationGeneration;
    }

    /** Runs inline at the stock index boundary; reads in-memory copy indexes, never files or the network. */
    public static int chooseIndex(Object owner, Object command, int target) {
        if (!(owner instanceof Navigator nativeOwner) || !(command instanceof Navigation navigation)) return target;
        captureNavigator(nativeOwner);
        Object token = navigation.rvxRecovery();
        Recovery recovery = token instanceof Recovery resume ? resume : null;
        OfflineQueueActions.Selection editedTap = null;
        if (token instanceof QueueTap tap) {
            navigation.rvxRecovery(null);
            if (tap.owner() != nativeOwner || tap.navigationGeneration() != navigationGeneration
                    || !OfflineQueueActions.ownsSelectedOccurrence(nativeOwner, tap.selected())) return -1;
            editedTap = tap.selected();
            target = editedTap.index();
        }
        if (recovery != null) {
            navigation.rvxRecovery(null);
            if (recovery.owner() != nativeOwner || recovery.previous() != selection) return -1;
            if (recovery.previous() != null
                    && recovery.previous().navigationGeneration() != navigationGeneration) return -1;
            if (recovery.resume() && (recovery.generation() != EXHAUSTED.generation()
                    || !OfflineReplay.enabled() || PlaybackConnectivity.reachable())) return -1;
            target = recovery.index();
        }
        EXHAUSTED.clear();
        List<?> items = nativeOwner.rvxItems();
        if (editedTap != null && (target >= items.size() || items.get(target) != editedTap.occurrence())) return -1;
        if (recovery != null && (target < 0 || target >= items.size()
                || nativeOwner.rvxItemId(items.get(target)) != recovery.itemId())) {
            target = -1;
            for (int index = 0; index < items.size(); index++) {
                if (nativeOwner.rvxItemId(items.get(index)) == recovery.itemId()) {
                    target = index;
                    break;
                }
            }
            if (target < 0) {
                logStop(nativeOwner, items, -1, recovery.remaining(), false, "removed-recovery-occurrence");
                stop(nativeOwner, null);
            }
        }
        if (target < 0 || target >= items.size()) return target;
        if (recovery != null && recovery.resume()
                && !recovery.videoId().equals(nativeOwner.rvxVideoId(items.get(target)))) return -1;
        String kind = navigation.rvxKind().name();
        boolean advance = recovery != null ? !recovery.resume()
                : "NEXT".equals(kind) || "AUTOPLAY".equals(kind) || "AUTONAV".equals(kind);
        int originalTarget = target;
        int remaining = recovery == null ? items.size() - target : recovery.remaining();

        String videoId = nativeOwner.rvxVideoId(items.get(target));
        if (OfflineReplay.enabled() && !PlaybackConnectivity.reachable() && !OfflineReplay.mayHold(videoId)) {
            List<String> ids = new ArrayList<>(items.size());
            for (Object item : items) ids.add(nativeOwner.rvxVideoId(item));
            PlayerFallbackLogic.Decision decision = PlayerFallbackLogic.decide(true, false, videoId, !advance,
                    ids, target, OfflineReplay::mayHold);
            if (decision.route() == PlayerFallbackLogic.Route.UNAVAILABLE) {
                if (advance) {
                    logStop(nativeOwner, items, target, remaining, true, "preflight-exhausted");
                    stop(nativeOwner, null);
                } else unavailable();
                return -1;
            }
            if (decision.route() == PlayerFallbackLogic.Route.SKIP) {
                int skipped = target;
                target = decision.nextIndex();
                Logger.printDebug(() -> "PlayerFallback: native skip index=" + skipped + " -> "
                        + decision.nextIndex() + " size=" + ids.size() + " kind=" + kind);
                videoId = ids.get(target);
            }
        }
        if (remaining <= target - originalTarget) {
            logStop(nativeOwner, items, originalTarget, remaining, advance, "recovery-budget-exhausted");
            stop(nativeOwner, null);
            return -1;
        }
        ExhaustedPlayback.Occurrence previous = recovery == null ? previousPlayable(nativeOwner, items)
                : recovery.previous() == null ? null : recovery.previous().previous();
        Object row = items.get(target);
        Selection chosen = new Selection(nativeOwner, nativeOwner.rvxItemId(row), videoId,
                advance, remaining - (target - originalTarget), previous, navigationGeneration,
                editedTap != null ? editedTap : OfflineQueueActions.select(nativeOwner, row, target));
        Object queue = nativeOwner instanceof WatchNavigator ? localQueue() : null;
        watchJoin = queue instanceof OfflineQueueActions.Queue local && queue instanceof OfflineWatchMetadata.Host
                ? new WatchJoin(chosen, local, row) : null;
        selection = chosen;
        return target;
    }

    static void startingOnlinePlaylist(String playlistId) {
        if (OfflineReplayLogic.isShufflePlaylistId(playlistId) && OfflineCopyStore.mayHaveSource(playlistId)) {
            onlinePlaylist = new OnlinePlaylist(playlistId);
        }
    }

    /** Called after the native primary queue inserts a page, not from an unrelated renderer or a tap. */
    public static void recordOnlineRows(Object queue, int lane, Collection<?> added) {
        OnlinePlaylist playlist = onlinePlaylist;
        if (playlist == null || lane != 0 || added == null || added.isEmpty()) return;

        synchronized (playlist) {
            Object live = playlist.queue.get();
            if (playlist.tailAdded || live != null && live != queue) return;
            boolean missingOrdinal = false;
            for (Object row : added) {
                if (!(row instanceof QueueRow item) || !(item.rvxQueueRenderer() instanceof MessageLite renderer)) {
                    missingOrdinal = true;
                    continue;
                }
                TapCommand watch = TapCommand.parse(renderer.toByteArray());
                if (!playlist.id.equals(watch.playlistId)) continue;
                if (watch.videoId == null || watch.index < 0) {
                    missingOrdinal = true;
                    continue;
                }

                if (live == null) {
                    live = queue;
                    playlist.queue = new WeakReference<>(queue);
                }
                String existing = playlist.loaded.putIfAbsent(watch.index, watch.videoId);
                if (existing != null && !existing.equals(watch.videoId)) playlist.ambiguous = true;
            }
            if (live != null && missingOrdinal) playlist.ambiguous = true;
        }
    }

    @Nullable
    private static OnlinePlaylist coveredPlaylist(OfflineQueueActions.Selection selected) {
        OnlinePlaylist playlist = onlinePlaylist;
        return playlist != null && playlist.queue.get() == selected.queue() ? playlist : null;
    }

    static boolean markTailInstalled(Object queue, int coveredIndex) {
        OnlinePlaylist playlist = onlinePlaylist;
        if (playlist == null) return false;
        synchronized (playlist) {
            if (playlist != onlinePlaylist || playlist.queue.get() != queue || playlist.tailAdded
                    || playlist.ambiguous || playlist.loaded.size() != coveredIndex + 1) return false;
            playlist.tailAdded = true;
            return true;
        }
    }

    @Nullable
    static Object editedQueueSelection(Object request, String targetVideoId) {
        Selection selected = selection;
        return selected != null && selected.videoId().equals(targetVideoId) &&
                ownsQueueContinuation(selected, request) ? selected : null;
    }

    @Nullable
    static Object editedNextResponse(Object token, Object request, OfflineReplay.NextRequest next, String playlistId,
                                     String videoId, byte[] command) throws IOException {
        Selection selected = (Selection) token;
        if (!videoId.equals(selected.videoId()) || !ownsQueueContinuation(selected, request)) return null;
        OfflineQueueActions.Selection row = selected.editedQueue();
        if (!(row.occurrence() instanceof QueueRow item)
                || !(item.rvxQueueRenderer() instanceof MessageLite renderer)) return null;

        List<byte[]> tail = null;
        int coveredIndex = -1;
        OnlinePlaylist playlist = coveredPlaylist(row);
        if (playlist != null) {
            List<String> covered = List.of();
            synchronized (playlist) {
                if (!playlist.tailAdded && !playlist.ambiguous && !playlist.loaded.isEmpty()
                        && playlist.loaded.firstKey() == 0
                        && playlist.loaded.lastKey() == playlist.loaded.size() - 1) {
                    coveredIndex = playlist.loaded.lastKey();
                    covered = List.copyOf(playlist.loaded.values());
                }
            }
            if (coveredIndex >= 0) tail = OfflineReplay.missingTail(playlist.id, covered, videoId, command);
        }

        String artwork = OfflinePlaybackArtwork.uri(videoId);
        var retainedArtwork = OfflinePlaybackArtwork.retained(row.occurrence(), artwork);
        byte[] bytes = OfflineWatchNext.buildContinuation(playlistId, renderer.toByteArray(), tail, row.index(),
                command, artwork);
        return next.rvxNextResponse(bytes, false, new OfflineQueueActions.Continuation(row, selected,
                request, tail == null || tail.isEmpty() ? -1 : coveredIndex, retainedArtwork));
    }

    /** Direct scalar/callback producers also short-circuit before starting network work. */
    public static ListenableFuture<?> localPlayer(Object request) {
        if (!(request instanceof Request replay) || !routeOffline(request)) return null;

        ReplayFuture result = new ReplayFuture();
        Utils.runOnBackgroundThread(() -> {
            if (result.isCancelled()) return;
            Object response = currentRoute(request) ? localResponse(replay, false) : null;
            if (!currentRoute(request)) result.cancel(false);
            else if (response != null) setLocal(result, response);
            else stopUnavailable(request, replay.rvxVideoId(), () -> result.cancel(false),
                    () -> !result.isCancelled(), -1);
        });
        notePublishedWhenDone(request, result);
        return result;
    }

    public static ListenableFuture<?> wrapPlayer(Object request, ListenableFuture<?> stock) {
        return wrapLane(request, stock, false);
    }

    static ListenableFuture<?> wrapLane(Object request, ListenableFuture<?> stock, boolean next) {
        if (stock == null || stock instanceof ReplayFuture || !(request instanceof Request)
                || !OfflineReplay.enabled()) return stock;

        route(request);
        PlayerFallbackFuture future = PlayerFallbackFuture.recoverAsync(stock, Utils::runOnBackgroundThread,
                (result, failure) -> recover(request, next, result, failure),
                next ? ReplayFuture::set : StreamSwap::finishOnline);
        PlaybackConnectivity.watch(request, future, next);
        if (!next) notePublishedWhenDone(request, future);
        scheduleDeadline(request, next, future::pending, future::tryFallback);
        return future;
    }

    static void scheduleDeadline(Object request, boolean next, BooleanSupplier pending,
                                 Consumer<Supplier<Object>> attempt) {
        if (!(request instanceof Request replay) || !pending.getAsBoolean()) return;
        // A seeded request has independent media and metadata lanes. For a playlist-only start, stock
        // derives the media request from next, so race that prerequisite against the saved queue instead.
        if (next ? !replay.rvxVideoId().isEmpty() || !OfflineReplay.mayAnswerNext(request)
                : !OfflineReplay.mayHold(replay.rvxVideoId())) return;

        long delay = routeOffline(request) ? 0 : LOCAL_DEADLINE_MS;
        Utils.runOnMainThreadDelayed(() -> {
            if (!pending.getAsBoolean() || !currentRoute(request)) return;
            Object owner = ownership(request);
            // A generation alone does not prove that the native runner still owns this request.
            if (!owns(request, owner)) return;
            Utils.runOnBackgroundThread(() -> {
                if (!pending.getAsBoolean() || !currentRoute(request) || !owns(request, owner)) return;
                attempt.accept(() -> {
                    Object value = fallbackResponse(request, next, false);
                    return currentRoute(request) && owns(request, owner) ? value : null;
                });
            });
        }, delay);
    }

    private static Object fallbackResponse(Object request, boolean next, boolean upstreamFailed) {
        return next ? OfflineReplay.nextResponse(request)
                : request instanceof Request replay ? localResponse(replay, upstreamFailed) : null;
    }

    public static Pair<?, ?> localPair(Object request) {
        if (!routeOffline(request) || ((Request) request).rvxVideoId().isEmpty()) return null;
        ListenableFuture<?> next = OfflineReplay.localNext(request);
        return next == null ? null : Pair.create(localPlayer(request), next);
    }

    public static Pair<?, ?> wrapPair(Object request, Pair<?, ?> stock) {
        if (!OfflineReplay.enabled()) return stock;
        return Pair.create(wrapPlayer(request, (ListenableFuture<?>) stock.first),
                OfflineReplay.wrapNext(request, (ListenableFuture<?>) stock.second));
    }

    public static Object wrapPacked(Object request, Object stock) {
        return OfflineReplay.enabled() && stock instanceof Packed packed ? packed.rvxRecover(request) : stock;
    }

    /** A reactive failure's replacement starts asynchronously; unavailable cancellation follows native STOP. */
    public static ListenableFuture<?> recoverFuture(Object request, boolean next, Throwable failure) {
        ReplayFuture result = new ReplayFuture();
        Utils.runOnBackgroundThread(() -> {
            if (!result.isCancelled()) recover(request, next, result, failure);
        });
        return result;
    }

    private static void recover(Object request, boolean next, ReplayFuture result, Throwable failure) {
        if (!currentRoute(request) || !currentFailure(request, result, failure)) {
            result.cancel(false);
            return;
        }
        if (!replayFailure(request, failure)) {
            result.setException(failure);
            return;
        }

        if (PlaybackConnectionLoss.find(failure) == null && holdsRequest(request)) {
            UpstreamProbe.probe("lane-failure");
        }
        Object response = fallbackResponse(request, next, upstreamFailure(failure));
        if (!currentRoute(request)) result.cancel(false);
        else if (response == null && PlaybackConnectionLoss.find(failure) == null
                && !routeOffline(request) && PlaybackConnectivity.reachableFor(request)) result.setException(failure);
        else completeRecovery(request, result, response, failure);
    }

    /**
     * The copy's answer for a request, or null when it must not be used. A copy of a song whose online video the
     * Video display asks for is declined while YouTube is reachable; it is not when YouTube is not.
     *
     * @param upstreamFailed The request just failed on the way to YouTube.
     */
    private static Object localResponse(Request request, boolean upstreamFailed) {
        Object owner = localPlayerOwner(request);
        try {
            byte[] bytes = OfflineReplay.storedForPlayer(request.rvxVideoId(), true);
            if (bytes == null) return null;

            Object response = OfflineReplay.preparePlayer(request.rvxPlayerResponse(bytes), request.rvxVideoId());
            markLocalPlayer(response, owner);
            if (response == null || routeOffline(request) || upstreamFailed
                    || !PlaybackConnectivity.reachableFor(request)) return response;

            String copyId = OfflineReplay.canonicalCopyId(request.rvxVideoId());
            if (StreamSwap.isVideoPair(response)) return response;

            if (AudioFirstLogic.isVideoMode(AudioFirst.currentMode()) && AudioFirst.declinesForVideo(copyId)) {
                return null;
            }
            return response;
        } catch (Exception unavailable) {
            Logger.printException(() -> "PlayerFallback: saved response could not be constructed", unavailable);
            return null;
        }
    }

    public static boolean rejectPlayer(Object request, Object callback) {
        if (!(request instanceof Request replay) || !routeOffline(request)) return false;
        stopUnavailable(request, replay.rvxVideoId(), () -> replay.rvxCancelPlayer(callback), () -> true, -1);
        return true;
    }

    static void finishUnavailable(Object request, ReplayFuture result) {
        String videoId = request instanceof Request replay ? replay.rvxVideoId() : "";
        stopUnavailable(request, videoId, () -> result.cancel(false), () -> !result.isCancelled(), -1);
    }

    static void finishUncached(Object request, Object ownership, Runnable completed) {
        if (!owns(request, ownership)) {
            completed.run();
            return;
        }
        Logger.printDebug(() -> "PlayerFallback: connectivity loss uncached v=" + ((Request) request).rvxVideoId());
        stopUnavailable(request, ((Request) request).rvxVideoId(), completed, () -> true, EXHAUSTED.generation());
    }

    private static void stopUnavailable(Object request, String videoId, Runnable completed,
                                        BooleanSupplier currentRequest, long lossGeneration) {
        Selection expected = selection;
        long expectedGeneration = navigationGeneration;
        Navigator active = navigator;
        Utils.runOnMainThreadNowOrLater(() -> {
            if (!currentRequest.getAsBoolean()) return;
            if (active == null || active != navigator || !active.rvxOwnsRequest(request)) {
                if (completed != null) completed.run();
                return;
            }
            // A new native selection owns its own runner: a late failure must never cancel it.
            if (expected != selection || expectedGeneration != navigationGeneration) {
                if (completed != null) completed.run();
                return;
            }
            List<?> items = active.rvxItems();
            int failedIndex = -1;
            int remaining = expected == null ? 0 : expected.remaining();
            boolean advancing = expected != null && expected.advancing();
            boolean dispatchFailed = false;
            if (expected != null && expected.owner() == active && videoId.equals(expected.videoId())) {
                List<String> ids = new ArrayList<>(items.size());
                for (int index = 0; index < items.size(); index++) {
                    Object item = items.get(index);
                    ids.add(active.rvxVideoId(item));
                    if (active.rvxItemId(item) == expected.itemId()) failedIndex = index;
                }
                int next = expected.advancing() && failedIndex >= 0
                        ? PlayerFallbackLogic.nextCached(ids, failedIndex, expected.remaining(), OfflineReplay::holds)
                        : -1;
                if (next >= 0) {
                    try {
                        Object item = items.get(next);
                        Object command = active.rvxJump(item, false);
                        ((Navigation) command).rvxRecovery(new Recovery(active, next,
                                active.rvxItemId(item), ids.get(next), expected,
                                expected.remaining() - (next - failedIndex), false, 0));
                        active.rvxCancelLoad();
                        if (completed != null) completed.run();
                        if (expected == selection) {
                            Logger.printDebug(() -> "PlayerFallback: native skip after transport -> " + next);
                            active.rvxDispatch(command);
                        }
                        return;
                    } catch (Exception failure) {
                        dispatchFailed = true;
                        Logger.printException(() -> "PlayerFallback: native recovery navigation failed", failure);
                    }
                }
            }
            String reason = dispatchFailed ? "recovery-dispatch-failed" : expected == null ? "missing-selection"
                    : !videoId.equals(expected.videoId()) ? "request-selection-mismatch"
                    : failedIndex < 0 ? "missing-failed-occurrence"
                    : !advancing ? "explicit-unavailable" : "in-flight-exhausted";
            logStop(active, items, failedIndex, remaining, advancing, reason);
            int current = active.rvxCurrent();
            ExhaustedPlayback.Occurrence failed = current >= 0 && current < items.size()
                    && videoId.equals(active.rvxVideoId(items.get(current)))
                    ? new ExhaustedPlayback.Occurrence(active.rvxItemId(items.get(current)), videoId) : null;
            ExhaustedPlayback.Occurrence previous = previousAtStop(active, items, expected, videoId);
            stop(active, completed);
            if (lossGeneration >= 0 && active == navigator && expected == selection && expected != null
                    && expected.navigationGeneration() == navigationGeneration) {
                EXHAUSTED.arm(active, expected, failed, previous, lossGeneration);
            }
        });
    }

    private static ExhaustedPlayback.Occurrence previousAtStop(Navigator owner, List<?> items, Selection expected,
                                                              String failedVideoId) {
        if (expected == null || expected.owner() != owner) return null;
        if (!failedVideoId.equals(expected.videoId()) && expected.videoId().equals(VideoInformation.getVideoId())
                && OfflineReplay.holds(expected.videoId())) {
            ExhaustedPlayback.Occurrence previous = new ExhaustedPlayback.Occurrence(expected.itemId(),
                    expected.videoId());
            if (findOccurrence(owner, items, previous) >= 0) return previous;
        }
        return expected.previous();
    }

    private static void stop(Navigator owner, Runnable completed) {
        Utils.runOnMainThreadNowOrLater(() -> {
            owner.rvxCancelAndStop();
            unavailable();
            if (completed != null) completed.run();
        });
    }

    static void unavailable() {
        Utils.showToastShort(str("morphe_music_player_unavailable_offline"));
        Logger.printDebug(() -> "PlayerFallback: unavailable offline; native queue retained");
    }
}
