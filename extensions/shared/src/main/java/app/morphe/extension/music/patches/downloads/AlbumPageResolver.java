/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.misc.requests.MusicBrowseRequest;
import app.morphe.extension.shared.utils.Utils;

/**
 * Finds the album page ({@code MPREb_…}) of a downloaded album playlist ({@code OLAK5uy_…}) and proves it. The
 * playlist response has no header: its track rows link to the album page, and a link is only a candidate. The album
 * page is the candidate whose native first page says that its primary Play button owns the playlist, the one
 * relationship that makes the two the same album.
 */
final class AlbumPageResolver {
    /** How many linked album pages are asked for before the album is left without one. */
    static final int MAX_CANDIDATES = 3;

    private static final String SHELF = "musicPlaylistShelfRenderer";

    /** The native first page of an album page, as the app's own page request returns it. */
    interface Fetch {
        byte[] nativePage(String pageId) throws Exception;
    }

    /** The proven album page of a playlist: its id and the native first page that proved it. */
    record Resolved(String pageId, byte[] page) {}

    private AlbumPageResolver() {}

    /**
     * The album pages the track rows of a playlist response link to, the most linked first, at most
     * {@link #MAX_CANDIDATES}. A response whose rows have no such link falls back to every link it has.
     */
    static List<String> candidates(Object playlistResponse) {
        Map<String, Integer> links = new LinkedHashMap<>();
        collect(playlistResponse, false, true, links);
        if (links.isEmpty()) collect(playlistResponse, false, false, links);

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(links.entrySet());
        ranked.sort((first, second) -> Integer.compare(second.getValue(), first.getValue()));

        List<String> pages = new ArrayList<>();
        for (Map.Entry<String, Integer> link : ranked) {
            if (pages.size() < MAX_CANDIDATES) pages.add(link.getKey());
        }
        return pages;
    }

    private static void collect(Object node, boolean inShelf, boolean shelfOnly, Map<String, Integer> links) {
        if (node instanceof JSONObject object) {
            JSONObject endpoint = object.optJSONObject("browseEndpoint");
            String id = endpoint == null ? "" : endpoint.optString("browseId");
            if ((inShelf || !shelfOnly) && OfflineBrowsePagesLogic.isAlbumPage(id)) links.merge(id, 1, Integer::sum);

            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                collect(object.opt(key), inShelf || SHELF.equals(key), shelfOnly, links);
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), inShelf, shelfOnly, links);
        }
    }

    /**
     * The first candidate that is the album page of the playlist, with the page that proved it; null when none is.
     * A fetch that fails ends the search: the answer is not known, and a later attempt asks again.
     */
    @Nullable
    static Resolved resolve(String playlistId, List<String> candidates, Fetch fetch) throws Exception {
        for (String pageId : candidates) {
            byte[] page = fetch.nativePage(pageId);
            if (PlaylistSyncPage.isAlbumPage(pageId, playlistId, page)) return new Resolved(pageId, page);
        }
        return null;
    }

    /** {@link #resolve} for a playlist response read off the network. A worker thread. */
    @Nullable
    static Resolved resolveOnline(String playlistId, JSONObject playlistResponse) throws Exception {
        return resolveOnline(playlistId, candidates(playlistResponse));
    }

    @Nullable
    private static Resolved resolveOnline(String playlistId, List<String> candidates) throws Exception {
        Utils.verifyOffMainThread();
        return resolve(playlistId, candidates,
                pageId -> MusicBrowseRequest.browseNative(Utils.getContext(), pageId, null));
    }

    /**
     * The proven album page of a downloaded album, asked of the network: the page it is already mapped to, else
     * the pages the rows of its playlist link to. A worker thread.
     */
    @Nullable
    static Resolved resolveDownloaded(String playlistId) throws Exception {
        Utils.verifyOffMainThread();
        String mapped = OfflineBrowsePages.albumPage(playlistId);
        if (mapped != null) return resolveOnline(playlistId, List.of(mapped));

        JSONObject playlist = MusicBrowseRequest.browse(Utils.getContext(), "VL" + playlistId, null, null);
        return resolveOnline(playlistId, playlist);
    }

    /**
     * {@link #resolveDownloaded} and the record of what it proved: the id of the album page that is now saved for
     * the album, or null when none was proved or the album is no longer downloaded. A worker thread.
     */
    @Nullable
    static String resolveAndSave(String playlistId) throws Exception {
        Resolved album = resolveDownloaded(playlistId);
        if (album == null) return null;

        return OfflineBrowsePages.rememberAlbum(album.pageId(), playlistId, album.page()) < 0 ? null : album.pageId();
    }
}
