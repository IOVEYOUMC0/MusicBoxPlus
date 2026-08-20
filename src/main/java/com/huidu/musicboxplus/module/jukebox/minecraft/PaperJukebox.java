package com.huidu.musicboxplus.module.jukebox.minecraft;

import com.huidu.musicboxplus.common.utils.JukeboxPlayableHelper;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;

public class PaperJukebox implements IJukebox {
    private final Jukebox jukebox;

    private static final long[] STOP_DELAYS = new long[]{1L, 5L, 20L, 40L};

    public PaperJukebox(Jukebox jukebox) {
        this.jukebox = jukebox;
    }

    @Override
    public boolean isEmpty() {
        ItemStack item = this.jukebox.getRecord();
        return item == null || item.getType() == Material.AIR;
    }

    @Override
    public void setJukebox(ItemStack item) {
        this.setJukebox(item, true);
    }

    @Override
    public void setJukebox(ItemStack item, boolean suppressVanillaPlayback) {
        if (!suppressVanillaPlayback) {
            this.jukebox.setRecord(item);
            this.jukebox.update();
            return;
        }
        if (insertWithoutPlaying(item)) {
            return;
        }
        stripAndInsert(item);
    }

    // Puts the disc in without letting the block start the vanilla track, and without
    // touching the item.
    //
    // Going through a live block state rather than a snapshot is what makes this work.
    // Jukebox.setRecord routes to the block entity's set-song-without-playing path, which
    // never starts the track. On a snapshot that gain is lost again at update() time: the
    // update re-applies the item through the ordinary setter, and that one does start the
    // track, broadcasting the play effect to every client in range.
    //
    // The item keeps its jukebox_playable component, which is what a comparator reads to
    // decide its output. Stripping the component silences the block just as effectively but
    // leaves the comparator reading zero, so redstone can no longer tell a loaded jukebox
    // from an empty one.
    //
    // Returns false when the block is not a jukebox or the server offers no live state, in
    // which case the caller falls back to stripping.
    private boolean insertWithoutPlaying(ItemStack item) {
        Location location = this.jukebox.getLocation();
        Block block = location.getBlock();
        if (block.getType() != Material.JUKEBOX) {
            return false;
        }
        try {
            // The has-record property is normally set by the ordinary setter that was
            // bypassed above, so it has to be applied here or the block keeps rendering and
            // behaving as empty.
            //
            // Written as a block-data string rather than through the typed setter, which the
            // oldest supported API does not have. A jukebox carries no other property, so
            // replacing its block data cannot lose anything, and the block keeps its entity
            // because the block itself is unchanged.
            boolean hasRecord = item != null && item.getType() != Material.AIR;
            BlockData current = block.getBlockData();
            if (!(current instanceof org.bukkit.block.data.type.Jukebox state) || state.hasRecord() != hasRecord) {
                block.setBlockData(Bukkit.createBlockData(Material.JUKEBOX,
                        "[has_record=" + hasRecord + "]"), false);
            }
            if (!(block.getState(false) instanceof Jukebox live)) {
                return false;
            }
            // Repairs discs silenced by the older approach: they carry an explicit
            // "component absent" patch that otherwise follows them out of the block and
            // keeps them mute and comparator-dead everywhere.
            JukeboxPlayableHelper.restoreJukeboxPlayable(item);
            live.setRecord(item);
            // Setting the disc leaves the block's own song player loaded with it, so it starts
            // ticking: emitting the jukebox-playing game event and its own note particles on
            // top of the ones this plugin draws. Clearing it stops that. The comparator is
            // unaffected -- its output is read from the stored item, not from the song player.
            live.stopPlaying();
            return true;
        } catch (Throwable ignored) {
            // Any server that does not hand out a live block state falls back below.
            return false;
        }
    }

    // Fallback for servers where the live-state path is unavailable: remove the component the
    // block plays from, then stop whatever already started.
    //
    // The removal is recorded on the item as an explicit "component absent" patch that travels
    // with it, so a disc treated this way stays silent in a vanilla jukebox and reads zero on a
    // comparator even after it is taken back out.
    private void stripAndInsert(ItemStack item) {
        if (item != null) {
            JukeboxPlayableHelper.removeJukeboxPlayable(item);
        }
        this.jukebox.setRecord(item);
        this.jukebox.update();
        this.jukebox.stopPlaying();
        // A vanilla disc can start playing on a delay, so schedule retries. Each retry has to
        // re-read the block state: this.jukebox is a snapshot taken before update(), so asking
        // it isPlaying() returns stale data and the retries would be no-ops.
        Location location = this.jukebox.getLocation();
        for (long delay : STOP_DELAYS) {
            Scheduler.regionLater(location, () -> stopVanillaPlaybackNow(location), delay);
        }
    }

    // Re-reads the block state on the spot and stops vanilla playback.
    // Must run on the region thread that owns the block.
    private static void stopVanillaPlaybackNow(Location location) {
        Block block = location.getBlock();
        if (block.getType() != Material.JUKEBOX) {
            return;
        }
        if (block.getState() instanceof Jukebox live && live.isPlaying()) {
            live.stopPlaying();
        }
    }

    @Override
    public ItemStack getJukebox() {
        return this.jukebox.getRecord();
    }

    @Override
    public void eject() {
        // Hand the disc back in a usable state; see restoreJukeboxPlayable.
        JukeboxPlayableHelper.restoreJukeboxPlayable(this.jukebox.getRecord());
        this.jukebox.eject();
        this.jukebox.update();
    }
}
