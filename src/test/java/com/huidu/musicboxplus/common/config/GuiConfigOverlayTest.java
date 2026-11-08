package com.huidu.musicboxplus.common.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The overlay must replace only what it defines: a partially translated screen keeps the rest of the
// base wording instead of losing it, which is what lets translations land one screen at a time.
class GuiConfigOverlayTest {

    private static YamlConfiguration yaml(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
        } catch (org.bukkit.configuration.InvalidConfigurationException impossible) {
            // The fixtures are written inline in the tests themselves.
            throw new IllegalStateException("Bad test fixture", impossible);
        }
        return config;
    }

    @Test
    void scalarValuesAreOverridden() {
        YamlConfiguration base = yaml("edit-menu:\n  title: \"<gold>音乐编辑菜单</gold>\"\n");
        YamlConfiguration overlay = yaml("edit-menu:\n  title: \"<gold>Music Edit Menu</gold>\"\n");

        GuiConfigOverlay.merge(base, overlay);

        assertEquals("<gold>Music Edit Menu</gold>", base.getString("edit-menu.title"));
    }

    @Test
    void keysTheOverlayOmitsKeepTheBaseValue() {
        YamlConfiguration base = yaml("""
                edit-menu:
                  title: "<gold>音乐编辑菜单</gold>"
                  rows: 6
                """);
        YamlConfiguration overlay = yaml("edit-menu:\n  title: \"<gold>Music Edit Menu</gold>\"\n");

        GuiConfigOverlay.merge(base, overlay);

        assertEquals(6, base.getInt("edit-menu.rows"), "an untranslated key must survive");
    }

    @Test
    void listsAreReplacedWholeInsteadOfAppended() {
        YamlConfiguration base = yaml("""
                buttons:
                  close:
                    name: "<red>关闭</red>"
                    lore:
                      - "<gray>点击关闭</gray>"
                      - "<gray>第二行</gray>"
                """);
        YamlConfiguration overlay = yaml("""
                buttons:
                  close:
                    lore:
                      - "<gray>Click to close</gray>"
                """);

        GuiConfigOverlay.merge(base, overlay);

        assertEquals(List.of("<gray>Click to close</gray>"), base.getStringList("buttons.close.lore"),
                "lore is translated as a block; a merged half-English list would be worse");
        assertEquals("<red>关闭</red>", base.getString("buttons.close.name"),
                "a key the overlay does not mention keeps the base text");
    }

    @Test
    void deeplyNestedSectionsAreMerged() {
        YamlConfiguration base = yaml("""
                music-editor:
                  buttons:
                    play:
                      name: "<green>播放</green>"
                    stop:
                      name: "<red>停止</red>"
                """);
        YamlConfiguration overlay = yaml("""
                music-editor:
                  buttons:
                    play:
                      name: "<green>Play</green>"
                """);

        GuiConfigOverlay.merge(base, overlay);

        assertEquals("<green>Play</green>", base.getString("music-editor.buttons.play.name"));
        assertEquals("<red>停止</red>", base.getString("music-editor.buttons.stop.name"));
    }

    @Test
    void languageCodesMapToOverlayFileNames() {
        assertEquals("gui-config_en.yml", GuiConfigOverlay.overlayFileName("en"));
        assertEquals("gui-config_zh_cn.yml", GuiConfigOverlay.overlayFileName("zh_cn"));
        assertEquals("gui-config_zh_cn.yml", GuiConfigOverlay.overlayFileName("zh-TW"));
        assertEquals("gui-config_zh_cn.yml", GuiConfigOverlay.overlayFileName("language_zh_cn.yml"));
        assertEquals("gui-config_en.yml", GuiConfigOverlay.overlayFileName("language_en.yml"));
        assertEquals("gui-config_en.yml", GuiConfigOverlay.overlayFileName(null));
        assertEquals("gui-config_de.yml", GuiConfigOverlay.overlayFileName("de"),
                "an unknown language still gets a predictable file name to translate into");
    }

    @Test
    void mergingAnEmptyOverlayChangesNothing() {
        YamlConfiguration base = yaml("edit-menu:\n  title: \"<gold>音乐编辑菜单</gold>\"\n");

        GuiConfigOverlay.merge(base, yaml(""));

        assertEquals("<gold>音乐编辑菜单</gold>", base.getString("edit-menu.title"));
        assertTrue(base.getKeys(false).contains("edit-menu"));
    }

    // Guards the shipped English overlay itself: it has to parse, and it must not accidentally drop
    // sections it does not translate (a typo in the file would otherwise only show up in game).
    @Test
    void theBundledEnglishOverlayParsesAndKeepsUntranslatedSections() throws Exception {
        YamlConfiguration base;
        try (var stream = getClass().getResourceAsStream("/gui-config.yml")) {
            assertTrue(stream != null, "bundled gui-config.yml missing from the test classpath");
            base = yaml(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        YamlConfiguration overlay;
        try (var stream = getClass().getResourceAsStream("/gui-config_en.yml")) {
            assertTrue(stream != null, "bundled gui-config_en.yml missing from the test classpath");
            overlay = yaml(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }

        String shopTitleBefore = base.getString("song-item.name");

        GuiConfigOverlay.merge(base, overlay);

        assertEquals("<gold>Music Edit Menu</gold>", base.getString("edit-menu.title"));
        assertEquals(overlay.getString("buttons.close.name"), base.getString("buttons.close.name"));
        assertEquals(shopTitleBefore, base.getString("song-item.name"),
                "song-item is not translated yet and must keep its base value");
        assertTrue(base.getConfigurationSection("buttons") != null);
    }
}
