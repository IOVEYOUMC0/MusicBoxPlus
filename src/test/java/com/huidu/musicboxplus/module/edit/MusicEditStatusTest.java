package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Toolbar tooltip values. These are the numbers a player reads while editing, so an off-by-one here
// is a visible lie ("bars 1-8" when 1-7 are on screen) rather than a crash.
class MusicEditStatusTest {

    @Test
    void pitchRangeCoversTheVisibleRowsInclusively() {
        // Four rows from C4 (48): 48,49,50,51.
        assertEquals("C5 - D#5", MusicEditStatus.pitchRange(48, 4, 119));
    }

    @Test
    void pitchRangeStopsAtTheHighestReachablePitch() {
        // Two rows of room left below maxPitch 48.
        assertEquals("C5 - C5", MusicEditStatus.pitchRange(48, 4, 48));
        assertEquals("A#4 - C5", MusicEditStatus.pitchRange(46, 4, 48));
    }

    @Test
    void pitchRangeNeverStartsBelowZero() {
        assertEquals("C1 - D#1", MusicEditStatus.pitchRange(-5, 4, 119));
    }

    @Test
    void aWindowAboveTheRangeCollapsesInsteadOfPrintingAReversedRange() {
        assertEquals("C6 - C6", MusicEditStatus.pitchRange(60, 4, 48),
                "maxPitch shrunk below the offset; a reversed range would be nonsense");
    }

    @Test
    void tickRangeIsInclusiveOfTheWholePage() {
        assertEquals("0 - 6", MusicEditStatus.tickRange(0, 7));
        assertEquals("7 - 13", MusicEditStatus.tickRange(1, 7));
        assertEquals("21 - 27", MusicEditStatus.tickRange(3, 7));
    }

    @Test
    void tickRangeSurvivesADegenerateWidth() {
        assertEquals("0 - 0", MusicEditStatus.tickRange(0, 0), "a zero-width page still shows one tick");
        assertEquals("0 - 0", MusicEditStatus.tickRange(-2, -1));
    }

    @Test
    void replacementsKeepThePairOrderTheItemBuilderScans() {
        String[] values = MusicEditStatus.replacements(48, 4, 119, 1, 7, 120, "4/4", 4, 42);

        Map<String, String> byKey = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            byKey.put(values[i], values[i + 1]);
        }
        assertEquals(8, byKey.size(), "every key must appear exactly once");
        assertEquals("48", byKey.get("{pitchOffset}"));
        assertEquals("C5 - D#5", byKey.get("{pitchRange}"));
        assertEquals("1", byKey.get("{tickOffset}"));
        assertEquals("7 - 13", byKey.get("{tickRange}"));
        assertEquals("120", byKey.get("{bpm}"));
        assertEquals("4/4", byKey.get("{timeSignature}"));
        assertEquals("4", byKey.get("{subdivision}"));
        assertEquals("42", byKey.get("{notes}"));
        assertEquals(0, values.length % 2, "an odd-length array would shift every later value");
    }

    @Test
    void everyPlaceholderIsBraced() {
        String[] values = MusicEditStatus.replacements(0, 1, 1, 0, 1, 1, "4/4", 1, 0);
        for (int i = 0; i < values.length; i += 2) {
            assertTrue(values[i].startsWith("{") && values[i].endsWith("}"),
                    "placeholder must keep its braces: " + values[i]);
        }
    }
}
