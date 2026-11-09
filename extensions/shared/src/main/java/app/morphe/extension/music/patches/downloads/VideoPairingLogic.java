/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which music video belongs to which downloaded song, from the queue the server lists for the same playlist or
 * album. The queue names the music video where there is one and the song otherwise, and carries no counterpart
 * information, so the pairing is by position among the tracks that were downloaded (an unavailable, greyed-out row
 * is in the recorded list but not in the queue). It is accepted only when it is sure: the queue must have exactly as
 * many items as there are downloaded tracks, and a video that differs from its song must be about as long as it.
 * Contains no Android code.
 */
final class VideoPairingLogic {
    /** A video cut and a song cut of one track differ by a second or so; more than this is another recording. */
    static final long DURATION_TOLERANCE_SECONDS = 3;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,4}");

    private VideoPairingLogic() {}

    /** A downloaded song; {@code durationSeconds} is 0 when its length is not known. */
    record Song(String videoId, long durationSeconds) {}

    /** The music video {@code videoId} that belongs to the song {@code songId}. */
    record Pair(String songId, String videoId) {}

    /**
     * @param pairs       The accepted pairs, in the order of the songs. Empty when the list was skipped.
     * @param skipReason  Why nothing was paired for the whole list, or null.
     * @param unverified  Items with a video id of their own whose length could not be checked: left unpaired.
     */
    record Result(List<Pair> pairs, @Nullable String skipReason, int unverified) {
        static Result skipped(String reason) {
            return new Result(List.of(), reason, 0);
        }
    }

    static Result pair(List<Song> songs, List<WatchQueueLogic.NextItem> queue) {
        if (queue.size() != songs.size()) {
            return Result.skipped("the queue has " + queue.size() + " items for " + songs.size() + " tracks");
        }

        List<Pair> pairs = new ArrayList<>();
        int unverified = 0;
        for (int index = 0; index < songs.size(); index++) {
            Song song = songs.get(index);
            String queued = queue.get(index).videoId();
            if (!isVideoId(queued)) return Result.skipped("item " + index + " has no video id");
            if (queued.equals(song.videoId())) continue;

            long videoSeconds = lengthSeconds(queue.get(index).length());
            if (videoSeconds < 0 || song.durationSeconds() <= 0) {
                unverified++;
                continue;
            }
            long difference = Math.abs(videoSeconds - song.durationSeconds());
            if (difference > DURATION_TOLERANCE_SECONDS) {
                return Result.skipped("item " + index + " " + queued + " is " + videoSeconds + " s, track "
                        + song.videoId() + " is " + song.durationSeconds() + " s");
            }
            pairs.add(new Pair(song.videoId(), queued));
        }
        return new Result(pairs, null, unverified);
    }

    static boolean isVideoId(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    /** The seconds of a length text ({@code m:ss} or {@code h:mm:ss}), or -1 when it is not one. */
    static long lengthSeconds(@Nullable String text) {
        if (text == null) return -1;

        String[] parts = text.trim().split(":", -1);
        if (parts.length < 2 || parts.length > 3) return -1;

        long seconds = 0;
        for (String part : parts) {
            if (!DIGITS.matcher(part).matches()) return -1;

            seconds = seconds * 60 + Long.parseLong(part);
        }
        return seconds;
    }
}
