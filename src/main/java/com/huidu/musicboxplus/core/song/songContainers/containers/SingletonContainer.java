package com.huidu.musicboxplus.core.song.songContainers.containers;

import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.songContainers.factory.SingletonContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;

import java.util.Collections;
import java.util.List;

// SongContainer that wraps exactly one track. Shuffle and sequential views are the
// same here because there is nothing to reorder.
public class SingletonContainer implements SongContainer {

    private final MusicBoxSong song;

    public SingletonContainer(MusicBoxSong song) {
        this.song = song;
    }

    @Override
    public String getNameId() {
        return SingletonContainerFactory.NAME + ":" + song.getHash();
    }

    @Override
    public List<MusicBoxSong> getSongs() {
        return Collections.singletonList(song);
    }

    @Override
    public List<MusicBoxSong> getSongsShuffle() {
        return getSongs();
    }
}