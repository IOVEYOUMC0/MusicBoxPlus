package com.huidu.musicboxplus.core.playback;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.BukkitUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.core.player.PlaybackSetup;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.player.playlist.ListPlaylist;
import com.huidu.musicboxplus.core.player.playlist.SingletonPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.LinkedBlockingDeque;

public class PlayerWrapper {
    public static final String METADATA_KEY = "musicboxInstance";

    // Concrete player creation is a module concern (Radio/Speaker players); core holds the
    // factories and module registers them at startup, so PlayerWrapper never imports module.
    private static volatile PlayerFactory speakerFactory;
    private static volatile PlayerFactory radioFactory;

    public static void setPlayerFactories(PlayerFactory speakerFactory, PlayerFactory radioFactory) {
        PlayerWrapper.speakerFactory = speakerFactory;
        PlayerWrapper.radioFactory = radioFactory;
    }

    // Holder pattern: NamespacedKeys are built lazily on first use, after MusicBox.instance is set.
    // Building them as static final fields on PlayerWrapper itself would crash if the class
    // initialized before onEnable, because NamespacedKey rejects a null plugin reference.
    private static final class Keys {
        static final NamespacedKey SPEAKER = new NamespacedKey(MusicBox.getInstance(), "speaker_mode");
        static final NamespacedKey SILENT = new NamespacedKey(MusicBox.getInstance(), "silent_mode");
        static final NamespacedKey AUTOPLAY = new NamespacedKey(MusicBox.getInstance(), "autoplay_enabled");
    }
    private final WeakReference<Player> playerRef;
    private boolean speaker;
    private boolean silent = false;
    private boolean autoPlayEnabled = true;
    private LoopMode loopMode = LoopMode.OFF;
    private volatile PlayerSongPlayer activePlayer;
    private PlayerPlayListModel playList;
    private final LinkedBlockingDeque<MusicBoxSong> recentSongs = new LinkedBlockingDeque<MusicBoxSong>();
    // The recent history fills in asynchronously after join; GUI rendering reads the deque
    // synchronously, so an unloaded history renders as empty and a GUI opened in that window
    // shows no "recent songs" button until the load lands. One-shot refresh callbacks below
    // let an open GUI pick the button up the moment the load finishes.
    private volatile boolean recentSongsLoaded = false;
    private final List<Runnable> recentSongsLoadCallbacks = new ArrayList<Runnable>();
    private volatile BossBar playBar;
    private float playbackSpeedMultiplier = 1.0f;
    private boolean seamlessPlayerSwap = false;

    // Recent-song persistence is throttled: every play() call refreshes the in-memory snapshot
    // (on the player's own thread) but the DELETE+reinsert rewrite fires at most once per window,
    // or rapid song switches each rewrite the whole list on the DB.
    private static final long RECENT_SAVE_DELAY_MILLIS = 30_000L;
    private final Object recentSaveLock = new Object();
    private volatile List<MusicBoxSong> recentSaveSnapshot;
    private volatile com.huidu.musicboxplus.common.utils.scheduler.MbTask recentSaveTask;

    private PlayerWrapper(Player player) {
        this.playerRef = new WeakReference<>(player);
        this.loadPersistentModes(player);
        MusicBoxConfig.BossBarSetting bossBarConfig = MusicBox.getInstance().getConfigObject().getBossbar();
        if (bossBarConfig.isEnable()) {
            this.playBar = Bukkit.createBossBar(
                "",
                resolveBossBarColor(bossBarConfig.getColor()),
                resolveBossBarStyle(bossBarConfig.getStyle()),
                this.resolveBossBarFlags(bossBarConfig.getFlags())
            );
            this.playBar.setVisible(false);
            this.playBar.addPlayer(player);
        }
        // Primes the volume cache so the first read on the playback path is a hit rather than a
        // deferred database load.
        VolumeManager.getPlayerVolume(player);
    }

    private void loadPersistentModes(Player player) {
        Byte speakerValue = player.getPersistentDataContainer().get(Keys.SPEAKER, PersistentDataType.BYTE);
        Byte silentValue = player.getPersistentDataContainer().get(Keys.SILENT, PersistentDataType.BYTE);
        this.speaker = speakerValue != null && speakerValue == (byte) 1;
        this.silent = silentValue != null && silentValue == (byte) 1;
        Byte autoPlayValue = player.getPersistentDataContainer().get(Keys.AUTOPLAY, PersistentDataType.BYTE);
        this.autoPlayEnabled = autoPlayValue == null || autoPlayValue == (byte) 1;
    }

    private void savePersistentModes() {
        Player player = getPlayer();
        if (player == null) {
            return;
        }
        final byte speakerByte = this.speaker ? (byte) 1 : (byte) 0;
        final byte silentByte = this.silent ? (byte) 1 : (byte) 0;
        final byte autoPlayByte = this.autoPlayEnabled ? (byte) 1 : (byte) 0;
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> {
            player.getPersistentDataContainer().set(Keys.SPEAKER, PersistentDataType.BYTE, speakerByte);
            player.getPersistentDataContainer().set(Keys.SILENT, PersistentDataType.BYTE, silentByte);
            player.getPersistentDataContainer().set(Keys.AUTOPLAY, PersistentDataType.BYTE, autoPlayByte);
        });
    }

    // The field is named from the user's perspective (true = wants auto-play), but every call
    // site needs "has the player switched it off". Name the getter after what it answers so a
    // future reader cannot mistake a true return for "auto-play is on".
    public boolean isAutoPlayOptedOut() {
        return !this.autoPlayEnabled;
    }

    public void setAutoPlayEnabled(boolean autoPlayEnabled) {
        this.autoPlayEnabled = autoPlayEnabled;
        this.savePersistentModes();
    }

    // A misspelled or missing colour must not take the player down with it: this runs in the
    // PlayerWrapper constructor, so an exception here leaves the joining player with no wrapper
    // at all -- no playback, no commands, no GUI -- for a purely cosmetic setting.
    private static BarColor resolveBossBarColor(String configured) {
        try {
            return BarColor.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) {
            MusicBox.getInstance().getLogger().warning(
                "Unknown bossbar.color '" + configured + "', falling back to BLUE");
            return BarColor.BLUE;
        }
    }

    private static BarStyle resolveBossBarStyle(String configured) {
        try {
            return BarStyle.valueOf(configured.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ignored) {
            MusicBox.getInstance().getLogger().warning(
                "Unknown bossbar.style '" + configured + "', falling back to SEGMENTED_20");
            return BarStyle.SEGMENTED_20;
        }
    }

    private BarFlag[] resolveBossBarFlags(List<String> configuredFlags) {
        if (configuredFlags == null || configuredFlags.isEmpty()) {
            return new BarFlag[0];
        }
        List<BarFlag> result = new ArrayList<>();
        for (String flagName : configuredFlags) {
            if (flagName == null || flagName.isBlank()) {
                continue;
            }
            try {
                result.add(BarFlag.valueOf(flagName.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                MusicBox.getInstance().getLogger().warning("Ignoring invalid bossbar flag: " + flagName);
            }
        }
        return result.toArray(new BarFlag[0]);
    }

    public static Optional<PlayerWrapper> getInstanceOptional(Player player) {
        return Optional.ofNullable(BukkitUtils.extractMetadata(PlayerWrapper.class, player, METADATA_KEY));
    }

    public static PlayerWrapper getInstance(Player player) {
        return PlayerWrapper.getInstanceOptional(player).orElseGet(() -> {
            PlayerWrapper instance = new PlayerWrapper(player);
            player.setMetadata(METADATA_KEY, new FixedMetadataValue(MusicBox.getInstance(), instance));
            instance.loadRecentSongs();
            return instance;
        });
    }

    public static void clearAll() {
        clearAll(true);
    }

    public static void clearAll(boolean saveRecentSongs) {
        // destroy() tears down wrapper state synchronously here (so a following reload/restore
        // observes a clean slate) and self-defers only its player-touching work (boss bar) onto
        // the owner's region — so this stays Folia-safe without making the whole teardown async.
        Bukkit.getOnlinePlayers().forEach(pl ->
            getInstanceOptional(pl).ifPresent(wrapper -> wrapper.destroy(saveRecentSongs)));
    }

    public void destroy() {
        destroy(true);
    }

    public void destroy(boolean saveRecentSongs) {
        if (saveRecentSongs) {
            saveRecentSongs();
        }
        this.destroyActivePlayer();
        Player localPlayer = getPlayer();
        BossBar bar = this.playBar;
        this.playBar = null;
        if (bar != null) {
            if (localPlayer != null) {
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(localPlayer, bar::removeAll);
            } else {
                bar.removeAll();
            }
        }
        if (localPlayer != null && localPlayer.isOnline()) {
            localPlayer.removeMetadata(METADATA_KEY, MusicBox.getInstance());
        }
        // The throttled save above schedules a 30s flush; a wrapper being destroyed must not
        // write this player's recent songs to the database after it has logged out.
        synchronized (recentSaveLock) {
            com.huidu.musicboxplus.common.utils.scheduler.MbTask recentTask = this.recentSaveTask;
            this.recentSaveTask = null;
            this.recentSaveSnapshot = null;
            if (recentTask != null) {
                recentTask.cancel();
            }
        }
        this.recentSongs.clear();
    }

    public boolean isPlayNow() {
        return this.activePlayer != null;
    }

    public boolean canSwitch() {
        Player player = getPlayer();
        return player != null && player.hasPermission(Permissions.SPEAKER);
    }

    public boolean switchModeChecked() {
        Player player = getPlayer();
        if (!this.canSwitch()) {
            if (player != null) {
                MessageUtils.send(player, Lang.CANT_SWITCH);
            }
            return false;
        }
        this.switchMode();
        return true;
    }

    public void switchMode() {
        this.setSpeaker(!this.speaker);
    }

    public void setSpeaker(boolean speaker) {
        if (this.speaker == speaker) {
            return;
        }
        this.speaker = speaker;
        this.savePersistentModes();
        this.refreshBossBarTitle();
        if (this.isPlayNow()) {
            PlayerSongPlayer oldPlayer = this.getActivePlayer();
            this.restartPlayback(oldPlayer.getPlayList(), oldPlayer.getTick());
        }
    }

    public void play(IPlayList song) {
        this.play(song, (short)-1);
    }

    public void play(IPlayList playList, short tick) {
        this.play(playList, tick, null);
    }

    // afterStart is how a caller learns that playback actually exists. Waiting a fixed number of
    // ticks instead does not work: a song whose arrangement is not compiled yet takes an async
    // detour and comes back later than any guess.
    public void play(IPlayList playList, short tick, Runnable afterStart) {
        int delayTicks;
        if (!MusicBox.getInstance().isPlaybackModuleEnabled()) {
            return;
        }
        MusicBoxSong currentSong = (MusicBoxSong) playList.getCurrent();
        if (currentSong != null) {
            this.addRecentSong(currentSong);
        }
        Player delayTarget = this.getPlayer();
        if ((delayTicks = MusicBox.getInstance().getConfigObject().getPlayDelayTicks()) > 0 && delayTarget != null) {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityLater(
                    delayTarget, () -> this.startPlayInternal(playList, tick, afterStart), delayTicks);
        } else {
            this.startPlayInternal(playList, tick, afterStart);
        }
    }

    private void startPlayInternal(IPlayList playList, short tick) {
        this.startPlayInternal(playList, tick, null);
    }

    // afterStart runs on the same thread that created the player, immediately after it exists, so
    // callers that need to touch the new player keep working whichever path whenReady takes.
    private void startPlayInternal(IPlayList playList, short tick, Runnable afterStart) {
        LoopMode oldLoopMode = this.loopMode;
        PlaybackSetup.whenReady(playList, this::dispatchToListener, () -> {
            if (this.speaker) {
                this.startSpeaker(playList);
            } else {
                this.startRadio(playList);
            }
            if (this.activePlayer != null) {
                if (tick > -1) {
                    this.activePlayer.setTick(tick);
                }
                if (oldLoopMode != LoopMode.OFF) {
                    this.activePlayer.getMusicBoxModel().setLoopMode(oldLoopMode);
                }
            }
            // The next song's arrangement is built now rather than at the transition, where the
            // cost would land on the playback thread mid-song.
            PlaybackSetup.prefetchNext(playList);
            if (afterStart != null) {
                afterStart.run();
            }
        });
    }

    // Where deferred playback start belongs: this player's own thread, which owns everything the
    // start touches. Falls back to the global region once they are gone.
    private void dispatchToListener(Runnable run) {
        Player player = this.getPlayer();
        if (player != null && player.isOnline()) {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entity(player, run);
        } else {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.global(run);
        }
    }

    private void restartPlayback(IPlayList playList, short tick) {
        this.seamlessPlayerSwap = true;
        try {
            // A restart always follows a song that was just playing, so its arrangement is in
            // memory and this runs inline; the bar update is passed along anyway so the cold path
            // cannot silently skip it.
            this.startPlayInternal(playList, tick, () -> {
                if (this.activePlayer != null) {
                    MusicBoxSong song = (MusicBoxSong) this.activePlayer.getMusicBoxSong();
                    if (song != null && song.getLength() > 0) {
                        double progress = Math.max(0.0, Math.min(1.0, (double)Math.max(0, tick) / (double)song.getLength()));
                        this.setBarProgress(progress);
                    }
                    this.setBarVisible(true);
                }
            });
        } finally {
            this.seamlessPlayerSwap = false;
        }
    }

    public void addRecentSong(MusicBoxSong song) {
        if (song == null) {
            return;
        }
        int maxRecentSongs = MusicBox.getInstance().getConfigObject().getMaxRecentSongs();
        
        recentSongs.remove(song);
        recentSongs.addFirst(song);
        
        while (recentSongs.size() > maxRecentSongs) {
            recentSongs.removeLast();
        }
        
        saveRecentSongsBatchInternal();
    }
    
    private void saveRecentSongsBatchInternal() {
        Player player = getPlayer();
        if (player == null) {
            return;
        }
        final java.util.UUID playerId = player.getUniqueId();
        synchronized (recentSaveLock) {
            // Captured on the caller's (player) thread so the async flush never touches the live
            // deque across threads; a newer capture simply replaces this one before the flush.
            recentSaveSnapshot = new ArrayList<>(recentSongs);
            if (recentSaveTask != null) {
                return; // A flush is already pending and will pick up this snapshot.
            }
            recentSaveTask = com.huidu.musicboxplus.common.utils.scheduler.Scheduler.asyncLater(
                    () -> flushRecentSongs(playerId), RECENT_SAVE_DELAY_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    private void flushRecentSongs(java.util.UUID playerId) {
        synchronized (recentSaveLock) {
            List<MusicBoxSong> toSave = recentSaveSnapshot;
            recentSaveSnapshot = null;
            recentSaveTask = null;
            if (toSave != null) {
                saveRecentSongsSync(playerId, toSave);
            }
        }
    }

    private void saveRecentSongsSync(java.util.UUID playerId, List<MusicBoxSong> songsToSave) {
        try {
            List<Integer> hashes = new ArrayList<>(songsToSave.size());
            for (MusicBoxSong s : songsToSave) {
                hashes.add(s.getHash());
            }
            DatabaseLoader.getBase().saveRecentSongsBatch(playerId, hashes);
        } catch (Exception e) {
            RuntimeDatabaseUtils.logFailure("save recent songs", e);
        }
    }

    public void loadRecentSongs() {
        Player player = getPlayer();
        if (player == null) {
            return;
        }
        final java.util.UUID playerId = player.getUniqueId();
        final int maxRecentSongs = MusicBox.getInstance().getConfigObject().getMaxRecentSongs();
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                List<Integer> songHashes = DatabaseLoader.getBase().getRecentSongs(playerId);
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entity(player, () -> {
                    this.recentSongs.clear();
                    int loaded = 0;
                    for (Integer hash : songHashes) {
                        if (loaded >= maxRecentSongs) {
                            break;
                        }
                        MusicBoxSong song = MusicBoxSongManager.findSongByHash(hash).orElse(null);
                        if (song == null) continue;
                        this.recentSongs.add(song);
                        loaded++;
                    }
                    this.markRecentSongsLoaded();
                });
            } catch (Exception e) {
                RuntimeDatabaseUtils.logFailure("load recent songs", e);
            }
        });
    }

    public void saveRecentSongs() {
        saveRecentSongsBatchInternal();
    }

    public void saveRecentSongsNow() {
        Player player = getPlayer();
        if (player == null) {
            return;
        }
        this.saveRecentSongsSync(player.getUniqueId(), new ArrayList<>(recentSongs));
    }

    public MusicBoxSong getRecentSong() {
        return this.recentSongs.peekFirst();
    }

    public List<MusicBoxSong> getRecentSongs() {
        return new ArrayList<MusicBoxSong>(this.recentSongs);
    }

    public boolean isRecentSongsLoaded() {
        return this.recentSongsLoaded;
    }

    // Runs the callback once the recent-history load finishes, or immediately when it already
    // did. Used by GUIs that render the history button: a GUI opened during the load can arm
    // a one-shot refresh instead of waiting for a manual click to reveal the button.
    public void onRecentSongsLoaded(Runnable callback) {
        if (callback == null) {
            return;
        }
        synchronized (this.recentSongsLoadCallbacks) {
            if (this.recentSongsLoaded) {
                callback.run();
                return;
            }
            this.recentSongsLoadCallbacks.add(callback);
        }
    }

    // Called on the player's region once the history has been filled, so pending GUI refresh
    // callbacks run on the main thread they were registered from.
    private void markRecentSongsLoaded() {
        List<Runnable> pending;
        synchronized (this.recentSongsLoadCallbacks) {
            this.recentSongsLoaded = true;
            pending = new ArrayList<Runnable>(this.recentSongsLoadCallbacks);
            this.recentSongsLoadCallbacks.clear();
        }
        for (Runnable callback : pending) {
            callback.run();
        }
    }

    public void clearRecentSongs() {
        this.recentSongs.clear();
        Player player = getPlayer();
        if (player == null) {
            return;
        }
        final java.util.UUID playerId = player.getUniqueId();
        com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
            try {
                DatabaseLoader.getBase().clearRecentSongs(playerId);
            } catch (Exception e) {
                RuntimeDatabaseUtils.logFailure("clear recent songs", e);
            }
        });
    }

    public void startSpeaker(IPlayList playList) {
        this.destroyActivePlayer();
        this.activePlayer = speakerFactory != null ? speakerFactory.create(playList, this) : null;
    }

    public void startRadio(IPlayList playList) {
        this.destroyActivePlayer();
        this.activePlayer = radioFactory != null ? radioFactory.create(playList, this) : null;
    }

    public void setPlaybackSpeedMultiplier(float playbackSpeedMultiplier) {
        MusicBoxConfig.SpeedConfig speedConfig = MusicBox.getInstance().getConfigObject().getSpeed();
        float min = speedConfig.getMinSpeed();
        float max = speedConfig.getMaxSpeed();
        float normalized = Math.max(min, Math.min(max, playbackSpeedMultiplier));
        boolean wasPaused = this.activePlayer != null && this.activePlayer.isPaused();
        short tick = this.activePlayer != null ? this.activePlayer.getTick() : -1;
        IPlayList playList = this.activePlayer != null ? this.activePlayer.getPlayList() : null;
        this.playbackSpeedMultiplier = normalized;
        this.refreshBossBarTitle();
        if (playList != null) {
            this.restartPlayback(playList, tick);
            if (wasPaused && this.activePlayer != null) {
                this.activePlayer.pause();
            }
        }
    }

    public void resetPlaybackSpeedMultiplier() {
        this.setPlaybackSpeedMultiplier(MusicBox.getInstance().getConfigObject().getSpeed().getDefaultSpeed());
    }

    public synchronized void destroyActivePlayer() {
        PlayerSongPlayer player = this.activePlayer;
        if (player != null) {
            this.activePlayer = null;
            player.destroy();
            this.afterDestroy();
        }
    }

    private void afterDestroy() {
        if (!this.seamlessPlayerSwap) {
            this.setBarVisible(false);
        }
        this.activePlayer = null;
    }

    public boolean isSeamlessPlayerSwap() {
        return this.seamlessPlayerSwap;
    }

    public void setBarVisible(boolean visible) {
        if (this.playBar == null) {
            return;
        }
        Player player = this.getPlayer();
        if (player == null) {
            return;
        }
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> {
            if (this.playBar != null) {
                this.playBar.setVisible(visible);
            }
        });
    }

    public void setBarTitle(String title) {
        if (this.playBar == null) {
            return;
        }
        Player player = this.getPlayer();
        if (player == null) {
            return;
        }
        String renderedTitle = title == null ? "" : MiniMessageUtils.toLegacyText(MiniMessageUtils.processComponent(title));
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> {
            if (this.playBar != null) {
                this.playBar.setTitle(renderedTitle);
            }
        });
    }

    public String buildBossBarTitle(MusicBoxSong song) {
        MusicBoxConfig.BossBarSetting bossBarConfig = MusicBox.getInstance().getConfigObject().getBossbar();
        String format = bossBarConfig != null && bossBarConfig.getFormat() != null && !bossBarConfig.getFormat().isBlank()
            ? bossBarConfig.getFormat()
            : "<green>{song}</green>";

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{song}", song != null ? song.getName() : Lang.UNKNOWN.toString());
        placeholders.put("{author}", song != null ? song.getAuthor() : Lang.UNKNOWN.toString());
        placeholders.put("{original_author}", song != null ? song.getOriginalAuthor() : Lang.UNKNOWN.toString());
        placeholders.put("{length}", song != null ? song.getLengthFormatted() : "0:00");
        placeholders.put("{mode}", this.getLocalizedPlaybackMode());
        placeholders.put("{silent}", this.getLocalizedSilentState());
        placeholders.put("{speed}", String.format(Locale.US, "%.2f", this.playbackSpeedMultiplier));
        placeholders.put("{volume}", String.valueOf(this.getVolume()));
        return com.huidu.musicboxplus.common.utils.StringUtils.replaceVariables(format, placeholders);
    }

    public String getLocalizedPlaybackMode() {
        return this.speaker ? Lang.PLACEHOLDER_MODE_SPEAKER.toString() : Lang.PLACEHOLDER_MODE_RADIO.toString();
    }

    public String getLocalizedSilentState() {
        return this.silent ? Lang.PLAY_SILENT_STATE_SILENT.toString() : Lang.PLAY_SILENT_STATE_AUDIBLE.toString();
    }

    public void refreshBossBarTitle() {
        MusicBoxSong song = null;
        if (this.activePlayer != null) {
            try {
                song = (MusicBoxSong) this.activePlayer.getMusicBoxSong();
            } catch (Exception e) {
                MusicBox.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                    "Failed to get current song for bossbar", e);
            }
        }
        this.setBarTitle(this.buildBossBarTitle(song));
    }

    public void setBarProgress(double progress) {
        if (this.playBar == null) {
            return;
        }
        Player player = this.getPlayer();
        if (player == null) {
            return;
        }
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> {
            if (this.playBar != null) {
                this.playBar.setProgress(progress);
            }
        });
    }

    public void play(MusicBoxSong song) {
        this.play(new SingletonPlayList(song));
    }

    public void play(SongContainer container) {
        this.play(container, null);
    }

    public void play(SongContainer container, Runnable afterStart) {
        this.play(ListPlaylist.fromContainer(container, false, false), (short) -1, afterStart);
    }

    public void nullActivePlayer(MusicBoxSongPlayer playerModel) {
        if (this.activePlayer == playerModel) {
            this.afterDestroy();
        }
    }

    public boolean canHearMusic() {
        Player player = getPlayer();
        return player != null && !this.silent && (!MusicBox.getInstance().getConfigObject().isHearPermissionsCheck() || player.hasPermission(Permissions.HEAR));
    }

    public int getVolume() {
        Player player = getPlayer();
        return player != null ? VolumeManager.getPlayerVolume(player) : 0;
    }

    public void setVolume(int volume) {
        Player player = getPlayer();
        if (player != null) {
            VolumeManager.setPlayerVolume(player, volume);
        }
    }

    public void increaseVolume() {
        Player player = getPlayer();
        if (player != null) {
            VolumeManager.getInstance().increaseVolume(player);
        }
    }

    public void decreaseVolume() {
        Player player = getPlayer();
        if (player != null) {
            VolumeManager.getInstance().decreaseVolume(player);
        }
    }

    public void mute() {
        Player player = getPlayer();
        if (player != null) {
            VolumeManager.getInstance().mutePlayer(player);
        }
    }

    public void setMaxVolume() {
        Player player = getPlayer();
        if (player != null) {
            VolumeManager.getInstance().setMaxVolume(player);
        }
    }

    public float getVolumePercent() {
        Player player = getPlayer();
        return player != null ? VolumeManager.getPlayerVolumePercent(player) : 0f;
    }



    public PlaybackContext getPlaybackContext() {
        return PlaybackContext.fromPlayer(this.activePlayer);
    }


    public void setPlayList(PlayerPlayListModel playlist) {
        if (playlist != null && !playlist.getSongs().isEmpty()) {
            this.play(playlist);
        }
    }

    public Player getPlayer() {
        return this.playerRef.get();
    }

    public boolean isSpeaker() {
        return this.speaker;
    }

    public boolean isSilent() {
        return this.silent;
    }

    public PlayerSongPlayer getActivePlayer() {
        return this.activePlayer;
    }

    public PlayerPlayListModel getPlayList() {
        return this.playList;
    }

    public void setSilent(boolean silent) {
        this.silent = silent;
        this.savePersistentModes();
        this.refreshBossBarTitle();
    }

    public void setLoopMode(LoopMode loopMode) {
        this.loopMode = loopMode;
        if (this.activePlayer != null) {
            this.activePlayer.getMusicBoxModel().setLoopMode(loopMode);
        }
    }

    public LoopMode getLoopMode() {
        return this.loopMode;
    }

    public float getPlaybackSpeedMultiplier() {
        return this.playbackSpeedMultiplier;
    }
}
