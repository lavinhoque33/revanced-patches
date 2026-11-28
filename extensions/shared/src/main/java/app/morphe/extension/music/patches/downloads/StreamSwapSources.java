/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-12.
 * Modified by lavinhoque33, 2026-11-27.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loopback URLs are opaque engine-routing tokens, never HTTP endpoints; no listener or socket is needed. The raw
 * audio copy of a song and the two streams of a video copy (its video-only stream and its own audio) are announced
 * alike, one token each. A token names one file generation for the whole process: every later response of the same
 * file announces the same token, so a Song/Video flip, a seek or a late answer never hands the player a new stream.
 */
final class StreamSwapSources {
    static final String KIND_RAW = "raw";
    /** The video-only stream of a video copy. */
    static final String KIND_VIDEO = "video";
    /** The audio stream of a video copy, the music video's own. */
    static final String KIND_VIDEO_AUDIO = "vaudio";

    /**
     * What a token serves: a file of a kind (the raw audio copy or a stream of a video copy), itag and length, and the
     * file's modification time when it was announced. A replaced file is another generation and gets a new token.
     */
    record Served(String kind, File file, int itag, long length, long fileModified) {
        static Served of(RawAudioFiles.Entry raw) {
            return new Served(KIND_RAW, raw.file(), raw.itag(), raw.length(), raw.file().lastModified());
        }

        static Served video(VideoCopyFiles.Entry copy) {
            return of(KIND_VIDEO, copy.video());
        }

        static Served audio(VideoCopyFiles.Entry copy) {
            return of(KIND_VIDEO_AUDIO, copy.audio());
        }

        private static Served of(String kind, VideoCopyFiles.Part part) {
            return new Served(kind, part.file(), part.itag(), part.length(), part.file().lastModified());
        }
    }

    static final class Source {
        final String videoId;
        final Served served;
        /** Latest https URL announced for this file, null while no answer had one (SABR formats have none). */
        volatile String online;
        final String key;
        final String token;
        final long fileModified;
        volatile boolean available = true;

        Source(String videoId, Served served, String online, String key, String token) {
            this.videoId = videoId;
            this.served = served;
            this.online = online;
            this.key = key;
            this.token = token;
            fileModified = served.fileModified();
        }

        String fallback() throws FileNotFoundException {
            String url = online;
            if (url == null) {
                throw new FileNotFoundException("Local audio unavailable; reload player response: " + videoId);
            }
            return url;
        }
    }

    private static final String SESSION = UUID.randomUUID().toString();
    private static final AtomicLong sequence = new AtomicLong();
    /** Every token issued in this process; an older generation stays resolvable and serves its fallback. */
    private static final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
    /** The latest generation of each stream (id and kind). */
    private static final ConcurrentHashMap<String, Source> latest = new ConcurrentHashMap<>();
    /** Latest accepted response decision per id, not the last response (which may be a prefetched next item). */
    private static final ConcurrentHashMap<String, List<Source>> selected = new ConcurrentHashMap<>();

    private StreamSwapSources() {}

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

    /** The format's own URL may be empty (SABR): the source then has no online fallback. */
    static Source issue(String videoId, RawAudioFiles.Entry raw, String formatUrl) {
        return issue(videoId, Served.of(raw), formatUrl);
    }

    /** The source of the video-only stream of a music video's copy, {@code videoId} being the music video's id. */
    static Source issueVideo(String videoId, VideoCopyFiles.Entry copy, String formatUrl) {
        return issue(videoId, Served.video(copy), formatUrl);
    }

    /** The source of the audio stream of a music video's copy. */
    static Source issueVideoAudio(String videoId, VideoCopyFiles.Entry copy, String formatUrl) {
        return issue(videoId, Served.audio(copy), formatUrl);
    }

    private static String streamKey(String videoId, String kind) {
        return videoId + "/" + kind;
    }

    /**
     * The token of this file generation, issued once: a later answer only refreshes its online fallback, which it never
     * clears. A different generation of the same stream retires the previous token.
     */
    private static synchronized Source issue(String videoId, Served served, String formatUrl) {
        String onlineUrl = StreamSwapLogic.onlineUrl(formatUrl);
        String streamKey = streamKey(videoId, served.kind());
        Source previous = latest.get(streamKey);
        if (previous != null && previous.available && previous.served.equals(served)) {
            if (onlineUrl != null) previous.online = onlineUrl;
            return previous;
        }
        if (previous != null) previous.available = false;

        String key = SESSION + "-" + sequence.incrementAndGet();
        String uri = StreamSwapLogic.tokenUrl(videoId, served.itag(), served.kind(), key);
        Source source = new Source(videoId, served, onlineUrl, key, uri);
        sources.put(key, source);
        latest.put(streamKey, source);
        return source;
    }

    /** The issued source for a token key; a token stays known for the process lifetime. */
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

    static void invalidate(String videoId) {
        for (Source source : sources.values()) {
            if (source.videoId.equals(videoId)) source.available = false;
        }
        latest.values().removeIf(source -> source.videoId.equals(videoId));
        selected.remove(videoId);
    }

    /** Every stream copy was deleted: no issued source may serve from a file again. */
    static void invalidateAll() {
        for (Source source : sources.values()) source.available = false;
        latest.clear();
        selected.clear();
    }

    static void invalidateMissing() {
        for (Source source : sources.values()) {
            if (!source.served.file().isFile() || source.served.file().length() != source.served.length()) {
                source.available = false;
                latest.values().remove(source);
            }
        }
    }
}
