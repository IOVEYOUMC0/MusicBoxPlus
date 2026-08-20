package com.huidu.musicboxplus.core.playback;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.core.player.VolumeManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

// Ties per-player playback state to the player lifecycle: start autoplay on join,
// tear down the wrapper on quit and death. Never unregistered -- this listener is
// always wanted regardless of the module configuration.
public class PlayerLifecycleListener implements Listener {

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        AutoPlayService.onJoin(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onExit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        PlayerWrapper.getInstanceOptional(player).ifPresent(PlayerWrapper::destroy);
        VolumeManager.cleanup(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDie(PlayerDeathEvent e) {
        if (!MusicBox.getInstance().isPlaybackModuleEnabled()) {
            return;
        }
        PlayerWrapper.getInstanceOptional(e.getEntity()).ifPresent(PlayerWrapper::destroyActivePlayer);
    }
}
