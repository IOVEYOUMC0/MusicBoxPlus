package com.huidu.musicboxplus.module.gui.minecraft;

import org.bukkit.event.inventory.InventoryClickEvent;

// Callback for a single slot in a Minecraft inventory. Implementations translate a
// raw click into the behaviour the slot advertises (open, close, give, select...).
public interface InventoryAction {

    void onEvent(InventoryClickEvent event);
}