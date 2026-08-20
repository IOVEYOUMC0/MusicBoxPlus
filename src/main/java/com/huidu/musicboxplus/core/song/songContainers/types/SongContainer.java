package com.huidu.musicboxplus.core.song.songContainers.types;

import com.huidu.musicboxplus.core.song.MusicBoxSong;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public interface SongContainer {
    String getNameId();

    List<MusicBoxSong> getSongs();

    default List<MusicBoxSong> getSongsShuffle() {
        ArrayList<MusicBoxSong> list = new ArrayList<>(getSongs());
        Collections.shuffle(list);
        return list;
    }

    default List<MusicBoxSong> getSongsRand(boolean rand) {
        return rand ? getSongsShuffle() : getSongs();
    }

    default List<MusicBoxSong> getAllSongs() {
        return getSongs();
    }

    default int getAllSongCount() {
        return getAllSongs().size();
    }
}