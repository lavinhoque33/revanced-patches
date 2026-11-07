/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-07.
 */
package app.morphe.extension.music.patches.downloads;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.utils.Logger;

/**
 * Keeps the next songs of a downloaded album on their local copies without taking the video away.
 *
 * <p>The queue of an album holds items with only the music video's renderer, so in the audio mode Next requested the
 * video's id, which has no stream copy. An item here keeps both renderers, the music video the server listed and a
 * copy of it rebuilt around the song's id, and shows the one the mode asks for: the song in an audio mode, the video
 * otherwise. Every mode change goes through {@link #onMode}, so the Song/Video pill flips every item, as the app does
 * for the queue items that carry both renderers.
 *
 * <p>The pill is enabled by the item: the app enables it for an item with a counterpart, or when the player response
 * has video, which a song's does not. {@link #pillItem} tells it that a tracked item has its video as counterpart.
 */
@SuppressWarnings("unused")
public final class QueueItems {
    /** A queue item that holds one panel renderer, by the two methods the patch adds to its class. */
    public interface Item {
        /** The renderer the item shows now. */
        Object rvxRenderer();

        /** Shows another renderer, as the item's own replacing method does. */
        void rvxReplace(Object renderer);
    }

    /** A panel renderer, rebuilt from bytes by the one method the patch adds to its class. */
    public interface Renderer {
        Object rvxWithBytes(byte[] bytes);
    }

    /** The two renderers of one item, by identity of the item. */
    private static final class Tracked extends WeakReference<Object> {
        final Object video;
        final Object song;
        final String videoId;
        final String songId;

        Tracked(Object item, Object video, Object song, String videoId, String songId) {
            super(item);
            this.video = video;
            this.song = song;
            this.videoId = videoId;
            this.songId = songId;
        }
    }

    private static final List<Tracked> tracked = new ArrayList<>();
    private static final QueueLogBudget log = new QueueLogBudget();
    private static final Object NOT_A_QUEUE_ITEM = new Object();
    private static volatile boolean alive;

    private QueueItems() {}

    /**
     * Injection point: a queue item was built, or was given another renderer. Items that name a downloaded song are
     * tracked and show the song in an audio mode; the rest are not touched.
     */
    public static void onQueueItem(@Nullable Object item) {
        try {
            if (!alive) {
                alive = true;
                Logger.printDebug(() -> "AudioFirst: queue item hook alive");
            }
            if (!AudioFirst.supported() || !(item instanceof Item queueItem)) return;
            if (!Settings.START_DOWNLOADED_IN_AUDIO.get() || !StreamSwap.enabled()) return;

            Object shown = queueItem.rvxRenderer();
            synchronized (tracked) {
                Tracked known = find(item);
                // The renderer this class put there is not a new one.
                if (known != null && shown == known.song) return;

                if (known != null) tracked.remove(known);
            }

            Tracked created = track(item, shown);
            if (created == null) return;

            String mode = AudioFirst.liveMode();
            if (AudioFirstLogic.showsSong(true, mode, true)) show(queueItem, created, mode);
        } catch (Exception ex) {
            Logger.printException(() -> "AudioFirst: queue item failure", ex);
        }
    }

    /**
     * Injection point: the toggle's item listener tests whether the item has no counterpart, as a single-renderer item
     * has not. An item tracked here has one, its video, so the listener gets an object that is not a queue item.
     */
    public static Object pillItem(@Nullable Object item) {
        try {
            if (item == null || !AudioFirst.supported() || !Settings.START_DOWNLOADED_IN_AUDIO.get()) return item;

            synchronized (tracked) {
                return find(item) != null ? NOT_A_QUEUE_ITEM : item;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "AudioFirst: queue item pill failure", ex);
            return item;
        }
    }

    /** Called when the app sets its mode: every tracked item shows the renderer the new mode asks for. */
    static void onMode(String mode) {
        try {
            List<Object> items = new ArrayList<>();
            List<Tracked> entries = new ArrayList<>();
            synchronized (tracked) {
                for (Iterator<Tracked> it = tracked.iterator(); it.hasNext(); ) {
                    Tracked entry = it.next();
                    Object item = entry.get();
                    if (item == null) {
                        it.remove();
                    } else {
                        items.add(item);
                        entries.add(entry);
                    }
                }
            }
            boolean enabled = Settings.START_DOWNLOADED_IN_AUDIO.get() && StreamSwap.enabled();
            for (int i = 0; i < items.size(); i++) {
                Item queueItem = (Item) items.get(i);
                Tracked entry = entries.get(i);
                Object wanted = AudioFirstLogic.showsSong(enabled, mode, true) ? entry.song : entry.video;
                if (queueItem.rvxRenderer() == wanted) continue;

                queueItem.rvxReplace(wanted);
                logView(entry, mode, wanted == entry.song ? "song" : "original");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "AudioFirst: queue item mode failure", ex);
        }
    }

    @Nullable
    private static Tracked track(Object item, Object shown) {
        if (!(shown instanceof MessageLite message) || !(shown instanceof Renderer rebuildable)) return null;

        DownloadedMarkers.warmUp();
        DownloadedRegistry registry = DownloadedMarkers.loadedRegistry();
        if (registry == null) return null;

        byte[] bytes = message.toByteArray();
        TapCommand row = TapCommand.parse(bytes);
        if (!row.isWatch() || row.videoId == null || row.videoId.isEmpty()) return null;

        AudioFirstLogic.Arm arm = AudioFirstLogic.chooseArm(true, List.of(row.videoId),
                registry.sourceTracks(registry.findSource(bytes)), row.index, row.hasParams, registry::isDownloaded);
        if (arm == null) {
            logDecision(row, "declined:no-copy");
            return null;
        }
        if (!AudioFirstLogic.namesTheRow(arm.rule(), arm.videoId(), row.videoId, registry::isDownloaded)) {
            logDecision(row, "declined:not-the-row(" + arm.rule() + ")");
            return null;
        }

        CommandRewrite.RendererResult result = CommandRewrite.retargetRenderer(bytes, row.videoId, arm.videoId(),
                true);
        if (result == null || !result.complete()) {
            logDecision(row, "declined:unreadable(" + (result == null ? "malformed" : result.describe()) + ")");
            return null;
        }

        Object song = rebuildable.rvxWithBytes(result.renderer());
        if (song == null) return null;

        Tracked created = new Tracked(item, shown, song, row.videoId, arm.videoId());
        synchronized (tracked) {
            tracked.add(created);
        }
        logDecision(row, "tracked(" + arm.rule() + "," + result.describe() + ")", arm.videoId());
        return created;
    }

    private static void show(Item item, Tracked entry, String mode) {
        item.rvxReplace(entry.song);
        logView(entry, mode, "song");
    }

    @Nullable
    private static Tracked find(Object item) {
        for (Iterator<Tracked> it = tracked.iterator(); it.hasNext(); ) {
            Tracked entry = it.next();
            Object known = entry.get();
            if (known == null) {
                it.remove();
            } else if (known == item) {
                return entry;
            }
        }
        return null;
    }

    private static void logDecision(TapCommand row, String outcome) {
        logDecision(row, outcome, null);
    }

    private static void logDecision(TapCommand row, String outcome, @Nullable String songId) {
        if (!log.allow(SystemClock.elapsedRealtime())) return;

        Logger.printDebug(() -> "AudioFirst: queue item view video=" + row.videoId + " -> song=" + songId
                + " mode=" + AudioFirst.liveMode() + " playlist=" + row.playlistId + " index=" + row.index
                + " outcome=" + outcome);
    }

    private static void logView(Tracked entry, String mode, String outcome) {
        if (!log.allow(SystemClock.elapsedRealtime())) return;

        Logger.printDebug(() -> "AudioFirst: queue item view video=" + entry.videoId + " -> song=" + entry.songId
                + " mode=" + mode + " outcome=" + outcome);
    }
}
