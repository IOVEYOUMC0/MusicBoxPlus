package com.huidu.musicboxplus.core.song;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicBoxSongContainerFallbackTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesInfoTxtWithoutDeletingTheLegacyFile() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("album"));
        List<String> description = List.of("Legacy line one", "Legacy line two");
        Path info = folder.resolve("info.txt");
        Files.write(info, description, StandardCharsets.UTF_8);

        MusicBoxSongContainer container = new MusicBoxSongContainer(folder.toFile(), null);

        assertEquals(description, container.getLore());
        assertTrue(Files.exists(info));

        Path migratedFile = folder.resolve("folder.yml");
        assertTrue(Files.exists(migratedFile));
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(migratedFile.toFile());
        assertEquals(description, migrated.getStringList("display.description"));
    }

    @Test
    void usesInfoTxtWhenFolderConfigHasNoDescription() throws IOException {
        Path folder = Files.createDirectory(tempDir.resolve("album"));
        List<String> description = List.of("Legacy description");
        Files.write(folder.resolve("info.txt"), description, StandardCharsets.UTF_8);

        YamlConfiguration config = new YamlConfiguration();
        config.set("display.name", "<gold>{folder}</gold>");
        config.save(folder.resolve("folder.yml").toFile());

        MusicBoxSongContainer container = new MusicBoxSongContainer(folder.toFile(), null);

        assertEquals(description, container.getLore());
    }
}
