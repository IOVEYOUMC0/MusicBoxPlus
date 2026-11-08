package com.huidu.musicboxplus.core.player;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.common.utils.cache.CacheCleaner;
import com.huidu.musicboxplus.common.utils.cache.CacheUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

// Per-player volume settings, cached in memory and persisted to the database.
public class VolumeManager {
    private static volatile VolumeManager instance;
    private static final Object LOCK = new Object();
    private static final long CLEANUP_INTERVAL_TICKS = 12000L;

    private final DeferredVolumeCache playerVolumes = new DeferredVolumeCache();
    private volatile boolean dirty = false;
    private final AtomicLong volumeVersion = new AtomicLong();
    private volatile long persistedVersion = 0L;
    private volatile boolean saveInProgress = false;
    private com.huidu.musicboxplus.common.utils.scheduler.MbTask autoSaveTask;
    private com.huidu.musicboxplus.common.utils.scheduler.MbTask cleanupTask;
    // Snapshot of the four volume bounds (default/min/max/step) so the per-note-per-listener
    // hot path (playTick -> VolumeManager.getVolume -> getDefaultVolume) does not walk the
    // MusicBox.getInstance().getConfigObject().getVolume() chain every call. Invalidated
    // (set to null) by the CacheCleaner so the next access lazily refetches.
    private volatile Settings settings;

    private static final class Settings {
        final int defaultVolume;
        final int minVolume;
        final int maxVolume;
        final int step;

        Settings(int defaultVolume, int minVolume, int maxVolume, int step) {
            this.defaultVolume = defaultVolume;
            this.minVolume = minVolume;
            this.maxVolume = maxVolume;
            this.step = step;
        }

        static Settings load() {
            MusicBoxConfig.VolumeConfig cfg = MusicBox.getInstance().getConfigObject().getVolume();
            return new Settings(cfg.getDefaultVolume(), cfg.getMinVolume(), cfg.getMaxVolume(), cfg.getStep());
        }
    }

    private Settings settings() {
        Settings s = settings;
        if (s == null) {
            s = Settings.load();
            settings = s;
        }
        return s;
    }

    public static VolumeManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new VolumeManager();
                }
            }
        }
        return instance;
    }

    private VolumeManager() {
        this(true);
    }

    // Allows deterministic persistence tests without starting Bukkit scheduler tasks.
    VolumeManager(boolean startTasks) {
        if (startTasks) {
            CacheUtils.registerCacheCleaner(new SettingsCleaner());
            startAutoSave();
            startCleanupTask();
        }
    }

    private final class SettingsCleaner implements CacheCleaner {
        @Override public void clearCache() { settings = null; }
        @Override public String getCacheName() { return "VolumeManager.settings"; }
    }

    private void startAutoSave() {
        synchronized (this) {
            if (autoSaveTask != null) {
                try {
                    autoSaveTask.cancel();
                } catch (Exception e) {
                    // Ignore cancellation failures; the task is being replaced either way.
                }
                autoSaveTask = null;
            }
            long interval = MusicBox.getInstance().getConfigObject().getVolume().getAutoSaveInterval();
            if (interval <= 0) {
                interval = 6000L;
            }
            // Pure async DB flush -> async scheduler. The tick interval is converted to
            // real time (1 tick = 50 ms) since the async scheduler is wall-clock based.
            long intervalMs = interval * Scheduler.TICK_MILLIS;
            autoSaveTask = Scheduler.asyncTimer(() -> {
                if (dirty) {
                    saveDirtyVolumes();
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void startCleanupTask() {
        synchronized (this) {
            if (cleanupTask != null) {
                try {
                    cleanupTask.cancel();
                } catch (Exception e) {
                    // Ignore cancellation failures; the task is being replaced either way.
                }
                cleanupTask = null;
            }
            // Map-only cleanup (no world/entity access) -> global region scheduler.
            cleanupTask = com.huidu.musicboxplus.common.utils.scheduler.Scheduler.globalTimer(
                    this::cleanupOfflinePlayers, CLEANUP_INTERVAL_TICKS, CLEANUP_INTERVAL_TICKS);
        }
    }

    private void saveDirtyVolumes() {
        saveDirtyVolumes(com.huidu.musicboxplus.common.utils.AsyncTaskManager::runAsync,
                volumes -> DatabaseLoader.getBase().savePlayerVolumesBatch(volumes));
    }

    void saveDirtyVolumes(BiConsumer<Runnable, Runnable> dispatcher, Consumer<Map<UUID, Integer>> writer) {
        if (saveInProgress) {
            return;
        }
        final long snapshotVersion = volumeVersion.get();
        if (snapshotVersion <= persistedVersion) {
            dirty = false;
            return;
        }
        final Map<UUID, Integer> toSave = snapshotForSave();
        saveInProgress = true;
        dispatcher.accept(() -> {
            boolean success = false;
            try {
                // Single transactional batch instead of N individual upserts — one prepare,
                // one round-trip, one commit even when many players are dirty at once.
                writer.accept(toSave);
                success = true;
            } catch (Exception e) {
                RuntimeDatabaseUtils.logFailure("save player volumes", e);
            } finally {
                // A failed batch must stay dirty so the next sweep retries it. Only advance
                // persistedVersion when the write actually committed (and nothing newer arrived).
                if (success && volumeVersion.get() == snapshotVersion) {
                    persistedVersion = snapshotVersion;
                    dirty = false;
                } else {
                    dirty = true;
                }
                saveInProgress = false;
            }
        }, () -> {
            // Rejection/shutdown means the task's finally block will never run.
            dirty = true;
            saveInProgress = false;
        });
    }

    private void saveDirtyVolumesSync() {
        final long snapshotVersion = volumeVersion.get();
        if (snapshotVersion <= persistedVersion) {
            dirty = false;
            return;
        }
        try {
            DatabaseLoader.getBase().savePlayerVolumesBatch(snapshotForSave());
            persistedVersion = snapshotVersion;
            dirty = false;
        } catch (Exception e) {
            RuntimeDatabaseUtils.logFailure("save player volumes", e);
            dirty = true;
        }
    }

    private Map<UUID, Integer> snapshotForSave() {
        return playerVolumes.snapshotForSave();
    }

    private int getDefaultVolume() {
        return settings().defaultVolume;
    }

    private int getVolumeStep() {
        return settings().step;
    }

    private int getMinVolume() {
        return settings().minVolume;
    }

    private int getMaxVolume() {
        return settings().maxVolume;
    }

    public int getVolume(Player player) {
        return getVolume(player.getUniqueId());
    }

    public int getVolume(UUID uuid) {
        // Fast path for the hot playback loop: getVolume runs ~20Hz per in-range listener. The common
        // case is a cache hit, so return it BEFORE building the reader lambda below and before the
        // getDefaultVolume() read -- otherwise a lambda was allocated on every note dispatch only to
        // be discarded by DeferredVolumeCache's own cache-hit return. The full race-safe
        // deferred-load path still runs on an actual miss.
        Integer cached = playerVolumes.get(uuid);
        if (cached != null) {
            return cached;
        }
        return playerVolumes.resolve(
                uuid, getDefaultVolume(),
                com.huidu.musicboxplus.common.utils.AsyncTaskManager::runAsync,
                u -> DatabaseLoader.getBase().getPlayerVolume(u),
                e -> RuntimeDatabaseUtils.logFailure("load player volume", e));
    }

    // JDK-only cache core. An immutable entry is also the load token: an explicit set always
    // creates a new entry, even for the same numeric default. Pending/failed reads are never
    // persistable; only a successful read (including no row) or an explicit set makes them so.
    static final class DeferredVolumeCache {
        private static final class Entry {
            final Integer volume;
            final boolean persistable;

            Entry(int volume, boolean persistable) {
                this.volume = volume;
                this.persistable = persistable;
            }
        }

        private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

        Integer get(UUID uuid) {
            Entry entry = entries.get(uuid);
            return entry == null ? null : entry.volume;
        }

        int resolve(UUID uuid, int defaultVolume, BiConsumer<Runnable, Runnable> dispatcher,
                    Function<UUID, Integer> reader, Consumer<Exception> onFailure) {
            Integer cached = get(uuid);
            if (cached != null) {
                return cached;
            }
            Entry placeholder = new Entry(defaultVolume, false);
            Entry existing = entries.putIfAbsent(uuid, placeholder);
            if (existing != null) {
                return existing.volume;
            }
            dispatcher.accept(() -> {
                final Integer loaded;
                try {
                    loaded = reader.apply(uuid);
                } catch (Exception e) {
                    // Leave only this untrusted placeholder ineligible for persistence. There is
                    // no separate failure flag that could arrive late and taint an explicit set.
                    onFailure.accept(e);
                    return;
                }
                entries.replace(uuid, placeholder,
                        new Entry(loaded == null ? defaultVolume : loaded, true));
            }, () -> {
                // The read never ran; the placeholder must remain non-persistable.
            });
            return defaultVolume;
        }

        void put(UUID uuid, int volume) {
            entries.put(uuid, new Entry(volume, true));
        }

        void putIfAbsent(UUID uuid, int volume) {
            entries.putIfAbsent(uuid, new Entry(volume, true));
        }

        Integer getPersistable(UUID uuid) {
            Entry entry = entries.get(uuid);
            return entry != null && entry.persistable ? entry.volume : null;
        }

        boolean isPersistable(UUID uuid) {
            return getPersistable(uuid) != null;
        }

        Map<UUID, Integer> snapshotForSave() {
            Map<UUID, Integer> snapshot = new HashMap<>();
            entries.forEach((uuid, entry) -> {
                if (entry.persistable) {
                    snapshot.put(uuid, entry.volume);
                }
            });
            return snapshot;
        }

        void remove(UUID uuid) {
            entries.remove(uuid);
        }

        void retainOnline(Set<UUID> onlineUuids) {
            entries.keySet().removeIf(uuid -> !onlineUuids.contains(uuid));
        }

        int size() {
            return entries.size();
        }

        void clear() {
            entries.clear();
        }
    }

    // Compatibility helper for existing map-based tests. Production uses token entries above;
    // numeric map equality alone cannot distinguish set(default) from the original placeholder.
    // Race-safe deferred load: returns the cached volume if present, otherwise inserts defaultVolume
    // as a placeholder, dispatches an async DB load via asyncExecutor, and returns the placeholder.
    //
    // The async path overwrites the placeholder only through a compare-and-set
    // (ConcurrentHashMap.replace). If a setVolume lands between the placeholder insert and the DB
    // load completing, the CAS fails and the player's own value survives instead of being clobbered
    // by the stale DB value.
    //
    // No try/catch here: callers handle exceptions inside dbReader (which returns null on failure)
    // and inside onLoaded.
    //
    // Package-private so VolumeManagerRaceTest can drive the race deterministically with a
    // latch-gated executor, without any Bukkit/DB scaffolding.
    static int resolveVolumeWithDeferredLoad(
            UUID uuid,
            int defaultVolume,
            Map<UUID, Integer> playerVolumes,
            Executor asyncExecutor,
            Function<UUID, Integer> dbReader,
            Consumer<Integer> onLoaded
    ) {
        Integer cached = playerVolumes.get(uuid);
        if (cached != null) {
            return cached;
        }

        final Integer defaultBoxed = defaultVolume;
        Integer existing = playerVolumes.putIfAbsent(uuid, defaultBoxed);
        if (existing != null) {
            return existing;
        }

        asyncExecutor.execute(() -> {
            Integer dbVolume = dbReader.apply(uuid);
            if (dbVolume == null) {
                return;
            }
            if (!playerVolumes.replace(uuid, defaultBoxed, dbVolume)) {
                return;
            }
            if (onLoaded != null) {
                onLoaded.accept(dbVolume);
            }
        });

        return defaultVolume;
    }

    public void setVolume(Player player, int volume) {
        setVolume(player.getUniqueId(), volume);
    }

    public void setVolume(UUID uuid, int volume) {
        cacheExplicitVolume(uuid, clampVolume(volume));
    }

    void cacheExplicitVolume(UUID uuid, int volume) {
        playerVolumes.put(uuid, volume);
        volumeVersion.incrementAndGet();
        dirty = true;
    }

    public int increaseVolume(Player player) {
        int newVolume = increaseVolume(player.getUniqueId());
        return newVolume;
    }

    public int increaseVolume(UUID uuid) {
        int current = getVolume(uuid);
        int newVolume = clampVolume(current + getVolumeStep());
        setVolume(uuid, newVolume);
        return newVolume;
    }

    public int decreaseVolume(Player player) {
        int newVolume = decreaseVolume(player.getUniqueId());
        return newVolume;
    }

    public int decreaseVolume(UUID uuid) {
        int current = getVolume(uuid);
        int newVolume = clampVolume(current - getVolumeStep());
        setVolume(uuid, newVolume);
        return newVolume;
    }

    public int setVolumeByAmount(Player player, int amount) {
        int newVolume = setVolumeByAmount(player.getUniqueId(), amount);
        byte byteVolume = (byte) Math.max(0, Math.min(100, newVolume));
        return newVolume;
    }

    public int setVolumeByAmount(UUID uuid, int amount) {
        int newVolume = clampVolume(amount);
        setVolume(uuid, newVolume);
        return newVolume;
    }

    public int setVolumeByPercent(Player player, int percent) {
        int newVolume = setVolumeByPercent(player.getUniqueId(), percent);
        byte byteVolume = (byte) Math.max(0, Math.min(100, newVolume));
        return newVolume;
    }

    public int setVolumeByPercent(UUID uuid, int percent) {
        int volume = (int) (getDefaultVolume() * (percent / 100.0));
        return setVolumeByAmount(uuid, volume);
    }

    private int clampVolume(int volume) {
        return Math.max(getMinVolume(), Math.min(getMaxVolume(), volume));
    }

    public void sendVolumeMessage(Player player) {
        int volume = getVolume(player);
        String bar = generateVolumeBar(volume);
        MessageUtils.send(player, Lang.VOLUME_CURRENT.toString()
                .replace("{volume}", String.valueOf(volume))
                .replace("{bar}", bar));
    }

    private String generateVolumeBar(int volume) {
        int bars = volume / 10;
        StringBuilder sb = new StringBuilder();
        sb.append("<dark_gray>[");
        for (int i = 0; i < 10; i++) {
            if (i < bars) {
                sb.append("<green>█");
            } else {
                sb.append("<gray>█");
            }
        }
        sb.append("<dark_gray>]");
        return StringUtils.t(sb.toString());
    }

    public void resetVolume(Player player) {
        resetVolume(player.getUniqueId());
    }

    public void resetVolume(UUID uuid) {
        playerVolumes.remove(uuid);
        final long snapshotVersion = volumeVersion.incrementAndGet();
        dirty = true;
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                DatabaseLoader.getBase().deletePlayerVolume(uuid);
                if (volumeVersion.get() == snapshotVersion) {
                    persistedVersion = snapshotVersion;
                    dirty = false;
                } else {
                    dirty = true;
                }
            } catch (Exception e) {
                RuntimeDatabaseUtils.logFailure("reset player volume", e);
            }
        });
    }

    public void cleanupOfflinePlayers() {
        int beforeSize = playerVolumes.size();
        // Snapshot online UUIDs once instead of calling Bukkit.getPlayer(uuid) per entry,
        // which would degrade to O(N*M) when many players have cached volumes.
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        Set<UUID> onlineUuids = new HashSet<>(onlinePlayers.size());
        for (Player p : onlinePlayers) {
            onlineUuids.add(p.getUniqueId());
        }
        // Flush any unsaved volumes for players we're about to evict, so we don't lose changes.
        if (dirty) {
            for (Map.Entry<UUID, Integer> entry : snapshotForSave().entrySet()) {
                if (!onlineUuids.contains(entry.getKey())) {
                    saveSinglePlayerVolume(entry.getKey());
                }
            }
        }
        playerVolumes.retainOnline(onlineUuids);
        int afterSize = playerVolumes.size();
        if (beforeSize != afterSize) {
            MusicBox.getInstance().getLogger().fine("Cleaned up " + (beforeSize - afterSize) + " offline player volumes");
        }
    }

    public static void cleanup(Player player) {
        if (instance != null) {
            UUID playerId = player.getUniqueId();
            Integer volume = instance.playerVolumes.get(playerId);
            if (volume != null && instance.dirty) {
                instance.saveSinglePlayerVolume(playerId);
            }
            instance.playerVolumes.remove(playerId);
        }
    }

    private void saveSinglePlayerVolume(UUID playerId) {
        // Read value and trust from the same immutable entry. A separate trust check could
        // approve a newer explicit set while still submitting an older untrusted default.
        Integer volume = playerVolumes.getPersistable(playerId);
        if (volume == null) {
            return;
        }
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                DatabaseLoader.getBase().savePlayerVolume(playerId, volume);
            } catch (Exception e) {
                RuntimeDatabaseUtils.logFailure("save player volume", e);
                // A failed write must not silently lose the change: put the entry back so the
                // next sweep (saveDirtyVolumes or cleanup) retries it. putIfAbsent never
                // clobbers a fresher volume written since the snapshot was taken.
                playerVolumes.putIfAbsent(playerId, volume);
                dirty = true;
            }
        });
    }

    public static void shutdown() {
        if (instance != null) {
            if (instance.autoSaveTask != null) {
                instance.autoSaveTask.cancel();
            }
            if (instance.cleanupTask != null) {
                instance.cleanupTask.cancel();
            }
            instance.saveDirtyVolumesSync();
            instance.playerVolumes.clear();
            instance = null;
        }
    }

    public static void reset() {
        shutdown();
    }

    public static int getPlayerVolume(Player player) {
        return getInstance().getVolume(player);
    }

    public static void setPlayerVolume(Player player, int volume) {
        getInstance().setVolume(player, volume);
    }

    public void mutePlayer(Player player) {
        setVolume(player, 0);
    }

    public void setMaxVolume(Player player) {
        setVolume(player, getMaxVolume());
    }

    public static float getPlayerVolumePercent(Player player) {
        int volume = getPlayerVolume(player);
        int maxVolume = getInstance().getMaxVolume();
        if (maxVolume <= 0) {
            return 1.0f;
        }
        return (float) volume / maxVolume;
    }
}
