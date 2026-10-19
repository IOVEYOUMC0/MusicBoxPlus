package com.huidu.musicboxplus.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.nbs.NbsCorpus;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Restarting a clock whose previous thread never left step().
//
// shutdown() joins for a bounded 2 seconds. A target that blocks inside playTicks() for longer than
// that (a slow region dispatch, a plugin's listener) is still inside step() when shutdown() returns,
// and the next start() used to hand it a fresh `running == true` -- so the old thread resumed looping
// and two threads advanced the same cursors: every tick dispatched twice, at double speed. The clock
// now stamps each run loop with a session number that shutdown() bumps, so the old loop is retired
// even though it is still alive.
//
// This test has to block a real thread for longer than the join timeout, so it takes ~2.5 s.
class PlaybackClockRestartTest {

    private PlaybackClock clock;

    @AfterEach
    void stopClock() {
        if (clock != null) {
            clock.shutdown();
        }
    }

    @Test
    void aThreadStuckInPlayTicksWhenShutdownReturnsNeverDispatchesAgain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<Thread, AtomicInteger> callsPerThread = new ConcurrentHashMap<>();
        BlockingTarget target = new BlockingTarget(entered, release, callsPerThread);

        clock = new PlaybackClock(System::nanoTime, "MusicBox-ClockRestartTest");
        clock.register(target);
        clock.start();

        assertTrue(entered.await(5, TimeUnit.SECONDS), "the clock never dispatched a tick");

        // The thread that is parked inside playTicks() right now. shutdown() will give up on it.
        Thread retired = onlyCaller(callsPerThread);

        clock.shutdown();
        clock.start();

        // The restart must actually produce a new dispatching thread, otherwise the assertion below
        // would hold trivially (a clock that is simply stopped also never calls back again).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (callsPerThread.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(2, callsPerThread.size(),
                "the restarted clock never dispatched; the old thread would have been the only one");

        release.countDown();
        Thread.sleep(300);

        assertEquals(1, callsPerThread.get(retired).get(),
                "the retired clock thread kept dispatching ticks after a restart, so two threads are "
                        + "advancing the same cursors");
    }

    private static Thread onlyCaller(Map<Thread, AtomicInteger> callsPerThread) {
        Set<Thread> threads = callsPerThread.keySet();
        assertEquals(1, threads.size(), "more than one thread reached playTicks before the shutdown");
        return threads.iterator().next();
    }

    private static final class BlockingTarget implements PlaybackClock.Target {

        private final PlaybackCursor cursor;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final Map<Thread, AtomicInteger> callsPerThread;

        BlockingTarget(CountDownLatch entered, CountDownLatch release, Map<Thread, AtomicInteger> callsPerThread)
                throws Exception {
            this.cursor = new PlaybackCursor(firstSong());
            // A fast speed keeps the wait for the first dispatch short; it does not change what is
            // being tested.
            this.cursor.setSpeed(8.0f);
            this.cursor.setPlaying(true);
            this.entered = entered;
            this.release = release;
            this.callsPerThread = callsPerThread;
        }

        @Override
        public PlaybackCursor cursor() {
            return cursor;
        }

        @Override
        public void playTicks(int firstTick, int count) {
            callsPerThread.computeIfAbsent(Thread.currentThread(), t -> new AtomicInteger()).incrementAndGet();
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void songFinished() {
        }

        @Override
        public boolean alive() {
            return true;
        }
    }

    private static CompiledSong firstSong() throws Exception {
        try (Stream<Path> stream = Files.list(NbsCorpus.BUNDLED)) {
            Path file = stream.filter(p -> p.toString().endsWith(".nbs")).sorted().findFirst().orElseThrow();
            return CompiledSong.compile(NbsReader.read(file));
        }
    }
}
