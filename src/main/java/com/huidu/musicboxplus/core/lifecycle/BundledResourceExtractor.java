package com.huidu.musicboxplus.core.lifecycle;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Paths;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;

// Unpacks the default config and the songs shipped inside the plugin jar into the data folder;
// the songs are written only on first run, so later user deletions are not restored.
public final class BundledResourceExtractor {
    private final MusicBox plugin;

    public BundledResourceExtractor(MusicBox plugin) {
        this.plugin = plugin;
    }

    private String logText(String english, String chinese) {
        return LogLocale.text(plugin, english, chinese);
    }

    // pluginJar must be the plugin's own jar: the caller passes getFile(), which is only
    // accessible from inside the plugin class, so it cannot be resolved here.
    public void ensureDefaults(File pluginJar) {
        File dataFolder = plugin.getDataFolder();
        boolean firstRun = !dataFolder.exists();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            plugin.getLogger().warning(logText("Failed to create plugin data folder", "创建插件数据目录失败"));
        }

        saveResourceIfMissing("config.yml");
        if (firstRun) {
            saveBundledSongs(pluginJar);
        }
    }

    private void saveResourceIfMissing(String resourcePath) {
        File targetFile = new File(plugin.getDataFolder(), resourcePath);
        if (targetFile.exists()) {
            return;
        }
        if (!StorageAccess.canWriteTo(targetFile)) {
            plugin.getLogger().warning(logText(
                "Data folder is not writable, skipping bundled file extraction for " + resourcePath,
                "数据目录不可写，跳过内置文件释放: " + resourcePath
            ));
            return;
        }
        try {
            plugin.saveResource(resourcePath, false);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, logText("Bundled resource not found: " + resourcePath, "未找到内置资源: " + resourcePath), e);
        }
    }

    private void saveBundledSongs(File pluginJar) {
        if (!StorageAccess.canWriteTo(new File(plugin.getDataFolder(), Paths.SONGS_DIR))) {
            plugin.getLogger().warning(logText(
                "Data folder is not writable, skipping bundled song extraction",
                "数据目录不可写，跳过内置歌曲释放"
            ));
            return;
        }
        try (JarFile jarFile = new JarFile(pluginJar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith("songs/")) {
                    continue;
                }

                File target = new File(plugin.getDataFolder(), entryName);
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) {
                        plugin.getLogger().warning(logText("Failed to create song directory: " + target.getAbsolutePath(), "创建歌曲目录失败: " + target.getAbsolutePath()));
                    }
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    plugin.getLogger().warning(logText("Failed to create song parent directory: " + parent.getAbsolutePath(), "创建歌曲父目录失败: " + parent.getAbsolutePath()));
                    continue;
                }

                try (InputStream in = jarFile.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, logText("Failed to extract bundled songs", "提取内置歌曲失败"), e);
        }
    }
}
