package com.huidu.musicboxplus.module.gui.minecraft;

/**
 * A click routed to a {@link Menu}: the top-inventory slot plus the modifiers that the menus which
 * distinguish them need. Deliberately free of Bukkit types so it can be constructed and asserted on
 * without a running server.
 *
 * @param slot       top-inventory slot index, already range-checked against the menu's inventory
 * @param rightClick whether the click was a right click
 * @param shiftClick whether shift was held
 */
public record MenuClick(int slot, boolean rightClick, boolean shiftClick) {
}
