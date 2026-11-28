/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-26.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.ResourceUtils;
import app.morphe.extension.shared.utils.Utils;

/**
 * The Video pill for a track that plays from the saved audio of a stream copy.
 *
 * <p>When the online response held directly playable video-only formats of the same timeline, the stream swap gave
 * them to the clip beside the saved audio ({@link OnlineVideoHybrid}): the app's own pill then flips that clip in
 * place, and this class is not asked. Otherwise the response that plays holds the local audio alone, and the app
 * cannot flip to the video in place: its own gate stays closed and its press does nothing. When the online response
 * has a video the clip cannot carry (SABR only, URL-less, ciphered or of another timeline), the press is answered
 * with the app's "Video not available" and the saved audio keeps playing: nothing is reloaded or navigated. That is
 * a limit of such a track, not a switch.
 *
 * <p>Offline the video cannot come, so the press is answered with the app's "Video not available" at once.
 */
@SuppressWarnings("unused")
public final class PillVideoSwitch {
    /** What the last online response of a track that plays from saved audio held, for the press that follows. */
    private record OnlineVideo(String videoId, PillVideoSwitchLogic.Video video, int musicVideoType) {}

    private static final String UNAVAILABLE_STRING = "av_switch_video_unavailable";
    private static final String DATA_SAVING_OVERRIDE = "DONT_PLAY_VIDEO_OVERRIDE";

    @Nullable
    private static volatile OnlineVideo onlineVideo;

    private PillVideoSwitch() {}

    /** Whether the Song/Video pill shows Song: the app's two audio modes, not the data-saving override. */
    private static boolean songMode() {
        String mode = AudioFirst.liveMode();
        return AudioFirstLogic.isAudioMode(mode) && !DATA_SAVING_OVERRIDE.equals(mode);
    }

    /**
     * The online response the stream swap replaced with the saved audio of a track: whether it had a video. Read by
     * the next press of the pill.
     */
    static void rememberOnlineVideo(String videoId, PlayerResponse online) {
        OnlineVideo seen = new OnlineVideo(videoId, PillVideoSwitchLogic.videoOf(online),
                online.getVideoDetails().getMusicVideoType());
        onlineVideo = seen;
        Logger.printDebug(() -> "PillVideo: online response v=" + videoId + " video=" + seen.video()
                + " type=" + seen.musicVideoType());
    }

    private static PillVideoSwitchLogic.Video onlineVideoOf(String videoId) {
        OnlineVideo seen = onlineVideo;
        return seen != null && seen.videoId().equals(videoId) ? seen.video() : PillVideoSwitchLogic.Video.UNKNOWN;
    }

    /**
     * An offline press: a track with a saved copy answers it here and nothing is looked up, dispatched or switched.
     *
     * @return True when the press ends here.
     */
    static boolean declinesOffline() {
        String playing = VideoInformation.getVideoId();
        PillVideoSwitchLogic.Outcome outcome = PillVideoSwitchLogic.offline(songMode(),
                PillAvailability.keepsNativeSwitch(), hasSavedCopy(playing));
        Logger.printDebug(() -> "PillVideo: offline press v=" + playing + " " + outcome + ": " + outcome.reason());
        if (outcome == PillVideoSwitchLogic.Outcome.DECLINE_OFFLINE) showUnavailable();
        return outcome.taken();
    }

    /** Whether a saved copy plays the track, under its own id, the song it is the alias of, or the raw source. */
    private static boolean hasSavedCopy(String videoId) {
        DownloadedRegistry registry = DownloadedMarkers.loadedRegistry();
        return PillVideoSwitchLogic.hasSavedCopy(videoId, OfflineCopyStore::has,
                id -> registry == null ? id : registry.canonicalId(id),
                id -> StreamSwap.supported() && StreamSwapSources.playsRaw(id));
    }

    /**
     * A press while online, of an item the extension does not track and whose clip holds no online video.
     *
     * @return True when the press ends here; false when the app's own listener goes on.
     */
    static boolean onOnlineClick() {
        String playing = VideoInformation.getVideoId();
        if (playing.isEmpty()) return false;

        PillVideoSwitchLogic.Facts facts = new PillVideoSwitchLogic.Facts(songMode(),
                PillAvailability.keepsNativeSwitch(), false,
                StreamSwap.supported() && StreamSwapSources.playsRaw(playing), onlineVideoOf(playing));
        PillVideoSwitchLogic.Outcome outcome = PillVideoSwitchLogic.online(facts);
        OnlineVideo seen = onlineVideo;
        Logger.printDebug(() -> "PillVideo: press v=" + playing + " " + outcome + ": " + outcome.reason()
                + " type=" + (seen == null || !seen.videoId().equals(playing) ? "?" : seen.musicVideoType()));
        if (outcome == PillVideoSwitchLogic.Outcome.DECLINE_ONLINE) showUnavailable();
        return outcome.taken();
    }

    /**
     * Whether a press of the pill belongs to this class or to the tracked queue items: the track plays from saved
     * audio alone. The optional audio/video switch interceptor must not take such a press, it looks the counterpart
     * up and queues it instead.
     */
    public static boolean ownsPillTap() {
        try {
            if (!AudioFirst.supported() || !StreamSwap.supported()) return false;

            String playing = VideoInformation.getVideoId();
            return !playing.isEmpty() && (StreamSwapSources.playsRaw(playing) || QueueItems.tracksPlaying());
        } catch (Exception ex) {
            Logger.printException(() -> "PillVideo: ownership failure", ex);
            return false;
        }
    }

    /** The app's own "Video not available", as its pill shows it. */
    private static void showUnavailable() {
        int id = ResourceUtils.getStringIdentifier(UNAVAILABLE_STRING);
        if (id == 0) {
            Logger.printDebug(() -> "PillVideo: no " + UNAVAILABLE_STRING + " string on this version");
            return;
        }
        Utils.showToastShort(Utils.getResources().getString(id));
    }
}
