package com.huidu.musicboxplus.core.sound;

import com.huidu.musicboxplus.core.nbs.RawNbsCustomInstrument;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Pitch is computed as though every sample sat at key 45. A custom instrument declares the key its
// own recording sits at, so a note on it has to be moved by (45 - sampleKey) first or the whole
// instrument plays that many semitones off.
class SongInstrumentsSampleKeyTest {

    private static final int VANILLA_COUNT = 16;

    private static SongInstruments withCustomPitch(int sampleKey) {
        return SongInstruments.of(VANILLA_COUNT,
                List.of(new RawNbsCustomInstrument("pad", "pad.ogg", sampleKey, false)));
    }

    @Test
    void vanillaInstrumentsStayCentredOnKey45() {
        SongInstruments instruments = SongInstruments.of(VANILLA_COUNT, List.of());

        assertEquals(45, instruments.sampleKey(0));
        assertEquals(60, instruments.adjustKey(0, 60), "a vanilla note must not be shifted");
    }

    @Test
    void customInstrumentShiftsTheNoteIntoTheSamplesFrame() {
        SongInstruments instruments = withCustomPitch(57);
        int customId = VANILLA_COUNT;

        assertEquals(57, instruments.sampleKey(customId));
        // A sample recorded at 57 has to be played an octave down to sound at 45.
        assertEquals(33, instruments.adjustKey(customId, 45));
        assertEquals(60, instruments.adjustKey(customId, 72));
    }

    @Test
    void sampleKeyIsClampedToTheFormatRange() {
        assertEquals(87, withCustomPitch(200).sampleKey(VANILLA_COUNT));
        assertEquals(0, withCustomPitch(-5).sampleKey(VANILLA_COUNT));
    }

    @Test
    void unknownInstrumentFallsBackToTheDefaultKey() {
        SongInstruments instruments = SongInstruments.of(VANILLA_COUNT, List.of());

        assertEquals(45, instruments.sampleKey(-1));
        assertEquals(45, instruments.sampleKey(999));
        assertEquals(60, instruments.adjustKey(999, 60));
    }
}
