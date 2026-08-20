package com.huidu.musicboxplus.api.player.model;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PlayerControlGUI;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import org.bukkit.entity.Player;

// Mutable playback state behind a MusicBoxSongPlayer. Exposed to the api layer so the
// player interface's default methods can delegate; it is @ApiStatus.Internal-style
// plumbing, not stable API for third-party plugins.
public interface MusicBoxSongPlayerModel {

    PlayerControlGUI getControlGUI();

    MusicBoxSong getMusicBoxSong();

    IPlayList getPlayList();

    void setPlayList(IPlayList playList);

    PositionPlayer getPositionPlayer();

    LoopMode getLoopMode();

    void setLoopMode(LoopMode loopMode);

    LoopMode toggleLoopMode();

    void pingSongEnded();

    void onSongEnd();

    boolean isNextCreated();

    void setNextCreated(boolean nextCreated);

    void createNextPlayer();

    MusicBoxSongPlayer getMusicBoxSongPlayer();

    boolean hasApiPlayer();

    float getPlaybackSpeedMultiplier();

    int getVolume();

    boolean isMuted(Player player);

    void mutePlayer(Player player);

    void unmutePlayer(Player player);

    void toggleMute(Player player);
}
