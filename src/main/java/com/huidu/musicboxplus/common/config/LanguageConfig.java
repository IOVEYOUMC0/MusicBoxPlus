package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import com.huidu.musicboxplus.common.utils.StringUtils;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class LanguageConfig {
    private static final String DEFAULT_LANGUAGE_FILE = "language_en.yml";
    private static final String LEGACY_DEFAULT_LANGUAGE_FILE = "language.yml";
    private static LanguageConfig instance;

    private final MusicBox plugin;
    private File configFile;
    private String selectedFileName = DEFAULT_LANGUAGE_FILE;
    private YamlConfiguration config;
    private YamlConfiguration englishDefaults;
    // Swapped wholesale on reload, never cleared in place: read from arbitrary threads.
    private volatile Map<String, String> messages = new HashMap<>();

    protected LanguageConfig(MusicBox plugin) {
        this.plugin = plugin;
        this.loadConfig();
    }

    public static LanguageConfig getInstance() {
        return instance;
    }

    public static synchronized LanguageConfig getInstance(MusicBox plugin) {
        if (instance == null) {
            instance = new LanguageConfig(plugin);
        }
        return instance;
    }

    private void loadConfig() {
        try {
            selectedFileName = resolveConfiguredLanguageFileName();
            configFile = new File(plugin.getDataFolder(), selectedFileName);

            ensureLanguageFileExists(configFile, selectedFileName);
            englishDefaults = loadYamlFromResource(DEFAULT_LANGUAGE_FILE);
            config = loadLanguageFile(configFile, selectedFileName);

            if (englishDefaults != null) {
                config.setDefaults(englishDefaults);
                autoFillMissingKeys();
            }

            loadMessages();
            plugin.getLogger().info(LogLocale.text(plugin, "Loaded language file: " + selectedFileName, "已加载语言文件: " + selectedFileName));
        } catch (Exception e) {
            plugin.getLogger().warning(LogLocale.text(plugin, "Failed to load language config: " + e.getMessage(), "加载语言配置失败: " + e.getMessage()));
            config = new YamlConfiguration();
            englishDefaults = loadYamlFromResource(DEFAULT_LANGUAGE_FILE);
            if (englishDefaults != null) {
                config.setDefaults(englishDefaults);
            }
            loadMessages();
        }
    }

    private String resolveConfiguredLanguageFileName() {
        MusicBoxConfig configObject = plugin.getConfigObject();
        String configured = null;
        if (configObject != null && configObject.getLanguage() != null) {
            configured = configObject.getLanguage();
        }

        if (configured == null || configured.trim().isEmpty()) {
            return DEFAULT_LANGUAGE_FILE;
        }

        String trimmed = configured.trim().replace('\\', '/');
        if (trimmed.contains("/") || trimmed.contains("..")) {
            plugin.getLogger().warning(LogLocale.text(plugin, "Invalid language file path '" + configured + "', falling back to " + DEFAULT_LANGUAGE_FILE, "语言文件路径无效 '" + configured + "'，将回退到 " + DEFAULT_LANGUAGE_FILE));
            return DEFAULT_LANGUAGE_FILE;
        }

        if ("en".equalsIgnoreCase(trimmed) || "default".equalsIgnoreCase(trimmed)) {
            return DEFAULT_LANGUAGE_FILE;
        }

        if (!trimmed.endsWith(".yml")) {
            trimmed = "language_" + trimmed.toLowerCase() + ".yml";
        }

        return trimmed;
    }

    private void ensureLanguageFileExists(File targetFile, String fileName) throws Exception {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        if (targetFile.exists()) {
            return;
        }

        if (!StorageAccess.canWriteTo(targetFile)) {
            plugin.getLogger().warning(LogLocale.text(plugin,
                "Language folder is not writable, loading bundled " + fileName + " in memory",
                "语言目录不可写，将以内存方式加载内置 " + fileName));
            return;
        }

        try (InputStream stream = openBundledLanguageStream(fileName)) {
            if (stream == null) {
                throw new IllegalStateException("Missing bundled fallback language resource for " + fileName);
            }
            Files.copy(stream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private InputStream openBundledLanguageStream(String fileName) {
        InputStream selectedStream = plugin.getResource(fileName);
        if (selectedStream != null) {
            return selectedStream;
        }
        InputStream defaultStream = plugin.getResource(DEFAULT_LANGUAGE_FILE);
        if (defaultStream != null) {
            return defaultStream;
        }
        return plugin.getResource(LEGACY_DEFAULT_LANGUAGE_FILE);
    }

    private YamlConfiguration loadYamlFromResource(String resourceName) {
        InputStream resourceStream = plugin.getResource(resourceName);
        if (resourceStream == null && DEFAULT_LANGUAGE_FILE.equals(resourceName)) {
            resourceStream = plugin.getResource(LEGACY_DEFAULT_LANGUAGE_FILE);
        }

        try (InputStream stream = resourceStream) {
            if (stream == null) {
                return null;
            }
            YamlConfiguration yaml = new YamlConfiguration();
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            yaml.loadFromString(stripLeadingBom(content));
            return yaml;
        } catch (Exception e) {
            plugin.getLogger().warning(LogLocale.text(plugin, "Failed to load bundled language resource " + resourceName + ": " + e.getMessage(), "加载内置语言资源失败 " + resourceName + ": " + e.getMessage()));
            return null;
        }
    }

    private YamlConfiguration loadLanguageFile(File file, String fileName) throws IOException {
        if (!file.exists()) {
            YamlConfiguration bundled = loadYamlFromResource(fileName);
            if (bundled != null) {
                return bundled;
            }
            throw new IOException("Language file does not exist and bundled fallback is missing: " + fileName);
        }
        try {
            return loadYamlFileStrict(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning(LogLocale.text(plugin, "Language file " + fileName + " is invalid, restoring from bundled copy: " + e.getMessage(), "语言文件 " + fileName + " 无效，正在从内置副本恢复: " + e.getMessage()));
            if (!StorageAccess.canWriteTo(file)) {
                YamlConfiguration bundled = loadYamlFromResource(fileName);
                if (bundled != null) {
                    plugin.getLogger().warning(LogLocale.text(plugin,
                        "Language file cannot be repaired because the folder is not writable, using bundled defaults in memory",
                        "语言文件无法修复，因为目录不可写，将以内存方式使用内置默认值"));
                    return bundled;
                }
            } else {
                restoreBundledLanguageFile(file, fileName);
            }
            try {
                return loadYamlFileStrict(file);
            } catch (IOException | InvalidConfigurationException retryException) {
                throw new IOException("Failed to reload restored language file " + fileName, retryException);
            }
        }
    }

    private YamlConfiguration loadYamlFileStrict(File file) throws IOException, InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        yaml.loadFromString(stripLeadingBom(content));
        return yaml;
    }

    private String stripLeadingBom(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        int start = 0;
        while (start < content.length() && content.charAt(start) == '\uFEFF') {
            start++;
        }
        return start == 0 ? content : content.substring(start);
    }

    private void restoreBundledLanguageFile(File targetFile, String fileName) throws IOException {
        if (targetFile.exists()) {
            File backupFile = new File(targetFile.getParentFile(), targetFile.getName() + ".broken.bak");
            Files.copy(targetFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        try (InputStream stream = openBundledLanguageStream(fileName)) {
            if (stream == null) {
                throw new IOException("Missing bundled language resource for " + fileName);
            }
            Files.copy(stream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void autoFillMissingKeys() {
        MusicBoxConfig configObject = plugin.getConfigObject();
        boolean autoFillMissing = configObject == null || configObject.isLanguageAutoFillMissing();

        if (!autoFillMissing || englishDefaults == null || config == null || configFile == null) {
            return;
        }

        if (!StorageAccess.canWriteTo(configFile)) {
            return;
        }

        int added = 0;
        for (String key : englishDefaults.getKeys(true)) {
            // contains(key, true) ignores defaults; setDefaults(englishDefaults) already ran, so
            // the one-arg form answers true for every key.
            if (englishDefaults.isConfigurationSection(key) || config.contains(key, true)) {
                continue;
            }

            Object defaultValue = englishDefaults.get(key);
            if (defaultValue != null) {
                config.set(key, defaultValue);
                added++;
            }
        }

        if (added <= 0) {
            return;
        }

        try {
            config.save(configFile);
            plugin.getLogger().info(LogLocale.text(plugin, "Added " + added + " missing language keys to " + selectedFileName, "已向 " + selectedFileName + " 补充 " + added + " 个缺失语言键"));
        } catch (Exception e) {
            plugin.getLogger().warning(LogLocale.text(plugin, "Failed to auto-fill language file " + selectedFileName + ": " + e.getMessage(), "自动补全语言文件失败 " + selectedFileName + ": " + e.getMessage()));
        }
    }

    private void loadMessages() {
        Map<String, String> loaded = new HashMap<>();

        if (englishDefaults != null) {
            copyConfigurationValues(englishDefaults, loaded);
        }
        if (config != null) {
            copyConfigurationValues(config, loaded);
        }
        messages = loaded;
    }

    private void copyConfigurationValues(Configuration source, Map<String, String> target) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) {
                continue;
            }

            if (source.isList(key)) {
                List<String> list = source.getStringList(key);
                if (!list.isEmpty()) {
                    target.put(key, String.join("\n", list));
                }
                continue;
            }

            String value = source.getString(key);
            if (value != null) {
                target.put(key, value);
            }
        }
    }

    private String getRaw(String key) {
        String message = messages.get(key);
        if (message == null) {
            return "<red>Missing: " + key + "</red>";
        }
        return message;
    }

    public String get(String key) {
        return StringUtils.t(getResolvedRaw(key));
    }

    public String get(String key, String... replacements) {
        String message = getResolvedRaw(key, replacements);
        return StringUtils.t(message);
    }

    public String getRawResolved(String key) {
        return getResolvedRaw(key);
    }

    public String getRawResolved(String key, String... replacements) {
        return getResolvedRaw(key, replacements);
    }

    private String getResolvedRaw(String key, String... replacements) {
        String message = getRaw(key);
        if (replacements.length > 0 && replacements.length % 2 == 0) {
            for (int i = 0; i < replacements.length; i += 2) {
                String placeholder = replacements[i];
                String value = replacements[i + 1];
                if (placeholder != null && value != null) {
                    message = message.replace(placeholder, value);
                }
            }
        }
        return message;
    }

    public void reload() {
        loadConfig();
        plugin.getLogger().info(LogLocale.text(plugin, "Language config reloaded", "语言配置已重载"));
    }

    public Set<String> getAvailableKeys() {
        return new HashSet<>(messages.keySet());
    }

    public MusicBox getPlugin() {
        return this.plugin;
    }

    public File getConfigFile() {
        return this.configFile;
    }

    public String getSelectedFileName() {
        return this.selectedFileName;
    }

    public YamlConfiguration getConfig() {
        return this.config;
    }
}
