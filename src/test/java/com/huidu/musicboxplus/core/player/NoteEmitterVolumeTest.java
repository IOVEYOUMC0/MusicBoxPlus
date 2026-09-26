package com.huidu.musicboxplus.core.player;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NoteEmitterVolumeTest {

    private static final float EPS = 1e-6F;

    // Speaker mode ran the same song through the range term while normal playback did not, so
    // turning the speaker on made the music quieter -- 10/16 of it at the default speakerRadius.
    @Test
    void aRadiusInsideVanillaHearingRangeDoesNotCutVolume() {
        float noRange = NoteEmitter.baseVolume(100, 100, 100);
        assertEquals(noRange, NoteEmitter.baseVolume(100, 100, 100, 10F), EPS,
                "speakerRadius 10 must sound the same as no speaker at all");
        assertEquals(noRange, NoteEmitter.baseVolume(100, 100, 100, 1F), EPS);
        assertEquals(noRange, NoteEmitter.baseVolume(100, 100, 100, 16F), EPS);
    }

    // Above 16 the term is load-bearing: it is the only thing that pushes the client's attenuation
    // radius past 16 blocks, which is what jukeboxRadius 64 needs.
    @Test
    void aRadiusBeyondVanillaHearingRangeStillScalesUp() {
        assertEquals(4F, NoteEmitter.baseVolume(100, 100, 100, 64F), EPS);
        assertEquals(2F, NoteEmitter.baseVolume(100, 100, 100, 32F), EPS);
    }

    @Test
    void percentageTermsStillMultiplyOnceEach() {
        assertEquals(0.25F, NoteEmitter.baseVolume(50, 50, 100), EPS);
        assertEquals(0.25F, NoteEmitter.baseVolume(50, 50, 100, 10F), EPS);
        assertEquals(0.5F, NoteEmitter.noteVolume(1F, 100, 50), EPS);
    }
}
