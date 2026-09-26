package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.edit.gui.PlayerMusicShopGUI;
import com.huidu.musicboxplus.module.edit.gui.PublishGUI;
import com.huidu.musicboxplus.module.gui.GUIActions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ShopExecutor
extends AbstractSelect {
    public ShopExecutor() {
        super(Permissions.SHOP);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player) && args.length > 0 && isPlayerShopKeyword(args[0])) {
            MessageUtils.send(sender, Lang.ONLY_PLAYERS);
            return;
        }

        if (args.length > 0 && isPlayerShopKeyword(args[0])) {
            boolean publishMode = "publish".equalsIgnoreCase(args[0]);
            if (publishMode && !MusicBox.getInstance().isPublishModuleEnabled()) {
                MessageUtils.send(sender, Lang.PUBLISH_SYSTEM_DISABLED);
                return;
            }
            if (!publishMode && !MusicBox.getInstance().isPlayerMusicShopModuleEnabled()) {
                MessageUtils.send(sender, Lang.NO_PERMISSIONS);
                return;
            }
            if (!sender.hasPermission(Permissions.SHOPMUSIC)) {
                MessageUtils.send(sender, Lang.NO_PERMISSIONS);
                return;
            }

            Player player = (Player) sender;
            String mode = args[0].toLowerCase(Locale.ROOT);
            if ("publish".equals(mode)) {
                new PublishGUI(player).open();
            } else {
                new PlayerMusicShopGUI(player).open();
            }
            return;
        }

        super.execute(sender, args);
    }

    @Override
    protected void noArgs(CommandSender sender, Player player) {
        GUIActions.openShopInventory(PlayerWrapper.getInstance(player));
    }

    @Override
    protected void processSong(CommandSender sender, Player player, MusicBoxSong song, String[] args) {
        GUIActions.playerBuyMusic(PlayerWrapper.getInstance(player), song);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            Stream<String> options = Stream.empty();
            if (sender instanceof Player && sender.hasPermission(Permissions.SHOPMUSIC)) {
                Stream<String> playerMusicOptions = MusicBox.getInstance().isPlayerMusicShopModuleEnabled()
                        ? Stream.of("player")
                        : Stream.empty();
                Stream<String> publishOptions = MusicBox.getInstance().isPublishModuleEnabled()
                        ? Stream.of("publish")
                        : Stream.empty();
                options = Stream.concat(playerMusicOptions, publishOptions);
            }
            if (sender.hasPermission(Permissions.ADMIN)) {
                options = Stream.concat(options, Bukkit.getOnlinePlayers().stream().map(HumanEntity::getName));
            }
            return StringUtils.tabCompletePrepare(args, options.distinct());
        }

        if (args.length == 2 && sender.hasPermission(Permissions.ADMIN) && !isPlayerShopKeyword(args[0])) {
            Stream<String> songs = MusicBoxSongManager.getRootContainer()
                    .getAllSongs().stream()
                    .map(MusicBoxSong::getName)
                    .map(name -> name.replace(' ', '_'));
            return StringUtils.tabCompletePrepare(args, 2, songs);
        }

        return new ArrayList<>();
    }

    private boolean isPlayerShopKeyword(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return "player".equals(normalized) || "music".equals(normalized) || "publish".equals(normalized);
    }
}
