/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
 * The decisions of {@link OfflineReplay} that need no Android: which playlist id stands for a list, whether a hook
 * acts or says why not, what a command names, and how the tracks of a list become the items of its queue.
 */
final class OfflineReplayLogic {
    static final String REASON_SETTING_OFF = "setting off";
    static final String REASON_ONLINE_REQUEST = "online request";
    static final String REASON_NOT_OURS = "not ours";
    static final String REASON_STOCK_ANSWERED = "stock answered";

    private static final int OFFLINE_WATCH_ENDPOINT = OfflineWatchCommand.ENDPOINT_FIELD;
    private static final int BROWSE_ENDPOINT = 48687626;
    /** The extension of the command that asks the app to remember a page as the Library tab's launch page. */
    private static final int VIDEO_ID_FIELD = OfflineWatchCommand.VIDEO_ID_FIELD;
    private static final int PLAYLIST_ID_FIELD = OfflineWatchCommand.PLAYLIST_ID_FIELD;
    private static final int INDEX_FIELD = OfflineWatchCommand.INDEX_FIELD;
    /** Prefix of the synthetic playlist id of a list; it starts with the stock prefix of a queue ("PPSV"). */
    private static final String QUEUE_PLAYLIST_PREFIX = "PPSVrvx:";
    /** An album page's browse id: not a playlist id, so an online watch command must not carry it. */
    private static final String ALBUM_BROWSE_PREFIX = "MPREb_";
    private static final long SKIP_LOG_INTERVAL_MS = 60_000L;
    private static final int OFFLINE_RESPONSE_FIELD = OfflineReplayResponse.OFFLINE_MARKER_FIELD;

    private OfflineReplayLogic() {}

    /** The video, playlist and index an offline watch command names. */
    record WatchTarget(String videoId, String playlistId, int index) {}

    /**
     * Whether the "No connection" sheet the app shows by itself is dismissed: only while offline, and only while the
     * video that plays is the one whose copy was replayed offline.
     */
    static boolean dismissesSheetShownLater(@Nullable String replayedVideoId, @Nullable String playingVideoId,
                                            boolean networkAvailable) {
        return !networkAvailable && replayedVideoId != null && replayedVideoId.equals(playingVideoId);
    }

    /** The synthetic playlist id the offline queue of a list carries. */
    static String playlistId(String sourceId) {
        return QUEUE_PLAYLIST_PREFIX + sourceId;
    }

    /** The list a synthetic playlist id stands for, or null when the id is not one of ours. */
    @Nullable
    static String sourceOf(@Nullable String playlistId) {
        if (playlistId == null || !playlistId.startsWith(QUEUE_PLAYLIST_PREFIX)) return null;

        String sourceId = playlistId.substring(QUEUE_PLAYLIST_PREFIX.length());
        return sourceId.isEmpty() ? null : sourceId;
    }

    /**
     * The playlist id the normal online watch command of a list's track carries, or null when the track plays on
     * its own: the all-songs list has no playlist, and an album page's browse id is not a playlist.
     */
    @Nullable
    static String onlinePlaylistId(String sourceId, String songsId) {
        if (songsId.equals(sourceId) || sourceId.startsWith(ALBUM_BROWSE_PREFIX)) return null;
        return sourceId;
    }

    /**
     * Whether the seek snap of the player must be dropped. After a drag the player snaps the target to the start of
     * the nearest span its stream cache holds; a copy played from a file has none, the lookup answers 0, and the
     * seek would restart the song. A snap of 0 is dropped (the dragged position stands) with no network, while the
     * offline replay is on; online the stock answer is left as it is.
     */
    static boolean dropsSeekSnap(boolean enabled, boolean online, long snappedMillis) {
        return enabled && !online && snappedMillis == 0;
    }

    /**
     * Why a hook leaves the stock behavior alone, or null when it acts. The first reason that applies wins, so a log
     * line names the outermost one: a disabled setting before an online request before a request for something
     * else before a stock answer.
     */
    @Nullable
    static String skipReason(boolean enabled, boolean offlineRequest, boolean ours, boolean stockFailed) {
        if (!enabled) return REASON_SETTING_OFF;
        if (!offlineRequest) return REASON_ONLINE_REQUEST;
        if (!ours) return REASON_NOT_OURS;
        if (!stockFailed) return REASON_STOCK_ANSWERED;
        return null;
    }

    /** True once per minute for each key: a skipped request is common, its line must not flood the log. */
    static boolean shouldLogSkip(ConcurrentMap<String, Long> lastLogged, String key, long nowMs) {
        boolean[] due = {false};
        lastLogged.compute(key, (ignored, last) -> {
            if (last != null && nowMs - last < SKIP_LOG_INTERVAL_MS) return last;
            due[0] = true;
            return nowMs;
        });
        return due[0];
    }

    /** What an offline watch command names, or null when the command is any other command. */
    @Nullable
    static WatchTarget offlineWatchOf(byte[] command) {
        try {
            byte[] payload = lengthDelimitedField(command, OFFLINE_WATCH_ENDPOINT);
            if (payload == null) return null;

            String videoId = "";
            String playlistId = "";
            int index = 0;
            CodedInputStream input = CodedInputStream.newInstance(payload);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                int wireType = WireFormat.getTagWireType(tag);
                if (field == VIDEO_ID_FIELD && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    videoId = input.readStringRequireUtf8();
                } else if (field == PLAYLIST_ID_FIELD && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    playlistId = input.readStringRequireUtf8();
                } else if (field == INDEX_FIELD && wireType == WireFormat.WIRETYPE_VARINT) {
                    index = input.readInt32();
                } else if (!input.skipField(tag)) {
                    break;
                }
            }
            return videoId.isEmpty() ? null : new WatchTarget(videoId, playlistId, index);
        } catch (IOException ex) {
            return null;
        }
    }

    /** The browse id of a browse command, or null when the command is any other command. */
    @Nullable
    static String browseIdOf(byte[] command) {
        try {
            byte[] endpoint = lengthDelimitedField(command, BROWSE_ENDPOINT);
            if (endpoint == null) return null;

            CodedInputStream input = CodedInputStream.newInstance(endpoint);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                // The page id is field 2 of the endpoint; field 1 is accepted too, as the Downloads rewrite does.
                boolean idField = field == 1 || field == 2;
                if (idField && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    return new String(input.readByteArray(), StandardCharsets.UTF_8);
                }
                if (!input.skipField(tag)) break;
            }
        } catch (IOException ignored) {
            // Not a message this decision knows: it is some other command.
        }
        return null;
    }

    /** Whether the message has the field, of any wire type. */
    static boolean hasField(byte[] message, int number) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(message);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (WireFormat.getTagFieldNumber(tag) == number) return true;
                if (!input.skipField(tag)) return false;
            }
        } catch (IOException ignored) {
            // A message that does not parse has no field to speak of.
        }
        return false;
    }

    /** Whether a player response carries the marker the app sets on a response it read from its offline store. */
    static boolean hasOfflineMarker(byte[] response) {
        return hasField(response, OFFLINE_RESPONSE_FIELD);
    }

    /** The queue items of a list's tracks that have stored data, in the list's order. */
    static List<OfflineWatchNext.Item> itemsOfTracks(List<OfflineSourceStore.Track> tracks) {
        List<OfflineWatchNext.Item> items = new ArrayList<>();
        for (OfflineSourceStore.Track track : tracks) {
            if (track.availableOffline()) items.add(itemOf(track.stored()));
        }
        return items;
    }

    /** The queue items of the all-songs list: every copy with stored data, sorted by title. */
    static List<OfflineWatchNext.Item> itemsOfSongs(List<OfflineCopyMetadata> copies) {
        List<OfflineCopyMetadata> sorted = new ArrayList<>(copies);
        sorted.sort(Comparator.comparing(OfflineCopyMetadata::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(OfflineCopyMetadata::videoId));
        List<OfflineWatchNext.Item> items = new ArrayList<>(sorted.size());
        for (OfflineCopyMetadata copy : sorted) items.add(itemOf(copy));
        return items;
    }

    static OfflineWatchNext.Item itemOf(OfflineCopyMetadata copy) {
        return new OfflineWatchNext.Item(copy.videoId(), copy.title(), copy.artist(),
                copy.album() == null ? "" : copy.album(), copy.durationMs(), copy.thumbnailUrl());
    }

    /**
     * The index of the video in the queue: the requested index when it holds the video, else its first place, else
     * -1. A request names the position the list had when it was tapped, which a changed list may have moved.
     */
    static int currentIndex(List<OfflineWatchNext.Item> items, String videoId, int requestedIndex) {
        if (requestedIndex >= 0 && requestedIndex < items.size()
                && items.get(requestedIndex).videoId().equals(videoId)) {
            return requestedIndex;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).videoId().equals(videoId)) return i;
        }
        return -1;
    }

    /** The payload of the first length-delimited field with the number, or null when the message has none. */
    @Nullable
    private static byte[] lengthDelimitedField(byte[] message, int number) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(message);
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (WireFormat.getTagFieldNumber(tag) == number
                    && WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                return input.readByteArray();
            }
            if (!input.skipField(tag)) return null;
        }
        return null;
    }
}
