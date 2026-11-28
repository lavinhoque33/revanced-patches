/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Keeps the fork's own player response when the stock refresh writes a declined one.
 * The stock row remains the only cache; only the response the stock selector reads is preserved.
 */
public final class NativeOfflinePolicy {
    private static final Map<String, byte[]> responses = new ConcurrentHashMap<>();

    private NativeOfflinePolicy() {}

    static void remember(String videoId, byte[] response) {
        responses.put(videoId, response);
    }

    /** Called from Laanu.a() on 9.45.51; keeps the expiry override scoped to the opt-in native index. */
    public static boolean nativeOfflineIndexEnabled() {
        return Settings.NATIVE_OFFLINE_INDEX.get();
    }

    /** Called from aafw.B on 9.45.51 before the native write; fills a missing offline state. */
    public static void attachPlayableState(String videoId, Object response) {
        if (!Settings.NATIVE_OFFLINE_INDEX.get() || response == null) return;
        try {
            if (NativeOfflineDirect.responseState(response) != null) return;

            NativeOfflineDirect.attachState(response, NativeOfflineDirect.newPlayableState());
            Logger.printInfo(() -> "Native offline availability: attached playable state v=" + videoId);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Native offline availability: cannot attach a playable state: " + ex);
        }
    }

    /** Called from Laahk.m on 9.45.51 with the refreshed video id and the stock player response. */
    public static Object keepImportedResponse(String videoId, Object incoming) {
        if (videoId == null || incoming == null || !Settings.NATIVE_OFFLINE_INDEX.get()) return incoming;

        byte[] response = responses.get(videoId);
        if (response == null) return incoming;

        try {
            Object kept = NativeOfflineDirect.parsePlayerResponse(response);
            try {
                NativeOfflineDirect.ensurePlayableState(kept, incoming);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Native offline policy: cannot attach the playable state: " + ex);
            }
            return kept;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Native offline policy: cannot restore the kept player response: " + ex);
            return incoming;
        }
    }
}
