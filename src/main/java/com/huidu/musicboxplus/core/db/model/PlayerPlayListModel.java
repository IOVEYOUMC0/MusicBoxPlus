package com.huidu.musicboxplus.core.db.model;

import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.songContainers.factory.ListContainerFactory;
import com.huidu.musicboxplus.core.song.songContainers.types.SongContainer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    // Membership cache behind hasSong(). Dropped by every mutation; see hasSong.
    private Set<MusicBoxSong> membership;

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
            this.membership = null;
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
                this.membership = null;
            }
        }
        return true;
    }

    // One membership set instead of a LinkedList scan per candidate. Adding a container with m songs
    // to a playlist that already holds n was O(n*m) pointer chasing -- and the caller filtered the
    // same candidates with hasSong() first, so it was paid twice per song. The set is built from the
    // list here rather than reused from membership: this path decides what gets written to the
    // database, so it must not depend on a cache that getSongs() callers can invalidate.
    public boolean addSongsBulk(List<MusicBoxSong> songsToAdd) {
        if (songsToAdd == null || songsToAdd.isEmpty()) {
            return true;
        }

        Set<MusicBoxSong> present = new HashSet<>(this.songs);
        List<MusicBoxSong> addedSongs = new LinkedList<>();
        for (MusicBoxSong song : songsToAdd) {
            if (song == null || !present.add(song)) {
                continue;
            }
            this.songs.add(song);
            addedSongs.add(song);
        }

        if (addedSongs.isEmpty()) {
            return true;
        }

        if (this.save()) {
            this.membership = null;
            return true;
        }

        this.songs.removeAll(addedSongs);
        return false;
    }

    /**
     * Whether this playlist already holds the song.
     *
     * Answered from a set built on first use: the add-song picker calls this once for every song item
     * it renders, and each call used to scan the whole LinkedList.
     *
     * The set is only ever used for display, so a stale one is harmless -- the worst case is a lore
     * line that offers to add a song the playlist already holds, and the add itself re-checks against
     * the list. It is dropped by every mutation below. MusicBoxSong does not override equals, so set
     * membership means the same thing as the list's identity-based contains.
     */
    public boolean hasSong(MusicBoxSong song) {
        if (song == null) {
            return false;
        }
        Set<MusicBoxSong> known = this.membership;
        if (known == null) {
            known = new HashSet<>(this.songs);
            this.membership = known;
        }
        return known.contains(song);
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
