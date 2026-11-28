/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.utils.Logger;

/** Projects the stable copy catalogue onto exact native media representations. Does not copy media bytes. */
final class NativeMirrorMedia {
    record Row(NativeMirrorStreams.Stream stream, Format format, long durationMs) {}
    record Video(String id, PlayerResponse response, List<Row> rows) {}
    record Playlist(OfflineSourceStore.Source source, List<String> tracks) {}
    record Plan(Map<String, Video> videos, Map<String, Playlist> playlists, Set<String> loose,
                Set<NativeMirrorDatabase.Item> retained) {
        static final Plan EMPTY = new Plan(Map.of(), Map.of(), Set.of(), Set.of());
    }

    private record Pair(Video song, @Nullable Video originalMusicVideo) {}

    private NativeMirrorMedia() {}

    static List<NativeMirrorDatabase.Item> initialCatalogue() {
        List<NativeMirrorDatabase.Item> items = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        Map<String, String> aliases = RawAudioStore.aliases();
        Set<String> copies = RawAudioStore.copyIds();
        for (OfflineSourceStore.Source source : OfflineCopyStore.sources()) {
            if (DownloadedMarkers.completedStreamSource(source)) {
                items.add(new NativeMirrorDatabase.Item(source.id(), true));
            }
            for (String id : source.videoIds()) listed.add(OfflineCopyStore.copyId(id, copies, aliases));
        }
        Set<String> loose = new HashSet<>(copies);
        loose.removeAll(listed);
        loose.addAll(RawAudioStore.individualIds());
        for (String id : loose) items.add(new NativeMirrorDatabase.Item(id, false));
        return items;
    }

    static Plan plan(Set<NativeMirrorDatabase.Item> items) {
        Map<String, Video> videos = new LinkedHashMap<>();
        Map<String, Playlist> playlists = new LinkedHashMap<>();
        Set<String> listed = new HashSet<>();
        Set<String> loose = new HashSet<>();
        Set<NativeMirrorDatabase.Item> retained = new HashSet<>();
        Set<String> copies = RawAudioStore.copyIds();
        Map<String, String> aliases = RawAudioStore.aliases();
        for (OfflineSourceStore.Source source : OfflineCopyStore.sources()) {
            NativeMirrorDatabase.Item item = new NativeMirrorDatabase.Item(source.id(), true);
            if (!items.contains(item)) continue;
            retained.add(item);
            if (!source.title().isEmpty() && DownloadedMarkers.completedStreamSource(source)) {
                List<String> tracks = new ArrayList<>(source.videoIds().size());
                for (String id : source.videoIds()) {
                    String copy = OfflineCopyStore.copyId(id, copies, aliases);
                    if (!copies.contains(copy)) continue;
                    if (!videos.containsKey(id)) {
                        Pair pair = read(id, copy, true);
                        if (pair == null) continue;
                        videos.put(id, pair.song());
                        if (pair.originalMusicVideo() != null) {
                            Video musicVideo = pair.originalMusicVideo();
                            videos.putIfAbsent(musicVideo.id(), musicVideo);
                        }
                    }
                    listed.add(copy);
                    tracks.add(id);
                }
                if (!tracks.isEmpty()) playlists.put(item.id(), new Playlist(source, tracks));
            }
        }
        for (NativeMirrorDatabase.Item item : items) {
            if (item.playlist() || !copies.contains(item.id())) continue;
            retained.add(item);
            if (listed.contains(item.id())) continue;
            Pair pair = read(item.id(), item.id(), false);
            if (pair == null) continue;
            videos.putIfAbsent(item.id(), pair.song());
            loose.add(item.id());
        }
        return new Plan(videos, playlists, loose, retained);
    }

    @Nullable
    private static Pair read(String id, String copy, boolean preferVideo) {
        try {
            if (NativeOfflineImport.includes(id) || NativeOfflineImport.includes(copy)) return null;
            OfflineCopyFiles.Stored stored = OfflineCopyStore.find(copy);
            if (stored == null) return null;
            PlayerResponse response = PlayerResponse.parseFrom(stored.response());
            if (preferVideo) {
                String video = VideoCopyStore.videoOf(copy);
                if (video == null) video = id;
                if (!NativeOfflineImport.includes(video)) {
                    Video musicVideo = videoCopy(video);
                    if (musicVideo != null) {
                        return id.equals(video) ? new Pair(musicVideo, null)
                                : new Pair(new Video(id, announcing(id, response, musicVideo.rows()),
                                        musicVideo.rows()), musicVideo);
                    }
                }
            }
            RawAudioFiles.Entry raw = RawAudioStore.find(copy);
            if (raw == null) return null;
            for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
                if (format.getItag() != raw.itag()) continue;
                Row row = row(format, raw.file(), raw.length(), raw.modified(), true, 0);
                if (row == null) return null;
                List<Row> rows = List.of(row);
                return new Pair(new Video(id, announcing(id, response, rows), rows), null);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Native offline index: copy unavailable for " + id, ex);
        }
        return null;
    }

    @Nullable
    private static Video videoCopy(String id) throws Exception {
        VideoCopyFiles.Entry copy = VideoCopyStore.find(id);
        if (copy == null) return null;
        byte[] bytes = VideoCopyStore.response(id);
        if (bytes == null) return null;
        PlayerResponse response = PlayerResponse.parseFrom(bytes);
        if (!id.equals(response.getVideoDetails().getVideoId())) return null;
        VideoCopyLogic.Stored formats = VideoCopyLogic.stored(response, copy.video().itag(), copy.audio().itag());
        if (formats == null) return null;
        Row video = row(formats.video(), copy.video().file(), copy.video().length(), copy.video().modified(),
                false, copy.durationMs());
        Row audio = row(formats.audio(), copy.audio().file(), copy.audio().length(), copy.audio().modified(),
                true, copy.durationMs());
        if (video == null || audio == null) return null;
        List<Row> rows = List.of(video, audio);
        return new Video(id, announcing(id, response, rows), rows);
    }

    @Nullable
    private static Row row(Format format, File file, long length, long modified, boolean audio, long duration) {
        if (format.getItag() <= 0 || length <= 0 || modified <= 0 || format.getLastModified() != modified
                || format.getContentLength() != length
                || !format.getMimeType().startsWith(audio ? "audio/" : "video/")) return null;
        Format indexed = NativeMirrorProto.indexFormat(format, audio);
        String track = indexed.getNativeAudioTrackId();
        return new Row(new NativeMirrorStreams.Stream(format.getItag(), modified, file, length, audio, track),
                indexed, format.getApproxDurationMs() > 0 ? format.getApproxDurationMs() : duration);
    }

    private static PlayerResponse announcing(String id, PlayerResponse response, List<Row> rows) {
        StreamingData.Builder streaming = StreamingData.newBuilder();
        for (Row row : rows) streaming.addAdaptiveFormats(row.format());
        return NativeMirrorProto.playerResponse(id, response, streaming);
    }
}
