package com.huidu.musicboxplus.module.speaker;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxRangeStateChangeEvent;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.api.player.PlayerSongPlayer;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.core.playback.SongUtils;
import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.player.NoteEmitter;
import com.huidu.musicboxplus.core.player.PlaybackSetup;
import com.huidu.musicboxplus.core.player.PlayerManager;
import com.huidu.musicboxplus.core.player.VolumeManager;
import com.huidu.musicboxplus.core.player.models.MusicBoxSongPlayerModel;
import com.huidu.musicboxplus.core.player.models.PlayerPlayerModel;
import com.huidu.musicboxplus.core.player.models.RangePlayerModel;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.NotNull;

public class SpeakerPlayer extends com.huidu.musicboxplus.core.player.AbstractEnginePlayer
        implements PlayerSongPlayer, PositionPlayer {
    private final MusicBoxSongPlayerModel musicBoxModel;
    private final PlayerPlayerModel model;
    private final RangePlayerModel rangeModel;
    private final PlayerWrapper owner;
    // Cross-thread (playback thread writes, main thread reads) -> volatile.
    private volatile boolean destroyed = false;
    // Range/particle work follows the speaker owner across regions, so it runs on the owner
    // player's entity scheduler (main thread on regular Paper) rather than a server-wide loop.
    private com.huidu.musicboxplus.common.utils.scheduler.MbTask tickTask;
    // Owner location/dead-state snapshot, refreshed every tick ON THE OWNER'S region. playTick runs
    // on each LISTENER's region (dispatch is per listener), so it must never read the
    // owner entity live — that would be a cross-region entity access on Folia.
    private volatile Location ownerLocationSnapshot;
    private volatile boolean ownerDeadSnapshot;

    // Playback arrangement for this speaker's song, resolved once. A speaker plays one song per
    // player instance, and speed only changes the tempo, not which notes sit on a tick.
    private volatile CompiledSong compiledSong;


    public SpeakerPlayer(IPlayList list, PlayerWrapper wrapper) {
        super(PlaybackSetup.compiledSongOf(list));
        requireSong(list);
        this.setPlaybackSpeed(wrapper.getPlaybackSpeedMultiplier());
        this.setEnable10Octave(MusicBox.getInstance().getConfigObject().isEnable10octave());
        try {
            this.soundCategory = org.bukkit.SoundCategory.valueOf(
                MusicBox.getInstance().getConfigObject().getSoundCategory().toUpperCase());
        } catch (IllegalArgumentException e) {
            this.soundCategory = org.bukkit.SoundCategory.RECORDS;
        }
        this.musicBoxModel = new MusicBoxSongPlayerModel(this, list, SongUtils.nextPlayerSong(wrapper));
        this.musicBoxModel.setOwnerPlayer(this);
        this.rangeModel = new RangePlayerModel(this.musicBoxModel);
        this.owner = wrapper;
        Player ownerPlayer = wrapper.getPlayer();
        if (ownerPlayer != null && Bukkit.isOwnedByCurrentRegion(ownerPlayer)) {
            this.ownerLocationSnapshot = ownerPlayer.getLocation();
            this.ownerDeadSnapshot = ownerPlayer.isDead();
        }
        this.setRange(MusicBox.getInstance().getConfigObject().getSpeakerRadius());
        this.model = new PlayerPlayerModel(wrapper, this.musicBoxModel);
        this.model.addPlayerToSong();
        // Already resolved by the super constructor and held on the cursor; resolving it a
        // second time re-entered the song's monitor for a value we have.
        this.compiledSong = getSong();
        this.setPlaying(true);
        PlayerManager.registerPlayer(this);
        // The wrapper holds its Player weakly, so this can be null for someone who logged out
        // between the click and here. Throwing now would strand a target already registered on the
        // playback clock: it would keep advancing, and nothing would ever destroy it.
        Player owner = wrapper.getPlayer();
        if (owner == null) {
            this.destroy();
            return;
        }
        long tickInterval = Math.max(1L, MusicBox.getInstance().getConfigObject().getPlayer().getTickInterval());
        this.tickTask = com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityTimer(
                owner, this::tick, tickInterval, tickInterval);
    }

    private static void requireSong(IPlayList list) {
        if (list == null || list.getCurrent() == null) {
            throw new IllegalStateException("Cannot create SpeakerPlayer without a current song");
        }
    }

    // Playback events belong on the owner's region, which is where the speaker follows.
    @Override
    protected org.bukkit.Location dispatchLocation() {
        return this.ownerLocationSnapshot;
    }

    @Override
    protected void onSongFinished() {
        this.onSongEnd();
    }




    @Override
    public void destroy() {
        if (this.isDestroyed()) {
            return;
        }
        // Publish the guard flag before any teardown so the isDestroyed() check above actually
        // blocks a concurrent or re-entrant destroy(); on Folia these callers run on different
        // threads (owner death/autoDestroy on the owner's region vs. reload on the global thread),
        // and both would otherwise pass the guard and tear down twice.
        this.destroyed = true;
        if (this.tickTask != null) {
            this.tickTask.cancel();
            this.tickTask = null;
        }
        try {
            super.destroy();
        } catch (IllegalPluginAccessException ignored) {
            // Ignored during plugin shutdown.
        }
        this.rangeModel.destroy();
        this.model.destroy();
        this.musicBoxModel.destroy();
        PlayerManager.unregisterPlayer(this);
    }

    @Override
    public boolean isDestroyed() {
        return this.destroyed;
    }

    @Override
    public void tick() {
        if (this.destroyed) {
            return;
        }
        // Runs on the owner's region: the only place the owner entity may be read. Snapshot its
        // location/dead-state here for the per-listener playTick path.
        Player owner = this.owner.getPlayer();
        if (owner == null) {
            return;
        }
        if (owner.isDead()) {
            this.ownerDeadSnapshot = true;
            if (this.getAutoDestroy()) {
                this.destroy();
            } else {
                this.setPlaying(false);
            }
            return;
        }
        // Publish a new snapshot only when the owner actually moved: comparing world/x/y/z via
        // primitive getters avoids allocating a Location every tick for a stationary owner. The
        // snapshot feeds sound position only (rotation is irrelevant) and is replaced wholesale,
        // never mutated, so reusing the old object is safe for the cross-region reader.
        Location snap = this.ownerLocationSnapshot;
        if (snap == null
                || snap.getWorld() != owner.getWorld()
                || snap.getX() != owner.getX()
                || snap.getY() != owner.getY()
                || snap.getZ() != owner.getZ()) {
            this.ownerLocationSnapshot = owner.getLocation();
        }
        this.ownerDeadSnapshot = false;
        this.rangeModel.tick();
    }




    public void playTick(@NotNull Player player, int tick) {
        // Runs on the LISTENER's region, so read the owner snapshot (refreshed in tick() on the
        // owner's region) instead of the owner entity.
        Location entityLocation = this.ownerLocationSnapshot;
        if (entityLocation == null || entityLocation.getWorld() == null) {
            return;
        }
        if (player.getWorld() != entityLocation.getWorld()) {
            return;
        }

        boolean inRange = this.rangeModel.isPlayerInRange(player);
        Boolean wasInRange = this.playerList.get(player.getUniqueId());
        if (inRange) {
            if (wasInRange == null || !wasInRange) {
                this.playerList.put(player.getUniqueId(), true);
                fireRangeStateChange(player, true);
            }
        } else if (wasInRange != null && wasInRange) {
            this.playerList.put(player.getUniqueId(), false);
            fireRangeStateChange(player, false);
        }
        CompiledSong compiled = this.compiledSong;
        boolean hasNotes = compiled != null && compiled.noteStart(tick) < compiled.noteEnd(tick);
        if (inRange && hasNotes) {
            int playbackVolume = resolvePlaybackVolume(player);
            if (this.volume > 0 && playbackVolume > 0 && this.getDistance() > 0) {
                // The listener term is folded into playbackVolume here, so it stays neutral.
                float baseVolume = NoteEmitter.baseVolume(this.volume, playbackVolume, 100,
                    this.getDistance());
                // A speaker never leaves mono: channelMode defaults to MonoMode and only the
                // radio player ever replaces it.
                float stereoWidth = 0F;
                NoteEmitter.emitTick(player, entityLocation, compiled, tick, baseVolume,
                    this.soundCategory, this.enable10Octave, stereoWidth);
            }
        }
        if (player.equals(this.model.getWrapper().getPlayer())) {
            this.model.nextTick(compiled != null ? compiled.lengthTicks() : 0, tick);
            if (!this.isPlayerVanished(player) && hasNotes) {
                this.spawnNote(player);
            }
        }
    }

    // Range enter/leave is dispatched on the listener's own region thread; a throwing listener
    // must not abort the tick loop, so the event is isolated like the other playTick callEvents.
    private void fireRangeStateChange(Player player, boolean inRange) {
        try {
            Bukkit.getPluginManager().callEvent(new MusicBoxRangeStateChangeEvent(this, player, inRange));
        } catch (Exception ex) {
            java.util.logging.Logger.getLogger(SpeakerPlayer.class.getName())
                    .log(java.util.logging.Level.FINEST, "Exception dispatching range state change", ex);
        }
    }

    private boolean isPlayerVanished(Player player) {
        if (player.hasMetadata("vanished")) {
            for (MetadataValue meta : player.getMetadata("vanished")) {
                if (meta.asBoolean()) {
                    return true;
                }
            }
        }
        if (player.hasMetadata("isVanished")) {
            for (MetadataValue meta : player.getMetadata("isVanished")) {
                if (meta.asBoolean()) {
                    return true;
                }
            }
        }
        if (player.hasMetadata("sv_invisible")) {
            return true;
        }
        if (player.hasMetadata("essentials_vanish")) {
            for (MetadataValue meta : player.getMetadata("essentials_vanish")) {
                if (meta.asBoolean()) {
                    return true;
                }
            }
        }
        return player.getGameMode() == GameMode.SPECTATOR;
    }

    private void spawnNote(Player player) {
        Location loc = player.getLocation();
        player.getWorld().spawnParticle(Particle.NOTE, loc.getX(), loc.getY() + 2.3, loc.getZ(), 1);
    }


    @Override
    public Location getLocation() {
        // Owner snapshot (refreshed on the owner's region in tick()); avoids reading the owner
        // entity from a foreign region on Folia.
        return this.ownerLocationSnapshot;
    }

    @Override
    public int getRange() {
        return super.getDistance();
    }

    @Override
    public void setRange(int range) {
        super.setDistance(range);
    }

    @Override
    public MusicBoxSongPlayerModel getMusicBoxModel() {
        return this.musicBoxModel;
    }

    public PlayerPlayerModel getModel() {
        return this.model;
    }

    public RangePlayerModel getRangeModel() {
        return this.rangeModel;
    }

    public PlayerWrapper getOwner() {
        return this.owner;
    }

    public short getCurrentTick() {
        return this.getTick();
    }
}
