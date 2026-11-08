/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-08.
 */

package app.morphe.extension.music.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.music.patches.downloads.OfflineReplayHooks;

/**
 * A row that opens the list of downloaded songs. The list is also reached from the app's own Downloads entries, but
 * what those show depends on the home the app has cached, so this row is the one that is always there.
 */
@SuppressWarnings({"unused", "deprecation"})
public class OpenDownloadsPreference extends Preference {

    public OpenDownloadsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public OpenDownloadsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public OpenDownloadsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public OpenDownloadsPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        // Nothing is stored here, and the tap is the whole feature.
        setPersistent(false);
        setSelectable(true);
    }

    @Override
    protected void onClick() {
        OfflineReplayHooks.openList(getContext());
    }
}
