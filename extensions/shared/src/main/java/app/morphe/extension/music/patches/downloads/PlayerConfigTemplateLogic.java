/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;

/** What {@link PlayerConfigTemplate} decides. Pure rules, so they can be tested without Android. */
final class PlayerConfigTemplateLogic {
    /** The saved configuration is looked at again when it is older than this: the app's own one changes rarely. */
    static final long REFRESH_MILLIS = 6L * 60 * 60 * 1000;

    private PlayerConfigTemplateLogic() {}

    /**
     * Whether a configuration is the app's own, the one its ExoPlayer engine plays with: a response fetched by
     * another client (the saving of a copy asks one) has none of it.
     */
    static boolean isAppConfig(PlayerConfig config) {
        return config.getExoPlayerConfig().getUseExoPlayer();
    }

    /** What is kept of the app's configuration: all of it, without the request hints that force SABR. */
    static PlayerConfig forOffline(PlayerConfig live) {
        return StreamSwapLogic.withoutServerAbr(live);
    }

    /** Whether the saved configuration is to be looked at again; true when none is held. */
    static boolean due(long lastCheckedMillis, long nowMillis) {
        return lastCheckedMillis <= 0 || nowMillis - lastCheckedMillis >= REFRESH_MILLIS;
    }

    /**
     * The stored response with the app's configuration under its own. The stored fields win, so what the saving
     * resolved (the loudness) and the ExoPlayer flags of {@link OfflineResponseTrim#forExoPlayer} stay; every
     * other setting of the app's player is the template's. A null template leaves the response as it is.
     */
    static PlayerResponse withTemplate(PlayerResponse stored, @Nullable PlayerConfig template) {
        if (template == null) return stored;

        PlayerConfig merged = template.toBuilder().mergeFrom(stored.getPlayerConfig()).build();
        return stored.toBuilder().setPlayerConfig(merged).build();
    }
}
