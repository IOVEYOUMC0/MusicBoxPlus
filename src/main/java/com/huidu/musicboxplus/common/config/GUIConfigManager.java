package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import com.huidu.musicboxplus.common.utils.StringUtils;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// GUI configuration manager: loads button configs, layouts and themes from gui-config.yml.
public class GUIConfigManager {
    private static volatile GUIConfigManager instance;
    private final JavaPlugin plugin;
    private volatile YamlConfiguration config;
    private File configFile;
    private final GUIButtonRegistry buttonRegistry = new GUIButtonRegistry();
    private final Map<String, GUIConfig> guiConfigs = new ConcurrentHashMap<String, GUIConfig>();
    private final Map<String, ProgressBarConfig> progressBarConfigs = new ConcurrentHashMap<>();
    private volatile SongItemConfig songItemConfig;
    private volatile Set<String> guiTitleKeywords;

    // The small section objects, parsed once per loadConfig and then shared.
    //
    // Every one of these getters used to build a fresh object on each call -- several YAML path
    // lookups plus StringUtils.t() (a MiniMessage parse and legacy serialisation) per list -- while
    // the callers sit inside per-slot render loops: a 45-slot shop page re-parsed the same constant
    // strings 45 times per open, and the control panel does it again on every 2 Hz refresh. The
    // values do not change until the next loadConfig, which is what invalidates this cache.
    //
    // Safe to share because every caller only reads the fields (verified across
    // module/gui/**); the one object whose identity is used as a cache key, SongItemConfig, is
    // already shared through its own field and is not part of this record.
    private record SectionConfigs(PlaylistItemConfig playlistItem, LoopModeConfig loopMode,
                                  ToggleStatusConfig toggleStatus,
                                  PlaybackStatusConfig playbackStatus,
                                  VolumeControlConfig volumeControl, ShopLoreConfig shopLore) {
    }

    private volatile SectionConfigs sectionConfigs;

    // Lazily rebuilt rather than eagerly in loadConfig(): getInstance() constructs the manager
    // before the config file is parsed in some paths, and a getter must never hand out a half-built
    // object. The first caller after a (re)load pays for all six parsers once.
    private SectionConfigs sectionConfigs() {
        SectionConfigs cached = sectionConfigs;
        if (cached != null) {
            return cached;
        }
        SectionConfigs built = new SectionConfigs(parsePlaylistItemConfig(), parseLoopModeConfig(),
                parseToggleStatusConfig(), parsePlaybackStatusConfig(), parseVolumeControlConfig(),
                parseShopLoreConfig());
        sectionConfigs = built;
        return built;
    }

    private static final Set<String> DEFAULT_GUI_TITLE_KEYWORDS = new HashSet<>(Arrays.asList(
        "MusicBox", "Playlist", "Control", "Volume", "Search",
        "Music", "Player", "Menu", "Shop", "Song", "Sign", "Settings", "Editor"
    ));

    private static final int INVENTORY_COLS = 9;

    // One definition of "the rows of a layout", shared by the row count and every slot-index walk
    // below. They used to disagree: a YAML block scalar keeps its trailing newline, and counting
    // that empty field made the menu a row taller than the layout it was built from.
    // Trailing line breaks only -- a deliberate blank FIRST row is a real row and shifts every
    // slot index after it, so strip() would have traded one mismatch for another.
    static String[] layoutRows(String layout) {
        return layout == null ? new String[0] : layout.replaceAll("\\R+$", "").split("\\R", -1);
    }

    // Slot index of the first occurrence of c in the layout, -1 if there is none.
    public static int getSlotForChar(String layout, char c) {
        if (layout == null) {
            return -1;
        }
        String[] lines = layoutRows(layout);
        for (int row = 0; row < Math.min(lines.length, 6); row++) {
            String line = lines[row];
            for (int col = 0; col < line.length() && col < INVENTORY_COLS; col++) {
                if (line.charAt(col) == c) {
                    return row * INVENTORY_COLS + col;
                }
            }
        }
        return -1;
    }

    // All slots matching c in the layout, in row-major order.
    public static List<Integer> getSlotsForChar(String layout, char c) {
        List<Integer> slots = new ArrayList<>();
        if (layout == null) {
            return slots;
        }
        String[] lines = layoutRows(layout);
        for (int row = 0; row < Math.min(lines.length, 6); row++) {
            String line = lines[row];
            for (int col = 0; col < line.length() && col < INVENTORY_COLS; col++) {
                if (line.charAt(col) == c) {
                    slots.add(row * INVENTORY_COLS + col);
                }
            }
        }
        return slots;
    }

    public static int getRowsForLayout(String layout, int fallback) {
        if (layout == null || layout.isBlank()) {
            return Math.max(1, Math.min(6, fallback));
        }
        int rows = layoutRows(layout).length;
        return Math.max(1, Math.min(6, rows));
    }

    public static GUIConfigManager getInstance() {
        return instance;
    }

    public GUIConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.loadConfig();
        // Published only after the whole configuration exists. It used to be set before loadConfig(),
        // so getInstance() could hand out a manager whose config and maps were still empty.
        instance = this;
    }

    public void loadConfig() {
        this.configFile = new File(this.plugin.getDataFolder(), "gui-config.yml");
        if (!this.configFile.exists() && StorageAccess.canWriteTo(this.configFile)) {
            this.plugin.saveResource("gui-config.yml", false);
        } else if (!this.configFile.exists()) {
            this.plugin.getLogger().warning(LogLocale.text((MusicBox)this.plugin,
                "GUI config folder is not writable, loading bundled gui-config.yml in memory",
                "GUI 配置目录不可写，将以内存方式加载内置 gui-config.yml"));
        }
        try {
            if (this.configFile.exists()) {
                try (InputStream fileStream = new java.io.FileInputStream(this.configFile);
                     Reader reader = new InputStreamReader(fileStream, StandardCharsets.UTF_8)) {
                    this.config = YamlConfiguration.loadConfiguration(reader);
                }
            } else {
                this.config = new YamlConfiguration();
                try (InputStream bundledStream = this.plugin.getResource("gui-config.yml")) {
                    if (bundledStream != null) {
                        this.config = YamlConfiguration.loadConfiguration(new InputStreamReader(bundledStream, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning(LogLocale.text((MusicBox)this.plugin,
                "Failed to load GUI config, using empty fallback: " + e.getMessage(),
                "加载 GUI 配置失败，将使用空白回退配置: " + e.getMessage()));
            this.config = new YamlConfiguration();
        }
        try (InputStream defaultStream = this.plugin.getResource("gui-config.yml")) {
            if (defaultStream != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
                this.config.setDefaults(defaultConfig);
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning(LogLocale.text((MusicBox)this.plugin,
                "Failed to load bundled GUI defaults: " + e.getMessage(),
                "加载内置 GUI 默认配置失败: " + e.getMessage()));
        }
        this.progressBarConfigs.clear();
        // The section objects are rebuilt from the config that was just parsed.
        this.sectionConfigs = null;
        this.applyLanguageOverlay();
        this.loadGUITitleKeywords();
        this.loadGUIConfigs();
        this.buttonRegistry.load(this.config);
        this.loadSongItemConfig();
    }

    /**
     * Overlays the translation matching the configured language, if one exists.
     *
     * <p>Precedence: a file in the plugin data folder wins, so an owner can correct or extend a
     * translation without waiting for a release; otherwise the bundled overlay is used, but only for
     * a non-Chinese language, because the shipped {@code gui-config.yml} is already Chinese and an
     * overlay would otherwise overwrite an owner's own Chinese edits.
     */
    private void applyLanguageOverlay() {
        String language = null;
        if (this.plugin instanceof MusicBox musicBox && musicBox.getConfigObject() != null) {
            language = musicBox.getConfigObject().getLanguage();
        }
        String fileName = GuiConfigOverlay.overlayFileName(language);
        YamlConfiguration overlay = null;

        File dataFile = new File(this.plugin.getDataFolder(), fileName);
        try {
            if (dataFile.isFile()) {
                overlay = YamlConfiguration.loadConfiguration(dataFile);
            } else if (!"zh_cn".equals(GuiConfigOverlay.languageSuffix(language))) {
                try (InputStream bundled = this.plugin.getResource(fileName)) {
                    if (bundled != null) {
                        overlay = YamlConfiguration.loadConfiguration(
                                new InputStreamReader(bundled, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            this.plugin.getLogger().warning(LogLocale.text((MusicBox) this.plugin,
                "Failed to load GUI language overlay " + fileName + ": " + e.getMessage(),
                "加载 GUI 语言覆盖文件失败 " + fileName + ": " + e.getMessage()));
            return;
        }
        if (overlay == null) {
            return;
        }
        GuiConfigOverlay.merge(this.config, overlay);
        this.plugin.getLogger().fine("Applied GUI language overlay: " + fileName);
    }

    private void loadGUITitleKeywords() {
        List<String> keywords = this.config.getStringList("gui-title-keywords");
        if (keywords == null || keywords.isEmpty()) {
            this.guiTitleKeywords = new HashSet<>(DEFAULT_GUI_TITLE_KEYWORDS);
        } else {
            this.guiTitleKeywords = new HashSet<>(keywords);
        }
    }

    public Set<String> getGUITitleKeywords() {
        if (this.guiTitleKeywords == null || this.guiTitleKeywords.isEmpty()) {
            return DEFAULT_GUI_TITLE_KEYWORDS;
        }
        return this.guiTitleKeywords;
    }

    private void loadGUIConfigs() {
        this.guiConfigs.clear();
        ConfigurationSection guiSection = this.config.getConfigurationSection("gui");
        if (guiSection == null) {
            return;
        }
        for (String guiName : guiSection.getKeys(false)) {
            ConfigurationSection buttonMappingSection;
            ConfigurationSection section = guiSection.getConfigurationSection(guiName);
            if (section == null) continue;
            GUIConfig guiConfig = new GUIConfig();
            guiConfig.rows = section.getInt("rows", 6);
            guiConfig.title = section.getString("title", "<gold>GUI");
            guiConfig.layout = section.getString("layout", "");
            ConfigurationSection songArea = section.getConfigurationSection("song-area");
            if (songArea != null) {
                guiConfig.songAreaStartRow = songArea.getInt("start-row", 0);
                guiConfig.songAreaEndRow = songArea.getInt("end-row", 5);
                guiConfig.songAreaStartCol = songArea.getInt("start-col", 0);
                guiConfig.songAreaEndCol = songArea.getInt("end-col", 9);
            }
            if ((buttonMappingSection = section.getConfigurationSection("button-mapping")) != null) {
                ButtonMappingConfig mapping = new ButtonMappingConfig();
                mapping.songs = this.getChar(buttonMappingSection, "songs", 'S');
                mapping.previous = this.getChar(buttonMappingSection, "previous", 'P');
                mapping.next = this.getChar(buttonMappingSection, "next", 'N');
                mapping.empty = this.getChar(buttonMappingSection, "empty", 'X');
                mapping.back = this.getChar(buttonMappingSection, "back", 'B');
                mapping.parent = this.getChar(buttonMappingSection, "parent", 'F');
                mapping.search = this.getChar(buttonMappingSection, "search", 'H');
                mapping.controlPanel = this.getChar(buttonMappingSection, "control-panel", 'C');
                mapping.playlist = this.getChar(buttonMappingSection, "playlist", 'L');
                mapping.preventDestroy = this.getChar(buttonMappingSection, "prevent-destroy", 'D');
                mapping.infoSign = this.getChar(buttonMappingSection, "info-sign", 'I');
                mapping.random = this.getChar(buttonMappingSection, "random", 'R');
                mapping.stop = this.getChar(buttonMappingSection, "stop", 'T');
                mapping.volume = this.getChar(buttonMappingSection, "volume", 'V');
                mapping.speed = this.getChar(buttonMappingSection, "speed", 'Y');
                mapping.recentSongs = this.getChar(buttonMappingSection, "recent-songs", 'R');
                mapping.playPause = this.getChar(buttonMappingSection, "play-pause", 'A');
                mapping.playMode = this.getChar(buttonMappingSection, "play-mode", 'M');
                mapping.progress = this.getChar(buttonMappingSection, "progress", 'P');
                mapping.songList = this.getChar(buttonMappingSection, "song-list", 'S');
                mapping.loop = this.getChar(buttonMappingSection, "loop", 'L');
                mapping.protect = this.getChar(buttonMappingSection, "protect", 'K');
                mapping.add = this.getChar(buttonMappingSection, "add", 'A');
                mapping.delete = this.getChar(buttonMappingSection, "delete", 'D');
                mapping.shuffle = this.getChar(buttonMappingSection, "shuffle", 'H');
                mapping.save = this.getChar(buttonMappingSection, "save", 'V');
                mapping.returnBtn = this.getChar(buttonMappingSection, "return", 'R');
                mapping.create = this.getChar(buttonMappingSection, "create", 'C');
                mapping.master = this.getChar(buttonMappingSection, "master", 'M');
                mapping.close = this.getChar(buttonMappingSection, "close", 'L');
                mapping.results = this.getChar(buttonMappingSection, "results", 'R');
                guiConfig.buttonMapping = mapping;
            }
            this.guiConfigs.put(guiName, guiConfig);
        }
    }

    private char getChar(ConfigurationSection section, String path, char defaultValue) {
        String value = section.getString(path, String.valueOf(defaultValue));
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value.charAt(0);
    }

    private void loadSongItemConfig() {
        ConfigurationSection section = this.config.getConfigurationSection("song-item");
        if (section == null) {
            this.songItemConfig = new SongItemConfig();
            return;
        }
        this.songItemConfig = new SongItemConfig();
        ConfigurationSection discSection = section.getConfigurationSection("disc");
        if (discSection != null) {
            this.songItemConfig.customEnabled = discSection.getBoolean("custom-enabled", false);
            this.songItemConfig.customMaterial = Material.matchMaterial(discSection.getString("custom-material", "MUSIC_DISC_CAT"));
            if (this.songItemConfig.customMaterial == null) {
                this.songItemConfig.customMaterial = Material.MUSIC_DISC_CAT;
            }
            this.songItemConfig.customModelData = discSection.getInt("custom-model-data", 0);
            this.songItemConfig.itemModel = discSection.getString("item-model", "");
            this.songItemConfig.useRandomDisc = discSection.getBoolean("use-random-disc", true);
            List<String> discList = discSection.getStringList("available-discs");
            for (String disc : discList) {
                Material mat = Material.matchMaterial(disc);
                if (mat == null) continue;
                this.songItemConfig.availableDiscs.add(mat);
            }
        }
        this.songItemConfig.nameFormat = StringUtils.t(section.getString("name-format", "<yellow>{song}"));
        this.songItemConfig.loreFormat = StringUtils.t(section.getStringList("lore-format"));
        this.songItemConfig.aliasesFormat = StringUtils.t(section.getString("aliases-format", "<gray>Aliases: <yellow>{aliases}"));
        this.songItemConfig.tagsFormat = StringUtils.t(section.getString("tags-format", "<gray>Tags: <aqua>{tags}"));
    }

    public ButtonConfig getButtonConfig(String guiName, String buttonName) {
        return this.buttonRegistry.getButtonConfig(guiName, buttonName);
    }

    public GUIConfig getGUIConfig(String guiName) {
        return this.guiConfigs.getOrDefault(guiName, new GUIConfig());
    }

    public boolean hasGUIConfig(String guiName) {
        return this.guiConfigs.containsKey(guiName);
    }

    public ItemStack createButtonItem(String guiName, String buttonName) {
        return this.buttonRegistry.createButtonItem(guiName, buttonName);
    }

    public ItemStack createButtonItem(String guiName, String buttonName, String ... replacements) {
        return this.buttonRegistry.createButtonItem(guiName, buttonName, replacements);
    }

    public int getButtonSlot(String guiName, String buttonName) {
        return this.buttonRegistry.getButtonSlot(guiName, buttonName);
    }

    public int getSongAreaStartRow(String guiName) {
        GUIConfig guiConfig = this.guiConfigs.get(guiName);
        return guiConfig != null ? guiConfig.songAreaStartRow : 0;
    }

    public int getSongAreaEndRow(String guiName) {
        GUIConfig guiConfig = this.guiConfigs.get(guiName);
        return guiConfig != null ? guiConfig.songAreaEndRow : 5;
    }

    public String getGUILayout(String guiName) {
        GUIConfig guiConfig = this.guiConfigs.get(guiName);
        return guiConfig != null ? guiConfig.layout : "";
    }

    public int getLayoutCharCount(String guiName, char c) {
        String layout = this.getGUILayout(guiName);
        if (layout == null || layout.isEmpty()) {
            return 0;
        }
        int count = 0;
        int rows = getGUIRows(guiName);
        if (rows < 1 || rows > 6) {
            rows = 3;
        }
        String[] lines = layoutRows(layout);
        for (int row = 0; row < Math.min(rows, lines.length); row++) {
            String line = lines[row];
            for (int col = 0; col < Math.min(INVENTORY_COLS, line.length()); col++) {
                if (line.charAt(col) == c) {
                    ++count;
                }
            }
        }
        return count;
    }

    public int getGUIRows(String guiName) {
        GUIConfig guiConfig = this.guiConfigs.get(guiName);
        return guiConfig != null ? guiConfig.rows : 6;
    }

    public String getGUITitle(String guiName) {
        GUIConfig guiConfig = this.guiConfigs.get(guiName);
        return guiConfig != null ? guiConfig.title : "<gold>GUI";
    }

    public boolean isButtonEnabled(String guiName, String buttonName) {
        ButtonConfig buttonConfig = this.getButtonConfig(guiName, buttonName);
        return buttonConfig.enabled;
    }

    public void reload() {
        // Build a fresh manager and publish it with a single volatile write, instead of clearing and
        // refilling every map in place. A GUI render on a Folia region thread used to be able to
        // observe an empty button table, a default layout, or the new layout paired with the previous
        // section configs; with the swap a reader always sees one complete configuration (old or new).
        new GUIConfigManager(this.plugin);
    }

    public ProgressBarConfig getProgressBarConfig(String guiName) {
        if (this.config == null) {
            return new ProgressBarConfig();
        }
        return this.progressBarConfigs.computeIfAbsent(guiName, this::parseProgressBarConfig);
    }

    private ProgressBarConfig parseProgressBarConfig(String guiName) {
        String basePath = "gui." + guiName + ".progress-bar.";
        ProgressBarConfig progressBarConfig = new ProgressBarConfig();
        progressBarConfig.enabled = this.config.getBoolean(basePath + "enabled", true);
        progressBarConfig.row = this.config.getInt(basePath + "row", 0);
        progressBarConfig.startCol = this.config.getInt(basePath + "start-col", 0);
        progressBarConfig.endCol = this.config.getInt(basePath + "end-col", 9);
        String unplayed = this.config.getString(basePath + "material-unplayed", "GRAY_STAINED_GLASS_PANE");
        String playing = this.config.getString(basePath + "material-playing", "WHITE_STAINED_GLASS_PANE");
        String halfway = this.config.getString(basePath + "material-halfway", "LIME_STAINED_GLASS_PANE");
        String played = this.config.getString(basePath + "material-played", "GREEN_STAINED_GLASS_PANE");
        try {
            progressBarConfig.materialUnplayed = Material.valueOf(unplayed);
        }
        catch (IllegalArgumentException ignored) {
        }
        try {
            progressBarConfig.materialPlaying = Material.valueOf(playing);
        }
        catch (IllegalArgumentException ignored) {
        }
        try {
            progressBarConfig.materialHalfway = Material.valueOf(halfway);
        }
        catch (IllegalArgumentException ignored) {
        }
        try {
            progressBarConfig.materialPlayed = Material.valueOf(played);
        }
        catch (IllegalArgumentException ignored) {
        }
        progressBarConfig.materialUnplayedModelData = this.config.getInt(basePath + "material-unplayed-model-data", 0);
        progressBarConfig.materialPlayingModelData = this.config.getInt(basePath + "material-playing-model-data", 0);
        progressBarConfig.materialHalfwayModelData = this.config.getInt(basePath + "material-halfway-model-data", 0);
        progressBarConfig.materialPlayedModelData = this.config.getInt(basePath + "material-played-model-data", 0);
        progressBarConfig.format = this.config.getString(basePath + "format", "<yellow>{percent}% <gray>- {time}");
        progressBarConfig.lore = this.config.getStringList(basePath + "lore");
        return progressBarConfig;
    }

    public SongItemConfig getSongItemConfig() {
        if (this.songItemConfig != null) {
            return this.songItemConfig;
        }
        SongItemConfig config = new SongItemConfig();
        if (this.config == null) {
            return config;
        }
        ConfigurationSection discSection = this.config.getConfigurationSection("song-item.disc");
        if (discSection != null) {
            config.customEnabled = discSection.getBoolean("custom-enabled", false);
            String customMaterialStr = discSection.getString("custom-material", "MUSIC_DISC_CAT");
            Material customMat = Material.matchMaterial(customMaterialStr);
            if (customMat != null) {
                config.customMaterial = customMat;
            }
            config.customModelData = discSection.getInt("custom-model-data", 0);
            config.itemModel = discSection.getString("item-model", "");
            config.useRandomDisc = discSection.getBoolean("use-random-disc", true);
            List<String> discStrList = discSection.getStringList("available-discs");
            if (discStrList != null && !discStrList.isEmpty()) {
                config.availableDiscs = new ArrayList<Material>();
                for (String discStr : discStrList) {
                    Material discMat = Material.matchMaterial(discStr);
                    if (discMat != null) {
                        config.availableDiscs.add(discMat);
                    }
                }
            }
        }
        config.nameFormat = StringUtils.t(this.config.getString("song-item.name-format", "<gold>{song}"));
        config.loreFormat = StringUtils.t(this.config.getStringList("song-item.lore-format"));
        config.aliasesFormat = StringUtils.t(this.config.getString("song-item.aliases-format", "<gray>Aliases: <yellow>{aliases}"));
        config.tagsFormat = StringUtils.t(this.config.getString("song-item.tags-format", "<gray>Tags: <aqua>{tags}"));
        this.songItemConfig = config;
        return config;
    }

    public PlaylistItemConfig getPlaylistItemConfig() {
        return sectionConfigs().playlistItem();
    }

    private PlaylistItemConfig parsePlaylistItemConfig() {
        PlaylistItemConfig playlistItemConfig = new PlaylistItemConfig();
        if (this.config == null) {
            return playlistItemConfig;
        }
        Material material = Material.matchMaterial(this.config.getString("playlist-item.material", "PAPER"));
        if (material != null) {
            playlistItemConfig.material = material;
        }
        playlistItemConfig.customModelData = this.config.getInt("playlist-item.custom-model-data", 0);
        playlistItemConfig.itemModel = this.config.getString("playlist-item.item-model", "");
        playlistItemConfig.craftEngineItem = this.config.getString("playlist-item.craft-engine-item", "");
        playlistItemConfig.nameFormat = StringUtils.t(this.config.getString("playlist-item.name-format", playlistItemConfig.nameFormat));
        if (this.config.contains("playlist-item.lore-format")) {
            playlistItemConfig.loreFormat = StringUtils.t(this.config.getStringList("playlist-item.lore-format"));
        }
        if (this.config.contains("playlist-item.item-lore")) {
            playlistItemConfig.itemLore = StringUtils.t(this.config.getStringList("playlist-item.item-lore"));
        }
        if (this.config.contains("playlist-item.list-lore")) {
            playlistItemConfig.listLore = StringUtils.t(this.config.getStringList("playlist-item.list-lore"));
        }
        if (this.config.contains("playlist-item.add-song-lore")) {
            playlistItemConfig.addSongLore = StringUtils.t(this.config.getStringList("playlist-item.add-song-lore"));
        }
        if (this.config.contains("playlist-item.add-song-exists-lore")) {
            playlistItemConfig.addSongExistsLore = StringUtils.t(this.config.getStringList("playlist-item.add-song-exists-lore"));
        }
        if (this.config.contains("playlist-item.add-container-lore")) {
            playlistItemConfig.addContainerLore = StringUtils.t(this.config.getStringList("playlist-item.add-container-lore"));
        }
        if (this.config.contains("playlist-item.default-lore")) {
            playlistItemConfig.defaultLore = StringUtils.t(this.config.getStringList("playlist-item.default-lore"));
        }
        return playlistItemConfig;
    }


    public LoopModeConfig getLoopModeConfig() {
        return sectionConfigs().loopMode();
    }

    private LoopModeConfig parseLoopModeConfig() {
        LoopModeConfig loopModeConfig = new LoopModeConfig();
        if (this.config == null) {
            return loopModeConfig;
        }
        loopModeConfig.off = this.config.getString("loop-mode.off", "<red>Off");
        loopModeConfig.single = this.config.getString("loop-mode.single", "<yellow>Single");
        loopModeConfig.all = this.config.getString("loop-mode.all", "<green>All");
        loopModeConfig.toggled = this.config.getString("loop-mode.toggled", "<gray>Loop mode: {mode}");
        loopModeConfig.lore = this.config.getStringList("loop-mode.lore");
        return loopModeConfig;
    }

    public ToggleStatusConfig getToggleStatusConfig() {
        return sectionConfigs().toggleStatus();
    }

    private ToggleStatusConfig parseToggleStatusConfig() {
        ToggleStatusConfig toggleStatusConfig = new ToggleStatusConfig();
        if (this.config == null) {
            return toggleStatusConfig;
        }
        toggleStatusConfig.enabled = this.config.getString("toggle-status.enabled", "<green>Enabled");
        toggleStatusConfig.disabled = this.config.getString("toggle-status.disabled", "<red>Disabled");
        return toggleStatusConfig;
    }

    public PlaybackStatusConfig getPlaybackStatusConfig() {
        return sectionConfigs().playbackStatus();
    }

    private PlaybackStatusConfig parsePlaybackStatusConfig() {
        PlaybackStatusConfig playbackStatusConfig = new PlaybackStatusConfig();
        if (this.config == null) {
            return playbackStatusConfig;
        }
        playbackStatusConfig.playing = this.config.getString("playback-status.playing", "<green>Playing");
        playbackStatusConfig.paused = this.config.getString("playback-status.paused", "<yellow>Paused");
        return playbackStatusConfig;
    }


    public VolumeControlConfig getVolumeControlConfig() {
        return sectionConfigs().volumeControl();
    }

    private VolumeControlConfig parseVolumeControlConfig() {
        VolumeControlConfig volumeControlConfig = new VolumeControlConfig();
        if (this.config == null) {
            return volumeControlConfig;
        }
        volumeControlConfig.title = this.config.getString("volume-control.title", "<gold>Volume Control");
        volumeControlConfig.levelFormat = this.config.getString("volume-control.level-format", "<yellow>Volume: {volume}%");
        volumeControlConfig.increaseText = this.config.getString("volume-control.increase-text", "<green>Increase Volume");
        volumeControlConfig.decreaseText = this.config.getString("volume-control.decrease-text", "<red>Decrease Volume");
        ConfigurationSection materialsSection = this.config.getConfigurationSection("volume-control.materials");
        if (materialsSection != null) {
            ConfigurationSection muteSection = materialsSection.getConfigurationSection("mute");
            if (muteSection != null) {
                volumeControlConfig.materialMute = Material.matchMaterial(muteSection.getString("material", "BARRIER"));
                volumeControlConfig.customModelDataMute = muteSection.getInt("custom-model-data", 0);
            }
            ConfigurationSection lowSection = materialsSection.getConfigurationSection("low");
            if (lowSection != null) {
                volumeControlConfig.materialLow = Material.matchMaterial(lowSection.getString("material", "RED_WOOL"));
                volumeControlConfig.customModelDataLow = lowSection.getInt("custom-model-data", 0);
            }
            ConfigurationSection mediumSection = materialsSection.getConfigurationSection("medium");
            if (mediumSection != null) {
                volumeControlConfig.materialMedium = Material.matchMaterial(mediumSection.getString("material", "ORANGE_WOOL"));
                volumeControlConfig.customModelDataMedium = mediumSection.getInt("custom-model-data", 0);
            }
            ConfigurationSection highSection = materialsSection.getConfigurationSection("high");
            if (highSection != null) {
                volumeControlConfig.materialHigh = Material.matchMaterial(highSection.getString("material", "YELLOW_WOOL"));
                volumeControlConfig.customModelDataHigh = highSection.getInt("custom-model-data", 0);
            }
            ConfigurationSection fullSection = materialsSection.getConfigurationSection("full");
            if (fullSection != null) {
                volumeControlConfig.materialFull = Material.matchMaterial(fullSection.getString("material", "GREEN_WOOL"));
                volumeControlConfig.customModelDataFull = fullSection.getInt("custom-model-data", 0);
            }
        }
        return volumeControlConfig;
    }

    public SearchConfig getSearchConfig() {
        SearchConfig searchConfig = new SearchConfig();
        if (this.config == null) {
            return searchConfig;
        }
        searchConfig.title = this.config.getString("search.title", "<gold>Search Music");
        searchConfig.placeholder = this.config.getString("search.placeholder", "<gray>Enter search term...");
        return searchConfig;
    }

    public MusicEditorConfig getMusicEditorConfig() {
        MusicEditorConfig editorConfig = new MusicEditorConfig();
        if (this.config == null) {
            return editorConfig;
        }
        ConfigurationSection editorSection = this.config.getConfigurationSection("music-editor");
        if (editorSection == null) {
            return editorConfig;
        }
        editorConfig.title = editorSection.getString("title", "<gold>Music Editor <dark_gray>- <yellow>{name}");
        editorConfig.layout = editorSection.getString("layout", editorConfig.layout);

        ConfigurationSection buttonMappingSection = editorSection.getConfigurationSection("button-mapping");
        if (buttonMappingSection != null) {
            for (String key : buttonMappingSection.getKeys(false)) {
                char c = getChar(buttonMappingSection, key, ' ');
                if (c != ' ') {
                    editorConfig.getButtonMapping().put(key, c);
                }
            }
            editorConfig.editAreaChar = editorConfig.getButtonMapping().getOrDefault("edit-area", 'E');
            editorConfig.emptyChar = editorConfig.getButtonMapping().getOrDefault("empty", 'X');
        }

        ConfigurationSection buttonsSection = editorSection.getConfigurationSection("buttons");
        if (buttonsSection != null) {
            for (String key : buttonsSection.getKeys(false)) {
                HotbarButtonConfig btnConfig = editorConfig.getButtons().get(key);
                if (btnConfig != null) {
                    GUISectionConfigLoader.loadHotbarButtonConfig(buttonsSection, key, btnConfig);
                }
            }
        }

        ConfigurationSection guiSection = editorSection.getConfigurationSection("gui");
        if (guiSection != null) {
            GUISectionConfigLoader.loadEditorItemConfig(guiSection, "empty-slot", editorConfig.emptySlot);
            GUISectionConfigLoader.loadEditorItemConfig(guiSection, "empty-slot-selected", editorConfig.emptySlotSelected);
            GUISectionConfigLoader.loadEditorItemConfig(guiSection, "playing-empty-slot", editorConfig.playingEmptySlot);
            GUISectionConfigLoader.loadEditorItemConfig(guiSection, "filled-note", editorConfig.filledNote);
            GUISectionConfigLoader.loadEditorItemConfig(guiSection, "filled-note-selected", editorConfig.filledNoteSelected);
            GUISectionConfigLoader.loadEditorItemConfig(guiSection, "multi-instrument", editorConfig.multiInstrument);
            GUISectionConfigLoader.loadEditorItemConfig(guiSection, "multi-instrument-selected", editorConfig.multiInstrumentSelected);
            editorConfig.multiInstrumentListFormat = StringUtils.t(guiSection.getString("multi-instrument-list-format", editorConfig.multiInstrumentListFormat));
            editorConfig.playingPrefix = StringUtils.t(guiSection.getString("playing-prefix", editorConfig.playingPrefix));
            editorConfig.warningPrefix = StringUtils.t(guiSection.getString("warning-prefix", editorConfig.warningPrefix));
            editorConfig.selectedPrefix = StringUtils.t(guiSection.getString("selected-prefix", editorConfig.selectedPrefix));
            editorConfig.extendedOctaveWarning = StringUtils.t(guiSection.getString("extended-octave-warning", editorConfig.extendedOctaveWarning));
            editorConfig.extendedOctaveAreaWarning = StringUtils.t(guiSection.getString("extended-octave-area-warning", editorConfig.extendedOctaveAreaWarning));
            editorConfig.enableOctaveHint = StringUtils.t(guiSection.getString("enable-octave-hint", editorConfig.enableOctaveHint));
            editorConfig.canOnlyDeleteHint = StringUtils.t(guiSection.getString("can-only-delete-hint", editorConfig.canOnlyDeleteHint));
            editorConfig.nowPlayingText = StringUtils.t(guiSection.getString("now-playing-text", editorConfig.nowPlayingText));
        }

        return editorConfig;
    }

    public JavaPlugin getPlugin() {
        return this.plugin;
    }

    public YamlConfiguration getConfig() {
        return this.config;
    }

    public File getConfigFile() {
        return this.configFile;
    }

    public Map<String, ButtonConfig> getButtonConfigs() {
        return this.buttonRegistry.getButtonConfigs();
    }

    public Map<String, GUIConfig> getGuiConfigs() {
        return this.guiConfigs;
    }

    public InstrumentSelectConfig getInstrumentSelectConfig() {
        InstrumentSelectConfig instrumentConfig = new InstrumentSelectConfig();
        if (this.config == null) {
            return instrumentConfig;
        }
        ConfigurationSection instrumentSection = this.config.getConfigurationSection("instrument-select");
        if (instrumentSection == null) {
            instrumentSection = this.config.getConfigurationSection("music-editor.instrument-select");
        }
        if (instrumentSection == null) {
            return instrumentConfig;
        }
        instrumentConfig.layout = instrumentSection.getString("layout", instrumentConfig.layout);

        ConfigurationSection buttonMappingSection = instrumentSection.getConfigurationSection("button-mapping");
        if (buttonMappingSection != null) {
            for (String key : buttonMappingSection.getKeys(false)) {
                char c = getChar(buttonMappingSection, key, ' ');
                if (c != ' ') {
                    instrumentConfig.getButtonMapping().put(key, c);
                }
            }
        }

        ConfigurationSection buttonsSection = instrumentSection.getConfigurationSection("buttons");
        if (buttonsSection != null) {
            for (String key : buttonsSection.getKeys(false)) {
                ConfigurationSection btnSection = buttonsSection.getConfigurationSection(key);
                if (btnSection != null) {
                    HotbarButtonConfig btnConfig = instrumentConfig.getButton(key);
                    if (btnConfig != null) {
                        String materialStr = btnSection.getString("material");
                        if (materialStr != null) {
                            btnConfig.material = Material.matchMaterial(materialStr);
                            if (btnConfig.material == null) {
                                btnConfig.material = Material.STONE;
                            }
                        }
                        btnConfig.name = StringUtils.t(btnSection.getString("name", btnConfig.name));
                        btnConfig.lore = StringUtils.t(btnSection.getStringList("lore"));
                        btnConfig.customModelData = btnSection.getInt("custom-model-data", 0);
                    }
                }
            }
        }
        return instrumentConfig;
    }

    public MusicSelectConfig getMusicSelectConfig() {
        MusicSelectConfig selectConfig = new MusicSelectConfig();
        if (this.config == null) {
            return selectConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("music-select");
        if (section == null) {
            return selectConfig;
        }
        selectConfig.title = section.getString("title", selectConfig.title);
        selectConfig.layout = section.getString("layout", selectConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, selectConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, selectConfig.buttons);

        return selectConfig;
    }

    public SettingsMenuConfig getSettingsMenuConfig() {
        SettingsMenuConfig settingsConfig = new SettingsMenuConfig();
        if (this.config == null) {
            return settingsConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("settings-menu");
        if (section == null) {
            return settingsConfig;
        }
        settingsConfig.title = section.getString("title", settingsConfig.title);
        settingsConfig.layout = section.getString("layout", settingsConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, settingsConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, settingsConfig.buttons);

        return settingsConfig;
    }

    public BPMSettingsConfig getBPMSettingsConfig() {
        BPMSettingsConfig bpmConfig = new BPMSettingsConfig();
        if (this.config == null) {
            return bpmConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("bpm-settings");
        if (section == null) {
            return bpmConfig;
        }
        bpmConfig.title = section.getString("title", bpmConfig.title);
        bpmConfig.layout = section.getString("layout", bpmConfig.layout);
        ConfigurationSection stepSection = section.getConfigurationSection("step-button");
        if (stepSection != null) {
            bpmConfig.stepIncreaseName = stepSection.getString("increase-name", bpmConfig.stepIncreaseName);
            bpmConfig.stepDecreaseName = stepSection.getString("decrease-name", bpmConfig.stepDecreaseName);
            List<String> stepLore = stepSection.getStringList("lore");
            if (stepLore != null && !stepLore.isEmpty()) {
                bpmConfig.stepLore = StringUtils.t(stepLore);
            }
        }
        List<Integer> steps = section.getIntegerList("steps");
        if (!steps.isEmpty()) {
            List<Integer> validSteps = steps.stream().filter(step -> step != 0).toList();
            if (!validSteps.isEmpty()) {
                bpmConfig.steps = validSteps;
            }
        }

        GUISectionConfigLoader.loadButtonMapping(section, bpmConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, bpmConfig.buttons);

        return bpmConfig;
    }

    public PublishGUIConfig getPublishGUIConfig() {
        PublishGUIConfig publishConfig = new PublishGUIConfig();
        if (this.config == null) {
            return publishConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("publish-gui");
        if (section == null) {
            return publishConfig;
        }
        publishConfig.title = section.getString("title", publishConfig.title);
        publishConfig.layout = section.getString("layout", publishConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, publishConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, publishConfig.buttons);

        return publishConfig;
    }

    public PublishConfirmConfig getPublishConfirmConfig() {
        PublishConfirmConfig confirmConfig = new PublishConfirmConfig();
        if (this.config == null) {
            return confirmConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("publish-confirm");
        if (section == null) {
            return confirmConfig;
        }
        confirmConfig.title = section.getString("title", confirmConfig.title);
        confirmConfig.layout = section.getString("layout", confirmConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, confirmConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, confirmConfig.buttons);

        return confirmConfig;
    }

    public ManagePublishedConfig getManagePublishedConfig() {
        ManagePublishedConfig manageConfig = new ManagePublishedConfig();
        if (this.config == null) {
            return manageConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("manage-published");
        if (section == null) {
            return manageConfig;
        }
        manageConfig.title = section.getString("title", manageConfig.title);
        manageConfig.layout = section.getString("layout", manageConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, manageConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, manageConfig.buttons);

        return manageConfig;
    }

    public PlayerShopConfig getPlayerShopConfig() {
        PlayerShopConfig shopConfig = new PlayerShopConfig();
        if (this.config == null) {
            return shopConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("player-shop");
        if (section == null) {
            return shopConfig;
        }
        shopConfig.title = section.getString("title", shopConfig.title);
        shopConfig.layout = section.getString("layout", shopConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, shopConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, shopConfig.buttons);

        return shopConfig;
    }

    public PublishReviewConfig getPublishReviewConfig() {
        PublishReviewConfig reviewConfig = new PublishReviewConfig();
        if (this.config == null) {
            return reviewConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("publish-review");
        if (section == null) {
            return reviewConfig;
        }
        reviewConfig.title = section.getString("title", reviewConfig.title);
        reviewConfig.layout = section.getString("layout", reviewConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, reviewConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, reviewConfig.buttons);

        return reviewConfig;
    }

    public PurchaseConfirmConfig getPurchaseConfirmConfig() {
        PurchaseConfirmConfig purchaseConfig = new PurchaseConfirmConfig();
        if (this.config == null) {
            return purchaseConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("purchase-confirm");
        if (section == null) {
            return purchaseConfig;
        }
        purchaseConfig.title = section.getString("title", purchaseConfig.title);
        purchaseConfig.layout = section.getString("layout", purchaseConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, purchaseConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, purchaseConfig.buttons);

        return purchaseConfig;
    }

    public ShopContainerPurchaseConfirmConfig getShopContainerPurchaseConfirmConfig() {
        ShopContainerPurchaseConfirmConfig purchaseConfig = new ShopContainerPurchaseConfirmConfig();
        if (this.config == null) {
            return purchaseConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("shop-container-purchase-confirm");
        if (section == null) {
            return purchaseConfig;
        }
        purchaseConfig.title = section.getString("title", purchaseConfig.title);
        purchaseConfig.layout = section.getString("layout", purchaseConfig.layout);

        GUISectionConfigLoader.loadButtonMapping(section, purchaseConfig.getButtonMapping());
        GUISectionConfigLoader.loadButtonsConfig(section, purchaseConfig.buttons);

        return purchaseConfig;
    }

    public ShopLoreConfig getShopLoreConfig() {
        return sectionConfigs().shopLore();
    }

    private ShopLoreConfig parseShopLoreConfig() {
        ShopLoreConfig shopLoreConfig = new ShopLoreConfig();
        if (this.config == null) {
            return shopLoreConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("song-item");
        if (section == null) {
            return shopLoreConfig;
        }
        shopLoreConfig.loadFromConfig(section);
        return shopLoreConfig;
    }

    public TextPlayerEditConfig getTextPlayerEditConfig() {
        TextPlayerEditConfig textPlayerConfig = new TextPlayerEditConfig();
        if (this.config == null) {
            return textPlayerConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("text-player-edit");
        if (section == null) {
            return textPlayerConfig;
        }
        textPlayerConfig.title = section.getString("title", textPlayerConfig.title);
        textPlayerConfig.layout = section.getString("layout", textPlayerConfig.layout);
        textPlayerConfig.rangeStep = Math.max(1, section.getInt("range-step", textPlayerConfig.rangeStep));
        textPlayerConfig.heightStep = Math.max(0.01, section.getDouble("height-step", textPlayerConfig.heightStep));
        textPlayerConfig.positionStep = Math.max(0.01, section.getDouble("position-step", textPlayerConfig.positionStep));
        textPlayerConfig.positionShiftMultiplier = Math.max(1, section.getInt("position-shift-multiplier", textPlayerConfig.positionShiftMultiplier));
        GUISectionConfigLoader.loadButtonMapping(section, textPlayerConfig.buttonMapping);
        GUISectionConfigLoader.loadButtonsConfig(section, textPlayerConfig.buttons);
        return textPlayerConfig;
    }

    public EditMenuConfig getEditMenuConfig() {
        EditMenuConfig editMenuConfig = new EditMenuConfig();
        if (this.config == null) {
            return editMenuConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("edit-menu");
        if (section == null) {
            return editMenuConfig;
        }
        editMenuConfig.title = StringUtils.t(section.getString("title", editMenuConfig.title));
        editMenuConfig.layout = section.getString("layout", editMenuConfig.layout);
        GUISectionConfigLoader.loadButtonMapping(section, editMenuConfig.buttonMapping);
        GUISectionConfigLoader.loadButtonsConfig(section, editMenuConfig.buttons);
        return editMenuConfig;
    }

    public PastePreviewConfig getPastePreviewConfig() {
        PastePreviewConfig pastePreviewConfig = new PastePreviewConfig();
        if (this.config == null) {
            return pastePreviewConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("paste-preview");
        if (section == null) {
            return pastePreviewConfig;
        }
        pastePreviewConfig.title = StringUtils.t(section.getString("title", pastePreviewConfig.title));
        pastePreviewConfig.layout = section.getString("layout", pastePreviewConfig.layout);
        GUISectionConfigLoader.loadButtonMapping(section, pastePreviewConfig.buttonMapping);
        GUISectionConfigLoader.loadButtonsConfig(section, pastePreviewConfig.buttons);
        return pastePreviewConfig;
    }

    public ClearConfirmConfig getClearConfirmConfig() {
        ClearConfirmConfig clearConfirmConfig = new ClearConfirmConfig();
        if (this.config == null) {
            return clearConfirmConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("clear-confirm");
        if (section == null) {
            return clearConfirmConfig;
        }
        ConfigurationSection mappingsSection = section.getConfigurationSection("button-mappings");
        if (mappingsSection != null) {
            for (String key : mappingsSection.getKeys(false)) {
                int slot = mappingsSection.getInt(key, -1);
                if (slot >= 0) {
                    clearConfirmConfig.getButtonMappings().put(key, slot);
                }
            }
        }
        GUISectionConfigLoader.loadButtonsConfig(section, clearConfirmConfig.getButtons());
        return clearConfirmConfig;
    }

    public ExitConfirmConfig getExitConfirmConfig() {
        ExitConfirmConfig exitConfirmConfig = new ExitConfirmConfig();
        if (this.config == null) {
            return exitConfirmConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("exit-confirm");
        if (section == null) {
            return exitConfirmConfig;
        }
        exitConfirmConfig.title = StringUtils.t(section.getString("title", exitConfirmConfig.title));
        exitConfirmConfig.layout = section.getString("layout", exitConfirmConfig.layout);
        GUISectionConfigLoader.loadButtonMapping(section, exitConfirmConfig.buttonMapping);
        GUISectionConfigLoader.loadButtonsConfig(section, exitConfirmConfig.buttons);
        return exitConfirmConfig;
    }

    public DeleteConfirmConfig getDeleteConfirmConfig() {
        DeleteConfirmConfig deleteConfirmConfig = new DeleteConfirmConfig();
        if (this.config == null) {
            return deleteConfirmConfig;
        }
        ConfigurationSection section = this.config.getConfigurationSection("delete-confirm");
        if (section == null) {
            return deleteConfirmConfig;
        }
        deleteConfirmConfig.title = StringUtils.t(section.getString("title", deleteConfirmConfig.title));
        deleteConfirmConfig.layout = section.getString("layout", deleteConfirmConfig.layout);
        GUISectionConfigLoader.loadButtonMapping(section, deleteConfirmConfig.buttonMapping);
        GUISectionConfigLoader.loadButtonsConfig(section, deleteConfirmConfig.buttons);
        loadLegacyDeleteConfirmButton(section, deleteConfirmConfig, "warning");
        loadLegacyDeleteConfirmButton(section, deleteConfirmConfig, "confirm");
        loadLegacyDeleteConfirmButton(section, deleteConfirmConfig, "cancel");
        return deleteConfirmConfig;
    }

    private void loadLegacyDeleteConfirmButton(ConfigurationSection section, DeleteConfirmConfig config, String key) {
        if (section.getConfigurationSection("buttons." + key) != null) {
            return;
        }
        ConfigurationSection legacy = section.getConfigurationSection(key);
        HotbarButtonConfig button = config.getButton(key);
        if (legacy == null || button == null) {
            return;
        }
        button.name = StringUtils.t(legacy.getString("name", button.name));
        List<String> lore = legacy.getStringList("lore");
        if (!lore.isEmpty()) {
            button.lore = StringUtils.t(lore);
        }
    }
}
