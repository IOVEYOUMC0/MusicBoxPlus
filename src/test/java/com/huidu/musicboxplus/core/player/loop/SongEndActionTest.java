package com.huidu.musicboxplus.core.player.loop;

import static com.huidu.musicboxplus.core.player.loop.SongEndAction.ADVANCE_NEXT;
import static com.huidu.musicboxplus.core.player.loop.SongEndAction.HOLD_AT_END;
import static com.huidu.musicboxplus.core.player.loop.SongEndAction.REPLAY_CURRENT;
import static com.huidu.musicboxplus.core.player.loop.SongEndAction.RESTART_FIRST;
import static com.huidu.musicboxplus.core.player.loop.SongEndAction.STOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.huidu.musicboxplus.api.player.loop.LoopMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// The full end-of-song truth table.
//
// Looping and playlists belong to MusicBox, not to the playback engine: the engine only says a
// song finished. Pinning every combination here means swapping the engine cannot quietly
// change what repeat-one, repeat-all, playlist advance or a held display do.
class SongEndActionTest {

    private static SongEndAction decide(LoopMode mode, boolean hasNext, boolean canReplay,
                                        boolean persistent) {
        return SongEndAction.decide(mode, hasNext, canReplay, persistent);
    }

    // Every one of the 24 input combinations, listed rather than derived, so a change in the
    // rules has to be written down here too.
    @Test
    void everyCombinationIsPinned() {
        List<String> wrong = new ArrayList<>();

        // mode, hasNext, canReplay, persistent, expected
        Object[][] table = {
            // Repeat-one wins over everything else, as long as there is a player to rewind.
            {LoopMode.SINGLE, true,  true,  true,  REPLAY_CURRENT},
            {LoopMode.SINGLE, true,  true,  false, REPLAY_CURRENT},
            {LoopMode.SINGLE, false, true,  true,  REPLAY_CURRENT},
            {LoopMode.SINGLE, false, true,  false, REPLAY_CURRENT},
            // With no player to rewind, repeat-one cannot apply and the rest decide.
            {LoopMode.SINGLE, true,  false, true,  ADVANCE_NEXT},
            {LoopMode.SINGLE, true,  false, false, ADVANCE_NEXT},
            {LoopMode.SINGLE, false, false, true,  HOLD_AT_END},
            {LoopMode.SINGLE, false, false, false, STOP},

            // A playlist with songs left always advances, whatever the loop mode.
            {LoopMode.ALL,    true,  true,  true,  ADVANCE_NEXT},
            {LoopMode.ALL,    true,  true,  false, ADVANCE_NEXT},
            {LoopMode.ALL,    true,  false, true,  ADVANCE_NEXT},
            {LoopMode.ALL,    true,  false, false, ADVANCE_NEXT},
            {LoopMode.OFF,    true,  true,  true,  ADVANCE_NEXT},
            {LoopMode.OFF,    true,  true,  false, ADVANCE_NEXT},
            {LoopMode.OFF,    true,  false, true,  ADVANCE_NEXT},
            {LoopMode.OFF,    true,  false, false, ADVANCE_NEXT},

            // Repeat-all only wraps once the playlist is exhausted, and it outranks holding.
            {LoopMode.ALL,    false, true,  true,  RESTART_FIRST},
            {LoopMode.ALL,    false, true,  false, RESTART_FIRST},
            {LoopMode.ALL,    false, false, true,  RESTART_FIRST},
            {LoopMode.ALL,    false, false, false, RESTART_FIRST},

            // Nothing left: a player that must outlive its song holds, others stop.
            {LoopMode.OFF,    false, true,  true,  HOLD_AT_END},
            {LoopMode.OFF,    false, false, true,  HOLD_AT_END},
            {LoopMode.OFF,    false, true,  false, STOP},
            {LoopMode.OFF,    false, false, false, STOP},
        };

        for (Object[] row : table) {
            LoopMode mode = (LoopMode) row[0];
            boolean hasNext = (Boolean) row[1];
            boolean canReplay = (Boolean) row[2];
            boolean persistent = (Boolean) row[3];
            SongEndAction expected = (SongEndAction) row[4];
            SongEndAction actual = decide(mode, hasNext, canReplay, persistent);
            if (actual != expected) {
                wrong.add(mode + " hasNext=" + hasNext + " canReplay=" + canReplay
                        + " persistent=" + persistent + ": expected " + expected + " got " + actual);
            }
        }

        assertEquals(List.of(), wrong);
        assertEquals(LoopMode.values().length * 2 * 2 * 2, table.length,
                "the table must list every combination; a new LoopMode needs new rows");
    }

    // Repeat-one has to beat playlist advance, or turning it on in the middle of a playlist
    // would step to the next song instead of repeating the current one.
    @Test
    void repeatOneOutranksPlaylistAdvance() {
        assertSame(REPLAY_CURRENT, decide(LoopMode.SINGLE, true, true, false));
    }

    // Repeat-all has to beat holding, or a looping display would stop at its last song.
    @Test
    void repeatAllOutranksHolding() {
        assertSame(RESTART_FIRST, decide(LoopMode.ALL, false, true, true));
    }

    // Stopping is the only path that lets a jukebox eject its disc, so a held player must
    // never reach it.
    @Test
    void aHeldPlayerNeverStops() {
        for (LoopMode mode : LoopMode.values()) {
            for (boolean canReplay : new boolean[]{true, false}) {
                assertSame(false, decide(mode, false, canReplay, true) == STOP,
                        mode + " with persistence on reached STOP, which would destroy the display");
            }
        }
    }

    // Looping and playlists must stay MusicBox's own. The playback engine reports that a song
    // ended and nothing more; the moment a third-party player type appears in these packages,
    // part of the behaviour has moved into a library and swapping that library can take it
    // away. NoteBlockAPI carries its own Playlist and RepeatMode, and neither was ever used.
    @Test
    void loopingAndPlaylistsCarryNoThirdPartyTypes() throws Exception {
        List<String> offenders = new ArrayList<>();
        java.nio.file.Path[] roots = {
            java.nio.file.Path.of("src", "main", "java", "com", "huidu", "musicboxplus", "core", "player", "loop"),
            java.nio.file.Path.of("src", "main", "java", "com", "huidu", "musicboxplus", "core", "player", "playlist"),
        };
        for (java.nio.file.Path root : roots) {
            try (var stream = java.nio.file.Files.walk(root)) {
                for (java.nio.file.Path p : stream.filter(x -> x.toString().endsWith(".java")).toList()) {
                    String code = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8)
                            .replaceAll("(?s)/\\*.*?\\*/", "")
                            .replaceAll("(?m)//.*$", "");
                    if (code.contains("xxmicloxx")) {
                        offenders.add(p.toString());
                    }
                }
            }
        }
        java.nio.file.Path api = java.nio.file.Path.of("musicbox-api", "src", "main", "java", "com", "huidu",
                "musicboxplus", "api", "player", "IPlayList.java");
        String apiCode = java.nio.file.Files.readString(api, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
        if (apiCode.contains("xxmicloxx")) {
            offenders.add(api.toString());
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void loopModeCyclesThroughAllStates() {
        assertSame(LoopMode.SINGLE, LoopMode.OFF.next());
        assertSame(LoopMode.ALL, LoopMode.SINGLE.next());
        assertSame(LoopMode.OFF, LoopMode.ALL.next());
    }

    @Test
    void loopModeRoundTripsThroughItsConfigKey() {
        for (LoopMode mode : LoopMode.values()) {
            assertSame(mode, LoopMode.fromKey(mode.getKey()));
            assertSame(mode, LoopMode.fromKey(mode.getKey().toUpperCase()));
        }
        assertSame(LoopMode.OFF, LoopMode.fromKey("nonsense"));
    }
}
