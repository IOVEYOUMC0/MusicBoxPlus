package com.huidu.musicboxplus.api.event;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Fired when a player switches to the next or previous song.
// Not cancellable: the switch has already taken place by the time listeners run.
public class MusicBoxSongChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MusicBoxSongPlayer player;
    private final Location location;
    private final MusicBoxSong oldSong;
    private final MusicBoxSong newSong;

    public MusicBoxSongChangeEvent(MusicBoxSongPlayer player, @Nullable MusicBoxSong oldSong, @Nullable MusicBoxSong newSong) {
        this.player = player;
        this.location = EventLocations.snapshot(player);
        this.oldSong = oldSong;
        this.newSong = newSong;
    }

    // The player that performed the switch.
    @NotNull
    public MusicBoxSongPlayer getPlayer() {
        return player;
    }

    // The song playing before the switch; null if there was none.
    @Nullable
    public MusicBoxSong getOldSong() {
        return oldSong;
    }

    // The song playing after the switch; null if there is none.
    @Nullable
    public MusicBoxSong getNewSong() {
        return newSong;
    }


    // Snapshot of the player's location, captured when the event was constructed.
    // Returns null for non-positional players; on the returned Location, getWorld() may itself
    // be null if the world has since been unloaded.
    @Nullable
    public Location getLocation() {
        return location == null ? null : location.clone();
    }


    @NotNull
    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}