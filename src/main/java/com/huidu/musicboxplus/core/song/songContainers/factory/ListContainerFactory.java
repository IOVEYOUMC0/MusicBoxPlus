package com.huidu.musicboxplus.core.song.songContainers.factory;

import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.core.song.songContainers.SongContainerFactory;

// Resolves a stored container id that names a saved playlist back into the database
// row. A missing or unreadable row yields null, which the caller treats as absent.
public class ListContainerFactory implements SongContainerFactory<PlayerPlayListModel> {

    public static final String NAME = "LIST";

    @Override
    public String getKey() {
        return NAME;
    }

    @Override
    public PlayerPlayListModel parseContainer(int id) {
        try {
            return DatabaseLoader.getBase().getPlayListById(id);
        } catch (Exception e) {
            RuntimeDatabaseUtils.logFailure("resolve playlist container", e);
            return null;
        }
    }
}