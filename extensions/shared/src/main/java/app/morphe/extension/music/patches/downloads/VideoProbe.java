/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.patches.misc.requests.MusicBrowseRequest;
import app.morphe.extension.music.patches.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * TEMPORARY diagnostic (round S44), REMOVED before the regular release: no setting, no UI, never on for a normal
 * user. It answers the open questions of the video copy design from a device and changes nothing in the app.
 *
 * <p>The marker file {@code files/rvx_probe_video} (UTF-8, one line each, at most {@link VideoProbeLogic#MAX_LINES}
 * used, blank and {@code #} lines ignored) switches it on; when the file is absent the probe does nothing. It is
 * read once per process, on a background thread, after the first command the app runs while the device is online.
 * The lines are
 * <ul>
 * <li>{@code player <videoId>}: asks every client the downloader tries, in its order, for the player response
 * through {@link StreamOrDetailsDataRequest#fetchRequestForDownload} (the downloader's own request), logs what each
 * client answered and the whole format table, and asks for the first byte of the best muxed, video-only and audio
 * formats with the downloader's own ranged request to learn whether the url downloads and how big the stream is;
 * <li>{@code fetch <videoId> <itag>}: stores that one format in {@code files/rvx_probe_video_out/} with the
 * downloader's ranged requests (the first such line of a run only) and logs its size, hash and container header;
 * <li>{@code next <playlistId> <videoId>}: sends Music's own {@code /next} for the video in the playlist, through
 * {@link MusicBrowseRequest} (the transport the playlist download's {@code browse} uses, with its auth headers), in
 * three request shapes, and logs the queue it lists.
 * </ul>
 * Every fact is one {@code VideoProbe:} log line; a failure is logged once with its class and message, and never
 * reaches the app.
 */
final class VideoProbe {
    private static final String TAG = "VideoProbe: ";
    private static final String MARKER_NAME = "rvx_probe_video";
    /** Not the marker's name: the marker is a file, so a directory cannot share it. */
    private static final String OUTPUT_NAME = "rvx_probe_video_out";

    private static final long MIN_FREE_BYTES = 200L * 1024 * 1024;
    private static final long MAX_FETCH_BYTES = 150L * 1024 * 1024;
    private static final long RANGE_BYTES = 8L * 1024 * 1024;
    private static final int MAX_RANGE_PROBES = 40;
    private static final long PAUSE_MILLIS = 400;

    private static final AtomicBoolean started = new AtomicBoolean();
    private static int rangeProbes;
    private static boolean fetched;

    private VideoProbe() {}

    /** Injection point (every command of the app, main thread): starts the probe once the device is online. */
    static void kick() {
        try {
            if (started.get() || !OfflineReplay.networkAvailable() || !started.compareAndSet(false, true)) return;

            Utils.runOnBackgroundThread(VideoProbe::run);
        } catch (Exception ex) {
            Logger.printWarn(() -> TAG + "could not start: " + describe(ex));
        }
    }

    private static void run() {
        try {
            File marker = new File(Utils.getContext().getFilesDir(), MARKER_NAME);
            if (!marker.isFile()) return;

            List<VideoProbeLogic.Line> lines = VideoProbeLogic.parseLines(
                    new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
            log("marker read, " + lines.size() + " lines");
            for (VideoProbeLogic.Line line : lines) {
                runLine(line);
                pause();
            }
            log("done");
        } catch (Exception ex) {
            Logger.printWarn(() -> TAG + "the run stopped: " + describe(ex));
        }
    }

    private static void runLine(VideoProbeLogic.Line line) {
        try {
            if (line instanceof VideoProbeLogic.PlayerLine player) {
                probePlayer(player.videoId());
            } else if (line instanceof VideoProbeLogic.FetchLine fetch) {
                probeFetch(fetch.videoId(), fetch.itag());
            } else if (line instanceof VideoProbeLogic.NextLine next) {
                probeNext(next.playlistId(), next.videoId());
            } else if (line instanceof VideoProbeLogic.InvalidLine invalid) {
                log("ignored line '" + invalid.text() + "': " + invalid.reason());
            }
        } catch (Exception ex) {
            log(line + " failed: " + describe(ex));
        }
    }

    private static void pause() throws InterruptedException {
        Thread.sleep(PAUSE_MILLIS);
    }

    private static void log(String text) {
        Logger.printInfo(() -> VideoProbeLogic.clip(TAG + text));
    }

    private static String describe(Exception ex) {
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    // ---- player ----

    /** What a client answered: its response, the formats of it as rows, and the client. */
    private record Answer(ClientType client, PlayerResponse response, List<VideoProbeLogic.FormatRow> rows) {}

    private static List<ClientType> clients() {
        return StreamOrDetailsDataRequest.buildClientOrder(SpoofVideoStreamsPatch.getAvailableClients(),
                Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }

    private static void probePlayer(String videoId) throws Exception {
        List<ClientType> clients = clients();
        log("player " + videoId + " clients=" + clients);
        for (ClientType client : clients) {
            Answer answer = ask(videoId, client, true);
            if (answer != null) probeRanges(videoId, answer);
            pause();
        }
    }

    /** One client's answer for a video, as the downloader asks for it; logs it when {@code verbose}. */
    @Nullable
    private static Answer ask(String videoId, ClientType client, boolean verbose) throws Exception {
        String prefix = "player " + videoId + " client=" + client + " ";
        long start = SystemClock.elapsedRealtime();
        StreamOrDetailsDataRequest.StreamData stream = StreamOrDetailsDataRequest
                .fetchRequestForDownload(videoId, List.of(client), client).getStream();
        long millis = SystemClock.elapsedRealtime() - start;
        if (stream == null) {
            String outcome = StreamOrDetailsDataRequest.downloadOutcome(videoId, client);
            if (verbose) {
                log(prefix + "no stream: " + (outcome == null ? "no outcome noted (timeout or exception)" : outcome)
                        + " ms=" + millis);
            }
            return null;
        }

        PlayerResponse response = PlayerResponse.parseFrom(stream.streamingData());
        List<VideoProbeLogic.FormatRow> rows = new ArrayList<>();
        for (Format format : response.getStreamingData().getFormatsList()) rows.add(rowOf("formats", format));
        for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
            rows.add(rowOf("adaptiveFormats", format));
        }

        if (verbose) logAnswer(prefix, response, rows, millis);
        return new Answer(client, response, rows);
    }

    private static void logAnswer(String prefix, PlayerResponse response, List<VideoProbeLogic.FormatRow> rows,
                                  long millis) {
        int plain = 0;
        for (VideoProbeLogic.FormatRow row : rows) if (row.hasUrl()) plain++;
        boolean sabrUrl = !response.getStreamingData().getServerAbrStreamingUrl().isEmpty();
        log(prefix + "ok ms=" + millis + " playability=" + response.getPlayabilityStatus().getStatus()
                + " formats=" + response.getStreamingData().getFormatsCount()
                + " adaptive=" + response.getStreamingData().getAdaptiveFormatsCount()
                + " withUrl=" + plain + " sabrUrl=" + (sabrUrl ? 'Y' : 'N')
                + " sabrOnly=" + (sabrUrl && plain == 0 ? 'Y' : 'N')
                + " expires=" + response.getStreamingData().getExpiresInSeconds()
                + " hls=" + (response.getStreamingData().getHlsManifestUrl().isEmpty() ? 'N' : 'Y'));
        log(prefix + "videoDetails lengthSeconds=" + response.getVideoDetails().getLengthSeconds()
                + " fields: " + VideoProbeLogic.scalarFields(response.getVideoDetails().toByteArray()));
        for (VideoProbeLogic.FormatRow row : rows) log(prefix + VideoProbeLogic.describe(row));
    }

    private static VideoProbeLogic.FormatRow rowOf(String list, Format format) {
        return new VideoProbeLogic.FormatRow(list, format.getItag(), format.getMimeType(), format.getQualityLabel(),
                format.getWidth(), format.getHeight(), format.getFps(), format.getBitrate(),
                format.getAverageBitrate(), format.getContentLength(), format.getLastModified(),
                format.getApproxDurationMs(), !format.getUrl().isEmpty(), !format.getSignatureCipher().isEmpty(),
                format.getInitRange().getStart(), format.getInitRange().getEnd(),
                format.getIndexRange().getStart(), format.getIndexRange().getEnd());
    }

    /** The url of the format a row describes. */
    @Nullable
    private static String urlOf(Answer answer, VideoProbeLogic.FormatRow row) {
        List<Format> list = row.list().equals("formats")
                ? answer.response().getStreamingData().getFormatsList()
                : answer.response().getStreamingData().getAdaptiveFormatsList();
        for (Format format : list) {
            if (format.getItag() == row.itag() && !format.getUrl().isEmpty()) return format.getUrl();
        }
        return null;
    }

    private static void probeRanges(String videoId, Answer answer) throws Exception {
        for (VideoProbeLogic.Target target : VideoProbeLogic.targetsOf(answer.rows())) {
            if (rangeProbes >= MAX_RANGE_PROBES) {
                log("ranged requests: the cap of " + MAX_RANGE_PROBES + " is reached");
                return;
            }

            String url = urlOf(answer, target.row());
            if (url == null) continue;

            rangeProbes++;
            probeRange(videoId, answer.client(), target, url);
            pause();
        }
    }

    /** Asks for the first byte of a stream and logs how the server answers. */
    private static void probeRange(String videoId, ClientType client, VideoProbeLogic.Target target, String url) {
        String prefix = "player " + videoId + " client=" + client + " range " + target.role().label
                + " itag=" + target.row().itag() + " host=" + VideoProbeLogic.hostOf(url) + " ";
        long start = SystemClock.elapsedRealtime();
        HttpURLConnection connection = null;
        try {
            connection = LocalDownloadManager.openAudioConnection(url, 0, 0);
            int status = connection.getResponseCode();
            long total = VideoProbeLogic.totalOf(connection.getHeaderField("Content-Range"));
            long listed = target.row().contentLength();
            log(prefix + "status=" + status + " total=" + total + " listed=" + listed
                    + (total > 0 && listed > 0 ? (total == listed ? " same" : " DIFFERENT") : "")
                    + " type=" + connection.getContentType()
                    + " ms=" + (SystemClock.elapsedRealtime() - start));
        } catch (Exception ex) {
            log(prefix + "failed: " + describe(ex) + " ms=" + (SystemClock.elapsedRealtime() - start));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ---- fetch ----

    private static void probeFetch(String videoId, int itag) throws Exception {
        String prefix = "fetch " + videoId + " itag=" + itag + " ";
        if (fetched) {
            log(prefix + "skipped: one file per run");
            return;
        }

        File directory = new File(Utils.getContext().getFilesDir(), OUTPUT_NAME);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cannot create " + OUTPUT_NAME);

        long free = directory.getUsableSpace();
        if (free < MIN_FREE_BYTES) {
            log(prefix + "skipped: only " + free + " bytes free, " + MIN_FREE_BYTES + " are needed");
            return;
        }

        for (ClientType client : clients()) {
            Answer answer = ask(videoId, client, false);
            VideoProbeLogic.FormatRow row = answer == null ? null : VideoProbeLogic.withItag(answer.rows(), itag);
            String url = row == null ? null : urlOf(answer, row);
            if (url == null) {
                pause();
                continue;
            }

            fetched = true;
            fetch(prefix + "client=" + client + " ", new File(directory, videoId + "-" + itag + ".bin"), url, row);
            return;
        }
        log(prefix + "no client offers this itag with a url");
    }

    private static void fetch(String prefix, File file, String url, VideoProbeLogic.FormatRow row) throws Exception {
        long total = row.contentLength() > 0 ? row.contentLength() : sizeOf(url);
        log(prefix + "start " + VideoProbeLogic.compactMime(row.mime()) + " total=" + total
                + " host=" + VideoProbeLogic.hostOf(url));
        if (total <= 0) throw new IOException("the size is unknown");
        if (total > MAX_FETCH_BYTES) {
            log(prefix + "skipped: " + total + " bytes is over the cap of " + MAX_FETCH_BYTES);
            return;
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long start = SystemClock.elapsedRealtime();
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            long position = 0;
            while (position < total) {
                long end = Math.min(total - 1, position + RANGE_BYTES - 1);
                position += copyRange(url, position, end, output, digest, buffer);
            }
        }

        long millis = SystemClock.elapsedRealtime() - start;
        log(prefix + "stored " + file.getName() + " bytes=" + file.length() + " sha256=" + hexOf(digest.digest())
                + " ms=" + millis + " mbit/s=" + VideoProbeLogic.throughput(file.length(), millis));
        logContainer(prefix, file, row);
    }

    /** The size of a stream, from the answer to a request of its first byte. */
    private static long sizeOf(String url) throws Exception {
        HttpURLConnection connection = LocalDownloadManager.openAudioConnection(url, 0, 0);
        try {
            return connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL
                    ? VideoProbeLogic.totalOf(connection.getHeaderField("Content-Range")) : -1;
        } finally {
            connection.disconnect();
        }
    }

    /** Copies bytes start..end of the stream to the file, and returns how many. */
    private static long copyRange(String url, long start, long end, FileOutputStream output, MessageDigest digest,
                                  byte[] buffer) throws Exception {
        long expected = end - start + 1;
        HttpURLConnection connection = LocalDownloadManager.openAudioConnection(url, start, end);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("bytes " + start + "-" + end + " answered HTTP " + status);
            }

            long copied = 0;
            try (InputStream input = connection.getInputStream()) {
                while (copied < expected) {
                    int read = input.read(buffer, 0, (int) Math.min(buffer.length, expected - copied));
                    if (read < 0) break;

                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    copied += read;
                }
            }
            if (copied != expected) throw new IOException("bytes " + start + "-" + end + " ended after " + copied);
            return copied;
        } finally {
            connection.disconnect();
        }
    }

    private static void logContainer(String prefix, File file, VideoProbeLogic.FormatRow row) throws IOException {
        try (RandomAccessFile access = new RandomAccessFile(file, "r")) {
            VideoProbeLogic.Reader reader = (position, buffer, length) -> {
                access.seek(position);
                return access.read(buffer, 0, length);
            };

            byte[] head = new byte[32];
            int read = reader.read(0, head, head.length);
            byte[] first = Arrays.copyOf(head, Math.max(read, 0));
            log(prefix + "first 32 bytes " + VideoProbeLogic.hex(first, 32));
            if (row.mime().contains("webm")) {
                log(prefix + "webm " + VideoProbeLogic.describeWebm(reader, file.length()));
            } else {
                log(prefix + "mp4 boxes " + VideoProbeLogic.describeBoxes(
                        VideoProbeLogic.mp4Boxes(reader, file.length())));
            }
        }
    }

    private static String hexOf(byte[] bytes) {
        return VideoProbeLogic.hex(bytes, bytes.length);
    }

    // ---- next ----

    private static void probeNext(String playlistId, String videoId) throws Exception {
        Context context = Utils.getContext();
        Integer stored = null;
        try {
            List<String> tracks = OfflineCopyStore.tracksBySource().get(playlistId);
            if (tracks != null) stored = tracks.size();
        } catch (Exception ex) {
            log("next stored tracks unknown: " + describe(ex));
        }
        log("next " + playlistId + " " + videoId + " storedTracks=" + (stored == null ? "unknown" : stored)
                + " transport=MusicBrowseRequest.nextWith (ANDROID_MUSIC context, the browse headers)");

        for (Variant variant : nextVariants(playlistId, videoId)) {
            probeNextVariant(context, "next " + playlistId + " " + variant.name() + " ", variant.fields());
            pause();
        }
    }

    /** One shape of the {@code /next} request. */
    private record Variant(String name, JSONObject fields) {}

    /** The shapes of the request: only what names the video, the app's usual flags, and those with the song type. */
    private static List<Variant> nextVariants(String playlistId, String videoId) throws Exception {
        JSONObject minimal = new JSONObject().put("videoId", videoId).put("playlistId", playlistId);
        JSONObject usual = new JSONObject().put("videoId", videoId).put("playlistId", playlistId)
                .put("isAudioOnly", true).put("enablePersistentPlaylistPanel", true)
                .put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL");
        JSONObject typed = new JSONObject(usual.toString()).put("watchEndpointMusicSupportedConfigs",
                new JSONObject().put("watchEndpointMusicConfig",
                        new JSONObject().put("musicVideoType", "MUSIC_VIDEO_TYPE_ATV")));
        return List.of(new Variant("minimal", minimal), new Variant("audio", usual), new Variant("audio+atv", typed));
    }

    private static void probeNextVariant(Context context, String prefix, JSONObject fields) {
        try {
            long start = SystemClock.elapsedRealtime();
            JSONObject response = MusicBrowseRequest.nextWith(context, fields);
            VideoProbeLogic.NextPage page = VideoProbeLogic.readNext(response, 0);
            log(prefix + "http=200 chars=" + response.toString().length() + " ms="
                    + (SystemClock.elapsedRealtime() - start) + " " + VideoProbeLogic.describe(page));
            for (VideoProbeLogic.NextItem item : page.items()) log(prefix + VideoProbeLogic.describe(item));

            if (!page.continuation().isEmpty()) continueNext(context, prefix, page);
        } catch (Exception ex) {
            log(prefix + "failed: " + describe(ex));
        }
    }

    /** One more request with the token of the first page: can the queue be paged, and how many items does it add? */
    private static void continueNext(Context context, String prefix, VideoProbeLogic.NextPage first) {
        try {
            long start = SystemClock.elapsedRealtime();
            JSONObject response = MusicBrowseRequest.nextWith(context,
                    new JSONObject().put("continuation", first.continuation()));
            VideoProbeLogic.NextPage page = VideoProbeLogic.readNext(response, first.items().size());
            log(prefix + "continuation http=200 chars=" + response.toString().length() + " ms="
                    + (SystemClock.elapsedRealtime() - start) + " " + VideoProbeLogic.describe(page));
            for (VideoProbeLogic.NextItem item : page.items()) {
                log(prefix + "continuation " + VideoProbeLogic.describe(item));
            }
        } catch (Exception ex) {
            log(prefix + "continuation failed: " + describe(ex));
        }
    }
}
