package com.huidu.musicboxplus.module.textdisplay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextDisplayOptionsTest {

    @Test
    void copyPreservesPositionAndDoubleSidedOptions() {
        TextDisplayPlayer.DisplayOptions options = TextDisplayPlayer.DisplayOptions.defaults();
        options.setHeightOffset(1.25);
        options.setXOffset(-2.5);
        options.setZOffset(3.75);
        options.setBillboardFixed(true);
        options.setFixedYaw(135.0f);
        options.setDoubleSided(true);

        TextDisplayPlayer.DisplayOptions copy = options.copy();

        assertEquals(1.25, copy.getHeightOffset());
        assertEquals(-2.5, copy.getXOffset());
        assertEquals(3.75, copy.getZOffset());
        assertTrue(copy.isBillboardFixed());
        assertEquals(135.0f, copy.getFixedYaw());
        assertTrue(copy.isDoubleSided());
    }
}
