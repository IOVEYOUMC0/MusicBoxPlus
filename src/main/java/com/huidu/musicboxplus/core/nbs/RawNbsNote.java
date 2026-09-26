package com.huidu.musicboxplus.core.nbs;

// One note as stored in the file. Every field is an int: instrument, key and panning are
// unsigned bytes in the format, and only finePitch is a signed 16-bit value.
//
// instrument is the file's own numbering, not remapped against the runtime instrument
// count; core.sound.SongInstruments resolves it.
//
// panning is the raw file value: 0..200, 100 centered, 0 being the LEFT end. Do not treat
// it as a signed offset -- core.sound.StereoPan converts it. Reversing the direction
// mirrors both channels and raises no error.
public record RawNbsNote(
        int tick,
        int layer,
        int instrument,
        int key,
        int velocity,
        int panning,
        int finePitch) {
}
