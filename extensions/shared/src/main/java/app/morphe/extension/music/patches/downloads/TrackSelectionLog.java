/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Set;
import java.util.function.Supplier;

import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.utils.Logger;

/**
 * DEBUG AID: logs what the app's own player selection does with the formats of a video copy, so a device log says
 * why a video track is, or is not, selected. Offline the stored answer of a copy plays its audio but no video track is
 * selected, online the same pair gets one.
 *
 * <p>Both entries are injected into the app's one method that filters the formats of a response by mime type, the
 * allowed itags, the codec sets and the quality limits, and reports the result of that filter (a player of the same
 * shape is found by the string of its viewport failure report): the start gets the formats the response offers, the
 * itags the app allows and its quality limits; the return gets the formats that remain. Nothing is changed. A line is
 * written when it differs from the last one, so a toggle that selects again shows only what changed.
 */
@SuppressWarnings("unused")
public final class TrackSelectionLog {
    private static final int MAX_LINE = 600;

    private static final Object lock = new Object();
    private static String lastLine = "";

    private TrackSelectionLog() {}

    /**
     * Injection point: the start of the format filter.
     *
     * @param videos The video formats of the response, {@code audios} its audio formats.
     * @param limits The quality limits the app applies.
     * @param videoItags The video itags the app allows; an empty set allows none.
     */
    public static void onFilter(@Nullable Object config, @Nullable Collection<?> videos,
                                @Nullable Collection<?> audios, @Nullable Object limits,
                                @Nullable Set<?> videoItags, @Nullable Set<?> audioItags) {
        try {
            if (!StreamSwap.enabled()) return;

            log(() -> "TrackSelection: filter v=" + VideoInformation.getVideoId() + " offeredVideo="
                    + size(videos) + " offeredAudio=" + size(audios) + " allowedVideoItags=" + videoItags
                    + " allowedAudioItags=" + audioItags + " limits=" + describe(limits, 1));
        } catch (Exception ex) {
            Logger.printException(() -> "TrackSelectionLog: filter start failure", ex);
        }
    }

    /**
     * Injection point: every return of the format filter.
     *
     * @param result What remains: the arrays of its fields are the video and audio formats left.
     */
    public static void onFiltered(@Nullable Object result) {
        try {
            if (!StreamSwap.enabled()) return;

            log(() -> "TrackSelection: result v=" + VideoInformation.getVideoId() + " " + describe(result, 1));
        } catch (Exception ex) {
            Logger.printException(() -> "TrackSelectionLog: filter result failure", ex);
        }
    }

    private static int size(@Nullable Collection<?> collection) {
        return collection == null ? -1 : collection.size();
    }

    private static void log(Supplier<String> line) {
        String text = line.get();
        synchronized (lock) {
            if (text.equals(lastLine)) return;
            lastLine = text;
        }
        Logger.printDebug(() -> text);
    }

    /**
     * The fields of an object of the app, by their obfuscated names: numbers, booleans and strings by value, arrays
     * and collections by length, and (one level, when {@code depth} allows) the app's own objects in braces.
     */
    private static String describe(@Nullable Object value, int depth) {
        if (value == null) return "null";

        StringBuilder out = new StringBuilder(value.getClass().getSimpleName()).append('{');
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;

            try {
                field.setAccessible(true);
                out.append(field.getName()).append('=').append(describeField(field.get(value), depth)).append(' ');
            } catch (Exception ignored) {
                out.append(field.getName()).append("=? ");
            }
            if (out.length() > MAX_LINE) return out.append("...}").toString();
        }
        return out.append('}').toString();
    }

    private static String describeField(@Nullable Object field, int depth) {
        if (field == null) return "null";
        if (field.getClass().isArray()) return "[" + Array.getLength(field) + "]";
        if (field instanceof Collection<?> collection) return "#" + collection.size();
        if (field instanceof Number || field instanceof Boolean || field instanceof CharSequence) {
            return field.toString();
        }

        String type = field.getClass().getName();
        boolean ownObject = !type.startsWith("java.") && !type.startsWith("android.") && !type.startsWith("j$.");
        return ownObject && depth > 0 ? describe(field, depth - 1) : field.getClass().getSimpleName();
    }
}
