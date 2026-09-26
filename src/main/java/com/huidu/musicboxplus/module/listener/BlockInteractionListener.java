package com.huidu.musicboxplus.module.listener;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent.DestroyReason;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.config.ConfigManager;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.SignMaterial;
import com.huidu.musicboxplus.common.utils.SignUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.player.AbstractBlockPlayer;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.edit.PlayerMusicDiscHelper;
import com.huidu.musicboxplus.module.gui.GUIActions;
import com.huidu.musicboxplus.module.jukebox.JukeboxPlayer;
import com.huidu.musicboxplus.module.sign.SignPlayer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

// Handles player-facing block interaction with signs and jukeboxes: right-click
// to control playback or insert a disc, and breaking to tear a player down.
public class BlockInteractionListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (MusicBox.getInstance().isStartingUp()) {
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null) {
            return;
        }
        if (e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Material type = b.getType();
        if (type != Material.JUKEBOX && !SignMaterial.isSign(type)) {
            return;
        }
        BlockState state = b.getState();
        if (state instanceof Sign && MusicBox.getInstance().isSignsModuleEnabled()) {
            Sign sign = (Sign) state;
            this.processSignClick(e.getPlayer(), sign, e);
        } else if (state instanceof Jukebox && MusicBox.getInstance().isJukeboxModuleEnabled()) {
            handleJukeboxClick(e, b, (Jukebox) state);
        }
    }

    private void handleJukeboxClick(PlayerInteractEvent e, Block b, Jukebox jukebox) {
        ItemStack item = e.getItem();
        if (e.getPlayer().isSneaking()) {
            if (JukeboxPlayer.onSneakingClick(jukebox, e.getPlayer())) {
                e.setCancelled(true);
            }
        } else if (item != null && MusicBoxSongManager.findByItem(item).isPresent()) {
            jukebox.eject();
            JukeboxPlayer.onJukeboxClick(jukebox, item, e);
        } else if (item != null && PlayerMusicDiscHelper.findMusicId(item).isPresent()) {
            e.setCancelled(true);
            MessageUtils.send(e.getPlayer(), Lang.DISC_MUSIC_UNAVAILABLE);
        } else {
            JukeboxPlayer player = AbstractBlockPlayer.findByLocation(b.getLocation());
            if (player != null) {
                player.destroy(DestroyReason.MANUAL_STOP);
            }
        }
    }

    private void processSignClick(Player player, Sign sign, Cancellable e) {
        AbstractBlockPlayer infoSign = AbstractBlockPlayer.findByInfoSign(sign.getLocation()).orElse(null);
        if (infoSign != null) {
            this.openControl(player, infoSign);
            e.setCancelled(true);
            return;
        }
        String lineTwo = SignUtils.getSignLine(sign, 1);
        if (lineTwo == null) {
            return;
        }
        String strippedLineTwo = StringUtils.stripAllColors(lineTwo).trim();
        if (strippedLineTwo == null || strippedLineTwo.isEmpty()) {
            return;
        }
        boolean isUnconfiguredSign = ConfigManager.getInstance().isValidSignAlias(strippedLineTwo);
        String setupText = ConfigManager.getInstance().getSignSetupText();
        if (setupText == null) {
            setupText = "";
        }
        String strippedSetupText = StringUtils.stripAllColors(setupText).trim();
        if (strippedSetupText == null) {
            strippedSetupText = "";
        }
        boolean isSetupMode = strippedLineTwo.equalsIgnoreCase(strippedSetupText);
        String displayText = ConfigManager.getInstance().getSignDisplayText();
        if (displayText == null) {
            displayText = "";
        }
        String strippedDisplayText = StringUtils.stripAllColors(displayText).trim();
        if (strippedDisplayText == null) {
            strippedDisplayText = "";
        }
        boolean isPlayMode = strippedLineTwo.equalsIgnoreCase(strippedDisplayText);
        if (!(isUnconfiguredSign || isSetupMode || isPlayMode)) {
            return;
        }
        e.setCancelled(true);
        AbstractBlockPlayer found = AbstractBlockPlayer.findByLocation(sign.getLocation());
        if (found instanceof SignPlayer) {
            SignPlayer existingPlayer = (SignPlayer) found;
            if (!existingPlayer.isDestroyed()) {
                this.openControl(player, existingPlayer);
                return;
            }
        }
        tryOpenSignSetup(player, sign);
    }

    private void tryOpenSignSetup(Player player, Sign sign) {
        if (!player.hasPermission(Permissions.SIGN)) {
            MessageUtils.send(player, Lang.NO_PERMISSIONS);
            return;
        }
        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
        if (wrapper != null) {
            GUIActions.openSignSetupInventory(wrapper, sign);
        }
    }

    private void openControl(Player player, AbstractBlockPlayer blockPlayer) {
        if (blockPlayer != null) {
            if (MusicBox.getInstance().getConfigObject().isBlockPlayerControlPermission() && !player.hasPermission(Permissions.CONTROL)) {
                boolean isSignOwner = blockPlayer instanceof SignPlayer signPlayer && signPlayer.isOwnerOrAdmin(player);
                if (!isSignOwner) {
                    MessageUtils.send(player, Lang.NO_PERMISSIONS);
                    return;
                }
            }
            blockPlayer.getControl().open(player);
        } else {
            MessageUtils.send(player, Lang.PLUGIN_NOT_LOADED);
        }
    }

    // Protection veto only, at normal priority so later plugins can still override it.
    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        if (!isMusicBlockBreak(e)) {
            return;
        }
        Block block = e.getBlock();
        if (!(block.getState() instanceof Sign) || !MusicBox.getInstance().isSignsModuleEnabled()) {
            return;
        }
        SignPlayer player = AbstractBlockPlayer.findByLocation(block.getLocation());
        if (player == null) {
            player = AbstractBlockPlayer.findByInfoSign(block.getLocation())
                    .filter(SignPlayer.class::isInstance)
                    .map(SignPlayer.class::cast)
                    .orElse(null);
        }
        if (player != null && player.isPreventDestroy()) {
            e.setCancelled(true);
            MessageUtils.send(e.getPlayer(), Lang.SIGN_PROTECT_ENABLED);
        }
    }

    // Teardown at MONITOR: destroy() is irreversible and nothing undoes it if a later listener
    // cancels the break.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakTeardown(BlockBreakEvent e) {
        if (!isMusicBlockBreak(e)) {
            return;
        }
        Block block = e.getBlock();
        BlockState state = block.getState();
        if (state instanceof Jukebox && MusicBox.getInstance().isJukeboxModuleEnabled()) {
            JukeboxPlayer jukeboxPlayer = AbstractBlockPlayer.findByLocation(block.getLocation());
            if (jukeboxPlayer != null) {
                jukeboxPlayer.deleteStoredVolume();
                jukeboxPlayer.destroy(DestroyReason.BLOCK_GONE);
            }
        } else if (state instanceof Sign && MusicBox.getInstance().isSignsModuleEnabled()) {
            SignPlayer signPlayer = AbstractBlockPlayer.findByLocation(block.getLocation());
            if (signPlayer != null) {
                signPlayer.deleteStoredVolume();
                signPlayer.destroy(DestroyReason.BLOCK_GONE);
            }
        }
    }

    private boolean isMusicBlockBreak(BlockBreakEvent e) {
        if (MusicBox.getInstance().isStartingUp()) {
            return false;
        }
        if (!MusicBox.getInstance().isSignsModuleEnabled() && !MusicBox.getInstance().isJukeboxModuleEnabled()) {
            return false;
        }
        Material type = e.getBlock().getType();
        return type == Material.JUKEBOX || SignMaterial.isSign(type);
    }
}
