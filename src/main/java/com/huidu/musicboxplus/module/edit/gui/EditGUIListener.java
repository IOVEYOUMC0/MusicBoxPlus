package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.module.gui.DeleteConfirmGUI;
import com.huidu.musicboxplus.module.gui.minecraft.Menu;
import com.huidu.musicboxplus.module.gui.minecraft.MenuClick;
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

        // One branch for every menu that implements Menu: adding a screen no longer means editing
        // this listener, and a menu cannot be silently unreachable because someone forgot to.
        //
        // This is now the only click dispatcher. MusicEditListener used to keep a parallel
        // class→handler map, which meant DeleteConfirmGUI was handled twice (this listener and
        // that map both fired), and every new screen had to be registered in two places.
        if (holder instanceof Menu menu) {
            // Cancel first, then decide whether to dispatch: a plugin-owned holder must never leave
            // a click uncancelled, or vanilla mechanics can move its display items into the player's
            // inventory (dupe) when the owning module was disabled mid-session.
            event.setCancelled(true);
            if (!isModuleActive(holder)) {
                return;
            }
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < event.getInventory().getSize()) {
                menu.handleClick(new MenuClick(slot, event.isRightClick(), event.isShiftClick()));
            }
        }
    }

    /**
     * Whether the module that owns this screen is currently enabled. Moved here with the dispatch:
     * it used to live in MusicEditListener, and a click must still do nothing after an admin turns
     * the owning module off while the window is open.
     */
    private static boolean isModuleActive(InventoryHolder holder) {
        MusicBox plugin = MusicBox.getInstance();
        if (holder instanceof DeleteConfirmGUI) {
            return true;
        }
        if (holder instanceof PlayerMusicShopGUI || holder instanceof PurchaseConfirmGUI) {
            return plugin.isPlayerMusicShopModuleEnabled();
        }
        if (holder instanceof PublishGUI || holder instanceof PublishConfirmGUI
                || holder instanceof ManagePublishedGUI || holder instanceof PublishReviewGUI) {
            return plugin.isPublishModuleEnabled();
        }
        return plugin.isEditorModuleEnabled();
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
