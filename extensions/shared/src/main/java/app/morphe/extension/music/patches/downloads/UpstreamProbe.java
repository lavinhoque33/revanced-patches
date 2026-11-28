/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Asks YouTube whether it can be reached, for the networks Android calls validated while every YouTube request fails
 * (a firewall, a filtered DNS, a dead route). The answer is {@link UpstreamVerdict}'s; this class runs the probe and
 * announces what changed.
 *
 * <p>The probe never runs on the main thread and never sits in a request's path: a tap, a page, a request or a
 * failure only starts one beside what it does, and the answer comes later, to whoever is waiting for it.
 */
final class UpstreamProbe {
    /** The Innertube host every player, watch-next and browse request uses; answers 204 with no body. */
    private static final String PROBE_URL = "https://youtubei.googleapis.com/generate_204";
    private static final int IO_TIMEOUT_MS = 5_000;
    private static final long TAP_FALLBACK_MS = 900;

    /** A start of a downloaded item that went online while the verdict was not dead, and how to run it again. */
    record Tap(UpstreamVerdict.Start start, String label, BooleanSupplier current, Runnable redo) {
        boolean acceptAnswer(AtomicReference<Tap> pending, long now, long navigation, String videoId) {
            return start.answeredBy(now, navigation, videoId) && pending.compareAndSet(this, null);
        }

        boolean takeForReplay(AtomicReference<Tap> pending, long now, long epoch, long navigation) {
            return pending.get() == this && start.redoable(now, epoch, navigation, current.getAsBoolean())
                    && pending.compareAndSet(this, null);
        }
    }

    private static final UpstreamVerdict verdict = new UpstreamVerdict();
    private static final AtomicReference<Tap> pendingTap = new AtomicReference<>();
    private static volatile long reprobeToken;
    private static final ThreadLocal<Boolean> localTapDispatch = new ThreadLocal<>();

    private UpstreamProbe() { }

    /** Whether the last verdict is that YouTube cannot be reached. Android's own state is not part of it. */
    static boolean dead() {
        return verdict.dead();
    }

    static boolean localTapDispatch() {
        return Boolean.TRUE.equals(localTapDispatch.get());
    }

    /** Probes unless the verdict was proved a moment ago. */
    static void probeUnlessFresh(String reason) {
        start(reason, true);
    }

    /** Probes now, but never two probes at once and never two closer than a few seconds. */
    static void probe(String reason) {
        start(reason, false);
    }

    /**
     * An unanswered downloaded start may use its local copy after a short wait. This is a decision about this
     * tap, not evidence about the network. The latest tap and its first player answer race for the same claim.
     * Called right after the start is made, so the navigation it reads is the start's own.
     *
     * @param videoIds The songs the start plays first, or empty when only its navigation identifies it.
     */
    static void watchTap(String label, Collection<String> videoIds, BooleanSupplier current, Runnable redo) {
        UpstreamVerdict.Start start = new UpstreamVerdict.Start(SystemClock.elapsedRealtime(), verdict.epoch(),
                PlayerFallback.navigationGeneration(), Set.copyOf(videoIds));
        Tap tap = new Tap(start, label, current, redo);
        pendingTap.set(tap);
        probeUnlessFresh(label);
        Utils.runOnMainThreadDelayed(() -> redoTap(tap, "unanswered"), TAP_FALLBACK_MS);
    }

    /**
     * A player answer, online or from a copy, was handed to the app for a request of this navigation. When it is
     * the watched start's own, that start works: it is never run again.
     */
    static void notePublished(long requestNavigation, String videoId) {
        Tap tap = pendingTap.get();
        if (tap != null && tap.acceptAnswer(pendingTap, SystemClock.elapsedRealtime(), requestNavigation, videoId)) {
            Logger.printDebug(() -> "UpstreamProbe: tap " + tap.label() + " answered v=" + videoId);
        }
    }

    /**
     * The stock way answered while the verdict is dead. That is no proof (the stock lookup reads stored responses
     * too): only a probe on the default network may end the verdict, so this only asks for one.
     */
    static void noteStockAnswer() {
        if (verdict.dead()) probe("answer");
    }

    /** The default network may have changed: a verdict does not outlive its network. */
    static void networkMayHaveChanged(boolean osOnline) {
        Network network = osOnline ? activeNetwork() : null;
        announce(verdict.networkIs(network, SystemClock.elapsedRealtime()), "network");
        if (network != null) probeUnlessFresh("network");
    }

    private static void start(String reason, boolean skipFresh) {
        if (!OfflineReplay.enabled() || !PlaybackConnectivity.osReachable()) return;

        UpstreamVerdict.Attempt attempt = verdict.begin(SystemClock.elapsedRealtime(), skipFresh);
        if (attempt == null) return;

        Utils.runOnBackgroundThread(() -> run(attempt, reason));
    }

    /** On a worker thread: the one place the network is touched. */
    private static void run(UpstreamVerdict.Attempt attempt, String reason) {
        Network network = activeNetwork();
        long began = SystemClock.elapsedRealtime();
        UpstreamVerdict.Answer answer;
        String outcome;
        try {
            int status = request(network);
            outcome = "http=" + status;
            answer = status >= 100 ? UpstreamVerdict.Answer.REACHED : UpstreamVerdict.Answer.INCONCLUSIVE;
        } catch (IOException | RuntimeException failure) {
            outcome = failure.getClass().getSimpleName();
            answer = failureAnswer(failure);
        } finally {
            verdict.finished(attempt);
        }

        long ms = SystemClock.elapsedRealtime() - began;
        String result = outcome;
        UpstreamVerdict.Answer observed = answer;
        Logger.printDebug(() -> "UpstreamProbe: probe " + reason + " " + result + " ms=" + ms
                + " answer=" + observed);
        announce(verdict.settle(attempt, answer, network, SystemClock.elapsedRealtime()), reason + " " + result);
    }

    static UpstreamVerdict.Answer failureAnswer(Throwable failure) {
        boolean unreachable = false;
        for (int depth = 0; depth < 16 && failure != null; depth++, failure = failure.getCause()) {
            if (failure instanceof SocketTimeoutException) return UpstreamVerdict.Answer.INCONCLUSIVE;
            if (failure instanceof ConnectException || failure instanceof NoRouteToHostException
                    || failure instanceof UnknownHostException) unreachable = true;
        }
        return unreachable ? UpstreamVerdict.Answer.UNREACHABLE : UpstreamVerdict.Answer.INCONCLUSIVE;
    }

    private static int request(@Nullable Network network) throws IOException {
        URL url = new URL(PROBE_URL);
        HttpURLConnection connection = (HttpURLConnection) (network != null
                ? network.openConnection(url) : url.openConnection());
        try {
            connection.setConnectTimeout(IO_TIMEOUT_MS);
            connection.setReadTimeout(IO_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static void announce(UpstreamVerdict.Transition transition, String detail) {
        if (transition.change() == UpstreamVerdict.Change.NONE) return;

        Utils.runOnMainThreadNowOrLater(() -> changed(transition, detail));
    }

    /**
     * On the main thread. A transition that a newer one overtook on its way here (a probe answered, the network
     * changed) is stale and does nothing: the newer one is delivered too, and it is the one that counts.
     */
    private static void changed(UpstreamVerdict.Transition transition, String detail) {
        if (!verdict.isCurrent(transition)) {
            Logger.printDebug(() -> "UpstreamProbe: stale " + transition.change() + " " + detail);
            return;
        }

        switch (transition.change()) {
            case BECAME_DEAD -> {
                Logger.printInfo(() -> "UpstreamProbe: verdict=DEAD " + detail);
                scheduleReprobe();
                redoTap(pendingTap.get(), "unreachable");
                PlaybackConnectivity.verdictChanged();
            }
            case STAYED_DEAD -> scheduleReprobe();
            case BECAME_ALIVE -> {
                Logger.printInfo(() -> "UpstreamProbe: verdict=ALIVE again " + detail);
                reprobeToken++;
                PlaybackConnectivity.verdictChanged();
            }
            case PROVED_ALIVE -> PlaybackConnectivity.verdictChanged();
            case DROPPED_DEAD -> {
                Logger.printInfo(() -> "UpstreamProbe: verdict=UNKNOWN network changed " + detail);
                reprobeToken++;
                PlaybackConnectivity.verdictChanged();
            }
            default -> { }
        }
    }

    /** While the verdict is dead, asks again after 10 s, then 20, 40 and every minute: Android never says. */
    private static void scheduleReprobe() {
        long token = ++reprobeToken;
        Utils.runOnMainThreadDelayed(() -> {
            if (token != reprobeToken || !verdict.dead()) return;

            probe("reprobe");
            scheduleReprobe();
        }, verdict.reprobeDelayMs());
    }

    /** Claim only the same unanswered tap. The local routing override lasts for its synchronous redispatch. */
    private static void redoTap(@Nullable Tap tap, String reason) {
        if (tap == null) return;

        long now = SystemClock.elapsedRealtime();
        long age = now - tap.start().at();
        if (!tap.takeForReplay(pendingTap, now, verdict.epoch(), PlayerFallback.navigationGeneration())) {
            pendingTap.compareAndSet(tap, null);
            return;
        }

        Logger.printInfo(() -> "UpstreamProbe: tap " + tap.label() + " age=" + age + " local-start=" + reason);
        PlayerFallback.cancelUnansweredLoad(tap.start().navigation());
        localTapDispatch.set(Boolean.TRUE);
        try {
            tap.redo().run();
        } finally {
            localTapDispatch.remove();
        }
    }

    @Nullable
    private static Network activeNetwork() {
        Context context = Utils.getContext();
        if (context == null || Build.VERSION.SDK_INT < 23
                || !(context.getSystemService(Context.CONNECTIVITY_SERVICE) instanceof ConnectivityManager manager)) {
            return null;
        }
        try {
            return manager.getActiveNetwork();
        } catch (RuntimeException unknown) {
            return null;
        }
    }
}
