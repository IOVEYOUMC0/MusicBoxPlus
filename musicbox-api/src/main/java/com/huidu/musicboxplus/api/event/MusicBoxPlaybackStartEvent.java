package com.huidu.musicboxplus.api.event;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Fired when a block-hosted player (jukebox, sign, text display) begins playing, once per
// player. Personal, radio and speaker playback do not fire it.
//
// The other events in this package all report a change to something already running, so a
// listener that only has those can never learn that playback began -- it has to infer it from
// an interaction it happened to see. That leaves out every start no player triggered: a disc
// delivered by a hopper, a redstone signal, a command, or a player rebuilt after a reload.
//
// Not cancellable. By the time it fires the player exists and is running; refusing playback
// belongs earlier, at the interaction.
public class MusicBoxPlaybackStartEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MusicBoxSongPlayer player;
    private final Location location;
    private final MusicBoxSong song;

    public MusicBoxPlaybackStartEvent(MusicBoxSongPlayer player) {
        this.player = player;
        this.location = EventLocations.snapshot(player);
        this.song = player.getMusicBoxSong();
    }

    @NotNull
    public MusicBoxSongPlayer getPlayer() {
        return player;
    }

    // The song being started; null when the player has none yet.
    @Nullable
    public MusicBoxSong getSong() {
        return song;
    }

    // Snapshot of the player's location, taken when the event was constructed.
    // null for players that have no position; on the returned Location, getWorld() may itself be
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
