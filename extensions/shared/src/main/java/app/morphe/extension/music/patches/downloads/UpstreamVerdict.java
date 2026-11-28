/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.Set;

/**
 * What the app knows about YouTube being reachable, apart from what Android says. Android keeps a network
 * validated while a firewall, a captive filter or a dead route stops every YouTube request: this verdict is the
 * second opinion. It holds only the state and the rules for changing it; the probe itself, the timers and the
 * threads belong to {@link UpstreamProbe}. Every method takes the time, so a test controls it.
 */
final class UpstreamVerdict {
    enum State { UNKNOWN, ALIVE, DEAD }
    enum Answer { REACHED, UNREACHABLE, INCONCLUSIVE }

    /** What a call changed, for the caller to announce. */
    enum Change { NONE, BECAME_DEAD, STAYED_DEAD, BECAME_ALIVE, PROVED_ALIVE, RESET, DROPPED_DEAD }

    /** One probe run: its number, and the network epoch it started in. */
    record Attempt(long id, long epoch) { }

    /**
     * A change, and the state it belongs to. Every change of the state (and of the network) has a new generation,
     * so a change delivered later than a newer one is recognised as stale: see {@link #isCurrent}.
     */
    record Transition(Change change, long generation) { }

    /**
     * A start of a downloaded item that went the online way while the verdict was not dead.
     *
     * @param at         When the start was made.
     * @param epoch      The network epoch it was made in.
     * @param navigation The player's navigation generation right after the start was made.
     * @param videoIds   The songs the start plays first, or empty when only the navigation tells (a fresh start
     *                   moves the navigation on, so a request of an earlier selection never has it).
     */
    record Start(long at, long epoch, long navigation, Set<String> videoIds) {
        /**
         * Whether a player answer handed to the app belongs to this start: made for a request of the same
         * navigation, after the start, and for one of its songs when those are known. A playlist-only request
         * (no song in it) of the same navigation is this start's own.
         */
        boolean answeredBy(long now, long requestNavigation, String videoId) {
            if (now < at || requestNavigation != navigation) return false;

            return videoIds.isEmpty() || videoId.isEmpty() || videoIds.contains(videoId);
        }

        /**
         * Whether the start, never answered, is run again from its copies when the verdict turns dead: it is young,
         * still the latest selection, and neither the network nor the player's navigation moved since.
         */
        boolean redoable(long now, long currentEpoch, long currentNavigation, boolean current) {
            long age = now - at;
            return age >= 0 && age <= TAP_WINDOW_MS && current
                    && currentEpoch == epoch && currentNavigation == navigation;
        }
    }

    /** An ALIVE verdict this young is not probed again by a tap. */
    static final long FRESH_MS = 2_000;
    /** Two probes never start closer than this. */
    static final long MIN_START_GAP_MS = 2_000;
    /** A probe thread that is still blocked after this long (DNS) does not stop the next one from starting. */
    static final long STUCK_MS = 8_000;
    static final long FIRST_REPROBE_MS = 10_000;
    static final long MAX_REPROBE_MS = 60_000;
    /** How long a start of a downloaded item waits for a verdict before it is left to the online start. */
    static final long TAP_WINDOW_MS = 6_000;

    private State state = State.UNKNOWN;
    private long since;
    @Nullable private Object network;
    private long epoch;
    private long generation;
    private long lastAttemptId;
    private boolean lastAttemptSettled = true;
    private boolean running;
    private long runningSince;
    private boolean started;
    private long lastStartAt;
    private int deadRuns;

    synchronized State state() {
        return state;
    }

    synchronized boolean dead() {
        return state == State.DEAD;
    }

    @Nullable
    synchronized Object network() {
        return network;
    }

    synchronized long epoch() {
        return epoch;
    }

    /** Whether nothing changed the state or the network since the transition: it is not stale. */
    synchronized boolean isCurrent(Transition transition) {
        return transition.generation() == generation;
    }

    /** The delay of the next probe while the verdict is dead: 10 s, doubling up to a minute. */
    synchronized long reprobeDelayMs() {
        int doublings = Math.min(Math.max(deadRuns - 1, 0), 3);
        return Math.min(FIRST_REPROBE_MS << doublings, MAX_REPROBE_MS);
    }

    /**
     * Claims the right to run one probe.
     *
     * @param skipFresh Do not probe when the verdict is ALIVE and younger than {@link #FRESH_MS}.
     * @return The attempt to run, or null when a probe is not needed or not allowed now.
     */
    @Nullable
    synchronized Attempt begin(long now, boolean skipFresh) {
        if (skipFresh && state == State.ALIVE && now - since < FRESH_MS) return null;
        if (running && now - runningSince < STUCK_MS) return null;
        if (started && now - lastStartAt < MIN_START_GAP_MS) return null;

        started = true;
        lastStartAt = now;
        running = true;
        runningSince = now;
        lastAttemptSettled = false;
        lastAttemptId++;
        return new Attempt(lastAttemptId, epoch);
    }

    /** The probe thread is done, whatever it found. */
    synchronized void finished(Attempt attempt) {
        if (attempt.id() == lastAttemptId) running = false;
    }

    /**
     * Only a concrete failure proves DEAD; elapsed time cannot distinguish packet loss from a slow network.
     * An answer found on another network says nothing about the current network.
     */
    synchronized Transition settle(Attempt attempt, Answer answer, @Nullable Object probedNetwork, long now) {
        if (attempt.epoch() != epoch || attempt.id() != lastAttemptId || lastAttemptSettled) {
            return transition(Change.NONE);
        }
        lastAttemptSettled = true;
        if (probedNetwork != null && network != null && !network.equals(probedNetwork)) {
            return transition(Change.NONE);
        }
        return transition(switch (answer) {
            case REACHED -> markAlive(probedNetwork, now);
            case UNREACHABLE -> markDead(probedNetwork, now);
            case INCONCLUSIVE -> Change.NONE;
        });
    }

    /**
     * The default network is now this one (null: none). A verdict belongs to the network it was formed on, so a
     * different one starts again from UNKNOWN and every probe still running for the old one is ignored.
     */
    synchronized Transition networkIs(@Nullable Object current, long now) {
        if (network == null && current != null) {
            network = current;
            return transition(Change.NONE);
        }
        if (network == null || network.equals(current)) return transition(Change.NONE);

        epoch++;
        generation++;
        boolean wasDead = state == State.DEAD;
        state = State.UNKNOWN;
        since = now;
        network = current;
        deadRuns = 0;
        started = false;
        running = false;
        lastAttemptSettled = true;
        return transition(wasDead ? Change.DROPPED_DEAD : Change.RESET);
    }

    private Transition transition(Change change) {
        return new Transition(change, generation);
    }

    private Change markAlive(@Nullable Object probedNetwork, long now) {
        State before = state;
        if (before != State.ALIVE) generation++;
        state = State.ALIVE;
        since = now;
        deadRuns = 0;
        if (probedNetwork != null) network = probedNetwork;
        return switch (before) {
            case DEAD -> Change.BECAME_ALIVE;
            case UNKNOWN -> Change.PROVED_ALIVE;
            case ALIVE -> Change.NONE;
        };
    }

    private Change markDead(@Nullable Object probedNetwork, long now) {
        boolean wasDead = state == State.DEAD;
        state = State.DEAD;
        deadRuns++;
        if (probedNetwork != null) network = probedNetwork;
        if (wasDead) return Change.STAYED_DEAD;

        generation++;
        since = now;
        return Change.BECAME_DEAD;
    }
}
