package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.module.edit.gui.PlayerMusicShopGUI;
import com.huidu.musicboxplus.module.edit.gui.PublishGUI;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ShopMusicExecutor extends AbstractSelect {

    public ShopMusicExecutor() {
        super(Permissions.SHOPMUSIC);
    }

    @Override
    protected void noArgs(CommandSender sender, Player player) {
        if (!MusicBox.getInstance().isPlayerMusicShopModuleEnabled()) {
            if (MusicBox.getInstance().isPublishModuleEnabled()) {
                PublishGUI publishGUI = new PublishGUI(player);
                publishGUI.open();
            } else {
                MessageUtils.send(sender, Lang.NO_PERMISSIONS);
            }
            return;
        }
        PlayerMusicShopGUI shopGUI = new PlayerMusicShopGUI(player);
        shopGUI.open();
    }

    @Override
    protected void processSong(CommandSender sender, Player player, MusicBoxSong song, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("publish")) {
            if (!MusicBox.getInstance().isPublishModuleEnabled()) {
                MessageUtils.send(sender, Lang.PUBLISH_SYSTEM_DISABLED);
                return;
            }
            PublishGUI publishGUI = new PublishGUI(player);
            publishGUI.open();
        } else {
            if (!MusicBox.getInstance().isPlayerMusicShopModuleEnabled()) {
                MessageUtils.send(sender, Lang.NO_PERMISSIONS);
                return;
            }
            PlayerMusicShopGUI shopGUI = new PlayerMusicShopGUI(player);
            shopGUI.open();
        }
    }
}
