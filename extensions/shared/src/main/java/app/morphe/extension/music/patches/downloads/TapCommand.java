/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * What a tapped command holds, read from its serialized message so that no obfuscated class is needed: the video-id
 * shaped strings anywhere in it, and the fields of its watch endpoint (the video id, the playlist id and the position
 * in it). A song of an album can arrive as the id of its music video while the stream copy is under the id of the
 * song, so the playlist and the position are what ties the tap to a copy.
 *
 * <p>Field numbers: the watch endpoint extension of a command is 48,687,757; inside it video id 1, playlist id 2, index
 * 3 (the third presence bit of the message), params 5, playlist entry id 13. Index and params are read leniently.
 */
final class TapCommand {
    static final int WATCH_ENDPOINT_FIELD = 48_687_757;
    private static final int VIDEO_ID_FIELD = 1;
    private static final int PLAYLIST_ID_FIELD = 2;
    private static final int INDEX_FIELD = 3;
    private static final int PARAMS_FIELD = 5;
    private static final int MAX_IDS = 8;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_NODES = 96;
    private static final int MAX_SUMMARY_FIELDS = 12;

    /** The video-id shaped strings of the whole command, in order. */
    final List<String> ids;
    /** The video id of the watch endpoint, or null when no watch endpoint was found. */
    @Nullable
    final String videoId;
    final String playlistId;
    /** The position in the playlist, or -1 when the endpoint has none. */
    final int index;
    final boolean hasParams;
    /** The field numbers and kinds of the command and of its watch endpoint, for the debug log. */
    final String fields;

    private TapCommand(List<String> ids, @Nullable String videoId, String playlistId, int index, boolean hasParams,
                       String fields) {
        this.ids = ids;
        this.videoId = videoId;
        this.playlistId = playlistId;
        this.index = index;
        this.hasParams = hasParams;
        this.fields = fields;
    }

    boolean isWatch() {
        return videoId != null;
    }

    static TapCommand parse(byte[] command) {
        List<String> ids = DownloadedRegistry.candidateIds(command, MAX_IDS);
        byte[] watch = null;
        String outer = "";
        try {
            int[] budget = {MAX_NODES};
            outer = summarize(command);
            watch = findWatchEndpoint(command, 0, budget);
        } catch (RuntimeException malformed) {
            // A message that does not parse still has its ids.
        }
        if (watch == null) return new TapCommand(ids, null, "", -1, false, outer);

        String videoId = "";
        String playlistId = "";
        int index = -1;
        boolean hasParams = false;
        try {
            int[] position = {0};
            while (position[0] < watch.length) {
                long key = readVarint(watch, position);
                int field = (int) (key >>> 3);
                switch ((int) (key & 7)) {
                    case 0 -> {
                        long value = readVarint(watch, position);
                        if (field == INDEX_FIELD) index = (int) value;
                    }
                    case 1 -> position[0] += 8;
                    case 5 -> position[0] += 4;
                    case 2 -> {
                        int length = (int) readVarint(watch, position);
                        if (length < 0 || position[0] + length > watch.length) throw new IllegalArgumentException();
                        String text = new String(watch, position[0], length, StandardCharsets.ISO_8859_1);
                        if (field == VIDEO_ID_FIELD) videoId = text;
                        else if (field == PLAYLIST_ID_FIELD) playlistId = text;
                        else if (field == PARAMS_FIELD) hasParams = !text.isEmpty();
                        position[0] += length;
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException malformed) {
            // Keep what was read.
        }
        String summary = outer + " watch=" + summarize(watch);
        return new TapCommand(ids, videoId, playlistId, index, hasParams, summary);
    }

    @Nullable
    private static byte[] findWatchEndpoint(byte[] message, int depth, int[] budget) {
        int[] position = {0};
        while (position[0] < message.length && budget[0]-- > 0) {
            long key = readVarint(message, position);
            int field = (int) (key >>> 3);
            switch ((int) (key & 7)) {
                case 0 -> readVarint(message, position);
                case 1 -> position[0] += 8;
                case 5 -> position[0] += 4;
                case 2 -> {
                    int length = (int) readVarint(message, position);
                    if (length < 0 || position[0] + length > message.length) throw new IllegalArgumentException();
                    byte[] payload = Arrays.copyOfRange(message, position[0], position[0] + length);
                    position[0] += length;
                    if (field == WATCH_ENDPOINT_FIELD) return payload;
                    if (depth < MAX_DEPTH && length >= 8) {
                        try {
                            byte[] nested = findWatchEndpoint(payload, depth + 1, budget);
                            if (nested != null) return nested;
                        } catch (RuntimeException notAMessage) {
                            // A string or bytes field, not a message.
                        }
                    }
                }
                default -> throw new IllegalArgumentException();
            }
        }
        return null;
    }

    /** "1:s11,2:s17,3:v4" for the top-level fields: number, kind (v varint, s length-delimited) and size or value. */
    private static String summarize(byte[] message) {
        StringBuilder summary = new StringBuilder("[");
        int[] position = {0};
        int count = 0;
        try {
            while (position[0] < message.length && count < MAX_SUMMARY_FIELDS) {
                long key = readVarint(message, position);
                int field = (int) (key >>> 3);
                if (count++ > 0) summary.append(',');
                switch ((int) (key & 7)) {
                    case 0 -> summary.append(field).append(":v").append(readVarint(message, position));
                    case 1 -> {
                        summary.append(field).append(":f64");
                        position[0] += 8;
                    }
                    case 5 -> {
                        summary.append(field).append(":f32");
                        position[0] += 4;
                    }
                    case 2 -> {
                        int length = (int) readVarint(message, position);
                        summary.append(field).append(":s").append(length);
                        position[0] += length;
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
        } catch (RuntimeException malformed) {
            summary.append(",?");
        }
        return summary.append(']').toString();
    }

    private static long readVarint(byte[] data, int[] position) {
        long result = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (position[0] >= data.length) throw new IllegalArgumentException("Truncated varint");
            byte b = data[position[0]++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
        }
        throw new IllegalArgumentException("Malformed varint");
    }
}
