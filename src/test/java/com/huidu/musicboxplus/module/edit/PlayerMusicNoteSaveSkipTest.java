package com.huidu.musicboxplus.module.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// The note-table rewrite skip in PlayerMusicManager.saveMusicSyncInternal.
//
// That method used to DELETE every row of `player_music_notes` for the song and re-INSERT all of
// them on every save, so renaming a 20k-note song wrote 20k rows to change one string in
// player_music. The skip is driven by calculateNotesSignature, so these pin the property it must
// have: unchanged notes fold to the same value, changed notes do not.
//
// No instrument names appear here on purpose: MusicNote.NoteInstrument cannot be class-initialised
// without a running server (its Material constants need a registry), and the note set alone is what
// this fingerprint decides on -- an instrument-only edit still changes the fold via
// getInstruments().hashCode().
class PlayerMusicNoteSaveSkipTest {

    private final PlayerMusicManager manager = PlayerMusicManager.getInstance();

    @Test
    void unchangedNotesFoldToTheSameFingerprint() {
        UUID id = UUID.randomUUID();

        assertEquals(fingerprint(id, notes(45, 47, 60)), fingerprint(id, notes(45, 47, 60)),
                "the same note set must not look like a change");
    }

    @Test
    void addedRemovedAndRepitchedNotesAllChangeTheFingerprint() {
        UUID id = UUID.randomUUID();
        int original = fingerprint(id, notes(45, 47));

        assertNotEquals(original, fingerprint(id, notes(45, 47, 60)), "an added note must be written");
        assertNotEquals(original, fingerprint(id, notes(45)), "a removed note must be written");
        assertNotEquals(original, fingerprint(id, notes(45, 48)), "a repitched note must be written");
    }

    @Test
    void reorderingNotesChangesTheFingerprintToo() {
        UUID id = UUID.randomUUID();

        // The fold deliberately follows storage order, so the same notes in a different order look
        // like a change. That direction is the safe one: it costs one redundant rewrite instead of
        // silently dropping a save. A pure reorder is caught earlier by the whole-snapshot
        // signature, which sorts the notes before folding them.
        assertNotEquals(fingerprint(id, notes(45, 47)), fingerprint(id, notes(47, 45)));
    }

    @Test
    void everySongStartsWithTheNoteTableUnwritten() {
        // What the fresh instance does for a song it has never committed: write the notes.
        assertTrue(manager.needsNoteRewrite(UUID.randomUUID(), 12345));
    }

    @Test
    void notesAreWrittenOnceAndThenSkippedUntilTheyChange() {
        UUID id = UUID.randomUUID();
        int signature = fingerprint(id, notes(45, 47));

        assertTrue(manager.needsNoteRewrite(id, signature), "the first save of a song writes its notes");

        manager.markNotesWritten(id, signature);

        assertFalse(manager.needsNoteRewrite(id, signature),
                "a metadata-only save (rename, BPM, description) must not rewrite the note table");
        assertTrue(manager.needsNoteRewrite(id, fingerprint(id, notes(45, 47, 60))),
                "a real note edit must still rewrite the note table");
    }

    @Test
    void oneSongsRecordDoesNotDecideAnotherSongsWrite() {
        UUID written = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        manager.markNotesWritten(written, 777);

        assertFalse(manager.needsNoteRewrite(written, 777));
        assertTrue(manager.needsNoteRewrite(other, 777),
                "the record is per song, not a global 'something was written' flag");
    }

    private static int fingerprint(UUID id, List<MusicNote> notes) {
        return PlayerMusicManager.calculateNotesSignature(song(id, notes));
    }

    private static PlayerMusic song(UUID id, List<MusicNote> notes) {
        return new PlayerMusic(id, "song", "author", UUID.randomUUID(),
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
