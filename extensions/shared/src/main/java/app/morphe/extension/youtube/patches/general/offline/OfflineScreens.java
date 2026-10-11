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

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Hosts the offline library and player screens in the hijacked {@code LicenseActivity}, the
 * same activity the RVX settings use. The screen is chosen by the intent data string.
 */
@SuppressWarnings("unused")
public final class OfflineScreens {
    public static final String LIBRARY_INTENT = "rvx_offline_library_intent";
    public static final String PLAYER_INTENT = "rvx_offline_player_intent";
    static final String EXTRA_VIDEO_ID = "rvx_offline_video_id";

    private static final String LICENSE_ACTIVITY = "com.google.android.libraries.social.licenses.LicenseActivity";
    private static final String DOWNLOADS_BROWSE_ID = "FEdownloads";

    static final String SORT_NEWEST = "NEWEST";
    static final String SORT_OLDEST = "OLDEST";
    static final String SORT_TITLE = "TITLE";
    static final String SORT_SIZE = "SIZE";

    /** A screen living in one LicenseActivity instance. */
    interface Screen {
        /** @return True if the back press was handled and the activity must stay. */
        boolean onBackPressed();

        void onResume();

        void onPause();

        void onDestroy();
    }

    private static final WeakHashMap<Activity, Screen> screens = new WeakHashMap<>();
    private static boolean lifecycleCallbacksRegistered;

    private OfflineScreens() {
    }

    // region intents

    static Intent libraryIntent(Context context) {
        return hostIntent(context, LIBRARY_INTENT);
    }

    static Intent playerIntent(Context context, @Nullable String videoId) {
        Intent intent = hostIntent(context, PLAYER_INTENT);
        if (videoId != null) intent.putExtra(EXTRA_VIDEO_ID, videoId);
        return intent;
    }

    private static Intent hostIntent(Context context, String key) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setData(Uri.parse(key));
        intent.setClassName(context.getPackageName(), LICENSE_ACTIVITY);
        return intent;
    }

    private static void start(@Nullable Context context, Intent intent) {
        if (context == null) context = Utils.getActivity();
        if (context == null) context = Utils.getContext();
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the offline screen", ex);
        }
    }

    public static void openLibrary(@Nullable Context context) {
        Context base = context != null ? context : Utils.getContext();
        start(context, libraryIntent(base));
    }

    static void openPlayer(@Nullable Context context, @Nullable String videoId) {
        Context base = context != null ? context : Utils.getContext();
        start(context, playerIntent(base, videoId));
    }

    // endregion

    // region injection points

    /**
     * Injection point, from {@code YouTubeActivityHook.initialize}.
     *
     * @return True if the activity hosts an offline screen.
     */
    public static boolean initialize(Activity activity) {
        String data = activity.getIntent().getDataString();
        final boolean library = LIBRARY_INTENT.equals(data);
        final boolean player = PLAYER_INTENT.equals(data);
        if (!library && !player) return false;

        try {
            registerLifecycleCallbacks(activity);
            Screen screen = library ? new OfflineLibraryScreen(activity) : new OfflinePlayerScreen(activity);
            screens.put(activity, screen);
            Logger.printInfo(() -> "Showing offline " + (library ? "library" : "player"));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the offline screen", ex);
        }
        return true;
    }

    /**
     * Injection point, from the patched {@code LicenseActivity.finish()}.
     *
     * @return True if the screen handled the back press and the activity must stay.
     */
    public static boolean handleBackPress(Activity activity) {
        Screen screen = screens.get(activity);
        return screen != null && screen.onBackPressed();
    }

    /**
     * @return If the activity hosts an offline screen, whose back presses must not reach the
     * settings search.
     */
    public static boolean hostsScreen(Activity activity) {
        return screens.containsKey(activity);
    }

    /**
     * Injection point, at the start of YouTube's browse-endpoint command handler.
     * Opens the offline library instead of YouTube's Downloads page. The navigation is cancelled,
     * so YouTube stays on the current page and Back from the library returns there.
     *
     * @return True to cancel YouTube's navigation.
     */
    public static boolean openLibraryInsteadOfBrowse(@Nullable String browseId) {
        if (!DOWNLOADS_BROWSE_ID.equals(browseId)
                || !Settings.IN_APP_DOWNLOADER.get()
                || !Settings.REPLACE_DOWNLOADS_PAGE.get()) {
            return false;
        }
        Logger.printInfo(() -> "Replacing the Downloads page with the offline library");
        Utils.runOnMainThread(() -> openLibrary(Utils.getActivity()));
        return true;
    }

    // endregion

    // region lifecycle

    private static void registerLifecycleCallbacks(Activity activity) {
        if (lifecycleCallbacksRegistered) return;
        lifecycleCallbacksRegistered = true;
        // LicenseActivity has no lifecycle methods left after the settings patch, so the
        // application callbacks forward them to the screen of that instance.
        activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                Screen screen = screens.get(activity);
                if (screen != null) screen.onResume();
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                Screen screen = screens.get(activity);
                if (screen != null) screen.onPause();
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                Screen screen = screens.remove(activity);
                if (screen != null) screen.onDestroy();
            }
        });
    }

    // endregion

    // region library order

    /** @return The videos in the library display order of the chosen sort. */
    static List<OfflineVideo> sorted(List<OfflineVideo> videos) {
        List<OfflineVideo> result = new ArrayList<>(videos);
        Comparator<OfflineVideo> comparator = switch (Settings.OFFLINE_LIBRARY_SORT.get()) {
            case SORT_OLDEST -> Comparator.comparingLong(video -> video.downloadedAt);
            case SORT_TITLE -> Comparator.comparing(video -> video.title.toLowerCase(Locale.ROOT));
            case SORT_SIZE -> Comparator.comparingLong((OfflineVideo video) -> video.sizeBytes).reversed();
            default -> Comparator.comparingLong((OfflineVideo video) -> video.downloadedAt).reversed();
        };
        result.sort(comparator);
        return result;
    }

    static List<String> order() {
        List<String> ids = new ArrayList<>();
        for (OfflineVideo video : sorted(OfflineIndex.getAll())) ids.add(video.videoId);
        return ids;
    }

    // endregion
}
