/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-18.
 * Modified by lavinhoque33, 2026-11-17.
 * Written by lavinhoque33, 2026-11-08.
 * Modified by lavinhoque33, 2026-11-10.
 */
package app.morphe.extension.music.patches.downloads;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.patches.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.requests.StreamOrDetailsDataRequest;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/**
 * Fills in the stored data of the stream copies that were downloaded before the offline replay existed, while the
 * device is online: one copy at a time, with a pause between two, so nothing the user plays is slowed down. The
 * response comes from the request the downloader itself uses, is checked against the copy exactly as the stream
 * swap checks a played one, and is stored the way an online play stores it. A run starts from the app's own
 * navigation, stops as soon as the setting is off or the network is gone, and the next run goes on with what is
 * still missing, so nothing has to be remembered between launches. A copy that fails is tried again after a wait
 * that grows, and is left for the next launch after {@link OfflineBackfillLogic#MAX_FAILURES} failures. The same
 * run gives a downloaded album that has no album page of its own the page it was downloaded from, first of all:
 * a tap on it in the Library's Downloads chip waits for that page.
 */
final class OfflineBackfill {
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final OfflineBackfillLogic.Schedule schedule = new OfflineBackfillLogic.Schedule();
    private static final OfflineBackfillLogic.Schedule headerSchedule = new OfflineBackfillLogic.Schedule();
    private static final OfflineBackfillLogic.Schedule albumSchedule = new OfflineBackfillLogic.Schedule();
    private static volatile long idleUntil;

    private enum Task { ALBUM_PAGE, MEDIA, HEADER }

    private record Step(Task task, String id) {}

    private OfflineBackfill() {}

    /** Starts a run when none is going and the setting and the network allow it. Returns at once. */
    static void kick() {
        if (OfflineBackfillLogic.blockedReason(OfflineReplay.enabled(), Utils.isInternetReachable()) != null) {
            return;
        }
        if (!OfflineBackfillLogic.mayStartAgain(idleUntil, SystemClock.elapsedRealtime())) return;
        if (!running.compareAndSet(false, true)) return;

        Utils.runOnBackgroundThread(OfflineBackfill::run);
    }

    /** {@link #kick} without the quiet period the last run asked for: the page of the downloads opened. */
    static void kickNow() {
        idleUntil = 0;
        kick();
    }

    private static void run() {
        int stored = 0;
        int failed = 0;
        try {
            while (true) {
                String blocked = OfflineBackfillLogic.blockedReason(OfflineReplay.enabled(),
                        Utils.isInternetReachable());
                if (blocked != null) {
                    Logger.printInfo(() -> "Backfill: paused, " + blocked);
                    break;
                }

                Step step = nextStep(SystemClock.elapsedRealtime());
                if (step == null) {
                    idleUntil = SystemClock.elapsedRealtime() + OfflineBackfillLogic.IDLE_MILLIS;
                    break;
                }

                String failure = perform(step);
                OfflineBackfillLogic.Schedule scheduled = scheduleOf(step.task());
                if (failure == null) {
                    scheduled.succeeded(step.id());
                    if (step.task() == Task.MEDIA) stored++;
                } else if (Utils.isInternetReachable()) {
                    scheduled.failed(step.id(), SystemClock.elapsedRealtime());
                    failed++;
                }
                Thread.sleep(OfflineBackfillLogic.PAUSE_BETWEEN_MILLIS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            Logger.printException(() -> "Backfill: the run stopped", ex);
        } finally {
            running.set(false);
        }

        if (stored > 0 || failed > 0) {
            int storedNow = stored;
            int failedNow = failed;
            Logger.printInfo(() -> "Backfill: summary stored=" + storedNow + " failed=" + failedNow
                    + " remaining=" + OfflineCopyStore.idsWithoutData().size());
        }
    }

    /** The next thing that is due: an album page before a copy, a copy before a title. Null when nothing is. */
    @Nullable
    private static Step nextStep(long now) {
        String album = albumSchedule.next(albumsWithoutPage(), now);
        if (album != null) return new Step(Task.ALBUM_PAGE, album);

        String copy = schedule.next(OfflineCopyStore.idsWithoutData(), now);
        if (copy != null) return new Step(Task.MEDIA, copy);

        String source = headerSchedule.next(OfflineCopyStore.sourceIdsWithoutTitle(), now);
        return source == null ? null : new Step(Task.HEADER, source);
    }

    private static OfflineBackfillLogic.Schedule scheduleOf(Task task) {
        return switch (task) {
            case ALBUM_PAGE -> albumSchedule;
            case MEDIA -> schedule;
            case HEADER -> headerSchedule;
        };
    }

    @Nullable
    private static String perform(Step step) {
        return switch (step.task()) {
            case ALBUM_PAGE -> backfillAlbumPage(step.id());
            case MEDIA -> backfill(step.id());
            case HEADER -> backfillHeader(step.id());
        };
    }

    /** The downloaded albums that have a stream copy and no genuine album page saved. */
    private static List<String> albumsWithoutPage() {
        List<String> owned = new ArrayList<>();
        for (OfflineSourceStore.Source source : OfflineCopyStore.sources()) {
            if (OfflineCopyStore.ownsStreamSource(source)) owned.add(source.id());
        }
        return OfflineBackfillLogic.albumsWithoutPage(owned, OfflineBrowsePages::hasAlbumPage);
    }

    /** @return null once the album has its page, else why it has none. Logs the outcome. */
    @Nullable
    private static String backfillAlbumPage(String sourceId) {
        try {
            String page = AlbumPageResolver.resolveAndSave(sourceId);
            if (page == null) throw new IllegalStateException("no album page of the album was proved and saved");

            Logger.printInfo(() -> "AlbumBackfill: " + sourceId + " page=" + page);
            DownloadsPage.onCopiesChanged();
            return null;
        } catch (Exception ex) {
            String failure = ex.getClass().getSimpleName() + " " + ex.getMessage();
            Logger.printInfo(() -> "AlbumBackfill: " + sourceId + " failed:" + failure);
            return failure;
        }
    }

    /** @return null once the playlist or album has its title, else why it has none. Logs the outcome. */
    @Nullable
    private static String backfillHeader(String sourceId) {
        try {
            String title = OfflineCopyStore.fillHeader(sourceId);
            Logger.printInfo(() -> "Backfill: source " + sourceId + " title=" + title);
            return null;
        } catch (Exception ex) {
            String failure = ex.getClass().getSimpleName() + " " + ex.getMessage();
            Logger.printInfo(() -> "Backfill: source " + sourceId + " failed:" + failure);
            return failure;
        }
    }

    /** @return null once the copy has stored data, else why it has none. Logs the outcome. */
    @Nullable
    private static String backfill(String videoId) {
        String failure = fetchAndStore(videoId);
        if (failure != null) Logger.printInfo(() -> "Backfill: " + videoId + " failed:" + failure);
        return failure;
    }

    @Nullable
    static String fetchAndStore(String videoId) {
        return fetchAndStore(videoId, false);
    }

    @Nullable
    static String fetchAndStore(String videoId, boolean userDownload) {
        try {
            RawAudioFiles.Entry raw = RawAudioStore.find(videoId);
            if (raw == null) return "no copy";

            String failure = "no client answered";
            for (ClientType client : StreamOrDetailsDataRequest.buildClientOrder(
                    SpoofVideoStreamsPatch.getAvailableClients(), Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get())) {
                StreamOrDetailsDataRequest request = StreamOrDetailsDataRequest.fetchRequestForDownload(
                        videoId, List.of(client), client);
                StreamOrDetailsDataRequest.StreamData stream = request.getStream();
                if (stream == null) continue;

                PlayerResponse response = PlayerResponse.parseFrom(stream.streamingData());
                Format selected = null;
                for (Format format : response.getStreamingData().getAdaptiveFormatsList()) {
                    if (format.getItag() == raw.itag()) {
                        selected = format;
                        break;
                    }
                }
                if (selected == null) {
                    failure = "itag " + raw.itag() + " missing";
                    continue;
                }

                String mismatch = StreamSwapLogic.mismatch(raw, selected, response);
                if (mismatch != null) {
                    failure = mismatch;
                    continue;
                }

                // The setting may have been turned off while the request was out.
                if (!userDownload && !OfflineReplay.enabled()) return "setting off";

                OfflineCopyStore.Outcome outcome = PlaylistDownloads.publishMedia(() ->
                        OfflineCopyStore.storeBackfilled(videoId, stream.streamingData(), raw.itag()));
                if (outcome.written() == null) return outcome.reason();

                int bytes = outcome.written().responseBytes();
                Logger.printInfo(() -> "Backfill: " + videoId + " stored=" + bytes);
                return null;
            }
            return failure;
        } catch (Exception ex) {
            return ex.getClass().getSimpleName() + " " + ex.getMessage();
        }
    }
}
