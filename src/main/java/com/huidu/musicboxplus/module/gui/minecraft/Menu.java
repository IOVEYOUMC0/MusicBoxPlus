package com.huidu.musicboxplus.module.gui.minecraft;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * A plugin-owned inventory that handles its own clicks.
 *
 * Before this, every menu class implemented InventoryHolder itself and the click
 * listeners had to know each concrete type: EditGUIListener and MusicEditListener
 * each carried an instanceof chain, so adding a screen meant editing a listener that had
 * nothing to do with the screen, and forgetting to meant "clicking does nothing" with no error.
 *
 * Implementing this interface is enough for the shared listener to route clicks; a new menu no
 * longer needs an edit anywhere else. The listener has already cancelled the event and verified the
 * slot belongs to this inventory, so implementations only decide what the slot means.
 */
public interface Menu extends InventoryHolder {

    /**
     * Handles a click on one of this menu's own slots. The click carries a top-inventory slot index,
     * already range-checked against getInventory(), plus the click modifiers.
     */
    void handleClick(MenuClick click);
}
