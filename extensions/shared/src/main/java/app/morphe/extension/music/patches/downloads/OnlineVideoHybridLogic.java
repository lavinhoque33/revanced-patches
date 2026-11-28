/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-28.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;

/**
 * Whether a track that plays from saved raw audio while online can also keep the online video of its own response,
 * and the response that does: the saved audio's token beside the online video-only formats, so the app's own Song/
 * Video switch flips the one clip in place. Pure rules, so they can be tested without Android.
 *
 * <p>Only directly playable formats qualify: video-only adaptive formats with an https url, no signature cipher, the
 * byte ranges the app's player builds its segments from, and the timeline of the saved audio. SABR responses (whose
 * formats carry no url), muxed formats and HLS manifests never reach the hybrid. Nothing of it claims the video is
 * stored or playable offline: the video formats are the online ones of this response, used once, by this clip.
 */
final class OnlineVideoHybridLogic {
    /** How far a video format's length may be from the saved audio's: the same cut, not another one. */
    static final long SAME_TIMELINE_TOLERANCE_MS = 1_500;

    /**
     * The answer for one response. {@code video} holds the formats the hybrid keeps, empty when it is refused, and
     * {@code reason} why it was refused, null when it is not. {@code summary} is what the log says of the response,
     * without urls or tokens.
     */
    record Verdict(List<Format> video, @Nullable String reason, String summary) {
        boolean eligible() {
            return reason == null;
        }
    }

    private OnlineVideoHybridLogic() {}

    static boolean isVideo(Format format) {
        return format.getMimeType().startsWith("video/");
    }

    private static boolean hasCipher(Format format) {
        return format.hasSignatureCipher() && !format.getSignatureCipher().isEmpty();
    }

    private static boolean hasDirectUrl(Format format) {
        return StreamSwapLogic.onlineUrl(format.getUrl()) != null && !hasCipher(format);
    }

    private static boolean hasRanges(Format format) {
        return format.hasInitRange() && format.hasIndexRange() && format.getContentLength() > 0;
    }

    private static boolean sameTimeline(Format video, long audioMs) {
        long videoMs = video.getApproxDurationMs();
        return videoMs > 0 && Math.abs(videoMs - audioMs) <= SAME_TIMELINE_TOLERANCE_MS;
    }

    /**
     * Decides whether {@code online}, the response the app asked for, keeps its video beside the saved audio.
     *
     * @param online  The effective online response of the request, after any stream spoofing.
     * @param videoId The video the request is for.
     * @param audio   The saved audio's format, its url already the local token.
     */
    static Verdict verdict(PlayerResponse online, String videoId, Format audio) {
        StreamingData streaming = online.getStreamingData();
        long lengthSeconds = online.getVideoDetails().getLengthSeconds();
        long audioMs = audio.getApproxDurationMs();

        List<Format> kept = new ArrayList<>();
        int videoFormats = 0, withUrl = 0, cipher = 0, ranged = 0, timed = 0;
        StringBuilder itags = new StringBuilder();
        for (Format format : streaming.getAdaptiveFormatsList()) {
            if (!isVideo(format)) continue;

            videoFormats++;
            if (StreamSwapLogic.onlineUrl(format.getUrl()) != null) withUrl++;
            if (hasCipher(format)) cipher++;
            if (hasRanges(format)) ranged++;
            if (sameTimeline(format, audioMs)) timed++;
            if (!hasDirectUrl(format) || !hasRanges(format) || !sameTimeline(format, audioMs)) continue;

            kept.add(format);
            itags.append(itags.length() == 0 ? "" : ",").append(format.getItag());
        }

        String summary = "videoFormats=" + videoFormats + " withUrl=" + withUrl + " cipher=" + cipher
                + " ranged=" + ranged + " sameTimeline=" + timed + " kept=" + kept.size()
                + " sabrUrl=" + streaming.hasServerAbrStreamingUrl() + " hls=" + streaming.hasHlsManifestUrl()
                + " muxed=" + streaming.getFormatsCount() + " expiresIn=" + streaming.getExpiresInSeconds()
                + " lengthSec=" + lengthSeconds + " audioMs=" + audioMs + " itags=[" + itags + "]";

        String reason = refusal(online, videoId, lengthSeconds, audioMs, videoFormats, kept.size());
        return new Verdict(reason == null ? List.copyOf(kept) : List.of(), reason, summary);
    }

    @Nullable
    private static String refusal(PlayerResponse online, String videoId, long lengthSeconds, long audioMs,
                                  int videoFormats, int kept) {
        StreamingData streaming = online.getStreamingData();
        if (!online.hasStreamingData()) return "no streaming data";
        if (!videoId.equals(online.getVideoDetails().getVideoId())) return "the response is of another video";
        if (streaming.hasExpiresInSeconds() && streaming.getExpiresInSeconds() <= 0) return "expired streams";
        if (lengthSeconds <= 0) return "no length, a live or unknown stream";
        if (audioMs <= 0) return "the saved audio has no length";
        if (!VideoCopyLogic.sameLength(lengthSeconds, audioMs)) return "the saved audio is another cut";
        if (videoFormats == 0) return "no video-only adaptive format";
        if (kept == 0) return "no directly playable video format of the same timeline";
        return null;
    }

    /**
     * The hybrid response: everything of the online one, but its adaptive formats are the kept online video formats
     * and the saved audio, its muxed formats, HLS manifest, SABR url and the request hints that force SABR gone. The
     * player then has one audio, the file, for both modes, and the video only from these urls.
     */
    static PlayerResponse hybrid(PlayerResponse online, List<Format> video, Format audio) {
        var streaming = online.getStreamingData().toBuilder().clearFormats().clearAdaptiveFormats()
                .addAllAdaptiveFormats(video).addAdaptiveFormats(audio).clearHlsManifestUrl()
                .clearServerAbrStreamingUrl();
        return StreamSwapLogic.withDirectStreams(online, streaming, true);
    }
}
