package com.huidu.musicboxplus;

import com.huidu.musicboxplus.common.utils.YamlSupportUtils;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.BaseConstructor;
import org.yaml.snakeyaml.introspector.BeanAccess;
import org.yaml.snakeyaml.introspector.PropertyUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Main plugin configuration; the field names below are the config.yml keys.
public class MusicBoxConfig {
    private String soundCategory = "RECORDS";
    private boolean blockMainThreadDb = true;
    private boolean convertMidiToNbs = true;

    private EconomySetting economy;
    private BossBarSetting bossbar;
    private int speakerRadius = 10;
    private int jukeboxRadius = 64;
    private int maxRecentSongs = 28;
    private int autoDestroy = 60;
    private int playDelayTicks = 0;
    private boolean bStats = true;
    private boolean debug = false;
    private boolean hearPermissionsCheck = false;
    private boolean blockPlayerControlPermission = false;
    private boolean enable10octave = false;
    private boolean enableTrumpetResourcePackFallback = false;
    private DatabaseSetting database;
    private CacheSetting cache;
    private PublishConfig publish;
    private ModuleConfig modules;
    private CustomRecordConfig customRecords;
    private EditorConfig editor;
    private StorageConfig storage;
    private VolumeConfig volume;
    private SpeedConfig speed;
    private PlayerConfig player;
    private SearchConfig search;
    private ResourcePackInstrumentConfig resourcePackInstruments;
    private String language = "en";
    private boolean languageAutoFillMissing = true;
    private WebConfig web;
    private PerformanceConfig performance;
    private AutoPlayConfig autoPlay;

    public static MusicBoxConfig createDefault() {
        MusicBoxConfig config = new MusicBoxConfig();
        config.economy = new EconomySetting();
        config.bossbar = new BossBarSetting();
        config.speakerRadius = 10;
        config.jukeboxRadius = 64;
        config.maxRecentSongs = 28;
        config.autoDestroy = 60;
        config.bStats = true;
        config.debug = false;
        config.enableTrumpetResourcePackFallback = false;
        config.database = new DatabaseSetting();
        config.cache = new CacheSetting();
        config.publish = new PublishConfig();
        config.modules = new ModuleConfig();
        config.customRecords = new CustomRecordConfig();
        config.editor = new EditorConfig();
        config.storage = new StorageConfig();
        config.volume = new VolumeConfig();
        config.speed = new SpeedConfig();
        config.player = new PlayerConfig();
        config.search = new SearchConfig();
        config.resourcePackInstruments = new ResourcePackInstrumentConfig();
        config.language = "en";
        config.languageAutoFillMissing = true;
        config.web = new WebConfig();
        config.performance = new PerformanceConfig();
        config.autoPlay = new AutoPlayConfig();
        return config;
    }

public static class ConfigParseException extends RuntimeException {
    public ConfigParseException(String message) {
        super(message);
    }
    
    public ConfigParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

public static MusicBoxConfig parseConfig(InputStream yamlStream) {
    BaseConstructor constructor = YamlSupportUtils.createCustomClassLoaderConstructor();
    PropertyUtils propertyUtils = new PropertyUtils();
    propertyUtils.setSkipMissingProperties(true);
    constructor.setPropertyUtils(propertyUtils);
    Yaml yaml = new Yaml(constructor);
    yaml.setBeanAccess(BeanAccess.FIELD);
    try (InputStream is = yamlStream) {
        MusicBoxConfig config = yaml.loadAs(is, MusicBoxConfig.class);
        if (config == null) {
            return createDefault();
        }
        config.applyDefaults();
        config.validate();
        return config;
    }
    catch (Exception e) {
        throw new ConfigParseException("配置解析失败: " + e.getMessage(), e);
    }
}
    
    private void applyDefaults() {
        if (economy == null) economy = new EconomySetting();
        if (bossbar == null) bossbar = new BossBarSetting();
        if (database == null) database = new DatabaseSetting();
        if (cache == null) cache = new CacheSetting();
        if (publish == null) publish = new PublishConfig();
        if (modules == null) modules = new ModuleConfig();
        if (customRecords == null) customRecords = new CustomRecordConfig();
        if (editor == null) editor = new EditorConfig();
        if (storage == null) storage = new StorageConfig();
        if (volume == null) volume = new VolumeConfig();
        if (speed == null) speed = new SpeedConfig();
        if (player == null) player = new PlayerConfig();
        if (search == null) search = new SearchConfig();
        if (resourcePackInstruments == null) resourcePackInstruments = new ResourcePackInstrumentConfig();
        if (language == null || language.trim().isEmpty()) language = "en";
        if (web == null) web = new WebConfig();
        if (performance == null) performance = new PerformanceConfig();
        if (autoPlay == null) autoPlay = new AutoPlayConfig();
    }
    
    // Out-of-range values are clamped rather than rejected, so a bad config never bricks startup.
    private void validate() {
        if (speakerRadius <= 0) speakerRadius = 16;
        if (jukeboxRadius <= 0) jukeboxRadius = 16;
        if (maxRecentSongs <= 0) maxRecentSongs = 10;
        if (maxRecentSongs > 100) maxRecentSongs = 100;
        if (playDelayTicks < 0) playDelayTicks = 0;
        if (playDelayTicks > 100) playDelayTicks = 100;
        if (performance.databaseBatchSize <= 0) performance.databaseBatchSize = 50;
        if (performance.databaseBatchSize > 1000) performance.databaseBatchSize = 1000;
        if (performance.maxKeywordIndexSize <= 0) performance.maxKeywordIndexSize = 10000;
        if (performance.textDisplayRefreshIntervalTicks <= 0) performance.textDisplayRefreshIntervalTicks = 1;
        if (performance.textDisplayRefreshIntervalTicks > 100) performance.textDisplayRefreshIntervalTicks = 100;
    }

    public EconomySetting getEconomy() {
        return this.economy;
    }

    public BossBarSetting getBossbar() {
        return this.bossbar;
    }

    public int getSpeakerRadius() {
        return this.speakerRadius;
    }

    public int getJukeboxRadius() {
        return this.jukeboxRadius;
    }

    public int getMaxRecentSongs() {
        return this.maxRecentSongs;
    }

    public int getAutoDestroy() {
        return this.autoDestroy;
    }

    public String getSoundCategory() {
        return this.soundCategory;
    }

    public int getPlayDelayTicks() {
        return this.playDelayTicks;
    }

    public boolean isBStats() {
        return this.bStats;
    }

    public boolean isDebug() {
        return this.debug;
    }

    public boolean isBlockMainThreadDb() {
        return this.blockMainThreadDb;
    }

    public boolean isHearPermissionsCheck() {
        return this.hearPermissionsCheck;
    }

    public boolean isBlockPlayerControlPermission() {
        return this.blockPlayerControlPermission;
    }

    public boolean isEnable10octave() {
        return this.enable10octave;
    }

    public boolean isConvertMidiToNbs() {
        return this.convertMidiToNbs;
    }

    public boolean isEnableTrumpetResourcePackFallback() {
        return this.enableTrumpetResourcePackFallback;
    }

    public DatabaseSetting getDatabase() {
        return this.database;
    }

    public CacheSetting getCache() {
        return this.cache;
    }

    public PublishConfig getPublishConfig() {
        return this.publish;
    }

    public ModuleConfig getModules() {
        return this.modules;
    }

    public CustomRecordConfig getCustomRecords() {
        return this.customRecords;
    }

    public EditorConfig getEditor() {
        return this.editor;
    }

    public StorageConfig getStorage() {
        return this.storage;
    }

    public VolumeConfig getVolume() {
        return this.volume;
    }

    public AutoPlayConfig getAutoPlay() {
        return this.autoPlay;
    }

    public SpeedConfig getSpeed() {
        return this.speed;
    }

    public PlayerConfig getPlayer() {
        return this.player;
    }

    public SearchConfig getSearch() {
        return this.search;
    }

    public ResourcePackInstrumentConfig getResourcePackInstruments() {
        return this.resourcePackInstruments;
    }

    public String getLanguage() {
        return this.language;
    }

    public boolean isLanguageAutoFillMissing() {
        return this.languageAutoFillMissing;
    }

    public WebConfig getWeb() {
        return this.web;
    }

    public PerformanceConfig getPerformance() {
        return this.performance;
    }

    private MusicBoxConfig() {
    }

    public static class EconomySetting {
        private boolean enable = false;
        private double price = 500;

        public boolean isEnable() {
            return this.enable;
        }

        public double getPrice() {
            return this.price;
        }

        private EconomySetting() {
        }
    }

    public static class BossBarSetting {
        private String format = "<green>{song}</green><gray> [{mode}]</gray>";

        private boolean enable = true;
        private String color = "BLUE";
        private String style = "SEGMENTED_20";
        private List<String> flags = new ArrayList<>();

        public boolean isEnable() {
            return this.enable;
        }

        public String getColor() {
            return this.color;
        }

        public String getStyle() {
            return this.style;
        }

        public List<String> getFlags() {
            return this.flags;
        }

        public String getFormat() {
            return this.format;
        }

        private BossBarSetting() {
        }
    }

    // No SignSetting class here on purpose: the sign: section is read straight from the YAML by
    // ConfigManager, which is what isValidSignAlias and the sign text actually consult. A second
    // parsed copy nobody reads is worse than none -- it looks authoritative and silently disagrees.

    public static class DatabaseSetting {
        private String type = "sqlite";

        private MySQLSetting mysql;

        public String getType() {
            return this.type;
        }

        public MySQLSetting getMysql() {
            return this.mysql;
        }

        private DatabaseSetting() {
        }
    }

    public static class MySQLSetting {
        private String host = "localhost";
        private int port = 3306;
        private String database = "musicboxplus";
        private String username = "root";
        private String password = "password";
        private String tablePrefix = "musicbox_";

        public String getHost() {
            return this.host;
        }

        public int getPort() {
            return this.port;
        }

        public String getDatabase() {
            return this.database;
        }

        public String getUsername() {
            return this.username;
        }

        public String getPassword() {
            return this.password;
        }

        public String getTablePrefix() {
            return this.tablePrefix;
        }

        private MySQLSetting() {
        }
    }

    public static class CacheSetting {
        private int searchCacheSize = 100;
        private int guiItemCacheSize = 200;
        private int guiLoreCacheSize = 200;
        private int compiledSongCacheSize = 64;

        public int getSearchCacheSize() {
            return this.searchCacheSize;
        }

        public int getGuiItemCacheSize() {
            return this.guiItemCacheSize;
        }

        public int getGuiLoreCacheSize() {
            return this.guiLoreCacheSize;
        }

        private CacheSetting() {
        }
        public int getCompiledSongCacheSize() {
            return this.compiledSongCacheSize;
        }

    }

    public static class PublishConfig {
        private boolean enable = true;
        private double minPrice = 0;
        private double maxPrice = 1000000;
        private double taxRate = 0.1;
        private int maxPublishedPerPlayer = 50;
        private boolean requireApproval = false;
        private boolean allowAuthorClaimOwnMusic = true;
        private boolean authorClaimOwnMusicOnce = true;

        public boolean isEnable() {
            return this.enable;
        }

        public double getMinPrice() {
            return this.minPrice;
        }

        public double getMaxPrice() {
            return this.maxPrice;
        }

        // Clamped to [0,1] on read so a misconfigured value can't make author revenue
        // negative (taxRate > 1) or pay out more than the buyer paid (taxRate < 0).
        public double getTaxRate() {
            return Math.max(0.0, Math.min(1.0, this.taxRate));
        }

        public int getMaxPublishedPerPlayer() {
            return this.maxPublishedPerPlayer;
        }

        public boolean isRequireApproval() {
            return this.requireApproval;
        }

        public boolean isAllowAuthorClaimOwnMusic() {
            return this.allowAuthorClaimOwnMusic;
        }

        public boolean isAuthorClaimOwnMusicOnce() {
            return this.authorClaimOwnMusicOnce;
        }

        private PublishConfig() {
        }
    }

    public static class ModuleConfig {
        private boolean playback = true;
        private boolean shop = true;
        private boolean playerMusic = true;
        // Heavy creation/economy features default OFF — opt in per server.
        private boolean publish = false;
        private boolean editor = false;
        private boolean webEditor = false;
        private boolean signs = true;
        private boolean jukeboxes = true;
        private boolean playlists = true;
        private boolean give = true;
        private boolean textPlayer = true;
        private boolean songTags = true;

        public boolean isPlayback() {
            return this.playback;
        }

        public boolean isShop() {
            return this.shop;
        }

        public boolean isPlayerMusic() {
            return this.playerMusic;
        }

        public boolean isPublish() {
            return this.publish;
        }

        public boolean isEditor() {
            return this.editor;
        }

        public boolean isWebEditor() {
            return this.webEditor;
        }

        public boolean isSigns() {
            return this.signs;
        }

        public boolean isJukeboxes() {
            return this.jukeboxes;
        }

        public boolean isPlaylists() {
            return this.playlists;
        }

        public boolean isGive() {
            return this.give;
        }

        public boolean isTextPlayer() {
            return this.textPlayer;
        }

        public boolean isSongTags() {
            return this.songTags;
        }

        private ModuleConfig() {
        }
    }

    public static class CustomRecordConfig {
        private boolean enabled = false;
        private boolean vanillaJukeboxPlayback = false;
        private String defaultNamespace = "musicboxplus";

        public boolean isEnabled() {
            return this.enabled;
        }

        public boolean isVanillaJukeboxPlayback() {
            return this.vanillaJukeboxPlayback;
        }

        public String getDefaultNamespace() {
            return this.defaultNamespace;
        }

        private CustomRecordConfig() {
        }
    }

    public static class EditorConfig {
        private int defaultMaxPitch = 24;
        private int extendedMaxPitch = 119;
        private int minBpm = 20;
        private int maxBpm = 300;
        private int bpmStep = 5;
        private int instrumentsPerPage = 27;
        private int maxHistorySize = 50;
        private int defaultLimit = 10;
        private int defaultBpm = 120;
        private int defaultBeatSubdivision = 4;
        private long autoSaveInterval = 6000L;

        public int getDefaultMaxPitch() {
            return this.defaultMaxPitch;
        }

        public int getExtendedMaxPitch() {
            return this.extendedMaxPitch;
        }

        public int getMinBpm() {
            return this.minBpm;
        }

        public int getMaxBpm() {
            return this.maxBpm;
        }

        public int getBpmStep() {
            return this.bpmStep;
        }

        public int getInstrumentsPerPage() {
            return this.instrumentsPerPage;
        }

        public int getMaxHistorySize() {
            return this.maxHistorySize;
        }

        public int getDefaultLimit() {
            return this.defaultLimit;
        }

        public int getDefaultBpm() {
            return this.defaultBpm;
        }

        public int getDefaultBeatSubdivision() {
            return this.defaultBeatSubdivision;
        }

        public long getAutoSaveInterval() {
            return this.autoSaveInterval;
        }

        private EditorConfig() {
        }
    }

    public static class StorageConfig {
        private String publishedMusicFolder = "PublishedMusic";
        private String inventoryBackupFolder = "inventory_backups";

        public String getPublishedMusicFolder() {
            return this.publishedMusicFolder;
        }

        public String getInventoryBackupFolder() {
            return this.inventoryBackupFolder;
        }

        private StorageConfig() {
        }
    }

    public static class VolumeConfig {
        private int defaultVolume = 100;
        private int step = 10;
        private int minVolume = 0;
        private int maxVolume = 100;
        private long autoSaveInterval = 6000L;

        public int getDefaultVolume() {
            return this.defaultVolume;
        }

        public int getStep() {
            return this.step;
        }

        public int getMinVolume() {
            return this.minVolume;
        }

        public int getMaxVolume() {
            return this.maxVolume;
        }

        public long getAutoSaveInterval() {
            return this.autoSaveInterval;
        }

        private VolumeConfig() {
        }
    }

    public static class AutoPlayConfig {
        private boolean enable = false;
        // "" or "master"/"all" = all songs; a song name = that song; "CHEST:<id>" = a folder
        private String source = "";
        private boolean shuffle = false;
        // OFF / SINGLE / ALL
        private String loop = "ALL";
        private int delayTicks = 40;

        public boolean isEnable() {
            return this.enable;
        }

        public String getSource() {
            return this.source;
        }

        public boolean isShuffle() {
            return this.shuffle;
        }

        public String getLoop() {
            return this.loop;
        }

        public int getDelayTicks() {
            return this.delayTicks;
        }

        private AutoPlayConfig() {
        }
    }

    public static class SpeedConfig {
        private float defaultSpeed = 1.0f;
        private float step = 0.25f;
        private float minSpeed = 0.25f;
        private float maxSpeed = 2.0f;

        public float getDefaultSpeed() {
            return this.defaultSpeed;
        }

        public float getStep() {
            return this.step;
        }

        public float getMinSpeed() {
            return this.minSpeed;
        }

        public float getMaxSpeed() {
            return this.maxSpeed;
        }

        private SpeedConfig() {
        }
    }

    public static class PlayerConfig {
        private long tickInterval = 1L;
        private long rangeCacheClearInterval = 5000L;

        public long getTickInterval() {
            return this.tickInterval;
        }

        public long getRangeCacheClearInterval() {
            return this.rangeCacheClearInterval;
        }

        private PlayerConfig() {
        }
    }

    public static class SearchConfig {
        private int maxKeywordLength = 10;

        public int getMaxKeywordLength() {
            return this.maxKeywordLength;
        }

        private SearchConfig() {
        }
    }

    public static class ResourcePackInstrumentConfig {
        private boolean enabled = false;

        private Map<String, String> soundKeys = new HashMap<>();

        public boolean isEnabled() {
            return this.enabled;
        }

        public Map<String, String> getSoundKeys() {
            return this.soundKeys;
        }

        private ResourcePackInstrumentConfig() {
        }
    }

    public static class WebConfig {
        private boolean enabled = false;
        private String bindAddress = "";
        private boolean bindAllInterfaces = false;
        private String host = "localhost";
        private int port = 8080;
        private boolean showPortInChat = false;
        private int linkExpireTime = 10;
        private int maxMusicLength = 10000;
        private int maxRequestSize = 10;
        private int rateLimit = 100;
        private String allowedOrigin = "";

        private List<String> trustedProxies = new ArrayList<>();

        public boolean isEnabled() {
            return this.enabled;
        }

        public String getBindAddress() {
            return this.bindAddress;
        }

        public boolean isBindAllInterfaces() {
            return this.bindAllInterfaces;
        }

        public String getHost() {
            return this.host;
        }

        public int getPort() {
            return this.port;
        }

        public boolean isShowPortInChat() {
            return this.showPortInChat;
        }

        public int getLinkExpireTime() {
            return this.linkExpireTime;
        }

        public int getMaxMusicLength() {
            return this.maxMusicLength;
        }

        public int getMaxRequestSize() {
            return this.maxRequestSize;
        }

        public int getRateLimit() {
            return this.rateLimit;
        }

        public String getAllowedOrigin() {
            return this.allowedOrigin;
        }

        public List<String> getTrustedProxies() {
            return this.trustedProxies;
        }

        private WebConfig() {
        }
    }

    public static class PerformanceConfig {
        private boolean editorIncrementalUpdate = true;

        private int databaseBatchSize = 50;
        private int maxKeywordIndexSize = 10000;
        private int textDisplayRefreshIntervalTicks = 10;

        public boolean isEditorIncrementalUpdate() {
            return this.editorIncrementalUpdate;
        }

        public int getDatabaseBatchSize() {
            return this.databaseBatchSize;
        }

        public int getMaxKeywordIndexSize() {
            return this.maxKeywordIndexSize;
        }

        public int getTextDisplayRefreshIntervalTicks() {
            return this.textDisplayRefreshIntervalTicks;
        }

        private PerformanceConfig() {
        }
    }
}
