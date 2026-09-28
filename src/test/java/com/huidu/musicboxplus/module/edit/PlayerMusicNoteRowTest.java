package com.huidu.musicboxplus.module.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.huidu.musicboxplus.core.db.utils.ResultSetRow;
import org.junit.jupiter.api.Test;

// PlayerMusicManager.parseNoteRow: the row -> note half of loadAllMusic.
//
// loadAllMusic now reads player_music_notes through executeStreamQuery, so a throw inside the row
// handler would abort the entire read and leave the previous cache in place instead of costing one
// song. These pin the "skip the bad row" behaviour that keeps one damaged row from costing every
// player's songbook.
//
// Only the paths that avoid resolving an instrument name are tested: MusicNote.NoteInstrument's
// class initialisation needs a registry-backed Material, so it cannot be loaded without a server
// ("No RegistryAccess implementation found" from Paper). The name -> enum mapping itself
// (parseInstrument -> NoteInstrument.parseStored) is untouched by the streaming change.
class PlayerMusicNoteRowTest {

    private final PlayerMusicManager manager = PlayerMusicManager.getInstance();

    @Test
    void missingPitchOrTickSkipsTheRowInsteadOfThrowing() {
        assertNull(manager.parseNoteRow(row(null, 100, "HARP")), "a NULL pitch cannot yield a note");
        assertNull(manager.parseNoteRow(row(45, null, "HARP")), "a NULL tick cannot yield a note");
    }

    @Test
    void nonNumericPitchOrTickSkipsTheRowInsteadOfThrowing() {
        assertNull(manager.parseNoteRow(row("forty-five", 100, null)));
        assertNull(manager.parseNoteRow(row(45, "soon", null)));
    }

    @Test
    void absentOrEmptyInstrumentsProduceANoteWithNoInstruments() {
        // A NULL `instruments` column used to NPE here (split on null); a damaged or hand-edited row
        // must still load as a playable note instead of taking the whole songbook down with it.
        for (Object stored : new Object[]{null, "", "   ", ","}) {
            MusicNote note = manager.parseNoteRow(row(45, 100, stored));
            assertNotNull(note, "stored instruments " + stored + " must still produce a note");
            assertEquals(0, note.getInstrumentCount(), "stored instruments " + stored);
        }
    }

    @Test
    void readsPitchAndTick() {
        MusicNote note = manager.parseNoteRow(row(45, 100, null));

        assertNotNull(note);
        assertEquals(45, note.getPitch());
        assertEquals(100, note.getTick());
    }

    @Test
    void numericStringsAreAccepted() {
        // MySQL can hand integer columns back as strings depending on the driver.
        MusicNote note = manager.parseNoteRow(row("45", "100", null));

        assertNotNull(note);
        assertEquals(45, note.getPitch());
        assertEquals(100, note.getTick());
    }

    private static ResultSetRow row(Object pitch, Object tick, Object instruments) {
        return ResultSetRow.builder()
                .addResultRow("music_id", "id-1")
                .addResultRow("pitch", pitch)
                .addResultRow("tick", tick)
                .addResultRow("instruments", instruments)
                .build();
    }
}
