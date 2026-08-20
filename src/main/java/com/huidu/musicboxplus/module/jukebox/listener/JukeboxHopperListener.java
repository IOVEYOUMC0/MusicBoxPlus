package com.huidu.musicboxplus.module.jukebox.listener;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.jukebox.minecraft.JukeboxFactory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class JukeboxHopperListener implements Listener {

    // ignoreCancelled: this handler performs the transfer itself through the jukebox API, so a
    // cancelled event must not reach it.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (!MusicBox.getInstance().isJukeboxModuleEnabled()) {
            return;
        }
        InventoryHolder destHolder = event.getDestination().getHolder();
        if (destHolder instanceof Jukebox) {
            Jukebox destJukebox = (Jukebox) destHolder;
            ItemStack destItem = event.getItem();
            MusicBoxSong destSong = MusicBoxSongManager.findByItem(destItem).orElse(null);
            if (destSong != null) {
                AbstractBlockPlayer found = AbstractBlockPlayer.findByLocation(destJukebox.getLocation());
                JukeboxPlayer existingPlayer = found instanceof JukeboxPlayer ? (JukeboxPlayer) found : null;
                if (destSong.shouldUseVanillaJukeboxPlayback()) {
                    if (existingPlayer != null) {
                        existingPlayer.destroy(DestroyReason.RECORD_REMOVED);
                    }
                    return;
                }
                if (existingPlayer != null) {
                    // Full chest rescan on the event thread would eat into the tick budget every
                    // time a hopper delivers a disc; defer it to the jukebox's region instead.
                    com.huidu.musicboxplus.common.utils.scheduler.Scheduler.regionLater(destJukebox.getLocation(),
                            () -> existingPlayer.getMusicBoxModel().getPlayList().updatePlaylist(), 1L);
                    return;
                }
                // Let vanilla move the disc and it starts the disc's own track: insertion runs
                // through the block's ordinary item setter, which broadcasts the play effect
                // before any listener could stop it. Move it here instead, through the same
                // path a manual insertion takes, and start playback so a disc delivered by
                // redstone behaves like one placed by hand.
                event.setCancelled(true);
                ItemStack toInsert = destItem.clone();
                toInsert.setAmount(1);
                Inventory source = event.getSource();
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(destJukebox.getLocation(),
                        () -> insertFromContainer(destJukebox, toInsert, source));
                return;
            }
        }

        InventoryHolder sourceHolder = event.getSource().getHolder();
        if (sourceHolder instanceof Jukebox) {
            Jukebox sourceJukebox = (Jukebox) sourceHolder;
            ItemStack sourceItem = event.getItem();
            MusicBoxSong sourceSong = MusicBoxSongManager.findByItem(sourceItem).orElse(null);
            if (sourceSong != null) {
                AbstractBlockPlayer found = AbstractBlockPlayer.findByLocation(sourceJukebox.getLocation());
                JukeboxPlayer existingPlayer = found instanceof JukeboxPlayer ? (JukeboxPlayer) found : null;
                if (sourceSong.shouldUseVanillaJukeboxPlayback()) {
                    if (existingPlayer != null) {
                        existingPlayer.destroy(DestroyReason.RECORD_REMOVED);
                    }
                    return;
                }
                event.setCancelled(true);
                final Jukebox finalJukebox2 = sourceJukebox;
                final ItemStack finalItem2 = sourceItem.clone();
                finalItem2.setAmount(1);
                final Inventory destInventory = event.getDestination();
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(finalJukebox2.getLocation(),
                        () -> handleStopMusic(finalJukebox2, finalItem2, destInventory));
            }
        }
    }

    // Runs a tick later on the jukebox's region, so everything is re-read live: the container
    // may have been emptied and the jukebox filled in the meantime. The disc is taken from the
    // container first and put back if the insertion cannot go ahead, so neither side can end up
    // holding a copy.
    private void insertFromContainer(Jukebox jukebox, ItemStack disc, Inventory source) {
        Block block = jukebox.getBlock();
        if (!(block.getState() instanceof Jukebox live)) {
            return;
        }
        ItemStack current = live.getRecord();
        if (current != null && !current.getType().isAir()) {
            return;
        }
        if (!source.removeItem(disc.clone()).isEmpty()) {
            return;
        }
        MusicBoxSong song = MusicBoxSongManager.findByItem(disc).orElse(null);
        if (song == null) {
            source.addItem(disc);
            return;
        }
        JukeboxFactory.getJukebox(live).setJukebox(disc);
        JukeboxPlayer.createNew(live);
    }

    private void handleStopMusic(Jukebox jukebox, ItemStack expected, Inventory destination) {
        // The InventoryMoveItemEvent was cancelled (the real disc stays in the block) and this
        // work was deferred to the jukebox's region a tick later. In that window another path
        // (a manual right-click eject, a concurrent hopper/dispenser pull) may have already taken
        // the disc. Re-read the LIVE record and only move what is actually still present -- blindly
        // re-adding the event-time clone would duplicate the disc (original ejected + clone re-added).
        Block block = jukebox.getBlock();
        BlockState state = block.getState();
        if (!(state instanceof Jukebox)) {
            return;
        }
        Jukebox live = (Jukebox) state;
        ItemStack current = live.getRecord();
        if (current == null || current.getType().isAir() || !current.isSimilar(expected)) {
            return;
        }
        AbstractBlockPlayer found = AbstractBlockPlayer.findByLocation(jukebox.getLocation());
        if (found instanceof JukeboxPlayer) {
            JukeboxPlayer existingPlayer = (JukeboxPlayer) found;
            // A jukebox that is still playing keeps its disc, which is what vanilla does and what
            // anyone building a hopper under one expects: the disc comes out when the track ends.
            //
            // Loop mode must not be part of this. It defaults to OFF, so requiring it to be
            // anything else made the common case -- an ordinary disc, playing -- release the disc
            // the instant a hopper asked for it.
            //
            // Not playing is the release condition on its own: a finished player has already
            // destroyed itself, and one left paused must not be able to hold the disc hostage.
            if (existingPlayer.isPlaying()) {
                return;
            }
            existingPlayer.destroy(DestroyReason.RECORD_REMOVED);
        }
        ItemStack toMove = current.clone();
        toMove.setAmount(1);
        JukeboxFactory.getJukebox(live).setJukebox(null);
        java.util.Map<Integer, ItemStack> leftover = destination.addItem(toMove);
        for (ItemStack remaining : leftover.values()) {
            jukebox.getWorld().dropItemNaturally(jukebox.getLocation(), remaining);
        }
    }
}
