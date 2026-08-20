package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.module.command.MusicBoxExecutor;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class Mute
implements SubCommand {
    private final MusicBoxExecutor parent;

    public Mute(MusicBoxExecutor parent) {
        this.parent = parent;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            this.parent.sendHelp(sender);
            return;
        }
        this.mute(sender, args);
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.MUTE) || sender.hasPermission(Permissions.ADMIN);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return StringUtils.tabCompletePrepare(args, Bukkit.getOnlinePlayers().stream().map(HumanEntity::getName));
        }
        return Collections.emptyList();
    }

    public void mute(CommandSender sender, String[] args) {
        Player p = Bukkit.getPlayer(args[0]);
        if (p == null) {
            MessageUtils.send(sender, Lang.PLAYER_OFFLINE, "{player}", args[0]);
            return;
        }
        PlayerWrapper.getInstanceOptional(p).ifPresent(PlayerWrapper::destroyActivePlayer);
        MessageUtils.send(sender, Lang.MUTED, "{player}", p.getName());
    }
}
