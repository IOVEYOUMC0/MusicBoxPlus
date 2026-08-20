package com.huidu.musicboxplus.api.event;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Fired once when a song player is destroyed.
//
// This is the only MusicBox notification guaranteed to cover every termination path:
// a song finishing naturally, a manual stop, the block being broken, a hopper pulling the
// disc out, chunk/world unload, plugin reload and server shutdown. MusicBoxStopEvent only
// fires for an explicit stop(), so this is the event to listen to if you need to know when
// playback truly ends. It is not cancellable - by the time it fires the player is already
// being torn down.
//
// Thread: dispatched on the region thread that owns the player's block/entity (the main
// thread on plain Paper). Listeners must not assume they are on the main thread and must
// not reach across regions to touch blocks or entities elsewhere.
//
// Location: getLocation() is a snapshot taken at construction. It is never null for block
// players and null for non-positional players such as a player's personal music player.
// getWorld() may be null once the world has been unloaded, so null-check before using it.
public class MusicBoxPlayerDestroyEvent extends Event {

    // Why the player was destroyed. Append new constants at the end so existing ordinals
    // are not shifted.
    public enum DestroyReason {
        // A new player at the same location took over; playback did not stop. Changing the
        // speed and advancing to the next song both go through here: MusicBox implements
        // them by constructing a new player, which destroys the old one at that location.
        // This is a handover, not a termination - a listener must not tear down its own
        // visuals when it sees this reason.
        REPLACED,
        // The playlist finished and there is no next song
        SONG_END,
        // Explicit stop: command, GUI stop button, or a player right-clicking with a non-disc item
        MANUAL_STOP,
        // The block hosting the player is gone: broken, blown up, pushed by a piston, or replaced
        BLOCK_GONE,
        // The disc was taken out of the jukebox by a hopper or a player
        RECORD_REMOVED,
        // Chunk unloaded
        CHUNK_UNLOAD,
        // World unloaded
        WORLD_UNLOAD,
        // Plugin reload
        RELOAD,
        // Server shutdown
        SHUTDOWN,
        // Destroy path that does not fall into any of the above
        UNKNOWN
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final MusicBoxSongPlayer player;
    private final Location location;
    private final MusicBoxSong song;
    private final DestroyReason reason;

    public MusicBoxPlayerDestroyEvent(@NotNull MusicBoxSongPlayer player,
                                      @Nullable Location location,
                                      @Nullable MusicBoxSong song,
                                      @NotNull DestroyReason reason) {
        this.player = player;
        this.location = location == null ? null : location.clone();
        this.song = song;
        this.reason = reason;
    }

    // The player being destroyed. It is already mid-teardown, so do not call playback
    // control methods on it.
    @NotNull
    public MusicBoxSongPlayer getPlayer() {
        return player;
    }

    // Snapshot of the player's location (the block coordinates for a block player).
    // Null for non-positional players; getWorld() may be null once the world is unloaded.
    @Nullable
    public Location getLocation() {
        return location == null ? null : location.clone();
    }

    // The song that was playing at destroy time; may be null.
    @Nullable
    public MusicBoxSong getSong() {
        return song;
    }

    @NotNull
    public DestroyReason getReason() {
        return reason;
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
