/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;

/**
 * The protos {@link NativeMirror} writes into the app's offline database. Field numbers are read off YouTube Music
 * 9.45.51's message info strings: the offline playlist data ({@code Laomv}) is 1 playlist id, 2 thumbnails, 5 title,
 * 7 saved time in seconds; the thumbnails ({@code Laqts}) are a repeated 1, each ({@code Laqtr}) 1 url, 2 width,
 * 3 height. The stock reader ({@code OfflinePlaylistCursorReader}) takes the playlist's id and title from this proto,
 * not from the table's columns.
 */
final class NativeMirrorProto {
    private static final int PLAYLIST_ID = 1;
    private static final int PLAYLIST_THUMBNAILS = 2;
    private static final int PLAYLIST_TITLE = 5;
    private static final int PLAYLIST_SAVED_SECONDS = 7;
    private static final int THUMBNAILS_THUMBNAIL = 1;
    private static final int THUMBNAIL_URL = 1;

    private NativeMirrorProto() {}

    /** The native cache key reads the flat audio-track field, while a downloaded format may carry the nested ID. */
    static Format indexFormat(Format stored, boolean audio) {
        if (!audio || !stored.getNativeAudioTrackId().isEmpty() || !stored.getAudioTrack().hasId()) return stored;
        return stored.toBuilder().setNativeAudioTrackId(stored.getAudioTrack().getId()).build();
    }

    /** Project metadata and exact formats only; opaque fields (including offline policy) are not copied. */
    static PlayerResponse playerResponse(String id, PlayerResponse source, StreamingData.Builder streams) {
        if (source.getStreamingData().hasExpiresInSeconds()) {
            streams.setExpiresInSeconds(source.getStreamingData().getExpiresInSeconds());
        }
        PlayerResponse.Builder response = PlayerResponse.newBuilder()
                .setVideoDetails(source.getVideoDetails().toBuilder().setVideoId(id))
                .setStreamingData(streams);
        if (source.hasPlayabilityStatus()) response.setPlayabilityStatus(source.getPlayabilityStatus());
        if (source.hasPlayerConfig()) response.setPlayerConfig(source.getPlayerConfig());
        return response.build();
    }

    /** The {@code offline_playlist_data_proto} of a playlist; an empty thumbnail url writes no thumbnail. */
    static byte[] playlistData(String playlistId, String title, String thumbnailUrl, long savedSeconds)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(PLAYLIST_ID, playlistId);
        if (!thumbnailUrl.isEmpty()) output.writeByteArray(PLAYLIST_THUMBNAILS, thumbnails(thumbnailUrl));
        output.writeString(PLAYLIST_TITLE, title);
        output.writeInt64(PLAYLIST_SAVED_SECONDS, savedSeconds);
        output.flush();
        return bytes.toByteArray();
    }

    private static byte[] thumbnails(String url) throws IOException {
        ByteArrayOutputStream thumbnail = new ByteArrayOutputStream();
        CodedOutputStream thumbnailOutput = CodedOutputStream.newInstance(thumbnail);
        thumbnailOutput.writeString(THUMBNAIL_URL, url);
        thumbnailOutput.flush();

        ByteArrayOutputStream list = new ByteArrayOutputStream();
        CodedOutputStream listOutput = CodedOutputStream.newInstance(list);
        listOutput.writeByteArray(THUMBNAILS_THUMBNAIL, thumbnail.toByteArray());
        listOutput.flush();
        return list.toByteArray();
    }

}
