/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The decisions and the row models of the Downloads list of the stream copies, with no Android dependency: whether
 * the list takes over a Downloads navigation, the rows of its three views, what a tap does and the bytes of the
 * browse command of the Device files footer.
 */
final class OfflineLibraryLogic {
    /** The Downloads page the Library tab, the account menu and the offline banner all navigate to. */
    static final String DOWNLOADS_BROWSE_ID = "FEmusic_offline";
    /** The page the Device files library opens at, the one {@link #DOWNLOADS_BROWSE_ID} is rewritten to. */
    static final String DEVICE_FILES_BROWSE_ID = "FEmusic_library_sideloaded_tracks";

    private static final String DOWNLOADS_BROWSE_PREFIX = "FEmusic_offline";
    private static final int BROWSE_ENDPOINT_FIELD = 48_687_626;
    private static final int BROWSE_ID_FIELD = 2;
    private static final int MAX_SAMPLE_SIZE = 64;

    /** What a Downloads navigation does. */
    enum Decision {
        /** The stock navigation (or the Device files redirect) goes on unchanged. */
        STOCK,
        /** The list opens and swallows the navigation. */
        OPEN,
        /** The list opens with a Device files row at its end, which continues to the Device files library. */
        OPEN_WITH_DEVICE_FILES
    }

    /** What a tap on a song row does. */
    enum Tap {
        /** The song is in the offline queue: play it from there. */
        PLAY_QUEUE,
        /** The song has no stored data, but the device is online: it plays like any song. */
        PLAY_ONLINE,
        /** The song has no stored data and the device is offline: say so. */
        EXPLAIN
    }

    /**
     * A downloaded album or playlist. {@code songCount} counts the songs that have a stream copy,
     * {@code availableCount} those of them with stored data; {@code coverVideoId} is the first of the latter.
     */
    record SourceRow(String sourceId, String title, int songCount, int availableCount,
                     @Nullable String coverVideoId) {}

    /**
     * A song of a list. {@code queueIndex} is its position in the offline queue of the list, or -1 when it has no
     * stored data and cannot play offline. {@code title} is empty for such a song: nothing is known of it but its id.
     */
    record SongRow(String videoId, String title, String artist, long durationMs, int queueIndex) {
        boolean availableOffline() {
            return queueIndex >= 0;
        }
    }

    private OfflineLibraryLogic() {}

    /**
     * Whether a navigation to {@code browseId} opens the list. The list is for the Downloads page only, offline and
     * online alike; it needs the replay and something stored. With Device files on, the generic Downloads page is
     * where the two meet, so the list gets the Device files row; the page's sub pages keep the Device files redirect.
     */
    static Decision decide(String browseId, boolean replayEnabled, boolean hasStoredData, boolean deviceFilesOn) {
        if (!replayEnabled || !hasStoredData || browseId == null
                || !browseId.startsWith(DOWNLOADS_BROWSE_PREFIX)) return Decision.STOCK;

        if (!deviceFilesOn) return Decision.OPEN;

        return DOWNLOADS_BROWSE_ID.equals(browseId) ? Decision.OPEN_WITH_DEVICE_FILES : Decision.STOCK;
    }

    /**
     * Whether a Library chip that resolved to {@code browseId} is the Downloads chip itself: its sub chips (songs,
     * albums, playlists) resolve to ids of their own and stay with the stock page.
     */
    static boolean isDownloadsChip(String browseId) {
        return DOWNLOADS_BROWSE_ID.equals(browseId);
    }

    /** The tap on a song row: from the queue, as a normal online song, or an explanation. */
    static Tap tapOn(SongRow row, boolean online) {
        if (row.availableOffline()) return Tap.PLAY_QUEUE;

        return online ? Tap.PLAY_ONLINE : Tap.EXPLAIN;
    }

    /** The albums and playlists, in the given order, with their songs counted; one without a song is left out. */
    static List<SourceRow> sourceRows(List<OfflineSourceStore.Source> sources,
                                      Function<String, List<OfflineSourceStore.Track>> tracksOf) {
        List<SourceRow> rows = new ArrayList<>();
        for (OfflineSourceStore.Source source : sources) {
            List<OfflineSourceStore.Track> tracks = tracksOf.apply(source.id());
            if (tracks.isEmpty()) continue;

            rows.add(new SourceRow(source.id(), source.title(), tracks.size(), availableCount(tracks),
                    coverOf(tracks)));
        }
        return rows;
    }

    private static int availableCount(List<OfflineSourceStore.Track> tracks) {
        int count = 0;
        for (OfflineSourceStore.Track track : tracks) {
            if (track.availableOffline()) count++;
        }
        return count;
    }

    /** The first song of the list that has stored data (and so a cover to show), or null. */
    @Nullable
    private static String coverOf(List<OfflineSourceStore.Track> tracks) {
        for (OfflineSourceStore.Track track : tracks) {
            if (track.availableOffline()) return track.videoId();
        }
        return null;
    }

    /** The songs of a source in its order, each with its place in {@code queueIds} (the offline queue of it). */
    static List<SongRow> trackRows(List<OfflineSourceStore.Track> tracks, List<String> queueIds) {
        List<SongRow> rows = new ArrayList<>();
        for (OfflineSourceStore.Track track : tracks) {
            rows.add(songRow(track.videoId(), track.stored(), queueIds));
        }
        return rows;
    }

    /**
     * Every stream copy as a song: the ones with stored data by title, then the ones without, by id.
     * {@code stored} may name a copy that is gone; it is not listed.
     */
    static List<SongRow> songRows(List<OfflineCopyMetadata> stored, Set<String> copyIds, List<String> queueIds) {
        List<OfflineCopyMetadata> present = new ArrayList<>();
        Set<String> withData = new HashSet<>();
        for (OfflineCopyMetadata metadata : stored) {
            if (!copyIds.contains(metadata.videoId())) continue;

            present.add(metadata);
            withData.add(metadata.videoId());
        }
        present.sort(Comparator.comparing(OfflineCopyMetadata::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(OfflineCopyMetadata::videoId));
        List<SongRow> rows = new ArrayList<>();
        for (OfflineCopyMetadata metadata : present) {
            rows.add(songRow(metadata.videoId(), metadata, queueIds));
        }

        List<String> without = new ArrayList<>();
        for (String id : copyIds) {
            if (!withData.contains(id)) without.add(id);
        }
        without.sort(null);
        for (String id : without) rows.add(songRow(id, null, queueIds));
        return rows;
    }

    private static SongRow songRow(String videoId, @Nullable OfflineCopyMetadata stored, List<String> queueIds) {
        if (stored == null) return new SongRow(videoId, "", "", 0, -1);

        return new SongRow(videoId, stored.title(), stored.artist(), stored.durationMs(),
                queueIds.indexOf(videoId));
    }

    /** The artist and the duration of a song, whichever are known, joined by a dot. */
    static String songSubtitle(String artist, long durationMs) {
        String duration = durationText(durationMs);
        if (artist.isEmpty()) return duration;

        return duration.isEmpty() ? artist : artist + " · " + duration;
    }

    /** {@code 3:07}, or {@code 1:02:03} from an hour on; empty for an unknown duration. */
    static String durationText(long durationMs) {
        if (durationMs <= 0) return "";

        long seconds = durationMs / 1000;
        long hours = seconds / 3600;
        long minutes = seconds / 60 % 60;
        long rest = seconds % 60;
        String pair = (rest < 10 ? "0" : "") + rest;
        return hours > 0
                ? hours + ":" + (minutes < 10 ? "0" : "") + minutes + ":" + pair
                : minutes + ":" + pair;
    }

    /** The power of two to decode a cover of the given size with, so that its longer side is not above target. */
    static int sampleSize(int width, int height, int target) {
        int sample = 1;
        while (sample < MAX_SAMPLE_SIZE && Math.max(width, height) / sample > target) sample *= 2;
        return sample;
    }

    /** The browse command of a page: the browse endpoint with the page id in its field 2. */
    static byte[] browseCommand(String browseId) {
        byte[] id = browseId.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream endpoint = new ByteArrayOutputStream(id.length + 6);
        writeVarint(endpoint, (BROWSE_ID_FIELD << 3) | 2);
        writeVarint(endpoint, id.length);
        endpoint.write(id, 0, id.length);

        ByteArrayOutputStream command = new ByteArrayOutputStream(endpoint.size() + 10);
        writeVarint(command, ((long) BROWSE_ENDPOINT_FIELD << 3) | 2);
        writeVarint(command, endpoint.size());
        byte[] bytes = endpoint.toByteArray();
        command.write(bytes, 0, bytes.length);
        return command.toByteArray();
    }

    private static void writeVarint(ByteArrayOutputStream output, long value) {
        while ((value & ~0x7FL) != 0) {
            output.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        output.write((int) value);
    }
}
