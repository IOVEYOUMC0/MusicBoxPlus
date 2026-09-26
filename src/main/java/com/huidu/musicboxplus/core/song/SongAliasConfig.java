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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
        // `songs` is a list of entries, each carrying its own `name`.
        //
        // It cannot be a mapping keyed by the song name. Bukkit's configuration API treats "." as a
        // path separator and offers no escape for it: MemorySection.set routes a Map value through
        // mapChildrenValues, which calls createSection(key), and loading a file does the same via
        // convertMapsToSections. So a name containing a dot is split on the way in *and* on the way
        // out -- "Song v1.0" becomes songs -> "song v1" -> "0" -- and the entry can never be read
        // back under its own name. A sequence has no keys to split, so the names survive verbatim.
        if (this.config.isList("songs")) {
            for (Map<?, ?> entry : this.config.getMapList("songs")) {
                String name = stringField(entry, "name");
                if (name == null || name.isEmpty()) {
                    continue;
                }
                this.songDataMap.put(name.toLowerCase(), dataFrom(entry));
            }
        } else {
            this.loadLegacySongsSection();
        }
        this.plugin.getLogger().info(LogLocale.text(this.plugin, "Loaded " + this.songDataMap.size() + " song configs", "已加载 " + this.songDataMap.size() + " 个歌曲配置"));
    }

    // The mapping form this file used before the list: `songs: { <name>: { aliases: [...] } }`.
    //
    // Still read so an existing file is not thrown away, and it recovers the entries the old format
    // had already lost. A dotted name was split into nested sections, so the pieces are still in the
    // file -- "song v1" -> "0" -- and joining them with the separator the writer split on
    // reconstructs the name. That is the one place the old encoding is reversible.
    private void loadLegacySongsSection() {
        ConfigurationSection songsSection = this.config.getConfigurationSection("songs");
        if (songsSection == null) {
            return;
        }
        for (String key : songsSection.getKeys(false)) {
            ConfigurationSection section = songsSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            String name = key;
            // Descend while this level holds no fields of its own and exactly one nested section:
            // that nesting is a name that was split, not a real structure.
            while (!hasFields(section) && section.getKeys(false).size() == 1) {
                String child = section.getKeys(false).iterator().next();
                ConfigurationSection childSection = section.getConfigurationSection(child);
                if (childSection == null) {
                    break;
                }
                name = name + "." + child;
                section = childSection;
            }
            if (!hasFields(section)) {
                continue;
            }
            this.songDataMap.put(name.toLowerCase(), new SongAliasData(
                    section.getStringList("aliases"),
                    section.getStringList("tags"),
                    section.getString("jukebox-playable", null),
                    section.getInt("custom-model-data", 0),
                    section.getString("custom-material", null),
                    section.getString("item-model", null),
                    section.getString("craft-engine-item", null)));
        }
    }

    private static boolean hasFields(ConfigurationSection section) {
        return section.isSet("aliases") || section.isSet("tags") || section.isSet("jukebox-playable")
                || section.isSet("custom-model-data") || section.isSet("custom-material")
                || section.isSet("item-model") || section.isSet("craft-engine-item");
    }

    private static String stringField(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> stringListField(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        List<String> list = new ArrayList<>();
        if (value instanceof List<?> raw) {
            for (Object element : raw) {
                if (element != null) {
                    list.add(String.valueOf(element));
                }
            }
        }
        return list;
    }

    private static int intField(Map<?, ?> entry, String key) {
        Object value = entry.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static SongAliasData dataFrom(Map<?, ?> entry) {
        return new SongAliasData(
                stringListField(entry, "aliases"),
                stringListField(entry, "tags"),
                stringField(entry, "jukebox-playable"),
                intField(entry, "custom-model-data"),
                stringField(entry, "custom-material"),
                stringField(entry, "item-model"),
                stringField(entry, "craft-engine-item"));
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

    // Rewrites the whole `songs` block from the in-memory map.
    //
    // A list of entries rather than a mapping keyed by song name, because the configuration API
    // splits mapping keys on "." -- see loadSongData. Each entry carries its own `name`, so the name
    // is stored verbatim and a file written here is readable by this class whatever the name is.
    // This also collapses N per-field config writes into one.
    public void saveConfig() {
        List<Map<String, Object>> songs = new ArrayList<>(this.songDataMap.size());
        for (Map.Entry<String, SongAliasData> entry : this.songDataMap.entrySet()) {
            SongAliasData data = entry.getValue();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("name", entry.getKey());
            fields.put("aliases", new ArrayList<>(data.getAliases()));
            fields.put("tags", new ArrayList<>(data.getTags()));
            // Null fields are omitted rather than written as explicit nulls, which matches what the
            // previous "set every field so that clearing persists" rule produced: a null value
            // removed the key.
            putIfPresent(fields, "jukebox-playable", data.getJukeboxPlayable());
            fields.put("custom-model-data", data.getCustomModelData());
            putIfPresent(fields, "custom-material", data.getCustomMaterial());
            putIfPresent(fields, "item-model", data.getItemModel());
            putIfPresent(fields, "craft-engine-item", data.getCraftEngineItem());
            songs.add(fields);
        }
        this.config.set("songs", songs);
        this.writeAtomically();
    }

    private static void putIfPresent(Map<String, Object> fields, String key, String value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    // Temp file + atomic move, like the other files this plugin rewrites in place. A plain
    // config.save(file) truncates the real file first, so a crash or a full disk mid-write took every
    // alias, tag and item override in the library with it.
    private void writeAtomically() {
        File temp = new File(this.configFile.getParentFile(), this.configFile.getName() + ".tmp");
        try {
            this.config.save(temp);
            try {
                Files.move(temp.toPath(), this.configFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), this.configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            temp.delete();
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
        // The in-memory map is already updated by the caller and is the source of truth, so the whole
        // block is rewritten from it; see saveConfig for why the song name must not end up in a path.
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
