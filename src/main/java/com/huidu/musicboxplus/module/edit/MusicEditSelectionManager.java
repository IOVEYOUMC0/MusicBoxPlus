package com.huidu.musicboxplus.module.edit;

import java.util.ArrayList;
import java.util.List;

final class MusicEditSelectionManager {
    private MusicEditGUI.SelectionMode mode = MusicEditGUI.SelectionMode.NONE;
    private int startSlot = -1;
    private int endSlot = -1;

    void beginOrExpandSelection(int slot) {
        if (mode == MusicEditGUI.SelectionMode.NONE || mode == MusicEditGUI.SelectionMode.SELECTED) {
            mode = MusicEditGUI.SelectionMode.SELECTING;
            startSlot = slot;
            endSlot = slot;
            return;
        }
        if (mode == MusicEditGUI.SelectionMode.SELECTING) {
            endSlot = slot;
        }
    }

    void startSelection(int slot) {
        startSlot = slot;
        endSlot = slot;
        mode = MusicEditGUI.SelectionMode.SELECTING;
    }

    void updateSelection(int slot) {
        if (mode == MusicEditGUI.SelectionMode.SELECTING) {
            endSlot = slot;
        }
    }

    void endSelection(int slot) {
        endSlot = slot;
        mode = MusicEditGUI.SelectionMode.SELECTED;
    }

    void clearSelection() {
        mode = MusicEditGUI.SelectionMode.NONE;
        startSlot = -1;
        endSlot = -1;
    }

    boolean isInSelectionMode() {
        return mode != MusicEditGUI.SelectionMode.NONE;
    }

    MusicEditGUI.SelectionMode getMode() {
        return mode;
    }

    List<MusicNote> getSelectedNotes(PlayerMusic music, List<Integer> editAreaSlots, int editCols, int pitchOffset, int tickOffset, int maxPitch) {
        List<MusicNote> notes = new ArrayList<>();
        if (startSlot < 0 || endSlot < 0) {
            return notes;
        }

        int startIndex = editAreaSlots.indexOf(startSlot);
        int endIndex = editAreaSlots.indexOf(endSlot);
        if (startIndex < 0 || endIndex < 0) {
            return notes;
        }

        int startRow = startIndex / editCols;
        int startCol = startIndex % editCols;
        int endRow = endIndex / editCols;
        int endCol = endIndex % editCols;

        int minRow = Math.min(startRow, endRow);
        int maxRow = Math.max(startRow, endRow);
        int minCol = Math.min(startCol, endCol);
        int maxCol = Math.max(startCol, endCol);

        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                int pitch = pitchOffset + row;
                int tick = col + tickOffset * editCols;
                if (pitch > maxPitch) {
                    continue;
                }
                MusicNote note = music.getNote(pitch, tick);
                if (note != null) {
                    notes.add(note);
                }
            }
        }
        return notes;
    }

    boolean isSlotInSelection(int slot, List<Integer> editAreaSlots, int editCols) {
        if (startSlot < 0 || endSlot < 0) {
            return false;
        }

        int index = editAreaSlots.indexOf(slot);
        if (index < 0) {
            return false;
        }

        int startIndex = editAreaSlots.indexOf(startSlot);
        int endIndex = editAreaSlots.indexOf(endSlot);
        if (startIndex < 0 || endIndex < 0) {
            return false;
        }

        int startRow = startIndex / editCols;
        int startCol = startIndex % editCols;
        int endRow = endIndex / editCols;
        int endCol = endIndex % editCols;

        int minRow = Math.min(startRow, endRow);
        int maxRow = Math.max(startRow, endRow);
        int minCol = Math.min(startCol, endCol);
        int maxCol = Math.max(startCol, endCol);

        int row = index / editCols;
        int col = index % editCols;
        return row >= minRow && row <= maxRow && col >= minCol && col <= maxCol;
    }
}
