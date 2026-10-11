/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-10-10.
 */

package app.morphe.extension.youtube.patches.general.offline;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.app.Activity;
import android.app.Notification;
import android.content.Context;
import android.net.Uri;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Built-in video downloader: quality picker, chunked parallel download of the DASH video and
 * audio streams, MP4 muxing, and saving to {@code Movies/RVX}. Downloads run one at a time,
 * further downloads wait in a queue. The process is kept alive by the hooked
 * {@code OfflineKeepAliveService}.
 */
public final class OfflineDownloader {
    enum State {QUEUED, DOWNLOADING, MERGING, SAVING}

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final String THUMBNAIL_URL = "https://i.ytimg.com/vi/%s/hqdefault.jpg";

    private static final long CHUNK_SIZE = 8L * 1024 * 1024;
    private static final int PARALLEL_CONNECTIONS = 3;
    private static final int CHUNK_ATTEMPTS = 3;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final double REQUIRED_SPACE_FACTOR = 2.2;
    private static final long NOTIFICATION_INTERVAL_MS = 1000;

    /** A download in the queue or in progress. */
    static final class Task {
        final String videoId;
        final String title;
        final String qualityLabel;
        final AtomicLong done = new AtomicLong();
        volatile long total;
        volatile State state = State.QUEUED;
        volatile boolean cancelled;
        volatile boolean timedOut;
        @Nullable
        volatile Future<?> future;

        OfflineFormats.Resolved resolved;
        OfflineFormats.Stream video;

        Task(OfflineFormats.Resolved resolved, OfflineFormats.Stream video) {
            this.videoId = resolved.videoId();
            this.title = resolved.title();
            this.qualityLabel = qualityLabel(video);
            this.resolved = resolved;
            this.video = video;
        }

        State getState() {
            return state;
        }

        /** @return 0-100, or -1 if the size is not known yet. */
        int getPercent() {
            long totalBytes = total;
            if (totalBytes <= 0) return -1;
            return (int) Math.min(100, done.get() * 100 / totalBytes);
        }
    }

    /** HTTP 403: the stream url of this client is not accepted, another client may work. */
    private static final class ForbiddenException extends IOException {
        ForbiddenException() {
            super("HTTP 403");
        }
    }

    private static final Map<String, Task> tasks = new LinkedHashMap<>();
    private static final Set<String> resolving = ConcurrentHashMap.newKeySet();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static final AtomicLong lastNotification = new AtomicLong();
    private static final ExecutorService queue = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "rvx-offline-download");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private OfflineDownloader() {
    }

    // region entry point and picker

    /**
     * Entry point of every video download button while the built-in downloader is on.
     */
    public static void download(@Nullable String videoId) {
        Utils.runOnMainThread(() -> downloadOnMainThread(videoId));
    }

    private static void downloadOnMainThread(@Nullable String videoId) {
        if (videoId == null || !VIDEO_ID.matcher(videoId).matches()) {
            Logger.printInfo(() -> "Refusing to download an unusable video id: " + videoId);
            Utils.showToastShort(str("revanced_offline_download_unavailable"));
            return;
        }
        synchronized (tasks) {
            if (tasks.containsKey(videoId) || resolving.contains(videoId)) {
                Utils.showToastShort(str("revanced_offline_already_downloading"));
                return;
            }
        }
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing()) {
            Logger.printInfo(() -> "No activity to show the quality picker");
            return;
        }

        resolving.add(videoId);
        // Set once: either the user dismissed the sheet while loading, or the formats arrived.
        AtomicBoolean settled = new AtomicBoolean();
        OfflinePickerSheet sheet = new OfflinePickerSheet(activity, videoId, () -> {
            if (settled.compareAndSet(false, true)) resolving.remove(videoId);
        });
        sheet.show();

        Utils.runOnBackgroundThread(() -> {
            OfflineVideo existing = OfflineIndex.get(videoId);
            final boolean alreadySaved = existing != null
                    && OfflineMediaStore.exists(Utils.getContext(), existing.contentUri);
            final OfflineFormats.Resolved resolved = alreadySaved ? null : OfflineFormats.resolve(videoId, 0);
            Utils.runOnMainThread(() -> {
                if (!settled.compareAndSet(false, true)) return; // The user cancelled.
                resolving.remove(videoId);
                if (alreadySaved) {
                    sheet.dismiss();
                    Utils.showToastShort(str("revanced_offline_already_downloaded"));
                } else if (resolved == null) {
                    sheet.dismiss();
                    Utils.showToastLong(str("revanced_offline_no_formats"));
                } else if (activity.isFinishing()) {
                    sheet.dismiss();
                } else {
                    sheet.showFormats(resolved, video -> {
                        sheet.dismiss();
                        enqueue(resolved, video);
                    });
                }
            });
        });
    }

    static String qualityLabel(OfflineFormats.Stream video) {
        return video.height() + "p" + (video.fps() > 30 ? String.valueOf(video.fps()) : "");
    }

    private static void enqueue(OfflineFormats.Resolved resolved, OfflineFormats.Stream video) {
        Task task = new Task(resolved, video);
        synchronized (tasks) {
            if (tasks.containsKey(task.videoId)) {
                Utils.showToastShort(str("revanced_offline_already_downloading"));
                return;
            }
            tasks.put(task.videoId, task);
            task.future = queue.submit(() -> run(task));
        }
        Logger.printInfo(() -> "Queued " + task.videoId + " at " + task.qualityLabel + " " + video.codec()
                + " (itag " + video.itag() + ", audio itag " + resolved.audio().itag() + ")");
        Utils.showToastShort(str("revanced_offline_download_started"));
        // The user just picked a quality, so the app is in the foreground and may start the service.
        OfflineServiceHooks.startDownloadForeground();
        notifyChanged(true);
    }

    // endregion

    // region queue state

    static boolean hasWork() {
        synchronized (tasks) {
            return !tasks.isEmpty();
        }
    }

    static List<Task> getTasks() {
        synchronized (tasks) {
            return new ArrayList<>(tasks.values());
        }
    }

    static void cancel(String videoId) {
        Task task;
        synchronized (tasks) {
            task = tasks.get(videoId);
        }
        if (task == null) return;
        Logger.printInfo(() -> "Cancelling download " + videoId);
        task.cancelled = true;
        if (task.state == State.QUEUED) {
            Future<?> future = task.future;
            if (future != null) future.cancel(false);
            finish(task);
        }
    }

    static void cancelAll(boolean timedOut) {
        for (Task task : getTasks()) {
            task.timedOut = timedOut;
            cancel(task.videoId);
        }
    }

    static void addListener(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyChanged(boolean force) {
        if (!force) {
            long now = System.currentTimeMillis();
            long previous = lastNotification.get();
            if (now - previous < NOTIFICATION_INTERVAL_MS || !lastNotification.compareAndSet(previous, now)) {
                return;
            }
        }
        Utils.runOnMainThread(() -> {
            OfflineServiceHooks.updateDownloadNotification();
            for (Runnable listener : listeners) listener.run();
        });
    }

    static Notification buildProgressNotification(Context context) {
        Task current = null;
        int count;
        synchronized (tasks) {
            count = tasks.size();
            for (Task task : tasks.values()) {
                if (task.state != State.QUEUED) {
                    current = task;
                    break;
                }
            }
            if (current == null && !tasks.isEmpty()) current = tasks.values().iterator().next();
        }
        return OfflineNotifications.buildDownloadProgress(context, current, Math.max(0, count - 1));
    }

    private static void finish(Task task) {
        boolean empty;
        synchronized (tasks) {
            tasks.remove(task.videoId);
            empty = tasks.isEmpty();
        }
        Utils.runOnMainThread(() -> {
            if (empty) OfflineServiceHooks.stopDownloadForeground();
            else OfflineServiceHooks.updateDownloadNotification();
            for (Runnable listener : listeners) listener.run();
        });
    }

    // endregion

    // region download

    private static File workDirectory(Context context) {
        File base = context.getExternalCacheDir();
        if (base == null) base = context.getCacheDir();
        File directory = new File(base, "rvx_offline_tmp");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        return directory;
    }

    private static void run(Task task) {
        if (task.cancelled) return;
        final Context context = Utils.getContext();
        final File work = workDirectory(context);
        final File videoFile = new File(work, task.videoId + ".video");
        final File audioFile = new File(work, task.videoId + ".audio");
        final File merged = new File(work, task.videoId + ".mp4");
        try {
            OfflineFormats.Resolved resolved = task.resolved;
            OfflineFormats.Stream video = task.video;
            task.state = State.DOWNLOADING;
            notifyChanged(true);

            while (true) {
                try {
                    downloadStreams(task, resolved, video, work, videoFile, audioFile);
                    break;
                } catch (ForbiddenException ex) {
                    // The urls of this client are refused. Start over with the next client,
                    // so the two files never mix chunks of different clients.
                    int next = resolved.clientIndex() + 1;
                    OfflineFormats.Resolved other = next < OfflineFormats.clientCount()
                            ? OfflineFormats.resolve(task.videoId, next)
                            : null;
                    OfflineFormats.Stream match = null;
                    if (other != null) {
                        for (OfflineFormats.Stream candidate : other.videos()) {
                            if (candidate.key().equals(video.key())) {
                                match = candidate;
                                break;
                            }
                        }
                    }
                    if (match == null) throw ex;
                    final int clientIndex = other.clientIndex();
                    Logger.printInfo(() -> "HTTP 403 for " + task.videoId + ", retrying with client #" + clientIndex);
                    resolved = other;
                    video = match;
                    task.resolved = other;
                    task.video = match;
                }
            }

            checkCancelled(task);
            task.state = State.MERGING;
            notifyChanged(true);
            Logger.printInfo(() -> "Merging " + task.videoId);
            Mp4Merger.merge(videoFile, audioFile, merged, () -> task.cancelled);
            deleteQuietly(videoFile);
            deleteQuietly(audioFile);

            checkCancelled(task);
            task.state = State.SAVING;
            notifyChanged(true);
            final long size = merged.length();
            final String displayName = displayName(resolved.title(), task.videoId);
            Uri uri = OfflineMediaStore.save(context, merged, displayName);
            Logger.printInfo(() -> "Saved " + task.videoId + " to " + uri + " (" + size + " bytes)");

            String thumbnail = saveThumbnail(context, task.videoId, resolved.thumbnailUrl());
            OfflineIndex.put(new OfflineVideo(task.videoId, resolved.title(), resolved.author(),
                    resolved.channelId(), resolved.lengthMs(), uri.toString(), displayName, size,
                    task.qualityLabel, video.codec(), System.currentTimeMillis(), thumbnail, 0));

            OfflineNotifications.showDownloadFinished(context, task.videoId, task.title);
            Utils.showToastShort(str("revanced_offline_download_complete_toast", task.title));
        } catch (Throwable ex) {
            if (task.cancelled) {
                Logger.printInfo(() -> "Download cancelled: " + task.videoId);
                if (task.timedOut) {
                    OfflineNotifications.showDownloadFailed(context, task.videoId, task.title,
                            str("revanced_offline_error_time_limit"));
                }
            } else {
                Logger.printException(() -> "Download failed: " + task.videoId, ex);
                String reason = ex instanceof ForbiddenException
                        ? str("revanced_offline_error_forbidden")
                        : ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                OfflineNotifications.showDownloadFailed(context, task.videoId, task.title, reason);
                Utils.showToastLong(str("revanced_offline_download_failed", reason));
            }
        } finally {
            deleteQuietly(videoFile);
            deleteQuietly(audioFile);
            deleteQuietly(merged);
            finish(task);
        }
    }

    private static void downloadStreams(Task task, OfflineFormats.Resolved resolved, OfflineFormats.Stream video,
                                        File work, File videoFile, File audioFile) throws IOException {
        final String userAgent = resolved.userAgent();
        final OfflineFormats.Stream audio = resolved.audio();
        long videoLength = video.contentLength() > 0 ? video.contentLength() : probeLength(video.url(), userAgent);
        long audioLength = audio.contentLength() > 0 ? audio.contentLength() : probeLength(audio.url(), userAgent);
        task.done.set(0);
        task.total = Math.max(0, videoLength) + Math.max(0, audioLength);

        long required = (long) (task.total * REQUIRED_SPACE_FACTOR);
        if (task.total > 0 && work.getUsableSpace() < required) {
            throw new IOException(str("revanced_offline_error_space",
                    Formatter.formatShortFileSize(Utils.getContext(), required)));
        }

        deleteQuietly(videoFile);
        deleteQuietly(audioFile);
        Logger.printInfo(() -> "Downloading " + task.videoId + ": video itag " + video.itag()
                + " (" + videoLength + " bytes), audio itag " + audio.itag() + " (" + audioLength + " bytes)");
        downloadFile(task, video.url(), videoLength, videoFile, userAgent);
        downloadFile(task, audio.url(), audioLength, audioFile, userAgent);
    }

    private static void downloadFile(Task task, String url, long length, File file,
                                     @Nullable String userAgent) throws IOException {
        if (length <= 0) {
            downloadWhole(task, url, file, userAgent);
            return;
        }
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(length);
        }

        final int chunks = (int) ((length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL_CONNECTIONS, chunks));
        List<Future<?>> futures = new ArrayList<>(chunks);
        try {
            for (int i = 0; i < chunks; i++) {
                final long start = i * CHUNK_SIZE;
                final long end = Math.min(length, start + CHUNK_SIZE) - 1;
                futures.add(pool.submit(() -> {
                    downloadChunk(task, url, start, end, file, userAgent);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof IOException io) throw io;
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new IOException(cause);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void downloadChunk(Task task, String url, long start, long end, File file,
                                      @Nullable String userAgent) throws IOException {
        final long expected = end - start + 1;
        for (int attempt = 1; ; attempt++) {
            checkCancelled(task);
            long written = 0;
            HttpURLConnection connection = open(url, start, end, userAgent);
            try {
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_FORBIDDEN) throw new ForbiddenException();
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("Range request returned HTTP " + code);
                }
                try (InputStream input = connection.getInputStream();
                     RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                    output.seek(start);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    while (written < expected) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, expected - written));
                        if (read < 0) break;
                        output.write(buffer, 0, read);
                        written += read;
                        task.done.addAndGet(read);
                        notifyChanged(false);
                        checkCancelled(task);
                    }
                }
                if (written != expected) {
                    throw new IOException("Incomplete range " + start + "-" + end + ": " + written + "/" + expected);
                }
                return;
            } catch (ForbiddenException | CancellationException ex) {
                throw ex;
            } catch (IOException ex) {
                task.done.addAndGet(-written);
                if (attempt >= CHUNK_ATTEMPTS) throw ex;
                final int failedAttempt = attempt;
                Logger.printInfo(() -> "Chunk " + start + "-" + end + " failed (attempt " + failedAttempt + "), retrying", ex);
                try {
                    //noinspection BusyWait
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", interrupted);
                }
            } finally {
                connection.disconnect();
            }
        }
    }

    /** Fallback for streams whose size the server does not reveal. */
    private static void downloadWhole(Task task, String url, File file, @Nullable String userAgent) throws IOException {
        HttpURLConnection connection = open(url, -1, -1, userAgent);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_FORBIDDEN) throw new ForbiddenException();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    task.done.addAndGet(read);
                    notifyChanged(false);
                    checkCancelled(task);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static long probeLength(String url, @Nullable String userAgent) throws IOException {
        HttpURLConnection connection = open(url, 0, 0, userAgent);
        try {
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_FORBIDDEN) throw new ForbiddenException();
            String range = connection.getHeaderField("Content-Range");
            if (code == HttpURLConnection.HTTP_PARTIAL && range != null) {
                int slash = range.lastIndexOf('/');
                if (slash >= 0) {
                    try {
                        return Long.parseLong(range.substring(slash + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return -1;
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, long start, long end, @Nullable String userAgent) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (userAgent != null) connection.setRequestProperty("User-Agent", userAgent);
        if (start >= 0) connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        return connection;
    }

    private static void checkCancelled(Task task) {
        if (task.cancelled) throw new CancellationException();
    }

    // endregion

    // region files

    static String displayName(String title, String videoId) {
        String clean = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        if (clean.startsWith(".")) clean = "_" + clean.substring(1);
        if (clean.length() > 120) clean = clean.substring(0, 120).trim();
        if (clean.isEmpty()) clean = "video";
        return String.format(Locale.US, "%s [%s].mp4", clean, videoId);
    }

    @NonNull
    private static String saveThumbnail(Context context, String videoId, @Nullable String preferredUrl) {
        File destination = OfflineIndex.getThumbnailFile(context, videoId);
        String[] urls = {preferredUrl, String.format(Locale.US, THUMBNAIL_URL, videoId)};
        for (String url : urls) {
            if (url == null || url.isEmpty()) continue;
            HttpURLConnection connection = null;
            try {
                connection = open(url, -1, -1, null);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) continue;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                }
                return destination.getAbsolutePath();
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch thumbnail " + url, ex);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return "";
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            Logger.printDebug(() -> "Could not delete " + file);
        }
    }

    // endregion
}
