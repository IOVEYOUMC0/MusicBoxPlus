package com.huidu.musicboxplus.core.nbs;

// A custom instrument declared by the file. soundFile is the sound file name inside the
// resource pack.
//
// pitch is the sample's own sound key -- the note the recording sits at, 0..87, default 45
// (F#4). It is a playback parameter: a note is shifted by (note key - this), not played at face
// value. core.sound.SongInstruments applies that shift via adjustKey(), so samples recorded at
// anything other than 45 no longer play (45 - pitch) semitones off.
public record RawNbsCustomInstrument(
        String name,
        String soundFile,
        int pitch,
        boolean pressKey) {
}
