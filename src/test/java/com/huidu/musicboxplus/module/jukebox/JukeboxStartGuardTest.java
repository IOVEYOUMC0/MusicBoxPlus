package com.huidu.musicboxplus.module.jukebox;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class JukeboxStartGuardTest {
    @Test
    void oneStartAcceptsTheDiscSelectedFromAChestByPlaylistConstruction() {
        AtomicReference<Integer> actualRecord = new AtomicReference<>();
        Integer recordBeforeConstruction = actualRecord.get();
        assertNull(recordBeforeConstruction);

        // JukeboxPlaylistImpl's constructor moves a chest disc into the empty block and caches it.
        Playlist playlist = new Playlist(actualRecord, 37);
        JukeboxStartGuard expected = JukeboxStartGuard.capture(playlist, Playlist::currentSongHash);

        assertNotNull(expected);
        assertTrue(expected.stillMatches(actualRecord.get()),
                "the first redstone pulse must start the disc the constructor just inserted");
        assertFalse(expected.stillMatches(recordBeforeConstruction),
                "expecting the pre-construction empty record prevented a first-pulse start");
    }

    @Test
    void pinnedSelectionRejectsRemovalSwapAndUnrecognisedRecordsDuringCompilation() {
        AtomicReference<Integer> actualRecord = new AtomicReference<>();
        Playlist playlist = new Playlist(actualRecord, 37);
        JukeboxStartGuard expected = JukeboxStartGuard.capture(playlist, Playlist::currentSongHash);

        actualRecord.set(99);
        assertFalse(expected.stillMatches(actualRecord.get()), "a different disc wins while compiling");
        actualRecord.set(null);
        assertFalse(expected.stillMatches(actualRecord.get()), "removed/unknown discs must not start");
        actualRecord.set(37);
        assertTrue(expected.stillMatches(actualRecord.get()));
    }

    @Test
    void minusOneIsARealHashNotAnEmptyRecordSentinel() {
        AtomicReference<Integer> actualRecord = new AtomicReference<>();
        Playlist playlist = new Playlist(actualRecord, -1);
        JukeboxStartGuard expected = JukeboxStartGuard.capture(playlist, Playlist::currentSongHash);

        assertNotNull(expected);
        assertTrue(expected.stillMatches(-1));
        assertFalse(expected.stillMatches(null));
        assertFalse(expected.stillMatches(0));
    }

    @Test
    void noCurrentSongDoesNotCreateADeferredStart() {
        assertNull(JukeboxStartGuard.capture(new Playlist(new AtomicReference<>(), null),
                Playlist::currentSongHash));
    }

    @Test
    void expectationIsCapturedOnceRatherThanFollowingLaterPlaylistChanges() {
        AtomicReference<Integer> current = new AtomicReference<>(37);
        JukeboxStartGuard expected = JukeboxStartGuard.capture(current, AtomicReference::get);
        current.set(99);

        assertTrue(expected.stillMatches(37));
        assertFalse(expected.stillMatches(current.get()));
    }

    private record Playlist(Integer currentSongHash) {
        Playlist(AtomicReference<Integer> actualRecord, Integer chestSongHash) {
            this(actualRecord.get() == null ? chestSongHash : actualRecord.get());
            actualRecord.set(currentSongHash);
        }
    }
}
