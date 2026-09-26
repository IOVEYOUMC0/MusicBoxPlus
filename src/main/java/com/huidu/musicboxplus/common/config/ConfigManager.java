package com.huidu.musicboxplus.common.config;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Paths;
import com.huidu.musicboxplus.common.utils.LogLocale;
import com.huidu.musicboxplus.common.utils.StorageAccess;
import com.huidu.musicboxplus.common.utils.StringUtils;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

public class ConfigManager {
    private static volatile ConfigManager instance;
    private static final Object LOCK = new Object();
    private final MusicBox plugin;
    private List<String> signAliases = new ArrayList<>();
    private String signDisplayText = "<gold>[MusicBox]</gold>";
    private String signSetupText = "<yellow>[MusicBox Setup]</yellow>";

    private ConfigManager(MusicBox plugin) {
        this.plugin = plugin;
        this.loadConfigurations();
    }

    public static ConfigManager getInstance(MusicBox plugin) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new ConfigManager(plugin);
                }
            }
        }
        return instance;
    }

    public static ConfigManager getInstance() {
        return instance;
    }

    public void reload() {
        this.loadConfigurations();
    }

    private void loadConfigurations() {
        this.loadSignAliases();
    }

    private void loadSignAliases() {
        File configFile = new File(this.plugin.getDataFolder(), Paths.CONFIG_FILE);
        if (!configFile.exists() && StorageAccess.canWriteTo(configFile)) {
            this.plugin.saveResource(Paths.CONFIG_FILE, false);
        }
        try {
            YamlConfiguration config;
            if (configFile.exists()) {
                config = YamlConfiguration.loadConfiguration(configFile);
            } else {
                try (InputStream stream = this.plugin.getResource(Paths.CONFIG_FILE);
                     Reader reader = stream == null ? null : new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    if (reader == null) {
                        throw new IllegalStateException("Missing bundled config.yml");
                    }
                    this.plugin.getLogger().warning(LogLocale.text(this.plugin,
                        "Config folder is not writable, sign aliases are using bundled defaults in memory",
                        "配置目录不可写，告示牌别名将以内存方式使用内置默认值"));
                    config = YamlConfiguration.loadConfiguration(reader);
                }
            }
            List<String> aliases = config.getStringList("sign.aliases");
            this.signAliases = aliases.isEmpty()
                ? Arrays.asList("[music]", "[musik]", "[音乐]", "[音乐盒]")
                : new ArrayList<>(aliases);
            this.signDisplayText = config.getString("sign.displayText", "<gold>[MusicBox]</gold>");
            this.signSetupText = config.getString("sign.setupText", "<yellow>[MusicBox Setup]</yellow>");
        } catch (Exception e) {
            this.plugin.getLogger().log(Level.WARNING, LogLocale.text(this.plugin,
                "Failed to load sign aliases, using defaults",
                "加载告示牌别名失败，将使用默认值"), e);
            this.signAliases = Arrays.asList("[music]", "[musik]", "[音乐]", "[音乐盒]");
            this.signDisplayText = "<gold>[MusicBox]</gold>";
            this.signSetupText = "<yellow>[MusicBox Setup]</yellow>";
        }
    }

    // Case-insensitive and colour-stripped, so listing case variants of the same word in
    // config.yml adds nothing.
    public boolean isValidSignAlias(String line) {
        String stripped = StringUtils.stripAllColors(line).trim();
        for (String alias : this.signAliases) {
            if (stripped.equalsIgnoreCase(StringUtils.stripAllColors(alias).trim())) {
                return true;
            }
        }
        return false;
    }

    // First entry in the configured list. This is the one written onto a sign the plugin sets
    // up itself, so the order in config.yml is load-bearing.
    public String getDefaultSignAlias() {
        return this.signAliases.isEmpty() ? "[music]" : this.signAliases.get(0);
    }

    public String getSignDisplayText() {
        return this.signDisplayText;
    }

    public String getSignSetupText() {
        return this.signSetupText;
    }

    public MusicBox getPlugin() {
        return this.plugin;
    }

    public List<String> getSignAliases() {
        return this.signAliases;
    }
}
