package com.huidu.musicboxplus.module.textdisplay;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.module.gui.textplayer.TextDisplayPlayerEditGUI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;

public class TextDisplayPlayerListener implements Listener {
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!MusicBox.getInstance().isTextPlayerModuleEnabled()) {
            return;
        }
        TextDisplayHandle handle = TextDisplayPlayerManager.getByInteraction(event.getRightClicked()).orElse(null);
        if (handle == null) {
            return;
        }
        event.setCancelled(true);
        Player viewer = event.getPlayer();
        boolean canEdit = viewer.hasPermission(Permissions.ADMIN) || handle.getDisplayOptions().isAllowPublicEdit();
        if (handle instanceof TextDisplayPlayer player) {
            if (canEdit && viewer.isSneaking()) {
                new TextDisplayPlayerEditGUI(player.getName()).open(viewer);
            } else {
                player.getControl().open(viewer);
            }
        } else if (canEdit) {
            // Song-less placeholder: no playback panel — open the edit menu to assign a song.
            new TextDisplayPlayerEditGUI(handle.getName()).open(viewer);
        }
    }
}
