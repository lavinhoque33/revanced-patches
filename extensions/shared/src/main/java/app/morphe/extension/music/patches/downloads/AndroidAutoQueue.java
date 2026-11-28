/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-26
 * Modified by lavinhoque33, 2026-11-11.
 */
package app.morphe.extension.music.patches.downloads;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.utils.Logger;
import app.morphe.extension.shared.utils.Utils;

/** Inserts the saved rows into the selected MediaSession's committed native queue, never a phone dispatcher. */
public final class AndroidAutoQueue {
    public interface Session {
        Owner rvxAutoQueueOwner();
    }

    public interface Owner {
        Executor rvxAutoExecutor();
        Object rvxAutoStorage();
        Object rvxAutoModel();
        List<?> rvxAutoItems();
        int rvxAutoCurrent();
        String rvxAutoVideoId(Object item);
        long rvxAutoItemId(Object item);
        Future<?> rvxAutoResolve(byte[] command, List<String> songs);
        List<?> rvxAutoResolved(Object response);
        void rvxAutoInsert(Object response);
        void rvxAutoObserve(Observer observer);
        void rvxAutoStopObserving(Observer observer);
    }

    private static final class Selection {
        final long request;
        final String song;
        final List<String> following;
        final Runnable timeout = () -> failed(this, new TimeoutException("Saved queue did not commit in time"));
        Owner owner;
        boolean decided;
        Object initialModel;
        long initialItem = Long.MIN_VALUE;
        Object readyModel;
        long readyItem;
        boolean requested;
        Future<?> lookup;
        Observer observer;

        Selection(String song, List<String> following, long request) {
            this.song = song;
            this.following = following;
            this.request = request;
        }
    }

    /** Implements the version's native current-item listener through a patch-time bridge. */
    public static final class Observer {
        final Selection selection;
        final Owner owner;
        final Object storage;
        volatile boolean active = true;
        boolean registered;

        Observer(Selection selection, Owner owner, Object storage) {
            this.selection = selection;
            this.owner = owner;
            this.storage = storage;
        }

        public void onChanged(Object item, Object reason) {
            if (!active || pending != selection) return;
            // Native callbacks may own the model monitor. Always leave it before taking the store monitor.
            CallbackDispatch.MAIN.post(() -> owner.rvxAutoExecutor().execute(() -> observed(this, item)));
        }
    }

    private static final class CallbackDispatch {
        static final Handler MAIN = new Handler(Looper.getMainLooper());
    }

    private static volatile Selection pending;
    private static final String REQUEST = "morphe_auto_queue_request";
    private static final long BATCH_TIMEOUT_MS = 8_000;

    private AndroidAutoQueue() {}

    /** Patched only after every native owner/readiness bridge has been installed. */
    public static boolean supported() {
        return false;
    }

    public static void cancel() {
        Selection previous;
        synchronized (AndroidAutoQueue.class) {
            previous = pending;
            pending = null;
        }
        retire(previous);
    }

    private static void retire(Selection selection) {
        if (selection == null) return;
        CallbackDispatch.MAIN.removeCallbacks(selection.timeout);
        Observer observer;
        Future<?> lookup;
        synchronized (AndroidAutoQueue.class) {
            observer = selection.observer;
            if (observer == null || !observer.active) return;
            observer.active = false;
            lookup = selection.lookup;
            selection.lookup = null;
        }
        if (lookup != null) lookup.cancel(false);
        CallbackDispatch.MAIN.post(() -> observer.owner.rvxAutoExecutor().execute(() -> {
            synchronized (observer.storage) {
                if (observer.registered) {
                    observer.owner.rvxAutoStopObserving(observer);
                    observer.registered = false;
                }
            }
        }));
    }

    /** Called before the unhooked native callback: its session-controller entry supplies the actual owner. */
    public static Bundle prepare(String song, List<String> following, Bundle extras, long request) {
        if (!supported() || following.isEmpty()) return extras;

        Selection selection = new Selection(song, following, request);
        Selection previous;
        synchronized (AndroidAutoQueue.class) {
            previous = pending;
            CallbackDispatch.MAIN.postDelayed(selection.timeout, BATCH_TIMEOUT_MS);
            pending = selection;
        }
        retire(previous);
        Logger.printDebug(() -> "Android Auto queue: prepare request=" + request + " song=" + song
                + " following=" + following.size());
        Bundle correlated = extras == null ? new Bundle() : new Bundle(extras);
        correlated.putLong(REQUEST, request);
        return correlated;
    }

    public static void onSession(Session session, Object caller, Object command, Bundle extras) {
        Selection selection;
        synchronized (AndroidAutoQueue.class) {
            selection = pending;
            if (!matches(selection, extras) || selection.owner != null) return;
        }
        Owner owner = session.rvxAutoQueueOwner();
        Object storage = owner.rvxAutoStorage();
        Observer observer;
        synchronized (storage) {
            synchronized (AndroidAutoQueue.class) {
                if (pending != selection || selection.owner != null) return;
                observer = new Observer(selection, owner, storage);
                selection.owner = owner;
                selection.observer = observer;
            }
            if (observer.active) {
                observer.registered = true;
                owner.rvxAutoObserve(observer);
            }
            synchronized (AndroidAutoQueue.class) {
                if (pending != selection) return;
                selection.initialModel = owner.rvxAutoModel();
                List<?> items = owner.rvxAutoItems();
                int current = owner.rvxAutoCurrent();
                if (current >= 0 && current < items.size()) {
                    selection.initialItem = owner.rvxAutoItemId(items.get(current));
                }
            }
        }
    }

    private static boolean matches(Selection selection, Bundle extras) {
        return selection != null && extras != null
                && selection.request == extras.getLong(REQUEST, Long.MIN_VALUE);
    }

    /** Captured at the native asynchronous decision entry, before its registers are reused. */
    public static Object onDecision(Session session, Object command, Bundle extras) {
        Selection selection;
        synchronized (AndroidAutoQueue.class) {
            selection = pending;
            if (!matches(selection, extras) || selection.owner == null) return null;
        }
        Owner owner = session.rvxAutoQueueOwner();
        synchronized (AndroidAutoQueue.class) {
            if (pending != selection || selection.owner != owner) return null;
            selection.decided = true;
            return selection;
        }
    }

    /** Stock explicitly accepted its existing descriptor/queue, without scheduling a replacement. */
    public static void onReuse(Object request) {
        if (!(request instanceof Selection selection)) return;
        Owner owner = selection.owner;
        synchronized (selection.observer.storage) {
            synchronized (AndroidAutoQueue.class) {
                if (pending != selection || selection.readyModel != null
                        || owner.rvxAutoStorage() != selection.observer.storage) return;
                List<?> items = owner.rvxAutoItems();
                int current = owner.rvxAutoCurrent();
                if (current < 0 || current >= items.size()) return;
                Object item = items.get(current);
                if (!selection.song.equals(owner.rvxAutoVideoId(item))) return;
                selection.readyModel = owner.rvxAutoModel();
                selection.readyItem = owner.rvxAutoItemId(item);
            }
        }
        owner.rvxAutoExecutor().execute(() -> advance(selection));
    }

    /** One native batch request preserves the queue context and response anchors for the whole saved ordering. */
    private static void advance(Selection selection) {
        Owner owner = selection.owner;
        Future<?> lookup;
        try {
            synchronized (selection.observer.storage) {
                synchronized (AndroidAutoQueue.class) {
                    if (pending != selection || selection.readyModel == null || selection.requested) return;
                    if (!matchesCurrent(selection)) {
                        throw new IllegalStateException("Saved queue start was superseded before metadata lookup");
                    }
                    selection.requested = true;
                    lookup = Objects.requireNonNull(owner.rvxAutoResolve(
                            OnlineQueueCommand.playNext(selection.following.get(0)), selection.following));
                    selection.lookup = lookup;
                }
            }
        } catch (Exception ex) {
            failed(selection, ex);
            return;
        }
        if (pending != selection) {
            if (lookup != null) lookup.cancel(false);
            return;
        }
        Utils.runOnBackgroundThread(() -> resolved(selection, lookup));
    }

    private static void resolved(Selection selection, Future<?> lookup) {
        try {
            Object response = lookup.get(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            List<?> items = selection.owner.rvxAutoResolved(response);
            if (items.size() != selection.following.size()) {
                throw new IllegalStateException("Native batch did not return all requested saved rows");
            }
            for (int index = 0; index < items.size(); index++) {
                if (!selection.following.get(index).equals(selection.owner.rvxAutoVideoId(items.get(index)))) {
                    throw new IllegalStateException("Native batch returned an unexpected saved-row order");
                }
            }
            synchronized (AndroidAutoQueue.class) {
                if (pending != selection || selection.lookup != lookup) return;
                selection.lookup = null;
            }
            selection.owner.rvxAutoExecutor().execute(() -> commit(selection, response));
        } catch (Exception ex) {
            failed(selection, ex);
        }
    }

    private static void failed(Selection selection, Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        synchronized (AndroidAutoQueue.class) {
            if (pending != selection) return;
            pending = null;
        }
        retire(selection);
        Logger.printException(() -> "Android Auto: could not queue saved rows", failure);
    }

    /** Caller holds the captured store and coordinator monitors. */
    private static boolean matchesCurrent(Selection selection) {
        Owner owner = selection.owner;
        if (owner.rvxAutoStorage() != selection.observer.storage) return false;
        List<?> items = owner.rvxAutoItems();
        int current = owner.rvxAutoCurrent();
        return current >= 0 && current < items.size()
                && canCommit(pending, selection, selection.readyModel, owner.rvxAutoModel(),
                selection.song, owner.rvxAutoVideoId(items.get(current)), selection.readyItem,
                owner.rvxAutoItemId(items.get(current)));
    }

    private static void observed(Observer observer, Object eventItem) {
        Selection selection = observer.selection;
        Owner owner = observer.owner;
        synchronized (observer.storage) {
            synchronized (AndroidAutoQueue.class) {
                if (!observer.active || pending != selection || selection.observer != observer
                        || !selection.decided || selection.readyModel != null
                        || owner.rvxAutoStorage() != observer.storage) return;
                Object model = owner.rvxAutoModel();
                List<?> items = owner.rvxAutoItems();
                int current = owner.rvxAutoCurrent();
                Object item = current >= 0 && current < items.size() ? items.get(current) : null;
                if (item == null || !selection.song.equals(owner.rvxAutoVideoId(item))) return;
                if (eventItem != item && (eventItem == null
                        || owner.rvxAutoItemId(eventItem) != owner.rvxAutoItemId(item))) return;
                if (!newQueue(selection.initialModel, selection.initialItem, model,
                        owner.rvxAutoItemId(item))) return;
                selection.readyModel = model;
                selection.readyItem = owner.rvxAutoItemId(item);
            }
        }
        advance(selection);
    }

    private static void commit(Selection selection, Object response) {
        Owner owner = selection.owner;
        try {
            synchronized (selection.observer.storage) {
                synchronized (AndroidAutoQueue.class) {
                    if (pending != selection) return;
                    if (!matchesCurrent(selection)) {
                        throw new IllegalStateException("Saved queue start was superseded before insertion");
                    }
                    int itemCount = owner.rvxAutoItems().size();
                    owner.rvxAutoInsert(response);
                    pending = null;
                    Logger.printDebug(() -> "Android Auto: committed "
                            + (owner.rvxAutoItems().size() - itemCount) + " of " + selection.following.size()
                            + " saved rows after " + selection.song);
                }
            }
        } catch (RuntimeException ex) {
            failed(selection, ex);
        } finally {
            if (pending != selection) retire(selection);
        }
    }

    static boolean newQueue(Object initialModel, long initialItem, Object model, long item) {
        return initialModel != model || initialItem != item;
    }

    static boolean canCommit(Object current, Object selection, Object readyModel, Object model,
                             String expected, String playing, long readyItem, long currentItem) {
        return selection != null && current == selection && readyModel != null && readyModel == model
                && readyItem == currentItem && expected != null && expected.equals(playing);
    }
}
