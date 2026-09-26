package com.huidu.musicboxplus.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.huidu.musicboxplus.core.nbs.NbsCorpus;
import com.huidu.musicboxplus.core.nbs.NbsReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

// The clock is driven through step() with a controlled time source, so scheduling is checked
// without starting a thread or waiting on real time.
class PlaybackClockTest {

    private static CompiledSong firstSong() throws Exception {
        try (Stream<Path> stream = Files.list(NbsCorpus.BUNDLED)) {
            Path file = stream.filter(p -> p.toString().endsWith(".nbs")).sorted().findFirst().orElseThrow();
            return CompiledSong.compile(NbsReader.read(file));
        }
    }

    private static final class Recorder implements PlaybackClock.Target {
        final PlaybackCursor cursor;
        final List<Integer> ticks = new ArrayList<>();
        int finishedCalls;
        boolean alive = true;

        Recorder(CompiledSong song, boolean playing) {
            this.cursor = new PlaybackCursor(song);
            this.cursor.setPlaying(playing);
        }

        @Override
        public PlaybackCursor cursor() {
            return cursor;
        }

        @Override
        public void playTicks(int firstTick, int count) {
            for (int i = 0; i < count; i++) {
                ticks.add(firstTick + i);
            }
        }

        @Override
        public void songFinished() {
            finishedCalls++;
        }

        @Override
        public boolean alive() {
            return alive;
        }
    }

    @Test
    void aTargetThatIsNotPlayingNeverSchedulesAWakeUp() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        Recorder idle = new Recorder(firstSong(), false);
        clock.register(idle);

        now.addAndGet(1_000_000_000L);
        long sleep = clock.step();

        assertEquals(Long.MAX_VALUE, sleep,
                "an idle player must not keep the clock awake; polling for it is what a "
                        + "thread-per-player design has to do");
        assertTrue(idle.ticks.isEmpty());
    }

    @Test
    void sleepIsGovernedByTheSoonestTarget() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");

        Recorder slow = new Recorder(firstSong(), true);
        slow.cursor.setSpeed(0.5f);
        Recorder fast = new Recorder(firstSong(), true);
        fast.cursor.setSpeed(2.0f);
        clock.register(slow);
        clock.register(fast);

        long sleep = clock.step();
        assertEquals(fast.cursor.nanosUntilNextTick(), sleep,
                "the clock must wake for whichever song needs the next tick first");
        assertTrue(sleep < slow.cursor.nanosUntilNextTick());
    }

    // Each target times from when it joined, so registering during a long sleep must not hand
    // the newcomer the whole elapsed span as a backlog.
    @Test
    void aTargetRegisteredLateStartsFromTheMomentItJoined() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        Recorder first = new Recorder(firstSong(), true);
        clock.register(first);

        now.addAndGet(2_000_000_000L);
        Recorder late = new Recorder(firstSong(), true);
        clock.register(late);

        clock.step();
        assertTrue(late.ticks.size() <= 1,
                "a freshly registered player received " + late.ticks.size()
                        + " ticks at once from time it was not present for");
    }

    // Players register on construction and again whenever they are set playing, and every
    // AbstractBlockPlayer constructor does both. Two entries for one target advance the SAME
    // cursor with the same elapsed time, so the song runs at double speed and every note is
    // emitted twice -- audible immediately, but from the outside it looks like a tempo bug
    // rather than a duplicate registration.
    @Test
    void registeringTheSameTargetTwiceDoesNotDoubleItsSpeed() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        Recorder target = new Recorder(firstSong(), true);

        clock.register(target);
        clock.register(target);
        assertEquals(1, clock.targetCount(), "the same target must not occupy two entries");

        now.addAndGet(target.cursor.tickPeriodNanos() * 4);
        clock.step();

        assertEquals(List.of(0, 1, 2, 3), target.ticks,
                "four tick periods must produce exactly four ticks, each once");
        assertEquals(3, target.cursor.tick(), "the cursor advanced further than the elapsed time");
    }

    // Resuming after every target has been paused: the clock parks, so the entry's timestamp is
    // as old as the pause. Handing that elapsed time to the cursor would skip the song forward
    // by however long it was paused.
    @Test
    void resumingAfterALongPauseDoesNotSkipAhead() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        Recorder target = new Recorder(firstSong(), false);
        clock.register(target);

        assertEquals(Long.MAX_VALUE, clock.step(), "a paused target parks the clock");
        now.addAndGet(600_000_000_000L);

        target.cursor.setPlaying(true);
        clock.register(target);
        clock.step();

        assertTrue(target.cursor.tick() <= 0,
                "resuming skipped to tick " + target.cursor.tick() + " instead of starting where it paused");
    }

    @Test
    void deadTargetsAreDroppedWithoutFurtherCallbacks() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        Recorder target = new Recorder(firstSong(), true);
        clock.register(target);

        now.addAndGet(200_000_000L);
        clock.step();
        int seen = target.ticks.size();
        assertTrue(seen > 0);

        target.alive = false;
        now.addAndGet(200_000_000L);
        clock.step();

        assertEquals(seen, target.ticks.size(), "a dead target must stop receiving ticks");
        assertEquals(0, clock.targetCount(), "a dead target must be dropped from the clock");
    }

    @Test
    void finishReportsOnceTheSongRunsOut() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        CompiledSong song = firstSong();
        Recorder target = new Recorder(song, true);
        clock.register(target);

        for (int i = 0; i < 100_000 && !target.cursor.finished(); i++) {
            now.addAndGet(500_000_000L);
            clock.step();
        }

        assertTrue(target.cursor.finished());
        assertTrue(target.finishedCalls > 0, "songFinished was never reported");
        assertEquals(song.lengthTicks(), target.ticks.get(target.ticks.size() - 1));
    }

    @Test
    void oneTargetThrowingDoesNotStopTheOthers() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        CompiledSong song = firstSong();

        Recorder healthy = new Recorder(song, true);
        PlaybackClock.Target broken = new PlaybackClock.Target() {
            private final PlaybackCursor cursor = new PlaybackCursor(song);

            {
                cursor.setPlaying(true);
            }

            @Override
            public PlaybackCursor cursor() {
                return cursor;
            }

            @Override
            public void playTicks(int firstTick, int count) {
                throw new IllegalStateException("boom");
            }

            @Override
            public void songFinished() {
            }

            @Override
            public boolean alive() {
                return true;
            }
        };

        clock.register(broken);
        clock.register(healthy);
        now.addAndGet(200_000_000L);

        // step() itself propagates, which is what the thread loop catches. What matters is
        // that the failure is confined to the one target.
        try {
            clock.step();
        } catch (IllegalStateException expected) {
            // The broken target threw; drive the rest again with it removed.
            clock.unregister(broken);
            now.addAndGet(200_000_000L);
            clock.step();
        }
        assertFalse(healthy.ticks.isEmpty(), "the healthy player never got a tick");
    }

    @Test
    void manyTargetsShareTheOneThread() throws Exception {
        AtomicLong now = new AtomicLong();
        PlaybackClock clock = new PlaybackClock(now::get, "test");
        CompiledSong song = firstSong();

        List<Recorder> targets = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            Recorder r = new Recorder(song, true);
            targets.add(r);
            clock.register(r);
        }

        now.addAndGet(500_000_000L);
        clock.step();

        for (Recorder r : targets) {
            assertFalse(r.ticks.isEmpty(), "every registered player must be advanced");
        }
        assertEquals(200, clock.targetCount());
    }
}
