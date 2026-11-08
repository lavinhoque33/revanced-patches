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

import app.morphe.extension.music.patches.CrossfadeManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
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
 * <p>The app enables the pill for an item with a counterpart, or when the player response has video, which a song's
 * does not, so the pill can look dimmed for a tracked song. {@link #onPillClick} takes the click itself, whatever the
 * pill's gate says.
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

    /** The toggle's owner, by the one method the patch adds to it: navigates to an item's own watch endpoint. */
    public interface PillOwner {
        void rvxNavigate(Object item);
    }

    /** The toggle's view, by the three methods the patch adds to it. */
    public interface PillView {
        /** The toggle's owner. */
        Object rvxOwner();

        /** The queue's current item, asked the way the view asks it when it binds. Null when there is none. */
        @Nullable
        Object rvxCurrentItem();

        /** The boolean the click listener tests before it shows its message: 1 or 0, or -1 when not readable. */
        int rvxGate();
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
    private static final QueueLogBudget pillClickLog = new QueueLogBudget();
    private static volatile boolean alive;
    /** The ids of the row the user chose, until the queue it starts is built; null when none is awaited. */
    @Nullable
    private static List<String> expectedStart;
    private static long expectedStartAt;

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
            Tracked known;
            synchronized (tracked) {
                known = find(item);
                // A renderer this class put there is not a new one.
                if (known != null && (shown == known.song || shown == known.video)) return;
            }
            if (known != null && keepsSong(known, shown)) return;

            if (known != null) {
                synchronized (tracked) {
                    tracked.remove(known);
                }
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
     * The app copies the renderer an item shows (to set its click tracking) and gives the copy back to the item. For
     * an item that shows its song that is a copy of the song, which names the downloaded song itself and so is no
     * row to track anew: the tracked item keeps its video and takes the copy as its song.
     *
     * @return True when {@code shown} is such a copy and the entry was updated.
     */
    private static boolean keepsSong(Tracked known, Object shown) {
        if (!(shown instanceof MessageLite message)) return false;

        TapCommand copy = TapCommand.parse(message.toByteArray());
        if (!AudioFirstLogic.RENDERS_SONG.equals(AudioFirstLogic.renderedAs(copy.videoId, known.songId,
                known.videoId))) {
            return false;
        }

        Object item = known.get();
        if (item == null) return false;

        synchronized (tracked) {
            int position = tracked.indexOf(known);
            if (position < 0) return false;

            tracked.set(position, new Tracked(item, known.video, shown, known.videoId, known.songId));
        }
        Logger.printDebug(() -> "AudioFirst: queue item kept song=" + known.songId + " (the app copied its renderer)");
        return true;
    }

    /**
     * Injection point: the start of the click listener of the pill, before its own gate. When the item being played
     * is tracked, the click is this class's: the mode goes to the other kind (the items show its renderer) and the
     * toggle's owner navigates to the item's own watch endpoint, which the app plays in that mode. Any other click is
     * the app's.
     *
     * @param view The toggle's view, which the patch gave the {@link PillView} methods.
     * @return True if the click was taken, so the app's must not run.
     */
    public static boolean onPillClick(@Nullable Object view) {
        try {
            if (!(view instanceof PillView pill) || !AudioFirst.supported()
                    || !Settings.START_DOWNLOADED_IN_AUDIO.get()) {
                return false;
            }

            Object current = pill.rvxCurrentItem();
            Tracked entry;
            Tracked playing;
            synchronized (tracked) {
                entry = current == null ? null : find(current);
                // The queue's current item can be missing while a queue is rebuilt: the song being played is the
                // fallback.
                playing = entry != null ? null : findPlaying(VideoInformation.getVideoId(),
                        VideoInformation.getPlayerResponseVideoId());
                if (entry == null) entry = playing;
            }
            String via = AudioFirstLogic.lookupSource(entry != null && playing == null, playing != null);
            Object item = entry == null ? null : entry.get();
            Object owner = pill.rvxOwner();
            String was = AudioFirst.liveMode();
            if (item == null || !(owner instanceof PillOwner)) {
                logClick(entry, via, current, pill, was, "stock");
                return false;
            }

            if (owner instanceof CrossfadeManager.VideoToggleAccess
                    && CrossfadeManager.shouldBlockVideoToggle(owner)) {
                logClick(entry, via, current, pill, was, "blocked-by-crossfade");
                return true;
            }

            AudioFirst.leaveTapsAlone();
            String now = AudioFirst.togglePillMode();
            if (now == null) {
                logClick(entry, via, current, pill, was, "stock:no-mode-holder");
                return false;
            }

            logClick(entry, via, current, pill, was, "takeover mode=" + was + "->" + now);
            expectStart(entry.songId, entry.videoId);
            ((PillOwner) owner).rvxNavigate(item);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "AudioFirst: pill click failure", ex);
            return false;
        }
    }

    private static void logClick(@Nullable Tracked entry, String via, @Nullable Object current, PillView pill,
                                 String mode, String outcome) {
        if (!pillClickLog.allow(SystemClock.elapsedRealtime())) return;

        int items;
        synchronized (tracked) {
            items = tracked.size();
        }
        Logger.printDebug(() -> "AudioFirst: pill click item=" + (entry == null ? null : entry.videoId)
                + " via=" + via + " class=" + simpleName(current) + " tracked=" + (entry != null)
                + " known=" + items + " gate=" + pill.rvxGate() + " mode=" + mode + " outcome=" + outcome);
    }

    private static String simpleName(@Nullable Object object) {
        return object == null ? "null" : object.getClass().getSimpleName();
    }

    /** The newest tracked item whose song or video is one of the ids being played. */
    @Nullable
    private static Tracked findPlaying(String... ids) {
        synchronized (tracked) {
            for (int i = tracked.size() - 1; i >= 0; i--) {
                Tracked entry = tracked.get(i);
                if (entry.get() == null) continue;

                for (String id : ids) {
                    if (id != null && !id.isEmpty() && (id.equals(entry.songId) || id.equals(entry.videoId))) {
                        return entry;
                    }
                }
            }
        }
        return null;
    }

    /**
     * The row the user chose starts a new queue, whose items are not built yet: it starts at the item known by one of
     * these ids, see {@link #startIndex}.
     */
    static void expectStart(String... ids) {
        synchronized (tracked) {
            expectedStart = List.of(ids);
            expectedStartAt = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Injection point: the app has built the items of a queue from a server response, and the position it starts at.
     * The response to a song's id marks no item as selected, so the app starts at the first item; the chosen row is
     * the one the user tapped or pressed the pill on.
     *
     * @return The position the queue starts at.
     */
    public static int startIndex(@Nullable List<?> items, int index) {
        try {
            if (items == null || !AudioFirst.supported() || !Settings.START_DOWNLOADED_IN_AUDIO.get()) return index;

            List<String> wanted;
            List<List<String>> itemIds = new ArrayList<>(items.size());
            synchronized (tracked) {
                if (expectedStart == null) return index;

                if (SystemClock.elapsedRealtime() - expectedStartAt > AudioFirstLogic.ARM_TTL_MILLIS) {
                    expectedStart = null;
                    return index;
                }
                wanted = expectedStart;
                for (Object item : items) {
                    Tracked entry = item == null ? null : find(item);
                    itemIds.add(entry == null ? List.of() : List.of(entry.videoId, entry.songId));
                }
            }

            int start = AudioFirstLogic.queueStart(index, itemIds, wanted);
            if (start != index || itemIds.stream().anyMatch(ids -> !ids.isEmpty())) {
                synchronized (tracked) {
                    expectedStart = null;
                }
                Logger.printDebug(() -> "AudioFirst: queue start " + index + " -> " + start + " of " + items.size()
                        + " items, wanted=" + wanted);
            }
            return start;
        } catch (Exception ex) {
            Logger.printException(() -> "AudioFirst: queue start failure", ex);
            return index;
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
