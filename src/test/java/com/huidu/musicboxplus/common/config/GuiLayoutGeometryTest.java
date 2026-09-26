package com.huidu.musicboxplus.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

// Row count and slot indices are derived from the same layout string by different methods, and
// they have to agree: the row count sizes the inventory, the slot indices place items in it.
// They did not. getRowsForLayout counted the trailing newline a YAML block scalar keeps as a row,
// so ten menus opened one row taller than the layout they were built from.
class GuiLayoutGeometryTest {

    // How the config actually arrives: SnakeYAML hands a block scalar back with a trailing "\n".
    private static final String THREE_ROWS = "aaaaaaaaa\nbbbbbbbbb\nccccccccc\n";

    @Test
    void trailingNewlineIsNotARow() {
        assertEquals(3, GUIConfigManager.getRowsForLayout(THREE_ROWS, 1));
        assertEquals(3, GUIConfigManager.getRowsForLayout("aaaaaaaaa\nbbbbbbbbb\nccccccccc", 1));
    }

    // A blank row in the middle or at the top is real: it shifts every slot index after it, so
    // dropping it would put the row count and the slot maths back out of step.
    @Test
    void blankLeadingRowStillCounts() {
        assertEquals(2, GUIConfigManager.getRowsForLayout("\nbbbbbbbbb\n", 1));
        assertEquals(9, GUIConfigManager.getSlotForChar("\nbbbbbbbbb\n", 'b'));
    }

    @Test
    void rowCountAndSlotIndicesAgree() {
        int rows = GUIConfigManager.getRowsForLayout(THREE_ROWS, 1);
        // The last mapped slot must fall inside the inventory the row count sizes.
        List<Integer> slots = GUIConfigManager.getSlotsForChar(THREE_ROWS, 'c');
        assertEquals(9, slots.size());
        assertEquals(18, slots.get(0));
        assertEquals(26, slots.get(slots.size() - 1));
        assertEquals(rows * 9, 27);
    }

    @Test
    void emptyAndNullFallBackWithoutThrowing() {
        assertEquals(1, GUIConfigManager.getRowsForLayout(null, 1));
        assertEquals(2, GUIConfigManager.getRowsForLayout("   ", 2));
        assertEquals(-1, GUIConfigManager.getSlotForChar(null, 'a'));
    }

    // Six is the inventory maximum; a longer layout must be clamped, not trusted.
    @Test
    void rowCountIsClampedToSixRows() {
        assertEquals(6, GUIConfigManager.getRowsForLayout("a\nb\nc\nd\ne\nf\ng\nh\n", 1));
    }
}
