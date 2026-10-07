/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-06.
 * modified by lavinhoque33, 2026-10-07: artwork, reload after phone changes and after login, keep loaded lists on failure,
 * keep Playlists node ids across restarts.
 */

package app.morphe.extension.music.patches.misc;

import android.content.Context;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Fills the "Library → Playlists" page of the Android Auto media browser.
 * <p>
 * Since mid 2026 the server-side media browser tree that YouTube Music downloads for Android Auto
 * contains a "Playlists" node without children, so Android Auto shows "No items".
 * This patch answers that node itself: it browses {@code FEmusic_liked_playlists} (the same list
 * the phone's Library shows) with the app's own login, shows each playlist as a folder, lists its
 * tracks ({@code VL<playlistId>}), and plays a track by handing a
 * {@code music.youtube.com/watch?v=…&list=…} link to the app's {@code onPlayFromUri}.
 * <p>
 * When the phone app adds a playlist, edits one, or likes a song, the open Android Auto pages
 * reload after a few seconds (the same request list Morphe's Android Auto patch reacts to).
 * If a later page fails to load, the pages loaded so far are shown. If the whole load fails,
 * the previous list is shown.
 */
@SuppressWarnings("unused")
public final class AndroidAutoPlaylistsPatch {
    private static final String ID_PREFIX = "morphe_aa:";
    private static final String PLAYLIST_ID_PREFIX = ID_PREFIX + "playlist:";
    private static final String TRACK_ID_PREFIX = ID_PREFIX + "track:";
    private static final String TRACK_ID_SEPARATOR = "|";

    /** Drawable name fragment of the app's own "Playlists" node icon (yt_outline_experimental_playlist_vd_theme_24). */
    private static final String PLAYLISTS_NODE_ICON_FRAGMENT = "_playlist_";
    private static final String ANDROID_RESOURCE_SCHEME = "android.resource";

    private static final String LIBRARY_PLAYLISTS_BROWSE_ID = "FEmusic_liked_playlists";
    private static final String PLAYLIST_BROWSE_ID_PREFIX = "VL";

    private static final String BROWSE_URL = "https://youtubei.googleapis.com/youtubei/v1/browse?prettyPrint=false";
    private static final String ITEM_FIELDS =
            "contents.musicTwoColumnItemRenderer(title,subtitle,thumbnail.musicThumbnailRenderer.thumbnail.thumbnails(url,width),"
                    + "navigationEndpoint(watchEndpoint(videoId,playlistId),browseEndpoint.browseId)),continuations";
    /** Response field mask; without it a library page is ~3 MB of menus. */
    private static final String FIELDS =
            "contents.singleColumnBrowseResultsRenderer.tabs.tabRenderer.content.sectionListRenderer.contents("
                    + "musicPlaylistShelfRenderer(" + ITEM_FIELDS + "),musicShelfRenderer(" + ITEM_FIELDS + ")),"
                    + "continuationContents(musicPlaylistShelfContinuation(" + ITEM_FIELDS + "),"
                    + "musicShelfContinuation(" + ITEM_FIELDS + "),"
                    + "sectionListContinuation.contents(musicShelfRenderer(" + ITEM_FIELDS + "),musicPlaylistShelfRenderer(" + ITEM_FIELDS + ")))";
    private static final String CLIENT_NAME = "ANDROID_MUSIC";
    private static final String CLIENT_ID = "21";
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10_000;

    private static final int MAX_PLAYLISTS = 200;
    private static final int MAX_TRACKS = 500;
    private static final int MAX_PAGES = 20;
    private static final long CACHE_TTL_MILLISECONDS = 5 * 60 * 1000;
    /** After this, show what has loaded instead of following more pages. */
    private static final long MAX_LOAD_MILLISECONDS = 25_000;

    /** Wait for the server (and new playlist artwork) to reflect a change before reloading. */
    private static final long REFRESH_DELAY_MILLISECONDS = 5_000;
    /** Android Auto pages reloaded after a change: the Playlists page plus the most recently opened playlists. */
    private static final int MAX_SUBSCRIPTIONS = 6;
    /** InnerTube requests that change the Library or a playlist. */
    private static final String[] LIBRARY_CHANGE_ENDPOINTS = {
            "/browse/edit_playlist", "/like/like", "/like/removelike", "/playlist/create", "/playlist/delete"
    };

    // Values of androidx.media.utils.MediaConstants.
    private static final String CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final String CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final String CONTENT_STYLE_SINGLE_ITEM_HINT = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT";
    private static final int CONTENT_STYLE_LIST_ITEM_HINT_VALUE = 1;

    private static final String COMPAT_MEDIA_ITEM_CLASS = "android.support.v4.media.MediaBrowserCompat$MediaItem";

    /**
     * Media ids of the app's "Playlists" nodes seen in results. Ids carry a per-session UUID, so keep a few,
     * and keep them across app restarts: Android Auto asks for the open page again with the old id.
     */
    @GuardedBy("itself")
    private static final Set<String> playlistsNodeIds = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 16;
        }
    });
    private static final String PREFERENCES_NAME = "morphe_android_auto_playlists";
    private static final String PREFERENCE_PLAYLISTS_NODE_IDS = "playlists_node_ids";
    /** Set by the first page request, before any result can name a Playlists node. */
    @Nullable
    private static volatile Context applicationContext;

    @GuardedBy("itself")
    private static final Map<String, CachedChildren> childrenCache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedChildren> eldest) {
            return size() > 32;
        }
    };

    /**
     * A page failed because the app had not sent a logged-in request yet (Android Auto starts the app
     * and asks for pages right away). The next logged-in request reloads the open pages.
     */
    private static volatile boolean waitingForAuthorization;

    @Nullable
    private static volatile Parcelable.Creator<?> compatMediaItemCreator;

    /** Android Auto page loads answered by this patch, by parent id, so they can be repeated after a change. */
    @GuardedBy("itself")
    private static final Map<String, Subscription> subscriptions = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Subscription> eldest) {
            return size() > MAX_SUBSCRIPTIONS;
        }
    };

    /** Cached lists older than this are reloaded (but still shown if reloading fails). */
    private static volatile long cacheInvalidatedAtMillis;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Runnable REFRESH_SUBSCRIPTIONS = AndroidAutoPlaylistsPatch::refreshSubscriptions;

    private record CachedChildren(long timeMillis, List<Entry> entries) {
    }

    private record Entry(String mediaId, String title, @Nullable String subtitle,
                         @Nullable String artworkUrl, boolean browsable) {
    }

    /**
     * One Android Auto page load: the compat browser service, Android Auto's connection record, and
     * the load options. Weak, so a disconnected Android Auto is not kept alive.
     */
    private record Subscription(String parentId, WeakReference<Object> service,
                                WeakReference<Object> connection, @Nullable Bundle options) {
    }

    private AndroidAutoPlaylistsPatch() {
    }

    /**
     * Injection point: start of {@code MediaBrowserServiceCompat.Result.sendResult(Object)}.
     * Remembers the media id of the app's "Playlists" node so its children can be answered here.
     */
    public static void onSendResult(@Nullable Object result) {
        if (!(result instanceof List<?> items)) {
            return;
        }
        try {
            for (Object item : items) {
                if (!(item instanceof Parcelable compatItem)) {
                    continue;
                }
                MediaBrowser.MediaItem mediaItem = toFrameworkMediaItem(compatItem);
                if (mediaItem == null) {
                    continue;
                }
                if (compatMediaItemCreator == null) {
                    compatMediaItemCreator = getCreator(compatItem.getClass());
                }
                if (!mediaItem.isBrowsable() || !isPlaylistsNode(mediaItem.getDescription())) {
                    continue;
                }
                String mediaId = mediaItem.getMediaId();
                if (mediaId == null || mediaId.startsWith(ID_PREFIX)) {
                    continue;
                }
                synchronized (playlistsNodeIds) {
                    if (playlistsNodeIds.add(mediaId)) {
                        Logger.printDebug(() -> "Playlists node found: " + mediaId);
                        savePlaylistsNodeIds();
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSendResult failure", ex);
        }
    }

    /**
     * Injection point: start of {@code MusicBrowserService.onLoadChildren(String, Result, Bundle)}.
     *
     * @return true if {@link #loadChildren} answers this parent id.
     */
    public static boolean shouldLoadChildren(@NonNull Context context, @Nullable String parentId) {
        if (applicationContext == null) {
            loadPlaylistsNodeIds(context.getApplicationContext());
        }
        if (parentId == null) {
            return false;
        }
        if (parentId.startsWith(PLAYLIST_ID_PREFIX)) {
            return true;
        }
        synchronized (playlistsNodeIds) {
            return playlistsNodeIds.contains(parentId);
        }
    }

    private static void loadPlaylistsNodeIds(Context context) {
        String saved = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(PREFERENCE_PLAYLISTS_NODE_IDS, "");
        synchronized (playlistsNodeIds) {
            // Saved ids are older than any seen in this process.
            List<String> seen = new ArrayList<>(playlistsNodeIds);
            playlistsNodeIds.clear();
            for (String id : saved.split("\n")) {
                if (!id.isEmpty()) {
                    playlistsNodeIds.add(id);
                }
            }
            playlistsNodeIds.addAll(seen);
        }
        applicationContext = context;
    }

    @GuardedBy("playlistsNodeIds")
    private static void savePlaylistsNodeIds() {
        Context context = applicationContext;
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREFERENCE_PLAYLISTS_NODE_IDS, String.join("\n", playlistsNodeIds))
                .apply();
    }

    /**
     * Injection point: start of {@code MediaBrowserServiceCompat.performLoadChildren(String, ConnectionRecord, Bundle)}.
     * Remembers loads of pages this patch answers so {@link #refreshSubscriptions} can repeat them.
     */
    public static void onPerformLoadChildren(Object service, @Nullable String parentId,
                                             Object connection, @Nullable Bundle options) {
        if (!(service instanceof Context context) || !shouldLoadChildren(context, parentId)) {
            return;
        }
        synchronized (subscriptions) {
            subscriptions.put(parentId, new Subscription(parentId, new WeakReference<>(service),
                    new WeakReference<>(connection), options));
        }
    }

    /**
     * Injection point: the app builds an InnerTube request.
     * Requests that change the Library or a playlist reload the open Android Auto pages,
     * as does the first logged-in request after a page failed for lack of one.
     */
    public static void onBuildRequest(@Nullable String url, @Nullable Map<String, String> headers) {
        if (url == null || !url.contains("/youtubei/v1/")) {
            return;
        }
        try {
            if (waitingForAuthorization && !AuthUtils.getAuthorization().isEmpty()) {
                waitingForAuthorization = false;
                scheduleRefresh("login headers available", 0);
            }
            String path = Uri.parse(url).getPath();
            if (path == null) {
                return;
            }
            for (String endpoint : LIBRARY_CHANGE_ENDPOINTS) {
                if (path.endsWith(endpoint)) {
                    cacheInvalidatedAtMillis = System.currentTimeMillis();
                    scheduleRefresh(endpoint, REFRESH_DELAY_MILLISECONDS);
                    return;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onBuildRequest failure", ex);
        }
    }

    /** Several changes in a row (liking songs one after another) cause one reload. */
    private static void scheduleRefresh(String reason, long delayMillis) {
        synchronized (subscriptions) {
            if (subscriptions.isEmpty()) {
                return;
            }
        }
        Logger.printDebug(() -> "Reloading Android Auto pages in " + delayMillis + " ms: " + reason);
        mainHandler.removeCallbacks(REFRESH_SUBSCRIPTIONS);
        mainHandler.postDelayed(REFRESH_SUBSCRIPTIONS, delayMillis);
    }

    /** Runs on the main thread, like the browser service's own page loads. */
    private static void refreshSubscriptions() {
        // Lists loaded during the delay may predate the change.
        cacheInvalidatedAtMillis = System.currentTimeMillis();
        List<Subscription> loads;
        synchronized (subscriptions) {
            loads = new ArrayList<>(subscriptions.values());
        }
        for (Subscription load : loads) {
            Object service = load.service.get();
            Object connection = load.connection.get();
            if (service == null || connection == null) {
                synchronized (subscriptions) {
                    subscriptions.remove(load.parentId);
                }
                continue;
            }
            try {
                reloadFolder(service, load.parentId, connection, load.options);
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Could not reload Android Auto page: " + load.parentId, ex);
            }
        }
    }

    /**
     * Injection point: called after the patch detached {@code result}.
     * Loads the children on a background thread and sends them through {@code result}.
     */
    public static void loadChildren(@NonNull Context context, @NonNull String parentId, @NonNull Object result) {
        Utils.runOnBackgroundThread(() -> {
            List<Parcelable> items = new ArrayList<>();
            try {
                List<Entry> entries = getChildren(context, parentId);
                for (Entry entry : entries) {
                    Parcelable item = toCompatMediaItem(entry);
                    if (item != null) {
                        items.add(item);
                    }
                }
                Logger.printDebug(() -> "Sending " + items.size() + " items for " + parentId);
            } catch (Exception ex) {
                Logger.printException(() -> "loadChildren failure: " + parentId, ex);
            }
            try {
                sendResult(result, items);
            } catch (Exception ex) {
                Logger.printException(() -> "sendResult failure: " + parentId, ex);
            }
        });
    }

    /**
     * Injection point: start of {@code MediaSessionCompat.Callback.onPlayFromMediaId(String, Bundle)}.
     *
     * @return The link to hand to {@code onPlayFromUri}, or null if the media id is not from this patch.
     */
    @Nullable
    public static Uri getPlaybackUri(@Nullable String mediaId) {
        if (mediaId == null || !mediaId.startsWith(TRACK_ID_PREFIX)) {
            return null;
        }
        // morphe_aa:track:<index>|<videoId>|<playlistId>
        String[] parts = mediaId.substring(TRACK_ID_PREFIX.length()).split("\\" + TRACK_ID_SEPARATOR, 3);
        if (parts.length < 2 || parts[1].isEmpty()) {
            return null;
        }
        Uri.Builder builder = new Uri.Builder()
                .scheme("https")
                .authority("music.youtube.com")
                .path("watch")
                .appendQueryParameter("v", parts[1]);
        if (parts.length == 3 && !parts[2].isEmpty()) {
            builder.appendQueryParameter("list", parts[2]);
        }
        Uri uri = builder.build();
        Logger.printDebug(() -> "Playing " + uri);
        return uri;
    }

    /**
     * Replaced by the patch with a call to {@code MediaBrowserServiceCompat.Result.sendResult(Object)}.
     */
    private static void sendResult(Object result, List<Parcelable> items) {
        throw new IllegalStateException("Not patched");
    }

    /**
     * Replaced by the patch with a call to {@code MediaBrowserServiceCompat.performLoadChildren}.
     * The service sends the new list only if Android Auto's connection is still registered.
     */
    private static void reloadFolder(Object service, String parentId, Object connection, @Nullable Bundle options) {
        throw new IllegalStateException("Not patched");
    }

    private static boolean isPlaylistsNode(MediaDescription description) {
        Uri icon = description.getIconUri();
        if (icon == null || !ANDROID_RESOURCE_SCHEME.equals(icon.getScheme())) {
            return false;
        }
        String name = icon.getLastPathSegment();
        return name != null && name.contains(PLAYLISTS_NODE_ICON_FRAGMENT);
    }

    private static List<Entry> getChildren(Context context, String parentId) throws IOException, JSONException {
        CachedChildren cached;
        synchronized (childrenCache) {
            cached = childrenCache.get(parentId);
        }
        if (cached != null && cached.timeMillis > cacheInvalidatedAtMillis
                && System.currentTimeMillis() - cached.timeMillis < CACHE_TTL_MILLISECONDS) {
            return cached.entries;
        }

        final List<Entry> entries;
        try {
            entries = parentId.startsWith(PLAYLIST_ID_PREFIX)
                    ? loadTracks(context, parentId.substring(PLAYLIST_ID_PREFIX.length()))
                    : loadPlaylists(context);
        } catch (IOException | JSONException | RuntimeException ex) {
            if (cached == null) {
                throw ex;
            }
            // An outdated list is more useful than an empty page.
            Logger.printException(() -> "Loading " + parentId + " failed, showing the previous list", ex);
            return cached.entries;
        }

        synchronized (childrenCache) {
            childrenCache.put(parentId, new CachedChildren(System.currentTimeMillis(), entries));
        }
        return entries;
    }

    private static List<Entry> loadPlaylists(Context context) throws IOException, JSONException {
        List<Entry> entries = new ArrayList<>();
        for (JSONObject renderer : browseAll(context, LIBRARY_PLAYLISTS_BROWSE_ID, MAX_PLAYLISTS)) {
            String browseId = optString(renderer, "navigationEndpoint", "browseEndpoint", "browseId");
            if (browseId == null || !browseId.startsWith(PLAYLIST_BROWSE_ID_PREFIX)) {
                continue;
            }
            String title = getText(renderer.optJSONObject("title"));
            if (title == null) {
                continue;
            }
            entries.add(new Entry(PLAYLIST_ID_PREFIX + browseId, title,
                    getText(renderer.optJSONObject("subtitle")), getThumbnailUrl(renderer), true));
        }
        Logger.printDebug(() -> "Loaded " + entries.size() + " playlists");
        return entries;
    }

    private static List<Entry> loadTracks(Context context, String browseId) throws IOException, JSONException {
        List<Entry> entries = new ArrayList<>();
        for (JSONObject renderer : browseAll(context, browseId, MAX_TRACKS)) {
            String videoId = optString(renderer, "navigationEndpoint", "watchEndpoint", "videoId");
            if (videoId == null) {
                continue; // "Add a song" and similar rows.
            }
            String title = getText(renderer.optJSONObject("title"));
            if (title == null) {
                continue;
            }
            String playlistId = optString(renderer, "navigationEndpoint", "watchEndpoint", "playlistId");
            String mediaId = TRACK_ID_PREFIX + entries.size()
                    + TRACK_ID_SEPARATOR + videoId
                    + TRACK_ID_SEPARATOR + (playlistId == null ? "" : playlistId);
            entries.add(new Entry(mediaId, title, getText(renderer.optJSONObject("subtitle")),
                    getThumbnailUrl(renderer), false));
        }
        Logger.printDebug(() -> "Loaded " + entries.size() + " tracks of " + browseId);
        return entries;
    }

    /**
     * Browses {@code browseId} and follows continuations.
     *
     * @return Every {@code musicTwoColumnItemRenderer} of the shelves, in order.
     */
    private static List<JSONObject> browseAll(Context context, String browseId, int maxItems)
            throws IOException, JSONException {
        List<JSONObject> renderers = new ArrayList<>();
        String continuation = null;
        final long deadline = System.currentTimeMillis() + MAX_LOAD_MILLISECONDS;
        for (int page = 0; page < MAX_PAGES && renderers.size() < maxItems; page++) {
            if (page > 0 && System.currentTimeMillis() > deadline) {
                Logger.printInfo(() -> "Loading " + browseId + " is slow, showing the first " + renderers.size() + " items");
                break;
            }
            final JSONObject response;
            try {
                response = browse(context, browseId, continuation);
            } catch (IOException | JSONException | RuntimeException ex) {
                if (page == 0) {
                    throw ex;
                }
                // Keep the pages that loaded.
                Logger.printException(() -> "Loading more of " + browseId + " failed, showing "
                        + renderers.size() + " items", ex);
                break;
            }
            String[] next = new String[1];
            collectItems(response, renderers, next);
            continuation = next[0];
            if (continuation == null) {
                break;
            }
        }
        return renderers.size() > maxItems ? renderers.subList(0, maxItems) : renderers;
    }

    private static JSONObject browse(Context context, String browseId, @Nullable String continuation)
            throws IOException, JSONException {
        try {
            return browse(context, browseId, continuation, true);
        } catch (BadRequestException ex) {
            // The field mask may no longer match the response layout. Retry with the full response.
            Logger.printDebug(() -> "Browse with field mask failed, retrying without: " + ex.getMessage());
            return browse(context, browseId, continuation, false);
        }
    }

    private static JSONObject browse(Context context, String browseId, @Nullable String continuation,
                                     boolean useFieldMask) throws IOException, JSONException {
        if (AuthUtils.getAuthorization().isEmpty()) {
            waitingForAuthorization = true;
            throw new IOException("No authorization header captured yet");
        }
        String clientVersion = getClientVersion(context);

        JSONObject client = new JSONObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", clientVersion);
        client.put("osName", "Android");
        client.put("osVersion", Build.VERSION.RELEASE);
        client.put("androidSdkVersion", Build.VERSION.SDK_INT);
        Locale locale = Locale.getDefault();
        client.put("hl", locale.toLanguageTag());
        if (!locale.getCountry().isEmpty()) {
            client.put("gl", locale.getCountry());
        }
        JSONObject body = new JSONObject();
        body.put("context", new JSONObject().put("client", client));
        if (continuation != null) {
            body.put("continuation", continuation);
        } else {
            body.put("browseId", browseId);
        }

        String url = useFieldMask
                ? BROWSE_URL + "&fields=" + URLEncoder.encode(FIELDS, StandardCharsets.UTF_8.name())
                : BROWSE_URL;
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", context.getPackageName() + "/" + clientVersion
                    + " (Linux; U; Android " + Build.VERSION.RELEASE + ") gzip");
            connection.setRequestProperty("X-YouTube-Client-Name", CLIENT_ID);
            connection.setRequestProperty("X-YouTube-Client-Version", clientVersion);
            for (Map.Entry<String, String> header : AuthUtils.getRequestHeader().entrySet()) {
                if (header.getValue() != null && !header.getValue().isEmpty()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_BAD_REQUEST && useFieldMask) {
                throw new BadRequestException(Requester.parseErrorString(connection));
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Browse " + browseId + " failed: HTTP " + code);
            }
            return Requester.parseJSONObject(connection);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Collects {@code musicTwoColumnItemRenderer} objects and the next continuation token.
     */
    private static void collectItems(Object node, List<JSONObject> renderers, String[] continuation) {
        if (node instanceof JSONObject object) {
            JSONArray names = object.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                Object value = object.opt(key);
                switch (key) {
                    case "musicTwoColumnItemRenderer" -> {
                        if (value instanceof JSONObject renderer) {
                            renderers.add(renderer);
                        }
                    }
                    case "nextContinuationData" -> {
                        if (value instanceof JSONObject data && continuation[0] == null) {
                            String token = data.optString("continuation");
                            if (!token.isEmpty()) {
                                continuation[0] = token;
                            }
                        }
                    }
                    // Not part of the item lists (only present without the field mask).
                    case "responseContext", "frameworkUpdates", "header", "menu", "fab", "subheaders" -> {
                    }
                    default -> collectItems(value, renderers, continuation);
                }
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                collectItems(array.opt(i), renderers, continuation);
            }
        }
    }

    @Nullable
    private static String getText(@Nullable JSONObject text) {
        if (text == null) {
            return null;
        }
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) {
            String simple = text.optString("simpleText");
            return simple.isEmpty() ? null : simple;
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) {
                builder.append(run.optString("text"));
            }
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    /**
     * The largest thumbnail of an item. The app itself gives Android Auto plain https artwork URLs.
     */
    @Nullable
    private static String getThumbnailUrl(JSONObject renderer) {
        JSONObject thumbnail = renderer.optJSONObject("thumbnail");
        JSONObject musicThumbnail = thumbnail == null ? null : thumbnail.optJSONObject("musicThumbnailRenderer");
        JSONObject details = musicThumbnail == null ? null : musicThumbnail.optJSONObject("thumbnail");
        JSONArray thumbnails = details == null ? null : details.optJSONArray("thumbnails");
        if (thumbnails == null) {
            return null;
        }
        String best = null;
        int bestWidth = -1;
        for (int i = 0; i < thumbnails.length(); i++) {
            JSONObject candidate = thumbnails.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            String url = candidate.optString("url");
            int width = candidate.optInt("width");
            if (!url.isEmpty() && width > bestWidth) {
                best = url;
                bestWidth = width;
            }
        }
        if (best != null && best.startsWith("//")) {
            best = "https:" + best;
        }
        return best;
    }

    @Nullable
    private static String optString(JSONObject object, String... path) {
        JSONObject current = object;
        for (int i = 0; i < path.length - 1; i++) {
            current = current.optJSONObject(path[i]);
            if (current == null) {
                return null;
            }
        }
        String value = current.optString(path[path.length - 1]);
        return value.isEmpty() ? null : value;
    }

    private static String getClientVersion(Context context) throws IOException {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            if (version != null && !version.isEmpty()) {
                return version;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read app version", ex);
        }
        throw new IOException("Unknown app version");
    }

    /**
     * Converts a {@code MediaBrowserCompat.MediaItem} to the framework class.
     * Both write the same parcel layout (flags, then the framework {@code MediaDescription}).
     */
    @Nullable
    private static MediaBrowser.MediaItem toFrameworkMediaItem(Parcelable compatItem) {
        Parcel parcel = Parcel.obtain();
        try {
            compatItem.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return MediaBrowser.MediaItem.CREATOR.createFromParcel(parcel);
        } catch (RuntimeException ex) {
            return null; // Not a media item.
        } finally {
            parcel.recycle();
        }
    }

    @Nullable
    private static Parcelable toCompatMediaItem(Entry entry) {
        Parcelable.Creator<?> creator = compatMediaItemCreator;
        if (creator == null) {
            try {
                creator = getCreator(Class.forName(COMPAT_MEDIA_ITEM_CLASS));
                compatMediaItemCreator = creator;
            } catch (ClassNotFoundException ex) {
                Logger.printException(() -> "MediaBrowserCompat.MediaItem not found", ex);
                return null;
            }
        }
        if (creator == null) {
            return null;
        }

        Bundle extras = new Bundle();
        extras.putInt(CONTENT_STYLE_SINGLE_ITEM_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE);
        if (entry.browsable) {
            // Show the tracks of a playlist as a list.
            extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE);
            extras.putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST_ITEM_HINT_VALUE);
        }
        MediaDescription description = new MediaDescription.Builder()
                .setMediaId(entry.mediaId)
                .setTitle(entry.title)
                .setSubtitle(entry.subtitle)
                .setIconUri(entry.artworkUrl == null ? null : Uri.parse(entry.artworkUrl))
                .setExtras(extras)
                .build();
        MediaBrowser.MediaItem mediaItem = new MediaBrowser.MediaItem(description,
                entry.browsable ? MediaBrowser.MediaItem.FLAG_BROWSABLE : MediaBrowser.MediaItem.FLAG_PLAYABLE);

        Parcel parcel = Parcel.obtain();
        try {
            mediaItem.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return (Parcelable) creator.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Nullable
    private static Parcelable.Creator<?> getCreator(Class<?> parcelableClass) {
        try {
            return (Parcelable.Creator<?>) parcelableClass.getField("CREATOR").get(null);
        } catch (ReflectiveOperationException | ClassCastException ex) {
            Logger.printException(() -> "No CREATOR in " + parcelableClass.getName(), ex);
            return null;
        }
    }

    private static final class BadRequestException extends IOException {
        BadRequestException(String message) {
            super(message);
        }
    }
}
