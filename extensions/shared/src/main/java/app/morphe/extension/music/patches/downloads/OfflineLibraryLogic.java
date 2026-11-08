/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The row models of the stream copies in the Downloads page, with no Android dependency: the downloaded albums and
 * playlists, the songs, and the bytes of the browse command of a page.
 */
final class OfflineLibraryLogic {
    /** The Downloads page the Library tab, the account menu and the offline banner all navigate to. */
    static final String DOWNLOADS_BROWSE_ID = "FEmusic_offline";

    private static final int BROWSE_ENDPOINT_FIELD = 48_687_626;
    private static final int BROWSE_ID_FIELD = 2;

    /**
     * A downloaded album or playlist. {@code songCount} counts the songs that have a stream copy,
     * {@code availableCount} those of them with stored data; {@code coverVideoId} is the first of the latter and
     * {@code thumbnailUrl} the recorded cover of the source, null when it has none.
     */
    record SourceRow(String sourceId, String title, int songCount, int availableCount,
                     @Nullable String coverVideoId, @Nullable String thumbnailUrl) {}

    /**
     * A song of a list. {@code queueIndex} is its position in the offline queue of the list, or -1 when it has no
     * stored data and cannot play offline. {@code title} is empty for such a song: nothing is known of it but its id.
     * {@code thumbnailUrl} is the recorded cover of the song, null when unknown.
     */
    record SongRow(String videoId, String title, String artist, long durationMs, int queueIndex,
                   @Nullable String thumbnailUrl) {
        boolean availableOffline() {
            return queueIndex >= 0;
        }
    }

    private OfflineLibraryLogic() {}

    /** The albums and playlists, in the given order, with their songs counted; one without a song is left out. */
    static List<SourceRow> sourceRows(List<OfflineSourceStore.Source> sources,
                                      Function<String, List<OfflineSourceStore.Track>> tracksOf) {
        List<SourceRow> rows = new ArrayList<>();
        for (OfflineSourceStore.Source source : sources) {
            List<OfflineSourceStore.Track> tracks = tracksOf.apply(source.id());
            if (tracks.isEmpty()) continue;

            rows.add(new SourceRow(source.id(), source.title(), tracks.size(), availableCount(tracks),
                    coverOf(tracks), source.thumbnailUrl().isEmpty() ? null : source.thumbnailUrl()));
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
        if (stored == null) return new SongRow(videoId, "", "", 0, -1, null);

        return new SongRow(videoId, stored.title(), stored.artist(), stored.durationMs(),
                queueIds.indexOf(videoId), stored.thumbnailUrl());
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

    /** The browse command of a page: the browse endpoint with the page id in its field 2. */
    static byte[] browseCommand(String browseId) {
        byte[] endpoint = new ProtoWriter().string(BROWSE_ID_FIELD, browseId).toByteArray();
        return new ProtoWriter().message(BROWSE_ENDPOINT_FIELD, endpoint).toByteArray();
    }
}
