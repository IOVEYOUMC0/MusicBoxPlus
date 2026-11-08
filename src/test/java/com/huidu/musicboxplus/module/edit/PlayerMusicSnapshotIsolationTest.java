package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerMusicSnapshotIsolationTest {
    private static PlayerMusic song() {
        return new PlayerMusic(UUID.randomUUID(), "song", "author", UUID.randomUUID(),
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0,
                List.of(new MusicNote(45, 0, List.of())), "");
    }

    @Test
    void queuedSnapshotDoesNotShareMutableNoteWrappers() {
        PlayerMusic live = song();
        PlayerMusic detached = live.snapshot();
        MusicNote original = live.getNotes().get(0);
        MusicNote copied = detached.getNotes().get(0);
        assertNotSame(original, copied);
        assertSame(original.getInstruments(), copied.getInstruments(),
                "immutable instrument vectors should be reused rather than copied per read");
        assertThrows(UnsupportedOperationException.class, () -> copied.getInstruments().add(null));
    }

    @Test
    void contentDigestDoesNotSortTheEntireNoteList() {
        PlayerMusic linear = new PlayerMusic(UUID.randomUUID(), "song", "author", UUID.randomUUID(),
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0,
                List.of(new MusicNote(45, 9), new MusicNote(47, 1)), "") {
            @Override
            public synchronized List<MusicNote> getNotesSortedByTick() {
                throw new AssertionError("a persistence digest must not sort on the region thread");
            }
        };
        assertEquals(SongContentFingerprint.of(linear), SongContentFingerprint.of(linear));
    }

    @Test
    void metadataAndPitchChangesDoNotCancelEachOtherInTheDigest() {
        UUID id = UUID.randomUUID(), author = UUID.randomUUID();
        PlayerMusic first = new PlayerMusic(id, "song", "author", author,
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0,
                List.of(new MusicNote(45, 0)), "a");
        PlayerMusic second = new PlayerMusic(id, "song", "author", author,
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0,
                List.of(new MusicNote(44, 0)), "b");
        assertNotEquals(SongContentFingerprint.of(first), SongContentFingerprint.of(second));
        assertNotEquals(SongContentFingerprint.of(first).notes(),
                SongContentFingerprint.of(second).notes());
    }

    @Test
    void onlyTheNotesDigestChangesWhenOnlyMetadataChanges() {
        UUID id = UUID.randomUUID(), author = UUID.randomUUID();
        List<MusicNote> notes = List.of(new MusicNote(45, 0));
        PlayerMusic before = new PlayerMusic(id, "song", "author", author,
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0, notes, "first");
        PlayerMusic after = new PlayerMusic(id, "renamed", "author", author,
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0, 0, notes, "second");

        assertEquals(SongContentFingerprint.of(before).notes(),
                SongContentFingerprint.of(after).notes(),
                "a metadata-only save must still be able to skip rewriting the note table");
        assertNotEquals(SongContentFingerprint.of(before).song(),
                SongContentFingerprint.of(after).song());
    }

    @Test
    void applySnapshotIsDetachedAndPreservesLazyIndexes() {
        PlayerMusic live = song();
        PlayerMusic detached = live.snapshot();
        live.applySnapshot(detached);
        assertFalse(live.indexesBuilt(), "applying a snapshot should not eagerly materialize both indexes");
        assertNotSame(live.getNotes().get(0), detached.getNotes().get(0));
        assertNotNull(live.getNote(45, 0));
        assertTrue(live.indexesBuilt());
    }
}
