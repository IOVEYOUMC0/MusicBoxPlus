package com.huidu.musicboxplus.api.player;

import org.bukkit.entity.Player;

// View-layer contract handed out by MusicBoxSongPlayer#getControl(). The concrete
// implementation lives in the GUI module; downstream plugins compile against this
// interface only and must not depend on the module's GUI class.
public interface PlayerControlGUI {

    void open(Player player);

    void close();

    void refresh();

    void stopUpdateTask();
}
