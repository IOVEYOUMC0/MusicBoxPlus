package com.huidu.musicboxplus.core.engine;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

// Drives every playing song from one thread.
//
// The alternative, a thread per player, costs a thread for each placed jukebox, sign and radio
// on the server, and a stopped player still burns one: with no next-tick time to sleep until,
// such a loop falls back to polling. Here a player that is not playing simply reports no next
// tick and the clock never wakes for it.
//
// Each target keeps its own last-advanced timestamp, so a player registered mid-cycle starts
// from the moment it joined rather than inheriting however long the clock happened to be
// asleep.
//
// The clock only decides when a tick is due and hands it over. Turning a tick into sound
// belongs to the target, which knows what thread may touch the world.
//
// step() carries the whole scheduling decision and takes its time from an injected source, so
// the policy is testable without starting a thread or waiting on real time.
public final class PlaybackClock {

    // What the clock drives. Every callback runs on the clock thread.
    public interface Target {
        PlaybackCursor cursor();

        // count consecutive ticks starting at firstTick have come due.
        void playTicks(int firstTick, int count);

        // The cursor reached the end of its song.
        void songFinished();

        // False once the target is gone; the clock drops it without further callbacks.
        boolean alive();
    }

    private static final class Entry {
        final Target target;
        // Written by register() from a player's own thread and by step() from the clock thread.
        volatile long lastNanos;

        Entry(Target target, long now) {
            this.target = target;
            this.lastNanos = now;
        }
    }

    // Longest the clock will sleep with targets registered. Nothing depends on it for
    // correctness; it just bounds how long a state change made without a wake-up goes
    // unnoticed.
    private static final long MAX_SLEEP_NANOS = 50_000_000L;

    // Long enough for an in-flight step() to finish, short enough not to hold up a server stop.
    private static final long SHUTDOWN_JOIN_MILLIS = 2000L;

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final LongSupplier nanoTime;
    private final String threadName;

    private volatile Thread thread;
    private volatile boolean running;

    public PlaybackClock(LongSupplier nanoTime, String threadName) {
        this.nanoTime = nanoTime;
        this.threadName = threadName;
    }

    // Idempotent. A target that registers twice would get one Entry per registration, and every
    // Entry advances the SAME cursor with the same elapsed time -- so a doubly registered player
    // runs at double speed and emits every note twice. Players legitimately call this more than
    // once (once on construction, again whenever they are set playing), so the check belongs here
    // rather than in each caller.
    public void register(Target target) {
        long now = nanoTime.getAsLong();
        for (Entry existing : entries) {
            if (existing.target == target) {
                // Restart this target's clock from now. With every target paused the thread parks,
                // so lastNanos can be arbitrarily old; letting that elapsed time reach the cursor
                // on resume would jump the song forward by however long the pause lasted.
                existing.lastNanos = now;
                wake();
                return;
            }
        }
        entries.add(new Entry(target, now));
        wake();
    }

    public void unregister(Target target) {
        entries.removeIf(e -> e.target == target);
    }

    public int targetCount() {
        return entries.size();
    }

    // Advances everything due and returns how long the caller may sleep before the next tick.
    // Long.MAX_VALUE means nothing is scheduled at all.
    public long step() {
        long now = nanoTime.getAsLong();
        long sleep = Long.MAX_VALUE;

        for (Entry entry : entries) {
            if (!entry.target.alive()) {
                entries.remove(entry);
                continue;
            }

            long elapsed = now - entry.lastNanos;
            entry.lastNanos = now;

            PlaybackCursor cursor = entry.target.cursor();
            if (cursor == null) {
                continue;
            }

            // advance()/firstEmittedTick()/finished()/nanosUntilNextTick() are four separate
            // synchronized calls; a seek or pause landing between them would split one batch
            // across two states (emit ticks from before a seek, or fire songFinished for a
            // cursor that was just restarted). Holding the cursor's monitor spans the whole
            // batch so it is atomic. playTicks only schedules per-listener work and never
            // touches the cursor, so this cannot deadlock.
            synchronized (cursor) {
                int count = cursor.advance(elapsed);
                if (count > 0) {
                    entry.target.playTicks(cursor.firstEmittedTick(), count);
                }
                if (cursor.finished()) {
                    entry.target.songFinished();
                }

                long next = cursor.nanosUntilNextTick();
                if (next < sleep) {
                    sleep = next;
                }
            }
        }

        return sleep;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(this::run, threadName);
        t.setDaemon(true);
        this.thread = t;
        t.start();
    }

    // Waits for the clock thread to actually leave step(). Returning while a tick is still being
    // dispatched means that dispatch reaches a plugin that is already disabled, which Bukkit
    // rejects with "Plugin attempted to register task while disabled".
    public void shutdown() {
        Thread t;
        synchronized (this) {
            running = false;
            t = this.thread;
            this.thread = null;
        }
        if (t == null) {
            return;
        }
        LockSupport.unpark(t);
        try {
            t.join(SHUTDOWN_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void run() {
        while (running) {
            long sleep;
            try {
                sleep = step();
            } catch (Throwable t) {
                // One misbehaving player must not take the clock down with it, or every other
                // song on the server stops.
                java.util.logging.Logger.getLogger(PlaybackClock.class.getName())
                        .log(java.util.logging.Level.SEVERE, "Playback tick failed for a target", t);
                sleep = MAX_SLEEP_NANOS;
            }
            if (sleep == Long.MAX_VALUE) {
                // Nothing scheduled: sleep until a register() wakes us.
                LockSupport.park(this);
            } else if (sleep > 0) {
                LockSupport.parkNanos(this, Math.min(sleep, MAX_SLEEP_NANOS));
            }
        }
    }

    private void wake() {
        Thread t = this.thread;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

}
