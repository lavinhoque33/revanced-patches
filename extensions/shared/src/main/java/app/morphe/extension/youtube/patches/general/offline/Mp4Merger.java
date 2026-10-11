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

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.utils.Logger;

/**
 * Joins the separately downloaded DASH video and audio files into one MP4 without re-encoding.
 */
final class Mp4Merger {
    private static final int DEFAULT_BUFFER_SIZE = 8 * 1024 * 1024;

    private Mp4Merger() {
    }

    /**
     * @param cancelled Polled between samples, so a cancelled download stops promptly.
     */
    @SuppressLint("WrongConstant") // Extractor sample flags map one to one to muxer buffer flags.
    static void merge(File video, File audio, File output, BooleanSupplier cancelled) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            videoExtractor.setDataSource(video.getAbsolutePath());
            audioExtractor.setDataSource(audio.getAbsolutePath());
            MediaFormat videoFormat = selectTrack(videoExtractor, "video/");
            MediaFormat audioFormat = selectTrack(audioExtractor, "audio/");

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION));
            }
            final int videoTrack = muxer.addTrack(videoFormat);
            final int audioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            started = true;

            int bufferSize = DEFAULT_BUFFER_SIZE;
            if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                bufferSize = Math.max(bufferSize, videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            boolean videoDone = false;
            boolean audioDone = false;
            // Samples are written in timestamp order, so the file is interleaved for streaming
            // and the muxer does not have to hold one track back while the other is written.
            while (!videoDone || !audioDone) {
                if (cancelled.getAsBoolean()) throw new IOException("Cancelled");

                final boolean useVideo;
                if (videoDone) useVideo = false;
                else if (audioDone) useVideo = true;
                else useVideo = videoExtractor.getSampleTime() <= audioExtractor.getSampleTime();

                MediaExtractor extractor = useVideo ? videoExtractor : audioExtractor;
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    if (useVideo) videoDone = true;
                    else audioDone = true;
                    continue;
                }
                int flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME
                        : 0;
                info.set(0, size, extractor.getSampleTime(), flags);
                muxer.writeSampleData(useVideo ? videoTrack : audioTrack, buffer, info);
                extractor.advance();
            }
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Could not finish the MP4 file", ex);
                    if (!cancelled.getAsBoolean()) {
                        //noinspection ThrowFromFinallyBlock
                        throw new IOException("Could not finish the MP4 file", ex);
                    }
                } finally {
                    muxer.release();
                }
            }
        }
    }

    private static MediaFormat selectTrack(MediaExtractor extractor, String mimePrefix) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new IOException("No " + mimePrefix + " track");
    }
}
