/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-09.
 */
package app.morphe.extension.music.patches.downloads;

import static org.junit.Assert.*;

import org.junit.Test;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.AudioConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ExoPlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.MediaCommonConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerGestureConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlaybackRoutingConfig;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

public class PlayerConfigTemplateLogicTest {
    private static PlayerConfig appConfig() {
        return PlayerConfig.newBuilder()
                .setExoPlayerConfig(ExoPlayerConfig.newBuilder().setUseExoPlayer(true))
                .setPlayerGestureConfig(PlayerGestureConfig.newBuilder().setDownAndOutPortraitAllowed(true))
                .setMediaCommonConfig(MediaCommonConfig.newBuilder().setEnableSabr(true))
                .setPlaybackRoutingConfig(PlaybackRoutingConfig.newBuilder().setPreferredEngine(3))
                .build();
    }

    @Test public void onlyAConfigWithTheExoPlayerFlagIsTheApps() {
        assertTrue(PlayerConfigTemplateLogic.isAppConfig(appConfig()));
        assertFalse(PlayerConfigTemplateLogic.isAppConfig(PlayerConfig.getDefaultInstance()));
        assertFalse(PlayerConfigTemplateLogic.isAppConfig(PlayerConfig.newBuilder()
                .setAudioConfig(AudioConfig.newBuilder().setExoAudioGate(true)).build()));
    }

    @Test public void theKeptConfigHasNoRequestHintThatForcesSabrAndKeepsTheRest() {
        PlayerConfig kept = PlayerConfigTemplateLogic.forOffline(appConfig());

        assertFalse(kept.hasMediaCommonConfig());
        assertFalse(kept.hasPlaybackRoutingConfig());
        assertTrue(kept.getExoPlayerConfig().getUseExoPlayer());
        assertTrue(kept.getPlayerGestureConfig().getDownAndOutPortraitAllowed());
    }

    @Test public void theConfigIsLookedAtAgainWhenNoneWasCheckedOrItIsOld() {
        assertTrue(PlayerConfigTemplateLogic.due(0, 5_000));
        assertFalse(PlayerConfigTemplateLogic.due(1_000, 1_000 + PlayerConfigTemplateLogic.REFRESH_MILLIS - 1));
        assertTrue(PlayerConfigTemplateLogic.due(1_000, 1_000 + PlayerConfigTemplateLogic.REFRESH_MILLIS));
    }

    @Test public void theTemplateIsLaidUnderTheStoredConfigAndTheStoredFieldsWin() {
        PlayerConfig stored = PlayerConfig.newBuilder()
                .setAudioConfig(AudioConfig.newBuilder().setMuteOnStart(true).setExoAudioGate(true))
                .setExoPlayerConfig(ExoPlayerConfig.newBuilder().setUseExoPlayer(true))
                .build();
        PlayerConfig template = appConfig().toBuilder()
                .setAudioConfig(AudioConfig.newBuilder().setMuteOnStart(false)).build();
        PlayerResponse response = PlayerResponse.newBuilder()
                .setVideoDetails(VideoDetails.newBuilder().setVideoId("clip"))
                .setPlayerConfig(stored).build();

        PlayerResponse result = PlayerConfigTemplateLogic.withTemplate(response, template);

        assertEquals("clip", result.getVideoDetails().getVideoId());
        assertTrue(result.getPlayerConfig().getPlayerGestureConfig().getDownAndOutPortraitAllowed());
        assertTrue(result.getPlayerConfig().getAudioConfig().getMuteOnStart());
        assertTrue(result.getPlayerConfig().getAudioConfig().getExoAudioGate());
        assertTrue(result.getPlayerConfig().getExoPlayerConfig().getUseExoPlayer());
    }

    @Test public void noTemplateLeavesTheStoredResponseAsItIs() {
        PlayerResponse response = PlayerResponse.newBuilder()
                .setPlayerConfig(PlayerConfig.newBuilder().setExoPlayerConfig(
                        ExoPlayerConfig.newBuilder().setUseExoPlayer(true))).build();

        assertSame(response, PlayerConfigTemplateLogic.withTemplate(response, null));
    }
}
