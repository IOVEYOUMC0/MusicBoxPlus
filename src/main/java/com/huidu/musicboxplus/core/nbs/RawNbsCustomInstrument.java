package com.huidu.musicboxplus.core.nbs;

// A custom instrument declared by the file. soundFile is the sound file name inside the
// resource pack.
//
// pitch is the sample's own sound key -- the note the recording sits at, 0..87, default 45
// (F#4). It is a playback parameter: a note should be shifted by (note key - this), not
// played at face value. Nothing reads it yet, so samples recorded at anything other than
// 45 play (45 - pitch) semitones off. Every custom instrument in the current corpus uses
// 45, which is why no test or listening pass would surface it.
public record RawNbsCustomInstrument(
        String name,
        String soundFile,
        int pitch,
        boolean pressKey) {
}
