package com.huidu.musicboxplus.core.player.playlist;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;

import java.util.Collections;
import java.util.List;

// Immutable, single-track playlist: it always sits on its one song and there is no
// navigation to speak of. Used whenever a jukebox is loaded with a single disc.
public class SingletonPlayList implements IPlayList {

    private final MusicBoxSong song;

    public SingletonPlayList(MusicBoxSong song) {
        this.song = song;
    }

    public MusicBoxSong getSong() {
        return song;
    }

    @Override
    public void next() {
    }

    @Override
    public List<MusicBoxSong> getNextSongs(int count) {
        return Collections.emptyList();
    }

    @Override
    public boolean hasNext() {
        return false;
    }

    @Override
    public boolean hasPrev() {
        return false;
    }

    @Override
    public boolean isSingleList() {
        return true;
    }

    @Override
    public List<MusicBoxSong> getPrevSongs(int count) {
        return Collections.emptyList();
    }

    @Override
    public void reset() {
    }

    @Override
    public MusicBoxSong getCurrent() {
        return song;
    }

    @Override
    public void back(int count) {
    }

    @Override
    public int getSongNum(com.huidu.musicboxplus.api.song.MusicBoxSong song) {
        return this.song == song ? 0 : -1;
    }

    @Override
    public void setSong(com.huidu.musicboxplus.api.song.MusicBoxSong song) {
    }
}