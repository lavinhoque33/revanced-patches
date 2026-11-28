/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;

/**
 * When a press of Video is the extension's: the current track plays from the saved audio of a stream copy, which
 * carries no video, and the user asks for the music video. Pure rules, so they can be tested without Android.
 */
final class PillVideoSwitchLogic {
    /** Whether the online response of the track playing has a video. */
    enum Video { UNKNOWN, PRESENT, ABSENT }

    /** What a press of the pill does, and the reason the log gives for it. */
    enum Outcome {
        /**
         * Online, the response has a video, but not one the clip can play beside the saved audio (SABR only,
         * URL-less, ciphered or of another timeline): the app's own "Video not available" is shown and the saved
         * audio keeps playing. Nothing is reloaded or navigated.
         */
        DECLINE_ONLINE("the online video cannot play beside the saved audio", true),
        /** Offline the video cannot come: the app's own "Video not available" is shown and nothing else happens. */
        DECLINE_OFFLINE("offline, only saved audio can play", true),
        /**
         * Online, but the response of the track has no video (a song without a music video): the app's own pill
         * answers, with its "Video not available" or with the counterpart's endpoint when the response names one.
         */
        STOCK_NO_VIDEO("the online response shows no video", false),
        /** Offline, a press toward Song has nothing to change: it was always swallowed there. */
        CONSUME_OFFLINE("offline, press toward Song", true),
        STOCK_PAIR("a video copy supplies both modes", false),
        STOCK_NO_COPY("offline without a copy", false),
        STOCK_VIDEO_MODE("press toward Song, the app flips the loaded response", false),
        STOCK_TRACKED("a tracked queue item", false),
        STOCK_NOT_SAVED_AUDIO("the track does not play from saved audio", false);

        private final String reason;
        private final boolean taken;

        Outcome(String reason, boolean taken) {
            this.reason = reason;
            this.taken = taken;
        }

        /** True when the press ends here; false when the app's own listener goes on. */
        boolean taken() {
            return taken;
        }

        String reason() {
            return reason;
        }
    }

    /** What is known when the pill is pressed while online. */
    record Facts(boolean songMode, boolean pair, boolean tracked, boolean savedAudio, Video video) {}

    private PillVideoSwitchLogic() {}

    /**
     * Whether the track playing has a saved copy that plays it. Playback finds the copy the way this does: stored
     * under the id itself, or under the song the id is the alias of (a music video of a downloaded song plays the
     * song's copy though it has no entry of its own), or the swap already serves it from a raw copy.
     *
     * @param stored      Whether a copy is stored under an id.
     * @param canonicalOf The id a copy is stored under for an id: the id itself unless it is an alias.
     * @param playsRaw    Whether the swap serves the id from a saved raw audio copy now.
     */
    static boolean hasSavedCopy(String videoId, Predicate<String> stored, UnaryOperator<String> canonicalOf,
                                Predicate<String> playsRaw) {
        if (stored.test(videoId)) return true;

        String canonical = canonicalOf.apply(videoId);
        if (!videoId.equals(canonical) && stored.test(canonical)) return true;

        return playsRaw.test(videoId);
    }

    /** Offline nothing is looked up, dispatched or switched: a saved copy answers the press, the app's own does not. */
    static Outcome offline(boolean songMode, boolean pair, boolean hasCopy) {
        if (pair) return Outcome.STOCK_PAIR;
        if (!hasCopy) return Outcome.STOCK_NO_COPY;
        return songMode ? Outcome.DECLINE_OFFLINE : Outcome.CONSUME_OFFLINE;
    }

    static Outcome online(Facts facts) {
        if (facts.pair) return Outcome.STOCK_PAIR;
        if (!facts.songMode) return Outcome.STOCK_VIDEO_MODE;
        if (facts.tracked) return Outcome.STOCK_TRACKED;
        if (!facts.savedAudio) return Outcome.STOCK_NOT_SAVED_AUDIO;
        return facts.video == Video.PRESENT ? Outcome.DECLINE_ONLINE : Outcome.STOCK_NO_VIDEO;
    }

    /**
     * Whether the online response of a track has a video. The formats of a SABR response have no URL, so the stream
     * is not asked for, only whether one of its formats is a video one: a video MIME type or picture dimensions.
     */
    static Video videoOf(PlayerResponse online) {
        if (!online.hasStreamingData()) return Video.UNKNOWN;

        StreamingData streaming = online.getStreamingData();
        for (Format format : streaming.getAdaptiveFormatsList()) {
            if (carriesVideo(format)) return Video.PRESENT;
        }
        for (Format format : streaming.getFormatsList()) {
            if (carriesVideo(format)) return Video.PRESENT;
        }
        return streaming.getAdaptiveFormatsCount() + streaming.getFormatsCount() == 0 ? Video.UNKNOWN : Video.ABSENT;
    }

    private static boolean carriesVideo(Format format) {
        return format.getMimeType().startsWith("video/") || format.getHeight() > 0 || format.getWidth() > 0;
    }
}
