package com.huidu.musicboxplus.common.config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Copying a language file's values, with the bundled English file as the base layer.
//
// An explicit empty list is a value ("render nothing"), not an absence: skipping it left the English
// default that had just been copied in, so the override silently did nothing.
class LanguageValueCopyTest {

    private static YamlConfiguration yaml(String contents) {
        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.loadFromString(contents);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return configuration;
    }

    /** The same layering loadMessages() performs: defaults first, then the user's file on top. */
    private static Map<String, String> layered(YamlConfiguration defaults, YamlConfiguration user) {
        Map<String, String> loaded = new HashMap<>();
        LanguageConfig.copyValues(defaults, loaded);
        LanguageConfig.copyValues(user, loaded);
        return loaded;
    }

    @Test
    void userFileOverridesAScalar() {
        Map<String, String> values = layered(
                yaml("greeting: hello\n"),
                yaml("greeting: hi\n"));

        assertEquals("hi", values.get("greeting"));
    }

    @Test
    void userFileOverridesATwoLineMessage() {
        Map<String, String> values = layered(
                yaml("lines:\n  - a\n  - b\n"),
                yaml("lines:\n  - c\n"));

        assertEquals("c", values.get("lines"));
    }

    @Test
    void anEmptyListOverridesTheDefaultWithNothing() {
        Map<String, String> values = layered(
                yaml("lore:\n  - english line\n"),
                yaml("lore: []\n"));

        assertEquals("", values.get("lore"),
                "an explicit empty list must clear the default, not fall back to it");
    }

    @Test
    void keysAbsentFromTheUserFileKeepTheDefault() {
        Map<String, String> values = layered(
                yaml("greeting: hello\nbye: goodbye\n"),
                yaml("greeting: hi\n"));

        assertEquals("hi", values.get("greeting"));
        assertEquals("goodbye", values.get("bye"));
    }

    @Test
    void sectionsAreNotFlattenedIntoTheMessageMap() {
        Map<String, String> values = layered(
                yaml("nested:\n  inner: value\n"),
                yaml("nested:\n  inner: overridden\n"));

        assertEquals(List.of("nested.inner"), values.keySet().stream().toList());
        assertEquals("overridden", values.get("nested.inner"));
    }
}
