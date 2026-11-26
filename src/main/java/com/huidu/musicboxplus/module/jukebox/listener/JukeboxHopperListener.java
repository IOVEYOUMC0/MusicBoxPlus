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

import java.util.concurrent.TimeUnit;

public class JukeboxHopperListener implements Listener {
    // Keep the five-second recovery for abandoned tasks, but carry an identity lease through every
    // callback. A delayed old task can neither mutate inventories nor release its replacement.
    private static final PendingInsertGate<Location> PENDING_INSERTS =
            new PendingInsertGate<>(TimeUnit.SECONDS.toNanos(5), System::nanoTime);

    /**
     * Drops in-flight insert guards. Called when the module is (re)enabled.
     *
     * Also invalidates callbacks queued by the previous session. Those callbacks may still
     * return an already-removed disc, but cannot insert it or release a new session's lease.
     * Abandoned guards additionally retain their five-second expiry recovery.
     */
    public static void resetPendingInserts() {
        PENDING_INSERTS.reset();
    }

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
                PendingInsertGate.Token<Location> token = PENDING_INSERTS.acquire(destinationLocation);
                if (token == null) {
                    return;
                }
                insertFromContainer(destJukebox, toInsert, source, sourceOwner, sourceLocation,
                        destinationLocation, token);
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
                                     Location sourceLocation, Location destinationLocation,
                                     PendingInsertGate.Token<Location> token) {
        if (sourceLocation == null || sourceLocation.getWorld() == null) {
            PENDING_INSERTS.release(token);
            return;
        }
        // InventoryMoveItemEvent may run on the source container's region. Mutate that inventory
        // there, then return to the jukebox region for the block write; never cross regions with
        // Inventory#removeItem or Inventory#addItem.
        try {
            runOnInventoryOwner(sourceOwner, sourceLocation, () -> {
                try {
                    boolean[] removed = {false};
                    if (!PENDING_INSERTS.runIfCurrent(token,
                            () -> removed[0] = source.removeItem(disc.clone()).isEmpty()) || !removed[0]) {
                        PENDING_INSERTS.release(token);
                        return;
                    }
                    if (!MusicBox.getInstance().isEnabled()) {
                        try {
                            // region() otherwise silently skips a remote task during shutdown.
                            returnItemToSourceNow(source, sourceLocation, disc);
                        } finally {
                            PENDING_INSERTS.release(token);
                        }
                        return;
                    }
                    // region() may execute inline during shutdown. If that callback throws after
                    // placing/returning the disc, it owns restitution; do not refund a second copy.
                    java.util.concurrent.atomic.AtomicBoolean destinationStarted =
                            new java.util.concurrent.atomic.AtomicBoolean();
                    try {
                        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.region(destinationLocation, () -> {
                            destinationStarted.set(true);
                            insertRemovedItem(jukebox, disc, source, sourceOwner, sourceLocation,
                                    destinationLocation, token);
                        });
                    } catch (RuntimeException failure) {
                        if (!destinationStarted.get()) {
                            try {
                                // Still on the source owner, so an immediate refund is safe.
                                returnItemToSourceNow(source, sourceLocation, disc);
                            } finally {
                                PENDING_INSERTS.release(token);
                            }
                        }
                        throw failure;
                    }
                } catch (RuntimeException failure) {
                    PENDING_INSERTS.release(token);
                    throw failure;
                }
            }, () -> PENDING_INSERTS.release(token));
        } catch (RuntimeException failure) {
            PENDING_INSERTS.release(token);
        }
    }

    private void insertRemovedItem(Jukebox jukebox, ItemStack disc, Inventory source, InventoryHolder sourceOwner,
                                   Location sourceLocation, Location destinationLocation,
                                   PendingInsertGate.Token<Location> token) {
        boolean[] inserted = {false};
        try {
            PENDING_INSERTS.runIfCurrent(token, () -> {
                Block block = jukebox.getBlock();
                if (!(block.getState() instanceof Jukebox live)) {
                    return;
                }
                ItemStack current = live.getRecord();
                if (current != null && !current.getType().isAir()) {
                    return;
                }
                MusicBoxSong song = MusicBoxSongManager.findByItem(disc).orElse(null);
                if (song == null) {
                    return;
                }
                JukeboxFactory.getJukebox(live).setJukebox(disc);
                inserted[0] = true;
                JukeboxPlayer.createNew(live);
            });
        } finally {
            if (inserted[0]) {
                PENDING_INSERTS.release(token);
            } else {
                // Expiry/reset can invalidate a task AFTER source removal. It must still return
                // the real disc, on the source owner; invalidating the lease is not cancellation.
                returnItemToSource(source, sourceOwner, sourceLocation, disc, destinationLocation, token);
            }
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
                                    ItemStack disc, Location fallbackLocation,
                                    PendingInsertGate.Token<Location> token) {
        java.util.concurrent.atomic.AtomicBoolean returnStarted = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable drop = () -> {
            try {
                com.huidu.musicboxplus.common.utils.scheduler.Scheduler.regionNow(fallbackLocation, () -> {
                    try {
                        fallbackLocation.getWorld().dropItemNaturally(fallbackLocation, disc);
                    } finally {
                        PENDING_INSERTS.release(token);
                    }
                });
            } catch (RuntimeException failure) {
                PENDING_INSERTS.release(token);
                throw failure;
            }
        };
        if (!MusicBox.getInstance().isEnabled()) {
            // Already on the destination region; its inline fallback remains available even when
            // scheduling a refund to a remote block owner would be silently skipped.
            drop.run();
            return;
        }
        try {
            runOnInventoryOwner(sourceOwner, sourceLocation, () -> {
                returnStarted.set(true);
                try {
                    returnItemToSourceNow(source, sourceLocation, disc);
                } finally {
                    PENDING_INSERTS.release(token);
                }
            }, () -> {
                returnStarted.set(true);
                drop.run();
            });
        } catch (RuntimeException failure) {
            // Scheduling can reject without invoking either callback. We are still on the target
            // region then; drop there rather than touching the remote source inventory or losing it.
            if (!returnStarted.get()) {
                drop.run();
            }
            throw failure;
        }
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
