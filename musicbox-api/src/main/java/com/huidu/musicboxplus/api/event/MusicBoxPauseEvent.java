package com.huidu.musicboxplus.api.event;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import org.bukkit.Location;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Fired when a song player is about to be paused.
// Cancelling the event (setCancelled(true)) aborts the pause and leaves playback running.
public class MusicBoxPauseEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MusicBoxSongPlayer player;
    private final Location location;
    private final MusicBoxSong currentSong;
    private boolean cancelled;

    public MusicBoxPauseEvent(MusicBoxSongPlayer player) {
        this.player = player;
        this.location = EventLocations.snapshot(player);
        this.currentSong = player.getMusicBoxSong();
    }

    @NotNull
    public MusicBoxSongPlayer getPlayer() {
        return player;
    }

    // The song playing at the moment of the pause; null when the player holds no song.
    public MusicBoxSong getCurrentSong() {
        return currentSong;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }


    // Snapshot of the player's location, taken when the event was constructed.
    // Null for players that have no position; on the returned Location, getWorld() may itself be
    // null if the world has since been unloaded.
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