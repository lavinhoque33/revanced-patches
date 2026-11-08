/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.music.patches.misc.requests.MusicBrowseRequest;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

// download the complete playlist, then publish a native offline queue in its original order.
public final class PlaylistDownloads {
    private static final Set<String> active = ConcurrentHashMap.newKeySet();

    private PlaylistDownloads() {}

    public static void enqueue(String playlistId) {
        // Decided once for the whole playlist, so a setting changed mid-way cannot split it between modes.
        DownloadMode mode = LocalDownloadManager.currentMode();
        if (mode == DownloadMode.OFF) return;
        if (mode.localFiles() && !NativeDownloads.ensureStoragePermission()) return;
        if (!active.add(playlistId)) {
            Utils.showToastShort(str("morphe_music_downloads_already_running"));
            return;
        }
        Utils.showToastShort(str("morphe_music_playlist_download_started"));
        Utils.submitOnBackgroundThread(() -> {
            String title = playlistId;
            SourceHeader header = SourceHeader.EMPTY;
            try {
                if (mode.localFiles()) NativeDownloads.migrateLegacy();
                List<String> tracks = new ArrayList<>();
                Set<String> continuations = new HashSet<>();
                String continuation = null;
                do {
                    JSONObject response = MusicBrowseRequest.browse(Utils.getContext(), "VL" + playlistId,
                            continuation, null);
                    if (continuation == null) {
                        header = header(playlistId, response);
                        if (!header.hasTitle()) throw new IOException("Playlist title is unavailable");
                        title = header.title();
                    }
                    Page page = readPage(response);
                    tracks.addAll(page.videoIds());
                    continuation = page.continuation();
                    if (continuation != null && !continuations.add(continuation)) {
                        throw new IOException("Music repeated a playlist continuation");
                    }
                } while (continuation != null);
                if (tracks.isEmpty()) throw new IOException("Playlist contains no downloadable tracks");
                List<Long> mediaIds = new ArrayList<>(tracks.size());
                int downloaded = 0;
                for (int index = 0; index < tracks.size(); index++) {
                    // already-saved tracks finish in milliseconds; unpaced
                    // updates exceed Android's per-app rate limit, which then drops the final notification.
                    if (LocalDownloadManager.canPostProgress()) {
                        LocalDownloadManager.showDownloadNotification("playlist:" + playlistId, title,
                                index * 100 / tracks.size(), true,
                                str("morphe_music_playlist_download_progress", index + 1, tracks.size()));
                    }
                    LocalDownloadManager.Saved saved = LocalDownloadManager.download(tracks.get(index), mode);
                    if (saved.downloaded()) downloaded++;
                    if (mode.localFiles()) mediaIds.add(NativeAudioStore.mediaId(saved.nativeTrack()));
                }
                // The playlist in Device files exists only for local files; a stream copy is invisible there.
                if (mode.localFiles()) NativePlaylistStore.save(playlistId, title, mediaIds);
                // The header check follows the stream copies: only a playlist that has them is remembered.
                if (mode.streamCopy()) DownloadedMarkers.onPlaylistSaved(playlistId, tracks, header);
                String done = completionText(mode, downloaded, tracks.size());
                LocalDownloadManager.awaitNotificationSlot();
                LocalDownloadManager.showDownloadNotification("playlist:" + playlistId, title, 100, false, done);
                Utils.showToastShort(done);
            } catch (Exception ex) {
                Logger.printException(() -> "Playlist download failed", ex);
                // Completed songs remain playable, but never label a truncated playlist complete.
                LocalDownloadManager.awaitNotificationSlot();
                String failed = str(mode.localFiles()
                        ? "morphe_music_playlist_download_failed" : "morphe_music_playlist_stream_failed");
                LocalDownloadManager.showDownloadNotification("playlist:" + playlistId, title, 0, false, failed);
                Utils.showToastLong(failed);
            } finally {
                active.remove(playlistId);
                // tracks now in a playlist leave "Not in a playlist";
                // songs kept from a failed download join it.
                if (mode.localFiles()) NativePlaylistStore.refreshUnsortedLater();
            }
            return null;
        });
    }

    /**
     * The text of a finished playlist download. A stream-only download that found every copy already saved says
     * so, because nothing was downloaded.
     */
    static String completionText(DownloadMode mode, int downloaded, int total) {
        if (mode.localFiles()) return str("morphe_music_playlist_download_complete", total);
        if (downloaded == 0) return str("morphe_music_playlist_stream_already_saved", total);
        return str("morphe_music_playlist_stream_complete", total);
    }

    record Page(List<String> videoIds, String continuation) {}

    static Page readPage(JSONObject response) throws IOException {
        List<String> ids = new ArrayList<>();
        String[] next = new String[1];
        boolean[] found = new boolean[1];
        collectShelves(response, ids, next, found, false);
        if (!found[0]) collectShelves(response, ids, next, found, true);
        if (!found[0]) throw new IOException("Music did not return a playlist shelf");
        return new Page(ids, next[0]);
    }

    private static void collectShelves(Object node, List<String> ids, String[] next, boolean[] found,
                                      boolean legacyShelf) throws IOException {
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (key.equals("musicPlaylistShelfRenderer") || key.equals("musicPlaylistShelfContinuation")
                        || (legacyShelf && !found[0]
                        && (key.equals("musicShelfRenderer") || key.equals("musicShelfContinuation")))) {
                    found[0] = true;
                    collectItems(value, ids, next);
                } else if (key.equals("appendContinuationItemsAction") || key.equals("reloadContinuationItemsCommand")) {
                    found[0] = true;
                    collectItems(value, ids, next);
                } else if (!key.equals("header") && !key.equals("menu") && !key.equals("menus")
                        && !key.equals("frameworkUpdates") && !key.equals("musicCarouselShelfRenderer")) {
                    collectShelves(value, ids, next, found, legacyShelf);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collectShelves(array.opt(i), ids, next, found, legacyShelf);
        }
    }

    private static void collectItems(Object node, List<String> ids, String[] next) throws IOException {
        if (node instanceof JSONObject object) {
            for (String key : new String[]{"musicResponsiveListItemRenderer", "musicTwoColumnItemRenderer", "musicMultiRowListItemRenderer"}) {
                JSONObject row = object.optJSONObject(key);
                if (row == null) continue;
                JSONObject itemData = row.optJSONObject("playlistItemData");
                String id = itemData == null ? "" : itemData.optString("videoId");
                if (id.isEmpty()) {
                    JSONObject endpoint = row.optJSONObject("navigationEndpoint");
                    // an owned playlist lists an "Add a song" search row, not a track.
                    if (itemData == null && endpoint != null && endpoint.has("searchEndpoint")) return;
                    JSONObject watch = endpoint == null ? null : endpoint.optJSONObject("watchEndpoint");
                    if (watch != null) id = watch.optString("videoId");
                }
                if (id.isEmpty()) throw new IOException("A playlist track is unavailable");
                ids.add(id); // Deliberate duplicate positions must remain in the playlist.
                return; // Never mistake a row's menus/recommendations for additional playlist tracks.
            }
            JSONObject continuation = object.optJSONObject("nextContinuationData");
            if (continuation == null) continuation = object.optJSONObject("continuationCommand");
            if (continuation != null) {
                String token = continuation.optString("continuation", continuation.optString("token"));
                if (!token.isEmpty()) next[0] = token;
                return;
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectItems(object.opt(keys.next()), ids, next);
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collectItems(array.opt(i), ids, next);
        }
    }

    /** The header of a playlist or album, read off the network. For use on a worker thread. */
    static SourceHeader fetchHeader(String playlistId) throws Exception {
        return header(playlistId, MusicBrowseRequest.browse(Utils.getContext(), "VL" + playlistId, null, null));
    }

    private static SourceHeader header(String playlistId, JSONObject response) throws Exception {
        SourceHeader header = SourceHeader.read(response);
        return header.hasTitle() ? header : albumHeader(playlistId, response);
    }

    /**
     * Album playlists (OLAK…) have no header in their playlist response. Every row links to
     * the album page instead; use that page's header only if all rows agree on one album and
     * the album page itself points back at this playlist.
     */
    private static SourceHeader albumHeader(String playlistId, JSONObject response) throws Exception {
        Set<String> albums = new HashSet<>();
        collectAlbums(response, albums);
        if (albums.size() != 1) return SourceHeader.EMPTY;
        JSONObject album = MusicBrowseRequest.browse(Utils.getContext(), albums.iterator().next(), null, null);
        return album.toString().contains("\"" + playlistId + "\"") ? SourceHeader.read(album) : SourceHeader.EMPTY;
    }

    private static void collectAlbums(Object node, Set<String> albums) {
        if (node instanceof JSONObject object) {
            JSONObject endpoint = object.optJSONObject("browseEndpoint");
            String id = endpoint == null ? "" : endpoint.optString("browseId");
            if (id.startsWith("MPREb_")) albums.add(id);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) collectAlbums(object.opt(keys.next()), albums);
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collectAlbums(array.opt(i), albums);
        }
    }
}
