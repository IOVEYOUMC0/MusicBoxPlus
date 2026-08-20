package com.huidu.musicboxplus.module.listener;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.SourcedBlockRedstoneEvent;
import com.huidu.musicboxplus.common.utils.RedstoneUtils;
import com.huidu.musicboxplus.common.utils.SignMaterial;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

// Funnels raw redstone changes into the sign and jukebox playback engines. Two
// listeners because Bukkit fires BlockRedstoneEvent for every block whose power
// changed; SourcedBlockRedstoneEvent additionally carries the block that triggered
// the change so a powered-adjacent source can be told apart from the block itself.
public class RedstoneListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        if (MusicBox.getInstance().isStartingUp()) {
            return;
        }
        if (!MusicBox.getInstance().isSignsModuleEnabled() && !MusicBox.getInstance().isJukeboxModuleEnabled()) {
            return;
        }
        if ((e.getOldCurrent() >= 1) == (e.getNewCurrent() >= 1)) {
            return;
        }
        if (!RedstoneUtils.isFastPreCheckPass(e.getBlock())) {
            return;
        }
        RedstoneUtils.handleRedstoneForBlock(e.getBlock(), e.getOldCurrent(), e.getNewCurrent());
    }

    @EventHandler(ignoreCancelled = true)
    public void onRedstoneCB(SourcedBlockRedstoneEvent e) {
        Block block = e.getBlock();
        // 先用便宜的 getType() 预筛，只有告示牌/唱片机才分配 BlockState 快照
        Material type = block.getType();
        if (type == Material.JUKEBOX && MusicBox.getInstance().isJukeboxModuleEnabled()) {
            Jukebox box = (Jukebox) block.getState();
            JukeboxPlayer.onRedstone(box, e.getSource(), e.getNewCurrent());
        } else if (SignMaterial.isSign(type) && MusicBox.getInstance().isSignsModuleEnabled()) {
            BlockState state = block.getState();
            if (state instanceof Sign sign && SignPlayer.isPlayerSign(sign)) {
                int pin = RedstoneUtils.getPin(sign.getBlock(), e.getSource());
                SignPlayer.redstoneSign(sign, pin, e.getNewCurrent());
            }
        }
    }
}
