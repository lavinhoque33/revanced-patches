/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import java.net.URI;
import java.net.URISyntaxException;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;

/** Pure admission and proto transformation used by playback and the host tests. */
final class StreamSwapLogic {
    private StreamSwapLogic() {}

    static String gate(boolean downloads, boolean localPlayback, boolean mainThread) {
        if (!downloads || !localPlayback) return "setting off";
        return mainThread ? "main thread" : null;
    }

    static boolean supportedAudio(int itag, String mime) {
        return itag == 251 && "audio/webm; codecs=\"opus\"".equals(mime);
    }

    static String mismatch(RawAudioFiles.Entry raw, Format audio, PlayerResponse online) {
        if (!supportedAudio(audio.getItag(), audio.getMimeType())) return "unsupported audio";
        if (raw.itag() != audio.getItag()) return "itag differs";
        if (!raw.mime().equals(audio.getMimeType())) return "mime differs";
        if (raw.length() <= 0 || raw.length() != audio.getContentLength()) return "contentLength differs";
        if (raw.modified() <= 0 || raw.modified() != audio.getLastModified()) return "lmt differs or missing";
        if (online.getVideoDetails().getLengthSeconds() <= 0 || online.getStreamingData().hasHlsManifestUrl()) {
            return "live or segmented stream";
        }
        if (!validRange(audio.hasInitRange(), audio.getInitRange().getStart(),
                audio.getInitRange().getEnd(), raw.length())
                || !validRange(audio.hasIndexRange(), audio.getIndexRange().getStart(),
                audio.getIndexRange().getEnd(), raw.length())) return "missing or invalid ranges";
        return null;
    }

    private static boolean validRange(boolean present, long start, long end, long length) {
        return present && start >= 0 && end >= start && end < length;
    }

    /**
     * The online fallback of a swapped format, or null. SABR responses (Music 9.40.51) have an empty Format.url: their
     * routing URL is StreamingData field 15, which the swap strips. Only a complete https URL can resume playback.
     */
    static String onlineUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null ? url : null;
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    /** Stock's exact host predicate, not a general loopback-address test. */
    static boolean isGuardedHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    /** Only called after strict original-stream identity matching. */
    static PlayerResponse replaceAudio(PlayerResponse online, Format audio) {
        var streaming = online.getStreamingData().toBuilder().clearFormats().clearAdaptiveFormats()
                .addAdaptiveFormats(audio);
        streaming.clearServerAbrStreamingUrl();
        var replacement = online.toBuilder().setStreamingData(streaming);
        if (!online.hasPlayerConfig()) return replacement.build();

        var config = online.getPlayerConfig();
        var common = config.getMediaCommonConfig();
        var routing = config.getPlaybackRoutingConfig();
        boolean stripCommon = common.hasMediaUstreamerRequestConfig() || common.hasEnableSabr();
        boolean stripForced = routing.getPreferredEngine() == 3;
        if (!stripCommon && !stripForced) return replacement.build();

        var player = config.toBuilder();
        if (stripCommon) {
            // Include opaque request hints; keep every unrelated field in the surrounding configuration.
            var withoutUstreamer = common.toBuilder().clearMediaUstreamerRequestConfig().clearEnableSabr().build();
            if (withoutUstreamer.getSerializedSize() == 0) {
                player.clearMediaCommonConfig();
            } else {
                player.setMediaCommonConfig(withoutUstreamer);
            }
        }
        if (stripForced) {
            var withoutForcedMode = routing.toBuilder().clearPreferredEngine().build();
            if (withoutForcedMode.getSerializedSize() == 0) {
                player.clearPlaybackRoutingConfig();
            } else {
                player.setPlaybackRoutingConfig(withoutForcedMode);
            }
        }
        return replacement.setPlayerConfig(player).build();
    }

}
