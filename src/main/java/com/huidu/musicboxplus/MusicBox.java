package com.huidu.musicboxplus;

import com.huidu.musicboxplus.common.Paths;
import com.huidu.musicboxplus.common.config.*;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.common.stats.bukkit.Metrics;
import com.huidu.musicboxplus.common.stats.charts.SingleLineChart;
import com.huidu.musicboxplus.common.utils.AsyncTaskManager;
import com.huidu.musicboxplus.common.utils.DebugLogger;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.cache.CacheUtils;
import com.huidu.musicboxplus.api.MusicBoxAPI;
import com.huidu.musicboxplus.core.api.MusicBoxApiServiceImpl;
import com.huidu.musicboxplus.core.lifecycle.BundledResourceExtractor;
import com.huidu.musicboxplus.module.lifecycle.ReloadPlaybackState;
import com.huidu.musicboxplus.core.playback.PlayerLifecycleListener;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.player.PlayerManager;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongContainer;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.SongAliasConfig;
import com.huidu.musicboxplus.module.ModuleRuntimeSync;
import com.huidu.musicboxplus.module.ShutdownSteps;
import com.huidu.musicboxplus.module.command.MusicBoxExecutor;
import com.huidu.musicboxplus.module.edit.MusicEditListener;
import com.huidu.musicboxplus.module.edit.PlayerMusicManager;
import com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager;
import com.huidu.musicboxplus.module.gui.GUIActions;
import com.huidu.musicboxplus.module.gui.GUIInputManager;
import com.huidu.musicboxplus.module.gui.minecraft.GUI;
import com.huidu.musicboxplus.module.hook.MusicBoxExpansion;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import com.huidu.musicboxplus.module.textdisplay.TextDisplayStore;
import com.huidu.musicboxplus.module.web.WebEditorServer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

public final class MusicBox extends JavaPlugin {
    private static MusicBox instance;

    private final ReentrantLock reloadLock = new ReentrantLock();
    private final Condition reloadCondition = reloadLock.newCondition();
    private volatile boolean reloading = false;
    private final ConcurrentHashMap<String, SmartConfigManager> configManagers = new ConcurrentHashMap<>();

    private MusicBoxConfig configObject;
    private volatile boolean loaded = false;
    private volatile boolean shuttingDown = false;
    private Metrics bStats;
    private WebEditorServer webEditorServer;
    private final ReloadPlaybackState reloadPlaybackState = new ReloadPlaybackState(this);
    // How often placed text displays are flushed to text-displays.yml. They change only when
    // somebody edits one, so this is a safety net rather than a hot path.
    private static final long TEXT_DISPLAY_SAVE_INTERVAL_SECONDS = 60L;
    private final ModuleRuntimeSync moduleRuntimeSync = new ModuleRuntimeSync(this);
    private final ShutdownSteps shutdownSteps = new ShutdownSteps(this);
    // Kept so onDisable can unregister it; persist()=true stops PAPI from doing so automatically.
    private MusicBoxExpansion papiExpansion;

    public static MusicBox getInstance() {
        return instance;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    private String logText(String english, String chinese) {
        return LogLocale.text(this, english, chinese);
    }

    @Override
    public void onEnable() {
        instance = this;
        migrateLegacyDataFolder();

        // Touch classes that onDisable references so the Paper plugin classloader is
        // forced to link them now, while it's fully open. Some Paper/Purpur builds put
        // the classloader into a "no new classes" mode during disable, and any class
        // that was never used during the server's session (e.g. PlayerSongPlayer if
        // nobody logged in / no GUI was opened) would throw NoClassDefFoundError at
        // shutdown — breaking the cleanup chain mid-way and leaving the DB un-closed.
        eagerLoadShutdownClasses();
        MusicBoxAPI.setService(new MusicBoxApiServiceImpl());

        new BundledResourceExtractor(this).ensureDefaults(getFile());
        CacheUtils.initialize();
        GUI.registerListener();
        initializeSmartConfigs();
        logStartupBanner();
        ConfigManager.getInstance(this);
        LanguageConfig.getInstance(this);
        GUIInputManager.getInstance();
        new GUIConfigManager(this);
        GUIActions.init();

        registerCommand("musicboxplus", new MusicBoxExecutor());

        // Player lifecycle (always wanted) is registered here; the block-facing
        // interaction/redstone/chunk listeners are registered by ModuleRuntimeSync.
        Bukkit.getPluginManager().registerEvents(new PlayerLifecycleListener(), this);
        moduleRuntimeSync.syncAll();

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            MusicBoxExpansion expansion = new MusicBoxExpansion(this);
            expansion.register();
            this.papiExpansion = expansion;
            getLogger().info(logText("Registered PlaceholderAPI expansion", "已注册 PlaceholderAPI 扩展"));
        }

        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.globalLater(() -> startupAsync().exceptionally(throwable -> {
            getLogger().log(Level.SEVERE, logText("Failed to initialize plugin", "插件初始化失败"), throwable);
            loaded = false;
            return null;
        }), 1L);
    }

    private CompletableFuture<Void> startupAsync() {
        reloadLock.lock();
        try {
            if (reloading) {
                return CompletableFuture.failedFuture(new IllegalStateException("Already initializing"));
            }
            reloading = true;
        } finally {
            reloadLock.unlock();
        }

        long startTime = System.currentTimeMillis();
        loaded = false;
        CacheUtils.clearAllCaches();

        // Initialize the database off-thread: on Folia the global region scheduler counts as the
        // "main thread", so running it there trips the warnIfMainThread check and throws.
        CompletableFuture<Void> dbInit = CompletableFuture.runAsync(() -> {
            try {
                reloadDatabase();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, AsyncTaskManager.getInstance().getAsyncExecutor());

        return dbInit.thenCompose(ignored -> {
            CompletableFuture<Void> playerMusicReload = usesPlayerMusicLibrary()
                    ? PlayerMusicManager.getInstance().reloadAsync()
                    : CompletableFuture.completedFuture(null);
            CompletableFuture<Void> songReload = reloadSongs();
            CompletableFuture<Void> completion = new CompletableFuture<>();

            CompletableFuture.allOf(songReload, playerMusicReload).whenComplete((unused, throwable) -> {
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.global(() -> {
                    reloadLock.lock();
                    try {
                        if (throwable == null) {
                            reloadGUI();
                            initBStats();
                            initWebServer();
                            loaded = true;
                            if (isSignsModuleEnabled()) {
                                SignPlayer.restorePreventedPlayers();
                            }
                            DebugLogger.debugPerformance("plugin startup", startTime);
                            getLogger().info(logText(
                                    "Plugin startup finished in " + (System.currentTimeMillis() - startTime) + "ms",
                                    "插件启动完成，用时 " + (System.currentTimeMillis() - startTime) + "ms"
                            ));
                            // Deferred: the scan must not run inline, or an exception in it would
                            // skip completion.complete() and leave startup hung. It also lets
                            // everything else settle before touching loaded chunks.
                            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.globalLater(
                                    MusicBox.this::restoreJukeboxesInLoadedChunks, 1L);
                            completion.complete(null);
                        } else {
                            loaded = false;
                            getLogger().log(Level.SEVERE, logText("Plugin startup failed", "插件启动失败"), throwable);
                            completion.completeExceptionally(throwable);
                        }
                        reloading = false;
                        reloadCondition.signalAll();
                    } finally {
                        reloadLock.unlock();
                    }
                });
            });
            return completion;
        }).exceptionally(throwable -> {
            reloadLock.lock();
            try {
                reloading = false;
                reloadCondition.signalAll();
            } finally {
                reloadLock.unlock();
            }
            loaded = false;
            getLogger().log(Level.SEVERE, logText("Plugin startup failed", "插件启动失败"), throwable);
            return null;
        });
    }

    // getDescription() is deprecated with no Paper 1.21.4 replacement (PluginMeta arrived
    // later), so the suppression is local to this banner instead of the whole class.
    @SuppressWarnings("deprecation")
    private void logStartupBanner() {
        String version = getDescription().getVersion();
        String authors = String.join(", ", getDescription().getAuthors());
        getLogger().info(" ");
        getLogger().info("  __  __           _      ____            _____  _           ");
        getLogger().info(" |  \\/  |         (_)    |  _ \\          |  __ \\| |          ");
        getLogger().info(" | \\  / |_   _ ___ _  ___| |_) | _____  _| |__) | |_   _ ___ ");
        getLogger().info(" | |\\/| | | | / __| |/ __|  _ < / _ \\ \\/ /  ___/| | | | / __|");
        getLogger().info(" | |  | | |_| \\__ \\ | (__| |_) | (_) >  <| |    | | |_| \\__ \\");
        getLogger().info(" |_|  |_|\\__,_|___/_|\\___|____/ \\___/_/\\_\\_|    |_|\\__,_|___/");
        getLogger().info(" ");
        getLogger().info(logText("MusicBoxPlus v" + version + " is starting...", "MusicBoxPlus v" + version + " 正在启动..."));
        getLogger().info(logText("Authors: " + authors, "作者: " + authors));
    }

    // The data folder is named after the plugin, so the 3.2 -> 4.0 rename moved it from
    // plugins/MusicBox to plugins/MusicBoxPlus and left the song library, config and database
    // behind. Runs before anything touches the folder.
    private void migrateLegacyDataFolder() {
        File current = getDataFolder();
        if (current.exists()) {
            return;
        }
        File legacy = new File(current.getParentFile(), "MusicBox");
        if (!legacy.isDirectory() || !new File(legacy, "config.yml").isFile()) {
            return;
        }
        if (legacy.renameTo(current)) {
            getLogger().info(logText(
                    "Plugin was renamed: moved plugins/MusicBox to plugins/" + current.getName(),
                    "插件已改名：已将 plugins/MusicBox 迁移至 plugins/" + current.getName()));
        } else {
            getLogger().warning(logText(
                    "Found plugins/MusicBox from the old plugin name but could not move it to plugins/"
                            + current.getName() + "; rename it by hand or the old data stays unused",
                    "发现旧插件名目录 plugins/MusicBox，但无法迁移到 plugins/" + current.getName()
                            + "；请手动改名，否则旧数据不会被使用"));
        }
    }

    private void initializeSmartConfigs() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        loadMainConfig();
        loadSelectedLanguageConfig();
        loadSimpleConfig("gui-config.yml", "gui");
    }

    private void loadMainConfig() {
        try {
            SmartConfigManager mainConfigManager = getOrCreateConfigManager(Paths.CONFIG_FILE, Paths.CONFIG_FILE);
            byte[] configBytes = mainConfigManager.loadConfig();
            configObject = MusicBoxConfig.parseConfig(new ByteArrayInputStream(configBytes));
            getLogger().info(logText("Loaded config.yml", "已加载 config.yml"));
            DebugLogger.debug("config.yml: " + mainConfigManager.getConfigStats());
        } catch (IOException | MusicBoxConfig.ConfigParseException e) {
            // parseConfig throws ConfigParseException (a RuntimeException) on any malformed YAML,
            // wrong-typed value or duplicate key. It must be caught here as well, or a single
            // config typo escapes onEnable and disables the whole plugin instead of degrading to
            // the defaults below.
            getLogger().log(Level.SEVERE, logText("Failed to load config.yml, using defaults", "加载 config.yml 失败，将使用默认配置"), e);
            configObject = MusicBoxConfig.createDefault();
        }
    }

    private void loadSimpleConfig(String configName, String displayName) {
        try {
            SmartConfigManager configManager = getOrCreateConfigManager(configName, configName);
            configManager.loadConfig();
            getLogger().info(logText("Loaded " + displayName + " config", "已加载 " + displayName + " 配置"));
            DebugLogger.debug(displayName + " config: " + configManager.getConfigStats());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, logText("Failed to load " + displayName + " config", "加载 " + displayName + " 配置失败"), e);
        }
    }

    private void loadSelectedLanguageConfig() {
        String languageFile = getSelectedLanguageFileName();
        try {
            SmartConfigManager configManager = getOrCreateConfigManager(languageFile, languageFile);
            configManager.loadConfig();
            getLogger().info(logText("Loaded " + languageFile, "已加载 " + languageFile));
            DebugLogger.debug(languageFile + ": " + configManager.getConfigStats());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, logText("Failed to load " + languageFile, "加载 " + languageFile + " 失败"), e);
        }
    }

    private String getSelectedLanguageFileName() {
        if (configObject == null || configObject.getLanguage() == null) {
            return "language_en.yml";
        }

        String configured = configObject.getLanguage();
        if (configured == null || configured.trim().isEmpty()) {
            return "language_en.yml";
        }

        String trimmed = configured.trim().replace('\\', '/');
        if (trimmed.contains("/") || trimmed.contains("..")) {
            return "language_en.yml";
        }

        if ("en".equalsIgnoreCase(trimmed) || "default".equalsIgnoreCase(trimmed)) {
            return "language_en.yml";
        }

        if (!trimmed.endsWith(".yml")) {
            trimmed = "language_" + trimmed.toLowerCase() + ".yml";
        }

        return trimmed;
    }

    private SmartConfigManager getOrCreateConfigManager(String configName, String resourcePath) {
        return configManagers.computeIfAbsent(configName, key -> new SmartConfigManager(this, configName, resourcePath));
    }

    private void registerCommand(String command, TabExecutor executor) {
        PluginCommand cmd = getCommand(command);
        if (cmd == null) {
            throw new IllegalStateException("Missing command registration for " + command);
        }
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    public void reloadPlugin() {
        reloadPluginAsync();
    }

    public CompletableFuture<Void> reloadPluginAsync() {
        reloadLock.lock();
        try {
            if (reloading) {
                return CompletableFuture.failedFuture(new IllegalStateException("Already reloading"));
            }
            reloading = true;
        } finally {
            reloadLock.unlock();
        }
        
        long startTime = System.currentTimeMillis();
        loaded = false;
        ReloadPlaybackState.Snapshot playbackSnapshot = reloadPlaybackState.capture();

        closeAllGUIs();
        reloadPlaybackState.stopPlayers();
        CacheUtils.clearAllCaches();

        try {
            reloadMainConfig();
            reloadLanguageConfig();
            moduleRuntimeSync.syncAll();
            reloadDatabaseOffThread();
            if (usesPublishedMusicLibrary()) {
                PublishedMusicManager.getInstance().loadAllPublishedMusic();
            }
        } catch (Exception e) {
            reloadLock.lock();
            try {
                reloading = false;
                reloadCondition.signalAll();
            } finally {
                reloadLock.unlock();
            }
            loaded = false;
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Void> playerMusicReload = usesPlayerMusicLibrary()
                ? PlayerMusicManager.getInstance().reloadAsync()
                : CompletableFuture.completedFuture(null);
        LanguageConfig.getInstance().reload();
        ConfigManager.getInstance().reload();
        PlayerWrapper.clearAll();

        CompletableFuture<Void> songReload = reloadSongs();
        CompletableFuture<Void> completion = new CompletableFuture<>();

        CompletableFuture.allOf(songReload, playerMusicReload).whenComplete((unused, throwable) -> {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.global(() -> {
                reloadLock.lock();
                try {
                    if (throwable == null) {
                        reloadGUI();
                        initBStats();
                        initWebServer();
                        loaded = true;
                        if (isSignsModuleEnabled()) {
                            SignPlayer.restorePreventedPlayers();
                        }
                        // Playback restore re-creates block players (signs/jukeboxes) and
                        // per-player playback; it self-schedules each block's region and each
                        // player's own region internally, so it is safe to invoke here on the
                        // global region thread (it only schedules region/entity work).
                        reloadPlaybackState.restore(playbackSnapshot);
                        DebugLogger.debugPerformance("plugin reload", startTime);
                        getLogger().info(logText(
                                "Plugin reload finished in " + (System.currentTimeMillis() - startTime) + "ms",
                                "插件重载完成，用时 " + (System.currentTimeMillis() - startTime) + "ms"
                        ));
                        completion.complete(null);
                    } else {
                        loaded = false;
                        getLogger().log(Level.SEVERE, logText("Plugin reload failed", "插件重载失败"), throwable);
                        completion.completeExceptionally(throwable);
                    }
                    reloading = false;
                    reloadCondition.signalAll();
                } finally {
                    reloadLock.unlock();
                }
            });
        });
        return completion;
    }

    private void reloadMainConfig() {
        try {
            SmartConfigManager configManager = getOrCreateConfigManager(Paths.CONFIG_FILE, Paths.CONFIG_FILE);
            byte[] configBytes = configManager.loadConfig();
            configObject = MusicBoxConfig.parseConfig(new ByteArrayInputStream(configBytes));
            configManager.cleanupOldBackups();
            getLogger().info(logText("Reloaded config.yml", "已重载 config.yml"));
            DebugLogger.debug("config.yml: " + configManager.getConfigStats());
        } catch (IOException | MusicBoxConfig.ConfigParseException e) {
            // Same as loadMainConfig: a malformed config throws ConfigParseException (a
            // RuntimeException), which must be caught here too so a bad reload falls back to
            // defaults instead of leaving the running instance broken.
            getLogger().log(Level.SEVERE, logText("Failed to reload config.yml, using defaults", "重载 config.yml 失败，将使用默认配置"), e);
            configObject = MusicBoxConfig.createDefault();
        }
    }

    private void reloadLanguageConfig() {
        String languageFile = getSelectedLanguageFileName();
        try {
            SmartConfigManager langManager = getOrCreateConfigManager(languageFile, languageFile);
            langManager.loadConfig();
            langManager.cleanupOldBackups();
            getLogger().info(logText("Reloaded " + languageFile, "已重载 " + languageFile));
            DebugLogger.debug(languageFile + ": " + langManager.getConfigStats());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, logText("Failed to reload " + languageFile, "重载 " + languageFile + " 失败"), e);
        }
    }

    // Runs reloadDatabase on the async executor and waits for it. The caller is on the global
    // region scheduler, where the database layer refuses to work.
    private void reloadDatabaseOffThread() {
        try {
            CompletableFuture.runAsync(this::reloadDatabase,
                    AsyncTaskManager.getInstance().getAsyncExecutor()).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
        }
    }

    private void reloadDatabase() {
        DatabaseLoader.reload();
        getLogger().info(logText("Reloaded database", "已重载数据库"));
    }

    private CompletableFuture<Void> reloadSongs() {
        try {
            long songStartTime = System.currentTimeMillis();
            MusicBoxSongManager.initializeCache();
            return MusicBoxSongManager.reloadAsync(new File(getDataFolder(), Paths.SONGS_DIR)).thenRun(() -> {
                getLogger().info(logText(
                        "Reloaded " + MusicBoxSongManager.getAllSongs().size() + " songs in " + (System.currentTimeMillis() - songStartTime) + "ms",
                        "已重载 " + MusicBoxSongManager.getAllSongs().size() + " 首歌曲，用时 " + (System.currentTimeMillis() - songStartTime) + "ms"
                ));
                try {
                    SongAliasConfig.getInstance().loadConfig();
                    SongAliasConfig.getInstance().applyToAllSongs();
                    MusicBoxSongManager.refreshSearchIndex();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, logText("Failed to apply song aliases", "应用歌曲别名失败"), e);
                }
                // After the songs, because a stored display resolves its playlist by song, and
                // on the global thread, because each display hops to its own region from there.
                if (isTextPlayerModuleEnabled()) {
                    com.huidu.musicboxplus.common.utils.scheduler.Scheduler.global(() -> {
                        try {
                            TextDisplayStore.restoreAll();
                            TextDisplayStore.startAutoSave(TEXT_DISPLAY_SAVE_INTERVAL_SECONDS);
                        } catch (Exception e) {
                            getLogger().log(Level.WARNING,
                                logText("Failed to restore text displays", "恢复文本显示失败"), e);
                        }
                    });
                }
                // Warm the song-stack building path off the main thread. The first song list the
                // server shows after startup otherwise rebuilds every item on the main thread
                // (MiniMessage parse + CraftEngine/ItemModel reflection + jukebox registry
                // lookup + NBT write) while the caches and JIT are still cold, which drops TPS.
                prewarmSongStacks();
            });
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, logText("Failed to reload songs", "重载歌曲失败"), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // Builds one ItemStack per song, discarding the result. The cost is real but it is paid
    // here on the async executor instead of on the main thread when the first player opens the
    // song list, and it fills the MiniMessage component cache plus the reflective lookups.
    private void prewarmSongStacks() {
        List<MusicBoxSong> songs = MusicBoxSongManager.getAllSongs();
        if (songs == null || songs.isEmpty()) {
            return;
        }
        long startTime = System.currentTimeMillis();
        int built = 0;
        for (MusicBoxSong song : songs) {
            try {
                song.getSongStack();
                built++;
            } catch (Throwable ignored) {
                // A single misconfigured song must not abort the warmup of the rest.
            }
        }
        getLogger().info(logText(
            "Pre-warmed " + built + "/" + songs.size() + " song stacks in " + (System.currentTimeMillis() - startTime) + "ms",
            "已预热 " + built + "/" + songs.size() + " 个歌曲物品，用时 " + (System.currentTimeMillis() - startTime) + "ms"));
    }

    // Resumes playback for jukeboxes that were left with a disc inside when the server shut
    // down. Runs on the global thread (startup completion), so chunk state is read through
    // region hops. Batches a few chunks per tick so scanning a large loaded area does not
    // stall any single region. Unloaded chunks are skipped by the ChunkLoadEvent handler
    // instead.
    private void restoreJukeboxesInLoadedChunks() {
        if (!isJukeboxModuleEnabled() || !MusicBoxSongManager.isLoaded()) {
            return;
        }
        List<Location> chunkOrigins = new ArrayList<>();
        try {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    chunkOrigins.add(new Location(world, (chunk.getX() << 4) + 8, 0, (chunk.getZ() << 4) + 8));
                }
            }
        } catch (Exception e) {
            // Folia may restrict chunk enumeration from the global region thread; chunks that
            // load later are covered by the ChunkLoadEvent handler.
            getLogger().log(Level.WARNING, logText("Failed to enumerate loaded chunks for jukebox restore", "枚举已加载区块以恢复唱片机失败"), e);
            return;
        }
        if (chunkOrigins.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, Math.min(8, chunkOrigins.size()));
        final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        com.huidu.musicboxplus.common.utils.scheduler.MbTask[] holder = new com.huidu.musicboxplus.common.utils.scheduler.MbTask[1];
        holder[0] = com.huidu.musicboxplus.common.utils.scheduler.Scheduler.globalTimer(() -> {
            int from = index.getAndAdd(batchSize);
            if (from >= chunkOrigins.size()) {
                if (holder[0] != null) {
                    holder[0].cancel();
                }
                return;
            }
            for (int i = from; i < from + batchSize && i < chunkOrigins.size(); i++) {
                Location origin = chunkOrigins.get(i);
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(origin, () -> {
                    if (!origin.isChunkLoaded()) {
                        return;
                    }
                    JukeboxPlayer.restoreJukeboxesInChunk(origin.getWorld().getChunkAt(origin));
                });
            }
        }, 1L, 1L);
    }

    private void reloadGUI() {
        try {
            GUIActions.reloadGUI();
            getLogger().info(logText("Reloaded GUI", "已重载 GUI"));
            DebugLogger.debugGUI("GUI reloaded");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, logText("Failed to reload GUI", "重载 GUI 失败"), e);
        }
    }

    private void initBStats() {
        if (configObject.isBStats() && bStats == null) {
            // Service registered under the MusicboxPlus name on bStats.org.
            bStats = new Metrics(this, 33338);
            bStats.addCustomChart(new SingleLineChart("song_count", () -> MusicBoxSongManager.getAllSongs() != null ? MusicBoxSongManager.getAllSongs().size() : 0));
        }
    }

    private void initWebServer() {
        if (webEditorServer != null) {
            webEditorServer.shutdown();
            webEditorServer = null;
        }

        MusicBoxConfig.WebConfig webConfig = getConfigObject() != null ? getConfigObject().getWeb() : null;
        if (!isWebEditorModuleEnabled() || webConfig == null || !webConfig.isEnabled()) {
            return;
        }

        webEditorServer = new WebEditorServer(this);
        if (webEditorServer.startup()) {
            getLogger().info(logText(
                    "Web editor started at " + webEditorServer.getConfig().getHost() + ":" + webEditorServer.getConfig().getPort(),
                    "Web 编辑器已启动: " + webEditorServer.getConfig().getHost() + ":" + webEditorServer.getConfig().getPort()
            ));
        } else {
            getLogger().warning(logText("Failed to start web editor", "启动 Web 编辑器失败"));
        }
    }

    public WebEditorServer getWebEditorServer() {
        return webEditorServer;
    }

    public boolean isWebEditorEnabled() {
        return webEditorServer != null && webEditorServer.isEnabled();
    }

    private final ModuleFlags moduleFlags = new ModuleFlags(() -> this.configObject);

    public boolean isPlaybackModuleEnabled() {
        return moduleFlags.isPlaybackModuleEnabled();
    }

    public boolean isShopModuleEnabled() {
        return moduleFlags.isShopModuleEnabled();
    }

    public boolean isPlayerMusicModuleEnabled() {
        return moduleFlags.isPlayerMusicModuleEnabled();
    }

    public boolean isPublishModuleEnabled() {
        return moduleFlags.isPublishModuleEnabled();
    }

    public boolean isPlayerMusicShopModuleEnabled() {
        return moduleFlags.isPlayerMusicShopModuleEnabled();
    }

    public boolean isEditorModuleEnabled() {
        return moduleFlags.isEditorModuleEnabled();
    }

    public boolean isWebEditorModuleEnabled() {
        return moduleFlags.isWebEditorModuleEnabled();
    }

    public boolean isSignsModuleEnabled() {
        return moduleFlags.isSignsModuleEnabled();
    }

    public boolean isJukeboxModuleEnabled() {
        return moduleFlags.isJukeboxModuleEnabled();
    }

    public boolean isPlaylistsModuleEnabled() {
        return moduleFlags.isPlaylistsModuleEnabled();
    }

    public boolean isGiveModuleEnabled() {
        return moduleFlags.isGiveModuleEnabled();
    }

    public boolean isTextPlayerModuleEnabled() {
        return moduleFlags.isTextPlayerModuleEnabled();
    }

    public boolean isSongTagsModuleEnabled() {
        return moduleFlags.isSongTagsModuleEnabled();
    }

    public boolean usesPlayerMusicLibrary() {
        return moduleFlags.usesPlayerMusicLibrary();
    }

    public boolean usesPublishedMusicLibrary() {
        return moduleFlags.usesPublishedMusicLibrary();
    }

    public boolean usesAnyPlaybackRuntime() {
        return moduleFlags.usesAnyPlaybackRuntime();
    }

    public void reloadPartial(String type) {
        reloadPartialAsync(type);
    }

    public CompletableFuture<Void> reloadPartialAsync(String type) {
        long startTime = System.currentTimeMillis();
        getLogger().info(logText("Starting partial reload: " + type, "开始局部重载: " + type));

        switch (type.toLowerCase()) {
            case "all":
                return reloadPluginAsync();
            case "config":
                reloadMainConfig();
                reloadLanguageConfig();
                moduleRuntimeSync.syncAll();
                if (usesPublishedMusicLibrary()) {
                    PublishedMusicManager.getInstance().loadAllPublishedMusic();
                }
                LanguageConfig.getInstance().reload();
                ConfigManager.getInstance().reload();
                initBStats();
                initWebServer();
                break;
            case "lang":
                reloadLanguageConfig();
                LanguageConfig.getInstance().reload();
                break;
            case Paths.SONGS_DIR:
                return reloadSongs().thenRun(() -> logPartialReloadComplete(type, startTime));
            case "gui":
                reloadGUI();
                ConfigManager.getInstance().reload();
                break;
            case "database":
                try {
                    reloadDatabaseOffThread();
                    if (usesPublishedMusicLibrary()) {
                        PublishedMusicManager.getInstance().loadAllPublishedMusic();
                    }
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, logText("Failed to reload database", "重载数据库失败"), e);
                    return CompletableFuture.failedFuture(e);
                }
                if (usesPlayerMusicLibrary()) {
                    return PlayerMusicManager.getInstance().reloadAsync().thenRun(() -> logPartialReloadComplete(type, startTime));
                }
                break;
            case "aliases":
                reloadAliasesOnly();
                break;
            default:
                getLogger().warning(logText("Unknown reload type: " + type, "未知的重载类型: " + type));
                return CompletableFuture.completedFuture(null);
        }

        logPartialReloadComplete(type, startTime);
        return CompletableFuture.completedFuture(null);
    }

    private void logPartialReloadComplete(String type, long startTime) {
        getLogger().info(logText(type + " reload finished in " + (System.currentTimeMillis() - startTime) + "ms", type + " 重载完成，用时 " + (System.currentTimeMillis() - startTime) + "ms"));
    }

    private void reloadAliasesOnly() {
        try {
            SongAliasConfig.getInstance().loadConfig();
            SongAliasConfig.getInstance().applyToAllSongs();
            MusicBoxSongManager.refreshSearchIndex();
            getLogger().info(logText("Reloaded song aliases", "已重载歌曲别名"));
        } catch (Exception e) {
            getLogger().log(Level.WARNING, logText("Failed to reload song aliases", "重载歌曲别名失败"), e);
        }
    }

    public void destroyAllPlayers() {
        try {
            PlayerWrapper.clearAll(false);
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING, logText("Failed to clear player playback state during shutdown", "关闭时清理玩家播放状态失败"), throwable);
        }
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        getLogger().info(logText("Disabling MusicBox", "正在禁用 MusicBox"));
        shutdownSteps.runAll();
        // Outside the shutdown chain because it touches another plugin's registry, not our own
        // resources; unregistering prevents a dangling expansion from resolving against a disabled
        // plugin on unload/hot reload.
        if (papiExpansion != null) {
            try {
                papiExpansion.unregister();
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, logText("Failed to unregister PlaceholderAPI expansion", "注销 PlaceholderAPI 扩展失败"), t);
            }
            papiExpansion = null;
        }
        getLogger().info(logText("MusicBox disabled", "MusicBox 已禁用"));
    }

    // Force-links every class onDisable touches by listing it in an array literal: the reference
    // alone triggers linking, which loads and verifies the bytecode while the plugin classloader is
    // still fully open. Class<?>[] rather than Class.forName so the compiler validates each
    // reference at build time -- if a class is renamed or removed this array stops compiling
    // instead of silently leaving a shutdown gap.
    private void eagerLoadShutdownClasses() {
        @SuppressWarnings("unused")
        Class<?>[] forceLoad = new Class<?>[] {
            // Classes whose static fields / methods are dereferenced during onDisable.
            com.huidu.musicboxplus.core.player.VolumeManager.class,
            com.huidu.musicboxplus.core.player.PlayerManager.class,
            com.huidu.musicboxplus.core.player.AbstractBlockPlayer.class,
            com.huidu.musicboxplus.module.sign.SignPlayer.class,
            // Interface referenced by PlayerWrapper.activePlayer; never linked until
            // a PlayerSongPlayer instance is created, which doesn't happen if no player
            // logs in before shutdown.
            com.huidu.musicboxplus.api.player.PlayerSongPlayer.class,
            com.huidu.musicboxplus.core.playback.PlayerWrapper.class,
            com.huidu.musicboxplus.module.edit.MusicEditListener.class,
            com.huidu.musicboxplus.module.edit.PlayerMusicManager.class,
            com.huidu.musicboxplus.module.edit.publish.PublishedMusicManager.class,
            com.huidu.musicboxplus.module.gui.minecraft.GUI.class,
            com.huidu.musicboxplus.core.db.DatabaseLoader.class,
            com.huidu.musicboxplus.core.song.MusicBoxSongContainer.class,
            com.huidu.musicboxplus.common.utils.AsyncTaskManager.class,
            com.huidu.musicboxplus.common.utils.cache.CacheUtils.class,
        };
    }

    private void closeAllGUIs() {
        GUIActions.closeAllOpen();
    }

    public ExecutorService getAsyncExecutor() {
        return AsyncTaskManager.getInstance().getAsyncExecutor();
    }

    public CompletableFuture<Void> reloadConfigAsync() {
        return reloadPluginAsync().exceptionally(throwable -> {
            getLogger().log(Level.SEVERE, "Async reload failed", throwable);
            return null;
        });
    }

    public String getConfigManagerStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("Config managers:\n");
        for (Map.Entry<String, SmartConfigManager> entry : configManagers.entrySet()) {
            stats.append("  ").append(entry.getKey()).append(": ").append(entry.getValue().getConfigStats()).append('\n');
        }
        return stats.toString();
    }

    public MusicBoxConfig getConfigObject() {
        return configObject;
    }

    public Metrics getBStats() {
        return bStats;
    }

    public ConcurrentHashMap<String, SmartConfigManager> getConfigManagers() {
        return configManagers;
    }

    // Inverted on purpose: true while the plugin is still starting up (or reloading) and the
    // song index / config are not yet usable. Handlers gate on this to skip work during startup.
    public boolean isStartingUp() {
        return !loaded;
    }

    public boolean waitForLoaded(long timeoutMs) {
        if (loaded) {
            return true;
        }

        reloadLock.lock();
        try {
            if (loaded) {
                return true;
            }

            long remaining = timeoutMs;
            final long startTime = System.currentTimeMillis();
            while (!loaded && remaining > 0) {
                if (!reloading) {
                    return loaded;
                }
                try {
                    if (!reloadCondition.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!reloading) {
                    return loaded;
                }
                remaining = timeoutMs - (System.currentTimeMillis() - startTime);
            }
            return loaded;
        } finally {
            reloadLock.unlock();
        }
    }
}
