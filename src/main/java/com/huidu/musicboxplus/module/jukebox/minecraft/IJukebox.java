package com.huidu.musicboxplus.module.jukebox.minecraft;

import org.bukkit.inventory.ItemStack;

// Abstraction over whatever a jukebox block can hold. Implementations wrap either
// the vanilla block state or a custom storage, so the display layer never talks to
// a concrete block type directly.
public interface IJukebox {

    boolean isEmpty();

    void setJukebox(ItemStack item);

    // Plays the disc through vanilla unless the caller opts out (e.g. a plugin is
    // driving the audio and only wants the block state updated).
    default void setJukebox(ItemStack item, boolean suppressVanillaPlayback) {
        setJukebox(item);
    }

    ItemStack getJukebox();

    void eject();
}