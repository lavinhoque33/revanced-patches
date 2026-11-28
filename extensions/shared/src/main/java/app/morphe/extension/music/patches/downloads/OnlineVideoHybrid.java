/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.utils.Logger;

/**
 * Local audio with online video: the stream swap's answer for a track that plays from saved raw audio while online,
 * when its own online response holds directly playable video-only formats of the same timeline
 * ({@link OnlineVideoHybridLogic}). The clip then plays the saved file in Song and keeps the response's online video
 * for Video, and the app's own switch flips between them in place, as it does for a stored video copy, without a
 * reload.
 *
 * <p>This is its own capability, never a video copy. It lives on the native streaming data of the one response the
 * stock adapter accepted with the hybrid formats, so prepared responses of the same track never share or overwrite
 * it. It counts only while the app's active clip was built from that streaming data and plays the saved raw file the
 * response carries. Nothing remembers the video urls for another clip or for offline use.
 *
 * <p>An online response without directly playable video-only formats (SABR only, ciphered, muxed, or of another
 * timeline) gets no hybrid: its clip plays the saved audio alone, and the Video pill declines while it plays.
 */
@SuppressWarnings("unused")
public final class OnlineVideoHybrid {
    /** Implemented on the stock streaming data by the Downloads patch: one slot per native instance. */
    public interface Streams {
        Object rvxHybrid();

        void rvxSetHybrid(Object hybrid);
    }

    /** The hybrid an accepted response's streaming data carries: the track and the saved audio's source. */
    private record Bound(String videoId, StreamSwapSources.Source audio) {}

    private OnlineVideoHybrid() {}

    /** Enabled by the patch only when both the response and the active clip expose their streaming data. */
    public static boolean supported() {
        return false;
    }

    /**
     * The hybrid response of a swap to saved raw audio, or null when the clip keeps the local audio alone: a stored
     * (offline) answer, an offline device, or an online response without directly playable video.
     *
     * @param online The effective online response of the request.
     * @param local  The saved audio's format, its url the local token of the issued source.
     * @param stored Whether the response is answered from storage, which never carries an online video.
     */
    @Nullable
    static PlayerResponse hybrid(PlayerResponse online, Format local, String videoId, boolean stored) {
        if (stored || !supported() || !PlaybackConnectivity.reachable()) return null;

        OnlineVideoHybridLogic.Verdict verdict = OnlineVideoHybridLogic.verdict(online, videoId, local);
        Logger.printDebug(() -> "OnlineVideoHybrid: v=" + videoId + " eligible=" + verdict.eligible()
                + (verdict.eligible() ? "" : " reason=" + verdict.reason()) + " " + verdict.summary());
        return verdict.eligible() ? OnlineVideoHybridLogic.hybrid(online, verdict.video(), local) : null;
    }

    /** The stock adapter accepted a hybrid response: its own streaming data carries the hybrid from now on. */
    static void accepted(Object response, String videoId, StreamSwapSources.Source audio) {
        if (response instanceof StreamSwap.Response adapter && adapter.rvxStreams() instanceof Streams streams) {
            streams.rvxSetHybrid(new Bound(videoId, audio));
            return;
        }
        Logger.printDebug(() -> "OnlineVideoHybrid: v=" + videoId + " accepted without streaming data");
    }

    /**
     * Whether the app's active clip is a hybrid of this track: it was built from the streaming data of an accepted
     * hybrid response, and its selected audio is a confirmed read of that response's saved raw file. Says nothing
     * of the network; the pill asks that itself.
     */
    static boolean playsBound(String videoId) {
        return supported() && PlaybackSourceInspector.activeHybrid(videoId);
    }

    /**
     * Whether streaming data carries the hybrid of this track and saved raw file; the inspector asks under its lock.
     */
    static boolean marks(Object streams, String videoId, StreamSwapSources.Source audio) {
        return streams instanceof Streams marked && marked.rvxHybrid() instanceof Bound bound
                && bound.audio() == audio && bound.videoId().equals(videoId);
    }
}
