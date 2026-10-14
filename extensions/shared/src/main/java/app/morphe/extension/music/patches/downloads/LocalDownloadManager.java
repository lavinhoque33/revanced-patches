/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.utils.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailEntry;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest;

/** Downloads the already resolved InnerTube audio stream without leaving YouTube Music. */
public final class LocalDownloadManager {

    private static final String DOWNLOAD_CHANNEL = "morphe_audio_downloads";
    private static final String USER_AGENT =
            "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip";

    /**
     * Ranges are fetched in parallel, which is what makes a download finish in seconds.
     * Short files are still split into this many equal ranges.
     */
    private static final int SEGMENT_COUNT = 4;
    // modified by lavinhoque33, 2026-10-14: googlevideo throttles any single range response
    // above ~10 MiB (16 MiB ranges ran at ~3 MB/s), so ranges are bounded and handed out from a queue.
    private static final long MAX_RANGE_SIZE = 8L * 1024 * 1024;
    private static final int MAX_CONNECTIONS = 6;
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * The system silently drops notifications posted faster than a few per second, which used to
     * leave stale progress on screen after the download had finished. Updates are spaced out.
     */
    private static final long NOTIFICATION_INTERVAL_MILLISECONDS = 1000;
    private static final AtomicLong lastNotificationTime = new AtomicLong();

    private static final int CONNECT_TIMEOUT_MILLISECONDS = 15_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 30_000;

    /** A range that makes no progress this many times in a row is treated as stalled. */
    private static final int MAX_STALLED_ATTEMPTS = 4;

    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");

    private static final String THUMBNAIL_URL = "https://i.ytimg.com/vi/";

    private static final Set<String> ACTIVE_DOWNLOADS = ConcurrentHashMap.newKeySet();

    private static volatile String currentTitle;
    private static volatile String currentArtist;
    private static volatile String currentAlbum;
    private static volatile int currentDurationSeconds;
    private static volatile Bitmap currentArtwork;

    public static void onSetMetadata(android.media.MediaMetadata metadata) {
        if (metadata == null) return;
        currentTitle = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
        currentArtist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
        currentAlbum = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM);
        long dur = metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION);
        currentDurationSeconds = dur > 0 ? (int) (dur / 1000) : 0;
        Bitmap art = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        currentArtwork = art;
    }

    private LocalDownloadManager() {
    }

    /** Must be called from the main thread. Resolution and downloading happen in background. */
    public static void enqueue(String videoId) {
        // Every file of a download is named after the id, so anything that is not a plain id
        // is refused rather than written under a different name than it is looked up by.
        if (!VIDEO_ID.matcher(videoId).matches()) {
            Logger.printDebug(() -> "Refusing to download an unusable id: " + videoId);
            Utils.showToastShort(str("morphe_music_downloads_unknown_track"));
            return;
        }

        // The scrobbler describes whatever the player is on, so it may only be used for that
        // track. Any other track is described by the video details of its own player response.
        final boolean isPlayingTrack = videoId.equals(VideoInformation.getVideoId());
        String title = isPlayingTrack ? currentTitle : null;
        String artist = isPlayingTrack ? currentArtist : null;
        String album = isPlayingTrack ? currentAlbum : null;
        int duration = isPlayingTrack ? currentDurationSeconds : 0;
        Bitmap artwork = isPlayingTrack ? currentArtwork : null;

        Utils.submitOnBackgroundThread(() -> {
            downloadBlocking(videoId, title, artist, album, duration, artwork);
            return null;
        });
    }

    private static void downloadBlocking(String videoId, String title, String artist, String album,
                                         int duration, Bitmap artwork) {
        if (!ACTIVE_DOWNLOADS.add(videoId)) {
            Utils.showToastShort(str("morphe_music_downloads_already_running"));
            return;
        }

        try {
            Context context = Utils.getContext();
            File directory = new File(
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "RVX");

            if (new File(directory, videoId + ".webm").isFile()
                    || new File(directory, videoId + ".m4a").isFile()) {
                Logger.printDebug(() -> "Already downloaded: " + videoId);
                Utils.showToastShort(str("morphe_music_downloads_already_saved"));
                return;
            }

            // modified by lavinhoque33, 2026-10-14: googlevideo can hand a client urls that refuse every
            // range past the first ~1 MB with HTTP 403 (seen with VISIONOS), so a refused stream is
            // replaced by the next client's instead of failing the whole download.
            StreamCandidates candidates = new StreamCandidates(videoId);
            ResolvedStream resolved = candidates.next();
            if (resolved == null) {
                throw new IllegalStateException("Audio stream unavailable");
            }

            VideoDetails details = resolved.details();

            // Anything the caller already knows wins, the player response only fills the blanks.
            title = firstNotBlank(title, details.getTitle(), videoId);
            artist = firstNotBlank(artist, stripTopic(details.getOwnerChannelName()), "");
            if (duration <= 0) duration = details.getLengthSeconds();
            if (artwork == null) artwork = resolveArtwork(videoId, details);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create download directory");
            }

            Utils.showToastShort(str("morphe_music_downloads_started"));
            awaitNotificationSlot();
            showDownloadNotification(videoId, title, 0, true, str("morphe_music_downloads_starting"));

            File downloaded;
            File downloadedTemporary;
            while (true) {
                Format format = resolved.format();
                Logger.printDebug(() -> "Downloading " + videoId + " as itag " + format.getItag()
                        + " (" + format.getMimeType() + ")");

                String extension = format.getMimeType().contains("mp4") ? ".m4a" : ".webm";
                downloaded = new File(directory, videoId + extension);
                downloadedTemporary = new File(directory, downloaded.getName() + ".part");

                if (downloadedTemporary.exists() && !downloadedTemporary.delete()) {
                    throw new IllegalStateException("Could not reset partial audio file");
                }

                try {
                    downloadWithResume(videoId, title, format.getUrl(), downloadedTemporary,
                            format.getContentLength());
                    break;
                } catch (ForbiddenException ex) {
                    Logger.printDebug(() -> "Stream refused, trying another client: " + videoId, ex);
                    resolved = candidates.next();
                    if (resolved == null) throw ex;
                }
            }
            final File destination = downloaded;
            final File temporary = downloadedTemporary;

            if (destination.exists() && !destination.delete()) {
                throw new IllegalStateException("Could not replace audio file");
            }
            if (!temporary.renameTo(destination)) {
                throw new IllegalStateException("Could not finish audio file");
            }
            Logger.printDebug(() -> "Saved " + destination + " (" + destination.length() + " bytes)");

            OfflineTrack.save(directory, videoId, title, artist, album, duration, artwork);
            Utils.showToastShort(str("morphe_music_downloads_complete"));
        } catch (Exception ex) {
            Logger.printException(() -> "Local audio download failed: " + videoId, ex);
            awaitNotificationSlot();
            showDownloadNotification(videoId, title, 0, false, str("morphe_music_downloads_failed"));
            Utils.showToastShort(str("morphe_music_downloads_failed"));
        } finally {
            ACTIVE_DOWNLOADS.remove(videoId);
        }
    }

    private record ResolvedStream(Format format, VideoDetails details) {
    }

    /** The server refused the stream's url itself, so another client's url may still work. */
    private static final class ForbiddenException extends IllegalStateException {
        ForbiddenException(String message) {
            super(message);
        }
    }

    /**
     * Hands out the audio streams a download may try, one resolved client at a time and in
     * playback order, so a refused stream costs only the next client's request.
     */
    private static final class StreamCandidates {
        private final String videoId;
        private final Iterator<ClientType> clients;
        @Nullable
        private StreamOrDetailsDataRequest cached;

        StreamCandidates(String videoId) {
            this.videoId = videoId;
            this.cached = StreamOrDetailsDataRequest.getRequestForVideoId(videoId);
            if (cached == null) {
                Logger.printDebug(() -> "No cached streaming request for download, resolving: " + videoId);
            }
            // The clients are named here because the playback order is only set up while spoofing
            // is on, and a download needs a client that answers with plain urls either way.
            this.clients = StreamOrDetailsDataRequest.buildClientOrder(
                    SpoofVideoStreamsPatch.getAvailableClients(),
                    Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get()).iterator();
        }

        /** @return The next stream with an audio url, or null once every client has been tried. */
        @Nullable
        ResolvedStream next() {
            while (true) {
                StreamOrDetailsDataRequest request = cached;
                if (request != null) {
                    cached = null;
                } else if (clients.hasNext()) {
                    ClientType client = clients.next();
                    request = StreamOrDetailsDataRequest.fetchRequestForDownload(
                            videoId, List.of(client), client);
                } else {
                    return null;
                }

                ResolvedStream stream = parseStream(videoId, request);
                if (stream != null) return stream;
            }
        }
    }

    private static String firstNotBlank(@Nullable String value, String fallback, String lastResort) {
        if (value != null && !value.isBlank()) return value;
        if (!fallback.isBlank()) return fallback;
        return lastResort;
    }

    /** Music uploads are owned by an "Artist - Topic" channel, which is not the artist name. */
    private static String stripTopic(String channelName) {
        return channelName.endsWith(" - Topic")
                ? channelName.substring(0, channelName.length() - " - Topic".length())
                : channelName;
    }

    /** @return Whether a progress update may be posted without the system dropping it. */
    private static boolean canPostProgress() {
        long now = System.currentTimeMillis();
        long previous = lastNotificationTime.get();
        return now - previous >= NOTIFICATION_INTERVAL_MILLISECONDS
                && lastNotificationTime.compareAndSet(previous, now);
    }

    /**
     * Waits for the next slot, so a notification that matters is never the one being dropped.
     * The loop sleeps exactly as long as the slot is away, and repeats only if another thread
     * took the slot first. Must be called off the main thread.
     */
    private static void awaitNotificationSlot() {
        while (true) {
            long now = System.currentTimeMillis();
            long previous = lastNotificationTime.get();
            long remaining = NOTIFICATION_INTERVAL_MILLISECONDS - (now - previous);

            if (remaining <= 0) {
                if (lastNotificationTime.compareAndSet(previous, now)) return;
                continue;
            }

            try {
                //noinspection BusyWait
                Thread.sleep(remaining);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Some videos come back without any video details, so the thumbnail of the id is used as a
     * fallback. It exists for every video, while the largest size does not.
     */
    @Nullable
    private static Bitmap resolveArtwork(String videoId, VideoDetails details) {
        Bitmap artwork = downloadArtwork(largestThumbnail(details));
        if (artwork != null) return artwork;

        artwork = downloadArtwork(THUMBNAIL_URL + videoId + "/maxresdefault.jpg");
        return artwork != null ? artwork : downloadArtwork(THUMBNAIL_URL + videoId + "/hqdefault.jpg");
    }

    @Nullable
    private static String largestThumbnail(VideoDetails details) {
        String url = null;
        int widest = 0;
        for (ThumbnailEntry entry : details.getThumbnail().getThumbnailsList()) {
            if (entry.getWidth() >= widest && !entry.getUrl().isBlank()) {
                widest = entry.getWidth();
                url = entry.getUrl();
            }
        }
        return url;
    }

    @Nullable
    private static Bitmap downloadArtwork(@Nullable String url) {
        if (url == null || url.isBlank()) return null;
        try (InputStream input = Requester.openConnection(url).getInputStream()) {
            return BitmapFactory.decodeStream(input);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch artwork: " + url, ex);
            return null;
        }
    }

    private static void downloadWithResume(String videoId, String title, String url,
                                           File temporary, long expectedLength) throws Exception {
        if (expectedLength <= 0) expectedLength = probeContentLength(url);
        final long totalLength = expectedLength;

        if (totalLength <= 0) {
            downloadUnknownLength(url, temporary);
        } else {
            downloadInParallel(videoId, title, url, temporary, totalLength);
        }

        if (totalLength > 0 && temporary.length() != totalLength) {
            throw new IllegalStateException(
                    "Incomplete audio file: " + temporary.length() + "/" + totalLength);
        }
        awaitNotificationSlot();
        showDownloadNotification(videoId, title, 100, false, str("morphe_music_downloads_complete"));
    }

    // modified by lavinhoque33, 2026-10-14: bounded ranges are handed out from a shared queue to a
    // capped worker pool; the first failure stops every connection immediately.
    private static void downloadInParallel(String videoId, String title, String url,
                                           File temporary, long totalLength) throws Exception {
        try (RandomAccessFile file = new RandomAccessFile(temporary, "rw")) {
            file.setLength(totalLength);
        }

        AtomicLong downloaded = new AtomicLong();
        ProgressCallback progress = bytes -> {
            if (!canPostProgress()) return;
            int percent = (int) Math.min(99, bytes * 100 / totalLength);
            showDownloadNotification(videoId, title, percent, true,
                    str("morphe_music_downloads_progress", percent));
        };

        // Short files keep the original equal split, longer ones use ranges below the throttle.
        long rangeSize = Math.min(MAX_RANGE_SIZE, (totalLength + SEGMENT_COUNT - 1) / SEGMENT_COUNT);
        long rangeCount = (totalLength + rangeSize - 1) / rangeSize;
        int workers = (int) Math.min(MAX_CONNECTIONS, rangeCount);
        RangeQueue queue = new RangeQueue(url, temporary, totalLength, rangeSize, downloaded, progress);

        // Android's ExecutorService is not AutoCloseable, so it is shut down in the finally below.
        //noinspection resource
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        ExecutorCompletionService<Void> completion = new ExecutorCompletionService<>(executor);

        try {
            for (int index = 0; index < workers; index++) {
                completion.submit(queue::work);
            }

            for (int index = 0; index < workers; index++) {
                try {
                    completion.take().get();
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof Exception cause) throw cause;
                    throw ex;
                }
            }
        } finally {
            // No worker may still write to the file once this returns.
            queue.stop();
            executor.shutdownNow();
            boolean interrupted = false;
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private interface ProgressCallback {
        void update(long bytes);
    }

    /** Hands out consecutive ranges of one file to the workers that download them. */
    private static final class RangeQueue {
        private final String url;
        private final File destination;
        private final long totalLength;
        private final long rangeSize;
        private final AtomicLong downloaded;
        private final ProgressCallback progress;
        private final AtomicLong nextStart = new AtomicLong();
        private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
        private volatile boolean stopped;

        RangeQueue(String url, File destination, long totalLength, long rangeSize,
                   AtomicLong downloaded, ProgressCallback progress) {
            this.url = url;
            this.destination = destination;
            this.totalLength = totalLength;
            this.rangeSize = rangeSize;
            this.downloaded = downloaded;
            this.progress = progress;
        }

        /** Aborts every connection, so blocked reads fail at once instead of timing out. */
        void stop() {
            stopped = true;
            for (HttpURLConnection connection : connections) connection.disconnect();
        }

        Void work() throws Exception {
            byte[] buffer = new byte[BUFFER_SIZE];
            try (RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
                while (true) {
                    long start = nextStart.getAndAdd(rangeSize);
                    if (start >= totalLength) return null;

                    downloadRange(start, Math.min(totalLength - 1, start + rangeSize - 1),
                            output, buffer);
                }
            }
        }

        private void checkStopped() {
            if (stopped) throw new CancellationException("Download stopped");
        }

        private void downloadRange(long start, long end, RandomAccessFile output,
                                   byte[] buffer) throws Exception {
            long position = start;
            int failures = 0;

            while (position <= end) {
                checkStopped();
                long before = position;
                HttpURLConnection connection = openAudioConnection(url, position, end);
                boolean reusable = false;
                connections.add(connection);

                try {
                    checkStopped();
                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                        throw new ForbiddenException("Range request returned HTTP 403");
                    }
                    if (responseCode != HttpURLConnection.HTTP_PARTIAL) {
                        throw new IllegalStateException("Range request returned HTTP " + responseCode);
                    }

                    try (InputStream input = connection.getInputStream()) {
                        output.seek(position);

                        while (position <= end) {
                            int read = readBuffer(input, buffer,
                                    (int) Math.min(buffer.length, end - position + 1));
                            if (read == 0) break;

                            output.write(buffer, 0, read);
                            position += read;
                            progress.update(downloaded.addAndGet(read));
                        }

                        // Reading up to the end lets Android keep the connection for the next range.
                        reusable = position > end && input.read() < 0;
                    }
                } catch (IOException ex) {
                    checkStopped();
                    Logger.printDebug(() -> "Audio range interrupted; retrying", ex);
                } finally {
                    connections.remove(connection);
                    if (!reusable) connection.disconnect();
                }

                failures = position > before ? 0 : failures + 1;
                if (failures >= MAX_STALLED_ATTEMPTS) {
                    throw new IllegalStateException("Download range stalled at " + position);
                }
            }
        }

        // modified by lavinhoque33, 2026-10-14: Android returns small socket reads, so fill the
        // buffer before each file write instead of writing every few KiB.
        private int readBuffer(InputStream input, byte[] buffer, int length) throws IOException {
            int filled = 0;
            while (filled < length) {
                checkStopped();
                int read = input.read(buffer, filled, length - filled);
                if (read < 0) break;
                filled += read;
            }
            return filled;
        }
    }

    private static long probeContentLength(String url) {
        HttpURLConnection connection = null;
        try {
            connection = openAudioConnection(url, 0, 0);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;

            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange == null) return -1;

            int slash = contentRange.lastIndexOf('/');
            return slash < 0 ? -1 : Long.parseLong(contentRange.substring(slash + 1));
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not probe audio length", ex);
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openAudioConnection(String url, long start, long end) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://music.youtube.com/");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        return connection;
    }

    private static void downloadUnknownLength(String url, File destination) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", USER_AGENT);

        try {
            if (connection.getResponseCode() == HttpURLConnection.HTTP_FORBIDDEN) {
                throw new ForbiddenException("HTTP 403");
            }
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Segments report progress from their own threads, and {@code notify} is a oneway binder
     * call whose order only holds per sending thread. Posting every update from the main thread
     * stops late progress from replacing the completed state on screen.
     */
    static void showDownloadNotification(String key, String title, int progress,
                                         boolean ongoing, String text) {
        Utils.runOnMainThread(() -> postDownloadNotification(key, title, progress, ongoing, text));
    }

    @SuppressLint("NotificationPermission") // The patch declares POST_NOTIFICATIONS.
    private static void postDownloadNotification(String key, String title, int progress,
                                                 boolean ongoing, String text) {
        Context context = Utils.getContext();
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        manager.createNotificationChannel(new NotificationChannel(DOWNLOAD_CHANNEL,
                str("morphe_music_downloads_notification_channel"),
                NotificationManager.IMPORTANCE_LOW));

        Notification.Builder builder = new Notification.Builder(context, DOWNLOAD_CHANNEL);
        builder.setSmallIcon(ongoing
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title == null || title.isBlank() ? "YouTube Music" : title)
                .setContentText(text)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true);

        if (ongoing) builder.setProgress(100, Math.max(0, progress), progress < 0);
        manager.notify(key.hashCode(), builder.build());
    }

    @Nullable
    private static ResolvedStream parseStream(String videoId,
                                              StreamOrDetailsDataRequest request) {
        StreamOrDetailsDataRequest.StreamData stream = request.getStream();
        if (stream == null) {
            Logger.printDebug(() -> "No stream data resolved for " + videoId);
            return null;
        }

        try {
            PlayerResponse response = PlayerResponse.parseFrom(stream.streamingData());
            Format best = response.getStreamingData()
                    .getAdaptiveFormatsList()
                    .stream()
                    .filter(format -> format.getMimeType().startsWith("audio/")
                            && !format.getUrl().isBlank())
                    .max(Comparator.comparingInt(Format::getBitrate))
                    .orElse(null);
            return best == null ? null : new ResolvedStream(best, response.getVideoDetails());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not parse audio formats: " + videoId, ex);
            return null;
        }
    }
}
