package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.config.MusicEditorConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The editor window's layout: which slots form the edit area, and how a layout slot maps onto the
 * player's own inventory.
 *
 * <p>Split out of {@code MusicEditGUI}, which carried the parse, the derived column/row counts, the
 * slot translations and the character lookup alongside playback, rendering and session handling.
 * This part is pure data and arithmetic: no Bukkit calls, no player state, so it is also the one
 * piece of the editor that can be reasoned about without a running server.
 *
 * <p>Layout rows 0..5 are the top inventory, row 9 is the player's hotbar and rows 6..8 are the
 * player's main inventory; {@link #layoutSlotToPlayerSlot} is the only place that mapping lives.
 */
final class MusicEditLayout {

    private String[] lines = new String[0];
    private final List<Integer> editAreaSlots = new ArrayList<>();
    private final Map<Integer, Integer> editAreaSlotIndexes = new HashMap<>();
    // -1 means "not computed yet"; the layout is fixed until the next parse.
    private int cachedColumns = -1;
    private int cachedRows = -1;

    /** Re-reads the edit area from the GUI config. Safe to call again after a config reload. */
    void parse(MusicEditorConfig config) {
        editAreaSlots.clear();
        editAreaSlotIndexes.clear();
        lines = new String[0];
        cachedColumns = -1;
        cachedRows = -1;

        String layout = config.getLayout();
        if (layout == null || layout.isEmpty()) {
            return;
        }

        String[] parsed = layout.split("\n");
        lines = parsed;
        for (int row = 0; row < parsed.length && row < 6; row++) {
            String line = parsed[row];
            for (int col = 0; col < line.length() && col < 9; col++) {
                if (line.charAt(col) == config.getEditAreaChar()) {
                    int slot = row * 9 + col;
                    editAreaSlotIndexes.put(slot, editAreaSlots.size());
                    editAreaSlots.add(slot);
                }
            }
        }
    }

    /** Edit-area slots, in layout order. Read-only view; the owner must not add to it. */
    List<Integer> editAreaSlots() {
        return editAreaSlots;
    }

    /** Index of a slot within the edit area, or null when the slot is not part of it. */
    Integer indexOf(int slot) {
        return editAreaSlotIndexes.get(slot);
    }

    boolean isEditAreaSlot(int slot) {
        return editAreaSlotIndexes.containsKey(slot);
    }

    int lineCount() {
        return lines.length;
    }

    /** Layout row, or null when the row is outside the configured layout. */
    String line(int row) {
        return row >= 0 && row < lines.length ? lines[row] : null;
    }

    /** Width of the edit area in slots, 7 when the layout defines none. */
    int columns() {
        if (cachedColumns >= 0) {
            return cachedColumns;
        }
        if (editAreaSlots.isEmpty()) {
            cachedColumns = 7;
            return cachedColumns;
        }
        int minCol = 9;
        int maxCol = 0;
        for (int slot : editAreaSlots) {
            int col = slot % 9;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }
        cachedColumns = maxCol - minCol + 1;
        return cachedColumns;
    }

    /** Height of the edit area in slots, 6 when the layout defines none. */
    int rows() {
        if (cachedRows >= 0) {
            return cachedRows;
        }
        if (editAreaSlots.isEmpty()) {
            cachedRows = 6;
            return cachedRows;
        }
        int minRow = 6;
        int maxRow = 0;
        for (int slot : editAreaSlots) {
            int row = slot / 9;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        cachedRows = maxRow - minRow + 1;
        return cachedRows;
    }

    /** Layout character at a player-inventory slot, or a space when that slot has no layout entry. */
    char charAtPlayerSlot(int playerSlot) {
        int layoutSlot = playerSlotToLayoutSlot(playerSlot);
        if (layoutSlot < 0) {
            return ' ';
        }
        String line = line(layoutSlot / 9);
        int col = layoutSlot % 9;
        return line != null && col < line.length() ? line.charAt(col) : ' ';
    }

    /** Row 9 is the hotbar, rows 6..8 the main inventory; anything else has no player slot. */
    static int layoutSlotToPlayerSlot(int layoutSlot) {
        int row = layoutSlot / 9;
        int col = layoutSlot % 9;
        if (row == 9) {
            return col;
        } else if (row >= 6 && row < 9) {
            return (row - 6) * 9 + col + 9;
        }
        return -1;
    }

    static int playerSlotToLayoutSlot(int playerSlot) {
        if (playerSlot >= 0 && playerSlot < 9) {
            return 9 * 9 + playerSlot;
        } else if (playerSlot >= 9 && playerSlot < 36) {
            return 6 * 9 + (playerSlot - 9);
        }
        return -1;
    }
}
