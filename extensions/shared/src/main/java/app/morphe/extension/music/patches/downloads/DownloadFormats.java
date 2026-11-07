/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.util.Comparator;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;

/** Which format of a player response a download takes. Pure, so the choice is pinned by host tests. */
final class DownloadFormats {
    private DownloadFormats() {}

    /**
     * The audio to download: the highest bitrate audio-only adaptive format with a url. Video and muxed formats are
     * never taken, even for a song that has a video, because only {@code adaptiveFormats} are read and their mime
     * type must be audio. A copy that has to play from the stream swap (stream only) must also be itag 251 Opus, so
     * that is the only one accepted then.
     */
    @Nullable
    static Format bestAudio(PlayerResponse response, boolean playableCopyOnly) {
        return response.getStreamingData()
                .getAdaptiveFormatsList()
                .stream()
                .filter(format -> format.getMimeType().startsWith("audio/")
                        && AudioTagger.canTag(format.getMimeType())
                        && (!playableCopyOnly
                        || StreamSwapLogic.supportedAudio(format.getItag(), format.getMimeType()))
                        && !format.getUrl().isBlank())
                .max(Comparator.comparingInt(Format::getBitrate))
                .orElse(null);
    }
}
