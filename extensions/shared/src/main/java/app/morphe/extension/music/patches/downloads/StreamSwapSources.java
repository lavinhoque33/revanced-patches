/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import java.io.FileNotFoundException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback URLs are opaque engine-routing tokens, never HTTP endpoints. No listener or socket is needed. */
final class StreamSwapSources {
    static final class Source {
        final String videoId;
        final RawAudioFiles.Entry raw;
        /** Original https URL of the swapped format, null for SABR formats that have none. */
        final String online;
        final String key;
        final String token;
        final long fileModified;
        volatile boolean available = true;

        Source(String videoId, RawAudioFiles.Entry raw, String online, String key, String token) {
            this.videoId = videoId;
            this.raw = raw;
            this.online = online;
            this.key = key;
            this.token = token;
            fileModified = raw.file().lastModified();
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
    static synchronized Source issue(String videoId, RawAudioFiles.Entry raw, String formatUrl) {
        String onlineUrl = StreamSwapLogic.onlineUrl(formatUrl);
        Source previous = latest.get(videoId);
        if (previous != null && previous.available && previous.raw.equals(raw)
                && Objects.equals(previous.online, onlineUrl)) return previous;

        String key = SESSION + "-" + sequence.incrementAndGet();
        String uri = "http://127.0.0.1:1/rvx?v=" + videoId + "&i=" + raw.itag() + "&m=raw&t=" + key;
        Source source = new Source(videoId, raw, onlineUrl, key, uri);
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
