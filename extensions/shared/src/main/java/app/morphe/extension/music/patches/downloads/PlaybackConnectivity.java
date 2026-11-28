/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-14.
 * Written by lavinhoque33, 2026-11-14.
 */
package app.morphe.extension.music.patches.downloads;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import java.util.Collection;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** One process callback, with only pending request identities retained; no offline-session state. */
public final class PlaybackConnectivity {
    private static final PendingPlaybackRequests PENDING = new PendingPlaybackRequests();
    private static ConnectivityManager.NetworkCallback callback;
    private static volatile boolean validated;
    private static long reconnectEpoch;
    private static volatile Runnable verdictListener;

    private PlaybackConnectivity() { }

    static void start() {
        register();
        UpstreamProbe.probeUnlessFresh("start");
    }

    static void watch(Object request, PlayerFallbackFuture future, boolean next) {
        Object ownership = PlayerFallback.ownership(request);
        if (ownership == null || !future.pending()) return;
        future.watch(request, ownership, PENDING, next);
        if (register()) checkCurrentNetwork();
        else PENDING.remove(future);
    }

    static void watch(PendingPlaybackRequests.Entry entry) {
        if (entry.ownership() == null || !entry.pending()) return;
        PENDING.add(entry);
        if (register()) checkCurrentNetwork();
        else PENDING.remove(entry);
    }

    static void finished(PendingPlaybackRequests.Entry entry) {
        PENDING.remove(entry);
    }

    private static void checkCurrentNetwork() {
        checkNetwork(() -> !osReachable());
    }

    /** Android's own state: the network is connected and validated. */
    public static boolean osReachable() {
        return Utils.isInternetReachable();
    }

    /**
     * Whether YouTube can be reached, for a decision that has no one item in it (the next one of the queue, the
     * lists of the car): Android says so and the last probe did not find it dead.
     */
    public static boolean reachable() {
        return osReachable() && !UpstreamProbe.dead();
    }

    /** The last probe found YouTube unreachable, whatever Android says. */
    public static boolean upstreamDead() {
        return UpstreamProbe.dead();
    }

    /** Asks beside the caller whether YouTube can be reached, unless that was proved a moment ago. */
    public static void probeUnlessFresh(String reason) {
        UpstreamProbe.probeUnlessFresh(reason);
    }

    /**
     * Watch an online start of a downloaded item. A still-unanswered tap can be redispatched from its copy
     * without changing the upstream verdict; a player answer, new selection or network change wins instead.
     *
     * @param videoIds The songs the start plays first; empty when the navigation alone tells its requests apart.
     */
    public static void watchStart(String label, Collection<String> videoIds, BooleanSupplier current,
                                  Runnable again) {
        UpstreamProbe.watchTap(label, videoIds, current, again);
    }

    /** True only on the stack redispatching a previously unanswered downloaded tap. */
    static boolean replayingUnansweredTap() {
        return UpstreamProbe.localTapDispatch();
    }

    /**
     * Whether YouTube can be reached for one item. A dead verdict only takes away the online way of an item that
     * has a copy to play instead: anything else keeps the stock way, which is an error when nothing answers.
     */
    static boolean reachableForTarget(boolean holdsCopy) {
        return osReachable() && !(holdsCopy && (UpstreamProbe.dead() || replayingUnansweredTap()));
    }

    /** {@link #reachableForTarget} for a player or watch-next request. */
    static boolean reachableFor(Object request) {
        return osReachable() && !((UpstreamProbe.dead() || replayingUnansweredTap())
                && PlayerFallback.holdsRequest(request));
    }

    /** The verdict about YouTube changed: the same path as Android's own network change. */
    static void verdictChanged() {
        checkCurrentNetwork();
        Runnable listener = verdictListener;
        if (listener != null) listener.run();
    }

    /** The car's lists follow the verdict as they follow the network: told on every change of it. */
    public static void listenForVerdict(Runnable listener) {
        verdictListener = listener;
    }

    static boolean isValidated() {
        return validated && !UpstreamProbe.dead();
    }

    /** Main-thread generation for work that must not outlive a return to the connected Library. */
    static long reconnectEpoch() {
        return reconnectEpoch;
    }

    private static void checkNetwork(BooleanSupplier offline) {
        Utils.runOnMainThreadNowOrLater(() -> {
            if (!OfflineReplay.enabled()) return;

            boolean playbackOffline = offline.getAsBoolean() || UpstreamProbe.dead();
            // A new Android-validated network need not wait for a separate probe to make a metadata request.
            boolean online = !playbackOffline;
            if (validated != online) {
                validated = online;
                if (online) {
                    reconnectEpoch++;
                    PlayerFallback.reconnected();
                } else {
                    QueueAddTail.wentOffline();
                }
                OfflineWatchMetadata.networkChanged(online);
            }
            if (playbackOffline) PENDING.disconnected(PlayerFallback::owns,
                    request -> request instanceof PlayerFallback.Request replay
                            && OfflineReplay.mayHold(replay.rvxVideoId()), offline, UpstreamProbe::dead,
                    PlayerFallback::finishUncached);
        });
    }

    @SuppressLint("MissingPermission")
    private static synchronized boolean register() {
        if (callback != null) return true;
        Context context = Utils.getContext();
        if (context == null || Build.VERSION.SDK_INT < 23
                || !(context.getSystemService(Context.CONNECTIVITY_SERVICE) instanceof ConnectivityManager manager)) {
            return false;
        }
        ConnectivityManager.NetworkCallback listener = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                // The lost network can still be returned briefly by the synchronous binder query.
                boolean otherValidated = otherValidatedNetwork(manager, network);
                UpstreamProbe.networkMayHaveChanged(otherValidated);
                if (!otherValidated) {
                    checkNetwork(() -> !otherValidatedNetwork(manager, network));
                }
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                UpstreamProbe.networkMayHaveChanged(osReachable());
                checkCurrentNetwork();
            }
        };
        try {
            validated = reachable();
            if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(listener);
            else manager.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), listener);
            callback = listener;
            return true;
        } catch (Exception failure) {
            Logger.printException(() -> "PlayerFallback: connectivity callback could not be registered", failure);
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private static boolean otherValidatedNetwork(ConnectivityManager manager, Network changed) {
        try {
            Network active = manager.getActiveNetwork();
            return active != null && !active.equals(changed) && validated(manager.getNetworkCapabilities(active));
        } catch (Exception unknown) {
            // Unknown state preserves stock handling rather than treating a handover as an outage.
            return true;
        }
    }

    private static boolean validated(NetworkCapabilities capabilities) {
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
