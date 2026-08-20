package com.huidu.musicboxplus.core.player.models;

import com.huidu.musicboxplus.api.event.MusicBoxSongChangeEvent;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.MusicBoxSongPlayer;
import com.huidu.musicboxplus.api.player.PlayerControlGUI;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.api.player.loop.LoopMode;
import com.huidu.musicboxplus.core.player.loop.SongEndAction;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class MusicBoxSongPlayerModel implements MusicBoxSongPlayer, com.huidu.musicboxplus.api.player.model.MusicBoxSongPlayerModel {

    @FunctionalInterface
    public interface ControlGuiFactory {
        PlayerControlGUI create(MusicBoxSongPlayerModel model);
    }

    // Registered by the gui module at startup; core cannot construct the module-layer GUI
    // implementation itself, and the api PlayerControlGUI contract cannot either.
    private static volatile ControlGuiFactory controlGuiFactory;

    public static void setControlGuiFactory(ControlGuiFactory factory) {
        controlGuiFactory = factory;
    }

    private final Object lock = new Object();
    // The player that owns this model, injected right after construction; block, radio and
    // speaker players all pass themselves. The model never creates a playback engine of its
    // own, so this is a back-reference, not something the model's lifetime governs.
    //
    // Typed as MusicBoxSongPlayer so that isPersistentOnEnd() below is reached through the
    // type system rather than an instanceof: a narrower type compiles just as well and is
    // simply false for anything outside that hierarchy, and a false there makes text players
    // destroy themselves and their floating display when the playlist ends.
    private volatile MusicBoxSongPlayer ownerPlayer;
    // Written by GUI clicks on the main thread, read by the song-end callback on the region
    // thread -> volatile so the end-of-song decision never sees a stale list or loop mode.
    private volatile IPlayList playList;
    private final PositionPlayer positionPlayer;
    private final Map<UUID, Boolean> mutedPlayers = new ConcurrentHashMap<>();
    private volatile boolean destroyed = false;
    private volatile LoopMode loopMode = LoopMode.OFF;
    private final Set<UUID> listeningPlayers = ConcurrentHashMap.newKeySet();
    private volatile PlayerControlGUI cachedControlGUI;
    // Written from the song-end callback (playback thread), read in destroy() on the main
    // thread -> volatile so the teardown observes the correct end/next state.
    private volatile boolean songEndNormal = false;
    private volatile boolean nextCreated = false;
    private final Function<IPlayList, ? extends MusicBoxSongPlayer> nextPlayerFactory;
    private volatile int volume = com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getVolume().getDefaultVolume();
    private volatile float playbackSpeedMultiplier = 1.0f;
    // Song the last MusicBoxSongChangeEvent was fired for; null means no event has fired yet.
    private volatile MusicBoxSong lastEventSong;
    // Serialises handovers: a GUI click that races the natural end-of-song transition would
    // otherwise build the successor twice, and the second build would destroy the first.
    private volatile boolean handoverPending = false;

    public MusicBoxSongPlayerModel(PositionPlayer positionPlayer, IPlayList playList, Function<IPlayList, ? extends MusicBoxSongPlayer> nextPlayerFactory) {
        this.positionPlayer = positionPlayer;
        this.playList = playList;
        // Required: without it the playlist would advance while nothing starts the next song,
        // which is silent. Every owner supplies one.
        this.nextPlayerFactory = Objects.requireNonNull(nextPlayerFactory, "nextPlayerFactory");
        this.lastEventSong = (MusicBoxSong) playList.getCurrent();
    }

    public void setOwnerPlayer(MusicBoxSongPlayer ownerPlayer) {
        this.ownerPlayer = ownerPlayer;
    }


    @Override
    public MusicBoxSongPlayerModel getMusicBoxModel() {
        return this;
    }

    @Override
    public short getTick() {
        MusicBoxSongPlayer owner = this.ownerPlayer;
        return owner != null ? owner.getTick() : 0;
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        
        destroyed = true;
        
        synchronized (lock) {
            MusicBoxSongPlayer owner = this.ownerPlayer;
            if (owner != null) {
                owner.setPlaying(false);
                // Reentrant: the owner's destroy() comes back here, and its own destroyed
                // flag is what stops the loop.
                owner.destroy();
                this.ownerPlayer = null;
            }
        }
        
        listeningPlayers.clear();
        mutedPlayers.clear();
        cachedControlGUI = null;
    }

    public PositionPlayer getPositionPlayer() {
        return positionPlayer;
    }

    public IPlayList getPlayList() {
        return playList;
    }

    public void setPlayList(IPlayList playList) {
        this.playList = playList;
    }

    public MusicBoxSong getMusicBoxSong() {
        return playList != null ? (MusicBoxSong) playList.getCurrent() : null;
    }
    
    public boolean isSongEndNormal() {
        return songEndNormal;
    }
    
    public boolean isNextCreated() {
        return nextCreated;
    }
    
    public void setNextCreated(boolean nextCreated) {
        this.nextCreated = nextCreated;
    }
    
    public MusicBoxSongPlayer getMusicBoxSongPlayer() {
        return positionPlayer != null ? positionPlayer : this.ownerPlayer;
    }
    
    // Player to report in API events. Prefers the real player implementation (block players
    // implement PositionPlayer, which is how downstream plugins get a location) and only
    // falls back to this, so the event's player field is never null.
    private MusicBoxSongPlayer resolveEventPlayer() {
        MusicBoxSongPlayer resolved = this.getMusicBoxSongPlayer();
        return resolved != null ? resolved : this;
    }

    public void tick() {
        // Called by PlayerManager every ~100ms on the Bukkit main thread.
    }

    public LoopMode getLoopMode() {
        return loopMode;
    }

    public void setLoopMode(LoopMode loopMode) {
        this.loopMode = loopMode;
    }

    public LoopMode toggleLoopMode() {
        loopMode = loopMode.next();
        return loopMode;
    }

    public void pingSongEnded() {
    }

    public void onSongEnd() {
        if (destroyed) {
            return;
        }
        
        MusicBoxSongPlayer owner = this.ownerPlayer;
        SongEndAction action = SongEndAction.decide(loopMode, playList.hasNext(), owner != null,
                owner != null && owner.isPersistentOnEnd());

        switch (action) {
            case REPLAY_CURRENT -> {
                if (owner != null) {
                    owner.setTick(0);
                    owner.setPlaying(true);
                }
            }
            case ADVANCE_NEXT -> {
                playList.next();
                createNextPlayer();
            }
            case RESTART_FIRST -> {
                playList.first();
                createNextPlayer();
            }
            case HOLD_AT_END -> {
                if (owner != null) {
                    // Rewound before parking: the cursor is sitting past the last tick, so without
                    // this the control panel's play button resumes a song with nothing left to
                    // play and the display can never be restarted.
                    owner.setTick(0);
                    owner.setPlaying(false);
                }
            }
            case STOP -> {
                // Set before destroy(), which reads it to decide whether to run songEnd() --
                // the sign's redstone pulse. JukeboxPlayer.songEnd() is deliberately empty.
                songEndNormal = true;
                destroy();
            }
        }
    }

    public void createNextPlayer() {
        // Guard against a GUI click racing the natural end-of-song transition: both would run
        // whenReady and build a successor, and the second one would destroy the first.
        if (handoverPending || destroyed) {
            return;
        }
        handoverPending = true;
        MusicBoxSong currentSong = (MusicBoxSong) playList.getCurrent();
        MusicBoxSong prevSong = this.lastEventSong;
        if (currentSong != prevSong) {
            this.lastEventSong = currentSong;
            // Report the real player, not this: this class does not implement
            // PositionPlayer, so downstream would have no location to read.
            try {
                Bukkit.getPluginManager().callEvent(
                    new MusicBoxSongChangeEvent(this.resolveEventPlayer(), prevSong, currentSong));
            } catch (Exception ex) {
                // A throwing listener must not abort the handover: without the event reaching a
                // success path below, nextCreated stays true and the old player is never replaced.
                com.huidu.musicboxplus.MusicBox.getInstance().getLogger().warning(
                    "MusicBoxSongChangeEvent listener threw during song switch: " + ex.getMessage());
            }
        }
        // Set before the handover is dispatched, not inside it: destroy() reads this to tell a
        // song change from a natural finish, and a jukebox that read false here would eject its
        // disc in the window while the successor is still being prepared.
        nextCreated = true;
        // The successor's constructor builds the song's arrangement, which on a cold song is a
        // whole-file read and parse. Every caller of this method is on a server thread -- four of
        // them are GUI clicks -- so it is handed over only once the arrangement is in memory.
        // A warm song, which is the normal case and always the case for the automatic end-of-song
        // transition (the previous player prefetched it), runs inline exactly as before.
        com.huidu.musicboxplus.core.player.PlaybackSetup.whenReady(playList, this::dispatchHandover, () -> {
            try {
                createNextPlayerNow();
            } finally {
                handoverPending = false;
            }
        });
    }

    private void dispatchHandover(Runnable run) {
        Location location = null;
        try {
            location = this.positionPlayer != null ? this.positionPlayer.getLocation() : null;
        } catch (Exception ignored) {
            // An unloaded world leaves no region to target; the global one still works.
        }
        if (location != null && location.getWorld() != null) {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(location, run);
        } else {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.global(run);
        }
    }

    private void createNextPlayerNow() {
        if (destroyed) {
            return;
        }
        MusicBoxSongPlayer nextPlayer = nextPlayerFactory.apply(playList);
        if (nextPlayer != null) {
            this.copySettingsTo((MusicBoxSongPlayerModel) nextPlayer.getMusicBoxModel());
            return;
        }
        // Nobody took over. The successor normally replaces this player at the same spot, so
        // without one this player would sit there for good: not playing, never torn down, still
        // ticking. That keeps its note particles coming and keeps anything that checks for a
        // live player -- a hopper trying to pull the disc, for one -- believing it is busy.
        //
        // songEndNormal stays false: the playlist did not run out, it moved on and the move
        // failed, so this must not be mistaken for a natural finish and trigger a disc eject or
        // a redstone pulse.
        nextCreated = false;
        destroy();
    }

    public PlayerControlGUI getControlGUI() {
        PlayerControlGUI cached = this.cachedControlGUI;
        if (cached == null) {
            synchronized (this) {
                cached = this.cachedControlGUI;
                if (cached == null) {
                    ControlGuiFactory factory = controlGuiFactory;
                    cached = factory != null ? factory.create(this) : null;
                    this.cachedControlGUI = cached;
                }
            }
        }
        return cached;
    }

    public boolean isMuted(Player player) {
        return mutedPlayers.getOrDefault(player.getUniqueId(), false);
    }

    public void mutePlayer(Player player) {
        mutedPlayers.put(player.getUniqueId(), true);
        this.removePlayer(player);
    }

    public void unmutePlayer(Player player) {
        mutedPlayers.remove(player.getUniqueId());
        if (!destroyed) {
            this.addPlayer(player);
        }
    }

    public void toggleMute(Player player) {
        if (isMuted(player)) {
            unmutePlayer(player);
        } else {
            mutePlayer(player);
        }
    }

    public void copySettingsTo(MusicBoxSongPlayerModel other) {
        other.loopMode = this.loopMode;
        other.mutedPlayers.putAll(this.mutedPlayers);
        other.volume = this.volume;
        other.playbackSpeedMultiplier = this.playbackSpeedMultiplier;
    }

    public int getVolume() {
        return this.volume;
    }

    public void adjustVolume(int delta) {
        this.setVolume(this.volume + delta);
    }

    public void setVolume(int volume) {
        int min = com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getVolume().getMinVolume();
        int max = com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getVolume().getMaxVolume();
        this.volume = Math.max(min, Math.min(max, volume));
    }

    public float getPlaybackSpeedMultiplier() {
        return this.playbackSpeedMultiplier;
    }

    public void setPlaybackSpeedMultiplier(float playbackSpeedMultiplier) {
        com.huidu.musicboxplus.MusicBoxConfig.SpeedConfig speedConfig =
            com.huidu.musicboxplus.MusicBox.getInstance().getConfigObject().getSpeed();
        float min = speedConfig.getMinSpeed();
        float max = speedConfig.getMaxSpeed();
        this.playbackSpeedMultiplier = Math.max(min, Math.min(max, playbackSpeedMultiplier));
    }

    public static void destroyAll() {
        for (MusicBoxSongPlayer player : new java.util.ArrayList<>(com.huidu.musicboxplus.core.player.PlayerManager.getActivePlayers())) {
            if (player != null && !player.isDestroyed()) {
                player.destroy();
            }
        }
    }

    // Listener and transport control belong to the owning player; the model only routes to it.
    @Override
    public Set<UUID> getPlayers() {
        MusicBoxSongPlayer owner = resolveOwner();
        return owner != null ? owner.getPlayers() : Set.of();
    }

    @Override
    public void addPlayer(Player player) {
        MusicBoxSongPlayer owner = resolveOwner();
        if (owner != null) {
            owner.addPlayer(player);
        }
    }

    @Override
    public void removePlayer(Player player) {
        MusicBoxSongPlayer owner = resolveOwner();
        if (owner != null) {
            owner.removePlayer(player);
        }
    }

    // Detaches a listener that is no longer online, so there is no Player to pass.
    public void removePlayer(UUID uuid) {
        MusicBoxSongPlayer owner = resolveOwner();
        if (owner instanceof com.huidu.musicboxplus.core.player.AbstractEnginePlayer enginePlayer) {
            enginePlayer.removePlayer(uuid);
        }
    }

    @Override
    public void setTick(int tick) {
        MusicBoxSongPlayer owner = resolveOwner();
        if (owner != null) {
            owner.setTick(tick);
        }
    }

    @Override
    public boolean isPlaying() {
        MusicBoxSongPlayer owner = resolveOwner();
        return owner != null && owner.isPlaying();
    }

    @Override
    public void setPlaying(boolean playing) {
        MusicBoxSongPlayer owner = resolveOwner();
        if (owner != null) {
            owner.setPlaying(playing);
        }
    }

    private MusicBoxSongPlayer resolveOwner() {
        return positionPlayer != null ? positionPlayer : this.ownerPlayer;
    }

    public boolean hasApiPlayer() {
        return this.ownerPlayer != null && !destroyed;
    }
}
