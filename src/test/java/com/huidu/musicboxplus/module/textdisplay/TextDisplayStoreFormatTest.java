package com.huidu.musicboxplus.module.textdisplay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// text-displays.yml is the only record that a text display exists: the floating entities are
// spawned non-persistent, so anything this format loses is gone at the next restart, silently and
// with no way to recover it. That makes the round trip worth pinning down on its own, away from
// the server plumbing.
class TextDisplayStoreFormatTest {

    @Test
    void roundTripsAFullyPopulatedDisplay() throws Exception {
        Map<String, Object> entry = display("stage", "world", 1.5, 64.0, -2.5);
        entry.put("speed", 1.25);
        entry.put("loop", "ALL");
        entry.put("currentIndex", 2);
        entry.put("hasEnd", true);
        entry.put("songs", List.of(song("A", 111), song("B", 222), song("C", 333)));
        entry.put("options", options());

        Map<String, Object> reread = single(TextDisplayStore.fromYaml(TextDisplayStore.toYaml(List.of(entry))));

        assertEquals("stage", reread.get("name"));
        assertEquals("world", reread.get("world"));
        assertEquals(1.5, ((Number) reread.get("x")).doubleValue());
        assertEquals(64.0, ((Number) reread.get("y")).doubleValue());
        assertEquals(-2.5, ((Number) reread.get("z")).doubleValue());
        assertEquals(16, ((Number) reread.get("range")).intValue());
        assertEquals(1.25, ((Number) reread.get("speed")).doubleValue());
        assertEquals("ALL", reread.get("loop"));
        assertEquals(2, ((Number) reread.get("currentIndex")).intValue());
        assertEquals(Boolean.TRUE, reread.get("hasEnd"));

        List<?> songs = (List<?>) reread.get("songs");
        assertEquals(3, songs.size(), "playlist order and length must survive");
        assertEquals("B", ((Map<?, ?>) songs.get(1)).get("name"));
        assertEquals(222, ((Number) ((Map<?, ?>) songs.get(1)).get("hash")).intValue());

        Map<?, ?> reoptions = (Map<?, ?>) reread.get("options");
        assertEquals(Boolean.FALSE, reoptions.get("showName"));
        assertEquals(Boolean.TRUE, reoptions.get("billboardFixed"));
        assertEquals(-1.25, ((Number) reoptions.get("heightOffset")).doubleValue());
        assertEquals(135.0, ((Number) reoptions.get("fixedYaw")).doubleValue());
    }

    // Display names come straight from a command argument with no validation. Keyed by name, a dot
    // would be read back as a nested path and the display would disappear into a section with no
    // fields -- which is why the file stores a list.
    @Test
    void survivesNamesThatLookLikeConfigurationPaths() throws Exception {
        List<Map<String, Object>> entries = List.of(
                minimal("my.display"),
                minimal("a.b.c"),
                minimal("with space"),
                minimal("列表"),
                minimal("dash-and_underscore"));

        List<Map<String, Object>> reread =
                TextDisplayStore.fromYaml(TextDisplayStore.toYaml(entries));

        assertEquals(entries.size(), reread.size(), "every display must come back");
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(entries.get(i).get("name"), reread.get(i).get("name"));
            assertEquals("world", reread.get(i).get("world"), "fields must not be swallowed by the name");
        }
    }

    // An idle placeholder is exactly a display with no songs; if an empty list did not survive it
    // would come back as a broken active player instead.
    @Test
    void roundTripsAnIdlePlaceholder() throws Exception {
        Map<String, Object> entry = display("idle", "world", 0, 70, 0);
        entry.put("songs", List.of());
        entry.put("options", options());

        Map<String, Object> reread = single(TextDisplayStore.fromYaml(TextDisplayStore.toYaml(List.of(entry))));
        assertTrue(((List<?>) reread.get("songs")).isEmpty());
    }

    @Test
    void readsAnEmptyOrSonglessFileWithoutFailing() throws Exception {
        assertEquals(List.of(), TextDisplayStore.fromYaml(""));
        assertEquals(List.of(), TextDisplayStore.fromYaml("displays: []\n"));
        assertEquals(List.of(), TextDisplayStore.fromYaml("something-else: 1\n"));
    }

    private static Map<String, Object> single(List<Map<String, Object>> entries) {
        assertEquals(1, entries.size());
        Map<String, Object> entry = entries.get(0);
        assertNotNull(entry);
        return entry;
    }

    private static Map<String, Object> minimal(String name) {
        return display(name, "world", 0, 64, 0);
    }

    private static Map<String, Object> display(String name, String world, double x, double y, double z) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("world", world);
        entry.put("x", x);
        entry.put("y", y);
        entry.put("z", z);
        entry.put("range", 16);
        return entry;
    }

    private static Map<String, Object> song(String name, int hash) {
        Map<String, Object> song = new LinkedHashMap<>();
        song.put("name", name);
        song.put("hash", hash);
        return song;
    }

    private static Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("showName", false);
        options.put("showSong", true);
        options.put("showProgress", false);
        options.put("showTime", true);
        options.put("heightOffset", -1.25);
        options.put("billboardFixed", true);
        options.put("fixedYaw", 135.0);
        options.put("allowPublicEdit", true);
        return options;
    }
}
