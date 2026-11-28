/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailEntry;

/** Uses the saved page's file artwork for the native player and queue. Worker only. */
final class OfflinePlaybackArtwork {
    private static final OfflinePlaylistArtwork.TrackCovers covers = new OfflinePlaylistArtwork.TrackCovers();

    private OfflinePlaybackArtwork() {}

    @Nullable
    static String uri(String videoId) {
        try {
            String uri = covers.uri(videoId);
            if (uri == null) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(Uri.parse(uri).getPath(), bounds);
            return bounds.outWidth > 0 && bounds.outWidth <= 4096 && bounds.outHeight > 0 && bounds.outHeight <= 4096
                    ? uri : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Prepared on the worker, then applied only after the native queue verifies the retained occurrence. */
    record Retained(PlayerFallback.QueueRow row, Object expected, Object updated) {
        void apply() {
            row.rvxApplyArtworkRenderer(expected, updated);
        }
    }

    @Nullable
    static Retained retained(Object occurrence, @Nullable String artwork) {
        if (artwork == null || !(occurrence instanceof PlayerFallback.QueueRow row)
                || !(row.rvxQueueRenderer() instanceof MessageLite expected)) return null;

        try {
            Object updated = row.rvxParseArtworkRenderer(OfflineWatchNext.withArtwork(expected.toByteArray(), artwork));
            return updated == null ? null : new Retained(row, expected, updated);
        } catch (Exception | LinkageError ignored) {
            return null;
        }
    }

    static PlayerResponse player(PlayerResponse response, String songId) {
        return playerWithUri(response, uri(songId));
    }

    static PlayerResponse playerWithUri(PlayerResponse response, @Nullable String uri) {
        if (uri == null || !response.hasVideoDetails()) return response;

        var details = response.getVideoDetails();
        var thumbnails = details.getThumbnail().toBuilder();
        if (thumbnails.getThumbnailsCount() == 0) {
            thumbnails.addThumbnails(ThumbnailEntry.newBuilder().setUrl(uri));
        } else {
            for (int index = 0; index < thumbnails.getThumbnailsCount(); index++) {
                ThumbnailEntry thumbnail = thumbnails.getThumbnails(index);
                if (!uri.equals(thumbnail.getUrl())) {
                    thumbnails.setThumbnails(index, thumbnail.toBuilder().setUrl(uri));
                }
            }
        }
        return response.toBuilder().setVideoDetails(details.toBuilder().setThumbnail(thumbnails)).build();
    }

    static List<OfflineWatchNext.Item> items(List<OfflineWatchNext.Item> original) {
        List<OfflineWatchNext.Item> localized = null;
        Map<String, String> byId = new HashMap<>();
        for (int index = 0; index < original.size(); index++) {
            OfflineWatchNext.Item item = original.get(index);
            String id = item.videoId();
            if (!byId.containsKey(id)) byId.put(id, uri(id));
            String uri = byId.get(id);
            if (uri == null || uri.equals(item.thumbnailUrl())) continue;

            if (localized == null) localized = new ArrayList<>(original);
            localized.set(index, new OfflineWatchNext.Item(id, item.title(), item.artist(), item.album(),
                    item.durationMs(), uri, item.musicVideoType()));
        }
        return localized == null ? original : localized;
    }
}
