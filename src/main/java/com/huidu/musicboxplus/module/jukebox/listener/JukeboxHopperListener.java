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
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JukeboxHopperListener implements Listener {
    private static final Set<Location> PENDING_INSERTS = ConcurrentHashMap.newKeySet();

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
                InventoryHolder sourceOwner = source.getHolder();
                Location sourceLocation = inventoryLocation(source);
                Location destinationLocation = destJukebox.getLocation().clone();
                if (!PENDING_INSERTS.add(destinationLocation)) {
                    return;
                }
                insertFromContainer(destJukebox, toInsert, source, sourceOwner, sourceLocation, destinationLocation);
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
                final InventoryHolder destinationOwner = destInventory.getHolder();
                final Location destinationLocation = inventoryLocation(destInventory);
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(finalJukebox2.getLocation(),
                        () -> handleStopMusic(finalJukebox2, finalItem2, destInventory, destinationOwner, destinationLocation));
            }
        }
    }

    // Runs a tick later on the jukebox's region, so everything is re-read live: the container
    // may have been emptied and the jukebox filled in the meantime. The disc is taken from the
    // container first and put back if the insertion cannot go ahead, so neither side can end up
    // holding a copy.
    private void insertFromContainer(Jukebox jukebox, ItemStack disc, Inventory source, InventoryHolder sourceOwner,
                                     Location sourceLocation,
                                     Location destinationLocation) {
        if (sourceLocation == null || sourceLocation.getWorld() == null) {
            PENDING_INSERTS.remove(destinationLocation);
            return;
        }
        // InventoryMoveItemEvent may run on the source container's region. Mutate that inventory
        // there, then return to the jukebox region for the block write; never cross regions with
        // Inventory#removeItem or Inventory#addItem.
        try {
            runOnInventoryOwner(sourceOwner, sourceLocation, () -> {
                if (!source.removeItem(disc.clone()).isEmpty()) {
                    PENDING_INSERTS.remove(destinationLocation);
                    return;
                }
                try {
                    com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(destinationLocation,
                            () -> insertRemovedItem(jukebox, disc, source, sourceOwner, sourceLocation, destinationLocation));
                } catch (RuntimeException failure) {
                    returnItemToSourceNow(source, sourceLocation, disc);
                    PENDING_INSERTS.remove(destinationLocation);
                }
            }, () -> PENDING_INSERTS.remove(destinationLocation));
        } catch (RuntimeException failure) {
            PENDING_INSERTS.remove(destinationLocation);
        }
    }

    private void insertRemovedItem(Jukebox jukebox, ItemStack disc, Inventory source, InventoryHolder sourceOwner,
                                   Location sourceLocation,
                                   Location destinationLocation) {
        try {
            Block block = jukebox.getBlock();
            if (!(block.getState() instanceof Jukebox live)) {
                returnItemToSource(source, sourceOwner, sourceLocation, disc, destinationLocation);
                return;
            }
            ItemStack current = live.getRecord();
            if (current != null && !current.getType().isAir()) {
                returnItemToSource(source, sourceOwner, sourceLocation, disc, destinationLocation);
                return;
            }
            MusicBoxSong song = MusicBoxSongManager.findByItem(disc).orElse(null);
            if (song == null) {
                returnItemToSource(source, sourceOwner, sourceLocation, disc, destinationLocation);
                return;
            }
            JukeboxFactory.getJukebox(live).setJukebox(disc);
            JukeboxPlayer.createNew(live);
        } finally {
            PENDING_INSERTS.remove(destinationLocation);
        }
    }

    private void handleStopMusic(Jukebox jukebox, ItemStack expected, Inventory destination,
                                 InventoryHolder destinationOwner, Location destinationLocation) {
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
        if (destinationLocation == null || destinationLocation.getWorld() == null) {
            jukebox.getWorld().dropItemNaturally(jukebox.getLocation(), toMove);
            return;
        }
        // The destination may belong to another region than the jukebox. Complete the inventory
        // mutation on that region and drop only any overflow there.
        runOnInventoryOwner(destinationOwner, destinationLocation, () -> {
            java.util.Map<Integer, ItemStack> leftover = destination.addItem(toMove);
            for (ItemStack remaining : leftover.values()) {
                destinationLocation.getWorld().dropItemNaturally(destinationLocation, remaining);
            }
        }, () -> com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(jukebox.getLocation(),
                () -> jukebox.getWorld().dropItemNaturally(jukebox.getLocation(), toMove)));
    }

    private void returnItemToSource(Inventory source, InventoryHolder sourceOwner, Location sourceLocation,
                                    ItemStack disc, Location fallbackLocation) {
        runOnInventoryOwner(sourceOwner, sourceLocation, () -> returnItemToSourceNow(source, sourceLocation, disc),
                () -> com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(fallbackLocation,
                        () -> fallbackLocation.getWorld().dropItemNaturally(fallbackLocation, disc)));
    }

    private static void returnItemToSourceNow(Inventory source, Location sourceLocation, ItemStack disc) {
        java.util.Map<Integer, ItemStack> leftover = source.addItem(disc.clone());
        for (ItemStack remaining : leftover.values()) {
            if (sourceLocation != null && sourceLocation.getWorld() != null) {
                sourceLocation.getWorld().dropItemNaturally(sourceLocation, remaining);
            }
        }
    }

    private static void runOnInventoryOwner(InventoryHolder owner, Location location, Runnable task, Runnable retired) {
        if (owner instanceof Entity entity) {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entity(entity, task, retired);
        } else if (location != null && location.getWorld() != null) {
            com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(location, task);
        } else {
            retired.run();
        }
    }

    private static Location inventoryLocation(Inventory inventory) {
        Location location = inventory.getLocation();
        if (location != null) {
            return location.clone();
        }
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof BlockState blockState) {
            return blockState.getLocation().clone();
        }
        if (holder instanceof Entity entity) {
            return entity.getLocation().clone();
        }
        return null;
    }
}
