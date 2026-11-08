package com.huidu.musicboxplus.core.song;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns explicit loader sessions; neither capture nor a rejected submission restarts the pool. */
final class ManagedSongLoader {
    private final Object monitor = new Object();
    private final int threads;
    private final int queueCapacity;
    private final long shutdownTimeout;
    private final TimeUnit shutdownTimeoutUnit;
    private final ThreadFactory threadFactory;
    private final ThreadLocal<Boolean> worker = ThreadLocal.withInitial(() -> false);
    private Session current = new Session(this);

    ManagedSongLoader(int threads, int queueCapacity, long shutdownTimeout,
                      TimeUnit shutdownTimeoutUnit, ThreadFactory threadFactory) {
        if (threads < 1 || queueCapacity < 1 || shutdownTimeout < 0L) {
            throw new IllegalArgumentException("Invalid song loader bounds");
        }
        this.threads = threads;
        this.queueCapacity = queueCapacity;
        this.shutdownTimeout = shutdownTimeout;
        this.shutdownTimeoutUnit = java.util.Objects.requireNonNull(shutdownTimeoutUnit);
        this.threadFactory = java.util.Objects.requireNonNull(threadFactory);
    }

    static final class Session {
        private final ManagedSongLoader owner;
        private final Set<Submission<?>> submissions = new HashSet<>();
        private boolean accepting = true;
        private ThreadPoolExecutor executor;

        private Session(ManagedSongLoader owner) {
            this.owner = owner;
        }
    }

    Session captureSession() {
        synchronized (monitor) {
            return current;
        }
    }

    /** Only an explicit enable/reset may rearm admission, and the replacement pool stays lazy. */
    void reset() {
        synchronized (monitor) {
            if (!current.accepting) {
                current = new Session(this);
            }
        }
    }

    boolean isActive(Session session) {
        synchronized (monitor) {
            return isActiveLocked(session);
        }
    }

    private boolean isActiveLocked(Session session) {
        return session != null && session.owner == this && current == session && session.accepting;
    }

    /** Every admitted task has its own future, including tasks discarded by shutdownNow(). */
    <T> CompletableFuture<T> submit(Session session, Callable<T> action) {
        java.util.Objects.requireNonNull(action);
        Submission<T> submission = new Submission<>(session, action);
        ThreadPoolExecutor executor = null;
        synchronized (monitor) {
            if (isActiveLocked(session)) {
                if (session.executor == null) {
                    session.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(queueCapacity), threadFactory,
                            new ThreadPoolExecutor.AbortPolicy());
                }
                executor = session.executor;
                session.submissions.add(submission);
            }
        }
        // Completion can synchronously run arbitrary/reentrant callbacks. Never do it under monitor.
        if (executor == null) {
            submission.future.completeExceptionally(closedSession());
            return submission.future;
        }
        submission.future.whenComplete((value, failure) -> {
            synchronized (monitor) {
                session.submissions.remove(submission);
            }
        });
        try {
            // shutdown may win this race; rejection still terminates the tracked future.
            executor.execute(submission);
        } catch (RejectedExecutionException failure) {
            submission.future.completeExceptionally(failure);
        }
        return submission.future;
    }

    /** Serialize only field publication with close/reset, never future completion or user callbacks. */
    void publish(Session session, Runnable publication) {
        synchronized (monitor) {
            if (!isActiveLocked(session)) {
                throw closedSession();
            }
            publication.run();
        }
    }

    void shutdown() {
        ThreadPoolExecutor executor;
        List<Submission<?>> unfinished;
        synchronized (monitor) {
            if (!current.accepting) {
                return;
            }
            current.accepting = false;
            executor = current.executor;
            unfinished = new ArrayList<>(current.submissions);
        }
        // Close admission before interrupting workers or invoking any completion callback.
        if (executor != null) {
            executor.shutdownNow();
        }
        for (Submission<?> submission : unfinished) {
            submission.future.completeExceptionally(closedSession());
        }
        // A completion callback may call shutdown from a loader worker. It cannot await itself.
        if (executor != null && !worker.get()) {
            try {
                executor.awaitTermination(shutdownTimeout, shutdownTimeoutUnit);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RejectedExecutionException closedSession() {
        return new RejectedExecutionException("Song loader session is closed");
    }

    private final class Submission<T> implements Runnable {
        private final Session session;
        private final Callable<T> action;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private Submission(Session session, Callable<T> action) {
            this.session = session;
            this.action = action;
        }

        @Override
        public void run() {
            worker.set(true);
            try {
                if (future.isDone() || !isActive(session)) {
                    future.completeExceptionally(closedSession());
                    return;
                }
                T value = action.call();
                if (isActive(session)) {
                    future.complete(value);
                } else {
                    future.completeExceptionally(closedSession());
                }
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            } finally {
                worker.remove();
            }
        }
    }
}
