package com.huidu.musicboxplus.core.nbs;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A RawNbsSong's lists must not be aliases of the caller's. The writer copies `notes` before sorting
// it, and songs are cached and compiled, so a caller that kept building into the same ArrayList would
// otherwise mutate a song that is already in use.
class RawNbsSongImmutabilityTest {

    private static RawNbsSong songWith(List<RawNbsNote> notes, List<RawNbsLayer> layers,
                                       List<RawNbsCustomInstrument> instruments) {
        return new RawNbsSong(4, 20, 8, 2, "t", "a", "o", "d", 1000, 4,
                false, 0, 0, notes, layers, instruments);
    }

    @Test
    void mutatingTheSourceListDoesNotChangeTheSong() {
        List<RawNbsNote> notes = new ArrayList<>();
        notes.add(new RawNbsNote(0, 0, 0, 45, 100, 100, 0));

        RawNbsSong song = songWith(notes, List.of(), List.of());
        notes.add(new RawNbsNote(1, 0, 0, 47, 100, 100, 0));

        assertEquals(1, song.notes().size(), "the song must keep the list it was built with");
    }

    @Test
    void theExposedListsRejectDirectMutation() {
        RawNbsSong song = songWith(List.of(new RawNbsNote(0, 0, 0, 45, 100, 100, 0)), List.of(), List.of());

        assertThrows(UnsupportedOperationException.class,
                () -> song.notes().add(new RawNbsNote(1, 0, 0, 47, 100, 100, 0)));
        assertThrows(UnsupportedOperationException.class, () -> song.layers().clear());
        assertThrows(UnsupportedOperationException.class, () -> song.customInstruments().clear());
    }

    @Test
    void nullListsBecomeEmptyInsteadOfFailingLater() {
        RawNbsSong song = songWith(null, null, null);

        assertTrue(song.notes().isEmpty());
        assertTrue(song.layers().isEmpty());
        assertTrue(song.customInstruments().isEmpty());
    }

    @Test
    void withoutCreditKeepsTheSameNoteData() {
        RawNbsSong song = songWith(List.of(new RawNbsNote(3, 1, 2, 60, 100, 100, 0)), List.of(), List.of());

        RawNbsSong stripped = song.withoutCredit();

        assertEquals(song.notes(), stripped.notes());
        assertEquals("", stripped.author());
        assertEquals("", stripped.originalAuthor());
    }
}
