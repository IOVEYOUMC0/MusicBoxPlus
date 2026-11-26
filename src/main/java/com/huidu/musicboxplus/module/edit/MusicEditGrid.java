package com.huidu.musicboxplus.module.edit;

/**
 * Maps an edit-area slot index onto the note grid cell it represents.
 *
 * The index is a position in the layout, not a pitch or tick: pitch runs along rows with the
 * current pitch offset, tick along columns with the current tick offset, and the layout is not
 * necessarily a full 6x9 rectangle. Getting that arithmetic wrong writes notes into the wrong cell,
 * which is why it is separated from the click handler and tested directly -- the handler around it
 * needs a live player and inventory, the arithmetic does not.
 */
final class MusicEditGrid {

    /** A cell in the note grid. */
    record Cell(int pitch, int tick) {
    }

    private MusicEditGrid() {
    }

    /**
     * Resolves a layout index to its cell. The index is a position within the edit area, as reported
     * by MusicEditLayout; columns is the edit-area width in slots and must be positive; pitchOffset is
     * the lowest visible pitch; tickOffset is the first visible tick in pages of columns; maxPitch is
     * the highest pitch the instrument range allows.
     *
     * Returns null when the index is negative, the width is not positive, or the row lands outside the
     * playable pitch range.
     */
    static Cell resolve(int layoutIndex, int columns, int pitchOffset, int tickOffset, int maxPitch) {
        if (layoutIndex < 0 || columns <= 0) {
            return null;
        }
        int localRow = layoutIndex / columns;
        int localCol = layoutIndex % columns;
        int pitch = pitchOffset + localRow;
        if (pitch < 0 || pitch > maxPitch) {
            return null;
        }
        return new Cell(pitch, localCol + tickOffset * columns);
    }

    /**
     * Visible slots that show one tick, i.e. one column of the edit area.
     *
     * The preview flash repaints a whole tick column, so the caller needs exactly these slots --
     * painting the rest of the grid for one column is 2x the edit area in rebuilt item stacks per
     * click. The walk mirrors resolve on purpose: slot i belongs to the column only when its local
     * column maps to tick under the current offset, and only when that row is still inside the
     * visible height and below maxPitch.
     *
     * The areaSlots are edit-area slots in layout order, as MusicEditLayout stores them.
     */
    static java.util.Set<Integer> columnSlots(java.util.List<Integer> areaSlots, int tick,
                                             int columns, int rows, int pitchOffset,
                                             int tickOffset, int maxPitch) {
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        if (areaSlots == null || columns <= 0) {
            return slots;
        }
        for (int i = 0; i < areaSlots.size(); i++) {
            int localRow = i / columns;
            if (localRow >= rows) {
                continue;
            }
            int localCol = i % columns;
            if (localCol + tickOffset * columns != tick) {
                continue;
            }
            if (localRow + pitchOffset > maxPitch) {
                continue;
            }
            slots.add(areaSlots.get(i));
        }
        return slots;
    }
}
