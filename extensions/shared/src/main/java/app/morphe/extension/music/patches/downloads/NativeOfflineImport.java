/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Download-time experiment. Neither the pilot list nor the transfer adapter is a source of offline eligibility. */
@SuppressWarnings("unused")
public final class NativeOfflineImport {
    static final String PILOT_FILE = "rvx_native_import_ids";
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static volatile Set<String> pilots;

    private NativeOfflineImport() {}

    /** Installed only on Music 9.45.51. */
    private static boolean supported() {
        return false;
    }

    public static boolean includes(@Nullable String videoId) {
        if (videoId == null || !supported() || !Utils.isContextSet() || !BaseSettings.DEBUG.get()) return false;

        Set<String> ids = pilots;
        return (ids == null ? loadPilots() : ids).contains(videoId);
    }

    /** Keep the pilot's delegated variant on the original stock command, not the fork's command. */
    public static boolean usesStockCommand(@Nullable String videoId) {
        return includes(videoId) && LocalDownloadManager.downloadMode() == DownloadMode.OFF;
    }

    private static synchronized Set<String> loadPilots() {
        if (pilots != null) return pilots;

        File file = new File(Utils.getContext().getFilesDir(), PILOT_FILE);
        Set<String> ids = Set.of();
        if (file.isFile() && file.length() <= 64) {
            try (BufferedReader input = Files.newBufferedReader(file.toPath())) {
                ids = readPilots(input);
            } catch (IOException ex) {
                Logger.printException(() -> "NativeOfflineImport: could not load pilot IDs", ex);
            }
        }
        pilots = ids;
        if (!ids.isEmpty()) Logger.printInfo(() -> "NativeOfflineImport: enabled for " + pilots);
        return ids;
    }

    static Set<String> readPilots(BufferedReader input) throws IOException {
        Set<String> ids = new HashSet<>(2);
        String line;
        while ((line = input.readLine()) != null) {
            if (!VIDEO_ID.matcher(line).matches() || ids.size() == 2 || !ids.add(line)) return Set.of();
        }
        return Set.copyOf(ids);
    }

    static boolean includesCacheKey(@Nullable String key) {
        return key != null && key.length() > 11 && key.charAt(11) == '.' && includes(key.substring(0, 11));
    }

    /** Implemented by the stock writer; cancellation stays tied to this transfer, not the current player. */
    public interface Cancellation {
        boolean rvxImportCancelled();
    }

    /** Implemented by the stock data source to keep its transport, credentials and Range semantics. */
    public interface Source {
        long rvxImportOpen(Object spec) throws IOException;
        int rvxImportRead(byte[] buffer, int offset, int length) throws IOException;
        Uri rvxImportUri();
        Map<String, List<String>> rvxImportHeaders();
        void rvxImportListen(Object listener);
        void rvxImportClose() throws IOException;
    }

    public static Transfer prepare(Source source, Cancellation cancellation, String key, long length,
                                   long lastModified, boolean encryptedCache) throws IOException {
        if (!encryptedCache || length <= 0 || lastModified <= 0 || !includesCacheKey(key)) {
            throw new IOException("NativeOfflineImport: not an identified, encrypted cache representation");
        }
        return new Transfer(source, cancellation, key, length);
    }

    static Transfer fromFile(File file, @Nullable Uri streamUri, Cancellation cancellation, String key, long length)
            throws IOException {
        if (!file.isFile() || length <= 0 || file.length() != length) {
            throw new IOException("NativeOfflineImport: incomplete fork representation");
        }
        return new Transfer(file, streamUri, cancellation, key, length);
    }

    static long rangeLength(long total, long position, long length) throws IOException {
        if (total <= 0 || position < 0 || position > total || length < -1 || length > total - position) {
            throw new IOException("NativeOfflineImport: requested range exceeds representation length");
        }
        return length == -1 ? total - position : length;
    }

    /** A writer-only upstream. It exposes either a completed fork file or a fully staged stock HTTP range. */
    public static final class Transfer {
        private final Source source;
        private final Cancellation cancellation;
        private final String cacheKey;
        private final long total;
        @Nullable private final File completed;
        @Nullable private final Uri streamUri;
        private long remaining;
        private RandomAccessFile staged;
        private Uri uri;
        private Map<String, List<String>> headers = Map.of();

        private Transfer(Source source, Cancellation cancellation, String cacheKey, long total) {
            this.source = source;
            this.completed = null;
            this.streamUri = null;
            this.cancellation = cancellation;
            this.cacheKey = cacheKey;
            this.total = total;
        }

        private Transfer(File completed, @Nullable Uri streamUri, Cancellation cancellation, String cacheKey, long total) {
            this.source = null;
            this.completed = completed;
            this.streamUri = streamUri;
            this.cancellation = cancellation;
            this.cacheKey = cacheKey;
            this.total = total;
        }

        public long open(Object spec, long position, long requested, String key) throws IOException {
            if (!cacheKey.equals(key) || staged != null) {
                throw new IOException("NativeOfflineImport: invalid writer request");
            }
            long length = rangeLength(total, position, requested);
            remaining = length;
            if (completed != null) {
                RandomAccessFile input = new RandomAccessFile(completed, "r");
                try {
                    if (input.length() != total) throw new IOException("NativeOfflineImport: fork file changed");
                    input.seek(position);
                    staged = input;
                    uri = streamUri;
                } finally {
                    if (staged == null) input.close();
                }
                return length;
            }
            File file = File.createTempFile("rvx-import-", ".part", Utils.getContext().getCacheDir());
            try {
                // The open descriptor owns the file. No plaintext part survives cancellation or process death.
                RandomAccessFile output = new RandomAccessFile(file, "rw");
                try {
                    Files.delete(file.toPath());
                    LocalDownloadManager.stageNativeRange(this, source, cancellation, spec, output, length,
                            requested == -1);
                    output.seek(0);
                    staged = output;
                } finally {
                    if (staged == null) output.close();
                }
            } finally {
                Files.deleteIfExists(file.toPath());
            }
            Logger.printInfo(() -> "NativeOfflineImport: staged " + cacheKey + " pos=" + position + " bytes=" + length);
            return length;
        }

        /** Snapshot the stock transport's response before the downloader closes it. */
        void received(Uri finalUri, Map<String, List<String>> responseHeaders) {
            uri = finalUri;
            headers = responseHeaders;
        }

        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (staged == null) throw new IOException("NativeOfflineImport: range is not open");
            if (length == 0) return 0;
            if (remaining == 0) return -1;
            if (cancellation.rvxImportCancelled()) throw new IOException("NativeOfflineImport: writer cancelled");
            int read = staged.read(buffer, offset, (int) Math.min(remaining, length));
            if (read < 0) throw new IOException("NativeOfflineImport: truncated writer source");
            remaining -= read;
            return read;
        }

        public Uri uri() {
            return uri;
        }

        public Map<String, List<String>> headers() {
            return headers;
        }

        public void listen(Object listener) {
            if (source != null) source.rvxImportListen(listener);
        }

        public void close() throws IOException {
            RandomAccessFile output = staged;
            staged = null;
            uri = null;
            headers = Map.of();
            if (output != null) output.close();
        }
    }
}
