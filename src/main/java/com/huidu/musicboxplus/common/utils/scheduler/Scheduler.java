package com.huidu.musicboxplus.common.utils.scheduler;

import com.huidu.musicboxplus.MusicBox;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// Central scheduling facade that keeps MusicBox working on both regular Paper/Spigot and on
// Folia from a single code path. It delegates to Paper's region-scheduler API, which exists
// on regular Paper too (there every task simply runs on the main server thread, so behaviour
// is unchanged); on Folia the same calls route each task to the correct region thread.
//
// Choosing the right method:
//   global - work that touches no specific entity, block or chunk (config, pure data
//            structures, plugin-wide logic). On Folia this runs on the global region thread,
//            which must never touch world state.
//   region - work bound to a block/location (block state, levers, spawning entities,
//            World#getNearbyPlayers). Runs on the region owning that location.
//   entity - work bound to a specific entity, usually a Player (opening inventories, sending
//            sounds, reading its position). Runs on the region that currently owns the entity
//            and follows it across region boundaries.
//   async  - off-thread work (I/O, DB, HTTP). Never touch the Bukkit API here.
//
// Folia rejects a delay/period of 0 ticks for the delayed/repeating variants, so those are
// clamped to a minimum of 1 tick.
public final class Scheduler {
    private Scheduler() {
    }

    private static final boolean FOLIA = detectFolia();

    // Minecraft runs 20 ticks per second; async schedulers are wall-clock based, so a
    // tick interval has to be converted with this factor before being passed to them.
    public static final long TICK_MILLIS = 50L;

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isFolia() {
        return FOLIA;
    }

    private static Plugin plugin() {
        return MusicBox.getInstance();
    }

    private static Consumer<ScheduledTask> wrap(Runnable run) {
        return task -> run.run();
    }

    // ------------------------------------------------------------------
    // Ownership checks
    // ------------------------------------------------------------------

    // true if the calling thread may safely touch the given location's region right now.
    public static boolean ownsRegion(Location location) {
        return Bukkit.isOwnedByCurrentRegion(location);
    }

    // true if the calling thread may safely touch the given entity right now.
    public static boolean ownsEntity(Entity entity) {
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    // ------------------------------------------------------------------
    // Global region (no entity / block / world access)
    // ------------------------------------------------------------------

    public static void global(Runnable run) {
        Bukkit.getGlobalRegionScheduler().execute(plugin(), run);
    }

    public static MbTask globalLater(Runnable run, long delayTicks) {
        return MbTask.of(Bukkit.getGlobalRegionScheduler()
                .runDelayed(plugin(), wrap(run), Math.max(1L, delayTicks)));
    }

    public static MbTask globalTimer(Runnable run, long initialDelayTicks, long periodTicks) {
        return MbTask.of(Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin(), wrap(run), Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    // ------------------------------------------------------------------
    // Region (tied to a block / location)
    // ------------------------------------------------------------------

    public static void region(Location location, Runnable run) {
        Bukkit.getRegionScheduler().execute(plugin(), location, run);
    }

    // Runs inline on the calling thread when it already owns the location's region, so the
    // body must tolerate being executed before this call returns; otherwise it is scheduled.
    public static void regionNow(Location location, Runnable run) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            run.run();
        } else {
            region(location, run);
        }
    }

    public static MbTask regionLater(Location location, Runnable run, long delayTicks) {
        return MbTask.of(Bukkit.getRegionScheduler()
                .runDelayed(plugin(), location, wrap(run), Math.max(1L, delayTicks)));
    }

    public static MbTask regionTimer(Location location, Runnable run, long initialDelayTicks, long periodTicks) {
        return MbTask.of(Bukkit.getRegionScheduler()
                .runAtFixedRate(plugin(), location, wrap(run), Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    // ------------------------------------------------------------------
    // Entity (tied to a specific entity, usually a player)
    // ------------------------------------------------------------------

    public static MbTask entity(Entity entity, Runnable run) {
        return MbTask.of(entity.getScheduler().run(plugin(), wrap(run), null));
    }

    public static MbTask entity(Entity entity, Runnable run, Runnable retired) {
        return MbTask.of(entity.getScheduler().run(plugin(), wrap(run), retired));
    }

    // Runs inline on the calling thread when it already owns the entity's region, so the body
    // must tolerate being executed before this call returns; otherwise it is scheduled.
    public static void entityNow(Entity entity, Runnable run) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            run.run();
        } else {
            entity(entity, run);
        }
    }

    public static MbTask entityLater(Entity entity, Runnable run, long delayTicks) {
        return MbTask.of(entity.getScheduler().runDelayed(plugin(), wrap(run), null, Math.max(1L, delayTicks)));
    }

    public static MbTask entityTimer(Entity entity, Runnable run, long initialDelayTicks, long periodTicks) {
        return MbTask.of(entity.getScheduler()
                .runAtFixedRate(plugin(), wrap(run), null, Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks)));
    }

    // ------------------------------------------------------------------
    // Async (off-thread)
    // ------------------------------------------------------------------

    public static void async(Runnable run) {
        Bukkit.getAsyncScheduler().runNow(plugin(), wrap(run));
    }

    public static MbTask asyncLater(Runnable run, long delay, TimeUnit unit) {
        return MbTask.of(Bukkit.getAsyncScheduler().runDelayed(plugin(), wrap(run), delay, unit));
    }

    public static MbTask asyncTimer(Runnable run, long initialDelay, long period, TimeUnit unit) {
        return MbTask.of(Bukkit.getAsyncScheduler().runAtFixedRate(plugin(), wrap(run), initialDelay, period, unit));
    }

    // ------------------------------------------------------------------
    // Teleport (Folia requires the async variant)
    // ------------------------------------------------------------------

    public static void teleport(Entity entity, Location location) {
        entity.teleportAsync(location);
    }
}
