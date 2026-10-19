package com.huidu.musicboxplus.common.utils;

import java.util.concurrent.ThreadLocalRandom;

// The colour of a vanilla NOTE particle.
//
// The note particle carries no data payload: its colour rides in the particle packet's x-offset, as a
// fraction of one hue turn. 0.0 and 1.0 are the same colour, and the note-block palette divides that
// turn into 24 steps (0 = F#, 1/24 = G, ... 6/24 = C ... 24/24 = F# an octave up). Vanilla's own
// jukebox sends `getRandom().nextInt(4) / 24.0f`; a note block sends its note's step divided by 24.
//
// This used to be passed as the raw step (0-24). The client reads that field as *turns*, so every
// whole number wraps to the same angle and all of those particles came out one colour: the note
// colour was effectively lost while the code read as if it were setting it. Keeping the division in
// one place is cheaper than remembering it at every spawnParticle call.
public final class NoteParticleColor {

    // Steps 0..24 inclusive: 25 values, the last one wrapping onto the first.
    public static final int STEPS = 25;

    private NoteParticleColor() {
    }

    /**
     * The x-offset a NOTE particle needs for note {@code step}, in [0, 1].
     *
     * Out-of-range steps wrap rather than being clamped, matching the palette: step 25 is the same
     * colour as step 0, and step -1 is the last colour of the turn below.
     */
    public static double ofStep(int step) {
        return (double) Math.floorMod(step, STEPS) / (STEPS - 1);
    }

    /** A colour picked from the whole palette. */
    public static double random() {
        return ofStep(ThreadLocalRandom.current().nextInt(STEPS));
    }
}
