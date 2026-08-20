package com.huidu.musicboxplus.core.song;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SongAliasConfig {
    private static SongAliasConfig instance;
    private final Map<String, SongAliasData> songDataMap = new HashMap<String, SongAliasData>();
    private final MusicBox plugin = MusicBox.getInstance();
    private File configFile;
    private FileConfiguration config;

    public static SongAliasConfig getInstance() {
        if (instance == null) {
            instance = new SongAliasConfig();
        }
        return instance;
    }

    private SongAliasConfig() {
    }

    public void loadConfig() {
        this.configFile = new File(this.plugin.getDataFolder(), "song-aliases.yml");
        if (!this.configFile.exists() && StorageAccess.canWriteTo(this.configFile)) {
            this.plugin.saveResource("song-aliases.yml", false);
        } else if (!this.configFile.exists()) {
            this.plugin.getLogger().warning(LogLocale.text(this.plugin,
                "Song alias config folder is not writable, loading bundled defaults in memory",
                "歌曲别名配置目录不可写，将以内存方式加载内置默认值"));
        }
        if (this.configFile.exists()) {
            this.config = YamlConfiguration.loadConfiguration(this.configFile);
        } else {
            this.config = new YamlConfiguration();
        }
        try (InputStream defaultStream = this.plugin.getResource("song-aliases.yml")) {
            if (defaultStream != null) {
                YamlConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
                this.config.setDefaults(defaultConfig);
                if (!this.configFile.exists()) {
                    this.config = defaultConfig;
                }
            }
        } catch (IOException e) {
            this.plugin.getLogger().warning(LogLocale.text(this.plugin,
                "Failed to load bundled song alias defaults: " + e.getMessage(),
                "加载内置歌曲别名默认配置失败: " + e.getMessage()));
        }
        this.songDataMap.clear();
        this.loadSongData();
    }

    private void loadSongData() {
        ConfigurationSection songsSection = this.config.getConfigurationSection("songs");
        if (songsSection == null) {
            return;
        }
        for (String songName : songsSection.getKeys(false)) {
            ConfigurationSection songSection = songsSection.getConfigurationSection(songName);
            if (songSection == null) continue;
            List<String> aliases = songSection.getStringList("aliases");
            List<String> tags = songSection.getStringList("tags");
            String jukeboxPlayable = songSection.getString("jukebox-playable", null);
            int customModelData = songSection.getInt("custom-model-data", 0);
            String customMaterial = songSection.getString("custom-material", null);
            String itemModel = songSection.getString("item-model", null);
            String craftEngineItem = songSection.getString("craft-engine-item", null);
            this.songDataMap.put(songName.toLowerCase(), new SongAliasData(aliases, tags, jukeboxPlayable, customModelData, customMaterial, itemModel, craftEngineItem));
        }
        this.plugin.getLogger().info(LogLocale.text(this.plugin, "Loaded " + this.songDataMap.size() + " song configs", "已加载 " + this.songDataMap.size() + " 个歌曲配置"));
    }

    public void applyToAllSongs() {
        int appliedCount = 0;
        for (MusicBoxSong song : MusicBoxSongManager.getAllSongs()) {
            song.resetAliasMetadata();
            String songName = song.getName().toLowerCase();
            SongAliasData data = this.songDataMap.get(songName);
            if (data == null) {
                continue;
            }
            song.addAliases(data.getAliases());
            song.addTags(data.getTags());
            song.setJukeboxPlayable(data.getJukeboxPlayable());
            song.setCustomModelData(data.getCustomModelData());
            song.setCustomMaterial(data.getCustomMaterial());
            song.setItemModel(data.getItemModel());
            song.setCraftEngineItem(data.getCraftEngineItem());
            ++appliedCount;
        }
        this.plugin.getLogger().info(LogLocale.text(this.plugin, "Applied custom song config to " + appliedCount + " songs", "已为 " + appliedCount + " 首歌曲应用自定义配置"));
    }

    public void saveConfig() {
        try {
            this.config.save(this.configFile);
        }
        catch (IOException e) {
            this.plugin.getLogger().severe(LogLocale.text(this.plugin, "Failed to save song alias config: " + e.getMessage(), "无法保存歌曲别名配置: " + e.getMessage()));
        }
    }

    public void addSongAlias(String songName, String alias) {
        songName = songName.toLowerCase();
        SongAliasData data = this.songDataMap.computeIfAbsent(songName, k -> new SongAliasData());
        data.addAlias(alias);
        MusicBoxSongManager.findByName(songName).ifPresent(song -> song.addAlias(alias));
        this.saveSongData(songName, data);
        MusicBoxSongManager.refreshSearchIndex();
    }

    public void removeSongAlias(String songName, String alias) {
        SongAliasData data = this.songDataMap.get(songName = songName.toLowerCase());
        if (data != null) {
            data.removeAlias(alias);
            MusicBoxSongManager.findByName(songName).ifPresent(song -> song.removeAlias(alias));
            this.saveSongData(songName, data);
            MusicBoxSongManager.refreshSearchIndex();
        }
    }

    public void addSongTag(String songName, String tag) {
        songName = songName.toLowerCase();
        SongAliasData data = this.songDataMap.computeIfAbsent(songName, k -> new SongAliasData());
        data.addTag(tag);
        MusicBoxSongManager.findByName(songName).ifPresent(song -> song.addTag(tag));
        this.saveSongData(songName, data);
        MusicBoxSongManager.refreshSearchIndex();
    }

    public void setSongJukeboxPlayable(String songName, String jukeboxPlayable) {
        songName = songName.toLowerCase();
        SongAliasData data = this.songDataMap.computeIfAbsent(songName, k -> new SongAliasData());
        data.setJukeboxPlayable(jukeboxPlayable);
        MusicBoxSongManager.findByName(songName).ifPresent(song -> song.setJukeboxPlayable(jukeboxPlayable));
        this.saveSongData(songName, data);
    }

    public void setSongCustomModelData(String songName, int customModelData) {
        songName = songName.toLowerCase();
        SongAliasData data = this.songDataMap.computeIfAbsent(songName, k -> new SongAliasData());
        data.setCustomModelData(customModelData);
        MusicBoxSongManager.findByName(songName).ifPresent(song -> song.setCustomModelData(customModelData));
        this.saveSongData(songName, data);
    }

    public void setSongCustomMaterial(String songName, String customMaterial) {
        songName = songName.toLowerCase();
        SongAliasData data = this.songDataMap.computeIfAbsent(songName, k -> new SongAliasData());
        data.setCustomMaterial(customMaterial);
        MusicBoxSongManager.findByName(songName).ifPresent(song -> song.setCustomMaterial(customMaterial));
        this.saveSongData(songName, data);
    }

    public void setSongItemModel(String songName, String itemModel) {
        songName = songName.toLowerCase();
        SongAliasData data = this.songDataMap.computeIfAbsent(songName, k -> new SongAliasData());
        data.setItemModel(itemModel);
        MusicBoxSongManager.findByName(songName).ifPresent(song -> song.setItemModel(itemModel));
        this.saveSongData(songName, data);
    }

    public void setSongCraftEngineItem(String songName, String craftEngineItem) {
        songName = songName.toLowerCase();
        SongAliasData data = this.songDataMap.computeIfAbsent(songName, k -> new SongAliasData());
        data.setCraftEngineItem(craftEngineItem);
        MusicBoxSongManager.findByName(songName).ifPresent(song -> song.setCraftEngineItem(craftEngineItem));
        this.saveSongData(songName, data);
    }

    public void removeSongTag(String songName, String tag) {
        SongAliasData data = this.songDataMap.get(songName = songName.toLowerCase());
        if (data != null) {
            data.removeTag(tag);
            MusicBoxSongManager.findByName(songName).ifPresent(song -> song.removeTag(tag));
            this.saveSongData(songName, data);
            MusicBoxSongManager.refreshSearchIndex();
        }
    }

    private void saveSongData(String songName, SongAliasData data) {
        String path = "songs." + songName;
        this.config.set(path + ".aliases", data.getAliases());
        this.config.set(path + ".tags", data.getTags());
        // Always set every field (null removes the key in Bukkit's YamlConfiguration) so that
        // clearing a value actually persists — the old "only write when non-null" left a stale
        // value in the file, which is why unset didn't work.
        this.config.set(path + ".jukebox-playable", data.getJukeboxPlayable());
        this.config.set(path + ".custom-model-data", data.getCustomModelData());
        this.config.set(path + ".custom-material", data.getCustomMaterial());
        this.config.set(path + ".item-model", data.getItemModel());
        this.config.set(path + ".craft-engine-item", data.getCraftEngineItem());
        this.saveConfig();
    }

    public Map<String, SongAliasData> getSongDataMap() {
        return this.songDataMap;
    }

    public static class SongAliasData {
        private final List<String> aliases;
        private final List<String> tags;
        private String jukeboxPlayable;
        private int customModelData;
        private String customMaterial;
        private String itemModel;
        private String craftEngineItem;

        public SongAliasData() {
            this.aliases = new ArrayList<String>();
            this.tags = new ArrayList<String>();
            this.jukeboxPlayable = null;
            this.customModelData = 0;
            this.customMaterial = null;
            this.itemModel = null;
            this.craftEngineItem = null;
        }

        public SongAliasData(List<String> aliases, List<String> tags, String jukeboxPlayable, int customModelData, String customMaterial) {
            this(aliases, tags, jukeboxPlayable, customModelData, customMaterial, null);
        }

        public SongAliasData(List<String> aliases, List<String> tags, String jukeboxPlayable, int customModelData, String customMaterial, String itemModel) {
            this(aliases, tags, jukeboxPlayable, customModelData, customMaterial, itemModel, null);
        }

        public SongAliasData(List<String> aliases, List<String> tags, String jukeboxPlayable, int customModelData, String customMaterial, String itemModel, String craftEngineItem) {
            this.aliases = new ArrayList<String>(aliases != null ? aliases : Collections.emptyList());
            this.tags = new ArrayList<String>(tags != null ? tags : Collections.emptyList());
            this.jukeboxPlayable = jukeboxPlayable;
            this.customModelData = customModelData;
            this.customMaterial = customMaterial;
            this.itemModel = itemModel;
            this.craftEngineItem = craftEngineItem;
        }

        public void addAlias(String alias) {
            if (alias != null && !alias.trim().isEmpty() && !this.aliases.contains(alias.toLowerCase())) {
                this.aliases.add(alias.toLowerCase());
            }
        }

        public void removeAlias(String alias) {
            if (alias != null) {
                this.aliases.remove(alias.toLowerCase());
            }
        }

        public void addTag(String tag) {
            if (tag != null && !tag.trim().isEmpty() && !this.tags.contains(tag.toLowerCase())) {
                this.tags.add(tag.toLowerCase());
            }
        }

        public void removeTag(String tag) {
            if (tag != null) {
                this.tags.remove(tag.toLowerCase());
            }
        }

        public List<String> getAliases() {
            return this.aliases;
        }

        public List<String> getTags() {
            return this.tags;
        }

        public String getJukeboxPlayable() {
            return this.jukeboxPlayable;
        }

        public int getCustomModelData() {
            return this.customModelData;
        }

        public String getCustomMaterial() {
            return this.customMaterial;
        }

        public String getItemModel() {
            return this.itemModel;
        }

        public String getCraftEngineItem() {
            return this.craftEngineItem;
        }

        public void setJukeboxPlayable(String jukeboxPlayable) {
            this.jukeboxPlayable = jukeboxPlayable;
        }

        public void setCustomModelData(int customModelData) {
            this.customModelData = customModelData;
        }

        public void setCustomMaterial(String customMaterial) {
            this.customMaterial = customMaterial;
        }

        public void setItemModel(String itemModel) {
            this.itemModel = itemModel;
        }

        public void setCraftEngineItem(String craftEngineItem) {
            this.craftEngineItem = craftEngineItem;
        }
    }
}
