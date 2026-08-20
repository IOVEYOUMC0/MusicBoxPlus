package com.huidu.musicboxplus.api;

import com.huidu.musicboxplus.api.player.PositionPlayer;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Contract implemented by the plugin core and registered via MusicBoxAPI#setService at
// startup. Keeps the api package free of compile-time dependencies on the core/module
// implementation classes, so it can be split into its own artifact later.
public interface MusicBoxApiService {

    boolean isMusicBoxDisc(@Nullable ItemStack item);

    boolean isPluginDrivenJukeboxDisc(@Nullable ItemStack item);

    @Nullable
    PositionPlayer getPlayerAt(@NotNull Location location);
}
