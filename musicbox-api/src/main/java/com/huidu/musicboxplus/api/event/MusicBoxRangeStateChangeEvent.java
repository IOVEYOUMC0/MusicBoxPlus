package com.huidu.musicboxplus.api.event;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

// Fired when a listener enters or leaves a player's range.
//
// Entering means the listener starts hearing the song from that moment, not from its start:
// playback is a live stream, so a listener who arrives halfway hears the second half.
public class MusicBoxRangeStateChangeEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final MusicBoxSongPlayer songPlayer;
    private final Player player;
    private final boolean inRange;

    public MusicBoxRangeStateChangeEvent(MusicBoxSongPlayer songPlayer, Player player, boolean inRange) {
        this.songPlayer = songPlayer;
        this.player = player;
        this.inRange = inRange;
    }

    @NotNull
    public MusicBoxSongPlayer getSongPlayer() {
        return songPlayer;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    public boolean isInRange() {
        return inRange;
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
