package com.huidu.musicboxplus.module.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// The two secondary indexes on PlayerMusic (pitch+tick -> note, tick -> notes) are built on first
// use, not when the song is loaded.
//
// They cost a HashMap node plus a concatenated String key per note, and a TreeMap node plus a
// CopyOnWriteArrayList per distinct tick: 80-130 MB of permanent heap for a million notes. Listing,
// searching and browsing player songs never reads them -- only the editor and the playback preview
// do -- so every song that is merely loaded now carries three plain collections instead of five
// populated ones.
//
// This is also the risky half of that change: a reader that forgets to build them sees an empty
// index and silently reports "this song has no notes". The tests below drive every reader and writer.
class PlayerMusicLazyIndexTest {

    @Test
    void loadingASongDoesNotBuildTheIndexes() {
        PlayerMusic music = song(notes(45, 47));

        assertFalse(music.indexesBuilt(), "the indexes must not be built for a song that is only loaded");
        assertEquals(2, music.getNoteCount(), "the note list itself is still there");
        assertEquals(1, music.getMaxTick(), "and the last tick is still readable without the indexes");
    }

    @Test
    void readingANoteBuildsTheIndexesAndStillFindsTheNote() {
        PlayerMusic music = song(notes(45, 47));

        MusicNote note = music.getNote(45, 0);

        assertNotNull(note, "a lazy index that is not built would report every note as missing");
        assertEquals(45, note.getPitch());
        assertTrue(music.indexesBuilt());
    }

    @Test
    void everyIndexReaderBuildsTheIndexes() {
        PlayerMusic byNote = song(notes(45, 47));
        assertNotNull(byNote.getNote(45, 0));
        assertTrue(byNote.indexesBuilt(), "getNote");

        PlayerMusic byTick = song(notes(45, 47));
        assertFalse(byTick.getNotesAtTick(0).isEmpty());
        assertTrue(byTick.indexesBuilt(), "getNotesAtTick");

        PlayerMusic byCeiling = song(notes(45, 47));
        assertNotNull(byCeiling.firstNoteTickAtOrAfter(0));
        assertTrue(byCeiling.indexesBuilt(), "firstNoteTickAtOrAfter");

        PlayerMusic byLast = song(notes(45, 47));
        assertNotNull(byLast.lastNoteTick());
        assertTrue(byLast.indexesBuilt(), "lastNoteTick");

        PlayerMusic byMap = song(notes(45, 47));
        assertFalse(byMap.getTickIndexMap().isEmpty());
        assertTrue(byMap.indexesBuilt(), "getTickIndexMap");
    }

    @Test
    void askingForTheSongLengthDoesNotBuildTheIndexes() {
        // The listing paths ask for this (web snapshots, disc lore), so if it built the index every
        // song a player merely looks at would pay the full cost.
        PlayerMusic music = song(notes(45, 47));

        assertEquals(1, music.getMaxTick(), "the highest tick of notes(45, 47)");
        assertEquals(1, music.getMaxTick(), "and the answer is cached");

        assertFalse(music.indexesBuilt(), "getMaxTick must not materialise the indexes");
    }

    @Test
    void theSongLengthFollowsTheNotesAfterAnEdit() {
        PlayerMusic music = song(notes(45, 47));
        assertEquals(1, music.getMaxTick());

        music.addNote(new MusicNote(60, 99));

        assertEquals(99, music.getMaxTick(), "a stale cached length would truncate the song");
    }

    @Test
    void addingANoteToALoadedSongDoesNotLoseTheExistingOnes() {
        PlayerMusic music = song(notes(45, 47));

        assertTrue(music.addNote(new MusicNote(60, 5)));

        assertEquals(3, music.getNoteCount());
        assertNotNull(music.getNote(45, 0), "a note from the loaded set vanished when the index was built");
        assertNotNull(music.getNote(60, 5));
        assertEquals(List.of(45, 47, 60), pitchesAtTickOrder(music));
    }

    @Test
    void removingANoteFromALoadedSongKeepsTheRest() {
        PlayerMusic music = song(notes(45, 47));
        MusicNote toRemove = music.getNote(45, 0);

        assertTrue(music.removeNote(toRemove));

        assertNull(music.getNote(45, 0));
        assertNotNull(music.getNote(47, 1));
        assertEquals(1, music.getNoteCount());
    }

    @Test
    void clearingNotesDoesNotResurrectThemOnTheNextAdd() {
        PlayerMusic music = song(notes(45, 47));
        assertNotNull(music.getNote(45, 0));

        music.clearNotes();
        assertTrue(music.addNote(new MusicNote(60, 5)));

        assertEquals(1, music.getNoteCount());
        assertNull(music.getNote(45, 0), "the cleared notes came back when the index was rebuilt");
        assertNotNull(music.getNote(60, 5));
    }

    @Test
    void applyingASnapshotRebuildsTheIndexes() {
        PlayerMusic music = song(notes(45, 47));
        PlayerMusic snapshot = song(notes(60, 62));

        music.applySnapshot(snapshot);

        assertEquals(2, music.getNoteCount());
        assertNull(music.getNote(45, 0));
        assertNotNull(music.getNote(60, 0), "the replaced note set is not indexed");
        assertTrue(music.indexesBuilt());
    }

    @Test
    void aMetadataOnlySnapshotCarriesNoIndexesAndNoNotes() {
        PlayerMusic snapshot = song(notes(45, 47)).snapshotWithoutNotes();

        assertEquals(0, snapshot.getNoteCount());
        assertFalse(snapshot.indexesBuilt(), "an empty song has nothing to index");
    }

    private static List<Integer> pitchesAtTickOrder(PlayerMusic music) {
        List<Integer> pitches = new ArrayList<>();
        for (MusicNote note : music.getNotesSortedByTick()) {
            pitches.add(note.getPitch());
        }
        return pitches;
    }

    private static PlayerMusic song(List<MusicNote> notes) {
        return new PlayerMusic(UUID.randomUUID(), "song", "author", UUID.randomUUID(),
                PlayerMusic.TimeSignature.FOUR_FOUR, 120, 4, 0L, 0L, notes, "");
    }

    private static List<MusicNote> notes(int... pitches) {
        List<MusicNote> notes = new ArrayList<>();
        for (int i = 0; i < pitches.length; i++) {
            notes.add(new MusicNote(pitches[i], i));
        }
        return notes;
    }
}
