package com.huidu.musicboxplus.core.player.models;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Where MusicBoxRangeStateChangeEvent is fired from.
//
// This is a structural test, and deliberately so: firing it needs a live PluginManager, so what can
// be pinned without a server is *where* the call sites are -- which is the whole of the bug. The
// event used to be fired from playTick, which derived the transition by comparing the range cache
// against the dispatch map. That comparison could never hold: the range scan marks an entering
// listener in the dispatch map before any playTick runs for them, and on the way out removes the
// listener inside the same scan. On Paper, where both run on the main thread, the event therefore
// never fired at all.
//
// So the invariant is: the event has exactly one source, the range transition, and no playTick
// re-derives it. A source scan is a blunt instrument, but the alternative here is no coverage at all.
class RangeStateChangeFiringTest {

    private static final Path RANGE_MODEL = Path.of("src", "main", "java", "com", "huidu",
            "musicboxplus", "core", "player", "models", "RangePlayerModel.java");

    private static List<Path> playTickSources() throws Exception {
        List<Path> sources = new ArrayList<>();
        for (String dir : new String[]{"core/player", "module/speaker", "module/radio"}) {
            Path root = Path.of("src", "main", "java", "com", "huidu", "musicboxplus", dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path path : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripComments(Files.readString(path, StandardCharsets.UTF_8));
                    if (code.contains("void playTick(")) {
                        sources.add(path);
                    }
                }
            }
        }
        return sources;
    }

    private static String stripComments(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void theTransitionItselfFiresItAndNoPlayTickDoes() throws Exception {
        assertTrue(Files.exists(RANGE_MODEL), "RangePlayerModel moved: " + RANGE_MODEL);
        String model = stripComments(Files.readString(RANGE_MODEL, StandardCharsets.UTF_8));
        assertTrue(model.contains("new MusicBoxRangeStateChangeEvent("),
                "the range transition must be what fires the event");
        assertTrue(model.contains("onPlayerEnterRange") && model.contains("onPlayerLeaveRange"),
                "both transitions should still exist");

        List<Path> playTicks = playTickSources();
        assertTrue(playTicks.size() >= 2,
                "expected the block and speaker playTick implementations, found " + playTicks);
        List<String> offenders = new ArrayList<>();
        for (Path path : playTicks) {
            if (stripComments(Files.readString(path, StandardCharsets.UTF_8))
                    .contains("new MusicBoxRangeStateChangeEvent(")) {
                offenders.add(path.toString());
            }
        }
        assertEquals(List.of(), offenders,
                "playTick cannot observe a range transition, so it must not try to fire this event");
    }
}
