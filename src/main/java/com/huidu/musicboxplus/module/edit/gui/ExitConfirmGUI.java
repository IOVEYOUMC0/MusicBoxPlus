package com.huidu.musicboxplus.module.edit.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class ExitConfirmGUI implements InventoryHolder {

    private final Player player;
    private final Inventory inventory;
    private final GUIConfigManager.ExitConfirmConfig config;
    private final String musicName;
    private final Runnable onSave;
    private final Runnable onDiscard;
    private final Runnable onCancel;
    private boolean handled = false;

    public ExitConfirmGUI(Player player, String musicName, Runnable onSave, Runnable onDiscard, Runnable onCancel) {
        this.player = player;
        this.musicName = musicName;
        this.onSave = onSave;
        this.onDiscard = onDiscard;
        this.onCancel = onCancel;
        this.config = GUIConfigManager.getInstance().getExitConfirmConfig();
        String title = config.getTitle().replace("{name}", musicName);
        this.inventory = Bukkit.createInventory(this, 27, MiniMessageUtils.processComponent(title));
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();

        int infoSlot = config.getSlotForButton("info");
        if (infoSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig infoConfig = config.getButton("info");
            List<String> lore = new ArrayList<>();
            for (String line : infoConfig.getLore()) {
                lore.add(line.replace("{name}", musicName));
            }
            ItemStack infoItem = ItemUtils.createStack(infoConfig.getMaterial(), infoConfig.getName().replace("{name}", musicName), lore, infoConfig.getCustomModelData());
            inventory.setItem(infoSlot, infoItem);
        }

        int saveSlot = config.getSlotForButton("save");
        if (saveSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig saveConfig = config.getButton("save");
            ItemStack saveItem = saveConfig.createItem();
            inventory.setItem(saveSlot, saveItem);
        }

        int discardSlot = config.getSlotForButton("discard");
        if (discardSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig discardConfig = config.getButton("discard");
            ItemStack discardItem = discardConfig.createItem();
            inventory.setItem(discardSlot, discardItem);
        }

        int cancelSlot = config.getSlotForButton("cancel");
        if (cancelSlot >= 0) {
            GUIConfigManager.HotbarButtonConfig cancelConfig = config.getButton("cancel");
            ItemStack cancelItem = cancelConfig.createItem();
            inventory.setItem(cancelSlot, cancelItem);
        }
    }

    public void handleClick(int slot) {
        int saveSlot = config.getSlotForButton("save");
        int discardSlot = config.getSlotForButton("discard");
        int cancelSlot = config.getSlotForButton("cancel");

        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);

        if (slot == saveSlot) {
            handled = true;
            player.closeInventory();
            if (onSave != null) {
                onSave.run();
            }
            return;
        }

        if (slot == discardSlot) {
            handled = true;
            player.closeInventory();
            if (onDiscard != null) {
                onDiscard.run();
            }
            return;
        }

        if (slot == cancelSlot) {
            handled = true;
            player.closeInventory();
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
        if (onCancel != null && player.isOnline()) {
            onCancel.run();
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
