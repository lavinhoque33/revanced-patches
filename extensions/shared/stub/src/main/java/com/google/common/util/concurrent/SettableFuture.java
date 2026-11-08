package com.google.common.util.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Compile-only class supplied by YouTube at runtime (public API of Guava, kept by the app under this name). */
public class SettableFuture<V> implements ListenableFuture<V> {
    public static <V> SettableFuture<V> create() {
        throw new UnsupportedOperationException("Stub!");
    }

    public boolean set(V value) {
        throw new UnsupportedOperationException("Stub!");
    }

    public boolean setException(Throwable throwable) {
        throw new UnsupportedOperationException("Stub!");
    }

    @Override
    public void addListener(Runnable listener, Executor executor) {
        throw new UnsupportedOperationException("Stub!");
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        throw new UnsupportedOperationException("Stub!");
    }

    @Override
    public boolean isCancelled() {
        throw new UnsupportedOperationException("Stub!");
    }

    @Override
    public boolean isDone() {
        throw new UnsupportedOperationException("Stub!");
    }

    @Override
    public V get() {
        throw new UnsupportedOperationException("Stub!");
    }

    @Override
    public V get(long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException("Stub!");
    }
}
