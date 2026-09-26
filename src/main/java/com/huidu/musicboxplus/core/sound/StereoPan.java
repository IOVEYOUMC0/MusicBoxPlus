package com.huidu.musicboxplus.core.sound;

// Stereo panning: turns a note's and its layer's panning into a sideways offset of the
// sound source relative to the listener.
//
// Minecraft has no panning parameter. Stereo is faked by moving the sound source to one
// side of the listener, so panning has to become a distance along the listener's left
// axis, in blocks.
//
// File values run 0..200 with 100 centered, and 0 is the LEFT end. Two independent
// sources agree: the format spec says "0 is 2 blocks left, 100 is center, 200 is 2 blocks
// right", and songs that name their layers by channel line up with it -- in
// Tokyo_Teddy_Bear.nbs every layer named "L ..." has panning at or near 0 and every layer
// named "R ..." has 200, with no exceptions.
//
// The returned offset is therefore positive toward the listener's LEFT. That direction is
// the vector (cos yaw, 0, sin yaw): Bukkit's facing vector is (-sin yaw, 0, cos yaw), so
// its right-hand side is (-cos yaw, 0, -sin yaw) and the offset above is the negation of
// it. Getting this backwards mirrors both channels for the whole song and reports no
// error at all -- the only symptom is on headphones.
//
// Values match the two-step form this replaced (flip the file value to 200-x when decoding,
// then (x-100)/100*maxDistance when playing) for every input.
public final class StereoPan {

    public static final int CENTER = 100;

    // How far the source moves when panning is hard to one side, in blocks.
    public static final float DEFAULT_MAX_DISTANCE = 2f;

    private StereoPan() {
    }

    public static boolean isCentered(int filePanning) {
        return filePanning == CENTER;
    }

    // Blocks to the listener's left. Negative means to the right.
    //
    // A centered layer leaves the note's own panning alone; otherwise the two are
    // averaged, so a layer's panning pulls its notes toward one side rather than
    // displacing them wholesale.
    public static float leftOffset(int fileLayerPanning, int fileNotePanning, float maxDistance) {
        if (isCentered(fileLayerPanning)) {
            return (CENTER - fileNotePanning) / 100f * maxDistance;
        }
        return ((CENTER - fileLayerPanning) + (CENTER - fileNotePanning)) / 200f * maxDistance;
    }

    public static float leftOffset(int fileLayerPanning, int fileNotePanning) {
        return leftOffset(fileLayerPanning, fileNotePanning, DEFAULT_MAX_DISTANCE);
    }

    // A note whose layer and own panning are both centered carries no stereo information,
    // so it can skip the fake-stereo path and its extra packet.
    public static boolean isCentered(int fileLayerPanning, int fileNotePanning) {
        return isCentered(fileLayerPanning) && isCentered(fileNotePanning);
    }
}
