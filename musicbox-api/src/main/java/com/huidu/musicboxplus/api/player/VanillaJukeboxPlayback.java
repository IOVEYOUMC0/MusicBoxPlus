package com.huidu.musicboxplus.api.player;

// Marks players whose playback is driven by the vanilla jukebox block rather than the
// plugin's own engine. Such players cannot pause/resume, seek or loop control, and the
// disc keeps producing vanilla jukebox sound instead of the plugin's.
public interface VanillaJukeboxPlayback {
}
