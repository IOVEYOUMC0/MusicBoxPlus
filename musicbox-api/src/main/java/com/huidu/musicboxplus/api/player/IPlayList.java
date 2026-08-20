package com.huidu.musicboxplus.api.player;

import com.huidu.musicboxplus.api.song.MusicBoxSong;

import java.util.List;

public interface IPlayList {
    void next();

    List<? extends MusicBoxSong> getNextSongs(int count);

    List<? extends MusicBoxSong> getPrevSongs(int count);

    boolean hasNext();

    boolean hasPrev();

    default boolean isSingleList() {
        return !hasNext() && !hasPrev();
    }

    MusicBoxSong getCurrent();

    void back(int count);

    int getSongNum(MusicBoxSong song);

    default boolean tryNext() {
        if (hasNext()) {
            next();
            return true;
        }
        return false;
    }

    void reset();

    default void first() {
        reset();
    }

    void setSong(MusicBoxSong song);

    default void updatePlaylist() {
    }
}

