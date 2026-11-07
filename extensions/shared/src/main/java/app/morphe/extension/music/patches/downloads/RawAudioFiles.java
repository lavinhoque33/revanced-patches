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
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Original itag bytes; the JSON is the commit record and is published last. All callers own a worker thread. */
final class RawAudioFiles {
    record Entry(File file, int itag, String mime, long length, long modified, String muxed, String digest) {}

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final long MAX_METADATA_BYTES = 4096;
    private final File directory;

    RawAudioFiles(File directory) {
        this.directory = directory;
    }

    static boolean validId(String id) {
        return id != null && VIDEO_ID.matcher(id).matches();
    }

    synchronized void retain(String videoId, File temporary, int itag, String mime, long length,
                             long modified, String muxed) throws Exception {
        if (!validId(videoId) || !StreamSwapLogic.supportedAudio(itag, mime) || length <= 0
                || modified <= 0 || temporary.length() != length || muxed == null || muxed.isEmpty()) {
            return;
        }
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No raw audio directory");

        String digest = digest(temporary);
        File raw = new File(directory, videoId + "." + itag);
        File sidecar = new File(directory, videoId + ".json");
        File pending = new File(directory, videoId + ".json.part");
        JSONObject metadata = new JSONObject().put("itag", itag).put("mime", mime).put("length", length)
                .put("lmt", modified).put("muxed", muxed).put("sha256", digest);
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

    synchronized Entry find(String videoId, String currentMuxed) {
        try {
            return read(videoId, currentMuxed);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Entry read(String videoId, String currentMuxed) throws Exception {
        if (!validId(videoId) || currentMuxed == null) return null;
        File sidecar = new File(directory, videoId + ".json");
        if (!sidecar.isFile() || sidecar.length() > MAX_METADATA_BYTES) return null;

        JSONObject metadata = new JSONObject(new String(Files.readAllBytes(sidecar.toPath()), StandardCharsets.UTF_8));
        int itag = metadata.getInt("itag");
        String mime = metadata.getString("mime");
        long length = metadata.getLong("length");
        long modified = metadata.getLong("lmt");
        String savedMuxed = metadata.getString("muxed");
        String checksum = metadata.getString("sha256");
        File raw = new File(directory, videoId + "." + itag);
        if (!StreamSwapLogic.supportedAudio(itag, mime) || length <= 0 || modified <= 0
                || !currentMuxed.equals(savedMuxed) || !raw.isFile() || raw.length() != length
                || !checksum.equals(digest(raw))) return null;

        return new Entry(raw, itag, mime, length, modified, savedMuxed, checksum);
    }

    synchronized void delete(String videoId) {
        if (!validId(videoId)) return;
        File[] files = directory.listFiles((parent, name) -> name.startsWith(videoId + "."));
        if (files != null) for (File file : files) file.delete();
    }

    /** Remove uncommitted, corrupt and no-longer-downloaded entries, including interrupted download leftovers. */
    synchronized void cleanup(Function<String, String> currentMuxed) {
        File[] files = directory.listFiles();
        if (files == null) return;
        Set<String> retained = new HashSet<>();
        for (File sidecar : files) {
            String name = sidecar.getName();
            if (!name.endsWith(".json")) continue;
            String id = name.substring(0, name.length() - 5);
            try {
                Entry entry = find(id, currentMuxed.apply(id));
                if (entry == null) continue;
                retained.add(name);
                retained.add(entry.file().getName());
            } catch (Exception ignored) {
                // A malformed or incomplete commit cannot authorize playback.
            }
        }
        for (File file : files) {
            if (file.isFile() && !retained.contains(file.getName())) file.delete();
        }
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
