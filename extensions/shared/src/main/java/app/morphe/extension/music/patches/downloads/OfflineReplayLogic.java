/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 * Modified by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

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
    /** The playlist id of an album's audio playlist, which the watch of a song on an album page carries. */
    private static final String AUDIO_PLAYLIST_PREFIX = "OLAK5uy_";
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

    /**
     * What the app's watch endpoint of a command names: the video, and the playlist and position when it has them
     * (the playlist id is empty for a song that plays on its own). Null when the command has no watch endpoint.
     */
    @Nullable
    static WatchTarget onlineWatchOf(byte[] command) {
        try {
            byte[] endpoint = lengthDelimitedField(command, CommandRewrite.WATCH_ENDPOINT_FIELD);
            if (endpoint == null) return null;

            String videoId = "";
            String playlistId = "";
            int index = 0;
            CodedInputStream input = CodedInputStream.newInstance(endpoint);
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                int field = WireFormat.getTagFieldNumber(tag);
                int wireType = WireFormat.getTagWireType(tag);
                if (field == VIDEO_ID_FIELD && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    videoId = input.readStringRequireUtf8();
                } else if (field == OnlineWatchCommand.PLAYLIST_ID_FIELD
                        && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED) {
                    playlistId = input.readStringRequireUtf8();
                } else if (field == OnlineWatchCommand.INDEX_FIELD && wireType == WireFormat.WIRETYPE_VARINT) {
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

    /** What the app's own command of a tap in the Downloads page, or of a recorded page, becomes when it runs. */
    enum Route {
        /** The app runs the command as it is. */
        STOCK,
        /** The command is dropped and the downloaded list plays from its first song, with no network. */
        PLAY_SOURCE,
        /** The command is dropped and the song plays from its copy as a track of the list, with no network. */
        PLAY_TRACK
    }

    /** A decision: what to do, and for which list and song (null where the route does not use them). */
    record Decision(Route route, @Nullable String sourceId, @Nullable String videoId) {
        static final Decision STOCK = new Decision(Route.STOCK, null, null);
    }

    /**
     * Decides a command when it is dispatched, never before: a row of the page carries the app's own command, and
     * only the network of this moment says whether the app can run it. Online every command stays the app's.
     * Offline, the browse of a downloaded list plays its songs unless a page was recorded for it (then the app opens
     * that page); the watch of a song of a downloaded list, from the recorded page or a row, plays that song as a
     * track of the list; the watch of a downloaded song on its own plays its copy.
     *
     * @param online         The device reaches the internet now.
     * @param browseId       The page a browse command opens, or null for any other command.
     * @param watch          What a watch command names, or null for any other command.
     * @param songsId        The id of the list of every song.
     * @param isSource       Whether a page id names a downloaded list, the {@code VL} prefix of a playlist included.
     * @param hasPage        Whether a page was recorded for the downloaded list.
     * @param hasCopy        Whether the song has a copy with stored data.
     * @param sourceOfWatch  The downloaded list a watch of a list's song belongs to, or null.
     */
    static Decision routeOf(boolean online, @Nullable String browseId, @Nullable WatchTarget watch, String songsId,
                            Predicate<String> isSource, Predicate<String> hasPage, Predicate<String> hasCopy,
                            Function<WatchTarget, String> sourceOfWatch) {
        if (online) return Decision.STOCK;

        if (browseId != null) {
            String sourceId = sourceOfBrowseId(browseId);
            boolean plays = isSource.test(sourceId) && !hasPage.test(sourceId);
            return plays ? new Decision(Route.PLAY_SOURCE, sourceId, null) : Decision.STOCK;
        }

        if (watch == null || !hasCopy.test(watch.videoId())) {
            // A song with no copy has nothing to play here; it may still start the downloaded list it is a track of.
            String sourceId = watch == null ? null : sourceOfWatch.apply(watch);
            return sourceId == null ? Decision.STOCK : new Decision(Route.PLAY_SOURCE, sourceId, null);
        }

        if (watch.playlistId().isEmpty()) return new Decision(Route.PLAY_TRACK, songsId, watch.videoId());

        String sourceId = sourceOfWatch.apply(watch);
        return sourceId == null ? Decision.STOCK : new Decision(Route.PLAY_TRACK, sourceId, watch.videoId());
    }

    /**
     * The downloaded list a watch of a playlist song belongs to: the list with the playlist's id, or for an album
     * page (its browse id is not the playlist id of the album's audio playlist) the downloaded album that holds the
     * song. Null when no downloaded list does.
     *
     * @param tracksBySource The song ids of every downloaded list, by list id.
     */
    @Nullable
    static String sourceOfPlaylist(String playlistId, String videoId, Map<String, List<String>> tracksBySource) {
        if (tracksBySource.containsKey(playlistId)) return playlistId;
        if (!playlistId.startsWith(AUDIO_PLAYLIST_PREFIX)) return null;

        for (Map.Entry<String, List<String>> source : tracksBySource.entrySet()) {
            if (source.getKey().startsWith(ALBUM_BROWSE_PREFIX) && source.getValue().contains(videoId)) {
                return source.getKey();
            }
        }
        return null;
    }

    /** The id of the downloaded list a browse id opens: a playlist page is {@code VL} and the playlist id. */
    static String sourceOfBrowseId(String browseId) {
        return browseId.startsWith("VL") ? browseId.substring(2) : browseId;
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

    /**
     * The items with the music video of every song that has a video copy: those items are wrappers whose counterpart
     * is the video. The others stay as they are.
     *
     * @param videoToSong The committed video copies, music video id to the id of the song they belong to.
     */
    static List<OfflineWatchNext.Item> withVideoCopies(List<OfflineWatchNext.Item> items,
                                                       Map<String, String> videoToSong) {
        if (videoToSong.isEmpty()) return items;

        Map<String, String> videoOfSong = new HashMap<>();
        for (Map.Entry<String, String> pair : videoToSong.entrySet()) videoOfSong.put(pair.getValue(), pair.getKey());

        List<OfflineWatchNext.Item> wrapped = new ArrayList<>(items.size());
        for (OfflineWatchNext.Item item : items) {
            String video = videoOfSong.get(item.videoId());
            wrapped.add(video == null || video.equals(item.videoId()) ? item : item.withVideoCopy(video));
        }
        return wrapped;
    }

    /**
     * The song a request names: the id itself, or the song of the video copy when the id is a music video that has
     * one. The queue of a list holds songs, so a request for a song's video is the request for that song's item.
     */
    static String songOf(String videoId, Map<String, String> videoToSong) {
        String song = videoToSong.get(videoId);
        return song == null ? videoId : song;
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
