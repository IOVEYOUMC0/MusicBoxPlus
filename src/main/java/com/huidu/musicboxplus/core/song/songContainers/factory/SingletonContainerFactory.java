package com.huidu.musicboxplus.core.song.songContainers.factory;

import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.core.song.songContainers.SongContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.containers.SingletonContainer;

// Resolves a stored container id that references a single song by its content hash
// back into a one-track container wrapping that song.
public class SingletonContainerFactory implements SongContainerFactory<SingletonContainer> {

    public static final String NAME = "ID";

    @Override
    public String getKey() {
        return NAME;
    }

    @Override
    public SingletonContainer parseContainer(int id) {
        return MusicBoxSongManager.findSongByHash(id).map(SingletonContainer::new).orElse(null);
    }
}