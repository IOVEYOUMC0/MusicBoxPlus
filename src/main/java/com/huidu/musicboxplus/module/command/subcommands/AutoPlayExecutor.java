package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.StringUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

// Toggles the per-player opt-out for auto-play on join: /musicboxplus autoplay [on|off|switch]
public class AutoPlayExecutor implements SubCommand {

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            MessageUtils.send(sender, Lang.SPECIFY_PLAYER);
            return;
        }
        Player player = (Player) sender;
        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);
        boolean enabled;
        if (args.length == 0 || args[0].equalsIgnoreCase("switch")) {
            // switch toggles: opting out now means enable after the flip.
            enabled = wrapper.isAutoPlayOptedOut();
        } else if (args[0].equalsIgnoreCase("on")) {
            enabled = true;
        } else if (args[0].equalsIgnoreCase("off")) {
            enabled = false;
        } else {
            MessageUtils.send(sender, Lang.UNKNOWN_TYPE);
            return;
        }
        wrapper.setAutoPlayEnabled(enabled);
        MessageUtils.send(player, enabled ? Lang.AUTOPLAY_ENABLED : Lang.AUTOPLAY_DISABLED);
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.AUTOPLAY);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            return StringUtils.tabCompletePrepare(args, Stream.of("on", "off", "switch"));
        }
        return Collections.emptyList();
    }
}
