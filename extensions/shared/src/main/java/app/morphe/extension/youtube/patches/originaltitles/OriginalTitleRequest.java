/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

// modified by lavinhoque33, 2026-10-17: fetches the DeArrow titles of 'Alternative thumbnails' as in Morphe's
// OriginalTitleRequest (DeArrow title wins when available, else the original title if restored).
// Kept the fork's oEmbed-only original title request and simpler retry.

package app.morphe.extension.youtube.patches.originaltitles;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.alternativethumbnails.AlternativeThumbnailsPatch;
import app.morphe.extension.youtube.patches.alternativethumbnails.AlternativeThumbnailsPatch.DeArrowTitlesAvailability;
import app.morphe.extension.youtube.patches.alternativethumbnails.DeArrowBrandingRequest;
import app.morphe.extension.youtube.patches.alternativethumbnails.DeArrowBrandingRequest.DeArrowTitle;

/**
 * Fetches the titles that replace the titles shown by YouTube.
 * <p>
 * Original titles are fetched from the public oEmbed endpoint, which always returns the title
 * as set by the uploader. DeArrow titles are fetched with {@link DeArrowBrandingRequest},
 * and if DeArrow has no title then the original title is used if original titles are restored.
 * Casual mode of DeArrow also needs the original title, to check if the casual votes are for
 * the current original title.
 * <p>
 * DeArrow titles can be used only for some navigations, such as only for the search results,
 * so the title that replaces the title is chosen when the title is shown.
 */
final class OriginalTitleRequest {

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 5000;

    /**
     * Time before a title that failed to fetch because of network errors is fetched again.
     * The titles fetched before are shown meanwhile, so a failing network does not keep loading.
     */
    private static final long FAILED_FETCH_RETRY_MILLISECONDS = 30_000;

    /**
     * Titles that can replace the title of a video.
     *
     * @param deArrowTitle  The DeArrow title, or null if the video has no DeArrow title,
     *                      DeArrow titles are not used, or it failed to fetch.
     * @param originalTitle The original title, or null if original titles are not restored,
     *                      the video has no available title such as a private video,
     *                      the DeArrow title is used for all navigations, or it failed to fetch.
     */
    record Titles(@Nullable String deArrowTitle, @Nullable String originalTitle) {
        /**
         * Can be called on any thread.
         *
         * @return The title that replaces the title of the video for the current navigation,
         *         or null if the title is not replaced.
         */
        @Nullable
        String replacement() {
            return replacement(deArrowTitle != null && AlternativeThumbnailsPatch.useDeArrowTitlesForCurrentNavigation());
        }

        /**
         * @param useDeArrow If the DeArrow title is used if the video has one.
         * @return The title that replaces the title of the video, or null if the title is not replaced.
         */
        @Nullable
        String replacement(boolean useDeArrow) {
            return useDeArrow && deArrowTitle != null ? deArrowTitle : originalTitle;
        }
    }

    /**
     * Video id -> titles.
     */
    private static final Map<String, CompletableFuture<Titles>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Video id -> time when the titles that failed to fetch can be fetched again.
     */
    private static final Map<String, Long> retryTimes =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    static CompletableFuture<Titles> fetch(String videoId) {
        synchronized (cache) {
            CompletableFuture<Titles> future = cache.get(videoId);
            if (future == null) {
                future = CompletableFuture.supplyAsync(() -> fetchTitles(videoId), Utils::runOnBackgroundThread);
                cache.put(videoId, future);
            } else if (future.isDone() && canRetry(videoId)) {
                retryTimes.remove(videoId);
                // The titles fetched before are kept until the titles are fetched again,
                // so the title is not shown as loading again.
                CompletableFuture<Titles> fetchedFuture = future;
                CompletableFuture.supplyAsync(() -> fetchTitles(videoId), Utils::runOnBackgroundThread)
                        .thenAccept(titles -> cache.replace(videoId, fetchedFuture, CompletableFuture.completedFuture(titles)));
            }
            return future;
        }
    }

    private static Titles fetchTitles(String videoId) {
        String deArrowTitle = null;
        String originalTitle = null;
        boolean originalFetched = false;
        if (RestoreOriginalTitlesPatch.USE_DEARROW) {
            try {
                DeArrowTitle deArrow = DeArrowBrandingRequest.fetchTitle(videoId);
                if (deArrow.needsOriginalTitle()) {
                    originalTitle = fetchOriginalTitle(videoId);
                    originalFetched = true;
                }
                deArrowTitle = deArrow.titleFor(originalTitle);
            } catch (DeArrowBrandingRequest.DeArrowException ex) {
                // The DeArrow title is fetched again later, and the original title is used meanwhile.
                retryTimes.put(videoId, System.currentTimeMillis() + FAILED_FETCH_RETRY_MILLISECONDS);
            }
        }
        // The original title is not needed if the DeArrow title is used for all navigations.
        final boolean fetchOriginal = RestoreOriginalTitlesPatch.RESTORE_ORIGINAL
                && (deArrowTitle == null || !DeArrowTitlesAvailability.usingDeArrowTitlesEverywhere());
        if (fetchOriginal && !originalFetched) {
            originalTitle = fetchOriginalTitle(videoId);
        }
        return new Titles(deArrowTitle, fetchOriginal ? originalTitle : null);
    }

    /**
     * Starts fetching the title if needed, and does not wait for it.
     *
     * @return The title that replaces the title of the video for the current navigation,
     *         or null if not yet available or the title is not replaced.
     */
    @Nullable
    static String getIfAvailable(String videoId) {
        Titles titles = fetch(videoId).getNow(null);
        return titles == null ? null : titles.replacement();
    }

    private static boolean canRetry(String videoId) {
        Long retryTime = retryTimes.get(videoId);
        return retryTime != null && System.currentTimeMillis() >= retryTime;
    }

    /**
     * @return If the titles are not yet fetched. Titles that failed to fetch
     *         are not pending, and the titles fetched before are used until they are fetched again.
     */
    static boolean isPending(String videoId) {
        CompletableFuture<Titles> future = cache.get(videoId);
        return future == null || !future.isDone();
    }

    /**
     * Calls the callback on the main thread when the titles are fetched.
     */
    static void getAsync(String videoId, Consumer<Titles> callback) {
        fetch(videoId).thenAccept(titles ->
                Utils.runOnMainThreadNowOrLater(() -> callback.accept(titles)));
    }

    @Nullable
    private static String fetchOriginalTitle(String videoId) {
        try {
            String url = "https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, StandardCharsets.UTF_8);

            HttpURLConnection connection = Requester.openConnection(url);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                String title = Requester.parseJSONObject(connection).optString("title");
                return title.isEmpty() ? null : title;
            }
            Logger.printDebug(() -> "oEmbed request failed for: " + videoId + " code: " + responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch original title of: " + videoId, ex);
            retryTimes.put(videoId, System.currentTimeMillis() + FAILED_FETCH_RETRY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchOriginalTitle failure", ex);
        }
        return null;
    }
}
