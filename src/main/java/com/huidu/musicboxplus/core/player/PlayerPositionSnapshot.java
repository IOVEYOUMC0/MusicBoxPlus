package com.huidu.musicboxplus.core.player;

import com.huidu.musicboxplus.common.utils.scheduler.MbTask;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Every online player's position, resampled on a timer, readable from any thread.
//
// On Folia a player's coordinates may only be read on the region thread that currently owns that
// player, so each block player used to schedule one entity task per player per scan and funnel
// every answer back through a region task -- O(players x blockPlayers) cross-thread dispatches a
// second, all of it recomputing the same handful of coordinates. Sampling once here makes range
// checks a plain map read, so the Folia path collapses into the same loop the non-Folia path
// already used and the cost drops to O(players).
//
// Sampling runs at half the range-scan period, so a scan never reads a sample older than half its
// own cadence and the time to notice someone entering range stays where it was.
public final class PlayerPositionSnapshot {

    public record Pos(UUID worldId, double x, double y, double z) {
    }

    private static final Map<UUID, Pos> POSITIONS = new ConcurrentHashMap<>();
    private static volatile MbTask sampleTask;

    // HALF the range-scan period, not the same as it. The scan used to read live coordinates, so
    // matching the periods would stack up to a full extra scan of staleness on top of the scan's
    // own cadence and roughly double how long it takes to notice someone walking into range.
    // Sampling twice as often keeps a sample at most half a scan old, and still costs one task per
    // player for the whole server rather than one per player per block player.
    private static long sampleIntervalTicks() {
        long refreshMillis = Math.max(50L,
                Math.max(1000L, com.huidu.musicboxplus.MusicBox.getInstance()
                        .getConfigObject().getPlayer().getRangeCacheClearInterval()) / 5);
        return Math.max(1L, refreshMillis / (2 * Scheduler.TICK_MILLIS));
    }

    private PlayerPositionSnapshot() {
    }

    // Idempotent, and only worth running on Folia -- elsewhere every caller can read a player's
    // position directly on the thread it already holds.
    public static void start() {
        if (!Scheduler.isFolia() || sampleTask != null) {
            return;
        }
        synchronized (PlayerPositionSnapshot.class) {
            if (sampleTask != null) {
                return;
            }
            long interval = sampleIntervalTicks();
            // First run after ONE tick, not a full period: a block player's first range scan runs
            // one tick after it is built (AbstractBlockPlayer schedules it that way precisely so
            // the song's opening is not played to an empty room), and an empty map at that moment
            // means nobody is in range while the cursor advances anyway -- those ticks are gone.
            MbTask scheduled = Scheduler.globalTimer(PlayerPositionSnapshot::sample, 1L, interval);
            // Latch only a handle that really exists: globalTimer returns an empty MbTask when the
            // plugin is not enabled, and storing that would leave start() believing it had already
            // armed the sampler and never try again.
            if (scheduled.handle() == null) {
                return;
            }
            sampleTask = scheduled;
        }
        // Prime it on the caller's thread too, so the common case -- the player who just placed the
        // block, whose region this thread already owns -- is in the map before that first scan
        // rather than a tick after it. entityNow runs inline for exactly those players.
        sample();
    }

    public static void stop() {
        synchronized (PlayerPositionSnapshot.class) {
            if (sampleTask != null) {
                sampleTask.cancel();
                sampleTask = null;
            }
        }
        POSITIONS.clear();
    }

    // Live view of the backing map: iteration is weakly consistent, which is what a deliberately
    // stale snapshot wants anyway -- no defensive copy per reader, per scan.
    public static Iterable<Map.Entry<UUID, Pos>> entries() {
        return POSITIONS.entrySet();
    }

    private static void sample() {
        // One snapshot, reused for both the write loop and the cleanup check. getOnlinePlayers()
        // builds a fresh collection each call, and the old cleanup called it a second time just to
        // compare sizes.
        java.util.Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player player : online) {
            UUID id = player.getUniqueId();
            // One entity task per player per interval, total -- not per player per block player.
            // entityNow so a player whose region the caller already owns is recorded synchronously,
            // which is what makes the priming call in start() useful.
            Scheduler.entityNow(player, () -> {
                if (!player.isOnline()) {
                    POSITIONS.remove(id);
                    return;
                }
                POSITIONS.put(id, new Pos(player.getWorld().getUID(),
                        player.getX(), player.getY(), player.getZ()));
            });
        }
        // Drop anyone who logged out between samples; the map is otherwise only ever added to.
        // By UUID set rather than a Bukkit.getPlayer per entry, which turned the sweep into an
        // O(entries) run of global player-table lookups on every sample.
        if (POSITIONS.size() > online.size()) {
            java.util.Set<UUID> onlineIds = new java.util.HashSet<>(online.size());
            for (Player player : online) {
                onlineIds.add(player.getUniqueId());
            }
            POSITIONS.keySet().removeIf(id -> !onlineIds.contains(id));
        }
    }
}
