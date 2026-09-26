package com.huidu.musicboxplus.core.playback;

import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.api.player.VanillaJukeboxPlayback;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import org.jetbrains.annotations.Nullable;

public final class PlaybackContext {
    private final @Nullable MusicBoxSongPlayer player;
    private final @Nullable MusicBoxSong song;
    private final boolean playing;
    private final boolean paused;
    private final boolean vanillaJukeboxPlayback;
    private final boolean playerScoped;
    private final boolean supportsPauseResume;
    private final boolean supportsProgressSeek;
    private final boolean supportsLoopControl;
    private final LoopMode loopMode;

    private PlaybackContext(
        @Nullable MusicBoxSongPlayer player,
        @Nullable MusicBoxSong song,
        boolean playing,
        boolean paused,
        boolean vanillaJukeboxPlayback,
        boolean playerScoped,
        boolean supportsPauseResume,
        boolean supportsProgressSeek,
        boolean supportsLoopControl,
        LoopMode loopMode
    ) {
        this.player = player;
        this.song = song;
        this.playing = playing;
        this.paused = paused;
        this.vanillaJukeboxPlayback = vanillaJukeboxPlayback;
        this.playerScoped = playerScoped;
        this.supportsPauseResume = supportsPauseResume;
        this.supportsProgressSeek = supportsProgressSeek;
        this.supportsLoopControl = supportsLoopControl;
        this.loopMode = loopMode;
    }

    public static @Nullable PlaybackContext fromPlayer(@Nullable MusicBoxSongPlayer player) {
        if (player == null) {
            return null;
        }
        MusicBoxSong song = player.getPlayList() != null ? (MusicBoxSong) player.getPlayList().getCurrent() : (MusicBoxSong) player.getMusicBoxSong();
        boolean vanillaJukeboxPlayback = player instanceof VanillaJukeboxPlayback
            && song != null
            && song.shouldUseVanillaJukeboxPlayback();
        boolean playerScoped = player instanceof PlayerSongPlayer;
        boolean supportsPauseResume = !vanillaJukeboxPlayback;
        boolean supportsProgressSeek = !vanillaJukeboxPlayback;
        boolean supportsLoopControl = !vanillaJukeboxPlayback;
        return new PlaybackContext(
            player,
            song,
            player.isPlaying(),
            player.isPaused(),
            vanillaJukeboxPlayback,
            playerScoped,
            supportsPauseResume,
            supportsProgressSeek,
            supportsLoopControl,
            player.getLoopMode()
        );
    }

    public @Nullable MusicBoxSongPlayer getPlayer() {
        return player;
    }

    public @Nullable MusicBoxSong getSong() {
        return song;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isVanillaJukeboxPlayback() {
        return vanillaJukeboxPlayback;
    }

    public boolean isPlayerScoped() {
        return playerScoped;
    }

    public boolean supportsPauseResume() {
        return supportsPauseResume;
    }

    public boolean supportsProgressSeek() {
        return supportsProgressSeek;
    }

    public boolean supportsLoopControl() {
        return supportsLoopControl;
    }

    public LoopMode getLoopMode() {
        return loopMode;
    }
}
