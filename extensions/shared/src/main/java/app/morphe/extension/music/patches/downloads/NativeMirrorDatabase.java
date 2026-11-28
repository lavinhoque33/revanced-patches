/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import app.morphe.extension.music.patches.downloads.NativeMirrorRules.Action;

/** Markers are private to each offline DB and commit in the same transaction as the stock rows. */
final class NativeMirrorDatabase {
    static final String VIDEOS = "videosV2";
    static final String STREAMS = "streams";
    static final String PLAYLISTS = "playlistsV13";
    static final String LINKS = "playlist_video";
    private static final String CLAIMS = "rvx_mirror_claims_v1";
    private static final String OWNED = "rvx_mirror_rows_v1";
    private static final String MEMBERS = "rvx_mirror_links_v1";
    private static final String BOOTSTRAPPED = "";
    private static final String LINK_MATCH = "rvx.link = playlist_video.rowid AND rvx.video = playlist_video.video_id"
            + " AND rvx.position = playlist_video.index_in_playlist AND rvx.saved = playlist_video.saved_timestamp"
            + " AND CASE rvx.kind WHEN 1 THEN playlist_video.playlist_id = rvx.id ELSE"
            + " playlist_video.playlist_id IS NULL AND playlist_video.video_id = rvx.id END";

    record Item(String id, boolean playlist) {
        int kind() { return playlist ? 1 : 0; }
    }
    record Route(NativeMirrorStreams.Stream stream, Uri uri) {}
    record Result(Set<String> videos, Set<String> playlists, Map<String, Route> streams, Set<Item> changed) {
        static final Result EMPTY = new Result(Set.of(), Set.of(), Map.of(), Set.of());
    }
    private record Marker(long rowId, long saved, byte[] payload, @Nullable byte[] streams) {}
    private record Evidence(long rowId, long saved, @Nullable byte[] payload) {}
    private record Link(String video, int position) {}
    private record Formats(List<byte[]> bytes, byte[] fingerprint) {}

    private NativeMirrorDatabase() {}

    static boolean supports(SQLiteDatabase db) {
        return !db.isReadOnly() && NativeMirrorRules.supportsSchema(db.getVersion());
    }

    static boolean initialized(SQLiteDatabase db) {
        return tableExists(db, CLAIMS) && has(db, "SELECT 1 FROM " + CLAIMS + " WHERE id = ?", BOOTSTRAPPED);
    }

    static Set<Item> claims(SQLiteDatabase db, List<Item> added) {
        Set<Item> claims = new HashSet<>(added);
        if (!tableExists(db, CLAIMS)) return claims;
        try (Cursor c = db.rawQuery("SELECT id, kind FROM " + CLAIMS + " WHERE id <> ''", null)) {
            while (c.moveToNext()) claims.add(new Item(c.getString(0), c.getInt(1) == 1));
        }
        return claims;
    }

    @Nullable
    static Result sync(SQLiteDatabase db, NativeMirrorMedia.Plan plan, List<Item> added, boolean enabled,
                       boolean adopt, BooleanSupplier stillCurrent) throws IOException {
        if (!enabled && !tableExists(db, OWNED)) return Result.EMPTY;
        Set<Item> changed = new HashSet<>();
        Set<String> videos = new HashSet<>();
        Set<String> playlists = new HashSet<>();
        Map<String, Route> streams = new HashMap<>();
        Set<Item> explicit = new HashSet<>(added);
        long now = System.currentTimeMillis();
        db.beginTransaction();
        try {
            createTables(db);
            Map<Item, Marker> markers = readMarkers(db);
            for (Item item : explicit) saveClaim(db, item);
            if (adopt) saveClaim(db, new Item(BOOTSTRAPPED, false));

            // Remove stale associations before making a song loose, or retiring an unreferenced video row.
            for (Item item : markers.keySet()) {
                if (item.playlist() ? !plan.playlists().containsKey(item.id()) : !plan.loose().contains(item.id())) {
                    if (removeLinks(db, item) != 0) changed.add(item);
                }
            }
            for (Map.Entry<String, NativeMirrorMedia.Video> entry : plan.videos().entrySet()) {
                Item item = new Item(entry.getKey(), false);
                NativeMirrorMedia.Video video = entry.getValue();
                if (syncVideo(db, item, video, markers.remove(item), explicit.contains(item), now, changed)) {
                    videos.add(video.id());
                    for (NativeMirrorMedia.Row row : video.rows()) {
                        NativeMirrorStreams.Stream stream = row.stream();
                        streams.put(stream.key(video.id()), new Route(stream, Uri.fromFile(stream.file())));
                    }
                }
            }
            for (NativeMirrorMedia.Playlist playlist : plan.playlists().values()) {
                Item item = new Item(playlist.source().id(), true);
                List<String> safeTracks = new ArrayList<>(playlist.tracks().size());
                for (String id : playlist.tracks()) if (videos.contains(id)) safeTracks.add(id);
                if (syncPlaylist(db, item, playlist.source(), safeTracks, markers.remove(item),
                        explicit.contains(item), now, changed)) playlists.add(item.id());
            }
            for (String id : plan.loose()) {
                if (!videos.contains(id)) {
                    if (removeLinks(db, new Item(id, false)) != 0) changed.add(new Item(id, false));
                    continue;
                }
                Item item = new Item(id, false);
                if (readLinks(db, item).isEmpty()) {
                    writeLink(db, item, id, 0, now);
                    changed.add(item);
                }
            }
            for (Map.Entry<Item, Marker> entry : markers.entrySet()) {
                Item item = entry.getKey();
                if (item.playlist()) syncPlaylist(db, item, null, List.of(), entry.getValue(), false, now, changed);
                else syncVideo(db, item, null, entry.getValue(), false, now, changed);
            }
            if (enabled) {
                for (Item item : claims(db, List.of())) {
                    if (!plan.retained().contains(item)) {
                        removeClaim(db, item);
                        // An orphan marker still protects surviving stock-owned rows. Clear it only if no row remains.
                        if (evidence(db, item) == null
                                && (item.playlist() || readStreamFingerprint(db, item.id()) == null)) {
                            removeMarker(db, item);
                        }
                    }
                }
            }
            if (!stillCurrent.getAsBoolean()) return null;
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return new Result(Set.copyOf(videos), Set.copyOf(playlists), Map.copyOf(streams), Set.copyOf(changed));
    }

    private static boolean syncVideo(SQLiteDatabase db, Item item, @Nullable NativeMirrorMedia.Video wanted,
                                     @Nullable Marker marker, boolean explicit, long now, Set<Item> changed) {
        String id = item.id();
        Evidence row = evidence(db, item);
        byte[] currentStreams = row != null && marker == null ? null : readStreamFingerprint(db, id);
        boolean owned = owns(marker, row) && Arrays.equals(marker.streams(), currentStreams);
        byte[] bytes = wanted == null ? null : wanted.response().toByteArray();
        byte[] signature = bytes == null ? null : hash(bytes);
        Formats formats = wanted == null ? null : formats(wanted.rows());
        boolean update = owned && wanted != null && (!Arrays.equals(signature, marker.payload())
                || !Arrays.equals(formats.fingerprint(), currentStreams));
        boolean stockChildren = hasStockLinks(db, item);
        Action action = NativeMirrorRules.action(wanted != null, row != null, marker != null, owned,
                stockChildren || row == null && currentStreams != null, update, explicit);
        switch (action) {
            case INSERT, UPDATE -> {
                if (row == null) {
                    // The stock store merges every videosV2 row at startup. A fork-only row has no stock
                    // offline proto and aborts that merge, so only the stock writer may create video rows.
                    return false;
                }
                long saved = owned ? row.saved() : now;
                ContentValues values = new ContentValues();
                values.put("id", id);
                values.put("deleted", 0);
                values.put("channel_id", wanted.response().getVideoDetails().getChannelId());
                values.put("saved_timestamp", saved);
                values.put("media_status", 0);
                values.put("preferred_stream_quality", 0);
                values.put("player_response_proto", bytes);
                values.put("metadata_timestamp", now);
                values.put("streams_timestamp", now);
                values.put("video_added_timestamp", saved);
                String track = "";
                for (NativeMirrorMedia.Row format : wanted.rows()) {
                    if (format.stream().audio()) track = format.stream().audioTrack();
                }
                if (track.isEmpty()) values.putNull("audio_track_id");
                else values.put("audio_track_id", track);
                long rowId = upsert(db, VIDEOS, id, values, owned ? row : null);
                if (!Arrays.equals(formats.fingerprint(), currentStreams)) {
                    if (owned) db.delete(STREAMS, "video_id = ?", args(id));
                    for (int i = 0; i < wanted.rows().size(); i++) {
                        writeStream(db, id, wanted.rows().get(i), formats.bytes().get(i));
                    }
                }
                mark(db, item, rowId, saved, signature, formats.fingerprint());
                changed.add(item);
                return true;
            }
            case REMOVE -> {
                if (!stockChildren) {
                    db.delete(STREAMS, "video_id = ?", args(id));
                    db.delete(VIDEOS, "id = ?", args(id));
                    removeMarker(db, item);
                    changed.add(item);
                }
            }
            case KEEP -> { return true; }
            case LEAVE_STOCK -> {
                if (row == null && marker != null && currentStreams != null && !stockChildren
                        && Arrays.equals(marker.streams(), currentStreams)) {
                    db.delete(STREAMS, "video_id = ?", args(id));
                    changed.add(item);
                }
            }
        }
        return false;
    }

    private static boolean syncPlaylist(SQLiteDatabase db, Item item, @Nullable OfflineSourceStore.Source source,
                                        List<String> tracks, @Nullable Marker marker, boolean explicit, long now,
                                        Set<Item> changed) throws IOException {
        boolean wanted = source != null && !tracks.isEmpty();
        Evidence row = evidence(db, item);
        boolean owned = owns(marker, row);
        boolean stockChildren = hasStockLinks(db, item);
        if (stockChildren) return false;
        long saved = owned ? row.saved() : now;
        byte[] proto = !wanted ? null : NativeMirrorProto.playlistData(item.id(), source.title(),
                source.thumbnailUrl(), TimeUnit.MILLISECONDS.toSeconds(saved));
        byte[] signature = proto == null ? null : hash(proto);
        List<Link> previous = owned ? readLinks(db, item) : List.of();
        boolean linksChanged = !sameTracks(previous, tracks);
        boolean updated = owned && (!Arrays.equals(signature, marker.payload()) || linksChanged);
        switch (NativeMirrorRules.action(wanted, row != null, marker != null, owned, false, updated, explicit)) {
            case INSERT, UPDATE -> {
                ContentValues values = new ContentValues();
                values.put("id", item.id());
                values.put("offline_playlist_data_proto", proto);
                values.put("placeholder", 0);
                values.put("size", tracks.size());
                values.put("saved_timestamp", saved);
                values.put("preferred_stream_quality", 0);
                values.put("offline_source_ve_type", -1);
                values.put("playlist_added_timestamp_millis", saved);
                long rowId = upsert(db, PLAYLISTS, item.id(), values, owned ? row : null);
                if (linksChanged) {
                    removeLinks(db, item);
                    for (int i = 0; i < tracks.size(); i++) writeLink(db, item, tracks.get(i), i, saved);
                }
                mark(db, item, rowId, saved, signature, null);
                changed.add(item);
                return true;
            }
            case REMOVE -> {
                removeLinks(db, item);
                db.delete(PLAYLISTS, "id = ?", args(item.id()));
                removeMarker(db, item);
                changed.add(item);
            }
            case KEEP -> { return true; }
            case LEAVE_STOCK -> {
                if (row == null && removeLinks(db, item) != 0) changed.add(item);
            }
        }
        return false;
    }

    private static boolean sameTracks(List<Link> previous, List<String> tracks) {
        if (previous.size() != tracks.size()) return false;
        for (int i = 0; i < tracks.size(); i++) {
            Link link = previous.get(i);
            if (link.position() != i || !tracks.get(i).equals(link.video())) return false;
        }
        return true;
    }

    private static boolean owns(@Nullable Marker marker, @Nullable Evidence row) {
        return marker != null && row != null && row.payload() != null
                && marker.rowId() == row.rowId()
                && NativeMirrorRules.same(row.saved(), row.payload(), marker.saved(), marker.payload());
    }

    @Nullable
    private static Evidence evidence(SQLiteDatabase db, Item item) {
        String table = item.playlist() ? PLAYLISTS : VIDEOS;
        String value = item.playlist() ? "CASE WHEN placeholder = 0 AND offline_source_ve_type = -1"
                + " AND player_response_tracking_params IS NULL THEN offline_playlist_data_proto END"
                : "CASE WHEN deleted = 0 AND media_status = 0 AND refresh_token IS NULL"
                + " AND offline_video_data_proto IS NULL THEN player_response_proto END";
        try (Cursor c = db.rawQuery("SELECT rowid, saved_timestamp, " + value + " FROM " + table + " WHERE id = ?",
                args(item.id()))) {
            return c.moveToFirst() ? new Evidence(c.getLong(0), c.getLong(1), c.isNull(2) ? null : hash(c.getBlob(2)))
                    : null;
        }
    }

    /** Storage, encryption and expiry are stock state; a native writer changing any of them ends our ownership. */
    @Nullable
    private static byte[] readStreamFingerprint(SQLiteDatabase db, String id) {
        try (Cursor c = db.rawQuery("SELECT format_stream_proto, bytes_total, bytes_transferred, audio_only, itag,"
                + " storage_id IS NULL AND encryption_key_type = 0 AND expired_stream = 0"
                + " AND external_yt_file_path IS NULL AND wrapped_key IS NULL FROM " + STREAMS
                + " WHERE video_id = ? ORDER BY itag", args(id))) {
            if (!c.moveToFirst()) return null;
            MessageDigest digest = digest();
            do {
                if (c.isNull(0) || c.getLong(1) != c.getLong(2) || c.getInt(5) != 1) return new byte[0];
                addFormat(digest, c.getInt(4), c.getLong(1), c.getInt(3), c.getBlob(0));
            } while (c.moveToNext());
            return digest.digest();
        }
    }

    private static Formats formats(List<NativeMirrorMedia.Row> rows) {
        MessageDigest digest = digest();
        List<byte[]> blobs = new ArrayList<>(rows.size());
        for (NativeMirrorMedia.Row row : rows) blobs.add(row.format().toByteArray());
        int first = rows.size() == 2 && rows.get(1).stream().itag() < rows.get(0).stream().itag() ? 1 : 0;
        for (int i = 0; i < rows.size(); i++) {
            int index = (first + i) % rows.size();
            NativeMirrorStreams.Stream stream = rows.get(index).stream();
            addFormat(digest, stream.itag(), stream.length(), stream.audio() ? 1 : 0, blobs.get(index));
        }
        return new Formats(blobs, digest.digest());
    }

    private static void addFormat(MessageDigest digest, int itag, long length, int audio, byte[] proto) {
        digest.update((byte) audio);
        for (int i = 0; i < Integer.BYTES; i++) digest.update((byte) (itag >>> (i * 8)));
        for (int i = 0; i < Long.BYTES; i++) digest.update((byte) (length >>> (i * 8)));
        digest.update(hash(proto));
    }

    private static void writeStream(SQLiteDatabase db, String id, NativeMirrorMedia.Row row, byte[] blob) {
        NativeMirrorStreams.Stream stream = row.stream();
        ContentValues values = new ContentValues();
        values.put("video_id", id);
        values.put("itag", stream.itag());
        values.put("format_stream_proto", blob);
        values.put("duration_millis", row.durationMs());
        values.put("audio_only", stream.audio() ? 1 : 0);
        values.put("bytes_total", stream.length());
        values.put("bytes_transferred", stream.length());
        db.insertOrThrow(STREAMS, null, values);
    }

    private static boolean hasStockLinks(SQLiteDatabase db, Item owner) {
        String condition = owner.playlist() ? "playlist_id = ?" : "video_id = ?";
        return has(db, "SELECT 1 FROM " + LINKS + " WHERE " + condition + " AND NOT EXISTS (SELECT 1 FROM "
                + MEMBERS + " rvx WHERE " + LINK_MATCH + ") LIMIT 1", owner.id());
    }

    private static List<Link> readLinks(SQLiteDatabase db, Item owner) {
        String where = owner.playlist() ? "playlist_id = ?" : "playlist_id IS NULL AND video_id = ?";
        List<Link> links = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT video_id, index_in_playlist FROM " + LINKS + " WHERE " + where
                + " ORDER BY index_in_playlist, rowid", args(owner.id()))) {
            while (c.moveToNext()) links.add(new Link(c.getString(0), c.getInt(1)));
        }
        return links;
    }

    private static int removeLinks(SQLiteDatabase db, Item item) {
        String[] key = args(item.id(), Integer.toString(item.kind()));
        int count = db.delete(LINKS, "EXISTS (SELECT 1 FROM " + MEMBERS + " rvx WHERE rvx.id = ? AND rvx.kind = ?"
                + " AND " + LINK_MATCH + ")", key);
        db.delete(MEMBERS, "id = ? AND kind = ?", key);
        return count;
    }

    private static void writeLink(SQLiteDatabase db, Item item, String id, int index, long saved) {
        ContentValues values = new ContentValues();
        if (item.playlist()) values.put("playlist_id", item.id());
        values.put("video_id", id);
        values.put("index_in_playlist", index);
        values.put("saved_timestamp", saved);
        long rowId = db.insertOrThrow(LINKS, null, values);
        db.execSQL("INSERT OR REPLACE INTO " + MEMBERS + " (id, kind, link, video, position, saved) VALUES (?,?,?,?,?,?)",
                new Object[]{item.id(), item.kind(), rowId, id, index, saved});
    }

    private static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + CLAIMS + " (id TEXT NOT NULL, kind INTEGER NOT NULL,"
                + " PRIMARY KEY(id,kind))");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + OWNED + " (id TEXT NOT NULL, kind INTEGER NOT NULL,"
                + " row_id INTEGER NOT NULL, saved INTEGER NOT NULL, payload BLOB NOT NULL, streams BLOB,"
                + " PRIMARY KEY(id,kind))");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + MEMBERS + " (id TEXT NOT NULL, kind INTEGER NOT NULL,"
                + " link INTEGER PRIMARY KEY, video TEXT NOT NULL, position INTEGER NOT NULL, saved INTEGER NOT NULL)");
    }

    private static Map<Item, Marker> readMarkers(SQLiteDatabase db) {
        Map<Item, Marker> result = new HashMap<>();
        try (Cursor c = db.rawQuery("SELECT id, kind, row_id, saved, payload, streams FROM " + OWNED, null)) {
            while (c.moveToNext()) {
                result.put(new Item(c.getString(0), c.getInt(1) == 1),
                        new Marker(c.getLong(2), c.getLong(3), c.getBlob(4), c.getBlob(5)));
            }
        }
        return result;
    }

    private static void mark(SQLiteDatabase db, Item item, long rowId, long saved, byte[] payload,
                             @Nullable byte[] streams) {
        db.execSQL("INSERT OR REPLACE INTO " + OWNED + " (id,kind,row_id,saved,payload,streams) VALUES (?,?,?,?,?,?)",
                new Object[]{item.id(), item.kind(), rowId, saved, payload, streams});
    }

    private static void saveClaim(SQLiteDatabase db, Item item) {
        db.execSQL("INSERT OR IGNORE INTO " + CLAIMS + " (id,kind) VALUES (?,?)",
                new Object[]{item.id(), item.kind()});
    }

    private static void removeClaim(SQLiteDatabase db, Item item) {
        db.delete(CLAIMS, "id = ? AND kind = ?", args(item.id(), Integer.toString(item.kind())));
    }

    private static void removeMarker(SQLiteDatabase db, Item item) {
        db.delete(OWNED, "id = ? AND kind = ?", args(item.id(), Integer.toString(item.kind())));
    }

    private static long upsert(SQLiteDatabase db, String table, String id, ContentValues values,
                               @Nullable Evidence existing) {
        if (existing == null) return db.insertOrThrow(table, null, values);
        db.update(table, values, "id = ?", args(id));
        return existing.rowId();
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        return has(db, "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", name);
    }

    private static boolean has(SQLiteDatabase db, String sql, String argument) {
        try (Cursor cursor = db.rawQuery(sql, args(argument))) { return cursor.moveToFirst(); }
    }

    private static String[] args(String... args) { return args; }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] hash(byte[] bytes) { return digest().digest(bytes); }
}
