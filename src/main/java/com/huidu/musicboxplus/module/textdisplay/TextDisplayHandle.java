package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.core.song.MusicBoxSong;
import org.bukkit.Location;

// A named, placed text display tracked by TextDisplayPlayerManager. It is either the active,
// song-playing TextDisplayPlayer or a song-less IdleTextDisplay placeholder; the operations here
// are valid for both, so commands and the edit GUI can treat them uniformly. Playback-only
// actions (control panel) must be gated on isActive().
public interface TextDisplayHandle {
    String getName();

    TextDisplayPlayer.DisplayOptions getDisplayOptions();

    void refreshText();

    void adjustHeight(double delta);

    // The currently shown song; null when this is a song-less placeholder.
    MusicBoxSong getDisplaySong();

    Location getLocation();

    int getRange();

    // Audio playback range as a block radius; takes effect live for an active player.
    void setRange(int range);

    // Re-applies billboard/orientation options from getDisplayOptions() to the live display entity.
    void applyVisualOptions();

    void destroy();

    // true for a playing TextDisplayPlayer, false for an idle placeholder.
    boolean isActive();
}
