/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback URLs are opaque engine-routing tokens, never HTTP endpoints. No listener or socket is needed. */
final class StreamSwapSources {
    static final class Source {
        final String videoId;
        final RawAudioFiles.Entry raw;
        final Uri online;
        final Uri token;
        final long fileModified;
        volatile boolean available = true;

        Source(String videoId, RawAudioFiles.Entry raw, Uri online, Uri token) {
            this.videoId = videoId;
            this.raw = raw;
            this.online = online;
            this.token = token;
            fileModified = raw.file().lastModified();
        }
    }

    private static final String SESSION = UUID.randomUUID().toString();
    private static final AtomicLong sequence = new AtomicLong();
    private static final ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Source> latest = new ConcurrentHashMap<>();

    private StreamSwapSources() {}

    static Source issue(String videoId, RawAudioFiles.Entry raw, String onlineUrl) {
        Source previous = latest.get(videoId);
        if (previous != null && previous.available && previous.raw.equals(raw)
                && previous.online.toString().equals(onlineUrl)) return previous;

        String token = SESSION + "-" + sequence.incrementAndGet();
        Uri uri = new Uri.Builder().scheme("http").encodedAuthority("127.0.0.1:1").path("/rvx")
                .appendQueryParameter("v", videoId).appendQueryParameter("i", Integer.toString(raw.itag()))
                .appendQueryParameter("m", "raw").appendQueryParameter("t", token).build();
        Source source = new Source(videoId, raw, Uri.parse(onlineUrl), uri);
        sources.put(token, source);
        latest.put(videoId, source);
        return source;
    }

    static Source find(Uri uri) {
        if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() != 1 || !"/rvx".equals(uri.getPath())) return null;
        String token = uri.getQueryParameter("t");
        if (token == null) return null;
        Source source = sources.get(token);
        if (source == null || !source.videoId.equals(uri.getQueryParameter("v"))
                || !Integer.toString(source.raw.itag()).equals(uri.getQueryParameter("i"))
                || !"raw".equals(uri.getQueryParameter("m"))) return null;
        return source;
    }

    static void invalidate(String videoId) {
        for (Source source : sources.values()) {
            if (source.videoId.equals(videoId)) source.available = false;
        }
        latest.remove(videoId);
    }

    static void invalidateMissing() {
        for (Source source : sources.values()) {
            if (!source.raw.file().isFile() || source.raw.file().length() != source.raw.length()) {
                source.available = false;
                latest.remove(source.videoId, source);
            }
        }
    }
}
