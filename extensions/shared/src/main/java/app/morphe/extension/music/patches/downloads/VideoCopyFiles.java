/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * The video copies of songs, in a directory of their own beside the raw audio copies: the muxed 360p mp4 of the
 * song's music video, filed under the id of the music video and tied to the song (the downloaded audio copy) it
 * belongs to. A copy is {@code <video>.mp4}, {@code <video>.resp} (the stored player response of the video) and
 * {@code <video>.json}, the commit record, which names the song and the lengths and checksum of the other two and is
 * published last. The pairs (video to song) are the commit records, read into memory once by {@link #ensureLoaded},
 * so that {@link #has} never touches the disk. All other disk access belongs to a worker thread.
 */
final class VideoCopyFiles {
    /** A committed copy. {@code sourceId} is the playlist or album it was downloaded from, null when unknown. */
    record Entry(File file, File response, String videoId, String songId, @Nullable String sourceId, int itag,
                 String mime, long length, long modified, long durationMs, String digest) {}

    /** What the directory holds: committed copies and the bytes of their videos. */
    record Stats(int count, long bytes) {}

    /** A committed copy with the file state that was hashed: another length or mtime is hashed again. */
    private record Candidate(Entry entry, long fileLength, long fileModified) {}

    static final int MAX_RESPONSE_BYTES = OfflineCopyFiles.MAX_RESPONSE_BYTES;

    private static final String VIDEO = ".mp4";
    private static final String RESPONSE = ".resp";
    private static final String COMMIT = ".json";
    private static final String PENDING = ".part";
    private static final long MAX_COMMIT_BYTES = 8192;

    private final File directory;
    private final Map<String, Candidate> verified = new HashMap<>();
    /** The committed copies: video id to the id of the song it belongs to. */
    private final Map<String, String> songOf = new ConcurrentHashMap<>();
    private volatile boolean loaded;

    VideoCopyFiles(File directory) {
        this.directory = directory;
    }

    /** Reads the committed pairs off the disk once; later calls do nothing. */
    synchronized void ensureLoaded() {
        if (loaded) return;

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.getName().endsWith(COMMIT)) continue;

                String id = idOf(file.getName());
                try {
                    Entry entry = committed(id);
                    if (entry != null) songOf.put(id, entry.songId());
                } catch (Exception ignored) {
                    // A malformed commit is not a copy; the orphan cleanup removes its files.
                }
            }
        }
        loaded = true;
    }

    /** Whether the video has a committed copy. False until loaded. No disk access. */
    boolean has(String videoId) {
        return songOf.containsKey(videoId);
    }

    /** The committed pairs, video id to song id. No disk access. */
    Map<String, String> pairs() {
        return new HashMap<>(songOf);
    }

    /** The id of the video copy that belongs to the song, or null. No disk access. */
    @Nullable
    String videoOf(String songId) {
        for (Map.Entry<String, String> pair : songOf.entrySet()) {
            if (pair.getValue().equals(songId)) return pair.getKey();
        }
        return null;
    }

    /**
     * Publishes the copy, replacing what was stored. The temporary file is private to the download and is moved.
     *
     * @return Whether the copy was published; false when the arguments cannot describe a playable copy.
     */
    boolean retain(String videoId, String songId, @Nullable String sourceId, File temporary, int itag, String mime,
                   long length, long modified, long durationMs, byte[] response) throws Exception {
        if (!RawAudioFiles.validId(videoId) || !RawAudioFiles.validId(songId)
                || !VideoCopyLogic.supportedVideo(itag, mime) || length <= 0 || temporary.length() != length
                || response.length == 0 || response.length > MAX_RESPONSE_BYTES) return false;

        // Hash the private file before taking the monitor playback needs.
        String digest = digest(temporary);
        JSONObject commit = new JSONObject().put("itag", itag).put("mime", mime).put("length", length)
                .put("lmt", modified).put("durationMs", durationMs).put("sha256", digest).put("songId", songId)
                .put("responseBytes", response.length);
        if (sourceId != null) commit.put("sourceId", sourceId);
        synchronized (this) {
            publish(videoId, temporary, response, commit);
            songOf.put(videoId, songId);
        }
        return true;
    }

    private void publish(String videoId, File temporary, byte[] response, JSONObject commit) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No video copy directory");

        verified.remove(videoId);
        songOf.remove(videoId);
        File video = file(videoId, VIDEO);
        File responseFile = file(videoId, RESPONSE);
        File commitFile = file(videoId, COMMIT);
        File pendingResponse = file(videoId, RESPONSE + PENDING);
        File pendingCommit = file(videoId, COMMIT + PENDING);
        // Invalidate a previous commit before replacing any bytes, even if the new write later fails.
        Files.deleteIfExists(commitFile.toPath());
        try {
            Files.write(pendingResponse.toPath(), response);
            Files.write(pendingCommit.toPath(), commit.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), video.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(pendingResponse.toPath(), responseFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(pendingCommit.toPath(), commitFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            Files.deleteIfExists(video.toPath());
            Files.deleteIfExists(responseFile.toPath());
            throw ex;
        } finally {
            Files.deleteIfExists(pendingResponse.toPath());
            Files.deleteIfExists(pendingCommit.toPath());
        }
    }

    /**
     * The committed copy that may be played, or null. The SHA-256 is computed outside the monitor (it reads the
     * whole file) and re-checked under it; an unchanged file (path, length, mtime) is hashed only once.
     */
    @Nullable
    Entry find(String videoId) {
        try {
            Candidate candidate;
            synchronized (this) {
                candidate = candidate(videoId);
                if (candidate == null) return null;
                if (candidate.equals(verified.get(videoId))) return candidate.entry();
            }

            String actual = digest(candidate.entry().file());
            synchronized (this) {
                // A concurrent retain, delete or replacement while hashing makes this result stale.
                if (!candidate.equals(candidate(videoId)) || !candidate.entry().digest().equals(actual)) return null;

                verified.put(videoId, candidate);
                return candidate.entry();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    /** The stored response of a committed copy, or null. */
    @Nullable
    byte[] response(String videoId) {
        try {
            Entry entry = committed(videoId);
            return entry == null ? null : Files.readAllBytes(entry.response().toPath());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Candidate candidate(String videoId) throws Exception {
        Entry entry = committed(videoId);
        if (entry == null) return null;

        return new Candidate(entry, entry.file().length(), entry.file().lastModified());
    }

    /** The commit's claim, checked structurally: files of the recorded lengths exist. Never hashes. */
    @Nullable
    private Entry committed(String videoId) throws Exception {
        if (!RawAudioFiles.validId(videoId)) return null;

        File commitFile = file(videoId, COMMIT);
        if (!commitFile.isFile() || commitFile.length() > MAX_COMMIT_BYTES) return null;

        JSONObject commit = new JSONObject(new String(Files.readAllBytes(commitFile.toPath()),
                StandardCharsets.UTF_8));
        int itag = commit.getInt("itag");
        String mime = commit.getString("mime");
        long length = commit.getLong("length");
        String songId = commit.getString("songId");
        long responseBytes = commit.getLong("responseBytes");
        File video = file(videoId, VIDEO);
        File response = file(videoId, RESPONSE);
        if (!VideoCopyLogic.supportedVideo(itag, mime) || length <= 0 || !RawAudioFiles.validId(songId)
                || !video.isFile() || video.length() != length || responseBytes <= 0
                || response.length() != responseBytes) return null;

        return new Entry(video, response, videoId, songId, commit.optString("sourceId", null), itag, mime, length,
                commit.optLong("lmt"), commit.optLong("durationMs"), commit.getString("sha256"));
    }

    /** The committed copies and the bytes of their videos. */
    Stats stats() {
        int count = 0;
        long bytes = 0;
        File[] files = directory.listFiles();
        if (files == null) return new Stats(0, 0);

        for (File file : files) {
            if (!file.getName().endsWith(COMMIT)) continue;

            try {
                Entry entry = committed(idOf(file.getName()));
                if (entry == null) continue;

                count++;
                bytes += entry.length();
            } catch (Exception ignored) {
                // A malformed commit is not a copy.
            }
        }
        return new Stats(count, bytes);
    }

    synchronized void delete(String videoId) {
        if (!RawAudioFiles.validId(videoId)) return;

        verified.remove(videoId);
        songOf.remove(videoId);
        File[] files = directory.listFiles((parent, name) -> name.startsWith(videoId + "."));
        if (files != null) for (File file : files) file.delete();
    }

    /** Removes every file of the directory, copies and leftovers alike. @return How many copies were committed. */
    synchronized int clear() {
        int count = stats().count();
        verified.clear();
        songOf.clear();
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        return count;
    }

    /**
     * Removes the copies of songs that are not in {@code songIds}, uncommitted and broken entries, and leftovers of
     * interrupted writes. Files modified after {@code notAfterMillis} are kept: they were published after the song
     * ids were read.
     *
     * @return The ids of the video copies that were removed.
     */
    synchronized Set<String> retainOnly(Set<String> songIds, long notAfterMillis) {
        Set<String> removed = new HashSet<>();
        File[] files = directory.listFiles();
        if (files == null) return removed;

        Set<String> keep = new HashSet<>();
        for (File file : files) {
            if (!file.getName().endsWith(COMMIT)) continue;

            String id = idOf(file.getName());
            try {
                Entry entry = committed(id);
                if (entry != null && songIds.contains(entry.songId())) keep.add(id);
            } catch (Exception ignored) {
                // A malformed commit is not kept.
            }
        }
        for (File file : files) {
            String id = idOf(file.getName());
            if ((keep.contains(id) && isCopyFile(file.getName())) || file.lastModified() > notAfterMillis) continue;

            if (!keep.contains(id)) {
                verified.remove(id);
                if (songOf.remove(id) != null) removed.add(id);
            }
            file.delete();
        }
        return removed;
    }

    private File file(String videoId, String suffix) {
        return new File(directory, videoId + suffix);
    }

    private static boolean isCopyFile(String name) {
        return name.endsWith(VIDEO) || name.endsWith(RESPONSE) || name.endsWith(COMMIT);
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
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }
}
