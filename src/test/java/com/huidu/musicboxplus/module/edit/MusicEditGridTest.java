package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// Edit-area slot -> note cell, the arithmetic the click handler depends on. Wrong here means a note
// lands one row or one page away from where the player clicked, which is invisible until they hear it.
class MusicEditGridTest {

    @Test
    void theFirstCellIsTheTopLeftOfTheVisibleWindow() {
        MusicEditGrid.Cell cell = MusicEditGrid.resolve(0, 7, 30, 0, 119);

        assertEquals(new MusicEditGrid.Cell(30, 0), cell);
    }

    @Test
    void columnsAdvanceTickAndRowsAdvancePitch() {
        // Column 2 of row 1 in a 7-wide area.
        assertEquals(new MusicEditGrid.Cell(31, 2), MusicEditGrid.resolve(9, 7, 30, 0, 119));
    }

    @Test
    void tickOffsetPagesTheWindow() {
        // The same layout slot one page to the right is seven ticks later.
        assertEquals(new MusicEditGrid.Cell(30, 7), MusicEditGrid.resolve(0, 7, 30, 1, 119));
        assertEquals(new MusicEditGrid.Cell(30, 21), MusicEditGrid.resolve(0, 7, 30, 3, 119));
    }

    @Test
    void pitchOffsetShiftsTheWholeArea() {
        assertEquals(new MusicEditGrid.Cell(45, 0), MusicEditGrid.resolve(0, 7, 45, 0, 119));
    }

    @Test
    void rowsAboveTheHighestPlayablePitchAreNotClickable() {
        // Two rows of room left, so the third row is past maxPitch.
        assertNull(MusicEditGrid.resolve(14, 7, 44, 0, 45), "row 2 would be pitch 46");
        assertEquals(new MusicEditGrid.Cell(45, 0), MusicEditGrid.resolve(7, 7, 44, 0, 45));
    }

    @Test
    void anImpossibleIndexOrWidthResolvesToNothing() {
        assertNull(MusicEditGrid.resolve(-1, 7, 30, 0, 119));
        assertNull(MusicEditGrid.resolve(0, 0, 30, 0, 119));
        assertNull(MusicEditGrid.resolve(0, -3, 30, 0, 119));
    }

    @Test
    void aNegativePitchOffsetDoesNotProduceNegativePitches() {
        assertNull(MusicEditGrid.resolve(0, 7, -1, 0, 119),
                "pitch -1 is below the instrument range");
    }

    // The preview flash repaints one tick column; painting the wrong slots means either a stale
    // highlight left behind or the whole grid rebuilt per click.
    @Test
    void aColumnSelectsTheSlotInEveryVisibleRow() {
        // 7x3 grid: slots 0..20, one slot per row per column.
        List<Integer> area = slots(21);

        java.util.Set<Integer> column = MusicEditGrid.columnSlots(area, 2, 7, 3, 30, 0, 119);

        assertEquals(java.util.Set.of(2, 9, 16), column);
    }

    @Test
    void aColumnFollowsTheTickOffset() {
        List<Integer> area = slots(21);

        assertEquals(java.util.Set.of(0, 7, 14),
                MusicEditGrid.columnSlots(area, 7, 7, 3, 30, 1, 119),
                "tick 7 is the first column of the next page");
        assertEquals(java.util.Set.of(), MusicEditGrid.columnSlots(area, 2, 7, 3, 30, 1, 119),
                "tick 2 is off-page once offset 1 is active");
    }

    @Test
    void rowsBelowTheVisibleHeightAreExcluded() {
        // 7 wide, but the layout holds 4 rows while only 2 are visible.
        List<Integer> area = slots(28);

        assertEquals(java.util.Set.of(1, 8), MusicEditGrid.columnSlots(area, 1, 7, 2, 30, 0, 119));
    }

    @Test
    void rowsAboveMaxPitchAreExcluded() {
        List<Integer> area = slots(21);

        // Rows 30,31,32; maxPitch 31 leaves the first two.
        assertEquals(java.util.Set.of(0, 7), MusicEditGrid.columnSlots(area, 0, 7, 3, 30, 0, 31));
    }

    @Test
    void aDegenerateGridSelectsNothing() {
        assertEquals(java.util.Set.of(), MusicEditGrid.columnSlots(List.of(), 0, 7, 3, 30, 0, 119));
        assertEquals(java.util.Set.of(), MusicEditGrid.columnSlots(slots(21), 0, 0, 3, 30, 0, 119));
        assertEquals(java.util.Set.of(), MusicEditGrid.columnSlots(null, 0, 7, 3, 30, 0, 119));
    }

    private static List<Integer> slots(int count) {
        List<Integer> slots = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            slots.add(i);
        }
        return slots;
    }
}
