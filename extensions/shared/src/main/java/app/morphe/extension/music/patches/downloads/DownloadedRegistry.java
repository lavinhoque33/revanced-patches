/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stream copies of Play downloads locally, held in memory so that a row or a header can be checked without any
 * disk or MediaStore access: the video ids that have a valid stream copy (the only thing a check means; a song
 * that is only a local file is not in it), and the track list of every playlist or album downloaded with stream
 * copies. Thread safe; contains no Android code.
 */
final class DownloadedRegistry {
    private static final int VIDEO_ID_LENGTH = 11;
    private static final byte VIDEO_ID_TAG = 0x0A;
    private static final byte PLAYLIST_ID_TAG = 0x12;
    private static final byte INDEX_TAG = 0x18;
    /** The first position a playlist id can start at after a video id field and its own tag and length. */
    private static final int WATCH_PLAYLIST_START = 15;

    private final Set<String> tracks = ConcurrentHashMap.newKeySet();
    private final Map<String, List<String>> sources = new ConcurrentHashMap<>();

    boolean hasTracks() {
        return !tracks.isEmpty();
    }

    boolean isDownloaded(String videoId) {
        return tracks.contains(videoId);
    }

    void add(String videoId) {
        tracks.add(videoId);
    }

    void remove(String videoId) {
        tracks.remove(videoId);
    }

    void addAll(Collection<String> videoIds) {
        tracks.addAll(videoIds);
    }

    void clear() {
        tracks.clear();
    }

    Set<String> snapshot() {
        return new HashSet<>(tracks);
    }

    /**
     * Forgets the tracks that were known before a check of the store and are absent from its result.
     * A track that was added while the check ran was not known before it and is never forgotten.
     */
    void prune(Set<String> knownBefore, Set<String> stillPresent) {
        Set<String> gone = new HashSet<>(knownBefore);
        gone.removeAll(stillPresent);
        tracks.removeAll(gone);
    }

    void putSource(String sourceId, List<String> videoIds) {
        sources.put(sourceId, Collections.unmodifiableList(new ArrayList<>(videoIds)));
    }

    /** The ordered video ids of a remembered playlist or album, or null. */
    @Nullable
    List<String> sourceTracks(@Nullable String sourceId) {
        return sourceId == null ? null : sources.get(sourceId);
    }

    /**
     * The remembered playlist or album whose id is a whole string of the serialized command, or null. A playlist id
     * is a protobuf string, so it is preceded by its length; that keeps a radio id that merely contains it apart.
     */
    @Nullable
    String findSource(byte[] message) {
        for (String sourceId : sources.keySet()) {
            byte[] id = sourceId.getBytes(StandardCharsets.ISO_8859_1);
            if (id.length == 0 || id.length > 127) continue;
            for (int start = 1; start + id.length <= message.length; start++) {
                if (message[start - 1] != id.length || !matches(message, start, id)) continue;
                return sourceId;
            }
        }
        return null;
    }

    private static boolean matches(byte[] message, int start, byte[] id) {
        for (int index = 0; index < id.length; index++) {
            if (message[start + index] != id[index]) return false;
        }
        return true;
    }

    boolean hasSource(String sourceId) {
        return sources.containsKey(sourceId);
    }

    /** True when every track of a remembered playlist or album still has a stream copy. */
    boolean isSourceDownloaded(@Nullable String sourceId) {
        List<String> videoIds = sourceId == null ? null : sources.get(sourceId);
        if (videoIds == null || videoIds.isEmpty()) return false;
        return tracks.containsAll(videoIds);
    }

    /**
     * Finds a downloaded video id in the serialized model of a row. A video id is a protobuf string of
     * exactly 11 characters, so it is always preceded by the length byte 11; that prefix keeps the scan
     * cheap and makes a match by chance practically impossible, because the id must also be a download.
     */
    @Nullable
    String findDownloaded(byte[] message) {
        if (tracks.isEmpty()) return null;
        for (int start = 0; start + VIDEO_ID_LENGTH < message.length; start++) {
            if (message[start] != VIDEO_ID_LENGTH || !isVideoId(message, start + 1)) continue;
            String candidate = new String(message, start + 1, VIDEO_ID_LENGTH, StandardCharsets.ISO_8859_1);
            if (tracks.contains(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Finds a downloaded video id that sits as a stand-alone word in a model: exactly 11 id characters with
     * a non-id character (or the edge of the model) on both sides, as in a URL or a plain text field.
     * The fallback of {@link #findDownloaded} for ids that are not whole protobuf strings.
     */
    @Nullable
    String findDownloadedLoose(byte[] message) {
        if (tracks.isEmpty()) return null;
        int runStart = 0;
        for (int index = 0; index <= message.length; index++) {
            if (index < message.length && isIdCharacter(message[index])) continue;

            if (index - runStart == VIDEO_ID_LENGTH) {
                String candidate = new String(message, runStart, VIDEO_ID_LENGTH, StandardCharsets.ISO_8859_1);
                if (tracks.contains(candidate)) return candidate;
            }
            runStart = index + 1;
        }
        return null;
    }

    /**
     * The song whose stream copy a row of an Elements list shows, or null: the row names a song that has a copy
     * ({@link #findDownloaded}), or its watch endpoint names a remembered playlist or album and the position of the
     * row in it, and the track the playlist keeps at that position has a copy. An album row can carry the id of the
     * music video while the copy is under the id of the song, so the position is what ties them. Only a watch
     * endpoint with an explicit position counts, never the first track by default and never a whole playlist: a
     * row that is not in the playlist must not get a check because the rest of it is downloaded.
     */
    @Nullable
    String findRowCopy(byte[] message) {
        if (tracks.isEmpty()) return null;

        String named = findDownloaded(message);
        return named != null ? named : findWatchedTrack(message);
    }

    /**
     * The downloaded track a watch endpoint of the message names by playlist and position. The endpoint is the
     * message that holds, in field order, the video id (field 1), the playlist id (field 2) and the position (field 3,
     * a varint), so the playlist id is a string preceded by its length, the tag 0x12 and a video id field.
     */
    @Nullable
    private String findWatchedTrack(byte[] message) {
        for (Map.Entry<String, List<String>> source : sources.entrySet()) {
            byte[] id = source.getKey().getBytes(StandardCharsets.ISO_8859_1);
            if (id.length == 0 || id.length > 127) continue;

            List<String> sourceTracks = source.getValue();
            for (int start = WATCH_PLAYLIST_START; start + id.length < message.length; start++) {
                if (message[start - 1] != id.length || message[start - 2] != PLAYLIST_ID_TAG
                        || !matches(message, start, id) || !followsVideoIdField(message, start - 2)) continue;

                int index = readIndex(message, start + id.length);
                if (index >= 0 && index < sourceTracks.size() && tracks.contains(sourceTracks.get(index))) {
                    return sourceTracks.get(index);
                }
            }
        }
        return null;
    }

    /** Whether the 11 characters before the playlist id tag are a video id field: tag 1, length 11, the id. */
    private static boolean followsVideoIdField(byte[] message, int playlistTag) {
        return message[playlistTag - 13] == VIDEO_ID_TAG && message[playlistTag - 12] == VIDEO_ID_LENGTH
                && isVideoId(message, playlistTag - 11);
    }

    /** The position field right after the playlist id (tag 0x18 and a varint), or -1 when there is none. */
    private static int readIndex(byte[] message, int position) {
        if (position >= message.length || message[position] != INDEX_TAG) return -1;

        long index = 0;
        for (int shift = 0, at = position + 1; shift < 35 && at < message.length; shift += 7, at++) {
            index |= (long) (message[at] & 0x7F) << shift;
            if ((message[at] & 0x80) == 0) return index <= Integer.MAX_VALUE ? (int) index : -1;
        }
        return -1;
    }

    /** The video-id-shaped strings of a model, at most {@code limit}: what {@link #findDownloaded} looks at. */
    static List<String> candidateIds(byte[] message, int limit) {
        List<String> candidates = new ArrayList<>();
        for (int start = 0; start + VIDEO_ID_LENGTH < message.length && candidates.size() < limit; start++) {
            if (message[start] == VIDEO_ID_LENGTH && isVideoId(message, start + 1)) {
                String candidate = new String(message, start + 1, VIDEO_ID_LENGTH, StandardCharsets.ISO_8859_1);
                if (!candidates.contains(candidate)) candidates.add(candidate);
            }
        }
        return candidates;
    }

    private static boolean isVideoId(byte[] message, int from) {
        for (int index = from; index < from + VIDEO_ID_LENGTH; index++) {
            if (!isIdCharacter(message[index])) return false;
        }
        return true;
    }

    private static boolean isIdCharacter(byte character) {
        return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9') || character == '-' || character == '_';
    }

    int trackCount() {
        return tracks.size();
    }

    /**
     * The video ids of a native playlist, or null when any of its tracks is not a download of the app.
     * Recovers the tracks of playlists that were saved before the track lists were remembered.
     */
    @Nullable
    static List<String> resolveTracks(List<Long> mediaIds, Map<Long, String> videoIdByMediaId) {
        if (mediaIds.isEmpty()) return null;
        List<String> videoIds = new ArrayList<>(mediaIds.size());
        for (long mediaId : mediaIds) {
            String videoId = videoIdByMediaId.get(mediaId);
            if (videoId == null) return null;
            videoIds.add(videoId);
        }
        return videoIds;
    }

    static String encode(List<String> videoIds) {
        return String.join("\n", videoIds);
    }

    static List<String> decode(String encoded) {
        List<String> videoIds = new ArrayList<>();
        for (String videoId : encoded.split("\n")) if (!videoId.isEmpty()) videoIds.add(videoId);
        return videoIds;
    }
}
