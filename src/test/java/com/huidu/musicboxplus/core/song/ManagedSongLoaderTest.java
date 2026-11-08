package com.huidu.musicboxplus.core.song;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ManagedSongLoaderTest {
    private static ManagedSongLoader loader(int queueCapacity, AtomicInteger threadsCreated) {
        return new ManagedSongLoader(1, queueCapacity, 0L, TimeUnit.MILLISECONDS, action -> {
            threadsCreated.incrementAndGet();
            Thread thread = new Thread(action, "test-song-loader");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3L, TimeUnit.SECONDS), "latch timed out");
    }

    private static void assertRejected(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(3L, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof RejectedExecutionException,
                "expected a definite rejection, got " + failure.getCause());
    }

    @Test
    void initiallyLazyAndShutdownNeverImplicitlyCreatesAnotherPool() throws Exception {
        AtomicInteger threads = new AtomicInteger();
        ManagedSongLoader loader = loader(2, threads);
        ManagedSongLoader.Session initial = loader.captureSession();
        assertEquals(0, threads.get());
        assertEquals(1, loader.submit(initial, () -> 1).get(3L, TimeUnit.SECONDS));
        assertEquals(1, threads.get());

        loader.shutdown();

        assertSame(initial, loader.captureSession());
        assertRejected(loader.submit(initial, () -> 2));
        assertRejected(loader.submit(loader.captureSession(), () -> 3));
        assertEquals(1, threads.get());
        loader.reset();
        ManagedSongLoader.Session replacement = loader.captureSession();
        assertNotSame(initial, replacement);
        assertEquals(1, threads.get(), "reset must leave the new pool lazy");
        try {
            assertEquals(4, loader.submit(replacement, () -> 4).get(3L, TimeUnit.SECONDS));
            assertEquals(2, threads.get());
        } finally {
            loader.shutdown();
        }
    }

    @Test
    void shuttingDownUnusedSessionDoesNotCreateItsPool() {
        AtomicInteger threads = new AtomicInteger();
        ManagedSongLoader loader = loader(1, threads);
        loader.shutdown();
        assertRejected(loader.submit(loader.captureSession(), () -> 1));
        assertEquals(0, threads.get());
    }

    @Test
    void shutdownCompletesRunningAndQueuedFuturesAndInterruptsWorker() throws Exception {
        ManagedSongLoader loader = loader(2, new AtomicInteger());
        ManagedSongLoader.Session session = loader.captureSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        CompletableFuture<Void> running = loader.submit(session, () -> {
            started.countDown();
            boolean finished = false;
            while (!finished) {
                try {
                    release.await();
                    finished = true;
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
            }
            return null;
        });
        await(started);
        CompletableFuture<Void> queued = loader.submit(session, () -> {
            queuedRan.set(true);
            return null;
        });
        CompletableFuture<Void> combined = CompletableFuture.allOf(running, queued);
        try {
            loader.shutdown();
            await(interrupted);
            // The running callable deliberately remains alive: its future must still finish now.
            assertRejected(running);
            assertRejected(queued);
            assertRejected(combined);
            assertFalse(queuedRan.get());
        } finally {
            release.countDown();
            loader.shutdown();
        }
    }

    @Test
    void oldSessionCannotSubmitOrPublishAfterExplicitReset() throws Exception {
        AtomicInteger threads = new AtomicInteger();
        ManagedSongLoader loader = loader(2, threads);
        ManagedSongLoader.Session stale = loader.captureSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attemptedLateWork = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Integer>> lateSubmission = new AtomicReference<>();
        AtomicBoolean published = new AtomicBoolean();
        AtomicBoolean publishRejected = new AtomicBoolean();
        CompletableFuture<Void> old = loader.submit(stale, () -> {
            started.countDown();
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Model a decoder which does not honor interruption promptly.
                }
            }
            lateSubmission.set(loader.submit(stale, () -> 99));
            try {
                loader.publish(stale, () -> published.set(true));
            } catch (RejectedExecutionException expected) {
                publishRejected.set(true);
            } finally {
                attemptedLateWork.countDown();
            }
            return null;
        });
        await(started);
        loader.shutdown();
        loader.reset();
        ManagedSongLoader.Session fresh = loader.captureSession();
        try {
            release.countDown();
            await(attemptedLateWork);
            assertRejected(old);
            assertRejected(lateSubmission.get());
            assertTrue(publishRejected.get());
            assertFalse(published.get());
            assertEquals(1, threads.get(), "stale work must not initialize the replacement pool");
            assertEquals(7, loader.submit(fresh, () -> 7).get(3L, TimeUnit.SECONDS));
            loader.publish(fresh, () -> published.set(true));
            assertTrue(published.get());
        } finally {
            release.countDown();
            loader.shutdown();
        }
    }

    @Test
    void fullQueueRejectsWithoutExecutingOnTheSubmittingThread() throws Exception {
        ManagedSongLoader loader = loader(1, new AtomicInteger());
        ManagedSongLoader.Session session = loader.captureSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejectedTaskRan = new AtomicBoolean();
        loader.submit(session, () -> {
            started.countDown();
            release.await();
            return null;
        });
        await(started);
        CompletableFuture<Integer> queued = loader.submit(session, () -> 1);
        try {
            CompletableFuture<Integer> rejected = loader.submit(session, () -> {
                rejectedTaskRan.set(true);
                return 2;
            });
            assertRejected(rejected);
            assertFalse(rejectedTaskRan.get());
            assertFalse(queued.isDone(), "the single queue slot must still belong to the queued task");
        } finally {
            release.countDown();
            loader.shutdown();
        }
    }

    @Test
    void shutdownFromWorkerDoesNotWaitForItsOwnTermination() throws Exception {
        ManagedSongLoader loader = new ManagedSongLoader(1, 1, 30L, TimeUnit.SECONDS, action -> {
            Thread thread = new Thread(action, "test-song-loader-self-shutdown");
            thread.setDaemon(true);
            return thread;
        });
        CountDownLatch shutdownReturned = new CountDownLatch(1);
        CompletableFuture<Void> running = loader.submit(loader.captureSession(), () -> {
            loader.shutdown();
            shutdownReturned.countDown();
            return null;
        });
        try {
            await(shutdownReturned);
            assertRejected(running);
        } finally {
            loader.shutdown();
        }
    }

    @Test
    void shutdownCallbacksCanReenterLifecycleFromAnotherThreadWithoutHoldingItsMonitor() throws Exception {
        ManagedSongLoader loader = loader(1, new AtomicInteger());
        ManagedSongLoader.Session session = loader.captureSession();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        loader.submit(session, () -> {
            started.countDown();
            release.await();
            return null;
        });
        await(started);
        CompletableFuture<Integer> queued = loader.submit(session, () -> 1);
        AtomicBoolean callbackRan = new AtomicBoolean();
        CompletableFuture<Integer> callback = queued.whenComplete((value, failure) -> {
            CountDownLatch resetCompleted = new CountDownLatch(1);
            Thread reentrant = new Thread(() -> {
                loader.reset();
                resetCompleted.countDown();
            }, "test-loader-reset");
            reentrant.setDaemon(true);
            reentrant.start();
            try {
                await(resetCompleted);
                callbackRan.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        });
        try {
            loader.shutdown();
            assertRejected(callback);
            assertTrue(callbackRan.get());
            assertNotSame(session, loader.captureSession());
        } finally {
            release.countDown();
            loader.shutdown();
        }
    }
}
