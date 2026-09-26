package com.huidu.musicboxplus.core.sound;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VanillaInstrumentTest {

    // Declaration order IS the NBS instrument id, and several places index by ordinal rather than
    // by name -- MusicEditSoundPlayer resolves the editor's preview sound that way.
    @Test
    void ordinalIsTheNbsId() {
        assertEquals("minecraft:block.note_block.harp", VanillaInstrument.soundNameById(0));
        assertEquals("minecraft:block.note_block.pling", VanillaInstrument.soundNameById(15));
        assertEquals("minecraft:block.note_block.trumpet", VanillaInstrument.soundNameById(16));
        assertEquals("minecraft:block.note_block.trumpet_exposed", VanillaInstrument.soundNameById(17));
        assertEquals("minecraft:block.note_block.trumpet_weathered", VanillaInstrument.soundNameById(18));
        assertEquals("minecraft:block.note_block.trumpet_oxidized", VanillaInstrument.soundNameById(19));
        assertEquals(20, VanillaInstrument.count());
    }

    // The trumpet timbres only exist from 1.26 on. On an older server the note is silent unless
    // something stands in for it, and NoteEmitter uses this table to pick what.
    @Test
    void trumpetFamilyFallsBackToDidgeridoo() {
        for (int id = 16; id <= 19; id++) {
            assertEquals(VanillaInstrument.DIDGERIDOO, VanillaInstrument.byId(id).fallback(),
                    "id " + id + " must stand in as didgeridoo, not go silent");
        }
    }

    @Test
    void instrumentsEveryServerHasNeedNoFallback() {
        for (int id = 0; id <= 15; id++) {
            assertNull(VanillaInstrument.byId(id).fallback(),
                    "id " + id + " exists on every supported server, so substituting it would "
                            + "silently change a song that plays correctly");
        }
    }
}
