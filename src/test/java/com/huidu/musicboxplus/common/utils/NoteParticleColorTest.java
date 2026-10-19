package com.huidu.musicboxplus.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

// The NOTE particle colour: the palette step divided by 24, not the step itself.
//
// The colour travels in the particle packet's x-offset, which the client reads as a fraction of one
// hue turn. Passing the raw step (0-24) made every particle the same colour, because a whole number
// of turns is the same angle as none: the code read as if it set a colour and did not. These pin the
// conversion, including the wrap at 1.0 and the palette's 24 divisions.
class NoteParticleColorTest {

    @Test
    void everyStepIsAFractionOfATurn() {
        for (int step = 0; step < NoteParticleColor.STEPS; step++) {
            double color = NoteParticleColor.ofStep(step);

            assertTrue(color >= 0.0 && color <= 1.0,
                    "step " + step + " produced " + color + ", which is not a hue fraction");
            assertEquals(step / 24.0, color, 1e-12, "step " + step + " must be " + step + "/24");
        }
    }

    @Test
    void thePaletteDividesTheTurnIntoStepsOfOneTwentyFourth() {
        Set<Double> distinct = new HashSet<>();
        for (int step = 0; step < NoteParticleColor.STEPS; step++) {
            distinct.add(NoteParticleColor.ofStep(step));
        }

        // 0.0 and 1.0 are the same colour, so 25 steps give 25 distinct offsets but 24 intervals.
        assertEquals(25, distinct.size(), "each step must be a distinct offset");
        assertEquals(1.0 / 24.0, NoteParticleColor.ofStep(1), 1e-12);
        assertEquals(6.0 / 24.0, NoteParticleColor.ofStep(6), 1e-12, "step 6 is C, a quarter turn");
    }

    @Test
    void theEndsOfTheTurnAreTheSameColourByDesign() {
        assertEquals(0.0, NoteParticleColor.ofStep(0));
        assertEquals(1.0, NoteParticleColor.ofStep(24));
    }

    @Test
    void stepsOutsideThePaletteWrapInsteadOfLeavingTheRange() {
        // Wrapping matters because a caller may hand over a note number or an accumulated step:
        // clamping would pile every out-of-range value onto one colour. -1 is the last colour of the
        // turn *below*, which in the palette is 24 (the same offset as 1.0), not 23.
        assertEquals(NoteParticleColor.ofStep(0), NoteParticleColor.ofStep(25));
        assertEquals(NoteParticleColor.ofStep(24), NoteParticleColor.ofStep(-1));
        assertEquals(NoteParticleColor.ofStep(0), NoteParticleColor.ofStep(-25));
        assertEquals(NoteParticleColor.ofStep(4), NoteParticleColor.ofStep(29));
        assertTrue(NoteParticleColor.ofStep(-1000) >= 0.0);
        assertTrue(NoteParticleColor.ofStep(Integer.MIN_VALUE) <= 1.0);
    }

    @Test
    void randomStaysInsideThePalette() {
        for (int i = 0; i < 200; i++) {
            double color = NoteParticleColor.random();

            assertTrue(color >= 0.0 && color <= 1.0, "random produced " + color);
            // Every value must be a palette step, or the drawn colour is not one of the 25.
            double step = color * 24.0;
            assertEquals(Math.rint(step), step, 1e-9, "random produced " + color + ", not a palette step");
        }
    }
}
