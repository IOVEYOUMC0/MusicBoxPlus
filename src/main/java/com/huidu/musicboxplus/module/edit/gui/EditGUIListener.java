package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.module.gui.DeleteConfirmGUI;
import com.huidu.musicboxplus.module.gui.song.SongPlayerControlGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EditGUIListener implements Listener {

    private static final Map<UUID, SongPlayerControlGUI> controlGUIs = new ConcurrentHashMap<>();
    private static EditGUIListener listener;

    public static void register() {
        if (listener != null) {
            return;
        }
        listener = new EditGUIListener();
        MusicBox.getInstance().getServer().getPluginManager().registerEvents(listener, MusicBox.getInstance());
    }

    public static void unregister() {
        if (listener != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            listener = null;
        }
        for (SongPlayerControlGUI controlGUI : controlGUIs.values()) {
            controlGUI.stopUpdateTask();
        }
        controlGUIs.clear();
    }

    public static void setControlGUI(Player player, SongPlayerControlGUI gui) {
        controlGUIs.put(player.getUniqueId(), gui);
    }

    public static void removeControlGUI(Player player) {
        controlGUIs.remove(player.getUniqueId());
    }

    public static SongPlayerControlGUI getControlGUI(Player player) {
        return controlGUIs.get(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        SongPlayerControlGUI controlGUI = controlGUIs.remove(playerId);
        if (controlGUI != null) {
            controlGUI.stopUpdateTask();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        InventoryHolder holder = event.getInventory().getHolder();
        
        if (holder == null) {
            return;
        }

        int slot = event.getRawSlot();
        
        if (holder instanceof EditMenuGUI) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                ((EditMenuGUI) holder).handleClick(slot);
            }
        } else if (holder instanceof SettingsMenuGUI) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                ((SettingsMenuGUI) holder).handleClick(slot);
            }
        } else if (holder instanceof BPMSettingsGUI) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                ((BPMSettingsGUI) holder).handleClick(slot);
            }
        } else if (holder instanceof DeleteConfirmGUI) {
            event.setCancelled(true);
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                ((DeleteConfirmGUI) holder).handleClick(slot);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof PastePreviewGUI) {
            ((PastePreviewGUI) holder).handleClose();
            return;
        }

        SongPlayerControlGUI controlGUI = controlGUIs.get(player.getUniqueId());
        if (controlGUI != null) {
            controlGUI.stopUpdateTask();
            controlGUIs.remove(player.getUniqueId());
        }
    }
}
