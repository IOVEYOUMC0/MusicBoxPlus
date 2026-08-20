package com.huidu.musicboxplus.module.radio;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.core.playback.SongUtils;
import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.NoteEmitter;
import com.huidu.musicboxplus.core.player.PlaybackSetup;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.core.player.models.MusicBoxSongPlayerModel;
import com.huidu.musicboxplus.core.player.models.PlayerPlayerModel;
import com.huidu.musicboxplus.core.sound.StereoPan;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class RadioPlayer
extends com.huidu.musicboxplus.core.player.AbstractEnginePlayer
implements PlayerSongPlayer {
    private final PlayerPlayerModel model;
    private final MusicBoxSongPlayerModel musicBoxModel;
    private volatile boolean destroyed = false;

    // Playback arrangement for this radio's song, resolved once. Speed only changes the tempo,
    // not which notes sit on a tick.
    private volatile CompiledSong compiledSong;

    private final int cachedSongLength;
    private int tickCounter = 0;

    public RadioPlayer(IPlayList list, PlayerWrapper wrapper) {
        super(PlaybackSetup.compiledSongOf(list));
        requireSong(list);
        this.setEnable10Octave(MusicBox.getInstance().getConfigObject().isEnable10octave());
        CompiledSong song = this.getSong();
        this.cachedSongLength = song != null ? song.lengthTicks() : 0;
        try {
            this.soundCategory = org.bukkit.SoundCategory.valueOf(
                MusicBox.getInstance().getConfigObject().getSoundCategory().toUpperCase());
        } catch (IllegalArgumentException e) {
            this.soundCategory = org.bukkit.SoundCategory.RECORDS;
        }
        this.setPlaybackSpeed(wrapper.getPlaybackSpeedMultiplier());
        this.musicBoxModel = new MusicBoxSongPlayerModel(null, list, SongUtils.nextPlayerSong(wrapper));
        this.musicBoxModel.setOwnerPlayer(this);
        this.model = new PlayerPlayerModel(wrapper, this.musicBoxModel);
        this.model.addPlayerToSong();
        this.compiledSong = this.getSong();
        this.setPlaying(true);
        // The wrapper holds its Player weakly, so this can be null for someone who logged out
        // while the song was still compiling. A radio with no listener has no reason to run and
        // would otherwise stay registered on the global playback clock forever, so tear it down
        // here (same guard SpeakerPlayer uses).
        if (wrapper.getPlayer() == null) {
            this.destroy();
        }
    }

    private static void requireSong(IPlayList list) {
        if (list == null || list.getCurrent() == null) {
            throw new IllegalStateException("Cannot create RadioPlayer without a current song");
        }
    }

    // The sound is placed at each listener's own head, so there is no world position for
    // playback events to belong to; they run on the global region.
    @Override
    protected org.bukkit.Location dispatchLocation() {
        return null;
    }

    @Override
    protected void onSongFinished() {
        this.onSongEnd();
    }



    @Override
    public void destroy() {
        if (!this.isDestroyed()) {
            // Publish the guard flag FIRST so a concurrent/re-entrant destroy() (e.g. owner quit on the
            // owner's region vs. reload/shutdown on the global thread under Folia) can't both pass the
            // isDestroyed() check and run teardown twice.
            this.destroyed = true;
            try {
                super.destroy();
            } catch (Exception ignored) {
                // Can be thrown while the plugin is being disabled; safe to ignore.
            }
            this.model.destroy();
            this.musicBoxModel.destroy();
        }
    }

    @Override
    public boolean isDestroyed() {
        return this.destroyed;
    }


    public void playTick(@NotNull Player player, int tick) {
        // 进度条更新由 model.nextTick 内部节流（每 20 tick 一次），不再在此处重复
        this.model.nextTick(this.cachedSongLength, tick);

        CompiledSong compiled = this.compiledSong;
        if (compiled == null || compiled.noteStart(tick) >= compiled.noteEnd(tick) || this.volume <= 0) {
            return;
        }
        int playbackVolume = resolvePlaybackVolume(player);
        if (playbackVolume <= 0) {
            return;
        }

        // The sound follows the listener's head, so there is no range falloff to apply.
        // getEyeLocation() 内部是 getLocation()+add() 两次分配，改为单次分配后原地抬 Y
        Location playbackLocation = player.getLocation();
        playbackLocation.setY(playbackLocation.getY() + player.getEyeHeight());
        float baseVolume = NoteEmitter.baseVolume(this.volume, playbackVolume, 100);
        // A radio pans songs that carry panning, and widens the ones that do not by sending each
        // note to both sides. The second form costs an extra packet per note per listener, which
        // is why only the radio asks for it.
        NoteEmitter.emitTick(player, playbackLocation, compiled, tick, baseVolume,
            this.soundCategory, this.enable10Octave,
            StereoPan.DEFAULT_MAX_DISTANCE, StereoPan.DEFAULT_MAX_DISTANCE);
    }

    public void forceUpdateVolume() {
        // Volume is resolved dynamically during playTick.
    }


    public PlayerPlayerModel getModel() {
        return this.model;
    }

    @Override
    public MusicBoxSongPlayerModel getMusicBoxModel() {
        return this.musicBoxModel;
    }

    public short getCurrentTick() {
        return this.getTick();
    }

    public int getCachedSongLength() {
        return this.cachedSongLength;
    }

    public byte getCurrentVolume() {
        byte currentVolume = (byte) 100;
        return currentVolume;
    }

    public int getTickCounter() {
        return this.tickCounter;
    }

    public int getVolumeCheckCounter() {
        int volumeCheckCounter = 0;
        return volumeCheckCounter;
    }
}
