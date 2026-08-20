package com.huidu.musicboxplus.core.song.songContainers.types;

import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongContainer;

import java.util.LinkedList;
import java.util.List;

public interface SubSongContainer extends SongContainer {
    List<MusicBoxSongContainer> getSubContainers();

    SubSongContainer getParentContainer();

    @Override
    default List<MusicBoxSong> getAllSongs() {
        LinkedList<MusicBoxSong> list = new LinkedList<>(getSongs());
        for (SongContainer sub : getSubContainers()) {
            list.addAll(sub.getAllSongs());
        }
        return list;
    }

    @Override
    default int getAllSongCount() {
        int count = getSongs().size();
        for (SongContainer sub : getSubContainers()) {
            count += sub.getAllSongCount();
        }
        return count;
    }
}