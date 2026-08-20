package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.MusicBoxConfig;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.module.command.SubCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SpeedExecutor implements SubCommand {
    private static final DecimalFormat FORMAT = new DecimalFormat("0.##");

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            MessageUtils.send(sender, Lang.ONLY_PLAYERS);
            return;
        }

        Player player = (Player) sender;
        PlayerWrapper wrapper = PlayerWrapper.getInstance(player);

        if (args.length == 0) {
            MessageUtils.send(player, Lang.SPEED_CURRENT, "{speed}", format(wrapper.getPlaybackSpeedMultiplier()));
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("reset") || action.equals("default")) {
            wrapper.resetPlaybackSpeedMultiplier();
            MessageUtils.send(player, Lang.SPEED_RESET, "{speed}", format(wrapper.getPlaybackSpeedMultiplier()));
            return;
        }

        try {
            float speed = Float.parseFloat(action);
            MusicBoxConfig.SpeedConfig speedConfig = MusicBox.getInstance().getConfigObject().getSpeed();
            if (!Float.isFinite(speed) || speed < speedConfig.getMinSpeed() || speed > speedConfig.getMaxSpeed()) {
                MessageUtils.send(player, Lang.SPEED_INVALID, "{min}", format(speedConfig.getMinSpeed()), "{max}", format(speedConfig.getMaxSpeed()));
                return;
            }
            wrapper.setPlaybackSpeedMultiplier(speed);
            MessageUtils.send(player, Lang.SPEED_SET, "{speed}", format(wrapper.getPlaybackSpeedMultiplier()));
        } catch (NumberFormatException e) {
            MusicBoxConfig.SpeedConfig speedConfig = MusicBox.getInstance().getConfigObject().getSpeed();
            MessageUtils.send(player, Lang.SPEED_INVALID, "{min}", format(speedConfig.getMinSpeed()), "{max}", format(speedConfig.getMaxSpeed()));
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            MusicBoxConfig.SpeedConfig speedConfig = MusicBox.getInstance().getConfigObject().getSpeed();
            List<String> actions = Stream.of(
                format(speedConfig.getMinSpeed()).replace("x", ""),
                "0.5",
                "0.75",
                format(speedConfig.getDefaultSpeed()).replace("x", ""),
                "1.25",
                "1.5",
                format(speedConfig.getMaxSpeed()).replace("x", ""),
                "reset"
            ).distinct().toList();
            return actions.stream().filter(option -> option.startsWith(input)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    @Override
    public boolean canExecute(CommandSender sender) {
        return sender.hasPermission(Permissions.SPEED);
    }

    private static String format(float speed) {
        return FORMAT.format(speed) + "x";
    }
}
