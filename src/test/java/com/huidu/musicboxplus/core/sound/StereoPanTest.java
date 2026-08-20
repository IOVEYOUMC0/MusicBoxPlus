package com.huidu.musicboxplus.core.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

// Panning parity against NoteBlockAPI.
//
// NoteBlockAPI does it in two steps: flip the file value to 200-x while decoding, then
// (x-100)/100*maxDistance while playing. Here the file value is kept as-is and the
// conversion happens once. The two must agree on every input, or both channels swap for
// the whole song -- an error nothing catches except listening on headphones.
class StereoPanTest {

    private static final float MAX = StereoPan.DEFAULT_MAX_DISTANCE;

    private static float noteBlockApiOffset(int fileLayerPanning, int fileNotePanning) {
        int layer = 200 - fileLayerPanning;
        int note = 200 - fileNotePanning;
        if (layer == 100) {
            return ((note - 100) / 100f) * MAX;
        }
        return ((layer - 100 + note - 100) / 200f) * MAX;
    }

    @Test
    void offsetMatchesNoteBlockApiOverTheWholeDomain() {
        List<String> diffs = new ArrayList<>();
        for (int layer = 0; layer <= 200; layer++) {
            for (int note = 0; note <= 200; note++) {
                float mine = StereoPan.leftOffset(layer, note, MAX);
                float theirs = noteBlockApiOffset(layer, note);
                if (mine != theirs && diffs.size() < 10) {
                    diffs.add("layerPan=" + layer + " notePan=" + note
                            + " ours=" + mine + " nba=" + theirs);
                }
            }
        }
        assertEquals(List.of(), diffs, "panning must match NoteBlockAPI exactly");
    }

    @Test
    void centerProducesNoOffset() {
        assertEquals(0f, StereoPan.leftOffset(100, 100, MAX));
        assertTrue(StereoPan.isCentered(100, 100));
    }

    // File value 0 is the left end, 200 the right one. The spec says so, and songs that
    // name their layers by channel confirm it: in Tokyo_Teddy_Bear.nbs the "L ..." layers
    // sit at 0 and the "R ..." layers at 200.
    @Test
    void fileValueZeroPansLeft() {
        assertEquals(MAX, StereoPan.leftOffset(100, 0, MAX), "file value 0 is hard left");
        assertEquals(-MAX, StereoPan.leftOffset(100, 200, MAX), "file value 200 is hard right");
    }

    @Test
    void layerAndNoteAverageWithoutExceedingTheLimit() {
        assertEquals(MAX, StereoPan.leftOffset(0, 0, MAX));
        assertEquals(0f, StereoPan.leftOffset(0, 200, MAX));
        for (int layer = 0; layer <= 200; layer++) {
            for (int note = 0; note <= 200; note++) {
                float offset = StereoPan.leftOffset(layer, note, MAX);
                assertTrue(offset >= -MAX && offset <= MAX,
                        "layerPan=" + layer + " notePan=" + note + " exceeds one side: " + offset);
            }
        }
    }
}
