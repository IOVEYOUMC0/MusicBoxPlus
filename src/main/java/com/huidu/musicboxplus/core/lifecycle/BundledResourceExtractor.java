package com.huidu.musicboxplus.core.lifecycle;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;

import java.io.File;
import java.io.IOException;
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
        // The installer must observe the original data-folder state before saveResource creates
        // it. Existing unmarked folders are legacy installations, never a reason to replay songs.
        try {
            BundledSongsInstaller.install(plugin.getDataFolder().toPath(), pluginJar.toPath());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    logText("Failed to initialize bundled songs", "未能初始化内置歌曲"), e);
        }
        saveResourceIfMissing("config.yml");
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
}
