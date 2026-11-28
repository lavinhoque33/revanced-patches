/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.patches.downloads.NativeMirrorDatabase.Item;
import app.morphe.extension.music.patches.downloads.NativeMirrorDatabase.Result;
import app.morphe.extension.music.patches.downloads.NativeMirrorMedia.Plan;
import app.morphe.extension.music.patches.downloads.NativeMirrorMedia.Playlist;
import app.morphe.extension.music.patches.downloads.NativeMirrorMedia.Row;
import app.morphe.extension.music.patches.downloads.NativeMirrorMedia.Video;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class NativeMirrorDatabaseTest {
    private static final Item SONG = new Item("song", false);
    private static final Item PLAYLIST = new Item("playlist", true);
    private static final Video ATV = video("song", row(251, 30, true));
    private static final Video OMV = video("musicVideo", row(134, 20, false), row(251, 40, true));

    @Test public void mirrorsRowsThenUpdatesMembershipAndRemovesOrphans() throws Exception {
        try (SQLiteDatabase db = database()) {
            Plan initial = plan("Order A", List.of(ATV.id(), OMV.id()), false);
            Result first = sync(db, initial, List.of(PLAYLIST), true);
            assertTrue(NativeMirrorDatabase.initialized(db));
            assertEquals(Set.of(ATV.id(), OMV.id()), first.videos());
            assertEquals(Set.of(PLAYLIST.id()), first.playlists());
            assertEquals(Set.of("song.251.30", "musicVideo.134.20", "musicVideo.251.40"), first.streams().keySet());
            assertEquals("song,musicVideo", playlistOrder(db));
            assertEquals(0, count(db, "SELECT count(*) FROM videosV2 WHERE refresh_token IS NOT NULL"));
            assertEquals(1, count(db, "SELECT count(*) FROM streams WHERE audio_only = 0"));
            assertTrue(sync(db, initial, List.of(), true).changed().isEmpty());

            Plan edited = plan("Order B", List.of(OMV.id(), ATV.id()), true);
            sync(db, edited, List.of(), true);
            assertEquals("musicVideo,song", playlistOrder(db));
            assertEquals(2, count(db, "SELECT count(*) FROM playlist_video"));
            assertEquals(1, count(db, "SELECT count(*) FROM streams WHERE video_id = 'song'"));

            sync(db, looseOnly(), List.of(SONG), true);
            assertEquals(1, count(db, "SELECT count(*) FROM videosV2"));
            assertEquals(0, count(db, "SELECT count(*) FROM playlistsV13"));
            assertEquals(1, count(db, "SELECT count(*) FROM playlist_video WHERE playlist_id IS NULL"));
            sync(db, Plan.EMPTY, List.of(), true);
            assertEquals(0, count(db, "SELECT count(*) FROM streams"));
            assertEquals(0, count(db, "SELECT count(*) FROM playlist_video"));
            assertTrue(NativeMirrorDatabase.claims(db, List.of()).isEmpty());
        }
    }

    @Test public void stockTakeoverAndStockDeletionRemainAuthoritative() throws Exception {
        try (SQLiteDatabase db = database()) {
            sync(db, looseOnly(), List.of(SONG), true);
            db.execSQL("UPDATE videosV2 SET refresh_token = 'native' WHERE id = 'song'");
            assertTrue(sync(db, looseOnly(), List.of(), true).streams().isEmpty());
            assertEquals(1, count(db, "SELECT count(*) FROM videosV2 WHERE refresh_token = 'native'"));
            assertEquals(0, count(db, "SELECT count(*) FROM playlist_video"));
            db.execSQL("DELETE FROM videosV2 WHERE id = 'song'");
            sync(db, looseOnly(), List.of(), true);
            assertEquals(0, count(db, "SELECT count(*) FROM streams"));
            assertTrue(sync(db, looseOnly(), List.of(), true).streams().isEmpty());
            assertEquals(Set.of("song.251.30"), sync(db, looseOnly(), List.of(SONG), true).streams().keySet());
        }
    }

    @Test public void claimsAndOwnershipNeverCrossAccountDatabases() throws Exception {
        try (SQLiteDatabase first = database(); SQLiteDatabase second = database()) {
            sync(first, looseOnly(), List.of(SONG), true);
            assertEquals(Set.of(SONG), NativeMirrorDatabase.claims(first, List.of()));
            assertTrue(NativeMirrorDatabase.claims(second, List.of()).isEmpty());
            second.execSQL("INSERT INTO videosV2 (id, refresh_token) VALUES ('song','native')");
            assertTrue(sync(second, looseOnly(), List.of(SONG), true).streams().isEmpty());
            assertEquals(1, count(second, "SELECT count(*) FROM videosV2 WHERE refresh_token = 'native'"));

            sync(first, Plan.EMPTY, List.of(), false);
            assertEquals(0, count(first, "SELECT count(*) FROM videosV2"));
            assertEquals(Set.of(SONG), NativeMirrorDatabase.claims(first, List.of()));
            assertEquals(Set.of("song.251.30"), sync(first, looseOnly(), List.of(), true).streams().keySet());
        }
    }

    @Test public void accountSwitchBeforeCommitRollsBackClaimsAndRows() throws Exception {
        try (SQLiteDatabase db = database()) {
            assertNull(NativeMirrorDatabase.sync(db, looseOnly(), List.of(SONG), true, true, () -> false));
            assertEquals(0, count(db, "SELECT count(*) FROM videosV2"));
            assertTrue(NativeMirrorDatabase.claims(db, List.of()).isEmpty());
        }
    }

    @Test public void onlyTheIdentifiedStockSchemaIsSupported() {
        try (SQLiteDatabase db = database()) {
            assertTrue(NativeMirrorDatabase.supports(db));
            db.setVersion(37);
            assertFalse(NativeMirrorDatabase.supports(db));
            db.setVersion(39);
            assertFalse(NativeMirrorDatabase.supports(db));
        }
    }

    private static Result sync(SQLiteDatabase db, Plan plan, List<Item> claims, boolean enabled) throws Exception {
        return NativeMirrorDatabase.sync(db, plan, claims, enabled, !NativeMirrorDatabase.initialized(db), () -> true);
    }

    private static Plan looseOnly() {
        return new Plan(Map.of(ATV.id(), ATV), Map.of(), Set.of(ATV.id()), Set.of(SONG));
    }

    private static Plan plan(String name, List<String> tracks, boolean changed) {
        OfflineSourceStore.Source source = new OfflineSourceStore.Source(PLAYLIST.id(), name, "",
                "https://example.invalid/art", tracks, changed ? 2 : 1);
        return new Plan(Map.of(ATV.id(), ATV, OMV.id(), OMV),
                Map.of(PLAYLIST.id(), new Playlist(source, tracks)), Set.of(), Set.of(PLAYLIST));
    }

    private static Video video(String id, Row... rows) {
        StreamingData.Builder streams = StreamingData.newBuilder();
        for (Row row : rows) streams.addAdaptiveFormats(row.format());
        PlayerResponse response = PlayerResponse.newBuilder().setStreamingData(streams)
                .setVideoDetails(VideoDetails.newBuilder().setVideoId(id)).build();
        return new Video(id, response, List.of(rows));
    }

    private static Row row(int itag, long lmt, boolean audio) {
        Format format = Format.newBuilder().setItag(itag).setContentLength(100).setLastModified(lmt).build();
        return new Row(new NativeMirrorStreams.Stream(itag, lmt, new File("file" + lmt), 100, audio, ""), format, 50);
    }

    private static String playlistOrder(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT group_concat(video_id) FROM (SELECT video_id FROM playlist_video"
                + " WHERE playlist_id = 'playlist' ORDER BY index_in_playlist)", null)) {
            assertTrue(c.moveToFirst());
            return c.getString(0);
        }
    }

    private static int count(SQLiteDatabase db, String query) {
        try (Cursor c = db.rawQuery(query, null)) {
            assertTrue(c.moveToFirst());
            return c.getInt(0);
        }
    }

    private static SQLiteDatabase database() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        db.setVersion(38);
        db.execSQL("CREATE TABLE videosV2 (id TEXT PRIMARY KEY, offline_video_data_proto BLOB, deleted INTEGER,"
                + " channel_id TEXT, refresh_token TEXT, saved_timestamp INTEGER, media_status INTEGER,"
                + " preferred_stream_quality INTEGER, player_response_proto BLOB, metadata_timestamp INTEGER,"
                + " streams_timestamp INTEGER, video_added_timestamp INTEGER, audio_track_id TEXT)");
        db.execSQL("CREATE TABLE streams (video_id TEXT, itag INTEGER, format_stream_proto BLOB,"
                + " duration_millis INTEGER, audio_only INTEGER, bytes_total INTEGER, bytes_transferred INTEGER,"
                + " storage_id TEXT, encryption_key_type INTEGER DEFAULT 0, expired_stream INTEGER DEFAULT 0,"
                + " external_yt_file_path TEXT, wrapped_key BLOB, PRIMARY KEY (video_id, itag))");
        db.execSQL("CREATE TABLE playlistsV13 (id TEXT PRIMARY KEY, offline_playlist_data_proto BLOB,"
                + " placeholder INTEGER, offline_source_ve_type INTEGER, player_response_tracking_params BLOB,"
                + " size INTEGER, saved_timestamp INTEGER, preferred_stream_quality INTEGER,"
                + " playlist_added_timestamp_millis INTEGER)");
        db.execSQL("CREATE TABLE playlist_video (playlist_id STRING, video_id STRING, index_in_playlist INTEGER,"
                + " saved_timestamp INTEGER)");
        return db;
    }
}
