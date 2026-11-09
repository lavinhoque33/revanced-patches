/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;

/**
 * What the video copy of a song is and when it can be taken: the muxed 360p mp4 (itag 18) of the song's music video,
 * the only format that is one self-contained progressive file. Never an adaptive or video-only format. Contains no
 * Android code.
 */
final class VideoCopyLogic {
    static final int MUXED_ITAG = 18;
    /** Room that must stay free on the device after the copy is written. */
    static final long MIN_FREE_BYTES_AFTER = 200L * 1024 * 1024;

    private VideoCopyLogic() {}

    /** A finished video download: the format's mime and modification time, the real length, the stored response. */
    record Download(String mime, long length, long modified, long durationMs, byte[] response) {}

    static boolean supportedVideo(int itag, @Nullable String mime) {
        return itag == MUXED_ITAG && mime != null && mime.startsWith("video/mp4");
    }

    /** The muxed itag 18 of a response's {@code formats} that has a plain url, or null. */
    @Nullable
    static Format muxed(PlayerResponse response) {
        for (Format format : response.getStreamingData().getFormatsList()) {
            if (supportedVideo(format.getItag(), format.getMimeType()) && !format.getUrl().isBlank()) return format;
        }
        return null;
    }

    /** The muxed itag 18 of a stored response (its url is empty), or null. */
    @Nullable
    static Format stored(PlayerResponse response) {
        for (Format format : response.getStreamingData().getFormatsList()) {
            if (supportedVideo(format.getItag(), format.getMimeType())) return format;
        }
        return null;
    }

    /**
     * Whether a request for a music video is answered from its video copy: the player shows a video mode. Where the
     * audio-first mode is not in effect (its hooks did not resolve) nothing could be corrected later, so any request
     * is a video one. An unknown mode with the audio-first mode in effect is not answered.
     */
    static boolean answersVideo(boolean audioFirstSupported, @Nullable String mode) {
        return !audioFirstSupported || AudioFirstLogic.isVideoMode(mode);
    }

    /** The online response describes the same cut: its length is unknown, or within the pairing tolerance. */
    static boolean sameLength(long onlineLengthSeconds, long copyDurationMs) {
        if (onlineLengthSeconds <= 0 || copyDurationMs <= 0) return true;

        return Math.abs(onlineLengthSeconds - copyDurationMs / 1000) <= VideoPairingLogic.DURATION_TOLERANCE_SECONDS;
    }

    /**
     * The string of the text a finished stream-only playlist download shows. Nothing was downloaded, audio or video,
     * only when no track and no video was saved now; the text names the videos only when the list has some.
     */
    static String completionKey(int audioDownloaded, int videosSaved, int videos) {
        boolean nothingNew = audioDownloaded == 0 && videosSaved == 0;
        String prefix = nothingNew
                ? "morphe_music_playlist_stream_already_saved" : "morphe_music_playlist_stream_complete";
        return videos > 0 ? prefix + "_videos" : prefix;
    }

    static boolean hasRoomFor(long freeBytes, long length) {
        return freeBytes - length >= MIN_FREE_BYTES_AFTER;
    }

    /**
     * The format a played copy is announced with: the stored one, with the url of the local token and the file's
     * real length, and the duration of the copy when the stored format has none.
     */
    static Format local(Format stored, String token, long length, long durationMs) {
        Format.Builder local = stored.toBuilder().setUrl(token).clearSignatureCipher().setContentLength(length);
        if (local.getApproxDurationMs() <= 0 && durationMs > 0) local.setApproxDurationMs(durationMs);
        return local.build();
    }
}
