package com.huidu.musicboxplus.core.song.songContainers.factory;

import com.huidu.musicboxplus.core.song.MusicBoxSongContainer;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.SongContainerFactory;

// Resolves a stored container id that points at a folder (a "chest") of songs back
// into the live container object. The id is the numeric key the database persists.
public class FolderContainerFactory implements SongContainerFactory<MusicBoxSongContainer> {

    public static final String NAME = "CHEST";

    @Override
    public String getKey() {
        return NAME;
    }

    @Override
    public MusicBoxSongContainer parseContainer(int id) {
        return MusicBoxSongManager.findContainerById(id).orElse(null);
    }
}