package com.huidu.musicboxplus.core.db.model;

import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.songContainers.factory.ListContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PlayerPlayListModel
implements SongContainer {
    private int id;
    private final UUID owner;
    private String name;
    private final List<MusicBoxSong> songs = new LinkedList<MusicBoxSong>();

    // Song hashes that are stored for this playlist but whose song is not in the current in-memory
    // index -- a .nbs that was renamed, moved or removed, or one that had not finished loading when
    // the playlist was read.
    //
    // They are deliberately invisible: getSongs() returns only what resolved, so the GUI never shows
    // a broken entry. But savePlayList() rewrites the playlist's rows from getSongs() alone, so
    // without carrying them here every save -- and addSong/removeSong/addSongsBulk all save --
    // deleted them from the database for good. Reading a playlist preserved them; touching one
    // destroyed them. See AbstractBase.extractPlayList and savePlayList.
    //
    // Nothing removes an entry from this list, which is the point: a song whose file is temporarily
    // missing must not lose its place in every playlist that referenced it. A hash that is gone for
    // good therefore stays in the table, invisible, which costs one row.
    private final List<Integer> unresolvedHashes = new LinkedList<Integer>();

    public List<Integer> getUnresolvedHashes() {
        return this.unresolvedHashes;
    }

    public Optional<PlayerWrapper> getOwnerWrapper() {
        Player player = Bukkit.getPlayer(this.owner);
        if (player == null) {
            return Optional.empty();
        }
        return Optional.of(PlayerWrapper.getInstance(player));
    }

    public boolean save() {
        try {
            DatabaseLoader.getBase().savePlayList(this);
            return true;
        } catch (Exception e) {
            RuntimeDatabaseUtils.logFailure("save playlist", e);
            return false;
        }
    }

    public boolean delete() {
        try {
            DatabaseLoader.getBase().deleteMe(this);
            return true;
        } catch (Exception e) {
            RuntimeDatabaseUtils.logFailure("delete playlist", e);
            return false;
        }
    }

    public boolean addSong(MusicBoxSong song) {
        if (song != null && !this.songs.contains(song)) {
            this.songs.add(song);
            if (!this.save()) {
                this.songs.remove(song);
                return false;
            }
        }
        return true;
    }

    public boolean removeSong(MusicBoxSong song) {
        if (song != null) {
            int index = this.songs.indexOf(song);
            if (index >= 0) {
                this.songs.remove(index);
                if (!this.save()) {
                    this.songs.add(index, song);
                    return false;
                }
            }
        }
        return true;
    }

    public boolean addSongsBulk(List<MusicBoxSong> songsToAdd) {
        if (songsToAdd == null || songsToAdd.isEmpty()) {
            return true;
        }

        List<MusicBoxSong> addedSongs = new LinkedList<>();
        for (MusicBoxSong song : songsToAdd) {
            if (song == null || this.songs.contains(song)) {
                continue;
            }
            this.songs.add(song);
            addedSongs.add(song);
        }

        if (addedSongs.isEmpty()) {
            return true;
        }

        if (this.save()) {
            return true;
        }

        this.songs.removeAll(addedSongs);
        return false;
    }

    @Override
    public String getNameId() {
        return ListContainerFactory.NAME + ":" + this.id;
    }

    public int getId() {
        return this.id;
    }

    public UUID getOwner() {
        return this.owner;
    }

    public String getName() {
        return this.name;
    }

    @Override
    public List<MusicBoxSong> getSongs() {
        return this.songs;
    }

    public void setId(int id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PlayerPlayListModel(int id, UUID owner, String name) {
        this.id = id;
        this.owner = owner;
        this.name = name;
    }
}
