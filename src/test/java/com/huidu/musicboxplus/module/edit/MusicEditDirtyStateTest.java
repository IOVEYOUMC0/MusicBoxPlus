package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The dirty-flag rule for asynchronous saves: clear only if nothing changed during the save, restore
// only if nothing changed since. Getting this backwards either drops edits or nags about a clean song.
class MusicEditDirtyStateTest {

    @Test
    void aFreshSongIsClean() {
        MusicEditDirtyState state = new MusicEditDirtyState();

        assertFalse(state.isDirty());
        assertEquals(0, state.version());
    }

    @Test
    void markingAChangeDirtyesAndBumpsTheVersion() {
        MusicEditDirtyState state = new MusicEditDirtyState();

        long first = state.markChanged();
        long second = state.markChanged();

        assertTrue(state.isDirty());
        assertEquals(1, first);
        assertEquals(2, second);
    }

    @Test
    void aSaveThatFinishedWithNoNewEditsClearsTheFlag() {
        MusicEditDirtyState state = new MusicEditDirtyState();
        long saved = state.markChanged();

        assertTrue(state.clearIfUnchangedSince(saved));
        assertFalse(state.isDirty(), "nothing changed during the save, so the song is saved");
    }

    @Test
    void aSaveThatOverlappedNewEditsKeepsTheFlag() {
        MusicEditDirtyState state = new MusicEditDirtyState();
        long captured = state.markChanged();
        state.markChanged(); // the player typed while the save was in flight

        assertFalse(state.clearIfUnchangedSince(captured));
        assertTrue(state.isDirty(), "clearing here would silently discard the newer edit");
    }

    @Test
    void aFailedSaveRestoresTheFlag() {
        MusicEditDirtyState state = new MusicEditDirtyState();
        long captured = state.markChanged();
        state.clearIfUnchangedSince(captured); // pretend the save path cleared optimistically

        state.restoreIfUnchangedSince(captured);

        assertTrue(state.isDirty(), "a failed save must leave the song dirty so it is retried");
    }

    @Test
    void aFailedSaveDoesNotOverwriteANewerChange() {
        MusicEditDirtyState state = new MusicEditDirtyState();
        long captured = state.markChanged();
        state.markChanged();
        state.clear();

        state.restoreIfUnchangedSince(captured);

        assertFalse(state.isDirty(),
                "the newer change owns the flag; restoring an older version must not revive it");
    }

    @Test
    void clearingUnconditionallyDiscardsTheFlag() {
        MusicEditDirtyState state = new MusicEditDirtyState();
        state.markChanged();

        state.clear();

        assertFalse(state.isDirty());
        assertEquals(1, state.version(), "discarding edits does not rewind the version counter");
    }

    @Test
    void aChangeAfterADiscardStillDirtyes() {
        MusicEditDirtyState state = new MusicEditDirtyState();
        state.markChanged();
        state.clear();

        state.markChanged();

        assertTrue(state.isDirty(), "editing after a discard must mark the song dirty again");
    }

    @Test
    void versionsKeepIncreasingAcrossClearAndRestore() {
        MusicEditDirtyState state = new MusicEditDirtyState();
        long first = state.markChanged();
        state.clear();
        long second = state.markChanged();

        assertTrue(second > first, "a reused version would let an old completion clear a new edit");
        assertFalse(state.clearIfUnchangedSince(first));
    }
}
