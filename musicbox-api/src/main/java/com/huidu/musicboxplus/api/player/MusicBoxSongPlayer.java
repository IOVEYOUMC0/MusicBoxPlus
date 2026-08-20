package com.huidu.musicboxplus.api.player;

import com.huidu.musicboxplus.api.event.MusicBoxPauseEvent;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.event.MusicBoxResumeEvent;
import com.huidu.musicboxplus.api.event.MusicBoxStopEvent;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.api.player.model.MusicBoxSongPlayerModel;
import com.huidu.musicboxplus.api.song.MusicBoxSong;
import org.bukkit.Bukkit;

import java.util.Set;
import java.util.UUID;

public interface MusicBoxSongPlayer {

    // Internal. Hands out the module-layer GUI implementation, which is not
    // stable API: its signature may change or disappear at any time.
    // Third-party plugins must not call this.
    @org.jetbrains.annotations.ApiStatus.Internal
    default PlayerControlGUI getControl() {
        return getMusicBoxModel().getControlGUI();
    }

    // Null when no song is currently loaded.
    default MusicBoxSong getMusicBoxSong() {
        return getMusicBoxModel().getMusicBoxSong();
    }

    // Internal. Exposes the mutable core-layer model and is not stable API.
    // Third-party plugins should go through MusicBoxAPI and the events in api.event instead.
    @org.jetbrains.annotations.ApiStatus.Internal
    MusicBoxSongPlayerModel getMusicBoxModel();

    // Tick the player is currently on, or -1 before the first tick.
    short getTick();

    // Jumps to a tick. Used to seek from the GUI and to restore a position after a reload.
    void setTick(int tick);

    // Whether playback is running. False covers both paused and stopped.
    boolean isPlaying();

    void setPlaying(boolean playing);

    default IPlayList getPlayList() {
        return getMusicBoxModel().getPlayList();
    }

    boolean isDestroyed();

    // Driven by PlayerManager's global timer on the Bukkit main thread, every 100 ms by default.
    // Thread-unsafe world access is therefore safe here; anything expensive must be
    // moved off to an async thread by the implementation itself.
    default void tick() {
    }

    // UUIDs of the players currently listening.
    Set<UUID> getPlayers();

    // Listener management. Range tracking calls these as players walk in and out.
    void addPlayer(org.bukkit.entity.Player player);

    void removePlayer(org.bukkit.entity.Player player);

    default void onSongEnd() {
        getMusicBoxModel().pingSongEnded();
        getMusicBoxModel().onSongEnd();
    }

    // True keeps the player alive when a song finishes with no loop and no next track,
    // parking it at the end instead of destroying it; false (the default) destroys it.
    // Placed displays such as the text player return true so their hologram does not
    // vanish out of nowhere.
    default boolean isPersistentOnEnd() {
        return false;
    }

    void destroy();

    // Destroy with a stated reason. Block players override this and fire
    // MusicBoxPlayerDestroyEvent; every other implementation falls back to plain destroy().
    default void destroy(DestroyReason reason) {
        destroy();
    }

    // True for both a paused and a stopped player.
    default boolean isPaused() {
        return !isPlaying();
    }

    // Pauses playback, keeping the current position. Fires MusicBoxPauseEvent, which
    // other plugins may cancel: returns true when the pause actually happened,
    // false when a listener cancelled it.
    default boolean pause() {
        MusicBoxPauseEvent event = new MusicBoxPauseEvent(this);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        setPlaying(false);
        return true;
    }

    // Resumes from the paused position. Fires MusicBoxResumeEvent, which other plugins
    // may cancel: returns true when playback actually resumed, false when a listener
    // cancelled it.
    default boolean resume() {
        MusicBoxResumeEvent event = new MusicBoxResumeEvent(this);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;
        setPlaying(true);
        return true;
    }

    // Stops playback and fires MusicBoxStopEvent, but does not destroy the player.
    // An explicit stop should read: if (!player.stop()) player.destroy(DestroyReason.MANUAL_STOP);
    // so listeners get their veto before anything is torn down.
    //
    // Returns true when a listener CANCELLED the stop, i.e. playback was left running. This is
    // the opposite of pause()/resume(), which return true on success -- deliberately not
    // flipped, because every call site reads if (player.stop()) return; and getting it wrong
    // leaves a torn-down player still running.
    default boolean stop() {
        MusicBoxStopEvent event = new MusicBoxStopEvent(this);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return true;
        setPlaying(false);
        return false;
    }

    default LoopMode getLoopMode() {
        return getMusicBoxModel().getLoopMode();
    }

    default void setLoopMode(LoopMode mode) {
        getMusicBoxModel().setLoopMode(mode);
    }

    // Advances to the next loop mode and returns the mode now in effect.
    default LoopMode toggleLoopMode() {
        return getMusicBoxModel().toggleLoopMode();
    }
}
