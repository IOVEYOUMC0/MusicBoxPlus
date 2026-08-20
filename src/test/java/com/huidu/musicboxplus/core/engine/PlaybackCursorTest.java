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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PlaybackCursorTest {

    private static final long MS = 1_000_000L;

    private static CompiledSong firstSong() throws Exception {
        try (Stream<Path> stream = Files.list(NbsCorpus.BUNDLED)) {
            Path file = stream.filter(p -> p.toString().endsWith(".nbs")).sorted().findFirst().orElseThrow();
            return CompiledSong.compile(NbsReader.read(file));
        }
    }

    private static PlaybackCursor playing(CompiledSong song) {
        PlaybackCursor cursor = new PlaybackCursor(song);
        cursor.setPlaying(true);
        return cursor;
    }

    // Every tick handed out, in order, for as long as the cursor keeps producing them.
    private static List<Integer> drain(PlaybackCursor cursor, long stepNanos, int steps) {
        List<Integer> ticks = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            int count = cursor.advance(stepNanos);
            for (int k = 0; k < count; k++) {
                ticks.add(cursor.firstEmittedTick() + k);
            }
        }
        return ticks;
    }

    @Test
    void ticksAreMonotonicAndNeverRepeat() throws Exception {
        PlaybackCursor cursor = playing(firstSong());
        List<Integer> ticks = drain(cursor, 7 * MS, 2000);
        assertFalse(ticks.isEmpty(), "cursor produced no ticks at all");
        for (int i = 1; i < ticks.size(); i++) {
            assertEquals(ticks.get(i - 1) + 1, ticks.get(i),
                    "ticks must be consecutive and never repeat, broke at index " + i);
        }
        assertEquals(0, ticks.get(0), "playback starts at tick 0");
    }

    // Music follows real time, so a given wall-clock span yields the same number of ticks
    // however it is chopped up. Chopping it finely is what a server under load does.
    @Test
    void tickRateFollowsTheWallClockRegardlessOfStepSize() throws Exception {
        CompiledSong song = firstSong();
        long totalNanos = 2L * 1_000_000_000L;

        long coarse = drain(playing(song), 50 * MS, (int) (totalNanos / (50 * MS))).size();
        long fine = drain(playing(song), MS, (int) (totalNanos / (MS))).size();
        long expected = (long) (2.0 * song.ticksPerSecond());

        assertTrue(Math.abs(coarse - expected) <= 2,
                "50ms steps produced " + coarse + " ticks over 2s, expected about " + expected);
        assertTrue(Math.abs(fine - expected) <= 2,
                "1ms steps produced " + fine + " ticks over 2s, expected about " + expected);
    }

    // A freeze must not dump the whole backlog as sound. The position still has to end up
    // where the wall clock says, so the skipped ticks are dropped rather than queued.
    @Test
    void aLongStallSkipsAheadInsteadOfReplayingEverything() throws Exception {
        CompiledSong song = firstSong();
        PlaybackCursor cursor = playing(song);
        cursor.advance(MS);

        long stallNanos = 5L * 1_000_000_000L;
        int emitted = cursor.advance(stallNanos);

        assertTrue(emitted <= PlaybackCursor.MAX_CATCH_UP_TICKS,
                "a 5s stall emitted " + emitted + " ticks in one go");
        long expectedPosition = (long) (5.0 * song.ticksPerSecond());
        assertTrue(cursor.tick() >= expectedPosition - 2,
                "after the stall the cursor sits at " + cursor.tick()
                        + ", behind the wall clock position " + expectedPosition);
        assertEquals(cursor.tick() - emitted + 1, cursor.firstEmittedTick(),
                "the ticks played must be the most recent ones, not the oldest");
    }

    @Test
    void pausedTimeDoesNotBurstOnResume() throws Exception {
        PlaybackCursor cursor = playing(firstSong());
        cursor.advance(100 * MS);
        int before = cursor.tick();

        cursor.setPlaying(false);
        assertEquals(0, cursor.advance(3L * 1_000_000_000L), "a paused cursor must not advance");
        assertEquals(before, cursor.tick());

        cursor.setPlaying(true);
        assertEquals(0, cursor.advance(MS / 2), "resuming must not release the paused time");
    }

    @Test
    void changingSpeedKeepsThePositionAndChangesOnlyTheRate() throws Exception {
        CompiledSong song = firstSong();
        PlaybackCursor cursor = playing(song);
        drain(cursor, 10 * MS, 50);
        int position = cursor.tick();
        long singleSpeedPeriod = cursor.tickPeriodNanos();

        cursor.setSpeed(2.0f);
        assertEquals(position, cursor.tick(), "changing speed must not move the cursor");
        assertTrue(cursor.tickPeriodNanos() < singleSpeedPeriod,
                "doubling the speed must shorten the tick period");

        int fast = drain(cursor, 10 * MS, 50).size();
        cursor.setSpeed(0.5f);
        int slow = drain(cursor, 10 * MS, 50).size();
        assertTrue(fast > slow, "2x produced " + fast + " ticks, 0.5x produced " + slow);
    }

    @Test
    void seekMovesToTheRequestedTick() throws Exception {
        PlaybackCursor cursor = playing(firstSong());
        cursor.seek(500);
        List<Integer> ticks = drain(cursor, 200 * MS, 1);
        assertFalse(ticks.isEmpty());
        assertEquals(500, ticks.get(0), "the first tick after a seek is the one asked for");
    }

    // The playable range is closed at both ends: a song of length L plays ticks 0 through L.
    @Test
    void playbackCoversTheClosedRangeThenFinishes() throws Exception {
        CompiledSong song = firstSong();
        PlaybackCursor cursor = playing(song);
        List<Integer> ticks = drain(cursor, 500 * MS, 20_000);

        assertTrue(cursor.finished(), "cursor never reported the song as finished");
        assertEquals(0, ticks.get(0));
        assertEquals(song.lengthTicks(), ticks.get(ticks.size() - 1),
                "the last tick played must be the song's final tick");
        assertEquals(song.lengthTicks() + 1, ticks.size(),
                "a song of length L must play exactly L+1 ticks");
    }

    // Timing reads the song for its tempo, and NoteBlockAPI dereferenced it unconditionally in
    // its sleep calculation, so a player left without a song threw from the playback thread.
    @Test
    void aCursorWithoutASongIsInertRatherThanThrowing() {
        PlaybackCursor cursor = new PlaybackCursor(null);
        cursor.setPlaying(true);
        assertEquals(0, cursor.advance(1_000_000_000L));
        assertTrue(cursor.finished());
        assertEquals(Long.MAX_VALUE, cursor.nanosUntilNextTick());
        assertEquals(0, cursor.tickPeriodNanos());
        cursor.setSpeed(2.0f);
        cursor.seek(10);
    }

    @Test
    void sleepHintShrinksAsTheNextTickApproaches() throws Exception {
        PlaybackCursor cursor = playing(firstSong());
        long full = cursor.nanosUntilNextTick();
        cursor.advance(full / 2);
        long half = cursor.nanosUntilNextTick();
        assertTrue(half < full, "sleep hint did not shrink: " + half + " vs " + full);

        cursor.setPlaying(false);
        assertEquals(Long.MAX_VALUE, cursor.nanosUntilNextTick(),
                "a paused cursor has no next tick, so the scheduler should not be woken for it");
    }

    // Every song in the corpus must run start to finish without stalling or overrunning.
    @Test
    void everyBundledSongPlaysThroughExactlyOnce() throws Exception {
        List<String> failures = new ArrayList<>();
        try (Stream<Path> stream = Files.list(NbsCorpus.BUNDLED)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".nbs")).sorted().toList()) {
                CompiledSong song = CompiledSong.compile(NbsReader.read(file));
                PlaybackCursor cursor = playing(song);
                long played = 0;
                int guard = 0;
                while (!cursor.finished() && guard++ < 1_000_000) {
                    played += cursor.advance(cursor.nanosUntilNextTick());
                }
                if (played != song.lengthTicks() + 1) {
                    failures.add(file.getFileName() + ": played " + played + " ticks, expected "
                            + (song.lengthTicks() + 1));
                }
            }
        }
        assertEquals(List.of(), failures);
    }
}
