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

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** The first stock result or connectivity takeover wins; late stock completion cannot replace a local response. */
final class PlayerFallbackFuture extends ReplayFuture implements PendingPlaybackRequests.Entry {
    interface Recovery {
        void recover(ReplayFuture result, Throwable failure);
    }

    private final ListenableFuture<?> stock;
    private final Executor executor;
    private final Recovery recovery;
    private final BiConsumer<ReplayFuture, Object> success;
    private boolean selected;
    private Object request;
    private Object ownership;
    private boolean next;
    private volatile PendingPlaybackRequests registry;

    private PlayerFallbackFuture(ListenableFuture<?> stock, Executor executor, Recovery recovery,
                                 BiConsumer<ReplayFuture, Object> success) {
        this.stock = stock;
        this.executor = executor;
        this.recovery = recovery;
        this.success = success;
        stock.addListener(this::stockCompleted, Runnable::run);
        addListener(() -> {
            PendingPlaybackRequests owner = registry;
            if (owner != null) owner.remove(this);
            if (isCancelled()) stock.cancel(false);
        }, Runnable::run);
    }

    static PlayerFallbackFuture recoverAsync(ListenableFuture<?> stock, Executor executor, Recovery recovery) {
        return recoverAsync(stock, executor, recovery, ReplayFuture::set);
    }

    static PlayerFallbackFuture recoverAsync(ListenableFuture<?> stock, Executor executor, Recovery recovery,
                                             BiConsumer<ReplayFuture, Object> success) {
        return new PlayerFallbackFuture(stock, executor, recovery, success);
    }

    /** A missed deadline does not claim a failed local lookup or cancel the native request. */
    void tryFallback(Supplier<Object> local) {
        if (!pending()) return;
        Object value = local.get();
        if (value != null && select()) PlayerFallback.setLocal(this, value);
    }

    void watch(Object request, Object ownership, PendingPlaybackRequests registry, boolean next) {
        this.request = request;
        this.ownership = ownership;
        this.next = next;
        this.registry = registry;
        registry.add(this);
    }

    private synchronized boolean select() {
        if (selected || isDone()) return false;
        selected = true;
        return true;
    }

    private void stockCompleted() {
        noteStockAnswer();
        if (!select()) return;
        try {
            success.accept(this, stock.get());
        } catch (CancellationException cancelled) {
            cancel(false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            setException(interrupted);
        } catch (ExecutionException failed) {
            recover(failed.getCause());
        } catch (RuntimeException failure) {
            setException(failure);
        }
    }

    /**
     * A stock player response while the upstream is called dead hints that it may be back. It is no proof (a
     * response the stock lookup read from its offline store carries the copy's marker and says nothing at all),
     * so it only asks for a probe on the default network.
     */
    private void noteStockAnswer() {
        if (!UpstreamProbe.dead() || !stock.isDone() || stock.isCancelled()) return;

        Object value;
        try {
            value = stock.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException | RuntimeException failed) {
            return;
        }
        if (value == null || value instanceof StreamSwap.Response response
                && OfflineReplayLogic.hasOfflineMarker(response.rvxBytes())) return;

        UpstreamProbe.noteStockAnswer();
    }

    private void recover(Throwable failure) {
        executor.execute(() -> {
            if (isCancelled()) return;
            try {
                recovery.recover(this, failure);
            } catch (RuntimeException unavailable) {
                setException(failure);
            }
        });
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
        return select();
    }

    @Override
    public void cancelUnavailable() {
        cancel(false);
    }

    @Override
    public synchronized boolean pending() {
        return !selected && !isDone();
    }

    @Override
    public boolean recover(PlaybackConnectionLoss loss) {
        if (!select()) return false;
        recover((Throwable) loss);
        // Do not cancel a shared transport here: its sibling next lane may still be subscribing.
        // The stock result is ignored after selection; explicit native/user cancellation still propagates.
        return true;
    }
}
