/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 * Modified by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loopback URLs are opaque engine-routing tokens, never HTTP endpoints; no listener or socket is needed. A video copy
 * is announced by its file's loopback {@code file} uri instead, because the framework player cannot be hooked.
 */
final class StreamSwapSources {
    static final String KIND_RAW = "raw";
    static final String KIND_VIDEO = "video";

    /** What a token serves: a file of a kind (the raw audio copy or the video copy), its itag and its length. */
    record Served(String kind, File file, int itag, long length) {
        static Served of(RawAudioFiles.Entry raw) {
            return new Served(KIND_RAW, raw.file(), raw.itag(), raw.length());
        }

        static Served of(VideoCopyFiles.Entry video) {
            return new Served(KIND_VIDEO, video.file(), video.itag(), video.length());
        }
    }

    static final class Source {
        final String videoId;
        final Served served;
        /** Original https URL of the swapped format, null for SABR formats that have none. */
        final String online;
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
            fileModified = served.file().lastModified();
        }

        String fallback() throws FileNotFoundException {
            if (online == null) {
                throw new FileNotFoundException("Local audio unavailable; reload player response: " + videoId);
            }
            return online;
        }
    }

    private static final String SESSION = UUID.randomUUID().toString();
    private static final AtomicLong sequence = new AtomicLong();
    private static final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Source> latest = new ConcurrentHashMap<>();
    /** Tokens of one track stay valid for in-flight responses; older ones are dropped beyond this many. */
    static final int TOKENS_PER_VIDEO = 4;
    /** Issue order per track, guarded by the class monitor; the maps above stay readable without it. */
    private static final Map<String, ArrayDeque<Source>> issued = new HashMap<>();

    private StreamSwapSources() {}

    /** The format's own URL may be empty (SABR): the source then has no online fallback. */
    static Source issue(String videoId, RawAudioFiles.Entry raw, String formatUrl) {
        return issue(videoId, Served.of(raw), formatUrl);
    }

    /** The source of a music video's video copy, {@code videoId} being the id of the music video. */
    static Source issueVideo(String videoId, VideoCopyFiles.Entry video, String formatUrl) {
        return issue(videoId, Served.of(video), formatUrl);
    }

    private static synchronized Source issue(String videoId, Served served, String formatUrl) {
        String onlineUrl = StreamSwapLogic.onlineUrl(formatUrl);
        Source previous = latest.get(videoId);
        if (previous != null && previous.available && previous.served.equals(served)
                && Objects.equals(previous.online, onlineUrl)) return previous;

        String key = SESSION + "-" + sequence.incrementAndGet();
        String uri = KIND_VIDEO.equals(served.kind()) ? StreamSwapLogic.videoFileUrl(served.file().getAbsolutePath())
                : "http://127.0.0.1:1/rvx?v=" + videoId + "&i=" + served.itag() + "&m=" + served.kind() + "&t=" + key;
        Source source = new Source(videoId, served, onlineUrl, key, uri);
        sources.put(key, source);
        latest.put(videoId, source);
        ArrayDeque<Source> history = issued.computeIfAbsent(videoId, id -> new ArrayDeque<>());
        history.addLast(source);
        while (history.size() > TOKENS_PER_VIDEO) sources.remove(history.removeFirst().key);
        return source;
    }

    /** The issued source for a token key, or null once it was replaced by newer ones for the same track. */
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

    /** The issued video source whose file a {@link StreamSwapLogic#videoFileUrl} uri names, or null. */
    static Source findVideoFile(Uri uri) {
        if (uri == null) return null;

        String path = StreamSwapLogic.videoFilePath(uri.getScheme(), uri.getHost(), uri.getPath());
        if (path == null) return null;

        for (Source source : sources.values()) {
            if (source.available && KIND_VIDEO.equals(source.served.kind())
                    && source.served.file().getAbsolutePath().equals(path)) return source;
        }
        return null;
    }

    static void invalidate(String videoId) {
        for (Source source : sources.values()) {
            if (source.videoId.equals(videoId)) source.available = false;
        }
        latest.remove(videoId);
    }

    /** Every stream copy was deleted: no issued source may serve from a file again. */
    static void invalidateAll() {
        for (Source source : sources.values()) source.available = false;
        latest.clear();
    }

    static void invalidateMissing() {
        for (Source source : sources.values()) {
            if (!source.served.file().isFile() || source.served.file().length() != source.served.length()) {
                source.available = false;
                latest.remove(source.videoId, source);
            }
        }
    }
}
