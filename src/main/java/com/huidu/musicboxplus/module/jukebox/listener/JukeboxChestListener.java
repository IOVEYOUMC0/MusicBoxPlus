package com.huidu.musicboxplus.module.jukebox.listener;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.utils.FaceUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Jukebox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class JukeboxChestListener implements Listener {
    // Inventory-sorting mods fire one InventoryClickEvent per moved item; without dedupe each
    // would schedule its own full chest rescan on the next tick. Pending keys coalesce every
    // event that lands in the same tick window into a single update per jukebox.
    private static final Set<Location> PENDING = ConcurrentHashMap.newKeySet();

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!MusicBox.getInstance().isJukeboxModuleEnabled()) {
            return;
        }
        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        
        if (holder == null) {
            return;
        }
        
        // A large chest's holder is a DoubleChest, not a Chest, so testing only for Chest
        // would miss that entire case.
        List<Block> chestBlocks = new ArrayList<>(2);
        if (holder instanceof Chest chest) {
            chestBlocks.add(chest.getBlock());
        } else if (holder instanceof DoubleChest doubleChest) {
            if (doubleChest.getLeftSide() instanceof Chest left) {
                chestBlocks.add(left.getBlock());
            }
            if (doubleChest.getRightSide() instanceof Chest right) {
                chestBlocks.add(right.getBlock());
            }
        }
        if (chestBlocks.isEmpty()) {
            return;
        }

        boolean affectsChestPlaylist = event.getClickedInventory() == inventory || event.isShiftClick();
        if (!affectsChestPlaylist) {
            ItemStack cursor = event.getCursor();
            ItemStack current = event.getCurrentItem();
            affectsChestPlaylist =
                MusicBoxSongManager.findByItem(cursor).isPresent()
                    || MusicBoxSongManager.findByItem(current).isPresent();
        }
        if (!affectsChestPlaylist) {
            return;
        }

        // Both halves of a large chest can touch the same jukebox; dedupe by location so it
        // is not scheduled twice.
        Set<Location> notified = new HashSet<>(2);
        for (Block chestBlock : chestBlocks) {
            Jukebox jukebox = FaceUtils.getRelativeAround(chestBlock, Jukebox.class);
            if (jukebox == null) {
                continue;
            }
            Location jukeboxLocation = jukebox.getLocation();
            if (!notified.add(jukeboxLocation) || !PENDING.add(jukeboxLocation)) {
                continue;
            }
            Scheduler.regionLater(jukeboxLocation, () -> {
                PENDING.remove(jukeboxLocation);
                AbstractBlockPlayer found = AbstractBlockPlayer.findByLocation(jukeboxLocation);
                if (found instanceof JukeboxPlayer) {
                    found.getMusicBoxModel().getPlayList().updatePlaylist();
                }
            }, 1L);
        }
    }
}
