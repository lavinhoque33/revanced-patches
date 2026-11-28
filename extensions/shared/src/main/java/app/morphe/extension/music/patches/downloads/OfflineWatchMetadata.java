/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** A separate watch metadata read; neither the sequencer nor the queue ever sees its result. */
@SuppressWarnings("unused")
public final class OfflineWatchMetadata {
    public interface Host {
        ListenableFuture<?> rvxFetchWatchMetadata(byte[] command);
        Reply rvxDecodeWatchMetadata(Object response);
    }

    public interface Reply {
        @Nullable Object rvxLocalWatchRequest();
        void rvxLocalWatchRequest(Object request);
        String rvxMetadataVideoId();
        @Nullable String rvxMetadataPlaylistId();
        List<?> rvxMetadataTabs();
        @Nullable Object rvxMetadataReaction();
    }

    public interface Tab {
        int rvxMetadataTabType();
    }

    public interface TransportCancellation {
        void rvxCancelMetadataTransport(Object handle);
    }

    /** The callback future can already be cancelled when the scheduler returns its native handle. */
    public static void bindTransport(ListenableFuture<?> result, TransportCancellation transport, Object handle) {
        result.addListener(() -> {
            if (result.isCancelled()) transport.rvxCancelMetadataTransport(handle);
        }, Runnable::run);
    }

    public interface View {
        void rvxAppendWatchTab(Object tab);
    }

    public interface Reaction {
        @Nullable Object rvxReactionState();
        void rvxShowWatchReaction(Object renderer, String videoId, @Nullable Object expected);
    }

    // Lhko.aw, the native Watch page browse endpoint classifier (not localized tab titles).
    private static final int LYRICS = 0x1737e;
    private static final int RELATED = 0x1737d;
    private static final AtomicLong reactionGeneration = new AtomicLong();
    private static volatile WeakReference<Reaction> reaction = new WeakReference<>(null);
    private static Binding binding;
    private static volatile Scope current;
    private static long validationEpoch;

    private record Binding(WeakReference<View> view, Reply reply, List<?> stock,
                           @Nullable PlayerFallback.WatchKey retained) { }

    /** Main-thread owned, except for immutable inputs captured by the workers. */
    private static final class Scope {
        final Host host;
        @Nullable Reply local;
        final PlayerFallback.WatchOccurrence occurrence;
        long lastAttempt = -1;
        @Nullable ListenableFuture<?> future;
        @Nullable List<?> remoteTabs;
        @Nullable volatile Object related;
        @Nullable ReconnectSurfaces.PlayerBinding surfaces;

        Scope(Host host, @Nullable Reply local, PlayerFallback.WatchOccurrence occurrence) {
            this.host = host;
            this.local = local;
            this.occurrence = occurrence;
        }
    }

    private record ReactionRead(Reaction owner, @Nullable Object state, long generation) { }

    private OfflineWatchMetadata() { }

    public static void applied(Object host, Object response) {
        if (!(host instanceof Host transport) || !(response instanceof Reply reply)) return;
        try {
            Object request = reply.rvxLocalWatchRequest();
            if (request == null) {
                witness("queue-apply", "no-tag");
                invalidate();
                return;
            }
            String videoId = reply.rvxMetadataVideoId();
            if (videoId == null) {
                witness("queue-apply", "no-parsed-video");
                invalidate();
                return;
            }
            if (PlayerFallback.attachRetainedWatch(host, request, reply)) return;

            var occurrence = PlayerFallback.watchOccurrence(transport, request, videoId, reply);
            if (occurrence == null) invalidate();
            else activateAfterApply(transport, occurrence, reply);
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineWatchMetadata: could not record current watch", failure);
        }
    }

    static void activateAfterApply(Host host, PlayerFallback.WatchOccurrence occurrence, Reply reply) {
        Utils.runOnMainThread(() -> activate(host, occurrence, reply));
    }

    static void committed(PlayerFallback.WatchOccurrence occurrence) {
        if (!(occurrence.queue() instanceof Host host) || occurrence.retained() == null) return;

        // The metadata binder may run on the same stack as stock delivery or commit. Never mutate it there.
        Utils.runOnMainThread(() -> {
            var key = occurrence.retained();
            Reply reply;
            boolean owned;
            synchronized (occurrence.queue()) {
                owned = PlayerFallback.ownsWatchOccurrence(occurrence);
                reply = owned ? key.turn().reply(key) : null;
            }
            if (owned) activate(host, occurrence, reply);
            else witness("activation", "stale-occurrence");
        });
    }

    private static void activate(Host host, PlayerFallback.WatchOccurrence occurrence, @Nullable Reply reply) {
        synchronized (occurrence.queue()) {
            if (!PlayerFallback.ownsWatchOccurrence(occurrence)) return;
        }

        Scope scope = current;
        if (scope == null || !sameOccurrence(scope.occurrence, occurrence)) {
            cancel();
            scope = new Scope(host, reply, occurrence);
            current = scope;
        } else if (reply != null) {
            scope.local = reply;
        }

        if (scope.remoteTabs != null) appendBoundTabs(scope, scope.remoteTabs);
        start();
    }

    static void witness(String edge, String reason) {
        Logger.printDebug(() -> "OfflineWatchMetadata: " + edge + " reason=" + reason);
    }

    static boolean sameOccurrence(PlayerFallback.WatchOccurrence a, PlayerFallback.WatchOccurrence b) {
        var retained = a.retained();
        return a.owner() == b.owner() && a.queue() == b.queue() && a.row() == b.row()
                && a.request() == b.request() && a.generation() == b.generation()
                && (retained == null ? b.retained() == null : retained.matches(b.retained()));
    }

    static boolean matchesResponse(String videoId, @Nullable String replyId) {
        return videoId.equals(replyId);
    }

    static boolean currentReaction(long readGeneration, long currentGeneration, Object readOwner, Object owner) {
        return readGeneration == currentGeneration && readOwner == owner;
    }

    public static void reactionOwner(Object owner) {
        if (owner instanceof Reaction view && reaction.get() != view) reaction = new WeakReference<>(view);
    }

    public static void reactionChanged() {
        reactionGeneration.incrementAndGet();
    }

    static void invalidate() {
        if (current == null) return;
        Utils.runOnMainThread(() -> {
            if (current != null && !owns(current)) {
                cancel();
                current = null;
            }
        });
    }

    static void networkChanged(boolean online) {
        validationEpoch++;
        if (!online) cancel();
        else start();
    }

    private static void cancel() {
        if (current != null && current.future != null) {
            ListenableFuture<?> fetch = current.future;
            current.future = null;
            fetch.cancel(false);
        }
    }

    private static boolean owns(Scope scope) {
        synchronized (scope.occurrence.queue()) {
            return scope == current && PlayerFallback.ownsWatchOccurrence(scope.occurrence);
        }
    }

    private static void start() {
        Scope scope = current;
        if (scope == null || !PlaybackConnectivity.isValidated() || scope.lastAttempt == validationEpoch
                || !owns(scope)) return;
        long epoch = validationEpoch;
        Reply local = scope.local;
        scope.lastAttempt = epoch;
        Utils.runOnBackgroundThread(() -> {
            try {
                String playlistId = local == null ? null : local.rvxMetadataPlaylistId();
                byte[] command = command(scope.occurrence.videoId(), playlistId);
                Utils.runOnMainThread(() -> dispatch(scope, epoch, command));
            } catch (Exception | LinkageError failure) {
                Logger.printException(() -> "OfflineWatchMetadata: could not prepare watch metadata", failure);
            }
        });
    }

    private static void dispatch(Scope scope, long epoch, byte[] command) {
        if (!usable(scope, epoch)) {
            witness("dispatch", "stale-scope-or-validation");
            return;
        }
        try {
            Reaction owner = reaction.get();
            ReactionRead read = owner == null ? null
                    : new ReactionRead(owner, owner.rvxReactionState(), reactionGeneration.get());
            ListenableFuture<?> future = scope.host.rvxFetchWatchMetadata(command);
            scope.future = future;
            if (BaseSettings.DEBUG.get()) {
                String videoId = scope.occurrence.videoId();
                Logger.printDebug(() -> "OfflineWatchMetadata: requested tabs v=" + videoId);
            }
            future.addListener(() -> readReply(scope, epoch, future, read), Utils::runOnBackgroundThread);
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineWatchMetadata: could not read watch metadata", failure);
        }
    }

    private static boolean usable(Scope scope, long epoch) {
        return PlaybackConnectivity.isValidated() && validationEpoch == epoch && owns(scope);
    }

    /** What a served reply gives for the song: the legacy tabs, the reaction, and the redesigned player's content. */
    record Decoded(List<?> tabs, @Nullable Object renderer, @Nullable ReconnectSurfaces.Prepared surfaces) { }

    /** Worker side. Null when the reply is not the song's own; nothing of it is read then. */
    @Nullable
    static Decoded decode(Host host, Object response, String videoId, boolean wantsReaction) {
        Reply remote = host.rvxDecodeWatchMetadata(response);
        if (!matchesResponse(videoId, remote.rvxMetadataVideoId())) {
            witness("completion", "wrong-response-video");
            return null;
        }
        return new Decoded(remote.rvxMetadataTabs(), wantsReaction ? remote.rvxMetadataReaction() : null,
                ReconnectSurfaces.prepare(remote, videoId));
    }

    private static void readReply(Scope scope, long epoch, ListenableFuture<?> future, @Nullable ReactionRead read) {
        if (future.isCancelled()) {
            witness("completion", "cancelled");
            return;
        }
        try {
            Decoded decoded = decode(scope.host, future.get(), scope.occurrence.videoId(), read != null);
            if (decoded != null) Utils.runOnMainThread(() -> publish(scope, epoch, future, decoded, read));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (CancellationException failure) {
            witness("completion", "cancelled");
        } catch (ExecutionException failure) {
            witness("completion", "transport-failed");
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineWatchMetadata: could not parse watch metadata", failure);
        }
    }

    private static void publish(Scope scope, long epoch, ListenableFuture<?> future, Decoded decoded,
                                @Nullable ReactionRead read) {
        String stale = completionRejection(scope, epoch, future);
        if (stale != null) {
            witness("completion", stale);
            return;
        }

        List<?> remoteTabs = decoded.tabs();
        scope.future = null;
        scope.remoteTabs = remoteTabs;
        String videoId = scope.occurrence.videoId();
        try {
            boolean bound = boundView(scope, binding) != null;
            boolean refreshed = appendBoundTabs(scope, remoteTabs);

            Object renderer = decoded.renderer();
            if (renderer != null && read != null && currentReaction(read.generation(),
                    reactionGeneration.get(), read.owner(), reaction.get()) && usable(scope, epoch)) {
                read.owner().rvxShowWatchReaction(renderer, videoId, read.state());
            }
            if (decoded.surfaces() != null && usable(scope, epoch)) {
                var surfaces = new ReconnectSurfaces.PlayerBinding(epoch, decoded.surfaces());
                scope.surfaces = surfaces;
                publishSurfaces(scope, surfaces, ReconnectSurfaces.currentPresenter());
            }
            if (BaseSettings.DEBUG.get()) {
                Logger.printDebug(() -> "OfflineWatchMetadata: fetched tabs v=" + videoId + " tabs=" + remoteTabs.size()
                        + " bound=" + bound);
                if (refreshed) Logger.printDebug(() -> "OfflineWatchMetadata: refreshed tabs v=" + videoId);
            }
        } catch (Exception | LinkageError failure) {
            Logger.printException(() -> "OfflineWatchMetadata: could not apply watch metadata", failure);
        }
    }

    /** The native player has opened or rebound using a Watch reply; never issue another Watch request here. */
    static void presenterBound(ReconnectSurfaces.Presenter presenter, String videoId, Object stockReply) {
        Scope scope = current;
        if (scope == null || !scope.occurrence.videoId().equals(videoId)) return;

        // Finish the stock bind before applying the prepared metadata, then recheck occurrence and network.
        Utils.runOnMainThread(() -> {
            var surfaces = scope.surfaces;
            if (ReconnectSurfaces.currentPresenter() != presenter || surfaces == null
                    || !surfaces.needsLyrics(presenter, validationEpoch, scope.local, stockReply)
                    || !usable(scope, surfaces.epoch)) return;

            witness("lyrics-late-bind", "owned-reply");
            publishSurfaces(scope, surfaces, presenter);
        });
    }

    private static void publishSurfaces(Scope scope, ReconnectSurfaces.PlayerBinding binding,
                                        @Nullable ReconnectSurfaces.Presenter presenter) {
        String videoId = scope.occurrence.videoId();
        var result = ReconnectSurfaces.publish(scope.host, videoId, binding.prepared, presenter);
        if (!usable(scope, binding.epoch)) return;

        if (result.lyricsBound()) binding.lyricsBound(presenter);
        scope.related = result.command();
        ReconnectSurfaces.log(videoId, result);
        if (result.command() != null) ReconnectSurfaces.refreshTitle();
    }

    @Nullable
    private static String completionRejection(Scope scope, long epoch, ListenableFuture<?> future) {
        return completionRejection(PlaybackConnectivity.isValidated(), validationEpoch == epoch, owns(scope),
                scope.future == future);
    }

    @Nullable
    static String completionRejection(boolean validated, boolean sameEpoch, boolean owned, boolean sameFuture) {
        if (!validated) return "unvalidated";
        if (!sameEpoch) return "stale-validation";
        if (!owned) return "stale-occurrence";
        return sameFuture ? null : "stale-future";
    }

    @Nullable
    private static View boundView(Scope scope, @Nullable Binding visible) {
        return visible != null && binds(scope, visible.reply(), visible.retained()) ? visible.view().get() : null;
    }

    private static boolean binds(Scope scope, Reply reply, @Nullable PlayerFallback.WatchKey viewOwner) {
        var retained = scope.occurrence.retained();
        return reply == scope.local && (retained == null ? viewOwner == null : retained.matches(viewOwner));
    }

    private static boolean appendBoundTabs(Scope scope, List<?> remoteTabs) {
        Binding visible = binding;
        View view = boundView(scope, visible);
        if (view == null || !owns(scope)) return false;

        List<?> mounted = appendTabs(visible.stock(), remoteTabs);
        if (mounted == visible.stock()) return false;
        for (int index = visible.stock().size(); index < mounted.size(); index++) {
            if (binding != visible || !owns(scope)) return false;
            view.rvxAppendWatchTab(mounted.get(index));
        }
        if (binding == visible) binding = new Binding(visible.view(), visible.reply(), mounted, visible.retained());
        return true;
    }

    /**
     * The title command that the served response gave the song playing now, or null. Asked by the player's title in
     * place of its own (empty) answer, so the exact song that was read and still owns the queue row is the only one
     * it is ever given for: an earlier song's scope is no longer current, and no later song has one yet.
     */
    @Nullable
    static Object relatedCommand() {
        Scope scope = current;
        return scope != null && scope.related != null && owns(scope) ? scope.related : null;
    }

    /** Rebinding the same local Watch page must not remove tabs already delivered for that occurrence. */
    public static List<?> visibleTabs(List<?> stock, Object view, Object response) {
        if (!(view instanceof View nativeView) || !(response instanceof Reply reply)) {
            binding = null;
            return stock;
        }
        Object request = reply.rvxLocalWatchRequest();
        if (request == null) {
            binding = null;
            return stock;
        }
        Scope scope = current;
        var retained = PlayerFallback.retainedWatchKey(request);
        List<?> merged = scope != null && scope.remoteTabs != null && binds(scope, reply, retained) && owns(scope)
                ? mergeTabs(stock, scope.remoteTabs) : null;
        List<?> mounted = merged == null ? stock : merged;
        binding = new Binding(new WeakReference<>(nativeView), reply, mounted, retained);
        return mounted;
    }

    /** Retain existing views, even on a second reconnect or after a native bind already added metadata. */
    static List<?> appendTabs(List<?> mounted, List<?> remote) {
        List<?> missing = mergeTabs(mounted, remote, false);
        return missing == null ? mounted : missing;
    }

    @Nullable
    static List<?> mergeTabs(List<?> local, List<?> remote) {
        return mergeTabs(local, remote, true);
    }

    @Nullable
    private static List<?> mergeTabs(List<?> local, List<?> remote, boolean replaceExisting) {
        if (local.isEmpty()) return null;
        Object lyrics = null;
        Object related = null;
        for (Object item : remote) {
            if (!(item instanceof Tab tab)) continue;
            int type = tab.rvxMetadataTabType();
            if (type == LYRICS) lyrics = item;
            else if (type == RELATED) related = item;
        }
        if (!replaceExisting) {
            for (Object item : local) {
                int type = item instanceof Tab tab ? tab.rvxMetadataTabType() : 0;
                if (type == LYRICS) lyrics = null;
                else if (type == RELATED) related = null;
            }
        }
        if (lyrics == null && related == null) return null;
        List<Object> merged = new ArrayList<>(local.size() + 2);
        // Keep Up Next and any other stock-local views, including their exact renderer objects.
        for (Object item : local) {
            int type = item instanceof Tab tab ? tab.rvxMetadataTabType() : 0;
            if ((type == LYRICS && lyrics != null) || (type == RELATED && related != null)) continue;
            merged.add(item);
        }
        if (lyrics != null) merged.add(lyrics);
        if (related != null) merged.add(related);
        return merged;
    }

    private static byte[] command(String videoId, @Nullable String playlistId) {
        String source = OfflineReplay.sourceOf(playlistId);
        if (source == null) source = playlistId;
        if (source != null && (source.startsWith("PL") || source.startsWith("OLAK5uy_"))) {
            OfflineSourceStore.Source saved = OfflineCopyStore.sourceForOpening(source);
            if (saved != null && source.equals(saved.id())) {
                int ordinal = saved.videoIds().indexOf(videoId);
                if (ordinal >= 0 && ordinal == saved.videoIds().lastIndexOf(videoId)) {
                    return OnlineWatchCommand.build(videoId, source, ordinal);
                }
            }
        }
        return OnlineWatchCommand.build(videoId, null, 0);
    }
}
