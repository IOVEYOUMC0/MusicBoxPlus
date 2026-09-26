package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

public class SilentExecutor
implements SubCommand {
    @Override
    public void execute(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!canControlOtherPlayers(sender)) {
                MessageUtils.send(sender, Lang.NO_PERMISSIONS);
                return;
            }
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                MessageUtils.send(sender, Lang.PLAYER_OFFLINE, "{player}", args[1]);
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player)sender;
        } else {
            MessageUtils.send(sender, Lang.SPECIFY_PLAYER);
            return;
        }
        PlayerWrapper wrapper = PlayerWrapper.getInstance(target);
        if (args.length == 0) {
            wrapper.setSilent(!wrapper.isSilent());
            return;
        }
        if (args[0].equalsIgnoreCase("on")) {
            wrapper.setSilent(true);
        } else if (args[0].equalsIgnoreCase("off")) {
            wrapper.setSilent(false);
        } else if (args[0].equalsIgnoreCase("switch")) {
            wrapper.setSilent(!wrapper.isSilent());
        } else {
            MessageUtils.send(sender, Lang.UNKNOWN_TYPE);
        }
        if (sender != target) {
            MessageUtils.send(
                sender,
                Lang.SILENT_MODE_RESPONSE,
                "{player}", target.getName(),
                "{state}", wrapper.isSilent() ? Lang.PLAY_SILENT_STATE_SILENT.toString() : Lang.PLAY_SILENT_STATE_AUDIBLE.toString()
            );
        }
    }

    private boolean canControlOtherPlayers(CommandSender sender) {
        return sender.hasPermission(Permissions.ADMIN) || sender.hasPermission(Permissions.SILENT_OTHER);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return StringUtils.tabCompletePrepare(args, Stream.of("off", "on", "switch"));
        }
        if (args.length == 2) {
            return StringUtils.tabCompletePrepare(args, 1, Bukkit.getOnlinePlayers().stream().map(HumanEntity::getName));
        }
        return Collections.emptyList();
    }
}
