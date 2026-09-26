package com.huidu.musicboxplus.core.engine;

// Where a song is, in time.
//
// Timing runs off the wall clock, not the server tick, so music keeps its tempo while the
// server is behind. A song's own tempo gives the tick period, and the speed multiplier scales
// it; neither touches the arrangement, so one CompiledSong serves every speed.
//
// After a stall the backlog is bounded, and the excess is skipped rather than replayed: the
// cursor jumps to where the wall clock says it should be and emits only the most recent ticks.
// Draining the whole backlog instead would dump hundreds of notes at once on the first tick
// after a freeze, which is worse to listen to than a gap.
//
// Ticks are strictly monotonic and never repeat. Playback code that cannot rely on that has to
// keep its own shadow counter to notice loops and seeks, which is what the players did while
// the engine replayed historical ticks through shared mutable fields.
//
// No dependency on the server: this is arithmetic, so it is fully unit-testable.
public final class PlaybackCursor {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    // Most ticks emitted from one advance. Under normal scheduling a wake produces one tick;
    // this only bounds how much a hiccup may bunch together. At 20 t/s it is 0.4s of music.
    public static final int MAX_CATCH_UP_TICKS = 8;

    private static final float MIN_SPEED = 0.01f;
    private static final float MIN_TICKS_PER_SECOND = 0.01f;

    private CompiledSong song;
    private float speed = 1.0f;

    // Last tick handed out. Starts before the song so the first emitted tick is 0.
    private int tick = -1;
    private int firstEmittedTick = -1;
    private long accumulatedNanos;
    private boolean playing;

    public PlaybackCursor(CompiledSong song) {
        this.song = song;
    }

    // Reads the reference from arbitrary threads (getSong on the main thread, advance on the
    // clock thread); keep it under the same monitor as the write in setSong for visibility.
    public synchronized CompiledSong song() {
        return song;
    }

    // Swapping songs restarts from the beginning; the accumulator is dropped so the new song
    // does not inherit a partial tick from the old tempo.
    public synchronized void setSong(CompiledSong song) {
        this.song = song;
        this.tick = -1;
        this.firstEmittedTick = -1;
        this.accumulatedNanos = 0;
    }

    public synchronized float speed() {
        return speed;
    }

    // Changing speed keeps the current position: only the period between future ticks changes.
    // The accumulator is rescaled so the fraction of a tick already elapsed is preserved rather
    // than being reinterpreted against the new period, which would nudge the beat.
    public synchronized void setSpeed(float speed) {
        float next = Math.max(MIN_SPEED, speed);
        if (next == this.speed) {
            return;
        }
        long period = tickPeriodNanos();
        if (period > 0) {
            double fraction = (double) accumulatedNanos / period;
            this.speed = next;
            this.accumulatedNanos = (long) (fraction * tickPeriodNanos());
        } else {
            this.speed = next;
        }
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized void setPlaying(boolean playing) {
        if (this.playing == playing) {
            return;
        }
        this.playing = playing;
        // Time spent paused must not turn into a burst of ticks on resume.
        this.accumulatedNanos = 0;
    }

    public synchronized int tick() {
        return tick;
    }

    // Moves to just before the given tick, so the next emitted tick is the one asked for.
    // A negative request is treated as "from the start": the old -1 produced a bogus empty tick
    // (tick = -2 -> next emitted tick -1), which external API callers could trigger.
    public synchronized void seek(int tick) {
        this.tick = Math.max(0, tick) - 1;
        this.firstEmittedTick = -1;
        this.accumulatedNanos = 0;
    }

    // True once the last tick of the song has been handed out.
    public synchronized boolean finished() {
        return song == null || tick >= song.lengthTicks();
    }

    // Ticks that came due over the elapsed time, as a count of consecutive ticks starting at
    // firstEmittedTick(). Returns 0 when paused, finished, or nothing is due yet.
    public synchronized int advance(long elapsedNanos) {
        firstEmittedTick = -1;
        if (!playing || song == null || elapsedNanos <= 0 || finished()) {
            return 0;
        }

        long period = tickPeriodNanos();
        if (period <= 0) {
            return 0;
        }

        accumulatedNanos += elapsedNanos;
        long due = accumulatedNanos / period;
        if (due <= 0) {
            return 0;
        }
        accumulatedNanos -= due * period;

        // Never past the end of the song.
        long remaining = (long) song.lengthTicks() - tick;
        if (due > remaining) {
            due = remaining;
        }

        // Old ticks are dropped, recent ones are played, and the position still matches the
        // wall clock afterwards.
        int emitted = (int) Math.min(due, MAX_CATCH_UP_TICKS);
        int skipped = (int) (due - emitted);

        firstEmittedTick = tick + 1 + skipped;
        tick += (int) due;
        return emitted;
    }

    // First tick of the run returned by the last advance, or -1 if it returned 0.
    public synchronized int firstEmittedTick() {
        return firstEmittedTick;
    }

    // Time until the next tick is due, for a scheduler deciding how long to sleep.
    public synchronized long nanosUntilNextTick() {
        if (!playing || song == null || finished()) {
            return Long.MAX_VALUE;
        }
        long period = tickPeriodNanos();
        return period <= 0 ? Long.MAX_VALUE : Math.max(0, period - accumulatedNanos);
    }

    // Nanoseconds between consecutive song ticks at the current tempo and speed.
    public synchronized long tickPeriodNanos() {
        if (song == null) {
            return 0;
        }
        float ticksPerSecond = Math.max(MIN_TICKS_PER_SECOND, song.ticksPerSecond() * speed);
        return (long) (NANOS_PER_SECOND / ticksPerSecond);
    }
}
