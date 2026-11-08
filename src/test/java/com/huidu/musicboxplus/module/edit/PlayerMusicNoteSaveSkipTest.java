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
// player_music. The skip is driven by the content digest, so these pin the property it must have:
// unchanged notes digest to the same value, changed notes do not -- and two different note sets that
// happen to share a 32-bit hash must still be treated as different.
//
// No instrument names appear here on purpose: MusicNote.NoteInstrument cannot be class-initialised
// without a running server (its Material constants need a registry).
class PlayerMusicNoteSaveSkipTest {

    private final PlayerMusicManager manager = PlayerMusicManager.getInstance();

    @Test
    void unchangedNotesDigestToTheSameValue() {
        UUID id = UUID.randomUUID();

        assertEquals(digest(id, notes(45, 47, 60)), digest(id, notes(45, 47, 60)),
                "the same note set must not look like a change");
    }

    @Test
    void addedRemovedAndRepitchedNotesAllChangeTheDigest() {
        UUID id = UUID.randomUUID();
        String original = digest(id, notes(45, 47));

        assertNotEquals(original, digest(id, notes(45, 47, 60)), "an added note must be written");
        assertNotEquals(original, digest(id, notes(45)), "a removed note must be written");
        assertNotEquals(original, digest(id, notes(45, 48)), "a repitched note must be written");
    }

    @Test
    void reorderingNotesChangesTheDigestToo() {
        UUID id = UUID.randomUUID();

        // The digest follows storage order, so the same notes in a different order look like a
        // change. That direction is the safe one: it costs one redundant rewrite instead of
        // silently dropping a save.
        assertNotEquals(digest(id, notes(45, 47)), digest(id, notes(47, 45)));
    }

    @Test
    void noteDigestIsNotTheLegacyCollidingIntFold() {
        UUID id = UUID.randomUUID();

        // Pitch 45/tick 0 and pitch 44/tick 31 collide under the old 31x fold
        // ("31*31 + 45*31 + 0" vs "31*31 + 44*31 + 31"), which silently dropped a real save.
        // The digest is length-delimited, so they must differ.
        assertNotEquals(digestWithTicks(id, new int[]{45}, new int[]{0}),
                digestWithTicks(id, new int[]{44}, new int[]{31}),
                "a 32-bit integer collision must not be mistaken for identical song content");
    }

    @Test
    void everySongStartsWithTheNoteTableUnwritten() {
        // What the fresh instance does for a song it has never committed: write the notes.
        assertTrue(manager.needsNoteRewrite(UUID.randomUUID(), "no-such-digest"));
    }

    @Test
    void notesAreWrittenOnceAndThenSkippedUntilTheyChange() {
        UUID id = UUID.randomUUID();
        String digest = digest(id, notes(45, 47));

        assertTrue(manager.needsNoteRewrite(id, digest), "the first save of a song writes its notes");

        manager.markNotesWritten(id, digest);

        assertFalse(manager.needsNoteRewrite(id, digest),
                "a metadata-only save (rename, BPM, description) must not rewrite the note table");
        assertTrue(manager.needsNoteRewrite(id, digest(id, notes(45, 47, 60))),
                "a real note edit must still rewrite the note table");
    }

    @Test
    void oneSongsRecordDoesNotDecideAnotherSongsWrite() {
        UUID written = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        manager.markNotesWritten(written, "shared-digest");

        assertFalse(manager.needsNoteRewrite(written, "shared-digest"));
        assertTrue(manager.needsNoteRewrite(other, "shared-digest"),
                "the record is per song, not a global 'something was written' flag");
    }

    private static String digest(UUID id, List<MusicNote> notes) {
        return SongContentFingerprint.of(song(id, notes)).notes();
    }

    private static String digestWithTicks(UUID id, int[] pitches, int[] ticks) {
        List<MusicNote> notes = new ArrayList<>();
        for (int i = 0; i < pitches.length; i++) {
            notes.add(new MusicNote(pitches[i], ticks[i]));
        }
        return SongContentFingerprint.of(song(id, notes)).notes();
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
