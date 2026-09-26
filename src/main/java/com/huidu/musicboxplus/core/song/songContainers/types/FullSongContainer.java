package com.huidu.musicboxplus.core.song.songContainers.types;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

public interface FullSongContainer extends SubSongContainer {
    default ItemStack getItemStack() {
        return getItemStack(Collections.emptyList());
    }

    ItemStack getItemStack(List<String> lore);

    String getName();
}