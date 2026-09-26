package com.huidu.musicboxplus.api.player;

import org.bukkit.Location;

public interface PositionPlayer extends MusicBoxSongPlayer {
    Location getLocation();

    int getRange();

    void setRange(int range);
}

