package com.huidu.musicboxplus.api.player;

// Marker for players tied to a single online Player (radio, speaker) as opposed to a
// block. The wrapper access that drove the old getModel() method was internal plumbing;
// module code reaches it through the concrete player classes instead.
public interface PlayerSongPlayer extends MusicBoxSongPlayer {
}
