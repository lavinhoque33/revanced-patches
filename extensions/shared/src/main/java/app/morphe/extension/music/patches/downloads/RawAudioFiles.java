/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Original itag bytes; the JSON is the commit record and is published last. All callers own a worker thread. */
final class RawAudioFiles {
    record Entry(File file, int itag, String mime, long length, long modified, String muxed, String digest) {}

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final long MAX_METADATA_BYTES = 4096;
    /** A committed copy together with the file state that was hashed: another length or mtime is hashed again. */
    private record Candidate(Entry entry, long fileLength, long fileModified) {}

    private final File directory;
    private final Map<String, Candidate> verified = new HashMap<>();

    RawAudioFiles(File directory) {
        this.directory = directory;
    }

    static boolean validId(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    void retain(String videoId, File temporary, int itag, String mime, long length,
                long modified, String muxed) throws Exception {
        if (!validId(videoId) || !StreamSwapLogic.supportedAudio(itag, mime) || length <= 0
                || modified <= 0 || temporary.length() != length || muxed == null || muxed.isEmpty()) {
            return;
        }
        // The temporary file is private to this download: hash it before taking the monitor playback needs.
        String digest = digest(temporary);
        JSONObject metadata = new JSONObject().put("itag", itag).put("mime", mime).put("length", length)
                .put("lmt", modified).put("muxed", muxed).put("sha256", digest);
        synchronized (this) {
            publish(videoId, temporary, itag, metadata);
        }
    }

    private void publish(String videoId, File temporary, int itag, JSONObject metadata) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No raw audio directory");

        verified.remove(videoId);
        File raw = new File(directory, videoId + "." + itag);
        File sidecar = new File(directory, videoId + ".json");
        File pending = new File(directory, videoId + ".json.part");
        // Invalidate a previous commit before replacing any bytes, even if the new write later fails.
        Files.deleteIfExists(sidecar.toPath());
        try {
            Files.write(pending.toPath(), metadata.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), raw.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(pending.toPath(), sidecar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            Files.deleteIfExists(raw.toPath());
            throw ex;
        } finally {
            Files.deleteIfExists(pending.toPath());
        }
    }

    /**
     * The committed copy for the current native track, or null. The SHA-256 is computed outside the monitor (it
     * reads the whole file) and re-checked under it; an unchanged file (path, length, mtime) is hashed only once.
     */
    Entry find(String videoId, String currentMuxed) {
        try {
            Candidate candidate;
            synchronized (this) {
                candidate = candidate(videoId, currentMuxed);
                if (candidate == null) return null;
                if (candidate.equals(verified.get(videoId))) return candidate.entry();
            }

            String actual = digest(candidate.entry().file());
            synchronized (this) {
                // A concurrent retain, delete or replacement while hashing makes this result stale.
                if (!candidate.equals(candidate(videoId, currentMuxed))) return null;
                if (!candidate.entry().digest().equals(actual)) return null;

                verified.put(videoId, candidate);
                return candidate.entry();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private Candidate candidate(String videoId, String currentMuxed) throws Exception {
        Entry entry = committed(videoId);
        if (entry == null || currentMuxed == null || !currentMuxed.equals(entry.muxed())) return null;

        return new Candidate(entry, entry.file().length(), entry.file().lastModified());
    }

    /** The sidecar's claim, checked only structurally: bytes of the recorded length exist. Never hashes. */
    private Entry committed(String videoId) throws Exception {
        if (!validId(videoId)) return null;
        File sidecar = new File(directory, videoId + ".json");
        if (!sidecar.isFile() || sidecar.length() > MAX_METADATA_BYTES) return null;

        JSONObject metadata = new JSONObject(new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8));
        int itag = metadata.getInt("itag");
        String mime = metadata.getString("mime");
        long length = metadata.getLong("length");
        long modified = metadata.getLong("lmt");
        String muxed = metadata.getString("muxed");
        String checksum = metadata.getString("sha256");
        File raw = new File(directory, videoId + "." + itag);
        if (!StreamSwapLogic.supportedAudio(itag, mime) || length <= 0 || modified <= 0
                || !raw.isFile() || raw.length() != length) return null;

        return new Entry(raw, itag, mime, length, modified, muxed, checksum);
    }

    synchronized void delete(String videoId) {
        if (!validId(videoId)) return;
        verified.remove(videoId);
        File[] files = directory.listFiles((parent, name) -> name.startsWith(videoId + "."));
        if (files != null) for (File file : files) file.delete();
    }

    /**
     * Remove uncommitted, structurally broken and no-longer-downloaded entries, including interrupted download
     * leftovers. Entries are NOT hashed here (that is left to {@link #find}): this runs after every media change and
     * must stay cheap. The native lookups run before the monitor is taken because playback needs it too.
     */
    void cleanup(Function<String, String> currentMuxed) {
        File[] snapshot = directory.listFiles();
        if (snapshot == null) return;

        Map<String, String> nativeUris = new HashMap<>();
        for (File file : snapshot) {
            if (isSidecar(file)) {
                String id = idOf(file.getName());
                nativeUris.put(id, currentMuxed.apply(id));
            }
        }

        synchronized (this) {
            File[] files = directory.listFiles();
            if (files == null) return;

            Set<String> retained = new HashSet<>();
            for (File sidecar : files) {
                if (!isSidecar(sidecar)) continue;
                String id = idOf(sidecar.getName());
                try {
                    Entry entry = committed(id);
                    if (entry == null) continue;
                    // A commit published after the snapshot has no lookup yet: keep it for the next pass.
                    if (nativeUris.containsKey(id) && !entry.muxed().equals(nativeUris.get(id))) continue;
                    retained.add(sidecar.getName());
                    retained.add(entry.file().getName());
                } catch (Exception ignored) {
                    // A malformed or incomplete commit cannot authorize playback.
                }
            }
            for (File file : files) {
                if (!file.isFile() || retained.contains(file.getName())) continue;
                verified.remove(idOf(file.getName()));
                file.delete();
            }
        }
    }

    private static boolean isSidecar(File file) {
        return file.getName().endsWith(".json");
    }

    private static String idOf(String fileName) {
        int dot = fileName.indexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        byte[] bytes = digest.digest();
        char[] hex = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int index = 0; index < bytes.length; index++) {
            hex[index * 2] = digits.charAt((bytes[index] & 0xff) >>> 4);
            hex[index * 2 + 1] = digits.charAt(bytes[index] & 15);
        }
        return new String(hex);
    }
}
