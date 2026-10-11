/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-10.
 */

package app.morphe.extension.youtube.patches.general.offline;

import static app.morphe.extension.youtube.patches.spoof.SpoofVideoStreamsPatch.AVAILABLE_CLIENTS;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.Nullable;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailEntry;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest;
import app.morphe.extension.shared.utils.Logger;

/**
 * Resolves the downloadable MP4 streams of a video, the same way the voice-over-translation
 * audio downloader does: one player request per spoof client, until a client answers with
 * plain stream urls (SABR-only clients have none).
 */
final class OfflineFormats {
    static final String CODEC_H264 = "H.264";
    static final String CODEC_AV1 = "AV1";

    private static final String CPN_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_";
    private static final SecureRandom CPN_RANDOM = new SecureRandom();
    private static final Pattern QUALITY_LABEL = Pattern.compile("^(\\d+)p(\\d+)?");

    @Nullable
    private static volatile Boolean av1Supported;

    private OfflineFormats() {
    }

    /** One downloadable stream. */
    record Stream(String url, int itag, String mimeType, long contentLength, int height, int fps,
                  String codec, int bitrate) {
        /** Identifies the same quality on another client, whose urls and itags may differ. */
        String key() {
            return height + "p" + fps + "/" + codec;
        }
    }

    /** The streams of one video as answered by one client. */
    record Resolved(String videoId, int clientIndex, @Nullable String userAgent,
                    String title, String author, String channelId, long lengthMs,
                    @Nullable String thumbnailUrl, List<Stream> videos, Stream audio) {
    }

    static int clientCount() {
        return AVAILABLE_CLIENTS.size();
    }

    /**
     * Requests the player response from each client starting at {@code firstClient}.
     *
     * @return The first usable answer, or null if no client has MP4 video and AAC audio.
     */
    @Nullable
    static Resolved resolve(String videoId, int firstClient) {
        Map<String, String> headers = SpoofVideoStreamsPatch.currentVideoRequestHeader;
        // AuthUtils retains headers across incognito changes. Do not revive a signed-in session there.
        if ((headers == null || headers.isEmpty()) && !AuthUtils.isNotLoggedIn()) {
            headers = AuthUtils.getRequestHeader();
        }
        if (headers == null) headers = Collections.emptyMap();

        for (int index = Math.max(0, firstClient); index < AVAILABLE_CLIENTS.size(); index++) {
            ClientType client = AVAILABLE_CLIENTS.get(index);
            final int clientIndex = index;
            try {
                Logger.printInfo(() -> "Resolving " + videoId + " with client " + client.name());
                StreamOrDetailsDataRequest.StreamData stream =
                        StreamOrDetailsDataRequest.fetchDownloadStream(videoId, client, headers);
                if (stream == null) {
                    Logger.printInfo(() -> "No playable stream response from " + client.name());
                    continue;
                }
                PlayerResponse response = PlayerResponse.parseFrom(stream.streamingData());
                Resolved resolved = parse(videoId, clientIndex, client.userAgent, response);
                if (resolved != null) {
                    Logger.printInfo(() -> "Resolved " + videoId + " with client " + client.name()
                            + ": " + resolved.videos().size() + " video streams");
                    return resolved;
                }
                Logger.printInfo(() -> "Client " + client.name() + " has no MP4 video with AAC audio");
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not resolve " + videoId + " with client " + client.name(), ex);
            }
        }
        return null;
    }

    @Nullable
    private static Resolved parse(String videoId, int clientIndex, @Nullable String userAgent,
                                  PlayerResponse response) {
        final boolean av1 = isAv1Supported();
        // One stream per quality and codec. The largest file of a quality is the best encode.
        Map<String, Stream> videos = new LinkedHashMap<>();
        Stream bestAudio = null;
        boolean bestAudioIsOriginal = false;

        for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
            String url = format.getUrl();
            if (url.isEmpty()) continue;
            String mimeType = format.getMimeType().toLowerCase(Locale.US);
            long length = format.getContentLength() > 0 ? format.getContentLength() : parseClen(url);

            if (mimeType.startsWith("video/mp4")) {
                String codec;
                if (mimeType.contains("avc1")) codec = CODEC_H264;
                else if (av1 && mimeType.contains("av01")) codec = CODEC_AV1;
                else continue;

                int height = format.getHeight();
                int fps = format.getFps();
                Matcher matcher = QUALITY_LABEL.matcher(format.getQualityLabel());
                if (matcher.find()) {
                    if (height <= 0) height = Integer.parseInt(matcher.group(1));
                    if (fps <= 0 && matcher.group(2) != null) fps = Integer.parseInt(matcher.group(2));
                }
                if (height <= 0) continue;
                if (fps <= 0) fps = 30;

                Stream candidate = new Stream(addCpn(url), format.getItag(), format.getMimeType(),
                        length, height, fps, codec, format.getBitrate());
                Stream existing = videos.get(candidate.key());
                if (existing == null || candidate.bitrate() > existing.bitrate()) {
                    videos.put(candidate.key(), candidate);
                }
            } else if (mimeType.startsWith("audio/mp4") && mimeType.contains("mp4a")) {
                // Multi-language videos list dubbed tracks as well. The original is marked in the url.
                boolean original = url.contains("acont%3Doriginal") || url.contains("acont=original");
                boolean dubbed = url.contains("acont%3Ddubbed") || url.contains("acont=dubbed");
                boolean drc = url.contains("drc%3D1") || url.contains("drc=1");
                if (dubbed || drc) continue;

                Stream candidate = new Stream(addCpn(url), format.getItag(), format.getMimeType(),
                        length, 0, 0, "AAC", format.getBitrate());
                if (bestAudio == null
                        || (original && !bestAudioIsOriginal)
                        || (original == bestAudioIsOriginal && candidate.bitrate() > bestAudio.bitrate())) {
                    bestAudio = candidate;
                    bestAudioIsOriginal = original;
                }
            }
        }

        if (videos.isEmpty() || bestAudio == null) return null;

        List<Stream> sorted = new ArrayList<>(videos.values());
        sorted.sort(Comparator.comparingInt(Stream::height).reversed()
                .thenComparing(Comparator.comparingInt(Stream::fps).reversed())
                .thenComparing(Stream::codec, Comparator.reverseOrder()));

        VideoDetails details = response.getVideoDetails();
        String title = details.getTitle().isBlank() ? videoId : details.getTitle();
        long lengthMs = details.getLengthSeconds() * 1000L;

        return new Resolved(videoId, clientIndex, userAgent, title, details.getOwnerChannelName(),
                details.getChannelId(), lengthMs, largestThumbnail(details), sorted, bestAudio);
    }

    @Nullable
    private static String largestThumbnail(VideoDetails details) {
        String url = null;
        int widest = 0;
        for (ThumbnailEntry entry : details.getThumbnail().getThumbnailsList()) {
            if (entry.getWidth() >= widest && !entry.getUrl().isBlank()) {
                widest = entry.getWidth();
                url = entry.getUrl();
            }
        }
        return url;
    }

    /**
     * H.264 is always offered. AV1 needs MediaMuxer support for AV1 in MP4 (Android 14)
     * and a decoder, otherwise the saved file could not be written or played.
     */
    static boolean isAv1Supported() {
        Boolean supported = av1Supported;
        if (supported != null) return supported;
        boolean result = false;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) {
                        if ("video/av01".equalsIgnoreCase(type)) {
                            result = true;
                            break;
                        }
                    }
                    if (result) break;
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not query AV1 decoders", ex);
            }
        }
        av1Supported = result;
        return result;
    }

    private static String addCpn(String url) {
        StringBuilder cpn = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            cpn.append(CPN_ALPHABET.charAt(CPN_RANDOM.nextInt(CPN_ALPHABET.length())));
        }
        return Uri.parse(url).buildUpon().appendQueryParameter("cpn", cpn.toString()).build().toString();
    }

    private static long parseClen(String url) {
        try {
            String clen = Uri.parse(url).getQueryParameter("clen");
            return clen == null ? -1 : Long.parseLong(clen);
        } catch (Exception ex) {
            return -1;
        }
    }
}
