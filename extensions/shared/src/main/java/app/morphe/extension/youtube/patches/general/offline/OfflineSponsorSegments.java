/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-05.
 */
package app.morphe.extension.youtube.patches.general.offline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Pure storage, category parsing and playback decisions, independent of the online player. */
final class OfflineSponsorSegments {
    static final List<String> CATEGORIES = List.of("sponsor", "selfpromo", "interaction", "intro", "outro",
            "preview", "hook", "filler", "music_offtopic");

    record Segment(String category, long startMs, long endMs) {
    }

    private OfflineSponsorSegments() {
    }

    static List<String> categories(String keys) {
        List<String> result = new ArrayList<>();
        for (String key : keys.split(",")) {
            String category = key.trim();
            if (CATEGORIES.contains(category) && !result.contains(category)) result.add(category);
        }
        return List.copyOf(result);
    }

    static List<Segment> fromJson(JSONArray array, boolean api) {
        List<Segment> result = new ArrayList<>();
        if (array == null) return result;
        for (int index = 0; index < array.length(); index++) {
            JSONObject json = array.optJSONObject(index);
            if (json == null) continue;
            String category = json.optString("category");
            JSONArray times = json.optJSONArray("segment");
            long start = api ? times == null ? -1 : (long) (times.optDouble(0, -1) * 1000)
                    : json.optLong("startMs", -1);
            long end = api ? times == null ? -1 : (long) (times.optDouble(1, -1) * 1000)
                    : json.optLong("endMs", -1);
            if (CATEGORIES.contains(category) && start >= 0 && end > start) {
                result.add(new Segment(category, start, end));
            }
        }
        return List.copyOf(result);
    }

    static JSONArray toJson(List<Segment> segments) throws org.json.JSONException {
        JSONArray result = new JSONArray();
        for (Segment segment : segments) {
            result.put(new JSONObject().put("category", segment.category()).put("startMs", segment.startMs())
                    .put("endMs", segment.endMs()));
        }
        return result;
    }

    static boolean durationMatches(long reported, long actual) {
        return reported > 0 && actual > 0 && Math.abs(reported - actual) <= 5000;
    }

    static Segment at(List<Segment> segments, List<String> selected, long position, boolean automatic,
                      Set<Segment> skippedOnce, Function<String, String> behavior) {
        Segment chosen = null;
        for (Segment segment : segments) {
            if (!selected.contains(segment.category()) || position < segment.startMs() || position >= segment.endMs()) {
                continue;
            }
            String mode = behavior.apply(segment.category());
            boolean skip = "skip".equals(mode) || ("skip-once".equals(mode) && !skippedOnce.contains(segment));
            if (automatic ? !skip : !"manual-skip".equals(mode)) continue;
            if (chosen == null || segment.endMs() > chosen.endMs()) chosen = segment;
        }
        return chosen;
    }

    static long nextDelay(List<Segment> segments, long position, float speed) {
        long delay = 250;
        for (Segment segment : segments) {
            if (segment.startMs() > position) {
                delay = Math.min(delay, (long) ((segment.startMs() - position) / Math.max(0.25f, speed)));
            }
        }
        return Math.max(20, delay);
    }

    static boolean canRepeat(long now, long lastSkip) {
        return lastSkip == 0 || now - lastSkip >= 500;
    }
}
