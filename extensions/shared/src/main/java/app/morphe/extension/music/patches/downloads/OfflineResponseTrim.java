/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayabilityStatus;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Status;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

/**
 * Cuts a player response down to what an offline replay needs: the song's identity, the one stored audio format
 * (its url is empty: the replay puts its own local token there), its expiry, the loudness configuration and the
 * playback tracking. Everything else a response carries (streaming urls, SABR and ustreamer settings, other
 * formats, captions, ads, storyboards) is left out. Pure, so the host tests pin what is kept.
 */
final class OfflineResponseTrim {
    /**
     * What stock sends for a streaming response. Used only for a response built at download time, which has no
     * expiry of its own; the replay swaps the url for a local one, so the value merely satisfies the app's parser.
     */
    static final long DEFAULT_EXPIRES_SECONDS = 21_540;

    /** The playback tracking field of a player response; the generated message does not declare it. */
    private static final int PLAYBACK_TRACKING_FIELD = 9;
    private static final int PLAYBACK_TRACKING_TAG = (PLAYBACK_TRACKING_FIELD << 3) | 2;

    private OfflineResponseTrim() {}

    /**
     * The trimmed form of an online response, or null when it has no adaptive format with {@code itag}.
     * Unknown fields inside the kept messages survive, so the app's own parser still finds what it needs.
     */
    @Nullable
    static byte[] trim(byte[] responseBytes, int itag) throws IOException {
        PlayerResponse response = PlayerResponse.parseFrom(responseBytes);
        Format audio = null;
        for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
            if (format.getItag() == itag) {
                audio = format;
                break;
            }
        }
        if (audio == null) return null;

        StreamingData streaming = response.getStreamingData();
        long expires = streaming.hasExpiresInSeconds() ? streaming.getExpiresInSeconds() : DEFAULT_EXPIRES_SECONDS;
        PlayerConfig config = response.getPlayerConfig();
        PlayerConfig.Builder kept = PlayerConfig.newBuilder();
        if (config.hasAudioConfig()) kept.setAudioConfig(config.getAudioConfig());

        byte[] trimmed = assemble(response.getPlayabilityStatus(), response.getVideoDetails(), audio, expires, kept)
                .toByteArray();
        byte[] tracking = playbackTracking(responseBytes);
        if (tracking.length == 0) return trimmed;

        // Concatenated encodings merge on parse, so the tracking rides along without being declared.
        byte[] joined = new byte[trimmed.length + tracking.length];
        System.arraycopy(trimmed, 0, joined, 0, trimmed.length);
        System.arraycopy(tracking, 0, joined, trimmed.length, tracking.length);
        return joined;
    }

    /**
     * A minimal valid response from what a download resolved: the details of the song and the downloaded format.
     * It has no loudness and no tracking; an online response replaces it once the copy has played online.
     */
    static byte[] fromDownload(Format downloaded, VideoDetails details) {
        return assemble(PlayabilityStatus.getDefaultInstance(), details, downloaded, DEFAULT_EXPIRES_SECONDS,
                PlayerConfig.newBuilder()).toByteArray();
    }

    private static PlayerResponse assemble(PlayabilityStatus playability, VideoDetails details, Format audio,
                                           long expiresInSeconds, PlayerConfig.Builder config) {
        Format local = audio.toBuilder().clearUrl().clearSignatureCipher().build();
        PlayerResponse.Builder response = PlayerResponse.newBuilder()
                .setPlayabilityStatus(playability.toBuilder().setStatus(Status.OK).clearReason())
                .setVideoDetails(details)
                .setStreamingData(StreamingData.newBuilder()
                        .setExpiresInSeconds(expiresInSeconds)
                        .addAdaptiveFormats(local));
        PlayerConfig built = config.build();
        if (built.getSerializedSize() > 0) response.setPlayerConfig(built);
        return response.build();
    }

    /** The encoded playback tracking fields of a response, or an empty array when it has none. */
    private static byte[] playbackTracking(byte[] responseBytes) throws IOException {
        CodedInputStream input = CodedInputStream.newInstance(responseBytes);
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(kept);
        for (int tag = input.readTag(); tag != 0; tag = input.readTag()) {
            if (tag == PLAYBACK_TRACKING_TAG) {
                output.writeBytes(PLAYBACK_TRACKING_FIELD, input.readBytes());
            } else if (!input.skipField(tag)) {
                break;
            }
        }
        output.flush();
        return kept.toByteArray();
    }
}
