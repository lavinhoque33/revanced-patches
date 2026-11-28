/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Modified by lavinhoque33, 2026-11-27
 * Modified by lavinhoque33, 2026-11-26.
 * Modified by lavinhoque33, 2026-11-14.
 * Written by lavinhoque33, 2026-11-14.
 */
package app.morphe.extension.music.patches.downloads;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import app.morphe.extension.shared.utils.Utils;

/** Patched into a native Observable subclass; construction never subscribes or starts a request. */
@SuppressWarnings("unused")
public final class PlayerStreamRace {
    public interface NativeCalls {
        void rvxSubscribe(Object source, Object observer);
        void rvxSubscribed(Object observer, Object disposable);
        void rvxNext(Object observer, Object value);
        void rvxError(Object observer, Throwable failure);
        void rvxComplete(Object observer);
        void rvxDispose(Object disposable);
        boolean rvxDisposed(Object disposable);
    }

    private final Object request;
    private final boolean next;
    private final Object source;

    public PlayerStreamRace(Object request, boolean next, Object source) {
        this.request = request;
        this.next = next;
        this.source = source;
    }

    public void subscribe(Object observer) {
        NativeCalls calls = (NativeCalls) (Object) this;
        calls.rvxSubscribe(source, new Subscription(request, PlayerFallback.ownership(request), next, calls, observer));
    }

    /** Native Observer and Disposable are installed by the same versioned bytecode adapter. */
    public static final class Subscription extends PlaybackStreamState implements PendingPlaybackRequests.Entry {
        private final Object request;
        private final Object ownership;
        private final boolean next;
        private final NativeCalls calls;
        private final Object observer;
        private volatile Object upstream;
        private volatile ListenableFuture<?> local;
        private ArrayDeque<Runnable> delivery;

        Subscription(Object request, Object ownership, boolean next, NativeCalls calls, Object observer) {
            this.request = request;
            this.ownership = ownership;
            this.next = next;
            this.calls = calls;
            this.observer = observer;
        }

        public void stockSubscribe(Object disposable) {
            upstream = disposable;
            if (disposed()) {
                calls.rvxDispose(disposable);
                return;
            }
            calls.rvxSubscribed(observer, this);
            if (!disposed() && ownership != null) PlaybackConnectivity.watch(this);
            // Race playlist-only Watch Next only at its producer, where the player transform consumes the same reply.
            if (!next) PlayerFallback.scheduleDeadline(request, false, this::pending, this::tryFallback);
        }

        public void stockNext(Object value) {
            if (!stockValue()) return;
            finished();
            UpstreamProbe.noteStockAnswer();
            emit(() -> {
                calls.rvxNext(observer, next ? value : StreamSwap.prepareOnline(value));
                notePublished(value);
            }, !next && StreamSwap.needsWorker(value));
        }

        /** A player answer was handed to the app: the start it belongs to works. */
        private void notePublished(Object value) {
            if (!next && value != null) PlayerFallback.notePublished(request);
        }

        public void stockError(Throwable failure) {
            if (!stockTerminal()) return;
            finished();
            emit(() -> calls.rvxError(observer, failure), false);
        }

        public void stockComplete() {
            if (!stockTerminal()) return;
            finished();
            emit(() -> calls.rvxComplete(observer), false);
        }

        /** Once preparation moves off main, keep subsequent values and terminal signals in native order. */
        private void emit(Runnable event, boolean needsWorker) {
            synchronized (this) {
                if (disposed()) return;
                if (delivery != null) {
                    delivery.addLast(event);
                    return;
                }

                if (needsWorker) {
                    delivery = new ArrayDeque<>();
                    delivery.addLast(event);
                    Utils.runOnBackgroundThread(this::drain);
                    return;
                }
            }
            if (!disposed()) event.run();
        }

        private void drain() {
            for (;;) {
                Runnable task;
                synchronized (this) {
                    task = disposed() ? null : delivery.pollFirst();
                    if (task == null) {
                        delivery = null;
                        return;
                    }
                }
                if (!disposed()) task.run();
            }
        }

        private void finished() {
            if (ownership != null) PlaybackConnectivity.finished(this);
        }

        public void dispose() {
            if (!disposeOnce()) return;
            finished();
            ListenableFuture<?> result = local;
            if (result != null) result.cancel(false);
            Object disposable = upstream;
            if (disposable != null) calls.rvxDispose(disposable);
        }

        public boolean isDisposed() {
            if (disposed()) return true;
            Object disposable = upstream;
            return !locallySelected() && disposable != null && calls.rvxDisposed(disposable);
        }

        @Override
        public Object request() {
            return request;
        }

        @Override
        public Object ownership() {
            return ownership;
        }

        @Override
        public boolean player() {
            return !next;
        }

        @Override
        public boolean selectUnavailable() {
            return selectLocal();
        }

        @Override
        public void cancelUnavailable() {
            dispose();
        }

        @Override
        public boolean pending() {
            return pendingResponse();
        }

        @Override
        public boolean recover(PlaybackConnectionLoss loss) {
            if (!selectLocal()) return false;
            ListenableFuture<?> result = PlayerFallback.recoverFuture(request, next, loss);
            local = result;
            if (disposed()) result.cancel(false);
            result.addListener(() -> localCompleted(result), Runnable::run);
            return true;
        }

        private void tryFallback(Supplier<Object> candidate) {
            if (!pending()) return;
            Object value = candidate.get();
            if (value != null && selectLocal()) deliverLocal(value, true);
        }

        private void localCompleted(ListenableFuture<?> result) {
            if (disposed()) return;
            try {
                Object value = result.get();
                deliverLocal(value, false);
            } catch (CancellationException cancelled) {
                dispose();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                localError(interrupted);
            } catch (ExecutionException failure) {
                localError(failure.getCause());
            }
        }

        private void deliverLocal(Object value, boolean prepared) {
            if (!localTerminal()) return;
            if (prepared && value instanceof StreamSwap.Response media) {
                StreamSwap.publishStored(value);
                NoConnectionSheet.onCopyReplayed(media.rvxVideoId());
            }
            finished();
            calls.rvxNext(observer, value);
            notePublished(value);
            if (!disposed()) calls.rvxComplete(observer);
            dispose();
        }

        private void localError(Throwable failure) {
            if (!localTerminal()) return;
            finished();
            calls.rvxError(observer, failure);
            dispose();
        }
    }
}
