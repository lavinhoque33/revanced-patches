/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-12.
 * Modified by lavinhoque33, 2026-11-22.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loopback URLs are opaque engine-routing tokens, never HTTP endpoints; no listener or socket is needed. The raw
 * audio copy of a song and the two streams of a video copy (its video-only stream and its own audio) are announced
 * alike, one token each.
 */
final class StreamSwapSources {
    static final String KIND_RAW = "raw";
    /** The video-only stream of a video copy. */
    static final String KIND_VIDEO = "video";
    /** The audio stream of a video copy, the music video's own. */
    static final String KIND_VIDEO_AUDIO = "vaudio";

    /** What a token serves: a file of a kind (the raw audio copy or a stream of a video copy), itag and length. */
    record Served(String kind, File file, int itag, long length, String digest, long fileModified) {
        static Served of(RawAudioFiles.Entry raw) {
            return new Served(KIND_RAW, raw.file(), raw.itag(), raw.length(), raw.digest(), raw.fileModified());
        }

        static Served video(VideoCopyFiles.Entry copy) {
            VideoCopyFiles.Part part = copy.video();
            return new Served(KIND_VIDEO, part.file(), part.itag(), part.length(), part.digest(), part.fileModified());
        }

        static Served audio(VideoCopyFiles.Entry copy) {
            VideoCopyFiles.Part part = copy.audio();
            return new Served(KIND_VIDEO_AUDIO, part.file(), part.itag(), part.length(), part.digest(),
                    part.fileModified());
        }

        boolean matchesFile() {
            return file.isFile() && file.length() == length && file.lastModified() == fileModified;
        }
    }

    static final class Source {
        final String videoId;
        final Served served;
        /** Latest matching HTTPS URL for this file generation, if any. */
        volatile String online;
        final String token;
        final PlaybackSourceInspector.LocalFormat inspectionFormat;
        volatile boolean available = true;

        Source(String videoId, Served served, String online, String token) {
            this.videoId = videoId;
            this.served = served;
            this.online = online;
            this.token = token;
            inspectionFormat = PlaybackSourceInspector.supported() ? inspectionFormat(this) : null;
        }

        String fallback() throws FileNotFoundException {
            String fallback = online;
            if (fallback == null) {
                throw new FileNotFoundException("Local media unavailable; reload player response: " + videoId);
            }
            return fallback;
        }
    }

    private static final String SESSION = UUID.randomUUID().toString();
    private static final AtomicLong sequence = new AtomicLong();
    private static final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Source> latest = new ConcurrentHashMap<>();
    /** Latest accepted response decision per id, not the last response (which may be a prefetched next item). */
    private static final ConcurrentHashMap<String, List<Source>> selected = new ConcurrentHashMap<>();
    // Native responses and prepared clips can reopen a token without a live extension wrapper. Keep a token
    // for the process lifetime, reused across responses for the same verified file generation.

    private StreamSwapSources() {}

    private static PlaybackSourceInspector.LocalFormat inspectionFormat(Source media) {
        PlaybackSourceInspector.Source source = switch (media.served.kind()) {
            case KIND_RAW -> PlaybackSourceInspector.Source.CACHED_AUDIO;
            case KIND_VIDEO_AUDIO -> PlaybackSourceInspector.Source.CACHED_VIDEO_AUDIO;
            case KIND_VIDEO -> PlaybackSourceInspector.Source.CACHED_VIDEO;
            default -> null;
        };
        return source == null ? null : new PlaybackSourceInspector.LocalFormat(source, media.served.itag(), media);
    }

    static void select(List<Source> sources) {
        selected.put(sources.get(0).videoId, sources);
    }

    static void selectOnline(String videoId) {
        selected.remove(videoId);
    }

    /** No storage access: the current item's accepted sources, not merely the existence of a downloaded copy. */
    static boolean playsLocal(String videoId) {
        List<Source> sources = selected.get(videoId);
        if (sources == null) return false;

        for (Source source : sources) {
            if (!source.available) return false;
        }
        return true;
    }

    /** The accepted sources are the raw audio copy alone: no video of a copy, no online stream. */
    static boolean playsRaw(String videoId) {
        List<Source> sources = selected.get(videoId);
        return sources != null && sources.size() == 1 && sources.get(0).available
                && KIND_RAW.equals(sources.get(0).served.kind());
    }

    /** The format's own URL may be empty (SABR): the source then has no online fallback. */
    @Nullable
    static Source issue(String videoId, RawAudioFiles.Entry raw, String formatUrl) {
        return issue(videoId, Served.of(raw), formatUrl);
    }

    /** The source of the video-only stream of a music video's copy, {@code videoId} being the music video's id. */
    @Nullable
    static Source issueVideo(String videoId, VideoCopyFiles.Entry copy, String formatUrl) {
        return issue(videoId, Served.video(copy), formatUrl);
    }

    /** The source of the audio stream of a music video's copy. */
    @Nullable
    static Source issueVideoAudio(String videoId, VideoCopyFiles.Entry copy, String formatUrl) {
        return issue(videoId, Served.audio(copy), formatUrl);
    }

    private static String streamKey(String videoId, String kind) {
        return videoId + "/" + kind;
    }

    @Nullable
    private static synchronized Source issue(String videoId, Served served, String formatUrl) {
        if (!served.matchesFile()) return null;

        String onlineUrl = StreamSwapLogic.onlineUrl(formatUrl);
        String streamKey = streamKey(videoId, served.kind());
        Source previous = latest.get(streamKey);
        if (previous != null) {
            if (previous.available && previous.served.equals(served)) {
                if (onlineUrl != null) previous.online = onlineUrl;
                return previous;
            }
            previous.available = false;
        }

        String key = SESSION + "-" + sequence.incrementAndGet();
        String uri = StreamSwapLogic.tokenUrl(videoId, served.itag(), served.kind(), key);
        Source source = new Source(videoId, served, onlineUrl, uri);
        sources.put(key, source);
        latest.put(streamKey, source);
        return source;
    }

    /** Resolves the token even when a native clip outlives the response that first used it. */
    static Source lookup(String key) {
        return sources.get(key);
    }

    static Source find(Uri uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() != 1 || !"/rvx".equals(uri.getPath())) return null;
        String token = uri.getQueryParameter("t");
        if (token == null) return null;
        Source source = lookup(token);
        if (source == null || !source.videoId.equals(uri.getQueryParameter("v"))
                || !Integer.toString(source.served.itag()).equals(uri.getQueryParameter("i"))
                || !source.served.kind().equals(uri.getQueryParameter("m"))) return null;
        return source;
    }

    static synchronized void invalidate(String videoId) {
        invalidateLatest(streamKey(videoId, KIND_RAW));
        invalidateLatest(streamKey(videoId, KIND_VIDEO));
        invalidateLatest(streamKey(videoId, KIND_VIDEO_AUDIO));
        selected.remove(videoId);
    }

    private static void invalidateLatest(String streamKey) {
        Source source = latest.remove(streamKey);
        if (source != null) source.available = false;
    }

    /** Every stream copy was deleted: no issued source may serve from a file again. */
    static synchronized void invalidateAll() {
        for (Source source : latest.values()) source.available = false;
        latest.clear();
        selected.clear();
    }

    static synchronized void invalidateMissing() {
        latest.values().removeIf(source -> {
            if (source.served.matchesFile()) return false;

            source.available = false;
            return true;
        });
    }
}
