package com.huidu.musicboxplus.core.api;

import com.huidu.musicboxplus.api.MusicBoxApiService;
import com.huidu.musicboxplus.api.player.PositionPlayer;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Core implementation behind the MusicBoxAPI facade. Registered by the plugin at startup.
public final class MusicBoxApiServiceImpl implements MusicBoxApiService {

    @Override
    public boolean isMusicBoxDisc(@Nullable ItemStack item) {
        return MusicBoxSongManager.findByItem(item).isPresent();
    }

    @Override
    public boolean isPluginDrivenJukeboxDisc(@Nullable ItemStack item) {
        return MusicBoxSongManager.findPlayableJukeboxSongByItem(item).isPresent();
    }

    @Override
    @Nullable
    public PositionPlayer getPlayerAt(@NotNull Location location) {
        AbstractBlockPlayer player = AbstractBlockPlayer.findByLocation(location);
        return player == null || player.isDestroyed() ? null : player;
    }
}
