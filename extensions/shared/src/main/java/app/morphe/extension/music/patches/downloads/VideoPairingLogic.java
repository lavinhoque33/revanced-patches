/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A playlist row that already names an official video keeps that video. Exact IDs and typed counterpart
 * relationships do not depend on queue order. Positional fallback is used only for a complete queue and an
 * otherwise unclaimed video with matching recording metadata.
 */
final class VideoPairingLogic {
    private static final long MAX_LENGTH_DIFFERENCE_SECONDS = 30;
    private static final double LENGTH_FRACTION = 0.12;

    static final String BY_SAME = "same";
    static final String BY_COUNTERPART = "counterpart";
    static final String BY_OFFICIAL_VIDEO = "official-video";
    static final String BY_RECORDING = "recording";
    static final String BY_NONE = "none";

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,4}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern EDITION_SUFFIX = Pattern.compile(
            "\\s*[\\[(]\\s*(?:(?:official\\s+)?(?:music\\s+)?(?:video|audio)"
                    + "|lyrics?(?:\\s+video)?|remastered(?:\\s+[0-9]{4})?|(?:feat|ft|featuring)\\b[^)\\]]*)"
                    + "\\s*[)\\]]\\s*$");
    private static final Pattern FEATURING = Pattern.compile("\\b(?:feat|ft|featuring)\\b.*$");
    private static final Pattern VIDEO_SUFFIX = Pattern.compile(
            "\\s[-\u2013|]\\s*(?:official\\s+)?(?:music\\s+)?(?:video|audio|lyrics?(?:\\s+video)?)$");
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private VideoPairingLogic() {}

    /** A downloaded song; {@code durationSeconds} is 0 when its length is not known, title and artist may be empty. */
    record Song(String videoId, long durationSeconds, String title, String artist) {}

    /** The music video {@code videoId} that belongs to the song {@code songId}. */
    record Pair(String songId, String videoId) {}

    /** One downloaded row and the evidence for accepting or declining its video. */
    record Decision(int index, String songId, String videoId, long songSeconds, long videoSeconds, String by,
                    @Nullable String reason) {
        boolean paired() {
            return BY_OFFICIAL_VIDEO.equals(by) || BY_COUNTERPART.equals(by) || BY_RECORDING.equals(by);
        }

        boolean differs() {
            return !BY_SAME.equals(by);
        }
    }

    /** Accepted pairs in playlist order and the decision for each row. */
    record Result(List<Pair> pairs, @Nullable String skipReason, List<Decision> decisions) {
        static Result skipped(String reason) {
            return new Result(List.of(), reason, List.of());
        }
    }

    static Result pair(List<Song> songs, List<WatchQueueLogic.NextItem> queue, Predicate<String> savedOfficialVideo) {
        Map<String, WatchQueueLogic.NextItem> exact = new HashMap<>();
        Map<String, WatchQueueLogic.NextItem> counterparts = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Set<String> playlistIds = new HashSet<>();
        for (Song song : songs) playlistIds.add(song.videoId());

        for (WatchQueueLogic.NextItem item : queue) {
            if (!isVideoId(item.videoId())) continue;

            exact.merge(item.videoId(), item, (first, other) -> other.isOfficialVideo() ? other : first);
            if (!isVideoId(item.counterpartId()) || item.videoId().equals(item.counterpartId())) continue;

            if (item.isOfficialVideo() && ("ATV".equals(item.counterpartType()) || item.counterpartType().isEmpty())) {
                link(counterparts, ambiguous, item.counterpartId(), item);
            } else if (item.counterpartIsOfficialVideo() && ("ATV".equals(item.type()) || item.type().isEmpty())) {
                link(counterparts, ambiguous, item.videoId(), item);
            }
        }

        List<Pair> pairs = new ArrayList<>();
        List<Decision> decisions = new ArrayList<>();
        for (int index = 0; index < songs.size(); index++) {
            Song song = songs.get(index);
            WatchQueueLogic.NextItem named = exact.get(song.videoId());
            WatchQueueLogic.NextItem linked = ambiguous.contains(song.videoId()) ? null
                    : counterparts.get(song.videoId());
            WatchQueueLogic.NextItem positional = queue.size() == songs.size() ? queue.get(index) : null;
            Decision decision = decide(index, song, named, linked, positional, playlistIds, counterparts,
                    savedOfficialVideo);
            decisions.add(decision);
            if (decision.paired()) pairs.add(new Pair(decision.songId(), decision.videoId()));
        }
        if (queue.isEmpty() && pairs.isEmpty()) return Result.skipped("the queue is empty");

        return new Result(pairs, null, decisions);
    }

    private static void link(Map<String, WatchQueueLogic.NextItem> counterparts, Set<String> ambiguous,
                             String songId, WatchQueueLogic.NextItem item) {
        WatchQueueLogic.NextItem previous = counterparts.putIfAbsent(songId, item);
        if (previous != null && !linkedVideoId(previous).equals(linkedVideoId(item))) ambiguous.add(songId);
    }

    private static String linkedVideoId(WatchQueueLogic.NextItem item) {
        return item.isOfficialVideo() ? item.videoId() : item.counterpartId();
    }

    private static Decision decide(int index, Song song, @Nullable WatchQueueLogic.NextItem named,
                                   @Nullable WatchQueueLogic.NextItem linked,
                                   @Nullable WatchQueueLogic.NextItem positional, Set<String> playlistIds,
                                   Map<String, WatchQueueLogic.NextItem> counterparts,
                                   Predicate<String> savedOfficialVideo) {
        if (named != null && named.isOfficialVideo()) {
            return decision(index, song, named.videoId(), lengthSeconds(named.length()), BY_OFFICIAL_VIDEO, null);
        }
        if ((named == null || named.type().isEmpty()) && savedOfficialVideo.test(song.videoId())) {
            return decision(index, song, song.videoId(), song.durationSeconds(), BY_OFFICIAL_VIDEO, null);
        }
        if (linked != null && !playlistIds.contains(linkedVideoId(linked))) {
            String videoLength = linked.isOfficialVideo() ? linked.length() : linked.counterpartLength();
            return decision(index, song, linkedVideoId(linked), lengthSeconds(videoLength), BY_COUNTERPART, null);
        }
        if (named != null) {
            return decision(index, song, named.videoId(), lengthSeconds(named.length()), BY_SAME, null);
        }
        if (positional == null) return decision(index, song, "", -1, BY_NONE, "queue positions don't align");

        String queued = positional.videoId();
        long videoSeconds = lengthSeconds(positional.length());
        boolean videoType = positional.isOfficialVideo() || "UGC".equals(positional.type())
                || positional.type().isEmpty();
        if (!isVideoId(queued) || playlistIds.contains(queued) || positional.hasCounterpart()
                || counterparts.containsKey(queued) || !videoType) {
            return decision(index, song, queued, videoSeconds, BY_NONE, "no unclaimed video at this position");
        }
        if (!sameTitle(song.title(), positional.title()) || !sameArtist(song.artist(), positional.artist())
                || !similarLength(song.durationSeconds(), videoSeconds)) {
            return decision(index, song, queued, videoSeconds, BY_NONE, "title, artist or length differs");
        }
        return decision(index, song, queued, videoSeconds, BY_RECORDING, null);
    }

    private static boolean sameArtist(String songArtist, String videoArtist) {
        String song = words(songArtist);
        return !song.isEmpty() && song.equals(words(videoArtist));
    }

    private static Decision decision(int index, Song song, String queued, long videoSeconds, String by,
                                     @Nullable String reason) {
        return new Decision(index, song.videoId(), queued, song.durationSeconds(), videoSeconds, by, reason);
    }

    static boolean similarLength(long songSeconds, long videoSeconds) {
        long tolerance = Math.min(MAX_LENGTH_DIFFERENCE_SECONDS, Math.round(songSeconds * LENGTH_FRACTION));
        return songSeconds > 0 && videoSeconds > 0 && Math.abs(videoSeconds - songSeconds) <= tolerance;
    }

    static boolean sameTitle(String songTitle, String videoTitle) {
        String song = normalizeTitle(songTitle);
        return !song.isEmpty() && song.equals(normalizeTitle(videoTitle));
    }

    /**
     * Compare names without stripping meaningful edition labels such as “Live” or “Remix”.
     */
    static String normalizeTitle(@Nullable String title) {
        if (title == null) return "";

        String text = MARKS.matcher(Normalizer.normalize(title, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        text = EDITION_SUFFIX.matcher(text).replaceAll("");
        text = VIDEO_SUFFIX.matcher(text.trim()).replaceAll("");
        text = FEATURING.matcher(text).replaceAll(" ");
        return NOT_ALPHANUMERIC.matcher(text).replaceAll(" ").trim();
    }

    private static String words(String text) {
        return NOT_ALPHANUMERIC.matcher(MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD))
                .replaceAll("").toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
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
