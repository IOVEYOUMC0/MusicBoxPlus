package com.huidu.musicboxplus.module.gui.minecraft;

/**
 * A click routed to a Menu: the top-inventory slot plus the modifiers that the menus which
 * distinguish them need. Deliberately free of Bukkit types so it can be constructed and asserted on
 * without a running server.
 *
 * The slot is a top-inventory index, already range-checked against the menu's inventory; the two flags
 * say whether the click was a right click and whether shift was held.
 */
public record MenuClick(int slot, boolean rightClick, boolean shiftClick) {
}
