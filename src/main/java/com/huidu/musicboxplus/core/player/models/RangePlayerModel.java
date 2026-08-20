package com.huidu.musicboxplus.core.player.models;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RangePlayerModel {
    private final MusicBoxSongPlayer model;
    private final Map<UUID, Boolean> playerInRangeCache = new ConcurrentHashMap<>();
    private Set<UUID> activePlayers = new HashSet<>();
    private Set<UUID> currentInRangeBuffer = new HashSet<>();
    private volatile boolean destroyed = false;
    private long lastCacheClear = 0;
    private long cacheClearInterval;
    private int autoDestroyMillis = -1;
    private long lastNonEmptyTime = 0;
    private long lastRangeRefresh = 0;
    private long rangeRefreshIntervalMillis;

    public RangePlayerModel(MusicBoxSongPlayer model) {
        this.model = model;
        long configured = MusicBox.getInstance().getConfigObject().getPlayer().getRangeCacheClearInterval();
        this.cacheClearInterval = Math.max(1000, configured);
        // Kept in milliseconds, not in PlayerManager ticks, so the refresh cadence does not
        // silently rescale when the (independently configured) PlayerManager interval changes.
        // Refresh ~5x more often than the per-player cache is flushed.
        this.rangeRefreshIntervalMillis = Math.max(50L, this.cacheClearInterval / 5);
    }

    // How often (ms) tick() actually does range work; other calls early-return.
    // A block player's tick timer is paced to this so it doesn't fire 20x/s to do ~1x/s of work.
    public long getRangeRefreshIntervalMillis() {
        return this.rangeRefreshIntervalMillis;
    }

    public void destroy() {
        destroyed = true;
        // Only the concurrent cache is cleared here. activePlayers/currentInRangeBuffer are plain
        // HashSets touched by tick() on the region thread; clearing them from an arbitrary thread
        // (main-thread shutdown, global-region cleanup) would race an in-flight iteration. After
        // destroyed=true tick() stops reading them, so leaving them for GC removes the race.
        playerInRangeCache.clear();
    }

    private static final long REFRESH_SLACK_MILLIS = 25L;

    public void tick() {
        if (destroyed) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastCacheClear > cacheClearInterval) {
            playerInRangeCache.clear();
            lastCacheClear = now;
        }

        // Half a tick of slack. The block player's timer fires on the tick counter at exactly
        // this interval, while this compares wall clock against the previous accepted refresh, so
        // without slack a fire landing a millisecond early is thrown away and the next scan waits
        // a full extra period. Paper runs ticks back to back to catch up after a lag spike, so
        // that happens routinely -- and a dropped scan is up to two seconds of silence for someone
        // who just walked into range.
        if (now - lastRangeRefresh < rangeRefreshIntervalMillis - REFRESH_SLACK_MILLIS) {
            if (autoDestroyMillis > 0 && activePlayers.isEmpty() && lastNonEmptyTime > 0) {
                if (now - lastNonEmptyTime > autoDestroyMillis) {
                    model.destroy();
                }
            }
            return;
        }
        lastRangeRefresh = now;

        PositionPlayer positionPlayer = model.getMusicBoxModel().getPositionPlayer();
        if (positionPlayer == null) {
            return;
        }

        Location targetLocation = positionPlayer.getLocation();
        if (targetLocation == null) {
            return;
        }

        World world = targetLocation.getWorld();
        if (world == null) {
            return;
        }

        int range = positionPlayer.getRange();
        double rangeSquared = range * range;
        double tx = targetLocation.getX();
        double ty = targetLocation.getY();
        double tz = targetLocation.getZ();

        currentInRangeBuffer.clear();

        // Filter the world's player list by squared distance rather than calling
        // world.getNearbyPlayers(range, ...): that walks the chunk entity sections inside the range
        // AABB (range 64 -> a 17x17-chunk cube), and on Folia it can touch a chunk owned by a
        // neighbouring region and throw IllegalStateException, killing this recurring tick task for
        // any block/speaker player near a region boundary. getPlayers() reads the pre-maintained
        // per-world list with no chunk access, and is cheaper for realistic player counts anyway.
        for (Player player : world.getPlayers()) {
            UUID uuid = player.getUniqueId();
            // Read coordinates directly instead of allocating a Location per player; every player
            // here is already in the target world, so Location.distanceSquared's world check
            // would be redundant too.
            double dx = player.getX() - tx;
            double dy = player.getY() - ty;
            double dz = player.getZ() - tz;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            boolean inRange = distanceSquared <= rangeSquared;
            playerInRangeCache.put(uuid, inRange);

            if (inRange) {
                currentInRangeBuffer.add(uuid);
                if (!activePlayers.contains(uuid)) {
                    onPlayerEnterRange(player);
                }
            }
        }

        for (UUID uuid : activePlayers) {
            if (!currentInRangeBuffer.contains(uuid)) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    onPlayerLeaveRange(player);
                } else {
                    // Logged out while in range. The uuid is about to be dropped from
                    // activePlayers, so this is the last chance to detach the listener; skipping
                    // it leaves the entry in the player's listener map for the rest of the
                    // server's life, which both grows without bound and defeats the "no listeners,
                    // nothing to do" check that runs on every tick.
                    onOfflinePlayerLeaveRange(uuid);
                }
            }
        }

        Set<UUID> swap = activePlayers;
        activePlayers = currentInRangeBuffer;
        currentInRangeBuffer = swap;
        currentInRangeBuffer.clear();

        if (autoDestroyMillis > 0 && activePlayers.isEmpty()) {
            if (lastNonEmptyTime == 0) {
                lastNonEmptyTime = now;
            } else if (now - lastNonEmptyTime > autoDestroyMillis) {
                model.destroy();
            }
        } else if (!activePlayers.isEmpty()) {
            lastNonEmptyTime = 0;
        }
    }

    private void onPlayerEnterRange(Player player) {
        if (destroyed) return;
        model.addPlayer(player);
    }

    private void onPlayerLeaveRange(Player player) {
        if (destroyed) return;
        model.removePlayer(player);
    }

    // The listener interface only takes a Player, which is exactly what is unavailable here, so
    // this reaches the two concrete owners directly rather than widening the API for one case.
    private void onOfflinePlayerLeaveRange(UUID uuid) {
        if (destroyed) return;
        if (model instanceof MusicBoxSongPlayerModel playerModel) {
            playerModel.removePlayer(uuid);
        } else if (model instanceof com.huidu.musicboxplus.core.player.AbstractEnginePlayer enginePlayer) {
            enginePlayer.removePlayer(uuid);
        }
        playerInRangeCache.remove(uuid);
    }

    public boolean isPlayerInRange(Player player) {
        if (destroyed) return false;

        Boolean cached = playerInRangeCache.get(player.getUniqueId());
        if (cached != null) {
            return cached;
        }

        PositionPlayer positionPlayer = model.getMusicBoxModel().getPositionPlayer();
        if (positionPlayer == null) {
            return false;
        }

        Location targetLocation = positionPlayer.getLocation();
        if (targetLocation == null || !targetLocation.getWorld().equals(player.getWorld())) {
            playerInRangeCache.put(player.getUniqueId(), false);
            return false;
        }

        int range = positionPlayer.getRange();
        double distanceSquared = player.getLocation().distanceSquared(targetLocation);
        boolean inRange = distanceSquared <= range * range;

        playerInRangeCache.put(player.getUniqueId(), inRange);
        return inRange;
    }




    public MusicBoxSongPlayer getModel() {
        return model;
    }

    public void setAutoDestroyMillis(int autoDestroyMillis) {
        this.autoDestroyMillis = autoDestroyMillis;
    }

    public int getAutoDestroyMillis() {
        return autoDestroyMillis;
    }

}
