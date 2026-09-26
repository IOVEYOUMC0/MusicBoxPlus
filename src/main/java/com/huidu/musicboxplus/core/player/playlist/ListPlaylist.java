package com.huidu.musicboxplus.core.player.playlist;

import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.utils.classes.PeekList;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;

import java.util.List;

// Playlist backed by an ordered list of tracks, navigated through a PeekList cursor
// that supports sequential and shuffle views plus a bounded end marker.
public class ListPlaylist implements IPlayList {

    private final PeekList<MusicBoxSong> peekList;

    public ListPlaylist(List<MusicBoxSong> songs) {
        this(songs, false);
    }

    public ListPlaylist(List<MusicBoxSong> songs, boolean hasEnd) {
        if (songs.isEmpty()) {
            throw new IllegalArgumentException("Playlist cannot be empty");
        }
        this.peekList = new PeekList<>(songs, hasEnd);
    }

    public static ListPlaylist fromContainer(SongContainer container, boolean rand, boolean hasEnd) {
        return new ListPlaylist(rand ? container.getSongsShuffle() : container.getAllSongs(), hasEnd);
    }

    @Override
    public void next() {
        peekList.next();
    }

    @Override
    public List<MusicBoxSong> getNextSongs(int count) {
        return peekList.getNextElements(count);
    }

    @Override
    public boolean hasNext() {
        return peekList.hasNext();
    }

    @Override
    public boolean hasPrev() {
        return peekList.hasPrev();
    }

    @Override
    public List<MusicBoxSong> getPrevSongs(int count) {
        return peekList.getPrevElements(count);
    }

    @Override
    public MusicBoxSong getCurrent() {
        return peekList.current();
    }

    @Override
    public void back(int count) {
        for (int i = 0; i < count; i++) {
            peekList.prev();
        }
    }

    @Override
    public int getSongNum(com.huidu.musicboxplus.api.song.MusicBoxSong song) {
        return peekList.getIndexOf((MusicBoxSong) song);
    }

    @Override
    public void setSong(com.huidu.musicboxplus.api.song.MusicBoxSong song) {
        peekList.moveTo((MusicBoxSong) song);
    }

    @Override
    public void reset() {
        peekList.reset();
    }

    public List<MusicBoxSong> getSongsSnapshot() {
        return List.copyOf(peekList.getList());
    }

    public int getCurrentIndex() {
        return peekList.getCurrent();
    }

    public boolean hasEnd() {
        return peekList.isHasEnd();
    }
}