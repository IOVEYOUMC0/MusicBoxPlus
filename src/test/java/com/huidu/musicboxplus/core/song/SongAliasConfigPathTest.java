package com.huidu.musicboxplus.core.song;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Why song data is stored as a list and not as a mapping keyed by song name.
//
// Bukkit's configuration API uses "." as its path separator and cannot escape it:
// MemorySection.set runs a Map value through mapChildrenValues -> createSection(key), and
// YamlConfiguration.loadFromString runs every mapping through convertMapsToSections -> createSection
// as well. So a name containing a dot is split on the way in *and* on the way out, and the entry can
// never be read back under its own name. These tests pin the mechanism, the resulting loss, and the
// recovery of what the old format left behind in existing files.
class SongAliasConfigPathTest {

    private static final String DOTTED = "Song v1.0";

    @TempDir
    Path tempDir;

    private YamlConfiguration saveAndReload(YamlConfiguration config, String name) throws Exception {
        Path file = tempDir.resolve(name);
        config.save(file.toFile());
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    // The old write shape, and the reason it is unrecoverable by a path lookup.
    @Test
    void aDottedSongNameIsSplitWhenUsedAsAMappingKey() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("songs." + DOTTED + ".aliases", List.of("rain"));

        YamlConfiguration reloaded = saveAndReload(config, "mapping.yml");
        ConfigurationSection songs = reloaded.getConfigurationSection("songs");
        assertNotNull(songs);

        // The key at the top level is only the part before the first dot...
        assertEquals(Set.of(DOTTED.substring(0, DOTTED.indexOf('.'))), songs.getKeys(false),
                "the dotted name should have been split into nested sections");
        assertFalse(songs.getKeys(false).contains(DOTTED),
                "the full name must not be a key -- if it ever is, this test no longer describes the bug");
        // ...and that is why the old reader, which iterated top-level keys and read a *relative*
        // "aliases" from each, saw nothing: the fields live one level further down.
        assertEquals(List.of(), songs.getConfigurationSection(DOTTED.substring(0, DOTTED.indexOf('.')))
                        .getStringList("aliases"),
                "reading the relative field from the split key yields nothing, which is the silent loss");
    }

    // The new write shape: a sequence has no keys, so nothing splits.
    @Test
    void aDottedSongNameSurvivesWhenTheBlockIsAList() throws Exception {
        Map<String, Object> dotted = new LinkedHashMap<>();
        dotted.put("name", DOTTED);
        dotted.put("aliases", List.of("rain", "storm"));
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("name", "plain");
        plain.put("aliases", List.of("x"));

        YamlConfiguration config = new YamlConfiguration();
        config.set("songs", List.of(dotted, plain));

        YamlConfiguration reloaded = saveAndReload(config, "list.yml");
        List<Map<?, ?>> entries = reloaded.getMapList("songs");
        assertEquals(2, entries.size(), "both entries should round-trip, got " + entries);
        assertEquals(DOTTED, entries.get(0).get("name"),
                "the dotted song name must come back verbatim, got " + entries.get(0).get("name"));
        assertEquals(List.of("rain", "storm"), entries.get(0).get("aliases"));
        assertEquals("plain", entries.get(1).get("name"));
        assertEquals(List.of("x"), entries.get(1).get("aliases"));
    }

    // Existing files already hold the split pieces, so the dotted entries are recoverable: joining
    // the nested levels with the separator the writer split on rebuilds the name.
    @Test
    void theSplitLevelsOfAnExistingFileReconstructTheName() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("songs." + DOTTED + ".aliases", List.of("rain"));
        config.set("songs.plain.aliases", List.of("x"));

        YamlConfiguration reloaded = saveAndReload(config, "legacy.yml");
        ConfigurationSection songs = reloaded.getConfigurationSection("songs");
        assertNotNull(songs);

        // Mirrors SongAliasConfig.loadLegacySongsSection.
        Map<String, List<String>> recovered = new LinkedHashMap<>();
        for (String key : songs.getKeys(false)) {
            ConfigurationSection section = songs.getConfigurationSection(key);
            String name = key;
            while (!hasFields(section) && section.getKeys(false).size() == 1) {
                String child = section.getKeys(false).iterator().next();
                ConfigurationSection childSection = section.getConfigurationSection(child);
                if (childSection == null) {
                    break;
                }
                name = name + "." + child;
                section = childSection;
            }
            if (hasFields(section)) {
                recovered.put(name, section.getStringList("aliases"));
            }
        }

        assertEquals(Map.of("Song v1.0", List.of("rain"), "plain", List.of("x")), recovered,
                "the nested levels must be joined back into the original name");
    }

    private static boolean hasFields(ConfigurationSection section) {
        return section.isSet("aliases") || section.isSet("tags") || section.isSet("jukebox-playable")
                || section.isSet("custom-model-data") || section.isSet("custom-material")
                || section.isSet("item-model") || section.isSet("craft-engine-item");
    }
}
