package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import com.huidu.musicboxplus.common.utils.cache.CacheUtils;
import com.huidu.musicboxplus.common.utils.cache.ExpiringCacheCleaner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SmartConfigManager
implements ExpiringCacheCleaner {
    private final MusicBox plugin;
    private final String configName;
    private final String resourcePath;
    private final ConcurrentHashMap<String, ConfigCache> cache;
    private static final ConcurrentHashMap<String, SmartConfigManager> instances = new ConcurrentHashMap<>();

    public SmartConfigManager(MusicBox plugin, String configName, String resourcePath) {
        this.plugin = plugin;
        this.configName = configName;
        this.resourcePath = resourcePath;
        this.cache = new ConcurrentHashMap<>();
        instances.put(configName, this);
        CacheUtils.registerCacheCleaner(this);
    }

    public byte[] loadConfig() throws IOException {
        File configFile = new File(this.plugin.getDataFolder(), this.configName);
        if (!configFile.exists()) {
            this.plugin.getLogger().info(LogLocale.text(this.plugin,
                "Config file " + this.configName + " not found, loading bundled defaults",
                "未找到配置文件 " + this.configName + "，将加载内置默认配置"));
            return this.createDefaultConfig();
        }
        String cacheKey = this.configName + "_" + configFile.lastModified();
        ConfigCache cached = this.cache.get(cacheKey);
        if (cached != null && !cached.isExpired() && cached.valid) {
            this.plugin.getLogger().fine("Using cached config: " + this.configName);
            try {
                return Files.readAllBytes(cached.file.toPath());
            } catch (IOException e) {
                this.plugin.getLogger().warning("缓存文件读取失败，重新加载配置: " + e.getMessage());
                this.cache.remove(cacheKey);
            }
        }
        if (!this.validateFileIntegrity(configFile)) {
            this.plugin.getLogger().warning("Config file " + this.configName + " corrupted or invalid, creating backup and regenerating...");
            return this.handleCorruptedConfig(configFile);
        }
        if (!this.validateConfigFormat(configFile)) {
            this.plugin.getLogger().warning("Config file " + this.configName + " has format issues, attempting repair...");
            return this.attemptConfigRepair(configFile);
        }
        this.cache.put(cacheKey, new ConfigCache(System.currentTimeMillis(), configFile, true));
        this.cleanupExpiredCache();
        return Files.readAllBytes(configFile.toPath());
    }

    private boolean validateFileIntegrity(File configFile) {
        try {
            if (!configFile.exists() || configFile.length() == 0L) {
                return false;
            }
            if (!configFile.canRead()) {
                this.plugin.getLogger().warning("Config file " + this.configName + " is not readable");
                return false;
            }
            if (configFile.length() > 0xA00000L) {
                this.plugin.getLogger().warning("Config file " + this.configName + " is too large (>10MB)");
                return false;
            }
            return true;
        }
        catch (Exception e) {
            this.plugin.getLogger().log(Level.WARNING, "Error validating file integrity for " + this.configName, e);
            return false;
        }
    }


    private byte[] handleCorruptedConfig(File configFile) throws IOException {
        try {
            String backupName = this.createBackup(configFile);
            this.plugin.getLogger().info("Created backup: " + backupName);
            if (configFile.delete()) {
                this.plugin.getLogger().info("Removed corrupted config file: " + this.configName);
            } else {
                this.plugin.getLogger().warning("Failed to remove corrupted config file: " + this.configName);
            }
            return this.createDefaultConfig();
        } catch (IOException e) {
            if (StorageAccess.isPermissionIssue(e)) {
                this.plugin.getLogger().warning(LogLocale.text(this.plugin,
                    "Config folder is not writable, using bundled defaults in memory for " + this.configName,
                    "配置目录不可写，将以内存方式使用 " + this.configName + " 的内置默认值"));
                return this.readBundledDefaultConfig();
            }
            throw e;
        }
    }

    private static final String TAB = "\t";

    // Rewrites the user's config line by line to replace tab indentation. A backup comes first,
    // without exception.
    private byte[] attemptConfigRepair(File configFile) throws IOException {
        try {
            this.plugin.getLogger().info("Created backup before repair: " + this.createBackup(configFile));
        } catch (IOException e) {
            // Without a backup the rewrite is unrecoverable, so fall back to the path that keeps
            // the file intact and loads the bundled defaults instead.
            this.plugin.getLogger().log(Level.WARNING,
                "Could not back up " + this.configName + " before repair; leaving it untouched", e);
            return this.readBundledDefaultConfig();
        }
        File repairedFile = new File(configFile.getParent(), this.configName + ".repaired");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(repairedFile), StandardCharsets.UTF_8));){
            String line;
            int lineNumber = 0;
            int fixedIssues = 0;
            while ((line = reader.readLine()) != null) {
                String repairedLine;
                if (!line.equals(repairedLine = this.repairConfigLine(line, ++lineNumber))) {
                    ++fixedIssues;
                    this.plugin.getLogger().fine("Fixed line " + lineNumber + ": '" + line + "' -> '" + repairedLine + "'");
                }
                writer.write(repairedLine);
                writer.newLine();
            }
            if (fixedIssues > 0) {
                this.plugin.getLogger().info("Repaired " + fixedIssues + " issues in config file: " + this.configName);
            }
        }
        catch (IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to repair config file: " + this.configName, e);
            return this.handleCorruptedConfig(configFile);
        }
        // Files.move rather than File.renameTo: renameTo fails on Windows when the destination
        // exists, which is always the case here, and leaves a stray .repaired file behind.
        try {
            Files.move(repairedFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.plugin.getLogger().info("Config file repaired successfully: " + this.configName);
            return Files.readAllBytes(configFile.toPath());
        } catch (IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Failed to replace config file with repaired version", e);
            return Files.readAllBytes(repairedFile.toPath());
        }
    }

    // Tab indentation is the only unambiguous repair, so it is the only one done here. Anything
    // else falls through to the parse failure and handleCorruptedConfig.
    private String repairConfigLine(String line, int lineNumber) {
        if (!line.contains(TAB)) {
            return line;
        }
        this.plugin.getLogger().fine("Replaced tab indentation at line " + lineNumber);
        return line.replace(TAB, "  ");
    }

    private String createBackup(File originalFile) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String backupName = this.configName.replace(".yml", "") + "_" + timestamp + ".bak";
        File backupFile = new File(this.plugin.getDataFolder(), backupName);
        Files.copy(originalFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return backupName;
    }

    private byte[] createDefaultConfig() throws IOException {
        File configFile = new File(this.plugin.getDataFolder(), this.configName);
        if (!StorageAccess.canWriteTo(configFile)) {
            this.plugin.getLogger().warning(LogLocale.text(this.plugin,
                "Config folder is not writable, using bundled defaults in memory for " + this.configName,
                "配置目录不可写，将以内存方式使用 " + this.configName + " 的内置默认值"));
            return this.readBundledDefaultConfig();
        }
        if (!this.plugin.getDataFolder().exists()) {
            StorageAccess.ensureParentDirectories(configFile);
        }
        try (InputStream resourceStream = this.plugin.getResource(this.resourcePath)) {
            if (resourceStream == null) {
                throw new IOException("Default resource " + this.resourcePath + " not found in plugin jar");
            }
            Files.copy(resourceStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        this.plugin.getLogger().info("Created default config file: " + this.configName);
        return Files.readAllBytes(configFile.toPath());
    }

    private byte[] readBundledDefaultConfig() throws IOException {
        return StorageAccess.readBundledResource(this.plugin, this.resourcePath);
    }

    public boolean validateConfigFormat(File configFile) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8));){
            String line;
            int lineCount = 0;
            int errors = 0;
            while ((line = reader.readLine()) != null) {
                ++lineCount;
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;
                if (line.contains(":") && !line.trim().startsWith("-") && !line.trim().startsWith("#") && line.split(":", 2).length != 2) {
                    this.plugin.getLogger().warning("Invalid key-value format at line " + lineCount + ": " + line);
                    ++errors;
                }
                if (!line.contains("\t")) continue;
                this.plugin.getLogger().warning("Tab character found at line " + lineCount + ", should use spaces");
                ++errors;
            }
            if (errors > 0) {
                this.plugin.getLogger().warning("Config file " + this.configName + " has " + errors + " format issues");
                return false;
            }
            return true;
        }
        catch (IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "Error validating config file format: " + this.configName, e);
            return false;
        }
    }

    public File[] getBackupFiles() {
        File dataFolder = this.plugin.getDataFolder();
        if (!dataFolder.exists()) {
            return new File[0];
        }
        return dataFolder.listFiles((dir, name) -> name.startsWith(this.configName.replace(".yml", "")) && name.endsWith(".bak"));
    }

    public void cleanupOldBackups() {
        File[] backups = this.getBackupFiles();
        if (backups.length <= 5) {
            return;
        }
        Arrays.sort(backups, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 5; i < backups.length; ++i) {
            if (backups[i].delete()) {
                this.plugin.getLogger().info("Deleted old backup: " + backups[i].getName());
                continue;
            }
            this.plugin.getLogger().warning("Failed to delete old backup: " + backups[i].getName());
        }
    }

    private void cleanupExpiredCache() {
        if (this.cache.size() > 100) {
            this.cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }
    }

    public String getConfigStats() {
        File[] backups = this.getBackupFiles();
        long totalBackupSize = 0L;
        for (File backup : backups) {
            totalBackupSize += backup.length();
        }
        return String.format("Config: %s, Backups: %d, Total backup size: %.2f MB, Cache entries: %d", this.configName, backups.length, (double)totalBackupSize / 1048576.0, this.cache.size());
    }

    @Override
    public void clearCache() {
        this.cache.clear();
        this.plugin.getLogger().fine("Cleared config cache for: " + this.configName);
    }

    @Override
    public void cleanupExpired() {
        int beforeSize = this.cache.size();
        this.cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        int afterSize = this.cache.size();
        if (beforeSize != afterSize) {
            this.plugin.getLogger().fine("Cleaned up " + (beforeSize - afterSize) + " expired cache entries for: " + this.configName);
        }
    }

    @Override
    public String getCacheName() {
        return "SmartConfigManager-" + this.configName;
    }

    public static void clearAllCaches() {
        instances.values().forEach(SmartConfigManager::clearCache);
    }

    public static ConcurrentHashMap<String, SmartConfigManager> getInstances() {
        return instances;
    }

    private static class ConfigCache {
        final long timestamp;
        final File file;
        final boolean valid;

        ConfigCache(long timestamp, File file, boolean valid) {
            this.timestamp = timestamp;
            this.file = file;
            this.valid = valid;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - this.timestamp > 300000L;
        }
    }
}
