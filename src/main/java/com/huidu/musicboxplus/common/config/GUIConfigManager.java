package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.ItemUtils;
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
    private volatile FolderItemConfig folderItemConfig;
    private volatile Set<String> guiTitleKeywords;

    private static final Set<String> DEFAULT_GUI_TITLE_KEYWORDS = new HashSet<>(Arrays.asList(
        "MusicBox", "Playlist", "Control", "Volume", "Search",
        "Music", "Player", "Menu", "Shop", "Song", "Sign", "Settings", "Editor"
    ));
    
    private static final int INVENTORY_COLS = 9;
    
    // Slot index of the first occurrence of c in the layout, -1 if there is none.
    public static int getSlotForChar(String layout, char c) {
        if (layout == null) {
            return -1;
        }
        String[] lines = layout.split("\n");
        for (int row = 0; row < lines.length; row++) {
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
        String[] lines = layout.split("\n");
        for (int row = 0; row < lines.length; row++) {
            String line = lines[row];
            for (int col = 0; col < line.length() && col < INVENTORY_COLS; col++) {
                if (line.charAt(col) == c) {
                    slots.add(row * INVENTORY_COLS + col);
                }
            }
        }
        return slots;
    }

    public static GUIConfigManager getInstance() {
        return instance;
    }

    public GUIConfigManager(JavaPlugin plugin) {
        instance = this;
        this.plugin = plugin;
        this.loadConfig();
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
        this.loadGUITitleKeywords();
        this.loadGUIConfigs();
        this.buttonRegistry.load(this.config);
        this.loadSongItemConfig();
        this.loadFolderItemConfig();
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

    private void loadFolderItemConfig() {
        ConfigurationSection section = this.config.getConfigurationSection("folder-item");
        if (section == null) {
            this.folderItemConfig = new FolderItemConfig();
            return;
        }
        this.folderItemConfig = new FolderItemConfig();
        this.folderItemConfig.material = Material.matchMaterial(section.getString("material", "CHEST"));
        if (this.folderItemConfig.material == null) {
            this.folderItemConfig.material = Material.CHEST;
        }
        this.folderItemConfig.customModelData = section.getInt("custom-model-data", 0);
        this.folderItemConfig.nameFormat = StringUtils.t(section.getString("name-format", "<gold>{folder}"));
        this.folderItemConfig.loreFormat = StringUtils.t(section.getStringList("lore-format"));
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
        for (char ch : layout.toCharArray()) {
            if (ch != c) continue;
            ++count;
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
        this.loadConfig();
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
        PlaylistItemConfig playlistItemConfig = new PlaylistItemConfig();
        if (this.config == null) {
            return playlistItemConfig;
        }
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
        ToggleStatusConfig toggleStatusConfig = new ToggleStatusConfig();
        if (this.config == null) {
            return toggleStatusConfig;
        }
        toggleStatusConfig.enabled = this.config.getString("toggle-status.enabled", "<green>Enabled");
        toggleStatusConfig.disabled = this.config.getString("toggle-status.disabled", "<red>Disabled");
        return toggleStatusConfig;
    }

    public PlaybackStatusConfig getPlaybackStatusConfig() {
        PlaybackStatusConfig playbackStatusConfig = new PlaybackStatusConfig();
        if (this.config == null) {
            return playbackStatusConfig;
        }
        playbackStatusConfig.playing = this.config.getString("playback-status.playing", "<green>Playing");
        playbackStatusConfig.paused = this.config.getString("playback-status.paused", "<yellow>Paused");
        return playbackStatusConfig;
    }


    public VolumeControlConfig getVolumeControlConfig() {
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

    public FolderItemConfig getFolderItemConfig() {
        return this.folderItemConfig;
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
        GUIConfigManager.HotbarButtonConfig button = config.getButton(key);
        if (legacy == null || button == null) {
            return;
        }
        button.name = StringUtils.t(legacy.getString("name", button.name));
        List<String> lore = legacy.getStringList("lore");
        if (!lore.isEmpty()) {
            button.lore = StringUtils.t(lore);
        }
    }

    public static class GUIConfig {
        private int rows = 6;
        private String title = "<gold>GUI";
        private String layout = "";
        private int songAreaStartRow = 0;
        private int songAreaEndRow = 5;
        private int songAreaStartCol = 0;
        private int songAreaEndCol = 9;
        private ButtonMappingConfig buttonMapping = new ButtonMappingConfig();

        public int getRows() {
            return this.rows;
        }

        public String getTitle() {
            return this.title;
        }

        public String getLayout() {
            return this.layout;
        }

        public int getSongAreaStartRow() {
            return this.songAreaStartRow;
        }

        public int getSongAreaEndRow() {
            return this.songAreaEndRow;
        }

        public int getSongAreaStartCol() {
            return this.songAreaStartCol;
        }

        public int getSongAreaEndCol() {
            return this.songAreaEndCol;
        }

        public ButtonMappingConfig getButtonMapping() {
            return this.buttonMapping;
        }
    }

    public static class ButtonMappingConfig {
        private char songs = (char)83;
        private char previous = (char)80;
        private char next = (char)78;
        private char empty = (char)88;
        private char back = (char)66;
        private char parent = (char)70;
        private char search = (char)72;
        private char controlPanel = (char)67;
        private char playlist = (char)76;
        private char preventDestroy = (char)68;
        private char infoSign = (char)73;
        private char random = (char)82;
        private char stop = (char)84;
        private char volume = (char)86;
        private char speed = (char)89;
        private char recentSongs = (char)82;
        private char playPause = (char)65;
        private char playMode = (char)77;
        private char progress = (char)80;
        private char songList = (char)83;
        private char loop = (char)76;
        private char protect = (char)75;
        private char add = (char)65;
        private char delete = (char)68;
        private char shuffle = (char)72;
        private char save = (char)86;
        private char returnBtn = (char)82;
        private char create = (char)67;
        private char master = (char)77;
        private char close = (char)76;
        private char results = (char)82;

        public char getSongs() {
            return this.songs;
        }

        public char getPrevious() {
            return this.previous;
        }

        public char getNext() {
            return this.next;
        }

        public char getEmpty() {
            return this.empty;
        }

        public char getBack() {
            return this.back;
        }

        public char getParent() {
            return this.parent;
        }

        public char getSearch() {
            return this.search;
        }

        public char getControlPanel() {
            return this.controlPanel;
        }

        public char getPlaylist() {
            return this.playlist;
        }

        public char getPreventDestroy() {
            return this.preventDestroy;
        }

        public char getInfoSign() {
            return this.infoSign;
        }

        public char getRandom() {
            return this.random;
        }

        public char getStop() {
            return this.stop;
        }

        public char getVolume() {
            return this.volume;
        }

        public char getSpeed() {
            return this.speed;
        }

        public char getRecentSongs() {
            return this.recentSongs;
        }

        public char getPlayPause() {
            return this.playPause;
        }

        public char getPlayMode() {
            return this.playMode;
        }

        public char getProgress() {
            return this.progress;
        }

        public char getSongList() {
            return this.songList;
        }

        public char getLoop() {
            return this.loop;
        }

        public char getProtect() {
            return this.protect;
        }

        public char getAdd() {
            return this.add;
        }

        public char getDelete() {
            return this.delete;
        }

        public char getShuffle() {
            return this.shuffle;
        }

        public char getSave() {
            return this.save;
        }

        public char getReturnBtn() {
            return this.returnBtn;
        }

        public char getCreate() {
            return this.create;
        }

        public char getMaster() {
            return this.master;
        }

        public char getClose() {
            return this.close;
        }

        public char getResults() {
            return this.results;
        }
    }

    public static class ButtonConfig {
        boolean enabled = false;
        int slot = -1;
        Material material = Material.STONE;
        int customModelData = 0;
        String name = "<white>Button";
        String nameOn = "<green>On";
        String nameOff = "<red>Off";
        List<String> lore = new ArrayList<String>();

        // enabled is set when the button exists in gui-config.yml (or falls back to a global
        // definition); an unconfigured button is a placeholder returned by getButtonConfig.
        public boolean isEnabled() {
            return this.enabled;
        }

        public int getSlot() {
            return this.slot;
        }

        public Material getMaterial() {
            return this.material;
        }

        public int getCustomModelData() {
            return this.customModelData;
        }

        public String getName() {
            return this.name;
        }

        public String getNameOn() {
            return this.nameOn;
        }

        public String getNameOff() {
            return this.nameOff;
        }

        public List<String> getLore() {
            return this.lore;
        }

        public ItemStack createItem() {
            return ItemUtils.createStack(this.material, this.name, this.lore, this.customModelData);
        }
    }

    public static class SongItemConfig {
        private boolean customEnabled = false;
        private Material customMaterial = Material.MUSIC_DISC_CAT;
        private int customModelData = 0;
        private String itemModel = "";
        private boolean useRandomDisc = true;
        private List<Material> availableDiscs = new ArrayList<Material>();
        private String nameFormat = "<yellow>{song}";
        private List<String> loreFormat = new ArrayList<String>();
        private String aliasesFormat = "<gray>Aliases: <yellow>{aliases}";
        private String tagsFormat = "<gray>Tags: <aqua>{tags}";

        public boolean isCustomEnabled() {
            return this.customEnabled;
        }

        public Material getCustomMaterial() {
            return this.customMaterial;
        }

        public int getCustomModelData() {
            return this.customModelData;
        }

        public String getItemModel() {
            return this.itemModel;
        }

        public boolean isUseRandomDisc() {
            return this.useRandomDisc;
        }

        public List<Material> getAvailableDiscs() {
            return this.availableDiscs;
        }

        public String getNameFormat() {
            return this.nameFormat;
        }

        public List<String> getLoreFormat() {
            return this.loreFormat;
        }

        public String getAliasesFormat() {
            return this.aliasesFormat;
        }

        public String getTagsFormat() {
            return this.tagsFormat;
        }
    }

    public static class FolderItemConfig {
        private Material material = Material.CHEST;
        private int customModelData = 0;
        private String nameFormat = "<gold>{folder}";
        private List<String> loreFormat = new ArrayList<String>();

        public Material getMaterial() {
            return this.material;
        }

        public int getCustomModelData() {
            return this.customModelData;
        }

        public String getNameFormat() {
            return this.nameFormat;
        }

        public List<String> getLoreFormat() {
            return this.loreFormat;
        }
    }

    public static class ProgressBarConfig {
        private boolean enabled = true;
        private int row = 0;
        private int startCol = 0;
        private int endCol = 9;
        private Material materialUnplayed = Material.GRAY_STAINED_GLASS_PANE;
        private int materialUnplayedModelData = 0;
        private Material materialPlaying = Material.WHITE_STAINED_GLASS_PANE;
        private int materialPlayingModelData = 0;
        private Material materialHalfway = Material.LIME_STAINED_GLASS_PANE;
        private int materialHalfwayModelData = 0;
        private Material materialPlayed = Material.GREEN_STAINED_GLASS_PANE;
        private int materialPlayedModelData = 0;
        private String format = "<yellow>{percent}% <gray>- {time}";
        private List<String> lore = new ArrayList<String>();

        public boolean isEnabled() {
            return this.enabled;
        }

        public int getRow() {
            return this.row;
        }

        public int getStartCol() {
            return this.startCol;
        }

        public int getEndCol() {
            return this.endCol;
        }

        public Material getMaterialUnplayed() {
            return this.materialUnplayed;
        }

        public int getMaterialUnplayedModelData() {
            return this.materialUnplayedModelData;
        }

        public Material getMaterialPlaying() {
            return this.materialPlaying;
        }

        public int getMaterialPlayingModelData() {
            return this.materialPlayingModelData;
        }

        public Material getMaterialHalfway() {
            return this.materialHalfway;
        }

        public int getMaterialHalfwayModelData() {
            return this.materialHalfwayModelData;
        }

        public Material getMaterialPlayed() {
            return this.materialPlayed;
        }

        public int getMaterialPlayedModelData() {
            return this.materialPlayedModelData;
        }

        public String getFormat() {
            return this.format;
        }

        public List<String> getLore() {
            return this.lore;
        }
    }

    public static class PlaylistItemConfig {
        private String nameFormat = "<gold>{playlist}";
        private List<String> loreFormat = new ArrayList<String>();
        private List<String> itemLore = new ArrayList<String>();
        private List<String> listLore = new ArrayList<String>();
        private List<String> addSongLore = new ArrayList<String>();
        private List<String> addSongExistsLore = new ArrayList<String>();
        private List<String> addContainerLore = new ArrayList<String>();
        private List<String> defaultLore = new ArrayList<String>();

        public PlaylistItemConfig() {
            itemLore.add("<gray>Left click to play</gray>");
            itemLore.add("<gray>Right click to remove</gray>");
            listLore.add("<gray>Left click to open</gray>");
            listLore.add("<gray>Right click to edit</gray>");
            addSongLore.add("<yellow>Click to add</yellow>");
            addSongExistsLore.add("<gray>Already in playlist</gray>");
            addContainerLore.add("<yellow>Right-click to add all</yellow>");
            defaultLore.add("<gray>Default playlist</gray>");
            defaultLore.add("<gray>Contains all available songs</gray>");
        }

        public String getNameFormat() {
            return this.nameFormat;
        }

        public List<String> getLoreFormat() {
            return this.loreFormat;
        }

        public List<String> getItemLore() {
            return this.itemLore;
        }

        public List<String> getListLore() {
            return this.listLore;
        }

        public List<String> getAddSongLore() {
            return this.addSongLore;
        }

        public List<String> getAddSongExistsLore() {
            return this.addSongExistsLore;
        }

        public List<String> getAddContainerLore() {
            return this.addContainerLore;
        }

        public List<String> getDefaultLore() {
            return this.defaultLore;
        }
    }

    public static class HotbarButtonConfig {
        Material material;
        String name;
        List<String> lore = new ArrayList<String>();
        int customModelData;
        boolean glow;
        int slot;
        String itemModel = "";
        String craftEngineItem = "";

        public HotbarButtonConfig(Material material, String name) {
            this(material, name, new ArrayList<String>(), 0, false);
        }

        public HotbarButtonConfig(Material material, String name, List<String> lore, int customModelData, boolean glow) {
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.customModelData = customModelData;
            this.glow = glow;
        }

        public Material getMaterial() { return this.material; }
        public String getName() { return this.name; }
        public List<String> getLore() { return this.lore; }
        public int getCustomModelData() { return this.customModelData; }
        public boolean isGlow() { return this.glow; }
        public int getSlot() { return this.slot; }
        public String getItemModel() { return this.itemModel; }
        public String getCraftEngineItem() { return this.craftEngineItem; }

        public ItemStack createItem() {
            return ItemUtils.createStack(this.material, this.name, this.lore, this.customModelData, this.itemModel, this.craftEngineItem);
        }
    }

    public static class EditorItemConfig {
        Material material;
        String name;
        List<String> lore = new ArrayList<String>();
        int customModelData;
        boolean glow;
        int slot;
        boolean useInstrumentMaterial;

        public EditorItemConfig(Material material, String name) {
            this.material = material;
            this.name = name;
        }

        public Material getMaterial() { return this.material; }
        public String getName() { return this.name; }
        public List<String> getLore() { return this.lore; }
        public int getCustomModelData() { return this.customModelData; }
        public boolean isGlow() { return this.glow; }
        public int getSlot() { return this.slot; }
        public boolean isUseInstrumentMaterial() { return this.useInstrumentMaterial; }

        public ItemStack createItem() {
            return ItemUtils.createStack(this.material, this.name, this.lore, this.customModelData);
        }
    }

    public static class PublishGUIConfig {
        private String title = "<gold>Publish Music";
        private String layout = "MMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nX<XCXR>XX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

        public PublishGUIConfig() {
            buttonMapping.put("music-item", 'M');
            buttonMapping.put("prev-page", '<');
            buttonMapping.put("close", 'C');
            buttonMapping.put("create", 'N');
            buttonMapping.put("revenue", 'R');
            buttonMapping.put("next-page", '>');
            buttonMapping.put("empty", 'X');
            buttons.put("music-item", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
            buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
            buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>Close"));
            buttons.put("create", new HotbarButtonConfig(Material.EMERALD, "<green>Create Listing"));
            buttons.put("revenue", new HotbarButtonConfig(Material.EMERALD, "<green>Claim Revenue", List.of("<gray>Pending: <yellow>{amount}"), 0, false));
            buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
        }

        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return this.buttons.get(key); }

        public int getSlotForButton(String buttonType) {
            Character c = buttonMapping.get(buttonType);
            return c == null ? -1 : GUIConfigManager.getSlotForChar(layout, c);
        }

        public int getSlotForChar(char c) {
            return GUIConfigManager.getSlotForChar(layout, c);
        }

        public List<Integer> getSlotsForChar(char c) {
            return GUIConfigManager.getSlotsForChar(layout, c);
        }
    }


    public static class LoopModeConfig {
        private String off = "<red>Off";
        private String single = "<yellow>Single";
        private String all = "<green>All";
        private String toggled = "<gray>Loop mode: {mode}";
        private List<String> lore = new ArrayList<String>();

        public String getOff() {
            return this.off;
        }

        public String getSingle() {
            return this.single;
        }

        public String getAll() {
            return this.all;
        }

        public String getToggled() {
            return this.toggled;
        }

        public List<String> getLore() {
            return this.lore;
        }
    }


    public static class ToggleStatusConfig {
        private String enabled = "<green>Enabled";
        private String disabled = "<red>Disabled";

        public String getEnabled() {
            return this.enabled;
        }

        public String getDisabled() {
            return this.disabled;
        }
    }

    public static class PlaybackStatusConfig {
        private String playing = "<green>Playing";
        private String paused = "<yellow>Paused";

        public String getPlaying() {
            return this.playing;
        }

        public String getPaused() {
            return this.paused;
        }
    }

    public static class VolumeControlConfig {
        private String title = "<gold>Volume Control";
        private String levelFormat = "<yellow>Volume: {volume}%";
        private String increaseText = "<green>Increase Volume";
        private String decreaseText = "<red>Decrease Volume";
        private Material materialMute = Material.BARRIER;
        private Material materialLow = Material.RED_WOOL;
        private Material materialMedium = Material.ORANGE_WOOL;
        private Material materialHigh = Material.YELLOW_WOOL;
        private Material materialFull = Material.GREEN_WOOL;
        private int customModelDataMute = 0;
        private int customModelDataLow = 0;
        private int customModelDataMedium = 0;
        private int customModelDataHigh = 0;
        private int customModelDataFull = 0;

        public String getTitle() {
            return this.title;
        }

        public String getLevelFormat() {
            return this.levelFormat;
        }

        public String getIncreaseText() {
            return this.increaseText;
        }

        public String getDecreaseText() {
            return this.decreaseText;
        }

        public Material getMaterialMute() {
            return this.materialMute;
        }

        public Material getMaterialLow() {
            return this.materialLow;
        }

        public Material getMaterialMedium() {
            return this.materialMedium;
        }

        public Material getMaterialHigh() {
            return this.materialHigh;
        }

        public Material getMaterialFull() {
            return this.materialFull;
        }

        public int getCustomModelDataMute() {
            return this.customModelDataMute;
        }

        public int getCustomModelDataLow() {
            return this.customModelDataLow;
        }

        public int getCustomModelDataMedium() {
            return this.customModelDataMedium;
        }

        public int getCustomModelDataHigh() {
            return this.customModelDataHigh;
        }

        public int getCustomModelDataFull() {
            return this.customModelDataFull;
        }
    }

    public static class SearchConfig {
        private String title = "<gold>Search Music";
        private String placeholder = "<gray>Enter search term...";

        public String getTitle() {
            return this.title;
        }

        public String getPlaceholder() {
            return this.placeholder;
        }
    }

    public static class MusicEditorConfig {
        private String title = "<gold>Music Editor<dark_gray> - <yellow>{name}";
        private String layout = "EEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nEEEEEEEEE\nUDLIRXBTM\nYQWJKXZCX\nXXXXXPXSX\nXXXHXXXXX";
        private char editAreaChar = 'E';
        private char emptyChar = 'X';
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        private final EditorItemConfig emptySlot = new EditorItemConfig(Material.GRAY_STAINED_GLASS_PANE, "<gray>Empty");
        private final EditorItemConfig emptySlotSelected = new EditorItemConfig(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>Selected <gray>Empty");
        private final EditorItemConfig playingEmptySlot = new EditorItemConfig(Material.GREEN_STAINED_GLASS_PANE, "<green>Playing");
        private final EditorItemConfig filledNote = new EditorItemConfig(Material.NOTE_BLOCK, "<green>{note}");
        private final EditorItemConfig filledNoteSelected = new EditorItemConfig(Material.NOTE_BLOCK, "<aqua>Selected <green>{note}");
        private final EditorItemConfig multiInstrument = new EditorItemConfig(Material.NOTE_BLOCK, "<yellow>Multiple Instruments <gray>({count})");
        private final EditorItemConfig multiInstrumentSelected = new EditorItemConfig(Material.NOTE_BLOCK, "<aqua>Selected <yellow>Multiple Instruments <gray>({count})");
        private String multiInstrumentListFormat = "<gray>- <green>{instrument}";
        private String playingPrefix = "<yellow>▶ ";
        private String warningPrefix = "<red>! ";
        private String selectedPrefix = "<aqua>» ";
        private String extendedOctaveWarning = "<red>Some notes are outside the playable octave range";
        private String extendedOctaveAreaWarning = "<red>Selection contains notes outside the playable octave range";
        private String enableOctaveHint = "<gray>Enable 10-octave mode in config to use higher notes";
        private String canOnlyDeleteHint = "<gray>Only deletion is available in this area";
        private String nowPlayingText = "<yellow>Now Playing";

        public MusicEditorConfig() {
            filledNote.useInstrumentMaterial = true;
            filledNoteSelected.useInstrumentMaterial = true;
            multiInstrument.useInstrumentMaterial = true;
            multiInstrumentSelected.useInstrumentMaterial = true;

            buttonMapping.put("edit-area", 'E');
            buttonMapping.put("empty", 'X');
            buttonMapping.put("pitch-up", 'U');
            buttonMapping.put("pitch-down", 'D');
            buttonMapping.put("tick-left", 'L');
            buttonMapping.put("tick-right", 'R');
            buttonMapping.put("instrument", 'I');
            buttonMapping.put("play", 'P');
            buttonMapping.put("save", 'S');
            buttonMapping.put("exit", 'H');
            buttonMapping.put("bpm-up", 'B');
            buttonMapping.put("bpm-down", 'T');
            buttonMapping.put("time-signature", 'M');
            buttonMapping.put("settings", 'G');
            buttonMapping.put("clear-all", 'C');
            buttonMapping.put("info-name", 'N');
            buttonMapping.put("info-notes", 'O');
            buttonMapping.put("info-bpm", 'K');
            buttonMapping.put("info-time", 'V');
            buttonMapping.put("confirm-save", 'W');
            buttonMapping.put("confirm-exit", 'Z');
            buttonMapping.put("cancel", 'A');
            buttonMapping.put("undo", 'Y');
            buttonMapping.put("redo", 'Z');
            buttonMapping.put("copy", 'Q');
            buttonMapping.put("paste", 'J');
            buttonMapping.put("delete-selection", 'K');

            buttons.put("pitch-up", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Pitch +"));
            buttons.put("pitch-down", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Pitch -"));
            buttons.put("tick-left", new HotbarButtonConfig(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Previous Tick"));
            buttons.put("tick-right", new HotbarButtonConfig(Material.BLUE_STAINED_GLASS_PANE, "<blue>Next Tick"));
            buttons.put("instrument", new HotbarButtonConfig(Material.NOTE_BLOCK, "<aqua>Instrument"));
            buttons.put("play", new HotbarButtonConfig(Material.JUKEBOX, "<green>Play"));
            buttons.put("play-stop", new HotbarButtonConfig(Material.BARRIER, "<red>Stop Playback"));
            buttons.put("save", new HotbarButtonConfig(Material.WRITABLE_BOOK, "<green>Save"));
            buttons.put("exit", new HotbarButtonConfig(Material.BARRIER, "<red>Exit"));
            buttons.put("empty", new HotbarButtonConfig(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray>Empty"));
            buttons.put("bpm-up", new HotbarButtonConfig(Material.LIME_DYE, "<green>BPM +5"));
            buttons.put("bpm-down", new HotbarButtonConfig(Material.RED_DYE, "<red>BPM -5"));
            buttons.put("time-signature", new HotbarButtonConfig(Material.CLOCK, "<aqua>Time Signature"));
            buttons.put("settings", new HotbarButtonConfig(Material.COMPASS, "<aqua>Editor Settings"));
            buttons.put("clear-all", new HotbarButtonConfig(Material.TNT, "<red>Clear Notes"));
            buttons.put("info-name", new HotbarButtonConfig(Material.NAME_TAG, "<gold>Music Name"));
            buttons.put("info-notes", new HotbarButtonConfig(Material.NOTE_BLOCK, "<yellow>Notes"));
            buttons.put("info-bpm", new HotbarButtonConfig(Material.REPEATER, "<gold>BPM"));
            buttons.put("info-time", new HotbarButtonConfig(Material.CLOCK, "<aqua>Length"));
            buttons.put("confirm-save", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Save"));
            buttons.put("confirm-exit", new HotbarButtonConfig(Material.RED_WOOL, "<red>Confirm Exit"));
            buttons.put("cancel", new HotbarButtonConfig(Material.BARRIER, "<yellow>Cancel"));
            buttons.put("undo", new HotbarButtonConfig(Material.ARMOR_STAND, "<yellow>Undo"));
            buttons.put("redo", new HotbarButtonConfig(Material.END_CRYSTAL, "<aqua>Redo"));
            buttons.put("copy", new HotbarButtonConfig(Material.WRITTEN_BOOK, "<green>Copy"));
            buttons.put("paste", new HotbarButtonConfig(Material.BOOK, "<gold>Paste"));
            buttons.put("delete-selection", new HotbarButtonConfig(Material.TNT, "<red>Delete Selection"));
        }

        public String getTitle() { return this.title; }
        public int getRows() { return 6; }
        public String getLayout() { return this.layout; }
        public char getEditAreaChar() { return this.editAreaChar; }
        public char getEmptyChar() { return this.emptyChar; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public Map<String, HotbarButtonConfig> getButtons() { return this.buttons; }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : GUIConfigManager.getSlotForChar(layout, c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
        public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
        public HotbarButtonConfig getButton(String buttonType) { return buttons.get(buttonType); }
        public boolean isChestSlot(int slot) { return slot >= 0 && slot < 54; }
        public boolean isInventorySlot(int slot) { return slot >= 54 && slot < 90; }
        public int toInventorySlot(int layoutSlot) { return layoutSlot >= 54 ? layoutSlot - 54 : -1; }
        public EditorItemConfig getEmptySlot() { return this.emptySlot; }
        public EditorItemConfig getEmptySlotSelected() { return this.emptySlotSelected; }
        public EditorItemConfig getPlayingEmptySlot() { return this.playingEmptySlot; }
        public EditorItemConfig getFilledNote() { return this.filledNote; }
        public EditorItemConfig getFilledNoteSelected() { return this.filledNoteSelected; }
        public EditorItemConfig getMultiInstrument() { return this.multiInstrument; }
        public EditorItemConfig getMultiInstrumentSelected() { return this.multiInstrumentSelected; }
        public String getMultiInstrumentListFormat() { return this.multiInstrumentListFormat; }
        public String getPlayingPrefix() { return this.playingPrefix; }
        public String getWarningPrefix() { return this.warningPrefix; }
        public String getSelectedPrefix() { return this.selectedPrefix; }
        public String getExtendedOctaveWarning() { return this.extendedOctaveWarning; }
        public String getExtendedOctaveAreaWarning() { return this.extendedOctaveAreaWarning; }
        public String getEnableOctaveHint() { return this.enableOctaveHint; }
        public String getCanOnlyDeleteHint() { return this.canOnlyDeleteHint; }
        public String getNowPlayingText() { return this.nowPlayingText; }
    }

    public static class InstrumentSelectConfig {
        private String layout = "IIIIIIIII\nIIIIIIIII\nIIIIIIIII\nN<XPX>X>B";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

        public InstrumentSelectConfig() {
            buttonMapping.put("note-info", 'N');
            buttonMapping.put("prev-page", '<');
            buttonMapping.put("play-preview", 'P');
            buttonMapping.put("next-page", '>');
            buttonMapping.put("back", 'B');
            buttonMapping.put("instrument", 'I');
            buttons.put("note-info", new HotbarButtonConfig(Material.NOTE_BLOCK, "<white>Note <yellow>{note}"));
            buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
            buttons.put("play-preview", new HotbarButtonConfig(Material.JUKEBOX, "<green>Preview From Current Note"));
            buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
            buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
        }

        public String getTitle() {
            String title = "<gold>Select Instrument";
            return title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
        public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
    }

    public static class MusicSelectConfig {
        private String title = "<gold>Select Music";
        private String layout = "MMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nX<XCXN>XX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

        public MusicSelectConfig() {
            buttonMapping.put("music-item", 'M');
            buttonMapping.put("prev-page", '<');
            buttonMapping.put("close", 'C');
            buttonMapping.put("create", 'N');
            buttonMapping.put("next-page", '>');
            buttonMapping.put("empty", 'X');
            buttons.put("music-item", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
            buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
            buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>Close"));
            buttons.put("create", new HotbarButtonConfig(Material.EMERALD, "<green>Create Music"));
            buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
        }

        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
        public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
    }

    public static class SettingsMenuConfig {
        private String title = "<gold>Music Settings";
        private String layout = "XBXSXTXNX\nXXXXCXXXX\nXXXXWXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

        public SettingsMenuConfig() {
            buttonMapping.put("bpm", 'B');
            buttonMapping.put("subdivision", 'S');
            buttonMapping.put("time-signature", 'T');
            buttonMapping.put("name", 'N');
            buttonMapping.put("clear", 'C');
            buttonMapping.put("save-close", 'W');
            buttonMapping.put("publish", 'P');
            buttonMapping.put("back", 'X');
            buttons.put("bpm", new HotbarButtonConfig(Material.REPEATER, "<gold>BPM Settings"));
            buttons.put("subdivision", new HotbarButtonConfig(Material.REDSTONE, "<red>Beat Subdivision"));
            buttons.put("time-signature", new HotbarButtonConfig(Material.CLOCK, "<aqua>Time Signature"));
            buttons.put("name", new HotbarButtonConfig(Material.NAME_TAG, "<yellow>Music Name"));
            buttons.put("clear", new HotbarButtonConfig(Material.TNT, "<red>Clear Notes"));
            buttons.put("save-close", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Save and Close"));
            buttons.put("publish", new HotbarButtonConfig(Material.EMERALD, "<green>Publish"));
            buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
        }

        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class BPMSettingsConfig {
        private String title = "<gold>BPM Settings";
        private String layout = "XXXCXXXXX\nXXXXXXXXX\nXXXXXXXXX\nXXXXBXXXX";
        private String stepIncreaseName = "<green>+{amount} BPM</green>";
        private String stepDecreaseName = "<red>-{amount} BPM</red>";
        private List<String> stepLore = new ArrayList<>(Arrays.asList(
            "<gray>Click to adjust BPM</gray>",
            "<gray>Current: </gray><yellow>{current}</yellow>",
            "<gray>After: </gray><yellow>{after}</yellow>"
        ));
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

        public BPMSettingsConfig() {
            buttonMapping.put("current-bpm", 'C');
            buttonMapping.put("reset", 'R');
            buttonMapping.put("back", 'B');
            buttons.put("current-bpm", new HotbarButtonConfig(Material.REPEATER, "<yellow>Current BPM: <green>{bpm}"));
            buttons.put("reset", new HotbarButtonConfig(Material.REDSTONE, "<red>Reset to 20"));
            buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
        }

        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
        public String getStepIncreaseName() { return this.stepIncreaseName; }
        public String getStepDecreaseName() { return this.stepDecreaseName; }
        public List<String> getStepLore() { return this.stepLore; }
        public List<Integer> getSlotsForChars(String chars) {
            List<Integer> slots = new ArrayList<>();
            if (chars == null || chars.isEmpty()) return slots;
            String[] rows = layout.split("\\n");
            for (int row = 0; row < rows.length; row++) {
                String line = rows[row];
                for (int col = 0; col < line.length() && col < 9; col++) {
                    if (chars.indexOf(line.charAt(col)) >= 0) slots.add(row * 9 + col);
                }
            }
            return slots;
        }
    }

    public static class PublishConfirmConfig {
        private String title = "<gold>Publish Music";
        private String layout = "XXXIXXXXX\nXPXDXTXCX\nXXXXBXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public PublishConfirmConfig() {
            buttonMapping.put("info", 'I'); buttonMapping.put("price", 'P'); buttonMapping.put("description", 'D'); buttonMapping.put("tax-info", 'T'); buttonMapping.put("confirm", 'C'); buttonMapping.put("cancel", 'X');
            buttons.put("info", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
            buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Price"));
            buttons.put("description", new HotbarButtonConfig(Material.WRITABLE_BOOK, "<aqua>Description"));
            buttons.put("tax-info", new HotbarButtonConfig(Material.REDSTONE, "<red>Tax Info"));
            buttons.put("confirm", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Publish"));
            buttons.put("cancel", new HotbarButtonConfig(Material.RED_WOOL, "<red>Cancel"));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class ManagePublishedConfig {
        private String title = "<gold>Published Music: {name}";
        private String layout = "XXXIXXXXX\nXPXSXUXTX\nXXXDXBXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public ManagePublishedConfig() {
            buttonMapping.put("info", 'I'); buttonMapping.put("price", 'P'); buttonMapping.put("stats", 'S'); buttonMapping.put("update", 'U'); buttonMapping.put("toggle", 'T'); buttonMapping.put("delete", 'D'); buttonMapping.put("back", 'B');
            buttons.put("info", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
            buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Price"));
            buttons.put("stats", new HotbarButtonConfig(Material.DIAMOND, "<aqua>Statistics"));
            buttons.put("update", new HotbarButtonConfig(Material.ANVIL, "<yellow>Update Description"));
            buttons.put("toggle-unpublish", new HotbarButtonConfig(Material.RED_WOOL, "<red>Unpublish"));
            buttons.put("toggle-republish", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Republish"));
            buttons.put("delete", new HotbarButtonConfig(Material.TNT, "<red>Delete Listing"));
            buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class PublishReviewConfig {
        private String title = "<gold>Publish Review";
        private String layout = "RRRRRRRRR\nRRRRRRRRR\nRRRRRRRRR\nRRRRRRRRR\nRRRRRRRRR\nX<XCXFX>";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public PublishReviewConfig() {
            buttonMapping.put("review-item", 'R'); buttonMapping.put("prev-page", '<'); buttonMapping.put("close", 'C'); buttonMapping.put("refresh", 'F'); buttonMapping.put("next-page", '>');
            buttons.put("review-item", new HotbarButtonConfig(Material.WRITABLE_BOOK, "<yellow>{name}"));
            buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
            buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>Close"));
            buttons.put("refresh", new HotbarButtonConfig(Material.COMPASS, "<aqua>Refresh"));
            buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
        public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
    }

    public static class PlayerShopConfig {
        private String title = "<gold>Player Music Shop";
        private String layout = "MMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nMMMMMMMMM\nX<XCXSY>X";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public PlayerShopConfig() {
            buttonMapping.put("music-item", 'M'); buttonMapping.put("prev-page", '<'); buttonMapping.put("close", 'C'); buttonMapping.put("search", 'S'); buttonMapping.put("my-music", 'Y'); buttonMapping.put("next-page", '>'); buttonMapping.put("empty", 'X');
            buttons.put("music-item", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
            buttons.put("prev-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Previous Page"));
            buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>Close"));
            buttons.put("search", new HotbarButtonConfig(Material.COMPASS, "<aqua>Search"));
            buttons.put("my-music", new HotbarButtonConfig(Material.CHEST, "<green>My Published Music"));
            buttons.put("next-page", new HotbarButtonConfig(Material.ARROW, "<yellow>Next Page"));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
        public List<Integer> getSlotsForChar(char c) { return GUIConfigManager.getSlotsForChar(layout, c); }
    }

    public static class PurchaseConfirmConfig {
        private String title = "<gold>Purchase: {name}";
        private String layout = "XXXIXXXXX\nXPXXCXXXX\nXXXXBXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public PurchaseConfirmConfig() {
            buttonMapping.put("info", 'I'); buttonMapping.put("price", 'P'); buttonMapping.put("confirm", 'C'); buttonMapping.put("back", 'B');
            buttons.put("info", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>{name}"));
            buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Price"));
            buttons.put("confirm", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Purchase"));
            buttons.put("no-money", new HotbarButtonConfig(Material.RED_WOOL, "<red>Not Enough Money"));
            buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class ShopContainerPurchaseConfirmConfig {
        private String title = "<gold>Purchase Folder: {folder}";
        private String layout = "XXXIXXXXX\nXPXXCXXXX\nXXXXBXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public ShopContainerPurchaseConfirmConfig() {
            buttonMapping.put("info", 'I');
            buttonMapping.put("price", 'P');
            buttonMapping.put("confirm", 'C');
            buttonMapping.put("no-money", 'C');
            buttonMapping.put("back", 'B');
            buttons.put("info", new HotbarButtonConfig(Material.CHEST, "<yellow>{folder}", List.of("<gray>Songs: <yellow>{count}"), 0, false));
            buttons.put("price", new HotbarButtonConfig(Material.GOLD_INGOT, "<gold>Total: <yellow>{price}", List.of("<gray>Your balance: <yellow>{balance}"), 0, false));
            buttons.put("confirm", new HotbarButtonConfig(Material.LIME_WOOL, "<green>Confirm Purchase", List.of("<gray>Buy every disc in this folder"), 0, false));
            buttons.put("no-money", new HotbarButtonConfig(Material.RED_WOOL, "<red>Not Enough Money", List.of("<gray>Need: <yellow>{need}"), 0, false));
            buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back"));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class EditMenuConfig {
        private String title = "<gold>Music Editor Menu";
        private String layout = "XXXCXXXXX\nXSXDXLXXX\nXXXXRXXXX\nXXXXBXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public EditMenuConfig() {
            buttonMapping.put("create", 'C'); buttonMapping.put("select", 'S'); buttonMapping.put("delete", 'D'); buttonMapping.put("rename", 'R'); buttonMapping.put("list", 'L'); buttonMapping.put("import", 'I'); buttonMapping.put("export", 'E'); buttonMapping.put("back", 'B');
            buttons.put("create", new HotbarButtonConfig(Material.EMERALD, "<green>Create Music", List.of("<gray>Create a new custom song"), 0, false));
            buttons.put("select", new HotbarButtonConfig(Material.NOTE_BLOCK, "<yellow>Select Music", List.of("<gray>Open and edit an existing custom song"), 0, false));
            buttons.put("delete", new HotbarButtonConfig(Material.REDSTONE, "<red>Delete Music", List.of("<gray>Delete one of your custom songs"), 0, false));
            buttons.put("rename", new HotbarButtonConfig(Material.NAME_TAG, "<gold>Rename Music", List.of("<gray>Change a custom song name"), 0, false));
            buttons.put("list", new HotbarButtonConfig(Material.BOOK, "<aqua>My Music List", List.of("<gray>Browse all of your custom songs"), 0, false));
            buttons.put("import", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<light_purple>Import Music File", List.of("<gray>Import an NBS or MIDI file"), 0, false));
            buttons.put("export", new HotbarButtonConfig(Material.MUSIC_DISC_11, "<aqua>Export as NBS", List.of("<gray>Export one of your songs"), 0, false));
            buttons.put("back", new HotbarButtonConfig(Material.BARRIER, "<red>Back", new ArrayList<>(), 0, false));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class PastePreviewConfig {
        private String title = "<gold>Paste Preview: {count} Notes";
        private String layout = "XXXXIXXXX\nXXXXCXXXX\nXXXXBXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public PastePreviewConfig() {
            buttonMapping.put("info", 'I'); buttonMapping.put("confirm", 'C'); buttonMapping.put("cancel", 'B');
            buttons.put("info", new HotbarButtonConfig(Material.BOOK, "<yellow>Preview", List.of("<gray>Review the pasted note count"), 0, false));
            buttons.put("confirm", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Confirm Paste", List.of("<gray>Paste notes into the current song"), 0, false));
            buttons.put("cancel", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Cancel", List.of("<gray>Discard this paste operation"), 0, false));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class ClearConfirmConfig {
        private final Map<String, Integer> buttonMappings = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public ClearConfirmConfig() {
            buttonMappings.put("confirm", 0); buttonMappings.put("info", 4); buttonMappings.put("cancel", 8);
            buttons.put("info", new HotbarButtonConfig(Material.BOOK, "<yellow>Warning", Arrays.asList("<gray>This will remove <red>{count} <gray>notes", "<red>This action cannot be undone!"), 0, false));
            buttons.put("confirm", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Confirm Clear", List.of("<gray>Delete the selected notes"), 0, false));
            buttons.put("cancel", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Cancel", List.of("<gray>Keep the current notes"), 0, false));
        }
        public Map<String, Integer> getButtonMappings() { return this.buttonMappings; }
        public Map<String, HotbarButtonConfig> getButtons() { return this.buttons; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Integer slot = buttonMappings.get(buttonType); return slot != null ? slot : -1; }
    }

    public static class DeleteConfirmConfig {
        private String title = "<red>Confirm delete: {name}</red>";
        private String layout = "XXXXIXXXX\nXXCXXXBXX\nXXXXXXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public DeleteConfirmConfig() {
            buttonMapping.put("warning", 'I');
            buttonMapping.put("confirm", 'C');
            buttonMapping.put("cancel", 'B');
            buttons.put("warning", new HotbarButtonConfig(Material.RED_TERRACOTTA, "<red>Delete </red><yellow>{name}</yellow><red>?</red>", List.of("<gray>This action cannot be undone</gray>"), 0, false));
            buttons.put("confirm", new HotbarButtonConfig(Material.GREEN_CONCRETE, "<green>Confirm</green>", List.of("<gray>Delete this item</gray>"), 0, false));
            buttons.put("cancel", new HotbarButtonConfig(Material.RED_CONCRETE, "<red>Cancel</red>", List.of("<gray>Keep this item</gray>"), 0, false));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class ExitConfirmConfig {
        private String title = "<red>Unsaved Changes";
        private String layout = "XXXXXXXXX\nXISXDXXXX\nXXXXCXXXX";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();
        public ExitConfirmConfig() {
            buttonMapping.put("info", 'I'); buttonMapping.put("save", 'S'); buttonMapping.put("discard", 'D'); buttonMapping.put("cancel", 'C');
            buttons.put("info", new HotbarButtonConfig(Material.PAPER, "<yellow>{name}", Arrays.asList("<gray>You have unsaved changes", "<gray>Choose what to do before exiting"), 0, false));
            buttons.put("save", new HotbarButtonConfig(Material.LIME_STAINED_GLASS_PANE, "<green>Save and Exit", List.of("<gray>Save changes before closing"), 0, false));
            buttons.put("discard", new HotbarButtonConfig(Material.RED_STAINED_GLASS_PANE, "<red>Discard Changes", List.of("<gray>Close without saving"), 0, false));
            buttons.put("cancel", new HotbarButtonConfig(Material.GRAY_STAINED_GLASS_PANE, "<gray>Cancel", List.of("<gray>Return to the editor"), 0, false));
        }
        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }

    public static class ShopLoreConfig {
        private List<String> shopLore = new ArrayList<>();
        private List<String> getLore = new ArrayList<>();
        private List<String> searchLore = new ArrayList<>();
        private List<String> recentLore = new ArrayList<>();
        private List<String> containerPlayAllLore = new ArrayList<>();
        private List<String> containerBuyAllLore = new ArrayList<>();

        public ShopLoreConfig() {
            shopLore.add("<gray>Left click to buy this music");
            shopLore.add("<gray>Price: <yellow>{price}");
            getLore.add("<gray>Left click to get this music");
            searchLore.add("<gray>Left click to play");
            recentLore.add("<gray>Left click to play");
            containerPlayAllLore.add("<yellow>Right click to play all");
            containerBuyAllLore.add("<yellow>Right click to buy all");
            containerBuyAllLore.add("<gray>Total: <yellow>{price}");
        }

        public List<String> getShopLore() { return this.shopLore; }
        public List<String> getGetLore() { return this.getLore; }
        public List<String> getSearchLore() { return this.searchLore; }
        public List<String> getRecentLore() { return this.recentLore; }
        public List<String> getContainerPlayAllLore() { return this.containerPlayAllLore; }
        public List<String> getContainerBuyAllLore() { return this.containerBuyAllLore; }

        public void loadFromConfig(ConfigurationSection section) {
            if (section == null) return;
            loadStringList(section, "shop-lore", shopLore);
            loadStringList(section, "get-lore", getLore);
            loadStringList(section, "search-lore", searchLore);
            loadStringList(section, "recent-lore", recentLore);
            loadStringList(section, "container-play-all-lore", containerPlayAllLore);
            loadStringList(section, "container-buy-all-lore", containerBuyAllLore);
        }

        private void loadStringList(ConfigurationSection section, String path, List<String> target) {
            List<String> list = section.getStringList(path);
            if (list != null && !list.isEmpty()) {
                target.clear();
                target.addAll(StringUtils.t(list));
            }
        }
    }

    public static class TextPlayerEditConfig {
        private String title = "<gold>文字播放器</gold> <dark_gray>-</dark_gray> <yellow>{name}</yellow>";
        private String layout = "XXXXIXXXX\nXNSPTXQCX\nXXXXBXXXD";
        private final Map<String, Character> buttonMapping = new HashMap<>();
        private final Map<String, HotbarButtonConfig> buttons = new HashMap<>();

        public TextPlayerEditConfig() {
            buttonMapping.put("info", 'I');
            buttonMapping.put("toggle-name", 'N');
            buttonMapping.put("toggle-song", 'S');
            buttonMapping.put("toggle-progress", 'P');
            buttonMapping.put("toggle-time", 'T');
            buttonMapping.put("choose-song", 'Q');
            buttonMapping.put("control", 'C');
            buttonMapping.put("close", 'B');
            buttonMapping.put("delete", 'D');
            buttons.put("info", new HotbarButtonConfig(Material.PAPER, "<yellow>{name}", Arrays.asList(
                "<gray>当前歌曲: <white>{song}",
                "<gray>右键浮动文字打开控制面板",
                "<gray>潜行右键打开此编辑菜单"
            ), 0, false));
            buttons.put("toggle-name", new HotbarButtonConfig(Material.NAME_TAG, "<yellow>名称: {status}", List.of("<gray>点击切换显示名称"), 0, false));
            buttons.put("toggle-song", new HotbarButtonConfig(Material.MUSIC_DISC_CAT, "<yellow>歌曲: {status}", List.of("<gray>点击切换显示歌曲"), 0, false));
            buttons.put("toggle-progress", new HotbarButtonConfig(Material.COMPARATOR, "<yellow>进度: {status}", List.of("<gray>点击切换显示进度"), 0, false));
            buttons.put("toggle-time", new HotbarButtonConfig(Material.CLOCK, "<yellow>时间: {status}", List.of("<gray>点击切换显示时间"), 0, false));
            buttons.put("choose-song", new HotbarButtonConfig(Material.JUKEBOX, "<green>选择歌曲", List.of("<gray>打开歌曲选择器"), 0, false));
            buttons.put("control", new HotbarButtonConfig(Material.REDSTONE_TORCH, "<gold>控制面板", List.of("<gray>打开播放控制"), 0, false));
            buttons.put("close", new HotbarButtonConfig(Material.BARRIER, "<red>关闭", List.of("<gray>关闭此菜单"), 0, false));
            buttons.put("delete", new HotbarButtonConfig(Material.TNT, "<red>删除", List.of("<gray>删除此文字播放器"), 0, false));
            buttons.put("edit-display", new HotbarButtonConfig(Material.PAPER, "<aqua>编辑显示", List.of("<gray>编辑浮动文字显示行"), 0, false));
        }

        public String getTitle() { return this.title; }
        public String getLayout() { return this.layout; }
        public Map<String, Character> getButtonMapping() { return this.buttonMapping; }
        public HotbarButtonConfig getButton(String key) { return buttons.get(key); }
        public int getSlotForButton(String buttonType) { Character c = buttonMapping.get(buttonType); return c == null ? -1 : getSlotForChar(c); }
        public int getSlotForChar(char c) { return GUIConfigManager.getSlotForChar(layout, c); }
    }
}
