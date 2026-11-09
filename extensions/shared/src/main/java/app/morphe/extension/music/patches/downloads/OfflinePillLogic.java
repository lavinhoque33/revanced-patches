/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the Song/Video pill starts with no network. The replayed queue holds the songs of a list, so no queue item
 * names the other kind of the song that plays; the replay keeps the queue it answered last and, with the video copies,
 * that is enough to tell the other kind's id and its place in the queue.
 */
final class OfflinePillLogic {
    /** The queue the replay answered last: its playlist id and the ids of its songs, in order. */
    record Queue(String playlistId, List<String> songIds) {}

    /** The watch an offline press starts: the other kind of the playing song. */
    record Target(String videoId, int musicVideoType, String playlistId, int index, String songId) {}

    private OfflinePillLogic() {}

    /**
     * The watch the press starts, or null when the press is not ours: none of the ids that play is a song of the
     * queue that has a video copy, or a video copy of such a song.
     *
     * @param videoToSong The committed video copies, music video id to the id of the song they belong to.
     * @param playing     The ids the player may be playing; the first that names a pair wins. Null ones are ignored.
     */
    @Nullable
    static Target target(@Nullable Queue queue, Map<String, String> videoToSong, String... playing) {
        if (queue == null || videoToSong.isEmpty()) return null;

        Map<String, String> videoOfSong = new HashMap<>();
        for (Map.Entry<String, String> pair : videoToSong.entrySet()) videoOfSong.put(pair.getValue(), pair.getKey());

        for (String id : playing) {
            if (id == null || id.isEmpty()) continue;

            String song = videoToSong.get(id);
            boolean video = song != null;
            if (!video) {
                song = id;
            }
            String other = video ? song : videoOfSong.get(song);
            int index = queue.songIds().indexOf(song);
            if (other == null || other.equals(id) || index < 0) continue;

            return new Target(other, video ? CommandRewrite.TYPE_ATV : CommandRewrite.TYPE_OMV, queue.playlistId(),
                    index, song);
        }
        return null;
    }
}
