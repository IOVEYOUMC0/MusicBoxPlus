package com.huidu.musicboxplus.module.edit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The selection rectangle is resolved once per repaint now, so these pin both halves: the bounds it
// produces, and that the cheap per-cell test agrees with the old per-cell list scan it replaced.
class MusicEditSelectionRectTest {

    private static final int COLS = 7;

    private static List<Integer> area(int rows) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < rows * COLS; i++) {
            slots.add(i);
        }
        return slots;
    }

    @Test
    void nothingSelectedProducesNoRect() {
        assertNull(new MusicEditSelectionManager().selectionRect(area(3), COLS));
    }

    @Test
    void oneSlotSelectsExactlyThatCell() {
        MusicEditSelectionManager manager = new MusicEditSelectionManager();
        manager.startSelection(9);

        MusicEditSelectionManager.Rect rect = manager.selectionRect(area(3), COLS);

        assertTrue(MusicEditSelectionManager.isIndexInSelection(9, COLS, rect));
        assertFalse(MusicEditSelectionManager.isIndexInSelection(8, COLS, rect));
        assertFalse(MusicEditSelectionManager.isIndexInSelection(16, COLS, rect));
    }

    @Test
    void aRectangleCoversBothCornersInAnyDragDirection() {
        MusicEditSelectionManager forward = new MusicEditSelectionManager();
        forward.startSelection(1);
        forward.updateSelection(16);

        MusicEditSelectionManager backward = new MusicEditSelectionManager();
        backward.startSelection(16);
        backward.updateSelection(1);

        assertEquals(forward.selectionRect(area(3), COLS), backward.selectionRect(area(3), COLS),
                "dragging up-left must select the same block as dragging down-right");
    }

    @Test
    void theRectIsInclusiveOfItsBounds() {
        MusicEditSelectionManager manager = new MusicEditSelectionManager();
        manager.startSelection(0);
        manager.updateSelection(9); // rows 0-1, cols 0-2

        MusicEditSelectionManager.Rect rect = manager.selectionRect(area(3), COLS);

        assertEquals(0, rect.minRow());
        assertEquals(1, rect.maxRow());
        assertEquals(0, rect.minCol());
        assertEquals(2, rect.maxCol());
        assertTrue(MusicEditSelectionManager.isIndexInSelection(2, COLS, rect), "last column included");
        assertFalse(MusicEditSelectionManager.isIndexInSelection(3, COLS, rect), "next column excluded");
    }

    @Test
    void anAnchorOutsideTheEditAreaYieldsNoRect() {
        MusicEditSelectionManager manager = new MusicEditSelectionManager();
        manager.startSelection(999);

        assertNull(manager.selectionRect(area(3), COLS));
    }

    @Test
    void aDegenerateWidthSelectsNothing() {
        MusicEditSelectionManager manager = new MusicEditSelectionManager();
        manager.startSelection(0);

        assertNull(manager.selectionRect(area(3), 0));
        assertFalse(MusicEditSelectionManager.isIndexInSelection(0, 0, null));
    }

    @Test
    void theCheapPerCellTestMatchesThePerCellListScan() {
        List<Integer> slots = area(6);
        MusicEditSelectionManager manager = new MusicEditSelectionManager();
        manager.startSelection(8);
        manager.updateSelection(30);

        MusicEditSelectionManager.Rect rect = manager.selectionRect(slots, COLS);
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            assertEquals(manager.isSlotInSelection(slot, slots, COLS),
                    MusicEditSelectionManager.isIndexInSelection(i, COLS, rect),
                    "slot " + slot + " disagreed with the old scan");
        }
    }

    @Test
    void outOfRangeIndexesAreNotSelected() {
        MusicEditSelectionManager manager = new MusicEditSelectionManager();
        manager.startSelection(0);
        manager.updateSelection(9);

        MusicEditSelectionManager.Rect rect = manager.selectionRect(area(3), COLS);

        assertFalse(MusicEditSelectionManager.isIndexInSelection(-1, COLS, rect));
        assertFalse(MusicEditSelectionManager.isIndexInSelection(0, COLS, null));
    }
}
