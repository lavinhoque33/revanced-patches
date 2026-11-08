/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Map;

import app.morphe.extension.shared.utils.Logger;

/**
 * The entry points the Downloads patch calls from the app's own code. Each one hands over to {@link OfflineReplay}
 * and answers "not ours" when that fails in any way, a class that does not verify or link included: the first failure
 * is logged and turns the replay off for the rest of the process, so the app plays as it did without it. This class
 * must stay small and name nothing but the app's stable types, so that it always loads.
 */
@SuppressWarnings("unused")
public final class OfflineReplayHooks {
    private static volatile boolean broken;

    private OfflineReplayHooks() {}

    /** Enabled by the patch only after every hook of the version is in place. */
    public static boolean supported() {
        return false;
    }

    /** Whether the replay is installed and has not failed in this process. */
    static boolean usable() {
        return supported() && !broken;
    }

    private static void disable(String hook, Throwable failure) {
        boolean first = !broken;
        broken = true;
        if (first) {
            Logger.printException(() -> "OfflineReplay: " + hook + " failed, the replay is off for this process",
                    failure);
        }
    }

    /** Injection point: every command the app dispatches. True when the command was taken and must not run. */
    public static boolean onCommand(Object dispatcher, Object command, Map<?, ?> context) {
        if (!usable()) return false;

        try {
            return OfflineReplay.onCommand(dispatcher, command, context);
        } catch (Throwable failure) {
            disable("command", failure);
            return false;
        }
    }

    /** Injection point: the Library page loads a chip; {@code browseId} is the page the chip resolved to. */
    public static void onLibraryChip(String browseId) {
        if (!usable()) return;

        try {
            OfflineReplay.onLibraryChip(browseId);
        } catch (Throwable failure) {
            disable("library chip", failure);
        }
    }

    /** Injection point: the flag the offline watch gate read. */
    public static void noteGateFlag(boolean flag) {
        if (!usable()) return;

        try {
            OfflineReplay.noteGateFlag(flag);
        } catch (Throwable failure) {
            disable("gate flag", failure);
        }
    }

    /** Injection point: true when the gate must let the offline watch command of this song through. */
    public static boolean holdsForGate(String videoId) {
        if (!usable()) return false;

        try {
            return OfflineReplay.holdsForGate(videoId);
        } catch (Throwable failure) {
            disable("gate", failure);
            return false;
        }
    }

    /** Injection point: the stored player response of the song, ready for the app's parser, or null. */
    @Nullable
    public static byte[] storedForPlayer(String videoId, boolean offlineRequest) {
        if (!usable()) return null;

        try {
            return OfflineReplay.storedForPlayer(videoId, offlineRequest);
        } catch (Throwable failure) {
            disable("player lookup", failure);
            return null;
        }
    }

    /** Injection point: the response built from the stored bytes, swapped to play the copy, or null. */
    @Nullable
    public static Object finishPlayer(Object response, String videoId) {
        if (!usable()) return null;

        try {
            return OfflineReplay.finishPlayer(response, videoId);
        } catch (Throwable failure) {
            disable("player response", failure);
            return null;
        }
    }

    /** Injection point: the watch-next future of a request; the stock future itself unless the request is ours. */
    public static ListenableFuture<?> wrapNext(Object request, ListenableFuture<?> stock) {
        if (!usable()) return stock;

        try {
            return OfflineReplay.wrapNext(request, stock);
        } catch (Throwable failure) {
            disable("watch-next", failure);
            return stock;
        }
    }

    /** Whether the Android Auto and the Library lists may use the replay. */
    public static boolean enabled() {
        if (!usable()) return false;

        try {
            return OfflineReplay.enabled();
        } catch (Throwable failure) {
            disable("settings", failure);
            return false;
        }
    }
}
