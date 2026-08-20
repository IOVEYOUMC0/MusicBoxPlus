package com.huidu.musicboxplus.core.song.songContainers;

import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;

public interface SongContainerFactory<T extends SongContainer> {
    public String getKey();

    T parseContainer(int id);
}

