package ru.spliterash.musicbox.minecraft.jukebox;

import org.bukkit.inventory.ItemStack;

public interface IJukebox {
    boolean isEmpty();

    void setJukebox(ItemStack item);

    ItemStack getJukebox();
}