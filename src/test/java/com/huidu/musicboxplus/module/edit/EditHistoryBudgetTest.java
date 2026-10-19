package com.huidu.musicboxplus.module.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// The undo stack's second limit: retained note data, not just entry count.
//
// The count cap alone let a single action be arbitrarily large. CLEAR_ALL carries a NoteData per note
// of the song, so clearing a 100k-note song pinned 6-10 MB of undo data until 50 more edits pushed it
// out, while the editor autosaved around it.
//
// Both limits are injected here so the rules can be exercised with a tiny budget instead of a real
// song. The production defaults are the config's maxHistorySize and EditHistory.MAX_HISTORY_BYTES.
class EditHistoryBudgetTest {

    private static final long SMALL_BUDGET = 2_000;

    @Test
    void theCountCapStillDropsTheOldestAction() {
        EditHistory history = new EditHistory(2, Long.MAX_VALUE);

        history.pushAction(EditAction.addNote(note(1)));
        history.pushAction(EditAction.addNote(note(2)));
        history.pushAction(EditAction.addNote(note(3)));

        assertEquals(2, history.getHistorySize());
        assertEquals(1, history.getCurrentIndex(), "the newest action is the current one");
        assertEquals(3, undoPitch(history), "undo returns the most recent action first");
        assertEquals(2, undoPitch(history));
        assertFalse(history.canUndo(), "the oldest action was dropped by the count cap");
    }

    @Test
    void anOversizedActionIsKeptSoUndoStillWorks() {
        // A clear-all on a very long song exceeds the whole budget on its own. Dropping it would leave
        // the player unable to undo the clear they just did, which is the one thing undo is for.
        EditHistory history = new EditHistory(50, SMALL_BUDGET);

        history.pushAction(EditAction.clearAll(notes(5_000)));

        assertEquals(1, history.getHistorySize(), "a single action is never dropped, however large");
        assertEquals(5_000, history.undo().getOldNotes().size(), "the clear is still undoable");
        assertTrue(history.getRetainedBytes() > SMALL_BUDGET);
    }

    @Test
    void theOldestActionsAreDroppedOnceTheBudgetIsCrossed() {
        EditHistory history = new EditHistory(50, SMALL_BUDGET);

        // Each clear-all of 10 notes estimates ~550 B, so a 2 KB budget holds three of them and every
        // further push drops exactly one from the oldest end.
        for (int i = 0; i < 10; i++) {
            history.pushAction(EditAction.clearAll(notes(10)));
        }

        assertEquals(3, history.getHistorySize(), "the budget should hold three of these actions");
        assertTrue(history.getRetainedBytes() <= SMALL_BUDGET,
                "retained " + history.getRetainedBytes() + " bytes against a budget of " + SMALL_BUDGET);

        // The surviving actions are the newest ones, so undo still walks back the recent edits.
        int undos = 0;
        while (history.canUndo()) {
            assertNotNull(history.undo());
            undos++;
        }
        assertEquals(3, undos, "the count cap must not have trimmed anything here");
    }

    @Test
    void redoHistoryIsDroppedAndItsBytesWithIt() {
        EditHistory history = new EditHistory(50, SMALL_BUDGET);
        history.pushAction(EditAction.clearAll(notes(10)));
        history.pushAction(EditAction.clearAll(notes(10)));
        history.undo();

        history.pushAction(EditAction.addNote(note(7)));

        assertNull(history.redo(), "a new action must discard the redo tail");
        assertEquals(
                EditHistory.estimateBytes(EditAction.clearAll(notes(10)))
                        + EditHistory.estimateBytes(EditAction.addNote(note(7))),
                history.getRetainedBytes(),
                "the discarded redo tail is still on the books");
    }

    @Test
    void clearingTheHistoryReleasesTheBudget() {
        EditHistory history = new EditHistory(50, SMALL_BUDGET);
        history.pushAction(EditAction.clearAll(notes(10)));

        history.clear();

        assertEquals(0, history.getRetainedBytes());
        assertEquals(0, history.getHistorySize());
        assertFalse(history.canUndo());
    }

    @Test
    void theEstimateGrowsWithTheNotesAnActionCarries() {
        long single = EditHistory.estimateBytes(EditAction.addNote(note(1)));
        long hundred = EditHistory.estimateBytes(EditAction.clearAll(notes(100)));

        assertTrue(hundred > single * 20,
                "an action holding 100 notes (" + hundred + ") must estimate far larger than one "
                        + "holding a single note (" + single + ")");
        assertEquals(0, EditHistory.estimateBytes(null));
    }

    private static int undoPitch(EditHistory history) {
        EditAction action = history.undo();
        assertNotNull(action);
        return action.getNewNotes().get(0).getPitch();
    }

    private static MusicNote note(int pitch) {
        return new MusicNote(pitch, pitch);
    }

    private static List<MusicNote> notes(int count) {
        List<MusicNote> notes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            notes.add(new MusicNote(i % 88, i));
        }
        return notes;
    }
}
