package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.module.command.SubCommand;
import com.huidu.musicboxplus.module.gui.GUIActions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class PlaylistExecutor
implements SubCommand {
    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.PLAYLIST);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            MessageUtils.send(sender, Lang.ONLY_PLAYERS);
            return;
        }
        Player player = (Player)sender;
        if (args.length == 0) {
            GUIActions.openPlaylistListEditor(PlayerWrapper.getInstance(player));
            return;
        }
        this.createPlaylist(player, args);
    }

    private void createPlaylist(Player player, String[] args) {
        if (args.length == 0) {
            MessageUtils.send(player, Lang.INPUT_NAME);
            return;
        }
        String name = StringUtils.t(String.join(" ", args));
        GUIActions.openPlaylistEditor(PlayerWrapper.getInstance(player), new PlayerPlayListModel(-1, player.getUniqueId(), name));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
