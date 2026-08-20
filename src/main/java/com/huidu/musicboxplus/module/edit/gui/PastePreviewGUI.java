package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class PastePreviewGUI implements InventoryHolder {

    private final Player player;
    private final Inventory inventory;
    private final GUIConfigManager.PastePreviewConfig config;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private final Runnable onClose;
    private boolean handled = false;

    public PastePreviewGUI(Player player, List<?> notesToPaste, Runnable onConfirm, Runnable onCancel) {
        this(player, notesToPaste, onConfirm, onCancel, null);
    }

    public PastePreviewGUI(Player player, List<?> notesToPaste, Runnable onConfirm, Runnable onCancel, Runnable onClose) {
        this.player = player;
        int noteCount = notesToPaste != null ? notesToPaste.size() : 0;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.onClose = onClose;
        this.config = GUIConfigManager.getInstance().getPastePreviewConfig();
        String title = config.getTitle().replace("{count}", String.valueOf(noteCount));
        this.inventory = Bukkit.createInventory(this, 27, MiniMessageUtils.processComponent(title));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();

        int infoSlot = config.getSlotForButton("info");
        if (infoSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig infoConfig = config.getButton("info");
            ItemStack infoItem = infoConfig.createItem();
            inventory.setItem(infoSlot, infoItem);
        }

        int confirmSlot = config.getSlotForButton("confirm");
        if (confirmSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig confirmConfig = config.getButton("confirm");
            ItemStack confirmItem = confirmConfig.createItem();
            inventory.setItem(confirmSlot, confirmItem);
        }

        int cancelSlot = config.getSlotForButton("cancel");
        if (cancelSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig cancelConfig = config.getButton("cancel");
            ItemStack cancelItem = cancelConfig.createItem();
            inventory.setItem(cancelSlot, cancelItem);
        }
    }

    public void handleClick(int slot) {
        int infoSlot = config.getSlotForButton("info");
        int confirmSlot = config.getSlotForButton("confirm");
        int cancelSlot = config.getSlotForButton("cancel");

        if (slot == infoSlot) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            return;
        }

        if (slot == confirmSlot) {
            handled = true;
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            if (onConfirm != null) {
                onConfirm.run();
            }
            return;
        }

        if (slot == cancelSlot) {
            handled = true;
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }

    public void handleClose() {
        if (handled) {
            return;
        }
        handled = true;
        if (onCancel != null) {
            onCancel.run();
        }
        if (onClose != null) {
            onClose.run();
        }
    }

    public void open() {
        com.huidu.musicboxplus.common.utils.scheduler.Scheduler.entityNow(player, () -> player.openInventory(inventory));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
