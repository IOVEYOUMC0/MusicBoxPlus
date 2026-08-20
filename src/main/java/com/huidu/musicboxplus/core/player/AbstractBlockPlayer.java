package com.huidu.musicboxplus.core.player;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlaybackStartEvent;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.event.MusicBoxRangeStateChangeEvent;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.common.utils.LocationKey;
import com.huidu.musicboxplus.common.utils.SignUtils;
import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.player.models.MusicBoxSongPlayerModel;
import com.huidu.musicboxplus.core.player.models.RangePlayerModel;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unchecked")
public abstract class AbstractBlockPlayer
extends AbstractEnginePlayer
implements PositionPlayer {
    private static final Logger logger = LoggerFactory.getLogger(AbstractBlockPlayer.class);
    private static final Map<LocationKey, AbstractBlockPlayer> players = new ConcurrentHashMap<LocationKey, AbstractBlockPlayer>();
    private static final Map<ChunkKey, Set<AbstractBlockPlayer>> playersByChunk = new ConcurrentHashMap<>();
    private static final Map<LocationKey, AbstractBlockPlayer> infoSignIndex = new ConcurrentHashMap<>();
    private static final long CLEANUP_INTERVAL_TICKS = 6000L;
    private static volatile boolean listenerRegistered = false;
    private static volatile boolean cleanupTaskRegistered = false;
    private static Listener worldUnloadListener;
    private static com.huidu.musicboxplus.common.utils.scheduler.MbTask cleanupTask;

    // Playback arrangement for this player's song, resolved once on construction. A block
    // player exists for exactly one song -- the next track gets a new player -- so this never
    // changes, and repeat-one replays the same arrangement.
    //
    // Speed does not affect it: the speed-adjusted Song shares its layers and notes with the
    // base song and differs only in tempo, so a given tick holds the same notes either way.
    private volatile CompiledSong compiledSong;

    // Guards the one-shot playback-start announcement.
    private volatile boolean startAnnounced;

    // Where the sound comes from. Held here now that the block player no longer inherits a
    // position from a base class.
    private volatile Location targetLocation;

    
    public static void registerWorldUnloadListener() {
        if (!listenerRegistered) {
            listenerRegistered = true;
            worldUnloadListener = new WorldUnloadListener();
            org.bukkit.Bukkit.getPluginManager().registerEvents(worldUnloadListener, JavaPlugin.getPlugin(MusicBox.class));
        }
        startCleanupTask();
    }
    
    private static void startCleanupTask() {
        if (cleanupTaskRegistered) {
            return;
        }
        cleanupTaskRegistered = true;
        // Global scheduler: performCleanup only iterates the registry and reads stored
        // Location references (no chunk/world access), so it is safe on the global region
        // thread. Any player it decides to destroy routes its own world-touching work to
        // the correct region internally (see destroy()).
        cleanupTask = com.huidu.musicboxplus.common.utils.scheduler.Scheduler.globalTimer(
            AbstractBlockPlayer::performCleanup,
            CLEANUP_INTERVAL_TICKS,
            CLEANUP_INTERVAL_TICKS
        );
        logger.info("BlockPlayer cleanup task started (interval: {} ticks)", CLEANUP_INTERVAL_TICKS);
    }
    
    private static void performCleanup() {
        int removedCount = 0;
        Iterator<Map.Entry<LocationKey, AbstractBlockPlayer>> iterator = players.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<LocationKey, AbstractBlockPlayer> entry = iterator.next();
            AbstractBlockPlayer player = entry.getValue();
            if (player.isDestroyed()) {
                iterator.remove();
                unindexChunk(player);
                Location infoSign = player.getInfoSign();
                if (infoSign != null) {
                    infoSignIndex.remove(new LocationKey(infoSign));
                }
                removedCount++;
                continue;
            }
            Location loc = player.getLocation();
            World locWorld = loc == null ? null : loc.getWorld();
            String reason;
            if (locWorld == null) {
                reason = "invalid location";
            } else if (locWorld.getName() == null) {
                reason = "invalid world name";
            } else {
                continue;
            }
            logger.debug("Removing block player with {}", reason);
            // Drop indexes before destroy() so a throwing destroy can't leave stale entries.
            iterator.remove();
            unindexChunk(player);
            try {
                player.destroy(DestroyReason.BLOCK_GONE);
            } catch (Exception e) {
                logger.warn("Error destroying invalid block player during cleanup", e);
            }
            removedCount++;
        }
        if (removedCount > 0) {
            logger.debug("Cleanup removed {} invalid block players, {} remaining", removedCount, players.size());
        }
    }
    
    public static void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
        cleanupTaskRegistered = false;
        if (worldUnloadListener != null) {
            HandlerList.unregisterAll(worldUnloadListener);
            worldUnloadListener = null;
        }
        listenerRegistered = false;
        for (AbstractBlockPlayer player : players.values()) {
            if (!player.isDestroyed()) {
                player.destroy(DestroyReason.SHUTDOWN);
            }
        }
        players.clear();
        playersByChunk.clear();
        infoSignIndex.clear();
        logger.info("BlockPlayer shutdown complete");
    }
    
    private static class WorldUnloadListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onWorldUnload(WorldUnloadEvent event) {
            cleanupWorld(event.getWorld());
        }
    }
    
    public static void cleanupWorld(World world) {
        if (world == null) {
            return;
        }
        Iterator<Map.Entry<LocationKey, AbstractBlockPlayer>> iterator = players.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<LocationKey, AbstractBlockPlayer> entry = iterator.next();
            AbstractBlockPlayer player = entry.getValue();
            Location loc = player.getLocation();
            if (loc != null && loc.getWorld() != null && loc.getWorld().equals(world)) {
                logger.debug("Cleaning up block player at {} due to world unload", loc);
                // Remove from indexes FIRST so a throwing destroy() can't leave stale entries.
                iterator.remove();
                unindexChunk(player);
                Location infoSign = player.getInfoSign();
                if (infoSign != null) {
                    infoSignIndex.remove(new LocationKey(infoSign));
                }
                try {
                    player.destroy(DestroyReason.WORLD_UNLOAD);
                } catch (Exception e) {
                    logger.warn("Error destroying block player during world unload", e);
                }
            }
        }
    }
    
    private final MusicBoxSongPlayerModel musicBoxModel;
    private final RangePlayerModel rangePlayerModel;
    private final LocationKey locationKey;
    private final ChunkKey chunkKey;
    // Written on the playback thread (playTick), read on the main thread
    // (control-panel progress, speed adjust, reload snapshot) -> volatile for visibility.
    // Always updated via advanceCurrentTick(int) so the value is monotonically non-decreasing
    // within a song (a song loop reset is detected by "new tick near 0, previous near end").
    // This shields GUI/PAPI readers from any out-of-order playTick calls the API might make
    // (e.g. a stale catch-up slice arriving after a later one).
    private volatile boolean destroyed = false;
    // Per-block region tick. Replaces the old single server-wide PlayerManager loop so the
    // range/auto-destroy work (which reads World#getNearbyPlayers at the block) runs on the
    // region that owns this block. On regular Paper this is just the main thread.
    private com.huidu.musicboxplus.common.utils.scheduler.MbTask tickTask;

    private record ChunkKey(String worldName, int x, int z) {
        private static ChunkKey from(Location location) {
            if (location == null || location.getWorld() == null) {
                return null;
            }
            return new ChunkKey(location.getWorld().getName(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }

        private static ChunkKey of(World world, int x, int z) {
            if (world == null) {
                return null;
            }
            return new ChunkKey(world.getName(), x, z);
        }
    }

    private static void indexChunk(AbstractBlockPlayer player) {
        ChunkKey key = player.chunkKey;
        if (key != null) {
            playersByChunk.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(player);
        }
    }

    private static void unindexChunk(AbstractBlockPlayer player) {
        ChunkKey key = player.chunkKey;
        if (key == null) {
            return;
        }
        Set<AbstractBlockPlayer> indexed = playersByChunk.get(key);
        if (indexed == null) {
            return;
        }
        indexed.remove(player);
        if (indexed.isEmpty()) {
            playersByChunk.remove(key, indexed);
        }
    }

    private static org.bukkit.SoundCategory resolveSoundCategory(String configValue) {
        try {
            return org.bukkit.SoundCategory.valueOf(configValue.toUpperCase());
        } catch (IllegalArgumentException e) {
            return org.bukkit.SoundCategory.RECORDS;
        }
    }


    public static Collection<AbstractBlockPlayer> getAll() {
        return players.values();
    }


    public AbstractBlockPlayer(IPlayList list, Location location, int range, float speedMultiplier) {
        super(PlaybackSetup.compiledSongOf(list));
        this.setEnable10Octave(MusicBox.getInstance().getConfigObject().isEnable10octave());
        this.setRange(range);
        this.soundCategory = resolveSoundCategory(MusicBox.getInstance().getConfigObject().getSoundCategory());
        Location centered = BukkitUtils.centerBlock(location);
        this.setTargetLocation(centered);
        this.locationKey = new LocationKey(centered);
        this.chunkKey = ChunkKey.from(centered);
        AbstractBlockPlayer oldBlock = players.get(this.locationKey);
        if (oldBlock != null) {
            oldBlock.musicBoxModel.setNextCreated(true);
            // Handover, not termination: this player is about to take over the same location.
            // REPLACED must be passed explicitly, or downstream plugins read it as "playback
            // finished" and tear down their own visuals -- a speed change takes this path
            // without firing SongChangeEvent, so nothing would ever rebuild them.
            oldBlock.destroy(DestroyReason.REPLACED);
        }
        this.musicBoxModel = new MusicBoxSongPlayerModel(this, list, this::runNextSong);
        this.musicBoxModel.setPlaybackSpeedMultiplier(speedMultiplier);
        // 倍速必须推送到播放游标（cursor.setSpeed），否则 GUI 显示倍速已调整而实际播放速度不变
        this.setPlaybackSpeed(speedMultiplier);
        this.musicBoxModel.setOwnerPlayer(this);
        this.rangePlayerModel = new RangePlayerModel(this.musicBoxModel);
        int autoDestroySeconds = MusicBox.getInstance().getConfigObject().getAutoDestroy();
        if (autoDestroySeconds > 0) {
            this.rangePlayerModel.setAutoDestroyMillis(autoDestroySeconds * 1000);
        }
        this.loadStoredVolume();
        PlayerManager.registerPlayer(this);
        // Already resolved by the super constructor and held on the cursor; resolving it a
        // second time re-entered the song's monitor for a value we have.
        this.compiledSong = getSong();
        // Build the next song's arrangement now, off-thread. The automatic transition at the end
        // of this one runs on the playback dispatch thread and constructs the successor inline,
        // so anything left uncompiled by then would stall it mid-song.
        PlaybackSetup.prefetchNext(list);
        this.setPlaying(true);
        // A block player is stationary and all of tick()'s work is wall-clock throttled: range
        // refresh (~1s), cache flush (~5s), sign/jukebox block checks (~5s). Pace the timer to the
        // range-refresh cadence instead of firing every server tick (20Hz) to do ~1Hz of work.
        long blockTickPeriod = Math.max(1L, this.rangePlayerModel.getRangeRefreshIntervalMillis() / 50L);
        // Initial delay of one tick, not blockTickPeriod: the coarse pacing is about how often
        // the range is rescanned, but the FIRST scan has to happen immediately or nobody is in
        // playerList while the cursor already advances, and the song's opening is played to no one.
        this.tickTask = com.huidu.musicboxplus.common.utils.scheduler.Scheduler.regionTimer(centered, this::tick, 1L, blockTickPeriod);
        // One tick later, independent of the tick task's coarse period: a player torn down before
        // its first tick would otherwise never announce that it started at all.
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.regionLater(centered, this::announceStartOnce, 1L);
        // Register last: every field above (musicBoxModel, rangePlayerModel, tickTask, ...) must
        // be finalised before this instance is visible to performCleanup / shutdown, which can
        // destroy a registered player and read those fields. Registering mid-constructor left a
        // half-initialised entry reachable between players.put and the model assignment.
        players.put(this.locationKey, this);
        indexChunk(this);
    }

    public static <T extends AbstractBlockPlayer> T findByLocation(Location location) {
        return (T)players.get(new LocationKey(location));
    }

    public static Set<? extends AbstractBlockPlayer> findByChunk(World world, int x, int z) {
        ChunkKey key = ChunkKey.of(world, x, z);
        if (key == null) {
            return Set.of();
        }
        Set<AbstractBlockPlayer> indexed = playersByChunk.get(key);
        if (indexed == null || indexed.isEmpty()) {
            return Set.of();
        }
        // Copy into a fresh set so callers (e.g. ChunkUnloadEvent → destroy() each) can
        // mutate the index via unindexChunk without ConcurrentModificationException, and
        // skip the Stream/Collectors allocation on a per-chunk event hot path.
        HashSet<AbstractBlockPlayer> result = new HashSet<>(indexed.size());
        for (AbstractBlockPlayer player : indexed) {
            if (!player.isDestroyed()) {
                result.add(player);
            }
        }
        return result;
    }

    protected abstract Location getInfoSign();

    public void tick() {
        if (destroyed) {
            return;
        }
        announceStartOnce();
        if (rangePlayerModel != null) {
            rangePlayerModel.tick();
        }
    }

    // Announced from the first tick rather than the constructor: a subclass has not finished
    // initialising while its superclass constructor runs, so handing this out there would give
    // listeners a half-built player.
    private void announceStartOnce() {
        if (startAnnounced || destroyed) {
            return;
        }
        startAnnounced = true;
        try {
            Bukkit.getPluginManager().callEvent(new MusicBoxPlaybackStartEvent(this));
        } catch (Exception ex) {
            logger.debug("Exception dispatching MusicBoxPlaybackStartEvent: {}", ex.getMessage());
        }
    }

    protected abstract MusicBoxSongPlayer runNextSong(IPlayList playlist);

    public static <T extends AbstractBlockPlayer> Optional<T> findByInfoSign(Location location) {
        LocationKey key = new LocationKey(location);
        AbstractBlockPlayer cached = infoSignIndex.get(key);
        if (cached != null && !cached.isDestroyed()) return Optional.of((T) cached);
        for (AbstractBlockPlayer player : players.values()) {
            if (player.isDestroyed()) continue;
            if (player.getInfoSign() != null && player.getInfoSign().equals(location)) {
                infoSignIndex.put(key, player);
                return Optional.of((T) player);
            }
        }
        return Optional.empty();
    }

    @Override
    public Location getLocation() {
        // Cloned: this is the api's PositionPlayer.getLocation, so downstream plugins hold the
        // result, and a caller that mutates it would move this player's own idea of where it is.
        Location location = this.getTargetLocation();
        return location == null ? null : location.clone();
    }

    @Override
    public int getRange() {
        return this.getDistance();
    }

    @Override
    public void setRange(int range) {
        this.setDistance(range);
    }

    public Location getTargetLocation() {
        return this.targetLocation;
    }

    public void setTargetLocation(Location targetLocation) {
        this.targetLocation = targetLocation;
    }

    // Song end is routed to the model, which owns looping and playlists.
    @Override
    protected void onSongFinished() {
        this.onSongEnd();
    }

    // Song end can build or destroy block players, so it belongs on this block's region.
    @Override
    protected Location dispatchLocation() {
        return this.targetLocation;
    }

    public void playTick(@NotNull Player player, int tick) {
        if (this.destroyed) {
            return;
        }
        Location targetLocation = this.getTargetLocation();
        World targetWorld = targetLocation != null ? targetLocation.getWorld() : null;
        if (targetWorld == null) {
            return;
        }
        if (player.getWorld() != targetWorld) {
            return;
        }

        boolean inRange = this.rangePlayerModel.isPlayerInRange(player);
        Boolean wasInRange = this.playerList.get(player.getUniqueId());
        if (inRange) {
            if (wasInRange == null || !wasInRange) {
                this.playerList.put(player.getUniqueId(), true);
                fireRangeStateChange(player, true);
            }
        } else if (wasInRange != null && wasInRange) {
            this.playerList.put(player.getUniqueId(), false);
            fireRangeStateChange(player, false);
        }
        if (!inRange) {
                return;
        }
        CompiledSong compiled = this.compiledSong;
        if (compiled == null || compiled.noteStart(tick) >= compiled.noteEnd(tick)) {
                return;
        }

        int playbackVolume = Math.max(0, Math.min(100, this.musicBoxModel.getVolume()));
        int listenerVolume = Math.max(0, Math.min(100, VolumeManager.getPlayerVolume(player)));
        if (this.volume <= 0 || playbackVolume == 0 || listenerVolume == 0 || this.getDistance() <= 0) {
                return;
        }
        // Everything fixed for this tick is computed once; only layer volume and note velocity
        // vary per note, and the emitter applies those.
        float baseVolume = NoteEmitter.baseVolume(this.volume, playbackVolume, listenerVolume,
            this.getDistance());
        // A block player never leaves mono: channelMode defaults to MonoMode and only the radio
        // player ever replaces it.
        // A block player is always mono: its sound already comes from a fixed point in the
        // world, so panning it relative to the listener would fight that.
        float stereoWidth = 0F;
        NoteEmitter.emitTick(player, targetLocation, compiled, tick, baseVolume,
            this.soundCategory, this.enable10Octave, stereoWidth);
    }

    // Range enter/leave is dispatched on the listener's own region thread; a throwing listener
    // must not abort the tick loop, so the event is isolated like the other playTick callEvents.
    private void fireRangeStateChange(Player player, boolean inRange) {
        try {
            Bukkit.getPluginManager().callEvent(new MusicBoxRangeStateChangeEvent(this, player, inRange));
        } catch (Exception ex) {
            logger.debug("Exception dispatching MusicBoxRangeStateChangeEvent: {}", ex.getMessage());
        }
    }

    public void setStoredPlaybackSpeedMultiplier(float playbackSpeedMultiplier) {
        this.musicBoxModel.setPlaybackSpeedMultiplier(playbackSpeedMultiplier);
        // 同步推送到播放游标，让倍速调整立即生效
        this.setPlaybackSpeed(playbackSpeedMultiplier);
    }

    @Override
    public boolean isDestroyed() {
        return this.destroyed;
    }

    @Override
    public void destroy() {
        // Inference for callers that gave no reason; every other call site should pass one.
        DestroyReason inferred = DestroyReason.UNKNOWN;
        try {
            if (this.musicBoxModel.isSongEndNormal()) {
                // onSongEnd() branch where the song finished with nothing queued after it
                inferred = DestroyReason.SONG_END;
            } else if (this.musicBoxModel.isNextCreated()) {
                // A new player already took over this location (song change / speed change):
                // a handover, not a termination.
                inferred = DestroyReason.REPLACED;
            }
        } catch (Exception ignored) {
            // A failed inference must not block the destroy itself
        }
        this.destroy(inferred);
    }

    // Destroy with an explicit reason. Every termination path funnels through here, and this is
    // where MusicBoxPlayerDestroyEvent is dispatched. Subclasses needing extra cleanup must
    // override this method, not the no-arg destroy(): call sites that invoke destroy(reason)
    // directly would bypass the latter.
    public void destroy(DestroyReason reason) {
        if (this.destroyed) {
            return;
        }
        this.destroyed = true;

        // Dispatched before anything is torn down, while location, song and player state are
        // still intact. Wrapped in try/catch because destroy() also runs during shutdown, where
        // third-party listeners or the PluginManager itself may throw and teardown must still
        // run to completion.
        try {
            Location snapshot = null;
            try {
                snapshot = this.getLocation();
            } catch (Exception ignored) {
                // Reading the location can fail once the world is unloaded; the event still goes out
            }
            MusicBoxSong currentSong = null;
            try {
                currentSong = this.musicBoxModel.getMusicBoxSong();
            } catch (Exception ignored) {
                // Same as above
            }
            Bukkit.getPluginManager().callEvent(new MusicBoxPlayerDestroyEvent(
                this, snapshot, currentSong, reason == null ? DestroyReason.UNKNOWN : reason));
        } catch (Exception ex) {
            logger.debug("Exception dispatching MusicBoxPlayerDestroyEvent: {}", ex.getMessage());
        }

        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }

        try {
            super.destroy();
        } catch (Exception ex) {
            logger.debug("Exception during super.destroy(): {}", ex.getMessage());
        }
        
        try {
            unindexChunk(this);
            if (!this.musicBoxModel.isNextCreated()) {
                players.remove(this.locationKey);
            }
        } catch (Exception ex) {
            logger.debug("Exception removing player from map: {}", ex.getMessage());
        }
        
        boolean normalEnd = false;
        try {
            normalEnd = this.musicBoxModel.isSongEndNormal();
        } catch (Exception ex) {
            logger.debug("Exception checking song end status: {}", ex.getMessage());
        }
        
        if (normalEnd) {
            try {
                this.songEnd();
            } catch (Exception ex) {
                logger.debug("Exception during songEnd(): {}", ex.getMessage());
            }
        }
        
        Location infoSign = this.getInfoSign();
        if (infoSign != null) {
            // LocationKey tolerates a null world, so dropping the index entry is always safe.
            infoSignIndex.remove(new LocationKey(infoSign));
            // destroy() is reachable off the block's region (performCleanup / cleanupWorld)
            // for players whose world is already gone. Scheduler.regionNow dereferences the
            // location's world (Bukkit.isOwnedByCurrentRegion / getRegionScheduler), which
            // throws on Folia when the world is null. Only schedule the block-touching clear
            // when the info sign's world is still present.
            if (infoSign.getWorld() != null) {
                final Location finalInfoSign = infoSign;
                final boolean nextCreated = this.musicBoxModel.isNextCreated();
                // Clearing the info sign touches block state -> must run on the region owning it.
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.regionNow(finalInfoSign, () -> {
                    try {
                        if (!nextCreated) {
                            SignUtils.clearInfoSign(finalInfoSign);
                        }
                    } catch (Exception ex) {
                        logger.debug("Exception clearing info sign: {}", ex.getMessage());
                    }
                });
            }
        }
        
        try {
            this.rangePlayerModel.destroy();
        } catch (Exception ex) {
            logger.debug("Exception destroying range player model: {}", ex.getMessage());
        }
        
        try {
            this.musicBoxModel.destroy();
        } catch (Exception ex) {
            logger.debug("Exception destroying music box model: {}", ex.getMessage());
        }
        
        try {
            PlayerManager.unregisterPlayer(this);
        } catch (Exception ex) {
            logger.debug("Exception unregistering player: {}", ex.getMessage());
        }
    }

    protected abstract void songEnd();

    @Override
    public MusicBoxSongPlayerModel getMusicBoxModel() {
        return this.musicBoxModel;
    }
    

    public RangePlayerModel getRangePlayerModel() {
        return this.rangePlayerModel;
    }

    public short getCurrentTick() {
        return this.getTick();
    }

    
    public LocationKey getLocationKey() {
        return this.locationKey;
    }

    public void adjustStoredVolume(int delta) {
        this.musicBoxModel.adjustVolume(delta);
        this.saveStoredVolumeAsync();
    }

    public void setStoredVolume(int volume) {
        this.musicBoxModel.setVolume(volume);
        this.saveStoredVolumeAsync();
    }

    public void deleteStoredVolume() {
        Location location = this.getLocation();
        if (location == null) {
            return;
        }
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                DatabaseLoader.getBase().deleteBlockPlayerVolume(location);
            } catch (Exception ex) {
                logger.debug("Exception deleting stored block-player volume: {}", ex.getMessage());
            }
        });
    }

    private void loadStoredVolume() {
        Location location = this.getLocation();
        if (location == null) {
            return;
        }
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            Integer storedVolume = DatabaseLoader.getBase().getBlockPlayerVolume(location);
            if (storedVolume != null) {
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.regionNow(location, () -> {
                    if (!this.destroyed) {
                        this.musicBoxModel.setVolume(storedVolume);
                    }
                });
            }
        });
    }

    private void saveStoredVolumeAsync() {
        Location location = this.getLocation();
        if (location == null) {
            return;
        }
        int volume = this.musicBoxModel.getVolume();
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                DatabaseLoader.getBase().saveBlockPlayerVolume(location, volume);
            } catch (Exception ex) {
                logger.debug("Exception saving stored block-player volume: {}", ex.getMessage());
            }
        });
    }
}
