/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-25
 */

package app.morphe.extension.music.patches.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.os.UserManager;

import app.morphe.extension.shared.utils.BaseThemeUtils;
import app.morphe.extension.shared.utils.ResourceUtils;

public final class MusicLightTheme {
    public static final int SURFACE = 0xFFF8F9FA;
    public static final int FOREGROUND = 0xFF0F0F0F;
    public static final int CONTROL_SURFACE = 0xFFF1F3F4;
    public static final String THEME_PREFERENCE_KEY = "morphe_dark_theme";

    static final String VALUE = "light";
    private static final int LIGHT_MCC = 816;

    // Application and Activity attach run on the main thread; renderers also read this on workers.
    private static volatile String startupTheme;

    private MusicLightTheme() {
    }

    /** Injection from the Music application and UI hosts before they attach the incoming context. */
    public static Context wrapBaseContext(Context base) {
        if (startupTheme == null) {
            startupTheme = readThemePreference(base);
        }
        if (!isEnabled()) {
            return base;
        }

        Configuration override = new Configuration();
        override.mcc = LIGHT_MCC;
        return base.createConfigurationContext(override);
    }

    private static String readThemePreference(Context base) {
        // The first attach can be for an Android Direct Boot service before credential storage opens.
        UserManager users = base.getSystemService(UserManager.class);
        if (users != null && !users.isUserUnlocked()) {
            return DrawableColorPatch.DEFAULT_DARK_THEME;
        }

        // Same file as Setting.preferences; neither Setting nor Utils can initialize before attach.
        try {
            return base.getSharedPreferences("revanced", Context.MODE_PRIVATE)
                    .getString(THEME_PREFERENCE_KEY, DrawableColorPatch.DEFAULT_DARK_THEME);
        } catch (ClassCastException ignored) {
            // A malformed value must not break startup. The normal settings loader will clean it up.
            return DrawableColorPatch.DEFAULT_DARK_THEME;
        }
    }

    public static boolean isEnabled() {
        return VALUE.equals(startupTheme);
    }

    public static int settingsBackColor(int stock) {
        return isEnabled() ? FOREGROUND : stock;
    }

    public static int listRowSecondaryResource(int stock) {
        return isEnabled() ? ListColors.SECONDARY : stock;
    }

    private static final class ListColors {
        private static final int SECONDARY = ResourceUtils.getColorIdentifier("ytm_text_color_secondary");
    }

    /** Innertube ThemeStore's Light value is 1; keep the stock value for the dark presets. */
    public static int selectElementsTheme(int original) {
        return isEnabled() ? 1 : original;
    }

    static String getStartupDarkTheme() {
        String theme = startupTheme;
        return theme == null || VALUE.equals(theme) ? DrawableColorPatch.DEFAULT_DARK_THEME : theme;
    }

    /** Music-only setThemeColor hook; returns true when the shared color lookup can be skipped. */
    public static boolean applySharedTheme() {
        boolean light = isEnabled();
        BaseThemeUtils.updateMusicThemeStatus(!light);
        if (light) {
            BaseThemeUtils.setThemeLightColor(SURFACE);
            BaseThemeUtils.setThemeDarkColor(FOREGROUND);
        }
        return light;
    }
}
