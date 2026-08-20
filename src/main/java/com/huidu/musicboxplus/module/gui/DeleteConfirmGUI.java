package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.common.config.GUIConfigManager;
import com.huidu.musicboxplus.common.utils.ItemUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeleteConfirmGUI implements InventoryHolder {

    private final Player player;
    private final String itemName;
    private final Runnable onConfirm;
    private final Runnable onCancel;
    private final Inventory inventory;
    private final GUIConfigManager.DeleteConfirmConfig config;
    private boolean handled = false;

    public DeleteConfirmGUI(Player player, String itemName, Runnable onConfirm, Runnable onCancel) {
        this.player = player;
        this.itemName = itemName;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.config = GUIConfigManager.getInstance().getDeleteConfirmConfig();
        String title = config.getTitle().replace("{name}", itemName);
        Component titleComponent = MiniMessageUtils.processComponent(title);
        this.inventory = Bukkit.createInventory(this, 27, titleComponent);
        updateInventory();
    }

    private void updateInventory() {
        inventory.clear();

        int warningSlot = config.getSlotForButton("warning");
        if (warningSlot >= 0) {
            inventory.setItem(warningSlot, createConfiguredItem("warning"));
        }

        int confirmSlot = config.getSlotForButton("confirm");
        if (confirmSlot >= 0) {
            inventory.setItem(confirmSlot, createConfiguredItem("confirm"));
        }

        int cancelSlot = config.getSlotForButton("cancel");
        if (cancelSlot >= 0) {
            inventory.setItem(cancelSlot, createConfiguredItem("cancel"));
        }
    }

    private ItemStack createConfiguredItem(String key) {
        GUIConfigManager.HotbarButtonConfig buttonConfig = config.getButton(key);
        if (buttonConfig == null) {
            return null;
        }
        return ItemUtils.createStack(
                buttonConfig.getMaterial(),
                replacePlaceholder(buttonConfig.getName()),
                replacePlaceholders(buttonConfig.getLore()),
                buttonConfig.getCustomModelData()
        );
    }

    private String replacePlaceholder(String text) {
        return text == null ? "" : text.replace("{name}", itemName);
    }

    private List<String> replacePlaceholders(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> replaced = new ArrayList<>(lines.size());
        for (String line : lines) {
            replaced.add(replacePlaceholder(line));
        }
        return replaced;
    }

    public void handleClick(int slot) {
        if (handled) {
            return;
        }
        int confirmSlot = config.getSlotForButton("confirm");
        int cancelSlot = config.getSlotForButton("cancel");

        if (slot == confirmSlot) {
            handled = true;
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 0.5f, 1.0f);
            player.closeInventory();
            if (onConfirm != null) {
                onConfirm.run();
            }
        } else if (slot == cancelSlot) {
            handled = true;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f);
            player.closeInventory();
            if (onCancel != null) {
                onCancel.run();
            }
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
