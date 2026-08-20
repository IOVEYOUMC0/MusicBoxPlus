package com.huidu.musicboxplus.module.jukebox;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.api.player.IPlayList;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.utils.SignUtils;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.jukebox.minecraft.JukeboxFactory;
import com.huidu.musicboxplus.module.sign.SignPlaylistUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Random;

public class JukeboxPlayer extends AbstractBlockPlayer implements com.huidu.musicboxplus.api.player.VanillaJukeboxPlayback {
    private Location infoSign;
    private static final long BLOCK_CHECK_INTERVAL_MS = 5000L;
    private static final Random PARTICLE_RANDOM = new Random();
    // Wall-clock throttle for the "is the jukebox block still here / still holding a disc" check.
    // A song-tick gate cannot be used: the tick mirror freezes at 0 whenever the jukebox has no
    // listeners in range (playTick, the only thing that advances it, never runs), so tick % 100 == 0
    // would hold every timer tick and read BlockState ~20x/s. Wall-clock is listener-independent.
    private long nextBlockCheckAt = 0L;

    private JukeboxPlayer(IPlayList list, int range, Jukebox box) {
        this(list, range, box, 1.0f);
    }

    private JukeboxPlayer(IPlayList list, int range, Jukebox box, float speedMultiplier) {
        super(list, box.getLocation(), range, speedMultiplier);
        SignUtils.findSign(box.getLocation()).ifPresent(sign -> {
            this.infoSign = sign.getLocation();
            SignPlaylistUtils.setPlayListInfo(this.infoSign, list);
        });
        this.sendNowPlayingActionBar(box);
    }

    private void sendNowPlayingActionBar(Jukebox box) {
        MusicBoxSong currentSong = (MusicBoxSong) this.getMusicBoxModel().getPlayList().getCurrent();
        if (currentSong == null) return;
        String author = currentSong.getAuthor();
        Component msg;
        if (author != null && !author.isEmpty()) {
            msg = Component.text("♪ ", NamedTextColor.GOLD)
                .append(Component.text(currentSong.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" - ", NamedTextColor.GRAY))
                .append(Component.text(author, NamedTextColor.WHITE));
        } else {
            msg = Component.text("♪ ", NamedTextColor.GOLD)
                .append(Component.text(currentSong.getName(), NamedTextColor.YELLOW));
        }
        // Not getNearbyPlayers: it walks the chunks in the radius, and on Folia a 64-block box
        // reaches chunks owned by a neighbouring region, which throws. getPlayers() is a plain
        // per-world list with no chunk access, and cheaper at realistic player counts anyway.
        Location center = box.getLocation();
        double radiusSquared = 64.0 * 64.0;
        for (Player player : center.getWorld().getPlayers()) {
            double dx = player.getX() - center.getX();
            double dy = player.getY() - center.getY();
            double dz = player.getZ() - center.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                player.sendActionBar(msg);
            }
        }
    }

    public static void onJukeboxClick(Jukebox jukebox, ItemStack clickedItem, PlayerInteractEvent event) {
        JukeboxPlayer existingPlayer = AbstractBlockPlayer.findByLocation(jukebox.getLocation());
        if (clickedItem == null && existingPlayer == null) {
            return;
        }
        if (existingPlayer != null) {
            existingPlayer.destroy(DestroyReason.MANUAL_STOP);
        }
        if (clickedItem == null) {
            return;
        }
        MusicBoxSong song = MusicBoxSongManager.findByItem(clickedItem).orElse(null);
        if (song == null) {
            return;
        }
        if (song.shouldUseVanillaJukeboxPlayback()) {
            return;
        }
        event.setCancelled(true);
        ItemStack recordItem = clickedItem.clone();
        recordItem.setAmount(1);
        int remaining = clickedItem.getAmount() - 1;
        event.getPlayer().getInventory().setItemInMainHand(remaining > 0 ? withAmount(clickedItem, remaining) : null);
        JukeboxFactory.getJukebox(jukebox).setJukebox(recordItem);
        JukeboxPlayer.createNew(jukebox);
    }

    private static ItemStack withAmount(ItemStack source, int amount) {
        ItemStack item = source.clone();
        item.setAmount(amount);
        return item;
    }

    public static void createNew(Jukebox jukebox) {
        try {
            ItemStack record = jukebox.getRecord();
            MusicBoxSong currentSong = MusicBoxSongManager.findByItem(record).orElse(null);
            if (currentSong != null && currentSong.shouldUseVanillaJukeboxPlayback()) {
                return;
            }
            // A record that is not a MusicBox disc must not reach the playlist constructor: it
            // ejects the disc and pulls one out of an adjacent chest.
            if (currentSong == null && record != null && record.getType() != Material.AIR) {
                return;
            }
            JukeboxPlaylistImpl playlist = new JukeboxPlaylistImpl(jukebox.getLocation());
            if (playlist.getCurrent() == null) {
                MusicBox.getInstance().getLogger().fine("Skipping jukebox player creation without a current song at " + jukebox.getLocation());
                return;
            }
            // Deferred until the arrangement is in memory, so inserting a disc never makes the
            // region thread read and parse the .nbs. Warm songs still construct inline.
            com.huidu.musicboxplus.core.player.PlaybackSetup.whenReady(playlist,
                    run -> com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(jukebox.getLocation(), run),
                    () -> {
                        // Re-checked on arrival: the disc may have been taken out, or another
                        // player may already have been built here, while the song compiled.
                        if (com.huidu.musicboxplus.core.player.AbstractBlockPlayer.findByLocation(jukebox.getLocation()) != null) {
                            return;
                        }
                        new JukeboxPlayer(playlist, MusicBox.getInstance().getConfigObject().getJukeboxRadius(), jukebox);
                        com.huidu.musicboxplus.core.player.PlaybackSetup.prefetchNext(playlist);
                    });
        } catch (JukeboxPlaylistInitException e) {
            MusicBox.getInstance().getLogger().fine("Cannot create jukebox player: " + e.getMessage());
        }
    }

    // True when the click was consumed by opening the control panel. A jukebox with nothing
    // playing consumes nothing: sneaking is how a player places a block against it, so
    // swallowing the click there makes it impossible to build next to one.
    public static boolean onSneakingClick(Jukebox jukebox, Player player) {
        JukeboxPlayer songPlayer = AbstractBlockPlayer.findByLocation(jukebox.getLocation());
        if (songPlayer == null) {
            return false;
        }
        if (MusicBox.getInstance().getConfigObject().isBlockPlayerControlPermission() && !player.hasPermission(Permissions.CONTROL)) {
            com.huidu.musicboxplus.common.utils.MessageUtils.send(player, com.huidu.musicboxplus.common.lang.Lang.NO_PERMISSIONS);
            return true;
        }
        songPlayer.getControl().open(player);
        return true;
    }

    public static void onRedstone(Jukebox box, Block source, int power) {
        if (power > 0) {
            JukeboxPlayer player = AbstractBlockPlayer.findByLocation(box.getLocation());
            if (player == null) {
                // Only create a player when none exists: treating a redstone pulse as a skip
                // (startNext) on an already-playing jukebox would eject an extra disc.
                JukeboxPlayer.createNew(box);
            }
        }
    }

    // Restores playback for jukeboxes that still hold a MusicBox disc after a server restart.
    // Runs on the chunk's region thread (ChunkLoadEvent), so block state reads are safe. Only
    // chunks that load after the plugin finished startup reach this; the startup pass in
    // MusicBox#restoreJukeboxesInLoadedChunks covers chunks that were already loaded then.
    public static void restoreJukeboxesInChunk(Chunk chunk) {
        if (!MusicBox.getInstance().isJukeboxModuleEnabled()) {
            return;
        }
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Jukebox jukebox)) {
                continue;
            }
            // Skip boxes a player is already attached to: the click/redstone/hopper paths may
            // have recreated a player before the chunk event got here, and a duplicate would
            // start the playlist over instead of resuming it.
            if (AbstractBlockPlayer.findByLocation(jukebox.getLocation()) != null) {
                continue;
            }
            ItemStack record = jukebox.getRecord();
            if (record == null || record.getType() == Material.AIR) {
                continue;
            }
            // Only MusicBox discs qualify. createNew does not guard on this itself (a vanilla
            // record reaches the playlist constructor, which ejects it and may pull a disc from
            // an adjacent chest), so restore must apply the same check the manual click path
            // does -- otherwise every vanilla jukebox in a loaded chunk gets hijacked on restart.
            MusicBoxSong song = MusicBoxSongManager.findByItem(record).orElse(null);
            if (song == null || song.shouldUseVanillaJukeboxPlayback()) {
                continue;
            }
            JukeboxPlayer.createNew(jukebox);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.isDestroyed()) {
            return;
        }
        Location loc = this.getTargetLocation();
        // Only while actually playing. A paused or stopped jukebox showing note particles reads
        // as still running, and vanilla shows none in that state either.
        if (loc != null && this.isPlaying()) {
            World world = loc.getWorld();
            if (world != null) {
                // Playback is plugin-driven, so the block never enters vanilla's "playing" state
                // and emits no note particles of its own; spawn them here instead.
                double x = loc.getBlockX() + 0.5;
                double y = loc.getBlockY() + 1.2;
                double z = loc.getBlockZ() + 0.5;
                // With a count of 0 the offset arguments carry particle data instead of spread,
                // which is how the note color (0-24) is chosen. One particle per tick (~1s) is
                // visually enough.
                world.spawnParticle(Particle.NOTE, x, y, z, 0,
                    PARTICLE_RANDOM.nextInt(25), 0, 0);
            }
        }
        long now = System.currentTimeMillis();
        if (now < this.nextBlockCheckAt) {
            return;
        }
        this.nextBlockCheckAt = now + BLOCK_CHECK_INTERVAL_MS;
        // tick() runs on this block's region (see AbstractBlockPlayer), so the block/state
        // access below is already on the owning thread and runs inline.
        if (loc == null) {
            return;
        }
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
            return;
        }
        // Take a single BlockState snapshot: every getState() copies the tile entity.
        BlockState state = loc.getBlock().getState();
        if (!(state instanceof Jukebox)) {
            this.deleteStoredVolume();
            this.destroy();
            return;
        }
        ItemStack record = ((Jukebox) state).getRecord();
        if (record == null || record.getType() == Material.AIR) {
            this.destroy();
        }
    }

    @Override
    protected JukeboxPlayer runNextSong(IPlayList list) {
        @NotNull BlockState state = Objects.requireNonNull(this.getTargetLocation()).getBlock().getState();
        if (state instanceof Jukebox) {
            MusicBoxSong currentSong = (MusicBoxSong) list.getCurrent();
            if (currentSong != null && currentSong.shouldUseVanillaJukeboxPlayback()) {
                this.getMusicBoxModel().setNextCreated(true);
                return null;
            }
            JukeboxPlayer nextPlayer = new JukeboxPlayer(list, this.getRange(), (Jukebox) state, this.getMusicBoxModel().getPlaybackSpeedMultiplier());
            this.getMusicBoxModel().copySettingsTo(nextPlayer.getMusicBoxModel());
            return nextPlayer;
        }
        this.getMusicBoxModel().setNextCreated(false);
        return null;
    }

    // A song reaching its natural end deliberately does nothing: the disc stays in the jukebox,
    // matching vanilla, where a finished disc is not ejected either and has to be taken back out
    // by hand.
    //
    // Do not call setRecord(null) here. That erases rather than ejects: the disc is neither
    // dropped nor returned, it simply vanishes from the world.
    //
    // Do not switch to eject() either. A dropped disc despawns after five minutes, so it is lost
    // whenever no player is around, and eject() acts on the world directly, which means a later
    // update() writes the stale disc from this snapshot back in.
    @Override
    protected void songEnd() {
    }

    @Override
    public Location getInfoSign() {
        return this.infoSign;
    }
}
