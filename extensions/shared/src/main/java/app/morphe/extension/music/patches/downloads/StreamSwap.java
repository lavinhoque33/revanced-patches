/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.net.Uri;
import android.os.Looper;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.utils.Logger;

/** Replaces audio only: the online response still owns radio, queue, lyrics, tracking and related content. */
@SuppressWarnings("unused")
public final class StreamSwap {
    /** Implemented on the stock response wrapper by the Downloads patch. */
    public interface Response {
        String rvxVideoId();
        byte[] rvxBytes();
        Object rvxWithBytes(byte[] bytes, boolean localStream);
    }

    private record Replacement(Object online, StreamSwapSources.Source source) {}
    private static final Map<Object, Replacement> replacements = Collections.synchronizedMap(new WeakHashMap<>());

    private StreamSwap() {}

    /** Enabled by the patch only after every required hook resolves. */
    public static boolean supported() {
        return false;
    }

    static boolean enabled() {
        return supported() && Settings.IN_APP_DOWNLOADS.get() && Settings.PLAY_DOWNLOADS_LOCALLY.get();
    }

    public static Object swap(Object original) {
        if (!supported()) return original;
        Replacement cached = replacements.get(original);
        if (cached != null) {
            // A response cache may outlive the setting or downloaded file. This path never accesses storage.
            if (!enabled() || !cached.source().available) return cached.online();
            if (onMainThread()) return original;
            original = cached.online();
        }
        String gate = StreamSwapLogic.gate(Settings.IN_APP_DOWNLOADS.get(),
                Settings.PLAY_DOWNLOADS_LOCALLY.get(), onMainThread());
        if (gate != null) return unchanged(original, "?", gate);
        if (!(original instanceof Response adapter)) return original;

        String videoId = "?";
        try {
            videoId = adapter.rvxVideoId();
            RawAudioFiles.Entry raw = RawAudioStore.find(videoId);
            if (raw == null) return unchanged(original, videoId, "no current raw download");
            PlayerResponse online = PlayerResponse.parseFrom(adapter.rvxBytes());
            if (!online.hasStreamingData()) return unchanged(original, videoId, "no StreamingData");
            Format selected = null;
            for (Format format : online.getStreamingData().getAdaptiveFormatsList()) {
                if (format.getItag() == raw.itag()) {
                    selected = format;
                    break;
                }
            }
            if (selected == null) return unchanged(original, videoId, "stored itag missing online");
            String mismatch = StreamSwapLogic.mismatch(raw, selected, online);
            if (mismatch != null) return unchanged(original, videoId, mismatch);
            // Only ordinary complete network representations can provide a safe online fallback.
            Uri onlineUri = Uri.parse(selected.getUrl());
            if (!"https".equals(onlineUri.getScheme()) || onlineUri.getHost() == null) {
                return unchanged(original, videoId, "no online URL");
            }

            StreamSwapSources.Source source = StreamSwapSources.issue(videoId, raw, selected.getUrl());
            Format local = selected.toBuilder().setUrl(source.token.toString()).clearSignatureCipher().build();
            PlayerResponse replacement = StreamSwapLogic.replaceAudio(online, local);
            Object result = adapter.rvxWithBytes(replacement.toByteArray(), true);
            if (result == null || result == original) return unchanged(original, videoId, "stock adapter declined");
            replacements.put(result, new Replacement(original, source));
            String id = videoId;
            Logger.printDebug(() -> "StreamSwap: decision=SWAP v=" + id + " itag=" + raw.itag());
            return result;
        } catch (Exception ex) {
            return unchanged(original, videoId, "raw copy or response unavailable");
        }
    }

    static boolean onMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static Object unchanged(Object original, String videoId, String reason) {
        Logger.printDebug(() -> "StreamSwap: decision=ONLINE v=" + videoId + " reason=" + reason);
        return original;
    }

    /** Keep the stock guard's exact answer except for tokens issued by this process. No storage work. */
    public static boolean blocksLoopback(Uri uri) {
        if (!StreamSwapLogic.isGuardedHost(uri.getHost())) return false;
        try {
            return StreamSwapSources.find(uri) == null;
        } catch (Exception ex) {
            return true;
        }
    }
}
