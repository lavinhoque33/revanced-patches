/*
 * Copyright (C) 2026 lavinhoque33
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Written by lavinhoque33, 2026-11-12
 */

package app.morphe.extension.music.patches;

/** Carries the outgoing playback decision through promotion and failed-load recovery. */
enum CrossfadeOutgoing {
    PLAYING,
    SILENT;

    static CrossfadeOutgoing fromPlayer(boolean playWhenReady, long msSincePause) {
        // The native skip can pause the player immediately before stopVideo.
        return playWhenReady || (msSincePause >= 0 && msSincePause < 500) ? PLAYING : SILENT;
    }

    boolean shouldPlay() {
        return this == PLAYING;
    }

    float volume(float appVolume) {
        return shouldPlay() ? appVolume : 0.0f;
    }
}
